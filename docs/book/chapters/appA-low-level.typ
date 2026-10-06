#import "../lib/book.typ": *

#appendix(
  "Dropping to OpenCV Java",
  subtitle: [The map of what is underneath, and how to go down to it and come back without leaking.],
)

Sooner or later you will want a function this library does not have. `Image` carries 65 public
methods and the mid-level `Mat` extensions in `Ops.scala` --- with the photo effects in `Effects.scala` and
the deskew pipeline in `Deskew.scala` --- add 45 more, which between them cover the
operations people reach for repeatedly --- and that is a small fraction of what `imgproc`, `core`,
`photo`, `calib3d`, `features2d`, `video`, `dnn` and `objdetect` expose. The gap is not a defect waiting to be
closed. A wrapper that tried to cover all of OpenCV would be a worse wrapper, so the interesting
question was never "does scalacv have `distanceTransform`?" It is: what does it cost you to call the
one it does not have, and what do you have to know to not corrupt memory doing it?

The answer this library gives is that it wraps the official `org.opencv.*` Java API rather than
reimplementing it. There is no shadow binding, no private JNI layer, no second `Mat`. `Image` holds a
`Managed[Mat]`; the `Managed[Mat]` holds an `org.opencv.core.Mat` from the same jar the OpenCV
documentation describes. Anything in the OpenCV 4.13 Java reference is therefore reachable from
scalacv code without ceremony, without a build change, and without leaving the pipeline you are
already in.

What it does #emph[not] give you is the memory model for free. The moment a raw call hands you a bare
`Mat`, you are back in the world Chapter 5, _Lifetimes: Managed, Releasable, and Scope_, exists to
get you out of: an object whose forty on-heap bytes conceal megabytes the collector cannot see, with
no owner and no scope. This appendix is one instruction repeated in five settings --- wrap the thing
before the next line runs --- plus what you need to read a C++ signature and land on the right Java
overload.

#sect("Three altitudes")

There are three levels, and picking the right one #emph[per step] rather than per file is the skill.

#figure-table("What each level gives you, and what it costs.")[
#tbl(
  columns: (auto, auto, auto, 1fr),
  [*Level*], [*What you hold*], [*Who frees it*], [*The cost*],
  [High --- `Image`], [An owned image, chained by verbs], [The chain: every transform releases its receiver, every terminal releases the result], [Move semantics. You cannot use one `Image` twice without a `.copy`, and the knobs the verbs do not expose are not reachable from here.],
  [Mid --- `Managed[Mat]` and the `Mat` extension ops], [One owned Mat at a time], [`use`, `pipe`, `Managed.scope`, or you], [You thread the handles. Nothing stops you from dropping one on the floor except `pipe` being shorter to type than the alternative.],
  [Low --- raw `org.opencv.*`], [Whatever the Java binding hands back], [You, entirely], [Untyped `int` constants, output parameters, a `finalize()` that will not save you, and no guard between a spent pointer and a SIGSEGV.],
)
]

The levels are not sealed tiers you commit to at the top of a file. One pipeline can start on
`Image`, borrow the Mat for a single `Imgproc` call the library does not wrap, and rise back to
`Image` for the write. That round trip is the normal shape, and the rest of this appendix is those
moves, written out.

It is worth saying plainly what dropping a level does #emph[not] buy: speed. The obvious reason to go
low is to reuse destination buffers across frames instead of allocating one per stage, and that
optimisation was measured on this project's `gray → blur → canny` chain before it was designed.
Reusing three preallocated destinations against fresh allocation came out at −4% at 640×480, −0.8% at
3840×2160, and #strong[+0.4%] --- reuse #emph[slower] --- at 1920×1080. A result whose sign flips
across the sweep is not a result. Go low because a call is missing, not because you are hoping for
microseconds; Chapter 35, _Performance_, has the wins that are real.

#sect("Going down: `mat` borrows, `managed` hands over")

An `Image` owns exactly one `Managed[Mat]`, and there are three doorways between it and everything
below. Which one you want depends on a single question: are you #emph[borrowing] the Mat for the
length of an expression, or #emph[taking] it?

