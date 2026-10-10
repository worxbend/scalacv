#import "../lib/book.typ": *

#chapter("Charts", subtitle: [Turning a column of numbers into pixels, on a machine with no screen.])

A vision service produces two kinds of output. The first is an image --- a frame with boxes on it, a
mask, a crop --- and the library has spent sixteen chapters on that one. The second is a number that
changes over time: faces per frame, motion energy per second, the length of a queue, the confidence
the model had before it stopped being sure. That output has nowhere to go. It is usually the more
interesting of the two, and it usually ends its life as a column of doubles in a log that nobody
opens.

The obvious fix is to plot it, and the obvious fix is where the trouble starts. The machine doing
the work is a container: no windowing system, no X socket, no fonts beyond whatever the base image
happened to ship. A plotting library that reaches for AWT wants a headless flag and a font path and
will still fail differently on the build agent than on your laptop. One that renders in a browser
wants a browser. Adding either to a deployment whose whole selling point is that `OpenCv.load()`
needs no `apt-get` is a poor trade for six bars and a line.

The process is already holding a renderer. It draws antialiased polylines, filled polygons and text
onto a buffer of pixels, it needs no display to do it, and Chapter 16 wrapped it in a compositional
value type. A bar chart is a sequence of filled rectangles. A line chart is a polyline. Once
`Picture` exists, charts are not a new capability --- they are an arrangement of one you have.

`Chart` is that observation, written down. Six functions, each taking numbers and a box and
returning a `Picture`. Nothing else: no window, no canvas object, no plotting session to configure.
This chapter uses one running job to show the shape of it --- count the faces in every frame of a
thirty-second clip, plot the count against time, and burn the plot into a corner of the last frame
so the whole answer travels as a single PNG in a chat message.

#sect("Six factories, one return type")

`Chart` lives in the `scalacv-graphs` module alongside `Picture`, `Color` and `Animation`; the same
`import scalacv.*` Chapter 16 already relied on brings it in. The running example needs a second
optional artifact --- `com.worxbend::scalacv-vision:0.4.1`, for the face detector --- while the
video reader itself is core. Neither has had its own chapter yet: Chapter 19 covers `Video` properly
and Chapter 24 the detector, and what is used here is the minimum of each.

Every factory validates its box the same way --- a non-positive `width` or `height` throws
`IllegalArgumentException` with the message `a chart needs a positive box, got 0x80`, because a
zero-width box does not produce an empty chart, it produces degenerate `Rect`s and a division by a
zero span.

#figure-table("The whole `Chart` API. Every one returns a `Picture` sized to its `width`×`height` box, origin top-left.")[
#tbl(
  columns: (auto, 1fr),
  [*Factory*], [*Signature*],
  [`bars`], [`bars(values: Seq[Double], width: Int, height: Int, color: Color = Color.Blue, gap: Int = 4)`],
  [`line`], [`line(values: Seq[Double], width: Int, height: Int, color: Color = Color.Green, strokeWidth: Int = 2)`],
  [`area`], [`area(values: Seq[Double], width: Int, height: Int, color: Color = Color.Blue, strokeWidth: Int = 2)`],
  [`scatter`], [`scatter(points: Seq[(Double, Double)], width: Int, height: Int, color: Color = Color.Red, radius: Double = 3)`],
  [`pie`], [`pie(values: Seq[Double], width: Int, height: Int, palette: Seq[Color] = Color.categorical)`],
  [`histogram`], [`histogram(data: Seq[Double], bins: Int, width: Int, height: Int, color: Color = Color.Purple)`],
)
]

Degenerate *data* is not an error, and this is the line worth remembering: a bad box throws, bad
numbers draw nothing. `bars` and `scatter` return `Picture.empty` for an empty sequence; `line` and
`area` return it for anything shorter than two points, because the divisor that spreads points
across the width is `values.size - 1`; `pie` returns it when the magnitudes sum to zero or the
palette is empty; `histogram` returns it for empty `data`, and additionally *throws* when `bins` is
less than one --- a check made before the box is checked. `Picture.empty` composites onto a frame as
nothing at all, which is the right behaviour for a chart driven by a stream that has not produced
any data yet.

