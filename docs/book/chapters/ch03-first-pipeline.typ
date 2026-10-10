#import "../lib/book.typ": *

#chapter("Your First Pipeline", subtitle: [Read, transform, detect, annotate, write --- as one chain that frees itself.])

Almost every computer-vision program has the same five-part skeleton. Get some pixels. Simplify them
until the thing you care about is the only thing left. Ask a question of what remains. Draw the
answer back onto the picture, so that a person can check it. Hand the result on --- to a file, to an
HTTP response, to the next frame. The interesting part is usually one line somewhere in the middle.
The other four parts are plumbing, and the plumbing is where programs written directly against
OpenCV's Java API go wrong.

They go wrong for one specific reason. A `Mat` --- OpenCV's image buffer --- is about forty bytes on
the JVM heap standing in front of megabytes of native memory the garbage collector cannot see and
will not free. Heap pressure is the only thing that triggers a collection, and heap pressure is
uncorrelated with how much native memory you are holding. Measured on this project's own test
machine: two thousand `Mat(1000, 1000, CV_8UC3)` allocations with the references dropped and no
explicit `System.gc()` finish at 5,865 MB resident. The same two thousand with `release()` called
finish at 144 MB. Forty-one times over, and nothing in between reports a fault --- the process
grows quietly until the kernel takes an interest.

The Java shape of a pipeline makes forgetting the path of least resistance. Each operation writes
into a destination `Mat` you allocated beforehand, so a five-step pipeline names five intermediates, and
every one of them needs releasing on the success path and on each of the paths where something
throws. scalacv's high-level answer is to make the intermediates unnameable. Every operation on an
`Image` returns a new `Image` and *spends* the one it was called on, so the chain holds exactly one
live buffer however long it grows, and the last step in the chain releases that.

This chapter builds one program from end to end with that chain. The subject is a photograph of a
noticeboard: first an edge map of it, then a version that counts the QR codes stuck to it, boxes the
faces in the photographs pinned beside them, and writes an annotated copy. Along the way the program
picks up the failure policy the library actually has, learns to run with no file on disk at
all, and makes --- deliberately --- the mistake every newcomer makes with move semantics.

Two lines come first, and they come before anything else in the process:

```scala
import scalacv.*

OpenCv.load()
```

The import brings in the whole surface: `Image`, `Scalar`, `Rect`, the enums, and every extension
method from whichever modules are on the classpath. You write it once per file, not once per
feature. `OpenCv.load()` links the native libraries; it is idempotent and thread-safe, so calling it
again from anywhere costs nothing, but calling anything else before it costs you a link error.

#sect("A synchronous scoped entry point")

There are two ways to get an `Image` from a file, and they are not equally forgiving. Start with
the forgiving one.

#example("The whole pipeline, in one expression.")[
```scala
Image.reading("noticeboard.jpg") { img =>
  img.gray.blur(2).canny(80, 160).write("edges.png")
}
```
]

`Image.reading` opens the path, hands the resulting `Image` to your block, and closes it when the
block returns --- on success, on a `Left`, and on an exception thrown from anywhere inside. It is the
preferred entry point for synchronous work. Do not let raw handles or lazy effects escape its
callback; manually owned resources still need explicit cleanup.

Two details support this cleanup policy. Release is idempotent, so `reading`
closing an image the block already consumed --- and the chain above does consume it, at `write` --- is
a no-op rather than a double free. And the whole body runs inside `Cv.attempt`, so a
`CvError.NativeCall` thrown by `gray` or `canny` partway down the chain comes back as a `Left`
instead of escaping past a signature that promised an `Either`.

#memory[
  `reading` closes the image it opened, not every image you derive from it. Its guarantee covers the
  source and the chain the source flows into; an `Image` you create separately inside the block ---
  from `copy`, say --- is yours to close. And nothing stops you returning the `Image` out of the
  block, which is precisely the thing not to do: it is closed the moment the block returns, so what
  escapes is a spent handle that throws on first use.
]

The type of that expression is worth reading carefully, because it surprises people once.
`reading` returns `Either[CvError, A]` where `A` is whatever the block returned --- and the block
returned `write`'s own `Either[CvError, Unit]`. So the full type is
`Either[CvError, Either[CvError, Unit]]`: one layer for the read, one for the write. Flatten it with
`flatMap(identity)` when you care about the outcome:

