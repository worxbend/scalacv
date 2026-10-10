#import "../lib/book.typ": *

#chapter("Why This Library Exists", subtitle: [How forty bytes of Java object can cost you six gigabytes of machine.])

A vision service is one of the few kinds of program that can die without ever misbehaving. The heap
graph is flat. There are no long pauses, no promotion storms, no allocation rate worth naming. Every
dashboard you have is green, and the container is killed anyway, at three in the morning, with a
kernel OOM message and no Java stack trace anywhere in the logs. Restart it and the clock begins
again: forty minutes of perfect health, then the same death.

The reason is that almost nothing your JVM monitoring can see is where the memory went. OpenCV
stores pixels in native memory, allocated by C++ through `cv::fastMalloc`, and hands the JVM a small
Java object holding a `long` address. A 1920 × 1080 three-channel frame is about six megabytes of
pixels behind roughly forty bytes of Java. Decode thirty of those a second and you are moving
180 MB/s of native memory behind 1.2 KB/s of heap. The garbage collector runs on heap pressure. It
sees the 1.2 KB/s. It has no reason to run at all, and so it does not, and the six megabytes stay
where they are.

This is not a bug in OpenCV, and it is not a bug in the JVM. It is what happens when a memory model
that reclaims by tracing meets a memory model that reclaims by explicit free, and nobody writes down
which one owns what. The Java bindings will happily let you not write it down. They will let you
drop a `Mat` on the floor, and the program will keep working --- for a while, on your laptop, on the
test image, at the scale you developed against.

scalacv exists because that failure is worth engineering against directly rather than discovering
repeatedly. The typed API, the enums, the `Either`s, the fluent pipeline --- all of that is pleasant,
and none of it is the reason. The reason is that in this library, every native object has exactly
one owner and a defined moment of death, and misusing one is a Scala exception at the offending line
rather than a segfault an hour later.

#sect("What the Java bindings actually are")

A great deal of confusion here comes from imagining the bindings are something more than they are.

OpenCV is a C++ library. Its Java API is generated: a code generator walks the C++ headers and emits,
for each exported class, a Java class holding a single `long nativeObj` field and a set of `private
static native` methods that take that address as their first argument. `org.opencv.core.Mat`,
`org.opencv.objdetect.CascadeClassifier`, `org.opencv.dnn.Net` --- all of them are this shape. There
is no Java-side data structure, no buffer, no `byte[]`. There is an address, and a set of JNI calls
that dereference it.

That shape has three consequences, and they recur throughout this book.

The first is that the Java object's size tells you nothing about what it costs. A `cv::Mat` header is
around a hundred bytes in C++, and the Java object in front of it is smaller still; the pixel buffer
hanging off that header is the megabytes. An empty `Mat` and a `Mat(1000, 1000, CV_8UC3)` are
indistinguishable in a heap dump, and one of them holds three megabytes of the machine. Every
profiler you already trust is measuring the wrong side of the pointer.

The second is that every method call is a JNI transition. The JIT compiler cannot see across it,
cannot inline it, and cannot optimise it away --- which is why a per-pixel accessor on a `Mat` is
slow, not because OpenCV is slow but because it is a border crossing per pixel.

The third is the dangerous one. A method call on a freed address is not an exception. It is a read of
memory the allocator has handed to someone else, which is a `SIGSEGV` when you are lucky and a
plausible-looking wrong answer when you are not. The JVM does not catch this. There is no stack
trace, because there is no Java frame to unwind --- the process is gone, and the `hs_err` file points
at C++ you did not write.

#memory[
  Freeing a native object twice is worse than either leaking it or using it after free. A double
  `delete` corrupts the C++ allocator's own bookkeeping, and the crash it eventually produces
  happens somewhere else entirely, at an unpredictable later moment, in code that is completely
  innocent. Every design decision in this chapter that looks over-cautious is aimed at that failure.
]

#sect("The collector cannot see what it does not own")

The gap between heap pressure and native pressure is measurable in a few lines, and this project
measures it. The test allocates 2000 `Mat(1000, 1000, CV_8UC3)` --- three megabytes of pixels
each --- drops the reference to every one of them immediately, and never calls `System.gc()`.
Nothing about that workload is pathological; it is what a batch job over a directory of photographs
does. Then it reads process resident-set size and compares it against the same loop with one line
added.

#figure-table("2000 unreleased Mats against the same 2000 released, measured as process RSS.")[
#tbl(
  columns: (1.6fr, 1fr),
  [Workload], [Final RSS],
  [references dropped, no `release()`], [5,865 MB],
  [`release()` on each], [144 MB],
)
]