#figure-table("The three doorways, and who is on the hook afterwards.")[
#tbl(
  columns: (auto, auto, 1fr),
  [*Doorway*], [*Direction*], [*Ownership, and what you must do*],
  [`img.mat`], [Down, a borrow], [Stays with the `Image`. Read it, pass it to a detector, run an `Imgproc` function against it --- and do #strong[not] release it, because the `Image` will.],
  [`img.managed`], [Down, a handover], [Moves to the returned `Managed[Mat]`, and the `Image` is spent. Release the `Managed`, or `use` it.],
  [`Image.wrap(managed)`], [Up, a handover], [Takes a `Managed[Mat]`, not a bare `Mat`: wrap the raw pointer with `Managed(mat)` first. Ownership moves into the new `Image`, so do not also release the `Managed` yourself.],
)
]

`img.mat` is the one you want for almost every raw call. It is `handle.get` --- a borrow with the
spent-handle check in front of it, so using it after the `Image` has been consumed throws
`IllegalStateException` in Scala rather than segfaulting in native code.

#example("A raw `Core` call on a borrowed Mat.")[
```scala
val img = Image.blank(64, 64)

// org.opencv.core.Core.mean is not wrapped. Call it on the borrowed Mat.
val average = org.opencv.core.Core.mean(img.mat)

img.close() // this frees the Mat; `average` is already a plain cv.Scalar, safe to keep
```
]

The mistake to see once is the one where you decide to be tidy about the Mat you were lent:

#example("Wrong: releasing a Mat you only borrowed.")[
```scala
// WRONG — `mat` is a loan, not a gift.
val img = Image.blank(64, 64)
val average = org.opencv.core.Core.mean(img.mat)
img.mat.release()  // frees the buffer the Image still believes it owns
img.close()        // frees it again
```
]

#memory[
`img.mat` hands back the same pointer the `Image` holds, so releasing it yourself means the
`Image`'s own `close()` frees that pointer a second time. `Managed` cannot protect you here: its
compare-and-set guarantees that #emph[it] releases once, and you went around it. A double `delete` is
undefined behaviour that merely #emph[often] survives --- worse than a crash, because your test suite
passes. If the Mat must outlive the `Image`, take `img.managed`, or `img.copy.managed` for an
independent copy.
]

A subtler version of the same error catches people who have internalised move semantics but write
the borrow on one line:

#example("Wrong: a transform in the middle of a borrow.")[
```scala
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

// WRONG — `.gray` is a transform: it consumes `img` and returns a NEW Image
// that nothing here has a name for, and so nothing closes.
val corners = Mat()
Imgproc.cornerHarris(img.gray.mat, corners, 2, 3, 0.04)
img.close() // a no-op: `img` was already spent by `.gray`
```
]

Give the transform's result a name and close #emph[that]. `.mat` is a query and `.gray` is not, so
`img.gray.mat` spends one `Image` and abandons another; Chapter 4, _The Image Type_, has the full
account of which methods borrow and which consume.

#sect("The mid level, and the contract it writes down")

`Ops.scala` declares an ownership contract in its header comment, worth quoting because every
mid-level call in the library depends on it holding:

#quote(block: true)[
Every operation in this file is pure with respect to its receiver: it allocates a fresh destination
Mat, writes the result there, and hands that back as a `Managed[Mat]` that the caller now owns and
must release. The receiver is never written to, never released, and never aliased into the result ---
the two Mats have different `dataAddr()`s, so releasing one cannot invalidate the other.
]

Three consequences follow. An op can be applied to a #emph[borrowed] Mat with no transfer ceremony ---
a frame `Video.frames` reuses across iterations, a detector's input, `img.mat` --- because `cvtColor`
will not touch it. There are no in-place variants, so no call site has to be read twice to work out
whether it mutated something; the scaladoc commits to the fact that if one is ever added it will say
so in its name and return `Unit`. And an op that takes a #emph[second] Mat borrows that too:
`addWeighted(alpha, other, beta)`, `absdiff(other)`, `masked(mask)`, `inpaint(mask, radius)` and
`seamlessCloneInto(background, mask, center)` all leave their extra operand as they found it.

The corollary is the one leak the mid level makes easy to write. Every stage returns a Mat you own,
so a two-stage pipeline produces two, and the intermediate has to go somewhere:

