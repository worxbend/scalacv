#import "../lib/book.typ": *

#chapter("Setting Up", subtitle: [Four artifacts, two dependency lines, one load call, and the cache that pays for all of it.])

Most Scala libraries install by adding a line to a build file. You add the coordinate, the resolver
walks the POM graph, and the code you wrote against the API on the website runs. That machinery was
designed around one assumption: that the thing being resolved is portable bytecode, identical on
every machine that will ever run it.

An OpenCV binding breaks that assumption in the first minute. Almost nothing you are installing is
Scala, and almost nothing is Java. OpenCV is a C++ library, and what your program actually calls is
several hundred megabytes of machine code compiled separately for Linux on x86-64, Linux on ARM64,
macOS on Apple silicon, macOS on Intel and Windows on x86-64. Those builds are not
interchangeable, they are not small, and Maven's vocabulary for "the same artifact, compiled for a
different machine" is the classifier --- a field that a published POM cannot carry through to a
downstream consumer. So the dependency resolution that works perfectly everywhere else stops one
step short of a program that runs.

The result is the most common way to have a bad first day with a JVM computer-vision library. The
Scala compiles. The types resolve. `org.opencv.core.Mat` is right there in the autocomplete. Then
the first call that crosses into native code dies with an `UnsatisfiedLinkError` naming a shared
object you have never heard of, and says nothing about which of the five platform jars you were
supposed to add. scalacv treats that as a design problem rather than a documentation problem:
`OpenCv.load()` catches the link failure and rewrites it into a message naming the platform you are
actually on, with the exact two lines to paste. Better still not to arrive there.

#sect("The four artifacts")

#warning[
  The installation coordinates in this book name source tag `v0.4.1`, not a verified Maven Central
  deployment. Central upload and attestation are disabled; tag workflows create draft releases.
  Build a clean checkout of that tag with `./mill __.publishLocal` and enable local Ivy resolution
  (`ivy2Local` in Coursier; sbt includes it by default). Unreleased fixes described in this book
  need the current checkout's actual `./mill show core.publishVersion` instead. Keep all four
  artifact versions aligned. Website deployment is not registry publication.
]


scalacv defines publication coordinates under the group id `com.worxbend`, as four separate artifacts. Only the first is
required. The other three are opt-in, and the split is real rather than cosmetic: `vision` and
`graphs` depend on `core` and on nothing else, so a service that reads and filters images never
pulls a SLAM loop-closure detector into its jar.

#figure-table("The published artifacts, and what lives in each.")[
#tbl(
  columns: (auto, 1fr),
  [*Artifact*], [*What it holds*],
  [`scalacv`], [The core: `Image`, `Managed`, `Camera`, `Video`, `Recorder`, contours, thresholding, the filter catalogue and the draw verbs],
  [`scalacv-vision`], [`Cascades`, `FaceRecognizer`, `Dnn`, `Ocr`, `Ar`, `MotionDetector`, `Features`, `OpticalFlow`, plus pose, tracking, calibration and the navigation front end],
  [`scalacv-graphs`], [The `Picture` scene graph, `Color`, `Chart` and animated GIF output],
  [`scalacv-zio`], [The ZIO integration, in package `scalacv.zio`],
)
]

Those names are the `artifactName` values in the project's own `build.mill`, not the directory
names --- the module directories are `core`, `vision`, `graphs` and `zio`, and without the explicit
override Mill would have published `com.worxbend:core_3`.

One consequence of the split is worth learning before it bites. Each module owns a distinct
package: `scalacv`, `scalacv.vision`, `scalacv.graphs`, or `scalacv.zio`. Add the dependency and the
wildcard import for every layer you use:

#example("Core plus optional layers: keep only the imports whose modules you use.")[
```scala
import scalacv.*
import scalacv.vision.*
import scalacv.graphs.*
import scalacv.zio.*
```
]

