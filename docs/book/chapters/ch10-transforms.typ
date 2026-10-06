#import "../lib/book.typ": *

#chapter("Geometric Transforms", subtitle: [Every pixel that moves is a pixel you invented, and a buffer you now own.])

A phone photograph of a delivery note arrives at 4032×3024, held at arm's length, tilted about four
degrees off upright, and shaped like a trapezium because the camera was not parallel to the desk.
Nothing downstream wants that. The OCR engine wants a flat, upright, roughly 1200×1600 greyscale
rectangle with the page filling the frame. Between the two lies this chapter: resize, crop, flip,
rotate, warp. It is the least glamorous part of a vision pipeline and the part that most often
decides whether the clever part works.

Moving pixels is not free the way copying bytes is free. A geometric transform asks, for every pixel
in the destination, where that pixel came from in the source --- and the answer is almost never a
whole pixel. It is 41.7% of the way between two columns and 0.3 of the way down two rows. Everything
in this chapter except the quarter-turns and the axis-aligned crop therefore #emph[invents] values
that were never in the input. The interpolation you choose is a choice about what it invents, and
the wrong choice is not a crash; it is a slow, invisible loss of the detail your detector was going
to key on.

The second cost is the one this book keeps returning to. A transform does not modify a `Mat`; it
allocates a second one and fills it. Rotating a 12-megapixel BGR frame allocates a destination of at
least 36 MB, and the source's 36 MB is still resident until somebody frees it. A six-stage chain
written the obvious way holds six buffers at once and offers, per buffer, about forty bytes of
on-heap header as the only evidence to the collector that any of them exist.

scalacv answers that with move semantics. Every transform on `Image` spends the image it was called
on and hands back a new one, so a chain of any length holds exactly one live `Mat`; the mid-level
`Managed[Mat]` extension ops make the same guarantee explicit with `pipe` and `Mats.chain`. Both
levels appear in this chapter, because a document-flattening pipeline needs one operation --- a
perspective warp --- that `Image` does not wrap, and reaching under the high-level API without
leaking is a skill this chapter is a good place to learn.

#sect("Resize: two modes, and why they are two methods")

The docket has to get smaller before anything else happens, both because 12 megapixels is more page
than any detector needs and because every subsequent stage costs proportionally less on a smaller
frame. There are two ways to say how much smaller, and scalacv keeps them apart.

`resize(width, height)` and `resizeTo(size, interpolation)` name an absolute destination in pixels.
`scale(factor, interpolation)` multiplies both sides by one number. At the mid level the same split
is `resize(size, interpolation)` and `scaled(fx, fy, interpolation)`, where `scaled` takes
independent factors for the two axes.

Note which of those five takes no filter. `Image.resize(width, height)` is a two-integer convenience
that forwards to `resizeTo` with the default `Interpolation.Linear`, and there is no overload that
adds a filter to the integer pair. When the filter matters --- and the next subsection argues that
on a downscale it always does --- the call to reach for is `resizeTo(Size(w, h), Interpolation.Area)`
rather than `resize(w, h)`.

#example("Absolute size, uniform factor, and independent factors.")[
```scala
import scalacv.*

OpenCv.load()

Image.reading("docket.jpg") { full =>
  full.copy.resize(1200, 1600).close()                              // absolute
  full.copy.resizeTo(Size(1200, 1600), Interpolation.Area).close()  // absolute, chosen filter
  full.copy.scale(0.25, Interpolation.Area).close()                 // uniform factor
  full.mat.scaled(fx = 0.25, fy = 0.5).use(_.rows)                  // mid-level, per-axis
}
```
]

They are two methods rather than one overload because OpenCV distinguishes the modes by passing
`Size(0, 0)` as a sentinel, and a sentinel size has no business in a typed API. Keeping them apart
also lets each one validate what it actually receives, and the two need different validation ---
which turns out to be more interesting than it sounds.

