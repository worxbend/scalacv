#import "../lib/book.typ": *

#appendix("Operations Reference", subtitle: [Every operation the library exposes, grouped by the job it does.])

There are two moments when you want a reference like this one. The first is when you know what you
want to do to an image and cannot remember what the library calls it --- you want the word for
"stretch the values so a float disparity map is visible", and you want it in five seconds, not after
scrolling a Scaladoc index. The second is the more dangerous one: you are fairly sure a method
exists, you write the call, and it compiles because Scala found something else with a similar name
on a different tier. `boxBlur` and `blur` are different filters. `Image.adaptiveThreshold` and
`Mat.adaptiveThreshold` take their arguments in a different order on purpose. `estimatePose` means
one thing for a human body and something else entirely for an ArUco marker.

So the tables below answer three questions per row, and nothing else. Does this operation exist,
spelled this way? What does it take, and what comes back? Which chapter explains why you would want
it? The parameter lists are abbreviated to the shape you need at a call site: defaults are shown
where getting one wrong changes the result, enum defaults are written by their case name alone
(`shape = Rect`, not `shape = MorphShape.Rect`, which is what the source says), and the trailing
`border`/`lineType` arguments that almost nobody passes are often elided with an ellipsis. Appendix C has
the full enumerations; the generated Scaladoc has the exact declarations.

Two tiers appear throughout, and the Signature column always says which. A row whose signature
begins `img.` is a method on `Image`, the owned, move-semantic type from Chapter 4. A row beginning
`mat.` is an extension method on `org.opencv.core.Mat`, brought into scope by `import scalacv.*` and
returning a `Managed[Mat]` you own. The `Image` tier is a curated subset: it exposes the operations a
pipeline usually wants, with the arguments a pipeline usually tunes. Everything the `Image` tier
omits --- `sobel`, `laplacian`, `boxBlur`, `absdiff`, every Hough transform, most of the drawing
primitives --- is on the `Mat` tier only, reachable through the borrowed `img.mat` in one step.

The grouping is by the job, not by the source file, because that is how you look something up. An
operation that appears on both tiers gets one row with both signatures separated by a middle dot,
since the difference between them is almost always a parameter or two rather than a difference in
what happens to the pixels. Where the two tiers genuinely disagree --- `adaptiveThreshold`'s argument
order, `threshold`'s return type --- they get a row each, adjacent, so the disagreement is visible
rather than buried in a footnote.

#sect("What each group needs on the classpath")

Every operation in this appendix ships in the core artifact, `com.worxbend::scalacv:0.4.1`, plus the
platform natives from Chapter 2. There is exactly one exception, marked in its row: `img.draw(picture)`
in the Drawing table comes from `com.worxbend::scalacv-graphs`. Nothing here needs
`scalacv-vision`; the detector verbs that live there --- `faces`, `qrCodes`, `arucoMarkers`,
`estimatePose`, `detectHaar`, `markFaces`, `drawSkeleton`, `drawTracks` --- are documented in
Chapters 24 through 34 rather than repeated here, because their arguments are models and detectors
rather than pixels.

Core uses `import scalacv.*`; the graphics extension also needs `import scalacv.graphs.*`.
An import cannot conjure a module you have
not declared as a dependency: if a call does not resolve, check the build file before you check the
spelling.

#sect("How to read a row")

The Signature column is abbreviated consistently, and knowing how saves you a trip to the
Scaladoc. Parameter names are exact --- they are what you will type in a named argument, and this
library expects you to use named arguments wherever two adjacent parameters share a type. Defaults
are shown only where the default is a decision: `depth = Unsigned8` is there because leaving it alone
changes the pixel type of the result, while the `border = Reflect101` that trails half the filters is
elided as an ellipsis because almost no caller sets it and the ones who do already know it exists.
Enum defaults lose their type prefix, so a row reads `shape = Rect` where the source says
`shape = MorphShape.Rect`. And return types appear only when they are not the obvious one: an
`Image` method with no stated return gives you an `Image`, a `Mat` extension with none gives you a
`Managed[Mat]`.

The What-it-does column carries the preconditions, because a precondition is the thing most likely
to turn a correct-looking call into an exception at run time. They come in two kinds and the wording
tells them apart. A rule stated about an #emph[argument] --- an odd kernel, a positive scale, a
radius of at least 1, a range on a normalised parameter --- is a `require` in the source: it throws
`IllegalArgumentException` on the JVM side, before anything reaches JNI, and the message names the
value you passed. A rule stated about the #emph[pixels] --- "`CV_8UC1` only", "needs 8-bit
3-channel input" --- is almost never checked here, because the library would have to duplicate
OpenCV's own type algebra to do it; hand one of those the wrong Mat and you get a
`CvError.NativeCall` quoting a C++ assertion instead. Two exceptions are worth knowing: an empty Mat
is rejected up front by `findContours` and by every drawing primitive, and the Hough transforms
check `CV_8UC1` themselves, because handing them anything but a Canny result is common enough that
the native message was worth intercepting. Chapter 6 draws the line; the tables assume it.

