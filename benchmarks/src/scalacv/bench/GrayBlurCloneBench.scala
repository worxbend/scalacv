package scalacv.bench

import scalacv.*

/** Isolates the wasted clone in `Motion.prepare`'s already-grey + blur branch. The old shape cloned the
  * borrowed grey frame and then blurred the clone (the blur allocates its own destination and only borrows,
  * so the clone was freed unused); the new shape blurs the borrowed frame directly.
  *
  * Both produce bit-identical output — verified by pixel hash — so this measures exactly the cost of the
  * removed clone on the per-frame motion path.
  *
  * Run: `./mill benchmarks.runMain scalacv.bench.GrayBlurCloneBench`
  */
object GrayBlurCloneBench:

  def main(args: Array[String]): Unit =
    OpenCv.load()

    val sizes = Seq(640 -> 480, 1920 -> 1080)
    val side = Size(5, 5) // blurRadius = 2

    println("=== gray+blur output hashes (must be identical for both shapes) ===")
    for (w, h) <- sizes do
      val g = BenchImages.gray(w, h)
      try
        val cloned = Managed(g.clone()).pipe(_.gaussianBlur(side))
        val direct = g.gaussianBlur(side)
        try
          println(
            f"${w}x$h%-9s cloned=${PixelHash.of(cloned.get)}  direct=${PixelHash.of(direct.get)}"
          )
        finally { cloned.release(); direct.release() }
      finally g.release()

    val results =
      for
        (w, h) <- sizes
        (label, run) <- Seq[(String, org.opencv.core.Mat => Unit)](
          "clone+blur (old)" -> (g => Managed(g.clone()).pipe(_.gaussianBlur(side)).release()),
          "blur direct (new)" -> (g => g.gaussianBlur(side).release())
        )
      yield
        val g = BenchImages.gray(w, h)
        val iters = if w >= 1920 then 3000 else 8000
        val r = Bench.measure(f"grayblur ${w}x$h $label", warmup = iters / 3, iterations = iters):
          run(g)
          Bench.blackhole(Bench.sink)
        g.release()
        r

    Bench.report("gray+blur clone elimination", results)
