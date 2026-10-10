#import "../lib/book.typ": *

#chapter("The Error Model", subtitle: [Which failures are values, which are bugs, and why the library refuses to blur the two.])

Ask OpenCV's Java API to read a file that is not there and it does not complain. `Imgcodecs.imread`
hands back a `Mat` --- an ordinary object, non-null, ready to be passed around --- whose `empty()` is
`true`. Your program carries on. Ten lines later a `cvtColor` is asked to convert that emptiness from
BGR to grey, and #emph[that] is where the failure surfaces: an `org.opencv.core.CvException` whose
message names a C++ assertion in a file you have never opened, several call frames from the mistake
you actually made.

That is one of three shapes failure takes in the bindings. `imwrite` returns a bare `false`, with no
indication whether the directory was missing, the extension unsupported, or the disk full.
`imencode` returns `false` for one class of problem and throws for another. A `CascadeClassifier`
constructed on a wrong path throws nothing at all: you get a classifier that loads no cascade and
finds nothing, forever, quietly. Three incompatible conventions, and no rule connecting any of them
to the kind of mistake being reported.

There are two tempting ways to flatten that, and both are wrong. Wrap every call in
`scala.util.Try` and every failure becomes a value --- including the ones that are bugs, which then
travel through your `recover` blocks and get logged at `WARN` instead of stopping the program at the
line that was wrong. Go the other way and make the whole API total, returning `Either` everywhere,
and the type is a lie: `CvException` escapes from ordinary in-memory operations, on Mats the library
never touched, and no wrapper can prevent it.

So scalacv asks one question of every failure, and answers it once, in the type. #emph[Could a
correct program, given different data, hit this?] A file the user chose is not there; a download
404s; a photograph is too blurred for the corner finder. Those are outcomes, and they come back as
`Either[CvError, A]`. A negative blur radius, an image reused after a transform consumed it --- no
change of input makes those correct. They are bugs, and they throw.

The running example for this chapter is small enough to hold in your head and touches every case: a
service that takes an uploaded photograph, finds the faces in it, draws a box round each one, and
writes a JPEG. It has a data-dependent failure at each end, a resource to load, a native call in the
middle, and at least two ways to hold it wrong.

#sect("The dividing line")

Eight kinds of thing can go wrong, and the library places each one in a regime deliberately.

#figure-table("Where each failure is delivered, and what you are expected to do about it.")[
#tbl(
  columns: (1.5fr, 1.5fr, 1.5fr),
  [*Failure*], [*Delivered as*], [*What you do*],
  [Missing or undecodable image], [`Left(DecodeFailed)`], [branch on it],
  [Missing model, cascade, video source], [`Left(LoadFailed)`], [branch on it],
  [Unwritable destination, no encoder], [`Left(EncodeFailed)`], [branch on it],
  [Ill-posed calibration], [`Left(CalibrationFailed)`], [capture more views],
  [Unforeseen native rejection], [`NativeCall` --- `Left` from `Cv.attempt`, otherwise thrown], [usually a bug; sometimes handled],
  [Natives absent, or the release bridge shut], [thrown `NativesMissing`], [fix the build or the JVM flags],
  [Bad argument], [thrown `IllegalArgumentException`], [fix the call],
  [Reusing a spent handle], [thrown `IllegalStateException`], [fix the code],
)
]

The first four rows are the `Either`. They are the failures a correct program meets in the course of
doing its job, and they are the ones the type system should be talking about. The last three throw,
and the fifth is the interesting hybrid: a native rejection is usually a bug, but not always, so it
is available both ways.

#sidebar("Why the ADT is an exception hierarchy")[
`CvError` is declared `sealed abstract class CvError(message: String, cause: Throwable | Null)
extends RuntimeException(message, cause)`. An error ADT that extends `RuntimeException` looks like a
compromise, and it is one --- a considered one.

The core cannot be made total. `CvException` comes back across JNI from operations this library did
not initiate and cannot foresee, so somewhere a `Throwable` has to be caught and carried. Because
`CvError` #emph[is] a `Throwable`, it carries: it can hold the original as its `cause` without a
lossy conversion, it can be rethrown by `Cv.orThrow`, and it can cross a `try`/`catch` written by
somebody who has never heard of this library.