The See column names one chapter, the one that explains the operation rather than merely uses it. A
few rows name two, separated by a comma, where the operation is introduced in one place and earns
its living in another --- `absdiff` is a `Core` call explained by motion detection, `undistort` is a
geometry call explained by calibration. A letter means an appendix.

#sidebar("The four kinds, in one line each")[
Chapter 5 established the vocabulary; the tables assume it. A #emph[query] borrows --- the image
survives the call and you get plain data back. A #emph[transform] consumes the receiver and returns
a fresh `Image`; the old handle throws `IllegalStateException` if you touch it again. A #emph[draw]
also consumes, but mutates the pixels in place rather than allocating a destination, which is why
annotating a 4K frame costs no allocation per overlay. A #emph[terminal] consumes and releases.

Everything in the tables below is a transform unless the row says otherwise. Where an operation
takes a second image --- a mask, a background, a blend partner --- that second image is
#emph[borrowed], never consumed, and closing it stays your job.
]

#sect("Constructing and destroying an image")

The constructors are all on the `Image` companion; the destructors are `close`, the two terminals in
the I/O table, and `managed`, which is a destructor only in the sense that it ends this `Image`'s
ownership without freeing anything. Of the six ways in, `reading` is the synchronous scoped form:
it closes on success, on failure and on exception, and it runs the body inside `Cv.attempt`, so a
transform that throws deep in a chain comes back as a `Left` instead of escaping past a signature
that promised an `Either`. Prefer it unless you have a reason to thread the ownership yourself.

#figure-table("Making an Image, and ending one.")[
#tbl(columns: (auto, 1.35fr, 1.35fr, auto),
  [*Operation*], [*Signature (abbreviated)*], [*What it does*], [*See*],
  [`blank`], [`Image.blank(width, height, color = Black, channels = 3)`], [A filled canvas. `channels` must be 1, 3 or 4; a non-positive extent throws `IllegalArgumentException`.], [4],
  [`read`], [`Image.read(path, flags = Color): Either[CvError, Image]`], [Reads a file. `Left` distinguishes an unusable path, a missing file, a directory, an empty file, an unreadable one and undecodable bytes.], [7],
  [`decode`], [`Image.decode(bytes, flags = Color): Either[CvError, Image]`], [Decodes bytes already in memory --- an HTTP body, a BLOB, a fixture.], [7],
  [`reading`], [`Image.reading(path, flags = Color)(use): Either[CvError, A]`], [Reads, runs `use`, closes on every path. The body runs inside `Cv.attempt`, so a transform's throw becomes a `Left`.], [5],
  [`wrap`], [`Image.wrap(handle: Managed[Mat]): Image`], [Adopts an existing handle. Ownership transfers; do not also release it yourself.], [4],
  [`fromBufferedImage`], [`Image.fromBufferedImage(bi): Image`], [From AWT. Always 3-channel BGR.], [7],
  [`copy`], [`img.copy: Image`], [Query. An independent deep copy --- the way to branch a chain.], [4],
  [`managed`], [`img.managed: Managed[Mat]`], [Spends this `Image` and hands the handle on. Frees nothing.], [5],
  [`close`], [`img.close(): Unit`], [Releases now. Idempotent, and `AutoCloseable`, so `Using` works.], [5],
)
]

#memory[
`img.mat` borrows and `img.managed` transfers, and the difference is the whole of this appendix's
safety story. A Mat obtained from `mat` is still owned by its `Image` --- release it and the
`Image`'s next call reads freed memory. A `Managed[Mat]` obtained from `managed` is yours: `use` it,
`release()` it, or hand it back to `Image.wrap`. Letting one escape without any of those three is
the one leak the type system will not catch for you.
]

#sect("Queries")

Queries borrow. None of these spends the image, so you can read a dimension in the middle of a chain
without taking a `copy` first, and none of them is affected by move semantics at all. The two that
cross the boundary out of native memory --- `toBufferedImage` and `contours` --- copy rather than
alias, which is why their results stay valid after the image they came from has been released.

#figure-table("Reading an image without consuming it.")[
#tbl(columns: (auto, 1.35fr, 1.35fr, auto),
  [*Operation*], [*Signature (abbreviated)*], [*What it does*], [*See*],
  [`width` `height`], [`img.width: Int`, `img.height: Int`], [Pixel dimensions, read from the Mat's `cols`/`rows`.], [4],
  [`size`], [`img.size: Size`], [The same pair as a `Size`, whose extents are `Double`.], [4],
  [`channels`], [`img.channels: Int`], [1 grey, 3 BGR, 4 BGRA.], [4],
  [`isEmpty`], [`img.isEmpty: Boolean`], [True for a Mat with no pixel data.], [4],
  [`mat`], [`img.mat: Mat`], [Borrows the raw handle for anything the `Image` tier does not wrap.], [4, A],
  [`toBufferedImage`], [`img.toBufferedImage: BufferedImage`], [A copy, for AWT and for notebook display. 8-bit only; 4 channels are flattened to BGR.], [7],
  [`contours`], [`img.contours(retrieval = External, approximation = Simple): Seq[Contour]`], [Outlines of a binary image, copied out of native memory as plain data.], [12],
  [`toString`], [`img.toString: String`], [`Image(640x480, 3ch)`, or `Image(<closed>)` once released --- it never dereferences a freed handle.], [4],
)
]