A missing module or import reports a missing symbol or member rather than a missing dependency.
`Not found: Cascades` is at least recognisable. `value faces is not a member of scalacv.Image` is
the confusing one, because `import scalacv.*` brings `Image` into scope but cannot activate an
extension in `scalacv.vision`. Both the vision jar and `import scalacv.vision.*` are required.

#note[
If a scalacv method the documentation describes will not compile, check its dependency and wildcard
import before you check your spelling. A core import does not import the optional layers.
]

#sect("Why there are two dependency lines")

`scalacv`'s own POM declares exactly one OpenCV coordinate: `org.bytedeco:opencv`, without a
classifier. That artifact is the OpenCV *Java API* --- the `org.opencv.*` classes, the JNI method
declarations, and no machine code whatsoever. It is enough to compile against and useless to run.

The machine code ships in sibling artifacts distinguished only by a classifier: same group, same
name, same version, different payload. A build tool can express that locally. It cannot express it
in a POM it publishes --- Mill 1.1.7 cannot write a classifier into a POM at all, so anything
classified in `core`'s dependency list would be silently stripped and consumers would resolve
nothing. And if the library did pick a platform on your behalf, it would be the wrong one for
everybody who does not share it. So the second line is yours.

#figure-table("The classifier for each supported target.")[
#tbl(
  columns: (1fr, auto),
  [*Where the code runs*], [*classifier*],
  [Linux x86-64], [`linux-x86_64`],
  [Linux ARM64], [`linux-arm64`],
  [macOS Apple silicon], [`macosx-arm64`],
  [macOS Intel], [`macosx-x86_64`],
  [Windows x86-64], [`windows-x86_64`],
)
]

Pick for the machine that will *run* the code, not the one that builds it: a `linux-x86_64` jar in a
container image built on an Apple-silicon laptop is a straight failure at startup. bytedeco also
publishes `-gpu` variants of `linux-x86_64`, `linux-arm64` and `windows-x86_64` for CUDA work, and
publishes no OpenCV natives at all for `windows-arm64` --- scalacv's own build refuses to derive a
classifier for that platform and says so in those words rather than resolving something that cannot
exist.

The second half of the trap is that one classifier line is not enough. Here is the version people
write first:

#example("Wrong: one native line. `OpenCv.load()` will not get through this.")[
```scala
def mvnDeps = Seq(
  mvn"com.worxbend::scalacv:0.4.1",
  mvn"org.bytedeco:opencv:4.13.0-1.5.13;classifier=linux-x86_64"
)
```
]

`libopencv_core` has a `NEEDED` entry on `libopenblas.so.0`, which ships only in openblas's own
classifier jar. Omit it and you get a half-loaded library: `load()` fails with an
`UnsatisfiedLinkError` that scalacv converts into a `CvError.NativesMissing` naming exactly what is
absent. Both lines, always, and both with the same classifier:

#example("Right: the API arrives through scalacv, the natives through two classifier jars.")[
```scala
def mvnDeps = Seq(
  mvn"com.worxbend::scalacv:0.4.1",
  mvn"org.bytedeco:opencv:4.13.0-1.5.13;classifier=linux-x86_64",
  mvn"org.bytedeco:openblas:0.3.31-1.5.13;classifier=linux-x86_64"
)
```
]

Two lines for you, three coordinates in total. The classifier-less API jar is the third, and it
arrives transitively through `scalacv`. That distinction matters if you ever vendor the natives into
a build of your own: a classifier dependency *replaces* the classifier-less artifact in resolution
rather than adding to it, which is why scalacv's own `build.mill` declares all three explicitly in
its `opencvNatives` block. Declare only the classifier and you get a classpath with zero
`org/opencv/` classes on it.

#subsect("The fat option, and what it costs")

If you would rather not choose --- a workshop, a demo repository, a laptop that dual-boots --- there
is one coordinate that works everywhere:

```scala
mvn"org.bytedeco:opencv-platform:4.13.0-1.5.13"
```

