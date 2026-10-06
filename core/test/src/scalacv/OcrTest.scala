package scalacv

import scalacv.graphs.*
import scalacv.vision.*

/** OCR preprocessing (the part scalacv owns) and the engine SPI. */
class OcrTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  /** A synthetic "document": horizontal black text-bars on white. */
  private def document(): Image =
    Image
      .blank(200, 140, Scalar.White)
      .drawRects(
        Seq(Rect(30, 20, 140, 8), Rect(30, 45, 140, 8), Rect(30, 70, 140, 8), Rect(30, 95, 140, 8)),
        Scalar.Black,
        Thickness.Filled
      )

  /** width/height of the ink's bounding box — large when the bars are horizontal, smaller when they tilt.
    * Thresholds inverted so the dark bars (not the white page) are the foreground.
    */
  private def horizontalRatio(img: Image): Double =
    val binary = img.copy.gray.threshold(128, kind = Threshold(Threshold.Mode.BinaryInv))
    try
      val boxes = binary.contours().map(_.boundingRect).filter(_.area > 20)
      if boxes.isEmpty then 0.0
      else
        val x0 = boxes.map(_.x).min
        val y0 = boxes.map(_.y).min
        val x1 = boxes.map(b => b.x + b.width).max
        val y1 = boxes.map(b => b.y + b.height).max
        (x1 - x0).toDouble / math.max(1, y1 - y0)
    finally binary.close()

  test("deskew straightens a rotated document"):
    val doc = document()
    val uprightRatio = horizontalRatio(doc) // borrows doc
    // Rotate on a WHITE background, as a real skewed scan would be (a black fill would look like ink).
    val skewed = Image.wrap(doc.mat.rotated(12.0, color = Scalar.White))
    doc.close()
    val skewedRatio = horizontalRatio(skewed)
    val deskewed = skewed.deskew() // consumes skewed
    val deskewedRatio = horizontalRatio(deskewed)
    deskewed.close()
    assert(
      skewedRatio < uprightRatio,
      s"rotation should tilt the bars (skewed $skewedRatio vs upright $uprightRatio)"
    )
    assert(
      deskewedRatio > skewedRatio * 1.15,
      s"deskew should straighten them back (deskewed $deskewedRatio vs skewed $skewedRatio)"
    )

  test("forOcr yields a single-channel binarised image"):
    val prepared = document().forOcr()
    try assertEquals(prepared.channels, 1)
    finally prepared.close()

  test("Ocr.read preprocesses, delegates to the engine, and leaves the source usable"):
    val engine = new OcrEngine:
      def recognize(image: Image): OcrResult =
        OcrResult(s"${image.width}x${image.height}", Seq(OcrWord("hi", 0.9f, Rect(0, 0, 10, 10))))
    val img = Image.blank(120, 80)
    try
      val result = Ocr.read(img, engine)
      assertEquals(result.text, "120x80") // the prepared copy keeps the size
      assertEquals(result.words.size, 1)
      assertEquals(img.width, 120) // borrowed — still alive
    finally img.close()

  test("OcrResult helpers: emptiness and confidence filtering"):
    assert(OcrResult("").isEmpty)
    val res =
      OcrResult("a b", Seq(OcrWord("a", 0.9f, Rect(0, 0, 1, 1)), OcrWord("b", 0.2f, Rect(0, 0, 1, 1))))
    assert(!res.isEmpty)
    assertEquals(res.confident(0.5f).map(_.text), Seq("a"))
