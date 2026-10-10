# Deploying to production

Everything you need to ship a scalacv service reliably: packaging, containers, resource limits, model provisioning, warm-up, and graceful fallback. The individual mechanisms are covered elsewhere ([native cache](/native-cache), [performance](/performance), [concurrency](/concurrency)) — this page is the operational checklist that ties them together.

```scala mdoc:silent
import scalacv.*

OpenCv.load()
```

## Package for one platform, not all

Add the **single** native classifier for where the service runs — not `opencv-platform`. A per-platform build is ~50 MB of jars; `opencv-platform` is ~408 MB of every OS you'll never deploy to.

```scala
// production build: exactly one platform
mvn"com.worxbend::scalacv:0.4.1",
mvn"org.bytedeco:opencv:4.14.0-1.5.14;classifier=linux-x86_64",
mvn"org.bytedeco:openblas:0.3.34-1.5.14;classifier=linux-x86_64"
```

If you build multi-arch images, produce one artifact per arch with its matching classifier, rather than one fat artifact carrying all of them.

## Container image: pre-warm and pin the cache

The first `OpenCv.load()` extracts ~196 MB of natives into a cache. **Do that at build time**, so container cold-start is instant instead of paying a 196 MB unpack on every boot:

```dockerfile
FROM eclipse-temurin:17-jre AS runtime
ENV JAVACPP_CACHE=/opt/javacpp
COPY target/app.jar /app/app.jar

# Pre-warm: extract the natives into an image layer now, once.
RUN java -Dorg.bytedeco.javacpp.cachedir=$JAVACPP_CACHE -cp /app/app.jar scalacv.smoke

# Every run reuses the baked cache and caps physical memory so a leak fails fast, not slow.
ENV JAVA_TOOL_OPTIONS="-Dorg.bytedeco.javacpp.cachedir=/opt/javacpp -Dorg.bytedeco.javacpp.maxPhysicalBytes=1G"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

`scalacv.smoke` is any main that calls `OpenCv.load()`. See [The native cache](/native-cache#pre-warming-a-container-image) for relocating the cache to a shared volume instead of baking it.

## Cap memory — and cap it on the right counter

Native pixel buffers live off-heap, so `-Xmx` does **not** bound them. Set a physical-memory ceiling so a leak or a runaway workload throws fast instead of getting OOM-killed:

```sh
java -Xmx512m -Dorg.bytedeco.javacpp.maxPhysicalBytes=1G -jar app.jar
```

:::danger[Don't gate on `maxBytes` alone]
`-Dorg.bytedeco.javacpp.maxBytes` is checked against `Pointer.totalBytes()`, which is **blind** to scalacv's `org.opencv.core.Mat` buffers — a Mat leak sails right past it. Use `maxPhysicalBytes` (RSS-based). See [Performance](/performance#measuring-memory-do-it-right).
:::

In a cgroup/container, set `maxPhysicalBytes` comfortably below the container limit so the JVM throws its own diagnostic error *before* the kernel OOM-kills the process with no explanation.

## Tame the thread pools

OpenCV and OpenBLAS each spin an internal thread pool. In a service that already handles requests concurrently, those inner pools oversubscribe the cores and hurt latency. Pin them and let your request executor own the parallelism:

```sh
OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1 java -jar app.jar
```

For a batch job doing one big pipeline at a time, do the opposite — leave OpenCV's threads on so it parallelises the heavy kernels. Measure both; see [Concurrency](/concurrency#thread-pool-oversubscription).

## Provision models offline

DNN models and face-recognition weights are downloaded and cached by `Models.fetch`, which verifies a pinned SHA-256 and accepts `file://` URLs. For an **air-gapped** deployment, bake the model into the image (or a mounted volume) and point the spec at a `file://` path — no network needed at runtime. Cascade XML and the OpenCV natives are already inside the jars, so those never touch the network. See [The native cache](/native-cache#downloaded-model-files-dnn-face-recognition).

## Warm up at startup, not on the first request

`OpenCv.load()` (and the first inference on a DNN `Net`) pays a one-time cost. Do it during boot / health-check so the first *real* request isn't slow:

```scala mdoc:silent
// Call once at startup — idempotent, so it's safe to invoke from a health check too.
OpenCv.load()
val ready: Boolean = OpenCv.isLoaded
```

```scala mdoc
ready
```

Expose readiness only after `OpenCv.isLoaded` is true (and after any model has done one warm-up inference).

## Fail gracefully

- **Headless is the default.** `OpenCv.load()` needs no GUI toolkit — no `libgtk` in your base image. See [Troubleshooting](/troubleshooting#headless).
- **Codecs vary by platform.** If `recordTo`/`Recorder` returns a `Left` about an unavailable codec, fall back to `Codec.Mjpg` in an `.avi` (always built in). Handle the `Either`, don't assume `mp4` exists.
- **Reads return values, not exceptions.** `Image.read` on a bad upload is a `Left(DecodeFailed)` — validate it and return a 4xx, don't let it become a 500 three calls later.
- **Every request scopes its resources.** Use `Image.reading` / `Managed.use` / `Camera.using` per request so a handler that throws still releases native memory. A leak in a long-lived service is unbounded.

## Observability

- **RSS is your leak alarm.** Monitor process RSS (or `Pointer.physicalBytes()`); a steady climb under steady load is a leak. A flat RSS is a healthy release discipline. See [Testing](/testing) for an in-process RSS assertion you can promote to a metric.
- **OpenCV logs to stderr.** Some negative paths (a failed `imread`, a codec probe) print an OpenCV warning to stderr even when scalacv turns the failure into a `Left`. Don't alert on those lines alone — alert on your own error rate.

## The checklist

- [ ] One platform classifier, not `opencv-platform`.
- [ ] Native cache pre-warmed into the image (or a shared volume).
- [ ] `-Dorg.bytedeco.javacpp.maxPhysicalBytes` set below the container limit.
- [ ] Inner thread pools pinned if you serve concurrently.
- [ ] Models provisioned via `file://` / baked in for offline runs.
- [ ] `OpenCv.load()` warmed at startup; readiness gated on `isLoaded`.
- [ ] Every request scopes its images (`reading`/`use`/`using`).
- [ ] Codec and read failures handled as `Either`, with fallbacks.
- [ ] RSS monitored as the leak signal.

## Next

- [The native cache](/native-cache) — cache and model provisioning in depth.
- [Performance](/performance) — throughput and memory measurement.
- [Concurrency](/concurrency) — serving requests across threads safely.