What you give up is nothing much: the hierarchy is `sealed`, so `match` is still exhaustive. What
you gain is a type that behaves correctly at the one boundary where the language cannot help you.
]

#sect("Six cases")

`CvError` has exactly six shapes, and each one is named for the moment you meet it.

#subsect("NativesMissing")

The JVM cannot reach native OpenCV. The case carries `details: String` and
`cause: Throwable | Null`, defaulted to `null` --- the union type is there because most of these are
constructed from a situation rather than from an exception, and the ones that #emph[are] wrapping
something (an `InaccessibleObjectException`, a `NoSuchMethodException`) keep it, since its message
names the module or package that could not be opened.

`details` is not a description of the problem. It is the remedy, written to be copied out of your
terminal and pasted into a build file or a launch script. That is why this case is thrown rather
than returned: there is no sensible way to carry on, and nothing to branch on.

Despite the name, only one of its two causes happens at start-up. `OpenCv.load()` throws it when the
per-platform classifier jars are absent. `Releasable`'s reflection bridge --- the machinery that
frees the 185 `org.opencv.*` types with no public `release()` --- throws it when a native handle is
freed, which is typically in the middle of a pipeline that has been working for twenty minutes.
Both messages are shown later in this chapter.

#subsect("DecodeFailed")

`final case class DecodeFailed(path: String, details: String)`. Image bytes did not become an image.
This is the case that exists because `imdecode` does not throw on rubbish --- it returns an empty
`Mat`, and the check has to be explicit. scalacv makes it for you and turns the result into a
`Left`, so the failure surfaces at the read rather than at whichever later operation happened to be
handed the emptiness.

Reading from a file adds the filesystem cases. `Images.read` does the byte-fetching with the JVM's
own file I/O and leaves only the decoding to OpenCV, so `details` says which of them happened
instead of collapsing them into one message: `there is no file at this path`, `this path is a
directory, not a file`, `the file is empty`, `this is not a usable filesystem path` followed by the
reason `Path.of` gave, or `the bytes are not an image in a format OpenCV can decode`.

#memory[
The empty `Mat` that a failed decode produces still owns a native handle, even with no pixel buffer
behind it. `Images` releases it on the failure path before constructing the `Left`. It matters more
than it sounds: one leaked handle per failed read is nothing, and one leaked handle per failed read
inside a retry loop pointed at a URL that has been 404ing for a week is a leak like any other.
]

#subsect("LoadFailed")

`final case class LoadFailed(resource: String, details: String)`. A #emph[non-image] resource could
not be resolved, loaded, or verified: a Haar cascade, an ONNX network, a downloaded model, a video
source, a `Recorder` whose codec the build does not have. It is kept apart from `DecodeFailed`
deliberately --- an HTTP 404 on a model download or a checksum mismatch is not an image-decode
failure and should not read like one.

The running example meets this first, because the cascade has to be loaded before there is anything
to detect with.

#example("The first fallible step: the classifier.")[
```scala
import scalacv.*
import org.opencv.objdetect.CascadeClassifier

val cascade: Either[CvError, Managed[CascadeClassifier]] =
  Cascades.load(CascadeName.FrontalFaceAlt)
```
]

`Cascades.load` extracts the XML from the bytedeco jar and loads it, and it reports the silent case
as well as the loud one: OpenCV builds an empty classifier for a path it cannot read, so scalacv
checks `empty()` and returns `Left(LoadFailed)` with a message saying so, rather than handing back a
detector that finds nothing.

#subsect("EncodeFailed")

`final case class EncodeFailed(path: String, details: String)`. The other end of the pipeline. Two
different failures share the one case so that a single `case EncodeFailed(...)` catches both: an
extension with no registered encoder --- checked with `haveImageWriter` up front, rather than
letting `imencode` throw --- and a destination the JVM cannot write to, which `details` separates
into `the parent directory does not exist` and `the destination is not writable`.

