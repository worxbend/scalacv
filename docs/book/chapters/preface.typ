#import "../lib/book.typ": *

= Preface

Computer vision on the JVM starts out easy, and then quite suddenly is not. Reading a JPEG,
converting it to grey, running an edge detector and writing the result back to disk is a dozen lines
against OpenCV's Java API, and it works the first time. The trouble arrives somewhere around the
two-thousandth image, when a service that has sat quietly inside a modest heap for a week is killed
by the kernel for holding six gigabytes of resident memory --- and the heap dump, the first place
anyone looks, shows nothing wrong at all.

The reason is that OpenCV's central type does not live on the heap. A `Mat` is roughly forty bytes
of Java object standing in front of a pixel buffer the native library allocated somewhere the
garbage collector cannot see. The collector schedules its work by heap pressure, and heap pressure
is the wrong signal: forty bytes per megabyte will not provoke a collection, so a frame loop can
exhaust the machine while the collector, correct by its own lights, never runs. Measured on this
project's test machine: 2000 allocations of `Mat(1000, 1000, CV_8UC3)`, references dropped, no
explicit `System.gc()`, finished at 5,865 MB resident. The same program calling `release()` finished
at 144 MB. Forty-one times, on JDK 21 and again on JDK 25.

The OpenCV documentation will not help you, because outside the JVM the problem does not exist: C++
destroys a `Mat` when it leaves scope, and Python's bindings reference-count. It is a JVM problem,
and it has two siblings the upstream docs are equally silent about --- an API where every option is
an untyped `int` constant, so a transposed digit compiles and quietly produces the wrong picture,
and a native distribution whose obvious load path initialises a GTK2-linked `highgui` and, on a
machine with no desktop, takes `objdetect` down with it.

`scalacv` takes those three problems as its subject: a Scala 3 API over the OpenCV 4.13 Java
bindings, typed where the bindings are untyped, scoped where they leak, headless by construction.
This is also a book about computer vision, because a wrapper is only worth reading about if it takes
you somewhere --- so by the end you will have built edge detectors, contour counters, face
detectors, a motion alarm, a calibrated camera, and a service that runs all of it without falling
over.

#sect("Who this book is for")

You are a Scala 3 developer who needs to do something with images or video and would rather not
spend a fortnight learning the C++ conventions of a library accreting since 2000. Or you are a JVM
engineer who has already been burned by native memory --- a `Mat`, a `ByteBuffer`, a JNI handle,
something --- and you want to know exactly who frees what before any of it goes near production.

Either way, this book assumes you read Scala 3 without effort: a case class, an extension method, a
`for` comprehension over `Either`, a `given`. Nothing needs macros or type-level programming, though
there is a ZIO layer for people who already run one. No OpenCV knowledge is assumed --- kernels,
colour spaces, homographies and camera intrinsics arrive at the point of use, with the least theory
that makes the parameters mean something, and the arithmetic appears where a formula is the
difference between choosing a threshold and guessing one.

Two things the book does not do. It does not teach you to train neural networks --- Part V runs an
ONNX model through OpenCV's DNN module, it does not produce one. And it does not present the library
as a Java API: `scalacv` returns `Seq`, `Option` and `Either` through extension methods brought in
by `import scalacv.*`, and is not designed to be called from Java.

#sect("Why this book exists")

OpenCV's Java bindings are generated --- a faithful, mechanical translation of a C++ header set, and
that faithfulness is what makes them awkward from Scala. The names keep C++ capitalisation, so
`Imgproc.GaussianBlur`, `Imgproc.Canny`, `Imgproc.Sobel` and `Imgproc.Laplacian` sit in otherwise
lower-camel code. `Imgcodecs.imread` reports a missing file by returning an empty `Mat` rather than
throwing, so the failure surfaces three calls later. `Imgproc.HoughLinesP` hands back `CV_32SC4`
integers where you expect floats. Each is defensible as a translation and indefensible as an API you
have to remember.