#sidebar("Two roundings, on purpose")[
`resize` truncates. A `Size` carries two `Double` extents and OpenCV truncates them toward zero on
the way into native code, so `Size(1.9, 1.9)` asks for a 1×1 image. `scaled` rounds --- and rounds
half to #emph[even], because OpenCV derives the destination as `cvRound(cols * fx)` × `cvRound(rows
* fy)` and `cvRound` is a banker's rounding.

The library validates each one the way its own native path behaves: `resize` checks the truncated
integers, `scaled` uses `math.rint` against the receiver's extent. That asymmetry is deliberate and
documented in the source. A single shared rule would be wrong for one of them: on a 100-pixel-wide
source, `fx = 0.006` legitimately yields a 1-pixel result that truncation would reject, and
`fx = 0.025` yields 2 where `math.round` insists on 3.

The payoff is the error you get when a computed target collapses. `Size(width * 0.004, height *
0.004)` on a small sprite is positive as a `Double` and empty as a `cv::Size`; without the check,
OpenCV aborts with `CV_Assert(inv_scale_x > 0)` and hands you a `CvError.NativeCall` quoting a C++
expression. With it you get an `IllegalArgumentException` naming your own argument.
]

#subsect("Choosing an interpolation")

`Interpolation` is a true enumeration with five cases, and its `.cvValue` is the raw `Imgproc.INTER_*`
constant if you ever need to hand it to a call scalacv does not wrap. The default everywhere in the
library is `Interpolation.Linear`, which is the right default and the wrong choice at both ends of
the range.

#figure-table("When each interpolation is the right one.")[
#tbl(
  columns: (auto, 1.1fr, 1.4fr),
  [`Interpolation`], [Reach for it when], [What it actually does],
  [`Nearest`], [Label maps, binary masks, index images], [Copies the closest source pixel. Blocky, but exact values --- 0 and 255, a class id --- survive instead of being averaged into something that is neither],
  [`Linear`], [General-purpose, either direction], [The default. A weighted average of four neighbours; fast, and good enough that most pipelines never change it],
  [`Cubic`], [Enlarging], [Sixteen neighbours and a smoother reconstruction. Keeps edges crisp where `Linear` softens them. Slower],
  [`Area`], [Shrinking], [Averages every source pixel that falls into the destination pixel. This is the one that matters: it is the only filter that looks at all the data it is throwing away],
  [`Lanczos4`], [Enlarging, when quality beats throughput], [An eight-tap windowed sinc. The sharpest option and the slowest],
)
]

The rule worth memorising is the one about `Area`. Shrink with `Linear` and each destination pixel is
built from four source pixels, but at a factor of 0.25 each destination pixel stands for a 4×4 block
of the source --- so twelve of every sixteen source pixels are never read at all. The result is
aliasing: fine repeating texture --- the ruling on a form, a halftone
screen, the weave of a fabric --- folds into moiré patterns that were not in the scene and that no
downstream stage can tell from real structure. `Area` averages the whole footprint instead, so the
detail is discarded as an average rather than as a coin flip.

#tip[Shrink with `Interpolation.Area`, enlarge with `Interpolation.Cubic` or `Interpolation.Lanczos4`,
and use `Interpolation.Nearest` for anything whose pixel values are labels rather than light.]

So the first line of the docket pipeline is a downscale with `Area`, not the default:

#example("The first stage: a working copy at a quarter size.")[
```scala
val working = full.copy.scale(0.25, Interpolation.Area)   // 4032x3024 -> 1008x756
```
]

#memory[`full.copy` allocates a second 36 MB buffer, and `scale` allocates a third --- 2.3 MB, since
each side is quartered. The copy is spent by `scale` and freed immediately, so two buffers survive
the line: `full`, which `Image.reading` will close, and `working`, which is now yours. Without the `.copy` you would not have needed the second
allocation at all --- but you would also have spent `full`, and the whole point of the pipeline below
is that the full-resolution original is still there at the end.]

#sect("Crop, and the check OpenCV does not perform")

`crop(rect)` cuts an axis-aligned region out of the image. The `Rect` is `Rect(x, y, width, height)`
--- corner first, then extent, which is a different shape from `Point` plus `Size` and trips
everybody up exactly once.

Two things happen before OpenCV sees anything. First, the rectangle is checked against the image:

```scala
require(
  rect.x >= 0 && rect.y >= 0 && rect.x + rect.width <= width && rect.y + rect.height <= height,
  s"crop $rect does not fit inside ${width}x$height"
)
```

This matters more than it looks. A `Rect` that runs off the edge is not a rare accident; it is the
normal output of padding a detector's bounding box by twenty pixels for context, and OpenCV's
`submat` responds to it by throwing from native code with a message about a region that does not fit
inside a matrix, naming neither of the values you passed. The `require` fails in Scala, before JNI,
quoting your rectangle and the image it did not fit.

Second, the crop is copied. `submat` gives an aliasing view onto the parent's pixel data --- a new
`Mat` header pointing into the same buffer --- and scalacv clones it, releases the view, and hands
back an independent image.

#memory[An aliasing `submat` view outlives nothing. Release the parent and the view is a live handle
into freed memory, which is a SIGSEGV with no Scala frame in it. `crop` clones so the result is a
real image you can keep, put in a `Map`, or send to another thread after the source is long gone.
That is the same reasoning behind `Rect` and `Point` being ordinary case classes: everything that
crosses the boundary out of native memory is copied, so nothing you hold is secretly a pointer.]

#sect("The exact turns")

Three operations move pixels without inventing any. `flip(how)` mirrors, and `Flip` is named for the
visible effect rather than for OpenCV's flip code: `Flip.Horizontal` mirrors left-to-right,
`Flip.Vertical` top-to-bottom, `Flip.Both` does both, which is a 180° point reflection. `rotate` with
a `Rotation` does a quarter or half turn as a pure pixel shuffle.

#figure-table("The lossless rotations.")[
#tbl(
  columns: (auto, auto, 1fr),
  [`Rotation`], [Angle], [Effect on the frame],
  [`Clockwise`], [90° clockwise], [Width and height swap],
  [`CounterClockwise`], [90° anticlockwise], [Width and height swap],
  [`Half`], [180°], [Unchanged],
)
]

Nothing is resampled, so these are exact and they compose: four `Clockwise` turns return the original
bytes. They are also the cheapest fix for a camera mounted sideways. Use them whenever the angle is
a multiple of 90, and reach for the arbitrary rotation below only when it is not.

#sect("Arbitrary rotation, and the canvas that has to grow")

The docket is tilted four degrees. Correcting that is an arbitrary-angle rotation, which OpenCV
expresses as a 2×3 affine matrix fed to `warpAffine`. Written directly, the obvious version is wrong:

#example("Wrong: the corners fall off the edge.")[
```scala
import org.opencv.core as cv
import org.opencv.imgproc.Imgproc
import scalacv.*

