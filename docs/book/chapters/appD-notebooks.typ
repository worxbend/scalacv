#import "../lib/book.typ": *

#appendix("Notebooks and Sample Data", subtitle: [Where to type the code, and what to point it at when you have no photographs.])

Two practical questions sit underneath every chapter in this book and belong to none of them. The
first is where the code goes while you are still deciding what it should be --- a build, a scratch
file, a notebook cell --- and the second is what you feed it, given that this repository contains no
photographs and no video clips at all.

Neither question is about computer vision, which is why they have been deferred to an appendix. Both
of them will nevertheless cost you an afternoon if you get them wrong, and the second one has a
sharper edge than it looks: a great many people learn a vision library by pulling an image off a
search engine, and end up with a licensing problem attached to a repository they then push.

scalacv's own answer is visible in its build. The documentation site type-checks and runs every
snippet it prints (`mill docs.mdocCheck`, on every pull request), the example module asserts its own
output in CI, and the test suites draw their inputs the same way --- none of it reads a bitmap from
disk, because there is none to read: no `.png`, `.jpg`, `.avi` or `.mp4` ships in any module.
`examples/src/scalacv/Fixtures.scala` says so in its opening comment: "there is no image asset in
this repository (no bitmap fixture ships, for licensing reasons). So every example generates its
input here rather than reading a file." That is not a workaround the project tolerates; it is the
better technique, and the second half of this appendix is a catalogue of it.

#sect("Almond: scalacv in a Jupyter cell")

Almond is the Scala Jupyter kernel. It resolves dependencies at run time through coursier, so there
is no build file, and it renders a `java.awt.image.BufferedImage` inline --- which is the entire
reason a notebook is worth the setup for image work. You get pixels under the cell that made them.

The dependency story is the one Chapter 2 tells: the API jar carries no native code, so the natives
arrive as a separate coordinate. What a notebook changes is how that coordinate is spelled --- and
whether it is worth carrying a classifier at all.

#figure-table("Three ways to put the natives on a notebook classpath.")[
#tbl(
  columns: (auto, 1fr),
  [*Spelling*], [*The coordinate*],
  [Mill's `mvn"…"`], [`org.bytedeco:opencv:4.13.0-1.5.13;classifier=linux-x86_64` --- the attribute follows a semicolon],
  [coursier's dependency string (scala-cli's `//> using dep`, and anything else that hands the coordinate to coursier verbatim)], [`org.bytedeco:opencv:4.13.0-1.5.13,classifier=linux-x86_64` --- the attribute follows a comma],
  [No classifier at all], [`org.bytedeco:opencv-platform:4.13.0-1.5.13` --- every platform in one coordinate, openblas included],
)
]

The separator is the tool's, not scalacv's, and getting it wrong is a resolution error rather than a
subtle one. A notebook is where that difference costs the most time for the least benefit, so the
first cell below takes the third row. `opencv-platform` resolves anywhere, needs no classifier table
and no second line for openblas, and costs about 408 MB against 36--80 MB for a matched pair. Trade
it for the pair --- `linux-x86_64`, `linux-arm64`, `macosx-arm64`, `macosx-x86_64` or
`windows-x86_64`, as Chapter 2 lists them --- once you know the platform and want that download back.

#example("The first cell of any scalacv notebook.")[
```scala
import $ivy.`com.worxbend::scalacv:0.1.0`
import $ivy.`org.bytedeco:opencv-platform:4.13.0-1.5.13`

import scalacv.*
OpenCv.load()
```
]

Add `com.worxbend::scalacv-vision:0.1.0` for the detectors, DNN, pose, tracking, OCR and calibration
--- which is what the ArUco, chessboard and OCR generators later in this appendix need --- or
`com.worxbend::scalacv-graphs:0.1.0` for `Picture`, charts and GIF. Both depend only on the core, so
adding one does not drag in the other.

`OpenCv.load()` is idempotent and safe to call from several threads, so putting it in the first cell
and re-running that cell later costs nothing. It also needs no GUI toolkit: it brings javacpp up
through a preset that links none, so a container without GTK loads OpenCV rather than failing in
`opencv_highgui` and taking `objdetect` down with it. That is what makes a headless notebook server
a supported place to run this, not a workaround.

#subsect("Flags to start the kernel with")

scalacv's own build forks every OpenCV-running JVM with the same short list, and a kernel benefits
from all of it.

