package scalacv

import scalacv.graphs.*
import scalacv.vision.*

import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.{Element, Node}

/** Guards the published dependency set of every publishable module.
  *
  * This exists because the failure it catches is invisible from inside the build. Mill 1.1.7's publish model
  * has no classifier field at all, so a `;classifier=` dependency is silently dropped from the generated POM.
  * Our own CI compiles from source, where the classifier *does* apply, so everything stays green while
  * consumers resolve an artifact with no natives and die at their first `OpenCv.load()`.
  *
  * The assertions are therefore about what each POM *says*, not about whether the build works. And there are
  * four published POMs now — `scalacv_3`, `scalacv-vision_3`, `scalacv-graphs_3`, `scalacv-zio_3` — so the
  * gate iterates over all of them (`-Dscalacv.poms=<colon-separated absolute paths>`, set by `core.test`'s
  * forkArgs); a classifier leak into any one of the three that were previously ungated is caught here.
  *
  * Each module's expected dependency set differs: `core` depends on opencv + scala3-library;
  * `vision`/`graphs` add a dependency on `scalacv_3` (core) *and* declare opencv directly; `zio` depends on
  * `scalacv_3` plus the ZIO artifacts and gets opencv transitively through core. The one invariant that holds
  * for every POM is that NO dependency carries a classifier — Mill cannot emit one, so any classifier present
  * is a host-specific leak (e.g. `linux-x86_64`) that would resolve to an artifact with no natives. For the
  * modules that declare opencv directly we additionally assert it is present and classifier-less;
  * `scalacv-zio_3` is exempt from the "present" half because it inherits opencv through core rather than
  * declaring it.
  *
  * Parsed with the JDK's own parser rather than scala-xml on purpose: scala-xml 2.4.0 for Scala 3 drags
  * scala3-library 3.7.4 onto the classpath, and a library whose whole version argument (D2) is about not
  * mixing TASTy versions should not do that in its own test suite.
  */
class PublishedPomTest extends munit.FunSuite:

  import PublishedPomTest.*

  poms.foreach: pom =>
    val id = pom.artifactId

    test(s"$id: exact dependencies, versions and scopes"):
      val scala = ("org.scala-lang", "scala3-library_3", "3.3.8", "compile", "false", "jar")
      val opencv = ("org.bytedeco", "opencv", Build.openCvArtifactVersion, "compile", "false", "jar")
      val core = ("com.worxbend", "scalacv_3", pom.version, "compile", "false", "jar")
      val expected = id match
        case "scalacv_3" => Set(scala, opencv)
        case "scalacv-vision_3" | "scalacv-graphs_3" => Set(scala, opencv, core)
        case "scalacv-zio_3" =>
          Set(
            scala,
            core,
            ("dev.zio", "zio_3", "2.1.26", "compile", "false", "jar"),
            ("dev.zio", "zio-streams_3", "2.1.26", "compile", "false", "jar")
          )
        case other => fail(s"unexpected published artifact: $other")
      assertEquals(pom.contracts.toSet, expected)

    test(s"$id: POM declares no duplicate groupId:artifactId"):
      val keys = pom.deps.map((g, a, _) => s"$g:$a")
      assertEquals(keys.distinct.size, keys.size, s"duplicate dependencies in $id POM: $keys")

    test(s"$id: POM carries no dependency classifier"):
      // Mill 1.1.7's POM model has no classifier field, so a `;classifier=` dep is silently stripped.
      // Asserting none survives guards every module against a host-specific classifier (e.g. linux-x86_64)
      // leaking in and resolving to an artifact with no natives.
      val classified = pom.deps.filter((_, _, c) => c.isDefined)
      assert(classified.isEmpty, s"$id POM must declare no classifier; found: $classified")

    test(s"$id: POM ships no per-platform natives"):
      // Consumers pick their own platform (README quick start). openblas is the more likely accident,
      // so name it; a stray classified opencv lands classifier-stripped and is caught by the duplicate
      // check above.
      val blas = pom.deps.filter((g, a, _) => g == "org.bytedeco" && a == "openblas")
      assert(blas.isEmpty, s"openblas must not reach $id POM: $blas")

    test(s"$id: POM records a version scheme"):
      val ns = pom.doc.getElementsByTagName("info.versionScheme")
      assert(ns.getLength == 1, s"info.versionScheme missing from $id POM")
      assertEquals(ns.item(0).getTextContent.trim, "early-semver")

    if requiresOpencv(id) then
      test(s"$id: POM depends on the classifier-less OpenCV Java API"):
        val opencv = pom.deps.filter((g, a, _) => g == "org.bytedeco" && a == "opencv")
        assertEquals(
          opencv.size,
          1,
          s"expected exactly one org.bytedeco:opencv dependency in $id POM, got $opencv"
        )
        assert(opencv.head._3.isEmpty, s"the opencv dependency in $id POM must carry no classifier: $opencv")

