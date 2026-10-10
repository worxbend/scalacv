#import "../lib/book.typ": *

#chapter("The Image Type", subtitle: [One owned Mat, three kinds of member, and a rule that makes a pipeline free itself.])

Every OpenCV program has the same shape. Pixels come in from somewhere --- a file, a socket, a camera
--- go through four or five operations, and something goes back out: a smaller file, a byte array, a
count. Written against the Java bindings, that shape costs more than it looks like it should. Each
`Imgproc` call wants a destination `Mat` you allocated yourself and writes into it, so a five-step
pipeline names six matrices, five of which are garbage the instant the next step returns. None of
them frees itself. `Mat` is about forty bytes on the heap in front of megabytes off it, so the
collector sees nothing worth collecting and never runs; the process grows until the kernel notices.
Measured on this project's own test machine, two thousand `Mat(1000, 1000, CV_8UC3)` with their
references dropped and no `System.gc()` finish at 5,865 MB resident. The same two thousand with
`release()` called finish at 144 MB.

So the accounting has to be done by hand, and it has to survive an exception thrown from the middle
of the chain, which means a `try`/`finally` per intermediate, or one `finally` that releases six
things in reverse order and has to cope with the ones that were never successfully allocated. This is
not hard. It is merely relentless, and it is wrong the first time somebody adds a step in the middle
without touching the cleanup at the bottom.

Most wrappers over OpenCV fix the pleasant half of this. They name the integer constants, return the
destination instead of taking it as an out-parameter, and give the operations verbs rather than
`Imgproc.` prefixes. The result reads better and leaks exactly as much as it did before, now with an
extra layer between you and the `release()` you still have to remember. The unpleasant half --- who
owns each buffer, and when it dies --- is the half that keeps the process alive.

`Image` takes one. It owns exactly one native `Mat` and enforces a rule about who is allowed to hand
that `Mat` on. The rule costs something real, and the cost is stated up front rather than buried: you
cannot use one `Image` twice. What it buys is a pipeline with no scope, no `try`, and no `release`
call anywhere in it, that still holds exactly one live `Mat` no matter how many steps long it is.

#example("The pipeline the rest of the chapter takes apart.")[
```scala
import scalacv.*

OpenCv.load()

Image.reading("page.jpg") { img =>
  img.gray.blur(2).canny(80, 160).write("edges.png")
}
```
]

That line allocates four matrices --- the one `Image.reading` opened, then one each for `gray`,
`blur` and `canny` --- and holds exactly one of them at any moment. All four are freed, including on
the path where `canny` throws part-way. Understanding why that is true, and what it stops you from
writing, is the whole of this chapter.

#sect("Three kinds of member")

Everything on `Image` is one of three shapes. Which shape a member has tells you what happens to the
image you called it on, and there is nothing else to know about ownership afterwards.

A #keyterm[query] borrows. It reads the underlying `Mat` and gives back plain immutable Scala data ---
an `Int`, a `Size`, a `Seq[Contour]` of value types --- and the image is still yours when it returns.
`width`, `height`, `size`, `channels` and `isEmpty` are queries; so is `contours`, which runs
`findContours` on a borrowed `Mat` and hands back Scala values. Because what a query returns owns no
native memory, the result outlives the image it came from without any care on your part. You can
close the image and keep the contours.

A #keyterm[transform] consumes. `gray`, `blur`, `canny`, `resize`, `crop`, the whole `draw*` family,
the photo verbs like `sepia` and `stylize` --- each returns a *new* `Image` and spends the one it was
called on. The receiver's handle is dead when the call returns: not dangling, not stale, dead in a way
the library can detect. That is what makes the chain in Example 4-1 leak-free without a scope. Each
step either frees the previous `Mat` or moves it into the result, so at no point do two of them exist.

A #keyterm[terminal] consumes and releases. `write` encodes to a path and frees; `bytes` encodes into
memory and frees; `close` frees and nothing else. After any of them the handle is spent exactly as
after a transform, and the native memory is gone. There is one terminal that consumes without freeing ---
`managed`, which hands the whole `Managed[Mat]` to you instead --- and it is the only member of
`Image` that can leave live native memory behind. It gets its own section later.