- `-Dorg.bytedeco.javacpp.cachedir=…` --- the first `load()` on Linux extracts about 196 MB into
  `~/.javacpp`. Point it somewhere writable when the notebook server's home is read-only or
  ephemeral, or the extraction repeats on every kernel restart.
- `-Djava.awt.headless=true` --- an assertion rather than a preference. A stray call that wants a
  window then fails loudly instead of blocking on a display that is not there. `toBufferedImage` is
  unaffected: a `BufferedImage` is heap memory and needs no display to exist.
- `--enable-native-access=ALL-UNNAMED` on JDK 24 and later, which silences the JEP 472 warning
  `System.load` emits.
- `--sun-misc-unsafe-memory-access=allow` on JDK 24 and later, which silences the four lines
  scala3-library 3.3.8's `LazyVals` provokes --- cosmetic, but otherwise a preface to every cell
  that prints.

The last two are exactly what `Deps.headlessJvmArgs` in `build.mill` adds for the examples, the
benchmarks, the leak harness and the tests, guarded by the same JDK-major check.

#subsect("Why the display method is toBufferedImage")

Almond has a renderer for `BufferedImage` and none for `Mat`. That asymmetry is the whole reason
`Image.toBufferedImage` exists in the shape Chapter 7 described: it copies, it borrows the image
rather than consuming it, and it accepts 8-bit images with 1, 3 or 4 channels. Return it from the
last expression in a cell and the cell shows a picture.

#example("The habit that makes every cell show its work.")[
```scala
def show(img: Image): java.awt.image.BufferedImage = img.toBufferedImage
```
]

Define that once, near the top of the notebook, and any cell whose last expression is `show(...)`
renders a picture instead of printing a type name.

Two restrictions bite in practice. `toBufferedImage` requires depth `CV_8U`, so a disparity map, a
distance transform or a raw gradient response throws an `IllegalArgumentException` rather than
silently truncating ---
bring it down first with `normalize()` for plain intensity, or `.gray.colorMap(Colormap.Viridis)`
for legible false colour. And transforms move the image, so `show(img.blur(5))` spends `img` and the
rest of the cell has nothing left to work on. Display a `.copy` when you want to see an
intermediate step and keep going.

#sect("The leak a notebook makes easy")

A notebook is a long-lived JVM with a top-level scope you keep adding to. That combination is
exactly the one the `Image` lifetime rules were designed against, and the failure mode is quiet.

#example("A cell that leaks, and leaks again every time you run it.")[
```scala
val frame = Image.read("photo.jpg").toOption.get
val edges = frame.copy.gray.canny(80, 160)
edges.toBufferedImage
```
]

`.toOption.get` is the first sin and the visible one --- Chapter 6 is about the `Left` it throws
away. The second is invisible, and worse. `frame` and `edges` are top-level notebook bindings, so
they live as long as the kernel does; the roughly forty bytes of Java header the collector sees for
each `Mat` never come under enough pressure to trigger a collection, and the megabytes behind them
are never freed. Re-run the cell to adjust a threshold and the previous `frame` and `edges` are
rebound, not released --- so a twenty-iteration tuning session has twenty generations of pixels
resident. Chapter 1 measured what that costs at scale: 2000 unreleased `Mat(1000, 1000, CV_8UC3)`
held 5 865 MB where releasing them held 144 MB.

#memory[
Notebook cells are the worst case for native memory, because every leak is permanent for the life of
the kernel and re-running a cell multiplies it. Never bind an `Image` or a `Managed` at a cell's top
level. Scope it, and let the last expression be a `BufferedImage`, an array, or a number --- values
the kernel can hold safely because they are on the heap.
]

The fix is the same one Chapter 5 gave for any other long-lived process, and it fits on one line
more than the leaking version.

#example("The same cell, owning nothing when it ends.")[
```scala
Image.reading("photo.jpg") { img =>
  val edges = img.gray.canny(80, 160)
  try show(edges)
  finally edges.close()
}
```
]

`Image.reading` releases the source when the block returns --- on success, on failure, and on
exception. The `try`/`finally` is there because `gray` and `canny` each move ownership into a *new*
`Image`, and `reading` only knows about the one it opened; `edges` is a handle the cell created and
so a handle the cell has to close. What escapes is a `BufferedImage`, which is heap memory the
collector understands. Where a cell needs several native objects at once, `Managed.scope` does the
same job for all of them:

