---
slug: /
title: scalacv
description: A Scala 3 wrapper for the OpenCV 4.14 Java API — a fluent, headless image pipeline that is honest about native memory.
hide_title: true
hide_table_of_contents: true
wrapperClassName: scv-landing
---

{/*
  SOURCE OF TRUTH for the Docusaurus landing page.

  mdoc type-checks the Scala below, then the docs assemble step (./mill docs.mdoc +
  scripts/assemble-docs.sh) writes this file to website/docs/index.mdx. Do not edit
  website/docs/index.mdx — edit this file.

  This page is authored as MDX (the assemble step gives it the .mdx extension) so it can import the
  React components under website/src/components/. Three consequences worth knowing before editing:

    - Comments must use this MDX form. An HTML comment is a parse error, not a comment.
    - A bare "<" followed by a letter starts a JSX tag. Write it as &lt; in prose, or wrap it in
      backticks.
    - A bare "{" starts a JavaScript expression. Same fix.

  Fenced code blocks are exempt from all three, so ordinary Scala snippets need no escaping.
*/}

import Hero from '@site/src/components/Hero';
import Section from '@site/src/components/Section';
import Capabilities from '@site/src/components/Capabilities';
import Paths from '@site/src/components/Paths';

<Hero />

<Section eyebrow="the shape of it" title="One chain, from file to result" lede={<>Consuming transforms transfer ownership to their result. Finish manually owned chains with a consuming terminal or close, and keep borrows inside the owner’s lifetime.</>}>

This runs headless, with no image file and no display server — which is also how it is tested:

```scala mdoc:silent
import scalacv.vision.*
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

`Image.reading` scopes a file to a block and releases it on success, on failure, and on exception —
prefer it for synchronous work, without escaping borrowed handles or lazy effects:

```scala mdoc:compile-only
Image.reading("photo.jpg") { img => img.gray.blur(2).canny(80, 160).write("edges.png") }
```

Detection results cross back as ordinary immutable Scala values, not as live native handles — so
there is nothing left for you to free:

```scala mdoc:compile-only
Image.reading("shelf.jpg") { img =>
  val codes: Seq[QrCode] = img.qrCodes          // decoded text and corners, no Mat left open
  img.drawText(s"${codes.size} codes found", Point(10, 30))
     .write("annotated.png")
}
```

</Section>

<Section eyebrow="what is in the box" title="Batteries, and the wiring diagram" tinted lede="Eight groups of capability, every one of them documented. Follow any chip to its guide." variant="wide">

<Capabilities />

</Section>

<Section eyebrow="the hard part" title="Honest about native memory">

An OpenCV `Mat` lives in native memory the JVM garbage collector cannot see and will not free. In
the Java API, forgetting `release()` leaks; releasing twice, or using a released `Mat`, is a
segmentation fault with no Scala stack trace to show you where.

`Managed[A]` moves that failure earlier and upward. It releases exactly once, and it throws an
ordinary Scala exception on use-after-release — in the JVM, before anything reaches JNI:

```scala mdoc:compile-only
val m = Managed(new org.opencv.core.Mat(4, 4, org.opencv.core.CvType.CV_8UC1))
m.close()
m.close()      // no-op: release happens exactly once
m.use(identity) // throws IllegalStateException, not SIGSEGV
```

The [Mat lifecycle guide](/mat-lifecycle) explains the whole model — who owns what, which
operations borrow rather than take, and how the leak test suite proves it with resident-set
measurements rather than assertions about intent.

</Section>

<Section eyebrow="find your route" title="Four ways in" tinted lede="Pick the row that describes you. Each is three pages long, in order." variant="wide">

<Paths />

</Section>

<Section eyebrow="no walled garden" title="The raw API is always one call away">

The high-level pipeline covers the common cases. When it does not cover yours, `mat` borrows the
underlying handle and the complete typed `org.opencv.*` surface is right there — along with a
mid-level layer of extension operations that return `Managed[Mat]` so you keep the safety without
the abstraction:

```scala mdoc:compile-only
Image.reading("photo.jpg") { img =>
  img.mat.cvtColor(ColorConversion.BgrToGray)   // mid-level extension → Managed[Mat]
     .pipe(_.gaussianBlur(Size(5, 5)))
     .pipe(_.canny(80, 160))
     .use(Images.encode(_, ".png"))
}
```

Nothing is hidden and nothing is final: see [the low-level guide](/low-level) for the escape
hatches, and [coming from OpenCV](/opencv-java) if you already know the C++ or Python names.

</Section>

<Section eyebrow="learn by building" title="Five tutorials, easiest first" tinted lede="Ordered by what each one needs from you, not by topic. The first two need nothing at all — they draw their own input, because this repository ships no image files.">

| Tutorial | What it teaches | What you need |
|---|---|---|
| [Count objects in an image](/tutorial) | threshold → contours → count | nothing — it draws its own scene |
| [Track a coloured object](/tutorial-color-tracking) | HSV masking and centroids | nothing — it draws its own scene |
| [Process a video frame by frame](/tutorial-video) | capture, transform, record | a clip or a webcam — or [make one](/sample-inputs) |
| [Detect faces in a photo](/tutorial-faces) | Haar cascades, then YuNet | a photograph; no model download |
| [Run a neural network](/tutorial-dnn) | ONNX inference through OpenCV | a model file — [get one](/models) |

</Section>

<Section eyebrow="next" title="Start where it suits you">

- **Never done this before?** [Image basics](/basics), then the [glossary](/glossary) when a word
  stops you.
- **Want it running today?** [Getting started](/getting-started) then the [cookbook](/cookbook) —
  around sixty recipes, each one runnable.
- **Evaluating it?** [Architecture](/architecture), [performance](/performance),
  [benchmark results](/benchmark-results), and the [FAQ](/faq).
- **Taking it to production?** [Streaming and backpressure](/streaming-and-backpressure),
  [observability](/observability), and [degradation and error budgets](/degradation-and-error-budgets).
- **Stuck?** [Troubleshooting](/troubleshooting) covers the errors people actually hit first.

Reference: [Operations](/operations-reference) · [Enums and constants](/enums-reference) ·
[Error model](/error-model) · [API docs](/api/core/index.html)

</Section>
