package scalacv

import scalacv.vision.*

import java.nio.file.Files

import org.bytedeco.javacpp.Loader
import org.opencv.core.{CvType, Mat, Point as CvPoint, Scalar as CvScalar, Size as CvSize}
import org.opencv.imgproc.Imgproc

/** The point of this suite is the *loud* failure.
  *
  * `new CascadeClassifier("/does/not/exist.xml")` succeeds. It returns an object that detects nothing, on
  * every frame, forever — a bug that looks exactly like "the camera saw no faces". Every assertion about a
  * `Left` below is guarding against that silence, not against an exception.
  */
class CascadesTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  /** The windows-x86_64 classifier jar ships an empty `share/`, so no cascade can be resolved there. */
  private val cascadesShipped: Boolean = !Loader.getPlatform.startsWith("windows")

  test("resolve extracts a real, readable file"):
    assume(cascadesShipped, "this platform ships no Haar cascades")
    val f = Cascades.resolve(CascadeName.FrontalFaceAlt).fold(e => fail(e.getMessage), identity)
    assert(f.isFile, s"$f should be a file")
    assert(f.canRead, s"$f should be readable")
    assert(f.length > 100_000L, s"$f is suspiciously small: ${f.length} bytes")

  test("resolve is stable across calls — javacpp caches the extraction"):
    assume(cascadesShipped, "this platform ships no Haar cascades")
    val a = Cascades.resolve(CascadeName.Eye).fold(e => fail(e.getMessage), identity)
    val b = Cascades.resolve(CascadeName.Eye).fold(e => fail(e.getMessage), identity)
    assertEquals(a.getAbsolutePath, b.getAbsolutePath)

  test("every CascadeName names a file that is actually in the jar"):
    // This is the whole value of the typed name: if a filename is wrong, it is wrong here and not in
    // production as a detector that silently never fires.
    assume(cascadesShipped, "this platform ships no Haar cascades")
    val missing = CascadeName.values.toList.filter(n => Cascades.resolve(n).isLeft)
    assertEquals(missing, Nil, s"unresolvable cascades: ${missing.map(_.fileName)}")

  test("resolve on Windows explains that the jar ships none"):
    assume(!cascadesShipped, "this platform does ship Haar cascades")
    val e = Cascades.resolve(CascadeName.FrontalFaceAlt).swap.getOrElse(fail("expected a Left on Windows"))
    assert(e.getMessage.contains("no Haar cascades"), e.getMessage)

  test("load yields a non-empty classifier"):
    assume(cascadesShipped, "this platform ships no Haar cascades")
    Cascades.load(CascadeName.FrontalFaceAlt) match
      case Left(e) => fail(e.getMessage)
      case Right(m) =>
        m.use: c =>
          assert(!c.empty(), "a loaded frontal-face cascade must not be empty")
          assert(c.getOriginalWindowSize.width > 0)
        assert(m.isReleased)

  test("loadFrom a path that does not exist is a Left, not a silent empty classifier"):
    val e = Cascades
      .loadFrom("/does/not/exist.xml")
      .swap
      .getOrElse(fail("a missing cascade must not load successfully"))
    assert(e.isInstanceOf[CvError.LoadFailed], s"expected LoadFailed, got ${e.getClass.getName}")
    assert(e.getMessage.contains("/does/not/exist.xml"), e.getMessage)

  test("load on a name the platform jar does not ship is a Left, never a silent empty classifier"):
    // The typed-name equivalent of the missing-path guard above: resolve fails for every name where the
    // classifier jar ships no cascades, and load must surface that as a Left rather than an empty
    // classifier. Only Windows exercises it — everywhere else every CascadeName resolves.
    assume(!cascadesShipped, "this platform ships the cascades, so no name is missing")
    CascadeName.values.foreach: n =>
      Cascades.load(n) match
        case Left(e: CvError.LoadFailed) =>
          assert(e.getMessage.contains(n.fileName), e.getMessage)
        case other => fail(s"${n.fileName}: expected a LoadFailed, got $other")

  test("loadFrom a file that is not a cascade is a Left"):
    val junk = Files.createTempFile("scalacv-not-a-cascade", ".xml")
    try
      Files.writeString(junk, "this is not a cascade\n")
      assert(Cascades.loadFrom(junk.toString).isLeft, "a malformed cascade XML must not load")
    finally Files.deleteIfExists(junk)

  test("detect on a blank image returns no rectangles and does not throw"):
    assume(cascadesShipped, "this platform ships no Haar cascades")
    Cascades.load(CascadeName.FrontalFaceAlt) match
      case Left(e) => fail(e.getMessage)
      case Right(m) =>
        m.use: c =>
          Managed.use(blank(240, 320)): img =>
            assertEquals(img.detect(c), Seq.empty[Rect])
            assertEquals(img.detect(c, scaleFactor = 1.3, minNeighbors = 5), Seq.empty[Rect])
            assertEquals(img.detect(c, minSize = Some(Size(40, 40))), Seq.empty[Rect])

  test("detect rejects a scaleFactor that would never terminate"):
    assume(cascadesShipped, "this platform ships no Haar cascades")
    Cascades.load(CascadeName.FrontalFaceAlt) match
      case Left(e) => fail(e.getMessage)
      case Right(m) =>
        m.use: c =>
          Managed.use(blank(64, 64)): img =>
            intercept[IllegalArgumentException](img.detect(c, scaleFactor = 1.0))
            intercept[IllegalArgumentException](img.detect(c, minNeighbors = -1))

  test("a released classifier throws instead of crashing the JVM"):
    assume(cascadesShipped, "this platform ships no Haar cascades")
    val m = Cascades.load(CascadeName.Eye).fold(e => fail(e.getMessage), identity)
    m.release()
    m.release() // idempotent: a second delete(long) would be undefined behaviour
    intercept[IllegalStateException](m.get)

  /** A grey single-channel image. Fixtures are drawn, never loaded — there is no test image in this repo. */
  private def blank(rows: Int, cols: Int): Mat =
    Mat(rows, cols, CvType.CV_8UC1, CvScalar(128))

  /** Everything else here asserts `Seq.empty` on a blank image, which an implementation of `detect` that
    * always returned nothing would also satisfy. This is the test that makes the detection path falsifiable:
    * a crude synthetic face, drawn programmatically so no image asset is needed.
    */
  private def syntheticFace(): Mat =
    val m = Mat(400, 400, CvType.CV_8UC1, CvScalar(60))
    def ellipse(cx: Int, cy: Int, rx: Int, ry: Int, v: Double): Unit =
      Imgproc.ellipse(m, CvPoint(cx, cy), CvSize(rx, ry), 0, 0, 360, CvScalar(v), -1)
    ellipse(200, 200, 90, 120, 200) // face
    ellipse(170, 160, 18, 10, 20) // eyes
    ellipse(230, 160, 18, 10, 20)
    ellipse(200, 205, 10, 25, 120) // nose
    ellipse(200, 260, 40, 12, 30) // mouth
    m

  test("detect finds a synthetic face, and minSize filters it out again"):
    Cascades.load(CascadeName.FrontalFaceAlt) match
      case Left(e) => fail(s"could not load the frontal-face cascade: ${e.getMessage}")
      case Right(managed) =>
        managed.use: classifier =>
          val face = syntheticFace()
          try
            val hits = face.detect(classifier, scaleFactor = 1.05, minNeighbors = 1)
            assertEquals(hits.size, 1, s"expected exactly one detection, got $hits")
            val r = hits.head
            assert(
              r.x >= 0 && r.y >= 0 && r.x + r.width <= 400 && r.y + r.height <= 400,
              s"detection escaped the image bounds: $r"
            )

            // The same call with a minSize larger than the face must drop it. Without this the
            // 6-arg overload is never exercised and a swapped-argument bug there is invisible.
            val filtered =
              face.detect(classifier, scaleFactor = 1.05, minNeighbors = 1, minSize = Some(Size(390, 390)))
            assertEquals(filtered.size, 0, s"minSize 390x390 should have filtered everything, got $filtered")

            val kept =
              face.detect(classifier, scaleFactor = 1.05, minNeighbors = 1, minSize = Some(Size(10, 10)))
            assertEquals(kept.size, 1, s"minSize 10x10 should have kept the face, got $kept")
          finally face.release()
