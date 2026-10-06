package scalacv

import scalacv.graphs.*
import scalacv.vision.*

import org.opencv.core.{CvType, Mat, Scalar as CvScalar}
import org.opencv.imgproc.Imgproc

/** The CI half of the project's perf rule — "no optimization without a bit-identical output hash" — for the
  * clone-elimination benchmarks. `GrayBlurCloneBench` removes the throwaway clone from `Motion.prepare`'s
  * already-grey + blur branch and blurs the borrowed frame directly, but it only *prints* the two pixel
  * hashes for a human to eyeball. Here they are asserted, so a change that makes the output differ — or makes
  * `gaussianBlur` destructive, which is what the removed clone was guarding against — fails the build.
  *
  * (The other bit-exact optimization, the translucent ROI alpha-blend, is gated by [[GraphicsAlphaRoiTest]].)
  */
class PixelHashTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  /** A deterministic single-channel grey scene — no image assets ship, so it is drawn. */
  private def grayScene(w: Int, h: Int): Mat =
    val m = Mat(h, w, CvType.CV_8UC1, CvScalar(40))
    var i = 0
    while i < 12 do
      val x = (i * 61) % w
      val y = (i * 27) % h
      Imgproc.rectangle(
        m,
        org.opencv.core.Point(x, y),
        org.opencv.core.Point(x + 40, y + 30),
        CvScalar((i * 47) % 256),
        -1
      )
      i += 1
    m

  test("gaussianBlur is bit-identical on the borrowed frame and a clone, and never mutates its source"):
    for (w, h) <- Seq((640, 480), (320, 240)) do
      val g = grayScene(w, h)
      val cloneMat = g.clone()
      try
        val before = PixelHash.of(g)
        val direct = g.gaussianBlur(Size(5, 5))
        val viaClone = cloneMat.gaussianBlur(Size(5, 5))
        try
          assertEquals(
            PixelHash.of(direct.get),
            PixelHash.of(viaClone.get),
            s"${w}x$h: blur output differs between the borrowed frame and its clone"
          )
          assertEquals(
            PixelHash.of(g),
            before,
            s"${w}x$h: gaussianBlur must not mutate its borrowed source — the removed clone was its safety net"
          )
        finally
          direct.release()
          viaClone.release()
      finally
        g.release()
        cloneMat.release()