It bundles every platform's natives, including openblas, so it replaces both classifier lines --- at
about *408 MB* against the 36--80 MB a single platform pair weighs (for `linux-x86_64`, about 31 MB
of `opencv` and 20 MB of `openblas`). A good trade on a developer machine, a poor one in a container
you will pull a thousand times a day.

#sect("The build files")

Mill is the project's own tool, so its syntax is the one every scalacv source file and error message
is written against. Note the quirk of `mvn"…"`: the classifier goes after a semicolon, inside the
interpolated string, not as a separate argument.

#example("A complete consumer `build.mill`.")[
```scala
//| mill-version: 1.1.7
package build

import mill.*, scalalib.*

object app extends ScalaModule {
  def scalaVersion = "3.3.8"

  def mvnDeps = Seq(
    mvn"com.worxbend::scalacv:0.4.1",         // core: images, video, contours, drawing, filters
    mvn"com.worxbend::scalacv-vision:0.4.1",  // detectors, DNN, pose, tracking, OCR, calibration
    mvn"com.worxbend::scalacv-graphs:0.4.1",  // the Picture scene graph, charts, animated GIFs
    mvn"com.worxbend::scalacv-zio:0.4.1",     // only if you use ZIO

    mvn"org.bytedeco:opencv:4.13.0-1.5.13;classifier=linux-x86_64",
    mvn"org.bytedeco:openblas:0.3.31-1.5.13;classifier=linux-x86_64"
  )

  def forkArgs = Seq("-Djava.awt.headless=true")
}
```
]

Delete the scalacv lines you do not need; keep both bytedeco ones. In sbt the same set uses `%%` for
the Scala artifacts and `%` for the bytedeco jars, which are ordinary Java-world coordinates with no
Scala suffix:

#example("The sbt equivalent.")[
```scala
libraryDependencies ++= Seq(
  "com.worxbend" %% "scalacv"        % "0.4.1",
  "com.worxbend" %% "scalacv-vision" % "0.4.1",
  "com.worxbend" %% "scalacv-graphs" % "0.4.1",
  "com.worxbend" %% "scalacv-zio"    % "0.4.1",
  "org.bytedeco" %  "opencv"         % "4.13.0-1.5.13" classifier "linux-x86_64",
  "org.bytedeco" %  "openblas"       % "0.3.31-1.5.13" classifier "linux-x86_64"
)
```
]

scala-cli is the fastest way to try the library at all, because a single file carries its own
dependencies. Its `using dep` directives take coursier's dependency syntax, where the classifier is
a comma-separated attribute:

#example("A self-contained scala-cli header.")[
```scala
//> using scala 3.3.8
//> using dep com.worxbend::scalacv:0.4.1
//> using dep org.bytedeco:opencv:4.13.0-1.5.13,classifier=linux-x86_64
//> using dep org.bytedeco:openblas:0.3.31-1.5.13,classifier=linux-x86_64
//> using javaOpt -Djava.awt.headless=true
```
]

scalacv is compiled with Scala 3.3.8 --- the LTS line, deliberately, because TASTy is not
forward-compatible and a library published from the Next line cannot be read by anyone on 3.3.x ---
and targets JDK 17 and later. On JDK 24 and newer, `System.load` emits a JEP 472 warning and Scala
3.3.8's `LazyVals$` provokes four lines of `sun.misc.Unsafe` deprecation noise. Neither is a
problem; both are noise, and the project silences them with two flags that *do not exist* on 17 or
21, where passing them is not a warning but `Unrecognized option` and a JVM that does not start.

#example("Runtime flags, but only on JDK 24 and newer.")[
```bash
java --enable-native-access=ALL-UNNAMED \
     --sun-misc-unsafe-memory-access=allow \
     -Djava.awt.headless=true \
     -cp app.jar hello
```
]

#sect("What `OpenCv.load()` actually does")