#figure-table("Representative members of each kind. `copy` and `toBufferedImage` borrow, yet build something new.")[
#tbl(
  columns: (auto, 1fr, auto),
  [*Query --- borrows*], [*Transform --- consumes*], [*Terminal --- consumes*],
  [`width`, `height`], [`gray`, `convert`], [`write(path)`],
  [`size`, `channels`], [`blur`, `gaussianBlur`], [`bytes(format)`],
  [`isEmpty`], [`canny`, `threshold`], [`close()`],
  [`contours(...)`], [`resize`, `scale`, `crop`], [`managed` (no free)],
  [`mat`], [`drawRect`, `drawText`], [],
  [`toBufferedImage`], [`sepia`, `stylize`, `filter`], [],
  [`copy`], [`inRange`, `applyMask`, `blend`], [],
)
]

`copy` and `toBufferedImage` sit off the grid in the same direction: both borrow the `Mat` and build
a fresh buffer from it, so the receiver stays alive and you hold a second, independent thing.
Everything else that returns an `Image` spends the one it was given.

#memory[
An `Image` that never reaches a terminal leaks. Dropping the reference is not enough, for exactly the
reason the chapter opened with: the forty bytes the collector can see are not the megabytes the
process is holding. If the tail of your work is a query rather than a `write` or a `bytes`, put the
work inside `Image.reading`, or `close()` it yourself.
]

#sect("Move semantics, and what happens when you get them wrong")

The rule is worth stating precisely, because "consumes" is doing real work in those sentences. A
transform does not copy your image and leave the original alone, and it does not mutate your image in
place and hand it back. It moves the `Mat` out from behind your handle. Here is the private helper
every transform on `Image` goes through, verbatim:

#example("The three lines that make the whole type work.")[
```scala
private def transform(op: Mat => Managed[Mat]): Image =
  try Image(op(handle.get))
  finally handle.release()
```
]

`op` borrows the source `Mat`, allocates a destination, runs the native call and returns a `Managed`
around the result; the `finally` frees the source whether or not that succeeded. Two properties fall
out of those three lines. The first is the one you use every day: after `img.gray`, the `Mat` that
`img` was holding is freed, so a twenty-step chain allocates twenty matrices and holds one. The
second matters on the day something goes wrong: the release is in a `finally`, so an operation that
throws part-way still frees its input, and the mid-level layer underneath frees the half-built
destination before the exception propagates. A failing pipeline leaks nothing either.

The in-place operations --- the `draw*` family --- go through a sibling helper, `paint`, which takes
the `Mat` out of the handle rather than releasing it, mutates it, and wraps it in a new `Image`. Same
outcome for the caller, no pixel copy at all: drawing five rectangles and a caption on a 4K frame
touches the pixels under the shapes and nothing else.

What all this rules out is using one handle twice.

#example("The mistake everybody makes once.")[
```scala
val img = Image.read("page.jpg").getOrElse(sys.error("no page"))

val edges = img.gray.canny(80, 160)   // consumes img
println(img.width)                    // img was spent two steps ago
```
]

The second line does not read freed memory. It throws:

```text
java.lang.IllegalStateException: this Mat has already been released or consumed —
using it now would crash the JVM from native code. A high-level Image is spent by any
transform (gray/blur/…) or terminal (write/bytes/close); call `.copy` before the first
use if you need it twice. Run with -Dscalacv.trackOwnership=true to record where it
was consumed.
```

That is `Managed`, the layer under `Image`, doing the one thing that cannot be done anywhere else.
Calling a method on a freed OpenCV object segfaults the JVM from native code: no stack trace, no
`catch`, no test report, only a dead process and an hs_err file. `Managed` holds its object in an
`AtomicReference` that is nulled by whichever of `release` or `take` gets there first, so the check
happens in Scala, before anything crosses JNI, and a second release is a no-op rather than a double
free. An `IllegalStateException` on the line that made the mistake is the difference between a
five-second fix and an afternoon.

#subsect("Pointing the error at the real mistake")

The exception fires at the *reuse*, and the reuse is rarely the interesting line. In Example 4-3 the
two lines are adjacent and there is nothing to diagnose; in real code the image is spent inside a
helper forty lines away and the failure lands on a `width` call that is entirely innocent.