Forty-one times the memory, for one omitted call, in a program that at no point holds more than one
live reference. The same ratio reproduces on JDK 21 and on JDK 25, so it is not an artefact of one
collector's tuning. And note what the second row proves: reclamation was always possible. The memory
was never unreachable-but-unfreeable. Nothing in the runtime had any motive to make it happen, and
no deadline by which to try.

That last point is the one people argue with, so it is worth stating flatly. The JVM is not failing
here; it is doing exactly what it promises, which is to manage the heap. Forty bytes per frame is
not heap pressure at any frame rate you can achieve, so nothing ever runs that would call
`release()` on your behalf --- and by the time the cgroup limit is hit, the killer that arrives is
the kernel's, not the JVM's.

#sect("The types you cannot free at all")

If the story ended at `Mat`, the fix would be discipline, and this library would be a thin
convenience. It does not end there.

Of the 188 `org.opencv.*` types that own native memory, exactly *three* expose a public `release()`:
`Mat`, `VideoCapture` and `VideoWriter`. The other 185 do not. `CascadeClassifier` does not. `Net`
does not. `QRCodeDetector`, `ArucoDetector`, `KalmanFilter` --- none of them. Each one carries a
`private static native void delete(long)` that the generator emitted, and a `finalize()` that calls
it, and nothing else. From Java, with the public API alone, there is no supported way to free a
detector before the collector decides to.

The cost of that scales with the object, and a detector is not a small object. A `KalmanFilter`
holds several matrices; a loaded `Net` holds the entire model's weights. Measured the same way:
4000 leaked `KalmanFilter`s reached 54 GB of resident memory, against 86 MB when released.

scalacv frees them anyway. That decision --- to reach past the public API of the bindings rather
than accept a 54 GB leak --- is the most consequential piece of engineering in the library, and
worth understanding before you rely on it.

#sect("Why finalizers and cleaners do not save you")

Three mechanisms are usually proposed at this point. Each is genuinely appealing, and each is worth
walking to its end, because each genuinely does not work.

#minor("Finalizers")

The bindings already have them. It is a common myth that `finalize()` no longer runs; it runs. The
problem is #emph[when]. A finalizer runs once the collector has determined the object is
unreachable, which requires the collector to run, which requires heap pressure --- and forty bytes
per frame produces none, as the table above measures. Finalization is also unordered, single-threaded
through one queue, and, since JDK 18, deprecated for removal, which means the mechanism this library
would be depending on is one the platform has announced it intends to delete.

#minor("A Cleaner")

`java.lang.ref.Cleaner` fixes the deprecation and the ordering, and fixes nothing else. A cleaner
action still fires on the same trigger: the referent becoming unreachable, observed by the collector,
under heap pressure. Registering a cleaner around a `Mat` gives you a better-engineered version of
the same latency, which is unbounded. You cannot cite a `Cleaner` in an argument about a fixed memory
ceiling, because it makes no promise about time.

#minor("A phantom-reference budget")

You can go further and count bytes yourself, tripping a manual drain when a native budget is
exceeded --- which is roughly what JavaCPP does with `Pointer.totalBytes()` and
`-Dorg.bytedeco.javacpp.maxBytes`. This is a real technique. It is also blind to the memory in
question, for a reason covered in the sidebar below, and it still leaves the 185 releaseless types
with nothing to call.

So scalacv does the unglamorous thing instead: it frees them itself, at a moment you determine, and
it fails loudly if it cannot. `Releasable[A]` is the type class that says how a native type dies, and
it has exactly two regimes. Which one applies is not a style choice; it is dictated by what the
generated binding exposes.

#figure-table("The two release regimes, and what dictates which one applies.")[
#tbl(
  columns: (1fr, 1.5fr),
  [Native type], [How it frees],
  [`Mat`, `VideoCapture`, `VideoWriter`], [the public `release()`. For `Mat` this drops the pixel #emph[buffer] immediately --- the part that costs megabytes.],
  [the other 185 (`CascadeClassifier`, `Net`, `ArucoDetector`, `KalmanFilter`, …)], [a cached `MethodHandle` onto the binding's private `delete(long)`, after #emph[disarming] the binding's finalizer.],
)
]

The second row is where the care lives: freeing one of the 185 is a three-step sequence whose order
is not negotiable, and getting it wrong is the double free described at the top of this chapter. The
bridge reaches for private API, so when it cannot --- a class loaded from a named module, a finalizer
field it is not permitted to zero --- it throws rather than quietly falling back to the collector,
because a leak is recoverable and a corrupted heap is not. Chapter 5, #emph[Lifetimes: Managed,
Releasable, and Scope], sets the sequence and its refusals out in full.

