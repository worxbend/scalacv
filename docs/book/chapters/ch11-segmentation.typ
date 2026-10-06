#import "../lib/book.typ": *

#chapter(
  "Thresholding and Colour Segmentation",
  subtitle: [Every pixel gets one bit, and the whole rest of the pipeline depends on which one.],
)

A classical vision pipeline spends most of its length working with images that have exactly two
values in them. Contour finding wants a binary image. Morphology is defined on one. Connected
components, blob measurement, the moments that give you a centroid --- all of them are stated over
a set of foreground pixels, and none of them knows or cares what colour that set used to be. The
mask is the currency: you buy every downstream measurement with one, and the quality of the
measurement is capped by the quality of the mask you paid with.

Which puts an uncomfortable amount of weight on one decision. Somewhere near the front of the
pipeline a function has to look at a pixel and answer yes or no, and every ambiguity in the scene
--- a shadow across the object, a highlight on its shoulder, a background the same brightness as
the thing you want --- gets collapsed into that answer and is gone. You cannot recover it later.
A contour drawn around a mask that leaked into the shadow is not a contour of the object; it is a
contour of the object plus the shadow, and it will report a centroid that drifts every time a cloud
passes.

So this chapter is about the several ways scalacv lets you make that decision, in rough order of
how much they know about the image. A fixed threshold knows nothing and is right only when you
control the lighting. Otsu's method reads the histogram and picks the level for you. Adaptive
thresholding gives up on a single level entirely and computes one per neighbourhood. And colour
segmentation stops thresholding brightness altogether, moves into a space where "green" is a range
on one axis, and thresholds that instead.

The running example is the one from the library's own tutorial: a green ball on a workbench, filmed
by a webcam, to be followed around the frame. It is small enough to fit on a page and awkward enough
to make every failure mode show up.

#sect("One number for the whole image")

The simplest possible mask is a comparison against a constant. `Image.threshold` is that, and it
takes the value, the value written into the foreground, and the flavour of comparison:

```scala
def threshold(value: Double, maxValue: Double = 255, kind: Threshold = Threshold.Binary): Image
```

It runs on the pixel values it is given, so in practice it follows `gray` --- and Otsu, below,
requires a single channel outright. Point it at the bench and it does exactly what you asked:

#example("A global threshold on a greyscale frame.")[
```scala
Image.reading("bench.png") { img =>
  img.gray.threshold(127).write("mask.png")
}
```
]

And the mask is useless. The ball is a mid-green whose luminance is close to the varnished bench it
is sitting on, so at 127 the two are on the same side of the line: either the ball is swallowed by
the background or half the bench comes along with it. Drop the level to 90 and the ball separates
on the lit side of the frame while the shaded corner turns solid white. There is no value of
`value` that works, because the scene does not have one: brightness is not the property that
distinguishes a green ball from brown wood.

That is the honest failure of a global threshold, and it is worth stating plainly because the fix
people reach for first --- nudging the number until this photograph looks right --- produces a
program that works on this photograph. The next frame has a different exposure and the number is
wrong again.

Where a fixed threshold does earn its place is downstream of something that has already normalised
the signal for you: a frame difference, where the foreground is "changed" and the background is
"unchanged"; a distance map; a confidence image out of a detector. In all of those the meaning of
the value is fixed by construction and a constant is the right tool.

#subsect("The Threshold model")

OpenCV's `THRESH_*` constants are a bitmask, not an enumeration: a comparison mode OR-ed with at
most one automatic-selection modifier. scalacv models that shape rather than flattening it, so
`Threshold` is a case class carrying a `Threshold.Mode` and an `Option[Threshold.Auto]`, and
`cvValue` is the OR of the two.

#figure-table("The five comparison modes of `Threshold.Mode`, for a pixel value `v` and a threshold `t`.")[
#tbl(
  columns: (auto, 1fr, 1.4fr),
  [`Mode`], [Result], [What it is for],
  [`Binary`], [`v > t` → `maxValue`, else 0], [The mask you almost always want],
  [`BinaryInv`], [`v > t` → 0, else `maxValue`], [Dark objects on a light ground --- printed text],
  [`Truncate`], [`v > t` → `t`, else `v`], [Clipping highlights; the result is not binary],
  [`ToZero`], [`v > t` → `v`, else 0], [Keeping the bright pixels' actual values],
  [`ToZeroInv`], [`v > t` → 0, else `v`], [Keeping the dark ones],
)
]

