#import "../lib/book.typ": *

#chapter("Video I/O", subtitle: [One reused buffer, a writer that fails loudly, and the arithmetic between frame index and wall-clock time.])

A still image is a resource you open, use, and drop. A video is the same resource arriving thirty
times a second for as long as you keep asking, and every one of those arrivals is a `Mat` --- around
six megabytes of off-heap pixels at 1080p, behind about forty bytes on the heap. Video is where
Chapter 1's cautionary tale becomes arithmetic: a minute of untouched 1080p frames is over ten
gigabytes of native memory that the heap graph will not mention.

That arithmetic rules out the abstraction most Scala programmers reach for first. "The frames of a
video" reads like a lazy sequence, and a `LazyList` is exactly wrong here for a reason that has
nothing to do with taste: it memoises. Once a cell is evaluated it holds its head forever so that a
second traversal is cheap, which means every frame the list ever produced stays reachable. Either
nothing is ever released --- an unbounded native leak --- or frames are released as they are consumed
and the list becomes a field of dangling handles that the next traversal hands back as empty Mats.
There is no version of that API where memoisation and per-frame release are both correct.

The second thing a video is, and a still image is not, is a stateful cursor. A capture has a
position; reading advances it; the read blocks in native code with no timeout of its own; and the
backend reports "the file ended" through the identical failure it uses for "the camera was
unplugged". None of that is visible in a type, so it has to be visible in the API's shape instead.

Output fails more quietly still. Ask OpenCV for a writer in a codec the build does not ship and its
usual answer is not an exception: it hands back a `VideoWriter` left quietly closed. Every subsequent
`write` then does nothing --- and reports nothing, because `VideoWriter.write` returns `void`. You
get a file that plays for zero seconds, out of a program in which every line reported success. That
failure, and how scalacv turns it into a value you cannot ignore, is the second half of this
chapter.

#sect("Opening a source")

Everything in this chapter ships in the core artifact, `com.worxbend::scalacv:0.4.1` --- the same
single dependency Part II ran on. Video needs no extra module: `Video`, `Camera`, `Recorder` and
`Codec` all live in the `scalacv` package, and the only other import any listing here needs is
`org.opencv.videoio`, for the raw `VideoCapture` type and the `CAP_PROP_*` constants.

`Video.open` has four overloads across two shapes: a device index, or a source string, each with and
without a `CaptureOptions`. Both return `Either[CvError, Managed[VideoCapture]]`.

#example("Every open is an `Either`, and the handle inside it is owned.")[
```scala
import scalacv.*
import scala.concurrent.duration.*
import org.opencv.videoio.VideoCapture

val fromFile: Either[CvError, Managed[VideoCapture]]    = Video.open("clip.mp4")
val fromCamera: Either[CvError, Managed[VideoCapture]]  = Video.open(0)
val fromNetwork: Either[CvError, Managed[VideoCapture]] =
  Video.open("rtsp://camera.local/stream", CaptureOptions.withTimeout(5.seconds))
```
]

The `source` string is whatever the backend understands: a filesystem path, an `rtsp://` or `http://`
URL, a `frame_%04d.png` image-sequence pattern, a GStreamer pipeline. OpenCV resolves it itself and
knows nothing about classpath resources.

Whether a source opens is data-dependent --- a missing file, an unreadable container, a protocol no
backend on this classpath can drive --- so it is a `Left` carrying a `CvError`, never a throw. What
is a programming error stays a throw: an empty source string or a negative device index fails the
`require` at the top of `open`, before OpenCV is involved.

The guarantee that matters is what `open` checks before handing the capture back. It sets
`setExceptionMode(true)` for the duration of the open, so a missing file becomes a `CvError` quoting
OpenCV's own message and naming the path, and it verifies `isOpened` afterwards. You never receive a
`Right` holding a capture that cannot deliver frames --- and the test suite pins both ends of that: a
nonexistent path is a `Left`, and so is an existing `.avi` holding 512 bytes of the letter `A`.
Neither becomes a video with no frames in it, the failure mode that costs you an afternoon because
an empty result looks exactly like a correct one.

