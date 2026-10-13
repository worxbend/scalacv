package scalacv

import org.opencv.core.{CvType, Mat}

import scala.util.Using

import scalacv.vision.*

/** Count-only matching must agree with the sorted correspondence API without consuming its inputs. */
class FeaturesCountTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  private val thresholds = Seq(
    Float.NegativeInfinity,
    -1f,
    0f,
    4f,
    16f,
    64f,
    128f,
    256f,
    Float.PositiveInfinity,
    Float.NaN
  )

  private def descriptors(bytes: Array[Byte]): Descriptors =
    require(bytes.length % 32 == 0)
    val rows = bytes.length / 32
    val mat = Mat(rows, 32, CvType.CV_8UC1)
    try
      if bytes.nonEmpty then mat.put(0, 0, bytes)
      new Descriptors(Vector.fill(rows)(Point(0, 0)), Managed(mat))
    catch
      case e: Throwable =>
        mat.release()
        throw e

  private def scene(seed: Int): Image =
    val random = new scala.util.Random(seed)
    val blocks = Vector.fill(100)(
      Rect(35 + random.nextInt(550), 35 + random.nextInt(390), 4 + random.nextInt(20), 4 + random.nextInt(20))
    )
    Image.blank(640, 480, Scalar(20, 20, 20)).drawRects(blocks, Scalar.White, Thickness.Filled)

  private def assertEquivalent(a: Descriptors, b: Descriptors, limits: Seq[Float]): Unit =
    limits.foreach: limit =>
      val sorted = Features.matches(a, b, limit)
      assertEquals(Features.countMatches(a, b, limit), sorted.size, s"threshold=$limit")
      assertEquals(sorted.map(_.distance), sorted.map(_.distance).sorted)
      assert(sorted.forall(_.distance <= limit))

  test("empty descriptors give zero on either side at every threshold"):
    Using
      .Manager: use =>
        val empty = use(descriptors(Array.emptyByteArray))
        val one = use(descriptors(new Array[Byte](32)))
        assert(empty.isEmpty)
        for (a, b) <- Seq((empty, one), (one, empty), (empty, empty)) do
          assertEquivalent(a, b, thresholds)
          assertEquals(Features.countMatches(a, b), 0)
      .get

  test("distance bound is inclusive and rejects NaN and unmatched nonempty inputs"):
    Using
      .Manager: use =>
        val a = use(descriptors(new Array[Byte](32)))
        val changed = new Array[Byte](32)
        changed(0) = 15 // exactly four different bits
        val b = use(descriptors(changed))
        assertEquals(Features.matches(a, b, 4f), Seq(FeatureMatch(0, 0, 4f)))
        assertEquals(Features.countMatches(a, b, 4f), 1)
        assertEquals(Features.countMatches(a, b, Math.nextDown(4f)), 0)
        assertEquals(Features.countMatches(a, b, Float.NaN), 0)
        assertEquivalent(a, b, thresholds :+ Math.nextDown(4f))
      .get

  test("cross-check does not count every query when descriptors share a best match"):
    Using
      .Manager: use =>
        val duplicates = use(descriptors(new Array[Byte](32 * 5)))
        val one = use(descriptors(new Array[Byte](32)))
        assertEquals(Features.countMatches(duplicates, one, 0f), 1)
        assertEquivalent(duplicates, one, thresholds)
        assertEquivalent(one, duplicates, thresholds)
      .get

  test("seeded random descriptor sets agree in both directions across thresholds"):
    Using
      .Manager: use =>
        for seed <- 0 until 4 do
          val random = new scala.util.Random(seed)
          val aBytes = new Array[Byte](32 * (1 + seed * 43))
          val bBytes = new Array[Byte](32 * (3 + seed * 37))
          random.nextBytes(aBytes)
          random.nextBytes(bBytes)
          val a = use(descriptors(aBytes))
          val b = use(descriptors(bBytes))
          val all = Features.matches(a, b, Float.PositiveInfinity)
          assert(all.nonEmpty)
          val boundaries = all.take(5).flatMap(m => Seq(Math.nextDown(m.distance), m.distance))
          assertEquivalent(a, b, thresholds ++ boundaries)
          assertEquivalent(b, a, thresholds ++ boundaries)
      .get

  test("generated images agree for repeated stable ORB descriptors without consuming them"):
    Using
      .Manager: use =>
        val aImage = use(scene(42))
        val bImage = use(scene(43))
        val a = use(Features.detect(aImage))
        val b = use(Features.detect(bImage))
        assert(a.size > 100)
        assert(b.size > 100)
        val before = Features.matches(a, b, Float.PositiveInfinity)
        val self = Features.matches(a, a, 0f)
        assert(self.nonEmpty)
        for _ <- 0 until 8 do
          assertEquivalent(a, b, thresholds)
          assertEquals(Features.countMatches(a, a, 0f), self.size)
        assert(!a.descriptors.isReleased)
        assert(!b.descriptors.isReleased)
        assertEquals(Features.matches(a, b, Float.PositiveInfinity), before)
        assertEquals(aImage.width, 640)
        assertEquals(bImage.width, 640)
      .get

  test("closed nonempty descriptors fail before JNI and do not consume the other side"):
    Using
      .Manager: use =>
        val closed = use(descriptors(new Array[Byte](32)))
        val live = use(descriptors(new Array[Byte](32)))
        closed.close()
        intercept[IllegalStateException](Features.countMatches(closed, live))
        intercept[IllegalStateException](Features.countMatches(live, closed))
        intercept[IllegalStateException](Features.matches(closed, live))
        assertEquals(Features.countMatches(live, live), 1)
        assert(!live.descriptors.isReleased)
      .get

  test("loop scores and earliest-tie selection agree with sorted matching"):
    Using
      .Manager: use =>
        val image = use(scene(71))
        val descriptors = use(Features.detect(image))
        val count = Features.matches(descriptors, descriptors).size
        assert(count > 100)
        val detector = use(LoopDetector(minMatches = 1, recentExclusion = 0))
        assertEquals(detector.addKeyframe(image), 0)
        assertEquals(detector.addKeyframe(image), 1)
        val expected = Some(LoopClosure(0, count, count.toDouble / descriptors.size))
        assertEquals(detector.detect(image), expected)
        assertEquals(detector.process(image), expected)
        assertEquals(detector.keyframeCount, 3)
      .get
