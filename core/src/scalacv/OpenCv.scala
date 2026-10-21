package scalacv

import java.io.File

import org.bytedeco.javacpp.Loader

/** Loads the OpenCV native libraries, without ever requiring a GUI toolkit.
  *
  * The obvious approach — `Loader.load(classOf[opencv_java])` — does not work on a headless machine. javacpp
  * eagerly initialises the whole preset graph, and `opencv_highgui` is GTK2-linked on Linux, so on a box
  * without GTK it throws and takes `objdetect`, `calib3d`, `features2d` and `video` down with it. `objdetect`
  * is precisely what this library needs most.
  *
  * `libopencv_java` itself links no GUI toolkit. So we bring javacpp up through a GUI-free preset, extract
  * the platform payload, and then load the JNI shim, resolving its dependencies **on demand** — see
  * [[satisfy]] for why loading them speculatively is not merely wasteful but unsafe. The result needs no
  * `apt-get install libgtk2.0-0t64` on any runner.
  */
object OpenCv:

  private val JniName = "opencv_java"

  @volatile private var loaded = false

  /** Loads the natives. Idempotent and safe to call from several threads. */
  def load(): Unit =
    if !loaded then
      synchronized:
        if !loaded then
          doLoad()
          loaded = true

  /** True once [[OpenCv.load]] has completed successfully. */
  def isLoaded: Boolean = loaded

  private def doLoad(): Unit =
    try
      // 1. javacpp + openblas, through a preset that links no GUI toolkit.
      Loader.load(classOf[org.bytedeco.opencv.global.opencv_core])

      // 2. Extract the platform payload. cacheResources returns directories as well as files.
      val platform = Loader.getPlatform
      val extracted =
        Loader.cacheResources(classOf[org.bytedeco.opencv.opencv_java], s"/org/bytedeco/opencv/$platform/")
      // Each extracted entry anchors its own containment root, so a library is kept only if it really
      // lives inside the directory javacpp extracted it to — see [[withinRoot]].
      val all = extracted.iterator
        .flatMap(e => collectLibs(if e.isDirectory then e else e.getParentFile, e))
        .toVector
      val (jni, modules) = all.partition(_.getName.contains(JniName))
      val payload = modules.map(f => f.getName -> f).toMap

      // 3. Load exactly what the JNI shim asks for, and nothing else — see [[satisfy]].
      jni.headOption match
        case Some(f) => satisfy(f, payload)
        case None =>
          throw CvError.NativesMissing(
            s"no $JniName library in the extracted $platform payload"
          )
    catch
      case e: CvError => throw e
      case e: UnsatisfiedLinkError =>
        throw CvError.NativesMissing(nativesMissingHelp(e.getMessage), e)
      case e: NoClassDefFoundError =>
        throw CvError.NativesMissing(nativesMissingHelp(e.getMessage), e)

  /** Loads `target`, resolving its dependencies **on demand** from the extracted payload.
    *
    * The obvious approach — `dlopen(RTLD_GLOBAL)` every module library and let a retry loop sort out the
    * order — is actively dangerous, and it took a JVM crash to find out why. The bundled
    * `libopencv_highgui.so` carries *unversioned* `NEEDED` entries (`libopencv_core.so`, not
    * `libopencv_core.so.413`). Loading it makes the dynamic linker search the system path, and on a machine
    * that happens to have OpenCV installed it does not fail — it succeeds, mapping six `libopencv_*.so.5.0.0`
    * system libraries into the global namespace, where they interpose on our 4.13.0 symbols. The next call
    * that crosses between the two ABIs dies in `cv::Mat::release()` with no Java stack trace. Reproduced
    * exactly that way.
    *
    * So on Linux and macOS: never speculatively load a library. Try the JNI shim, read the soname out of the
    * `UnsatisfiedLinkError`, load *that* library from the payload, and try again. The shim asks only for what
    * it actually needs, which on Linux excludes highgui entirely and on macOS includes it — correct on both
    * without a platform conditional. And a dependency we cannot satisfy from the payload is a real error
    * rather than something the linker quietly resolves against whatever the host has lying around.
    *
    * Windows is the exception. Its `UnsatisfiedLinkError` is `Can't find dependent libraries` and names no
    * library, so the soname cannot be extracted and demand-driven loading has nothing to act on. There a bulk
    * retry-load is used instead — and it is *safe* there for the reason it is not on Linux: the Windows DLL
    * names embed the version (`opencv_core4130.dll`, not `opencv_core.dll`), so a bulk load cannot silently
    * bind a different major version's DLL from the system, and `opencv_highgui` links only the OS-provided
    * USER32/GDI32, which are always present. The bulk fallback is gated on the platform for exactly that
    * reason — see [[bulkLoadIsSafe]] — because "the message named no library" is *not* a Windows-only
    * condition: a cross-classloader conflict, an undefined symbol, a `noexec` cache mount or an architecture
    * mismatch all produce messages [[missingSoname]] cannot parse, on every platform.
    *
    * Throws the `UnsatisfiedLinkError` or a [[CvError.NativesMissing]] describing the first dependency it
    * cannot satisfy; off Windows an unparseable error is rethrown as-is rather than bulk-loaded around.
    */
  private def satisfy(target: File, payload: Map[String, File]): Unit =
    // Named for what it holds — the dependency files already loaded on this path — rather than `loaded`,
    // which is the object's own load-completion flag a few lines up. Two different things under one name in
    // one file is a trap for the next reader even though the scopes never actually collide.
    val dependenciesLoaded = scala.collection.mutable.Set.empty[String]
    var bulkTried = false

    // `lastMissing` is threaded as a parameter, not shared: each retry chain carries its own last-missing
    // soname, so satisfying a dependency on one path cannot mask a genuine repeat on another.
    def attempt(load: () => Unit, what: String, lastMissing: String): Unit =
      try load()
      catch
        case e: UnsatisfiedLinkError =>
          missingSoname(e.getMessage) match
            case Some(missing) =>
              if missing == lastMissing then
                // Asking for the same library twice means loading it did not help.
                throw CvError.NativesMissing(
                  s"$what needs $missing, which is in the payload but does not satisfy it"
                )
              val dep = payload
                .get(missing)
                .orElse(payload.get(baseName(missing)))
                .getOrElse:
                  throw CvError.NativesMissing(
                    s"""$what needs $missing, which is not in the extracted OpenCV payload.
                     |
                     |This is a dependency of OpenCV itself rather than of scalacv. It usually means
                     |the platform-classifier jar is incomplete or was extracted only partially; try
                     |clearing the javacpp cache (~/.javacpp) and running again.""".stripMargin
                  )
              if !dependenciesLoaded.add(dep.getName) then throw e
              // Load the dependency on its own fresh path, then retry the original load — remembering this
              // round's missing soname, so a repeat of it means loading the dependency did not help.
              attempt(() => Loader.loadGlobal(dep.getAbsolutePath), dep.getName, "")
              attempt(load, what, missing)
            case None =>
              // The error named no library. Only Windows reaches here legitimately, and only there is a
              // bulk load safe; everywhere else an unparseable message means the shim failed for a reason
              // no amount of extra loading can fix (already loaded in another classloader, undefined
              // symbol, noexec mount, wrong ELF class), and sweeping the payload with RTLD_GLOBAL would
              // map a system OpenCV of a different major version over ours — the crash described above.
              // So off Windows the original error is rethrown untouched.
              if !bulkLoadIsSafe(Loader.getPlatform) then throw e
              // Bulk-load the payload once, then retry. If we have already bulk-loaded and still cannot
              // satisfy the shim, it is a real error.
              if bulkTried then throw e
              bulkTried = true
              bulkLoad(payload.values)
              attempt(load, what, lastMissing)

    attempt(() => System.load(target.getAbsolutePath), target.getName, "")

  /** Whether the speculative bulk load is safe on `platform`, which is a javacpp platform string such as
    * `linux-x86_64`, `macosx-arm64` or `windows-x86_64`.
    *
    * Windows only. Its DLL names embed the major version (`opencv_core4130.dll`), so `LoadLibrary` cannot
    * silently bind a system OpenCV of a different major version; the ELF and Mach-O payloads ship
    * *unversioned* names (`libopencv_core.so`) that the dynamic linker will happily resolve against whatever
    * the host has installed, which is the ABI-mixing crash [[satisfy]] describes.
    *
    * Fails closed: an absent or unrecognised platform string means "not safe", because the cost of wrongly
    * declining is a clear error and the cost of wrongly allowing is a SIGSEGV with no Java stack trace.
    */
  private[scalacv] def bulkLoadIsSafe(platform: String | Null): Boolean =
    Option(platform).exists(_.startsWith("windows"))

  /** Loads every library in `libs`, retrying until a whole pass makes no progress.
    *
    * Only used on Windows — [[bulkLoadIsSafe]] is the gate — where the linker error is uninformative.
    * Failures are tolerated: link order is a DAG we do not know, so a library that fails on one pass may
    * succeed on the next once its dependencies are in, and `highgui` failing is not fatal.
    */
  private def bulkLoad(libs: Iterable[File]): Unit =
    var remaining = libs.toList
    var progress = true
    while remaining.nonEmpty && progress do
      val before = remaining.size
      remaining = remaining.filter: f =>
        try
          Loader.loadGlobal(f.getAbsolutePath)
          false
        catch case _: Throwable => true
      progress = remaining.size < before

  /** Pulls the missing library's name out of a linker error, on any of the three platforms.
    *
    * Linux: `libopencv_xphoto.so.413: cannot open shared object file: No such file or directory` macOS:
    * `Library not loaded: @rpath/libopencv_highgui.413.dylib` Windows: `Can't find dependent libraries` — no
    * name, so this returns None and [[satisfy]] falls back to a bulk load (safe there; see its comment).
    *
    * `None` does not mean "Windows". Any message this cannot parse yields it, including several that only
    * ever occur on Linux or macOS, which is why [[satisfy]] gates the bulk fallback on [[bulkLoadIsSafe]]
    * rather than on this returning `None`.
    */
  private[scalacv] def missingSoname(message: String | Null): Option[String] =
    Option(message).flatMap: m =>
      val linux = raw"([\w.+-]+\.so[\w.]*): cannot open shared object file".r
      val mac = raw"Library not loaded: (?:@rpath/)?([\w.+-]+\.dylib)".r
      linux.findFirstMatchIn(m).map(_.group(1)).orElse(mac.findFirstMatchIn(m).map(_.group(1)))

  private def baseName(soname: String): String =
    soname.split("/").last

  /** javacpp hands back a mix of files and directories depending on the resource layout. `root` is the
    * directory the walk started in; libraries that escape it are dropped — see [[withinRoot]].
    */
  private[scalacv] def collectLibs(root: File | Null, f: File): Seq[File] =
    if f.isDirectory then Option(f.listFiles).toSeq.flatten.flatMap(collectLibs(root, _))
    else if isNativeLib(f.getName) && withinRoot(root, f) then Seq(f)
    else Seq.empty

  /** Whether `f` really lives under `root`, with symlinks resolved.
    *
    * The extracted payload is not all regular files. javacpp materialises the unversioned aliases as
    * symlinks, and on a machine with OpenCV already installed one of them can point straight out of the
    * cache: on the machine this was found on, `libopencv_highgui.so` → `/usr/lib/libopencv_highgui.so.5.0.0`
    * — whose own `NEEDED` entries name OpenCV **5.x** core, imgproc and imgcodecs. Handing that path to the
    * dynamic linker is the ABI-mixing crash [[satisfy]] describes, so an entry that escapes the directory
    * javacpp extracted never enters the payload map in the first place. Defence in depth behind
    * [[bulkLoadIsSafe]]: the demand-driven path only ever looks up versioned sonames, which these aliases are
    * not, but nothing in the types says it must stay that way.
    *
    * A `null` root (an extracted entry sitting at the filesystem root) cannot be judged, so it is allowed; a
    * path that cannot be canonicalised at all is dropped, because a library the filesystem will not resolve
    * is not one worth loading.
    */
  private[scalacv] def withinRoot(root: File | Null, f: File): Boolean =
    root match
      case null => true
      case r =>
        try f.getCanonicalPath.startsWith(r.getCanonicalPath + File.separator)
        catch case _: java.io.IOException => false

  /** The library-name prefix comes from javacpp rather than being hardcoded, because Windows has none: module
    * libraries are `libopencv_core.so.413` on Linux, `libopencv_core.413.dylib` on macOS and
    * `opencv_core4130.dll` on Windows. Names are version-suffixed, so the extension test cannot be
    * `endsWith`. And the payload also ships `cv2.cpython-*.so`, a Python extension module that is not
    * loadable as a plain shared library — hence matching on the `opencv_` prefix rather than on the suffix
    * alone.
    */
  private lazy val libPrefix: String =
    Option(Loader.loadProperties().getProperty("platform.library.prefix")).getOrElse("")

  private[scalacv] def isNativeLib(n: String): Boolean =
    n.startsWith(s"${libPrefix}opencv_") && n.matches(raw".*\.(so|dylib|dll)(\.\d+)*")

  /** The natives are not on the classpath — which, for a consumer, is the expected state until they add the
    * dependency for their platform. Tell them exactly what to add, for the platform they are actually on,
    * rather than making them find it.
    *
    * One failure wears this exception's clothes without being this failure: a JVM refuses to map the same
    * native file twice, so a second classloader loading scalacv throws `UnsatisfiedLinkError: … already
    * loaded in another classloader` even though the jars are right there. Advising that user to add
    * dependencies they demonstrably already have sends them down a dead end, so that message gets its own
    * text.
    */
  private def nativesMissingHelp(cause: String | Null): String =
    if Option(cause).exists(_.contains("already loaded in another classloader")) then
      s"""The OpenCV natives are on the classpath but another classloader in this JVM has already
         |loaded them ($cause).
         |
         |A JVM maps a given native library file into exactly one classloader, so the second one to
         |ask gets this error. Loading a second copy is not an option either: two copies of
         |libopencv_java each keep their own globals, and a Mat allocated by one is meaningless to the
         |other. Load scalacv from a classloader both sides share — in a servlet container that means
         |the container's shared/common library directory rather than each web application's
         |WEB-INF/lib; in OSGi, a single bundle that exports scalacv rather than one copy per
         |bundle.""".stripMargin
    else nativesNotOnClasspathHelp(cause)

  /** The genuine "add the dependency" case, split out so the classloader message above reads as prose. */

  private def nativesNotOnClasspathHelp(cause: String | Null): String =
    val plat =
      try Loader.getPlatform
      catch
        case _: Throwable => "<your-platform>"
    // The versions come from Build, which is generated from the build's own Deps block: a hard-coded
    // number here would keep telling users to add whatever release was current when this text was
    // written, which for a "your classpath is wrong" message is the one thing it must not do.
    s"""OpenCV natives are not on the classpath ($cause).
       |
       |scalacv depends on the classifier-less OpenCV Java API only, because a build tool cannot
       |express a per-platform classifier in a published POM. Add the natives for your platform:
       |
       |  "org.bytedeco" % "opencv"   % "${Build.openCvArtifactVersion}" classifier "$plat"
       |  "org.bytedeco" % "openblas" % "${Build.openBlasArtifactVersion}" classifier "$plat"
       |
       |Both lines are needed: libopencv_core links libopenblas. If you would rather not pick a
       |platform, "org.bytedeco" % "opencv-platform" % "${Build.openCvArtifactVersion}" bundles every
       |one, at a cost of about 408 MB.""".stripMargin