#memory[
`VideoCapture` is one of exactly three `org.opencv.*` types with a real public `release()` --- the
others are `Mat` and `VideoWriter`. The other 185 native-owning types expose only a private
`delete(long)` and have to be freed through the handle bridge. A capture needs none of that
machinery, and `Video.open` still wraps it in a `Managed[VideoCapture]`: release becomes an atomic
get-and-set that frees exactly once, and touching a released capture throws `IllegalStateException`
on the Scala side rather than segfaulting from JNI. Every failure path inside
`open` releases the half-built capture before returning the `Left`, so a failed open leaks nothing.
]

The handle is caller-owned. Prefer `.use`, which releases on every exit path:

```scala
Video.open("clip.mp4").map(_.use(c => Video.frames(c)(_.size)))
```

If the capture has to outlive a single block, release it yourself in a `finally`; `release()` is
idempotent, so belt and braces costs nothing.

#sect("What the file claims about itself")

`Video.info(capture)` returns a `CaptureInfo`. It `require`s an open capture --- against a closed one
every field would be a query through a dead handle --- and every field it returns is a `CAP_PROP_*`
query, which is to say what the backend *claims*, not what will actually decode.

#example("Metadata, which is advisory in every field.")[
```scala
Video.open("clip.mp4").map { capture =>
  capture.use { c =>
    val meta: CaptureInfo = Video.info(c)
    (meta.size, meta.fps, meta.frameCount, meta.backendName)
  }
}
```
]

#figure-table("`CaptureInfo`, and where each field is unreliable.")[
#tbl(
  columns: (auto, auto, 1fr),
  [Field], [Type], [When it lies],
  [`width` / `height`], [`Int`], [a camera may report `0` before it has delivered a frame; `size` bundles the pair as a `Size`],
  [`fps`], [`Double`], [`0` for a camera that has not warmed up; a nominal, not measured, rate for a file],
  [`frameCount`], [`Long`], [`0` for a live source (the question is meaningless); off by a frame or two on some containers],
  [`backendName`], [`String`], [this one does not --- it names the backend that opened the source],
)
]

`frameCount` is clamped at zero on the way out, because some backends answer `-1`.

#warning[
Never make `frameCount` a loop bound. `for i <- 0 until Video.info(c).frameCount.toInt` is a bug in
two directions at once: against a live source it reports `0` and you process nothing, and against a
container that over-reports you read past the end. The only frame count that is true is the one the
frame loop actually delivers. Use `info` to size a writer or draw a progress bar, and let the
iterator decide when the video is over.
]

#sect("Walking the frames")

#example("The frame loop, and its full signature.")[
```scala
def frames[A](capture: VideoCapture, attemptsPerFrame: Int = 1)(f: Iterator[BorrowedMat] => A): A
```
]

`Video.frames` runs your function over an `Iterator[BorrowedMat]` created when the block begins and retired
when it returns. The iterator owns *exactly one* `Mat` and decodes every frame into it, in place.
That is the whole design: the native footprint of a frame loop is one frame, whether the video runs
five seconds or five hours, and there is no per-frame allocation to pay for.

Three behaviours of that iterator are worth knowing before you write against it. `hasNext` is
idempotent --- it holds a decoded frame pending rather than decoding again --- which matters because
`for f <- frames` desugars into exactly the `hasNext; hasNext; next()` shape that a naive
implementation would turn into a silent frame-dropper. `next()` past the end throws
`NoSuchElementException`, rather than handing back an empty `Mat` that looks like a legitimate black
frame. And the capture is only borrowed: `frames` neither releases nor rewinds it, so a second call
resumes exactly where the first stopped.

#example("Two traversals of one capture read consecutive spans.")[
```scala
Video.open("clip.mp4").map { capture =>
  capture.use { c =>
    val firstTen = Video.frames(c)(_.take(10).size)  // frames 0..9
    val nextTen  = Video.frames(c)(_.take(10).size)  // frames 10..19 — resumes, does not rewind
    (firstTen, nextTen)
  }
}
```
]

`attemptsPerFrame` is how many consecutive failed reads end the stream. The default of `1` is right
for a file, where the first `false` from `read` *is* end-of-file; a live camera can drop a frame
without the stream being over, and a small value --- 2 to 5 --- rides that out. It is a bound and not
a retry-forever, because `read` blocks in native code with no timeout of its own and an unbounded
loop would turn a dead source into a thread that both hangs and spins.

Inside the loop, `frames` turns OpenCV's exception mode *off* and restores it afterwards. OpenCV
signals end-of-file through the same `CvException` it uses for a broken stream --- measured on a
clean five-frame file, the end of the video arrives as
`cap.cpp:533 error: (-2:Unspecified error) in function 'grab'`. With exception mode on, the loop
could not tell a finished video from an unplugged camera. Off, a `read` returning `false` ends the
stream cleanly and a genuine decode failure still surfaces as `CvError.NativeCall`.