The lifetime story is worse, and it is why the library exists at all. Of the 188 `org.opencv.*`
types that own native memory, exactly three expose a public `release()`. `CascadeClassifier`, `Net`,
`QRCodeDetector`, `ArucoDetector` and 181 others give you no way to free them from Java --- there is
no method to forget to call, which is a worse position than having one. Measured the same way as the
`Mat` loop: 4000 leaked `KalmanFilter`s reached 54 GB of resident memory, against 86 MB when
`scalacv` freed them.

#memory[
  The book's signature callout. It marks the places where native lifetime is at stake: who owns a
  handle, when it is freed, and what happens if you touch it afterwards. Getting that wrong costs
  gigabytes of resident memory, or a SIGSEGV with no Scala stack trace. It is never a matter of
  style.
]

None of this is in the OpenCV documentation, which is written for C++ and adapted for Python. The
answers are scattered across mailing lists, bug reports and the bindings' own source; this book
collects them.

#sect("How this book is organised")

Seven parts, in the order you would meet the material building something real.

- *Part I, Foundations* --- why the bindings need wrapping, the dependency block and why the
  natives arrive as lines of their own, a first pipeline, the `Image` type, the ownership model,
  and the error policy that decides what returns `Either[CvError, A]` and what throws.

- *Part II, Working with Pixels* --- reading and writing, the typed enums that replace the `int`
  constants, filters and morphology, geometric transforms, colour segmentation, contours, Hough,
  drawing, photo transforms.

- *Part III, The Graphics Layer* --- the `Picture` scene graph in `scalacv-graphs`: primitives,
  layout, affine transforms, dashed strokes, the `Color` palette, `Chart`, and `Animation` with its
  GIF encoder, composited onto an image by `draw`.

- *Part IV, Video and Cameras* --- the borrowing `Video.frames` path and the copy-or-borrow decision
  that separates a convenient frame loop from a fast one, `Camera` and `Recorder` on a source that
  never ends, motion detection, and what to do when the pipeline falls behind.

- *Part V, Vision Applications* --- where models come from, faces and recognition, ONNX inference,
  object detection, ArUco markers, pose, tracking, calibration, visual navigation, OCR
  preprocessing, screen capture and conferencing.

- *Part VI, Into Production* --- where the time and the memory go, concurrency, the ZIO layer,
  observability, degradation, testing in CI with no display server, deployment, troubleshooting.

- *Part VII, Appendices* --- the escape hatch to raw OpenCV, the operation and enum tables,
  notebook display, and the glossary.

Read Parts I and II in order; after that the parts stand alone, and a chapter that leans on an
earlier one says so in its first paragraph.

#sect("Conventions used in this book")

Every type, method, parameter, enum case, flag and path is set in code, so `Image.reading` and
`ColorConversion.BgrToGray` stay unambiguous mid-sentence. Listings the prose refers back to are
numbered per chapter --- `Example 4-2` is the second listing in Chapter 4 --- and so are tables, as
`Table 4-1`. Five callouts appear, and they mean different things. A sixth device, the boxed
sidebar, carries a digression that would derail a paragraph --- there is one below.

#figure-table("The callout vocabulary, in ascending order of how much attention it wants.")[
  #tbl(
    columns: (auto, 1fr),
    [*Callout*], [*What it means*],
    [Note], [An aside. Skip it and lose nothing but context.],
    [Tip], [A shortcut, an idiom, or a better default than the obvious one.],
    [Warning], [Something that will bite: wrong results, a build that will not finish.],
    [Caution], [Something that costs data or money if you get it wrong.],
    [Native memory], [A native lifetime hazard --- ownership, release, use-after-release.],
  )
]

The prose spells British, as the library's own documentation does: *colour*, *grey*, *behaviour*.
The API does not, because OpenCV does not --- the text speaks of a colour conversion and then calls
`ColorConversion.BgrToGray`, of a greyscale image whose method is `gray`. An identifier is always
spelled the way the compiler spells it.

#sect("Using the code examples")

The listings are Apache-2.0 licensed, like the library. Use them in your own programs freely;
attribution is welcome and not required. They are trimmed for the page: unless a chapter says
otherwise, assume that `import scalacv.*` heads the file and one `OpenCv.load()` has already run
--- both shown in full in Chapter 2, implicit thereafter.

