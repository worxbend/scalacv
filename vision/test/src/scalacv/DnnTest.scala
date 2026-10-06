package scalacv

import scalacv.vision.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.collection.mutable.ArrayBuffer
import scala.util.Using

import org.opencv.core.{CvType, Mat, Scalar as CvScalar}

/** The model under test is *built here*, byte by byte.
  *
  * No fixture file may be committed, and the ONNX model (YuNet) is a build-time download that would make this
  * suite need the network. So [[TinyOnnx]] emits a real ONNX ModelProto — one `Relu` over a declared input
  * shape — as protobuf wire bytes. It is 106 bytes, it costs nothing, and it makes the forward pass genuinely
  * testable: `Relu` has an exact expected output, so an implementation that forwarded the wrong blob, or
  * returned its input unchanged, fails here.
  */
class DnnTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  private val Height = 4
  private val Width = 6

  /** A `Height` x `Width` BGR Mat with a distinct constant in each channel: B=10, G=20, R=30. Distinct is the
    * whole point — a uniform grey would make the channel-order assertions unfalsifiable.
    */
  private def bgrFixture(): Mat = Mat(Height, Width, CvType.CV_8UC3, CvScalar(10, 20, 30))

  private def tempFile(name: String, bytes: Array[Byte]): Path =
    val f = Files.createTempFile("scalacv-dnn-", name)
    f.toFile.deleteOnExit()
    Files.write(f, bytes)

  /** Every float of a blob, in NCHW order. */
  private def floats(blob: Mat): Array[Float] =
    val buf = new Array[Float](blob.total.toInt)
    blob.get(Array(0, 0, 0, 0), buf)
    buf

  /** The value at `(0, channel, y, x)` of a 4-dimensional NCHW blob. */
  private def at(blob: Mat, channel: Int, y: Int, x: Int): Float =
    val w = blob.size(3)
    val h = blob.size(2)
    floats(blob)((channel * h + y) * w + x)

  private def sizes(blob: Mat): Seq[Int] = (0 until blob.dims).map(blob.size)

  // ---------------------------------------------------------------- fromOnnx

  test("fromOnnx on a path that does not exist is a Left, not a throw"):
    val missing = "/nonexistent/scalacv/definitely-not-here.onnx"
    Dnn.fromOnnx(missing) match
      case Left(e: CvError.LoadFailed) => assert(e.getMessage.contains(missing), e.getMessage)
      case Left(e) => fail(s"expected a LoadFailed naming the path, got $e")
      case Right(net) => net.release(); fail("a missing model must not load")

  test("fromOnnx on a directory is a Left"):
    val dir = Files.createTempDirectory("scalacv-dnn-dir")
    dir.toFile.deleteOnExit()
    assert(Dnn.fromOnnx(dir.toString).isLeft, "a directory is not a model")

  test("fromOnnx on a file of random bytes is a Left rather than a crash"):
    val junk = new Array[Byte](4096)
    scala.util.Random(42).nextBytes(junk)
    val f = tempFile("junk.onnx", junk)
    Dnn.fromOnnx(f.toString) match
      case Left(_) => // OpenCV throws from the ONNX importer; the point is that we are still alive.
      case Right(net) => net.release(); fail("4096 random bytes are not an ONNX model")
    // Asserted after the fact: the JVM survived the native failure and can still do work.
    Managed.use(bgrFixture())(m => assertEquals(m.rows, Height))

  test("fromOnnx on an empty file is a Left"):
    val f = tempFile("empty.onnx", Array.emptyByteArray)
    assert(Dnn.fromOnnx(f.toString).isLeft, "a zero-byte file is not an ONNX model")

  test("fromOnnx loads a real ONNX graph and reports its declared output"):
    val f = tempFile("relu.onnx", TinyOnnx.relu(Seq(1, 3, Height, Width)))
    Dnn.fromOnnx(f.toString) match
      case Left(e) => fail(s"the generated model should load: $e")
      case Right(net) =>
        net.use: n =>
          assert(!n.empty(), "a loaded net must have layers")
          // The importer renames nodes but keeps the graph's declared output blob name.
          assertEquals(n.getUnconnectedOutLayersNames.toArray.toSeq, Seq("out"))

  // ----------------------------------------------------------- blobFromImage

  test("blobFromImage produces a 4-dimensional NCHW blob of the requested spatial size"):
    Managed.use(bgrFixture()): src =>
      Using.resource(Dnn.blobFromImage(src, size = Some(Size(20, 10)))): blob =>
        assertEquals(blob.get.dims, 4)
        // Size is (width, height); the blob's trailing dims are (height, width). Deliberately
        // asymmetric numbers so a transposition cannot pass.
        assertEquals(sizes(blob.get), Seq(1, 3, 10, 20))
        assertEquals(blob.get.`type`, CvType.CV_32F)

  test("blobFromImage without a size keeps the source's own spatial size"):
    Managed.use(bgrFixture()): src =>
      Using.resource(Dnn.blobFromImage(src))(blob => assertEquals(sizes(blob.get), Seq(1, 3, Height, Width)))

  test("blobFromImage on a single-channel image produces a single-channel blob"):
    Managed.use(Mat(Height, Width, CvType.CV_8UC1, CvScalar(77))): src =>
      Using.resource(Dnn.blobFromImage(src)): blob =>
        assertEquals(sizes(blob.get), Seq(1, 1, Height, Width))
        assertEqualsFloat(at(blob.get, 0, 0, 0), 77f, 1e-4f)

  test("blobFromImage lays the source channels out as planes, in BGR order"):
    Managed.use(bgrFixture()): src =>
      Using.resource(Dnn.blobFromImage(src)): blob =>
        assertEqualsFloat(at(blob.get, 0, 0, 0), 10f, 1e-4f)
        assertEqualsFloat(at(blob.get, 1, 0, 0), 20f, 1e-4f)
        assertEqualsFloat(at(blob.get, 2, 0, 0), 30f, 1e-4f)

  test("swapRB actually swaps the first and third channel planes"):
    Managed.use(bgrFixture()): src =>
      Using
        .Manager: use =>
          val plain = use(Dnn.blobFromImage(src, swapRB = false))
          val swapped = use(Dnn.blobFromImage(src, swapRB = true))
          // B and R change places; G is untouched. All three assertions matter: without the middle
          // one a blob of all-30s would pass the first, and without the outer ones a no-op would.
          assertEqualsFloat(at(swapped.get, 0, 0, 0), at(plain.get, 2, 0, 0), 1e-4f)
          assertEqualsFloat(at(swapped.get, 1, 0, 0), at(plain.get, 1, 0, 0), 1e-4f)
          assertEqualsFloat(at(swapped.get, 2, 0, 0), at(plain.get, 0, 0, 0), 1e-4f)
          assertEqualsFloat(at(swapped.get, 0, 0, 0), 30f, 1e-4f)
        .get

  test("mean is subtracted before scaleFactor multiplies"):
    Managed.use(bgrFixture()): src =>
      // (10 - 4) * 2 = 12, not 10 * 2 - 4 = 16. The two orders are distinguishable on purpose.
      Using.resource(Dnn.blobFromImage(src, scaleFactor = 2.0, mean = Scalar(4, 0, 0))): blob =>
        assertEqualsFloat(at(blob.get, 0, 0, 0), 12f, 1e-4f)
        assertEqualsFloat(at(blob.get, 1, 0, 0), 40f, 1e-4f)

  test("mean is applied in the blob's channel order — after swapRB, not before"):
    Managed.use(bgrFixture()): src =>
      // Measured, not assumed: OpenCV swaps first and subtracts second, so with swapRB the first
      // mean component meets R (30), not B (10). The two readings are 30 apart here on purpose —
      // pre-swap would leave plane 0 at 30 and drive plane 2 to -20.
      Using.resource(Dnn.blobFromImage(src, mean = Scalar(30, 0, 0), swapRB = true)): blob =>
        assertEqualsFloat(at(blob.get, 0, 0, 0), 0f, 1e-4f)
        assertEqualsFloat(at(blob.get, 1, 0, 0), 20f, 1e-4f)
        assertEqualsFloat(at(blob.get, 2, 0, 0), 10f, 1e-4f)

  test("blobFromImage rejects an empty Mat and a non-positive size"):
    Managed.use(Mat()): empty =>
      intercept[IllegalArgumentException](Dnn.blobFromImage(empty).release())
    Managed.use(bgrFixture()): src =>
      intercept[IllegalArgumentException](Dnn.blobFromImage(src, size = Some(Size(0, 0))).release())

  // ------------------------------------------------------------------ forward

  test("forward runs the graph — a Relu clamps the negatives its input carries"):
    val f = tempFile("relu.onnx", TinyOnnx.relu(Seq(1, 3, Height, Width)))
    Using
      .Manager: use =>
        val net = use(Dnn.fromOnnx(f.toString).fold(e => fail(s"load failed: $e"), identity))
        // mean=50 on the B channel drives that plane to 10 - 50 = -40 and leaves G at 20, R at 30.
        val src = use(Managed(bgrFixture()))
        val blob = use(Dnn.blobFromImage(src.get, mean = Scalar(50, 0, 0)))
        assertEqualsFloat(at(blob.get, 0, 0, 0), -40f, 1e-4f)

        val out = use(Dnn.forward(net.get, blob.get))
        assertEquals(sizes(out.get), Seq(1, 3, Height, Width))
        assertEqualsFloat(at(out.get, 0, 0, 0), 0f, 1e-4f) // clamped
        assertEqualsFloat(at(out.get, 1, 0, 0), 20f, 1e-4f) // untouched
        assertEqualsFloat(at(out.get, 2, 0, 0), 30f, 1e-4f) // untouched
        // The output is a distinct allocation, not an alias of the input we still own.
        assert(out.get.dataAddr != blob.get.dataAddr)
      .get

  test("each forward owns its output — a later run does not rewrite an earlier result"):
    val f = tempFile("relu.onnx", TinyOnnx.relu(Seq(1, 3, Height, Width)))
    Using
      .Manager: use =>
        val net = use(Dnn.fromOnnx(f.toString).fold(e => fail(s"load failed: $e"), identity))
        // Two *different* inputs, which is the whole point: with one input a shared buffer is
        // indistinguishable from two copies, and every assertion below would hold either way.
        val srcA = use(Managed(bgrFixture())) // B=10
        val srcB = use(Managed(Mat(Height, Width, CvType.CV_8UC3, CvScalar(70, 80, 90))))
        val blobA = use(Dnn.blobFromImage(srcA.get))
        val blobB = use(Dnn.blobFromImage(srcB.get))

        val outA = use(Dnn.forward(net.get, blobA.get))
        assertEqualsFloat(at(outA.get, 0, 0, 0), 10f, 1e-4f)
        val outB = use(Dnn.forward(net.get, blobB.get))
        // Two headers onto the net's own reused output blob share an address; two owned copies cannot.
        assert(outA.get.dataAddr != outB.get.dataAddr, "each forward must hand back a buffer of its own")
        assertEqualsFloat(at(outA.get, 0, 0, 0), 10f, 1e-4f) // still A's answer, not overwritten by B's
        assertEqualsFloat(at(outB.get, 0, 0, 0), 70f, 1e-4f)
        // The copy must not flatten the blob back to a 2-D Mat on the way out.
        assertEquals(sizes(outA.get), Seq(1, 3, Height, Width))
      .get

  test("forward by output name gives the same result as forwarding to the end"):
    val f = tempFile("relu.onnx", TinyOnnx.relu(Seq(1, 3, Height, Width)))
    Using
      .Manager: use =>
        val net = use(Dnn.fromOnnx(f.toString).fold(e => fail(s"load failed: $e"), identity))
        val src = use(Managed(bgrFixture()))
        val blob = use(Dnn.blobFromImage(src.get, mean = Scalar(50, 0, 0)))
        val byName = use(Dnn.forward(net.get, blob.get, Some("out")))
        val toEnd = use(Dnn.forward(net.get, blob.get))
        // Distinct buffers first: while the two results aliased the net's own output blob, the float
        // comparison below held for any implementation at all, including a broken one.
        assert(byName.get.dataAddr != toEnd.get.dataAddr, "each forward must hand back a buffer of its own")
        assertEquals(floats(byName.get).toSeq, floats(toEnd.get).toSeq)
      .get

  test("forward with an output name that does not exist is a named CvError, not a crash"):
    val f = tempFile("relu.onnx", TinyOnnx.relu(Seq(1, 3, Height, Width)))
    Using
      .Manager: use =>
        val net = use(Dnn.fromOnnx(f.toString).fold(e => fail(s"load failed: $e"), identity))
        val src = use(Managed(bgrFixture()))
        val blob = use(Dnn.blobFromImage(src.get))
        val e = intercept[CvError.NativeCall](Dnn.forward(net.get, blob.get, Some("no-such-blob")).release())
        assert(e.getMessage.contains("Net.forward"), e.getMessage)
      .get

  test("forward rejects an empty net and an empty blob before reaching native code"):
    Managed.use(bgrFixture()): src =>
      Using.resource(Dnn.blobFromImage(src)): blob =>
        Using.resource(Managed(org.opencv.dnn.Net())(using Dnn.given_Releasable_Net)): empty =>
          intercept[IllegalArgumentException](Dnn.forward(empty.get, blob.get).release())
    val f = tempFile("relu.onnx", TinyOnnx.relu(Seq(1, 3, Height, Width)))
    Using
      .Manager: use =>
        val net = use(Dnn.fromOnnx(f.toString).fold(e => fail(s"load failed: $e"), identity))
        val emptyBlob = use(Managed(Mat()))
        intercept[IllegalArgumentException](Dnn.forward(net.get, emptyBlob.get).release())
      .get

  test("a released Net throws on use instead of segfaulting"):
    val f = tempFile("relu.onnx", TinyOnnx.relu(Seq(1, 3, Height, Width)))
    val net = Dnn.fromOnnx(f.toString).fold(e => fail(s"load failed: $e"), identity)
    net.release()
    net.release() // idempotent: a second delete of the same pointer is undefined behaviour
    intercept[IllegalStateException](net.get)

