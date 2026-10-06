package scalacv.graphs

import scalacv.*

/** A colour with an alpha channel — the palette for [[Picture]] graphics.
  *
  * Doodle-inspired: colours are values you build and transform (`.lighten`, `.fadeOut`, `.blend`) rather than
  * raw BGR triples. Stored as RGBA in `[0, 255]`; the renderer converts to OpenCV's BGR at the boundary, and
  * `alpha` drives per-shape transparency when a picture is drawn over an image.
  */
final case class Color(red: Int, green: Int, blue: Int, alpha: Int = 255):
  require(
    red >= 0 && red <= 255 && green >= 0 && green <= 255 && blue >= 0 && blue <= 255 && alpha >= 0 && alpha <= 255,
    s"colour channels must be in [0, 255], got ($red, $green, $blue, $alpha)"
  )

  /** True when fully opaque. */
  def opaque: Boolean = alpha >= 255

  /** The same colour at a new alpha. */
  def withAlpha(a: Int): Color = copy(alpha = clamp(a))

  /** Reduces the alpha by `amount` in `[0, 1]` — `fadeOut(0.5)` is half as opaque. */
  def fadeOut(amount: Double): Color = withAlpha((alpha * (1 - amount)).round.toInt)

  /** Mixes `amount` (`[0, 1]`) of `other` into this colour. */
  def blend(other: Color, amount: Double): Color =
    val t = math.max(0.0, math.min(1.0, amount))
    Color(
      mix(red, other.red, t),
      mix(green, other.green, t),
      mix(blue, other.blue, t),
      mix(alpha, other.alpha, t)
    )

  /** Toward white. */
  def lighten(amount: Double): Color = blend(Color.White, amount)

  /** Toward black. */
  def darken(amount: Double): Color = blend(Color.Black, amount)

  /** Rotates the hue by `degrees` around the colour wheel, keeping saturation, lightness and alpha. */
  def spin(degrees: Double): Color =
    val (h, s, l) = hsl
    Color.hsl(h + degrees, s, l, alpha)

  /** The colour opposite on the wheel — [[spin]] by 180°. */
  def complement: Color = spin(180)

  /** More vivid: pushes saturation up by `amount` in `[0, 1]`. */
  def saturate(amount: Double): Color =
    val (h, s, l) = hsl
    Color.hsl(h, s + (1 - s) * clampUnit(amount), l, alpha)

  /** Less vivid: pulls saturation down by `amount` in `[0, 1]`. `desaturate(1)` is grey. */
  def desaturate(amount: Double): Color =
    val (h, s, l) = hsl
    Color.hsl(h, s * (1 - clampUnit(amount)), l, alpha)

  /** This colour's hue (degrees), saturation and lightness (`[0, 1]`). */
  def hsl: (Double, Double, Double) =
    val r = red / 255.0
    val g = green / 255.0
    val b = blue / 255.0
    val max = math.max(r, math.max(g, b))
    val min = math.min(r, math.min(g, b))
    val l = (max + min) / 2
    if max == min then (0.0, 0.0, l)
    else
      val d = max - min
      val s = if l > 0.5 then d / (2 - max - min) else d / (max + min)
      val h =
        if max == r then (g - b) / d + (if g < b then 6 else 0)
        else if max == g then (b - r) / d + 2
        else (r - g) / d + 4
      (h * 60, s, l)

  /** This colour as an OpenCV [[Scalar]] **in BGR channel order** — the bridge from the RGBA [[Picture]]
    * palette to the BGR values the `Image` drawing verbs and the `org.opencv.*` ops take. The name says the
    * order because getting it wrong compiles fine and paints red as blue. The alpha is dropped (a `Scalar`'s
    * fourth channel is not an alpha the drawing verbs honour); pre-blend with [[fadeOut]]/[[blend]] if you
    * need it baked in.
    */
  def toBgrScalar: Scalar = Scalar(blue.toDouble, green.toDouble, red.toDouble)

  // Internal shorthand for the graphics layer's own call sites; same value as [[toBgrScalar]].
  private[scalacv] def toBgr: Scalar = toBgrScalar

  private def clamp(v: Int): Int = math.max(0, math.min(255, v))
  private def clampUnit(v: Double): Double = math.max(0.0, math.min(1.0, v))
  private def mix(a: Int, b: Int, t: Double): Int = (a + (b - a) * t).round.toInt