#sect("Colour and channels")

Colour conversions change the channel count to whatever the conversion implies, so `channels` after
`gray` is 1 and after `convert(ColorConversion.BgrToBgra)` is 4. Everything in this group is a
transform, and several of the tone operations are built from the ones above them: `gamma` and
`posterize` are both a 256-entry lookup table, `saturate` is a blend against a grey copy, `sepia` and
`temperature` are 3×3 colour matrices. That matters when you are counting allocations rather than
lines of code --- `saturate` costs two intermediate Mats, not one.

#figure-table("Colour space, tone and per-channel work.")[
#tbl(columns: (auto, 1.35fr, 1.35fr, auto),
  [*Operation*], [*Signature (abbreviated)*], [*What it does*], [*See*],
  [`gray`], [`img.gray`], [BGR to single-channel grey --- `convert(BgrToGray)` under a shorter name.], [8],
  [`convert`], [`img.convert(conversion)` · `mat.cvtColor(conversion)`], [Any of the ten `ColorConversion` cases.], [8],
  [`toHsv`], [`img.toHsv`], [BGR to HSV, the space to threshold colour in.], [11],
  [`invert`], [`img.invert` · `mat.bitwiseNot()`], [Bitwise NOT: `255 - v` per channel for 8-bit.], [8],
  [`adjust`], [`img.adjust(brightness = 0, contrast = 1.0)`], [Linear brightness and contrast in one pass, via `convertScaleAbs`.], [15],
  [`convertScaleAbs`], [`mat.convertScaleAbs(alpha = 1, beta = 0)`], [Scale, take the absolute value, saturating-cast to 8-bit. The companion to a `Signed16` Sobel.], [9],
  [`normalize`], [`img.normalize(min = 0, max = 255, depth = Unsigned8)`], [Min-max stretch. The default depth is what makes a float or 16-bit result displayable; pass `SameAsSource` to keep precision. The `Mat` twin names the bounds `alpha`/`beta`.], [15],
  [`gamma`], [`img.gamma(g)`], [Gamma correction through a 256-entry LUT. `g < 1` darkens, `g > 1` lifts. Must be positive.], [15],
  [`saturate`], [`img.saturate(factor)`], [`0` is grey (still 3-channel), `1` unchanged, above that vivid. Negative throws.], [15],
  [`temperature`], [`img.temperature(shift)`], [Warm above zero, cool below. `shift` must lie in `[-1, 1]`.], [15],
  [`channel`], [`img.channel(index)` · `mat.extractChannel(index)`], [One channel as its own single-channel image. An out-of-range index throws.], [8],
  [`colorMap`], [`img.colorMap(map)`], [False-colours a single-channel image through one of the ten `Colormap` cases. Needs 8-bit input.], [15],
  [`equalizeHist`], [`img.equalizeHist` · `mat.equalizeHist()`], [Histogram equalisation. `CV_8UC1` only, so usually after `gray`.], [9],
)
]

#sect("Filtering and smoothing")

The radius-based names on the `Image` tier all derive an odd kernel as `2 * radius + 1`, so
`blur(2)` is a 5×5, and `medianBlur(1)` a 3×3. The `Mat` tier takes the kernel instead and validates
it: `gaussianBlur` and `boxBlur` want a `Size` whose extents are odd and positive, with `Size(0, 0)`
allowed for `gaussianBlur` alone, which then derives the kernel from the sigma; `medianBlur` wants
the odd side length as a bare `Int`.

#figure-table("Smoothing, and the two filters that keep edges.")[
#tbl(columns: (auto, 1.35fr, 1.35fr, auto),
  [*Operation*], [*Signature (abbreviated)*], [*What it does*], [*See*],
  [`blur`], [`img.blur(radius)`], [Radius-based Gaussian. `radius = 0` is the identity but still spends the receiver; negative throws.], [9],
  [`gaussianBlur`], [`img.gaussianBlur(kernel, sigmaX = 0, sigmaY = 0)` · `mat.gaussianBlur(kernel, sigmaX = 0, sigmaY = 0, border = Reflect101)`], [Full control. A zero sigma means "derive it from the kernel"; a zero kernel means "derive it from the sigma"; both zero throws.], [9],
  [`boxBlur`], [`mat.boxBlur(kernel, anchor = Point(-1, -1), border = Reflect101)`], [Normalised box filter --- a different family from `blur`, which is why it has a different name.], [9],
  [`medianBlur`], [`img.medianBlur(radius)` · `mat.medianBlur(ksize)`], [Kills salt-and-pepper noise without smearing edges. `radius` starts at 1; `ksize` must be odd and at least 3, so there is no do-nothing value.], [9],
  [`bilateralFilter`], [`img.bilateralFilter(diameter = 9, sigmaColor = 75, sigmaSpace = 75)`], [Edge-preserving smooth. Markedly slower than a Gaussian. A `diameter` of 0 or less lets OpenCV derive it.], [9],
  [`edgePreserving`], [`img.edgePreserving(strength = 60, detail = 0.4f)`], [Flattens texture while holding edges --- the basis of the painterly filters.], [15],
  [`sharpen`], [`img.sharpen(amount = 1.0)`], [Unsharp mask: adds back `amount` × (image − its blur). Overdo it and edges halo.], [9],
)
]

