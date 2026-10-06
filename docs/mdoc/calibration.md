# Camera calibration

Every metric thing scalacv does with geometry — a marker's [distance in centimetres](/marker-ar),
[where the camera is](/navigation#absolute-localization), the [depth of a stereo pair](/navigation#stereo-depth-obstacles) —
rests on knowing the camera's optics: its focal length, its optical centre, and how its lens bends
straight lines. Those numbers are the [`Intrinsics`](/api/core/scalacv/Intrinsics.html). Until you
measure them you are stuck with `Intrinsics.approx`, a field-of-view *guess* — good enough to watch an
overlay track, not good enough to measure with. **Calibration replaces the guess with measurement.**

:::tip[New here? Read this first.]
Calibration is a one-time step you do per camera (per lens, per zoom setting). You print a chessboard,
photograph it from a dozen angles, and scalacv reads the camera's true focal length and lens distortion
off those photos. Feed the result to [Marker AR](/marker-ar), [pose estimation](/pose-estimation) or
[navigation](/navigation) and their measurements become *real* — metres, not "indicative".
:::

```scala mdoc:invisible
import scalacv.vision.*
import scalacv.*
import org.opencv.core.{CvType, Mat}
import org.opencv.core as cv
OpenCv.load()
```

## The three-step workflow

The whole process is three calls, and this page walks each one:

| Step | Call | What you get |
|---|---|---|
| 1. Describe the board | `ChessboardPattern(columns, rows, squareSize)` | the target's known geometry |
| 2. (optional) Check one frame | [`Calibration.findCorners`](/api/core/scalacv/vision/Calibration$.html) | `Some(corners)` if the board is fully visible |
| 3. Solve | [`Calibration.fromChessboard`](/api/core/scalacv/vision/Calibration$.html) | a [`Calibration`](/api/core/scalacv/vision/Calibration.html): intrinsics + error |

## The target

Calibration works by showing the camera something whose geometry you already know and seeing how the
lens deforms it. The classic target is a chessboard, described by its **inner-corner** grid — the
crossings *between* squares, not the squares themselves. A board of 10×7 squares has a 9×6 inner grid:

```scala mdoc:silent
val board = ChessboardPattern(columns = 9, rows = 6, squareSize = 0.025) // 25 mm squares
```

The fields, and why each is what it is:

| Field | Meaning | Notes |
|---|---|---|
| `columns` | inner corners across | squares-across − 1; must be ≥ 2 |
| `rows` | inner corners down | squares-down − 1; must be ≥ 2 |
| `squareSize` | real edge length of one square | default `1.0`; sets the world unit, **not** the intrinsics |

`squareSize` is the real edge length of one square. It sets the unit the recovered geometry is
expressed in — pass metres and a marker's `distance` comes back in metres — but it does **not** affect
the intrinsics, which are always in pixels. The total corner count the detector must find is
`columns × rows`:

```scala mdoc
board.corners
```

:::note[Why an asymmetric grid?]
Prefer a board with an **odd × even** inner grid (like 9×6), not square (like 8×8). A symmetric board
has a rotational ambiguity — the solver cannot tell which way is up — while an asymmetric one has a
single unambiguous orientation in every view.
:::

## Finding the board

[`findCorners`](/api/core/scalacv/vision/Calibration$.html) locates and sub-pixel-refines the whole inner
grid in one image, returning the corners in pixel coordinates (or `None` if the full board is not
visible — the detector is all-or-nothing):

```scala mdoc:invisible
// A synthetic flat board with a white quiet zone, so the doc runs with no fixture files.
def chessboard(): Image =
  val square = 40; val margin = 40
  val w = (board.columns + 1) * square + 2 * margin
  val h = (board.rows + 1) * square + 2 * margin
  val m = Mat(h, w, CvType.CV_8UC3, cv.Scalar(255, 255, 255))
  for r <- 0 until board.rows + 1; c <- 0 until board.columns + 1 if (r + c) % 2 == 0 do
    val roi = m.submat(margin + r * square, margin + (r + 1) * square, margin + c * square, margin + (c + 1) * square)
    roi.setTo(cv.Scalar(0, 0, 0)); roi.release()
  Image.wrap(Managed(m))
```

```scala mdoc
val img = chessboard()
try Calibration.findCorners(img, board).map(_.size)
finally img.close()
```

All 54 corners (9 × 6), found. `findCorners` **borrows** the image (it stays alive; close it yourself),
and returns plain immutable `Point`s that are safe to keep after the image is freed.

This all-or-nothing behaviour is exactly what you want for an interactive capture tool: show the live
frame, call `findCorners`, and only keep frames where it returns `Some`.

```scala mdoc:compile-only
import scala.collection.mutable.ArrayBuffer

val keep = ArrayBuffer.empty[Image]
Camera.using(0) { cam =>
  cam.foreach() { frame =>
    Calibration.findCorners(frame, board) match
      case Some(_) if keep.size < 15 => keep += frame.copy // a good view — bank a copy
      case _                         => ()                 // no board (or enough already)
  }
}
// `keep` now holds up to 15 views spanning whatever angles you moved the board through.
```

:::tip[Why `Option`, not `Either`?]
"The board is not in this frame" is an ordinary query result, not a failure — the same reason the
[detectors](/object-detection) and [trackers](/tracking) return `Option`/`Seq`. It is
[`fromChessboard`](#recovering-the-camera), where *too few* boards or a non-converging solver truly is a
failure, that returns an `Either`.
:::

## Recovering the camera

Hand several views of the board — from a *range of angles*, which is what makes the problem
well-posed — to [`fromChessboard`](/api/core/scalacv/vision/Calibration$.html). It finds the board in each,
drops the frames where it is not fully visible, and runs OpenCV's calibration to recover the camera:

```scala mdoc:compile-only
val frames: Seq[Image] = (1 to 15).flatMap(i => Image.read(s"calib/$i.jpg").toOption)

Calibration.fromChessboard(frames, board, minViews = 8) match
  case Right(calib) =>
    val i = calib.intrinsics
    println(f"fx=${i.fx}%.1f fy=${i.fy}%.1f  centre=(${i.cx}%.1f, ${i.cy}%.1f)")
    println(f"RMS reprojection error: ${calib.reprojectionError}%.3f px")
  case Left(err) =>
    System.err.println(err.getMessage) // too few views showed the board, or the solver failed
```

`minViews` is your floor on how many views must actually contain the whole board:

| `minViews` | When | Trade-off |
|---|---|---|
| `3` (default) | quick check, controlled lighting | numbers wobble; fine to *see* it work |
| `8`–`15` | production calibration | where the intrinsics get trustworthy |
| `20+` | wide/fisheye lenses, tight tolerance | more angles pin down distortion |

The views you pass may include duds — blurred, badly angled, board half out of frame. `fromChessboard`
runs `findCorners` on each and **silently skips** the ones that fail, so you can hand it a whole capture
folder. Only if *fewer than `minViews`* survive does it fail.

### Reading the result

A [`Calibration`](/api/core/scalacv/vision/Calibration.html) carries three things:

| Field | Type | What it is |
|---|---|---|
| `intrinsics` | [`Intrinsics`](/api/core/scalacv/Intrinsics.html) | the recovered `fx, fy, cx, cy` + distortion |
| `imageSize` | `Size` | the resolution the numbers were measured at |
| `reprojectionError` | `Double` | RMS pixel error — the quality score |

The headline quality number is `reprojectionError`: the root-mean-square distance, over every corner of
every view, between where a corner actually sat and where the recovered model predicts it. It comes back
*with* the result rather than being buried, precisely so you can judge the fit:

| RMS error | Verdict | Likely cause if high |
|---|---|---|
| **< 0.5 px** | excellent | — |
| **0.5–1 px** | good — ship it | — |
| **1–2 px** | marginal | some blur, too few angles |
| **> 2 px** | poor — recapture | motion blur, bad board, or the board flexed |

:::warning[The intrinsics are tied to the resolution]
`fx, fy, cx, cy` are in **pixels at `imageSize`**. If you calibrate at 1920×1080 but run the camera at
960×540, halve them (or recalibrate at the running resolution). Changing zoom or swapping the lens
invalidates the calibration entirely.
:::

When too few views show the whole board the result is a
[`CvError.CalibrationFailed`](/api/core/scalacv/CvError$$CalibrationFailed.html) — a value, not an
exception, because how many boards a capture happened to catch is data, not a bug:

```scala mdoc
{
  val one = chessboard()
  val out = Calibration.fromChessboard(Seq(one), board, minViews = 8) match
    case Right(_)  => "calibrated"
    case Left(err) => s"failed: ${err.getMessage.take(40)}…"
  one.close()
  out
}
```

## The camera model it produces

The recovered numbers are an [`Intrinsics`](/api/core/scalacv/Intrinsics.html) — the same type every
metric tool takes:

| Field | Meaning | Units |
|---|---|---|
| `fx`, `fy` | focal length | pixels |
| `cx`, `cy` | principal point (optical centre) | pixels |
| `distortion` | radial/tangential coefficients `k1, k2, p1, p2[, k3 …]` | — |

Before you have calibrated, `Intrinsics.approx` fabricates a serviceable model from the image size and
an estimated field of view — centred principal point, square pixels, *no* distortion:

```scala mdoc:silent
val guess = Intrinsics.approx(Size(1280, 720), horizontalFovDegrees = 60)
```

```scala mdoc
f"guessed fx=${guess.fx}%.0f  cx=${guess.cx}%.0f  cy=${guess.cy}%.0f"
```

A narrower field of view is a longer lens, so `fx`/`fy` grow as the angle shrinks. This is the placeholder
a real calibration replaces.

## Straightening the lens

With a calibration in hand, [`undistort`](/api/core/scalacv/Image.html) maps the barrel or pincushion
bend of a wide lens back out, so straight edges in the world come back straight. There are two overloads —
pass the whole `Calibration` (it unwraps the intrinsics for you) or the `Intrinsics` directly:

```scala mdoc:compile-only
val calibration: Calibration = ???
Image.reading("wide-angle.jpg")(_.undistort(calibration).write("straightened.jpg"))
```

:::note[`undistort` consumes the image]
Like every transform, `undistort` follows [move semantics](/mat-lifecycle): it *consumes* the receiver
and returns a new `Image`. Take `.copy` first if you also need the original.
:::

## Feeding the geometry stack

The recovered `intrinsics` are the *same* [`Intrinsics`](/api/core/scalacv/Intrinsics.html) the pose
tools already take — so a calibration drops straight in where an `Intrinsics.approx` guess used to be,
turning "indicative" into metric:

```scala mdoc:compile-only
val calib: Calibration = ???
val marker: ArucoMarker = ???

// Marker pose, now to scale (see: Marker AR)
Ar.estimatePose(marker, markerLength = 0.05, calib.intrinsics)
```

That is the whole point of measuring the camera. Everything downstream becomes metric the moment you
hand it a real calibration instead of a field-of-view estimate:

| Consumer | Becomes metric | See |
|---|---|---|
| `Ar.estimatePose` / `arMarkers` | marker distance in real units | [Marker AR](/marker-ar) |
| `Localizer.locate` | absolute camera position at map scale | [Navigation](/navigation#absolute-localization) |
| `VisualOdometry` / `Odometry` | correct rotation between frames | [Navigation](/navigation#visual-odometry) |
| `HeadPose.estimate` | head distance and orientation | [Pose estimation](/pose-estimation) |
| `Image.undistort` | straight lines stay straight | [above](#straightening-the-lens) |

The full runnable version — which projects a board through a *known* camera and recovers it back to
within a fraction of a percent — is
[`CalibrationDemo`](https://github.com/w0rxbend/scalacv/blob/master/examples/src/scalacv/CalibrationDemo.scala).

## Next

- [Marker AR](/marker-ar) — the first thing to point a calibrated camera at.
- [Visual navigation & SLAM](/navigation) — odometry, localization and stereo depth, all metric once calibrated.
- [Pose estimation](/pose-estimation) — head pose, which shares the same `Intrinsics` and `solvePnP` core.
