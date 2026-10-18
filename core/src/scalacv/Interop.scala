package scalacv

import java.awt.image.{BufferedImage, DataBufferByte}

import org.opencv.core.{CvType, Mat}

/** Bridges between an OpenCV `Mat` and a `java.awt.image.BufferedImage`.
  *
  * This is what lets scalacv images render in a notebook (Almond/Jupyter display a `BufferedImage`
  * automatically) and interoperate with anything in the AWT/Swing world — `ImageIO`, an on-screen `JLabel`, a
  * `Graphics2D` you already have. See [[Image.toBufferedImage]] and [[Image.fromBufferedImage]].
  */
private[scalacv] object Interop:

  /** Copies `mat` (8-bit, 1/3/4 channels) into a `BufferedImage`. A 4-channel image is flattened to BGR. */
  def toBufferedImage(mat: Mat): BufferedImage =
    require(
      mat.depth == CvType.CV_8U,
      s"toBufferedImage needs an 8-bit image, got depth ${mat.depth}. A 16-bit or float image — a depth or " +
        "disparity map, a raw filter response — has to be brought to 8-bit first: `normalize` rescales it to " +
        "0–255 grey, `colorMap` renders it in false colour."
    )
    require(!mat.empty(), "toBufferedImage needs a non-empty image")
    require(
      mat.channels == 1 || mat.channels == 3 || mat.channels == 4,
      s"toBufferedImage supports 1, 3 or 4 channels, got unsupported channel count: ${mat.channels}"
    )
    // AWT takes grey or 3-byte BGR directly; a 4-channel BGRA image is flattened to BGR first.
    val kind = if mat.channels == 1 then BufferedImage.TYPE_BYTE_GRAY else BufferedImage.TYPE_3BYTE_BGR
    val image = BufferedImage(mat.cols, mat.rows, kind)
    val target = image.getRaster.getDataBuffer.asInstanceOf[DataBufferByte].getData
    // Copy straight into AWT's own backing array: one bulk `get`, no intermediate byte[] and no
    // defensive clone. `get` reads a contiguous run, so it needs a continuous Mat — a 1/3-channel Mat
    // usually already is one (so the common path is a single copy), and only the rare non-continuous
    // submat is cloned to continue it. A 4-channel image is converted to BGR, which continues it too.
    if mat.channels == 4 then mat.cvtColor(ColorConversion.BgraToBgr).use(_.get(0, 0, target)): Unit
    else if mat.isContinuous then mat.get(0, 0, target): Unit
    else Managed.use(mat.clone())(_.get(0, 0, target)): Unit
    image

  /** Copies any `BufferedImage` into a fresh 3-channel BGR `Mat` (caller-owned). */
  def toMat(image: BufferedImage): Mat =
    val w = image.getWidth
    val h = image.getHeight
    val mat = Cv.orThrow("Interop.toMat")(Mat(h, w, CvType.CV_8UC3))
    // Guard the fill: this is the one raw native allocation in the read path with no Managed wrapping it, so a
    // broken raster, an oversized `put`, or a failing `drawImage` between allocating the Mat and returning it
    // would otherwise strand a native buffer with no owner. Free it on any throw before it escapes.
    try
      // A TYPE_3BYTE_BGR source already stores bytes in exactly the B,G,R interleaving CV_8UC3 wants —
      // that is the very layout the general path below draws *into* — so its backing array copies straight
      // in, skipping a second BufferedImage and a full Graphics2D redraw. This is the round-trip out of
      // toBufferedImage, and the common ImageIO-JPEG case. The exact-length guard keeps it to the simple,
      // contiguous raster: a translated or padded sub-raster (getSubimage) fails it and takes the safe path.
      val fastData = image.getType match
        case BufferedImage.TYPE_3BYTE_BGR =>
          val d = image.getRaster.getDataBuffer.asInstanceOf[DataBufferByte].getData
          // Long math: w*h*3 overflows a signed Int past a ~26k-pixel square, and a wrapped negative
          // could spuriously equal a (never-negative) array length, taking the fast path on the wrong
          // raster. Comparing as Long keeps the guard honest for very large images.
          if d.length.toLong == w.toLong * h * 3 then d else null
        case _ => null
      if fastData != null then mat.put(0, 0, fastData)
      else
        // Any other type (ARGB, indexed, custom, or an unusual raster) → normalise through a known BGR
        // layout so every source is handled uniformly.
        val bgr = BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR)
        val g = bgr.getGraphics
        try g.drawImage(image, 0, 0, null)
        finally g.dispose()
        mat.put(0, 0, bgr.getRaster.getDataBuffer.asInstanceOf[DataBufferByte].getData)
      mat
    catch
      case e: Throwable =>
        mat.release()
        throw e
