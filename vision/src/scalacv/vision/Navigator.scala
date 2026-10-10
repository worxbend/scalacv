package scalacv.vision

import org.opencv.core.Core

import scalacv.*

/** A suggested steering action from what is ahead. */
enum Steering:
  case Straight, Left, Right, Stop

/** The navigator's read of the scene: the chosen [[Steering]], how clear the path ahead is (`0` blocked … `1`
  * wide open), and the raw near-ness of each third.
  */
final case class Guidance(
    steering: Steering,
    clearanceAhead: Double,
    leftNearness: Double,
    centreNearness: Double,
    rightNearness: Double
)

/** Reactive obstacle avoidance — turning a depth reading into a steering suggestion.
  *
  * The simplest useful navigation primitive: split the view ahead into left / centre / right, measure how
  * near the closest thing is in each (from a [[StereoDepth]] disparity map, brighter = nearer), and steer
  * toward the clearest when something looms in the centre. It is memoryless and reflexive — a
  * Braitenberg-style avoider, not a planner; a planner layers a map and a goal on top (see the navigation
  * guide).
  */
object Navigator:

  /** Heuristic steering from measured disparity with explicit pixel thresholds. Invalid correspondence pixels
    * are conservatively treated as blocked. Guidance nearness is relative to `blockedPixels`. Uses a maximum
    * 5x5 local mean to retain narrow supported obstacles while reducing isolated noise. This is a
    * demonstration policy, NOT a safety controller; it has no robot footprint or stopping model.
    */
  def steerMeasured(disparity: DisparityMeasurement, dangerPixels: Double, blockedPixels: Double): Guidance =
    require(
      dangerPixels.isFinite && dangerPixels > 0 && blockedPixels.isFinite && blockedPixels >= dangerPixels,
      "pixel thresholds must be finite, positive and ordered"
    )
    disparity.pixelsCopy.use: pixels =>
      pixels.convertTo(pixels, org.opencv.core.CvType.CV_64F)
      disparity.validityCopy.use: validity =>
        Managed.use(org.opencv.core.Mat()): invalid =>
          Core.bitwise_not(validity, invalid)
          org.opencv.imgproc.Imgproc.threshold(
            pixels,
            pixels,
            blockedPixels,
            blockedPixels,
            org.opencv.imgproc.Imgproc.THRESH_TRUNC
          )
          pixels.setTo(org.opencv.core.Scalar(blockedPixels), invalid)
          guidance(pixels, dangerPixels / blockedPixels, 1.0, blockedPixels)

  /** Suggests steering from an 8-bit display map. Thresholds remain brightness fractions, NOT distances. Uses
    * the strongest 5x5 local mean (smaller on tiny bands), rather than a whole-band mean. Display
    * normalization is scene-relative: prefer [[steerMeasured]] for stable pixel-disparity thresholds. This
    * memoryless demonstration heuristic is not a safety controller.
    *
    * @param dangerNearness
    *   how near (`0`…`1`) something in the centre must be before it triggers a turn.
    * @param blockedNearness
    *   the near-ness at which a third counts as impassable; if both sides are blocked, [[Steering.Stop]].
    */
  def steer(disparity: Image, dangerNearness: Double = 0.55, blockedNearness: Double = 0.8): Guidance =
    require(
      dangerNearness >= 0 && dangerNearness <= 1,
      s"dangerNearness must be in [0, 1], got $dangerNearness"
    )
    require(
      blockedNearness >= 0 && blockedNearness <= 1,
      s"blockedNearness must be in [0, 1], got $blockedNearness"
    )
    val mat = disparity.mat
    require(!mat.empty() && mat.depth() == org.opencv.core.CvType.CV_8U, "steer needs a non-empty 8-bit map")
    Mats.grayscale(mat).use(gray => guidance(gray, dangerNearness, blockedNearness, 255.0))

  private def guidance(
      mat: org.opencv.core.Mat,
      dangerNearness: Double,
      blockedNearness: Double,
      scale: Double
  ): Guidance =
    val width = mat.cols
    val height = mat.rows
    // Below three columns the thirds collapse: width 1/2 give a right band of negative or zero width, which
    // is a raw CvException from submat, not a steering answer. A real disparity map is hundreds of px wide.
    require(width >= 3, s"steer needs a disparity map at least 3 px wide, got ${width}px")
    val third = width / 3

    def nearness(x0: Int, x1: Int): Double =
      Managed.use(mat.submat(Rect(x0, 0, x1 - x0, height).toCv)): band =>
        Managed.use(org.opencv.core.Mat()): local =>
          // Fixed local support, not whole-band dilution or a noise-sensitive single-pixel maximum.
          org.opencv.imgproc.Imgproc.boxFilter(
            band,
            local,
            org.opencv.core.CvType.CV_64F,
            org.opencv.core.Size(math.min(5, band.cols), math.min(5, band.rows)),
            org.opencv.core.Point(-1, -1),
            true,
            Core.BORDER_DEFAULT | Core.BORDER_ISOLATED
          )
          val value = Core.minMaxLoc(local).maxVal / scale
          if value >= 1.0 - 1e-12 then 1.0 else math.max(0.0, value)

    val left = nearness(0, third)
    val centre = nearness(third, third * 2)
    val right = nearness(third * 2, width)

    val steering =
      if centre < dangerNearness then Steering.Straight // clear ahead
      else if math.min(left, right) >= blockedNearness then Steering.Stop // boxed in
      else if left < right then Steering.Left // turn toward the clearer side
      else Steering.Right

    Guidance(steering, 1.0 - centre, left, centre, right)