object Color:

  def rgb(red: Int, green: Int, blue: Int): Color = Color(red, green, blue)
  def rgba(red: Int, green: Int, blue: Int, alpha: Int): Color = Color(red, green, blue, alpha)

  /** A shade of grey. */
  def gray(value: Int): Color = Color(value, value, value)

  /** From hue (degrees), saturation and lightness (`[0, 1]`) — the natural space for generating palettes. */
  def hsl(hue: Double, saturation: Double, lightness: Double, alpha: Int = 255): Color =
    val h = ((hue % 360) + 360) % 360 / 360.0
    val s = math.max(0.0, math.min(1.0, saturation))
    val l = math.max(0.0, math.min(1.0, lightness))
    if s == 0 then gray((l * 255).round.toInt).withAlpha(alpha)
    else
      val q = if l < 0.5 then l * (1 + s) else l + s - l * s
      val p = 2 * l - q
      def channel(t0: Double): Int =
        val t = (t0 % 1 + 1) % 1
        val v =
          if t < 1.0 / 6 then p + (q - p) * 6 * t
          else if t < 1.0 / 2 then q
          else if t < 2.0 / 3 then p + (q - p) * (2.0 / 3 - t) * 6
          else p
        (v * 255).round.toInt
      Color(channel(h + 1.0 / 3), channel(h), channel(h - 1.0 / 3), alpha)

  /** A categorical palette: `n` colours spaced evenly around the hue wheel — distinct labels for distinct
    * series, tracks or classes.
    */
  def wheel(n: Int, saturation: Double = 0.65, lightness: Double = 0.55): Seq[Color] =
    require(n >= 0, s"a palette cannot have a negative size, got $n")
    (0 until n).map(i => hsl(360.0 * i / math.max(1, n), saturation, lightness))

  /** A sequential palette: `n` colours blended evenly from `from` to `to` — the honest choice for ordered
    * data (a heat scale, a gradient fill).
    */
  def ramp(from: Color, to: Color, n: Int): Seq[Color] =
    require(n >= 0, s"a palette cannot have a negative size, got $n")
    if n <= 1 then Seq(from).take(n)
    else (0 until n).map(i => from.blend(to, i.toDouble / (n - 1)))

  /** A ready-made set of eight distinct hues — a sensible default for categorical charts. */
  val categorical: Seq[Color] = wheel(8)

  val Transparent: Color = Color(0, 0, 0, 0)
  val Black: Color = Color(0, 0, 0)
  val White: Color = Color(255, 255, 255)
  val Gray: Color = Color(128, 128, 128)
  val LightGray: Color = Color(200, 200, 200)
  val DarkGray: Color = Color(64, 64, 64)
  val Red: Color = Color(220, 40, 40)
  val Green: Color = Color(40, 200, 80)
  val Blue: Color = Color(50, 110, 230)
  val Yellow: Color = Color(240, 210, 50)
  val Orange: Color = Color(240, 150, 40)
  val Purple: Color = Color(150, 80, 200)
  val Pink: Color = Color(240, 120, 170)
  val Cyan: Color = Color(40, 200, 220)
  val Magenta: Color = Color(220, 60, 200)

/** The reverse of [[Color.toBgrScalar]]: read an OpenCV BGR [[Scalar]] into the RGBA [[Color]] palette, for
  * handing an OpenCV colour to the [[Picture]] graphics layer. Channels are rounded and clamped to
  * `[0, 255]`; the result is fully opaque (a `Scalar`'s fourth channel is not a reliable alpha, so it is not
  * carried over). An extension rather than a method on `Scalar` so the core `Scalar` type stays free of any
  * dependency on this graphics-layer palette.
  */
extension (self: Scalar)
  def toColor: Color =
    def channel(v: Double): Int = math.max(0, math.min(255, v.round.toInt))
    Color(red = channel(self.v2), green = channel(self.v1), blue = channel(self.v0))
