package scalacv

import scalacv.vision.*

/** Marker AR: pose recovery from a synthetic marker view, projection round-trip, and the overlays. */
class ArTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  /** A fronto-parallel view of marker id 7: the tag on a white quiet zone, centred in the frame. */
  private def markerScene(): Image =
    val bordered =
      Aruco
        .generateMarker(ArucoDictionary.Dict4x4_50, id = 7, sizePixels = 200)
        .use(_.border(60, 60, 60, 60, color = Scalar.White))
    Image.wrap(bordered)

  /** What [[ExplodingDistortion]] throws — one instance, so a test can assert on identity and so prove the
    * exception was neither swallowed nor replaced on its way out of the method.
    */
  private object Boom extends RuntimeException("simulated native allocation failure in distCoeffs")

  /** A distortion vector that throws the moment OpenCV reads it.
    *
    * `Intrinsics.distCoeffs` is the fourth of the six native acquisitions `Ar.estimatePose` and `Ar.project`
    * each make, and the only documented way it fails is a native allocation failure — `std::bad_alloc`, which
    * the OpenCV bindings hand back as a plain `java.lang.Exception` (see `Cv.attempt`). A 72-byte
    * `MatOfDouble` cannot be made to fail on demand, so this stands in for it and fails at the same instant:
    * `distCoeffs` reads `distortion` only when it splats it into the `MatOfDouble` constructor.
    *
    * The constructor validates finite coefficients, so arm this fault only after building Intrinsics. It then
    * fails on the first read past element zero, inside native acquisition.
    */
  private final class ExplodingDistortion extends scala.collection.immutable.Seq[Double]:
    var armed = false
    def length: Int = 4
    override def isEmpty: Boolean = false
    def apply(i: Int): Double = if !armed || i == 0 then 0.01 else throw Boom
    def iterator: Iterator[Double] = Iterator.range(0, length).map(i => apply(i))

  test("Intrinsics.approx centres the principal point and grows f with a narrower FoV"):
    val narrow = Intrinsics.approx(Size(640, 480), horizontalFovDegrees = 30)
    val wide = Intrinsics.approx(Size(640, 480), horizontalFovDegrees = 90)
    assertEquals((narrow.cx, narrow.cy), (320.0, 240.0))
    assert(narrow.fx > wide.fx, "a narrower field of view means a longer focal length")

  test("a fronto-parallel marker recovers an in-plane flip and a positive distance"):
    val scene = markerScene()
    try
      val intr = Intrinsics.approx(scene.size)
      val markers = scene.arMarkers(intr, markerLength = 0.1)
      assertEquals(markers.size, 1)
      val mp = markers.head
      assertEquals(mp.id, 7)
      // OpenCV's marker frame is y-up, the image frame y-down, so a head-on tag comes back as a ~180°
      // flip about an axis in the marker plane — magnitude ~π with a near-zero z (no in-plane spin).
      assert(math.abs(mp.pose.rvec(2)) < 0.1, s"unexpected in-plane spin: rvec=${mp.pose.rvec}")
      assert(mp.distance > 0, s"the marker is in front of the camera, got ${mp.distance}")
      assert(mp.pose.tvec(2) > 0, "z (depth) should be positive")
    finally scene.close()

  test("projecting the marker's own corners reproduces the detected corners"):
    val scene = markerScene()
    try
      val intr = Intrinsics.approx(scene.size)
      val marker = scene.arucoMarkers().head
      val pose = Ar.estimatePose(marker, markerLength = 0.1, intr).get
      val h = 0.1 / 2
      val model = Seq(Point3(-h, h, 0), Point3(h, h, 0), Point3(h, -h, 0), Point3(-h, -h, 0))
      val reprojected = Ar.project(model, pose, intr)
      marker.corners
        .zip(reprojected)
        .foreach: (observed, projected) =>
          val err = observed.distanceTo(projected)
          assert(err < 2.0, s"reprojection error $err px too large ($observed vs $projected)")
    finally scene.close()

  test("the cube geometry has eight corners and twelve edges over the marker plane"):
    val corners = Ar.cubeCorners(0.1)
    assertEquals(corners.size, 8)
    assertEquals(Ar.cubeEdges.size, 12)
    assert(corners.take(4).forall(_.z == 0.0), "the base sits on the marker plane")
    assert(corners.drop(4).forall(_.z > 0.0), "the top rises toward the camera")

  test("drawMarkerAxes and drawMarkerCube annotate without changing the frame size"):
    val intr = Intrinsics.approx(markerScene().size)
    val axed = markerScene().drawMarkerAxes(intr, markerLength = 0.1)
    try assertEquals((axed.width, axed.height), (320, 320))
    finally axed.close()
    val cubed = markerScene().drawMarkerCube(intr, markerLength = 0.1)
    try assertEquals((cubed.width, cubed.height), (320, 320))
    finally cubed.close()

  test("estimatePose rejects a marker without four corners"):
    intercept[IllegalArgumentException](
      Ar.estimatePose(ArucoMarker(1, Seq(Point(0, 0))), 0.1, Intrinsics.approx(Size(100, 100)))
    )

  test("a throw part-way through acquisition propagates unchanged and leaves Ar usable"):
    // Both entry points acquire six native objects before they can call OpenCV. They used to be plain vals
    // in front of the try/finally that freed them, so a throw from the fourth left the first three with no
    // owner; each is now held by Managed.use, whose finally frees whatever was acquired before the throw.
    //
    // The freeing itself is not observable from here — those Mats never escape the method, so there is no
    // handle to check dataAddr() on, and this test does pass against the old shape. What it does pin is the
    // half of the contract that is observable and that a botched conversion breaks: the caller's exception
    // arrives unmasked (a release that threw on the way out would replace it, which is how a wrongly nested
    // use block usually shows up) and an aborted call leaves nothing behind that stops the next one.
    val scene = markerScene()
    try
      val intr = Intrinsics.approx(scene.size)
      val coefficients = ExplodingDistortion()
      val broken = intr.copy(distortion = coefficients)
      coefficients.armed = true
      val marker = scene.arucoMarkers().head
      assert(
        intercept[RuntimeException](Ar.estimatePose(marker, 0.1, broken)) eq Boom,
        "estimatePose must let the original failure through, not one raised while unwinding"
      )
      val pose = Ar.estimatePose(marker, 0.1, intr).get
      assert(
        intercept[RuntimeException](Ar.project(Ar.cubeCorners(0.1), pose, broken)) eq Boom,
        "project must let the original failure through, not one raised while unwinding"
      )
      assertEquals(Ar.project(Ar.cubeCorners(0.1), pose, intr).size, 8, "an aborted call wedged the next one")
    finally scene.close()
