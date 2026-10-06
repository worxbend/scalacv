package scalacv

import org.opencv.core.{CvType, Mat}

/** Helpers that do not belong on a Mat: the single place a destination Mat is allocated ([[Mats.produce]]),
  * the shared greyscale reduction, small lifts between native memory and plain Scala data, and the named
  * preconditions the ops enforce before crossing into native code.
  */
object Mats:

  /** Runs `stages` in order, releasing each intermediate as soon as the next stage has consumed it.
    *
    * `src` is borrowed and never released — it belongs to whoever created it. Everything the stages allocate
    * except the final result is released, including when a stage throws. Equivalent to a fold of [[pipe]],
    * which is exactly how it is implemented; it exists because a long pipeline reads better as a list of
    * stages than as a chain of nested lambdas.
    *
    * {{{
    * Mats.chain(frame)(
    *   _.cvtColor(ColorConversion.BgrToGray),
    *   _.gaussianBlur(Size(5, 5), 1.5),
    *   _.canny(50, 150)
    * )
    * }}}
    */
  def chain(src: Mat)(stages: (Mat => Managed[Mat])*): Managed[Mat] =
    require(
      stages.nonEmpty,
      "chain needs at least one stage: with none there is no owned Mat to return, and returning the " +
        "source would hand back something the caller does not own"
    )
    stages.tail.foldLeft(stages.head(src))(_.pipe(_))

  /** Allocates the destination, runs the native call, and wraps the result.
    *
    * Private, and the single place a destination Mat is created, so the ownership contract is enforced in one
    * spot rather than at every operation in this file. If the native call throws, the destination is released
    * before the exception propagates — otherwise every failed operation would leak a Mat that no caller ever
    * saw and therefore could not free.
    */
  private[scalacv] def produce(operation: String)(fill: Mat => Unit): Managed[Mat] =
    val dst = Mat()
    try
      Cv.orThrow(operation)(fill(dst))
      Managed(dst)
    catch
      case e: Throwable =>
        dst.release()
        throw e

  /** A single-channel greyscale version of `mat`, owned by the caller.
    *
    * Almost every algorithm that is not about colour — corner detection, optical flow, stereo matching, ORB,
    * template differencing, chessboard detection, deskewing — starts by reducing to one channel, and each has
    * to cope with being handed an image that is *already* one channel. This is that step, in one place: it
    * used to be copied verbatim into seven files, which is seven chances for one of them to drift on the
    * question below and no way to notice.
    *
    * The question is what to do when the input is already grey, and the answer is **clone**, not "hand the
    * receiver back". Every op in this file returns a Mat the caller owns and must release; a `Managed`
    * wrapping the borrowed receiver would look identical at the call site and would free an image belonging
    * to someone else the moment the `use` block ended — a caller's frame, a detector's input. One extra copy
    * on an already-grey image is the price of a uniform ownership rule, and the alternative is a
    * use-after-free that only appears on greyscale input.
    *
    * `channels >= 3` rather than `== 3`: a BGRA frame converts through the same `BGR2GRAY`, which ignores the
    * fourth channel.
    *
    * A 2-channel Mat is neither already grey nor convertible — `BGR2GRAY` on it aborts in native code — so it
    * is rejected up front with [[IllegalArgumentException]], the same named-precondition treatment
    * [[Mats.requireKernel]] gives a bad kernel.
    */
  private[scalacv] def grayscale(mat: Mat): Managed[Mat] =
    require(
      mat.channels == 1 || mat.channels >= 3,
      s"grayscale needs a 1-channel or 3+-channel image, got ${mat.channels} channels — a 2-channel " +
        "Mat has no greyscale conversion"
    )
    if mat.channels >= 3 then mat.cvtColor(ColorConversion.BgrToGray) else Managed(mat.clone())

  /** Reads the top-left `r`×`c` block of a `CV_64F` Mat into plain Scala rows — for lifting a small solver
    * result (a rotation, a camera matrix) out of native memory into immutable data. The Mat is borrowed.
    */
  private[scalacv] def readMatrix(mat: Mat, r: Int, c: Int): Seq[Seq[Double]] =
    (0 until r).map(i => (0 until c).map(j => mat.get(i, j)(0)))

  /** Reads the first `r` entries of a `CV_64F` column vector into a plain Scala `Seq` — the companion to
    * [[readMatrix]] for a translation or similar single-column result. The Mat is borrowed.
    */
  private[scalacv] def readColumn(mat: Mat, r: Int): Seq[Double] =
    (0 until r).map(i => mat.get(i, 0)(0))

  /** The bounding boxes of the blobs in a binary mask (`CV_8UC1`, 0/255), boxes smaller than `minArea`
    * dropped, largest first. The shared tail of every foreground-mask pipeline (motion, screen diff, obstacle
    * detection): find the contours, box them, filter, sort. The mask is borrowed; the result is plain data.
    * The *cleanup* step in front of this (dilate, open or close, and at what radius) is deliberately left to
    * the caller — those are different operations chosen per pipeline, not drift.
    */
  private[scalacv] def blobs(mask: Mat, minArea: Int): Seq[Rect] =
    mask.findContours().map(_.boundingRect).filter(_.area >= minArea).sortBy(-_.area)

  /** The inverse of [[readColumn]]: `values` as a caller-owned `n`×1 `CV_64F` Mat, for handing a small vector
    * — a Rodrigues rotation, a translation — back to a native solver.
    *
    * The write is guarded because it is the one step that can fail after the allocation: between a bare
    * `Mat(...)` and the caller taking ownership there is nobody to free it, so a throwing `put` would strand
    * a native buffer no one ever saw.
    */
  private[scalacv] def column(values: Seq[Double]): Mat =
    require(values.nonEmpty, "a column Mat needs at least one value")
    val m = Mat(values.size, 1, CvType.CV_64F)
    try
      m.put(0, 0, values*): Unit
      m
    catch
      case e: Throwable =>
        m.release()
        throw e

  /** Shared kernel validation. OpenCV's own check lives in native code and aborts with a `CvException`
    * quoting a C++ expression; failing here names the parameter the caller actually passed.
    */
  private[scalacv] def requireKernel(op: String, kernel: Size, allowZero: Boolean): Unit =
    val w = kernel.width.toInt
    val h = kernel.height.toInt
    val zero = allowZero && w == 0 && h == 0
    val hint = if allowZero then " (or Size(0, 0) to derive it from sigma)" else ""
    require(
      zero || (w > 0 && h > 0 && w % 2 == 1 && h % 2 == 1),
      s"$op needs an odd, positive kernel$hint, got $kernel"
    )

  /** The 8-bit 3-channel (`CV_8UC3`) input contract of the photo/stylisation and tone ops, as a named
    * precondition. Without it a grey or float input reaches the native call and dies as
    * [[CvError.NativeCall]] — an unchecked, data-looking failure for what is really a programmer error,
    * exactly the split the ops' scaladoc promises to enforce with [[IllegalArgumentException]].
    */
  private[scalacv] def require8Bit3Channel(op: String, mat: Mat): Unit =
    require(
      mat.`type`() == CvType.CV_8UC3,
      s"$op needs an 8-bit 3-channel image (CV_8UC3), got ${mat.channels()} channel(s), " +
        s"depth ${mat.depth()}"
    )
