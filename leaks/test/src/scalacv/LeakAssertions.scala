package scalacv

import org.bytedeco.javacpp.Pointer

/** RSS-based leak assertions.
  *
  * Phase 6 of the memory audit proved that JavaCPP's `Pointer.totalBytes()` is **blind** to scalacv's memory:
  * a deliberate ~1.4 GB `org.opencv.core.Mat` leak moved it by 0 bytes, because those buffers are allocated
  * by OpenCV's own JNI (`cv::fastMalloc`) rather than through JavaCPP's tracked `Pointer` allocators. So
  * `totalBytes()` — and the `-Dorg.bytedeco.javacpp.maxBytes` budget derived from it — cannot gate a scalacv
  * leak. The only signal that sees these buffers is **process RSS**, read from `/proc/self/statm` on Linux
  * and falling back to `Pointer.physicalBytes()` (itself RSS-based) elsewhere.
  *
  * These assertions must run where their suite is the **only** one in the JVM (the dedicated `leaks` module),
  * because RSS is process-global and would otherwise be contaminated by concurrently-running suites.
  */
object LeakAssertions:

  /** Linux page size; overridable for exotic kernels via `SCALACV_PAGE_BYTES`. 4 KiB on x86_64/arm64. */
  private val pageBytes: Long =
    sys.env.get("SCALACV_PAGE_BYTES").flatMap(_.toLongOption).getOrElse(4096L)

  /** Resident set size in bytes: `/proc/self/statm` field 2 (resident pages) × page size on Linux, else
    * `Pointer.physicalBytes()`.
    */
  def rssBytes(): Long =
    val statm = java.nio.file.Path.of("/proc/self/statm")
    if java.nio.file.Files.isReadable(statm) then
      val fields = String(java.nio.file.Files.readAllBytes(statm)).trim.split("\\s+")
      fields(1).toLong * pageBytes
    else Pointer.physicalBytes()

  /** GC, drain JavaCPP's deallocation queue, GC again — settle native memory before a measurement. */
  private def settle(): Unit =
    System.gc(); Thread.sleep(150)
    Pointer.deallocateReferences()
    System.gc(); Thread.sleep(150)

  /** Runs `workload` `warmup` times (amortising one-time init/JIT/arena warmup), then `n` measured times, and
    * asserts RSS grew by less than `toleranceMB`. The tolerance is bounded-not-zero on purpose: RSS never
    * returns exactly to baseline (allocator arenas, code cache). A per-iteration native leak of even a modest
    * Mat clears this bound over a few hundred iterations, while a leak-free workload stays flat.
    *
    * `leakBytesPerIteration` is the size of the **smallest** native buffer the workload would leak per
    * iteration if the path under test regressed — the figure the test's comment derives. It is required, not
    * defaulted, because a gate is only a gate when `n × leak` clears the tolerance: the original
    * blurBackground workload leaked 320×240×3 × 200 = 43.9 MiB against a 48 MiB tolerance and passed *while
    * leaking*. The `require` refuses any configuration whose full-blown leak would not reach three times the
    * tolerance, so a test cannot quietly be tuned into vacuity.
    */
  def assertBounded(
      name: String,
      n: Int = 300,
      warmup: Int = 40,
      toleranceMB: Long = 48,
      leakBytesPerIteration: Long
  )(workload: () => Unit): Unit =
    val leakBytes = n.toLong * leakBytesPerIteration
    val floorBytes = 3L * toleranceMB * 1024L * 1024L
    require(
      leakBytes >= floorBytes,
      s"$name: a full leak of $leakBytesPerIteration bytes × $n iterations is only " +
        s"${leakBytes / (1024L * 1024L)} MiB, below 3 × the $toleranceMB MiB tolerance " +
        s"(${floorBytes / (1024L * 1024L)} MiB) — this configuration could pass while leaking; raise n or " +
        "the workload size"
    )
    var i = 0
    while i < warmup do
      workload(); i += 1
    settle()
    val before = rssBytes()
    i = 0
    while i < n do
      workload(); i += 1
    settle()
    val grewMB = (rssBytes() - before) / (1024L * 1024L)
    assert(
      grewMB <= toleranceMB,
      s"$name: RSS grew ${grewMB}MB over $n iterations (tolerance ${toleranceMB}MB) — likely a native leak. " +
        "Note: Pointer.totalBytes() is blind to org.opencv.core.Mat, so RSS is the signal here."
    )
