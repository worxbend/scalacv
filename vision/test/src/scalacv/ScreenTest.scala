package scalacv

import scalacv.vision.*

import org.opencv.core.{CvType, Mat}
import org.opencv.core as cv

/** Screen analysis: template matching and change detection on synthetic screenshots. */
class ScreenTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  /** A grey "screen" with a distinctive white square (with a dark outline, so it has contrast to match on).
    */
  private def screenWithSquareAt(x: Int, y: Int): Image =
    Image
      .blank(120, 120, Scalar(50, 50, 50))
      .drawRect(Rect(x, y, 20, 20), Scalar.White, Thickness.Filled)
      .drawRect(Rect(x, y, 20, 20), Scalar.Black, Thickness.Stroke(2))

  test("locate finds a template at its true position with a high score"):
    val screen = screenWithSquareAt(40, 30)
    // Template = a crop that includes the square and a little grey border, so it has variance to correlate on.
    val template = screen.copy.crop(Rect(36, 26, 28, 28))
    try
      Screen.locate(screen, template) match
        case None => fail("the template should be found in the image it came from")
        case Some(m) =>
          assert(math.abs(m.location.x - 36) <= 3, s"x off: ${m.location}")
          assert(math.abs(m.location.y - 26) <= 3, s"y off: ${m.location}")
          assert(m.score > 0.9, s"a self-match should score near 1, got ${m.score}")
    finally
      screen.close()
      template.close()

  test("a template larger than the image is rejected"):
    val screen = Image.blank(30, 30)
    val template = Image.blank(50, 50)
    try intercept[IllegalArgumentException](Screen.locate(screen, template))
    finally
      screen.close()
      template.close()

  test("diff reports the region that changed between two captures"):
    val before = Image.blank(120, 120, Scalar(50, 50, 50))
    val after = screenWithSquareAt(60, 40) // same grey, plus a square
    try
      val changed = Screen.diff(before, after, minArea = 50)
      assert(changed.nonEmpty, "the added square should show up as a changed region")
      val r = changed.head
      // the change should sit around the square at (60, 40)
      assert(r.x <= 70 && r.x + r.width >= 60 && r.y <= 50 && r.y + r.height >= 40, s"unexpected region $r")
    finally
      before.close()
      after.close()

  test("diff on same-size-only inputs rejects a mismatch"):
    val a = Image.blank(40, 40)
    val b = Image.blank(50, 50)
    try intercept[IllegalArgumentException](Screen.diff(a, b))
    finally
      a.close()
      b.close()

  test("diff rejects same-size captures whose depth differs"):
    // absdiff would fail natively on CV_8U vs CV_32F; the check belongs up front, as an IAE.
    val a = Image.blank(40, 40)
    val b = Image.wrap(Managed(Mat(40, 40, CvType.CV_32FC3, cv.Scalar.all(0))))
    try intercept[IllegalArgumentException](Screen.diff(a, b))
    finally
      a.close()
      b.close()

  test("diff rejects same-size captures whose channel counts differ"):
    val a = Image.blank(40, 40)
    val b = Image.blank(40, 40, Scalar.Black, channels = 1)
    try intercept[IllegalArgumentException](Screen.diff(a, b))
    finally
      a.close()
      b.close()

  /** Two pixel-identical squares on one grey screen, so both correlation peaks score the same. */
  private def screenWithTwoSquares(): Image =
    Image
      .blank(160, 120, Scalar(50, 50, 50))
      .drawRect(Rect(20, 20, 20, 20), Scalar.White, Thickness.Filled)
      .drawRect(Rect(20, 20, 20, 20), Scalar.Black, Thickness.Stroke(2))
      .drawRect(Rect(110, 70, 20, 20), Scalar.White, Thickness.Filled)
      .drawRect(Rect(110, 70, 20, 20), Scalar.Black, Thickness.Stroke(2))

  test("findAll reports each of two identical squares exactly once, non-overlapping and best first"):
    val screen = screenWithTwoSquares()
    val template = screen.copy.crop(Rect(16, 16, 28, 28))
    try
      val matches = Screen.findAll(screen, template, minScore = 0.8)
      assertEquals(matches.size, 2, s"one hit per square, got $matches")
      val byPosition = matches.sortBy(m => (m.location.y, m.location.x))
      assert(math.abs(byPosition(0).location.x - 16) <= 3, s"first square off: ${byPosition(0)}")
      assert(math.abs(byPosition(0).location.y - 16) <= 3, s"first square off: ${byPosition(0)}")
      assert(math.abs(byPosition(1).location.x - 106) <= 3, s"second square off: ${byPosition(1)}")
      assert(math.abs(byPosition(1).location.y - 66) <= 3, s"second square off: ${byPosition(1)}")
      assertEquals(ObjectTracker.iou(matches(0).location, matches(1).location), 0.0)
      assert(matches(0).score >= matches(1).score, s"not best first: $matches")
    finally
      screen.close()
      template.close()

  test("maxMatches caps findAll and locate is its head"):
    val screen = screenWithTwoSquares()
    val template = screen.copy.crop(Rect(16, 16, 28, 28))
    try
      val capped = Screen.findAll(screen, template, maxMatches = 1)
      assertEquals(capped.size, 1)
      assertEquals(capped, Screen.locate(screen, template).toSeq)
    finally
      screen.close()
      template.close()

  test("a maxMatches below one is rejected"):
    val screen = screenWithTwoSquares()
    val template = screen.copy.crop(Rect(16, 16, 28, 28))
    try intercept[IllegalArgumentException](Screen.findAll(screen, template, maxMatches = 0))
    finally
      screen.close()
      template.close()

  test("a template the size of the image yields exactly one hit at the origin with score near 1"):
    // Non-zero variance, so the normalised correlation is defined; the score map is a single cell, which
    // forces the suppression rectangle to clamp to it.
    val screen = Image
      .blank(40, 40, Scalar(50, 50, 50))
      .drawRect(Rect(10, 10, 20, 20), Scalar.White, Thickness.Filled)
      .drawRect(Rect(10, 10, 20, 20), Scalar.Black, Thickness.Stroke(2))
    val template = screen.copy
    try
      val hits = Screen.findAll(screen, template, maxMatches = 20)
      assertEquals(hits.size, 1)
      assertEquals(hits.head.location, Rect(0, 0, 40, 40))
      assert(math.abs(hits.head.score - 1.0) < 1e-3, s"a self-match should score 1, got ${hits.head.score}")
    finally
      screen.close()
      template.close()

  test("locate answers None for the colour-inverted template and Some once minScore is -1"):
    val screen = screenWithSquareAt(40, 30)
    // The exact 255 - x negative of the crop at (36, 26): it correlates at about -1 on the square and never
    // reaches a confident positive score anywhere else.
    val inverted = Image
      .blank(28, 28, Scalar(205, 205, 205))
      .drawRect(Rect(4, 4, 20, 20), Scalar.Black, Thickness.Filled)
      .drawRect(Rect(4, 4, 20, 20), Scalar.White, Thickness.Stroke(2))
    try
      assertEquals(Screen.locate(screen, inverted), None)
      val anything = Screen.locate(screen, inverted, minScore = -1.0)
      assert(anything.isDefined, "with the floor at -1 the best peak is always reported")
      assert(anything.get.score < 0.8, s"the negative must not pass the default floor: ${anything.get}")
      assertEquals(screen.width, 120)
      assertEquals(inverted.width, 28)
    finally
      screen.close()
      inverted.close()

  test(
    "diff returns nothing for identical frames, orders regions largest first and drops blobs under minArea"
  ):
    val before = Image.blank(120, 120, Scalar(50, 50, 50))
    // The 3x3 speck dilates to at most 7x7 = 49 px, under the default minArea of 100; the 20x20 square to at
    // most 24x24.
    val after = before.copy
      .drawRect(Rect(10, 10, 20, 20), Scalar.White, Thickness.Filled)
      .drawRect(Rect(80, 80, 3, 3), Scalar.White, Thickness.Filled)
    try
      assertEquals(Screen.diff(before, before), Seq.empty)
      val regions = Screen.diff(before, after, minArea = 1)
      assertEquals(regions.size, 2, s"square and speck, got $regions")
      val square = regions(0)
      val speck = regions(1)
      assert(square.area > speck.area, s"largest first: $regions")
      assert(square.x <= 10 && square.y <= 10, s"square region $square")
      assert(square.x + square.width >= 30 && square.y + square.height >= 30, s"square region $square")
      assert(square.width <= 26 && square.height <= 26, s"square region $square")
      assert(speck.x <= 80 && speck.x + speck.width >= 83, s"speck region $speck")
      assertEquals(Screen.diff(before, after).size, 1, "the default minArea drops the speck")
      assertEquals(before.width, 120)
    finally
      before.close()
      after.close()

  test("diff handles single-channel captures"):
    val before = Image.blank(120, 120, Scalar(50), channels = 1)
    val after = before.copy
      .drawRect(Rect(10, 10, 20, 20), Scalar.White, Thickness.Filled)
      .drawRect(Rect(80, 80, 3, 3), Scalar.White, Thickness.Filled)
    try
      assertEquals(Screen.diff(before, after, minArea = 1).size, 2)
      assertEquals(before.width, 120)
    finally
      before.close()
      after.close()