#example("Several handles, one scope, and only plain data escapes.")[
```scala
Managed.scope { own =>
  val marker = own.adopt(Aruco.generateMarker(ArucoDictionary.Dict4x4_50, id = 7, sizePixels = 200))
  // marker is a borrowed org.opencv.core.Mat; the scope releases it when the block returns.
  val bgr    = own.adopt(marker.cvtColor(ColorConversion.GrayToBgr))
  val sheet  = own.adopt(bgr.border(60, 60, 60, 60, BorderType.Constant, Scalar.White))
  Images.encode(sheet, ".png") // Either[CvError, Array[Byte]] — heap bytes, safe to bind
}
```
]

The rule `Managed.scope` carries is that nothing acquired inside may escape: the value the block
returns must be plain data, or an object with a separate owner. In a notebook that rule is a gift,
because "plain data" is exactly what a cell can safely keep --- a byte array, a `Seq[Contour]`, a
count, a `BufferedImage`.

#sidebar("Restarting the kernel is not a memory strategy")[
It is tempting to treat "restart the kernel" as the release. It works, in the sense that the process
exits and the operating system reclaims everything --- and it is precisely how people discover the
problem, because the restart is what makes the machine usable again. The reason not to rely on it is
that the discipline you practise in a notebook is the discipline you carry into the service you
write afterwards, where there is no restart between frames. Scope every cell, and the frame loop in
Chapter 20 will already look normal to you.
]

#sect("scala-cli, when a notebook is more machinery than you need")

For a scratch script --- one input, one output, no cells --- scala-cli is lighter. The dependency
declarations live in `using` directives at the top of the file, so the file is the build.

#example("A complete scratch script, run with scala-cli run edges.scala.")[
```scala
//> using scala 3.3.8
//> using dep com.worxbend::scalacv:0.1.0
//> using dep org.bytedeco:opencv:4.13.0-1.5.13,classifier=linux-x86_64
//> using dep org.bytedeco:openblas:0.3.31-1.5.13,classifier=linux-x86_64

import scalacv.*

@main def edges(): Unit =
  OpenCv.load()
  val scene = Image
    .blank(320, 200, Scalar(30, 30, 30))
    .drawCircle(Point(150, 70), 30, Scalar.White, Thickness.Filled)
    .drawRect(Rect(40, 120, 80, 50), Scalar.White, Thickness.Filled)
  scene.gray.canny(80, 160).write("edges.png") match
    case Right(_)  => println("wrote edges.png")
    case Left(err) => println(s"failed: ${err.getMessage}")
```
]

That script reads no file, which is the point of the second half of this appendix.

#sect("Sample data you draw rather than download")

Every tutorial input in this book can be generated, with three exceptions named at the end. A drawn
input has exactly the properties you meant it to have, so when a step misbehaves you know whether the
bug is in your code or in your picture --- and you can change the picture to find out. Move two discs
until they touch and watch the contour count fall from six to five: the merged-blob problem,
reproduced deliberately, at the exact separation where it starts.

Write every generator as a `def`, never a `val`. Transforms consume the image they are called on, so
a shared `val` scene is good for exactly one pipeline before it is spent. A `def` costs one
allocation and removes a whole class of use-after-move error. Chapter 40 uses the same rule in the
test suite for the same reason.

#subsect("A scene with countable objects")

`Image.blank` gives a canvas; the drawing verbs paint onto it and hand the image straight back, so a
scene is one chain. Colours are BGR --- `Scalar.Red` is `Scalar(0, 0, 255)` --- and `Thickness.Filled`
is what makes a shape solid enough to threshold.

#example("A dark tray with five bright discs and one speck of noise.")[
```scala
def coinTray(): Image =
  Image
    .blank(320, 200, Scalar(30, 30, 30))
    .drawCircle(Point(60, 60), 25, Scalar.White, Thickness.Filled)
    .drawCircle(Point(150, 70), 30, Scalar.White, Thickness.Filled)
    .drawCircle(Point(240, 60), 20, Scalar.White, Thickness.Filled)
    .drawCircle(Point(90, 150), 22, Scalar.White, Thickness.Filled)
    .drawCircle(Point(200, 150), 28, Scalar.White, Thickness.Filled)
    .drawCircle(Point(280, 180), 2, Scalar.White, Thickness.Filled)
```
]

`coinTray().gray.threshold(128).contours()` returns six contours. Five of them measure between 1200
and 2740 units of `Contour.area`; the speck measures 8.0, and filtering it out on area is the
exercise in Chapter 12.

