package scalacv

import scalacv.graphs.*

import org.opencv.core.{Core, CvType, Mat, Scalar as CvScalar}

/** The bit-exact gate for the ROI alpha-blend in `Graphics.alpha`.
  *
  * The optimization paints a translucent shape opaquely onto the image and blends back only a conservative
  * bounding box of the painted pixels, instead of cloning and blending the whole image. It is correct only if
  * that box contains every pixel the draw touches. This test proves it over a corpus, by an independent
  * reference: the same shape rendered fully opaque and then blended over the original with a whole-image
  * `addWeighted` — the exact arithmetic the old full-image path used. If the ROI is ever too small, the
  * translucent render keeps an opaque pixel the reference blended, and the hashes diverge.
  *
  * The corpus covers every primitive (circle fill/stroke, dashed, polygon fill, thick polyline, text, star,
  * ellipse, rotated), thin and thick strokes, and positions centred, against each edge, and partly off-canvas
  * — where a naive bound would clip.
  */
class GraphicsAlphaRoiTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  private val W = 200
  private val H = 150
  private val alpha = 128
  private val t = alpha / 255.0

  /** A textured background so a mis-blend is visible against varied pixels, not a flat field. */
  private def background(): Mat =
    val m = Mat(H, W, CvType.CV_8UC3, CvScalar(40, 60, 90))
    org.opencv.imgproc.Imgproc.rectangle(
      m,
      org.opencv.core.Point(0, 0),
      org.opencv.core.Point(W, H / 2),
      CvScalar(90, 40, 60),
      -1
    )
    org.opencv.imgproc.Imgproc.circle(m, org.opencv.core.Point(W / 2, H / 2), 50, CvScalar(20, 120, 200), -1)
    m

  /** The independent reference: draw the shape opaque, then blend the whole image — the old algorithm. */
  private def referenceHash(build: Int => Picture): Long =
    val bg = background()
    try
      val layer = Image.wrap(Managed(bg.clone())).draw(build(255))
      try
        val out = Mat()
        try
          Core.addWeighted(layer.mat, t, bg, 1 - t, 0, out)
          hash(out)
        finally out.release()
      finally layer.close()
    finally bg.release()

  private def actualHash(build: Int => Picture): Long =
    val bg = background()
    try
      val img = Image.wrap(Managed(bg.clone())).draw(build(alpha))
      try hash(img.mat)
      finally img.close()
    finally bg.release()

  private def hash(m: Mat): Long = PixelHash.of(m)

  private def col(a: Int): Color = Color.Orange.withAlpha(a)

  // Each entry: a picture parameterised only by its alpha, so opaque (reference) and translucent (actual)
  // draw identical geometry. Positions include mid-canvas, hard against edges, and partly off-canvas.
  private val corpus: Seq[(String, Int => Picture)] = Seq(
    "fill circle centred" -> (a => Picture.circle(Point(100, 75), 30).fillColor(col(a)).noStroke),
    "fill circle off top-left" -> (a => Picture.circle(Point(5, 5), 25).fillColor(col(a)).noStroke),
    "fill circle off bottom-right" -> (a => Picture.circle(Point(198, 148), 30).fillColor(col(a)).noStroke),
    "thick stroke circle" -> (a => Picture.circle(Point(100, 75), 30).stroke(col(a), 9).noFill),
    "dashed circle" -> (a => Picture.circle(Point(60, 60), 25).stroke(col(a), 3).noFill.dashed),
    "fill quad centred" -> (a => Picture.rectangle(Rect(70, 50, 60, 40)).fillColor(col(a)).noStroke),
    "fill quad off edge" -> (a => Picture.rectangle(Rect(180, 120, 40, 40)).fillColor(col(a)).noStroke),
    "thick stroke quad" -> (a => Picture.rectangle(Rect(40, 30, 80, 60)).stroke(col(a), 7).noFill),
    "rotated quad" -> (a =>
      Picture.rectangle(Rect(60, 40, 60, 40)).fillColor(col(a)).noStroke.rotate(25, Point(90, 60))
    ),
    "open polyline thick" -> (a =>
      Picture.polyline(Seq(Point(20, 20), Point(90, 120), Point(160, 30))).stroke(col(a), 6)
    ),
    "polygon fill" -> (a =>
      Picture.polygon(Seq(Point(30, 30), Point(120, 40), Point(80, 110))).fillColor(col(a)).noStroke
    ),
    "star" -> (a => Picture.star(Point(100, 75), 5, 40, 18).fillColor(col(a)).noStroke),
    "ellipse stroke" -> (a => Picture.ellipse(Point(100, 75), 60, 30).stroke(col(a), 4).noFill),
    "text" -> (a => Picture.text("Ag", Point(60, 90)).strokeColor(col(a)).fontScale(1.5)),
    "text near edge" -> (a => Picture.text("xy", Point(2, 20)).strokeColor(col(a)).fontScale(1.0)),
    "scaled group" -> (a =>
      Picture.circle(Point(50, 50), 20).fillColor(col(a)).noStroke.scale(1.8, Point(100, 75))
    )
  )

  corpus.foreach: (name, build) =>
    test(s"ROI alpha blend is pixel-identical to full-image blend: $name"):
      assertEquals(
        actualHash(build),
        referenceHash(build),
        s"the ROI bound clipped a painted pixel for '$name' — it must be a superset of what the draw touches"
      )