The flag named in that error message is the fix. Start the JVM with
`-Dscalacv.trackOwnership=true` and every handle records a `Throwable` at the moment it is spent;
when a spent handle is later touched, that `Throwable` is attached as the *cause* of the
`IllegalStateException`, so the stack trace you get names the transform or terminal that actually
consumed the image as well as the line that tripped over it.

```bash
java -Dscalacv.trackOwnership=true -cp ... com.example.Batch
```

It is off by default. Turn it on to debug a use-after-move and leave it off in production; Chapter 5,
#emph[Lifetimes: Managed, Releasable, and Scope], has the per-spend cost and the reason the flag has
to go on the command line rather than into a `System.setProperty` call.

#sidebar("The verb that could not be called close")[
OpenCV's morphology has five compound operations, and one of them is `MORPH_CLOSE` --- a dilation
followed by an erosion, the standard way to seal small holes in a mask. A fluent image type also
needs a `close()`, because `AutoCloseable` is how the JVM spells "release this". Two meanings, one
short, obvious, universally understood name.

`Image` gave the name to the resource. `close()` frees the native memory, and morphological closing
is reached through `morphology(MorphOp.Close)` alongside its four siblings --- `Open`, `Gradient`,
`TopHat`, `BlackHat`. The scaladoc says so in one dry parenthesis: there is no bare `close` method
because `close()` already releases the image.

It is the right way round. Getting the wrong `close` would have been a lifetime bug in a type whose
entire purpose is lifetimes, and lifetime bugs in native memory are the expensive kind. Erosion and
dilation, which collide with nothing, keep their own top-level verbs (`erode`, `dilate`) because you
reach for them constantly. Everything else in the family goes through `morphology`, which is one word
longer and never ambiguous.
]

#sect("Branching: `copy`, and the borrowed `mat`")

Move semantics forbid feeding one image into two chains, and sooner or later you want to. There are
two ways out, and they are not interchangeable --- one costs a buffer, the other costs nothing.

When both branches genuinely need to *transform* the pixels, take a `copy`. It borrows the receiver
and clones the `Mat`, so afterwards you hold two independent images with two independent lifetimes,
and each chain spends its own.

#example("Two chains, two lifetimes, one deliberate copy.")[
```scala
val page      = Image.read("page.jpg").getOrElse(sys.error("no page"))
val forHuman  = page.copy                     // borrows: `page` is still alive

val preview   = forHuman.scale(0.25).bytes(".jpg")   // consumes forHuman
val forEngine = page.gray.adaptiveThreshold(blockSize = 15, c = 10).bytes(".png")
```
]

#tip[
`copy` is the one place in the API where a pixel copy is deliberate. Every other step threads a single
buffer through the chain. If you find yourself calling `copy` inside a per-frame loop, that is a
signal to restructure the loop rather than to accept the allocation --- one full-frame clone per frame
at 30 fps is a lot of memory bandwidth spent on a branch you may not need.
]

Often you do not need it. If one of the two branches only *reads* --- a mean, a detector, a
measurement, a draw straight onto the image you already own --- drop to the borrowed `Mat` instead.
`mat` is a query: it hands out the raw `org.opencv.core.Mat` and the `Image` keeps ownership of it.
The mid-level extension operations and the whole typed `org.opencv.*` surface are available on it, and
none of that consumes the image.

#example("A read-only branch that costs no buffer.")[
```scala
val page = Image.read("page.jpg").getOrElse(sys.error("no page"))

val meanGrey = org.opencv.core.Core.mean(page.mat).`val`(0)  // a raw org.opencv.* call
val codes    = page.qrCodes                                  // an extension query

val out = page                        // still alive, still ours
  .drawText(s"${codes.size} codes, mean ${meanGrey.toInt}", Point(10, 30))
  .write("annotated.png")             // now spent, and freed
```
]

The rule for telling them apart is short. If the second use needs its own pixels, `copy`. If it only
needs to look at these ones, borrow the `Mat`.

#memory[
Borrowing is asymmetric on the members that take another image as an *argument*. `applyMask`,
`blend`, `inpaint` and `seamlessCloneInto` consume the receiver and merely borrow what you pass in ---
so the mask or the background you handed over is still live when the call returns, and freeing it is
your job. A segmentation that builds a mask, applies it, and forgets to `close()` the mask leaks one
full-size `Mat` per call.
]

#sect("Getting an image in the first place")

