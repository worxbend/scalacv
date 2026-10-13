package scalacv

import scalacv.graphs.*

class GraphicsCachingTest extends munit.FunSuite:
  private class CountingMeasurer extends TextMeasurer:
    var calls = 0
    var width = 10.0
    def measure(text: String, font: Font, scale: Double): TextMetrics =
      calls += 1
      TextMetrics(Size(text.length * width * scale, 8 * scale), 2)

  test("session metrics reuse exact keys and clear releases retained text and invalidates results"):
    val backend = new CountingMeasurer
    val cache = new TextMetricCache(backend, maxEntries = 2, maxChars = 8)
    val first = cache.measure("ab", Font.Simplex, 1)
    assertEquals(cache.measure("ab", Font.Simplex, 1), first)
    assertEquals(backend.calls, 1)
    assertEquals(cache.entryCount, 1)
    assertEquals(cache.retainedChars, 2L)
    backend.width = 20
    cache.clear()
    assertEquals(cache.entryCount, 0)
    assertEquals(cache.retainedChars, 0L)
    assertEquals(cache.measure("ab", Font.Simplex, 1).size.width, 40.0)
    assertEquals(backend.calls, 2)

  test("row uses 2n-1 measurements and matches the centres and tight packing of beside"):
    val backend = new CountingMeasurer
    val layout = new PictureLayout(backend)
    val pictures = (1 to 12).map(i => Picture.text("x" * i, Point(i, i)).fontScale(1))
    val row = layout.row(pictures, gap = 3)
    assertEquals(backend.calls, 2 * pictures.size - 1)
    val expected = pictures.foldLeft(Picture.empty)((a, b) => layout.beside(a, b, gap = 3))
    assertEquals(layout.bounds(row), layout.bounds(expected))

  test("column uses 2n-1 measurements and matches above"):
    val backend = new CountingMeasurer
    val layout = new PictureLayout(backend)
    val pictures = (1 to 12).map(i => Picture.text("x" * i, Point(i, i)).fontScale(i))
    val column = layout.column(pictures, gap = 5)
    assertEquals(backend.calls, 2 * pictures.size - 1)
    val expected = pictures.foldLeft(Picture.empty)((a, b) => layout.above(a, b, gap = 5))
    assertEquals(layout.bounds(column), layout.bounds(expected))

  test("entry capacity uses LRU access order, not insertion order"):
    val backend = new CountingMeasurer
    val cache = new TextMetricCache(backend, maxEntries = 2, maxChars = 100)
    def get(s: String) = cache.measure(s, Font.Simplex, 1)
    get("a")
    get("b")
    get("a")
    get("c")
    get("a")
    assertEquals(backend.calls, 3)
    assertEquals(cache.entryCount, 2)
    get("b")
    assertEquals(backend.calls, 4)

  test("character budget counts UTF-16 per key and oversized text bypasses without eviction"):
    val backend = new CountingMeasurer
    val cache = new TextMetricCache(backend, maxEntries = 10, maxChars = 4)
    cache.measure("\ud83d\ude80", Font.Simplex, 1)
    cache.measure("ab", Font.Simplex, 1)
    assertEquals(cache.retainedChars, 4L)
    cache.measure("ab", Font.Duplex, 1)
    assertEquals(cache.entryCount, 2)
    assertEquals(cache.retainedChars, 4L)
    cache.measure("oversized", Font.Simplex, 1)
    cache.measure("oversized", Font.Simplex, 1)
    cache.measure("ab", Font.Simplex, 1)
    assertEquals(backend.calls, 5)
    assertEquals(cache.retainedChars, 4L)
    cache.measure("\ud83d\ude80", Font.Simplex, 1)
    assertEquals(backend.calls, 6)

  test("zero limits disable retention and negative capacities are rejected"):
    for (entries, chars) <- Seq((0, 10), (10, 0)) do
      val backend = new CountingMeasurer
      val cache = new TextMetricCache(backend, entries, chars)
      cache.measure("", Font.Simplex, 1)
      cache.measure("", Font.Simplex, 1)
      assertEquals(backend.calls, 2)
      assertEquals(cache.entryCount, 0)
      assertEquals(cache.retainedChars, 0L)
    intercept[IllegalArgumentException](new TextMetricCache(new CountingMeasurer, -1, 1))
    intercept[IllegalArgumentException](new TextMetricCache(new CountingMeasurer, 1, -1))

  test("text, font, exact effective scale and backend sessions are distinct keys"):
    val backend = new CountingMeasurer
    val cache = new TextMetricCache(backend)
    cache.measure("a", Font.Simplex, 1)
    cache.measure("b", Font.Simplex, 1)
    cache.measure("a", Font.Duplex, 1)
    cache.measure("a", Font.Simplex, math.nextUp(1.0))
    assertEquals(backend.calls, 4)
    val layout = new PictureLayout(cache)
    layout.bounds(Picture.text("a", Point(0, 0)).fontScale(0.5).scale(2).rotate(90))
    assertEquals(backend.calls, 4)
    val other = new CountingMeasurer
    other.width = 99
    assertEquals(new TextMetricCache(other).measure("a", Font.Simplex, 1).size.width, 99.0)
    assertEquals(cache.measure("a", Font.Simplex, 1).size.width, 10.0)

  test("backend failures are retried, not retained"):
    var calls = 0
    val cache = new TextMetricCache(new TextMeasurer:
      def measure(text: String, font: Font, scale: Double): TextMetrics =
        calls += 1
        if calls == 1 then throw new IllegalStateException("measurement failed")
        TextMetrics(Size(12, 8), 2))
    intercept[IllegalStateException](cache.measure("a", Font.Simplex, 1))
    assertEquals(cache.entryCount, 0)
    assertEquals(cache.retainedChars, 0L)
    assertEquals(cache.measure("a", Font.Simplex, 1), TextMetrics(Size(12, 8), 2))
    assertEquals(calls, 2)

  test("one cache is shared by label, repeated renderer layouts and drawing measurement"):
    val backend = new CountingMeasurer
    val cache = new TextMetricCache(backend)
    val renderer = new Renderer:
      val textMeasurer: TextMeasurer = cache
      protected def draw(primitive: RenderPrimitive, style: PictureStyle): Unit = primitive match
        case RenderPrimitive.Text(text, _) =>
          cache.measure(text, style.font, style.fontScale)
          ()
        case _ => ()
    val label = renderer.layout.label("session", Point(2, 3))
    val first = renderer.layout.bounds(label)
    assertEquals(renderer.layout.bounds(label), first)
    renderer.render(label)
    assertEquals(backend.calls, 1)
    cache.clear()
    renderer.render(label)
    assertEquals(backend.calls, 2)
    assert(!OpenCvRenderer.textMeasurer.isInstanceOf[TextMetricCache])

  test("concurrent callers coalesce a key without racing the backend"):
    val backend = new CountingMeasurer
    val cache = new TextMetricCache(backend)
    val executor = java.util.concurrent.Executors.newFixedThreadPool(8)
    val start = new java.util.concurrent.CountDownLatch(1)
    try
      val tasks = (1 to 32).map { _ =>
        executor.submit(new java.util.concurrent.Callable[TextMetrics]:
          def call(): TextMetrics =
            assert(start.await(5, java.util.concurrent.TimeUnit.SECONDS))
            cache.measure("same", Font.Simplex, 1))
      }
      start.countDown()
      tasks.foreach(t => assertEquals(t.get(10, java.util.concurrent.TimeUnit.SECONDS).size.width, 40.0))
      assertEquals(backend.calls, 1)
      assertEquals(cache.entryCount, 1)
      assertEquals(cache.retainedChars, 4L)
    finally
      start.countDown()
      executor.shutdownNow()

  test("empty scenes take no space, singleton origins are preserved and shapes need no metrics"):
    val backend = new CountingMeasurer
    val layout = new PictureLayout(backend)
    val empty = Picture.polyline(Seq.empty)
    val shape = Picture.circle(Point(-10, 30), 7)
    for arrange <- Seq(layout.row(_: Seq[Picture], 8), layout.column(_: Seq[Picture], 8)) do
      assertEquals(layout.bounds(arrange(Seq.empty)), None)
      assertEquals(layout.bounds(arrange(Seq(empty, Picture.empty))), None)
      assertEquals(layout.bounds(arrange(Seq(empty, shape, Picture.empty))), layout.bounds(shape))
    assertEquals(backend.calls, 0)

  private def closeBounds(actual: Option[Bounds], expected: Option[Bounds]): Unit =
    assertEquals(actual.isDefined, expected.isDefined)
    actual.zip(expected).foreach { (a, b) =>
      assertEqualsDouble(a.minX, b.minX, 1e-9)
      assertEqualsDouble(a.minY, b.minY, 1e-9)
      assertEqualsDouble(a.maxX, b.maxX, 1e-9)
      assertEqualsDouble(a.maxY, b.maxY, 1e-9)
    }

  test("rotated geometry and backend text metrics preserve alignment with zero and negative gaps"):
    val layout = new PictureLayout(new CountingMeasurer)
    val pictures = Seq(
      Picture.empty,
      Picture.rectangle(Rect(-8, 3, 20, 9)).rotate(37),
      Picture.text("descenders", Point(9, 14)).fontScale(1.5).scale(2).rotate(90),
      Picture.polyline(Seq.empty),
      Picture.circle(Point(40, -12), 35),
      Picture.text("last", Point(-3, 8)).fontScale(0.75).rotate(-25)
    )
    for gap <- Seq(0.0, 7.25, -50.0) do
      val row = layout.row(pictures, gap)
      val column = layout.column(pictures, gap)
      val beside = pictures.foldLeft(Picture.empty)((a, b) => layout.beside(a, b, gap))
      val above = pictures.foldLeft(Picture.empty)((a, b) => layout.above(a, b, gap))
      closeBounds(layout.bounds(row), layout.bounds(beside))
      closeBounds(layout.bounds(column), layout.bounds(above))
      // Compare every item's placement, not only the outer box that can hide a misplaced inner item.
      def primitiveBounds(picture: Picture): Vector[Bounds] =
        val result = Vector.newBuilder[Bounds]
        val measurer = layout.textMeasurer
        val recorder = new Renderer:
          val textMeasurer: TextMeasurer = measurer
          protected def draw(primitive: RenderPrimitive, style: PictureStyle): Unit =
            val p = primitive match
              case RenderPrimitive.Circle(c, r) => Picture.circle(c, r)
              case RenderPrimitive.Path(ps, closed) => Picture.polyline(ps, closed)
              case RenderPrimitive.Text(t, at) =>
                Picture.text(t, at).font(style.font).fontScale(style.fontScale)
            this.layout.bounds(p).foreach(result += _)
        recorder.render(picture)
        result.result()
      for (actual, expected) <- Seq((row, beside), (column, above)) do
        val a = primitiveBounds(actual)
        val b = primitiveBounds(expected)
        assertEquals(a.size, b.size)
        a.zip(b).foreach((x, y) => closeBounds(Some(x), Some(y)))

  test("batch placements exactly match folds through nested affine and text measurement contexts"):
    val layout = new PictureLayout(new CountingMeasurer)
    val random = new scala.util.Random(0L)
    def fraction(): Double = (random.nextInt(201) - 100) / 10.0
    def commands(picture: Picture): Vector[(RenderPrimitive, PictureStyle)] =
      val result = Vector.newBuilder[(RenderPrimitive, PictureStyle)]
      val measurer = layout.textMeasurer
      val recorder = new Renderer:
        val textMeasurer: TextMeasurer = measurer
        protected def draw(primitive: RenderPrimitive, style: PictureStyle): Unit =
          result += ((primitive, style))
          ()
      recorder.render(picture)
      result.result()
    for sample <- 0 until 64 do
      val pictures = (0 until 6).map { i =>
        val p = Point(fraction(), fraction())
        val shape = i % 3 match
          case 0 => Picture.circle(p, math.abs(fraction()))
          case 1 => Picture.line(p, Point(fraction(), fraction()))
          case _ => Picture.text(s"label$i", p).fontScale(0.3)
        shape
          .translate(fraction(), fraction())
          .rotate(fraction())
          .scale(0.7)
          .translate(fraction(), fraction())
      }
      for gap <- Seq(0.1, 0.0, -12.3); horizontal <- Seq(true, false) do
        val expected = pictures.foldLeft(Picture.empty) { (a, b) =>
          if horizontal then layout.beside(a, b, gap) else layout.above(a, b, gap)
        }
        val actual = if horizontal then layout.row(pictures, gap) else layout.column(pictures, gap)
        assertEquals(
          layout.bounds(actual),
          layout.bounds(expected),
          s"sample=$sample gap=$gap horizontal=$horizontal"
        )
        assertEquals(commands(actual), commands(expected))
        // Equal outer boxes alone miss inner placement errors; outer affine composition can amplify them.
        assertEquals(
          commands(actual.rotate(17).scale(1.3).translate(0.1, -0.3)),
          commands(expected.rotate(17).scale(1.3).translate(0.1, -0.3))
        )

  test("ordinary bounds never memoizes mutable measurers or transformed contexts"):
    val backend = new CountingMeasurer
    val layout = new PictureLayout(backend)
    val picture = Picture.text("ab", Point(0, 10)).fontScale(1)
    assertEquals(layout.bounds(picture).get.width, 20.0)
    backend.width = 30
    assertEquals(layout.bounds(picture).get.width, 60.0)
    assertEquals(layout.bounds(picture.scale(2)).get.width, 120.0)
    assertEquals(backend.calls, 3)

