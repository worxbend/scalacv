package scalacv.vision

import org.opencv.core.Mat
import org.opencv.dnn.Net

import scalacv.*

/* The pose-estimation decode: how a keypoint network's output tensor becomes a [[Pose]], and the one-call
 * `image.estimatePose(net, ...)` that runs blob → forward → decode. The data model it decodes into lives in
 * Pose.scala.
 */

/** How a pose network encodes its keypoints in the tensor `forward` returns — see [[PoseEstimator]]. */
enum KeypointLayout:

  /** Direct regression, `[1, 1, K, 3]` rows of `(y, x, score)` normalised to `[0, 1]` — MoveNet's format. */
  case Regression

  /** One heatmap per keypoint, `[1, K, H, W]`; each keypoint is the arg-max of its plane — OpenPose's format.
    */
  case Heatmap

/** Human-pose (skeleton) estimation over a keypoint network run through [[Dnn]].
  *
  * MediaPipe's models ship as TFLite; OpenCV's inference path — and therefore scalacv's — is ONNX, so this is
  * built the way [[FaceDetect]] and [[Dnn]] are: **you bring the model** (`Dnn.fromOnnx`), and scalacv
  * provides the typed result and the decode. The two common output layouts are both handled
  * ([[PoseEstimator.decode]]), so a MoveNet or an OpenPose export drops in by naming its [[KeypointLayout]]
  * and [[PoseTopology]].
  *
  * {{{
  * // With a caller-loaded Net (see Dnn):
  * val pose = Dnn.blobFromImage(image.mat, size = Some(Size(192, 192)), swapRB = true).use { blob =>
  *   Dnn.forward(net, blob).use { out =>
  *     PoseEstimator.decode(out.mat, image.size, KeypointLayout.Regression)
  *   }
  * }
  * }}}
  *
  * For hand and head pose see [[PoseTopology.Hand21]] and [[HeadPose]].
  */
object PoseEstimator:

  /** Decodes a network's output tensor into a [[Pose]] in image pixels.
    *
    * Tensors must be contiguous single-channel CV_32F with positive extents and batch size one. Regression
    * also accepts 2-D `[K,3]` rows.
    *
    * @param output
    *   the Mat from `Dnn.forward`.
    * @param imageSize
    *   the size of the image the keypoints should be scaled to.
    * @param layout
    *   how the tensor encodes keypoints — see [[KeypointLayout]].
    * @param topology
    *   the naming/connectivity; its `size` must match the model's keypoint count.
    */
  def decode(
      output: Mat,
      imageSize: Size,
      layout: KeypointLayout,
      topology: PoseTopology = PoseTopology.CocoBody17
  ): Pose =
    layout match
      case KeypointLayout.Regression => decodeRegression(output, imageSize, topology)
      case KeypointLayout.Heatmap => decodeHeatmap(output, imageSize, topology)

  /** `[1, 1, K, 3]` rows of `(y, x, score)`, normalised — MoveNet. */
  private def decodeRegression(output: Mat, imageSize: Size, topology: PoseTopology): Pose =
    val k = topology.size
    val shape = TensorShape.validate(output, imageSize)
    // Validate before reshape: a model whose output is not K×(y,x,score) makes `reshape` throw a raw
    // CvException about total-size mismatch. Name it instead, the way FaceDetect names a wrong column count.
    val expected = k * 3
    if output.total() != expected then
      throw CvError.NativeCall(
        "decoding the pose regression output",
        IllegalStateException(
          s"expected $expected values (K=$k keypoints × (y, x, score)) but the model produced ${output.total()}. " +
            "This is not the regression pose model this topology decodes."
        )
      )
    require(shape == Vector(k, 3) || shape == Vector(1, 1, k, 3), "regression requires [K,3] or [1,1,K,3]")
    Managed.use(output.reshape(1, k)): flat => // k rows x 3 cols (y, x, score)
      val row = Array.ofDim[Float](3)
      val kps = (0 until k).map: i =>
        flat.get(i, 0, row)
        Keypoint(topology.names(i), Point(row(1) * imageSize.width, row(0) * imageSize.height), row(2))
      Pose(kps.toSeq, topology)

  /** `[1, K, H, W]` — one heatmap per keypoint; each keypoint is the arg-max of its plane. OpenPose. */
  private def decodeHeatmap(output: Mat, imageSize: Size, topology: PoseTopology): Pose =
    // `size(1..3)` reads dimensions that only exist on a 4-D [1, K, H, W] tensor; on anything else it throws a
    // raw CvException. Name the shape mismatch up front, mirroring FaceDetect's wrong-column-count error.
    if output.dims() != 4 then
      throw CvError.NativeCall(
        "decoding the pose heatmap output",
        IllegalStateException(
          s"expected a 4-D [1, K, H, W] heatmap tensor but the model produced a ${output.dims()}-D output. " +
            "This is not the heatmap pose model this topology decodes."
        )
      )
    val shape = TensorShape.validate(output, imageSize)
    require(shape.head == 1, "heatmap batch size must be one")
    val k = output.size(1)
    val h = output.size(2)
    val w = output.size(3)
    require(k == topology.size, s"the model has $k keypoints but ${topology.names.size} were named")
    Managed.use(output.reshape(1, k)): flat => // k rows x (h*w) cols
      val plane = Array.ofDim[Float](h * w)
      val kps = (0 until k).map: c =>
        flat.get(c, 0, plane)
        var best = 0
        var bestVal = plane(0)
        var idx = 1
        while idx < plane.length do
          if plane(idx) > bestVal then
            bestVal = plane(idx)
            best = idx
          idx += 1
        val py = best / w
        val px = best % w
        Keypoint(
          topology.names(c),
          Point(px.toDouble / w * imageSize.width, py.toDouble / h * imageSize.height),
          bestVal
        )
      Pose(kps.toSeq, topology)

/** The one-call pose pipeline on [[Image]] — an extension method so it lives beside the estimator rather than
  * in the image class. `import scalacv.*` gives `image.estimatePose(net, ...)`.
  */
extension (img: Image)

  /** Runs a keypoint `Net` over this image and decodes a [[Pose]] — the one-call form of the blob → forward →
    * decode dance, the pose counterpart to `image.faces(detector)`. The image is only read from (it stays
    * alive), and the network is borrowed, not released.
    *
    * The blob knobs mirror [[Dnn.blobFromImage]] and are model-specific — the defaults suit a MoveNet-style
    * export (RGB input, `[0, 1]` range); pass the values your model documents. For a network whose output
    * layout or keypoint scheme differs, name the [[KeypointLayout]] and [[PoseTopology]]. When you need the
    * intermediate blob or output tensor, drop to [[Dnn]] and [[PoseEstimator.decode]] directly.
    *
    * @param net
    *   a caller-owned pose network (see [[Dnn.fromOnnx]]). Stateful — do not share one across threads.
    * @param inputSize
    *   the spatial size the network expects, e.g. `Size(192, 192)` for MoveNet Lightning.
    * @param layout
    *   how the output tensor encodes keypoints — see [[KeypointLayout]].
    */
  def estimatePose(
      net: Net,
      inputSize: Size,
      layout: KeypointLayout,
      topology: PoseTopology = PoseTopology.CocoBody17,
      scaleFactor: Double = 1.0 / 255,
      mean: Scalar = Scalar(0, 0, 0),
      swapRB: Boolean = true
  ): Pose =
    Dnn
      .blobFromImage(img.mat, scaleFactor, Some(inputSize), mean, swapRB)
      .use: blob =>
        Dnn
          .forward(net, blob)
          .use: out =>
            PoseEstimator.decode(out, img.size, layout, topology)
