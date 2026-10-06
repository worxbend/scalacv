#import "../lib/book.typ": *

#chapter("Filtering and Morphology", subtitle: [Smoothing, sharpening, edges, and the operators that clean up a mask.])

Put a scanned invoice on your desk and look at it the way a program has to. The paper is not white:
it is a field of values between 190 and 245, drifting darker toward the corner where the scanner lid
did not quite close. The ink is not black either --- the thin strokes of a 9-point font land on the
sensor as a smear of greys. Scattered across the page are isolated specks, single pixels at 30 and
single pixels at 250, dust and sensor noise in equal measure.

Now write the rule that separates ink from paper. Any rule that looks at one pixel at a time is
already lost. A pixel at 140 is ink in the bright half of the page and paper in the shadowed corner;
a pixel at 30 is either the middle of a stroke or a speck of dust, and nothing about that pixel
distinguishes the two. The information you need is not in the pixel. It is in the pixel's
neighbourhood --- what is around it, and how much like its surroundings it is.

That is the single idea behind every operation in this chapter. A filter looks at a small window
around each pixel, computes one number from that window, and writes it to the same position in a
fresh destination image. Change what the window computes and you get a different filter: an average
smooths, a difference finds edges, a median throws out the specks, a maximum grows bright regions.
The destination is always separate from the source, which is why scalacv offers no in-place variant
of any of them.

The practical work, then, is the sequence: which filter, in what order, with what window size. A
wrong order costs you the thin strokes. A window one size too large closes the counters in the letter
"e". By the end of this chapter that scan will be a clean binary mask of the ink, and every step of
getting it there will have a reason.

#sect("A kernel, slid over the image")

A #keyterm[kernel] is a small grid of numbers --- 3×3, 5×5, 7×7. To filter an image with it, centre
the grid on a pixel, multiply each kernel number by the pixel underneath it, add the products
together, and write that sum to the destination. Then move one pixel right and do it again. That is
the whole operation, and everything interesting comes from choosing the numbers.

Three rules let you predict the result without doing any of the arithmetic:

- If the numbers are all positive and #emph[sum to one], the output is a weighted average of the
  neighbourhood. Overall brightness is preserved and detail is smeared. That is a blur.

- If the numbers #emph[sum to zero], flat regions produce zero and only places where the image
  changes produce anything at all. That is a derivative --- an edge detector.

- If the centre is a large positive number, its neighbours are negative, and the whole thing still
  sums to one, you get the original image plus its own difference from its surroundings. That is a
  sharpen.

scalacv does not expose a general convolution: there is no `filter2D` on the public surface, and the
named operations carry their own kernels. `emboss` is the only one that writes a convolution kernel
out by hand --- three rows of `CV_32F` behind the library's single `filter2D` call --- and it is worth
reading precisely because it is directional and therefore easy to predict:

#example("The one kernel scalacv writes out by hand.")[
```text
-2  -1   0
-1   1   1
 0   1   2
```
]

The numbers sum to one, so mid-grey stays mid-grey. They are negative up-and-left and positive
down-and-right, so a boundary running across that diagonal produces a bright side and a dark side ---
relief lighting, from the top left. Rotate the sign pattern and the light moves. That is all `emboss`
is, and every other operation in this chapter is the same trick with different numbers.

#sect("Blurring, and choosing which blur")

The reason to blur before almost anything else is that noise is high-frequency and structure is not.
An averaging window pulls a lone speck back toward its neighbours and leaves a broad stroke roughly
where it was --- but it does the same to real detail, so the question is never whether to blur but
how much, and with which family. scalacv gives you four, and they differ in what they preserve rather
than in how blurry the result looks.

#figure-table("The blur family: what each one computes, and what survives it.")[
#tbl(
  columns: (auto, 1.6fr, auto, 1.5fr),
  [*Operation*], [*What the window computes*], [*Edges*], [*Reach for it when*],
  [`boxBlur`], [plain average of the window], [smeared], [you want the cheapest possible smoothing],
  [`gaussianBlur`], [bell-weighted average, centre heaviest], [smeared], [general denoise, and before every edge detector],
  [`medianBlur`], [the median value in the window], [mostly kept], [salt-and-pepper specks],
  [`bilateralFilter`], [weighted average, but only over pixels of similar value], [kept], [you must smooth flat regions and keep boundaries sharp],
)
]

