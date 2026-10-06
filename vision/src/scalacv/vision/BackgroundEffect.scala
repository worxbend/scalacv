package scalacv.vision

import org.opencv.core.{Core, CvType, Mat, Scalar as CvScalar}
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc

import scalacv.*

/** Video-conferencing background effects — blur or replace the background behind a person.
  *
  * The **compositing** here is scalacv's and needs no model: given a foreground `mask` (white over the
  * person, black over the background), it feathers the edge and alpha-blends. The **mask** is the part a
  * model produces — run a selfie-segmentation ONNX network through [[Dnn]] and turn its output into a mask
  * with [[Segmenter]], or bring any binary mask you already have (even a colour [[Image.inRange]] key for a
  * green screen).
  *
  * The effects are extension methods on [[Image]] (defined below) — `image.blurBackground(mask)` and
  * `image.replaceBackground(mask, bg)` — so they chain like any other transform; this object holds the shared
  * compositing they delegate to.
  */
object BackgroundEffect:

  /** fg where `mask` is white, `bg` where black, feathered across the edge. `fg`, `bg` and `mask` are all
    * borrowed and must share the image size; `mask` is `CV_8UC1`. Returns a new owned `CV_8UC3` Mat.
    */
  private[scalacv] def alphaBlend(fg: Mat, bg: Mat, mask: Mat, feather: Int): Managed[Mat] =
    require(
      mask.rows == fg.rows && mask.cols == fg.cols,
      s"the mask (${mask.cols}x${mask.rows}) must match the image (${fg.cols}x${fg.rows})"
    )
    // The doc above promises a CV_8UC1 mask; a colour or float mask would get as far as the native
    // multiply/convert before failing with a raw CvException, so its shape is asserted up front.
    require(
      mask.depth() == CvType.CV_8U && mask.channels() == 1,
      s"the mask must be CV_8UC1, got ${CvType.typeToString(mask.`type`())}"
    )
    require(
      bg.rows == fg.rows && bg.cols == fg.cols,
      s"the background (${bg.cols}x${bg.rows}) must match the image (${fg.cols}x${fg.rows})"
    )
    require(feather >= 0, s"feather cannot be negative, got $feather")
    // One `orThrow` around the whole block rather than eight around the individual calls. Every step below
    // is part of one operation from a caller's point of view, and OpenCV's own message already quotes the
    // failing C++ expression; what it cannot say is which scalacv call it came from. A `CvError` raised
    // further in (by `Mats.produce` at the end) is a `CvError` already and passes through unchanged.
    Cv.orThrow("alphaBlend"):
      Managed.scope: own =>
        // A feathered soft mask, then a 3-channel float alpha in [0, 1] and its complement.
        val side = (feather * 2 + 1).toDouble
        val soft =
          own.adopt(if feather > 0 then mask.gaussianBlur(Size(side, side)) else Managed(mask.clone()))
        val alpha1 = own(Mat())
        soft.convertTo(alpha1, CvType.CV_32F, 1.0 / 255.0)
        val alpha3 = own(Mat())
        Imgproc.cvtColor(alpha1, alpha3, Imgproc.COLOR_GRAY2BGR)
        val ones = own(Mat(alpha3.size(), CvType.CV_32FC3, CvScalar.all(1.0)))
        val inv3 = own(Mat())
        Core.subtract(ones, alpha3, inv3)
        // fg*alpha + bg*(1-alpha), in float, back to 8-bit.
        val fgF = own(Mat())
        fg.convertTo(fgF, CvType.CV_32F)
        val bgF = own(Mat())
        bg.convertTo(bgF, CvType.CV_32F)
        val fgP = own(Mat())
        Core.multiply(fgF, alpha3, fgP)
        val bgP = own(Mat())
        Core.multiply(bgF, inv3, bgP)
        val sumF = own(Mat())
        Core.add(fgP, bgP, sumF)
        // The result escapes the scope alive, so it is the one Mat here that must NOT be owned by it:
        // `Mats.produce` allocates it, releases it if `convertTo` throws, and hands the caller the only
        // reference. Registering it with `own` as well would free the Mat this method returns.
        Mats.produce("alphaBlend")(sumF.convertTo(_, CvType.CV_8U))

  /** Blurs the background behind `mask`, keeping the person sharp. Borrows `image` and `mask`. */
  private[scalacv] def blur(image: Mat, mask: Mat, strength: Int, feather: Int): Managed[Mat] =
    require(strength >= 1, s"blur strength must be ≥ 1, got $strength")
    val side = (strength * 2 + 1).toDouble
    image.gaussianBlur(Size(side, side)).use(bg => alphaBlend(image, bg, mask, feather))

  /** Replaces the background behind `mask` with `background` (resized to fit). Borrows all three. */
  private[scalacv] def replace(image: Mat, mask: Mat, background: Mat, feather: Int): Managed[Mat] =
    background
      .resize(Size(image.cols.toDouble, image.rows.toDouble))
      .use(bg => alphaBlend(image, bg, mask, feather))

