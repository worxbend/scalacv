package scalacv

import scalacv.graphs.*
import scalacv.vision.*

import org.opencv.core.{CvType, Mat}

/** Native-leak regression gate. Each test drives a workload a few hundred times and asserts process RSS stays
  * bounded — the signal that sees `org.opencv.core.Mat` buffers (see [[LeakAssertions]]). This suite owns its
  * JVM (the `leaks` module), so RSS is not contaminated by other suites.
  *
  * These complement the deterministic contract tests in `core.test` (which assert a consumed handle throws on
  * reuse): those prove *a* handle was released; these prove memory does not accumulate at scale.
  */
class LeakTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  test("the high-level Image pipeline holds one Mat and does not accumulate"):
    LeakAssertions.assertBounded("Image pipeline"): () =>
      Image
        .blank(640, 480, Scalar.White)
        .gray
        .blur(2)
        .canny(80, 160)
        .bytes(".png")
        .fold(throw _, _ => ())

  test("blurBackground on the error path frees the receiver — no leak per failed call (F1)"):
    // A size-mismatched mask makes every call throw; the fix frees the receiver on that path. A
    // regression would leak a 320x240x3 Mat (~230 KB) per call, ~46 MB over 200 — well past tolerance.
    LeakAssertions.assertBounded("blurBackground error path", n = 200): () =>
      val img = Image.blank(320, 240, Scalar(0, 0, 255))
      val badMask = Image.blank(2, 2, Scalar.Black, channels = 1)
      try
        try img.blurBackground(badMask).close() // .close() only reached if it wrongly succeeds
        catch case _: IllegalArgumentException => () // expected; the fix already freed img
      finally badMask.close()

  test("undistort with a fresh Intrinsics per call does not leak the camera/dist matrices (F/§3.4)"):
    // Intrinsics.cameraMatrix/distCoeffs allocate a fresh Mat each call; undistort must free both.
    val intr = Intrinsics(fx = 500, fy = 500, cx = 320, cy = 240, distortion = Seq(0.1, -0.05, 0, 0))
    LeakAssertions.assertBounded("undistort", n = 300): () =>
      Image.blank(200, 200, Scalar.White).undistort(intr).close()

  test("the zio Scope mapping releases like any other owner — no accumulation over repeated scopes"):
    // scalacv.zio.acquireRelease routes release through the same Releasable as Managed; a regression in
    // the Scope mapping would leak a 640x480x3 Mat (~920 KB) per scope, far past tolerance.
    import _root_.zio.*
    import scalacv.zio.acquireRelease
    LeakAssertions.assertBounded("zio scoped Mats", n = 200): () =>
      Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe
          .run(ZIO.scoped(acquireRelease(Mat(640, 480, CvType.CV_8UC3))))
          .getOrThrowFiberFailure(): Unit
      }