The canonical versions live in the repository, and they are canonical because they are compiled.
Every snippet under `docs/mdoc/` tagged `mdoc` is compiled against the real library --- natives on
the classpath, not a stub --- and most are executed as well, so a drifted snippet fails the build
instead of misleading a reader; the programs in `examples/` are compiled by Mill on every change,
and `./mill examples.runMain scalacv.smoke` proves the natives loaded on your machine. Where the
book and the repository disagree, the repository is right.

#sect("What you need installed")

A JDK, 17 or newer. A build tool: this project uses Mill 1.1.7, which fetches its own launcher and
provisions its own JDK, but the library is an ordinary published artifact, so sbt works as well ---
as does any Coursier-backed tool, `scala-cli` included, that can express a dependency classifier.
Nothing else: no system OpenCV, no `apt-get` step, no display server, no GUI toolkit.
`OpenCv.load()` is headless, and so is every test in the library's own suite.

You add two kinds of dependency line: the library, and the natives for your platform.

#example("The minimum, in Mill: the core library plus natives for one platform.")[
```scala
def mvnDeps = Seq(
  mvn"com.worxbend::scalacv:0.4.1",
  mvn"org.bytedeco:opencv:4.13.0-1.5.13;classifier=linux-x86_64",
  mvn"org.bytedeco:openblas:0.3.31-1.5.13;classifier=linux-x86_64"
)
```
]

Both native lines are needed: `libopencv_core` links against `libopenblas`, so dropping the second
leaves you a half-loaded library rather than an honest failure. Chapter 2 has the classifier for
every platform, the sbt spelling, the optional `scalacv-vision`, `scalacv-graphs` and `scalacv-zio`
modules, and what lands on disk the first time you run: roughly 196 MB on Linux, extracted once
into `~/.javacpp` and reused forever after, relocatable with `-Dorg.bytedeco.javacpp.cachedir`.

#sidebar("Why the natives are a separate line")[
  It looks like an oversight, and it is not. The `scalacv` artifact depends on the OpenCV *Java API*
  jar, which contains no native code; the machine code ships in per-platform classifier jars, and no
  build tool can put a classifier into a published POM. A library that picked one for you would be
  picking wrong for everyone on a different machine.

  There is an escape hatch: `org.bytedeco:opencv-platform:4.13.0-1.5.13` bundles every platform and
  works anywhere, for about 408 MB instead of the 36--80 MB one platform costs --- a fine trade on a
  laptop, a poor one in a container image.

  Get it wrong and `OpenCv.load()` does not hand you a bare `UnsatisfiedLinkError`: it throws
  `CvError.NativesMissing`, whose message is both coordinates, copy-pasteable, with the classifier
  for the platform you are actually on already filled in.
]

#sect("Acknowledgements")

This book, and the library it documents, rest on other people's work. The OpenCV project first: a
quarter-century of algorithms, maintained by Intel, Willow Garage and a very large community,
Apache-2.0 licensed since 4.5.0. Everything in Parts II through V is OpenCV doing the arithmetic;
`scalacv` only decides who owns the result. Bytedeco's JavaCPP Presets, by
Samuel Audet and contributors, are how those natives reach a JVM at all: the classifier jars, the
extraction into `~/.javacpp`, and the loading machinery behind a headless `OpenCv.load()`. Under
that sits OpenBLAS, doing the linear algebra.

The name `scalacv` and the original spark come from `mcallisto/scalacv` by Mario Càllisto, a Scala
2.11 wrapper over the OpenCV 3.0 Java API begun in 2015; two example ideas trace further back, to
`rladstaetter/isight-java` and `chimpler/blog-scala-javacv`. None of those repositories carried a
license, so this library is a clean-room rewrite sharing no code with any of them: the effect model,
the module layout and the underlying Java API all changed, and every call site was rewritten. The
credit is for the inspiration, and it is recorded here and in the repository's `NOTICE`.

Chapter 1 picks up where this preface stops: with the forty bytes standing in front of the six
gigabytes, and what a typed, scoped API has to do about them.
