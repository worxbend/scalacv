import scalacv.*
import scalacv.vision.*
import scalacv.graphs.*
import scalacv.zio.*
import org.opencv.core.{CvType, Mat}
import _root_.zio.{Runtime, Unsafe, ZIO}

/** Compiled with Scala 3.3.8 and executed on JDK 17 against packaged jars only. */
object ScalaConsumerSmoke:
  def main(args: Array[String]): Unit =
    assert(System.getProperty("java.specification.version") == "17")
    OpenCv.load()
    val image = Picture.circle(Point(16, 16), 8).fillColor(Color.Red).render(32, 32)
    try
      assert(image.width == 32)
      assert(image.qrCodes.isEmpty) // vision extension and JNI, not just a class literal
      val gray = image.copy.gray
      try assert(gray.channels == 1)
      finally gray.close()
    finally image.close()

    val effect = ZIO.scoped {
      for
        mat <- new Mat(8, 8, CvType.CV_8UC1).scoped
        rows <- ZIO.attemptBlocking(mat.rows())
      yield rows
    }
    val rows = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
    }
    assert(rows == 8)
    println("SCALA-CONSUMER-OK")
