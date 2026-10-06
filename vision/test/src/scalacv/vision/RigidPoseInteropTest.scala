package scalacv.vision

import org.opencv.calib3d.Calib3d
import org.opencv.core.Mat

import scalacv.*

/** Domain wrappers stay source-compatible; native matrices are scoped at solver/projection boundaries. */
class RigidPoseInteropTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  private def assertTransform(
      actual: RigidTransform,
      expected: RigidTransform,
      tolerance: Double = 1e-12
  ): Unit =
    for i <- 0 until 3; j <- 0 until 3 do
      assertEqualsDouble(actual.rotation(i)(j), expected.rotation(i)(j), tolerance, s"rotation($i,$j)")
    for i <- 0 until 3 do
      assertEqualsDouble(actual.translation(i), expected.translation(i), tolerance, s"translation($i)")

  test("pose wrappers expose the same source-to-destination transform without changing constructor fields"):
    val rvec = Seq(0.0, 0.0, math.Pi / 2.0)
    val translation = Seq(1.0, 0.0, 0.0)
    val expected = RigidTransform.fromRotationVector(rvec, translation)
    val marker = Pose3D(rvec = rvec, tvec = translation)
    val absolute = CameraPose(rotation = expected.rotation, translation = translation)
    val motion = CameraMotion(rotation = expected.rotation, translation = translation, inliers = 7)
    assertTransform(marker.transform, expected)
    assertTransform(absolute.transform, expected)
    assertTransform(motion.transform, expected)
    assertEquals(marker.rvec, rvec)
    assertEquals(marker.copy(tvec = Seq(0.0, 3.0, 4.0)).distance, 5.0)
    assertEquals(absolute.copy(translation = Seq(0.0, 0.0, 0.0)).translation, Seq(0.0, 0.0, 0.0))
    val CameraMotion(rotation, direction, count) = motion.copy(inliers = 9)
    assertEquals(rotation, expected.rotation)
    assertEquals(direction, translation)
    assertEquals(count, 9)
    absolute.position
      .zip(Seq(0.0, 1.0, 0.0))
      .foreach: (actual, wanted) =>
        assertEqualsDouble(actual, wanted, 1e-12)

  test("domain factories preserve a transform through the principal Rodrigues representation"):
    for angle <- Seq(0.0, 1e-12, math.Pi - 1e-8, math.Pi, math.Pi + 1e-8) do
      val expected = RigidTransform.fromRotationVector(Seq(angle, 0.0, 0.0), Seq(0.2, -0.1, 3.0))
      val objectPose = Pose3D.fromTransform(expected)
      assertTransform(objectPose.transform, expected)
      assertTransform(CameraPose.fromTransform(expected).transform, expected)
      assertEquals(objectPose.tvec, expected.translation)

  test("project accepts reusable geometry and agrees with explicit pinhole source-to-camera math"):
    val transform = RigidTransform.fromRotationVector(Seq(0.2, -0.3, 0.1), Seq(0.2, -0.1, 3.0))
    val intrinsics = Intrinsics(600.0, 610.0, 320.0, 240.0)
    val model = Seq(Point3(0.0, 0.0, 0.0), Point3(0.1, -0.2, 0.3), Point3(-0.3, 0.2, 0.1))
    val projected = Ar.project(model, transform, intrinsics)
    val legacy = Ar.project(model, Pose3D.fromTransform(transform), intrinsics)
    projected
      .zip(model)
      .zip(legacy)
      .foreach: (pair, previous) =>
        val (pixel, point) = pair
        val camera = transform.transformPoint(point)
        assertEqualsDouble(pixel.x, intrinsics.fx * camera.x / camera.z + intrinsics.cx, 5e-5)
        assertEqualsDouble(pixel.y, intrinsics.fy * camera.y / camera.z + intrinsics.cy, 5e-5)
        assertEquals(pixel, previous)
    assertEquals(Ar.project(Seq.empty, transform, intrinsics), Seq.empty)

  test("Rodrigues conversion matches scoped OpenCV matrices near zero and pi"):
    val axes = Seq(Seq(1.0, 0.0, 0.0), Seq(0.0, -1.0, 0.0), Seq(0.0, 0.0, 1.0), Seq(-1.0, 2.0, -3.0))
    for axis <- axes do
      val norm = math.hypot(math.hypot(axis(0), axis(1)), axis(2))
      for angle <- Seq(0.0, 1e-12, 1e-7, 0.5, math.Pi - 1e-8, math.Pi, math.Pi + 1e-8, 4.0) do
        val rvec = axis.map(_ * angle / norm)
        val transform = RigidTransform.fromRotationVector(rvec, Seq(0.1, -0.2, 3.0))
        val nativeRotation = Managed.scope: own =>
          val input = own(Mats.column(rvec))
          val output = own(Mat())
          Calib3d.Rodrigues(input, output)
          Mats.readMatrix(output, 3, 3)
        // Both values survive the scope's release; neither the transform nor the readout retains a Mat.
        assertTransform(transform, RigidTransform(nativeRotation, transform.translation))
        val nativePrincipalRotation = Managed.scope: own =>
          val input = own(Mats.column(transform.rotationVector))
          val output = own(Mat())
          Calib3d.Rodrigues(input, output)
          Mats.readMatrix(output, 3, 3)
        assertTransform(RigidTransform(nativePrincipalRotation, transform.translation), transform)

  test("known marker and localizer recover the same object/world-to-camera geometry"):
    val expected = RigidTransform.fromRotationVector(Seq(math.Pi - 0.2, 0.1, -0.05), Seq(0.1, -0.08, 2.5))
    val intrinsics = Intrinsics(600.0, 610.0, 320.0, 240.0)
    val length = 0.8
    val h = length / 2.0
    val square = Seq(Point3(-h, h, 0.0), Point3(h, h, 0.0), Point3(h, -h, 0.0), Point3(-h, -h, 0.0))
    val observed = Ar.project(square, expected, intrinsics)
    val marker =
      Ar.estimatePose(ArucoMarker(7, observed), length, intrinsics).getOrElse(fail("marker solve failed"))
    val localizer = Localizer
      .locate(square.map(p => (p.x, p.y, p.z)), observed, intrinsics)
      .getOrElse(fail("planar localization solve failed"))
    assertTransform(marker.transform, expected, 1e-5)
    assertTransform(localizer.transform, expected, 1e-5)
    assertTransform(marker.transform, localizer.transform, 1e-5)
    localizer.position
      .zip(expected.inverse.translation)
      .foreach: (actual, wanted) =>
        assertEqualsDouble(actual, wanted, 1e-5)
    val reprojected = Ar.project(square, localizer.transform, intrinsics)
    reprojected
      .zip(observed)
      .foreach: (actual, wanted) =>
        assertEqualsDouble(actual.x, wanted.x, 5e-5)
        assertEqualsDouble(actual.y, wanted.y, 5e-5)

  test("recovered CameraMotion transform keeps a unit direction rather than claiming metric scale"):
    val intrinsics = Intrinsics(600.0, 600.0, 320.0, 240.0)
    val points = Seq(
      Point3(-2.0, -1.5, 4.0),
      Point3(2.0, -1.5, 9.0),
      Point3(-2.0, 1.5, 12.0),
      Point3(2.0, 1.5, 5.0),
      Point3(0.0, 0.0, 7.0),
      Point3(1.0, -0.8, 3.5),
      Point3(-1.2, 0.6, 10.0),
      Point3(0.4, 1.2, 6.0),
      Point3(-0.5, -1.0, 8.5),
      Point3(1.5, 0.3, 4.5),
      Point3(-1.8, -0.2, 6.5),
      Point3(0.8, 0.9, 11.0)
    )
    val metric = RigidTransform.fromRotationVector(Seq(0.0, 0.1, 0.0), Seq(0.3, 0.0, 0.1))
    val from = Ar.project(points, RigidTransform.identity, intrinsics)
    val to = Ar.project(points, metric, intrinsics)
    val motion = VisualOdometry.estimate(from, to, intrinsics).getOrElse(fail("motion solve failed"))
    assertEquals(motion.transform.translation, motion.translation)
    assertEqualsDouble(motion.transform.translationNorm, 1.0, 1e-12)
    assert(math.abs(motion.transform.translationNorm - metric.translationNorm) > 0.5)
    val alignment = motion.translation.zip(metric.translation).map(_ * _).sum / metric.translationNorm
    assert(alignment > 0.9, s"translation direction was reversed: ${motion.translation}")
    for i <- 0 until 3; j <- 0 until 3 do
      assertEqualsDouble(motion.transform.rotation(i)(j), metric.rotation(i)(j), 0.05)
    assert(motion.inliers > 0)

  test("thin domain wrappers reject malformed and non-finite geometry at construction"):
    val identity = RigidTransform.identity.rotation
    val zero = Seq(0.0, 0.0, 0.0)
    intercept[IllegalArgumentException](CameraPose(identity.take(2), zero))
    intercept[IllegalArgumentException](CameraMotion(identity, Seq(0.0, 0.0), 1))
    intercept[IllegalArgumentException](Pose3D(Seq(0.0, 0.0), zero))
    intercept[IllegalArgumentException](CameraPose(identity, Seq(Double.NaN, 0.0, 0.0)))
    intercept[IllegalArgumentException](
      CameraMotion(identity.updated(0, Seq(Double.PositiveInfinity, 0.0, 0.0)), zero, 1)
    )
    intercept[IllegalArgumentException](Pose3D(Seq(Double.NegativeInfinity, 0.0, 0.0), zero))
    intercept[IllegalArgumentException](Pose3D(zero, Seq(0.0, Double.NaN, 0.0)))

  test("Pose3D distance uses a stable shared norm for large and tiny finite translations"):
    val large = Pose3D(Seq(0.0, 0.0, 0.0), Seq(3e200, 4e200, 0.0))
    assertEqualsDouble(large.distance / 1e200, 5.0, 1e-12)
    val tiny = Pose3D(Seq(0.0, 0.0, 0.0), Seq(3e-200, 4e-200, 0.0))
    assertEqualsDouble(tiny.distance / 1e-200, 5.0, 1e-12)