#subsect("A scene composed rather than chained")

The drawing verbs mutate an image and consume it, which is why a generator has to be a `def`. The
`Picture` scene graph from `scalacv-graphs` moves the reuse one level up: a `Picture` is an
immutable description holding no native memory, so it may safely be the `val` that an `Image` may
not, and each `render` builds its own fresh image from it.

#example("One disc, described once and rendered three times into one tray.")[
```scala
/** A description, not pixels — no native memory, so a top-level binding is safe. */
val disc = Picture.circle(Point(0, 0), 24).fillColor(Color.Orange).noStroke

def tray(): Image =
  Picture
    .all(Seq(Point(60, 60), Point(150, 70), Point(240, 60)).map(disc.at))
    .render(320, 200, Color.DarkGray)
```
]

That tray is 320 × 200, three channels, and thresholds to three contours of area 1820 apiece ---
identical discs, because they are one description placed three times rather than three calls that
might drift apart. `Picture.at` translates the shape's origin onto a point, `Picture.all` stacks a
`Seq` of them, and `render(width, height, background)` is the only step that allocates. Chapter 16
is the scene graph in full; here the point is narrower --- the "def, never `val`" rule is about
pixels, not about the plan for them.

#subsect("A chessboard for calibration")

`ChessboardPattern(columns, rows)` counts *inner* corners, so a 9 × 6 pattern is a board of 10 × 7
squares. Draw one more square than corners in each direction and leave a white margin, or the corner
finder has no background to work against.

#example("A flat chessboard with a quiet zone.")[
```scala
def chessboard(columns: Int = 9, rows: Int = 6, square: Int = 60, margin: Int = 60): Image =
  val squaresX = columns + 1
  val squaresY = rows + 1
  val dark =
    for
      r <- 0 until squaresY
      c <- 0 until squaresX
      if (r + c) % 2 == 0
    yield Rect(margin + c * square, margin + r * square, square, square)
  val canvas =
    Image.blank(squaresX * square + 2 * margin, squaresY * square + 2 * margin, Scalar.White)
  dark.foldLeft(canvas)((img, cell) => img.drawRect(cell, Scalar.Black, Thickness.Filled))
```
]

With those defaults the canvas is 720 × 540, and
`Calibration.findCorners(board, ChessboardPattern(columns = 9, rows = 6))` returns `Some` of 54
points --- `columns × rows`, which `ChessboardPattern.corners` will tell you. The detector is
all-or-nothing, so a `Some` means the whole board was found, never a subset. The `foldLeft` is there
because `drawRect` consumes the image and returns a new one --- that is the general shape of
"draw N things" in scalacv.

#warning[
A drawn board calibrates nothing. `Calibration.fromChessboard` recovers focal length and distortion
from how perspective changes between views, and every view of a flat drawn board is the same view.
Use the drawn board to prove that corner detection and your pattern dimensions are right; use a
printed board and a real camera for numbers you can trust. Chapter 31 has the procedure.
]

#subsect("An ArUco marker sheet")

Fiducial markers are generated rather than photographed, so there is no compromise here at all ---
you can make a pixel-perfect one and decode it straight back. `Aruco.generateMarker` returns the tag
with its own black border and *no* quiet zone, and the detector hunts for a dark quadrilateral on a
light background, so the padding is load-bearing.

#example("One marker, padded so it can be found.")[
```scala
def markerSheet(id: Int, sizePixels: Int = 200): Image =
  Image
    .wrap(Aruco.generateMarker(ArucoDictionary.Dict4x4_50, id, sizePixels))
    .pad(60, color = Scalar.White)
```
]

`markerSheet(7)` is 320 × 320 once padded, and `sheet.arucoMarkers()` on it returns exactly one
`ArucoMarker`, with `id == 7` and four corners. The extension defaults to `Dict4x4_50`, the
dictionary the marker was generated from; ask for a different one and you get an empty `Seq` rather
than a wrong answer. For a multi-marker sheet, generate several and paste them into a blank canvas;
Chapter 28 covers what the detector does with them.

#subsect("A document for OCR")

The test suite's synthetic document is horizontal black bars on white --- ink-shaped enough for the
deskew step to find a dominant angle, with no font rendering involved.

