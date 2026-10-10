package scalacv

import scalacv.graphs.*
import scalacv.vision.*

import java.lang.reflect.{Constructor, Field, Method, Modifier, Type as JType}
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.jar.JarFile

import scala.jdk.CollectionConverters.*
import scala.util.{Random, Try, Using}

/** Renders scalacv's compiled public surface as a stable, sorted text document.
  *
  * ==Why this exists==
  *
  * Track B's gate used to read "API surface reviewed by you", which is not a command and therefore cannot
  * fail. This turns it into one: the rendered surface of each published module is committed as
  * `<module>/api.golden`, the tests below compare against it, and `git diff --exit-code` catches a
  * reviewed-and-forgotten change. Any addition, removal or signature change to a publicly reachable member
  * shows up as a diff a human has to look at.
  *
  * ==One golden per published module==
  *
  * `core`, `vision`, `graphs` and `zio` are four separately published artifacts (`scalacv`, `scalacv-vision`,
  * `scalacv-graphs`, `scalacv-zio`), each with the same binary-compatibility promise. `core` publishes
  * `package scalacv`; vision, graphs and zio publish `scalacv.vision`, `scalacv.graphs` and `scalacv.zio` —
  * deliberately *not* the same package, so the artifacts can coexist on a JPMS module path (a split package
  * across artifacts would be a hard error there). `core.test` has all four on its classpath, so this one
  * suite gates all four: the rendering and golden-diff logic below is parameterized over a [[Module]] — a
  * name, its compiled classes directory, and its golden path — and a class is included in a module's surface
  * only if it was compiled into *that* module's output directory. Core's `Image` therefore never leaks into
  * vision's golden and vice versa.
  *
  * ==What "public" means here==
  *
  * It means **JVM-public**, read back out of the class files, not "public in the Scala source". The two
  * differ in one direction that matters: Scala's `private[scalacv]` and even some `private` members compile
  * to `ACC_PUBLIC` on the JVM, so `Point.toCv` and `Managed`'s constructor appear below. That is deliberate
  * rather than a limitation being tolerated. Those members really are callable — from Java, from any code
  * that declares itself in package `scalacv`, and from a `ClassLoader` — so they really are part of the
  * bytecode surface checked historically by `ci/binary-compatibility.py`. These goldens remain a filtered
  * human-review aid, not a historical ABI check. A gate that reported the narrower source-level view would be
  * quietly wrong about exactly the members most likely to break someone.
  *
  * ==Determinism is the whole value==
  *
  * A golden file that churns on an unrelated recompile gets regenerated reflexively and stops being a gate.
  * Everything below is therefore either sorted or excluded:
  *
  *   - `Class.getDeclaredMethods`/`getDeclaredFields` are explicitly documented as returning members in no
  *     particular order, so every list is sorted by its own rendered text.
  *   - Synthetic and bridge members are dropped. Scala 3 emits a bridge for every `Mirror` method
  *     (`ordinal(Object)`, `fromProduct(Product)`), which carries no information the non-bridge overload does
  *     not.
  *   - Compiler-invented names are dropped by name: `$anonfun` lifted lambda bodies, `$lzy`/`$lzyINIT` lazy
  *     val backing state, `OFFSET$_m_N` lazy val field offsets (whose numbering is assigned by the compiler
  *     and has no source-level meaning), `$values`, and the `MODULE$` field.
  *   - `$$anon$N` classes — one per `enum` case — are dropped entirely. Their numbers are allocated across a
  *     whole compilation unit, so adding one case renumbers every later one and would rewrite the file for no
  *     API change. The cases themselves are still covered: they show up as fields on the companion.
  *   - Static forwarders are dropped. Scala emits a static copy of every member of `object X` onto class `X`
  *     for Java callers; keeping both would list every module member twice.
  *   - `$lessinit$greater` is normalised to `<init>` so constructor default-argument getters read as
  *     `<init>$default$1` rather than as mangling.
  *
  * Default-argument getters (`foo$default$2`) are kept on purpose. They are stable — the suffix is a
  * parameter index — and dropping them would let a default appear or vanish with no diff, which is a
  * source-compatibility change consumers can feel.
  *
  * ==Where the classes come from==
  *
  * Each module is rendered from its own compiled output directory, `out/<module>/compile.dest/classes`,
  * resolved beneath the build root. The build root itself is found by walking up from a known scalacv class's
  * code source (`classOf[Managed[?]]`) — or from the working directory — to the nearest `build.mill`, the one
  * assumption about layout made here and a cheap one to re-point. Enumerating strictly from a single module's
  * directory is what keeps the per-module surfaces disjoint and excludes the test module's own classes for
  * free.
  */