#sect("The borrowing contract")

This is the one place in scalacv where a `Mat` you are handed is not yours --- the exact inverse of
the contract everywhere else in the library. The frame is *borrowed*: valid from the `next()` that
returned it until you next ask the iterator for anything, at which point the same buffer is
overwritten with the following frame, and released for good when the `frames` block returns, on the
exception path too.

So the mistake is the one that looks most like ordinary Scala:

#example("Wrong. Not ten owned frames --- ten views that are spent when the block ends.")[
```scala
Video.open("clip.mp4").map { capture =>
  capture.use { c =>
    Video.frames(c)(_.toVector)   // collects views that throw after the traversal
  }
}
```
]

The collection itself succeeds, but accessing any retained `BorrowedMat` throws
`IllegalStateException` after the traversal. The view checks liveness before JNI; extracting and
retaining the raw `frame.mat` bypasses that protection and remains unsafe. `toList`, `sliding` and
`buffered` retain views too, so reduce each frame or take an owned copy before combining.

The right version reduces each frame to something owned *before* pulling the next one:

#example("Right. Each frame becomes a number inside the loop; the vector holds Ints.")[
```scala
val contourCensus: Either[CvError, Vector[Int]] =
  Video.open("clip.mp4").map { capture =>
    capture.use { c =>
      Video.frames(c) { frames =>
        frames.map { frame =>
          frame.mat.cvtColor(ColorConversion.BgrToGray)
            .pipe(_.canny(80, 160))
            .use(_.findContours().size)
        }.toVector
      }
    }
  }
```
]

`toVector` is fine here and unsafe two listings ago, and the difference is the element type. Every
mid-level `Ops` call --- `cvtColor`, `canny`, `resize`, `gaussianBlur` --- allocates its own
destination and hands back a `Managed[Mat]` you own, aliasing nothing. So the whole of Part II runs
correctly over a borrowed frame, and what escapes the `map` is an `Int`.

#memory[
The frame's buffer belongs to the iterator, and the iterator is retired before its `Mat` is released
when the block exits. That ordering is load-bearing: `VideoCapture.read` on a released `Mat` would
quietly reallocate it and hand back a real frame in memory nothing owns and nothing will free.
Retirement is what makes an iterator you accidentally let escape *inert* --- it reports no more
frames, and `next()` throws a message naming the cause --- rather than a slow leak.
]

#figure-table("What a borrowed frame supports, and what the liveness guard rejects.")[
#tbl(
  columns: (1fr, auto, 1fr),
  [Operation], [Safe?], [Why],
  [Read pixels, query `empty`, `size`, `findContours`], [yes], [consumed before the next pull],
  [Any `Ops` call: `cvtColor`, `canny`, `resize`], [yes], [allocates its own owned destination],
  [`rec.write(frame.mat)`, encoding to bytes], [yes], [the work happens before the next pull],
  [`toList` / `toVector` / `sliding` / `buffered`], [no], [spent views; access throws],
  [Stashing the `Mat` in a `var`, field or collection], [no], [the view is spent after the next pull or at block exit],
  [`frame.clone()`, keeping and releasing the clone], [yes], [a raw `Mat` you now own; `framesCopied` wraps it for you],
)
]

When you genuinely need frames that outlive the loop --- comparing three, picking the sharpest,
compositing --- reach for `Video.framesCopied`. It has the same signature as `frames`,
`attemptsPerFrame` and its default of `1` included, but over an `Iterator[Managed[Mat]]`: each frame
is cloned into a caller-owned handle with its own pixel buffer. The clone happens as you pull, so
frames you never reach are never copied, and everything you do pull is yours to release.

#example("Frames you can keep, at one full-frame copy each.")[
```scala
Video.open("clip.mp4").map { capture =>
  capture.use { c =>
    val firstThree: Vector[Managed[Mat]] = Video.framesCopied(c)(_.take(3).toVector)
    try firstThree.foreach(m => analyse(m.get))
    finally firstThree.foreach(_.release())
  }
}
```
]

The price is one allocation and one full-frame copy per frame, which is why it is not the default.
`Camera`, which hands you an owned `Image` per frame, is `framesCopied` with the copy wrapped and the
lifetime handled --- so before writing the listing above, check whether `Camera` already does what
you want.