class GraphicsCachingNativeTest extends munit.FunSuite:
  override def beforeAll(): Unit = OpenCv.load()

  for horizontal <- Seq(true, false) do
    test(s"fractional ${if horizontal then "row" else "column"} matches fold pixels at an integer boundary"):
      def point(x: Double, y: Double): Point = if horizontal then Point(x, y) else Point(y, x)
      val pictures = Seq(
        Picture.circle(point(0, 10), 0.2).noStroke,
        Picture.circle(point(0.1, 10), 0.3).noStroke,
        Picture.line(point(0, 5), point(0, 15)).strokeColor(Color.Red).smooth(false)
      )
      val layout = PictureLayout.openCv
      val expected = pictures.foldLeft(Picture.empty) { (a, b) =>
        if horizontal then layout.beside(a, b, 0.1) else layout.above(a, b, 0.1)
      }
      val actual = if horizontal then layout.row(pictures, 0.1) else layout.column(pictures, 0.1)
      // Render without a compensating translation: adding an integer can hide the one-ULP difference.
      val a = actual.render(20, 20)
      try
        val b = expected.render(20, 20)
        try
          assertEquals(org.opencv.core.Core.norm(a.mat, b.mat, org.opencv.core.Core.NORM_INF), 0.0)
          assertEquals(PixelHash.of(a), PixelHash.of(b))
          assertEquals(layout.bounds(actual), layout.bounds(expected))
        finally b.close()
      finally a.close()

  test("batch layouts and cached Hershey label metrics preserve bounds and rendered pixels"):
    val plain = new PictureLayout(TextMeasurer.openCv)
    val cached = new PictureLayout(new TextMetricCache(TextMeasurer.openCv))
    def items(layout: PictureLayout): Seq[Picture] = Seq(
      layout.label("Ag", Point(15, 25), fontScale = 0.5, background = Color.Red),
      Picture.circle(Point(20, 30), 11).fillColor(Color.Blue),
      Picture.rectangle(Rect(12, 18, 21, 13)).rotate(90, Point(12, 18)).fillColor(Color.Green),
      Picture.empty,
      layout.label("Ag", Point(15, 25), fontScale = 0.5, background = Color.Red),
      Picture.text("scale", Point(20, 20)).font(Font.Duplex).fontScale(0.5).scale(2).rotate(90)
    )
    for gap <- Seq(8.0, 0.0, -6.0); horizontal <- Seq(true, false) do
      val expected = items(plain).foldLeft(Picture.empty) { (a, b) =>
        if horizontal then plain.beside(a, b, gap) else plain.above(a, b, gap)
      }
      val actual = if horizontal then cached.row(items(cached), gap) else cached.column(items(cached), gap)
      assertEquals(cached.bounds(actual), plain.bounds(expected))
      val a = actual.translate(40, 40).render(600, 600)
      try
        val b = expected.translate(40, 40).render(600, 600)
        try
          assertEquals(PixelHash.of(a), PixelHash.of(b))
          assertEquals(org.opencv.core.Core.norm(a.mat, b.mat, org.opencv.core.Core.NORM_INF), 0.0)
        finally b.close()
      finally a.close()