#sect("Edges and gradients")

`canny` is the one everyone reaches for, and its two thresholds are both `Double` and silently
swappable --- name them at the call site. The derivative operators live on the `Mat` tier only,
because their useful output is not 8-bit and the `Image` tier has no way to say so.

#figure-table("Derivatives and edge maps.")[
#tbl(columns: (auto, 1.35fr, 1.35fr, auto),
  [*Operation*], [*Signature (abbreviated)*], [*What it does*], [*See*],
  [`canny`], [`img.canny(threshold1, threshold2, apertureSize = 3, l2Gradient = false)`], [Hysteresis edge detection; the result is always `CV_8UC1`. `threshold1` is the weak level. `apertureSize` must be 3, 5 or 7.], [9],
  [`sobel`], [`mat.sobel(dx, dy, kernelSize = 3, depth = SameAsSource, scale = 1, delta = 0, …)`], [First or higher derivative. `kernelSize = -1` selects the 3×3 Scharr kernel. Leave `depth` alone on an 8-bit image and the negative lobe is clipped away.], [9],
  [`laplacian`], [`mat.laplacian(kernelSize = 1, depth = SameAsSource, scale = 1, delta = 0, …)`], [Second derivative. `kernelSize = 1` is the 3×3 aperture OpenCV special-cases.], [9],
  [`absdiff`], [`mat.absdiff(other): Managed[Mat]`], [Per-element `|self - other|`. `other` is borrowed. The basis of frame-difference motion detection.], [21],
)
]

#sect("Morphology")

A morphological operation needs a structuring element, and both tiers build one for you from a
radius: the element is `2 * radius + 1` on a side, in the `MorphShape` you name. The `Mat` tier adds
`iterations`; both throw on a radius below 1.

#figure-table("Erosion, dilation and the compound operators.")[
#tbl(columns: (auto, 1.35fr, 1.35fr, auto),
  [*Operation*], [*Signature (abbreviated)*], [*What it does*], [*See*],
  [`erode`], [`img.erode(radius = 1, shape = Rect)` · `mat.erode(radius = 1, shape = Rect, iterations = 1)`], [Shrinks bright regions; clears small bright specks.], [9],
  [`dilate`], [`img.dilate(radius = 1, shape = Rect)` · `mat.dilate(radius = 1, shape = Rect, iterations = 1)`], [Grows bright regions; fills small dark gaps.], [9],
  [`morphology`], [`img.morphology(op, radius = 1, shape = Rect)` · `mat.morphology(op, radius = 1, shape = Rect, iterations = 1)`], [The compound operators: `Open`, `Close`, `Gradient`, `TopHat`, `BlackHat`. There is no bare `close` method, because `close()` already releases.], [9],
)
]

#sect("Thresholding and masks")

The `Image` tier's `threshold` discards the level OpenCV computed, which is fine for a fixed value
and wrong for `Threshold.otsu()` --- that number is usually the reason you called it. The `Mat` tier
returns it as a `ThresholdResult`.

#figure-table("Binarising, masking and compositing.")[
#tbl(columns: (auto, 1.35fr, 1.35fr, auto),
  [*Operation*], [*Signature (abbreviated)*], [*What it does*], [*See*],
  [`threshold`], [`img.threshold(value, maxValue = 255, kind = Binary)`], [Fixed or automatic binarisation. Drops the computed level.], [11],
  [`threshold`], [`mat.threshold(value, maxValue = 255, kind = Binary): (Managed[Mat], ThresholdResult)`], [The same, keeping the level --- which for `Threshold.otsu()` and `Threshold.triangle()` is the answer.], [11],
  [`adaptiveThreshold`], [`img.adaptiveThreshold(blockSize = 11, c = 2.0, method = Gaussian, inverse = false)`], [Per-neighbourhood threshold, for uneven lighting. `CV_8UC1` only; `blockSize` odd and at least 3.], [11],
  [`adaptiveThreshold`], [`mat.adaptiveThreshold(maxValue = 255, method = Gaussian, blockSize = 11, c = 2.0, inverse = false)`], [The same operation in OpenCV's own argument order. Use named arguments and the divergence stops mattering.], [11],
  [`inRange`], [`img.inRange(lo, hi)` · `mat.inRange(lo, hi)`], [A `CV_8UC1` mask, 0 or 255, of the pixels whose every channel lies in range. Usually run on HSV.], [11],
  [`applyMask`], [`img.applyMask(mask)` · `mat.masked(mask)`], [Keeps pixels where the mask is non-zero, blacks out the rest. The mask is borrowed.], [11],
  [`blend`], [`img.blend(other, weight = 0.5)`], [`this * weight + other * (1 - weight)`. The weight is #emph[this] image's share; outside `[0, 1]` it throws. `other` is borrowed and must match in size and type.], [15],
  [`addWeighted`], [`mat.addWeighted(alpha, other, beta, gamma = 0)`], [The unconstrained form: `self * alpha + other * beta + gamma`.], [9],
)
]

