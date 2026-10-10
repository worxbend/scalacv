package scalacv

import munit.FunSuite
import org.opencv.core.{CvType, Mat}
import scalacv.vision.*

class VisionNumericalRegressionTest extends FunSuite:
  OpenCv.load()

  test("navigation local support does not sample neighboring bands") {
    scala.util.Using.resource(Image.blank(15, 10, Scalar.Black)) { image =>
      Managed.use(image.mat.submat(Rect(0, 0, 5, 10).toCv)) { left =>
        left.setTo(org.opencv.core.Scalar.all(255))
      }
      val result = Navigator.steer(image, 0.3, 0.8)
      assertEquals(result.centreNearness, 0.0)
      assertEquals(result.steering, Steering.Straight)
    }
  }

  test("extreme disjoint rectangles have zero IoU") {
    assertEquals(ObjectTracker.iou(Rect(Int.MaxValue - 5, 0, 10, 10), Rect(0, 0, 10, 10)), 0.0)
  }

  test("intrinsics reject nonfinite calibration") {
    intercept[IllegalArgumentException](Intrinsics(Double.PositiveInfinity, 500, 320, 240))
    intercept[IllegalArgumentException](Intrinsics(500, 500, Double.NaN, 240))
    intercept[IllegalArgumentException](Intrinsics(500, 500, 320, 240, Seq(0, 0, 0, Double.NaN)))
  }

  test("collapsed localization has no pose") {
    assertEquals(
      Localizer
        .locate(Seq.fill(6)((0.0, 0.0, 0.0)), Seq.fill(6)(Point(0, 0)), Intrinsics(500, 500, 320, 240)),
      None
    )
  }

  test("distinct stationary correspondences have no observable motion") {
    val p = Seq(
      Point(30, 40),
      Point(110, 75),
      Point(260, 170),
      Point(410, 300),
      Point(55, 310),
      Point(510, 20),
      Point(610, 405),
      Point(230, 450)
    )
    assertEquals(VisualOdometry.estimate(p, p, Intrinsics(500, 500, 320, 240)), None)
  }

  test("minimal five point geometry is decoded without a stacked-matrix assertion") {
    val k = Intrinsics(500, 500, 320, 240)
    val world = Seq((-1.0, -0.5, 4.0), (0.4, -0.8, 6.0), (1.2, 0.3, 5.0), (-0.7, 0.9, 7.0), (0.2, 0.4, 3.0))
    val a = world.map((x, y, z) => Point(500 * x / z + 320, 500 * y / z + 240))
    val b = world.map((x, y, z) => Point(500 * (x + 0.6) / z + 320, 500 * y / z + 240))
    val motion = VisualOdometry.estimate(a, b, k)
    assert(
      motion.forall(_.inliers >= 5)
    ) // multiple supported minimal solutions may legitimately be ambiguous
  }

  test("batched segmentation and heatmaps are rejected before reshape") {
    Managed.use(Mat(Array(2, 2, 2, 2), CvType.CV_32F)) { m =>
      intercept[IllegalArgumentException](Segmenter.decodeMask(m, Size(8, 8)))
    }
    Managed.use(Mat(Array(2, 17, 2, 2), CvType.CV_32F)) { m =>
      intercept[IllegalArgumentException](PoseEstimator.decode(m, Size(8, 8), KeypointLayout.Heatmap))
    }
  }

  test("out of representable grid coordinates do not wrap") {
    val g = OccupancyGrid(11, 11, 1)
    intercept[IllegalArgumentException](g.cellOf(4294967296.0, 0))
    assertEquals(g.probability(0, 0), 0.5)
  }

  test("template exhaustion retains negative correlation without duplicates") {
    Managed.use(Mat(8, 8, CvType.CV_8UC1)) { m =>
      m.put(0, 0, Array.tabulate[Byte](64)(i => (i * 17).toByte))
      scala.util.Using.resource(Image.wrap(Managed(m.clone()))) { image =>
        assertEquals(Screen.findAll(image, image, minScore = -1, maxMatches = 3).size, 1)
      }
    }
  }

  private val scene: Seq[Point3] =
    val random = new scala.util.Random(174)
    Vector.fill(60)(
      Point3(random.nextDouble() * 4 - 2, random.nextDouble() * 3 - 1.5, random.nextDouble() * 5 + 4)
    )

  test("odometry uses distortion and preserves robust inlier support") {
    val k = Intrinsics(500, 530, 320, 240, Seq(0.2, -0.04, 0.005, -0.003, 0.01))
    val a = Ar.project(scene, Pose3D(Seq(0, 0, 0), Seq(0, 0, 0)), k)
    val clean = Ar.project(scene, Pose3D(Seq(0.01, 0.03, -0.02), Seq(0.6, 0.05, 0.02)), k)
    val b = clean.zipWithIndex.map((p, i) => if i < 12 then Point(30 + i * 31, 440 - i * 23) else p)
    val motion = VisualOdometry.estimate(a, b, k).getOrElse(fail("distorted scene should recover motion"))
    assert(motion.inliers >= 40 && motion.inliers <= 48, s"support = ${motion.inliers}")
    assert(motion.translation.head > 0.97, s"translation = ${motion.translation}")
    assertEqualsDouble(motion.rotation(0)(2), 0.0299, 0.02)
  }

  test("pure rotation and negligible translation do not imply observable translation") {
    val k = Intrinsics(500, 500, 320, 240)
    val a = Ar.project(scene, Pose3D(Seq(0, 0, 0), Seq(0, 0, 0)), k)
    for translation <- Seq(Seq(0.0, 0.0, 0.0), Seq(0.000001, 0.0, 0.0)) do
      val b = Ar.project(scene, Pose3D(Seq(0.01, 0.03, -0.02), translation), k)
      assertEquals(VisualOdometry.estimate(a, b, k), None)
  }

  test("localization rejects collinear and nonfinite inputs but accepts a planar square") {
    val k = Intrinsics(500, 500, 320, 240)
    val line = (0 until 6).map(i => (i.toDouble, 0.0, 5.0))
    assertEquals(Localizer.locate(line, line.map((x, y, z) => Point(500 * x / z + 320, 240)), k), None)
    val square = Seq((-1.0, -1.0, 5.0), (1.0, -1.0, 5.0), (1.0, 1.0, 5.0), (-1.0, 1.0, 5.0))
    val pixels = square.map((x, y, z) => Point(500 * x / z + 320, 500 * y / z + 240))
    assert(Localizer.locate(square, pixels, k).nonEmpty)
    assertEquals(Localizer.locate(square, pixels.updated(0, Point(Double.NaN, 0)), k), None)
    val inconsistent = scene.indices.map(i => Point((i * 151 % 640).toDouble, (i * 79 % 480).toDouble))
    assertEquals(Localizer.locate(scene.map(p => (p.x, p.y, p.z)), inconsistent, k), None)
  }

  test("grid clips billion-cell rays and never invents boundary obstacles") {
    val g = OccupancyGrid(11, 11, 1)
    g.observe(0, 0, 4294967296.0, 0)
    for x <- 0 to 5 do assert(g.probability(x, 0) < 0.5)
    assertEquals(g.probability(-1, 0), 0.5)
    val crossing = OccupancyGrid(11, 11, 1)
    crossing.observe(-1e9, -1e9, 1e9, 1e9)
    for x <- -5 to 5 do assert(crossing.probability(x, x) < 0.5)
    crossing.observe(1e9, 1e9, 2e9, 2e9)
    assertEquals(crossing.probability(0, 1), 0.5)
    intercept[IllegalArgumentException](g.cellOf(Double.NaN, 0))
  }

  test("decoders reject wrong depth and channels but preserve supported layouts") {
    for typ <- Seq(CvType.CV_64F, CvType.CV_32FC2) do
      Managed.use(Mat(Array(1, 17, 2, 2), typ)) { m =>
        intercept[IllegalArgumentException](PoseEstimator.decode(m, Size(8, 8), KeypointLayout.Heatmap))
      }
    for shape <- Seq(Array(1, 1, 17, 3), Array(17, 3)) do
      Managed.use(Mat(shape, CvType.CV_32F, org.opencv.core.Scalar(0.5))) { m =>
        assertEquals(PoseEstimator.decode(m, Size(8, 8), KeypointLayout.Regression).keypoints.size, 17)
      }
    for shape <- Seq(Array(1, 3, 2, 2), Array(3, 2, 2)) do
      Managed.use(Mat(shape, CvType.CV_32F, org.opencv.core.Scalar(1))) { m =>
        scala.util.Using.resource(Segmenter.decodeMask(m, Size(8, 8))) { mask =>
          assertEquals(org.opencv.core.Core.countNonZero(mask.mat), 64)
        }
      }
  }

  test("template matches never overlap even with adjacent equal peaks") {
    scala.util.Using.resource(Image.blank(30, 10, Scalar.White)) { image =>
      scala.util.Using.resource(Image.blank(10, 10, Scalar.White)) { template =>
        val hits = Screen.findAll(image, template, -1, 20)
        assertEquals(hits.size, 3)
        for a <- hits.indices; b <- 0 until a do
          assertEquals(ObjectTracker.iou(hits(a).location, hits(b).location), 0.0)
      }
    }
  }

  test("grid clipping remains correct beyond double interpolation precision") {
    val g = OccupancyGrid(11, 11, 1)
    g.observe(-1e100, -1e100, 1e100, 1e100)
    for x <- -5 to 5 do assert(g.probability(x, x) < 0.5)
  }

  test("minimal five-point scenes expose supported hypotheses rather than raising the minimum") {
    val k = Intrinsics(500, 500, 320, 240)
    val recovered = scene
      .sliding(5, 5)
      .flatMap { points =>
        val a = Ar.project(points, Pose3D(Seq(0, 0, 0), Seq(0, 0, 0)), k)
        val b = Ar.project(points, Pose3D(Seq(0.01, 0.03, -0.02), Seq(0.6, 0.05, 0.02)), k)
        VisualOdometry.estimateCandidates(a, b, k)
      }
      .toList
    assert(recovered.nonEmpty, "five-point input must not be rejected unconditionally")
    assert(recovered.forall(_.inliers == 5))
  }

  test("measured navigation keeps pixel thresholds and treats unknown as blocked") {
    Managed.use(Mat(40, 300, CvType.CV_32F, org.opencv.core.Scalar(1))) { pixels =>
      Managed.use(pixels.submat(0, 40, 145, 155))(_.setTo(org.opencv.core.Scalar(10)))
      scala.util.Using.resource(DisparityMeasurement.fromPixels(pixels)) { d =>
        assert(Navigator.steerMeasured(d, 8, 20).steering != Steering.Straight)
        assertEqualsDouble(d.at(150, 10).get, 10, 1e-9)
      }
      pixels.setTo(org.opencv.core.Scalar(-1))
      scala.util.Using.resource(DisparityMeasurement.fromPixels(pixels)) { d =>
        assertEquals(Navigator.steerMeasured(d, 8, 20).steering, Steering.Stop)
        assertEquals(Navigator.steerMeasured(d, 8, 20.1).steering, Steering.Stop)
      }
    }
  }

  test("stereo measurement owns buffers independently and closes idempotently") {
    val random = new scala.util.Random(123)
    val image = Image.wrap(Managed(Mat(48, 160, CvType.CV_8UC1)))
    try
      val bytes = Array.fill[Byte](48 * 160)(random.nextInt(256).toByte)
      image.mat.put(0, 0, bytes)
      val d = StereoDepth.measure(image, image, 16, 3)
      assertEquals(d.width, 160)
      assertEquals(d.height, 48)
      val pixels = d.pixelsCopy
      val mask = d.validityCopy
      try
        assertEquals(pixels.get.`type`(), CvType.CV_32FC1)
        assertEquals(mask.get.`type`(), CvType.CV_8UC1)
        d.close()
        d.close()
        intercept[IllegalStateException](d.at(0, 0))
        assert(!pixels.get.empty())
        assert(!mask.get.empty())
      finally
        pixels.release()
        mask.release()
        d.close()
      assertEquals(image.width, 160)
    finally image.close()
  }

  test("bounded loop detector retains bounded metadata and resets on close") {
    val d = LoopDetector(maxKeyframes = 3)
    val image = Image.wrap(Managed(Mat.zeros(32, 32, CvType.CV_8UC1)))
    try
      for i <- 0 until 200 do assertEquals(d.addKeyframe(image), i)
      val field = d.getClass.getDeclaredFields.find(_.getName.contains("keyframes")).get
      field.setAccessible(true)
      val storage = field.get(d).asInstanceOf[scala.collection.Iterable[?]]
      assertEquals(storage.size, 3)
      d.close()
      assertEquals(d.keyframeCount, 0)
      assertEquals(d.addKeyframe(image), 0)
      assertEquals(d.process(image), None)
      assertEquals(d.keyframeCount, 2)
    finally
      d.close()
      image.close()
  }

  test("disparity samples retain pixel units independent of other scene extrema") {
    def measurement(last: Float): DisparityMeasurement =
      Managed.use(Mat(1, 3, CvType.CV_32FC1)) { m =>
        m.put(0, 0, Array(10f, -1f, last))
        DisparityMeasurement.fromPixels(m)
      }
    scala.util.Using.resource(measurement(20)) { a =>
      scala.util.Using.resource(measurement(100)) { b =>
        assertEquals(a.at(0, 0), Some(10.0))
        assertEquals(a.at(1, 0), None)
        assertEquals(a.at(0, 0), b.at(0, 0))
        scala.util.Using.resource(a.visualize(100)) { av =>
          scala.util.Using.resource(b.visualize(100)) { bv =>
            assertEquals(av.mat.get(0, 0)(0), bv.mat.get(0, 0)(0))
          }
        }
      }
    }
  }

  test("local support suppresses isolated noise without diluting narrow obstacles") {
    val image = Image.wrap(Managed(Mat.zeros(40, 300, CvType.CV_8UC1)))
    try
      image.mat.put(20, 150, Array(255.toByte))
      assertEquals(Navigator.steer(image).steering, Steering.Straight)
      Managed.use(image.mat.submat(0, 40, 145, 155))(_.setTo(org.opencv.core.Scalar(255)))
      assert(Navigator.steer(image).steering != Steering.Straight)
    finally image.close()
  }

  test("grid quantization retains nearest rounding including half boundaries") {
    val g = OccupancyGrid(11, 11, 1)
    assertEquals(g.cellOf(0.49999999999999994, -0.5), (5, 5))
    assertEquals(g.cellOf(0.5, -0.5000000000000001), (6, 4))
  }

  test("IoU remains symmetric and bounded at extreme coordinates and areas") {
    val boxes = Seq(
      Rect(Int.MinValue, Int.MinValue, Int.MaxValue, Int.MaxValue),
      Rect(Int.MaxValue - 5, 0, 10, 10),
      Rect(0, 0, Int.MaxValue, Int.MaxValue),
      Rect(0, 0, 0, 0)
    )
    for a <- boxes; b <- boxes do
      val value = ObjectTracker.iou(a, b)
      assert(value.isFinite && value >= 0 && value <= 1)
      assertEquals(value, ObjectTracker.iou(b, a))
    assertEquals(ObjectTracker.iou(boxes.head, boxes.head), 1.0)
  }

  test("narrow persistent obstacle is not diluted by the whole band") {
    Managed.use(Mat.zeros(60, 300, CvType.CV_8UC1)) { m =>
      Managed.use(m.submat(0, 60, 145, 155))(_.setTo(org.opencv.core.Scalar(255)))
      scala.util.Using.resource(Image.wrap(Managed(m.clone()))) { image =>
        assert(Navigator.steer(image).steering != Steering.Straight)
      }
    }
  }