`Threshold.Binary` --- the value, not the mode --- is the plain fixed binary case and the default
for both tiers of the API. Any other fixed mode is the case class applied directly, with `auto`
taking its `None` default: `Threshold(Threshold.Mode.BinaryInv)` binarises printed text with the ink
white. The two automatic modes are reached through the constructors `Threshold.otsu(mode)` and
`Threshold.triangle(mode)`, each of which defaults its `mode` to `Mode.Binary`, so
`Threshold.otsu()` is Otsu plus a plain binary comparison and `Threshold.otsu(Threshold.Mode.BinaryInv)`
is Otsu plus an inverted one. `computesThreshold` reports whether OpenCV is going to pick the level
itself, which is the same question as whether the returned `ThresholdResult` means anything.

#sect("Letting the histogram choose: Otsu")

Otsu's method looks at the greyscale histogram and searches every possible threshold for the one
that minimises the variance within the two resulting groups --- equivalently, that maximises the
variance between them. It is asking: of all the ways to cut this histogram in two, which cut
produces the two tightest clusters?

The assumption buried in that question is that there are two clusters. Otsu works beautifully on a
bimodal histogram --- printed text on paper, a backlit object, a fluorescent bead on a dark ground
--- because the two modes are exactly what it is looking for. On a histogram with one broad hump
it still returns a number, cheerfully, and that number is somewhere in the middle of the hump,
splitting a single population in half along a line that means nothing.

The number is often the reason you called. The mid-level `Ops.threshold` is the tier that hands it
back, returning a pair of the mask and a `ThresholdResult`:

```scala
def threshold(value: Double, maxValue: Double = 255, kind: Threshold = Threshold.Binary)
    : (Managed[Mat], ThresholdResult)
```

With an automatic mode the `value` argument is ignored --- pass `0` --- and `ThresholdResult.value`
carries the level OpenCV settled on.

#example("Otsu on the bench frame, reporting the level it chose.")[
```scala
val chosen: Either[CvError, Double] =
  Image.reading("bench.png") { img =>
    img.mat.cvtColor(ColorConversion.BgrToGray).use { grey =>
      val (mask, level) = grey.threshold(0, 255, Threshold.otsu())
      mask.release()   // only the number was wanted here
      level.value      // e.g. 138.0
    }
  }
```
]

The high-level `Image.threshold` accepts the same `kind` and drops the computed value, because the
common case is "binarise this" rather than "tell me the level". When you want both, drop a tier.

#memory[
The pair that `Ops.threshold` returns has an owned `Managed[Mat]` in the first slot, and `Managed`
frees nothing on its own. Writing `grey.threshold(0, 255, Threshold.otsu())._2.value` to grab only
the number leaks a full-size mask per call. The README puts a measured number on what that costs:
2000 unreleased 1000×1000 three-channel Mats finish at 5 865 MB of RSS, against 144 MB when the same
2000 are released --- and a frame loop reaches 2000 in about a minute. Either `release()` it as
above, or consume it with `use`/`pipe` --- `_1` exists so a pipeline can thread the mask onward, not
so the mask can be discarded silently.
]

#sidebar("Otsu, Triangle, and the shape of your histogram")[
Nobuyuki Otsu published his method in 1979, and it has outlived a great deal of more sophisticated
work because it is exhaustive over a 256-bin search space --- there is nothing to tune and nothing
to get wrong.

`Threshold.Auto` carries a second option, `Triangle`, which fits a line from the histogram's peak
to its far end and takes the bin furthest from that line. Where Otsu wants two modes, Triangle
wants one dominant mode and a long tail --- fluorescence images, sparse bright features on a big
uniform background. If Otsu is giving you a level that sits inside your one big peak, Triangle is
the next thing to try, and it costs one identifier to find out.

The library uses Otsu on itself: `Ops.deskew` binarises with
`Threshold.otsu(Threshold.Mode.BinaryInv)` before measuring the text angle, because a page of print
is the bimodal histogram Otsu was designed for and the ink needs to be the foreground.
]

#sect("Giving up on a single level: adaptive thresholding")

Photograph a receipt on a desk under one lamp and the top of the page is nearly white while the
bottom is a dim grey --- darker, quite possibly, than the ink at the top. No global threshold can
separate ink from paper in that image, Otsu included, because the two populations overlap when you
pool the whole frame. But they do not overlap #emph[locally]: within any small window, the ink is darker
than the paper immediately around it.