`Images.write` encodes fully into a byte array and only then writes, which costs you the encoded
size in heap and buys something worth having: a failed encode can no longer leave a truncated file
where a valid one used to be.

#subsect("CalibrationFailed")

`final case class CalibrationFailed(details: String)`. `Calibration.fromChessboard` could not
produce intrinsics: fewer than `minViews` of the images showed the whole board, or `calibrateCamera`
did not converge. This is the purest data-dependent failure in the library --- it turns entirely on
what the capture actually saw --- so it is returned, and the `details` tell you what to do about it,
naming both numbers: how many views showed the grid, out of how many you supplied. The fix is
another twenty minutes with the board, not an edit to the call.

#subsect("NativeCall")

`final case class NativeCall(operation: String, cause: Throwable)`. The residual case: a
`CvException` from an ordinary in-memory operation. A channel-count violation, a size mismatch, a
depth the operation does not accept. Note that `cause` here is `Throwable`, not `Throwable | Null`
--- this case never exists without something to wrap.

`operation` is the string the call site supplied, and it is the whole value the wrapper adds.
OpenCV's own message is preserved verbatim and deliberately not parsed: that text is not a stable
interface, and turning it into error codes would be inventing structure upstream never promised.

#sect("Cv.attempt, and the guard that makes the policy real")

Everything the library wraps already returns an `Either`. `Cv.attempt` is for when you leave the
paved road --- a raw `org.opencv.*` call scalacv does not cover --- and want it to obey the same
policy.

#example("Lifting a raw OpenCV call into the error model.")[
```scala
import org.opencv.core.{Core, Mat}

def meanBrightness(m: Mat): Either[CvError, Double] =
  Cv.attempt("Core.mean")(Core.mean(m).`val`(0))
```
]

The implementation is five lines and three `catch` clauses, and it is worth knowing them exactly.
A `CvException` becomes `Left(NativeCall(operation, e))`. A `CvError` the block produced itself is
passed through unchanged, so lifting an already-lifted call does not double-wrap it. And a
#emph[bare] `java.lang.Exception` --- matched by exact class, `e.getClass == classOf[Exception]` ---
also becomes a `NativeCall`, because OpenCV's `throwJavaException` degrades to a plain `Exception`
for anything that is not a `cv::Exception`: `std::bad_alloc`, `std::out_of_range`, and whatever it
does not recognise.

That exact-class test is the load-bearing part of the whole error model. Because it demands the
class be `Exception` itself, every subclass keeps travelling.

#figure-table("What crosses `Cv.attempt`, and what does not.")[
#tbl(
  columns: (1.7fr, 1.3fr),
  [*Thrown inside the block*], [*Comes out as*],
  [`org.opencv.core.CvException`], [`Left(NativeCall)`],
  [A `CvError` the block produced], [`Left`, unchanged],
  [`java.lang.Exception`, exact class], [`Left(NativeCall)`],
  [`IllegalArgumentException`], [propagates],
  [`IllegalStateException`], [propagates],
  [`InvalidPathException`], [propagates],
  [Any `Error` --- `OutOfMemoryError`, `UnsatisfiedLinkError`], [propagates],
)
]

Read the bottom half of that table as a design statement. The split between values and bugs is not a
convention the library asks you to remember; it is enforced by a class comparison, and a programmer
error physically cannot become a `Left`. `Images` relies on this in the other direction: `Path.of`
throws `InvalidPathException`, a plain `RuntimeException` that `attempt` will not catch, so `read`
and `write` guard the path resolution explicitly rather than let a throw escape a function whose
contract is to return an `Either`.

When a native failure genuinely is a bug at your call site, `Cv.orThrow(operation)(block)` is
`attempt` that rethrows instead of returning --- literally `attempt(operation)(a).fold(throw _,
identity)`.

#sect("The failures that throw")

Two exception types are part of the policy rather than gaps in it.

#subsect("IllegalArgumentException: what the library can see coming")

Where a bad argument is visible before anything crosses JNI, a `require` rejects it, with a message
that quotes the value you passed.