#sect("Geometry: resize, crop, rotate, warp")

`resize` on the `Image` tier takes no interpolation argument and always uses `Interpolation.Linear`;
`resizeTo` and `scale` take one. Sizes are `Double` and truncate toward zero on the way into native
code, which is why a computed target that lands between 0 and 1 is rejected here rather than
aborting inside OpenCV.

#figure-table("Moving pixels around the plane.")[
#tbl(columns: (auto, 1.35fr, 1.35fr, auto),
  [*Operation*], [*Signature (abbreviated)*], [*What it does*], [*See*],
  [`resize`], [`img.resize(width, height)`], [Absolute pixel size. Always linear interpolation.], [10],
  [`resizeTo`], [`img.resizeTo(size, interpolation = Linear)` · `mat.resize(size, interpolation = Linear)`], [Absolute resize with the interpolation of your choice. A target that truncates to under 1 pixel throws.], [10],
  [`scale`], [`img.scale(factor, interpolation = Linear)` · `mat.scaled(fx, fy, interpolation = Linear)`], [Factor-based. The `Mat` form takes independent axes and checks the result against the receiver's own extent, because OpenCV rounds half to even.], [10],
  [`crop`], [`img.crop(rect)`], [An independent copy, not an aliasing view. The rectangle must lie inside the image.], [10],
  [`flip`], [`img.flip(how)` · `mat.flip(flip)`], [Mirror: `Horizontal`, `Vertical`, `Both`.], [10],
  [`rotate`], [`img.rotate(rotation)` · `mat.rotate(rotation)`], [Lossless quarter turn --- exact pixels, no interpolation.], [10],
  [`rotate`], [`img.rotate(degrees, scale = 1.0)` · `mat.rotated(degrees, scale = 1.0, interpolation = Linear, …)`], [Arbitrary angle, canvas expanded so no corner clips. A positive `degrees` turns the same way as `Rotation.CounterClockwise`, but it resamples, so prefer the quarter-turn form when the angle is a right angle.], [10],
  [`pad`], [`img.pad(size, borderType = Constant, color = Black)`], [A uniform border on all four sides.], [10],
  [`border`], [`img.border(top, bottom, left, right, borderType = Constant, color = Black)` · `mat.border(…)`], [Independent widths per side. Negative widths throw.], [10],
  [`undistort`], [`img.undistort(intrinsics)` · `mat.undistorted(intrinsics)`], [Maps out the lens bend using calibrated `Intrinsics`. A plain copy when the distortion vector is empty.], [31],
  [`deskew`], [`img.deskew(maxAngle = 45.0)` · `mat.deskew(maxAngle = 45.0)`], [Finds the dominant text tilt and rotates upright, filling the corners white. A skew beyond `maxAngle` is treated as a misread and left alone.], [33],
)
]

#sect("Photographic and stylisation")

Five of these wrap `org.opencv.photo`, and the three stylisation ones --- `stylize`, `sketch`,
`enhance` --- want 8-bit 3-channel input; hand one a greyscale image and it fails in native code
rather than at the call site. `filter` is the composition point: a `Filter` is a named
`Image => Image`, so your own are as first-class as the seventeen built-ins.

#figure-table("Looks, repairs and composites.")[
#tbl(columns: (auto, 1.35fr, 1.35fr, auto),
  [*Operation*], [*Signature (abbreviated)*], [*What it does*], [*See*],
  [`stylize`], [`img.stylize(strength = 60, detail = 0.45f)`], [A smooth painterly cartoon, via edge-aware smoothing.], [15],
  [`sketch`], [`img.sketch(strength = 60, detail = 0.07f, shade = 0.02f)` · `mat.pencilSketch(…)`], [Colour pencil-sketch rendering.], [15],
  [`enhance`], [`img.enhance(strength = 10, detail = 0.15f)` · `mat.detailEnhance(…)`], [Boosts local contrast and texture.], [15],
  [`sepia`], [`img.sepia`], [Sepia tone, through a 3×3 colour matrix.], [15],
  [`emboss`], [`img.emboss`], [Directional convolution --- a raised-relief look.], [15],
  [`posterize`], [`img.posterize(levels)`], [Quantises to `levels` tones per channel. `levels` must be in `[2, 256]`.], [15],
  [`inpaint`], [`img.inpaint(mask, radius = 3.0)`], [Fills the region under the mask from its surroundings --- a scratch, an object, a watermark. The mask is borrowed.], [15],
  [`seamlessCloneInto`], [`img.seamlessCloneInto(background, mask, center)`], [Poisson clone of this image into `background`. Both extra images are borrowed; the result is background-sized.], [15],
  [`filter`], [`img.filter(f: Filter)`], [Applies a named filter. `Filter.all` holds the seventeen built-ins, in order from `Filter.grayscale` to `Filter.dramatic`.], [15],
  [`Filter`], [`Filter(name)(run)` · `f.andThen(next)`], [Names any `Image => Image` as a filter, and composes two.], [15],
)
]