/** Turns a selfie-segmentation network's output into a person mask.
  *
  * The model is yours (run it through [[Dnn]] — a MODNet, U²-Net, or MediaPipe-selfie ONNX export); this
  * decodes its output tensor into the `CV_8UC1` mask that [[BackgroundEffect]] wants: white over the person,
  * black over the background. The decode is testable on a synthetic tensor, so no download is needed to prove
  * it.
  */
object Segmenter:

  /** Decodes a segmentation output tensor into a binary person mask (`255` = person), scaled to `imageSize`.
    *
    * Handles the two common shapes: `[1, 1, H, W]` (a single foreground probability plane) and `[1, 2, H, W]`
    * (background/foreground, whose **last** channel is taken as the person).
    *
    * @param threshold
    *   the probability above which a pixel is the person.
    */
  def decodeMask(output: Mat, imageSize: Size, threshold: Float = 0.5f): Image =
    val dims = output.dims
    require(dims == 3 || dims == 4, s"expected a [1,C,H,W] or [C,H,W] segmentation tensor, got $dims dims")
    val (c, h, w) =
      if dims == 4 then (output.size(1), output.size(2), output.size(3))
      else (output.size(0), output.size(1), output.size(2))
    val channel = if c >= 2 then c - 1 else 0 // last channel = foreground for a 2-class output
    Managed.use(output.reshape(1, c)): flat => // c rows x (h*w)
      val plane = Array.ofDim[Float](h * w)
      flat.get(channel, 0, plane)
      Managed.use(Mat(h, w, CvType.CV_8UC1)): small =>
        val bytes = plane.map(p => (if p >= threshold then 255 else 0).toByte)
        small.put(0, 0, bytes)
        Image.wrap(small.resize(imageSize, Interpolation.Nearest))

/** The high-level virtual-background verbs on [[Image]] — extension methods so they sit beside the
  * compositing in [[BackgroundEffect]]. `import scalacv.*` gives `image.blurBackground(mask)` and
  * `image.replaceBackground(mask, bg)`.
  */
extension (img: Image)

  /** Runs a selfie-segmentation `Net` over this image and decodes the person mask — the one-call form of blob
    * → forward → [[Segmenter.decodeMask]], ready to hand to [[blurBackground]] or [[replaceBackground]]. The
    * image is only read from (it stays alive, so you can segment then composite in two lines), and the
    * network is borrowed, not released. The returned mask is a new owned `CV_8UC1` [[Image]].
    *
    * The blob knobs mirror [[Dnn.blobFromImage]] and are model-specific; the defaults suit a MediaPipe-selfie
    * or MODNet-style export (RGB input, `[0, 1]` range). When you need the raw output tensor, drop to [[Dnn]]
    * and [[Segmenter.decodeMask]] directly.
    *
    * @param net
    *   a caller-owned segmentation network (see [[Dnn.fromOnnx]]). Stateful — one per thread.
    * @param inputSize
    *   the spatial size the network expects, e.g. `Size(256, 256)`.
    * @param threshold
    *   the foreground probability above which a pixel is the person.
    */
  def segment(
      net: Net,
      inputSize: Size,
      threshold: Float = 0.5f,
      scaleFactor: Double = 1.0 / 255,
      mean: Scalar = Scalar(0, 0, 0),
      swapRB: Boolean = true
  ): Image =
    Dnn
      .blobFromImage(img.mat, scaleFactor, Some(inputSize), mean, swapRB)
      .use: blob =>
        Dnn
          .forward(net, blob)
          .use: out =>
            Segmenter.decodeMask(out, img.size, threshold)

  /** Video-conferencing blur: keeps the person (where `mask` is white) sharp and blurs the background,
    * feathering the edge. `mask` is a borrowed `CV_8UC1` foreground mask (from [[Segmenter]] or any keying).
    */
  def blurBackground(mask: Image, strength: Int = 15, feather: Int = 7): Image =
    // The compositing runs INSIDE the try: it can throw (a size-mismatched mask trips alphaBlend's
    // require, gaussianBlur can raise a CvException), and like every Image transform this consumes the
    // receiver, so `img` must be closed on the throw path too — not only on success.
    try Image(BackgroundEffect.blur(img.mat, mask.mat, strength, feather))
    finally img.close()

  /** Replaces the background (where `mask` is black) with `background`, resized to fit and feathered at the
    * edge — a virtual background. `mask` and `background` are borrowed.
    */
  def replaceBackground(mask: Image, background: Image, feather: Int = 7): Image =
    // Inside the try for the same reason as blurBackground: the compositing can throw, and the
    // receiver is consumed either way, so close it on the throw path too.
    try Image(BackgroundEffect.replace(img.mat, mask.mat, background.mat, feather))
    finally img.close()