```scala
val done: Either[CvError, Unit] =
  Image.reading("noticeboard.jpg") { img =>
    img.gray.blur(2).canny(80, 160).write("edges.png")
  }.flatMap(identity)
```

#sect("What each operation does to the pixels")

Read the chain left to right and it is four verbs, each one narrowing what the image contains.

#minor("gray")

`gray` converts a three-channel BGR image to one channel, by the standard luminance weighting ---
green counts for most, blue for least, because that is roughly how a human eye responds. It is
`convert(ColorConversion.BgrToGray)` under a shorter name, and it is the first move in most
pipelines for two reasons: colour is not what edge detection is looking at, and one channel is a
third of the work.

Everything downstream assumes it. `canny` produces a `CV_8UC1` result whatever you feed it, and
`equalizeHist` and `adaptiveThreshold` accept nothing else --- both are documented as `CV_8UC1`
only, and a three-channel argument throws a `CvError.NativeCall` naming the operation that rejected
it --- a `Left` inside `reading`, which folds those throws for you --- rather than being silently
reinterpreted as one channel the library picked for you.

#minor("blur")

`blur(radius)` is a Gaussian blur expressed as a radius rather than a kernel: `radius` 2 is a 5×5
kernel, `radius` 3 a 7×7, and `radius` 0 is the identity. A Gaussian replaces each pixel with a
weighted average of its neighbourhood, the weights falling off with distance, so the fine, high-
frequency variation --- sensor noise, JPEG ringing, paper grain --- averages out while real structure
survives.

That matters more than it looks, because the next step measures gradients, and a gradient is a
difference between neighbouring pixels. Skip the blur and every speck of noise is a small, sharp
gradient of its own; Canny dutifully reports each one and the output is a snowstorm. A radius of 1
or 2 is the usual dose. If you want the kernel and sigmas spelled out instead, `gaussianBlur(kernel,
sigmaX, sigmaY)` takes them directly; if the noise is salt-and-pepper rather than grain,
`medianBlur` is the sharper tool --- it takes a radius on the same scale, where 1 is a 3×3
neighbourhood, and it replaces each pixel with the median of that neighbourhood rather than a
weighted mean, so a lone bright speck is discarded outright instead of being smeared over its
neighbours.

#memory[
  Three operations, one buffer. `gray` allocates a single-channel `Mat` and releases the colour one
  it read from; `blur` allocates its output and releases the grey input; `canny` does the same
  again; `write` encodes and releases the last. At no point in that chain are two images live, and
  there is no name in scope you could have used to keep one alive by accident.
]

#minor("canny")

`canny(threshold1, threshold2)` is the edge detector, and the two thresholds are the part worth
understanding, because they are not a range and they are not interchangeable.

Canny computes the gradient magnitude at every pixel, thins the result so that only the ridge line
of each edge survives, and then applies *hysteresis*: any pixel above `threshold2` is accepted
outright as a strong edge; any pixel below `threshold1` is discarded; a pixel between the two is
accepted only if it is connected to a chain that reaches a strong pixel. That middle band is the
whole design. A single threshold either breaks long faint edges into dashes or floods the image with
noise; a strong-and-weak pair lets a faint stretch of a real contour survive on the strength of the
part of the contour that is unambiguous.

So `threshold1` is the weak, linking threshold and `threshold2` the strong one, and a ratio of about
1:2 or 1:3 between them is the conventional starting point --- 80 and 160 in the listing above, 50
and 150 in a great deal of published example code. Both parameters are `Double` and silently
swappable, which is why the scaladoc tells you to name them at the call site when the values are not
obviously ordered: `canny(threshold1 = 80, threshold2 = 160)`.

The two remaining parameters are rarely touched. `apertureSize` (default `3`) is the size of the
Sobel kernel used for the gradient, and `l2Gradient` (default `false`) switches the magnitude from
the cheap `|Gx| + |Gy|` approximation to a true Euclidean norm.

#figure-table("The chain, operation by operation.")[
#tbl(
  columns: (auto, auto, 1fr),
  [Step], [Channels], [What it does to the pixels],
  [`gray`], [3 → 1], [luminance-weighted greyscale; colour is discarded, not averaged],
  [`blur(2)`], [1 → 1], [5×5 Gaussian; suppresses noise so gradients mean something],
  [`canny(80, 160)`], [1 → 1], [gradient, thinning, then hysteresis between the two thresholds],
  [`write("edges.png")`], [--- ], [encodes by file extension, then releases],
)
]