That is what adaptive thresholding computes. For each pixel it takes the mean (or a
Gaussian-weighted mean) over a `blockSize` × `blockSize` neighbourhood, subtracts a constant `c`,
and thresholds against that. The lighting gradient cancels, because it is present in both the pixel
and its neighbourhood.

#example("The document scan --- the case where nothing else works.")[
```scala
Image.reading("receipt.jpg") { img =>
  img.gray.adaptiveThreshold(blockSize = 31, c = 7).write("receipt-bw.png")
}
```
]

Two knobs, and they pull in different directions. `blockSize` must be odd and at least 3; it sets
how big a region counts as "local", and it wants to be comfortably larger than the features you are
keeping but smaller than the scale on which the lighting changes. Too small and the interior of a
thick stroke starts comparing itself to its own ink and hollows out. Too large and you are back to
a global threshold. `c` is subtracted from the local average, so raising it demands that a pixel be
more clearly darker than its surroundings before it is kept --- it is the noise knob. On flat paper
with a faint gradient, `c` near 2 is fine; on a phone photo with sensor noise, 7 to 10 cleans up a
speckled result far better than blurring first.

`AdaptiveMethod` chooses the weighting: `Mean` gives every pixel in the block the same vote,
`Gaussian` weights the centre more heavily. `Gaussian` is the default and is slightly gentler at
edges; `Mean` is cheaper and is often indistinguishable on text.

#warning[
The two tiers spell `adaptiveThreshold`'s parameters in a different order on purpose. `Image` leads
with the two you actually tune; `Ops` mirrors OpenCV's own order. The divergence cannot bite
silently --- the leading parameters have different types across the tiers, so a positional call
meant for one does not compile against the other --- but it is still a reason to name your
arguments and stop thinking about it.
]

#figure-table("`adaptiveThreshold` across the two tiers.")[
#tbl(
  columns: (auto, 1fr),
  [Tier], [Signature],
  [`Image`], [`adaptiveThreshold(blockSize: Int = 11, c: Double = 2.0, method: AdaptiveMethod = AdaptiveMethod.Gaussian, inverse: Boolean = false): Image`],
  [`Ops` (on `Mat`)], [`adaptiveThreshold(maxValue: Double = 255, method: AdaptiveMethod = AdaptiveMethod.Gaussian, blockSize: Int = 11, c: Double = 2.0, inverse: Boolean = false): Managed[Mat]`],
)
]

There is no free `Threshold.Mode` here: adaptive thresholding is binary or nothing, and `mode` picks
which side. For scanned text you want `mode = Threshold.Mode.BinaryInv`, so the ink comes out white
and the contours you find afterwards are the letters rather than the space between them.

#sect("Thresholding the right axis: colour in HSV")

Back to the green ball. The reason no brightness threshold works is that brightness is the wrong
axis. Nor does thresholding BGR help: a single real-world colour smears across all three channels
as the light changes, so no fixed box in BGR captures "green" for longer than one exposure.

HSV separates the question. Hue is the colour itself, saturation is how far it is from grey, value
is how bright it is. Under changing light the value moves a lot, the saturation moves some, and the
hue barely moves at all --- which makes "green, at any brightness" a range on a single channel.
`toHsv` is the move, and `convert(ColorConversion.BgrToHsv)` is the general form it delegates to.

One thing to internalise before writing a single bound: in OpenCV's 8-bit HSV, #strong[hue runs
0--179], not 0--359, because the degrees are halved to fit in a byte. Saturation and value use the
full 0--255. A `Scalar` handed to `inRange` is therefore `Scalar(hue, sat, val)` on exactly that
scale, and a bound of 120 means blue, not green.

`inRange` produces the mask: a `CV_8UC1` image, 255 where every channel of the source falls inside
`[lo, hi]` and 0 everywhere else, regardless of how many channels the source had.

#example("The colour mask, in the two lines it takes.")[
```scala
val mask = frame.copy.toHsv.inRange(Scalar(35, 80, 80), Scalar(85, 255, 255))
mask.channels // 1 — a binary mask, whatever the source had
```
]

The `copy` is doing real work. Every `Image` transform consumes its receiver, so `frame.toHsv`
would spend the frame and leave you holding a mask with no pixels to apply it to. Branching off a
copy is the idiom whenever one path builds the mask and another supplies the content.

