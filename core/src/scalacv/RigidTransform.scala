package scalacv

/** An immutable rigid mapping between two 3D coordinate frames.
  *
  * Rotation is row-major and points are column vectors: `x_destination = R * x_source + t`. Coordinates and
  * translation use the same caller-chosen units. There are no native resources to release. The rotation must
  * be finite, 3×3, orthonormal and have determinant +1 (absolute tolerance `1e-8`); translation must contain
  * three finite values. Invalid construction, including `copy`, throws `IllegalArgumentException`. Inputs are
  * immutable Scala sequences, not native matrix handles.
  */
final case class RigidTransform(rotation: Seq[Seq[Double]], translation: Seq[Double]):
  RigidTransform.validate(rotation, translation)

  /** Length of the translation in its own units, using a norm that avoids squaring overflow/underflow. */
  def translationNorm: Double = math.hypot(math.hypot(translation(0), translation(1)), translation(2))

  /** The principal Rodrigues rotation vector, with length in `[0, pi]` radians.
    *
    * At exactly pi the axis sign is ambiguous; only the represented rotation is guaranteed to round-trip. A
    * largest-component quaternion extraction avoids dividing by sin(angle) near zero or pi.
    */
  def rotationVector: Seq[Double] =
    val r = rotation
    val trace = r(0)(0) + r(1)(1) + r(2)(2)
    var w = 0.0
    var x = 0.0
    var y = 0.0
    var z = 0.0
    if trace >= 0.0 then
      val s = 2.0 * math.sqrt(1.0 + trace)
      w = s / 4.0
      x = (r(2)(1) - r(1)(2)) / s
      y = (r(0)(2) - r(2)(0)) / s
      z = (r(1)(0) - r(0)(1)) / s
    else if r(0)(0) >= r(1)(1) && r(0)(0) >= r(2)(2) then
      val s = 2.0 * math.sqrt(1.0 + r(0)(0) - r(1)(1) - r(2)(2))
      w = (r(2)(1) - r(1)(2)) / s
      x = s / 4.0
      y = (r(0)(1) + r(1)(0)) / s
      z = (r(0)(2) + r(2)(0)) / s
    else if r(1)(1) >= r(2)(2) then
      val s = 2.0 * math.sqrt(1.0 + r(1)(1) - r(0)(0) - r(2)(2))
      w = (r(0)(2) - r(2)(0)) / s
      x = (r(0)(1) + r(1)(0)) / s
      y = s / 4.0
      z = (r(1)(2) + r(2)(1)) / s
    else
      val s = 2.0 * math.sqrt(1.0 + r(2)(2) - r(0)(0) - r(1)(1))
      w = (r(1)(0) - r(0)(1)) / s
      x = (r(0)(2) + r(2)(0)) / s
      y = (r(1)(2) + r(2)(1)) / s
      z = s / 4.0
    val sineHalf = math.hypot(math.hypot(x, y), z)
    if sineHalf == 0.0 then Vector(0.0, 0.0, 0.0)
    else
      val sign = if w < 0.0 then -1.0 else 1.0
      val scale = sign * 2.0 * math.atan2(sineHalf, math.abs(w)) / sineHalf
      Vector(x * scale, y * scale, z * scale)

  /** Applies `other` first, then this transform, like function composition.
    *
    * If `other` maps A → B and this transform maps B → C, the result maps A → C: rotation `R_this * R_other`,
    * translation `R_this * t_other + t_this`. Computed rotations are re-orthonormalized to prevent accepted
    * input rounding from accumulating beyond the construction tolerance. Frames and units must agree at B; in
    * particular, a monocular unit direction is not a metric baseline. A non-finite computed translation is
    * rejected, just like a non-finite input.
    */
  def compose(other: RigidTransform): RigidTransform =
    val shifted = transformPoint(Point3(other.translation(0), other.translation(1), other.translation(2)))
    val composed = Vector.tabulate(3, 3)((i, j) =>
      rotation(i)(0) * other.rotation(0)(j) +
        rotation(i)(1) * other.rotation(1)(j) + rotation(i)(2) * other.rotation(2)(j)
    )
    RigidTransform(RigidTransform.orthonormalized(composed), Vector(shifted.x, shifted.y, shifted.z))

  /** Reverses this mapping: rotation `Rᵀ`, translation `-Rᵀ * t`, from destination back to source. The
    * computed rotation is re-orthonormalized within input rounding precision; translation uses that corrected
    * rotation. This prevents a valid approximate input from failing the inverse's validation.
    */
  def inverse: RigidTransform =
    val reversed = RigidTransform.orthonormalized(Vector.tabulate(3, 3)((i, j) => rotation(j)(i)))
    RigidTransform(
      reversed,
      Vector.tabulate(3)(i =>
        -(reversed(i)(0) * translation(0) + reversed(i)(1) * translation(1) +
          reversed(i)(2) * translation(2))
      )
    )

  /** Maps a point from the source frame into the destination frame, including translation. */
  def transformPoint(point: Point3): Point3 =
    Point3(
      rotation(0)(0) * point.x + rotation(0)(1) * point.y + rotation(0)(2) * point.z + translation(0),
      rotation(1)(0) * point.x + rotation(1)(1) * point.y + rotation(1)(2) * point.z + translation(1),
      rotation(2)(0) * point.x + rotation(2)(1) * point.y + rotation(2)(2) * point.z + translation(2)
    )