object PublicApi:

  /** Members whose names contain any of these are compiler bookkeeping, not API. */
  private val ExcludedNameFragments: Seq[String] =
    Seq("$anonfun", "$lzy", "$$anon", "$init$", "$deserializeLambda$", "$proxy")

  /** Members with exactly these names are compiler bookkeeping, not API. */
  private val ExcludedNames: Set[String] = Set("MODULE$", "$values")

  /** Lazy-val field offsets. Numbered by the compiler; carries no source-level meaning. */
  private val OffsetPrefix = "OFFSET$"

  /** The header prepended to a module's golden. `goldenLabel` is the repo-relative golden path so the "read
    * `git diff …`" hint names the right file for each module.
    */
  private def header(goldenLabel: String): String =
    s"""|# scalacv — golden public API surface
        |#
        |# Generated by core/test/src/scalacv/PublicApiTest.scala. Do not edit by hand.
        |#
        |# Regenerate:  SCALACV_API_REGENERATE=1 ./mill core.test
        |#          or: -Dscalacv.api.regenerate=true on the test JVM
        |#
        |# Then read `git diff $goldenLabel` before committing. Every line here is a member
        |# something outside this library can call; a diff you cannot explain is a bug.
        |#
        |# This is the JVM-visible surface: Scala's `private[scalacv]` compiles to ACC_PUBLIC and
        |# so appears below. See the scaladoc on PublicApi for the full set of exclusions.
        |""".stripMargin

  // ---------------------------------------------------------------------------------------------
  // Modules
  // ---------------------------------------------------------------------------------------------

  /** One published module's API surface: where its compiled classes live and where its golden sits.
    *
    * `classesDir` and `goldenPath` are both derived from [[buildRoot]] and the module name, mirroring Mill's
    * layout: classes in `out/<name>/compile.dest/classes`, golden at `<name>/api.golden`.
    */
  final case class Module(name: String):
    def goldenLabel: String = s"$name/api.golden"
    def classesDir: Path =
      buildRoot.resolve("out").resolve(name).resolve("compile.dest").resolve("classes")
    def goldenPath: Path = buildRoot.resolve(name).resolve("api.golden")

  /** The published modules whose surfaces this suite gates, in golden-file order. */
  val modules: Seq[Module] = Seq(Module("core"), Module("vision"), Module("graphs"), Module("zio"))

  /** `core`, used by the renderer-invariant tests as a representative surface. */
  def coreModule: Module = modules.head

  // ---------------------------------------------------------------------------------------------
  // Discovery
  // ---------------------------------------------------------------------------------------------

  /** The directory or jar a known scalacv class was compiled into. Used only to anchor [[buildRoot]]. */
  def codeSource: Path =
    val cs = classOf[Managed[?]].getProtectionDomain.getCodeSource
    require(cs != null, "scalacv has no CodeSource; cannot locate the compiled classes")
    Paths.get(cs.getLocation.toURI: URI)

  /** The build root, found by walking up from the compiled classes (or the working directory) to the nearest
    * `build.mill`. Each module's classes directory and golden path hang off this.
    */
  lazy val buildRoot: Path =
    val roots = LazyList(Try(codeSource).toOption, Some(Paths.get(""))).flatten.flatMap(walkUpToBuild)
    roots.headOption.getOrElse(
      throw IllegalStateException(
        "could not find build.mill above either the compiled classes or the working directory; " +
          "PublicApiTest cannot locate the module classes or their goldens"
      )
    )

  /** The class loader that sees every module's classes. In a `core.test` fork `core`, `vision`, `graphs` and
    * `zio` all sit on the same application classpath, so one loader resolves classes from any of them by
    * name.
    */
  private lazy val loader: ClassLoader = classOf[Managed[?]].getClassLoader

  /** Every binary class name under a directory or jar, unsorted. */
  private def binaryNames(source: Path): Seq[String] =
    if Files.isDirectory(source) then
      Using.resource(Files.walk(source)): stream =>
        stream
          .iterator()
          .asScala
          .filter(p => p.getFileName.toString.endsWith(".class"))
          .map(p => source.relativize(p).toString.replace(java.io.File.separatorChar, '/'))
          .map(_.stripSuffix(".class").replace('/', '.'))
          .toVector
    else
      Using.resource(JarFile(source.toFile)): jar =>
        jar
          .entries()
          .asScala
          .map(_.getName)
          .filter(_.endsWith(".class"))
          .map(_.stripSuffix(".class").replace('/', '.'))
          .toVector

  /** The `scalacv` classes compiled into `dir`, i.e. the surface of the module that owns `dir`.
    *
    * Loaded with `initialize = false` on purpose: initialising `OpenCv$` or any enum companion here would
    * drag the native libraries in as a side effect of an API check that does not need them.
    */
  def classesIn(dir: Path): Seq[Class[?]] =
    val names = binaryNames(dir).filter(n => n == "scalacv" || n.startsWith("scalacv."))
    val loaded = names.flatMap(n => Try(Class.forName(n, false, loader)).toOption)
    loaded.filter(keepClass)

  private def keepClass(c: Class[?]): Boolean =
    Modifier.isPublic(c.getModifiers) &&
      !c.isSynthetic &&
      !c.isAnonymousClass &&
      !c.isLocalClass &&
      !excludedName(c.getName)

  private def excludedName(n: String): Boolean =
    ExcludedNameFragments.exists(n.contains) || n.startsWith(OffsetPrefix) || ExcludedNames.contains(n)

  // ---------------------------------------------------------------------------------------------
  // Rendering
  // ---------------------------------------------------------------------------------------------

  /** `scalacv.CvError$DecodeFailed$` -> `scalacv.CvError.DecodeFailed`. */
  private def displayName(c: Class[?]): String =
    c.getName.stripSuffix("$").replace('$', '.')

  private def kind(c: Class[?]): String =
    val mods = c.getModifiers
    if c.getName.endsWith("$") then "object"
    else if c.isInterface then "trait"
    else if Modifier.isAbstract(mods) then "abstract class"
    else if Modifier.isFinal(mods) then "final class"
    else "class"

  private def typeParams(ps: Array[? <: java.lang.reflect.TypeVariable[?]]): String =
    if ps.isEmpty then ""
    else
      ps.map { p =>
        val bounds = p.getBounds.toVector.map(typeName).filterNot(_ == "java.lang.Object")
        p.getName + (if bounds.isEmpty then "" else bounds.mkString(" extends ", " & ", ""))
      }.mkString("<", ", ", ">")

  /** Erasure-free where the bytecode carries a generic signature, erased where it does not.
    *
    * Falls back to the erased form rather than failing: a generic signature that references a class absent
    * from the runtime classpath throws from `getGenericParameterTypes`, and a partial dump is worth more than
    * a crashed gate.
    */
  private def typeName(t: JType): String = t.getTypeName

  private def paramList(generic: => Array[JType], erased: => Array[Class[?]]): String =
    val ts = Try(generic.map(typeName).toVector).getOrElse(erased.map(typeName).toVector)
    ts.mkString("(", ", ", ")")

  private def memberMods(mods: Int): String =
    val b = StringBuilder()
    if Modifier.isStatic(mods) then b ++= "static "
    if Modifier.isAbstract(mods) then b ++= "abstract "
    if Modifier.isFinal(mods) then b ++= "final "
    b.result()

  /** `$lessinit$greater$default$1` -> `<init>$default$1`. */
  private def normaliseName(n: String): String = n.replace("$lessinit$greater", "<init>")

  private def render(m: Method): String =
    val ret = Try(typeName(m.getGenericReturnType)).getOrElse(typeName(m.getReturnType))
    val ps = paramList(m.getGenericParameterTypes, m.getParameterTypes)
    s"${memberMods(m.getModifiers)}def ${normaliseName(m.getName)}${typeParams(m.getTypeParameters)}$ps: $ret"

  private def render(c: Constructor[?]): String =
    val ps = paramList(c.getGenericParameterTypes, c.getParameterTypes)
    s"def <init>${typeParams(c.getTypeParameters)}$ps: void"

  private def render(f: Field): String =
    val t = Try(typeName(f.getGenericType)).getOrElse(typeName(f.getType))
    s"${memberMods(f.getModifiers)}val ${normaliseName(f.getName)}: $t"

  private def keepMember(name: String, mods: Int, synthetic: Boolean, bridge: Boolean): Boolean =
    Modifier.isPublic(mods) && !synthetic && !bridge && !excludedName(name)

  /** True when `object X` exists alongside class `X`, i.e. `X`'s static members are forwarders. */
  private def hasModule(c: Class[?], all: Set[String]): Boolean =
    !c.getName.endsWith("$") && all.contains(c.getName + "$")

  private def memberLines(c: Class[?], all: Set[String]): Vector[String] =
    val dropStatics = hasModule(c, all)
    def statically(mods: Int): Boolean = Modifier.isStatic(mods)

    val ms = c.getDeclaredMethods.toVector
      .filter(m => keepMember(m.getName, m.getModifiers, m.isSynthetic, m.isBridge))
      .filterNot(m => dropStatics && statically(m.getModifiers))
      .map(render)

    val fs = c.getDeclaredFields.toVector
      .filter(f => keepMember(f.getName, f.getModifiers, f.isSynthetic, false))
      .filterNot(f => dropStatics && statically(f.getModifiers))
      .map(render)

    val cs = c.getDeclaredConstructors.toVector
      .filter(k => keepMember("<init>", k.getModifiers, k.isSynthetic, false))
      .map(render)

    (ms ++ fs ++ cs).distinct.sorted

  /** The block for one class: a header line, its supertypes, then its sorted members. */
  private def classBlock(c: Class[?], all: Set[String]): Vector[String] =
    val header = s"${kind(c)} ${displayName(c)}${typeParams(c.getTypeParameters)}"
    val superClass = Try(c.getGenericSuperclass).toOption
      .flatMap(Option(_))
      .map(t => s"  extends ${typeName(t)}")
      .toVector
    val ifaces = Try(c.getGenericInterfaces.toVector).getOrElse(Vector.empty)
    val implemented =
      if ifaces.isEmpty then Vector.empty
      else Vector(ifaces.map(typeName).sorted.mkString("  implements ", ", ", ""))
    header +: (superClass ++ implemented ++ memberLines(c, all).map("  " + _))

  /** A class that exists only to carry static forwarders to its companion object.
    *
    * Scala emits `Build.class` next to `Build$.class` so Java can write `Build.scalaVersion()`. Once the
    * forwarders are filtered out nothing is left, and an empty block next to the real `object` one is pure
    * noise. Dropping it loses nothing: the object's own block carries every member.
    */
  private def isEmptyForwarderShell(c: Class[?], all: Set[String]): Boolean =
    hasModule(c, all) && memberLines(c, all).isEmpty

  /** Renders the given classes under `goldenLabel`'s header. Order of the argument is irrelevant — everything
    * is sorted here.
    */
  def dump(cs: Seq[Class[?]], goldenLabel: String): String =
    val all = cs.map(_.getName).toSet
    val blocks =
      cs.filterNot(c => isEmptyForwarderShell(c, all)).map(c => classBlock(c, all)).sortBy(_.head)
    (header(goldenLabel) +: blocks.map(_.mkString("\n"))).mkString("\n") + "\n"

  /** The dump of one module's compiled `scalacv` surface as it stands right now. */
  def currentFor(m: Module): String = dump(classesIn(m.classesDir), m.goldenLabel)

  // ---------------------------------------------------------------------------------------------
  // Golden files
  // ---------------------------------------------------------------------------------------------

  private def walkUpToBuild(start: Path): Option[Path] =
    Iterator
      .iterate(Option(start.toAbsolutePath.normalize))(_.flatMap(p => Option(p.getParent)))
      .takeWhile(_.isDefined)
      .flatten
      .find(p => Files.isRegularFile(p.resolve("build.mill")))

  def readGolden(p: Path): Option[String] =
    if Files.isRegularFile(p) then Some(String(Files.readAllBytes(p), StandardCharsets.UTF_8)) else None

  def writeGolden(p: Path, text: String): Unit =
    Files.createDirectories(p.getParent)
    Files.write(p, text.getBytes(StandardCharsets.UTF_8))

  /** Set by env var because Mill 1.1.7 gives no way to add a `-D` to a test fork from the command line; the
    * system property is honoured too, for anyone running the test JVM directly.
    */
  def regenerateRequested: Boolean =
    sys.props.get("scalacv.api.regenerate").exists(v => v.isEmpty || v == "true") ||
      sys.env.get("SCALACV_API_REGENERATE").exists(v => v == "1" || v.equalsIgnoreCase("true"))

  /** A line-oriented diff, budgeted so a wholesale rewrite does not bury the first real difference. */
  def diff(golden: String, actual: String): String =
    val g = golden.linesIterator.toVector
    val a = actual.linesIterator.toVector
    val firstDivergence = g.zip(a).indexWhere((x, y) => x != y)
    val added = a.filterNot(g.toSet).take(40)
    val removed = g.filterNot(a.toSet).take(40)
    val b = StringBuilder()
    b ++= s"golden has ${g.size} lines, the compiled API has ${a.size}\n"
    if firstDivergence >= 0 then
      b ++= s"first difference at line ${firstDivergence + 1}:\n"
      b ++= s"  - ${g(firstDivergence)}\n"
      b ++= s"  + ${a(firstDivergence)}\n"
    if removed.nonEmpty then b ++= removed.map("  - " + _).mkString("gone from the API:\n", "\n", "\n")
    if added.nonEmpty then b ++= added.map("  + " + _).mkString("new in the API:\n", "\n", "\n")
    b.result()