#sect("Drawing")

Drawing is the one family that mutates rather than allocating: OpenCV has no out-of-place rasteriser,
and cloning a frame per overlay is exactly what a video loop cannot afford. The `Image` tier wraps
five primitives and consumes the receiver each time; the `Mat` tier has the full set, returns `Unit`,
and leaves the `Image` that lent you the Mat alive. That is how you draw a line on an `Image`: there
is no `img.drawLine`, so reach through `img.mat`.

#figure-table("Rasterising onto an image you already own.")[
#tbl(columns: (auto, 1.35fr, 1.35fr, auto),
  [*Operation*], [*Signature (abbreviated)*], [*What it does*], [*See*],
  [`drawRect`], [`img.drawRect(rect, color = White, thickness = Default)` · `mat.drawRect(…, lineType = Connected8)`], [An axis-aligned rectangle. `Thickness.Filled` makes it solid.], [14],
  [`drawRects`], [`img.drawRects(rects, color = Green, thickness = Default)`], [Many rectangles in one pass --- detector boxes, ROIs.], [14],
  [`drawCircle`], [`img.drawCircle(center, radius, color = White, thickness = Default)` · `mat.drawCircle(…)`], [A circle. A negative radius throws.], [14],
  [`drawText`], [`img.drawText(text, at, color = White, scale = 1.0)` · `mat.drawText(text, at, color = White, font = Simplex, scale = 1.0, thickness = Default, …)`], [Hershey vector text, anchored on the #emph[baseline's] left end, not the top-left. Non-ASCII draws as `?`.], [14],
  [`drawContours`], [`img.drawContours(contours, color = White, thickness = Default)` · `mat.drawContours(…)`], [Renders what `findContours` returned. `Thickness.Filled` turns them back into a mask.], [12, 14],
  [`drawLine`], [`mat.drawLine(from, to, color = White, thickness = Default, lineType = Connected8)`], [A straight line. Coordinates outside the image are clipped, not rejected.], [14],
  [`drawArrow`], [`mat.drawArrow(from, to, …, tipLength = 0.1)`], [A line with a head at `to`, sized as a fraction of the line.], [14],
  [`drawPolyline`], [`mat.drawPolyline(points, closed = true, …)`], [A connected run of segments. An empty `points` draws nothing rather than failing.], [14],
  [`fillPolygon`], [`mat.fillPolygon(points, color = White, lineType = Connected8)`], [Fills the implicitly closed outline, even-odd rule.], [14],
  [`drawSegments`], [`mat.drawSegments(segments, …)`], [The renderer for a `houghLinesP` result.], [13, 14],
  [`textSize`], [`Draw.textSize(text, font = Simplex, scale = 1.0, thickness = Default): TextMetrics`], [Query. Measures without drawing. A background box needs `size.height + baseline` to enclose descenders.], [14],
  [`draw`], [`img.draw(picture: Picture)` --- needs `scalacv-graphs`], [Renders a whole scene graph in one call.], [16],
)
]

#memory[
`drawPolyline`, `fillPolygon` and `drawContours` reach OpenCV through the generated Java binding's
`Converters.vector_vector_Point_to_Mat`, which allocates one `Mat` per polygon plus one for the outer
vector and releases none of them. scalacv frees the `MatOfPoint`s it built itself, in a `finally`,
but that upstream residue cannot be fixed from here. It is bounded per call and unbounded across a
video loop --- so if you annotate every frame with contours, draw them from as few calls as you can,
and watch RSS rather than heap.
]

#sect("Contours and shape measurement")

`findContours` copies every outline out of native memory and frees the `MatOfPoint`s before it
returns, so a `Contour` is ordinary immutable Scala data that outlives the image it came from. Its
measurements are `lazy val`s: reading three of them off one contour materialises the point
conversion once.

