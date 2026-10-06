package scalacv

import scalacv.graphs.*
import scalacv.vision.*

import org.opencv.core as cv
import org.opencv.core.{CvType, Mat}
import org.opencv.imgproc.Imgproc

/** The photo-filter library: tone/colour effects, colormaps, the photo module, and the Filter catalog. */
class FiltersTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  /** A small colourful scene (needed 8-bit 3-channel for the photo/stylisation filters). */
  private def sample(): Image =
    val m = Mat(80, 100, CvType.CV_8UC3, cv.Scalar(60, 90, 140))
    Imgproc.rectangle(m, cv.Point(15, 15), cv.Point(45, 45), cv.Scalar(40, 200, 60), -1)
    Imgproc.circle(m, cv.Point(70, 40), 18, cv.Scalar(220, 80, 60), -1)
    Image.wrap(Managed(m))

  test("saturate(0) produces a grey (channels equal)"):
    val grey = Image.blank(40, 40, Scalar(0, 0, 255)).saturate(0) // pure red → grey
    try
      val p = grey.mat.get(20, 20)
      assert(math.abs(p(0) - p(1)) < 6 && math.abs(p(1) - p(2)) < 6, s"expected grey, got ${p.toList}")
    finally grey.close()

  test("temperature warms toward red and cools toward blue"):
    val warm = Image.blank(30, 30, Scalar(128, 128, 128)).temperature(0.8)
    try assert(warm.mat.get(15, 15)(2) > warm.mat.get(15, 15)(0), "warm: red > blue")
    finally warm.close()
    val cool = Image.blank(30, 30, Scalar(128, 128, 128)).temperature(-0.8)
    try assert(cool.mat.get(15, 15)(0) > cool.mat.get(15, 15)(2), "cool: blue > red")
    finally cool.close()

  test("a colormap turns a grey value into a colour"):
    val heat = Image.blank(40, 40, Scalar(200, 200, 200), channels = 1).colorMap(Colormap.Jet)
    try
      assertEquals(heat.channels, 3)
      val p = heat.mat.get(20, 20)
      assert(
        !(math.abs(p(0) - p(1)) < 6 && math.abs(p(1) - p(2)) < 6),
        s"colormap should be colourful, got ${p.toList}"
      )
    finally heat.close()

  test("posterize collapses to the requested levels"):
    val bright = Image.blank(20, 20, Scalar(200, 200, 200)).posterize(2)
    try assert(bright.mat.get(10, 10)(0) > 200, "200 → top level")
    finally bright.close()
    val dark = Image.blank(20, 20, Scalar(100, 100, 100)).posterize(2)
    try assert(dark.mat.get(10, 10)(0) < 55, "100 → bottom level")
    finally dark.close()

  test("gamma < 1 darkens the mid-tones"):
    val original = Image.blank(20, 20, Scalar(128, 128, 128))
    val darker = Image.blank(20, 20, Scalar(128, 128, 128)).gamma(0.5)
    try
      assert(
        darker.mat.get(10, 10)(0) < 128,
        s"gamma 0.5 should darken 128, got ${darker.mat.get(10, 10)(0)}"
      )
    finally
      original.close()
      darker.close()

  test("sepia and emboss run on a 3-channel image"):
    val s = sample().sepia
    try assertEquals(s.channels, 3)
    finally s.close()
    val e = sample().emboss
    try assertEquals((e.width, e.height, e.channels), (100, 80, 3))
    finally e.close()

  test("sepia, temperature, saturate and gamma reject a grey input with a named precondition"):
    // A grey (or float) input used to die inside OpenCV as CvError.NativeCall; a wrong-type input is a
    // programmer error, so each op names itself in an IllegalArgumentException before any native call.
    val calls: Seq[(String, Image => Image)] = Seq(
      "sepia" -> (_.sepia),
      "temperature" -> (_.temperature(0.5)),
      "saturate" -> (_.saturate(1.5)),
      "gamma" -> (_.gamma(2.2))
    )
    for (op, call) <- calls do
      val grey = Image.blank(20, 20, Scalar(128, 128, 128), channels = 1)
      val e = intercept[IllegalArgumentException](call(grey))
      assert(e.getMessage.contains(op), s"the failure must name $op, got: ${e.getMessage}")
      grey.close() // close is idempotent; the rejected transform already released it

  test("pencilSketchBoth returns both the colour and the grey sketch, each with its channel count"):
    val img = sample()
    val pair = img.mat.pencilSketchBoth() // borrows img's Mat; img stays alive
    try
      assertEquals(pair.colour.get.`type`(), CvType.CV_8UC3, "the colour sketch keeps 3 channels")
      assertEquals(pair.grey.get.`type`(), CvType.CV_8UC1, "the grey sketch is single-channel")
      assertEquals(pair.colour.get.size(), img.mat.size())
      assertEquals(pair.grey.get.size(), img.mat.size())
      assertNotEquals(pair.colour.get.dataAddr(), pair.grey.get.dataAddr(), "two independent outputs")
    finally
      pair.colour.release()
      pair.grey.release()
      img.close()

  test("inpaint fills a masked hole from its surroundings"):
    val holed = Image
      .blank(60, 60, Scalar(255, 255, 255))
      .drawRect(Rect(25, 25, 10, 10), Scalar.Black, Thickness.Filled)
    val mask = Image
      .blank(60, 60, Scalar.Black, channels = 1)
      .drawRect(Rect(25, 25, 10, 10), Scalar.White, Thickness.Filled)
    try
      val fixed = holed.inpaint(mask)
      try
        assert(
          fixed.mat.get(30, 30)(0) > 200,
          s"the hole should be filled white, got ${fixed.mat.get(30, 30).toList}"
        )
      finally fixed.close()
    finally mask.close()

  test("seamlessCloneInto returns a background-sized composite"):
    val obj = Image.blank(30, 30, Scalar(40, 60, 220))
    val mask = Image.blank(30, 30, Scalar.White, channels = 1)
    val background = Image.blank(120, 120, Scalar(180, 180, 180))
    try
      val out = obj.seamlessCloneInto(background, mask, Point(60, 60))
      try assertEquals((out.width, out.height), (120, 120))
      finally out.close()
    finally
      mask.close()
      background.close()

  test("every built-in filter applies without error"):
    for f <- Filter.all do
      val out = sample().filter(f)
      try assert(out.width == 100 && out.height == 80, s"filter ${f.name} changed the size unexpectedly")
      finally out.close()

  test("filters compose with andThen"):
    val combo = Filter.sepia.andThen(Filter.sharpen)
    assert(combo.name.contains("sepia") && combo.name.contains("sharpen"))
    val out = sample().filter(combo)
    try assertEquals(out.channels, 3)
    finally out.close()

  test("Filter.all contains exactly the named catalog — this assertion is the registry"):
    // `all` is a hand-maintained mirror of the companion's vals: a filter added without an entry here
    // fails silently everywhere `all` is used. Naming each one turns that omission into a test failure.
    assertEquals(
      Filter.all.map(_.name),
      Seq(
        "grayscale",
        "sepia",
        "invert",
        "warm",
        "cool",
        "vivid",
        "muted",
        "noir",
        "vintage",
        "cartoon",
        "sketch",
        "posterize",
        "emboss",
        "softBlur",
        "sharpen",
        "heatmap",
        "dramatic"
      )
    )

  test("a composed name keeps a pair in full, then collapses to first+…+last"):
    // Unbounded `a+b+c+…` grows with every composition; the cap keeps the label a label.
    assertEquals(Filter.grayscale.andThen(Filter.invert).name, "grayscale+invert")
    val four = Filter.grayscale.andThen(Filter.invert).andThen(Filter.warm).andThen(Filter.cool)
    assertEquals(four.name, "grayscale+…+cool")
