package scalacv

import org.opencv.core.Core
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/** These assert against OpenCV's own constants rather than against literals, so a version bump that renumbers
  * anything fails here instead of silently changing behaviour.
  */
class EnumsTest extends munit.FunSuite:

  test("enum cvValues match OpenCV's constants"):
    assertEquals(ColorConversion.BgrToGray.cvValue, Imgproc.COLOR_BGR2GRAY)
    assertEquals(Interpolation.Cubic.cvValue, Imgproc.INTER_CUBIC)
    assertEquals(LineType.AntiAliased.cvValue, Imgproc.LINE_AA)
    assertEquals(ContourRetrieval.External.cvValue, Imgproc.RETR_EXTERNAL)
    assertEquals(BorderType.Wrap.cvValue, Core.BORDER_WRAP)

  test("BorderType.requireFilterSupport rejects the mode imgproc filters abort on"):
    // Wrap reaches cv::FilterEngine::init, which asserts columnBorderType != BORDER_WRAP. Native code is
    // not consistent about it -- GaussianBlur on CV_8U takes a SIMD path that never reaches the assertion
    // and quietly ignores the mode, while CV_32F aborts -- so the guard has to be depth-independent and
    // has to fire before the native call.
    val rejected = intercept[IllegalArgumentException]:
      BorderType.requireFilterSupport("gaussianBlur", BorderType.Wrap)
    assert(rejected.getMessage.contains("gaussianBlur"), rejected.getMessage)
    assert(rejected.getMessage.contains("BORDER_WRAP"), rejected.getMessage)

  test("BorderType.requireFilterSupport accepts everything copyMakeBorder and the filters agree on"):
    // Falsifiable the other way round: a guard that rejected too much would break the four filter ops for
    // their own default, Reflect101.
    BorderType.values
      .filterNot(_ == BorderType.Wrap)
      .foreach: border =>
        BorderType.requireFilterSupport("boxBlur", border)

  test("Threshold composes a mode with an automatic modifier"):
    // The combination an `enum ThresholdType` could not express.
    assertEquals(Threshold.otsu().cvValue, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU)
    assertEquals(
      Threshold.triangle(Threshold.Mode.ToZero).cvValue,
      Imgproc.THRESH_TOZERO | Imgproc.THRESH_TRIANGLE
    )

  test("a plain Threshold carries no modifier bits"):
    assertEquals(Threshold(Threshold.Mode.Binary).cvValue, Imgproc.THRESH_BINARY)
    assert(!Threshold(Threshold.Mode.Binary).computesThreshold)
    assert(Threshold.otsu().computesThreshold)

  test("ImreadFlags maps every legal (colour, scale) pair onto its named constant"):
    // The (colour, scale) pair is total: each legal combination is exactly one Imgcodecs.IMREAD_* value, not
    // an OR of reduced bits. Any renumber upstream fails here.
    val expected: Map[(ImreadColor, ImreadScale), Int] = Map(
      (ImreadColor.Grayscale, ImreadScale.Full) -> Imgcodecs.IMREAD_GRAYSCALE,
      (ImreadColor.Grayscale, ImreadScale.Half) -> Imgcodecs.IMREAD_REDUCED_GRAYSCALE_2,
      (ImreadColor.Grayscale, ImreadScale.Quarter) -> Imgcodecs.IMREAD_REDUCED_GRAYSCALE_4,
      (ImreadColor.Grayscale, ImreadScale.Eighth) -> Imgcodecs.IMREAD_REDUCED_GRAYSCALE_8,
      (ImreadColor.Color, ImreadScale.Full) -> Imgcodecs.IMREAD_COLOR,
      (ImreadColor.Color, ImreadScale.Half) -> Imgcodecs.IMREAD_REDUCED_COLOR_2,
      (ImreadColor.Color, ImreadScale.Quarter) -> Imgcodecs.IMREAD_REDUCED_COLOR_4,
      (ImreadColor.Color, ImreadScale.Eighth) -> Imgcodecs.IMREAD_REDUCED_COLOR_8,
      (ImreadColor.ColorRgb, ImreadScale.Full) -> Imgcodecs.IMREAD_COLOR_RGB,
      (ImreadColor.Unchanged, ImreadScale.Full) -> Imgcodecs.IMREAD_UNCHANGED,
      (ImreadColor.AnyDepth, ImreadScale.Full) -> Imgcodecs.IMREAD_ANYDEPTH
    )
    // Every legal combination — both orientation settings — is covered, and no illegal one sneaks in.
    for
      color <- ImreadColor.values
      scale <- ImreadScale.values
      ignore <- Seq(false, true)
      base <- expected.get((color, scale))
      // Unchanged (-1) admits no extra bit, so its orientation-on variant is illegal, not covered here.
      if !(color == ImreadColor.Unchanged && ignore)
    do
      val want = if ignore then base | Imgcodecs.IMREAD_IGNORE_ORIENTATION else base
      assertEquals(ImreadFlags(color, scale, ignore).cvValue, want, s"$color/$scale ignore=$ignore")

  test("ImreadFlags rejects combinations OpenCV has no constant for"):
    // Reduced-size decode exists only for Grayscale and Color.
    intercept[IllegalArgumentException](ImreadFlags(ImreadColor.Unchanged, ImreadScale.Half))
    intercept[IllegalArgumentException](ImreadFlags(ImreadColor.AnyDepth, ImreadScale.Half))
    intercept[IllegalArgumentException](ImreadFlags(ImreadColor.ColorRgb, ImreadScale.Quarter))
    // Unchanged (-1) can carry no extra bit, orientation included.
    intercept[IllegalArgumentException](
      ImreadFlags(ImreadColor.Unchanged, ImreadScale.Full, ignoreOrientation = true)
    )

  test("geometry round-trips across the native boundary"):
    val r = Rect(3, 4, 10, 20)
    assertEquals(Rect.from(r.toCv), r)
    assertEquals(r.area, 200L)
    val p = Point(1.5, 2.5)
    assertEquals(Point.from(p.toCv), p)
    assertEquals(Scalar.from(Scalar.Red.toCv), Scalar(0, 0, 255, 0))

  test("geometry rejects impossible values"):
    intercept[IllegalArgumentException](Rect(0, 0, -1, 5))
    intercept[IllegalArgumentException](Size(-1, 5))
