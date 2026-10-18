package scalacv

import java.lang.reflect.Modifier

import org.opencv.core.Mat

/** The `Mat` extension methods (`Ops`, `Draw`, `Hough`, `Contours`, `Deskew`, `Effects`, vision's `Cascades`)
  * are one upstream member away from silent shadowing: if a future OpenCV release adds a `Mat` method with
  * the same name, the member wins at every call site and the scalacv version is never called again — with no
  * warning from the compiler. This suite turns that into a failing test.
  */
class MatExtensionShadowingTest extends munit.FunSuite:

  /** The JVM holders of the top-level extension methods on `Mat`. */
  private val Holders: Seq[String] = Seq(
    "scalacv.Ops$package$",
    "scalacv.Draw$package$",
    "scalacv.Hough$package$",
    "scalacv.Contours$package$",
    "scalacv.Deskew$package$",
    "scalacv.Effects$package$",
    "scalacv.vision.Cascades$package$"
  )

  private val matNames: Set[String] = classOf[Mat].getMethods.map(_.getName).toSet

  /** Public, non-default-getter method names whose first parameter is the `Mat` receiver. */
  private def extensionNames(holder: String): Set[String] =
    val c = Class.forName(holder, false, classOf[Managed[?]].getClassLoader)
    c.getMethods.iterator
      .filter(m => Modifier.isPublic(m.getModifiers) && !m.getName.contains("$default$"))
      .filter(m => m.getParameterTypes.headOption.contains(classOf[Mat]))
      .map(_.getName)
      .toSet

  test("no Mat extension method shares a name with a Mat member"):
    // Self-checks: the comparison below can only be trusted if both sides are non-trivial.
    assert(matNames.contains("clone"), s"Mat's own member names were not collected: $matNames")
    val extensions = Holders.map(h => h -> extensionNames(h))
    extensions.foreach: (holder, names) =>
      assert(names.nonEmpty, s"no Mat extension methods were discovered on $holder")
    val colliders =
      extensions.flatMap((holder, names) => names.intersect(matNames).toSeq.sorted.map(holder -> _))
    assert(colliders.isEmpty, s"Mat extension methods shadowed by a Mat member of the same name: $colliders")