The scaling is worth knowing exactly, because it is the source of every surprise in this chapter.
`bars`, `line` and `area` divide by `values.map(math.abs).max.max(1e-9)`, so the largest magnitude
in the series lands at the top of the box and everything else is a fraction of it. That floor of
`1e-9` is the reason an all-zero series is a flat line along the baseline rather than a `NaN`
polyline that OpenCV would refuse. There is a two-pixel inset --- the tallest point sits at `y = 2`,
not `y = 0` --- so a stroke at the peak stays inside the box instead of being clipped by its own
edge. `scatter` is the only one that maps a genuine range: it takes the minimum and maximum of both
axes and fits them into the box, inset by `radius` so the extreme markers are whole; an axis with no
spread at all --- every `x` identical --- is centred rather than divided by zero. `pie` fits a
circle of radius `math.min(width, height) / 2 - 2` into the box, starts its first slice at `-90`
degrees --- twelve o'clock --- and cycles the palette with `i % palette.size` when there are more
slices than colours. `histogram` bins `data` into `bins` equal-width buckets across `[min, max]` and
then hands the counts to `bars` with `gap = 1`.

#sect("Counting faces across a clip")

The measurement comes first, and it has nothing to do with charting. Open the video, run the
detector on every frame, keep the count, and keep the last frame. `Video.framesCopied` --- Chapter
5's `Managed`, applied to video --- is the right traversal here: it clones each frame into a
caller-owned `Managed[Mat]` as you pull it, and it clones as you pull rather than up front, which is
exactly what "I want to keep the last one" requires. The plain `Video.frames` hands you one Mat
reused for every frame; keeping a reference to it would keep nothing.

#example("One pass over the clip: a count per frame, and the final frame kept.")[
```scala
import scalacv.*
import org.opencv.core.Mat
import org.opencv.objdetect.FaceDetectorYN

def timeline(path: String, detector: FaceDetectorYN): Either[CvError, (Seq[Double], Option[Image])] =
  Video.open(path).map { capture =>
    capture.use { cap =>
      Video.framesCopied(cap) { frames =>
        var last: Option[Managed[Mat]] = None
        val counts = frames.map { owned =>
          last.foreach(_.release())      // the previous frame is no longer the last one
          last = Some(owned)
          FaceDetect.detect(detector, owned.get).size.toDouble
        }.toVector                       // forces the traversal, in order, once
        (counts, last.map(Image.wrap))
      }
    }
  }
```
]

#memory[
Three lifetimes are in play in that loop and only one of them is automatic. `capture.use` releases
the `VideoCapture`. Each frame `framesCopied` hands you is yours: the loop releases the previous one
before adopting a new one, so exactly one clone is alive at a time regardless of how long the clip
is, and the survivor becomes an `Image` the caller must close. The detector is the third.
`FaceDetectorYN` is one of the 185 `org.opencv.*` types with no public `release()` at all; scalacv
frees it anyway through `Releasable.nativeHandle`, which is why it reaches you from
`FaceDetect.create` as a `Managed[FaceDetectorYN]` and why letting the raw handle escape that
`Managed` costs you the model's weights for the life of the process. `FaceDetect.detect` also
*mutates* the detector --- it sets the input size to each frame's size --- so one detector belongs
to one thread.
]

Thirty seconds at thirty frames per second gives 900 counts. The first instinct is a bar per frame.

```scala
Chart.bars(counts, 240, 56)     // 900 bars in a 240-pixel box
```

What renders is the first forty-eight bars and then nothing. `bars` computes `barWidth` as
`math.max(1, (width - gap * (n + 1)) / n)`, and at 900 values that expression is negative, so the
clamp fires and every bar is one pixel wide. The layout then places bar `i` at
`gap + i * (barWidth + gap)` --- `4 + 5i` here --- which for the forty-ninth bar is `x = 244`, already
past the right edge. OpenCV clips it, silently, and you are looking at the first second and a half
of the clip. The rule is arithmetic: a box holds about `width / (gap + 1)` bars --- 48 in a 240-pixel
box at the default gap of 4 --- and a bar chart is the wrong instrument above that.

Two right answers. Downsample into as many buckets as the box can hold, or use the series charts,
whose x-coordinate is `i / (n - 1) * width` and therefore accommodates any length by putting several
samples on the same pixel column.

#example("A second-by-second bar chart, and a per-frame trace, from the same counts.")[
```scala
val perSecond = counts.grouped(30).map(g => g.sum / g.size).toVector  // 30 buckets
val bars      = Chart.bars(perSecond, 240, 56, Color.Cyan)

val trace = Chart.line(counts, 240, 56, Color.Cyan)
  .on(Chart.area(counts, 240, 56, Color.Cyan))   // the fill under its own outline
```
]