#example("A page of text-shaped bars, then a skewed scan of it.")[
```scala
def document(): Image =
  Image
    .blank(200, 140, Scalar.White)
    .drawRects(
      Seq(Rect(30, 20, 140, 8), Rect(30, 45, 140, 8), Rect(30, 70, 140, 8), Rect(30, 95, 140, 8)),
      Scalar.Black,
      Thickness.Filled
    )

/** The same page as a tilted scan. Rotate on a WHITE background, as a real scanner
  * would leave it: the default black fill reads as ink and drags the deskew angle.
  */
def skewedDocument(degrees: Double = 12.0): Image =
  val doc = document()
  try Image.wrap(doc.mat.rotated(degrees, color = Scalar.White))
  finally doc.close()
```
]

`rotated` is the mid-level verb on the borrowed `Mat`, not a transform on the `Image`, which is why
`doc` is closed by hand; it expands the canvas so no corner is clipped, so a 200 × 140 page comes
back 225 × 179 at twelve degrees. Run `skewedDocument().forOcr()` and you get a single-channel
binarised image --- `channels == 1` --- with the bars back near horizontal. What it will not give
you is text: `Ocr.read` needs an `OcrEngine` you supply, and no engine will read a bar. Bars test the
geometry; real glyphs test the engine. Chapter 33 draws that line.

#subsect("A moving object for tracking and motion detection")

A `Recorder` writes `Image`s, and the images can be drawn ones, so a video file is about ten lines.
Use `Codec.Mjpg` with an `.avi` extension: Motion-JPEG is served by OpenCV's built-in writer, so it
needs no FFmpeg, no GStreamer and no system codec, and it is `Recorder`'s default for that reason.
The container is part of the deal --- MJPG will not open inside an `.mp4`.

#example("Fifty frames of honest motion.")[
```scala
Recorder.using("clip.avi", Size(320, 240), fps = 25.0, codec = Codec.Mjpg) { rec =>
  (0 until 50).foreach { i =>
    val f = Image
      .blank(320, 240, Scalar(20, 20, 20))
      .drawCircle(Point(20 + i * 5, 120), 18, Scalar.Green, Thickness.Filled)
    try rec.write(f).fold(e => throw e, identity)
    finally f.close()
  }
}
```
]

`Recorder.using` closes the writer on the success path and on an exception --- a `VideoWriter` that
is never released leaves a truncated, unplayable file --- and hands back an
`Either[CvError, A]`, so the cell's value tells you whether the codec opened at all. `rec.write`
*borrows* the frame, so the `try`/`finally` is yours to write; every frame is allocated and freed
inside the loop, which is why a 50-frame clip and a 50 000-frame clip use the same memory.

Read it back with `Camera.usingFile("clip.avi")`, which closes the capture for you and returns your
block's value in an `Either`. Count the frames with `cam.foreach(attemptsPerFrame = 1)`, which hands
each frame over as an owned `Image` and closes it for you: `attemptsPerFrame` is a parameter of the
frame-pulling methods, not of `usingFile`, and its default of 3 exists so a flaky live camera can
drop a frame without ending the stream. On a finite file it only costs two extra blocking reads at
the end.

A disc that moves five pixels a frame is exactly what a frame-difference detector is looking for
(Chapter 21), and its bounding box is a clean seed for `Tracker.init` (Chapter 30).

#subsect("A gradient and a noise field")

Filters are easier to judge against a signal you can predict than against a scene. Neither a ramp nor
a noise field is a drawing, so both are built by filling a byte array and handing it to a `Mat` ---
the same pattern `OccupancyGrid.toImage` uses, with the pixels allocated *before* the `Mat` so
nothing can throw in the window where the buffer has no owner.

#example("A horizontal ramp and a uniform noise field, both 8-bit single-channel.")[
```scala
import org.opencv.core.{CvType, Mat}

private def fromBytes(width: Int, height: Int, bytes: Array[Byte]): Image =
  val mat = Mat(height, width, CvType.CV_8UC1)
  val handle = Managed(mat)
  try
    mat.put(0, 0, bytes): Unit
    Image.wrap(handle)
  catch
    case e: Throwable =>
      handle.release()
      throw e

/** 0 on the left edge, 255 on the right. */
def ramp(width: Int = 256, height: Int = 64): Image =
  require(width > 1, "a ramp needs at least two columns")
  val bytes = new Array[Byte](width * height)
  for y <- 0 until height; x <- 0 until width do
    bytes(y * width + x) = (x * 255 / (width - 1)).toByte
  fromBytes(width, height, bytes)

/** Uniform 0–255 noise, reproducible from `seed`. */
def noise(width: Int = 256, height: Int = 256, seed: Long = 42L): Image =
  val bytes = new Array[Byte](width * height)
  java.util.Random(seed).nextBytes(bytes)
  fromBytes(width, height, bytes)
```
]

