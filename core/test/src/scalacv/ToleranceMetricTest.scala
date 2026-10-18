package scalacv

import org.opencv.core.Core

/** Tolerance-metric comparisons — PSNR and max-abs-diff — the cross-platform-robust alternative to the
  * bit-exact pixel hashes in [[PixelHashTest]]. Bit-exact equality is correct only same-platform; SIMD paths
  * and OpenCV-build differences break it, so a golden set that must survive a multi-OS/arch matrix has to
  * compare within a threshold. This suite exercises the metrics on a genuinely lossy path (JPEG roundtrip),
  * which by construction is never bit-exact, and pins the comparator behaviour so the infrastructure is ready
  * when the CI matrix grows. The choice between the two comparators, and when a bit-exact hash is still the
  * right gate, is written up in docs/mdoc/testing.md.
  */
class ToleranceMetricTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  /** Peak signal-to-noise ratio in dB (higher = closer; identical images are +inf). */
  private def psnr(a: Image, b: Image): Double = Core.PSNR(a.mat, b.mat)

  /** Largest absolute per-element difference across all channels (0 = pixel-identical). */
  private def maxAbsDiff(a: Image, b: Image): Double = Core.norm(a.mat, b.mat, Core.NORM_INF)

  /** A deterministic, detailed BGR scene — gradients and shapes, so a lossy codec has something to lose. */
  private def scene(): Image =
    var img = Image.blank(160, 120, Scalar(20, 40, 60))
    val rnd = new scala.util.Random(11)
    for _ <- 0 until 12 do
      val c = Scalar(rnd.nextInt(256).toDouble, rnd.nextInt(256).toDouble, rnd.nextInt(256).toDouble)
      img =
        img.drawCircle(Point(rnd.nextInt(160), rnd.nextInt(120)), 4 + rnd.nextInt(20), c, Thickness.Filled)
    img

  test("identical images: PSNR is very high and max-abs-diff is zero"):
    val a = scene()
    val b = a.copy
    try
      assertEquals(maxAbsDiff(a, b), 0.0)
      // OpenCV clamps MSE to a tiny epsilon rather than 0, so identical images report a large finite
      // PSNR (hundreds of dB) instead of +inf — either way, far above any real-image threshold.
      assert(psnr(a, b) > 100.0, s"identical images should have a very high PSNR, got ${psnr(a, b)}")
    finally
      a.close()
      b.close()

  test("JPEG roundtrip is within a PSNR tolerance — lossy, so never bit-exact"):
    val src = scene()
    try
      val bytes = src.copy.bytes(".jpg").fold(throw _, identity)
      val decoded = Image.decode(bytes).fold(throw _, identity)
      try
        val db = psnr(src, decoded)
        // Lossy: not identical (a bit-exact hash would fail here), but well above a quality floor.
        assert(maxAbsDiff(src, decoded) > 0.0, "JPEG is lossy, so the roundtrip must differ from the source")
        assert(db > 30.0, s"JPEG roundtrip PSNR should clear 30 dB, got $db dB")
      finally decoded.close()
    finally src.close()

  test("max-abs-diff tolerance accepts a near-identical transform a bit-exact check would reject"):
    // A gamma of 1.0 is the identity in math but routes through an integer LUT, so rounding can nudge a
    // pixel by 1 — exactly the sub-threshold difference a tolerance metric is meant to absorb.
    val src = scene()
    val nudged = src.copy.gamma(1.0)
    try
      assert(
        maxAbsDiff(src, nudged) <= 1.0,
        s"a LUT identity should differ by ≤1, got ${maxAbsDiff(src, nudged)}"
      )
    finally
      src.close()
      nudged.close()