/** Rotates about the centre, into a destination the same size as the source. */
def clipped(src: Image, degrees: Double): Image =
  val w = src.width
  val h = src.height
  Managed.scope: own =>
    val m = own(Imgproc.getRotationMatrix2D(cv.Point(w / 2.0, h / 2.0), degrees, 1.0))
    val dst = Managed(cv.Mat())
    Imgproc.warpAffine(src.mat, dst.get, m, cv.Size(w.toDouble, h.toDouble))
    Image.wrap(dst)
```
]

Rotate a rectangle inside a frame of its own size and the four corners leave the frame. At 30° on a
160×120 image the rotated rectangle keeps about 83% of its area inside the old frame, so a sixth of
the picture is gone --- and it is gone from the corners, which is exactly where the page edges you
were about to detect live. The fix is arithmetic, not a flag: the destination has to be the
axis-aligned bounding box of the rotated rectangle, and the matrix's translation column has to be
shifted so the rotated image lands inside it.

For a source `w` wide and `h` high, rotated by an angle whose matrix carries `cos` and `sin` in its
first row, the enlarged canvas is

```text
newW = round(h * abs(sin) + w * abs(cos))
newH = round(h * abs(cos) + w * abs(sin))
```

and the third column of each row gains `(newW - w) / 2` and `(newH - h) / 2` respectively --- a
recentring, so the rotation about the old centre ends up about the new one. For the 160×120 frame at
30° that is a 199×184 destination --- nearly twice the pixel count of the 160×120 source, which is
the price of not losing the corners.

You do not have to write that. `Image.rotate(degrees, scale)` and the mid-level `rotated` do it for
you, which is why the high-level rotation never clips:

#example("Right: the canvas grows to fit.")[
```scala
val tilted = Image.blank(160, 120).rotate(30)                // 199x184, nothing clipped
val zoomed = Image.blank(160, 120).rotate(30, scale = 0.5)   // turn and halve in one warp