```scala
Image.blank(8, 8).blur(-1)
// java.lang.IllegalArgumentException: requirement failed:
//   blur radius cannot be negative, got -1
```

Do not try to catch that as a value. The following looks like defensive programming and is not:

#example("Wrong. The require throws straight through attempt.")[
```scala
// `radius` came from a query parameter, so this looks careful. It is not:
// IllegalArgumentException is not an Exception by exact class, so it
// propagates out of `attempt` and this Either is never a Left.
def thumbnail(img: Image, radius: Int): Either[CvError, Array[Byte]] =
  Cv.attempt("blur")(img.blur(radius).bytes(".jpg")).flatten
```
]

It is worse than a `Right` that should have been a `Left`. The `require` fires inside `blur`, before
the `bytes` terminal that would have released the image, so every request carrying a negative radius
throws #emph[and] strands a Mat. The mistake underneath is a category error: an untrusted number has
been carried all the way to a precondition instead of being checked where it entered the system.
Validate at the boundary, and by the time the value reaches `blur` it is a programmer error again ---
which is exactly what the `require` is for.

#example("Right. Untrusted input is validated where it arrives.")[
```scala
def thumbnail(img: Image, radius: Int): Either[String, Array[Byte]] =
  if radius < 0 || radius > 20 then
    img.close()
    Left(s"blur radius must be between 0 and 20, got $radius")
  else img.blur(radius).bytes(".jpg").left.map(_.getMessage)
```
]

#subsect("IllegalStateException: the handle you already spent")

`Managed` releases exactly once, and reading from one that has been released or transferred throws
`IllegalStateException` on the Scala side --- before anything reaches JNI, where the same mistake is
a SIGSEGV with no stack trace and no test report. `Image` inherits that guard, and because every
transform #emph[moves] the image, the commonest way to trip it is to use a value twice.

#example("Wrong. Every transform spends its receiver.")[
```scala
val img = Image.blank(8, 8)
val a = img.gray     // consumes img
val b = img.blur(2)  // IllegalStateException: this Mat has already been
                     // released or consumed
```
]

#example("Right. Copy before the first use when you need two.")[
```scala
val original = Image.blank(8, 8)
val edges = original.copy.gray.canny(80, 160) // works on a copy
val small = original.resize(4, 4)             // original still live here
```
]

This one is deliberately not an `Either`, and the reason is worth stating plainly: threading it
through a `flatMap` would let somebody #emph[handle] it. There is no handling to be done. The value
is gone, the pipeline downstream of the mistake is meaningless, and the only correct response is to
change the source. An `Either` would offer a recovery that cannot exist.

#memory[
The exception fires at the #emph[reuse], which is rarely the line you need to see. Start the JVM
with `-Dscalacv.trackOwnership=true` and the `IllegalStateException` carries, as its cause, the
stack of the transform or terminal that actually spent the handle. It is off by default because it
allocates a `Throwable` every time a handle is spent; the code that reads it lives only on the
already-failing path, so a program that never misuses a handle pays nothing for the option.
]

#sect("Composing")

Because every fallible step is an `Either[CvError, A]`, they thread with `flatMap`, and the first
failure short-circuits the rest --- the encode never runs when the decode already failed. The
running example is small enough to write as one comprehension, and its type then says precisely what
can go wrong: nothing but a `CvError`.

#example("Read, detect, annotate, write --- and leak the cascade.")[
```scala
def annotate(path: String, out: String): Either[CvError, Int] =
  for
    cascade <- Cascades.load(CascadeName.FrontalFaceAlt)  // LoadFailed
    result  <- Image.reading(path) { img =>               // DecodeFailed
                 val boxes = cascade.use(c => img.detectHaar(c, minNeighbors = 5))
                 img.drawRects(boxes).write(out).map(_ => boxes.size)
               }
    count   <- result                                     // EncodeFailed
  yield count
```
]

Two things in that listing are doing real work. A third is quietly wrong.

`Image.reading` is the scoped entry point: it closes the image when the block returns, on success,
on failure, and on an exception, and closing is idempotent, so it is still correct when the block
already consumed the image with a terminal. It also runs the whole body inside `Cv.attempt`, which
is why a `NativeCall` thrown by a transform halfway down the chain comes back as a `Left` instead of
escaping past a signature that promised an `Either`.