#sect("The boundary where failure becomes a value")

Four methods on `Image` turn failure into a value, and they are exactly the four that touch the
outside world: `Image.read`, `Image.decode`, `write` and `bytes`, each returning
`Either[CvError, A]`. `Image.reading` is a fifth only in the sense that it is built on `read`.
Everything between them --- the transforms --- returns a bare `Image`.

That split is a policy, not an accident. A missing file, a JPEG truncated by a failed upload, a path
whose parent directory does not exist: those are conditions of the data, they will happen in
production, and a caller has something sensible to do about each. A negative blur radius or a reused
image is a bug in the program, and wrapping it in an `Either` only lets it travel further from where
it was written. The former are values; the latter throw.

`Image.read` gives you the `Either` directly, and `flatMap` threads it:

#example("The same pipeline, threading the Either by hand.")[
```scala
val edges: Either[CvError, Unit] =
  Image.read("noticeboard.jpg")
    .flatMap(_.gray.blur(2).canny(80, 160).write("edges.png"))
```
]

This form exists because `reading`'s block does not always fit: when the image is one input among
several, when you are already inside a `for` comprehension over other `Either`s, when the result of
the read is meant to be passed onwards rather than consumed on the spot. It is equally leak-free
*provided the chain reaches a terminal*, because `write`, `bytes` and `close` all release. What it
does not give you is a guarantee. If the chain is abandoned halfway --- an early `return`, a branch
that produces a `Left` before the terminal, an exception from a call you did not expect to throw ---
the `Image` is still holding its `Mat` and nothing will take it back. That is the whole difference
between the two forms, and it is why `reading` is the default.

`Image.decode` is `read` for bytes you already have: an HTTP request body, a database BLOB, a test
fixture. `bytes(format)` is the mirror image of `write` --- it encodes to an in-memory image file and
releases, so an HTTP handler never has to touch the filesystem to answer with a PNG.

All three read entry points --- `Image.read`, `Image.decode`, `Image.reading` --- carry a second,
defaulted parameter, `flags: ImreadFlags = ImreadFlags.Color`, which decides what the decoder
produces before a pixel reaches you. `ImreadFlags.Grayscale` decodes straight to one channel and makes the later `gray`
call unnecessary; `ImreadFlags.Unchanged` keeps an alpha channel a colour read would drop. The type
is a case class of `(color, scale, ignoreOrientation)` rather than an OR-able bitmask, because
OpenCV's `IMREAD_*` constants are not orthogonal bits --- each reduced-size value already bakes in
its own colour bit, and `IMREAD_UNCHANGED` is `-1`, whose bits swamp anything OR-ed with it. So
`ImreadFlags(ImreadColor.Grayscale, ImreadScale.Half)` is a single named constant, not a
combination, and the codec skips the discarded detail instead of decoding it and throwing it away.

The failures are specific enough to act on. `CvError.DecodeFailed` carries the path and a `details`
string that distinguishes "there is no file at this path", "this path is a directory, not a file",
"the file is empty" and bytes no registered decoder recognises. `CvError.EncodeFailed` does the same
for the write side, down to "no encoder is registered for this extension".

#sidebar("Why scalacv never calls imread")[
  The decisive reason is the path, not the error: `imread` narrows the filename through JNI and
  Windows then reads the result in the process's ANSI code page, so a path with a non-ASCII
  character resolves to a name that is not there. Chapter 7, #emph[Reading, Writing, and Interop],
  has the detail.

  So `Images.read` does not call `imread` at all. The JVM resolves the path, checks that it exists,
  is not a directory and is not empty, reads the bytes itself, and hands only the decoding to
  OpenCV through `imdecode`. `Images.write` is the same trade in reverse: the pixels are encoded in
  memory first and the bytes are written by the JVM, so a failed encode can no longer leave a
  half-written file on disk.

  Distinguishable errors are the side effect, and they are the reason the four `details` strings
  above can exist: `imread` signals all four with the same empty `Mat`, plus a `findDecoder`
  warning printed to stderr that no API lets you suppress. That last warning still appears for
  genuinely undecodable bytes, because it comes from `imdecode`.

  It is not free. The encoded image passes through a JVM byte array, so peak heap grows by the size
  of that array, and a file above 2 GB is out of reach because `Files.readAllBytes` cannot return
  an array that long. `path` also means a filesystem path resolved by the JVM --- not a classpath
  resource, not a URL, not a glob. The same narrowing hazard applies to every other `String`-path
  native call in the library --- `VideoCapture`, `VideoWriter`, `CascadeClassifier.load`,
  `Dnn.readNet` --- and none of those has an in-memory equivalent to reroute through, so they stay
  ASCII-path-only on Windows.
]