#sidebar("Why the frame source is an Iterator and nothing friendlier")[
`Iterator` is not an elegant type. It was chosen because every more elegant candidate retains, and
retention is what a single reused buffer cannot survive. `LazyList` and `Stream` memoise by
definition; `Seq` is strict; a reactive stream buffers to give you back-pressure.

Even within `Iterator` the combinators split cleanly. `map`, `filter`, `zipWithIndex`, `take`,
`foreach` and `foldLeft` pull one element and forget it, and are safe; `sliding`, `grouped`,
`buffered`, `duplicate` and every `to*` conversion hold elements, and are wrong on a borrowed frame.
The rule that survives contact with the API: a combinator is safe on a borrowed frame exactly when it
never has two frames in hand at once. When you need two --- a frame difference, motion detection,
temporal smoothing --- `framesCopied` is not a nicety, it is the requirement.
]

#sect("Seeking, frame index, and wall-clock time")

`Video` has no `seek`. That is deliberate: seeking is a property of the capture, not of a traversal,
and only file-backed sources support it at all. The capture is borrowed rather than hidden, so you
set the position on it directly with the videoio constants.

#example("Jump to frame 100, then read from there.")[
```scala
import org.opencv.videoio.Videoio

Video.open("clip.mp4").map { capture =>
  capture.use { c =>
    val accepted = c.set(Videoio.CAP_PROP_POS_FRAMES, 100.0)  // file sources only
    val landedOn = c.get(Videoio.CAP_PROP_POS_FRAMES)         // where it actually put you
    (accepted, landedOn, Video.frames(c)(_.take(30).size))    // 30 frames from there
  }
}
```
]

Frame index and wall-clock time are not the same quantity, and the gap between them is where
timestamps go wrong. The index is an exact integer the decoder counts. The time is `index / fps`, and
`fps` came out of `CaptureInfo`, where it is the container's *nominal* rate. For constant-frame-rate
material the two agree; for anything variable --- a phone recording that dropped frames under thermal
load, a screen capture, an RTSP stream --- they drift, and the drift accumulates over the clip. If
the timestamp matters, ask the backend for its own answer with `Videoio.CAP_PROP_POS_MSEC` rather
than multiplying an index by a nominal rate. If only ordering matters, the index is exact and free.

Seeking is approximate in a second way, which is why the listing above reads the position back
rather than trusting it. A decoder can usually only start at a keyframe, so a backend may land you on
the nearest one rather than on the frame you named, and `set` returning `true` says the property was
accepted, not that the cursor is where you asked. Seek, then ask.

#note[
`frames` resuming rather than rewinding composes with seeking: set a position, take a span, set
another, take another. The capture carries the state; the iterator is one traversal of it.
]

#sect("Writing: the Recorder")

Output is a `Recorder`: a `VideoWriter` fixed at open time to one frame size, one frame rate, one
codec, and one channel count.

#example("Opening a writer. Everything but the path and the size has a default.")[
```scala
def open(
    path: String,
    size: Size,
    fps: Double = 30.0,
    codec: Codec = Codec.Mjpg,
    color: Boolean = true
): Either[CvError, Recorder]
```
]

`fps` must be positive and both extents of `size` must be positive; both are `require`d rather than
returned as a `Left`, because a zero in either is a caller who passed `CaptureInfo` fields straight
through without reading the warning on them, not a property of the data.

`Recorder.using` takes the same five parameters plus a `use: Recorder => A` block, closes the writer
on every exit path, and returns `Either[CvError, A]` --- so a `using` nested inside another
`Either`-returning scope is one `.flatten` away from the flat result you want. `write` comes in two
overloads --- `write(image: Image)` and `write(frame: Mat)` --- returning `Either[CvError, Unit]`,
and both *borrow*: writing neither consumes nor releases the frame. The `Mat` overload exists so
that a borrowed frame from `Video.frames` can go straight into the encoder without the clone an
`Image` would force.

#example("A frame-for-frame re-encode with no copy anywhere in it.")[
```scala
val reencoded: Either[CvError, Long] =
  Video.open("clip.mp4").flatMap { capture =>
    capture.use { c =>
      val meta   = Video.info(c)
      val outFps = if meta.fps > 0 then meta.fps else 30.0
      Recorder.using("copy.avi", meta.size, outFps, Codec.Mjpg) { rec =>
        Video.frames(c) { frames =>
          frames.foldLeft(0L)((n, mat) => n + rec.write(mat).fold(throw _, _ => 1L))
        }
      }
    }
  }
```
]

