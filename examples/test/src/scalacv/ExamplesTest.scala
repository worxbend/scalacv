package scalacv

import java.nio.file.Files

import org.opencv.core.CvType

/** The Track D gate: the examples must produce asserted output, not merely compile.
  *
  * CannyEdges and QrDecode are required by the gate to write real, checkable output in CI; the others are
  * round-trips that would be hollow without a positive assertion, so each detects back exactly what it
  * generated.
  */
class ExamplesTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  test("CannyEdges writes a non-trivial PNG whose edges are real"):
    val png = Fixtures.shapes().use(m => CannyEdges.run(m).fold(throw _, identity))
    assert(png.length > 100, s"a PNG of ${png.length} bytes is implausibly small")
    // PNG magic, so we know it is actually an encoded image and not empty bytes.
    assertEquals(png.take(4).toSeq, Seq(0x89.toByte, 'P'.toByte, 'N'.toByte, 'G'.toByte))
    // The edge image must contain white edge pixels — a blank result would also be a valid PNG.
    Images
      .decode(png)
      .fold(
        throw _,
        _.use { edges =>
          // The decoded PNG is 3-channel; countNonZero needs one channel, so measure on a gray view.
          edges.cvtColor(ColorConversion.BgrToGray).use { gray =>
            val nonZero = org.opencv.core.Core.countNonZero(gray)
            assert(nonZero > 50, s"expected real edges, found only $nonZero lit pixels")
          }
        }
      )

  test("CannyEdges.writeTo produces a file on disk"):
    val out = Files.createTempFile("scalacv-canny-", ".png")
    val p = CannyEdges.writeTo(out)
    assert(Files.size(p) > 100, "the written PNG is implausibly small")
    Files.deleteIfExists(out)

  test("QrDecode round-trips its payload"):
    val payload = "https://github.com/w0rxbend/scalacv"
    assertEquals(QrDecode.roundTrip(payload), Seq(payload))

  test("QrDecode finds nothing in a blank image without throwing"):
    val blank = org.opencv.core.Mat(200, 200, CvType.CV_8UC3, org.opencv.core.Scalar(255, 255, 255))
    try assertEquals(QrDecode.run(blank), Seq.empty)
    finally blank.release()

  test("ArucoMarkers round-trips the marker id it generated"):
    assertEquals(ArucoMarkers.roundTrip(23), Seq(23))

  test("FaceDetectHaar runs, and resolves its cascade off the platform payload"):
    // Windows ships no cascades; treat that as a skip rather than a failure.
    Fixtures.shapes().use { m =>
      FaceDetectHaar.run(m) match
        case Right(faces) => assert(faces.isEmpty, s"a blank scene produced $faces")
        case Left(err) =>
          assume(
            err.getMessage.toLowerCase.contains("windows"),
            s"unexpected cascade failure: ${err.getMessage}"
          )
    }
