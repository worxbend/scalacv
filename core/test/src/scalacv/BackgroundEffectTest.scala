package scalacv

import scalacv.graphs.*
import scalacv.vision.*

import java.nio.file.Files

import org.opencv.core.{Core, CvType, Mat}
import org.opencv.core as cv

/** Video-conferencing background effects and the segmentation-mask decode. Verified by construction — a
  * half-and-half mask must keep the person half and change only the background half.
  */
class BackgroundEffectTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  private val W = 100
  private val H = 80

  /** An all-red BGR scene (the "person"/foreground content). */
  private def scene(): Image = Image.blank(W, H, Scalar(0, 0, 255))

  /** A mask with the left half white (person) and the right half black (background). */
  private def leftMask(): Image =
    Image
      .blank(W, H, Scalar.Black, channels = 1)
      .drawRect(Rect(0, 0, W / 2, H), Scalar.White, Thickness.Filled)

  test("replaceBackground keeps the person half and swaps the background half"):
    val img = scene()
    val mask = leftMask()
    val bg = Image.blank(W, H, Scalar(0, 255, 0)) // green
    try
      val out = img.replaceBackground(mask, bg, feather = 0) // hard edge for an exact pixel check
      try
        assertEquals((out.width, out.height, out.channels), (W, H, 3))
        val left = out.mat.get(H / 2, 20) // person side → red
        val right = out.mat.get(H / 2, W - 20) // background side → green
        assert(left(2) > 200 && left(1) < 60, s"person half should stay red, got ${left.toList}")
        assert(right(1) > 200 && right(2) < 60, s"background half should be green, got ${right.toList}")
      finally out.close()
    finally
      mask.close()
      bg.close()

  test("blurBackground preserves the image shape"):
    val img = scene()
    val mask = leftMask()
    try
      val out = img.blurBackground(mask, strength = 5, feather = 3)
      try assertEquals((out.width, out.height, out.channels), (W, H, 3))
      finally out.close()
    finally mask.close()

  test("blurBackground closes the receiver even when compositing throws (no leak on the error path)"):
    // A wrong-size mask trips alphaBlend's require. The compositing runs inside blurBackground's try,
    // so the receiver is consumed on the throw path too — before the fix it leaked, staying alive.
    val img = scene()
    val badMask = Image.blank(2, 2, Scalar.Black, channels = 1)
    try
      intercept[IllegalArgumentException](img.blurBackground(badMask))
      // The receiver was spent, so touching it now must fail rather than read a leaked Mat.
      intercept[IllegalStateException](img.width): Unit
    finally
      badMask.close()
      img.close() // idempotent; frees it if the fix regressed and it is still alive

  test("replaceBackground closes the receiver even when compositing throws"):
    val img = scene()
    val badMask = Image.blank(2, 2, Scalar.Black, channels = 1)
    val bg = Image.blank(W, H, Scalar(0, 255, 0))
    try
      intercept[IllegalArgumentException](img.replaceBackground(badMask, bg))
      intercept[IllegalStateException](img.width): Unit
    finally
      badMask.close()
      bg.close()
      img.close()

  test("a 3-channel mask is rejected as not CV_8UC1, spending the receiver either way"):
    // The alphaBlend scaladoc mandates CV_8UC1; a colour mask used to sail past the size check and die
    // later inside native code. Both verbs share the compositor, so check one of each shape.
    val colourMask = Image.blank(W, H, Scalar.White) // 3 channels, right size
    val img = scene()
    try
      intercept[IllegalArgumentException](img.blurBackground(colourMask))
      intercept[IllegalStateException](img.width): Unit
    finally
      colourMask.close()
      img.close()
    val colourMask2 = Image.blank(W, H, Scalar.White)
    val img2 = scene()
    val bg = Image.blank(W, H, Scalar(0, 255, 0))
    try
      intercept[IllegalArgumentException](img2.replaceBackground(colourMask2, bg))
      intercept[IllegalStateException](img2.width): Unit
    finally
      colourMask2.close()
      bg.close()
      img2.close()

  test("Segmenter.decodeMask thresholds a probability plane into a scaled person mask"):
    val h = 8
    val w = 10
    val tensor = Mat(Array(1, 1, h, w), CvType.CV_32F, cv.Scalar.all(0))
    val flat = tensor.reshape(1, 1) // 1 row x (h*w), shares data
    try
      val plane = Array.ofDim[Float](h * w)
      for y <- 0 until h; x <- 0 until w do plane(y * w + x) = if x < w / 2 then 0.9f else 0.1f
      flat.put(0, 0, plane)
      val mask = Segmenter.decodeMask(tensor, Size(W.toDouble, H.toDouble), threshold = 0.5f)
      try
        assertEquals((mask.width, mask.height, mask.channels), (W, H, 1))
        assert(mask.mat.get(H / 2, 20)(0) > 200, "left (p=0.9) should be foreground/white")
        assertEquals(mask.mat.get(H / 2, W - 20)(0), 0.0, "right (p=0.1) should be background/black")
      finally mask.close()
    finally
      flat.release()
      tensor.release()

  test(
    "segment runs blob → forward → decodeMask and takes the person mask from the last plane, borrowing the image"
  ):
    // Relu is the identity on a [0, 1] blob, so the mask is predictable from what was drawn: with the
    // default swapRB the blob's planes are (R, G, B), decodeMask takes the LAST of c = 3, and B/255 is 1.0
    // on the painted left half and 0.0 on the right — straddling the 0.5 threshold.
    val f = Files.createTempFile("scalacv-seg-", ".onnx")
    f.toFile.deleteOnExit() // a failed load throws before the finally below runs
    Files.write(f, TinyOnnx.relu(Seq(1, 3, 4, 6)))
    val net = Dnn.fromOnnx(f.toString).fold(e => fail(s"load failed: $e"), identity)
    val img =
      Image.blank(W, H, Scalar.Black).drawRect(Rect(0, 0, W / 2, H), Scalar(255, 0, 0), Thickness.Filled)
    try
      val mask = img.segment(net.get, Size(6, 4))
      try
        assertEquals((mask.width, mask.height, mask.channels), (W, H, 1))
        assertEquals(mask.mat.get(H / 2, 20)(0), 255.0, "the blue half is the person")
        assertEquals(mask.mat.get(H / 2, W - 20)(0), 0.0, "the black half is background")
        assertEquals(img.width, W, "segment only reads the image, so it must still be alive")
      finally mask.close()
    finally
      img.close()
      net.release()
      Files.deleteIfExists(f): Unit

  test("decodeMask on a background/foreground 2-plane tensor thresholds the last plane, not the first"):
    val h = 8
    val w = 10
    val tensor = Mat(Array(1, 2, h, w), CvType.CV_32F, cv.Scalar.all(0))
    val flat = tensor.reshape(1, 2) // 2 rows x (h*w), shares data
    try
      // Plane 0 (background) is "person" everywhere; only plane 1 carries the left/right split. If the
      // decode read plane 0 the whole mask would be white.
      val background = Array.fill(h * w)(0.9f)
      val foreground = Array.ofDim[Float](h * w)
      for y <- 0 until h; x <- 0 until w do foreground(y * w + x) = if x < w / 2 then 0.9f else 0.1f
      flat.put(0, 0, background)
      flat.put(1, 0, foreground)
      val mask = Segmenter.decodeMask(tensor, Size(W.toDouble, H.toDouble), threshold = 0.5f)
      try
        assertEquals(mask.mat.get(H / 2, 20)(0), 255.0, "left of the foreground plane is the person")
        assertEquals(mask.mat.get(H / 2, W - 20)(0), 0.0, "right of the foreground plane is background")
        val white = Core.countNonZero(mask.mat)
        assert(math.abs(white - W * H / 2) <= W * H / 20, s"expected about half the mask white, got $white")
      finally mask.close()
    finally
      flat.release()
      tensor.release()

  test("decodeMask accepts a [C,H,W] tensor and counts a probability equal to the threshold as person"):
    val h = 8
    val w = 10
    val tensor = Mat(Array(1, h, w), CvType.CV_32F, cv.Scalar.all(0))
    val flat = tensor.reshape(1, 1)
    try
      val plane = Array.ofDim[Float](h * w)
      for y <- 0 until h; x <- 0 until w do plane(y * w + x) = if x < w / 2 then 0.5f else 0.49f
      flat.put(0, 0, plane)
      val mask = Segmenter.decodeMask(tensor, Size(w.toDouble, h.toDouble), threshold = 0.5f)
      try
        assertEquals((mask.width, mask.height), (w, h))
        assertEquals(mask.mat.get(h / 2, 2)(0), 255.0, "p == threshold counts as person")
        assertEquals(mask.mat.get(h / 2, w - 3)(0), 0.0, "p just under the threshold is background")
      finally mask.close()
    finally
      flat.release()
      tensor.release()

  test("decodeMask rejects a plain 2-D Mat as not a segmentation tensor"):
    Managed.use(Mat(8, 10, CvType.CV_32F, cv.Scalar.all(0.9))): twoD =>
      intercept[IllegalArgumentException](Segmenter.decodeMask(twoD, Size(10, 8)).close()): Unit

  test("blurBackground with a hard mask leaves the person half bit-identical and blurs only the background"):
    // A black square on each side gives the blur an edge to smear on both halves: on a flat person half a
    // blur-everything implementation would leave red as red and pass, so the person half has an edge too.
    val src = scene()
      .drawRect(Rect(20, 30, 10, 10), Scalar.Black, Thickness.Filled)
      .drawRect(Rect(70, 30, 10, 10), Scalar.Black, Thickness.Filled)
    val ref = src.copy
    val mask = leftMask()
    try
      val out = src.blurBackground(mask, strength = 5, feather = 0)
      try
        // alpha is exactly 1.0 over the person, so fg*1 + bg*0 converted back to 8-bit is exact.
        Managed.use(out.mat.submat(0, H, 0, W / 2)): left =>
          Managed.use(ref.mat.submat(0, H, 0, W / 2)): refLeft =>
            assertEquals(Core.norm(left, refLeft, Core.NORM_INF), 0.0, "the person half must be untouched")
        Managed.use(out.mat.submat(0, H, W / 2, W)): right =>
          Managed.use(ref.mat.submat(0, H, W / 2, W)): refRight =>
            assert(Core.norm(right, refRight, Core.NORM_INF) > 0, "the background half must change")
        val edge = out.mat.get(35, 70)(2) // midway down the square's left edge, red smeared into black
        assert(edge > 0 && edge < 255, s"a blurred edge is neither pure red nor pure black, got $edge")
      finally out.close()
    finally
      ref.close()
      mask.close()

  test("blurBackground rejects a strength below 1 and a negative feather, spending the receiver either way"):
    val mask = leftMask()
    try
      val zeroStrength = scene()
      intercept[IllegalArgumentException](zeroStrength.blurBackground(mask, strength = 0))
      intercept[IllegalStateException](zeroStrength.width)
      val negativeFeather = scene()
      intercept[IllegalArgumentException](negativeFeather.blurBackground(mask, feather = -1))
      intercept[IllegalStateException](negativeFeather.width): Unit
    finally mask.close()

  test("replaceBackground stretches a background of another size to the frame, borrowing the background"):
    val img = scene()
    val mask = leftMask()
    val bg = Image.blank(10, 8, Scalar(0, 255, 0)) // a tenth of the frame, flat green so any resize is exact
    try
      val out = img.replaceBackground(mask, bg, feather = 0)
      try
        assertEquals((out.width, out.height, out.channels), (W, H, 3))
        val left = out.mat.get(H / 2, 20)
        val right = out.mat.get(H / 2, W - 20)
        assert(left(2) > 200, s"person half should stay red, got ${left.toList}")
        assert(right(1) > 200 && right(2) < 60, s"background half should be green, got ${right.toList}")
        assertEquals((bg.width, bg.height), (10, 8), "the background is resized into a copy, not in place")
      finally out.close()
    finally
      mask.close()
      bg.close()

  test("a BGRA image handed to blurBackground fails as a NativeCall named alphaBlend and is consumed"):
    // The mask matches in size, so the require passes and the failure is OpenCV refusing to multiply a
    // 4-channel float by the 3-channel alpha — surfaced under scalacv's operation name, not swallowed.
    val img = Image.blank(W, H, Scalar(0, 0, 255, 255), channels = 4)
    val mask = leftMask()
    try
      val e = intercept[CvError.NativeCall](img.blurBackground(mask))
      assert(e.getMessage.contains("alphaBlend"), e.getMessage)
      intercept[IllegalStateException](img.width): Unit
    finally
      mask.close()
      img.close()