Three details in that listing are load-bearing. `meta.fps` goes through a guard because
`Recorder.open` requires a positive rate while `CaptureInfo` is advisory in every field --- passing a
source's `0` through unexamined turns a data-dependent failure into an `IllegalArgumentException` out
of a call whose type promised a `Left`. The `foldLeft` counts as it goes, so "how many frames were
written" is a value rather than a comment. And `fold(throw _, …)` is what stops a rejected write from
being silently discarded: `write` returns an `Either`, and an `Either` dropped in statement position
is the same class of bug as ignoring the result of `open`. That throw leaves the surrounding `Either`
rather than joining it; wrapping the block in `Cv.attempt` folds it back into a `Left`, which is
exactly what `Camera.recordTo` does internally.

#memory[
`VideoWriter` is the third of the three types with a public `release()`, and a `Recorder` wraps it in
a `Managed[VideoWriter]` exactly as a capture is wrapped. `close()` is idempotent and is what
finalises the container --- a writer that is never closed can leave the file's index unwritten, so
the clip is not merely leaked, it is unplayable. `Recorder.using` closes it for you. If `open` fails,
the half-built `VideoWriter` is released before the `Left` is returned, in the same discipline
`Video.open` applies to a capture.
]

Two things `write` rejects with an `IllegalArgumentException` rather than a `Left`, because both are
programming errors and not data-dependent failures: a frame whose dimensions do not match the size
the recorder was opened at, and a frame that is not 8-bit. The second check earns its place.
`VideoWriter.write` returns `void` and the encoder never inspects the depth, so a `CV_32F` frame ---
a float-depth Sobel, a distance transform, a raw disparity map --- used to be accepted, its bytes
reinterpreted as 8-bit pixels, and a perfectly playable file of noise produced with every call
reporting success. Bring a float frame down with `normalize`, which produces 8-bit by default, or
with `convertScaleAbs` --- the two the rejection message itself names.

#sect("FourCC, containers, and the zero-byte file")

A `Codec` is a four-character code --- a FourCC --- packed into an `Int` in pure Scala, using the same
bit layout as OpenCV's `CV_FOURCC`. Packing it in Scala rather than calling `VideoWriter.fourcc`
means the enum can be referenced before `OpenCv.load()` has run.

#figure-table("The four codecs, and what each one costs you in portability.")[
#tbl(
  columns: (auto, auto, auto, 1fr),
  [Codec], [`fourcc`], [Container], [Availability],
  [`Codec.Mjpg`], [`MJPG`], [`.avi`], [*The default.* Motion-JPEG, served by videoio's built-in writer --- no FFmpeg, no GStreamer, no system codec. Large files.],
  [`Codec.Mp4v`], [`mp4v`], [`.mp4`], [MPEG-4 Part 2. Needs videoio linked against FFmpeg or a platform encoder.],
  [`Codec.Avc1`], [`avc1`], [`.mp4`], [H.264. Best compression, only if the build ships an H.264 encoder.],
  [`Codec.Xvid`], [`XVID`], [`.avi`], [Xvid MPEG-4.],
)
]

The container is half of the bargain, in both directions: MJPG opens only inside an `.avi`, `Mp4v`
only inside an `.mp4`. Codec and extension always move together, and the commonest form of the
mistake is changing one and forgetting the other --- `Codec.Mjpg` with an `out.mp4` path fails to
open even though the codec itself is present on every build.

`Mjpg` is the default for a concrete reason rather than a conservative one: the `org.bytedeco`
`linux-x86_64` and `windows-x86_64` payloads this library pins ship *no FFmpeg plugin at all*, so
`Mp4v` and `Avc1` do not open there.

Now the failure this section is named for. At the raw OpenCV level an unavailable codec gives you
nothing to check: `VideoWriter.open` usually leaves `isOpened` false rather than throwing --- it can
throw at the codec boundary, which is why `Recorder.open` guards against both --- and from then on
`write` returns `void` and does nothing. What is left on disk is an empty file or a bare container
header, produced by a program in which every line reported success.

`Recorder.open` converts that into a value you cannot accidentally ignore. It checks `isOpened` and
returns `Left(CvError.LoadFailed)` naming the path, the codec, and the fallback to try. So the way to
detect a missing codec is to stop discarding the `Either`:

