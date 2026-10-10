# Reading & writing images

```scala mdoc:invisible
import scalacv.graphs.*
import scalacv.*
OpenCv.load()
```

Every image pipeline starts by getting pixels *in* and ends by getting them *out* — from a file, an HTTP
body, a database BLOB, a byte array in a test. This page is about that boundary: the small set of
functions that turn the outside world into a `Mat` you can work with, and a processed image back into
bytes on disk or in memory.

It is also the one edge of the library where OpenCV's own error reporting is genuinely inconsistent — a
missing file, a corrupt JPEG, and an unknown format each fail in a *different* way, and two of them fail
silently. scalacv flattens all of that into a single `Either[CvError, ?]`, so a bad path is a value you
handle right there, never a `CvException` that surfaces three call frames later.

## The two layers, and which to pick

Everything here comes in two flavours:

| You have / want | Reach for | Returns |
|---|---|---|
| A path or bytes, and a full read → process → write pipeline | [`Image`](/image-api) | `Either[CvError, Image]` |
| A raw `org.opencv.*` `Mat` already in hand | [`Images`](#the-images-object) | `Either[CvError, Managed[Mat]]` |

Reach for **`Image`** first — it is the fluent, self-freeing wrapper, and `write`/`bytes` encode *and*
release in one step. Drop to **`Images`** when you already hold a `Mat` (a detector's output, a video
frame you copied) and just need to encode or persist it. The two share the same error model; `Image.read`
is literally `Images.read` with an `Image` wrapped around the result.

:::tip
If you are new here, start with [`Image.reading`](#prefer-image-for-read--process--write) — it opens a
file, runs your pipeline, and closes the image for you even on failure. Keep borrowed handles and lazy effects inside its synchronous callback; raw or explicitly transferred resources still need an owner.
:::

## Quick start

The whole shape of an I/O pipeline, top to bottom — read a file, transform it, write it back:

```scala mdoc:compile-only
Image.read("photo.jpg").flatMap(_.gray.blur(2).canny(80, 160).write("edges.png"))
```

That touches a real file, so it is shown here as a non-running snippet. The rest of this page builds a
*synthetic* image in memory, so every remaining example actually runs during the docs build.

## Why reading returns an `Either`

`imread` and `imdecode` **never throw** for a file that is missing, is a directory, or holds bytes that
are not a decodable image. They return a `Mat` whose `empty()` is `true` — and print a `findDecoder`
warning to stderr that nothing can silence. Anyone who forgets the `empty()` check does not fail there;
they fail later, with a `CvException` from some innocent `Imgproc` call that had nothing to do with the
mistake.

scalacv makes the check for you and turns that empty Mat into a `Left`, so the failure is impossible to
forget:

```scala mdoc
Images.read("/does/not/exist.png").isLeft
```

The `Left` is a [`CvError.DecodeFailed`](/error-model), and it says **which** of the unreadable cases
happened — absent, a directory, empty, or genuinely undecodable. That is more than `imread` can tell you:
`imread` answers all four with the same empty Mat. `Images.read` gets the distinction by doing the file
I/O on the JVM side (`Files.readAllBytes`) and handing the bytes to the same in-memory decoder
`Images.decode` uses, so the filesystem questions are answered by the filesystem:

```scala mdoc
Images.read("/does/not/exist.png").left.map(_.getMessage)
```

:::note
Every failure here folds into `CvError`. From OpenCV's codecs: a decode that produces an empty Mat
(→ [`DecodeFailed`](/error-model)), an encode that returns `false` (→ [`EncodeFailed`](/error-model)),
and an unknown extension, which *throws* — headed off with a `haveImageWriter` check so it too becomes an
`EncodeFailed` rather than an escaped exception. From the JVM's file I/O, which `read` and `write` use for
the filesystem half of the job: a missing file, a directory, an unwritable destination or a path the
filesystem cannot represent, each with its own message. See the [error model](/error-model) for the full
ADT.
:::

:::info[Why the file I/O is not `imread`'s]
Routing through `Files.readAllBytes`/`Files.write` also fixes a Windows-only bug. `imread` takes a
`const char*`, and the JNI hands it the path in modified UTF-8, which the Windows CRT's `fopen` then
interprets in the active ANSI code page — so any path with a non-ASCII character (`фото.png`,
`日本.png`, a user folder with an accent in it) fails to open and is misreported as "missing file". Java's
own file APIs use the wide-character calls and have no such problem. The cost is that `read` buffers the
whole encoded file in the JVM heap, which matters only above ~2 GB. Note that the *other* String-path
native calls — `VideoCapture`, `VideoWriter`, `CascadeClassifier.load`, `Dnn.readNet` — still carry the
same hazard; they have no in-memory equivalent to reroute through.
:::

## The `Images` object

Four functions, symmetric in pairs — file vs memory, in vs out:

| | Read (in) | Write (out) |
|---|---|---|
| **File** | `read(path, flags)` | `write(path, mat)` |
| **Memory** | `decode(bytes, flags)` | `encode(mat, ext)` |

### Reading from a file

`Images.read` resolves a filesystem path itself — it does not understand classpath resources or URLs —
and hands back an owned Mat you are responsible for:

```scala mdoc:compile-only
Images.read("photo.jpg")                             // Either[CvError, Managed[Mat]]
Images.read("scan.png", ImreadFlags.Grayscale)       // decode straight to one channel
```

Because the result is owned, prefer consuming it in place over holding it. `Managed.use` runs your
function and releases the Mat when it returns, so nothing leaks:

```scala mdoc:compile-only
Images.read("photo.jpg").map(_.use { mat =>
  // work with `mat` here; it is released when `use` returns
  mat.rows * mat.cols
})
```

### Decoding from memory

`Images.decode` is `read`'s in-memory twin — for an HTTP response body, a BLOB, or a test fixture you
already hold as bytes. It rejects an empty array before it ever reaches OpenCV (there is nothing there to
decode), and otherwise behaves exactly like `read`, including the empty-Mat-to-`Left` translation:

```scala mdoc:compile-only
Images.decode(httpBody)                              // Either[CvError, Managed[Mat]]
Images.decode(httpBody, ImreadFlags.Unchanged)       // keep any alpha channel
```

```scala mdoc:invisible
// placeholder so the compile-only snippet above type-checks without a real request
val httpBody: Array[Byte] = Array.emptyByteArray
```

An empty array short-circuits to a `Left` with its own message, so you never confuse "no bytes arrived"
with "the bytes were not an image":

```scala mdoc
Images.decode(Array.emptyByteArray).left.map(_.getMessage)
```

### Writing to a file

`Images.write` picks the encoder from the path's extension. It does **not** modify or release the Mat you
pass. `imwrite` has two failure modes and `write` covers both:

- an unwritable destination — a missing parent directory, no permission — which `imwrite` reports by
  returning `false`, surfaced as [`CvError.EncodeFailed`](/error-model);
- an extension with no registered encoder, which `imwrite` reports by **throwing** `CvException`,
  surfaced as `CvError.EncodeFailed` too (a `haveImageWriter` check catches it before the throw).

Both are `Left`s, so a single check catches either:

```scala mdoc:silent
import org.opencv.core.{CvType, Mat}

// A synthetic image, so this page needs no fixture file. `source` is released at the end.
val source = Mat(64, 64, CvType.CV_8UC3)
```

```scala mdoc
Images.write("/no/such/directory/out.png", source).isLeft   // parent directory missing -> EncodeFailed
```

:::warning
`write` does **not** create parent directories. A path into a folder that does not exist is a `Left`, not
a thrown exception — but it is still a failure, so pattern-match or `flatMap` the result rather than
assuming success.
:::

### Encoding to memory

`Images.encode` is `write` without the filesystem: it returns the encoded image file as a plain JVM
`Array[Byte]` (the staging buffer is released before returning, so there is no native memory left for you
to think about). `ext` selects the format the way a filename extension would — `".png"`, `".jpg"`,
`".webp"`.

A **leading period matters**: `imencode` silently fails without one. `encode` adds it for you if you
forget, so both of these are the same call:

```scala mdoc
(Images.encode(source, ".png").isRight, Images.encode(source, "png").isRight)
```

An extension with no encoder yields a `Left` rather than the `CvException` OpenCV throws:

```scala mdoc
Images.encode(source, ".not-a-format").isLeft
```

Format choice is a trade of size against fidelity — the codec is picked purely from the extension:

| `ext` | Lossy? | Alpha | Use it for |
|---|---|---|---|
| `".png"` | no | yes | screenshots, masks, anything you re-process |
| `".jpg"` / `".jpeg"` | yes | no | photos where a smaller file wins |
| `".webp"` | either | yes | modern web delivery |
| `".bmp"` | no | no | a raw, decoder-free dump |

## `ImreadFlags`

Both `read` and `decode` take an `ImreadFlags`, defaulting to `ImreadFlags.Color`. It is a typed value,
not a bare `int` — a decode `color`, an optional `scale`, and an `ignoreOrientation` flag. The three
named constants cover the common cases:

| Value | Meaning |
|---|---|
| `ImreadFlags.Color` | force 3-channel BGR (the default) |
| `ImreadFlags.Grayscale` | force single-channel greyscale |
| `ImreadFlags.Unchanged` | as stored, alpha channel and all |

The full `ImreadColor` enum has two more cases you build with the `ImreadFlags(color, …)` constructor:

| `ImreadColor` | Effect |
|---|---|
| `Grayscale` | single channel |
| `Color` | 3-channel BGR |
| `ColorRgb` | 3-channel RGB (no `BgrToRgb` step needed) |
| `Unchanged` | exactly as stored, alpha and all |
| `AnyDepth` | keep 16-/32-bit depth instead of downcasting to 8-bit |

Unlike a raw bitmask, `color` and `scale` are *not* independent bits you OR together — OpenCV's
`IMREAD_*` constants are not orthogonal, so the `(color, scale)` pair maps totally onto exactly one named
constant. Pass `ignoreOrientation = true` to skip the EXIF rotation (the one genuinely independent flag),
and an `ImreadScale` other than `Full` to decode a downscaled image cheaply. The resolved OpenCV int is
`cvValue`:

```scala mdoc
ImreadFlags.Grayscale.cvValue
```

### Reduced-size decode

`ImreadScale` decodes a *downscaled* image directly, without ever materialising the full-resolution one.
That is strictly cheaper than reading full then resizing, because the codec skips the discarded detail
rather than producing it and throwing it away:

| `ImreadScale` | Fraction | A 4000×3000 source decodes to |
|---|---|---|
| `Full` | 1/1 | 4000×3000 |
| `Half` | 1/2 | 2000×1500 |
| `Quarter` | 1/4 | 1000×750 |
| `Eighth` | 1/8 | 500×375 |

```scala mdoc:silent
// greyscale, decoded at half resolution
val thumbnail = ImreadFlags(ImreadColor.Grayscale, ImreadScale.Half)
```

:::tip
Building a thumbnail grid or a gallery preview? Decode at `Quarter` or `Eighth` up front. On a folder of
large photos it is the single biggest I/O win available — see [Performance](/performance).
:::

Reduced-size decode exists only for `Grayscale` and `Color`, and `Unchanged` can carry no extra bit;
combinations OpenCV has no constant for are rejected at construction, not silently decoded as something
else:

```scala mdoc:crash
ImreadFlags(ImreadColor.AnyDepth, ImreadScale.Half) // require fails: no reduced-size AnyDepth decode
```

`Unchanged` likewise refuses a scale or an orientation flag, because `IMREAD_UNCHANGED` is `-1` and its
bits swamp everything else:

```scala mdoc:crash
ImreadFlags(ImreadColor.Unchanged, ImreadScale.Half) // require fails
```

## Round-tripping through memory

`encode` and `decode` compose into a full in-memory round trip — the pattern behind serving an image
over HTTP, or stashing one in a cache — with no file ever touched:

```scala mdoc
val roundTrip: Either[CvError, (Int, Int)] =
  Images
    .encode(source, ".png")                                  // Mat -> PNG bytes
    .flatMap(png => Images.decode(png))                      // bytes -> owned Mat
    .map(_.use(mat => (mat.rows, mat.cols)))                 // read it, then release
roundTrip
```

The same round trip in the high-level API never names a `Mat`: `Image.bytes(".png")` encodes and
releases, `Image.decode(bytes)` reads back:

```scala mdoc:compile-only
for
  png  <- Image.read("photo.jpg").flatMap(_.gray.bytes(".png"))
  back <- Image.decode(png)
yield back.width
```

```scala mdoc:invisible
source.release()
```

## Prefer `Image` for read → process → write

`Images` is the right layer when you already hold a raw `Mat`. For the far more common
read-something, transform-it, write-it-back shape, the high-level [`Image`](/image-api) is nicer: its
`read`/`decode` return an `Either[CvError, Image]`, and `write`/`bytes` encode **and release** in one
step, so a whole pipeline never names a `Mat` or a `release`:

```scala mdoc:compile-only
Image.read("photo.jpg").flatMap(_.gray.blur(2).canny(80, 160).write("edges.png"))
```

Best of all is `Image.reading`, which opens the file, runs your body, and closes the image afterwards —
even if the body already consumed it, and even on an exception. Failure inside the body comes back as a
`Left` rather than escaping the `Either`:

```scala mdoc:compile-only
Image.reading("photo.jpg")(_.gray.canny(80, 160).write("edges.png"))
```

`Image.decode(bytes)` mirrors it for in-memory input, and `Image.bytes(".png")` mirrors `encode` on the
way out. See the [Image API](/image-api) for the full story.

## Next

- [Image API](/image-api) — the fluent `Image` wrapper this page keeps pointing at, with move semantics explained.
- [Image processing](/image-processing) — the operation catalogue: what to do with the pixels once they are in.
- [Mat lifecycle](/mat-lifecycle) — how `Managed[Mat]` guarantees release-exactly-once under both layers.