#example("Wrong: `use` on a middle stage strands the result.")[
```scala
// WRONG — `use` frees the blur output, then hands back the canny result's bare
// Mat, stripped of the Managed that owned it. That Managed is now unreachable,
// so nothing will ever free the canny output.
val edges: Mat = src.gaussianBlur(Size(5, 5), 1.5).use(_.canny(50, 150).get)
```
]

`pipe` exists for exactly that shape. It is defined on `Managed[Mat]`, feeds the wrapped Mat to the
next stage, and releases it in a `finally` once that stage has produced its own output --- so the
intermediate can neither be leaked nor used after the chain has moved on, even if the stage throws.

Dropping the `.get` --- `use(_.canny(50, 150))` --- is in fact correct: the canny `Managed` travels
out of the block intact and the type of `edges` becomes `Managed[Mat]` rather than `Mat`. That is the
uncomfortable part. The safe version and the leaking version differ by four characters and the
compiler accepts both, so the shape worth writing is the one that has a name.

#example("Right: `pipe` between Mat stages, `use` at the terminal.")[
```scala
import org.opencv.core.Core

val edgePixels: Either[CvError, Int] =
  Image.reading("photo.jpg") { img =>
    img.mat.cvtColor(ColorConversion.BgrToGray)  // mid-level extension -> Managed[Mat]
       .pipe(_.gaussianBlur(Size(5, 5)))
       .pipe(_.canny(80, 160))
       .use(Core.countNonZero(_))                // terminal: an Int, so `use`, not `pipe`
  }
```
]

The division of labour is mechanical: `pipe` when the next stage returns another `Managed[Mat]`,
`Managed.use` when it returns anything else --- `Core.countNonZero` for a count, `findContours` for a
`Seq[Contour]`, `Images.encode` for a byte array. Watch the return type on that last one: `encode`
hands back its own `Either[CvError, Array[Byte]]`, so putting it inside `Image.reading` leaves you
holding two nested `Either`s to flatten, which is usually a sign the encode belongs outside the
block. `Mats.chain` is the n-stage form, a fold of `pipe`. It #emph[borrows] its source and never
releases it, which is what lets you hand it a frame you do not own.

#example("`Mats.chain` on a borrowed frame.")[
```scala
Mats.chain(frame)(
  _.cvtColor(ColorConversion.BgrToGray),
  _.gaussianBlur(Size(5, 5), 1.5),
  _.canny(50, 150)
)
```
]

Not everything at this level returns a `Managed[Mat]`. The `Mat` extensions in `Draw.scala` return
`Unit` and mutate the receiver, as the naming rule promises; the ones in `Contours.scala` and
`Hough.scala` return plain immutable Scala data and free every native object they allocated on the
way. Appendix B, _Operations Reference_, lists which is which.

#note[
`Mats.chain` is the only public member of `Mats`. `produce`, `grayscale`, `column` and the rest are
`private[scalacv]`: they are where the ownership contract is enforced, in one place, and exposing
them would expose the ability to break it.
]

#sect("Coming back up")

A raw call hands you a bare `Mat`, and from that instant it is a native allocation with no owner. Give
it one before anything else happens: `Managed(mat)` at the mid level, `Image.wrap(Managed(mat))` at
the high level, which hands the chaining API back with it.

#example("Adopting a raw result, at both levels.")[
```scala
import org.opencv.core.{CvType, Mat}
import org.opencv.imgproc.Imgproc

val raw = Mat()
Imgproc.Laplacian(src.mat, raw, CvType.CV_16S) // a borrowed Mat in, a Mat you own out

val owned: Managed[Mat] = Managed(raw)         // freed exactly once, from here on
val encoded = Image.wrap(owned).bytes(".png")  // ...or lift it and keep chaining
```
]

Ownership transfers #emph[into] the `Image`, so do not also release the `Managed`; `bytes` is a
terminal and releases it for you. `img.managed` is the same handover run backwards.

#caution[
Never leave a raw `Mat` unwrapped past the next line. If an exception fires between the allocation
and the wrap --- and the native call is the likeliest thing to throw --- the buffer leaks, and
nothing will reclaim it in a timeframe that matters. The library holds itself to this: `Mats.produce`
releases its half-built destination before rethrowing, so a failed operation leaks nothing.
]