The other half of the pair is `applyMask`, which keeps this image where the mask is non-zero and
blacks out the rest:

#example("Segment the scene: mask on one branch, pixels on the other.")[
```scala
val scene = Image.blank(320, 240, Scalar(30, 30, 30))
  .drawCircle(Point(210, 120), 28, Scalar.Green, Thickness.Filled)

val mask = scene.copy.toHsv.inRange(Scalar(35, 80, 80), Scalar(85, 255, 255))
val justGreen: Either[CvError, Array[Byte]] = scene.applyMask(mask).bytes(".png")
mask.close() // applyMask borrowed it — close it yourself
```
]

#memory[
`applyMask` #emph[borrows] its argument. The receiver is consumed as usual and the returned image
carries the result forward, but the mask you passed in is still alive when the call returns and
nothing will close it for you. The same is true of `blend`'s `other`. The rule across the whole
library is that the receiver is spent and a second image argument is borrowed --- so every
`applyMask` in your code should have a `close()` on the mask somewhere below it, and in a per-frame
loop that `close()` belongs in a `finally`.
]

#subsect("Red, and the seam in the hue wheel")

Hue is circular and red sits on the seam, at both ends of the 0--179 range at once. A single
`inRange` is a box, and no box captures both ends. This is the trap everyone hits, usually while
wondering why the same code that found the green ball finds nothing at all on the red one.

#figure-table("Hue centres on OpenCV's 0--179 scale. Widen the saturation and value floors to admit washed-out and shadowed pixels.")[
#tbl(
  columns: (auto, auto, 1fr),
  [Colour], [Hue], [Note],
  [Red], [0--10 #emph[and] 170--179], [Wraps the seam --- needs two ranges],
  [Orange], [~10--20], [],
  [Yellow], [~25--35], [],
  [Green], [~35--85], [The ball],
  [Cyan], [~85--95], [],
  [Blue], [~100--130], [],
  [Magenta], [~140--160], [],
)
]

The fix is two masks, OR-ed together. `inRange` and `applyMask` are wrapped, but a raw bitwise OR
is not, so this is one of the places you reach past the wrapped API into `org.opencv.core.Core`:

#example("Both ends of the wheel, and the unmanaged Mat it leaves you holding.")[
```scala
import org.opencv.core.{Core, Mat}

val hsv  = redScene().toHsv     // a frame of its own; toHsv spends it
val low  = hsv.copy.inRange(Scalar(0, 80, 80), Scalar(10, 255, 255))
val high = hsv.copy.inRange(Scalar(170, 80, 80), Scalar(179, 255, 255))

val redMask = Mat()
Core.bitwise_or(low.mat, high.mat, redMask)
val red = Image.wrap(Managed(redMask))   // adopted before anything else can throw

low.close(); high.close(); hsv.close()
```
]

#memory[
`Core.bitwise_or` fills a `Mat` you allocated, and that `Mat` is outside the library's ownership
model entirely --- nothing tracks it, nothing releases it, and a `finally` you did not write is a
leak that runs at frame rate. `Image.wrap(Managed(redMask))` adopts it on the spot and puts it back
under the normal rules. Do the adoption in the same expression that created the raw `Mat`, not
three lines later where an early return can slip between them.
]

The alternative you will see suggested is rotating the hue channel by 90 so red lands in the middle
of the range and one box suffices. It is a real technique, but it wants modular arithmetic: the
rotation has to wrap 179 back round to 0. `adjust` cannot express that --- it saturates at 255
rather than wrapping --- so implementing it means going to `Core` anyway. Two ranges is the shorter
road, and it is what the library's own documentation recommends.

#sect("Cleaning the mask")

A freshly cut mask is never clean. Sensor noise puts isolated white pixels across the background;
a specular highlight on the ball desaturates a patch of it out of range and punches a hole. Both
are handled by morphology, which Chapter 9 covers in full --- here is the part that matters at the
end of a segmentation.

Opening is an erosion followed by a dilation: it removes bright detail smaller than the kernel and
leaves everything larger essentially untouched. Closing is the reverse pairing, and it fills dark
holes smaller than the kernel. The order is always open then close, because you want the specks
gone before you start filling things in --- close first and you will have grown the specks into
blobs that opening can no longer remove.