// Mid-level, on a borrowed Mat: a Managed[Mat] with the knobs Image does not surface.
val spun = frame.mat.rotated(
  degrees = 15,
  scale = 1.0,
  interpolation = Interpolation.Cubic,
  border = BorderType.Constant,
  color = Scalar.White
)
```
]

`Image.rotate` surfaces two of `rotated`'s five parameters; the other three --- `interpolation`,
`border` and `color` --- are why the mid-level call still earns its place. Note the default rather
than the name: the fill defaults to `BorderType.Constant` here and on `pad`/`border` (a geometric
transform exposes never-seen pixels, where reflecting image content would smear it into the border),
while the *filters* (`gaussianBlur`, `sobel`, ...) default to `Reflect101`, which keeps the kernel's
support inside real image content and avoids edge ringing.

The `scale` argument is not a convenience wrapper around a separate resize --- it goes into the same
matrix, so a rotate-and-shrink resamples once instead of twice. One resampling is always better than
two.

#warning[`Image.rotate(degrees)` measures the angle #emph[counter-clockwise], which is OpenCV's
`getRotationMatrix2D` convention, so `rotate(90.0)` and `rotate(Rotation.CounterClockwise)` agree.
`Picture.rotate(degrees, about)` in the `scalacv-graphs` scene graph measures the same angle
#emph[clockwise], because it works in screen coordinates where y points down and it turns vector
geometry rather than pixels. The two are documented and both
are correct for what they transform; they are not interchangeable.]

#sect("Border modes: what fills the corners")

A rotated rectangle inside its bounding box leaves four triangular regions that correspond to no
source pixel at all, and something has to go in them. That something is a `BorderType`, and the same
enum controls what `pad` and `border` put in the margin they add. It has five cases; OpenCV's sixth
constant, `BORDER_ISOLATED`, is deliberately absent, because it is a modifier that only changes the
behaviour of region-of-interest calls scalacv does not yet expose, and an enum case that is silently
ignored is worse than one that is missing.

#figure-table("Border modes, and where each one belongs.")[
#tbl(
  columns: (auto, 1.1fr, 1.3fr),
  [`BorderType`], [Fills with], [Use it for],
  [`Constant`], [The `color` you pass; `Scalar.Black` by default], [A visible frame, a letterbox, or a white page background under a deskew],
  [`Replicate`], [The nearest edge pixel, repeated outward], [Padding before a filter, so the filter does not see a fake edge],
  [`Reflect`], [A mirror of the edge, including the edge pixel], [Seamless tiling],
  [`Reflect101`], [A mirror excluding the edge pixel], [OpenCV's own default for filter borders],
  [`Wrap`], [Pixels from the opposite side], [Genuinely periodic images --- and only via `pad`, `border` or `rotated`],
)
]

#example("Padding a page with white instead of black.")[
```scala
Image.reading("docket.jpg") { img =>
  img.pad(24, color = Scalar.White).write("matted.png")           // uniform margin
}