The `count <- result` line is the price of that scoping. `reading` returns `Either[CvError, A]`, and
here `A` is itself `Either[CvError, Int]`, because the body ends in `write`. Binding it on its own
line flattens the nesting and keeps the comprehension readable; `.flatten` on the outer `Either`
does the same job in one call when you would rather not name it.

What is wrong is the first generator. `Cascades.load` hands back a `Managed[CascadeClassifier]` that
is caller-owned, and the only thing that releases it is the `cascade.use` in the second generator's
body. When `Image.reading` returns a `Left` --- the file is not there, the bytes are not an image ---
that body never runs, the comprehension short-circuits, and the classifier stays allocated. The
signature is honest, the types check, and the process loses a native handle on every bad upload.

#memory[
This is the sharp edge of composing `Either` over native resources: a generator that acquires and a
later generator that releases are joined by nothing the compiler enforces, and every `Left` between
them skips the release. The same shape with an image rather than a cascade:

```scala
for
  img   <- Image.decode(upload)   // a live Mat now exists
  boxes <- classify(img)          // Left here, and img is never closed
  _     <- img.drawRects(boxes).write(out)
yield boxes.size
```

Nothing warns you; the heap stays flat and RSS climbs. Either put the acquisition inside a scope
that closes it --- `Image.reading` for a path, `Managed.use` for a handle, `Using.resource` for a
decoded upload, since `Image` is `AutoCloseable` --- or arrange the comprehension so that nothing
between the acquisition and its terminal can produce a `Left`.
]

The fix is not a `try`/`finally` bolted on, and not a rule to remember. It is to stop letting a
scope be implied by the order of the generators and make it a block that encloses everything that
can short-circuit. `Managed.use` releases in a `finally`, so a `Left` inside it, an exception, and a
clean return all reach the same release.

#example("The same pipeline, with the classifier's lifetime made explicit.")[
```scala
def annotate(path: String, out: String): Either[CvError, Int] =
  Cascades.load(CascadeName.FrontalFaceAlt).flatMap { cascade =>
    cascade.use { c =>
      Image
        .reading(path) { img =>
          val boxes = img.detectHaar(c, minNeighbors = 5)
          img.drawRects(boxes).write(out).map(_ => boxes.size)
        }
        .flatten
    }
  }
```
]

That is the idiom `Cascades` documents --- `Cascades.load(name).map(_.use(c => image.detectHaar(c)))`
--- with a `flatMap` in place of the `map` because the body is itself fallible. `detectHaar` borrows
the classifier and returns a `Seq[Rect]` of plain data, so nothing native escapes the block.

Fallbacks compose the same way. `orElse` gives you a second attempt at the whole pipeline, and the
`Left` from the first is discarded only where you say so.

#example("A fallback that is explicit about what it swallows.")[
```scala
def avatar(path: String, out: String): Either[CvError, Int] =
  annotate(path, out).orElse {
    Image.reading("assets/placeholder.png") { img =>
      img.resize(256, 256).write(out).map(_ => 0)
    }.flatten
  }
```
]

#sect("Error messages as a feature")

Two of this library's messages are not diagnostics so much as instructions, and both are worth
seeing before you meet them at three in the morning.

The first is the one every new user hits. scalacv depends on the classifier-less OpenCV Java API,
because this project keeps POMs platform-neutral and its pinned Mill publisher cannot encode dependency classifiers --- so the natives are
a line you add yourself, and forgetting it is the expected state, not an exotic misconfiguration.
`OpenCv.load()` therefore does not fail with a link error. It detects the platform you are actually
on and prints the lines for it, with the versions read from `Build.openCvArtifactVersion` and
`Build.openBlasArtifactVersion` --- regenerated on every compile from `build.mill`'s `Deps` block, so
the numbers in the message cannot drift away from the ones the library was built against.