object RigidTransform:

  private val RotationTolerance = 1e-8

  /** Gram–Schmidt on computed near-rotation rows, followed by a right-handed cross product. Inputs come only
    * from accepted rotations or their products, so the rows are finite and far from degenerate. Never use
    * this to accept invalid user input: construction still validates the original matrix.
    */
  private def orthonormalized(rotation: Seq[Seq[Double]]): Seq[Seq[Double]] =
    def normalized(row: Seq[Double]): Seq[Double] =
      val norm = math.hypot(math.hypot(row(0), row(1)), row(2))
      row.map(_ / norm)
    val first = normalized(rotation(0))
    val projection = first.zip(rotation(1)).map(_ * _).sum
    val second = normalized(rotation(1).zip(first).map((value, axis) => value - projection * axis))
    val third = normalized(
      Vector(
        first(1) * second(2) - first(2) * second(1),
        first(2) * second(0) - first(0) * second(2),
        first(0) * second(1) - first(1) * second(0)
      )
    )
    Vector(first, second, third)

  private def validate(rotation: Seq[Seq[Double]], translation: Seq[Double]): Unit =
    require(rotation.sizeIs == 3 && rotation.forall(_.sizeIs == 3), "rotation must be a 3×3 matrix")
    require(translation.sizeIs == 3, "translation must contain three values")
    require(rotation.forall(_.forall(_.isFinite)), "rotation values must be finite")
    require(translation.forall(_.isFinite), "translation values must be finite")
    var i = 0
    while i < 3 do
      var j = i
      while j < 3 do
        val dot = rotation(i)(0) * rotation(j)(0) + rotation(i)(1) * rotation(j)(1) +
          rotation(i)(2) * rotation(j)(2)
        val expected = if i == j then 1.0 else 0.0
        require(math.abs(dot - expected) <= RotationTolerance, "rotation must be orthonormal")
        j += 1
      i += 1
    val determinant =
      rotation(0)(0) * (rotation(1)(1) * rotation(2)(2) - rotation(1)(2) * rotation(2)(1)) -
        rotation(0)(1) * (rotation(1)(0) * rotation(2)(2) - rotation(1)(2) * rotation(2)(0)) +
        rotation(0)(2) * (rotation(1)(0) * rotation(2)(1) - rotation(1)(1) * rotation(2)(0))
    require(math.abs(determinant - 1.0) <= RotationTolerance, "rotation must have determinant +1")

  /** Builds a transform from a Rodrigues vector: direction is the right-handed axis, length is radians.
    *
    * Conversion is pure Scala, including very small angles; no OpenCV library needs to be loaded. Both
    * vectors must have three finite components, and the rotation-vector length must be finite.
    */
  def fromRotationVector(rvec: Seq[Double], translation: Seq[Double]): RigidTransform =
    require(rvec.sizeIs == 3, "a rotation vector must contain three values")
    require(rvec.forall(_.isFinite), "rotation vector values must be finite")
    val angle = math.hypot(math.hypot(rvec(0), rvec(1)), rvec(2))
    require(angle.isFinite, "rotation vector length must be finite")
    if angle == 0.0 then RigidTransform(identity.rotation, translation)
    else
      val x = rvec(0) / angle
      val y = rvec(1) / angle
      val z = rvec(2) / angle
      val sine = math.sin(angle)
      val cosine = math.cos(angle)
      // 1 - cos(angle) loses the quadratic term near zero; this half-angle form does not.
      val halfSine = math.sin(angle / 2.0)
      val complement = 2.0 * halfSine * halfSine
      RigidTransform(
        Vector(
          Vector(cosine + x * x * complement, x * y * complement - z * sine, x * z * complement + y * sine),
          Vector(y * x * complement + z * sine, cosine + y * y * complement, y * z * complement - x * sine),
          Vector(z * x * complement - y * sine, z * y * complement + x * sine, cosine + z * z * complement)
        ),
        translation
      )

  /** The mapping that leaves every point unchanged. */
  val identity: RigidTransform = RigidTransform(
    Vector(Vector(1.0, 0.0, 0.0), Vector(0.0, 1.0, 0.0), Vector(0.0, 0.0, 1.0)),
    Vector(0.0, 0.0, 0.0)
  )