#sidebar("Why the obvious leak detector reports nothing")[
  The first instinct on suspecting a native leak is to reach for JavaCPP's accounting:
  `Pointer.totalBytes()`, and the `-Dorg.bytedeco.javacpp.maxBytes` budget derived from it. This
  project's memory audit tried exactly that, and found it completely blind: a deliberate leak of
  roughly 1.4 GB of `org.opencv.core.Mat` moved `totalBytes()` by zero bytes.

  The explanation is that those buffers are never allocated through a JavaCPP `Pointer` allocator at
  all. OpenCV's own JNI calls `cv::fastMalloc`, on the far side of a boundary JavaCPP has no
  visibility into. Its counter is accurate about the memory it allocated and silent about the memory
  that actually costs you the machine.

  So scalacv's leak suite measures process RSS instead --- `/proc/self/statm` field two on Linux,
  falling back to `Pointer.physicalBytes()` elsewhere --- runs a workload three hundred times after a
  warm-up, and asserts the growth stays under a bounded tolerance. The bound is deliberately not
  zero, because allocator arenas and the code cache mean RSS never returns exactly to baseline,
  while a per-iteration leak of even a modest `Mat` clears it easily over that many rounds. The
  suite owns its own JVM, because RSS is process-global and a concurrently running suite would
  contaminate it. That is a clumsier instrument than a counter, and the only one that sees the thing
  being measured.
]

#sect("Four commitments follow from this")

Everything else in the library is downstream of the argument above, and falls into four commitments.

#subsect("Typed everything")

OpenCV's Java API is a C++ API in translation, which means its parameters are `int`s carrying
enumerated meanings. `Imgproc.cvtColor(src, dst, 6)` is a legal call; the 6 is `COLOR_BGR2GRAY`. So
is `Imgproc.cvtColor(src, dst, 7)`, and so is every other integer you could type there, and the
compiler has no opinion about which one you meant. Nor do the families of constants occupy separate
spaces: the `int` that `cvtColor` reads as a colour conversion, the one `resize` reads as an
interpolation and the one `threshold` reads as a mode are the same type, so a constant lifted from
one family passes silently into another. scalacv replaces every one of those with a typed enum:
`ColorConversion.BgrToGray`, whose single job is to carry `Imgproc.COLOR_BGR2GRAY` in its `cvValue`,
so that you never type the number and never hand one family's constant to another.

#subsect("Explicit resource ownership")

`Managed[A]` is the ownership primitive, and it makes two guarantees, both of which exist because
getting them wrong is a JVM crash rather than an exception. Release is a compare-and-set, so a second
release is a no-op and never a double free. Access after release throws an ordinary
`IllegalStateException` on the Scala side, before anything crosses JNI.

#example("A released handle fails in Scala, not in C++.")[
```scala
import org.opencv.core.{CvType, Mat}

val m = Managed(Mat(4, 4, CvType.CV_8UC1))
m.close()
m.close()        // no-op: release happens exactly once
m.use(identity)  // throws IllegalStateException, not SIGSEGV
```
]

On top of that, the high-level `Image` adds move semantics: a transform #emph[consumes] the image it
was called on and returns a new one, so a chain of any length holds exactly one live `Mat` at a time.
The mistake this catches is the one people actually make --- branching off a source and then reusing
it:

#example("The wrong version: the source was already spent.")[
```scala
val img  = Image.blank(8, 8)
val gray = img.gray   // .gray moved the Mat out of `img`
img.width             // throws: `img` was spent by .gray
```
]

#example("The right version: branch off a copy, which is independent.")[
```scala
val src        = Image.blank(120, 80, Scalar.White)
val edgeBytes  = src.copy.gray.canny(50, 150).bytes(".png")  // works on a clone
val thumbBytes = src.resize(30, 20).bytes(".png")            // consumes `src`
```
]

Note what the type system does and does not do here. It does not reject the wrong version at compile
time; both listings compile. The guarantee is narrower and still worth having: the failure is a
Scala-side `IllegalStateException` naming the type and telling you to call `.copy`, thrown before any
address reaches JNI, rather than undefined behaviour in C++. The exception fires at the reuse, which
is rarely the interesting line. Start the JVM with `-Dscalacv.trackOwnership=true` and it carries, as
its cause, the stack of the transform that actually spent the handle. It is off by default because it
allocates a `Throwable` on every consume; the read happens only on the already-failing path, so a
correct program pays nothing for it.

#subsect("Genuinely headless")