#figure-table("Finding outlines and measuring them.")[
#tbl(columns: (auto, 1.35fr, 1.35fr, auto),
  [*Operation*], [*Signature (abbreviated)*], [*What it does*], [*See*],
  [`findContours`], [`mat.findContours(retrieval = External, approximation = Simple): Seq[Contour]`], [Outlines of a binary image. Single-channel 8-bit input; the hierarchy is not exposed.], [12],
  [`contours`], [`img.contours(retrieval = External, approximation = Simple): Seq[Contour]`], [Query. The same call from the `Image` tier, without spending the image.], [12],
  [`points`], [`contour.points: Seq[Point]`], [The outline itself. `Simple` collapses straight runs, so a rectangle is four points.], [12],
  [`boundingRect`], [`contour.boundingRect: Rect`], [OpenCV's upright box, #emph[inclusive] of the extreme pixels.], [12],
  [`area`], [`contour.area: Double`], [Shoelace area, measured between boundary pixel centres --- a filled 100×50 rectangle reports 4851, not 5000. Always non-negative.], [12],
  [`perimeter`], [`contour.perimeter: Double`], [Closed arc length.], [12],
  [`centroid`], [`contour.centroid: Option[Point]`], [Centre of mass from image moments. `None` when the area is zero.], [12],
  [`convexHull`], [`contour.convexHull: Contour`], [The tightest convex outline, whose vertices are points of this contour.], [12],
  [`approx`], [`contour.approx(epsilon, closed = true): Contour`], [Ramer--Douglas--Peucker simplification; `epsilon` is usually a small fraction of the perimeter.], [12],
  [`area`], [`rect.area: Long`], [Rectangle area. `Long`, because `width * height` overflows an `Int` past a 46340-pixel side. `rect.topLeft` and `rect.bottomRight` give the corners as `Point`s.], [8, 12],
  [`distanceTo`], [`point.distanceTo(other): Double`], [Euclidean distance, via `math.hypot`.], [8],
)
]

#sect("Hough")

All three transforms demand a non-empty `CV_8UC1` image --- the output of `canny`, typically --- and
say so with an `IllegalArgumentException` rather than aborting in native code. `threshold` is a vote
count, not a fraction, so it scales with image size: a threshold tuned on a 400-pixel thumbnail is
wrong on the 4000-pixel original. There is no circle transform here; Chapter 13 shows
`Imgproc.HoughCircles` through the low-level API, and says why the wrapper stops at lines.

#figure-table("The line transforms, and the types they return.")[
#tbl(columns: (auto, 1.35fr, 1.35fr, auto),
  [*Operation*], [*Signature (abbreviated)*], [*What it does*], [*See*],
  [`houghLines`], [`mat.houghLines(threshold, rho = 1.0, theta = Pi/180, srn = 0.0, stn = 0.0, minTheta = 0.0, maxTheta = Pi): Seq[PolarLine]`], [Infinite lines in Hesse normal form. `theta` is the angle of the #emph[normal].], [13],
  [`houghLinesP`], [`mat.houghLinesP(threshold, rho = 1.0, theta = Pi/180, minLineLength = 0.0, maxLineGap = 0.0): Seq[Segment]`], [Finite segments with integer endpoints --- the underlying Mat is `CV_32SC4`.], [13],
  [`houghLinesWithAccumulator`], [`mat.houghLinesWithAccumulator(threshold, rho = 1.0, theta = Pi/180, …): Seq[PolarLineWithVotes]`], [As `houghLines`, keeping each line's vote count so you can rank the results.], [13],
  [`start` `end`], [`segment.start: Point`, `segment.end: Point`], [The endpoints as `Point`s, for drawing and measurement.], [13],
  [`length`], [`segment.length: Double`], [Euclidean length --- the near-universal "keep the long ones" filter.], [13],
  [`line`], [`polarLineWithVotes.line: PolarLine`], [Drops the votes once you have ranked by them.], [13],
)
]

#sect("I/O and encoding")

Neither `read` nor `write` calls `imread`/`imwrite`. The bytes move through `java.nio.file` and
OpenCV is left only the codec work, because the JNI layer narrows a Java `String` path to modified
UTF-8 and any non-ASCII character then resolves to a different, nonexistent name on Windows. The
price is that the encoded file passes through a JVM byte array, so peak heap grows by its size and a
file above 2 GB is out of reach.

#figure-table("The boundary between OpenCV and everything else.")[
#tbl(columns: (auto, 1.35fr, 1.35fr, auto),
  [*Operation*], [*Signature (abbreviated)*], [*What it does*], [*See*],
  [`write`], [`img.write(path): Either[CvError, Unit]`], [Terminal. Encodes by extension, writes, then releases --- on success and on failure.], [7],
  [`bytes`], [`img.bytes(format = ".png"): Either[CvError, Array[Byte]]`], [Terminal. Encodes in memory, then releases.], [7],
  [`Images.read`], [`Images.read(path, flags = Color): Either[CvError, Managed[Mat]]`], [The `Mat`-tier read. `Left` says which of six causes it was.], [7],
  [`Images.write`], [`Images.write(path, mat): Either[CvError, Unit]`], [Borrows the Mat. Encoding completes before the destination is touched, so a failure cannot leave a half-written file.], [7],
  [`Images.encode`], [`Images.encode(mat, ext = ".png"): Either[CvError, Array[Byte]]`], [In-memory encode. A missing leading period is added; an unregistered extension is a `Left`, not a throw.], [7],
  [`Images.decode`], [`Images.decode(bytes, flags = Color): Either[CvError, Managed[Mat]]`], [In-memory decode. An empty array is rejected before it reaches OpenCV.], [7],
  [`ImreadFlags`], [`ImreadFlags(color, scale = Full, ignoreOrientation = false)`], [A total model, not a bitmask: the pair maps onto exactly one `IMREAD_*` constant. `ImreadFlags.Color`, `.Grayscale` and `.Unchanged` are the shorthands.], [7, 8],
  [`DecodeFailed`], [`CvError.DecodeFailed(path, details)`], [The one error every read path produces.], [6],
  [`EncodeFailed`], [`CvError.EncodeFailed(path, details)`], [The one error every write and encode path produces.], [6],
)
]