class ApiRendererFixture[A <: java.lang.Number]:
  final def value(a: A): A = a

/** The gate: each published module's compiled public surface must equal its committed `<module>/api.golden`.
  *
  * The renderer-invariant tests run against `core` as a representative surface (the renderer is the same for
  * every module); the per-module tests below gate `core`, `vision`, `graphs` and `zio` each against their own
  * golden.
  */
class PublicApiTest extends munit.FunSuite:

  test("the renderer retains method finality and generic bounds"):
    val dump = PublicApi.dump(Seq(classOf[ApiRendererFixture[?]]), "fixture")
    assert(dump.contains("A extends java.lang.Number"), dump)
    assert(dump.contains("final def value"), dump)

  private val core = PublicApi.coreModule
  private lazy val coreDump: String = PublicApi.currentFor(core)

  test("the core dump covers the whole scalacv package"):
    val names = PublicApi.classesIn(core.classesDir).map(_.getName).toSet
    // A discovery bug -- wrong classes dir, wrong package filter -- shows up as an empty or tiny set,
    // and an empty set would otherwise make every other assertion here vacuously true.
    assert(names.size > 50, s"only ${names.size} scalacv classes discovered in core; discovery is broken")
    // Landmarks from three different files, so a single-file regression cannot hide.
    Seq("scalacv.Managed", "scalacv.CvError", "scalacv.Images$", "scalacv.Ops$package$").foreach: n =>
      assert(names.contains(n), s"$n missing from the dump; discovered: ${names.toVector.sorted.take(20)}")
    // The classes dir must be core's output, not the test module's.
    assert(
      !names.exists(_.endsWith("Test")),
      s"test classes leaked into the API dump: ${names.filter(_.endsWith("Test"))}"
    )

  test("the core dump is totally ordered"):
    // Falsifiable: reflection returns members in an unspecified order that is not alphabetical (javap
    // shows `productIterator` before `hashCode` before `x`), so dropping any `.sorted` breaks this.
    val lines = coreDump.linesIterator.toVector.dropWhile(_.startsWith("#"))
    val (headers, members) = lines.partition(l => !l.startsWith("  "))
    assertEquals(headers, headers.sorted, "class blocks are not sorted")
    assertEquals(headers.distinct, headers, "duplicate class blocks in the dump")

    // Member lines are sorted within each block; `extends`/`implements` are positional, not sorted.
    var block = Vector.empty[String]
    var checked = 0
    def flush(): Unit =
      val ms = block.filterNot(l => l.startsWith("  extends ") || l.startsWith("  implements "))
      assertEquals(ms, ms.sorted, s"members out of order: $ms")
      assertEquals(ms.distinct, ms, s"duplicate members: $ms")
      if ms.nonEmpty then checked += 1
      block = Vector.empty
    lines.foreach: l =>
      if l.startsWith("  ") then block = block :+ l else flush()
    flush()
    assert(checked > 30, s"only $checked non-empty class blocks were order-checked")
    assert(members.nonEmpty)

  test("the core dump does not depend on class discovery order"):
    // The real non-determinism risk: a filesystem or jar walk that hands classes back in a different
    // order on another machine. A fixed seed keeps the test itself reproducible.
    val shuffled = Random(20260723L).shuffle(PublicApi.classesIn(core.classesDir).toVector)
    assertEquals(PublicApi.dump(shuffled, core.goldenLabel), coreDump)

  test("two independent core dumps are byte-identical"):
    val a = PublicApi.currentFor(core).getBytes(StandardCharsets.UTF_8)
    val b = PublicApi.currentFor(core).getBytes(StandardCharsets.UTF_8)
    assert(java.util.Arrays.equals(a, b), "the dump is not reproducible within a single JVM")

  // One gate per published module: core, vision, graphs and zio each against their own golden.
  PublicApi.modules.foreach: m =>
    test(s"the ${m.name} public API matches ${m.goldenLabel}"):
      val actual = PublicApi.currentFor(m)

      // No compiler-generated noise reaches any module's dump (same renderer, same filters as core).
      val offenders = actual.linesIterator
        .filter: line =>
          Seq("$anonfun", "$$anon$", "$lzy", "OFFSET$", "MODULE$", "$values", "$lessinit$greater")
            .exists(line.contains)
        .take(10)
        .toVector
      assert(offenders.isEmpty, s"non-deterministic or synthetic entries in the ${m.name} dump: $offenders")

      if PublicApi.regenerateRequested then
        val previous = PublicApi.readGolden(m.goldenPath)
        PublicApi.writeGolden(m.goldenPath, actual)
        println(s"[PublicApiTest] regenerated ${m.goldenPath}")
        // A regenerating run must never be able to produce a green build. If SCALACV_API_REGENERATE
        // were ever exported in a shell profile or a CI environment, a silently-passing regeneration
        // would disable this gate permanently -- which is precisely the failure it exists to catch.
        if !previous.contains(actual) then
          fail(
            s"regenerated ${m.goldenPath}. Re-run WITHOUT SCALACV_API_REGENERATE, and " +
              s"commit the diff after reading it:\n  git diff ${m.goldenLabel}"
          )
      else
        PublicApi.readGolden(m.goldenPath) match
          case None =>
            fail(
              s"${m.goldenPath} does not exist. Create it with:\n" +
                "  SCALACV_API_REGENERATE=1 ./mill core.test"
            )
          case Some(golden) if golden == actual => ()
          case Some(golden) =>
            fail(
              s"scalacv's ${m.name} public API no longer matches ${m.goldenLabel}.\n\n" +
                PublicApi.diff(golden, actual) +
                "\nIf every line above is an intended API change, regenerate and commit the result:\n" +
                "  SCALACV_API_REGENERATE=1 ./mill core.test\n" +
                s"  git diff ${m.goldenLabel}   # then read it\n" +
                "Otherwise the change is accidental -- fix the source, not the golden file."
            )