Thirty buckets fit: `(240 - 4 × 31) / 30` is a bar three pixels wide, and the last of them ends at
`x = 210`, comfortably inside the box. `area` is `line` with the polyline closed down to the baseline
and filled at `color.fadeOut(0.7)`, drawn over its own outline. The two agree on every vertex by
construction, because both are built from one private `seriesPoints` definition rather than from two
that could drift apart. Overlaying a fresh `line` on top, as above, restores the outline to full
strength through the faded fill, which reads better over a photograph than either chart alone.

#sect("A chart is a picture, so it composites like one")

Nothing above has touched a pixel. `Chart.area(counts, 240, 56, Color.Cyan)` is a tree of case
classes; it has the same `at`, `on`, `translate`, `scale` and `bounds` as any other `Picture`, and
it turns into pixels only where Chapter 16 said it would --- at `render`, which allocates a fresh
canvas, or at `image.draw`, which consumes an existing image and returns the annotated one.

#example("The plot, a backing panel, and a title tag, burned into the last frame.")[
```scala
// detector: Managed[FaceDetectorYN], from FaceDetect.create(modelPath, Size(320, 320))
timeline("lobby.mp4", detector.get).foreach { (counts, lastFrame) =>
  lastFrame.foreach { frame =>
    val panel = Rect(12, frame.height - 108, 264, 96)  // 12 px of margin around the 240x56 plot
    val plot  = Chart.line(counts, 240, 56, Color.Cyan)
      .on(Chart.area(counts, 240, 56, Color.Cyan))
      .at(Point(panel.x + 12, panel.y + 32))

    val overlay = Picture.all(Seq(
      Picture.roundedRectangle(panel, radius = 6).fillColor(Color.Black.withAlpha(150)).noStroke,
      plot,
      Picture.text(f"faces/frame, peak ${counts.max}%.0f", Point(panel.x + 12, panel.y + 22))
        .strokeColor(Color.Black)
        .fontScale(0.45)
        .on(Picture.rectangle(Rect(panel.x + 8, panel.y + 6, 200, 22))
              .fillColor(Color.Cyan)
              .noStroke)
    ))

    frame.draw(overlay).write("lobby-summary.png")   // draw consumes; write releases
  }
}
```
]

`Picture.all` groups a sequence with the first element at the bottom, so the panel paints, the plot
paints over it, and the title tag paints over both. That tag is text composed *onto* its own filled
box with `.on`, rather than the ready-made `Picture.label`, for the reason Chapter 16's warning
gives: `label` composes with `under`, which puts the fill on top, so an opaque background paints out
the text it was meant to sit behind. The panel is the piece that is easy to skip and
expensive to skip: a chart drawn straight onto a frame has whatever contrast the frame happens to
give it, which for a cyan trace over a sunlit floor is none. One rounded rectangle at
`Color.Black.withAlpha(150)` buys a known background --- and because the `Picture` layer honours
alpha per shape (OpenCV's own drawing verbs do not), the frame still shows through it.

#memory[
The chart layer allocates no native memory. A `Picture` is heap data; build a hundred of them per
frame and OpenCV never hears about it. Native cost appears at exactly two boundaries.
`picture.render(w, h, background)` allocates a fresh `Mat` and returns an `Image` you own --- the
call for a chart in a report, where there is no frame to draw onto. `image.draw(picture)` consumes
its receiver and returns the annotated image. Above, `write` is the terminal that releases it.
]

For the report case --- a chart as a file in its own right, no video involved --- the same picture
goes through `render` instead:

```scala
val latencies: Seq[Double] = ???            // one detector call, in milliseconds, per frame

Chart.histogram(latencies, bins = 24, width = 480, height = 160)
  .render(480, 160, Color.White)
  .write("latency.png")
```

#sect("What Chart does not draw")

This is a chart layer for overlays and reports, sized to that job, and it is worth being blunt about
the boundary rather than discovering it at 2 a.m. There are no axes. No tick marks, no tick values,
no gridlines, no title, no legend, no units. There is no way to pin a range: every series is
normalised to its own largest magnitude, always, and there is no `min`/`max` parameter to say
otherwise. There is no log scale, no stacking, no grouped bars, and no multi-series call.