Image.reading("docket.jpg") { img =>
  img.border(top = 4, bottom = 4, left = 20, right = 20,
             borderType = BorderType.Replicate).write("framed.png")
}
```
]

`Wrap` is the one value in the enum that is not universally accepted. `copyMakeBorder` --- behind
`pad` and `border` --- honours it, and so does `warpAffine` behind `rotated`. OpenCV's `imgproc`
filter family does not: `gaussianBlur`, `boxBlur`, `sobel` and `laplacian` all reach
`cv::FilterEngine::init`, which asserts that the border type is not `BORDER_WRAP` and aborts.

#warning[That abort is inconsistent across depths --- the same call can pass silently on one and kill
the process on another --- which is why scalacv runs `BorderType.requireFilterSupport` on every
filter's `border` argument rather than letting native code decide; Chapter 9,
#emph[Filtering and Morphology], has the measurement. `BORDER_TRANSPARENT` is absent from the enum
entirely for a related reason: `copyMakeBorder` throws on it and `warpAffine` with it leaves the
freshly-allocated destination uninitialised, so it can only ever produce a crash or garbage pixels.]

#sect("Reading a 2×3 affine")

A rotation is one member of a family. Every affine transform is a 2×3 matrix that maps a source
point to a destination point by

```text
x' = a*x + b*y + c
y' = d*x + e*y + f
```

which is a 2×2 linear part --- rotation, scale, shear, and any composition of them --- plus a
translation in the third column. The scene graph in `scalacv-graphs` carries exactly these six
numbers internally and exposes them as `Picture.translate(dx, dy)`, `Picture.rotate(degrees, about)`
and `Picture.scale(factor, about)`; at the pixel level, you build the matrix yourself as a `CV_64F`
`Mat` and hand it to `warpAffine`.

#figure-table("The affine building blocks, as 2×3 matrices.")[
#tbl(
  columns: (auto, auto, 1fr),
  [Transform], [Matrix], [Note],
  [Identity], [`[1 0 0 ; 0 1 0]`], [Copies],
  [Translate by `(dx, dy)`], [`[1 0 dx ; 0 1 dy]`], [Pure third column; sub-pixel `dx` resamples],
  [Scale by `(sx, sy)`], [`[sx 0 0 ; 0 sy 0]`], [About the origin, not the centre],
  [Rotate by θ], [`[cos θ  −sin θ 0 ; sin θ  cos θ 0]`], [About the origin. With y pointing down this turns clockwise on screen],
  [Shear x by `k`], [`[1 k 0 ; 0 1 0]`], [Slants verticals; leaves horizontals alone],
)
]

Anything about a point other than the origin is a translate, then the transform, then the inverse
translate --- which is precisely what `getRotationMatrix2D(center, angle, scale)` composes for you,
and why its third column is not zero.

#example("A hand-built shear, warped by hand.")[
```scala
import org.opencv.core as cv
import org.opencv.core.CvType
import org.opencv.imgproc.Imgproc
import scalacv.*

/** Slants `src` by `k` (x shifted by k*y), into a canvas wide enough for the lean. */
def sheared(src: Image, k: Double): Image =
  val w = src.width
  val h = src.height
  Managed.scope: own =>
    val m = own(cv.Mat(2, 3, CvType.CV_64F))
    m.put(0, 0, 1.0, k, if k < 0 then -k * h else 0.0)
    m.put(1, 0, 0.0, 1.0, 0.0)
    // Allocated last and wrapped immediately: this is the result, so the scope must not own it.
    val dst = Managed(cv.Mat())
    Imgproc.warpAffine(
      src.mat, dst.get, m,
      cv.Size(w + math.abs(k) * h, h.toDouble),
      Interpolation.Linear.cvValue,
      BorderType.Constant.cvValue,
      cv.Scalar(255, 255, 255)
    )
    Image.wrap(dst)
