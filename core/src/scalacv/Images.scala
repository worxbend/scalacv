package scalacv

import java.io.IOException
import java.nio.file.{AccessDeniedException, Files, InvalidPathException, NoSuchFileException, Path}

import org.opencv.core.{Mat, MatOfByte}
import org.opencv.imgcodecs.Imgcodecs

/** Reading, writing, encoding and decoding images — the boundary between OpenCV and everything else.
  *
  * This is the one place in the library where OpenCV's error reporting is genuinely inconsistent, and the
  * whole point of the object is to flatten that into a single `Either`. Two distinct failure shapes come out
  * of `org.opencv.imgcodecs.Imgcodecs`:
  *
  *   1. `imdecode` **never throws** for bytes that are not an image. It returns a `Mat` with
  *      `empty() == true` (and logs a `findDecoder` warning to stderr). Anyone who forgets the `empty()`
  *      check gets a `CvException` several call frames later, from an `Imgproc` operation that had nothing to
  *      do with the mistake.
  *   1. `imencode` **throws `CvException`** when the extension names no known encoder — so `haveImageWriter`
  *      is consulted first and that case is returned as [[CvError.EncodeFailed]] before the throw can happen,
  *      rather than being recovered from OpenCV's error text.
  *
  * Every function here returns `Either[CvError, ?]` covering both. The encode failures share the one
  * [[CvError.EncodeFailed]] type, so `case EncodeFailed(...)` catches them all.
  *
  * ==The file I/O is done by the JVM, not by OpenCV==
  * [[read]] and [[write]] do **not** call `imread`/`imwrite`. They open the file with `java.nio.file` and
  * leave OpenCV only the codec work, through [[decode]] and [[encode]]. The reason is the path. The JNI layer
  * narrows a Java `String` with `GetStringUTFChars`, so the native side receives modified UTF-8 bytes, and
  * OpenCV's imgcodecs hands those bytes straight to `fopen`. On Windows the C runtime interprets them in the
  * process's ANSI code page, so *any* non-ASCII character in the path resolves to a different, nonexistent
  * name: `imread` then returns an empty Mat and `imwrite` returns `false` — indistinguishable from "the file
  * is not there" and "the directory is not writable". Upstream has not fixed this (opencv#4292 is still open
  * in 4.13) and exposes no wide-character entry point to call instead. Reading and writing the bytes on the
  * JVM side avoids the narrowing altogether, and as a side effect lets both functions report *which* of the
  * causes they used to lump together actually happened.
  *
  * The price is that the encoded file passes through a JVM byte array: peak heap grows by its size, and a
  * file above 2 GB is out of reach, because `Files.readAllBytes` cannot return an array that long. Routing
  * around that with a memory-mapped buffer was rejected — `imdecode` needs a `MatOfByte` built from a JVM
  * array anyway, so the copy is not avoidable here.
  *
  * The same narrowing hazard applies to every other `String`-path native call in the library —
  * `VideoCapture`, `VideoWriter`, `CascadeClassifier.load`, `Dnn.readNet` — and none of those has an
  * in-memory equivalent to reroute through, so they remain ASCII-path-only on Windows.
  *
  * ==Ownership==
  * A returned [[Managed]]`[Mat]` is **caller-owned**: nothing else holds a reference and nothing else will
  * free it. Prefer `Images.read(p).map(_.use(...))` over holding one. Mats created internally — the
  * `MatOfByte` staging buffers, and the empty Mat a failed read hands back — are released here.
  */