object PublishedPomTest:

  /** Published artifactIds (Scala-3 `_3`-suffixed) whose POM must declare the OpenCV Java API *directly*.
    * `scalacv-zio_3` is absent on purpose: it inherits opencv transitively through `scalacv_3`. Removing
    * `opencvApi` from any of these modules' `mvnDeps` makes the "depends on OpenCV" test fail — which is the
    * point.
    */
  private val requiresOpencv = Set("scalacv_3", "scalacv-vision_3", "scalacv-graphs_3")

  /** The generated POMs to check, from `-Dscalacv.poms` (colon-separated absolute paths). Falls back to the
    * legacy singular `-Dscalacv.pom` so the property rename is backward-compatible. Fails loudly rather than
    * quietly passing zero POMs, since a green run over an empty set would test nothing.
    */
  private def poms: Seq[Pom] =
    val raw = sys.props.get("scalacv.poms").orElse(sys.props.get("scalacv.pom"))
    val paths = raw.toSeq.flatMap(_.split(':').toSeq).map(_.trim).filter(_.nonEmpty)
    if paths.isEmpty then
      throw AssertionError("no POMs given; run this via `./mill core.test` (it sets -Dscalacv.poms=...)")
    val files = paths.map(java.io.File(_))
    val missing = files.filterNot(_.isFile)
    if missing.nonEmpty then throw AssertionError(s"these POMs were not generated: ${missing.mkString(", ")}")
    val parsed = files.map(Pom(_))
    val expected = Set("scalacv_3", "scalacv-vision_3", "scalacv-graphs_3", "scalacv-zio_3")
    require(
      parsed.size == 4 && parsed.map(_.artifactId).toSet == expected,
      "expected exactly the four library POMs"
    )
    require(parsed.map(_.version).distinct.size == 1, "library POM versions differ")
    parsed

  /** One parsed POM: its project artifactId and its declared dependencies. */
  private final class Pom(file: java.io.File):
    val doc =
      val f = DocumentBuilderFactory.newInstance()
      f.setNamespaceAware(false)
      f.newDocumentBuilder().parse(file)

    private def directChildText(parent: Node, tag: String): Option[String] =
      val kids = parent.getChildNodes
      (0 until kids.getLength).iterator
        .map(kids.item)
        .collectFirst { case e: Element if e.getTagName == tag => e.getTextContent.trim }

    private def childText(e: Element, tag: String): Option[String] =
      val ns = e.getElementsByTagName(tag)
      if ns.getLength == 0 then None else Some(ns.item(0).getTextContent.trim)

    /** The project's own artifactId (the direct `<artifactId>` child of `<project>`), not a dependency's. */
    val artifactId: String = directChildText(doc.getDocumentElement, "artifactId").getOrElse("<unknown>")

    val version: String = directChildText(doc.getDocumentElement, "version").getOrElse("")

    val contracts: Seq[(String, String, String, String, String, String)] =
      val ns = doc.getElementsByTagName("dependency")
      (0 until ns.getLength).map: i =>
        val e = ns.item(i).asInstanceOf[Element]
        (
          childText(e, "groupId").getOrElse(""),
          childText(e, "artifactId").getOrElse(""),
          childText(e, "version").getOrElse(""),
          childText(e, "scope").getOrElse("compile"),
          childText(e, "optional").getOrElse("false"),
          childText(e, "type").getOrElse("jar")
        )

    val deps: Seq[(String, String, Option[String])] =
      val ns = doc.getElementsByTagName("dependency")
      (0 until ns.getLength).map: i =>
        val e = ns.item(i).asInstanceOf[Element]
        (
          childText(e, "groupId").getOrElse(""),
          childText(e, "artifactId").getOrElse(""),
          childText(e, "classifier")
        )