The high-level entry point is `blur(radius)`, which is a Gaussian expressed in the unit you actually
think in. A radius of 2 is a 5×5 kernel --- the side is `radius * 2 + 1`, so radius 1 is
3×3, radius 3 is 7×7. A negative radius is rejected with
`IllegalArgumentException`; a radius of 0 is the identity.

#example("Radius is half the kernel side, minus the centre pixel.")[
```scala
Image.reading("scan.jpg") { img =>
  img.gray.blur(2).write("soft.png")   // 5x5 Gaussian
}
```
]

That identity case is not free, and it is the first place native lifetime intrudes on a chapter that
looks like pure arithmetic.

#memory[
`blur(0)` copies no pixels, but it still #emph[spends] the image it was called on, exactly like every
other transform. The implementation moves the `Mat` out of the receiver's handle into a fresh `Image`
rather than handing the same handle back, so the source is left spent instead of aliased alive.
Anything else would let you hold two `Image` values over one native buffer, and the second `close()`
would be a double free. The rule is uniform: after `img.blur(n)`, for any `n`, `img` is gone.
]

When you need to name the kernel and the standard deviation yourself, `gaussianBlur` takes them
directly. Both extents must be odd and positive, or `Size(0, 0)` to let OpenCV derive the kernel from
the sigmas --- and with `Size(0, 0)` you must give a positive `sigmaX`, because otherwise there is
nothing to derive from. A `sigmaY` of 0 means "same as `sigmaX`", OpenCV's own convention and not a
degenerate value. `Size` holds two `Double`s and they are truncated toward zero on the way into
native code, so the check runs on the truncated integers: `Size(4.9, 4.9)` is rejected as an even 4×4
kernel rather than accepted as 5×5. `boxBlur` runs that same check with the zero kernel disallowed,
because it has no sigma to derive a size from.

#example("An explicit kernel and sigma, high level and mid level.")[
```scala
img.gaussianBlur(Size(5, 5), sigmaX = 1.5)              // Image
mat.gaussianBlur(Size(0, 0), sigmaX = 2.0)              // Managed[Mat]
```
]

#note[
The mid-level operation is called `boxBlur`, not `blur`, on purpose. `Image.blur` is a
radius-based #emph[Gaussian]; a `Mat` method sharing that name would silently change filter family ---
and output --- the moment a reader dropped from `image.blur(2)` to `image.mat.blur(...)`. Two
algorithms, two names.
]

#subsect("Median, and why the scan needs it")

A Gaussian averages, and an average is dragged by an outlier. A single black speck in a white field
does not disappear under a 5×5 Gaussian; it spreads into a grey smudge 5 pixels across, which is
worse, because now it survives a threshold as a blob instead of a dot.

A median has no such weakness. Sort the values in the window, take the middle one, and a lone speck
--- by definition a minority in its own neighbourhood --- is discarded outright rather than averaged
in. Edges survive too: at a boundary, more than half the window is on one side, so the median lands
on that side rather than halfway between. That is the whole case for `medianBlur` on a scanned page,
where the noise is exactly salt-and-pepper. The high-level form takes a radius like `blur` does, so
radius 1 is the 3×3 window, and unlike `blur` there is no identity: radius must be at least 1. The
mid-level form takes the kernel side directly and requires it odd and at least 3.

#example("Two spellings of the same 3x3 median.")[
```scala
img.medianBlur(1)      // Image — radius 1, so a 3x3 window
mat.medianBlur(3)      // Managed[Mat] — the side, odd and >= 3
```
]

#subsect("Bilateral, the expensive one")

`bilateralFilter` asks a second question. A Gaussian weights a neighbour by how far away it is; a
bilateral filter weights it by how far away it is #emph[and] by how different its value is. A
neighbour across a strong boundary is discounted almost to nothing, so the average never crosses the
edge: flat regions go smooth and the boundary stays as crisp as it started.

