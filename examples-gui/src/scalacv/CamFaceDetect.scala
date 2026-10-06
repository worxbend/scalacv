package scalacv

import scalacv.vision.*

import javafx.application.Application
import javafx.scene.image.{Image, ImageView}
import javafx.scene.{Group, Scene}
import javafx.stage.Stage
import org.opencv.videoio.VideoCapture

/** The heritage webcam face-detector, on JavaFX.
  *
  * This is the one place a GUI toolkit is allowed: `examples-gui` is never built in CI and never published,
  * because OpenJFX resolves per-host. It ties every native object to a `try`/`finally` and closes the capture
  * when the window closes.
  *
  * It needs a camera and a display, so it is not part of any automated gate — it exists to show the full
  * pipeline, not to be tested here. Run with: `./mill examples-gui.runMain scalacv.CamFaceDetect`.
  */
class CamFaceDetect extends Application:

  override def start(stage: Stage): Unit =
    OpenCv.load()

    val view = ImageView()
    stage.setScene(Scene(Group(view), 640, 480))
    stage.setTitle("scalacv — camera face detect")
    stage.show()

    val capture = VideoCapture(0)
    if !capture.isOpened then
      capture.release()
      sys.error("no camera available on device 0")
    val cascade = Cascades.load(CascadeName.FrontalFaceAlt) match
      case Right(c) => c
      case Left(e) =>
        capture.release()
        sys.error(s"cannot load the face cascade: ${e.getMessage}")

    val timer = new javafx.animation.AnimationTimer:
      override def handle(now: Long): Unit =
        Video.frames(capture) { frames =>
          if frames.hasNext then
            Managed.use(frames.next().clone()) { frame =>
              val faces = frame.detect(cascade.get)
              for f <- faces do frame.drawRect(f, Scalar.Green, Thickness.Stroke(2))
              view.setImage(toFxImage(frame))
            }
        }
    stage.setOnCloseRequest { _ =>
      timer.stop()
      cascade.release()
      capture.release()
    }
    timer.start()

  /** Encodes an OpenCV Mat to a JavaFX Image through PNG bytes — no SwingFXUtils, no AWT. */
  private def toFxImage(mat: org.opencv.core.Mat): Image =
    Images.encode(mat, ".png") match
      case Right(bytes) => Image(java.io.ByteArrayInputStream(bytes))
      case Left(err) => sys.error(s"could not encode frame: ${err.getMessage}")

object CamFaceDetect:
  def main(args: Array[String]): Unit = Application.launch(classOf[CamFaceDetect], args*)
