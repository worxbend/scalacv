package scalacv

import scalacv.vision.*

/** Unit tests for [[Features]] — ORB detection and cross-image matching. The existing coverage in
  * NavigationTest is only a self-match; this adds detection, matching two *different* views of a scene, the
  * distance filter and ordering, and the empty-input guard.
  */
class FeaturesTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  /** A textured scene (white blocks on dark) offset by (ox, oy) — a controllable camera shift. */
  private def scene(ox: Int, oy: Int): Image =
    Image
      .blank(220, 180, Scalar(30, 30, 30))
      .drawRects(
        Seq(
          Rect(30 + ox, 30 + oy, 26, 26),
          Rect(130 + ox, 40 + oy, 30, 22),
          Rect(70 + ox, 110 + oy, 22, 34),
          Rect(150 + ox, 120 + oy, 26, 26)
        ),
        Scalar.White,
        Thickness.Filled
      )

  test("detect rejects a non-positive maxFeatures"):
    val img = scene(0, 0)
    try intercept[IllegalArgumentException](Features.detect(img, 0))
    finally img.close()

  test("detect finds ORB features in a textured scene"):
    val img = scene(0, 0)
    val d = Features.detect(img)
    try
      assert(d.size > 0, "the textured scene should yield ORB keypoints")
      assert(!d.isEmpty)
      assert(
        d.points.forall(p => p.x >= 0 && p.x < 220 && p.y >= 0 && p.y < 180),
        "keypoints lie in the image"
      )
    finally
      d.close()
      img.close()

  test("matches relates two shifted views, best first and within the distance bound"):
    val a = Features.detect(scene(0, 0))
    val b = Features.detect(scene(5, 3))
    try
      val ms = Features.matches(a, b, maxDistance = 64f)
      assert(ms.nonEmpty, "two views of the same scene should share features")
      assert(
        ms.map(_.distance).sorted == ms.map(_.distance),
        "matches should be sorted best (smallest) first"
      )
      assert(ms.forall(_.distance <= 64f), "no match may exceed the distance bound")
      assert(
        ms.forall(m =>
          m.queryIndex >= 0 && m.queryIndex < a.size && m.trainIndex >= 0 && m.trainIndex < b.size
        ),
        "match indices should point into the two descriptor sets"
      )
    finally
      a.close()
      b.close()

  test("a tighter maxDistance only keeps closer matches"):
    val a = Features.detect(scene(0, 0))
    val b = Features.detect(scene(5, 3))
    try
      val loose = Features.matches(a, b, maxDistance = 64f)
      val tight = Features.matches(a, b, maxDistance = 16f)
      assert(tight.size <= loose.size, "a tighter bound cannot admit more matches")
      assert(tight.forall(_.distance <= 16f))
    finally
      a.close()
      b.close()

  test("a self-match is exact and complete"):
    val a = Features.detect(scene(0, 0))
    try
      val ms = Features.matches(a, a)
      assert(ms.nonEmpty)
      assert(
        ms.forall(_.distance == 0f),
        "with cross-check, each descriptor's mutual best is itself at distance 0"
      )
      assert(ms.forall(m => m.queryIndex == m.trainIndex), "self-match indices should line up")
    finally a.close()

  test("matches is empty when a side has no descriptors"):
    // A flat fill has no corners, so ORB finds nothing — the guard returns an empty match set, no native call.
    val featured = Features.detect(scene(0, 0))
    val flat = Features.detect(Image.blank(220, 180, Scalar(30, 30, 30)))
    try
      assert(flat.isEmpty, "a flat image should yield no ORB features")
      assertEquals(Features.matches(featured, flat), Seq.empty)
      assertEquals(Features.matches(flat, featured), Seq.empty)
    finally
      featured.close()
      flat.close()
