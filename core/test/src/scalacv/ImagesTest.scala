package scalacv

import scalacv.graphs.*
import scalacv.vision.*

import java.io.RandomAccessFile
import java.nio.file.{Files, Path}

import scala.util.Using

import org.opencv.core.{CvType, Mat, Point as CvPoint, Scalar as CvScalar}
import org.opencv.imgproc.Imgproc

/** The fixtures here are drawn, not loaded. The repository ships no test image and must not acquire one by
  * design, and a drawn Mat is a better fixture anyway: the assertions can name the exact pixel values a
  * lossless round trip has to preserve.
  */
class ImagesTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  private val Width = 48
  private val Height = 32

  /** A 48x32 BGR Mat: dark grey background, a filled red square in the top-left corner. */
  private def fixture(): Mat =
    val m = Mat(Height, Width, CvType.CV_8UC3, CvScalar(20, 30, 40))
    Imgproc.rectangle(m, CvPoint(4, 4), CvPoint(20, 20), CvScalar(0, 0, 255), -1)
    m

  private def tempDir(): Path =
    val d = Files.createTempDirectory("scalacv-images")
    d.toFile.deleteOnExit()
    d

  private def assertSamePixels(a: Mat, b: Mat)(using munit.Location): Unit =
    assertEquals(a.rows, b.rows)
    assertEquals(a.cols, b.cols)
    assertEquals(a.channels, b.channels)
    for (y, x) <- Seq((0, 0), (10, 10), (Height - 1, Width - 1)) do
      assertEquals(b.get(y, x).toSeq, a.get(y, x).toSeq, s"pixel ($y, $x) differs")

  test("encode/decode round-trips a drawn Mat through PNG without losing a pixel"):
    Managed.use(fixture()): src =>
      val bytes = Images.encode(src, ".png").fold(e => fail(s"encode failed: $e"), identity)
      assert(bytes.length > 8, "a PNG of a 48x32 image cannot be 8 bytes")
      // PNG's 8-byte magic. Asserted so a format mix-up cannot hide behind a successful decode.
      assertEquals(bytes.take(4).toSeq, Seq(0x89.toByte, 'P'.toByte, 'N'.toByte, 'G'.toByte))

      Images.decode(bytes, ImreadFlags.Unchanged) match
        case Left(e) => fail(s"decode failed: $e")
        case Right(decoded) => decoded.use(assertSamePixels(src, _))

  test("write/read round-trips through the filesystem"):
    val file = tempDir().resolve("drawn.png")
    Managed.use(fixture()): src =>
      assertEquals(Images.write(file.toString, src), Right(()))
      assert(Files.exists(file))
      Images.read(file.toString, ImreadFlags.Unchanged) match
        case Left(e) => fail(s"read failed: $e")
        case Right(back) => back.use(assertSamePixels(src, _))

  test("read honours the flags — Grayscale collapses three channels to one"):
    val file = tempDir().resolve("grey.png")
    Managed.use(fixture())(src => assertEquals(Images.write(file.toString, src), Right(())))
    Images.read(file.toString, ImreadFlags.Grayscale) match
      case Left(e) => fail(s"read failed: $e")
      case Right(m) => assertEquals(m.use(_.channels), 1)

  test("read of a nonexistent path is a Left, not an empty Mat"):
    val missing = tempDir().resolve("definitely-not-here.png").toString
    Images.read(missing) match
      case Right(m) => m.release(); fail("a missing file must not produce a usable Mat")
      case Left(e) =>
        assert(e.isInstanceOf[CvError.DecodeFailed], s"expected DecodeFailed, got $e")
        assert(e.getMessage.contains(missing), e.getMessage)

  test("read of a directory is a Left"):
    val dir = tempDir().toString
    Images.read(dir) match
      case Right(m) => m.release(); fail("a directory must not produce a usable Mat")
      case Left(e) => assert(e.isInstanceOf[CvError.DecodeFailed], s"expected DecodeFailed, got $e")

  test("read of a file that is not an image is a Left"):
    val junk = tempDir().resolve("not-an-image.png")
    Files.write(junk, "this is text, not a PNG".getBytes("UTF-8"))
    assert(Images.read(junk.toString).isLeft)

  test("read names which of the three unreadable cases happened"):
    // While read went through imread, all three came back as one empty Mat, so the message had to hedge
    // across all of them and every one of its three suggestions was wrong two times out of three. Reading
    // the bytes on the JVM makes them distinguishable; this pins that they stay distinct and keep naming
    // the file.
    val dir = tempDir()
    val missing = dir.resolve("definitely-not-here.png")
    val junk = dir.resolve("not-an-image.png")
    Files.write(junk, "this is text, not a PNG".getBytes("UTF-8"))
    val details = Seq(missing.toString, dir.toString, junk.toString).map: p =>
      Images.read(p) match
        case Right(m) => m.release(); fail(s"'$p' must not produce a usable Mat")
        case Left(e: CvError.DecodeFailed) =>
          assert(e.getMessage.contains(p), e.getMessage)
          e.details
        case Left(other) => fail(s"expected DecodeFailed, got $other")
    assertEquals(details.distinct.size, 3, s"the three causes must not share one message: $details")

  test("read of an empty file says the file is empty"):
    val empty = tempDir().resolve("empty.png")
    Files.createFile(empty)
    Images.read(empty.toString) match
      case Right(m) => m.release(); fail("a zero-byte file must not produce a usable Mat")
      case Left(e: CvError.DecodeFailed) =>
        assert(e.details.contains("empty"), e.getMessage)
        assert(e.getMessage.contains(empty.toString), e.getMessage)
      case Left(other) => fail(s"expected DecodeFailed, got $other")

  test("write/read round-trips through a path containing non-ASCII characters"):
    // The JNI narrows a Java String to modified UTF-8, and OpenCV's imgcodecs passes those bytes to fopen.
    // On Windows the C runtime reads them in the ANSI code page, so any non-ASCII character named a
    // different, nonexistent file: imwrite returned false and imread returned an empty Mat, on a path that
    // was perfectly valid. Doing the I/O on the JVM removes the narrowing. This assertion holds on Linux
    // and macOS either way — POSIX open takes the bytes verbatim — so it is the pin for the Windows job.
    val dir = tempDir()
    // A JVM whose sun.jnu.encoding cannot represent these characters at all would fail below for a reason
    // that has nothing to do with the code under test, so probe for that and skip rather than fail.
    val probe = dir.resolve("проба")
    Files.createFile(probe)
    assume(Files.exists(probe), "this JVM's sun.jnu.encoding cannot represent a non-ASCII filename")

    val file = dir.resolve("фото-Zoë-日本.png")
    Managed.use(fixture()): src =>
      assertEquals(Images.write(file.toString, src), Right(()))
      assert(Files.exists(file), s"nothing was written to $file")
      Images.read(file.toString, ImreadFlags.Unchanged) match
        case Left(e) => fail(s"read failed: ${e.getMessage}")
        case Right(back) => back.use(assertSamePixels(src, _))

  test("a path the filesystem cannot represent is an error, not a file under a name nobody asked for"):
    // Path.of rejects an embedded NUL byte. Handing the same string to the JNI instead re-encoded the NUL
    // as modified UTF-8's 0xC0 0x80, which POSIX accepts, so imwrite reported success after creating a
    // file under a mangled name. InvalidPathException is a plain RuntimeException, so it also has to be
    // caught explicitly: Cv.attempt does not, and an Either-returning function must not throw it.
    // The NUL is spelled as a char rather than as a string escape, so that this source file does not
    // itself have to contain a NUL byte.
    val bad = tempDir().resolve("nulbyte.png").toString.replace("nulbyte", s"nul${0.toChar}byte")
    Managed.use(fixture()): src =>
      Images.write(bad, src) match
        case Right(_) => fail("a path containing a NUL byte must not report a successful write")
        case Left(e) => assert(e.isInstanceOf[CvError.EncodeFailed], s"expected EncodeFailed, got $e")
    Images.read(bad) match
      case Right(m) => m.release(); fail("a path containing a NUL byte must not produce a usable Mat")
      case Left(e) => assert(e.isInstanceOf[CvError.DecodeFailed], s"expected DecodeFailed, got $e")

  test("encode with an extension OpenCV has no encoder for is EncodeFailed, not a throw"):
    Managed.use(fixture()): src =>
      // imencode would throw CvException here; haveImageWriter catches it first. The failure must be the
      // same EncodeFailed a caller matches for the unwritable-path case, not a leaked-through NativeCall.
      Images.encode(src, ".notaformat") match
        case Left(_: CvError.EncodeFailed) => ()
        case other => fail(s"expected EncodeFailed, got $other")

  test("encode adds the leading period imencode requires"):
    Managed.use(fixture()): src =>
      val withDot = Images.encode(src, ".png")
      val without = Images.encode(src, "png")
      assert(withDot.isRight, s"$withDot")
      assertEquals(without.map(_.toSeq), withDot.map(_.toSeq))

  test("write to an unwritable path is a Left"):
    // A parent directory that does not exist: imwrite signals this by returning false rather than
    // throwing, which is the half of write() that Cv.attempt alone would miss.
    val bad = tempDir().resolve("no-such-subdir").resolve("out.png").toString
    Managed.use(fixture()): src =>
      Images.write(bad, src) match
        case Right(_) => fail("writing under a missing directory must not report success")
        case Left(e) => assert(e.getMessage.contains(bad), e.getMessage)

  test("write with an unknown extension is EncodeFailed — the throwing failure mode"):
    val bad = tempDir().resolve("out.notaformat").toString
    Managed.use(fixture()): src =>
      Images.write(bad, src) match
        case Left(_: CvError.EncodeFailed) => ()
        case other => fail(s"expected EncodeFailed, got $other")

  test("encode of an empty Mat is EncodeFailed, not NativeCall"):
    Managed.use(Mat()): empty =>
      Images.encode(empty, ".png") match
        case Left(e: CvError.EncodeFailed) => assert(e.getMessage.contains("empty"), e.getMessage)
        case other => fail(s"expected EncodeFailed, got $other")

  test("encode of a 2-channel Mat is EncodeFailed"):
    Managed.use(Mat.zeros(8, 8, CvType.CV_8UC2)): two =>
      Images.encode(two, ".png") match
        case Left(e: CvError.EncodeFailed) => assert(e.getMessage.contains("2-channel"), e.getMessage)
        case other => fail(s"expected EncodeFailed, got $other")

  test("write of an empty Mat is EncodeFailed naming the path"):
    val file = tempDir().resolve("empty.png").toString
    Managed.use(Mat()): empty =>
      Images.write(file, empty) match
        case Left(e: CvError.EncodeFailed) =>
          assertEquals(e.path, file)
          assert(!Files.exists(Path.of(file)), "a failed encode must not touch the destination")
        case other => fail(s"expected EncodeFailed, got $other")

  test("a file above 2 GB is a Left, not an OutOfMemoryError"):
    val huge = tempDir().resolve("huge.png")
    try
      Using.resource(RandomAccessFile(huge.toFile, "rw"))(_.setLength(3L << 30))
      assume(Files.size(huge) == (3L << 30), "this filesystem did not produce a 3 GB sparse file")
      Images.read(huge.toString) match
        case Left(e: CvError.DecodeFailed) => assert(e.getMessage.contains("2 GB"), e.getMessage)
        case Left(other) => fail(s"expected DecodeFailed, got $other")
        case Right(m) => m.release(); fail("a 3 GB file must not decode to a usable Mat")
    finally Files.deleteIfExists(huge): Unit

  test("decode of garbage is a Left"):
    val garbage = Array.tabulate(64)(i => (i * 7).toByte)
    Images.decode(garbage) match
      case Right(m) => m.release(); fail("garbage must not decode to a usable Mat")
      case Left(e) => assert(e.isInstanceOf[CvError.DecodeFailed], s"expected DecodeFailed, got $e")

  test("decode of an empty array is a Left"):
    assert(Images.decode(Array.empty[Byte]).isLeft)

  test("a decoded Mat is caller-owned and releasable"):
    Managed.use(fixture()): src =>
      val bytes = Images.encode(src).fold(e => fail(s"encode failed: $e"), identity)
      val decoded = Images.decode(bytes).fold(e => fail(s"decode failed: $e"), identity)
      assert(decoded.get.dataAddr() != 0L)
      decoded.release()
      assert(decoded.isReleased)
      // The source must be untouched: nothing here aliases or frees the receiver.
      assert(src.dataAddr() != 0L, "encode must not release the Mat it was given")