#sect("The full recipe for an unwrapped call")

Calling something OpenCV has but scalacv does not is five steps:

+ Borrow the input Mat with `img.mat`, or produce it with mid-level ops.
+ Open a `Managed.scope` for the temporaries. Anything the call needs and nothing hands back ---
  intermediate images, kernels, output vectors --- is registered with `own` the moment it is
  constructed, so a throw anywhere releases everything acquired so far, in reverse order.
+ Allocate the destination and make the raw call inside `Cv.orThrow("name")`, so a `CvException`
  arrives as a `CvError.NativeCall` naming the operation instead of as an opaque C++ message.
  Chapter 6, _The Error Model_, has the rest.
+ Return #strong[plain data] from the scope, or a value allocated outside it. Nothing the scope owns
  may escape --- the same rule `use` carries, applied to a group.
+ Wrap anything going onward in `Managed` or `Image.wrap`.

Here is all five on a call the rest of this book never uses: `Imgproc.distanceTransform`, which
replaces every non-zero pixel of a binary image with its distance to the nearest zero pixel --- the
standard way to find the thickest part of a blob. Its input must be a single-channel 8-bit mask.

Transliterated straight from the C++ tutorial, it has three independent defects:

#example("Wrong: three defects, none of which the compiler sees.")[
```scala
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

// WRONG.
val src = Image.read("shapes.png").toOption.get
val grey = Mat()
Imgproc.cvtColor(src.mat, grey, Imgproc.COLOR_BGR2GRAY)
val bin = Mat()
Imgproc.threshold(grey, bin, 128, 255, Imgproc.THRESH_BINARY)
val dist = Mat()
Imgproc.distanceTransform(bin, dist, Imgproc.DIST_L2, 3)
Image.wrap(Managed(dist)).write("dist.png")
src.mat.release()
```
]

`grey` and `bin` are never freed --- two full-frame buffers per call, unbounded across a loop.
`src.mat.release()` frees a Mat the `Image` still owns, and `src` is never closed. And `dist` comes
back `CV_32F`, holding distances in pixels rather than an image: a blob twenty pixels thick peaks at
ten, not at 255, so half the job --- turning the measurement into something displayable --- has not
happened. `Image.toBufferedImage` rejects a Mat in that state and `colorMap` aborts in native code on
it. The missing step is `normalize`, and the reason it is easy to forget is that no signature here
mentions it.

#example("Right: scoped temporaries, a named native call, plain data out.")[
```scala
import org.opencv.core.{Core, Mat}
import org.opencv.imgproc.Imgproc

val thickest: Either[CvError, Double] =
  Image.reading("shapes.png") { src =>
    Managed.scope: own =>
      val grey = own.adopt(src.mat.cvtColor(ColorConversion.BgrToGray))
      val bin = own.adopt(grey.threshold(128)._1)
      val dist = own(Mat())
      Cv.orThrow("distanceTransform"):
        Imgproc.distanceTransform(bin, dist, Imgproc.DIST_L2, Imgproc.DIST_MASK_5)
      Core.minMaxLoc(dist).maxVal // a Double — plain data, safe to leave the scope
  }
```
]

Two details carry the weight. `own(...)` is for a bare object you constructed; `own.adopt(...)` is for
one that arrives already wrapped, and it takes over that handle rather than building a second one
around the same pointer. And the value leaving the block is a `Double`, read out of native memory
before the scope closes over it.

To keep the distance map itself, the escaping value has to be allocated outside the scope's
ownership --- which a mid-level op does for free, because it hands back a `Managed` the scope was
never told about:

#example("An owned result escaping a scope, legitimately.")[
```scala
// Same imports and the same `src` as the listing above.
val map: Managed[Mat] =
  Managed.scope: own =>
    val grey = own.adopt(src.mat.cvtColor(ColorConversion.BgrToGray))
    val bin = own.adopt(grey.threshold(128)._1)
    val dist = own(Mat())
    Cv.orThrow("distanceTransform"):
      Imgproc.distanceTransform(bin, dist, Imgproc.DIST_L2, Imgproc.DIST_MASK_5)
    // Allocated by a mid-level op, so NOT one of the scope's objects: it may escape.
    dist.normalize().pipe(_.colorMap(Colormap.Jet))
```
]