Call it once, at the top of your program, before anything else in the library. It is idempotent and
thread-safe --- a `\@volatile` flag guarding a double-checked block --- so putting it at the top of
every entry point costs nothing after the first, and you should never gate it behind a flag of your
own. `OpenCv.isLoaded` reports whether the first call has completed.

What it does not need is as important as what it does. It needs no GUI toolkit, no X server, no
`DISPLAY`, and no `apt-get install libgtk2.0-0t64` on a CI runner. That is not a happy accident of
the bundled build; it is the reason the loader does not use the one-line form every OpenCV-on-the-JVM
snippet reaches for.

#sidebar("Why not `Loader.load(classOf[opencv_java])`?")[
That one-liner initialises the *whole* preset graph. One member of that graph is
`opencv_highgui`, which on Linux is linked against GTK2. On a headless box it throws --- and takes `objdetect`, `calib3d`, `features2d` and `video`
down with it, which is to say precisely the modules a vision library exists to call.

`libopencv_java` itself links no GUI toolkit at all. So `OpenCv.load()` brings javacpp up through a
GUI-free preset (`org.bytedeco.opencv.global.opencv_core`), extracts the platform payload with
`Loader.cacheResources`, and then loads only the JNI shim plus whatever that shim actually asks for.
On Linux the shim never asks for highgui; on macOS it does. The same code is correct on both without
a platform conditional. Do not "simplify" it back.
]

The loading itself is demand-driven, and the reason is a crash rather than an efficiency argument.
The bundled `libopencv_highgui.so` carries *unversioned* `NEEDED` entries --- `libopencv_core.so`,
not `libopencv_core.so.413`. Speculatively `dlopen`-ing every module library with `RTLD_GLOBAL`
makes the dynamic linker search the system path for those names, and on a machine that happens to
have a distribution OpenCV installed it does not fail. It succeeds, mapping six
`libopencv_*.so.5.0.0` system libraries into the global namespace, where they interpose on the
4.13.0 symbols the rest of the process is using.

#memory[
Two OpenCV ABIs in one address space is not a link error --- it is a `Mat` allocated by one version
and freed by the other. The process dies inside `cv::Mat::release()` with no Java stack trace, at
whatever point the two ABIs first meet, which may be minutes after startup and nowhere near the
code that caused it. This was reproduced exactly that way on a developer machine with a system
OpenCV 5 installed, and it is why the loader never loads a library it was not asked for.
]

So on Linux and macOS the sequence is: try `System.load` on the JNI shim; read the missing soname
out of the `UnsatisfiedLinkError`; load *that one library*, by absolute path, from the extracted
payload; retry. A dependency the payload cannot satisfy becomes a real `CvError.NativesMissing`
rather than something the linker quietly resolves against whatever the host has lying around, and
asking twice for the same soname is treated as a failure rather than an infinite retry.

Windows is the exception. Its link error reads `Can't find dependent libraries` and names nothing,
so there is no soname to act on and a bulk retry-load is used instead --- safe there for the same
reason it is unsafe elsewhere, since Windows DLL names embed the version (`opencv_core4130.dll`, not
`opencv_core.dll`), and `opencv_highgui` there links only the always-present USER32 and GDI32.

The gate on that fallback is the platform string, not the shape of the error, and the distinction is
load-bearing. "The message named no library" is not a Windows-only condition: a cross-classloader
conflict, an undefined symbol, a `noexec` cache mount and an architecture mismatch all produce
messages the soname parser cannot read, on every platform. Deciding to bulk-load from the error
alone would sweep the payload with `RTLD_GLOBAL` on exactly the Linux machines the previous
paragraph is about. So off Windows an unparseable error is rethrown untouched, and the gate fails
closed --- an absent or unrecognised platform means "not safe", because the cost of wrongly
declining is a clear error and the cost of wrongly allowing is a segfault with no Java stack
trace.