The obvious way to bring OpenCV's natives up --- `Loader.load(classOf[opencv_java])` --- eagerly
initialises the whole preset graph, and `opencv_highgui` is GTK2-linked on Linux. On a machine
without GTK it throws, and takes `objdetect`, `calib3d`, `features2d` and `video` down with it. That
is precisely the set this library needs most. `OpenCv.load()` instead comes up through a GUI-free
preset, extracts the platform payload, and resolves the JNI shim's dependencies on demand rather than
loading them speculatively --- which turns out to matter for correctness and not only for tidiness,
as Chapter 2 shows. It needs no display server and no `apt-get` on any runner, and the whole test
suite runs that way.

#subsect("Errors as values where they belong, exceptions where they belong")

The library draws a deliberate line. Failures that are #emph[data-dependent and expected] --- a
missing file, undecodable bytes, a model that will not load, a calibration that will not converge ---
return `Either[CvError, A]`, with `CvError` a typed hierarchy you can match on: `DecodeFailed`,
`EncodeFailed`, `LoadFailed`, `CalibrationFailed` and `NativeCall`. `NativesMissing` sits outside
that list: natives that will not load, or a release bridge that will not open, are always thrown
rather than returned, because there is no program state in which continuing is the right move.
Failures that are #emph[programmer errors] --- an even Gaussian kernel, a negative radius, reusing a
consumed handle --- throw too, because they are bugs to fix rather than conditions to branch on. Modelling a use-after-move
as a `Left` would invite you to recover from it, and there is nothing to recover: the handle is gone.

#sect("What this library is not")

Three disclaimers, because each one saves you a wrong expectation.

It is *not a reimplementation of OpenCV*. There is no pixel loop in this repository that could have
been an OpenCV call. Every operation bottoms out in the same `Imgproc`, `Core` or `Calib3d` function
you would have called yourself, with the same numerics and the same results.

It is *not a wall in front of OpenCV*. The escape hatches are documented API, not accidents:
`image.mat` borrows the underlying handle, `image.managed` hands the whole ownership over,
`Image.wrap` takes it back, and a mid-level layer of extension methods on `Mat` gives you every
operation one tier down while still returning a `Managed[Mat]` you own --- and never writing to its
receiver, so it can be applied to a borrowed frame with no ceremony. When the high-level pipeline
does not cover your case, you drop a tier; you do not leave the library.

It is *not designed to be called from Java*. The API returns `Seq`, `Option` and `Either`, reaches
you through extension methods activated by `import scalacv.*`, and uses Scala 3 features throughout.
It wraps a Java library; it is not one.

#sect("The shape of it")

Here is the whole argument as code. It draws its own input, so it runs with no image file and no
display server --- which is how it is tested.

#example("One chain, from a blank canvas to encoded bytes.")[
```scala
import scalacv.*

OpenCv.load()

val edges: Either[CvError, Array[Byte]] =
  Image
    .blank(160, 120, Scalar.White)
    .drawRect(Rect(30, 30, 90, 60), Scalar.Black)
    .gray
    .canny(50, 150)
    .bytes(".png")
```
]

Count the `release()` calls: there are none, and none is missing. Every stage consumes the image it
was given and returns the next one, so by the time `canny` runs there is nothing left holding the
grey `Mat`, and `bytes` --- a terminal --- releases the last handle as it produces the array. The only
thing that survives the chain is plain data: a `Left` carrying a typed `CvError`, or a `byte[]` you
can keep forever.

When the input comes from a file rather than a constructor, `Image.reading` scopes it to a block and
releases it on success, on failure, and on exception:

```scala
Image.reading("photo.jpg") { img => img.gray.blur(2).canny(80, 160).write("edges.png") }
```

Chapter 4, #emph[The Image Type], unpacks the ownership model that makes the chain safe: which calls
borrow and which consume, what `copy` actually costs, and how to step down to the `Mat` tier when
`Image` does not reach far enough. Chapter 5, #emph[Lifetimes], goes underneath it to `Managed`,
`Releasable`, and `Managed.scope`, which owns the several native objects an operation such as
`solvePnP` needs at once.

#sect("Where this goes next")

The argument is made; what remains is a working process. Chapter 2, #emph[Setting Up], takes you
from an empty build file to a JVM that has loaded the natives --- the classifier for every supported
platform, the Mill, `sbt` and `scala-cli` spellings of the same lines, the optional `scalacv-vision`,
`scalacv-graphs` and `scalacv-zio` modules, what `OpenCv.load()` writes to disk the first time it
runs, and how to prove on your own machine that it worked.