#sect("A pipeline with no file on disk")

You do not need a photograph to run any of this, and it is worth knowing the headless idiom early
because it is how the library tests itself --- this repository ships no image fixtures at all, and
its examples draw their own input.

`Image.blank(width, height, color, channels)` gives you a canvas. The `draw*` methods paint on it;
coordinates are pixels with the origin at the top-left, `x` growing right and `y` growing *down*.
Colours are `Scalar` values in BGR order, so `Scalar.Red` is `Scalar(0, 0, 255)` --- use the named
constants and the order stops mattering.

#example("The same four verbs, on a scene the program draws for itself.")[
```scala
val edges: Either[CvError, Array[Byte]] =
  Image
    .blank(160, 120, Scalar.White)
    .drawRect(Rect(30, 30, 90, 60), Scalar.Black)
    .gray
    .canny(50, 150)
    .bytes(".png")
```
]

That runs in a unit test on a build agent with no display server, no `apt-get` and no fixture
directory, and it exercises the identical code path as the version that reads a file. The only
substitutions are `blank` plus `drawRect` for `read`, and `bytes` for `write`.

Note that `drawRect` sits in the chain like any other step. Drawing mutates the `Mat` in place ---
the image owns it, so there is nothing to copy --- but it still spends the receiver and hands back a
fresh `Image`, so the rule stays uniform: whatever you called it on is gone afterwards.

#sect("Detect, then annotate")

Now the middle of the program, where it asks a question. Detection is a *query*: it reads the image
and leaves it alive, so you can run several detectors on one image and then draw all their answers.

#example("Count the codes, box the faces, label the result.")[
```scala
val marked: Either[CvError, Unit] =
  FaceDetect.create(FaceDetect.ModelFileName, Size(320, 320)).flatMap { detector =>
    detector.use { yunet =>
      Image.reading("noticeboard.jpg") { img =>
        val codes: Seq[QrCode] = img.qrCodes      // query: borrows
        val found: Seq[Face] = img.faces(yunet)   // query: borrows

        img.markFaces(found)                      // transform: consumes
          .drawText(s"${codes.size} codes, ${found.size} faces", Point(10, 30), Scalar.Red)
          .write("annotated.png")
      }.flatMap(identity)
    }
  }
```
]

`qrCodes` returns `Seq[QrCode]`, each one a `text` and a `corners: Seq[Point]`. The text is empty
when OpenCV located a symbol but could not decode it --- a blurred or partly covered code --- and
those entries are deliberately kept rather than filtered out, because the corners are still good
enough to draw an overlay or to re-crop and try again.

`faces(detector)` returns `Seq[Face]`, each with a `box`, a `score` in `[0, 1]`, and exactly five
`landmarks` in a fixed order: right eye, left eye, nose tip, right mouth corner, left mouth corner.
"Right" means the subject's right, so it appears on the left of the image. The `box` is not clipped
to the frame --- YuNet regresses boxes from anchors, so a face at the edge legitimately yields a
negative `x`, and `crop` rejects such a rectangle outright. `Face.clippedBox(width, height)` is
what to crop by: the intersection with the frame, as an `Option[Rect]` that is `None` when the box
falls entirely outside it.

The detector comes from `FaceDetect.create(modelPath, inputSize, scoreThreshold, nmsThreshold)`,
which returns `Either[CvError, Managed[FaceDetectorYN]]`. The last two default to `0.9f` and
`0.3f`: the first is the confidence below which a detection is not reported, the second the overlap
above which two boxes are treated as the same face and the weaker one dropped. The model is a
download rather than a shipped asset --- `FaceDetect.ModelFileName` names the file every OpenCV Zoo
mirror uses, and `FaceDetect.downloadModel(dir)` takes a `java.nio.file.Path`, fetches it, verifies
its pinned SHA-256 and answers `Either[CvError, Path]`. `faces` also has an overload taking the
`Managed[FaceDetectorYN]` directly, which is the better call when the handle is in scope: the
spent-handle guard travels with the argument instead of being thrown away by a bare `.get`.

