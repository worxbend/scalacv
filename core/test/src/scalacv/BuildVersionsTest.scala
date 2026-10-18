package scalacv

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.util.Using

/** The gate on version drift: every bytedeco coordinate printed anywhere a *user* reads must be the one the
  * build actually resolves.
  *
  * ==Why this is a test and not a convention==
  *
  * The `org.bytedeco:opencv` version appears in nineteen places: the build, two error messages in the library
  * itself, the README, THIRD-PARTY, six documentation pages, and a CI job. Only one of them — `Deps.opencv`
  * in `build.mill` — decides anything. Scala Steward bumps that one and cannot know about the other eighteen,
  * so every bump used to leave the project telling people to add a version it no longer builds against. That
  * is a worse failure than an out-of-date sentence: the whole purpose of those lines is to be pasted into
  * someone's build file, and the natives must resolve at exactly the version whose JNI shim the Java API
  * calls. A mismatch is not a stale doc, it is a broken classpath — or, worse, two OpenCV ABIs in one
  * process.
  *
  * The library's own copies were removed rather than checked: [[Build]] is generated from `Deps`, and
  * `OpenCv`/`Cascades` interpolate it. Prose cannot be generated that way without turning readable pages into
  * templates, so the prose is *checked* instead.
  *
  * ==How the check works==
  *
  * bytedeco versions have a distinctive shape — `<opencv release>-<javacpp release>`, e.g. two dotted triples
  * joined by a hyphen — that essentially nothing else in this repository matches. So rather than teaching
  * this test the six different syntaxes the docs use to spell a dependency (Mill's `mvn"…"`, sbt's `%`, a
  * markdown table cell, a bare coordinate in prose, a shell argument), it finds every string of that shape
  * and asserts each one is a version the build really uses. A new page that quotes a dependency is covered
  * the day it is written, with no list to remember to update.
  *
  * `CHANGELOG.md` is deliberately not scanned: a changelog entry recording "bumped OpenCV from X to Y"
  * *should* contain an old version, and rewriting history to satisfy a test would be the wrong fix.
  */
class BuildVersionsTest extends munit.FunSuite:

  /** Two dotted triples joined by a hyphen — the bytedeco `<library>-<javacpp>` version shape. */
  private val BytedecoVersion = raw"\b\d+\.\d+\.\d+-\d+\.\d+\.\d+\b".r

  /** The versions the build resolves, and therefore the only ones a reader may be told to use. */
  private val allowed: Set[String] =
    Set(Build.openCvArtifactVersion, Build.openBlasArtifactVersion)

  /** The repo root. Reuses [[PublicApi.buildRoot]], which walks up to `build.mill`, rather than repeating the
    * walk — one definition of "where the repository is" for the whole test module.
    */
  private def repoRoot: Path = PublicApi.buildRoot

  /** Everything a user reads that could quote a dependency: the build itself, the top-level documents, every
    * mdoc page, and the CI workflows (whose consumer-smoke job resolves the natives by hand).
    */
  private def scanned: Seq[Path] =
    val roots = Seq(
      repoRoot.resolve("build.mill"),
      repoRoot.resolve("README.md"),
      repoRoot.resolve("THIRD-PARTY.md")
    )
    val trees = Seq(repoRoot.resolve("docs/mdoc"), repoRoot.resolve(".github/workflows"))
    val walked = trees.filter(Files.isDirectory(_)).flatMap { dir =>
      Using.resource(Files.walk(dir))(_.iterator.asScala.filter(Files.isRegularFile(_)).toVector)
    }
    (roots.filter(Files.isRegularFile(_)) ++ walked).sorted

  test("every file a user reads quotes a bytedeco version the build actually resolves"):
    assert(
      scanned.nonEmpty,
      s"found nothing to scan under $repoRoot — is the repo layout still what we think?"
    )
    val stale = scanned.flatMap { file =>
      val text = String(Files.readAllBytes(file), StandardCharsets.UTF_8)
      BytedecoVersion
        .findAllIn(text)
        .toVector
        .distinct
        .filterNot(allowed.contains)
        .map(version => s"  ${repoRoot.relativize(file)} quotes $version")
    }
    assert(
      stale.isEmpty,
      s"""these files quote a bytedeco version the build no longer uses.
         |The build resolves ${allowed.toSeq.sorted.mkString(" and ")} (from `Deps` in build.mill);
         |update the text below to match, because those lines are meant to be pasted into a build file:
         |
         |${stale.mkString("\n")}""".stripMargin
    )

  test("Build.openCvVersion is the OpenCV half of the bytedeco coordinate"):
    // If these two ever disagree the generator in build.mill has stopped deriving one from the other,
    // and `Build` is back to being two independently-maintained strings.
    assert(
      Build.openCvArtifactVersion.startsWith(s"${Build.openCvVersion}-"),
      s"Build.openCvVersion (${Build.openCvVersion}) is not the OpenCV half of " +
        s"Build.openCvArtifactVersion (${Build.openCvArtifactVersion})"
    )

  test("Build.openCvVersion matches the OpenCV bindings actually on the classpath"):
    // The strongest form of the check: not "the build file and the docs agree with each other", but
    // "what we tell a bug reporter is the release whose Java API we are compiled against". `Core.VERSION`
    // is generated into the binding by the same javacpp build that produced the natives.
    OpenCv.load()
    assertEquals(org.opencv.core.Core.VERSION, Build.openCvVersion)
