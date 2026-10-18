package scalacv

import java.nio.file.{Files, Path}

import org.opencv.core.Mat

/** The ownership promises [[Image]] makes on its *failure* paths.
  *
  * The happy-path discipline — a transform spends, a query borrows, a terminal releases — is pinned in
  * `ImageTest`. What is pinned here is the half that only shows when something goes wrong: a transform that
  * fails inside OpenCV must still free its source, a terminal that answers `Left` must still release, a
  * precondition that fires before the Mat is touched must *not* spend the image, and a borrowed argument must
  * come back alive. Kept as its own suite on purpose (see `docs/mdoc/testing.md`): a regression in any of
  * these is the kind that segfaults, and a crash here names the offender instead of taking a shared JVM down.
  *
  * `dataAddr() == 0L` is the leak predicate: it is the native buffer pointer itself, which `Mat.release`
  * drops, so it says whether the memory was freed rather than whether RSS happened to shrink.
  */
class ImageOwnershipTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  private def tempDir(): Path =
    val d = Files.createTempDirectory("scalacv-image-own")
    d.toFile.deleteOnExit()
    d

  test(
    "a transform that fails natively surfaces CvError.NativeCall naming the op, spends the image and frees the source Mat"
  ):
    // Three channels: equalizeHist accepts CV_8UC1 only, so the failure comes from inside OpenCV, past every
    // check this library runs itself — the path `transform`'s finally exists for.
    val img = Image.blank(16, 16)
    val raw = img.mat
    val e = intercept[CvError.NativeCall](img.equalizeHist)
    assertEquals(e.operation, "equalizeHist")
    intercept[IllegalStateException](img.width)
    assertEquals(raw.dataAddr(), 0L, "the source Mat must be freed when the op throws")

  test(
    "an Image-level precondition failure leaves the image usable — the checks that run before the Mat is touched"
  ):
    val other = Image.blank(16, 16)
    try
      val rejected: Seq[(String, Image => Any)] = Seq(
        "blur(-1)" -> (_.blur(-1)),
        "scale(0)" -> (_.scale(0)),
        "crop off-image" -> (_.crop(Rect(0, 0, 99, 99))),
        "medianBlur(0)" -> (_.medianBlur(0)),
        "blend weight 1.5" -> (_.blend(other, 1.5))
      )
      for (label, call) <- rejected do
        val img = Image.blank(16, 16)
        intercept[IllegalArgumentException](call(img))
        assertEquals(img.width, 16, s"$label must not spend the image")
        img.close()
    finally other.close()
    val zeroWidth = intercept[IllegalArgumentException](Image.blank(0, 5))
    assert(zeroWidth.getMessage.contains("positive size"), zeroWidth.getMessage)
    val negativeHeight = intercept[IllegalArgumentException](Image.blank(5, -1))
    assert(negativeHeight.getMessage.contains("positive size"), negativeHeight.getMessage)

  test(
    "a failed terminal still releases: bytes with an unknown format and write into a missing directory are EncodeFailed and the image is spent"
  ):
    val img = Image.blank(8, 8)
    val raw = img.mat
    img.bytes(".notaformat") match
      case Left(_: CvError.EncodeFailed) => ()
      case other => fail(s"expected EncodeFailed, got $other")
    intercept[IllegalStateException](img.width)
    assertEquals(raw.dataAddr(), 0L, "bytes must release even when it answers Left")

    val img2 = Image.blank(8, 8)
    val raw2 = img2.mat
    val unwritable = tempDir().resolve("no-such-subdir").resolve("x.png").toString
    img2.write(unwritable) match
      case Left(_: CvError.EncodeFailed) => ()
      case other => fail(s"expected EncodeFailed, got $other")
    intercept[IllegalStateException](img2.width)
    assertEquals(raw2.dataAddr(), 0L, "write must release even when it answers Left")

  test(
    "applyMask, blend, inpaint and seamlessCloneInto borrow their arguments, which stay usable afterwards"
  ):
    // A small interior region rather than an all-white mask: inpaint and seamlessClone over a mask that covers
    // the whole source, or touches its border, is not a well-defined OpenCV input.
    val mask = Image
      .blank(20, 20, Scalar.Black, channels = 1)
      .drawRect(Rect(8, 8, 4, 4), Scalar.White, Thickness.Filled)
    val other = Image.blank(20, 20, Scalar(1, 2, 3))
    val background = Image.blank(40, 40)

    def assertAlive(label: String, arg: Image)(using munit.Location): Unit =
      assertEquals(arg.width, 20, s"$label must not be consumed") // an IllegalStateException if it was
      assert(arg.mat.dataAddr() != 0L, s"$label must keep its native buffer")

    Image.blank(20, 20).applyMask(mask).close()
    assertAlive("applyMask's mask", mask)

    Image.blank(20, 20).blend(other, 0.5).close()
    assertAlive("blend's other", other)

    Image.blank(20, 20).inpaint(mask).close()
    assertAlive("inpaint's mask", mask)

    Image.blank(20, 20).seamlessCloneInto(background, mask, Point(20, 20)).close()
    assertEquals(background.width, 40, "seamlessCloneInto's background must not be consumed")
    assert(background.mat.dataAddr() != 0L, "seamlessCloneInto's background must keep its native buffer")
    assertAlive("seamlessCloneInto's mask", mask)

    // Exactly once each, and not in a `finally`: close is idempotent, so a finally-close would pass even if
    // the op had already spent the argument.
    mask.close()
    other.close()
    background.close()

  test(
    "an Image over an empty Mat reports isEmpty and 0×0, prints safely before and after close, and its terminals answer Left without throwing"
  ):
    val img = Image.wrap(Managed(Mat()))
    assert(img.isEmpty)
    assertEquals((img.width, img.height), (0, 0))
    assertEquals(img.size, Size(0, 0))
    assert(img.toString.startsWith("Image(0x0"), img.toString)
    intercept[CvError.NativeCall](img.gray) // cvtColor asserts on an empty source
    intercept[IllegalStateException](img.width)
    assertEquals(img.toString, "Image(<closed>)")

    // A Left of any CvError: today imencode's rejection surfaces through Cv.attempt as NativeCall, and which
    // case it is belongs to Images.encode, not to the terminal's release promise.
    val img2 = Image.wrap(Managed(Mat()))
    assert(img2.bytes().isLeft, "encoding an empty Mat must be a Left, not a throw")
    intercept[IllegalStateException](img2.width)

    // Encoding completes before the destination is touched, so a failed encode leaves no file behind.
    val target = tempDir().resolve("empty.png")
    assert(Managed.use(Mat())(m => Images.write(target.toString, m)).isLeft)
    assert(!Files.exists(target), s"a failed encode must not create $target")

    val blank = Image.blank(16, 16)
    try assertEquals(blank.toString, "Image(16x16, 3ch)")
    finally blank.close()

  test(
    "Image.reading turns a NativeCall thrown inside the body into a Left, lets programmer errors escape, closes even after the body consumed the image, and never runs the body for an unreadable path"
  ):
    // A file rather than a directory under deleteOnExit: a non-empty directory never gets deleted on exit.
    val png = Files.createTempFile("scalacv-image-own-", ".png")
    try
      // Three channels, so equalizeHist fails inside OpenCV rather than in a precondition.
      assertEquals(Image.blank(32, 24, Scalar(30, 30, 30)).write(png.toString), Right(()))

      Image.reading(png.toString)(_.equalizeHist) match
        case Left(e: CvError.NativeCall) => assertEquals(e.operation, "equalizeHist")
        case other => fail(s"expected the body's NativeCall as a Left, got $other")

      intercept[IllegalArgumentException](Image.reading(png.toString)(_.blur(-1)))

      val width = Image.reading(png.toString): img =>
        val g = img.gray // spends `img`; reading's own close afterwards must be a harmless no-op
        try g.width
        finally g.close()
      assertEquals(width, Right(32))

      var ran = false
      val missing = Image.reading("/does/not/exist.png"): _ =>
        ran = true
        0
      missing match
        case Left(_: CvError.DecodeFailed) => ()
        case other => fail(s"expected DecodeFailed, got $other")
      assert(!ran, "the body must not run when there is no image to hand it")
    finally Files.deleteIfExists(png)
