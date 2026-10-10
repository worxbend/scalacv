package scalacv

import java.nio.file.Files

import org.opencv.core.Mat

import scalacv.graphs.*
import scalacv.vision.*

/** Scope ownership must survive consuming extensions in optional modules. */
class ScopedImageIntegrationTest extends munit.FunSuite:
  override def beforeAll(): Unit = OpenCv.load()

  private def withInput[A](use: String => A): A =
    val path = Files.createTempFile("scalacv-scope-integration", ".png")
    try
      assertEquals(Image.blank(8, 8, Scalar.White).write(path.toString), Right(()))
      use(path.toString)
    finally Files.deleteIfExists(path): Unit

  test("reading releases the successor of the vision background blur extension"):
    withInput: path =>
      val mask = Image.blank(8, 8, Scalar.White, channels = 1)
      var observed = Option.empty[Mat]
      try
        val result = Image.reading(path): image =>
          val blurred = image.blurBackground(mask, strength = 1, feather = 0)
          observed = Some(blurred.mat)
          blurred.width
        assertEquals(result, Right(8))
        assertEquals(observed.get.dataAddr(), 0L)
        assertEquals(mask.width, 8, "a borrowed mask must stay alive")
      finally
        observed.foreach(_.release())
        mask.close()

  test("reading releases replacement output but not borrowed background and mask"):
    withInput: path =>
      val mask = Image.blank(8, 8, Scalar.White, channels = 1)
      val background = Image.blank(8, 8, Scalar.Black)
      var observed = Option.empty[Mat]
      try
        val result = Image.reading(path): image =>
          val replaced = image.replaceBackground(mask, background, feather = 0)
          observed = Some(replaced.mat)
          replaced.height
        assertEquals(result, Right(8))
        assertEquals(observed.get.dataAddr(), 0L)
        assertEquals(mask.width, 8)
        assertEquals(background.width, 8)
      finally
        observed.foreach(_.release())
        background.close()
        mask.close()

  test("reading follows graphics paint ownership into a later image transform"):
    withInput: path =>
      var observed = Option.empty[Mat]
      try
        val result = Image.reading(path): image =>
          val gray = image.draw(Picture.circle(Point(4, 4), 2)).gray
          observed = Some(gray.mat)
          gray.width
        assertEquals(result, Right(8))
        assertEquals(observed.get.dataAddr(), 0L)
      finally observed.foreach(_.release())

  test("animation closes a transformed canvas when its callback fails"):
    var observed = Option.empty[Mat]
    val failure = IllegalArgumentException("callback failed")
    try
      val thrown = intercept[IllegalArgumentException]:
        Animation.foreach(1, 8, 8)(_ => Picture.empty): canvas =>
          val gray = canvas.gray
          observed = Some(gray.mat)
          throw failure
      assert(thrown eq failure)
      assertEquals(observed.get.dataAddr(), 0L)
    finally observed.foreach(_.release())