#sect("Interop")

The library never walls off the layer underneath it. These are the moves between tiers, plus the
handful of combinators that make an owned `Managed[Mat]` pleasant to chain. Two of them are worth
committing to memory, because together they cover most of what a hand-written OpenCV sequence needs:
`pipe` consumes the `Managed` it is called on, releasing it once the next stage has produced its own
output, and `Mats.chain` is that same guarantee written as a list of stages rather than a nest of
lambdas. `chain`'s `src` is the exception: it is a borrowed `Mat`, never released, because it belongs
to whoever made it.

#figure-table("Moving between the tiers, and owning what you find there.")[
#tbl(columns: (auto, 1.35fr, 1.35fr, auto),
  [*Operation*], [*Signature (abbreviated)*], [*What it does*], [*See*],
  [`OpenCv.load`], [`OpenCv.load(): Unit`], [Loads the natives. Idempotent, thread-safe, headless. `OpenCv.isLoaded` reports the state.], [2],
  [`Managed`], [`Managed(a)(using Releasable[A]): Managed[A]`], [Takes ownership of any native handle for which a `Releasable` exists.], [5],
  [`use`], [`managed.use(f)` · `Managed.use(a)(f)`], [Runs `f` and releases afterwards, exception or not.], [5],
  [`get`], [`managed.get: A`], [Borrows. Throws `IllegalStateException` after release, in Scala, rather than segfaulting in JNI.], [5],
  [`release`], [`managed.release(): Unit`], [Frees exactly once. `managed.isReleased` reports the state.], [5],
  [`scope`], [`Managed.scope(body: Scope => B): B`], [Ties several handles to one block. `own(a)` registers a new one; `own.adopt(handle)` takes over an existing `Managed`.], [5],
  [`pipe`], [`managed.pipe(f: Mat => Managed[Mat]): Managed[Mat]`], [Feeds the Mat to the next stage and releases it once that stage has produced its own output.], [5],
  [`chain`], [`Mats.chain(src)(stages*): Managed[Mat]`], [The n-stage form of `pipe`. `src` is borrowed and never released.], [A],
  [`attempt`], [`Cv.attempt(operation)(a): Either[CvError, A]`], [Folds a native throw into a value. This is how you give a transform an `Either`.], [6],
  [`orThrow`], [`Cv.orThrow(operation)(a): A`], [The throwing twin, tagging the failure with the operation's name.], [6],
  [`nativeHandle`], [`Releasable.nativeHandle[A]`], [A `Releasable` for any `org.opencv.*` binding that keeps its address in `nativeObj` --- which is all 185 of the types with no public `release()` --- and cannot be handed the wrong accessor.], [5, A],
  [`handle`], [`Releasable.handle[A](getNativeAddr)`], [The explicit-accessor form, for a type whose address lives somewhere else.], [5, A],
)
]

#sidebar("Where the verb is not where you looked")[
Six lookups fail often enough to be worth naming.

There is no `img.drawLine`: the `Image` tier wraps five drawing primitives and the line is not one of
them, so draw it through `img.mat`, which leaves the image alive rather than consuming it. There is
no circle transform at all --- the wrapper commits to the line transforms, and Chapter 13 shows
`Imgproc.HoughCircles` through the low-level API instead.

`img.blur` is a Gaussian and `mat.boxBlur` is a box filter; they are deliberately not the same name,
because dropping from one tier to the other must not switch filter families behind your back.
`img.threshold` throws away the level OpenCV computed, which makes `Threshold.otsu()` pointless on
that tier --- use `mat.threshold`, which returns it.

Recognising text is `Ocr.read(image, engine, preprocess = true)`, an object method that borrows the
image; `recognize` is the one method #emph[you] implement, on `OcrEngine`. And `estimatePose` is two
unrelated operations: `img.estimatePose(net, inputSize, layout, …)` gives you human body keypoints,
while `Ar.estimatePose(marker, markerLength, intrinsics)` gives a detected marker a 3-D pose. Both
live in `scalacv-vision`.
]

#sect("Where to go from a row")

A row in this appendix tells you a name exists and roughly what it costs; it deliberately does not
tell you which `Colormap` to pick, what `ContourRetrieval.Tree` promises about nesting, or why
`ImreadFlags` refuses to be OR-ed together. Those are questions about the enumerations rather than
about the operations, and the two references are meant to be read together: find the verb here, find
the constant you have to hand it there.

Appendix C, #emph[Enums and Types Reference], answers them case by case, with the OpenCV constant printed
underneath each one so you can check a translation against the C++ documentation without leaving the
page --- including the three types that look like enumerations and are not.
