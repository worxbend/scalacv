package scalacv

import scalacv.vision.*

/** OCR preprocessing, headless. scalacv owns the OpenCV half (grayscale, denoise, binarise, deskew); the
  * recognition itself is a pluggable OcrEngine — here a stub, in real use Tesseract via tess4j or bytedeco.
  */
@main def ocrDemo(): Unit =
  OpenCv.load()

  // A skewed synthetic "scan": dark text-bars on white, tilted 9 degrees.
  val page = Image
    .blank(240, 160, Scalar.White)
    .drawRects(
      Seq(Rect(30, 30, 180, 10), Rect(30, 60, 180, 10), Rect(30, 90, 180, 10)),
      Scalar.Black,
      Thickness.Filled
    )
  val scan = Image.wrap(page.mat.rotated(9.0, color = Scalar.White))
  page.close()

  // A stand-in engine; swap in a real one that shells out to / binds Tesseract.
  val engine = new OcrEngine:
    def recognize(image: Image): OcrResult =
      OcrResult(s"<Tesseract would read a ${image.width}x${image.height} prepared page here>")

  // Ocr.read runs forOcr (grayscale -> denoise -> adaptive threshold -> deskew) then the engine.
  val result = Ocr.read(scan, engine)
  println(result.text)

  // The prepared image on its own, to show the preprocessing produced something:
  scan
    .forOcr()
    .bytes(".png")
    .foreach(bytes => println(s"prepared page: ${bytes.length} bytes, deskewed & binarised"))
  println("OK")