One more piece of defence explains a library that appears to be in the payload and is ignored
anyway. javacpp materialises the unversioned aliases as symlinks, and on a machine with OpenCV
already installed one can point straight out of the cache --- where this was found,
`libopencv_highgui.so` resolved to `/usr/lib/libopencv_highgui.so.5.0.0`, whose own dependencies
name OpenCV 5.x. Any extracted entry whose canonical path escapes the directory javacpp extracted it
into never enters the payload map at all.

#warning[
`Image.blank`, `Image.read`, `Camera.open` and every other constructor cross into native code.
Calling one before `OpenCv.load()` is the single most common first-run mistake. Put the call at the
top of your `main`, or in a test fixture, so it always runs first.
]

#sect("The extraction cache")

The natives are not on your classpath as loadable code. They are payloads *inside* the bytedeco
jars, and before the dynamic linker can touch them they have to exist as real files on a real
filesystem. That is what the first `OpenCv.load()` in a fresh environment does: it unpacks the
platform's libraries out of the jars into a cache directory, once. On Linux that is about *196 MB*
under `~/.javacpp`, and every later run reuses it. Extraction is content-addressed and idempotent,
so re-running never re-extracts and two processes can share one directory safely.

That once-per-environment cost is invisible on a laptop and very visible on a cold container start.
Point javacpp elsewhere when `~/.javacpp` does not suit --- a read-only home, a thin image layer, a
cache shared across CI builds:

#example("Relocating the native cache, two ways.")[
```bash
java -Dorg.bytedeco.javacpp.cachedir=/var/cache/javacpp -jar your-app.jar

# Or, for containers where you do not control the java invocation:
export JAVA_TOOL_OPTIONS="-Dorg.bytedeco.javacpp.cachedir=/var/cache/javacpp"
```
]

The directory must be writable on first use; after that it can be mounted read-only. Because
extraction is idempotent, running any main that calls `OpenCv.load()` at image-build time bakes the
unpacked libraries into a layer and makes the first `load()` in every container instant. The
opposite choice --- mounting the cache on a shared volume --- keeps the image thin and pays the
warm-up once per volume. Pick per workload; there is no default that is right for both.

Two smaller stores sit beside it. Haar cascade XML is a classpath resource, and OpenCV only knows
filesystem paths, so `Cascades.resolve` extracts the one you name out of the classifier jar's
`share/opencv4/haarcascades/` directory into the same javacpp cache --- on demand, cached after the
first call, and needing no native load of its own, which is why it works before `OpenCv.load()`. A
cascade-based detector therefore ships nothing extra. The exception is `windows-x86_64`, whose jar
carries an empty `share/` directory and no cascades at all; there `resolve` returns a
`CvError.LoadFailed` naming the platform rather than a null. Downloaded DNN and face-recognition
models are the other store, and they are yours to place: `Models.fetch` writes them where you point
it, checking each file against the size and the SHA-256 its `ModelSpec` pins --- on download *and*
on every cache hit, so a truncated file is caught on the run after the one that fetched it.

#memory[
Set a ceiling in production, and set the right one.
`-Dorg.bytedeco.javacpp.maxPhysicalBytes` reads process RSS, so it sees OpenCV's `cv::fastMalloc`
buffers and will stop a leaking `Mat` loop before the kernel does. `-Dorg.bytedeco.javacpp.maxBytes`
tracks only javacpp's own `Pointer` accounting, which is blind to `org.opencv.core.Mat` entirely ---
gate on it and a leak runs RSS to the moon while the number you are watching sits flat.
]

Five settings cover everything a scalacv process wants told at startup. None is required to get a
first run working; all are worth knowing before a run happens somewhere you cannot attach a
debugger.