#figure-table("The missing pieces, and where each one actually comes from.")[
#tbl(
  columns: (auto, 1fr),
  [*Not in `Chart`*], [*Build it with*],
  [Axis lines], [`Picture.line` along the two edges of the box],
  [Tick values, titles, units], [`Picture.text`, or a text-over-box tag built with `.on` (Chapter 16) for a readable panel behind it],
  [Gridlines], [`Picture.line` per gridline, `.dotted`, at `Color.LightGray.fadeOut(...)`],
  [A fixed y-range], [Nothing --- normalise the data yourself and draw the polyline directly],
  [Two series on one scale], [`Picture.polyline`, with a peak you choose (below)],
  [Values below a baseline], [`scatter`, or shift the data positive and draw your own zero line],
)
]

The multi-series limit is the one that misleads. Two `Chart.line` calls over the same box compose
perfectly --- `a.on(b)` --- and each has been scaled to its *own* peak, so a series that never
exceeds 3 and one that reaches 300 are drawn to the identical height. The chart looks like a
comparison and is not one, and dividing the data first does not help: the normalisation is
recomputed from whatever you pass in. When two series must share a scale, drop one level and write
the mapping yourself --- four lines of the same `Picture` vocabulary, which is all `Chart` does
internally:

#example("A series on a scale you control, rather than on its own.")[
```scala
def series(values: Seq[Double], peak: Double, width: Int, height: Int): Picture =
  Picture.polyline(values.zipWithIndex.map { (v, i) =>
    Point(i.toDouble / (values.size - 1) * width, height - v / peak * (height - 2))
  })

val faces, bodies: Seq[Double] = ???             // two counts from the same pass over the clip
val peak = (faces ++ bodies).max                 // one peak for both
val both = series(faces,  peak, 240, 56).strokeColor(Color.Cyan).strokeWidth(2)
  .on(series(bodies, peak, 240, 56).strokeColor(Color.Orange).strokeWidth(2))
```
]

The `height - 2` is the same two-pixel inset the built-in charts use; keeping it means your
hand-rolled series lines up with a `Chart.area` fill drawn beside it.

#subsect("The magnitude fold")

One more consequence of that normalisation deserves its own warning, because it produces a chart
that is entirely plausible and entirely wrong. `bars`, `line` and `area` plot `math.abs(v)`. Face
counts are never negative, so the running example never notices --- but the moment you chart the
*change* in the count, the data is signed:

```scala
val delta = counts.zip(counts.drop(1)).map((a, b) => b - a)
Chart.line(delta, 240, 56)     // draws |b - a|: every departure looks like an arrival
```

Nothing throws. Nothing is clipped. The series is folded upwards about zero, and a frame where two
people left renders identically to one where two arrived. `pie` rectifies the same way, treating a
negative value as a positive share of the total. Shift signed data into the positive range yourself
and draw the baseline where zero landed:

#example("Signed data, plotted honestly.")[
```scala
val lo    = math.min(0.0, delta.min)           // the shift floor, never above zero
val above = delta.map(_ - lo)                  // now every value is >= 0
val peak  = above.max.max(1e-9)                // the floor Chart applies internally, applied here
val zeroY = 56 - (-lo) / peak * (56 - 2)       // where the original zero ends up

val plot = Chart.line(above, 240, 56, Color.Yellow)
  .on(Picture.line(Point(0, zeroY), Point(240, zeroY)).strokeColor(Color.LightGray).dotted)
```
]

`scatter` is the exception and the escape hatch: it maps the true `(x, y)` range into the box, so
negatives land where they belong. `histogram` is unaffected --- it bins the raw values over their
own `[min, max]` range, so negative *data* falls in the right bucket, and the counts it then charts
are never negative.

#sect("Design that survives the medium")

A chart in a report is read at arm's length on a bright screen. A chart burned into the corner of a
1080p frame is read after an encoder has been through it, on a phone, at half size, in a chat
client. The second case is the unforgiving one.

*Measure the type, do not guess it.* Text is drawn with OpenCV's Hershey vector fonts at
`fontScale`, whose default is `0.5`. What that means in pixels depends on the font and the string,
and `Draw.textSize` will tell you rather than leaving it to a trial render:

```scala
Draw.textSize("faces/frame", Font.Simplex, 0.5).size.height   // the glyph height, in pixels
```

