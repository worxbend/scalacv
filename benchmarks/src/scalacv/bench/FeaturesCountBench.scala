package scalacv.bench

import scala.util.Using

import org.opencv.core.Core

import scalacv.*
import scalacv.vision.*

/** Count-only loop scoring versus the sorted correspondence API. Detection is outside timing: both paths
  * repeatedly borrow the same real ORB descriptors, as loop detection does for stored keyframes. Includes the
  * default 500-feature budget and a larger 2000-feature case. No descriptor-result cache is involved.
  *
  * Run: `./mill benchmarks.runMain scalacv.bench.FeaturesCountBench`
  */
object FeaturesCountBench:

  private def scene(ox: Int, oy: Int): Image =
    val random = new scala.util.Random(7481L)
    val blocks = Vector.fill(450)(
      Rect(
        40 + random.nextInt(850) + ox,
        40 + random.nextInt(610) + oy,
        4 + random.nextInt(25),
        4 + random.nextInt(25)
      )
    )
    Image.blank(960, 720, Scalar(20, 20, 20)).drawRects(blocks, Scalar.White, Thickness.Filled)

  private def hash(counts: Seq[Int]): Long =
    counts.foldLeft(0xcbf29ce484222325L)((h, count) => (h ^ count.toLong) * 0x100000001b3L)

  private def compare(a: Descriptors, b: Descriptors, budget: Int): Unit =
    val thresholds = Seq(-1f, 0f, 16f, 32f, 64f, 128f, Float.PositiveInfinity, Float.NaN)
    val baseline = thresholds.map(Features.matches(a, b, _).size)
    val counted = thresholds.map(Features.countMatches(a, b, _))
    require(baseline == counted, s"count mismatch: $baseline != $counted")
    require(baseline(4) > 0, "the shifted scene must have matches at the default threshold")
    println(s"budget=$budget descriptors=${a.size}/${b.size} thresholds=${thresholds.mkString(",")}")
    println(s"counts=${counted.mkString(",")} equivalence=true")
    println(f"baseline-hash=0x${hash(baseline)}%016x count-hash=0x${hash(counted)}%016x")

    // Warm both paths together before timing either.
    for _ <- 0 until 500 do
      Bench.blackhole(Features.matches(a, b).size)
      Bench.blackhole(Features.countMatches(a, b))

    val iterations = if budget == 500 then 2000 else 500
    val sortedSamples = new Array[Long](iterations)
    val countSamples = new Array[Long](iterations)
    def elapsed(body: => Int): Long =
      val start = System.nanoTime()
      Bench.blackhole(body)
      System.nanoTime() - start

    // Alternate order within every pair, rather than running long blocks that confound host load drift.
    var i = 0
    while i < iterations do
      if i % 2 == 0 then
        sortedSamples(i) = elapsed(Features.matches(a, b).size)
        countSamples(i) = elapsed(Features.countMatches(a, b))
      else
        countSamples(i) = elapsed(Features.countMatches(a, b))
        sortedSamples(i) = elapsed(Features.matches(a, b).size)
      i += 1

    def result(name: String, samples: Array[Long]): Bench.Result =
      val mean = samples.iterator.map(_.toDouble).sum / samples.length
      val variance = samples.iterator.map(n => math.pow(n.toDouble - mean, 2)).sum / (samples.length - 1)
      Bench.Result(name, mean, math.sqrt(variance), samples.length)

    val sorted = result(s"matches.size n=$budget", sortedSamples)
    val count = result(s"countMatches n=$budget", countSamples)
    val difference =
      result("paired sorted - count (positive wins)", sortedSamples.zip(countSamples).map(_ - _))
    Bench.report(s"ORB count-only matching, budget=$budget (alternating pairs)", Seq(sorted, count))
    println(f"paired saving=${difference.meanMicros}%.2f µs ± ${difference.ci95Micros}%.2f (95%%)")
    println(f"count reduction=${100.0 * difference.meanNs / sorted.meanNs}%.2f%%")

    java.lang.management.ManagementFactory.getThreadMXBean match
      case bean: com.sun.management.ThreadMXBean if bean.isThreadAllocatedMemorySupported =>
        if !bean.isThreadAllocatedMemoryEnabled then bean.setThreadAllocatedMemoryEnabled(true)
        def allocated(body: => Int): Long =
          val before = bean.getCurrentThreadAllocatedBytes()
          Bench.blackhole(body)
          bean.getCurrentThreadAllocatedBytes() - before
        var sortedBytes = 0L
        var countBytes = 0L
        for _ <- 0 until 100 do
          sortedBytes += allocated(Features.matches(a, b).size)
          countBytes += allocated(Features.countMatches(a, b))
        println(
          s"JVM allocated bytes/call: matches.size=${sortedBytes / 100} countMatches=${countBytes / 100}"
        )
      case _ => println("JVM allocation counter unavailable")

  def main(args: Array[String]): Unit =
    OpenCv.load()
    val threads = Core.getNumThreads()
    // Keep native scheduling noise controlled, not hidden: both methods use the same single native thread.
    Core.setNumThreads(1)
    try
      println(s"OpenCV threads=${Core.getNumThreads()} (previous=$threads); ORB extraction excluded")
      Using
        .Manager: use =>
          val aImage = use(scene(0, 0))
          val bImage = use(scene(5, 3))
          for budget <- Seq(500, 2000) do
            Using
              .Manager: descriptors =>
                val a = descriptors(Features.detect(aImage, budget))
                val b = descriptors(Features.detect(bImage, budget))
                compare(a, b, budget)
              .get
        .get
    finally Core.setNumThreads(threads)
