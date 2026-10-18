package scalacv.bench

import org.opencv.core.{Mat, Size as CvSize}
import org.opencv.imgproc.Imgproc

import scalacv.*

/** Sizes the per-frame destination-allocation cost the report flags as headroom #1. Runs the canonical
  * `gray -> blur -> canny` preprocessing chain two ways on the same frame, per "frame":
  *
  *   - `chain` — the pure API: every stage allocates a fresh destination Mat (Mats.chain / Ops), the current
  *     per-frame behaviour;
  *   - `reuse` — three preallocated destination Mats reused across frames (OpenCV's `create` is a no-op when
  *     dims/type match), i.e. what an opt-in scratch-arena pipeline would do.
  *
  * Both produce bit-identical output (verified by hash), so the delta is exactly the cost of the per-frame
  * allocation + free. This decides whether the arena feature is worth its surface.
  *
  * Run: `./mill benchmarks.runMain scalacv.bench.ArenaReuseBench`
  */
object ArenaReuseBench:

  def main(args: Array[String]): Unit =
    OpenCv.load()

    val sizes = Seq(640 -> 480, 1920 -> 1080, 3840 -> 2160)
    val k = CvSize(5, 5)

    def chainOut(frame: Mat): Managed[Mat] =
      Mats.chain(frame)(
        _.cvtColor(ColorConversion.BgrToGray),
        _.gaussianBlur(Size(5, 5)),
        _.canny(50, 150)
      )

    println("=== gray->blur->canny output hashes (chain vs reuse; must match) ===")
    for (w, h) <- sizes do
      val frame = BenchImages.scene(w, h)
      val g = Mat(); val b = Mat(); val e = Mat()
      try
        val chained = chainOut(frame)
        Imgproc.cvtColor(frame, g, Imgproc.COLOR_BGR2GRAY)
        Imgproc.GaussianBlur(g, b, k, 0)
        Imgproc.Canny(b, e, 50, 150)
        try println(f"${w}x$h%-9s chain=${PixelHash.of(chained.get)}  reuse=${PixelHash.of(e)}")
        finally chained.release()
      finally { frame.release(); g.release(); b.release(); e.release() }

    val results =
      for (w, h) <- sizes yield
        val frame = BenchImages.scene(w, h)
        val iters = if w >= 3840 then 300 else if w >= 1920 then 1200 else 4000
        // Fresh-allocation path (current pure API).
        val alloc = Bench.measure(f"chain(alloc) ${w}x$h", warmup = iters / 3, iterations = iters):
          chainOut(frame).release()
        // Reused-destination path (what an arena would do): allocate the three dsts once, reuse them.
        val g = Mat(); val b = Mat(); val e = Mat()
        val reuse = Bench.measure(f"reuse(arena) ${w}x$h", warmup = iters / 3, iterations = iters):
          Imgproc.cvtColor(frame, g, Imgproc.COLOR_BGR2GRAY)
          Imgproc.GaussianBlur(g, b, k, 0)
          Imgproc.Canny(b, e, 50, 150)
          Bench.blackhole(e.rows)
        g.release(); b.release(); e.release(); frame.release()
        (alloc, reuse)

    Bench.report("per-frame allocation cost (headroom #1)", results.flatMap(t => Seq(t._1, t._2)))
