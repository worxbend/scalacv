package scalacv

/** Pure geometry laws: no OpenCV loader or native resources are needed. */
class RigidTransformTest extends munit.FunSuite:

  private def assertPoint(actual: Point3, expected: Point3, tolerance: Double = 1e-12): Unit =
    assertEqualsDouble(actual.x, expected.x, tolerance, "x")
    assertEqualsDouble(actual.y, expected.y, tolerance, "y")
    assertEqualsDouble(actual.z, expected.z, tolerance, "z")

  test("identity maps a source point into the same destination coordinates"):
    val point = Point3(2.5, -3.0, 7.0)
    assertPoint(RigidTransform.identity.transformPoint(point), point)

  test("construction rejects malformed dimensions and non-finite components"):
    val identity = RigidTransform.identity.rotation
    val zero = Seq(0.0, 0.0, 0.0)
    intercept[IllegalArgumentException](RigidTransform(identity.take(2), zero))
    intercept[IllegalArgumentException](RigidTransform(identity.updated(1, Seq(0.0, 1.0)), zero))
    intercept[IllegalArgumentException](RigidTransform(identity, zero.take(2)))
    intercept[IllegalArgumentException](RigidTransform(identity, zero :+ 0.0))
    for bad <- Seq(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity) do
      intercept[IllegalArgumentException](RigidTransform(identity.updated(0, Seq(bad, 0.0, 0.0)), zero))
      intercept[IllegalArgumentException](RigidTransform(identity, Seq(0.0, bad, 0.0)))

  test("construction requires an orthonormal proper rotation, not a scale, shear or reflection"):
    val zero = Seq(0.0, 0.0, 0.0)
    for invalid <- Seq(
        Seq(Seq(2.0, 0.0, 0.0), Seq(0.0, 1.0, 0.0), Seq(0.0, 0.0, 1.0)),
        Seq(Seq(1.0, 0.1, 0.0), Seq(0.0, 1.0, 0.0), Seq(0.0, 0.0, 1.0)),
        Seq(Seq(-1.0, 0.0, 0.0), Seq(0.0, 1.0, 0.0), Seq(0.0, 0.0, 1.0))
      )
    do intercept[IllegalArgumentException](RigidTransform(invalid, zero))

  test("inverse reverses a rotated and translated mapping using -R transpose t"):
    val transform = RigidTransform(
      Seq(Seq(0.0, -1.0, 0.0), Seq(1.0, 0.0, 0.0), Seq(0.0, 0.0, 1.0)),
      Seq(1.0, 2.0, 3.0)
    )
    assertEquals(transform.inverse.translation, Seq(-2.0, 1.0, -3.0))
    val source = Point3(2.0, 4.0, 8.0)
    assertPoint(transform.transformPoint(source), Point3(-3.0, 4.0, 11.0))
    assertPoint(transform.inverse.transformPoint(transform.transformPoint(source)), source)
    assertPoint(transform.transformPoint(transform.inverse.transformPoint(source)), source)
    assertEquals(transform.inverse.inverse, transform)

  test("compose applies the argument first, then the receiver, and is not commutative"):
    val turn = RigidTransform(
      Seq(Seq(0.0, -1.0, 0.0), Seq(1.0, 0.0, 0.0), Seq(0.0, 0.0, 1.0)),
      Seq(0.0, 0.0, 0.0)
    )
    val shift = RigidTransform(RigidTransform.identity.rotation, Seq(2.0, 0.0, 0.0))
    val point = Point3(1.0, 0.0, 0.0)
    assertPoint(turn.compose(shift).transformPoint(point), Point3(0.0, 3.0, 0.0))
    assertPoint(shift.compose(turn).transformPoint(point), Point3(2.0, 1.0, 0.0))
    assertPoint(turn.compose(shift).transformPoint(point), turn.transformPoint(shift.transformPoint(point)))
    assertEquals(turn.compose(RigidTransform.identity), turn)
    assertEquals(RigidTransform.identity.compose(shift), shift)
    assertPoint(turn.compose(turn.inverse).transformPoint(point), point)
    assertPoint(turn.inverse.compose(turn).transformPoint(point), point)

  test("Rodrigues vectors use radians and the right-handed axis-angle convention"):
    val zero = Seq(0.0, 0.0, 0.0)
    assertEquals(RigidTransform.fromRotationVector(zero, Seq(1.0, 2.0, 3.0)).translation, Seq(1.0, 2.0, 3.0))
    assertEquals(RigidTransform.fromRotationVector(zero, zero), RigidTransform.identity)
    val quarter = math.Pi / 2.0
    assertPoint(
      RigidTransform
        .fromRotationVector(Seq(quarter, 0.0, 0.0), zero)
        .transformPoint(Point3(0.0, 1.0, 0.0)),
      Point3(0.0, 0.0, 1.0)
    )
    assertPoint(
      RigidTransform
        .fromRotationVector(Seq(0.0, quarter, 0.0), zero)
        .transformPoint(Point3(0.0, 0.0, 1.0)),
      Point3(1.0, 0.0, 0.0)
    )
    assertPoint(
      RigidTransform
        .fromRotationVector(Seq(0.0, 0.0, quarter), zero)
        .transformPoint(Point3(1.0, 0.0, 0.0)),
      Point3(0.0, 1.0, 0.0)
    )
    assertPoint(
      RigidTransform
        .fromRotationVector(Seq(0.0, 0.0, -quarter), zero)
        .transformPoint(Point3(1.0, 0.0, 0.0)),
      Point3(0.0, -1.0, 0.0)
    )

  test("Rodrigues input rejects malformed and non-finite vectors before conversion"):
    val zero = Seq(0.0, 0.0, 0.0)
    intercept[IllegalArgumentException](RigidTransform.fromRotationVector(Seq(0.0, 0.0), zero))
    for bad <- Seq(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity) do
      intercept[IllegalArgumentException](RigidTransform.fromRotationVector(Seq(0.0, bad, 0.0), zero))
    intercept[IllegalArgumentException](RigidTransform.fromRotationVector(zero, Seq(0.0, 0.0)))
    intercept[IllegalArgumentException](RigidTransform.fromRotationVector(zero, Seq(0.0, Double.NaN, 0.0)))
    intercept[IllegalArgumentException](RigidTransform.fromRotationVector(Seq.fill(3)(Double.MaxValue), zero))

  test("rotation vectors round-trip at zero, small angles and both sides of pi"):
    val zero = Seq(0.0, 0.0, 0.0)
    assertEquals(RigidTransform.identity.rotationVector, zero)
    val axes = Seq(Seq(1.0, 0.0, 0.0), Seq(0.0, -1.0, 0.0), Seq(0.0, 0.0, 1.0), Seq(-1.0, 2.0, -3.0))
    for axis <- axes do
      val norm = math.hypot(math.hypot(axis(0), axis(1)), axis(2))
      for angle <- Seq(1e-12, 1e-7, 0.7, math.Pi - 1e-8, math.Pi, math.Pi + 1e-8, 4.0) do
        val rvec = axis.map(_ * angle / norm)
        val transform = RigidTransform.fromRotationVector(rvec, Seq(0.2, -0.5, 4.0))
        val vector = transform.rotationVector
        assert(vector.forall(_.isFinite), s"non-finite rotation vector for $rvec")
        assert(math.hypot(math.hypot(vector(0), vector(1)), vector(2)) <= math.Pi + 1e-14)
        val restored = RigidTransform.fromRotationVector(vector, transform.translation)
        for i <- 0 until 3; j <- 0 until 3 do
          assertEqualsDouble(restored.rotation(i)(j), transform.rotation(i)(j), 1e-12, s"rvec=$rvec ($i,$j)")
        if angle < math.Pi then
          for i <- 0 until 3 do assertEqualsDouble(vector(i), rvec(i), math.max(1e-25, angle * 1e-14))

  test("rotation-vector extraction handles exact pi matrices with mixed-sign axes"):
    // 180 degrees about (1,-1,0)/sqrt(2); a skew-only extraction would return the zero vector.
    val transform = RigidTransform(
      Seq(Seq(0.0, -1.0, 0.0), Seq(-1.0, 0.0, 0.0), Seq(0.0, 0.0, -1.0)),
      Seq(0.0, 0.0, 0.0)
    )
    val restored = RigidTransform.fromRotationVector(transform.rotationVector, transform.translation)
    for i <- 0 until 3; j <- 0 until 3 do
      assertEqualsDouble(restored.rotation(i)(j), transform.rotation(i)(j), 1e-12)

  test("composition is associative and inverse identities hold across general rotations"):
    val random = new scala.util.Random(42L)
    def sample(): RigidTransform = RigidTransform.fromRotationVector(
      Vector.fill(3)(random.nextDouble() * 6.0 - 3.0),
      Vector.fill(3)(random.nextDouble() * 20.0 - 10.0)
    )
    for _ <- 0 until 100 do
      val a = sample()
      val b = sample()
      val c = sample()
      val point = Point3(1.25, -4.5, 7.75)
      assertPoint(a.compose(b).transformPoint(point), a.transformPoint(b.transformPoint(point)), 1e-12)
      assertPoint(
        a.compose(b).compose(c).transformPoint(point),
        a.compose(b.compose(c)).transformPoint(point),
        1e-12
      )
      assertPoint(a.inverse.transformPoint(a.transformPoint(point)), point, 1e-12)
      assertPoint(a.compose(a.inverse).transformPoint(point), point, 1e-12)
      assertPoint(a.inverse.compose(a).transformPoint(point), point, 1e-12)

  test("composition stabilizes accepted near-orthogonal rotations instead of accumulating drift"):
    val near = RigidTransform(
      Vector(Vector(1.0 + 4e-9, 0.0, 0.0), Vector(0.0, 1.0, 0.0), Vector(0.0, 0.0, 1.0)),
      Vector(0.0, 0.0, 0.0)
    )
    val composed = near.compose(near)
    assertPoint(composed.transformPoint(Point3(1.0, 2.0, 3.0)), Point3(1.0, 2.0, 3.0))
    var accumulated = RigidTransform.identity
    for _ <- 0 until 1000 do accumulated = near.compose(accumulated)
    assertPoint(accumulated.transformPoint(Point3(1.0, 2.0, 3.0)), Point3(1.0, 2.0, 3.0))

  test("inverse accepts a near-orthogonal input whose transpose exceeds the input tolerance"):
    val near = RigidTransform(
      Vector(
        Vector(0.7071067854291881, -0.7071067797723338, 0.0),
        Vector(0.7071067854291881, 0.7071067797723338, 0.0),
        Vector(0.0, 0.0, 1.0)
      ),
      Vector(1.0, 2.0, 3.0)
    )
    val inverse = near.inverse
    val point = Point3(2.0, -4.0, 8.0)
    assertPoint(inverse.transformPoint(near.transformPoint(point)), point, 1e-7)
    for i <- 0 until 3; j <- 0 until 3 do
      val dot = inverse.rotation(i).zip(inverse.rotation(j)).map(_ * _).sum
      assertEqualsDouble(dot, if i == j then 1.0 else 0.0, 1e-12)

  test("translation norm avoids overflow and underflow from squaring"):
    val identity = RigidTransform.identity.rotation
    assertEquals(RigidTransform.identity.translationNorm, 0.0)
    assertEquals(RigidTransform(identity, Seq(3.0, 4.0, 0.0)).translationNorm, 5.0)
    assertEqualsDouble(RigidTransform(identity, Seq(3e200, 4e200, 0.0)).translationNorm / 1e200, 5.0, 1e-12)
    assertEqualsDouble(
      RigidTransform(identity, Seq(3e-200, 4e-200, 0.0)).translationNorm / 1e-200,
      5.0,
      1e-12
    )

  test("copy revalidates the rigid-transform contract"):
    intercept[IllegalArgumentException](RigidTransform.identity.copy(translation = Seq(Double.NaN, 0.0, 0.0)))