`Draw.textSize`'s own `scale` defaults to `1.0`, not to the `Picture` style's `0.5`, so pass the
scale you are actually drawing at --- the two defaults differ, and a measurement taken at the wrong
one is off by a factor of two. The `baseline` field beside `size` is the descender depth; a box
drawn `size.height` tall clips every `g` and `y`, which is the arithmetic a `Picture`'s own `bounds`
already does for you --- it measures text as `size.height` above the baseline and `baseline` below.

Two consequences follow. Hershey glyphs are strokes, not filled outlines, so a small `fontScale` at
the default `strokeWidth` of 1 produces one-pixel-wide letters --- and a one-pixel line is the first
thing a lossy encoder discards. There is no thinner setting to blame: `strokeWidth` clamps its
argument with `math.max(1, width)`, so 1 is the floor, and the only remedies are a heavier stroke or
a larger scale. If you must go small, thicken before you shrink. And the Hershey fonts are ASCII: a
non-ASCII character is drawn as `?`, so no degree sign, no micro sign, no en dash in an axis label.
Write `deg` and `us` and be understood.

*Label directly; do not spend a legend.* A legend costs a swatch, a gap and a word per series. In a
200-pixel-wide overlay that is a third of the chart given over to a lookup table, and it puts the
name somewhere the eye has to travel to and back. Put the name at the end of its own line instead,
in its own colour:

```scala
val last   = Point(240, 56 - faces.last / peak * (56 - 2))   // the series' right-hand end
val glyphs = Picture.text("faces", last).strokeColor(Color.Black).fontScale(0.4)
val tag = glyphs.bounds.fold(glyphs) { b =>
  glyphs.on(
    Picture
      .rectangle(Rect((b.minX - 2).round.toInt, (b.minY - 2).round.toInt,
                      (b.width + 4).round.toInt, (b.height + 4).round.toInt))
      .fillColor(Color.Cyan)
      .noStroke
  )
}
```

`bounds` measures the string with the style's own font and scale, descenders included, and sizing a
filled box from that measurement is what makes a tag readable over a frame where bare `Picture.text`
would land white-on-white half the time. Build it as `glyphs.on(box)` rather than reaching for
`Picture.label`: as Chapter 16 warns, `label` composes the box with `under`, so an opaque background
is drawn last and hides the text. `Picture.text` anchors on the glyph baseline, so a tag anchored at
`x = width` hangs off the right-hand end of the plot: leave it that room in the panel, or subtract
the measured width and put the tag inside.

#sidebar("What a codec does to a thin red line")[
Video compression does not treat colour the way it treats brightness. Every codec you are likely to
write through --- MJPEG, H.264, VP9 --- keeps luminance at full resolution and subsamples
chrominance, usually to half in each direction, then quantises the colour planes harder than the
luma plane. A thin line whose only separation from its background is *hue* is therefore attacking
the exact channel the encoder has decided matters least: red on green at matched brightness smears
into a brown smudge at any bitrate you would actually ship.

The fix is to separate by lightness, not by hue alone. `Color` gives you the number: `color.hsl`
returns `(hue, saturation, lightness)`, and the third component is the one that survives.
`Color.Yellow` against `Color.Black.withAlpha(150)` is a lightness contrast that reads at any
bitrate; `Color.Red` against `Color.Purple` is a hue contrast that reads on your monitor and nowhere
else. `Color.wheel(n)` spaces hues evenly and holds lightness fixed at `0.55` --- excellent for
categorical labels in a report, indifferent for lines that have to survive an encoder. For that
case, pick from the named colours by brightness, or build a `Color.ramp` from a dark colour to a
light one and let the scale carry the meaning.
]

#sect("Where this goes next")

Six functions, a `Picture` out of each, and the rest is the composition vocabulary you already had.
That is the whole of the chart layer, and its limits are real: no axes, no ranges, one scale per
series, magnitudes only. What it buys is a plot rendered by the same engine that rendered the frame,
in the same process, with no display server, no dependency beyond the graphics module Chapter 16
already added, and no native memory of its own.

The counts chart in the corner of one still frame is a summary. The obvious next question is what it
looks like as the clip plays --- a trace that grows a point per frame, written back out as a video
or a shareable GIF. Chapter 18, #emph[Animation and GIF], is a `Picture` valued by frame number, and it is the
last piece of the graphics module.