There are five ways to construct one, plus the scoped entry point, and they differ mainly in where
the pixels come from and whether the outside world can refuse.

#figure-table("The constructors on the `Image` companion.")[
#tbl(
  columns: (auto, auto, 1fr),
  [*Constructor*], [*Returns*], [*For*],
  [`Image.read(path, flags)`], [`Either[CvError, Image]`], [a file on disk],
  [`Image.decode(bytes, flags)`], [`Either[CvError, Image]`], [an image file already in memory],
  [`Image.blank(width, height, color, channels)`], [`Image`], [a fresh canvas to draw on],
  [`Image.fromBufferedImage(image)`], [`Image`], [a frame from AWT, Swing or `ImageIO`],
  [`Image.wrap(handle)`], [`Image`], [adopting a `Managed[Mat]` you hold],
  [`Image.reading(path, flags)(use)`], [`Either[CvError, A]`], [read, work, close --- all scoped],
)
]

`read` and `decode` return `Either` because they are the boundary: a path that is not there, a path
that is a directory, an empty file, bytes that are not an image. `imread` reports all four the same
unhelpful way, by handing back an empty `Mat`, so `read` does not call it at all --- the JVM resolves
the path and reads the bytes, and only the decode is left to OpenCV. A failure comes back as a
`CvError.DecodeFailed` carrying the path, with which of the four it was in the error's `details`:
"there is no file at this path", "this path is a directory, not a file", "the file is empty", or the
decoder's own complaint about the bytes. `blank`, `fromBufferedImage` and `wrap` cannot fail against
the outside world and do not pretend to: `blank` rejects a non-positive size, or a channel count
other than 1, 3 or 4, with `IllegalArgumentException` up front, because that is a bug in your code
rather than a fact about the world.

`Image.reading` is the scoped entry point to reach for unless you have a reason not
to. It reads the file, runs your block, and closes the image afterwards --- on success, on failure, and
on an exception thrown from the middle of your chain. It is harmless if the block already consumed the
image, because release is idempotent.

#example("The scoped form, where the body ends in a query.")[
```scala
val inkBlocks: Either[CvError, Int] =
  Image.reading("page.jpg") { img =>
    val binary = img.gray.threshold(128)
    try binary.contours().size
    finally binary.close()
  }
```
]

`reading` closes the image it opened, and nothing else. `gray` and `threshold` spend their receivers,
so `img` needs no help; the binary image at the end of that chain is a different object, and
`contours` is a query, so nothing has consumed it when the block returns. That is what the
`try`/`finally` is for. A body whose last verb is `write` or `bytes` needs neither line --- but the
moment a chain ends in a measurement rather than an output, its tail is yours to close.

There is a second guarantee inside `reading` that is easy to miss. The whole block runs inside
`Cv.attempt`, so a `CvError.NativeCall` thrown by a transform in your chain comes back as a `Left`
rather than escaping past the `Either` you are already holding. The return type is honest about the
whole body, not only about the read.

Because `Image` is `AutoCloseable`, `scala.util.Using` manages one too, which is the form to use when
the image did not come from a path:

```scala
Using(Image.fromBufferedImage(frame))(_.gray.canny(80, 160).bytes(".png"))
```

Both spellings end at the same place. `reading` is tidier when the pixels come from a path; `Using`
covers everything else, and the moments when an image shares a scope with a detector or a video
writer.

#sect("The two escape hatches")

`Image` is a convenience, never a wall, and the two members that prove it are worth keeping straight
because they differ on exactly one point: who frees the `Mat` afterwards.

`mat` #keyterm[borrows]. The `Image` still owns the buffer, still frees it at its terminal, and you
must not call `release()` on what you were handed. Use it for any `org.opencv.*` call this type does
not wrap, and for the mid-level extension operations, which take a borrowed `Mat` and return an owned
`Managed[Mat]`.

`managed` #keyterm[hands ownership over]. It spends the `Image` --- the handle is dead afterwards,
exactly as after `write` --- but it does not free anything. What you get back is a live `Managed[Mat]`
that is now yours to `use`, to `release()`, or to hand to `Image.wrap` to build a new `Image` around.