You pay for it. There is no separable shortcut and no small fixed kernel: the cost per pixel scales
with the diameter of the neighbourhood, so assume this one is an order of magnitude dearer than a
Gaussian until you have measured otherwise.

#example("Bilateral: the high-level defaults, and the mid-level call that has none.")[
```scala
img.bilateralFilter()                                            // 9, 75, 75
img.bilateralFilter(diameter = 9, sigmaColor = 75, sigmaSpace = 75)
mat.bilateralFilter(9, 75.0, 75.0)                               // no defaults here
```
]

A `diameter` of 0 or less lets OpenCV derive the neighbourhood size from `sigmaSpace`. `sigmaColor`
is the one to tune first: raise it and more distant values count as "similar", so more edges get
smoothed away; lower it and the filter becomes conservative to the point of doing nothing.

#sect("The scan, first attempt")

With one blur chosen, here is the obvious pipeline for the invoice --- and it is wrong in two ways
that are worth seeing before the fix.

#example("The version that loses the shadowed corner.")[
```scala
Image.reading("scan.jpg") { img =>
  img.gray
     .blur(2)
     .threshold(127)
     .write("ink-bad.png")
}
```
]

The first fault is the blur. A 5×5 Gaussian on 9-point text at 300 dpi is wider than the strokes it
is smoothing; the thin verticals come out grey, and the threshold then decides they are paper. The
second fault is `threshold(127)`. One cut for the whole page cannot be right for both the bright half
and the corner the lid missed: high enough to catch the ink in the shadow and the bright half fills
with paper texture; low enough to keep the bright half clean and the corner goes solid black.

Both faults have the same shape --- a fixed global decision cannot cope with something that varies
across the image. That is what `adaptiveThreshold` is for: it computes a cut per neighbourhood rather
than once for the page, which is why it is the standard preparation step before OCR.

#example("The same page, with a filter that does not smear and a cut that follows the light.")[
```scala
Image.reading("scan.jpg") { img =>
  img.gray
     .medianBlur(1)                                   // 3x3: specks go, strokes stay
     .adaptiveThreshold(blockSize = 25, c = 8, mode = Threshold.Mode.BinaryInv)
     .write("ink.png")
}
```
]

`blockSize` is the odd neighbourhood side the local threshold is computed over. It wants to be
comfortably larger than a stroke and smaller than the lighting variation --- 25 pixels is a
reasonable start for body text on a 300 dpi scan. `c` is subtracted from the local mean, so raising it
keeps less: turn it up when background texture starts coming through. `mode = Threshold.Mode.BinaryInv`
puts the ink at 255 and the paper at 0, the convention every operation in the rest of this chapter
expects. Like
`equalizeHist`, the operation is `CV_8UC1` only --- the other reason `gray` comes first --- and
`blockSize` must be odd and at least 3.

