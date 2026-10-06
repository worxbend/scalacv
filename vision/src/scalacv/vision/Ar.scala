package scalacv.vision

import org.opencv.calib3d.Calib3d
import org.opencv.core.{MatOfPoint2f, MatOfPoint3f}

import scalacv.*

/** An object-to-camera pose: `x_camera = R * x_object + t`.
  *
  * `rvec` is a Rodrigues axis-angle vector in radians; `tvec` uses the object's units (metres for a
  * metre-sized marker). The constructor/accessors stay in OpenCV's familiar vector representation;
  * [[transform]] supplies shared immutable geometry math without allocating native resources.
  */
final case class Pose3D(rvec: Seq[Double], tvec: Seq[Double]):

  /** The same object-to-camera mapping as immutable, validated geometry. */
  val transform: RigidTransform = RigidTransform.fromRotationVector(rvec, tvec)

  /** Straight-line distance from camera to object, in the marker's units. */
  def distance: Double = transform.translationNorm

object Pose3D:

  /** Wraps an object-to-camera transform; Rodrigues vectors use the principal angle in `[0, pi]`. */
  def fromTransform(transform: RigidTransform): Pose3D =
    Pose3D(transform.rotationVector, transform.translation)

/** A detected marker together with the pose recovered for it — what `image.arMarkers` returns. */
final case class MarkerPose(marker: ArucoMarker, pose: Pose3D):
  export pose.distance
  def id: Int = marker.id

/** Marker-based augmented reality: recover a marker's 3D pose from its detected corners, then project model
  * geometry back onto the image to draw on top of it.
  *
  * The flow is the classic one. [[estimatePose]] runs `solvePnP` (with the square-planar `IPPE_SQUARE`
  * solver, which is both faster and more stable for a flat tag than the general iterative one) against the
  * four corners an [[Aruco]] detection gives you, yielding a [[Pose3D]]. [[project]] then maps any [[Point3]]
  * model — a set of axes, a cube — through that pose and the camera [[Intrinsics]] to pixel coordinates you
  * can draw with the ordinary [[Draw]] verbs. The high-level `image.drawMarkerAxes` and
  * `image.drawMarkerCube` wire all three steps together.
  */
object Ar:

  /** The canonical object points of a square marker of side `length`, centred at the origin in its own plane
    * (`z = 0`), ordered to match OpenCV's corner order: top-left, top-right, bottom-right, bottom-left.
    */
  private def markerObjectPoints(length: Double): Seq[Point3] =
    val h = length / 2.0
    Seq(Point3(-h, h, 0), Point3(h, h, 0), Point3(h, -h, 0), Point3(-h, -h, 0))

  /** Recovers `marker`'s pose relative to the camera. `markerLength` is the tag's real side length (in
    * whatever unit you want the pose expressed in — metres is conventional). `None` if `solvePnP` fails to
    * converge, which for four coplanar corners is rare but not impossible.
    *
    * Every Mat this needs is owned for the duration of the call and freed before it returns, on the throwing
    * path as well as the normal one — the returned [[Pose3D]] is plain `Seq[Double]`, so nothing native
    * escapes and the caller has nothing to release.
    *
    * @throws IllegalArgumentException
    *   if the marker does not have four corners or `markerLength` is not positive.
    */
  def estimatePose(marker: ArucoMarker, markerLength: Double, intrinsics: Intrinsics): Option[Pose3D] =
    // The two preconditions stay above the first acquisition, so a rejected marker throws while there is
    // still nothing native to free.
    require(marker.corners.size == 4, s"a marker pose needs four corners, got ${marker.corners.size}")
    require(markerLength > 0, s"markerLength must be positive, got $markerLength")
    // Pnp.solve owns and frees every Mat the solve needs, on the throwing path as well as the normal one —
    // the returned Pose3D is plain Seq[Double], so nothing native escapes and the caller has nothing to
    // release. A native failure is rethrown as a CvError here, where HeadPose.estimate folds it to None:
    // IPPE_SQUARE on four coplanar corners has no degenerate-input trap worth hiding.
    Pnp
      .solve(
        markerObjectPoints(markerLength),
        marker.corners,
        intrinsics,
        PnpSolver.IppeSquare
      ) { (_, rvec, tvec) =>
        // readColumn copies the rotation and translation out into Seq[Double] before rvec/tvec are released.
        Pose3D(Mats.readColumn(rvec, 3), Mats.readColumn(tvec, 3))
      }
      .fold(throw _, identity)

  /** Projects model `points` (in the marker's frame) to pixel coordinates through `pose` and the camera.
    *
    * As with [[estimatePose]], every Mat is owned for the duration of the call and freed before it returns,
    * including when a native call or an allocation throws; the returned [[Point]]s are copies.
    */
  def project(points: Seq[Point3], pose: Pose3D, intrinsics: Intrinsics): Seq[Point] =
    projectVectors(points, pose.rvec, pose.tvec, intrinsics)

  /** Projects source-frame points through an object-to-camera transform. Translation and points must use the
    * same units; a monocular motion direction needs scale recovery before metric projection. Native
    * conversion and ownership are scoped exactly as in the [[Pose3D]] overload.
    */
  def project(points: Seq[Point3], transform: RigidTransform, intrinsics: Intrinsics): Seq[Point] =
    projectVectors(points, transform.rotationVector, transform.translation, intrinsics)

  private def projectVectors(
      points: Seq[Point3],
      rotationVector: Seq[Double],
      translation: Seq[Double],
      intrinsics: Intrinsics
  ): Seq[Point] =
    if points.isEmpty then Seq.empty
    else
      Managed.scope: own =>
        val obj = own(MatOfPoint3f(points.map(_.toCv)*))
        val out = own(MatOfPoint2f())
        val camera = own(intrinsics.cameraMatrix)
        val dist = own(intrinsics.distCoeffs)
        val rvec = own(Mats.column(rotationVector))
        val tvec = own(Mats.column(translation))
        Cv.orThrow("projectPoints")(Calib3d.projectPoints(obj, rvec, tvec, camera, dist, out))
        // toArray copies the projected corners onto the JVM heap, so this must read `out` before the
        // scope releases it.
        out.toArray.map(Point.from).toSeq

  /** The eight corners of a `size`-sided cube resting on the marker plane (base on `z = 0`, rising toward the
    * camera), ordered base 0–3 then top 4–7 above them. Feed to [[project]] to draw a wireframe.
    */
  private[scalacv] def cubeCorners(size: Double): Seq[Point3] =
    val h = size / 2.0
    Seq(
      Point3(-h, -h, 0),
      Point3(h, -h, 0),
      Point3(h, h, 0),
      Point3(-h, h, 0),
      Point3(-h, -h, size),
      Point3(h, -h, size),
      Point3(h, h, size),
      Point3(-h, h, size)
    )

  /** The twelve edges of the cube from [[cubeCorners]], as index pairs. */
  private[scalacv] val cubeEdges: Seq[(Int, Int)] =
    Seq((0, 1), (1, 2), (2, 3), (3, 0), (4, 5), (5, 6), (6, 7), (7, 4), (0, 4), (1, 5), (2, 6), (3, 7))