#example("Down to the mid-level tier and back.")[
```scala
val page = Image.read("page.jpg").getOrElse(sys.error("no page"))

// Borrowed: the Image still owns the Mat and will free it.
val edgeBytes = page.mat
  .cvtColor(ColorConversion.BgrToGray)
  .pipe(_.canny(80, 160))
  .use(Images.encode(_, ".png"))

page.close()                                     // still ours, still to be closed

// Handed over: nothing frees this but you.
val canvas: Managed[Mat] = Image.blank(640, 480).managed
val png = canvas.use(Images.encode(_, ".png"))   // released at the end of `use`
```
]

The mid-level chain has the same one-live-buffer property, spelled with different words: `cvtColor`
returns an owned `Managed[Mat]`, `pipe` releases its input once the next operation has produced its
own, and `use` releases the last one after `Images.encode` returns. What none of them touches is
`page`'s buffer --- `mat` lends it out and takes it back --- which is why the `close()` is not optional.

#memory[
`managed` is the only exit from `Image` that leaves live native memory behind. Drop the returned
`Managed[Mat]` on the floor and it leaks precisely as any stray `Managed` would --- the `Image` that
used to be responsible for it is spent and will never run its terminal. Give the result to `use`,
release it in a `finally`, or wrap it straight back into an `Image`.
]

#sect("Why `faces` is not a member")

`Image` has more than sixty members and none of them mentions a face, a QR code or a skeleton. That is
not an omission. Detection and its overlays arrive as *extension methods*, brought in by
`import scalacv.vision.*` beside core’s `import scalacv.*`, and they read at the call site exactly as members
would:

#example("Extension methods, indistinguishable at the call site.")[
```scala
import scalacv.*

// `detector` is a FaceDetectorYN loaded from a model file --- see Chapter 24.
Image.reading("crowd.jpg") { img =>
  val found = img.faces(detector)          // extension, in FaceDetect.scala
  img.markFaces(found)                     // extension, in FaceDetect.scala
     .drawText(s"${found.size} faces", Point(10, 30))   // member
     .write("marked.png")                  // member
}
```
]

The reason for the split is what the class is *about*. `Image` is about an image: its size, its
pixels, the operations that turn one image into another. Face detection is about faces --- it needs a
`FaceDetectorYN` you loaded from a model file, it returns a `Face` with landmarks, and it belongs with
the rest of the face code. Putting it on the class would mean the class grows a member for every
vision application anyone ever adds, and the core module would have to know about ONNX networks,
ArUco dictionaries and OCR engines to compile at all. So `qrCodes` and `arucoMarkers` live in
`Detectors.scala`, `faces` and `markFaces` in `FaceDetect.scala`, `detectHaar` in `Cascades.scala`,
`arMarkers` and `drawMarkerAxes` in `Ar.scala`, `forOcr` in `Ocr.scala`, `drawSkeleton` in
`Pose.scala` --- every one of them in the separate `scalacv-vision` module, which depends on the core
and not the other way round.

The discipline survives the move. An extension method is built out of the same members you have, so it
is a query or a transform for the same reasons they are: `qrCodes` and `detectHaar` read
`img.mat` and are therefore queries, while `forOcr` --- which is `gray`, then `medianBlur`, then
`adaptiveThreshold`, then `deskew` --- is a transform, and spends the image like any other chain of
four. You do not have to learn a second set of rules for the extension surface, and `contours` stayed
a member precisely because it is not a domain: it is core image processing that any binary image
wants.

#note[
The internal helper `paint`, which the library's own overlay verbs are built on, is `private[scalacv]`
--- visible to `Pose.scala` and friends, not to you. That visibility exists so the library can put its
domain verbs in their own files, not as a public extension point. To write your own overlay, draw
through the borrowed `img.mat` and return the image unchanged; the mid-level draw operations mutate in
place, so the caller's next verb consumes it exactly as it would after a built-in draw.
]

#sect("How an `Image` fails")

Four things go wrong on this type, and they are deliberately not made to look alike.

#figure-table("What each kind of failure does, and what you write to handle it.")[
#tbl(
  columns: (auto, auto, 1fr),
  [*What happened*], [*How it surfaces*], [*What you do*],
  [A boundary operation failed --- `read`, `decode`, `write`, `bytes`], [`Left(CvError)`], [handle the value],
  [OpenCV rejected the pixels at runtime], [throws `CvError.NativeCall`], [wrap in `Cv.attempt` if you want a value],
  [An argument the library can check is wrong], [throws `IllegalArgumentException`], [fix the call],
  [A spent handle was used again], [throws `IllegalStateException`], [`copy`, or restructure the chain],
)
]

