package scalacv

import scala.collection.immutable.ArraySeq
import scala.util.Using

import munit.FunSuite
import org.opencv.core.{CvType, Mat}

class UndistorterTest extends FunSuite:
  override def beforeAll(): Unit = OpenCv.load()

  private def fixture(width: Int, height: Int, channels: Int = 3): Mat =
    val src = Mat(height, width, CvType.CV_8UC(channels))
    try
      val row = new Array[Byte](width * channels)
      for y <- 0 until height do
        for x <- row.indices do row(x) = ((x * 73 + y * 29 + x * y * 3) & 255).toByte
        src.put(y, 0, row)
      src
    catch
      case error: Throwable =>
        src.release()
        throw error

  test("preparation snapshots even an unsafely array-backed coefficient sequence"):
    val coefficients = Array(-0.21, 0.04, 0.003, -0.002, 0.01)
    val calibration = Intrinsics(230, 235, 79.3, 57.7, ArraySeq.unsafeWrapArray(coefficients))
    Managed.use(fixture(161, 119)): src =>
      val expected = src.undistorted(calibration).use(PixelHash.of)
      Using.resource(Undistorter.prepare(calibration, Size(161, 119))): prepared =>
        java.util.Arrays.fill(coefficients, 0.0)
        assertNotEquals(src.undistorted(calibration).use(PixelHash.of), expected)
        assertEquals(prepared(src).use(PixelHash.of), expected)

  private val coefficients = Vector(
    -0.21, 0.04, 0.003, -0.002, 0.01, 0.006, -0.004, 0.002, 0.0002, -0.0003, 0.0004, -0.0001, 0.012, -0.018
  )

  for count <- Seq(0, 4, 5, 8, 12, 14) do
    test(s"$count coefficients match one-shot hashes across channels and stripe boundaries"):
      for
        (width, height) <- Seq(161 -> 119, 641 -> 83, 4097 -> 3)
        channels <- Seq(1, 3, 4)
      do
        val calibration =
          Intrinsics(width * 0.83, width * 0.81, width * 0.47, height * 0.53, coefficients.take(count))
        Managed.use(fixture(width, height, channels)): src =>
          Using.resource(Undistorter.prepare(calibration, Size(width, height))): prepared =>
            val before = PixelHash.of(src)
            val expected = src.undistorted(calibration).use(PixelHash.of)
            prepared(src).use: result =>
              assertEquals(result.size(), src.size())
              assertEquals(result.`type`(), src.`type`())
              assertEquals(PixelHash.of(result), expected)
              assertNotEquals(result.dataAddr(), src.dataAddr())
            assertEquals(PixelHash.of(src), before)
            if count == 0 then assertEquals(expected, before)
            else assertNotEquals(expected, before)

  test("higher-depth images retain exact sample values and their native type"):
    val calibration = Intrinsics(140, 145, 71.2, 61.3, coefficients.take(14))
    Using.resource(Undistorter.prepare(calibration, Size(161, 119))): prepared =>
      for cvType <- Seq(CvType.CV_16UC1, CvType.CV_16SC1, CvType.CV_32FC1, CvType.CV_64FC1) do
        Managed.scope: own =>
          val bytes = own(fixture(161, 119, 1))
          val src = own(Mat())
          bytes.convertTo(src, cvType, 71.3, -120.7)
          val expected = own.adopt(src.undistorted(calibration))
          val result = own.adopt(prepared(src))
          assertEquals(result.`type`(), cvType)
          assertEquals(org.opencv.core.Core.norm(result, expected, org.opencv.core.Core.NORM_INF), 0.0)

  test("non-contiguous input is borrowed without treating parent padding as pixels"):
    Managed.scope: own =>
      val parent = own(fixture(181, 137))
      val src = own(parent.submat(7, 126, 9, 170))
      assert(!src.isContinuous())
      val calibration = Intrinsics(140, 145, 71.2, 61.3, coefficients.take(5))
      Using.resource(Undistorter.prepare(calibration, Size(161, 119))): prepared =>
        val expected = src.undistorted(calibration).use(PixelHash.of)
        assertEquals(prepared(src).use(PixelHash.of), expected)

  test("repeated calls read the new frame and return independently owned outputs"):
    Managed.use(fixture(161, 119)): src =>
      val calibration = Intrinsics(140, 145, 71.2, 61.3, coefficients.take(5))
      Using.resource(Undistorter.prepare(calibration, Size(161, 119))): prepared =>
        prepared(src).use: first =>
          val firstHash = PixelHash.of(first)
          src.setTo(org.opencv.core.Scalar(113, 57, 201))
          val expected = src.undistorted(calibration).use(PixelHash.of)
          assertNotEquals(expected, firstHash)
          prepared(src).use: second =>
            assertEquals(PixelHash.of(second), expected)
            assertNotEquals(second.dataAddr(), first.dataAddr())
          assertEquals(PixelHash.of(first), firstHash)

  test("invalid prepared dimensions fail before allocating maps"):
    val calibration = Intrinsics(100, 100, 50, 50)
    for size <- Seq(
        Size(0, 100),
        Size(100, 0),
        Size(100.5, 100),
        Size(100, 100.5),
        Size(32767, 1),
        Size(1, 32767),
        Size(Int.MaxValue.toDouble, 1)
      )
    do
      intercept[IllegalArgumentException]:
        Using.resource(Undistorter.prepare(calibration, size))(_ => ())

  test("wrong frame dimensions and empty input are rejected without consuming the source"):
    val calibration = Intrinsics(100, 100, 50, 50)
    Using.resource(Undistorter.prepare(calibration, Size(100, 80))): prepared =>
      for (width, height) <- Seq(99 -> 80, 100 -> 79, 80 -> 100) do
        Managed.use(fixture(width, height)): src =>
          val before = PixelHash.of(src)
          intercept[IllegalArgumentException](prepared(src).close())
          assertEquals(PixelHash.of(src), before)
      Managed.use(Mat()): empty =>
        intercept[IllegalArgumentException](prepared(empty).close())

  // Observe real native buffers without widening the public API with mutable map accessors.
  private def mapsOf(prepared: Undistorter): Seq[Mat] =
    val fields = classOf[Undistorter].getDeclaredFields.filter(_.getType == classOf[Managed[?]])
    assertEquals(fields.length, 2)
    fields.toSeq.map: field =>
      field.setAccessible(true)
      field.get(prepared).asInstanceOf[Managed[Mat]].get

  test("close releases both real map buffers and preserves already-returned images"):
    Managed.use(fixture(161, 119)): src =>
      val prepared = Undistorter.prepare(Intrinsics(140, 145, 71.2, 61.3), Size(161, 119))
      try
        val maps = mapsOf(prepared)
        assert(maps.forall(!_.empty()))
        prepared(src).use: result =>
          val expected = PixelHash.of(result)
          prepared.close()
          prepared.close()
          assert(maps.forall(_.empty()))
          assertEquals(PixelHash.of(result), expected)
          assert(!src.empty())
          intercept[IllegalStateException](prepared(src).close())
      finally prepared.close()

  test("scope-body failure still releases the native map buffers"):
    val prepared = Undistorter.prepare(Intrinsics(140, 145, 71.2, 61.3), Size(161, 119))
    try
      val maps = mapsOf(prepared)
      val failure = new RuntimeException("consumer failed")
      val thrown = intercept[RuntimeException]:
        Using.resource(prepared)(_ => throw failure)
      assert(thrown eq failure)
      assert(maps.forall(_.empty()))
      Managed.use(Mat()): empty =>
        intercept[IllegalStateException](prepared(empty).close())
    finally prepared.close()

  for nativeFailure <- Seq(false, true) do
    test(s"partial preparation releases maps, matrices and stripe views (native failure: $nativeFailure)"):
      val acquired = scala.collection.mutable.ArrayBuffer.empty[Mat]
      val sentinel = new IllegalStateException("second stripe acquisition failed")
      var primary: Throwable = sentinel
      val calibration = Intrinsics(140, 145, 71.2, 61.3, coefficients.take(5))
      try
        val thrown = intercept[RuntimeException]:
          Using.resource(Undistorter.prepareObserved(calibration, Size(161, 119)): mat =>
            acquired += mat
            // Two maps, four camera matrices, then two views per stripe. The first stripe
            // has run real initUndistortRectifyMap and closed its views before we fail.
            if acquired.size == 10 then
              assert(acquired.take(6).forall(!_.empty()))
              assert(acquired.slice(6, 8).forall(_.empty()))
              assert(acquired.takeRight(2).forall(!_.empty()))
              assertEquals(acquired(0).`type`(), CvType.CV_16SC2)
              assertEquals(acquired(1).`type`(), CvType.CV_16UC1)
              if nativeFailure then
                // A real JNI bounds failure, not a fabricated CvException. The failing
                // call is inside fillMaps' intermediate and current-stripe scopes.
                primary = intercept[org.opencv.core.CvException]:
                  mat.submat(-1, 1, 0, 1).release()
              throw primary)(_ => ())
        assertEquals(acquired.size, 10)
        if nativeFailure then
          assert(thrown.isInstanceOf[CvError.NativeCall])
          assert(thrown.getCause eq primary)
          assert(thrown.getMessage.contains("prepare undistort"))
        else assert(thrown eq primary)
        assert(acquired.take(2).forall(_.empty()), "both construction-owned map buffers must be released")
        assert(acquired.slice(2, 6).forall(_.empty()), "all intermediate camera matrices must be released")
        assert(acquired.drop(6).forall(_.empty()), "completed and failing stripe views must be released")
      // Keep even a leaking rollback mutant from stranding the real native test buffers.
      finally acquired.reverseIterator.foreach(_.release())

  test("a failed native application does not poison the prepared maps"):
    val calibration = Intrinsics(100, 100, 50, 50, coefficients.take(5))
    Using.resource(Undistorter.prepare(calibration, Size(100, 80))): prepared =>
      Managed.use(Mat(80, 100, CvType.CV_8SC1)): unsupported =>
        intercept[CvError.NativeCall](prepared(unsupported).close())
        assert(!unsupported.empty())
      Managed.use(fixture(100, 80)): src =>
        assertEquals(prepared(src).use(PixelHash.of), src.undistorted(calibration).use(PixelHash.of))