```
]

That listing is the pattern for every unwrapped OpenCV call in this chapter, so it is worth reading
twice. `Managed.scope` owns the working matrices and releases them in reverse order even if
`warpAffine` throws part-way. The destination is created #emph[outside] the scope's ownership,
because it is the one object that has to survive the block, and `Image.wrap` transfers that
ownership to the returned `Image`. `.cvValue` gives the raw int for the typed enums, so you keep the
typed vocabulary right up to the JNI boundary.

#memory[`getRotationMatrix2D` and `getPerspectiveTransform` both #emph[return] a fresh `Mat`. It is
small --- six or nine doubles --- but it is native, it is yours, and nothing in OpenCV's Java API
suggests as much. A per-frame warp that forgets it leaks a `Mat` header and its buffer thirty times a
second. Wrapping the return value in `own(...)` the moment it appears is the habit that makes the
mistake impossible.]

#sect("Three points or four: affine versus perspective")

An affine transform has six degrees of freedom, which is exactly three point correspondences. It can
translate, rotate, scale, shear, and it preserves one thing absolutely: parallel lines stay parallel.
A rectangle becomes a parallelogram, never a trapezium.

The docket is a trapezium. Its top edge is shorter than its bottom edge because the top of the page
was further from the lens, and no affine matrix can undo that. What you need is a #emph[projective]
transform --- a 3×3 homography with eight degrees of freedom, which is four point correspondences ---
applied with `warpPerspective`.

#figure-table("Which warp the job needs.")[
#tbl(
  columns: (auto, auto, auto, 1fr),
  [], [Correspondences], [Matrix], [Preserves],
  [`warpAffine`], [3 point pairs], [2×3], [Parallel lines, midpoints, ratios along a line],
  [`warpPerspective`], [4 point pairs], [3×3], [Straight lines only],
)
]

The distinction is not academic. If the tilt is a rotation in the image plane --- a page that was
straight on the desk but scanned crooked --- an affine rotation is correct and cheaper, and
`Image.deskew(maxAngle)` will even find the angle for you. It thresholds with an inverted Otsu so the
ink becomes the non-zero pixels, fits a minimum-area rectangle to them, folds that rectangle's angle
into the equivalent tilt in the range −45° to 45°, and rotates by it, filling the exposed corners
white. It returns a plain copy in two cases: a tilt under 0.1°, which is not worth a resample, and a
tilt beyond `maxAngle` (default `45.0`), which is treated as a misread on the grounds that a page of
large graphics can fool the estimate.

One difference from `rotated` is easy to miss and matters here: `deskew` keeps the frame size. It
warps into a destination of the source's own `cols`×`rows` rather than into a grown bounding box,
because a deskew is a correction of a degree or two and growing the canvas for it would leave every
downstream stage handling a slightly different frame size per page. The corners it exposes are
inside the original frame, which is why filling them white is enough. If the tilt is out of the
plane rather than in it, no amount of affine rotation helps and only a homography will flatten it.

#subsect("Flattening the docket")

scalacv wraps `warpAffine` behind `rotated` and `deskew`, but it does not wrap `warpPerspective`. The
low-level surface is one step away, as always, and the four-corner warp is a good demonstration that
the escape hatch is a door rather than a hole. The work is in three parts: find the quadrilateral,
order its corners, and warp them onto a rectangle.

#example("Finding the page: the largest four-sided contour.")[
```scala
import scala.util.Using
import scalacv.*

/** The page outline, if the largest contour simplifies to a quadrilateral. */
def pageQuad(img: Image): Option[Seq[Point]] =
  Using.resource(img.copy.gray.blur(2).canny(60, 180)) { edges =>
    edges.contours()
      .filter(_.area > 0.2 * img.width * img.height)
      .maxByOption(_.area)
      .map(c => c.approx(0.02 * c.perimeter))
      .map(_.points)
      .filter(_.size == 4)
  }
```
]

`Contour.approx` is Ramer--Douglas--Peucker simplification: no vertex more than `epsilon` pixels off
the original outline, with `epsilon` conventionally a small fraction of the perimeter. Two percent
turns a noisy scan of a rectangle back into four corners. The area filter drops the small contours
--- a logo, a stamp, the shadow of a pen --- before the sort, so a `maxByOption` cannot be fooled by
a large but non-rectangular blob. `Using.resource` closes the edge image whether the body returns a
quad or `None`; `contours` is a query, so it borrows and leaves the image alive, which means
something still has to close it.

`getPerspectiveTransform` cares about the order of the four points, and `findContours` gives them in
traversal order starting from wherever the scan happened to begin. Fix that with plain arithmetic on
plain data --- one of the quieter benefits of `Point` being a case class:

#example("Ordering four corners clockwise from the top-left.")[
```scala
def clockwiseFromTopLeft(quad: Seq[Point]): Seq[Point] =
  val cx = quad.map(_.x).sum / quad.size
  val cy = quad.map(_.y).sum / quad.size
  // atan2 increases clockwise on screen, because y points down.
  val ring = quad.sortBy(p => math.atan2(p.y - cy, p.x - cx))
  val start = ring.indices.minBy(i => ring(i).x + ring(i).y)   // top-left minimises x + y
  ring.drop(start) ++ ring.take(start)