/** A hand-rolled ONNX ModelProto writer — just enough protobuf to describe one operator.
  *
  * There is no protobuf dependency in this build and adding one to emit 106 bytes would be absurd, so the
  * three wire-format primitives are written out directly. Field numbers come from onnx.proto3: ModelProto
  * {ir_version=1, producer_name=2, graph=7, opset_import=8}, GraphProto {node=1, name=2, input=11,
  * output=12}, NodeProto {input=1, output=2, name=3, op_type=4}, ValueInfoProto {name=1, type=2}, TypeProto
  * {tensor_type=1}, Tensor {elem_type=1, shape=2}, TensorShapeProto {dim=1}, Dimension {dim_value=1}.
  */
private object TinyOnnx:

  /** Base-128 varint, little-endian groups, high bit set on all but the last. */
  private def varint(value: Long): Array[Byte] =
    val out = ArrayBuffer.empty[Byte]
    var x = value
    var more = true
    while more do
      var chunk = (x & 0x7f).toInt
      x = x >>> 7
      more = x != 0
      if more then chunk |= 0x80
      out += chunk.toByte
    out.toArray

  private def cat(parts: Array[Byte]*): Array[Byte] = Array.concat(parts*)

  /** Wire type 0: a varint field. */
  private def vi(field: Int, value: Long): Array[Byte] = cat(varint(field << 3), varint(value))

  /** Wire type 2: a length-delimited field. */
  private def ld(field: Int, payload: Array[Byte]): Array[Byte] =
    cat(varint((field << 3) | 2), varint(payload.length), payload)

  private def str(field: Int, s: String): Array[Byte] = ld(field, s.getBytes(UTF_8))

  /** A ValueInfoProto declaring a float tensor of a fixed shape. */
  private def valueInfo(field: Int, name: String, shape: Seq[Int]): Array[Byte] =
    val dims = cat(shape.map(d => ld(1, vi(1, d.toLong)))*)
    val tensorType = cat(vi(1, 1), ld(2, dims)) // elem_type = FLOAT
    ld(field, cat(str(1, name), ld(2, ld(1, tensorType))))

  /** A model whose whole graph is `out = Relu(in)` over a tensor of `shape`. */
  def relu(shape: Seq[Int]): Array[Byte] =
    val node = ld(1, cat(str(1, "in"), str(2, "out"), str(3, "relu"), str(4, "Relu")))
    val graph = cat(node, str(2, "g"), valueInfo(11, "in", shape), valueInfo(12, "out", shape))
    cat(
      vi(1, 7), // ir_version 7
      str(2, "scalacv"),
      ld(7, graph),
      ld(8, cat(str(1, ""), vi(2, 13))) // opset_import: default domain, version 13
    )
