package scalacv.bench

import scala.util.Using

import org.opencv.core.Core

import scalacv.*

/** Measures explicit undistortion-map reuse, including its one-off preparation cost separately. All timed
  * frame calls allocate and release their own output, just like the one-shot baseline. Hash comparison is
  * outside the timed loop and aborts the benchmark on any pixel difference.
  *
  * Run: `./mill benchmarks.runMain scalacv.bench.UndistortBench`
  */
object UndistortBench:

  def main(args: Array[String]): Unit =
    OpenCv.load()
    val threads = Core.getNumThreads()
    // Avoid measuring native worker scheduling instead of the removed calibration/map construction.
    // This setting belongs to the standalone benchmark, never to Undistorter itself.
    Core.setNumThreads(1)
    try
      println(s"OpenCV ${Core.VERSION}; native threads=${Core.getNumThreads()}; warmup=200; samples=800")
      for (width, height) <- Seq(640 -> 480, 1920 -> 1080) do
        val size = Size(width, height)
        val calibration = Intrinsics(
          width * 0.83,
          width * 0.81,
          width * 0.47,
          height * 0.53,
          Vector(-0.21, 0.04, 0.003, -0.002, 0.01)
        )
        Managed.use(BenchImages.scene(width, height)): src =>
          Using.resource(Undistorter.prepare(calibration, size)): prepared =>
            val baselineHash = src.undistorted(calibration).use(PixelHash.of)
            val preparedHash = prepared(src).use(PixelHash.of)
            require(baselineHash == preparedHash, s"undistort output differs at ${width}x$height")
            println(
              s"${width}x$height baseline=$baselineHash prepared=$preparedHash maps=${width.toLong * height * 6} bytes"
            )
            val preparation =
              Bench.measure(s"${width}x$height prepare+close", warmup = 100, iterations = 300):
                Using.resource(Undistorter.prepare(calibration, size))(p =>
                  Bench.blackhole(p.size.width.toInt)
                )
            val baseline = Bench.measure(s"${width}x$height one-shot", warmup = 200, iterations = 800):
              src.undistorted(calibration).use(m => Bench.blackhole(m.get(0, 0)(0).toLong))
            val reused = Bench.measure(s"${width}x$height prepared", warmup = 200, iterations = 800):
              prepared(src).use(m => Bench.blackhole(m.get(0, 0)(0).toLong))
            Bench.report(s"undistort ${width}x$height", Seq(preparation, baseline, reused))
            val saved = baseline.meanNs - reused.meanNs
            println(
              f"speedup=${baseline.meanNs / reused.meanNs}%.2fx; frame latency reduction=${100 * saved / baseline.meanNs}%.1f%%"
            )
            if saved > 0 then
              println(f"prepare+close amortized after ${math.ceil(preparation.meanNs / saved)}%.0f frames")
            else println("No steady-state win in this run; do not claim an optimization win.")
    finally Core.setNumThreads(threads)
