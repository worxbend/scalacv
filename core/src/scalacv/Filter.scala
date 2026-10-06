package scalacv

/** A named, composable photo filter — an `Image => Image` transform you can name, chain, and apply.
  *
  * The catalog in the companion is a set of ready-made "looks" built from the [[Image]] tone, colour, and
  * stylisation operations; each is a `Filter` you apply with `image.filter(Filter.vintage)` or compose with
  * `andThen`. Because a filter is just a named transform, your own are first-class:
  * `Filter("mine")(_.gamma(1.2).saturate(1.3))`.
  *
  * Like every [[Image]] transform, applying a filter **consumes** the image and returns a new one.
  */
final class Filter(val name: String, private val run: Image => Image):

  /** Applies the filter to `image` (consuming it). */
  def apply(image: Image): Image = run(image)

  /** This filter, then `next`.
    *
    * The composed name keeps both halves for a pair (`"sepia+sharpen"`), but a longer chain collapses to
    * `first+…+last` — an unbounded `a+b+c+…` grows with every composition and stops being a usable label. The
    * name is a display string for logs and pickers, not a serialisation; a filter whose own name contains `+`
    * is read as already-composed here, which is one reason to not do that.
    */
  def andThen(next: Filter): Filter =
    val combined =
      val firstPlus = name.indexOf('+')
      if firstPlus < 0 then s"$name+${next.name}"
      else s"${name.substring(0, firstPlus)}+…+${next.name}"
    new Filter(combined, image => next(run(image)))

  override def toString: String = s"Filter($name)"

object Filter:

  /** Names an `Image => Image` transform as a filter. */
  def apply(name: String)(run: Image => Image): Filter = new Filter(name, run)

  val grayscale: Filter = Filter("grayscale")(_.saturate(0)) // 3-channel grey, so filters still chain
  val sepia: Filter = Filter("sepia")(_.sepia)
  val invert: Filter = Filter("invert")(_.invert)
  val warm: Filter = Filter("warm")(_.temperature(0.5))
  val cool: Filter = Filter("cool")(_.temperature(-0.5))
  val vivid: Filter = Filter("vivid")(_.saturate(1.5).enhance())
  val muted: Filter = Filter("muted")(_.saturate(0.6))
  val noir: Filter = Filter("noir")(_.saturate(0).adjust(contrast = 1.3).gamma(0.9))
  val vintage: Filter = Filter("vintage")(_.sepia.saturate(0.85).gamma(0.9))
  val cartoon: Filter = Filter("cartoon")(_.stylize())
  val sketch: Filter = Filter("sketch")(_.sketch())
  val posterize: Filter = Filter("posterize")(_.posterize(6))
  val emboss: Filter = Filter("emboss")(_.emboss)
  val softBlur: Filter = Filter("softBlur")(_.blur(3))
  val sharpen: Filter = Filter("sharpen")(_.sharpen())
  val heatmap: Filter = Filter("heatmap")(_.gray.colorMap(Colormap.Inferno))
  val dramatic: Filter = Filter("dramatic")(_.enhance(strength = 20).saturate(1.3))

  /** Every built-in filter — handy for a contact sheet or a picker. */
  val all: Seq[Filter] =
    Seq(
      grayscale,
      sepia,
      invert,
      warm,
      cool,
      vivid,
      muted,
      noir,
      vintage,
      cartoon,
      sketch,
      posterize,
      emboss,
      softBlur,
      sharpen,
      heatmap,
      dramatic
    )
