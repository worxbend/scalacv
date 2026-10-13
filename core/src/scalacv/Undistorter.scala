package scalacv

import scala.util.Using

import org.opencv.calib3d.Calib3d
import org.opencv.core.{Core, CvType, Mat}
import org.opencv.imgproc.Imgproc

/** Prepared lens correction for sequential frames with one calibration and resolution.
  *
  * Owns two native maps (six bytes per pixel), not the input or the returned images. There is no global
  * cache: keep this object in an explicit `Using.resource` scope and close every returned [[Managed]]. Calls,
  * including `close`, must not overlap. Like [[Managed]], the liveness guard is not a concurrency lock.
  * Calibration is snapshotted during preparation; changing the caller's coefficient storage cannot change
  * this object's maps.
  *
  * Uses the same camera matrix, linear interpolation and black constant border as `Mat.undistorted`. Existing
  * one-shot operations are unchanged; preparation only pays off when reused across frames.
  */
final class Undistorter private (
    val size: Size,
    private val map1: Managed[Mat],
    private val map2: Managed[Mat]
) extends AutoCloseable:

  /** Borrows `src` and returns an independent, caller-owned result of the same type and resolution. Throws
    * `IllegalStateException` after close and `IllegalArgumentException` for a different resolution.
    */
  def apply(src: Mat): Managed[Mat] =
    val coordinates = map1.get
    val fractions = map2.get
    require(
      src.dims() == 2 && src.cols() == size.width.toInt && src.rows() == size.height.toInt,
      s"prepared undistortion needs a ${size.width.toInt}x${size.height.toInt} image"
    )
    Mats.produce("prepared undistort"): dst =>
      if src.depth() == CvType.CV_8U then
        Imgproc.remap(src, dst, coordinates, fractions, Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT)
      else remapStripes(src, dst, coordinates, fractions)

  private def remapStripes(src: Mat, dst: Mat, coordinates: Mat, fractions: Mat): Unit =
    // Float remap's vector/scalar tails can round differently when a full-height call replaces
    // undistort's stripes. Keep the original call boundaries for non-byte depths as well as the maps.
    val width = src.cols()
    val height = src.rows()
    dst.create(height, width, src.`type`())
    val stripeHeight = Undistorter.stripeHeight(width, height)
    var y = 0
    while y < height do
      val end = math.min(y + stripeHeight, height)
      Managed.scope: own =>
        val first = own(coordinates.submat(y, end, 0, width))
        val second = own(fractions.submat(y, end, 0, width))
        val output = own(dst.submat(y, end, 0, width))
        Imgproc.remap(src, output, first, second, Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT)
      y = end

  /** Releases both map buffers. Idempotent; previously returned images remain valid. */
  override def close(): Unit = Using.resources(map1, map2)((_, _) => ())

object Undistorter:

  /** Builds maps for a positive integer resolution below OpenCV remap's 32767-pixel limit per side.
    * Preparation releases intermediate camera matrices and rolls back map buffers on failure.
    *
    * {{{
    * scala.util.Using.resource(Undistorter.prepare(intrinsics, Size(640, 480))) { prepared =>
    *   prepared(frame).use { corrected => /* consume corrected before this scope ends */ }
    * }
    * }}}
    */
  def prepare(intrinsics: Intrinsics, size: Size): Undistorter =
    prepareObserved(intrinsics, size)(_ => ())

  // Construction-only fault/ownership probe: callbacks borrow resources after ownership registration.
  // No observer or mutable map accessor survives on the prepared object.
  private[scalacv] def prepareObserved(intrinsics: Intrinsics, size: Size)(
      acquired: Mat => Unit
  ): Undistorter =
    def observe(mat: Mat): Mat =
      acquired(mat)
      mat
    require(
      Seq(size.width, size.height).forall(n => n > 0 && n < Short.MaxValue && n == n.toInt.toDouble),
      "prepared undistortion needs integer dimensions in [1, 32767)"
    )
    // Iterator copying also snapshots immutable views with externally mutable backing storage.
    val snapshot = intrinsics.copy(distortion = intrinsics.distortion.iterator.toVector)
    val map1 = Managed(Mat())
    try
      acquired(map1.get)
      val map2 = Managed(Mat())
      try
        acquired(map2.get)
        Cv.orThrow("prepare undistort")(fillMaps(snapshot, size, map1.get, map2.get, observe))
        new Undistorter(size, map1, map2)
      catch case error: Throwable => Using.resource(map2)(_ => throw error)
    catch case error: Throwable => Using.resource(map1)(_ => throw error)

  private def stripeHeight(width: Int, height: Int): Int = math.min(math.max(1, 4096 / width), height)

  private def fillMaps(
      intrinsics: Intrinsics,
      size: Size,
      map1: Mat,
      map2: Mat,
      observe: Mat => Mat
  ): Unit =
    Managed.scope: own =>
      val camera = observe(own(intrinsics.cameraMatrix))
      val distortion = observe(own(intrinsics.distCoeffs))
      val rectification = observe(own(Mat.eye(3, 3, CvType.CV_64F)))
      val outputCamera = observe(own(camera.clone()))
      val width = size.width.toInt
      val height = size.height.toInt
      map1.create(height, width, CvType.CV_16SC2)
      map2.create(height, width, CvType.CV_16UC1)
      // OpenCV 4.14 undistort computes maps in ~4096-pixel stripes, shifting cy for each stripe.
      // Preserve that floating-point evaluation order: a single full-height init can round a map
      // coordinate differently at interpolation-bin boundaries. These map views are preparation-only.
      val stripeHeight = Undistorter.stripeHeight(width, height)
      var y = 0
      while y < height do
        val end = math.min(y + stripeHeight, height)
        Managed.scope: stripe =>
          val first = observe(stripe(map1.submat(y, end, 0, width)))
          val second = observe(stripe(map2.submat(y, end, 0, width)))
          val _ = outputCamera.put(1, 2, intrinsics.cy - y)
          Calib3d.initUndistortRectifyMap(
            camera,
            distortion,
            rectification,
            outputCamera,
            Size(width, end - y).toCv,
            CvType.CV_16SC2,
            first,
            second
          )
        y = end
