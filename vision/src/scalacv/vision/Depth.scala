package scalacv.vision

import org.opencv.calib3d.StereoSGBM
import org.opencv.core.{Core, Mat}

import scalacv.*

/** One detected obstacle: where it is in the frame and how near it is (`0` far … `1` right in front). */
final case class Obstacle(region: Rect, nearness: Double)

/** Depth from a rectified stereo pair — the basis of obstacle detection on a robot or drone.
  *
  * A [[disparity]] map encodes how far each pixel shifts between the left and right cameras, which is inverse
  * to distance: nearer things shift more. [[Obstacles.fromDisparity]] then reads the near-field blobs off it.
  *
  * The pair must already be **rectified** (row-aligned) — that is a one-time camera-calibration step OpenCV
  * also provides (`stereoRectify`), done off the hot path, so it is not wrapped here.
  */
object StereoDepth:

  private given Releasable[StereoSGBM] = Releasable.nativeHandle

  /** A disparity map from a rectified `left`/`right` pair, as an 8-bit single-channel [[Image]] normalised so
    * **brighter = nearer**. `numDisparities` (the depth range searched) must be positive and a multiple of
    * 16; `blockSize` is the odd matching window.
    */
  def disparity(left: Image, right: Image, numDisparities: Int = 64, blockSize: Int = 9): Image =
    require(
      numDisparities > 0 && numDisparities % 16 == 0,
      s"numDisparities must be a positive multiple of 16, got $numDisparities"
    )
    require(blockSize >= 3 && blockSize % 2 == 1, s"blockSize must be odd and ≥ 3, got $blockSize")
    require(
      left.width == right.width && left.height == right.height,
      s"the stereo pair must match in size, got ${left.width}x${left.height} and ${right.width}x${right.height}"
    )
    Mats
      .grayscale(left.mat)
      .use: l =>
        Mats
          .grayscale(right.mat)
          .use: r =>
            // A build without the stereo contrib module returns null from `create` (the same failure
            // mode Tracker.create guards against); wrapping that in Managed would surface later as an
            // opaque "already released" at the first `compute`. It is an environment failure, not a
            // programmer error, so it is a CvError.NativeCall — thrown, not returned, because this
            // method's contract is Image-or-throw, exactly like the `compute` orThrow below it.
            val created = StereoSGBM.create(0, numDisparities, blockSize)
            if created == null then
              throw CvError.NativeCall(
                "StereoSGBM.create",
                IllegalStateException(
                  "OpenCV returned no stereo matcher — this build may not include the stereo module"
                )
              )
            Managed(created).use: sgbm =>
              Managed.use(Mat()): raw => // CV_16S disparity, fixed-point
                Cv.orThrow("StereoSGBM.compute")(sgbm.compute(l, r, raw))
                // `normalize` defaults to an 8-bit result, which is exactly what a viewable disparity map
                // needs: the raw CV_16S fixed-point values mean nothing to a display or to `colorMap`.
                Image.wrap(raw.normalize(0, 255))

/** Obstacle detection from a depth/disparity map. */
object Obstacles:

  /** The near-field obstacles in a `disparity` map (as produced by [[StereoDepth.disparity]], brighter =
    * nearer): connected regions closer than `minNearness`, each with its mean nearness. Largest first.
    *
    * @param minNearness
    *   how near (`0`…`1`) a region must be to count as an obstacle.
    * @param minArea
    *   ignore blobs smaller than this many pixels.
    */
  def fromDisparity(disparity: Image, minNearness: Double = 0.5, minArea: Int = 200): Seq[Obstacle] =
    require(minNearness >= 0 && minNearness <= 1, s"minNearness must be in [0, 1], got $minNearness")
    require(minArea >= 0, s"minArea cannot be negative, got $minArea")
    val cutoff = (minNearness * 255).toInt.toDouble
    disparity.mat
      .threshold(cutoff, 255)
      .image
      .use: near =>
        near
          .morphology(MorphOp.Close, radius = 2)
          .use: cleaned =>
            Mats
              .blobs(cleaned, minArea)
              .map(region => Obstacle(region, meanNearness(disparity.mat, region)))
              .sortBy(-_.nearness)

  private def meanNearness(disparity: Mat, region: Rect): Double =
    Managed.use(disparity.submat(region.toCv))(patch => Core.mean(patch).`val`(0) / 255.0)
