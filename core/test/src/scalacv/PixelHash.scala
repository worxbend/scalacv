package scalacv

import org.opencv.core.Mat

/** The one pixel-exact content hash shared by core.test and graphs.test.
  *
  * The clone-elimination checks in PixelHashTest, the translucent ROI alpha-blend in GraphicsAlphaRoiTest and
  * the roundtrip laws in PropertyTest must fold pixels the same way. graphs.test compiles this fixture source
  * directly rather than depending on core.test's integration suites, so neither test module gains a
  * dependency on the other's tests.
  *
  * Keep this in sync with BenchImages.hash in the benchmarks module: that is a deliberate separate copy
  * because the benchmarks module is not on the test classpath.
  */
object PixelHash:

  /** FNV-1a (a small, fast, well-mixed 64-bit hash) over the raw pixel bytes.
    *
    * The bytes are read one row at a time rather than as a single block: an OpenCV `Mat` may be a view into a
    * larger buffer, in which case its rows are not adjacent in memory and a whole-buffer read would fold in
    * padding that is not part of the image.
    */
  def of(m: Mat): Long =
    val rowBytes = m.cols * m.elemSize().toInt
    val buf = new Array[Byte](rowBytes)
    var h = 0xcbf29ce484222325L
    var r = 0
    while r < m.rows do
      val _ = m.get(r, 0, buf)
      var i = 0
      while i < rowBytes do
        h = (h ^ (buf(i) & 0xffL)) * 0x100000001b3L
        i += 1
      r += 1
    h

  /** Same hash, for callers holding an [[Image]] rather than a bare `Mat`. Consumes nothing. */
  def of(img: Image): Long = of(img.mat)