#example("What a missing native payload tells you, on Linux x86-64.")[
```text
OpenCV natives are not on the classpath (...).

scalacv depends on the classifier-less OpenCV Java API only, because a build tool
cannot express a per-platform classifier in a published POM. Add the natives for
your platform:

  "org.bytedeco" % "opencv"   % "4.13.0-1.5.13" classifier "linux-x86_64"
  "org.bytedeco" % "openblas" % "0.3.31-1.5.13" classifier "linux-x86_64"

Both lines are needed: libopencv_core links libopenblas. If you would rather not
pick a platform, "org.bytedeco" % "opencv-platform" % "4.13.0-1.5.13" bundles
every one, at a cost of about 408 MB.
```
]

There is a second text behind the same case, for the failure that wears its clothes without being
it: a JVM maps a given native library file into exactly one classloader, so a second classloader
loading scalacv gets `already loaded in another classloader` even though the jars are demonstrably
present. Telling that user to add dependencies they already have sends them down a dead end, so
`OpenCv` detects the phrase and prints different advice --- load scalacv from a classloader both
sides share.

The second message comes from the release path, and it is the more surprising one.

#example("The release bridge, refusing to guess.")[
```text
cannot open org.opencv.objdetect.CascadeClassifier.delete(long)
(InaccessibleObjectException).

  --add-opens <module>/org.opencv.objdetect=ALL-UNNAMED

scalacv fails here rather than falling back to the garbage collector, because
that fallback does not reclaim native memory in any useful timeframe.
```
]

The flag is computed from the class's own module and package rather than guessed, which is why a
class in the unnamed module --- OpenCV on the classpath, the normal case --- produces no flag at
all. `addOpensRemedy` says so in words instead, and asks for a bug report, because a reflection
failure there is not a module-access problem and a `--add-opens` line naming a `null` module would
be worse than useless.

#memory[
Both `NativeDelete` and `NativeFinalizer` throw rather than degrade, and the reason differs in each
case. If the private `delete(long)` cannot be opened, the fallback would be to leave the object to
the collector --- an unbounded native leak that looks exactly like success. If the `nativeObj` field
cannot be zeroed, scalacv refuses to free the object #emph[at all]: the binding's `finalize` calls
`delete(this.nativeObj)` unconditionally, so freeing without first disarming it means the same
pointer is deleted twice, and a corrupted heap surfaces somewhere else entirely, much later. A leak
is recoverable. A double free is not. So the library takes the leak, loudly, and hands you the flag
that fixes it.
]

#sect("Three things not to do")

#minor("Do not catch Throwable around a native call.")

It is the reflex, and here it is precisely wrong. `Throwable` swallows the programmer errors this
whole model exists to keep visible --- the `IllegalArgumentException` from a `require`, the
`IllegalStateException` from a spent handle --- and turns a bug you would have fixed in five minutes
into a warning line and a wrong picture. If you are shedding load under memory pressure, catch
`OutOfMemoryError` specifically, not its supertype.

#minor("Do not swallow a NativeCall.")

`case Left(_: CvError.NativeCall) => None` compiles, runs, and destroys the only information anybody
was ever going to get. OpenCV's message is not parsed by the library precisely because it is not a
stable interface --- which means it is #emph[all] there is. The `operation` string and that message
together are the whole account of what happened inside the native call. Log both before you decide
the frame is not worth having.

#minor("Do not reach for Try where the library gives you Either.")

Wrapping `Images.read` in a `Try` puts every failure back into one undifferentiated bucket:
`DecodeFailed` and a spent-handle bug arrive as the same `Failure`, and the exhaustive `match` over
the sealed hierarchy --- the thing that tells you at compile time when you have forgotten a case ---
is gone. The library has already sorted the failures for you. Taking the sorted result and
re-mixing it is work that buys nothing.

#sect("Next")

The error model is easiest to see at the one boundary where all three of OpenCV's failure
conventions meet at once: turning files into pixels and back. Chapter 7, #emph[Reading, Writing, and
Interop], takes `Images.read`, `decode`, `encode` and `write` apart in detail --- what each one
actually calls, why `imread` and `imwrite` are not used at all, and what that choice costs in heap.