/** The high-level marker-AR verbs on [[Image]]. Extension methods, not members of [[Image]], so the marker
  * pipeline lives next to [[Ar]] rather than in the image class; `import scalacv.vision.*` makes
  * `image.arMarkers(…)` and the overlays available.
  */
extension (img: Image)

  /** Detects every marker and recovers its 3D [[Pose3D]] in one step — the query behind marker AR.
    * `markerLength` is the tag's real side length (metres, conventionally); `intrinsics` is the camera model
    * ([[Intrinsics.approx]] if you have not calibrated). Markers whose pose fails to solve are dropped.
    */
  def arMarkers(
      intrinsics: Intrinsics,
      markerLength: Double,
      dictionary: ArucoDictionary = ArucoDictionary.Dict4x4_50
  ): Seq[MarkerPose] =
    Aruco
      .detect(img.mat, dictionary)
      .flatMap(m => Ar.estimatePose(m, markerLength, intrinsics).map(MarkerPose(m, _)))

  /** Draws a 3D coordinate frame at every marker's pose — the classic "is my pose right?" overlay. X is red,
    * Y green, Z blue (pointing out of the tag toward the camera). `markerLength` is the tag's real side; the
    * axes are drawn at `axisLength`, or at half the side when it is `None`.
    */
  def drawMarkerAxes(
      intrinsics: Intrinsics,
      markerLength: Double,
      dictionary: ArucoDictionary = ArucoDictionary.Dict4x4_50,
      axisLength: Option[Double] = None
  ): Image =
    val len = axisLength.getOrElse(markerLength / 2.0)
    val poses = img.arMarkers(intrinsics, markerLength, dictionary)
    img.paint: m =>
      poses.foreach: mp =>
        val pts = Ar.project(
          Seq(Point3(0, 0, 0), Point3(len, 0, 0), Point3(0, len, 0), Point3(0, 0, len)),
          mp.pose,
          intrinsics
        )
        m.drawLine(pts(0), pts(1), Scalar.Red, Thickness.Stroke(2)) // X
        m.drawLine(pts(0), pts(2), Scalar.Green, Thickness.Stroke(2)) // Y
        m.drawLine(pts(0), pts(3), Scalar.Blue, Thickness.Stroke(2)) // Z

  /** Draws a wireframe cube standing on every marker, sized to the marker's side when `size` is `None` — the
    * "hello world" of marker AR. Consumes this image and returns the annotated one.
    */
  def drawMarkerCube(
      intrinsics: Intrinsics,
      markerLength: Double,
      dictionary: ArucoDictionary = ArucoDictionary.Dict4x4_50,
      color: Scalar = Scalar.Green,
      size: Option[Double] = None
  ): Image =
    val cube = size.getOrElse(markerLength)
    val poses = img.arMarkers(intrinsics, markerLength, dictionary)
    img.paint: m =>
      poses.foreach: mp =>
        val pts = Ar.project(Ar.cubeCorners(cube), mp.pose, intrinsics)
        Ar.cubeEdges.foreach((a, b) => m.drawLine(pts(a), pts(b), color, Thickness.Stroke(2)))
