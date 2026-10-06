package scalacv.vision

import org.opencv.core.{Mat, Size as CvSize}
import org.opencv.dnn.{Dnn as CvDnn, Net}

import scalacv.*

/** Deep-network inference over `org.opencv.dnn`, narrowed to the ONNX path.
  *
  * Only ONNX is exposed. OpenCV can also read Caffe, Darknet, TensorFlow, TFLite and Torch graphs, but every
  * one of those importers has its own set of unsupported-layer failure modes, and offering seven entry points
  * would imply a level of support this library cannot honestly give. ONNX is the format the other frameworks
  * export to, so one importer covers the realistic cases.
  *
  * ==Ownership==
  *
  * `Net` is one of the 185 generated types with no public `release()`, so it is freed through the
  * `delete(long)` bridge — see [[Releasable.nativeHandle]]. Both the `Net` from [[fromOnnx]] and the `Mat`s
  * from [[blobFromImage]] and [[forward]] are **caller-owned**: release them, or take them with
  * [[Managed.use]]. Nothing here takes ownership of an argument.
  *
  * ==Statefulness==
  *
  * A `Net` is not a pure function. `setInput` mutates it and `forward` reads that mutation back, so a single
  * `Net` cannot be driven from two threads concurrently — [[forward]] does both in one call precisely so that
  * the window between them is not something a caller can accidentally widen, but it is still not a lock. One
  * `Net` per thread, or serialise access yourself.
  *
  * That statefulness reaches the results too, and this is where OpenCV would otherwise hand back a nasty
  * surprise: `Net.forward` returns a header onto the layer's own output buffer, which the next pass
  * overwrites. [[forward]] copies before it returns, so holding two outputs from one `Net` — this frame's
  * heatmap against the previous frame's, or `frames.map(f => Dnn.forward(net, blob(f)))` — means what it
  * looks like it means.
  */