The ramp makes a `posterize` step count, a gamma curve and a `colorMap` legible at a glance: the
output is a graph of the operation, not a picture of a scene. The noise field is the denoiser's test
bench, and the seed is the point of it --- `noise()` gives the same pixels on every run, so
`medianBlur(1)` and `bilateralFilter()` are compared on identical input rather than on two fields
that are merely similar. `Managed(mat)` picks up the `given Releasable[Mat]` from the companion, and
`Image.wrap` takes ownership of that handle; Appendix A covers the wider `org.opencv.*` surface both
of them drop into.

#subsect("The inputs you have to supply yourself")

Three things cannot be drawn, and pretending otherwise wastes a day.

- *A photographed face.* Haar cascades and YuNet both key on the light-and-dark structure of a
  photographed face --- brows darker than forehead, the highlight on the nose bridge, skin texture.
  A cartoon has none of it. The detector will load and run on a drawn face and return some number,
  and that number tells you nothing either way. Chapter 24 assumes a real portrait.
- *A lens.* Distortion coefficients, vignetting and rolling shutter come from optics, not from
  rasterisation. Chapter 31's undistortion is a no-op on a drawn frame.
- *Real degradation.* Sensor noise, motion blur, JPEG artefacts and uneven lighting are what
  production thresholds are actually tuned against. Drawn inputs prove your logic; real captures tell
  you what your numbers need to be.

#sect("Real datasets, and the licence attached to each of them")

When you do need photographs, take the licence as seriously as the pixels, and keep the files
*outside* your repository --- the same reasoning that keeps them out of scalacv's.

#figure-table("Where to get images you are allowed to use.")[
#tbl(
  columns: (auto, 1fr),
  [*Source*], [*What to check before you commit to it*],
  [Wikimedia Commons], [Every file page states its licence. Filter for public domain or CC0; CC BY-SA files oblige you to share alike, which reaches your derived outputs.],
  [Unsplash], [The Unsplash licence permits free use, commercial included, with no attribution required. It does not permit redistributing the photos as a competing stock library.],
  [Your own camera], [No licence question at all, and the only source that matches your actual optics and lighting. This is what a calibration set has to be.],
)
]

#caution[
An academic vision dataset is not automatically usable. Several of the best-known face and pedestrian
sets are licensed for non-commercial research only, some require a signed agreement, and a few have
been withdrawn over consent. Read the terms before a dataset reaches a build that ships, and record
which one you used --- a model trained on restricted data inherits the restriction.
]

#sect("What good output looks like")

Every value below was produced by running the generators exactly as printed above, against this
repository's `core` and `vision`.

#figure-table("Expected results from the generators in this appendix.")[
#tbl(
  columns: (auto, auto, auto),
  [*Input*], [*Check*], [*Expected*],
  [`coinTray()`], [`gray.threshold(128).contours()`], [`6` contours; five of area 1200--2740, one of 8.0],
  [`tray()`], [`gray.threshold(128).contours()`], [`3` contours, area 1820 apiece],
  [`chessboard()`], [`Calibration.findCorners(…, ChessboardPattern(9, 6))`], [`Some(54)` on a 720 × 540 canvas],
  [`markerSheet(7)`], [`arucoMarkers()` on a 320 × 320 sheet], [one marker, `id == 7`, 4 corners],
  [`document()`], [`forOcr().channels`], [`1`, still 200 × 140],
  [`clip.avi`], [frames counted inside `Camera.usingFile`], [`Right(50)`, each 320 × 240],
  [`ramp()`], [first and last column of row 0], [`0` and `255`, one channel],
)
]

If a number here does not match on your machine, check the input before you touch the algorithm:
print the generator's size and channel count first, and only then suspect the operation.

#sect("Where this leaves you")

You now have a place to type code that will not leak between cells, and a way to manufacture every
input this book has asked for except the three that genuinely require a camera. That is the last of
the practical apparatus. Appendix E, the Glossary, closes the book with the vocabulary --- owned,
borrowed, move semantics, spent, query/transform/terminal, classifier --- that these forty-two
chapters and five appendices have been using in a specific and deliberate sense.
