# Tutorial: process a video frame by frame

Images are the warm-up; video is where computer vision earns its keep. This tutorial builds up the video loop one step at a time: open a source, grab a single frame, process *every* frame, write the result back out as a new video, and finally raise an alarm when something moves. By the end you'll have the pattern behind edge-effect filters, dashcams, and motion-triggered recorders.

Video needs a real file or camera, so these snippets are `compile-only` (they type-check but don't run in the docs build) — copy them into a project with a clip to try them live.

```scala mdoc:silent
import scalacv.vision.*
import scalacv.*

OpenCv.load()
```

## Step 1 — open a source

A video file, a stream URL, or a webcam are all the same to [`Camera`](/video). Open it with a scoped helper so it's always closed:

```scala mdoc:compile-only
// A file (or an rtsp:// / http:// URL):
Camera.usingFile("clip.mp4") { cam =>
  // ... work with cam ...
}

// A webcam by device index:
Camera.using(0) { cam =>
  // ... work with cam ...
}
```

`usingFile` / `using` return `Either[CvError, A]` — a `Left` if the source can't be opened (missing file, busy camera, no backend). You never get a silently-empty stream.

## Step 2 — grab one frame

Before looping, sanity-check by pulling a single frame. `snapshot` hands you an owned [`Image`](/image-api) you can treat like any other:

```scala mdoc:compile-only
Camera.usingFile("clip.mp4") { cam =>
  cam.snapshot().flatMap(_.write("first-frame.png"))
}
```

## Step 3 — process every frame

`foreach` runs your function on every frame as an owned `Image` and **closes it for you** after each iteration — so a long video never piles up memory. Here we count how many frames have significant edge content:

```scala mdoc:compile-only
Camera.usingFile("clip.mp4") { cam =>
  var busyFrames = 0
  cam.foreach() { frame =>
    val edges = frame.copy.gray.canny(80, 160)
    try if edges.contours().size > 20 then busyFrames += 1
    finally edges.close()
  }
  busyFrames
}
```

Note the `frame.copy` — `foreach` owns `frame` and closes it, so we branch off a copy to avoid consuming the frame it's about to release. (If you *only* read the frame, you can transform it directly.)

## Step 4 — write a new video

`recordTo` reads every frame, applies your `transform`, and writes the results as a new video — the one-call form of the whole loop. The transform must **keep the frame size**, so a colour-space change is fine but a resize is not. Here we make an "edge video": greyscale → Canny → back to 3-channel so the recorder (colour) accepts it:

```scala mdoc:compile-only
Camera.usingFile("clip.mp4") { cam =>
  cam.recordTo("edges.mp4") { frame =>
    frame.gray.canny(80, 160).convert(ColorConversion.GrayToBgr)
  }
}
```

`recordTo` returns the number of frames written (or a `Left` if the codec is unavailable or the path isn't writable). If `mp4` fails on your platform, fall back to the always-available Motion-JPEG:

```scala mdoc:compile-only
Camera.usingFile("clip.mp4") { cam =>
  cam.recordTo("edges.avi", codec = Codec.Mjpg) { frame =>
    frame.gray.canny(80, 160).convert(ColorConversion.GrayToBgr)
  }
}
```

## Step 5 — a live motion alarm

Feed frames in order to a stateful [`MotionDetector`](/motion-detection) and it tells you whether — and where — something moved. This is the core of a security camera:

```scala mdoc:compile-only
Camera.using(0) { cam =>
  val detector = MotionDetector.frameDifference()
  try
    cam.foreach() { frame =>
      val motion = detector.detect(frame)
      if motion.moving then
        println(s"ALERT: motion in ${motion.regionCount} region(s), ${(motion.ratio * 100).round}% of frame")
    }
  finally detector.close()
}
```

The detector holds one frame of state between calls, so it must see frames in order on one thread — and it owns native memory, so `close()` it (here, via `try/finally`).

## Going faster: borrow, don't copy

`Camera.foreach` hands you an *owned* `Image` (one clone per frame) — convenient, and fine for most work. When you're only *reading* each frame and throughput matters, drop to [`Video.frames`](/video), which reuses **one buffer** for the whole stream (zero per-frame allocation). The catch is the borrowing contract — don't keep the frame past its turn:

```scala mdoc:compile-only
Video.open("clip.mp4").map { capture =>
  capture.use { c =>
    // `frame` is one reused buffer; reduce it inside the loop, never collect it.
    Video.frames(c) { frames =>
      frames.map(frame => frame.mat.cvtColor(ColorConversion.BgrToGray).use(_.rows)).sum
    }
  }
}
```

See [Performance](/performance#zero-copy-borrow-frames-instead-of-copying-them) for when each path wins, and [Mat lifecycle](/mat-lifecycle) for the borrowing rules.

## Next

- [Video & capture](/video) — the full capture/record surface, timeouts, backends.
- [Motion detection](/motion-detection) — frame-difference vs background-subtraction.
- [Concurrency](/concurrency) — processing streams across threads safely.
