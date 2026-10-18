package scalacv.bench

import java.awt.image.BufferedImage

import scalacv.*

/** Benchmarks [[Image.fromBufferedImage]] (i.e. `Interop.toMat`) — the path for pulling an AWT/Swing/ ImageIO
  * frame into OpenCV. The common source type is `TYPE_3BYTE_BGR` (what `toBufferedImage` emits, and what many
  * ImageIO JPEG reads produce); a `TYPE_INT_ARGB` source exercises the general redraw path.
  *
  * Run: `./mill benchmarks.runMain scalacv.bench.ToMatBench`
  */
object ToMatBench:

  private def bgr(w: Int, h: Int): BufferedImage =
    val bi = BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR)
    val g = bi.getGraphics
    try
      g.setColor(java.awt.Color(30, 60, 200))
      g.fillRect(0, 0, w, h)
      g.setColor(java.awt.Color(200, 150, 100))
      g.drawLine(0, 0, w, h)
    finally g.dispose()
    bi

  private def argb(w: Int, h: Int): BufferedImage =
    val src = bgr(w, h)
    val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = out.getGraphics
    try g.drawImage(src, 0, 0, null)
    finally g.dispose()
    out

  def main(args: Array[String]): Unit =
    OpenCv.load()

    val sizes = Seq(640 -> 480, 1920 -> 1080, 3840 -> 2160)

    // Correctness: hash the resulting Mat so before/after is provably pixel-exact.
    println("=== fromBufferedImage output hashes (must be identical before/after) ===")
    for (w, h) <- sizes; (name, mk) <- Seq("3BYTE_BGR" -> (bgr(_, _)), "INT_ARGB" -> (argb(_, _))) do
      val img = Image.fromBufferedImage(mk(w, h))
      try println(f"${w}x$h%-9s $name%-10s hash=${PixelHash.of(img.mat)}%d")
      finally img.close()

    val results =
      for
        (w, h) <- sizes
        (name, mk) <- Seq[(String, (Int, Int) => BufferedImage)]("3BYTE_BGR" -> bgr, "INT_ARGB" -> argb)
      yield
        val src = mk(w, h)
        val iters = if w >= 3840 then 500 else if w >= 1920 then 2000 else 8000
        Bench.measure(f"fromBufferedImage ${w}x$h $name", warmup = iters / 3, iterations = iters):
          val img = Image.fromBufferedImage(src)
          Bench.blackhole(img.width)
          img.close()

    Bench.report("fromBufferedImage", results)
