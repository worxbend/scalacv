package scalacv.vision

import org.opencv.calib3d.Calib3d
import org.opencv.core.Mat

import scalacv.*

/** A camera's absolute pose: the 3×3 rotation and 3-vector translation that map world points into the camera
  * frame (`x_cam = R·x_world + t`).
  */
final case class CameraPose(rotation: Seq[Seq[Double]], translation: Seq[Double]):

  /** The camera's position in world coordinates, `-Rᵀ·t`. */
  def position: Seq[Double] =
    (0 until 3).map(i => -(0 until 3).map(j => rotation(j)(i) * translation(j)).sum)

/** Absolute localization — where the camera is, given a map of known 3D points and their matches in the
  * current frame.
  *
  * This is the "I recognise these landmarks, so I must be *here*" step: match the frame's [[Features]] to a
  * map, then hand the 3D↔2D correspondences here and `solvePnP` recovers the 6-DoF pose. Unlike
  * [[VisualOdometry]] (relative, up-to-scale, drifts), this is absolute and metric — it is what stops a SLAM
  * trajectory from drifting once a map exists.
  */
object Localizer:

  /** The camera pose from `worldPoints` (3D map points) and their `imagePoints` (matched 2D projections), via
    * `solvePnP`.
    *
    * How many correspondences are needed depends on the shape of the map points, because this uses OpenCV's
    * default iterative solver: four are enough when the world points are coplanar (they all lie on one flat
    * surface, such as a wall or a floor), but six are needed when they are not, since the non-planar
    * initialiser is a direct linear transform that has no solution below six points. OpenCV decides which of
    * the two cases applies, so scalacv does not try to predict it.
    *
    * Returns `None` — never throws — whenever the pose cannot be recovered: fewer than four correspondences,
    * four or five non-coplanar ones, or a degenerate configuration that makes `solvePnP` fail or refuse.
    *
    * @param intrinsics
    *   the pinhole camera model, including any lens distortion — use [[Intrinsics.approx]] when uncalibrated.
    */
  def locate(
      worldPoints: Seq[(Double, Double, Double)],
      imagePoints: Seq[Point],
      intrinsics: Intrinsics
  ): Option[CameraPose] =
    require(
      worldPoints.size == imagePoints.size,
      s"need one image point per world point, got ${worldPoints.size} and ${imagePoints.size}"
    )
    if worldPoints.size < 4 then None
    else
      // The solvePnP block runs inside Cv.attempt (via Pnp.solve, which also owns and frees every Mat)
      // because the default SOLVEPNP_ITERATIVE solver does not always answer with `ok = false`: on four or
      // five non-coplanar points it aborts inside its DLT initialiser with a native CV_Assert ("needs at
      // least 6 points"), which arrives here as a raw org.opencv.core.CvException. That is not even a
      // CvError, so a caller catching scalacv's own error type would miss it, and this method promises an
      // Option. Guarding by counting points instead was rejected: OpenCV decides planarity itself, by an
      // SVD on the point covariance, and a re-implemented threshold would disagree with it on near-planar
      // inputs and let the same assertion through.
      Pnp
        .solve(
          worldPoints.map((x, y, z) => Point3(x, y, z)),
          imagePoints,
          intrinsics,
          PnpSolver.Iterative
        ) { (own, rvec, tvec) =>
          val rotation = own(Mat())
          Calib3d.Rodrigues(rvec, rotation)
          CameraPose(Mats.readMatrix(rotation, 3, 3), Mats.readColumn(tvec, 3))
        }
        .getOrElse(None)