Name those arguments. The `Image` verb leads with the two you tune, `adaptiveThreshold(blockSize, c,
method, inverse)`; the mid-level `Ops` version mirrors OpenCV's own `(maxValue, method, blockSize, c,
inverse)`. The divergence cannot bite silently --- the leading parameters have different types across
the tiers, `blockSize: Int` against `maxValue: Double`, so a positional call written for one tier does
not compile against the other --- and named arguments make the question moot anyway.

The result is close, and it is not clean. Scattered through it are one- and two-pixel white specks
the median did not catch, and some strokes have hairline gaps where the paper showed through. That is
a job for a different family of operator entirely.

#sect("Edges: derivatives, and the two-threshold trick")

Before the morphology, the other reason to filter: finding where the image changes. An edge is a
place where intensity moves quickly, so an edge detector is a derivative. `sobel` is the workhorse
--- the derivative in x, in y, or a mixed higher-order one, over a kernel that also does a little
smoothing so noise does not register as a hundred tiny edges. It lives at the `Mat` tier only, as does
`laplacian`: there is no `Image.sobel`, because a raw derivative is rarely the last step. Computed
correctly it comes back 16-bit signed, and something has to bring it down to 8 bits before it is an
image you can write. So the worked example drops a tier.

#example("The horizontal derivative --- and the depth that makes it correct.")[
```scala
Mats.chain(frame)(
  _.cvtColor(ColorConversion.BgrToGray),
  _.sobel(dx = 1, dy = 0, depth = OutputDepth.Signed16),
  _.convertScaleAbs()
)
```
]

That `depth` argument is the trap the `OutputDepth` type exists to warn you about. Its default,
`OutputDepth.SameAsSource`, means "give the destination the source's depth" --- and on the commonest
input, an 8-bit unsigned image, that clips every negative derivative to zero. A derivative is
negative wherever the image gets darker left-to-right, which is one side of every edge in the frame,
so half of each edge silently disappears and nothing tells you. Compute into `OutputDepth.Signed16`,
which keeps the negative lobe, and bring it back down with `convertScaleAbs`, which scales, takes the
absolute value, and saturating-casts to 8-bit. `laplacian` --- the second derivative, and therefore
isotropic rather than directional --- has the identical trap and the identical remedy.

#memory[
Every mid-level operation allocates a fresh destination `Mat` and hands you something you own, so a
three-stage chain written naively strands two full-size buffers. `Mats.chain` exists for this shape:
it releases each intermediate the moment the next stage has produced its own output, in a `finally`,
so a stage that throws does not leak its input either. The source you pass in is borrowed and never
released --- it belongs to whoever created it. `chain` is a fold over `pipe`, the `Managed[Mat]`
extension that feeds one stage and releases its input; `pipe` is what to reach for when there is only
one stage to add.
]

There is no separate `scharr` method, because OpenCV's Scharr operator #emph[is] Sobel with a
particular 3×3 kernel, and the library exposes it the way OpenCV does: pass `kernelSize = -1`. The
check on that parameter says as much in its own words --- `sobel kernelSize must be odd and positive,
or -1 for the 3x3 Scharr kernel` --- so the one value that looks illegal is the documented one. Scharr
is the more rotationally accurate of the two at that size, so prefer it when you will use the
gradient's #emph[direction] and not only its magnitude.

#example("Scharr is a kernel size, not a method.")[
```scala
mat.sobel(dx = 1, dy = 0, kernelSize = -1, depth = OutputDepth.Signed16)
```
]

#subsect("Canny, and what the two thresholds actually do")

`canny` is not a kernel. It is a four-step algorithm that runs a Sobel internally, thins the response
down to one-pixel ridges, and then decides which of those ridges to keep --- and the deciding is
where its two thresholds come in. They are widely misread as "low detail" and "high detail" knobs,
which they are not.

The rule is #keyterm[hysteresis]. A candidate whose gradient is above `threshold2` is accepted
unconditionally: those are the strong edges. A candidate below `threshold1` is discarded outright.
Everything in between --- the weak edges --- is kept #emph[only if it is connected, through other
in-between pixels, to something already accepted].

That solves a specific problem. A single threshold on a real image is always wrong: high enough to
reject texture, and long edges break into dashes wherever they dim; low enough to keep them
continuous, and the frame fills with noise. Hysteresis lets you set the strong threshold aggressively
--- high enough that essentially nothing spurious clears it --- and then rescue the dim continuations
of the edges it did find, because a genuine edge that fades is still attached to the part of itself
that did not. So: `threshold2` is the level above which you are confident you have an edge, and
`threshold1` is between a half and a third of it.

#figure-table("Starting values for canny, for a normally-exposed 8-bit image.")[
#tbl(
  columns: (auto, auto, 1.7fr),
  [*`threshold1`*], [*`threshold2`*], [*Character*],
  [50], [150], [permissive --- fine detail and some texture; the usual first try],
  [80], [160], [the pairing this library's own examples use throughout; balanced],
  [100], [200], [conservative --- strong boundaries only, texture rejected],
  [30], [90], [low-contrast material: faded scans, fog, underexposure],
)
]

#warning[
`canny(threshold1, threshold2)` takes two `Double`s in an order nothing enforces, and swapping them
does not fail --- it quietly changes which edges survive. Name them at the call site
(`canny(threshold1 = 80, threshold2 = 160)`) whenever the pair is not obviously ordered. The result is
always `CV_8UC1` regardless of the input type, and `apertureSize` --- the internal Sobel aperture ---
accepts only 3, 5 or 7. Anything else is rejected in Scala with `IllegalArgumentException`, because
OpenCV's own response is to abort in native code.
]

#sect("Sharpening")

Sharpening is the inverse of blurring, quite literally. Take the image, take a blurred copy of it,
and the difference between them is precisely the detail the blur removed; add that difference back on
top of the original and the detail is exaggerated. The technique is #keyterm[unsharp masking], named
after the darkroom practice of using a deliberately #emph[unsharp] negative as the mask.

`sharpen(amount)` is that in one call --- `image + amount * (image - blur(image))`, implemented as a
Gaussian with `Size(0, 0)` and a sigma fixed at 3.0, followed by an `addWeighted` of `1 + amount`
against `-amount`. An `amount` of 0 is a no-op, around 1 is a firm sharpen, and a negative amount is
rejected.

#example("Unsharp masking, and the point at which it stops helping.")[
```scala
img.sharpen()          // amount = 1.0
img.sharpen(0.4)       // gentle
img.sharpen(3.0)       // haloes
```
]

Learn the failure mode by sight. Because the added detail is a difference across an edge, a large
`amount` puts a bright fringe on the bright side and a dark fringe on the dark side --- a
#keyterm[halo]. Once you can see haloes, the number is too high, and no further sharpening will
recover detail that was never in the file.

#sect("Morphology: the operators for a binary mask")

The scan's remaining problems --- isolated specks and hairline gaps --- are no longer noise in the
grey-level sense. The image is binary now: ink at 255, paper at 0. What is left is a question about
#emph[shape], and morphology is the family that answers shape questions.

Morphology also slides a window, but instead of averaging it takes an extremum. #keyterm[Erosion]
writes the minimum of the neighbourhood, so a white region survives only where the whole window fits
inside it: bright regions shrink from the boundary inward, and anything smaller than the window
disappears. #keyterm[Dilation] writes the maximum, so a white region grows outward by the window's
radius and any dark gap narrower than the window is filled. Neither is much use alone --- both resize
everything --- but their compositions are.

#figure-table("The compound operators, and what each is for on a binary mask.")[
#tbl(
  columns: (auto, 1.1fr, 1.6fr),
  [*`MorphOp`*], [*Definition*], [*Use it to*],
  [`Open`], [erode, then dilate], [delete specks smaller than the kernel, leaving everything else the size it was],
  [`Close`], [dilate, then erode], [fill holes and join gaps narrower than the kernel, leaving everything else the size it was],
  [`Gradient`], [dilation minus erosion], [outline every region --- a one-pass boundary of a mask],
  [`TopHat`], [source minus its opening], [isolate the bright detail smaller than the kernel],
  [`BlackHat`], [closing minus the source], [isolate the dark detail smaller than the kernel],
)
]

`Open` and `Close` are the two you will use constantly because each removes something without
resizing what it leaves. Erosion alone shrinks the specks #emph[and] the strokes; erosion followed by
dilation deletes the specks --- once eroded away there is nothing left for the dilation to grow back
--- and restores the strokes to roughly their original width. `Close` is the same argument reversed,
which is why it fills gaps without fattening the glyphs.

#example("The scan, finished.")[
```scala
Image.reading("scan.jpg") { img =>
  img.gray
     .medianBlur(1)                                   // sensor specks
     .adaptiveThreshold(blockSize = 25, c = 8, mode = Threshold.Mode.BinaryInv)
     .morphology(MorphOp.Open, radius = 1)            // survivors of the median
     .morphology(MorphOp.Close, radius = 1)           // rejoin broken strokes
     .write("ink.png")
}
```
]

Two knobs decide what those calls do, and there are only two. The first is `radius`, which works
much as it does for `blur`: the structuring element is `radius * 2 + 1` pixels on a side, so radius 1
is 3×3 --- and, as with `medianBlur` and unlike `blur`, it must be at least 1. Radius is the size
threshold in the most direct sense available --- `Open` at radius 1 deletes anything a 3×3 square
cannot sit inside, radius 2 anything a 5×5 cannot. On text, radius 1 is usually where you stop; radius 2 starts closing the counters in "e"
and "a".

The second is `shape`, a `MorphShape` with three cases.

#figure-table("Structuring-element shapes, and when the choice matters.")[
#tbl(
  columns: (auto, 1.2fr, 1.6fr),
  [*`MorphShape`*], [*The element*], [*Choose it when*],
  [`Rect`], [a filled square --- the default], [you have no reason to prefer another; it is the fastest],
  [`Ellipse`], [a filled disc inscribed in the square], [the shapes are round or organic and a square element would leave square corners on them],
  [`Cross`], [the centre row and centre column only], [you want to affect horizontal and vertical runs and leave diagonals alone --- table rules, scan lines],
)
]

At radius 1 the three are nearly indistinguishable --- a 3×3 disc and a 3×3 square differ by four
corner pixels. The choice starts to matter from radius 2 upward, and it matters most on `Close`:
closing a round blob with a `Rect` element leaves visible square corners on it, which is exactly the
artefact `Ellipse` avoids.

#memory[
Morphology is the one family here that needs a second native allocation --- the structuring element
itself, from `getStructuringElement`. `Ops` builds it inside `withStructuringElement`, which frees it
on every path including the throwing one, so a morphology call still leaves you owning exactly one
buffer: the destination. The kernel never reaches your code and there is nothing about it to release.
]

Two asymmetries to know. The mid-level `erode`, `dilate` and `morphology` all take an `iterations`
parameter (at least 1, applying the operator repeatedly) and the `Image` verbs do not, so on `Image`
you chain the calls or drop a tier. And there is deliberately no bare `close` method on `Image` for
`MorphOp.Close` --- `close()` already means "release the native memory", and two meanings for that
name would be an excellent way to free an image you meant to filter.

#sect("What happens at the edge of the image")

Centre a 5×5 kernel on the pixel at `(0, 0)` and two of its rows and two of its columns hang off the
image. Every filter in this chapter has to invent those pixels, and `BorderType` is how you say what
to invent.

#figure-table("Border modes, shown on the pixels `a b c d | ...` at the left edge.")[
#tbl(
  columns: (auto, 1fr, 1.6fr),
  [*`BorderType`*], [*Invents*], [*Notes*],
  [`Constant`], [`0 0 0 0 | a b c d`], [a fixed colour, black by default; the default for `pad`, `border` and `rotated`],
  [`Replicate`], [`a a a a | a b c d`], [the edge pixel, repeated],
  [`Reflect`], [`d c b a | a b c d`], [mirrored, with the edge pixel duplicated],
  [`Reflect101`], [`e d c b | a b c d`], [mirrored #emph[about] the edge pixel, so it is not duplicated --- the default for every filter that takes a border],
  [`Wrap`], [`w x y z | a b c d`], [the opposite edge, as if the image tiled --- #emph[not valid for filters]],
)
]

`Reflect101` is the filter default for a good reason: it introduces no artificial gradient at the
boundary. `Constant` puts a hard black step just outside the image, and an edge detector will happily
report a bright edge around your whole frame that is not in your data. `Replicate` is the alternative
when reflection would be wrong --- a gradient image, where the mirrored continuation runs backwards.

Four filters take a `border` parameter: `gaussianBlur`, `boxBlur`, `sobel` and `laplacian`, each
defaulting to `Reflect101`. `medianBlur`, `bilateralFilter` and `canny` do not expose one, and
neither does any high-level filter --- `Image.pad` and `Image.border` take a `borderType`, defaulting
to `Constant` filled with `Scalar.Black`, but `Image.blur` and `Image.gaussianBlur` do not, so
changing a filter's border means dropping that stage to the `Mat` tier.

#warning[
`BorderType.Wrap` is valid for `pad`, `border` and `rotated`, and invalid for `gaussianBlur`,
`boxBlur`, `sobel` and `laplacian`. Passing it to one of the four filters throws
`IllegalArgumentException` from `BorderType.requireFilterSupport` before any native call, naming the
operation you asked for.
]

#sidebar("One int, two domains")[
That last restriction is not scalacv being fussy. OpenCV packs two different sets of accepted values
into one `int`, and `BorderType` is the union of them: `copyMakeBorder` (behind `pad` and `border`)
and `warpAffine` (behind `rotated`) honour all five modes, while the `imgproc` filter family does not.
Every filter routes through `cv::FilterEngine::init`, which asserts
`columnBorderType != BORDER_WRAP` and aborts.

Leaving that to native code would be tolerable if it were consistent. It is not. Verified against
OpenCV 4.13: `GaussianBlur` with `BORDER_WRAP` #emph[silently ignores] the mode on `CV_8U` input ---
the SIMD path never reaches the assertion --- and aborts on `CV_32F`. So the same call succeeds
against the 8-bit frames it was developed on and crashes the first time it meets a float image, in
native code, with no Scala stack frame in sight. Checking at the Scala boundary makes both depths fail
identically and names the parameter you actually passed.

Two types --- a filter border without `Wrap`, widening into a transform border with it --- would make
the mistake unrepresentable, but that is a breaking change to four public signatures, so for now the
check is a runtime one.
]

#sect("What filtering costs")

Cost scales with the kernel, and not linearly. A naive #emph[k]×#emph[k] convolution does
#emph[k]#super[2] multiply-adds per pixel, so going from 3×3 to 9×9 is nine times the arithmetic, not
three.

The saving grace is #keyterm[separability]. A Gaussian kernel factors into a horizontal pass and a
vertical pass over one-dimensional kernels, giving the same result for 2#emph[k] multiply-adds per
pixel instead of #emph[k]#super[2]. OpenCV does this for you --- `GaussianBlur`, `blur` and `Sobel`
all run separably --- so a Gaussian's cost grows roughly linearly with the kernel side. `medianBlur`
and `bilateralFilter` do not factor that way, which is the other half of why bilateral filtering is
the expensive one.

Real numbers, from this project's harness on one developer machine: the canonical
`gray` → `blur` → `canny` chain measured 145.3 µs at 640×480, 574.2 µs at 1920×1080, and 7556 µs at
3840×2160. That last figure is more than 13× the 1080p one for 4× the pixels --- above some size the
working set stops fitting in cache and the curve bends. One machine's microseconds, not yours: the
ratios reproduce, the absolutes do not.

#tip[
Before you tune a filter chain for speed, check what OpenCV is already doing on your machine.
`ConfigProbeBench` prints `useOptimized`, `getNumThreads`, `getNumberOfCPUs` and the parallel, IPP and
OpenCL lines of the build information, then measures how a bilateral filter on a
1280×720 scene scales as `Core.setNumThreads` is stepped through 1, 2, 4 and your core
count. The answer is a property of your machine, so run it and read your own.
]

The project's own rule, worth adopting: no optimisation without a measured delta #emph[and] a
bit-identical output hash. A change that is faster because it quietly computes something different is
not an optimisation, and a timing number alone cannot tell the two apart. Chapter 35 is where the
harness, the reading rule for a confidence interval, and the optimisation that was measured and then
deliberately #emph[not] built all live.

#sect("Where this leads")

The scan is now a binary mask: ink at 255, paper at 0, specks gone, strokes joined. Every step was in
service of that --- the median to survive the sensor, the local threshold to survive the lighting,
the opening and closing to survive both.

Chapter 10, #emph[Geometric Transforms], takes the other half of the imgproc surface --- the
operations that move pixels rather than recombine them, and the interpolation choice that decides
what a resized image loses.

This chapter also leaned on `adaptiveThreshold` ahead of its proper treatment. Chapter 11,
#emph[Thresholding and Colour Segmentation], gives the cut itself the attention it deserves: the
`Threshold.Mode` cases, Otsu and Triangle and the value they compute for you, how to choose between
a global cut, an automatic one and the per-neighbourhood variant used here --- and then builds masks
a second way entirely, from colour in HSV, handing them straight back to the `Open` and `Close` you
have just met.