#example("Prefer the compact codec, fall back to the one that always opens.")[
```scala
def openRecorder(base: String, size: Size, fps: Double): Either[CvError, Recorder] =
  Recorder.open(s"$base.mp4", size, fps, Codec.Mp4v)
    .orElse(Recorder.open(s"$base.avi", size, fps, Codec.Mjpg))
```
]

A second signal is worth wiring into any batch job: the frame count. `Camera.recordTo` returns
`Either[CvError, Long]`, the number of frames actually written, and a `Right(0)` means the source
yielded nothing and no file was created. Asserting a positive count catches the empty-source case
that a `Right` alone does not distinguish.

#sect("The worked example: an annotated re-encode")

Put the pieces together: read a clip, count the external contours in each frame, burn that count
into the frame as text, write the result out as a video. The interesting part is that the annotation
*consumes* the frame.

`Camera.recordTo` is the one-call form of the whole loop:
`recordTo(path, fps = 0, codec = Codec.Mjpg, attemptsPerFrame = 3)(transform)`. It reads every frame,
applies your `Image => Image` transform, and writes the results, sizing the recorder from the *first
transformed frame* rather than from `CaptureInfo` --- which is what makes it correct against a source
that answers `0`×`0` until it has delivered something. The `fps` default of `0` means *derive it*:
the source's reported rate if it has one, `30.0` if it does not --- the same guard the previous
listing had to write out by hand.

#example("Read, annotate, write. `recordTo` owns every lifetime in the loop.")[
```scala
import scalacv.*

val written: Either[CvError, Long] =
  Camera.usingFile("clip.mp4") { cam =>
    cam.recordTo("annotated.avi", codec = Codec.Mjpg, attemptsPerFrame = 1) { frame =>
      val edges = frame.copy.gray.canny(80, 160)
      val n = try edges.contours().size finally edges.close()
      frame.drawText(s"$n contours", Point(12, 28), Scalar.Green)
    }
  }.flatten
```
]

Two details carry the listing. `frame.copy` is there because `gray` is a transform and transforms
consume: without the copy, the frame would be spent before `drawText` saw it. And the last expression
consumes `frame` and returns a new `Image`, which `recordTo` closes after writing --- the original
handle is already spent by then, and `close()` on a spent handle is a no-op rather than a double
free. `attemptsPerFrame = 1` is the file setting; the default of `3` suits a camera and costs two
extra blocking reads at end-of-file here.

The transform must hand back an 8-bit, three-channel frame, because the recorder is opened
`color = true` --- which is why an edge video's transform has to end
`.convert(ColorConversion.GrayToBgr)`. Resizing inside the transform is allowed, as long as *every*
frame is resized the same way; a size that changes part-way through is reported as a `Left`, not
thrown.

`recordTo` writes every frame it reads. When that is not what you want --- writing only the frames
that pass a test, writing two outputs, interleaving something else --- open the `Recorder` yourself
and drive the loop:

#example("The explicit form: a recorder, a frame loop, and a filter between them.")[
```scala
val busy: Either[CvError, Long] =
  Camera.usingFile("clip.mp4") { cam =>
    val outFps = if cam.fps > 0 then cam.fps else 30.0
    Recorder.using("busy.avi", cam.size, outFps, Codec.Mjpg) { rec =>
      var kept = 0L
      cam.foreach(1) { frame =>
        val edges = frame.copy.gray.canny(80, 160)
        val n = try edges.contours().size finally edges.close()
        if n > 20 then
          val marked = frame.drawText(s"$n contours", Point(12, 28), Scalar.Green)
          try rec.write(marked).fold(throw _, _ => kept += 1)
          finally marked.close()
      }
      kept
    }
  }.flatten
```
]

`cam.foreach` hands you an owned `Image` and closes it when your function returns, so the frames you
skip need nothing from you. The ones you annotate produce a second `Image` that `foreach` knows
nothing about: `marked` is yours, `write` only borrows it, and the `finally` closes it on the
throwing path too. The `.flatten` is the nested-`Either` tax named earlier --- `usingFile` and
`using` each contribute one. Sizing the recorder from `cam.size` is safe only because the source is a
file that already reports its geometry; against a camera, take the size from the first frame as
`recordTo` does, and keep the `fps` guard either way.