```
]

And then the warp itself, in the same shape as the shear above:

#example("Four corners onto a flat page.")[
```scala
import org.opencv.core as cv
import org.opencv.core.MatOfPoint2f
import org.opencv.imgproc.Imgproc
import scalacv.*

/** Warps the quadrilateral `corners` (clockwise from top-left) onto a flat `width`x`height` page.
  * `src` is borrowed: it is neither consumed nor released.
  */
def flatten(src: Image, corners: Seq[Point], width: Int, height: Int): Image =
  require(corners.size == 4, s"a perspective warp needs four corners, got ${corners.size}")
  require(width > 0 && height > 0, s"flatten needs a positive page, got ${width}x$height")
  Managed.scope: own =>
    val from = own(MatOfPoint2f(corners.map(p => cv.Point(p.x, p.y))*))
    val to = own(MatOfPoint2f(
      cv.Point(0, 0),
      cv.Point(width - 1, 0),
      cv.Point(width - 1, height - 1),
      cv.Point(0, height - 1)
    ))
    val h = own(Imgproc.getPerspectiveTransform(from, to))
    val dst = Managed(cv.Mat())
    Imgproc.warpPerspective(
      src.mat, dst.get, h,
      cv.Size(width.toDouble, height.toDouble),
      Interpolation.Linear.cvValue,
      BorderType.Constant.cvValue,
      cv.Scalar(255, 255, 255)
    )
    Image.wrap(dst)
```
]

Three native objects are handed to `own` inside that block --- the two point matrices and the
homography `getPerspectiveTransform` returns --- and the scope frees all three in reverse order, on
the way out or on the way through an exception. The destination is the one allocation the scope does
not own, and `Image.wrap` is where its ownership transfers to the returned `Image`.

The destination corners are `width - 1` and `height - 1`, not `width` and `height`, because they are
pixel centres rather than an extent: the last column of a `width`-wide image is at index `width - 1`.
Getting that wrong scales the output by a fraction of a pixel, which is invisible on a photograph and
measurable the moment somebody reads coordinates back off the flattened page.

#sect("The ordering trap: detect small, draw large")

The pipeline now has all its pieces, and assembling them exposes the mistake this chapter exists to
prevent. Finding the page on a 12-megapixel frame is wasteful; the page outline is a few thousand
pixels of edge and it is as findable at a quarter of the size. So you detect on `working` and
warp the original --- and the coordinates do not line up.

#example("Wrong: coordinates from one image, pixels from another.")[
```scala
Image.reading("docket.jpg") { full =>
  val working = full.copy.scale(0.25, Interpolation.Area)
  val quad = pageQuad(working)                  // corners in 1008x756 space
  working.close()
  quad.map(q => flatten(full, clockwiseFromTopLeft(q), 1200, 1600))
  // The corners are a quarter of the way in. The warp flattens the top-left
  // sixteenth of the page onto the output and calls it a document.
}
```
]

Every coordinate a detector, a contour finder or a template matcher returns is expressed in the
pixel space of the image you handed it. Scale the image and you have scaled that space. Mapping a
result back means dividing by the factor you multiplied by --- and getting the factor from the
images, not from the argument you passed, because `scaled` rounds the destination with `cvRound` and
the achieved factor is only approximately the one you asked for.

#example("Right: derive the factor from the sizes, and map back through it.")[
```scala
/** The mapping between a downscaled working image and the full-resolution source. */
final case class Downscale(fullWidth: Int, fullHeight: Int, workWidth: Int, workHeight: Int):
  val fx: Double = fullWidth.toDouble / workWidth
  val fy: Double = fullHeight.toDouble / workHeight

  def point(p: Point): Point = Point(p.x * fx, p.y * fy)

  def rect(r: Rect): Rect =
    Rect(
      math.round(r.x * fx).toInt,
      math.round(r.y * fy).toInt,
      math.round(r.width * fx).toInt,
      math.round(r.height * fy).toInt
    )