`normalize` defaults its `depth` to `OutputDepth.Unsigned8` rather than to OpenCV's own `dtype = -1`,
and that default is doing real work here: with `-1` a `CV_32F` input rescaled to `[0, 255]` comes back
`CV_32F` holding the values 0 to 255, which is the same trap the previous listing fell into wearing a
different hat. `OutputDepth.SameAsSource` is there for the case that genuinely wants it --- rescaling
a float image into `[0, 1]` for a model input, where 8 bits would collapse the range onto 256 levels.
Departing from the C++ default like this is the kind of judgement you give up by going low; the
reasoning behind each such departure is in Appendix C, _Enums and Types Reference_.

#warning[
`Point`, `Size`, `Rect` and `Scalar` are Scala case classes copied across the boundary, and their
`toCv` and `from` conversions are `private[scalacv]` --- your code cannot call them. When a raw
signature wants an `org.opencv.core.Rect`, construct one directly, `org.opencv.core.Rect(r.x, r.y,
r.width, r.height)`, and convert the result back by hand. The copying is deliberate: OpenCV's
geometry types are mutable Java objects with public fields, so a `Seq[cv.Rect]` from a detector is a
set of live handles whose contents can change underneath you.
]

#sect("Reading the C++ documentation, landing in Java")

Every reference page you will search for is a C++ page. Three translation rules get you from there to
the Java binding, mechanically enough to apply without checking.

#minor("Namespaces become classes, and capitalisation is preserved exactly")

`cv::GaussianBlur` is `Imgproc.GaussianBlur` --- capital G and all. `cv::cvtColor` is
`Imgproc.cvtColor`, lower case, because that is how C++ spells it. The binding generator does not
normalise, so `Imgproc.Canny`, `Imgproc.Sobel`, `Imgproc.Laplacian` and `Core.LUT` sit next to
`Imgproc.resize`, `Core.mean` and `Core.normalize`, and `Core.bitwise_not` keeps its underscore. The
module a function lives in decides its class: `imgproc` → `Imgproc`, `core` → `Core`, `photo` →
`Photo`, `calib3d` → `Calib3d`, `dnn` → `Dnn`, `objdetect` → `Objdetect`. If a call is not where you
expect it, it is almost always because you guessed the module rather than the name.

#minor("Outputs are parameters, not return values")

A C++ signature like `void GaussianBlur(InputArray src, OutputArray dst, Size ksize, double sigmaX)`
becomes a Java method returning `void` and taking a `Mat dst` you allocated. `InputArray` means
borrowed; `OutputArray` means "I will `create()` this to the size and type I need, then fill it";
`InputOutputArray` means it reads #emph[and] writes the same Mat, which is the one shape to be careful
of, because it is a mutation the type system does not show you. A function that has genuinely more
than one output --- `distanceTransformWithLabels`, `solvePnP` --- takes one `Mat` per output, which is
precisely the case `Managed.scope` was written for.

#minor("Constants are bare `int`s on the module class")

`Imgproc.COLOR_BGR2GRAY`, `Imgproc.THRESH_BINARY`, `Core.NORM_MINMAX`, `Photo.INPAINT_TELEA`,
`Imgproc.DIST_L2`. They are `public static final int`, they live on the same class as the functions
that consume them, and --- this is the whole reason Chapter 8, _Typed Constants and Colour Spaces_,
exists --- nothing stops you passing a border type where an interpolation was wanted. Both are ints;
both compile. When you come back up, come back to the enums.

#sidebar("The overload that does not exist")[
C++ default arguments do not survive the binding as defaults. `Imgproc.threshold` is a single
five-argument overload with none at all --- which is why the library's own `threshold` extension
spells out all five rather than layering Scala defaults over Java ones that are not there.

Where C++ #emph[does] have defaults, the generator emits one overload per trailing prefix, and the
one you want may be two arities up from what autocomplete offers first. `Imgproc.arrowedLine` is the
sharp case: only its eight-argument overload reaches `tipLength`, and the seventh parameter you must
pass to get there is `shift`, which almost nothing wants --- the library's `drawArrow` passes `0` for
it and says so in a comment, because a bare `0` in the middle of a call is otherwise unreadable.
Count the C++ parameters, subtract the defaults you are happy with, and pick that arity.
]