object Dnn:

  /** `Net` has no public `release()`, so freeing it needs the `delete(long)` bridge. Exposed so that callers
    * who obtain a `Net` some other way — `Dnn.readNet`, `Net.quantize` — can put it in a [[Managed]] on the
    * same terms with `import Dnn.given`.
    */
  given Releasable[Net] = Releasable.nativeHandle

  /** Loads an ONNX model from a filesystem path.
    *
    * Every way this can go wrong is a `Left`, and the distinction between them is deliberately not modelled:
    * OpenCV reports a missing file, a file of arbitrary bytes and a structurally invalid graph as three
    * *different* `CvException`s from three different lines of `onnx_importer.cpp` (verified against 4.13.0),
    * and none of that is a stable interface worth pattern-matching on. What matters to a caller is that the
    * model did not load, and that the process is still alive.
    *
    * The path is checked before OpenCV sees it. That is not redundant with the native check — it is the only
    * way a missing file gets an error message that names the file rather than quoting a C++ source location.
    *
    * The `empty()` guard afterwards is belt-and-braces: 4.13.0's importer throws rather than handing back an
    * empty `Net`, but the sibling readers in the same header do not all behave that way (`CascadeClassifier`
    * famously does the opposite), and an empty `Net` that reaches a caller fails much later, inside
    * `forward`, with nothing pointing back at the load.
    *
    * @return
    *   a caller-owned `Net`, or the reason it could not be read.
    */
  def fromOnnx(path: String): Either[CvError, Managed[Net]] =
    internal.ModelLoader
      .loadNative[Net](
        path,
        describe = s"reading an ONNX model from '$path'",
        missingDetails = "no such file (or it is a directory), so there is no model to read",
        // The empty() guard is belt-and-braces: 4.13.0's importer throws rather than handing back an
        // empty Net, but the sibling readers in the same header do not all behave that way
        // (CascadeClassifier famously does the opposite), and an empty Net that reaches a caller fails
        // much later, inside forward, with nothing pointing back at the load.
        validate =
          net => Option.when(net.empty())("OpenCV read this file but produced a network with no layers")
      )(CvDnn.readNetFromONNX(path))
      .map(Managed(_))

  /** Turns an image into the 4-dimensional NCHW blob a network expects.
    *
    * The result is a single-channel `CV_32F` Mat with `dims == 4` and sizes `(1, C, H, W)` — batch, channels,
    * height, width — where `C` is the channel count of `mat` (3 for a BGR image, 1 for greyscale) and `H`/`W`
    * come from `size` when it is given and from `mat` when it is not. Note the transposition: OpenCV's `Size`
    * is `(width, height)` while the blob's trailing dimensions are `(height, width)`, so `Size(300, 200)`
    * yields sizes `(1, 3, 200, 300)`. Getting that backwards is the usual cause of a network that runs and
    * returns nonsense.
    *
    * Per-channel arithmetic is `(pixel - mean) * scaleFactor`, in that order — `mean` is **not** scaled. So a
    * model trained on `[0, 1]` inputs wants `scaleFactor = 1.0 / 255` and a mean in `[0, 255]`.
    *
    * @param scaleFactor
    *   multiplier applied after the mean subtraction.
    * @param size
    *   the spatial size to resize to. `None` keeps the source's own size, which is right only for a network
    *   with a dynamic input shape.
    * @param mean
    *   subtracted per channel, in the channel order of the **blob**, not of `mat`. Measured against 4.13.0
    *   and 4.14.0: the swap happens first, so with `swapRB = true` on an ordinary BGR image `mean` is
    *   `(R, G, B)`. That is what the published per-model mean triples assume, and it is the one parameter
    *   interaction here whose two readings differ by exactly the amount that makes a model quietly worse
    *   rather than broken.
    * @param swapRB
    *   swaps the first and third channels. Almost every published model was trained on RGB while OpenCV
    *   decodes to BGR, so this is usually `true` in practice; it defaults to `false` only because that is
    *   OpenCV's own default and silently disagreeing with upstream documentation would be worse.
    * @param crop
    *   when `true`, resize so the short side matches `size` and centre-crop the rest away; when `false`,
    *   resize both axes and accept the changed aspect ratio.
    * @return
    *   a caller-owned blob.
    * @throws IllegalArgumentException
    *   if `mat` is empty, or if `size` is given but not strictly positive — OpenCV treats a zero extent as
    *   "do not resize", which would silently produce a blob of the wrong shape rather than fail.
    */
  def blobFromImage(
      mat: Mat,
      scaleFactor: Double = 1.0,
      size: Option[Size] = None,
      mean: Scalar = Scalar(0, 0, 0),
      swapRB: Boolean = false,
      crop: Boolean = false
  ): Managed[Mat] =
    require(!mat.empty(), "Dnn.blobFromImage needs a non-empty image")
    require(
      size.forall(s => s.width > 0 && s.height > 0),
      s"Dnn.blobFromImage needs a strictly positive size, or None to keep the source's: ${size.orNull}"
    )
    // Size(0, 0) is OpenCV's spelling of "do not resize"; `require` above keeps a caller from reaching it
    // by accident, and None reaches it on purpose.
    val target = size.fold(CvSize(0, 0))(_.toCv)
    // Unlike the Imgproc wrappers in Ops.scala, this native call allocates the destination itself and
    // returns it, so there is no half-built Mat to release if it throws — the throw happens before any
    // handle crosses the boundary.
    Managed(
      Cv.orThrow("Dnn.blobFromImage")(
        CvDnn.blobFromImage(mat, scaleFactor, target, mean.toCv, swapRB, crop)
      )
    )

  /** Sets `blob` as the network's input and runs it.
    *
    * One call rather than two because the two are not independently useful: a `Net` whose input has been set
    * but not forwarded is a half-applied mutation, and a `forward` without a preceding `setInput` reads
    * whatever the last caller left behind. Fusing them makes the stateful pair atomic from the caller's point
    * of view.
    *
    * `blob` is borrowed, not consumed — but it must stay alive until this returns, and releasing it while a
    * forward is in flight is a crash rather than an exception. The safe shape is to keep it in a [[Managed]]
    * that outlives the call.
    *
    * @param outputName
    *   which blob to retrieve. `None` runs to the last layer, which is what a single-output classifier or
    *   regressor wants. Give a name for a multi-output network — the valid names are
    *   `net.getUnconnectedOutLayersNames`, and note that these are *blob* names, which for an ONNX import are
    *   the graph's declared outputs and not the `onnx_node!…` layer names.
    * @return
    *   a caller-owned output blob — an independent copy rather than a view onto the network's internals, so
    *   it keeps its values when the same `Net` is forwarded again. Its shape is the network's, not the
    *   input's.
    * @throws IllegalArgumentException
    *   if the net has no layers or the blob is empty — both are programmer errors that OpenCV would otherwise
    *   report from native code with no reference to the call site.
    * @throws CvError.NativeCall
    *   if `outputName` names nothing, or the graph rejects the input shape.
    */
  def forward(net: Net, blob: Mat, outputName: Option[String] = None): Managed[Mat] =
    require(!net.empty(), "Dnn.forward needs a network with at least one layer")
    require(!blob.empty(), "Dnn.forward needs a non-empty input blob")
    Cv.orThrow("Net.setInput")(net.setInput(blob))
    // `Net.forward` hands back a header onto the layer's *own* output blob, not a copy: the generated JNI
    // wraps the returned `cv::Mat` with `new Mat(_retval_)`, which shares the pixel buffer, and OpenCV's dnn
    // blob manager reuses that same allocation for the next pass of the same shape. Measured on 4.13.0: two
    // forwards from one `Net` returned the identical `dataAddr`, and the second rewrote the first result's
    // pixels in place — so a caller diffing this frame's output against the previous frame's got zero, every
    // time, with nothing thrown. `release()` on such a header frees nothing either, because the `Net` still
    // holds a reference to the buffer. Copying once here is what makes the returned handle the unique owner
    // this file documents, and the borrowed header is released immediately so its refcount does not linger
    // until GC. Rejected: handing out a borrowed view type that pins the `Net`, which would introduce a
    // second ownership model for the sake of one method. The copy is a single memcpy of the output blob,
    // negligible beside the forward pass that produced it.
    val borrowed = Cv.orThrow("Net.forward")(outputName match
      case Some(name) => net.forward(name)
      case None => net.forward())
    try Mats.produce("Net.forward output copy")(dst => borrowed.copyTo(dst))
    finally borrowed.release()