#memory[
  Look at what crosses back from a detector: `QrCode`, `Face`, `Point`, `Rect` --- immutable Scala
  case classes, copied out of OpenCV's result matrices. Nothing you receive owns native memory, so
  there is nothing for you to free.

  That is not a stylistic preference. `QRCodeDetector` and `ArucoDetector` are two of the 185
  `org.opencv.*` types that own native memory and expose only a private `delete(long)` --- no public
  `release()` at all. `qrCodes` therefore builds its detector, uses it and frees it inside the one
  call, and hands you data instead of a handle. There is no version of that API where a caller could
  have done the freeing.
]

`markFaces(faces, color)` draws a box per face and a dot per landmark; `drawText(text, at, color,
scale)` writes a label. Both are transforms, so they consume and return, and the chain ends at
`write`. One thing about `drawText`: `at` is not the top-left corner. OpenCV anchors text on its
baseline, so a `y` of 0 puts almost the whole string above the top of the image and draws nothing
visible. `Draw.textSize` measures the string for you when placement has to be exact: it answers a
`TextMetrics` carrying the text's `size` and, separately, the `baseline` depth beneath it, and a
background box has to be `size.height + baseline` tall or every `g` and `y` is clipped. And only the
Hershey vector fonts exist --- OpenCV cannot render a system font, and anything non-ASCII comes out
as `?`.

#sect("The mistake everybody makes once")

Here is a version of the annotation step that looks obviously correct and is not.

```scala
Image.reading("noticeboard.jpg") { img =>
  val thumb = img.resize(320, 240)   // transform: consumes `img`
  val codes = img.qrCodes            // throws: `img` is spent
  thumb.drawText(s"${codes.size} codes", Point(10, 30)).write("thumb.png")
}
```

`resize` is a transform. It took the `Mat` out of `img`, resized it into a fresh one, and left `img`
holding nothing. The next line asks the spent handle for its buffer, and instead of reading freed
memory it throws:

```text
java.lang.IllegalStateException: this Mat has already been released or consumed —
using it now would crash the JVM from native code. A high-level Image is spent by any
transform (gray/blur/…) or terminal (write/bytes/close); call `.copy` before the first
use if you need it twice. Run with -Dscalacv.trackOwnership=true to record where it
was consumed.
```

That exception is the point of the design. In the Java API the same mistake reaches JNI and the
process dies with a segmentation fault and no Scala stack trace --- there is nothing to attach a
debugger to and nothing to log. Here it is an ordinary JVM exception, thrown on the Scala side
before anything crosses the boundary, and the hint at the end is real: start the JVM with
`-Dscalacv.trackOwnership=true` and the exception carries, as its cause, a stack trace of the line
that consumed the handle. The throw fires at the reuse, which is rarely the interesting line; the
cause points at the transform that spent it.

The fix is to branch explicitly with `copy`, which is a deep copy and therefore an independent
image:

```scala
Image.reading("noticeboard.jpg") { img =>
  val codes = img.qrCodes                     // query: `img` is still alive
  val label = s"${codes.size} codes"
  for
    _ <- img.copy.resize(320, 240).write("thumb.png")   // the copy is spent, not `img`
    _ <- img.drawText(label, Point(10, 30)).write("annotated.png")
  yield ()
}
```

The ordering rule that falls out of this is short: run every query first, take a `copy` for every
branch you need, and let the last branch consume the original.

#figure-table("Three kinds of method, three effects on the receiver.")[
#tbl(
  columns: (auto, 1fr, auto),
  [Kind], [Examples], [Effect on the receiver],
  [Query], [`width`, `channels`, `contours()`, `qrCodes`, `faces`], [borrows --- call as often as you like],
  [Transform], [`gray`, `blur`, `canny`, `crop`, `drawRect`, `markFaces`], [consumes --- returns a new `Image`],
  [Terminal], [`write`, `bytes`, `close`], [consumes and releases the native memory],
)
]

