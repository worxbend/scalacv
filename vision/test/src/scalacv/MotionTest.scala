package scalacv

import scalacv.vision.*

import org.opencv.core as cv
import org.opencv.core.{CvType, Mat}
import org.opencv.imgproc.Imgproc

/** [[MotionDetector]] on synthetic frames: a static grey scene with a white square that we move. No camera
  * needed — the detector is fed hand-built [[Image]]s and, for the stream path, JPEG bytes.
  */
class MotionTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  private val Width = 160
  private val Height = 120

  /** A grey `w`×`h` scene with a 20×20 white square whose left edge is at `squareX`. */
  private def frameSized(w: Int, h: Int, squareX: Int): Image =
    val m = Mat(h, w, CvType.CV_8UC3, cv.Scalar(60, 60, 60))
    Imgproc.rectangle(m, cv.Point(squareX, 40), cv.Point(squareX + 20, 60), cv.Scalar(240, 240, 240), -1)
    Image.wrap(Managed(m))

  /** A grey scene with a 20×20 white square whose left edge is at `squareX`. */
  private def frameWith(squareX: Int): Image = frameSized(Width, Height, squareX)

  /** Feeds one frame (detect borrows it) and closes it. */
  private def motionOf(d: MotionDetector, squareX: Int): Motion =
    val f = frameWith(squareX)
    try d.detect(f)
    finally f.close()

  private def jpegOf(squareX: Int): Array[Byte] =
    val f = frameWith(squareX)
    try f.bytes(".jpg").fold(e => fail(e.getMessage), identity)
    finally () // bytes() already consumed and closed f

  test("frame differencing: first frame is a baseline, a still scene stays still, a moved object is motion"):
    val d = MotionDetector.frameDifference(minArea = 50)
    try
      assert(!motionOf(d, 20).moving, "the first frame is the baseline")
      assert(!motionOf(d, 20).moving, "an unchanged scene is not motion")
      val moved = motionOf(d, 90)
      assert(moved.moving, s"a moved object should register motion, ratio=${moved.ratio}")
      assert(moved.regionCount >= 1, "the moved object should produce at least one region")
      assert(moved.largest.exists(_.area >= 50), "the largest region should clear minArea")
    finally d.close()

  test("detect decodes JPEG bytes straight off a stream"):
    val d = MotionDetector.frameDifference(minArea = 50)
    try
      assert(d.detect(jpegOf(20)).fold(e => fail(e.getMessage), !_.moving), "first JPEG is the baseline")
      val moved = d.detect(jpegOf(90)).fold(e => fail(e.getMessage), identity)
      assert(moved.moving, s"a moved object in JPEG should register motion, ratio=${moved.ratio}")
    finally d.close()

  test("detect on non-image bytes is a Left, not a throw"):
    val d = MotionDetector.frameDifference()
    try assert(d.detect(Array[Byte](1, 2, 3)).isLeft)
    finally d.close()

  test("reset drops the baseline so the next frame is fresh"):
    val d = MotionDetector.frameDifference(minArea = 50)
    try
      motionOf(d, 20) // baseline
      assert(motionOf(d, 90).moving) // motion vs the baseline
      d.reset()
      assert(!motionOf(d, 90).moving, "after reset, the next frame is a fresh baseline")
    finally d.close()

  test("background subtraction warms up on a static scene, then flags a new object"):
    val d = MotionDetector.backgroundSubtraction(minArea = 50, history = 20)
    try
      for _ <- 0 until 15 do motionOf(d, 20) // let the model learn the static scene
      val moved = motionOf(d, 95)
      assert(moved.moving, s"a new foreground object should be motion, ratio=${moved.ratio}")
    finally d.close()

  test("a size mismatch throws without wedging the detector (H1: no leak, no stale baseline)"):
    val d = MotionDetector.frameDifference(minArea = 50)
    try
      assert(!motionOf(d, 20).moving, "the first frame is the baseline")
      // A differently sized frame cannot be diffed against the baseline: absdiff throws (surfaced as the
      // boundary CvError.NativeCall), and the leaked `current` bug (H1) would have both stranded that
      // frame and left the detector wedged.
      val odd = frameSized(Width + 40, Height + 40, 20)
      try intercept[CvError](d.detect(odd))
      finally odd.close()
      // The baseline survived and nothing was left half-swapped: a correctly sized frame still detects.
      val moved = motionOf(d, 90)
      assert(moved.moving, s"after the mismatch the detector still works, ratio=${moved.ratio}")
    finally d.close()

  test("Motion.still is no motion"):
    assert(!Motion.still.moving)
    assertEquals(Motion.still.regionCount, 0)
