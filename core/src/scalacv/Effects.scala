package scalacv

import org.opencv.core.{Core, CvType, Mat}
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo

/* The social-media effects: tone, stylisation and look-altering filters as extension methods on `Mat`.
 *
 * These follow the ownership contract declared in Ops.scala to the letter — every op allocates a fresh
 * destination, never writes to or releases its receiver, and hands back a `Managed[Mat]` the caller owns.
 * Most of them share one input contract, the 8-bit 3-channel image, enforced up front by
 * [[Mats.require8Bit3Channel]] so a grey or float input fails as a named programmer error rather than as a
 * `CvError.NativeCall` from inside `org.opencv.photo`.
 */

/** The two outputs of `Mat.pencilSketchBoth`: the [[colour]] sketch (3-channel) and the [[grey]] one
  * (single-channel). A named pair rather than a tuple for the same reason [[Thresholded]] is one — the field
  * names, not positions, say which plate is which. Both Mats are owned by the receiver of the pair and must
  * be released independently.
  */
final case class PencilSketchPair(colour: Managed[Mat], grey: Managed[Mat])

extension (self: Mat)

  /** Stylisation — a smooth, painterly cartoon look via edge-aware smoothing. Needs 8-bit 3-channel input.
    */
  def stylize(strength: Float = 60, detail: Float = 0.45f): Managed[Mat] =
    Mats.require8Bit3Channel("stylize", self)
    Mats.produce("stylization")(Photo.stylization(self, _, strength, detail))

  /** A colour pencil-sketch rendering. Needs 8-bit 3-channel input. The greyscale sketch OpenCV also computes
    * is discarded — see [[pencilSketchBoth]] if you want it.
    */
  def pencilSketch(strength: Float = 60, detail: Float = 0.07f, shade: Float = 0.02f): Managed[Mat] =
    val both = pencilSketchBoth(strength, detail, shade)
    both.grey.release()
    both.colour

  /** Both halves of a pencil sketch: the colour rendering **and** the greyscale one OpenCV computes on the
    * way — the colour plate and the grey plate of the same drawing. [[pencilSketch]] pays for the grey output
    * and throws it away (the native call always fills both destinations); this overload exists for callers
    * that want the grey sketch too, so the work is never wasted. Needs 8-bit 3-channel input.
    *
    * Both Mats in the returned pair are owned by the caller and must be released independently.
    */
  def pencilSketchBoth(
      strength: Float = 60,
      detail: Float = 0.07f,
      shade: Float = 0.02f
  ): PencilSketchPair =
    Mats.require8Bit3Channel("pencilSketchBoth", self)
    // Not `Mats.produce` — there are two destinations here, so the ownership contract (release everything
    // on failure) is spelled out by hand.
    val grey = Mat()
    val colour = Mat()
    try
      Cv.orThrow("pencilSketch")(Photo.pencilSketch(self, grey, colour, strength, detail, shade))
      PencilSketchPair(Managed(colour), Managed(grey))
    catch
      case e: Throwable =>
        grey.release()
        colour.release()
        throw e

  /** Detail enhancement — boosts local contrast and texture. Needs 8-bit 3-channel input. */
  def detailEnhance(strength: Float = 10, detail: Float = 0.15f): Managed[Mat] =
    Mats.require8Bit3Channel("detailEnhance", self)
    Mats.produce("detailEnhance")(Photo.detailEnhance(self, _, strength, detail))

  /** Edge-preserving smoothing — flattens texture while keeping edges (the basis of the painterly filters).
    * Needs 8-bit 3-channel input.
    */
  def edgePreserving(strength: Float = 60, detail: Float = 0.4f): Managed[Mat] =
    Mats.require8Bit3Channel("edgePreserving", self)
    Mats.produce("edgePreservingFilter")(
      Photo.edgePreservingFilter(self, _, Photo.RECURS_FILTER, strength, detail)
    )

  /** Sepia tone, via a colour matrix. Needs 8-bit 3-channel input. */
  def sepia: Managed[Mat] =
    Mats.require8Bit3Channel("sepia", self)
    Managed.use(Mat(3, 3, CvType.CV_32F)): m =>
      m.put(0, 0, 0.131, 0.534, 0.272, 0.168, 0.686, 0.349, 0.189, 0.769, 0.393)
      Mats.produce("sepia")(Core.transform(self, _, m))

  /** Gamma correction: `g` < 1 darkens the mid-tones, `g` > 1 lifts them. Needs 8-bit 3-channel input — the
    * same contract as its stylisation neighbours, enforced up front rather than as a `CvError.NativeCall`
    * from `Core.LUT` on a float image.
    */
  def gamma(g: Double): Managed[Mat] =
    require(g > 0, s"gamma must be positive, got $g")
    Mats.require8Bit3Channel("gamma", self)
    lut(Array.tabulate(256)(i => math.round(math.pow(i / 255.0, 1.0 / g) * 255).toInt.min(255).max(0)))

  /** Posterises to `levels` tones per channel. */
  def posterize(levels: Int): Managed[Mat] =
    require(levels >= 2 && levels <= 256, s"levels must be in [2, 256], got $levels")
    val step = 255.0 / (levels - 1)
    lut(Array.tabulate(256)(i => (math.round(i / step) * step).round.toInt.min(255)))

  /** Emboss, via a directional convolution. */
  def emboss: Managed[Mat] =
    Managed.use(Mat(3, 3, CvType.CV_32F)): k =>
      k.put(0, 0, -2.0, -1.0, 0.0, -1.0, 1.0, 1.0, 0.0, 1.0, 2.0)
      Mats.produce("emboss")(Imgproc.filter2D(self, _, OutputDepth.SameAsSource.cvValue, k))

  /** Adjusts saturation: `factor` > 1 is more vivid, `< 1` toward grey, `0` fully grey (still 3-channel).
    * Needs 8-bit 3-channel input.
    */
  def saturate(factor: Double): Managed[Mat] =
    require(factor >= 0, s"saturation factor cannot be negative, got $factor")
    Mats.require8Bit3Channel("saturate", self)
    self
      .cvtColor(ColorConversion.BgrToGray)
      .pipe(_.cvtColor(ColorConversion.GrayToBgr))
      .use(grey => self.addWeighted(factor, grey, 1 - factor))

  /** Colour temperature: `shift` > 0 warms (more red), `< 0` cools (more blue), in `[-1, 1]`. Needs 8-bit
    * 3-channel input.
    */
  def temperature(shift: Double): Managed[Mat] =
    require(shift >= -1 && shift <= 1, s"temperature shift must be in [-1, 1], got $shift")
    Mats.require8Bit3Channel("temperature", self)
    Managed.use(Mat(3, 3, CvType.CV_32F)): m =>
      m.put(0, 0, 1 - 0.3 * shift, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1 + 0.3 * shift)
      Mats.produce("temperature")(Core.transform(self, _, m))

  /** A 256-entry lookup table applied to every channel — the engine behind [[gamma]] and [[posterize]]. */
  private def lut(table: Array[Int]): Managed[Mat] =
    Managed.use(Mat(1, 256, CvType.CV_8UC1)): lookup =>
      lookup.put(0, 0, table.map(_.toByte))
      Mats.produce("LUT")(Core.LUT(self, lookup, _))
