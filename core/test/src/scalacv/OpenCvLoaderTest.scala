package scalacv

import java.io.File
import java.nio.file.{Files, Path}

/** The loader's decision logic, tested as pure string/filename functions — no natives loaded.
  *
  * [[OpenCv.missingSoname]] and [[OpenCv.isNativeLib]] are the platform-specific, fragile parts of native
  * loading: `missingSoname` drives the demand-driven `satisfy` retry on Linux/macOS and the bulk-load
  * fallback on Windows (where it must return `None`), `bulkLoadIsSafe` decides whether that fallback may run
  * at all, and `isNativeLib`/`withinRoot`/`collectLibs` decide which extracted files reach the payload. None
  * of them needs a real dlopen, a Windows runner, or `OpenCv.load()` to exercise — they are the branches a
  * Windows box would otherwise be the only way to reach. The captured messages below are the real shapes each
  * platform's linker produces.
  */
class OpenCvLoaderTest extends munit.FunSuite:

  // --- missingSoname: Linux (glibc ld.so) ---

  test("missingSoname extracts the soname from a plain ld.so message"):
    val msg = "libopencv_java4130.so: cannot open shared object file: No such file or directory"
    assertEquals(OpenCv.missingSoname(msg), Some("libopencv_java4130.so"))

  test("missingSoname extracts the dependency from an UnsatisfiedLinkError wrapper"):
    // The form the JVM actually throws: the shim's own path, then the missing NEEDED entry.
    val msg =
      "/home/ci/.javacpp/cache/opencv-4.13.0.jar/org/bytedeco/opencv/linux-x86_64/libopencv_java4130.so: " +
        "libopencv_core.so.413: cannot open shared object file: No such file or directory"
    assertEquals(OpenCv.missingSoname(msg), Some("libopencv_core.so.413"))

  test("missingSoname keeps a versioned .so suffix"):
    val msg = "libopencv_xphoto.so.413: cannot open shared object file: No such file or directory"
    assertEquals(OpenCv.missingSoname(msg), Some("libopencv_xphoto.so.413"))

  // --- missingSoname: macOS (dyld) ---

  test("missingSoname extracts an @rpath dylib name"):
    val msg = "Library not loaded: @rpath/libopencv_java4130.dylib"
    assertEquals(OpenCv.missingSoname(msg), Some("libopencv_java4130.dylib"))

  test("missingSoname extracts the dylib from a full dyld failure message"):
    val msg =
      "dlopen(/Users/ci/.javacpp/cache/libopencv_java.dylib, 0x0001): " +
        "Library not loaded: @rpath/libopencv_core.413.dylib\n" +
        "  Referenced from: <A1B2> /Users/ci/.javacpp/cache/libopencv_java.dylib\n" +
        "  Reason: tried: '/usr/lib/libopencv_core.413.dylib' (no such file), image not found"
    assertEquals(OpenCv.missingSoname(msg), Some("libopencv_core.413.dylib"))

  // --- missingSoname: no extractable name -> None (drives the Windows bulk-load fallback) ---

  test("missingSoname returns None for the Windows 'dependent libraries' message"):
    assertEquals(OpenCv.missingSoname("Can't find dependent libraries"), None)

  test("missingSoname returns None for a Windows message that names only a .dll"):
    // missingSoname has no .dll branch at all: even when Windows does name the DLL, nothing is
    // extracted, so satisfy falls back to a bulk load (safe on Windows; see satisfy's comment).
    val msg = "Error loading opencv_core4130.dll: The specified module could not be found."
    assertEquals(OpenCv.missingSoname(msg), None)

  test("missingSoname returns None for a dyld message lacking 'Library not loaded:'"):
    assertEquals(OpenCv.missingSoname("dlopen(/Users/ci/libopencv_java.dylib, 1): image not found"), None)

  test("missingSoname returns None for a null message"):
    assertEquals(OpenCv.missingSoname(null), None)

  // --- isNativeLib: extension classification ---
  //
  // isNativeLib gates on BOTH a version-tolerant extension regex and the platform library prefix
  // (`${libPrefix}opencv_`, "lib" on Linux/macOS). The extension cases below hold the valid prefix
  // fixed and vary only the suffix; the prefix cases hold a valid extension fixed and vary the prefix.

  test("isNativeLib accepts .so, .dylib and .dll libraries"):
    assert(OpenCv.isNativeLib("libopencv_core.so"), ".so is a native lib")
    assert(OpenCv.isNativeLib("libopencv_core.dylib"), ".dylib is a native lib")
    assert(OpenCv.isNativeLib("libopencv_core.dll"), ".dll is a native lib")

  test("isNativeLib accepts a multi-group version suffix (widened (\\.\\d+)* regex)"):
    // The regex was widened from a single group to `(\.\d+)*`, so a fully-versioned soname matches.
    assert(OpenCv.isNativeLib("libopencv_core.so.4.13.0"), "libopencv_core.so.4.13.0 must match")
    assert(OpenCv.isNativeLib("libopencv_foo.so.4.13.0"), "any *.so.N.N.N with the prefix matches")

  test("isNativeLib rejects non-library extensions"):
    assert(!OpenCv.isNativeLib("libopencv_core.jar"), ".jar is not a native lib")
    assert(!OpenCv.isNativeLib("libopencv_core.txt"), ".txt is not a native lib")
    assert(!OpenCv.isNativeLib("libopencv_core.h"), "a header is not a native lib")

  test("isNativeLib rejects a name with no extension (a directory)"):
    assert(!OpenCv.isNativeLib("libopencv_core"), "an extensionless name is not a native lib")

  test("isNativeLib rejects a .so followed by a non-numeric suffix"):
    // (\.\d+)* only admits numeric groups, so a trailing `.txt` after `.so` is not a native lib.
    assert(!OpenCv.isNativeLib("libopencv_foo.so.txt"), "libopencv_foo.so.txt is not a native lib")

  test("isNativeLib requires the opencv library prefix, not just a native extension"):
    // A correct extension alone is not enough: the payload also ships cv2.cpython-*.so, so the prefix
    // gate is load-bearing. libfoo.so.4.13.0 has a matching extension but the wrong prefix.
    assert(!OpenCv.isNativeLib("libfoo.so.4.13.0"), "wrong prefix, so not classified as a native lib")
    assert(!OpenCv.isNativeLib("cv2.cpython-311-x86_64-linux-gnu.so"), "the Python ext module is excluded")

  // --- bulkLoadIsSafe: the speculative RTLD_GLOBAL sweep is Windows-only ---
  //
  // `missingSoname` returning None is NOT a Windows-only condition, even though the branch it feeds is
  // commented as such. "already loaded in another classloader", "undefined symbol", "failed to map
  // segment" (noexec cache mount) and "wrong ELF class" all reach it on Linux, and the dyld message
  // pinned above reaches it on macOS. Off Windows the sweep dlopen(RTLD_GLOBAL)s every extracted
  // libopencv_*.so, and the bundled unversioned `libopencv_highgui.so` pulls the *system* OpenCV in on
  // top of ours; the first call across that ABI boundary SIGSEGVs in cv::Mat::release() with no Java
  // stack trace. These cases pin the gate that keeps the sweep off Linux and macOS.

  test("bulkLoadIsSafe is true on Windows, whose DLL names embed the major version"):
    assert(OpenCv.bulkLoadIsSafe("windows-x86_64"), "windows-x86_64 may bulk-load")
    assert(OpenCv.bulkLoadIsSafe("windows-x86"), "windows-x86 may bulk-load")

  test("bulkLoadIsSafe is false on Linux and macOS, where unversioned sonames bind the system OpenCV"):
    assert(!OpenCv.bulkLoadIsSafe("linux-x86_64"), "linux-x86_64 must never bulk-load")
    assert(!OpenCv.bulkLoadIsSafe("linux-arm64"), "linux-arm64 must never bulk-load")
    assert(!OpenCv.bulkLoadIsSafe("macosx-arm64"), "macosx-arm64 must never bulk-load")
    assert(!OpenCv.bulkLoadIsSafe("macosx-x86_64"), "macosx-x86_64 must never bulk-load")

  test("bulkLoadIsSafe fails closed on an unknown or absent platform"):
    // Wrongly declining costs a clear error message; wrongly allowing costs a JVM crash.
    assert(!OpenCv.bulkLoadIsSafe(null), "no platform string means no bulk load")
    assert(!OpenCv.bulkLoadIsSafe(""), "an empty platform string means no bulk load")
    assert(!OpenCv.bulkLoadIsSafe("ios-arm64"), "an unrecognised platform means no bulk load")

  // --- withinRoot / collectLibs: an extracted entry that escapes the extraction directory ---

  /** Builds a throwaway extraction directory and hands it to `body`, deleting it afterwards.
    *
    * Real files and real symlinks, because `withinRoot` is defined in terms of `getCanonicalPath`, which is
    * exactly the symlink resolution being tested — a mocked filesystem would test nothing.
    */
  private def withTempTree(body: Path => Unit): Unit =
    val dir = Files.createTempDirectory("scalacv-loader-test")
    try body(dir)
    finally
      // Deepest first, so a directory is only removed once it is empty. `walk` does not follow symlinks,
      // so the escaping link is deleted as a link and its target outside the tree is left alone.
      Files
        .walk(dir)
        .sorted(java.util.Comparator.reverseOrder[Path]())
        .forEach(p => Files.delete(p))

  test("withinRoot rejects a symlink whose target escapes the extraction directory"):
    withTempTree: dir =>
      val root = Files.createDirectory(dir.resolve("linux-x86_64"))
      val outside = Files.createFile(dir.resolve("libopencv_highgui.so.5.0.0"))
      val real = Files.createFile(root.resolve("libopencv_core.so.413"))
      val escaping =
        try Files.createSymbolicLink(root.resolve("libopencv_highgui.so"), outside)
        catch case _: Exception => null
      assume(escaping != null, "the filesystem allows symlinks")
      assert(OpenCv.withinRoot(root.toFile, real.toFile), "a real file under the root is kept")
      assert(!OpenCv.withinRoot(root.toFile, escaping.toFile), "a symlink out of the root is dropped")

  test("withinRoot keeps a file in a nested subdirectory of the root"):
    withTempTree: dir =>
      val nested = Files.createDirectories(dir.resolve("a/b"))
      val f = Files.createFile(nested.resolve("libopencv_core.so.413"))
      assert(OpenCv.withinRoot(dir.toFile, f.toFile), "depth below the root does not matter")

  test("withinRoot allows a null root, which cannot be judged"):
    assert(OpenCv.withinRoot(null, new File("/anything")), "no root means no containment claim")

  test("collectLibs drops an extracted alias pointing at the system OpenCV"):
    // The shape found in a real ~/.javacpp cache on a host with OpenCV 5.x installed:
    // libopencv_highgui.so -> /usr/lib/libopencv_highgui.so.5.0.0. isNativeLib says yes to both names,
    // so without the containment check the system library lands in the payload map and can be handed
    // to the dynamic linker.
    withTempTree: dir =>
      val root = Files.createDirectory(dir.resolve("linux-x86_64"))
      val outside = Files.createFile(dir.resolve("libopencv_highgui.so.5.0.0"))
      val real = Files.createFile(root.resolve("libopencv_core.so.413"))
      val escaping =
        try Files.createSymbolicLink(root.resolve("libopencv_highgui.so"), outside)
        catch case _: Exception => null
      assume(escaping != null, "the filesystem allows symlinks")
      assertEquals(
        OpenCv.collectLibs(root.toFile, root.toFile).map(_.getName).sorted,
        List(real.toFile.getName)
      )