#sect("Mat basics, for when you must")

A `Mat` is a header plus a reference-counted pixel buffer. Two numbers describe its shape and one
describes its element type, and the spellings are not the ones you expect.

#minor("Type codes")

A type code is `CV_<bits><U|S|F>C<channels>`: `CV_8UC3` is eight-bit unsigned, three channels, the
default BGR image. The #emph[depth] is the type without the channel count, which is why `CvType.depth`
and `CvType.channels` are separate calls over the code `Mat`'s `type()` method returns --- and why
the library checks depth rather than type when it wants "any 8-bit image". (`type` is a Scala
keyword, so that call is spelled with backticks around the method name.) `CvType.typeToString` turns
a code into something readable, and it is what the library's own error messages print rather than a
bare integer.

#figure-table("The type codes that appear in this library, and where.")[
#tbl(
  columns: (auto, auto, 1fr),
  [*Code*], [*Element*], [*Where you meet it*],
  [`CV_8UC3`], [8-bit unsigned ×3], [The default image. `Image.blank(w, h)` and every colour read.],
  [`CV_8UC1`], [8-bit unsigned ×1], [Greyscale, and every binary mask: the output of `canny`, `inRange` and `threshold`, and what `findContours` wants (it also accepts `CV_32SC1`) and `houghLines` insists on.],
  [`CV_8UC4`], [8-bit unsigned ×4], [BGRA. `Image.blank(w, h, channels = 4)`; flattened to BGR on the way to AWT.],
  [`CV_16S`], [16-bit signed], [`OutputDepth.Signed16` --- a `sobel` that keeps its negative lobe instead of clipping it to zero.],
  [`CV_32F`], [32-bit float ×1], [Distance and disparity maps, unnormalised filter responses, a `MatOfPoint2f` converted for `minAreaRect`.],
  [`CV_64F`], [64-bit float ×1], [Small solver matrices --- a camera matrix, a rotation, a translation.],
)
]

#minor("Rows and columns, width and height")

`Mat` is row-major and constructed row-count first, `Mat(rows, cols, type)`; `Size` is constructed
extent-first, `Size(width, height)`. A 640×480 image is therefore `Mat(480, 640, CV_8UC3)` and
`Size(640, 480)`, and the two sit next to each other in real code. `Image` papers over this ---
`width` is `cols`, `height` is `rows`, and `Image.blank(width, height, …)` builds `Mat(height, width,
…)` --- but the moment you allocate a Mat yourself the order is yours to get right, and getting it
wrong on a non-square image produces something OpenCV processes happily and you do not recognise.

#minor("Submatrices alias their parent")

`m.submat(rect)` does not copy. Neither do `rowRange`, `colRange`, `row` or `col`. They return a new
`Mat` header pointing into the parent's buffer, which is the single most useful and most dangerous
fact about the type: writing through a view writes through to the parent, and reading a view after
the parent's buffer is gone reads freed memory.

#memory[
A view and its parent share one allocation. A view that outlives its parent is a read of freed
memory, with no guard anywhere to catch it, because the view's handle looks perfectly alive. If a
region has to survive the image it came from, `clone()` it. That is exactly what `Image.crop` does:
it takes the `submat`, clones it, and releases the view before the parent, so the caller gets an
independent copy and no header is stranded --- and the `Managed.use` around the view makes that hold
even when `clone` throws.
]

Aliasing has a second consequence. A submat is usually not #emph[continuous] --- its rows are strided
across the parent's wider ones --- while a bulk `Mat.get(0, 0, bytes)` reads a contiguous run.
`toBufferedImage` checks `isContinuous` and clones only when it is false; do the same in your own
bulk reads.

#sect("Where the Java bindings are wrong, and what the library does about it")

Some of what this library does is not convenience. It is repair.

