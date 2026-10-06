package scalacv

import scalacv.graphs.*
import scalacv.vision.*

/** Unit tests for the RGBA [[Color]] palette. Pure value arithmetic — no OpenCV natives — so it needs no
  * `OpenCv.load()`. Covers construction and bounds, the BGR channel-order bridge to [[Scalar]], the alpha and
  * blend transforms, HSL round-tripping, and the palette generators.
  */
class ColorTest extends munit.FunSuite:

  test("channels must be in range"):
    intercept[IllegalArgumentException](Color(-1, 0, 0))
    intercept[IllegalArgumentException](Color(0, 256, 0))
    intercept[IllegalArgumentException](Color(0, 0, 0, 300))
    Color(0, 255, 128, 40) // in range: no throw

  test("toBgrScalar maps RGBA to OpenCV BGR order and drops alpha"):
    // Color.Red is RGBA(220, 40, 40); as a BGR Scalar that is (blue=40, green=40, red=220).
    val s = Color.Red.toBgrScalar
    assertEquals((s.v0, s.v1, s.v2), (40.0, 40.0, 220.0))
    // The alpha is not carried into the Scalar's fourth channel.
    assertEquals(Color(10, 20, 30, 128).toBgrScalar, Scalar(30.0, 20.0, 10.0))

  test("Scalar.toColor is the inverse of toBgrScalar and comes back opaque"):
    val c = Color(17, 200, 99)
    assertEquals(c.toBgrScalar.toColor, c)
    // A BGR scalar reads back with channels swapped into RGB.
    assertEquals(Scalar(30.0, 20.0, 10.0).toColor, Color(10, 20, 30, 255))

  test("alpha helpers"):
    assert(Color.White.opaque)
    assert(!Color.White.withAlpha(200).opaque)
    assertEquals(Color.White.withAlpha(400).alpha, 255) // clamped
    assertEquals(Color.White.withAlpha(-5).alpha, 0) // clamped
    assertEquals(Color(0, 0, 0, 200).fadeOut(0.5).alpha, 100)
    assertEquals(Color.Transparent.alpha, 0)

  test("blend interpolates each channel and clamps the amount"):
    val a = Color(0, 0, 0, 0)
    val b = Color(100, 200, 40, 200)
    assertEquals(a.blend(b, 0.0), a)
    assertEquals(a.blend(b, 1.0), b)
    assertEquals(a.blend(b, 0.5), Color(50, 100, 20, 100))
    assertEquals(a.blend(b, -1.0), a) // amount clamped to [0, 1]
    assertEquals(a.blend(b, 2.0), b)

  test("lighten and darken move toward white and black"):
    assertEquals(Color.Gray.lighten(1.0), Color.White.withAlpha(255))
    assertEquals(Color.Gray.darken(1.0), Color.Black)
    val g = Color(128, 128, 128)
    assert(g.lighten(0.5).red > g.red, "lighten should raise the channels")
    assert(g.darken(0.5).red < g.red, "darken should lower the channels")

  test("hsl of a grey has zero saturation; a primary reports its hue"):
    val (_, sGrey, lGrey) = Color(128, 128, 128).hsl
    assertEqualsDouble(sGrey, 0.0, 1e-9)
    assertEqualsDouble(lGrey, 128 / 255.0, 1e-9)
    val (hRed, sRed, _) = Color(255, 0, 0).hsl
    assertEqualsDouble(hRed, 0.0, 1e-9)
    assertEqualsDouble(sRed, 1.0, 1e-9)
    val (hGreen, _, _) = Color(0, 255, 0).hsl
    assertEqualsDouble(hGreen, 120.0, 1e-9)

  test("Color.hsl round-trips through .hsl"):
    for
      hue <- Seq(0.0, 45.0, 120.0, 210.0, 300.0)
      sat <- Seq(0.3, 0.7, 1.0)
      light <- Seq(0.25, 0.5, 0.75)
    do
      val (h, s, l) = Color.hsl(hue, sat, light).hsl
      assertEqualsDouble(h, hue, 1.0, s"hue for ($hue,$sat,$light)")
      assertEqualsDouble(s, sat, 0.02, s"saturation for ($hue,$sat,$light)")
      assertEqualsDouble(l, light, 0.01, s"lightness for ($hue,$sat,$light)")

  test("hsl normalises a wrapped or negative hue"):
    assertEquals(Color.hsl(360 + 40, 0.5, 0.5), Color.hsl(40, 0.5, 0.5))
    assertEquals(Color.hsl(-320, 0.5, 0.5), Color.hsl(40, 0.5, 0.5))

  test("spin by 360 is a no-op and complement is spin by 180"):
    val c = Color(200, 40, 40)
    val spun = c.spin(360)
    // Round-trip through HSL can shift a channel by one unit; allow it.
    assert(math.abs(spun.red - c.red) <= 1 && math.abs(spun.green - c.green) <= 1)
    assertEquals(c.complement, c.spin(180))

  test("saturate raises and desaturate lowers HSL saturation; desaturate(1) is grey"):
    val c = Color.hsl(200, 0.5, 0.5)
    assert(c.saturate(0.5).hsl._2 > c.hsl._2)
    assert(c.desaturate(0.5).hsl._2 < c.hsl._2)
    assertEqualsDouble(c.desaturate(1.0).hsl._2, 0.0, 1e-9)

  test("wheel produces n evenly spaced hues"):
    assertEquals(Color.wheel(0), Seq.empty)
    assertEquals(Color.wheel(5).size, 5)
    intercept[IllegalArgumentException](Color.wheel(-1))
    assertEquals(Color.categorical.size, 8)
    // Distinct hues: no two of the eight categorical colours are equal.
    assertEquals(Color.categorical.distinct.size, 8)

  test("ramp blends evenly from end to end"):
    intercept[IllegalArgumentException](Color.ramp(Color.Black, Color.White, -1))
    assertEquals(Color.ramp(Color.Black, Color.White, 0), Seq.empty)
    assertEquals(Color.ramp(Color.Black, Color.White, 1), Seq(Color.Black))
    val r = Color.ramp(Color.Black, Color.White, 3)
    assertEquals(r.head, Color.Black)
    assertEquals(r.last, Color.White)
    assertEquals(r(1), Color(128, 128, 128, 255))

  test("gray builds an equal-channel colour"):
    assertEquals(Color.gray(80), Color(80, 80, 80))
    assertEquals(Color.rgba(1, 2, 3, 4), Color(1, 2, 3, 4))