Both live on `MorphOp`, alongside `Gradient`, `TopHat` and `BlackHat`, and all five are applied
through the same method. `Image.morphology(op, radius, shape)` is the whole of the high-level
signature; the mid-level `Ops.morphology` adds an `iterations` count, for the case where you want
the same small kernel applied repeatedly rather than one large one. Note that there is no bare
`close` verb on `Image` for `MorphOp.Close` --- `close()` already means "release this image" ---
which is why the op goes through `morphology` and not through a name of its own:

#example("Threshold, then open, then close --- the standard clean-up.")[
```scala
val clean = frame.copy.toHsv
  .inRange(Scalar(35, 80, 80), Scalar(85, 255, 255))
  .morphology(MorphOp.Open, radius = 2)   // drop the speckle
  .morphology(MorphOp.Close, radius = 3)  // fill the highlight's hole
                                          // `clean` is yours: close it or feed it a terminal
```
]

The `radius` is not the kernel size: the structuring element's side is `radius * 2 + 1`, so
`radius = 2` is a 5×5 kernel. Choose it from the size of the noise, not by taste. Look at the raw
mask, find the largest speck you want gone, and pick the smallest radius that exceeds it --- then
stop, because every extra pixel of radius also rounds off the object you are keeping. For a
640×480 webcam frame, `radius = 1` or `2` covers ordinary sensor noise. If you need 5 to clean the
mask, the bounds are wrong and morphology is papering over it. There is no identity radius: a
`require` rejects anything below 1, unlike `blur(0)`, which is deliberately a no-op --- a
zero-radius structuring element is not a thing OpenCV can build.

`MorphShape` defaults to `Rect`, which is the right default for cleaning noise. `Ellipse` is worth
switching to when the object is round and you can see the corners of a square kernel in the result.

`medianBlur` is the other tool for this job and is sometimes better: on a binary mask it erases
isolated pixels without eroding the edges at all. `Image.medianBlur` takes a radius, so
`medianBlur(1)` is a 3×3 window; the mid-level op on `Mat` takes the odd kernel size directly and
rejects anything even or below 3.

#sect("The tracker, end to end")

Everything above assembles into the tracker. Convert, threshold the colour, clean, take the largest
contour, read its centroid.

#example("One frame of the colour tracker.")[
```scala
def locate(frame: Image): Option[Point] =
  val mask = frame.copy.toHsv
    .inRange(Scalar(35, 80, 80), Scalar(85, 255, 255))
    .morphology(MorphOp.Open, radius = 2)
    .morphology(MorphOp.Close, radius = 3)
  try
    mask.contours()
      .filter(_.area > 200)      // a speck is never the target
      .maxByOption(_.area)
      .flatMap(_.centroid)
  finally mask.close()
```
]

`contours()` borrows the mask and returns `Seq[Contour]` --- plain immutable Scala data, copied out
of native memory before the call returns, so the contours stay valid after the mask is released.
`Contour.area` is the shoelace area (`Imgproc.contourArea`), which is measured between the
#emph[centres] of the boundary pixels and so runs a little under the pixel count: a filled 100×50
rectangle reports 99 × 49 = 4851, not 5000. That matters only if you are comparing it against a
count you derived some other way; as a relative size for picking the largest blob it is exactly
right. `centroid` is derived from image moments and is an `Option` because a degenerate zero-area
blob has no centre of mass. The `filter` before `maxByOption` is
what stops a single surviving speck from being promoted to "the target" on a frame where the ball
is out of view; without it, `maxByOption` returns `Some` of whatever noise is left and your tracker
reports a position with total confidence.

Wrap that in a camera loop and it is a live tracker:

#example("The same logic over a video, with nothing accumulating.")[
```scala
Camera.usingFile("bench.mp4") { cam =>
  cam.foreach() { frame =>
    locate(frame) match
      case Some(p) => println(s"target at (${p.x.toInt}, ${p.y.toInt})")
      case None    => println("target lost")
  }
}
```
]

#memory[
`Camera.foreach` owns each frame and closes it when your block returns, which is why `locate` takes
a `copy` rather than consuming the frame it was handed --- consuming it would leave `foreach`
closing an already-spent handle, which is harmless (release is idempotent) but would also mean you
could not draw on the frame afterwards. Inside `locate`, exactly one mask is allocated and exactly
one is freed, in a `finally`, so a thousand-frame video allocates a thousand masks and holds one.
That is the shape every per-frame pipeline should have: one owner, one `finally`, nothing crossing
the loop boundary.
]

