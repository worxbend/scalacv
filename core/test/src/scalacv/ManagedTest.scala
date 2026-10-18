package scalacv

import java.util.concurrent.atomic.AtomicInteger

import scala.util.Using

import org.opencv.core as cv
import org.opencv.core.{CvType, Mat}
import org.opencv.objdetect.CascadeClassifier

/** The behaviour here is not stylistic. Each assertion stands in for a way the JVM dies.
  *
  * A double `delete` on an OpenCV handle is undefined behaviour, and calling any method on a freed one
  * segfaults from native code — no stack trace, no catch, no test report. Both reproduced on this machine
  * before the guard existed.
  */
class ManagedTest extends munit.FunSuite:

  override def beforeAll(): Unit = OpenCv.load()

  test("release frees the native buffer"):
    val m = Managed(Mat(64, 64, CvType.CV_8UC3))
    assert(m.get.dataAddr() != 0L, "a fresh Mat should own native memory")
    m.release()
    assert(m.isReleased)

  test("release is idempotent — a second call must not double-free"):
    val m = Managed(Mat(8, 8, CvType.CV_8UC1))
    m.release()
    m.release()
    m.release()
    assert(m.isReleased)

  test("release is a single compare-and-set — a concurrent race frees exactly once"):
    // The central safety claim, and the one race the AtomicReference CAS exists for: N threads all
    // call release() at the same instant. A plain boolean flag would let two of them both read
    // "not yet released" and both free the pointer — a double-free, undefined behaviour on a native
    // handle. The CAS admits exactly one. Run enough rounds that a broken implementation is very
    // unlikely to survive by luck.
    val threads = 64
    (1 to 50).foreach: _ =>
      val frees = java.util.concurrent.atomic.AtomicInteger(0)
      given Releasable[String] = _ => { frees.incrementAndGet(); () }
      val m = Managed("payload")
      val start = java.util.concurrent.CountDownLatch(1)
      val workers = (1 to threads).map: _ =>
        val t = Thread { () => start.await(); m.release() }
        t.start()
        t
      start.countDown() // fire all threads as close to simultaneously as the scheduler allows
      workers.foreach(_.join())
      assertEquals(frees.get, 1, "the underlying free must run exactly once, however many threads raced")
      assert(m.isReleased)

  test("access after release throws rather than crashing the JVM"):
    val m = Managed(Mat(8, 8, CvType.CV_8UC1))
    m.release()
    val e = intercept[IllegalStateException](m.get)
    assert(e.getMessage.contains("already been released"), e.getMessage)

  test("the spent-handle error names the fix and, when tracking is off, the flag that would locate it"):
    val m = Managed(Mat(8, 8, CvType.CV_8UC1))
    m.release()
    val e = intercept[IllegalStateException](m.get)
    // The commonest cause is an Image reused after a move, so the message points at `.copy`...
    assert(e.getMessage.contains(".copy"), e.getMessage)
    // ...and, since tracking is off by default in this suite, at the flag that records the consuming site.
    assert(e.getMessage.contains("scalacv.trackOwnership"), e.getMessage)

  test("an Image reused after a transform throws the move-semantics error rather than reading freed memory"):
    val img = Image.blank(16, 16)
    img.gray.close() // gray consumes `img` and returns a fresh Image, which we close
    val e = intercept[IllegalStateException](img.width)
    assert(e.getMessage.contains("already been released or consumed"), e.getMessage)

  test("use releases on the happy path"):
    val m = Managed(Mat(8, 8, CvType.CV_8UC1))
    assertEquals(m.use(_.rows), 8)
    assert(m.isReleased)

  test("use releases when the body throws"):
    val m = Managed(Mat(8, 8, CvType.CV_8UC1))
    intercept[RuntimeException](m.use(_ => throw RuntimeException("boom")))
    assert(m.isReleased, "the Mat must still be released when the body throws")

  test("Using.Manager releases every Mat, in reverse order"):
    val mats = Using.Manager: use =>
      val a = use(Managed(Mat(4, 4, CvType.CV_8UC1)))
      val b = use(Managed(Mat(4, 4, CvType.CV_8UC1)))
      List(a, b)
    assert(mats.get.forall(_.isReleased))

  test("scope releases every handle, in reverse acquisition order, and only at the end"):
    val freed = List.newBuilder[String]
    given Releasable[String] = s => { freed += s; () }
    val joined = Managed.scope: own =>
      val a = own("first")
      val b = own("second")
      val c = own("third")
      assertEquals(freed.result(), Nil, "nothing may be released while the body is still running")
      s"$a-$b-$c"
    assertEquals(joined, "first-second-third")
    assertEquals(freed.result(), List("third", "second", "first"))

  test("scope releases what it acquired when the body throws, and rethrows the original error"):
    val freed = List.newBuilder[String]
    given Releasable[String] = s => { freed += s; () }
    val e = intercept[RuntimeException]:
      Managed.scope: own =>
        own("first")
        own("second")
        throw RuntimeException("boom")
    assertEquals(e.getMessage, "boom")
    assertEquals(freed.result(), List("second", "first"))

  test("scope frees the Mats already acquired when a later allocation throws"):
    // The hole in the `val`s-then-try/finally form this replaces: everything allocated before the `try`
    // is unguarded, so a constructor that throws part-way strands it. Here the failure is the second
    // allocation, and the first Mat must still lose its buffer.
    def failingAllocation: Mat = throw RuntimeException("allocation failed")
    var acquired: Mat | Null = null
    intercept[RuntimeException]:
      Managed.scope: own =>
        acquired = own(Mat(64, 64, CvType.CV_8UC3))
        own(failingAllocation)
    acquired match
      case m: Mat => assertEquals(m.dataAddr(), 0L, "the Mat acquired before the failure must be freed")
      case null => fail("the first allocation should have succeeded")

  test("the delete(long) bridge frees a handle class that has no release()"):
    // CascadeClassifier is one of the 185 types with no public release(). If this regime ever
    // stops working the failure must be loud, because the alternative is a silent 634x leak.
    given Releasable[CascadeClassifier] = Releasable.nativeHandle
    val c = Managed(CascadeClassifier())
    c.release()
    assert(c.isReleased)
    intercept[IllegalStateException](c.get)

  test("the delete(long) bridge is idempotent for handle classes too"):
    given Releasable[CascadeClassifier] = Releasable.nativeHandle
    val c = Managed(CascadeClassifier())
    c.release()
    c.release()
    assert(c.isReleased)

  test("after release the Mat owns no native memory (the primary leak assertion)"):
    // Deliberately not an RSS measurement. RSS after a release reflects glibc arena behaviour,
    // not whether the buffer was freed, and has both false negatives and false positives.
    // dataAddr() is the pointer itself.
    val raw = Mat(256, 256, CvType.CV_8UC3)
    val m = Managed(raw)
    assert(raw.dataAddr() != 0L)
    m.release()
    assertEquals(raw.dataAddr(), 0L, "release() must drop the native buffer")

  test("Cv.attempt names the operation instead of letting CvException escape"):
    val bad = Mat()
    val r = Cv.attempt("cvtColor")(org.opencv.imgproc.Imgproc.cvtColor(bad, Mat(), 999999))
    assert(r.isLeft, "an invalid conversion code should be a Left, not a throw")
    r.left.foreach: e =>
      assert(e.isInstanceOf[CvError.NativeCall], s"expected NativeCall, got $e")
      assert(e.getMessage.contains("cvtColor"), e.getMessage)

  test("take hands the object out without freeing it, spends the handle, and makes a later release a no-op"):
    val frees = AtomicInteger(0)
    given Releasable[String] = _ => { frees.incrementAndGet(); () }
    val m = Managed("payload")
    assertEquals(m.take(), "payload")
    assert(m.isReleased, "a taken handle is spent")
    assertEquals(frees.get, 0, "take transfers ownership; it must not free")
    val e = intercept[IllegalStateException](m.take())
    assert(e.getMessage.contains("transferring"), e.getMessage)
    intercept[IllegalStateException](m.get)
    m.release()
    assertEquals(frees.get, 0, "the object now belongs to the taker, so the spent handle has nothing to free")

    // The same contract on a real Mat: the buffer survives both the take and the later release.
    val raw = Mat(16, 16, CvType.CV_8UC1)
    val handle = Managed(raw)
    val out = handle.take()
    assertNotEquals(out.dataAddr(), 0L, "take must not free the buffer")
    handle.release()
    assertNotEquals(out.dataAddr(), 0L, "releasing a taken handle must not free the transferred buffer")
    out.release()

  test("adopt releases an already-wrapped handle exactly once, at scope end, and leaves the original spent"):
    val frees = AtomicInteger(0)
    given Releasable[String] = _ => { frees.incrementAndGet(); () }
    val h = Managed("x")
    val v = Managed.scope: own =>
      val a = own.adopt(h)
      assertEquals(frees.get, 0, "nothing may be released while the body is still running")
      assert(!h.isReleased)
      a + "!"
    assertEquals(v, "x!")
    assertEquals(frees.get, 1)
    assert(h.isReleased, "the scope took over the handle, so the original is spent")
    h.release()
    assertEquals(frees.get, 1, "the scope already freed it; the original handle's release is the CAS no-op")

  test("adopting the same handle twice in one scope still frees it once"):
    val frees = AtomicInteger(0)
    given Releasable[String] = _ => { frees.incrementAndGet(); () }
    val h = Managed("x")
    Managed.scope: own =>
      own.adopt(h)
      own.adopt(h)
      ()
    assertEquals(frees.get, 1)

  test("scope keeps the body's exception and attaches a release failure as suppressed, releasing the rest"):
    val goodFrees = AtomicInteger(0)
    given Releasable[String] = s =>
      if s == "bad" then throw RuntimeException("release failed") else { goodFrees.incrementAndGet(); () }
    val e = intercept[RuntimeException]:
      Managed.scope: own =>
        own("good")
        own("bad")
        throw RuntimeException("boom")
    assertEquals(e.getMessage, "boom")
    assertEquals(e.getSuppressed.map(_.getMessage).toSeq, Seq("release failed"))
    assertEquals(goodFrees.get, 1, "a failing release must not stop the other handles from being released")

  test("scope propagates a release failure when the body itself succeeded"):
    given Releasable[String] = s => if s == "bad" then throw RuntimeException("release failed") else ()
    val e = intercept[RuntimeException]:
      Managed.scope: own =>
        own("bad")
        1
    assertEquals(e.getMessage, "release failed")

  test("the delete bridge refuses a type without a nativeObj field with NativesMissing, in both forms"):
    // Plain JVM objects have no nativeObj, so both forms must fail loudly rather than pretend to free.
    locally:
      given Releasable[Object] = Releasable.nativeHandle
      val e = intercept[CvError.NativesMissing](Managed(new Object).release())
      assert(e.getMessage.contains("no nativeObj field"), e.getMessage)
    locally:
      // A non-zero address forces the disarm step, which is where the missing field is discovered.
      given Releasable[StringBuilder] = Releasable.handle(_ => 42L)
      val e = intercept[CvError.NativesMissing](Managed(StringBuilder()).release())
      assert(e.getMessage.contains("no nativeObj field"), e.getMessage)

  test("the accessor form of the delete bridge zeroes nativeObj, so re-wrapping a freed object is a no-op"):
    given Releasable[CascadeClassifier] = Releasable.handle(_.getNativeObjAddr)
    val c = CascadeClassifier()
    assertNotEquals(c.getNativeObjAddr, 0L, "a fresh CascadeClassifier should hold a pointer")
    Managed(c).release()
    assertEquals(c.getNativeObjAddr, 0L, "release must zero nativeObj so finalize() deletes nullptr")
    // The second wrap reads address 0, and free() short-circuits before touching delete(long).
    Managed(c).release()

  test("Cv.attempt returns a thrown CvError as-is, and orThrow rethrows that same instance"):
    val err = CvError.LoadFailed("r", "d")
    Cv.attempt("op")(throw err) match
      case Left(e) => assert(e eq err, s"a CvError must pass through unwrapped, got $e")
      case other => fail(other.toString)
    val rethrown = intercept[CvError.LoadFailed](Cv.orThrow("op")(throw err))
    assert(rethrown eq err)
    assertEquals(Cv.orThrow("op")(41 + 1), 42)

  test("Cv.attempt wraps a CvException in NativeCall, naming the operation and keeping the message"):
    val r = Cv.attempt("op")(throw new org.opencv.core.CvException("native"))
    r match
      case Left(e @ CvError.NativeCall("op", cause)) =>
        assertEquals(cause.getMessage, "native")
        assertEquals(e.getMessage, "OpenCV failed during op: native")
      case other => fail(other.toString)

  test("every CvError names its resource and details, and the wrapping variants keep their cause"):
    assertEquals(
      CvError.DecodeFailed("/p.png", "bad header").getMessage,
      "could not decode an image from '/p.png': bad header"
    )
    assertEquals(CvError.LoadFailed("m.onnx", "404").getMessage, "could not load 'm.onnx': 404")
    assertEquals(CvError.EncodeFailed("/o.jpg", "x").getMessage, "could not write an image to '/o.jpg': x")
    assertEquals(CvError.CalibrationFailed("3 views").getMessage, "camera calibration failed: 3 views")
    val cause = RuntimeException("inner")
    val native = CvError.NativeCall("cvtColor", cause)
    assertEquals(native.getMessage, "OpenCV failed during cvtColor: inner")
    assert(native.getCause eq cause)
    assert(CvError.NativesMissing("d", cause).getCause eq cause)
    assertEquals(CvError.NativesMissing("d").getCause, null)

  test("Mats.grayscale hands back an owned clone for an already-grey input, never an alias of the receiver"):
    val grey = Mat(8, 8, CvType.CV_8UC1, cv.Scalar(7))
    try
      val outAddr = Mats.grayscale(grey).use(_.dataAddr())
      assertNotEquals(outAddr, grey.dataAddr(), "the result must not share the borrowed buffer")
      assertNotEquals(grey.dataAddr(), 0L, "releasing the result must leave the borrowed receiver intact")
      assertEquals(grey.get(0, 0)(0), 7.0)
    finally grey.release()

  test("Mats.grayscale reduces a 4-channel input to CV_8UC1"):
    val bgra = Mat(8, 8, CvType.CV_8UC4, cv.Scalar(1, 2, 3, 4))
    try Mats.grayscale(bgra).use(m => assertEquals(m.`type`(), CvType.CV_8UC1))
    finally bgra.release()