#sect("Performance: decode, resize, skip")

This book has no frames-per-second number to give you, and neither does the benchmark suite: every
measured figure in `benchmark-results.md` is one call to one operation over a synthetic scene, and
decode, colour conversion, your own logic and encode all interact. Measure your pipeline, on your
material.

What the measurements *do* settle is where the wins are not. Reusing destination `Mat`s across frames
--- the "arena" that received wisdom calls the largest single win in an OpenCV loop --- was
benchmarked against the plain allocating pipeline over the `gray → blur → canny` chain, at three
frame sizes. Both variants produce bit-identical output, verified by hash, so the delta is the
per-frame allocate-and-free cost and nothing else.

#figure-table("`ArenaReuseBench`: reusing destination Mats across frames, per chain.")[
#tbl(
  columns: (auto, auto, auto, auto),
  [Frame], [Fresh allocation], [Reused destinations], [Delta],
  [640×480], [145.3 µs], [139.1 µs], [−4%],
  [1920×1080], [574.2 µs], [576.3 µs], [+0.4% --- reuse was *slower*],
  [3840×2160], [7556 µs], [7493 µs], [−0.8%],
)
]

At 1080p and above the delta is under one percent and changes sign, which is the shape of noise
rather than of a win --- and this harness runs both variants inside one un-forked JVM, so a
sub-one-percent gap between them is precisely the kind of number its own methodology says not to
trust. The 4% at 640×480 is the largest honest reading in the table, and it buys 6 µs off a chain
that costs 145. An opt-in arena for that meant a large new API surface and the reversal of the
no-in-place ownership contract the whole library rests on, so it was not built.

The wins that are real are the same win: stop copying frames.

- *Do not copy what you only read.* A 1080p BGR frame is about six megabytes, so copying every one
  at 30 fps is roughly 180 MB/s of allocation --- an order of magnitude more traffic than the arena
  above was ever going to save. `Video.frames` allocates nothing per frame; `Camera` costs one
  full-frame clone per frame for the convenience of an owned `Image`. Stay on `Camera` until a
  profiler says that clone is your bottleneck, then drop to `Video.frames` on `cam.capture`, which is
  the same capture, borrowed.
- *Write borrowed frames straight through.* `Recorder.write`'s `Mat` overload exists so that a
  re-encode costs no clone at all.
- *Resize at the head of the pipeline, not in the middle of it.* Halving each side quarters the pixel
  count, and everything downstream --- blur, threshold, contours --- is linear or worse in pixels.
  Detection on a 640-wide frame followed by scaling the coordinates back up is usually a better trade
  than detection on a 1080p one.

Skipping frames deserves a caveat, because the obvious spelling does not save what you think. A
`filter` or a `zipWithIndex` over the iterator is safe --- neither retains --- but the iterator has
already decoded every frame it offers, so filtering saves your processing and none of the decode,
which is frequently the largest single cost in the loop.

#example("Process every fifth frame --- but the other four were still decoded.")[
```scala
Video.frames(c) { frames =>
  frames.zipWithIndex.collect { case (frame, i) if i % 5 == 0 =>
    frame.mat.cvtColor(ColorConversion.BgrToGray)
      .pipe(_.canny(80, 160))
      .use(_.findContours().size)
  }.toVector
}
```
]

To skip the decode as well, advance the capture yourself. `VideoCapture.grab()` moves the source
forward without decoding the frame or copying it into a `Mat` --- it is exactly what `Video.open`
uses to discard warm-up frames on a camera. Pull one frame through the iterator, grab past the ones
you do not want, then pull again; `frames` resuming rather than rewinding is what makes that
composition work. On a file, seeking is cheaper still where the container supports it.

#sect("Where this goes next")

The capture is opened, walked, seeked and closed; the frame is borrowed and then reduced or copied on
purpose; the writer fails as a `Left` at open rather than as a zero-byte file at three in the
morning. Every guarantee here rested on the source being finite and cooperative: `frameCount` meant
something, `attemptsPerFrame = 1` was correct, and end-of-stream arrived exactly once.

Chapter 20, #emph[Cameras and Recording], removes all three. A camera reports no frame count, delivers an
under-exposed first frame while its auto-exposure loop converges, drops a frame without the stream
being over, and never ends at all --- and the `CaptureOptions` fields this chapter passed over,
`backend`, `warmupFrames` and the two best-effort timeouts, are the levers for every one of those.