To annotate rather than print, `drawCircle` draws into the frame's own Mat --- no copy is made ---
but it is still a transform in the ownership sense: it takes the handle, mutates the pixels, and
hands back a #emph[new] `Image` around the same buffer. The result is what owns the frame afterwards,
so it has to be kept and closed:

#example("Marking the frame, and keeping the handle the drawing hands back.")[
```scala
Camera.usingFile("bench.mp4") { cam =>
  cam.foreach() { frame =>
    val marked = locate(frame) match
      case Some(p) => frame.drawCircle(p, 8, Scalar.Red, Thickness.Stroke(3))
      case None    => frame
    marked.close()
  }
}
```
]

#memory[
Discarding a drawing verb's result --- `frame.drawCircle(...)` as a bare statement --- leaks the
frame, and it leaks it in the one shape that looks safest. `drawCircle` goes through the internal
`paint`, which #emph[takes] the handle out of `frame` and rewraps the same Mat in the returned
`Image`. The `frame` you still hold is now spent, so `Camera.foreach`'s own `close()` on it releases
nothing, and the only handle that could have freed the buffer was the value you threw away. Bind
the result. The `None` branch above rebinds the untouched frame instead, so exactly one name owns
the buffer on both paths and one `close()` covers both.
]

#sect("Choosing the bounds")

Which leaves the one number nobody can give you: the bounds. `Scalar(35, 80, 80)` to
`Scalar(85, 255, 255)` is a starting point for green, not an answer, and the gap between a tracker
that works in the room you wrote it in and one that works in the next room is decided here rather
than anywhere above.

Start by measuring rather than guessing. Crop a small patch of the object out of a representative
frame, convert it to HSV, and take its mean. That last step is one of the few places in a
segmentation where the wrapped API runs out: `Image` exposes size, channel count and the borrowed
Mat, but no pixel accessor, and `Scalar.from` --- the conversion that would hand OpenCV's four
doubles back as a `Scalar` --- is `private[scalacv]`. For one number, reach through `Image.mat` to
`Core.mean` and read its `val` array directly:

#example("Sampling a patch to find where the object actually sits in HSV.")[
```scala
import org.opencv.core.Core

val sample: Either[CvError, Array[Double]] =
  Image.reading("ball.png") { img =>
    val patch = img.crop(Rect(204, 114, 12, 12)).toHsv
    try Core.mean(patch.mat).`val`   // (hue, sat, val, 0)
    finally patch.close()
  }
```
]

#memory[
`crop` and `toHsv` each consume their receiver, so after that first line the only live `Image` in
the block is `patch` --- `img` is already spent, and `reading`'s own `close()` is a no-op on it.
Nothing frees `patch` for you, because it never passed through a terminal. The `finally` is not
decoration.
]

Then widen the box, asymmetrically. Hue is the channel you trust, so keep it tight --- ±15 either
side of the measured value is generous for a distinctly coloured object, and going wider is how
green starts catching yellow tape and cyan mugs. Saturation and value are the channels the lighting
moves, so open them right up: a floor around 80 with a ceiling of 255 admits the object in shade
and under a highlight, while the floor still rejects the near-grey pixels whose hue is numerically
green but visually meaningless. It is that saturation floor, not the hue range, that keeps a grey
bench out of a green mask --- a nearly-unsaturated pixel has a hue, but it is noise.

Finally, measure on more than one photograph. Bounds tuned on a single image are fitted to that
image's white balance, and every camera's automatic white balance moves hue by a few units between
a sunlit frame and a tungsten one. Sample the object in the worst light you expect and the best,
take the union, and widen a little more. If the union is so wide that it starts admitting the
background, the answer is not a cleverer threshold --- it is a different coloured ball, or a
different cue altogether.

#sect("What the mask is for")

A mask on its own is a picture of a decision. What makes it useful is what comes next: turning
those white regions into shapes with area, perimeter, bounding boxes and centroids that you can
count, sort and track. `contours()` appeared once in this chapter, called with its
defaults and with no explanation of the `ContourRetrieval` and `ContourApproximation` knobs it
takes, of what a hierarchy of nested contours means, or of why `Contour` is plain Scala data rather
than a native handle. Chapter 12, #emph[Contours and Shape Analysis], is where that gets paid off.
