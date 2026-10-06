package scalacv

import scalacv.vision.*

import java.nio.file.Files

/** Face recognition: the embedding metrics and Gallery lookup (no model needed), load-error handling, and a
  * model-gated end-to-end smoke that runs only when SCALACV_SFACE_MODEL points to the SFace ONNX.
  */
class FaceRecognizerTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  private def emb(xs: Float*): FaceEmbedding = FaceEmbedding(xs.toVector)

  test("cosineSimilarity is 1 for identical, 0 for orthogonal, -1 for opposite"):
    assertEqualsDouble(emb(1, 0, 0).cosineSimilarity(emb(2, 0, 0)), 1.0, 1e-9)
    assertEqualsDouble(emb(1, 0).cosineSimilarity(emb(0, 1)), 0.0, 1e-9)
    assertEqualsDouble(emb(1, 0).cosineSimilarity(emb(-1, 0)), -1.0, 1e-9)

  test("l2Distance is 0 for identical and the Euclidean norm otherwise"):
    assertEqualsDouble(emb(1, 2, 3).l2Distance(emb(1, 2, 3)), 0.0, 1e-9)
    assertEqualsDouble(emb(0, 0).l2Distance(emb(3, 4)), 5.0, 1e-9)

  test("comparing embeddings of different lengths is rejected"):
    intercept[IllegalArgumentException](emb(1, 2).cosineSimilarity(emb(1, 2, 3)))

  test("Gallery.identify returns the closest enrolled face above the threshold"):
    val gallery = Gallery.empty
      .enroll("ada", emb(1, 0, 0))
      .enroll("grace", emb(0, 1, 0))
    // A probe close to ada.
    gallery.identify(emb(0.9f, 0.1f, 0f)) match
      case Some(FaceMatch(name, s)) =>
        assertEquals(name, "ada")
        assert(s > 0.9, s"similarity should be high, got $s")
      case None => fail("expected a match")

  test("Gallery.identify returns None for a stranger below the threshold"):
    val gallery = Gallery.empty.enroll("ada", emb(1, 0, 0))
    assertEquals(gallery.identify(emb(0, 1, 0)), None) // orthogonal → similarity 0 < 0.363

  test("Gallery is immutable — enroll returns a new gallery"):
    val g0 = Gallery.empty
    val g1 = g0.enroll("ada", emb(1, 0))
    assertEquals(g0.size, 0)
    assertEquals(g1.size, 1)
    assertEquals(g1.names, Seq("ada"))

  test("the SFace model pins match the published artifact and a fetch-safe mirror"):
    // The mirror host is the whole point: github.com/.../raw serves the 131-byte LFS pointer for .onnx
    // files, so any URL that is not media.githubusercontent.com is a broken download waiting to happen.
    assert(
      FaceRecognizer.ModelUrls.nonEmpty && FaceRecognizer.ModelUrls.forall(
        _.startsWith("https://media.githubusercontent.com/media/opencv/opencv_zoo/")
      ),
      s"every mirror must serve LFS bytes, got ${FaceRecognizer.ModelUrls}"
    )
    assert(FaceRecognizer.ModelUrls.head != FaceRecognizer.ModelUrls.last, "a pinned and a floating URL")
    assertEquals(
      FaceRecognizer.ModelSha256,
      "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79"
    )
    assertEquals(FaceRecognizer.ModelSizeBytes, 38696353L)
    assertEquals(FaceRecognizer.EmbeddingSize, 128)

  test("the recognizer's face row shares the detector's YuNet layout constants"):
    // faceRow builds Mat(1, FaceDetect.ResultColumns) from box + landmarks + score; if the two files ever
    // disagree about the row format this identity breaks first.
    assertEquals(FaceDetect.ResultColumns, 4 + 2 * FaceDetect.LandmarkCount + 1)
    val face = Face(
      box = Rect(1, 2, 3, 4),
      landmarks = (0 until FaceDetect.LandmarkCount).map(i => Point(i * 2.0, i * 2.0 + 1)),
      score = 0.5f
    )
    Managed.use(FaceRecognizer.faceRow(face)): row =>
      assertEquals((row.rows, row.cols), (1, FaceDetect.ResultColumns))
      val flat = Array.ofDim[Float](FaceDetect.ResultColumns)
      row.get(0, 0, flat)
      assertEquals(flat.take(4).toSeq, Seq(1f, 2f, 3f, 4f))
      assertEquals(flat(4), 0f); assertEquals(flat(5), 1f) // first landmark (x=0, y=1)
      assertEquals(flat.last, 0.5f)

  test("load rejects a missing path with a Left, not an exception"):
    assert(FaceRecognizer.load("/no/such/sface.onnx").isLeft)

  test("load rejects a file that is not an SFace network"):
    val junk = Files.createTempFile("scalacv-not-sface", ".onnx")
    Files.write(junk, "not a model".getBytes)
    try assert(FaceRecognizer.load(junk.toString).isLeft)
    finally Files.deleteIfExists(junk)

  test("load on an empty file, or a text file masquerading as .onnx, is a Left naming the path"):
    // Pins the null/empty guard the shared ModelLoader added: before it, a factory that returned null for
    // these files would have been wrapped into a Right(null-backed recognizer). Whatever the failure mode —
    // thrown CvException or null return — the caller must get a Left that names the file it blamed.
    val empty = Files.createTempFile("scalacv-empty", ".onnx")
    val text = Files.createTempFile("scalacv-text", ".onnx")
    try
      Files.writeString(text, "this is plain text, not an ONNX model")
      Seq(empty, text).foreach: f =>
        FaceRecognizer.load(f.toString) match
          case Left(e) =>
            assert(e.getMessage.contains(f.toString), s"the error must name the path: ${e.getMessage}")
          case Right(r) =>
            r.close()
            fail(s"load accepted $f")
    finally
      Files.deleteIfExists(empty)
      Files.deleteIfExists(text)

  test("end to end: the same face embeds consistently (needs SCALACV_SFACE_MODEL + network)"):
    val model = sys.env.get("SCALACV_SFACE_MODEL")
    assume(model.isDefined, "set SCALACV_SFACE_MODEL to the SFace ONNX to run this test")
    val detectorModel = FaceDetect.downloadModel(Files.createTempDirectory("scalacv-sface-yunet"))
    assume(detectorModel.isRight, "the YuNet model download is needed and failed")

    FaceRecognizer.load(model.get) match
      case Left(e) => fail(s"could not load the supplied SFace model: $e")
      case Right(recognizer) =>
        try
          FaceDetect.create(detectorModel.toOption.get.toString, Size(320, 320)) match
            case Left(e) => fail(s"could not build the detector: $e")
            case Right(detector) =>
              try
                val scene = drawFaceScene()
                try
                  val faces = scene.faces(detector.get)
                  assume(faces.nonEmpty, "the synthetic face was not detected")
                  val e1 = recognizer.embed(scene, faces.head)
                  assertEquals(e1.values.size, 128)
                  assertEqualsDouble(e1.cosineSimilarity(e1), 1.0, 1e-6)
                finally scene.close()
              finally detector.release()
        finally recognizer.close()

  /** A crude frontal face YuNet will accept — the same construction FaceDetectTest uses. */
  private def drawFaceScene(): Image =
    val img = Image.blank(320, 320, Scalar(200, 200, 200))
    img.mat.drawCircle(Point(160, 160), 70, Scalar(180, 160, 150), Thickness.Filled)
    img.mat.drawCircle(Point(135, 140), 10, Scalar.Black, Thickness.Filled)
    img.mat.drawCircle(Point(185, 140), 10, Scalar.Black, Thickness.Filled)
    img.mat.drawCircle(Point(160, 165), 8, Scalar(120, 100, 90), Thickness.Filled)
    img.mat.drawLine(Point(140, 190), Point(180, 190), Scalar.Black, Thickness.Stroke(3))
    img