`managed` is the one member that fits none of the three: it spends the `Image` without freeing
anything, handing the underlying `Managed[Mat]` --- and the obligation to release it --- to the
caller. `mat` is its opposite number, borrowing the raw `org.opencv.core.Mat` for any `org.opencv.*`
call the fluent surface does not wrap while ownership stays where it was. Between them they are the
escape hatch that keeps the low-level API one method away.

#sect("Running it")

Two lines of dependency, as ever: scalacv itself, and the OpenCV natives for the platform you are
actually on. This project keeps POMs platform-neutral, so the native coordinates are yours to
pick.

#example("A Mill module for the program in this chapter.")[
```scala
package build

import mill.*, scalalib.*

object scan extends ScalaModule {
  def scalaVersion = "3.3.8"
  def mvnDeps = Seq(
    mvn"com.worxbend::scalacv:0.4.1",
    mvn"com.worxbend::scalacv-vision:0.4.1",   // faces, QR, markers
    mvn"org.bytedeco:opencv:4.13.0-1.5.13;classifier=linux-x86_64",
    mvn"org.bytedeco:openblas:0.3.31-1.5.13;classifier=linux-x86_64"
  )
}
```
]

With `@main def scan(path: String)` in package `demo`, that runs as:

```bash
./mill scan.runMain demo.scan noticeboard.jpg
```

Both bytedeco lines are needed: `libopencv_core` links `libopenblas`, so dropping the second gives
you a half-loaded library rather than a clean failure. Swap `linux-x86_64` for the classifier that
matches where the code will run --- Chapter 2 has the full list, along with
`org.bytedeco:opencv-platform`, which bundles every platform and works anywhere for about 408 MB
instead of 36--80 MB.

For a single file, scala-cli needs no build definition at all --- the directives are the build:

#example("The same program as one scala-cli script.")[
```scala
//> using scala 3.3.8
//> using dep com.worxbend::scalacv:0.4.1
//> using dep com.worxbend::scalacv-vision:0.4.1
//> using dep org.bytedeco:opencv:4.13.0-1.5.13,classifier=linux-x86_64
//> using dep org.bytedeco:openblas:0.3.31-1.5.13,classifier=linux-x86_64

import scalacv.*

@main def scan(path: String): Unit =
  OpenCv.load()
  Image.reading(path)(_.gray.blur(2).canny(80, 160).write("edges.png"))
    .flatMap(identity)
    .fold(e => println(s"failed: ${e.getMessage}"), _ => println("wrote edges.png"))
```
]

```bash
scala-cli run scan.scala -- noticeboard.jpg
```

#note[
  On JDK 24 and newer, noise from two unrelated sources precedes every run.
  `--enable-native-access=ALL-UNNAMED` silences the JEP 472 warning that `System.load` emits when a
  native library is loaded from an unnamed module. `--sun-misc-unsafe-memory-access=allow` silences
  the four lines that scala3-library 3.3.8's `LazyVals$` provokes, which has nothing to do with
  OpenCV. This repository's own build passes both; add them as `forkArgs` in Mill or
  `//> using javaOpt` in scala-cli if the warnings bother you. Neither flag exists before JDK 24,
  and passing either to a 17 or 21 JVM is not a warning but `Unrecognized option` --- the process
  does not start.
]

The first `OpenCv.load()` on a machine extracts the native libraries out of those jars into
`~/.javacpp` --- around 196 MB on Linux --- and every later run reuses that cache; a read-only home
or a thin container layer wants `-Dorg.bytedeco.javacpp.cachedir=…` instead.

#sect("Next: the rule underneath the chain")

Everything in this chapter rests on one sentence: a transform consumes the image it was called on.
It bought a leak-free pipeline with no scopes, no `try`/`finally`, and no name in scope for an
intermediate you could forget --- and it cost one `IllegalStateException` that you have now met.

Chapter 4, #emph[The Image Type], takes the type apart member by member: the full catalogue of
queries, transforms and terminals, what `mat` and `managed` are for when the fluent surface runs
out, and why `crop` returns an independent copy rather than a view onto the pixels it was cut
from. Chapter 5, #emph[Lifetimes: Managed, Releasable, and Scope], goes underneath it --- what
`Managed[A]` actually is, why `release` may be called twice and `get` may not, how `Releasable`
reaches the 185 OpenCV types with no public `release()`, and what `Managed.scope` does when one
computation needs several native objects alive at once.
