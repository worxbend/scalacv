package scalacv.bench

import scalacv.*
import scalacv.graphs.*

/** Batch layout versus repeated beside/above, plus session metric reuse versus uncached Hershey metrics.
  * Bounds equality and full-scene pixel hashes are asserted outside timing. Layout timings include a final
  * bounds traversal; metric timings model a warm session with repeated labels, not unique-text workloads.
  *
  * Run: `./mill benchmarks.runMain scalacv.bench.GraphicsCachingBench`
  */
object GraphicsCachingBench:
  private def fold(layout: PictureLayout, items: Seq[Picture], horizontal: Boolean): Picture =
    items.foldLeft(Picture.empty) { (a, b) =>
      if horizontal then layout.beside(a, b) else layout.above(a, b)
    }

  private def batch(layout: PictureLayout, items: Seq[Picture], horizontal: Boolean): Picture =
    if horizontal then layout.row(items) else layout.column(items)

  private def pixelHash(layout: PictureLayout, picture: Picture): Long =
    val b = layout.bounds(picture).get
    val image =
      picture.translate(8 - b.minX, 8 - b.minY).render(b.width.ceil.toInt + 17, b.height.ceil.toInt + 17)
    try PixelHash.of(image.mat)
    finally image.close()

  private def reportDelta(baseline: Bench.Result, optimized: Bench.Result): Unit =
    val reduction = 100 * (1 - optimized.meanNs / baseline.meanNs)
    val separate = optimized.meanNs + optimized.ci95Ns < baseline.meanNs - baseline.ci95Ns
    println(f"${optimized.name}: reduction=$reduction%.1f%%, non-overlapping 95%% CIs=$separate")

  def main(args: Array[String]): Unit =
    OpenCv.load()
    val plain = new PictureLayout(TextMeasurer.openCv)
    val cache = new TextMetricCache(TextMeasurer.openCv, maxEntries = 64, maxChars = 1024)
    val cached = new PictureLayout(cache)
    val scenes = Seq(
      "shapes" -> (0 until 200).map(i => Picture.circle(Point(20, 20), 5 + i % 7).fillColor(Color.Green)),
      "text" -> (0 until 200).map(i => Picture.text(s"label-${i % 16}", Point(20, 20)).fontScale(0.5))
    )
    val results = Vector.newBuilder[Bench.Result]
    for (kind, items) <- scenes; horizontal <- Seq(true, false) do
      val axis = if horizontal then "row" else "column"
      val reference = fold(plain, items, horizontal)
      val optimized = batch(plain, items, horizontal)
      val memoized = batch(cached, items, horizontal)
      require(plain.bounds(reference) == plain.bounds(optimized), s"$kind $axis bounds differ")
      require(plain.bounds(reference) == cached.bounds(memoized), s"$kind $axis cached bounds differ")
      val hash = pixelHash(plain, reference)
      require(pixelHash(plain, optimized) == hash, s"$kind $axis pixels differ")
      require(pixelHash(cached, memoized) == hash, s"$kind $axis cached pixels differ")
      println(s"$kind $axis n=${items.size}: equal bounds=${plain.bounds(reference)}, pixel hash=$hash")
      val baseline = Bench.measure(s"$kind $axis fold + bounds", warmup = 150, iterations = 600):
        Bench.blackhole(plain.bounds(fold(plain, items, horizontal)).hashCode)
      val batched = Bench.measure(s"$kind $axis batch + bounds", warmup = 150, iterations = 600):
        Bench.blackhole(plain.bounds(batch(plain, items, horizontal)).hashCode)
      results += baseline
      results += batched
      reportDelta(baseline, batched)
      if kind == "text" then
        val reused = Bench.measure(s"$kind $axis batch cached + bounds", warmup = 150, iterations = 600):
          Bench.blackhole(cached.bounds(batch(cached, items, horizontal)).hashCode)
        results += reused
        reportDelta(batched, reused)

    val texts = (0 until 256).map(i => s"session-label-${i % 16}")
    cache.clear()
    texts.foreach { text =>
      require(cache.measure(text, Font.Duplex, 0.75) == TextMeasurer.openCv.measure(text, Font.Duplex, 0.75))
    }
    val uncached = Bench.measure("256 uncached text metrics", warmup = 1000, iterations = 3000):
      texts.foreach(text => Bench.blackhole(TextMeasurer.openCv.measure(text, Font.Duplex, 0.75)))
    val reused = Bench.measure("256 session-cached text metrics", warmup = 1000, iterations = 3000):
      texts.foreach(text => Bench.blackhole(cache.measure(text, Font.Duplex, 0.75)))
    results += uncached
    results += reused
    reportDelta(uncached, reused)
    println(s"Session retention: ${cache.entryCount} entries, ${cache.retainedChars} UTF-16 code units")
    cache.clear()
    require(cache.entryCount == 0 && cache.retainedChars == 0, "clear retained session text")
    Bench.report("Graphics batch layout and opt-in metric caching", results.result())
