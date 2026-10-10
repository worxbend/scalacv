package scalacv.vision

import org.opencv.core.{CvType, Mat}

import scalacv.Size

/** Validation before float-array reads or reshapes; axes are model-specific, storage is shared. */
private[vision] object TensorShape:
  def validate(output: Mat, imageSize: Size): Vector[Int] =
    require(
      imageSize.width.isFinite && imageSize.height.isFinite && imageSize.width >= 1 && imageSize.height >= 1 &&
        imageSize.width <= Int.MaxValue && imageSize.height <= Int.MaxValue,
      "image size must be finite and at least one pixel"
    )
    require(
      !output.empty() && output.depth() == CvType.CV_32F && output.channels() == 1,
      "tensor must be nonempty single-channel CV_32F"
    )
    require(output.isContinuous(), "tensor must be contiguous")
    val shape = Vector.tabulate(output.dims())(output.size)
    require(
      shape.forall(_ > 0) && output.total() <= Int.MaxValue,
      "tensor extents must be positive and addressable"
    )
    shape
