package scalacv.vision

import org.opencv.core.{Core, CvType, Mat}
import org.opencv.imgproc.Imgproc

import scalacv.*

/** One template-match hit — where a template was found and how well it matched. */
final case class TemplateMatch(location: Rect, score: Double)

/** Screen (and screenshot) analysis: finding a known sub-image, and spotting what changed between two
  * captures.
  *
  * The staple of screen automation and visual testing — "is this button on screen, and where?", "what changed
  * since the last frame?". It is ordinary template matching and differencing, no model involved.
  *
  * {{{
  * import scalacv.*
  * OpenCv.load()
  *
  * for
  *   screen   <- Image.read("screenshot.png")
  *   template <- Image.read("button.png")
  * yield
  *   try Screen.locate(screen, template) // Option[TemplateMatch]
  *   finally { screen.close(); template.close() }
  * }}}
  */
object Screen:

  /** Finds the single best occurrence of `template` in `image`, or `None` if nothing matches at or above
    * `minScore`. Both images are borrowed. `minScore` is a normalised correlation in `[-1, 1]`; `0.8`+ is a
    * confident match.
    */
  def locate(image: Image, template: Image, minScore: Double = 0.8): Option[TemplateMatch] =
    findAll(image, template, minScore, maxMatches = 1).headOption

  /** Finds up to `maxMatches` non-overlapping occurrences of `template`, best first, each at or above
    * `minScore`. After each hit all overlapping top-left positions are excluded.
    */
  def findAll(
      image: Image,
      template: Image,
      minScore: Double = 0.8,
      maxMatches: Int = 20
  ): Seq[TemplateMatch] =
    val img = image.mat
    val tmpl = template.mat
    require(
      tmpl.rows <= img.rows && tmpl.cols <= img.cols,
      s"the template (${tmpl.cols}x${tmpl.rows}) is larger than the image (${img.cols}x${img.rows})"
    )
    require(maxMatches >= 1, s"maxMatches must be at least 1, got $maxMatches")
    val (tw, th) = (tmpl.cols, tmpl.rows)
    require(minScore >= -1 && minScore <= 1, "minScore must be in [-1, 1]")
    Managed.scope: own =>
      val result = own(Mat())
      Cv.orThrow("matchTemplate")(Imgproc.matchTemplate(img, tmpl, result, Imgproc.TM_CCOEFF_NORMED))
      val eligible = own(Mat(result.rows, result.cols, CvType.CV_8UC1, org.opencv.core.Scalar(255)))
      val hits = List.newBuilder[TemplateMatch]
      var found = 0
      var searching = true
      while searching && found < maxMatches && Core.countNonZero(eligible) > 0 do
        val mm = Core.minMaxLoc(result, eligible)
        if mm.maxVal.isFinite && mm.maxVal >= minScore then
          val x = mm.maxLoc.x.toInt
          val y = mm.maxLoc.y.toInt
          hits += TemplateMatch(Rect(x, y, tw, th), mm.maxVal)
          found += 1
          // Exclude every top-left position whose rectangle overlaps this hit. The mask, not a
          // correlation-domain sentinel, represents exhaustion (including minScore == -1).
          val x0 = math.max(0, x - tw + 1)
          val y0 = math.max(0, y - th + 1)
          val x1 = math.min(result.cols.toLong, x.toLong + tw).toInt
          val y1 = math.min(result.rows.toLong, y.toLong + th).toInt
          Managed.use(eligible.submat(y0, y1, x0, x1))(patch => patch.setTo(org.opencv.core.Scalar(0)): Unit)
        else searching = false
      hits.result()

  /** The regions that changed between two same-size captures — a one-shot screen diff. Both are borrowed; the
    * result is plain `Rect` data (largest first).
    *
    * @param threshold
    *   per-pixel intensity delta that counts as changed.
    * @param minArea
    *   changed blobs smaller than this are ignored.
    */
  def diff(before: Image, after: Image, threshold: Int = 25, minArea: Int = 100): Seq[Rect] =
    val a = before.mat
    val b = after.mat
    require(
      a.rows == b.rows && a.cols == b.cols,
      s"the two captures must be the same size, got ${a.cols}x${a.rows} and ${b.cols}x${b.rows}"
    )
    // Size alone is not enough: `absdiff` also requires matching channel count and depth, and a mismatch
    // there escapes as a raw CvException from native code. Reject it up front, as a programmer error.
    require(
      a.channels() == b.channels() && a.depth() == b.depth(),
      s"the two captures must have the same type, got ${CvType.typeToString(a.`type`())} and " +
        s"${CvType.typeToString(b.`type`())}"
    )
    a.absdiff(b)
      .use: d =>
        Mats
          .grayscale(d)
          .use: gray =>
            gray
              .threshold(threshold.toDouble, 255)
              .image
              .use: mask =>
                mask
                  .dilate(radius = 2)
                  .use: merged =>
                    Mats.blobs(merged, minArea)