#figure-table("The properties a scalacv process reads at startup.")[
#tbl(
  columns: (auto, 1fr),
  [*Property*], [*Effect*],
  [`org.bytedeco.javacpp.cachedir`], [Where the natives are unpacked. Defaults to `~/.javacpp`; must be writable the first time, and may be read-only afterwards],
  [`org.bytedeco.javacpp.maxPhysicalBytes`], [The ceiling to set. Reads process RSS, so it counts OpenCV's own `cv::fastMalloc` buffers],
  [`org.bytedeco.javacpp.maxBytes`], [javacpp's `Pointer` accounting only, which never sees an `org.opencv.core.Mat`. Not a leak guard],
  [`java.awt.headless`], [Set it to `true` as an assertion: a GUI-toolkit regression then fails loudly instead of opening a window on somebody's laptop],
  [`scalacv.trackOwnership`], [Records where each native handle was spent, so a use-after-move exception carries the consuming call as its cause. Off by default --- it allocates a `Throwable` per spend],
)
]

`JAVA_TOOL_OPTIONS` carries any of them into a JVM whose command line you do not control --- in
practice, a container entrypoint written by someone else. Reach for `scalacv.trackOwnership` the
first time a pipeline throws `IllegalStateException: this Mat has already been released or consumed`
and the stack trace points at the reuse rather than at the call that spent the handle.

#sect("Hello, it works")

The smallest honest proof that the install is complete is a program that allocates a real image,
runs a real OpenCV kernel over it, and produces bytes --- with no file on disk to go missing and no
window to open. `Image.blank` gives you a canvas, `bytes` encodes and releases in one step, and the
whole thing is four calls.

#example("`hello.scala` --- a headless smoke test with no inputs.")[
```scala
import scalacv.*

@main def hello(): Unit =
  OpenCv.load()

  val png: Either[CvError, Array[Byte]] =
    Image
      .blank(160, 120, Scalar.White)
      .drawRect(Rect(30, 30, 90, 60), Scalar.Black)
      .gray
      .canny(50, 150)
      .bytes(".png")

  png match
    case Right(bytes) => println(s"OK: ${bytes.length} bytes of PNG")
    case Left(err)    => println(s"failed: ${err.getMessage}")
```
]

A white canvas, a black rectangle drawn on it, converted to grey, run through Canny edge detection,
encoded as a PNG in memory. Every step consumes the image it was called on and hands back a fresh
one, so exactly one native buffer is alive at any moment and `bytes` releases the last of them ---
which is the ownership rule Chapter 5 is entirely about. For now the only thing to read out
of it is the printed line.

Run it with whichever build you set up:

```bash
./mill app.runMain hello       # Mill
sbt "runMain hello"            # sbt
scala-cli run hello.scala      # scala-cli
```

The first run pauses for the extraction described above and then prints a number of bytes. Every run
after that starts immediately. If instead you get a stack trace, read the next section before you
change anything.

#sect("Verifying the install")

`Core.VERSION` is the first thing everyone prints, and it proves nothing. It is a plain static
`String` resolved from constants at class-initialisation time: a program with *zero* natives on the
classpath prints `4.13.0` and exits successfully, having crossed no JNI boundary at all. scalacv's
own smoke-test example allocates a `Mat` instead, precisely because allocation is the thing under
test.

What you can trust is `scalacv.Build`, which reports how the artifact on your classpath was
actually built. Every value in it is generated from the `Deps` block in `build.mill` rather than
typed out a second time, so it cannot drift from the versions the build resolved --- which is the
whole point of a number you are going to quote in a bug report.

#figure-table("What `scalacv.Build` exposes.")[
#tbl(
  columns: (auto, 1fr),
  [*Value*], [*What it reports*],
  [`Build.scalaVersion`], [The Scala version scalacv was compiled with],
  [`Build.openCvVersion`], [The OpenCV release, in OpenCV's own numbering: `4.13.0`],
  [`Build.openCvArtifactVersion`], [The `org.bytedeco:opencv` coordinate version, OpenCV paired with javacpp: `4.13.0-1.5.13`. This is the string that goes in a build file],
  [`Build.openBlasArtifactVersion`], [The `org.bytedeco:openblas` version the natives need],
)
]

