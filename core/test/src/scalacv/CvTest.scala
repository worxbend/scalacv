package scalacv

import scalacv.graphs.*
import scalacv.vision.*

/** The error policy of [[Cv.attempt]]: OpenCV's JNI shim throws a bare `java.lang.Exception` for failures
  * that are not `cv::Exception` (std::bad_alloc, std::out_of_range, unknown). Those must be captured as
  * `CvError.NativeCall`, while genuine programmer errors thrown as subclasses must still propagate.
  */
class CvTest extends munit.FunSuite:

  test("attempt wraps a bare java.lang.Exception as CvError.NativeCall"):
    val result = Cv.attempt("op")(throw new java.lang.Exception("boom"))
    result match
      case Left(CvError.NativeCall(operation, _)) => assertEquals(operation, "op")
      case other => fail(s"expected Left(NativeCall), got $other")

  test("attempt lets Exception subclasses propagate as programmer errors"):
    intercept[IllegalArgumentException](Cv.attempt("op")(throw new IllegalArgumentException("x")))

  test("NativeCall names the cause class when it carries no message"):
    val e = CvError.NativeCall("op", new java.lang.Exception())
    assert(e.getMessage.contains("java.lang.Exception"), e.getMessage)
    assert(!e.getMessage.contains("null"), e.getMessage)