Transforms do not return `Either`, and that is a decision rather than an oversight. A chain of twenty
`Either`-returning steps is unreadable, and the failures in question are rare and data-dependent: a
`canny` on an image with the wrong depth, an `equalizeHist` on three channels when it wants one. When
OpenCV throws for one of those, the library wraps it in `CvError.NativeCall`, which names the
operation and keeps OpenCV's own message verbatim. It is unchecked, so it is invisible at the call
site --- which is the honest trade for a chain that reads like a sentence.

Argument mistakes that the library can see without touching a pixel are rejected before the native
call, with `IllegalArgumentException` and a message containing the value you passed:
`blur(-1)`, `scale(0.0)`, `medianBlur(0)`, a `blend` weight outside `[0, 1]`, a `crop` rectangle that
does not fit. Those are bugs in your code and are not meant to be pattern-matched.

When you do want a transform's throw as a value, `Cv.attempt` folds it into one:

#example("Folding an unchecked throw back into an Either.")[
```scala
val edgeCount: Either[CvError, Int] =
  Cv.attempt("edge count"):
    val edges = page.gray.canny(80, 160)
    try edges.contours().size finally edges.close()
```
]

Note what the `try`/`finally` is for: `contours` is a query, so nothing in that block releases the
image, and the block has to. `Cv.attempt` catches OpenCV's exceptions and the library's own
`CvError`s; it deliberately lets `IllegalArgumentException` and `IllegalStateException` through,
because a programmer error should not come back as a `Left` you might quietly log. Chapter 6 works
through the full `CvError` hierarchy and the reasoning behind that line.

#sect("Putting it together")

The running pipeline, assembled: read a photograph of a page, keep a reduced-size preview for a human
and a binarised version for an OCR engine, count the ink blocks, and free everything on every path.

#example("One read, two deliberate copies, two terminals, nothing leaked.")[
```scala
import scalacv.*

def process(path: String): Either[CvError, Int] =
  Image.reading(path) { page =>
    val binary = page.copy.gray.threshold(128)                 // a copy, spent by the chain
    val blocks = try binary.contours() finally binary.close()  // a query, so this tail is ours

    for
      _ <- page.copy.scale(0.25).write("preview.jpg")          // copy borrows, scale consumes it
      _ <- page.forOcr().write("ocr-input.png")                // consumes `page` itself
    yield blocks.size                                          // plain data, safe once all is freed
  }.flatten
```
]

Read it against the three kinds. `copy` borrows, so `page` survives both branches and is spent only
by the last of them. `scale`, `gray`, `threshold` and `forOcr` each consume what they were called on,
so no intermediate outlives its successor. `contours` borrows and returns value types, which is why
`blocks.size` is legal on the `yield` line, long after every `Mat` in the function has been freed ---
and, because it borrows, why `binary` is the one image here that has to be closed by name. The two
`write` calls are terminals and free the images they were called on.

The interesting path is the failing one. If the preview write fails --- an unwritable directory, an
extension OpenCV has no encoder for --- the comprehension short-circuits and `page` is never consumed
at all. Nothing in the body frees it, and nothing has to: `Image.reading` closes it on the way out,
which is a no-op on the happy path and a rescue on this one. The `flatten` is there because the block
returns an `Either` of its own and `reading` wraps it in a second; two failure channels, one shape at
the call site.

What it does *not* have is a `release`, a `Mat`, or a cleanup block per step. One `finally`, on the
one value a query left alive, is the whole of the accounting. That is the trade the type makes: one
rule to hold in your head --- a transform spends its receiver --- in exchange for never doing the rest
of the bookkeeping yourself.

Chapter 5 goes down one tier, to the layer this one kept behind a curtain: what `Managed[A]` is and
why it can release exactly once, how `Releasable` gives a `release()` to the 185 `org.opencv.*` types
that were never given one, and how `Managed.scope` owns the six handles a single `solvePnP` needs
without six levels of indentation. Chapter 6 takes up the error model: what each `CvError` case
actually means, where the line between a returned failure and a thrown one falls, and how to build a
pipeline that reports the difference to whoever is on call.