The largest item is the one Chapter 5 is built around: of the 188 `org.opencv.*` types that own
native memory, exactly three --- `Mat`, `VideoCapture` and `VideoWriter` --- expose a public
`release()`. `CascadeClassifier`, `Net`, `QRCodeDetector`, `FaceDetectorYN`, `KalmanFilter` and 180
others expose only a `private static native void delete(long)` and an unconditional `finalize()`.
`Releasable.nativeHandle` reads the binding's own `nativeObj` field reflectively, #strong[disarms the
finalizer first] by zeroing it, and only then calls `delete(long)` through a cached `MethodHandle`.
The order is not fussiness: a finalizer that later runs against a pointer you already freed is a
double free, and this project found that one as a `SIGSEGV` inside
`Java_org_opencv_objdetect_FaceDetectorYN_delete` on the JVM's own `Finalizer` thread. Measured, 4000
leaked `KalmanFilter`s reached 54 GB against 86 MB released.

The second item is a leak the library documents rather than fixes. The generated binding for
`polylines`, `fillPoly` and `drawContours` runs its input through
`Converters.vector_vector_Point_to_Mat`, which allocates one `Mat` per polygon plus one for the outer
vector and releases none of them. The library's own `withPolygons` helper --- file-private in
`Draw.scala`, and the single place those polygons are built --- frees the `MatOfPoint`s #emph[it]
allocated, in a `finally`, and its scaladoc states flatly that this does not make the call
leak-free: the residue is upstream in the official Java API and cannot be reached from here without
reimplementing the converter. It is bounded per call and unbounded across a video
loop --- Chapter 14, _Drawing and Annotation_, has the mitigation.

The rest are smaller divergences worth knowing before you trust a C++ page:

#figure-table("Places the Java binding does not behave like the C++ documentation.")[
#tbl(
  columns: (auto, 1fr),
  [*Call*], [*The divergence, and what the library does*],
  [`findContours`], [The C++ version modifies its input; the Java binding copies internally, so it does not. The library still treats a thresholded image as consumed, because the habit is cheaper than the exception.],
  [`imread` / `imwrite`], [`imread` reports a missing file, a directory, an empty file and undecodable bytes identically, as a `Mat` whose `empty()` is true; `imwrite` collapses the same range onto a bare `false`. `Images.read` and `Images.write` call neither: the JVM reads and writes the bytes and OpenCV is left only the codec work. That sidesteps the `GetStringUTFChars` narrowing that breaks every non-ASCII path on Windows, and as a side effect lets `CvError` name which failure happened.],
  [`FaceDetectorYN.detect`], [Returns an `int` status flag --- `1` for "the network ran" --- not a face count. Read as a count it reports one face per successful call. No faces gives a 0×0 Mat, not an N×15 Mat with zero rows. Chapter 24, _Face Detection_, has all four traps.],
  [`Imgproc.Sobel` with `ddepth = -1`], [On an 8-bit source every negative derivative clips to zero and half of each edge silently disappears. `OutputDepth` exists so the default cannot be chosen by accident.],
  [Geometry types], [`cv.Rect`, `cv.Point` and friends are mutable objects with public fields, handed back live from detectors. The library copies them into immutable case classes at the boundary.],
)
]

#tip[
When a detector you manage yourself refuses to free, the thrown `CvError.NativesMissing` names the
exact `--add-opens` flag, computed from the offending class's own module and package rather than
guessed --- typically `--add-opens org.bytedeco.opencv/org.opencv.objdetect=ALL-UNNAMED`. The module
is `org.bytedeco.opencv` and the package is `org.opencv.objdetect`, which do not share a prefix; that
is the pair people get wrong, and the reason the message computes it rather than asking you to. It is
never `java.base/java.lang` --- the field being opened is OpenCV's own `nativeObj`, not a JDK
internal. Chapter 42, _Troubleshooting_, has the fix.
]

#sect("When to stay high")

The rule is short: if `Image` has it, use `Image`. Not because the lower levels are dangerous in some
vague way, but because each level down moves one guarantee from the library to you --- release
exactly once, then the typed constant, then the ownership of every temporary --- and those
obligations are worth collecting one at a time, at the one step that needs them, rather than for a
whole file.

So drop exactly one level, at exactly the step where the level above does not expose the knob you
need, and come straight back up. `mat` borrows, `Managed` adopts, `Image.wrap` lifts. Reaching for
`org.opencv.*` is a normal thing to do rather than an admission that the wrapper failed.

Appendix B, _Operations Reference_, is the other half of this map: every wrapped operation, at which
level it lives, what it consumes and what it hands back --- so that before you write a raw call you
can check whether the call is already there.