The distinction between the last two is the one that costs people an afternoon. The natives for your
platform must resolve at *exactly* the artifact version, because the Java API and the JNI shim it
calls have to come from the same javacpp build; `4.13.0` on its own is not a coordinate. Extend the
hello program to print all of it, and you have a report worth pasting into an issue:

#example("A version report you can paste into a bug report.")[
```scala
import org.bytedeco.javacpp.Loader

println(s"scalacv  built with Scala ${Build.scalaVersion}")
println(s"opencv   org.bytedeco:opencv:${Build.openCvArtifactVersion}")
println(s"openblas org.bytedeco:openblas:${Build.openBlasArtifactVersion}")
println(s"platform ${Loader.getPlatform}")
println(s"loaded   ${OpenCv.isLoaded}")
println(s"runtime  ${org.opencv.core.Core.VERSION} (expected ${Build.openCvVersion})")
println(s"headless ${java.awt.GraphicsEnvironment.isHeadless}")
```
]

`Loader.getPlatform` is worth printing on its own: it is the exact string the loader will look for
inside the jars, and if it disagrees with the classifier you added, that disagreement *is* your
bug. The project's own examples module carries two mains in this spirit --- `jvmReport`, which
prints `java.version`, `java.vendor` and `java.vm.name` so a three-JDK CI matrix cannot silently run
the same JDK three times, and `smoke`, which allocates an 8×8 `Mat` across JNI and constructs one of
each detector to prove its module linked, releasing every one.

#subsect("When it goes wrong anyway")

If the natives are missing or wrong, `OpenCv.load()` does not hand you the linker's error. It throws
`CvError.NativesMissing` carrying this, with the version numbers taken from the build and the
platform taken from `Loader.getPlatform` at the moment of failure:

```text
OpenCV natives are not on the classpath (<the underlying message>).

scalacv depends on the classifier-less OpenCV Java API only, because a build tool cannot
express a per-platform classifier in a published POM. Add the natives for your platform:

  "org.bytedeco" % "opencv"   % "4.13.0-1.5.13" classifier "linux-x86_64"
  "org.bytedeco" % "openblas" % "0.3.31-1.5.13" classifier "linux-x86_64"

Both lines are needed: libopencv_core links libopenblas. If you would rather not pick a
platform, "org.bytedeco" % "opencv-platform" % "4.13.0-1.5.13" bundles every
one, at a cost of about 408 MB.
```

One failure wears that exception's clothes without being it, and gets its own message: scalacv
loaded from two classloaders at once. The loader detects that case and prints the real fix rather
than asking for dependencies you demonstrably already have. Chapter 42, #emph[Troubleshooting], has
the message and the remedy in full.

#tip[
Before you debug a native problem, check the cheap things in order: both bytedeco lines present,
both with the *same* classifier, that classifier matching `Loader.getPlatform` on the machine that
runs the code, and `OpenCv.load()` called before the first constructor. Four checks resolve almost
every first-day failure.
]

#sect("Where this leaves you")

You now have a project that resolves, a JVM that loads roughly 196 MB of C++ without a window
manager anywhere in sight, a cache that makes every subsequent start immediate, and a four-call
program that proves all of it. What you do not yet have is any feel for what those four calls cost, or why each
one hands back a new `Image` instead of mutating the one you gave it.

Chapter 3, #emph[Your First Pipeline], answers the first half. It takes the skeleton nearly every vision
program has --- get pixels, simplify them, measure something, draw the answer, write it out --- and
builds it as one chain that frees itself at every link, with `Image.reading` scoping a file to a
block and `Either` carrying the failures that are data rather than defects. Chapter 5,
#emph[Lifetimes: Managed, Releasable, and Scope], then goes underneath the chain to `Managed`,
`Releasable` and the move semantics that make it leak-free: who owns a native handle, when it is
freed, and what the `IllegalStateException: this Mat has already been released or consumed` means
when you reach for one that has already been spent. From here on, every listing in this book assumes
the `import scalacv.*` and the single `OpenCv.load()` written above, and repeats them only where the
listing is a complete program.