def flatDocket(path: String, page: Size): Either[CvError, Option[Image]] =
  Image.reading(path) { full =>
    val working = full.copy.scale(0.25, Interpolation.Area)
    val up = Downscale(full.width, full.height, working.width, working.height)
    val quad =
      try pageQuad(working)
      finally working.close()
    quad.map: q =>
      flatten(full, clockwiseFromTopLeft(q).map(up.point), page.width.toInt, page.height.toInt)
  }
```
]

Three details in that listing pay for themselves. `Downscale` is built from `full.width` and
`working.width` rather than from the literal `0.25`, so it carries the factor that was achieved
rather than the one that was requested --- and `cvRound`'s half-to-even rule means those two are not
always the same number. The `try`/`finally` closes `working` even when `pageQuad` throws, which it
can: `canny` on a pathological image raises `CvError.NativeCall`, an unchecked throw that no
`Either` in the signature would have caught. And `up.point` is applied to the ordered corners rather
than to the raw contour, so the ordering logic works entirely in one coordinate space and only the
four survivors are converted.

#memory[`flatDocket` returns an `Image` that has never reached a terminal, which means the caller
owns it. That is the honest signature for a factory --- but it is also the one shape in the high-level
API that can leak, because nothing closes for you once a value escapes `reading`. Give it to
`Using.resource`, or end the chain in `write` or `bytes`, both of which release. The same warning
applies to `clipped`, `sheared` and `flatten` above: every one of them hands back an `Image` nobody
else will close.]

The same arithmetic runs on rectangles. A `Rect` from a Haar cascade, or the `location` of a
`TemplateMatch` from `Screen.findAll` in `scalacv-vision`, maps back with `Downscale.rect`, which is
what lets you detect on a small frame and draw on a large one:

#example("Detect at a quarter size, annotate at full size.")[
```scala
// `template` must already be at the working scale: a matcher compares pixels, not proportions,
// so a full-resolution template will not be found in a quarter-resolution frame.
Image.reading("desk.png") { full =>
  val working = full.copy.scale(0.25, Interpolation.Area)
  val up = Downscale(full.width, full.height, working.width, working.height)
  val hits =
    try Screen.findAll(working, template, minScore = 0.85)
    finally working.close()
  full.drawRects(hits.map(h => up.rect(h.location)), Scalar.Green).write("annotated.png")
}
```
]

The rounding in `Downscale.rect` is `math.round` rather than the `cvRound` the resize itself used,
and that is deliberate: this is a box to draw, not a destination to allocate, so half-to-even buys
nothing and half-up is the one a reader can predict. A box that lands a pixel wide of the object is a
cosmetic error; a destination size that rounds differently from OpenCV's is a native assertion.

It composes, as long as you apply the inverses in reverse order: a pipeline that downscales, then
crops, then rotates produces coordinates that must be un-rotated, then offset by the crop origin,
then divided by the scale factor. That is why keeping each transform as a value --- a `Downscale`, a
`Rect`, an angle --- rather than as loose `Double`s scattered across a method is worth the ceremony.

#sect("Where this leaves the docket")

The page arrives at 4032×3024 and leaves as a flat 1200×1600 rectangle, having been resampled
exactly twice: once by `Area` on the way down to a working size, and once by the perspective warp
onto the final page. Everything in between --- the contour search, the corner ordering, the
coordinate mapping --- moved numbers rather than pixels, which is the shape every geometric pipeline
should converge on. Resample as few times as you can, choose the filter to match the direction, and
keep the transform itself as a value you can invert.

That flat page is now a mask waiting to happen. This chapter leaned on `canny` and on `Contour.approx`
to find the page without ever explaining how a pixel becomes a one or a zero. The next chapter,
#emph[Thresholding and Colour Segmentation], is where that gets its own treatment: the `Threshold`
bitmask and its automatic modes, `adaptiveThreshold` under uneven lighting, and masks built from
colour in HSV rather than from intensity. The chapter after it takes the masks and turns them into
measurable geometry --- areas, perimeters, hulls, and the bounding boxes that the `Downscale` mapping
above exists to move between resolutions.