object Images:

  /** Reads an image from the filesystem.
    *
    * The bytes are read by the JVM and only the decoding is left to OpenCV, via [[decode]]. `imread` is not
    * used at all; the object header says why, and what it costs.
    *
    * @param path
    *   a filesystem path, resolved by the JVM. It is not a classpath resource, a URL, or a glob.
    * @param flags
    *   how the decoded pixels are converted — colour, grayscale, unchanged, reduced size. Every
    *   [[ImreadFlags]] value means exactly the same thing to `imdecode` as it does to `imread`.
    * @return
    *   `Left(CvError.DecodeFailed)` if the path cannot be represented on this filesystem, names nothing,
    *   names a directory, names an empty file, names a file above 2 GB (which no JVM array can hold), cannot
    *   be read, or holds bytes no registered decoder recognises. Which one it was is in the error's
    *   `details`: those causes used to be one message, because one empty Mat was all `imread` gave us to tell
    *   them apart. For the last case OpenCV still prints its own `findDecoder` warning to stderr and gives us
    *   no way to silence it.
    */
  def read(path: String, flags: ImreadFlags = ImreadFlags.Color): Either[CvError, Managed[Mat]] =
    def failed(details: String): Either[CvError, Managed[Mat]] = Left(CvError.DecodeFailed(path, details))
    resolve(path) match
      case Left(why) => failed(why)
      case Right(file) =>
        if !Files.exists(file) then failed("there is no file at this path")
        else if Files.isDirectory(file) then failed("this path is a directory, not a file")
        else
          sizeOf(file) match
            case Left(why) => failed(why)
            // `Files.readAllBytes` refuses an array this long with an `OutOfMemoryError` — an `Error`, which
            // must not escape a method whose contract is an `Either`. Its own limit is `Int.MaxValue - 8`.
            case Right(size) if size > Int.MaxValue - 8 =>
              failed(s"the file is $size bytes; an image above 2 GB cannot be read into a JVM array")
            case Right(_) =>
              readAllBytes(file) match
                case Left(why) => failed(why)
                // decode rejects an empty array too, but as "<bytes>" with no filename and no hint that the
                // emptiness is the file's rather than the caller's.
                case Right(bytes) if bytes.isEmpty => failed("the file is empty")
                case Right(bytes) =>
                  decode(bytes, flags).left.map:
                    // decode never saw a filename, so it names its source "<N bytes>". Put the path back —
                    // an error that does not say which file failed is half an error.
                    case CvError.DecodeFailed(_, details) => CvError.DecodeFailed(path, details)
                    case other => other

  /** Writes `mat` to `path`, choosing the encoder from the path's extension.
    *
    * The pixels are encoded in memory by [[encode]] and the resulting bytes are written by the JVM; `imwrite`
    * is not used at all, for the path-narrowing reason in the object header. Two consequences worth knowing:
    * encoding completes before the destination is touched, so a failed encode can no longer leave a
    * half-written file behind, and the encoded image passes through a JVM byte array, so peak heap grows by
    * its size.
    *
    * Every failure surfaces as [[CvError.EncodeFailed]], so a caller matching that one case handles them all:
    * an extension with no registered encoder, which is caught by asking `haveImageWriter` first rather than
    * letting `imencode` throw a `CvException`; an empty or 2-channel image, which `imencode` would reject
    * with a native assertion; a path this filesystem cannot represent; a missing parent directory; and a
    * destination that is not writable. The last two used to share one message, because `imwrite` signalled
    * both with a bare `false`.
    *
    * A depth other than 8- or 16-bit is **not** rejected: OpenCV converts it to 8-bit (saturating) and prints
    * a warning. Convert explicitly first if that matters — see [[encode]].
    *
    * The receiver is not modified and not released.
    */
  def write(path: String, mat: Mat): Either[CvError, Unit] =
    def failed(details: String): Either[CvError, Unit] = Left(CvError.EncodeFailed(path, details))
    if !Imgcodecs.haveImageWriter(path) then failed("no encoder is registered for this extension")
    else
      resolve(path) match
        case Left(why) => failed(why)
        case Right(file) =>
          // haveImageWriter has already established there is a usable extension, so a '.' is present.
          // Splitting on the *last* one is what OpenCV's own findEncoder does (strrchr), so the format
          // chosen here cannot drift from the format the check above approved.
          val ext = path.substring(path.lastIndexOf('.'))
          val encoded = encode(mat, ext).left.map:
            // encode reports the extension as its source; the caller asked about a file.
            case CvError.EncodeFailed(_, details) => CvError.EncodeFailed(path, details)
            case other => other
          encoded.flatMap(bytes => writeAllBytes(file, bytes).left.map(CvError.EncodeFailed(path, _)))

  /** Encodes `mat` into an in-memory image file, without touching the filesystem.
    *
    * `ext` selects the format the same way a filename extension would: `".png"`, `".jpg"`, `".webp"`. A
    * leading period is added if you omit one, because `imencode` silently fails without it and the mistake is
    * easy to make. An extension with no registered encoder yields a `Left` rather than the `CvException`
    * OpenCV throws, and so do the two inputs `imencode` rejects with a native assertion: an empty Mat and a
    * 2-channel (or more-than-4-channel) one. Every one of those is [[CvError.EncodeFailed]].
    *
    * A depth other than 8- or 16-bit is **not** rejected here: OpenCV converts the pixels to 8-bit
    * (saturating) and prints a warning to stderr. If that silent conversion matters — a float disparity map,
    * a `CV_32S` label image — convert explicitly (`normalize`, `convertScaleAbs`) before encoding.
    *
    * The returned array is a plain JVM copy — the staging `MatOfByte` is released before returning, so there
    * is no native memory left for the caller to think about.
    */
  def encode(mat: Mat, ext: String = ".png"): Either[CvError, Array[Byte]] =
    val dotted = if ext.startsWith(".") then ext else s".$ext"
    // haveImageWriter keys off the filename's extension, so hand it a name, not a bare ".png".
    val channels = mat.channels()
    if !Imgcodecs.haveImageWriter(s"x$dotted") then
      Left(CvError.EncodeFailed(dotted, "no encoder is registered for this extension"))
    else if mat.empty() then
      Left(CvError.EncodeFailed(dotted, "the image is empty; there is nothing to encode"))
    else if channels == 2 || channels > 4 then
      Left(
        CvError.EncodeFailed(dotted, s"$channels-channel images cannot be encoded; use 1, 3 or 4 channels")
      )
    else
      Managed.use(MatOfByte()): buffer =>
        Cv.attempt(s"imencode('$dotted')")(Imgcodecs.imencode(dotted, mat, buffer)).flatMap {
          case true => Right(buffer.toArray)
          case false => Left(CvError.EncodeFailed(dotted, "imencode returned false"))
        }

  /** Decodes an image from bytes already in memory — an HTTP response body, a BLOB, a test fixture.
    *
    * Like [[read]], the underlying `imdecode` does not throw on garbage; it returns an empty Mat. An empty
    * input array is rejected before it reaches OpenCV, since there is nothing there to decode and the native
    * call's behaviour on a zero-length buffer is not something upstream documents.
    */
  def decode(bytes: Array[Byte], flags: ImreadFlags = ImreadFlags.Color): Either[CvError, Managed[Mat]] =
    if bytes.isEmpty then Left(CvError.DecodeFailed("<bytes>", "the byte array is empty"))
    else
      Managed.use(MatOfByte(bytes*)): encoded =>
        Cv.attempt("imdecode")(Imgcodecs.imdecode(encoded, flags.cvValue))
          .flatMap(
            own(_, s"<${bytes.length} bytes>", "the bytes are not an image in a format OpenCV can decode")
          )

  /** Turns the Mat a decoder handed back into either an owned [[Managed]] or a `DecodeFailed`.
    *
    * The empty Mat is released on the failure path. It carries no pixel buffer, but it is still a live native
    * handle, and leaking one per failed read in a retry loop is a leak like any other.
    */
  private def own(mat: Mat, source: String, details: String): Either[CvError, Managed[Mat]] =
    if mat.empty() then
      mat.release()
      Left(CvError.DecodeFailed(source, details))
    else Right(Managed(mat))

  /** Turns a `String` path into a `java.nio.file.Path` as data rather than as a throw.
    *
    * `Path.of` raises `InvalidPathException` for an embedded NUL byte on any platform, and on Windows for the
    * characters NTFS forbids in a name. That is a plain `RuntimeException`, which [[Cv.attempt]] deliberately
    * does not catch — it matches `CvException`, `CvError`, and the exact class `java.lang.Exception` — so an
    * unguarded one would escape [[read]] and [[write]] as a throw from functions whose whole contract is to
    * return an `Either`.
    */
  private def resolve(path: String): Either[String, Path] =
    try Right(Path.of(path))
    catch case e: InvalidPathException => Left(s"this is not a usable filesystem path: ${e.getReason}")

  /** A file's size, reporting an `IOException` as text rather than throwing it. */
  private def sizeOf(file: Path): Either[String, Long] =
    try Right(Files.size(file))
    catch case e: IOException => Left(describe(e))

  /** Reads a whole file, reporting an `IOException` as text rather than throwing it. */
  private def readAllBytes(file: Path): Either[String, Array[Byte]] =
    try Right(Files.readAllBytes(file))
    catch case e: IOException => Left(describe(e))

  /** Writes `bytes` over `file`, separating the two causes `imwrite` used to merge into one `false`. */
  private def writeAllBytes(file: Path, bytes: Array[Byte]): Either[String, Unit] =
    try
      val _ = Files.write(file, bytes)
      Right(())
    catch
      // Both of these extend IOException, so they have to be matched ahead of it.
      case _: NoSuchFileException => Left("the parent directory does not exist")
      case _: AccessDeniedException => Left("the destination is not writable")
      case e: IOException => Left(describe(e))

  /** An `IOException`'s message, which for the `java.nio.file` exceptions is often only the path and is
    * occasionally `null` — in which case the class name is still more use to a reader than the word "null".
    */
  private def describe(e: IOException): String = Option(e.getMessage).getOrElse(e.toString)
