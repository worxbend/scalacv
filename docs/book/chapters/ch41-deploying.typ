#import "../lib/book.typ": *

#chapter("Deploying to Production", subtitle: [Getting it onto a server without a 400 MB surprise.])

The service worked on your laptop. It reads an upload, detects faces, draws boxes, returns a PNG,
and it does all of that fast enough that nobody thought to ask how fast. Then you containerise it,
and three numbers you had never considered become the only numbers anyone wants to discuss.

The first is the image size. Add OpenCV the convenient way and one dependency line puts about 408 MB
of jars into the image, most of it native code for operating systems you will never deploy to. Your
registry bill notices. So does every autoscaling event, because the node has to pull those bytes
before the pod can start.

The second is cold start. The first request after a deployment is slower than every request that
follows it by orders of magnitude, and nothing in your code changed between the two. What happened
is that the first `OpenCv.load()` in a fresh container unpacked about 196 MB of shared libraries out
of the jars and onto disk, and the request that triggered it paid for all of it --- while your load
balancer, which had already been told the pod was ready, kept sending traffic into the wait.

The third is the one that wakes people up. The pod is configured with `-Xmx512m` and a 1 GB memory
limit. The heap graph is flat. The pod restarts anyway, with exit code 137 and no stack trace, no
exception, no log line. Chapter 1 explained why: a `Mat` is about forty bytes on the heap and
megabytes off it, so the number your heap graph draws and the number the kernel enforces are
measuring different things. In a container that divergence stops being an interesting
fact about the JVM and becomes an outage.

None of the three is a defect. All three are the direct, predictable consequence of shipping native
code, and each has a fix that costs one line in a build file, one layer in a Dockerfile, or one field
in a pod spec. This chapter is those lines.

#sect("Package for one platform, not for all of them")

The natives ship in per-platform classifier jars, and Chapter 2 explained why scalacv cannot pick one
for you: a build tool has no way to put a `<classifier>` into a published POM, so the coordinate that
would carry the natives is the one coordinate scalacv is unable to publish. That left you with a
choice, and for local development the lazy answer is fine. For a production build it is not.

#example("The convenient dependency, and what it costs in a registry.")[
```scala
// Works on every machine you will ever own. Ships about 408 MB to every machine
// you will ever run.
mvn"org.bytedeco:opencv-platform:4.13.0-1.5.13"
```
]

`opencv-platform` bundles every platform bytedeco builds for --- Linux x86-64 and ARM64, macOS Intel
and Apple Silicon, Windows x86-64, and several you have never deployed to --- with the matching
OpenBLAS jar for each. It is about 408 MB, against roughly 51 MB for the `linux-x86_64` pair a Linux
container actually uses. The other 350-odd megabytes are dead weight in every layer, every pull, and
every registry garbage-collection pass you will ever run.

#example("The production dependency: one platform, pinned.")[
```scala
def mvnDeps = Seq(
  mvn"com.worxbend::scalacv:0.4.1",
  mvn"org.bytedeco:opencv:4.13.0-1.5.13;classifier=linux-x86_64",
  mvn"org.bytedeco:openblas:0.3.31-1.5.13;classifier=linux-x86_64"
)
```
]

Both lines, always. `libopencv_core` links `libopenblas`, and the OpenBLAS payload ships only in
OpenBLAS's own classifier jar --- omit it and `OpenCv.load()` fails with a `CvError.NativesMissing`
carrying both dependency lines, the classifier filled in from `Loader.getPlatform` and the versions
read out of `Build`, which the build generates from the same constants it resolves the jars with. The
message cannot tell you to add a release that is no longer the one the library was compiled against.

#figure-table("What each packaging choice actually puts in the image.")[
#tbl(
  columns: (auto, auto, 1fr),
  [*Choice*], [*Jar bytes*], [*When it is the right answer*],
  [`opencv` + `openblas`, one classifier], [36--80 MB\ (`linux-x86_64`: \~31 + \~20 MB)],
  [every production deployment, and CI],
  [`opencv-platform`], [\~408 MB],
  [a developer laptop, a sample repository, a workshop --- convenience only],
)
]

If you build multi-arch images, build one artifact per architecture with its own matching classifier
and let the manifest list choose between them. Do not build one fat artifact carrying both: you would
be paying `opencv-platform`'s tax by hand, with extra steps. And note that bytedeco publishes no
OpenCV natives at all for `windows-arm64` --- scalacv's own build fails fast and says so rather than
producing an artifact that cannot load.

#sect("The cache that is not in your image")

Adding the classifier jars gets the natives onto the classpath, not onto disk as loadable libraries.
They are payloads inside the jars. The first `OpenCv.load()` in a fresh environment extracts the
platform payload out of them and into a cache directory --- on Linux, about 196 MB under `~/.javacpp`
--- and loads the libraries from there by absolute path. Every later run reuses the extracted copy.

Two properties make that manageable rather than merely expensive: it is *content-addressed*, so
re-running never re-extracts, and two processes can share one directory safely. Together they mean
the unpack can happen anywhere, at any time before the first request --- including in a build layer
that has no relationship to the container that eventually uses it.

The directory is relocatable, which matters in a container because the default is a home directory
that a non-root user may not own and a read-only root filesystem will refuse outright.

#example("Two ways to say the same thing; the second is the one containers usually need.")[
```bash
java -Dorg.bytedeco.javacpp.cachedir=/opt/javacpp -jar app.jar

# When the entrypoint is not yours to edit, the JVM reads this instead:
export JAVA_TOOL_OPTIONS="-Dorg.bytedeco.javacpp.cachedir=/opt/javacpp"
```
]

That property name is exact: `org.bytedeco.javacpp.cachedir`, all lower case, no camel hump. It is
javacpp's, not scalacv's, so nothing in this library validates it --- a typo does not fail, it
silently leaves the cache in the default location, and you find out when a read-only root filesystem
rejects the write at boot.

#figure-table("Where to put the extracted natives, and what each choice buys.")[
#tbl(
  columns: (auto, 1fr, 1fr),
  [*Strategy*], [*How*], [*Trade*],
  [Bake into the image],
  [run a warm-up main in a build layer, `COPY` the result into the runtime stage],
  [image grows by \~196 MB; cold start is instant and self-contained --- the right default for
   autoscaling],
  [Mount a shared volume],
  [point `cachedir` at a mount; first pod to boot populates it],
  [thin image; cold start waits on the volume being warm, and the volume is now a dependency that can
   be empty],
  [Leave it in the container's home],
  [do nothing],
  [no configuration; every cold start pays the 196 MB unpack, and it breaks under
   `readOnlyRootFilesystem`],
)
]

The third row is not a strategy, it is what happens when nobody chooses. It is also the source of the
slow first request from the opening of this chapter.

#sect("A Dockerfile, line by line")

Here is the whole thing, and then the reasoning for each part of it. It is multi-stage, it warms the
cache in a layer nobody serves traffic from, it runs as a non-root user, and it survives a read-only
root filesystem.

#example("A production image: build, warm, run.")[
```dockerfile
# ---- 1. build the fat jar ------------------------------------------------
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY . .
RUN ./mill app.assembly && cp out/app/assembly.dest/out.jar /src/app.jar

# ---- 2. warm the javacpp cache in a throwaway stage ----------------------
FROM eclipse-temurin:17-jre AS warm
COPY --from=build /src/app.jar /app/app.jar
RUN java -Dorg.bytedeco.javacpp.cachedir=/opt/javacpp \
         -cp /app/app.jar com.example.Warmup

# ---- 3. the runtime image ------------------------------------------------
FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10001 --create-home --home-dir /home/app app
COPY --from=build /src/app.jar /app/app.jar
COPY --from=warm --chown=10001:10001 /opt/javacpp /opt/javacpp
ENV OPENBLAS_NUM_THREADS=1 \
    OMP_NUM_THREADS=1 \
    JAVA_TOOL_OPTIONS="-Dorg.bytedeco.javacpp.cachedir=/opt/javacpp \
      -Dorg.bytedeco.javacpp.maxPhysicalBytes=1600M \
      -XX:MaxRAMPercentage=30 \
      -Djava.io.tmpdir=/tmp"
USER 10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```
]

*The build stage carries a JDK; the runtime stages carry a JRE.* Nothing at run time compiles Scala,
and the JRE base image is smaller by a margin worth having --- one of the few size wins on this page
that costs nothing at all.

*Stage 2 exists only to run one main.* `com.example.Warmup` is any class whose `main` calls
`OpenCv.load()` --- the whole point is the side effect on `/opt/javacpp`. Giving it its own stage
keeps the runtime image free of the build's leftovers; `COPY --from=warm` brings across only the
extracted libraries.

*The warm-up must use the same `cachedir` the runtime will.* The extraction is content-addressed
under whatever root it is given; warm `/opt/javacpp` and then run with the property unset and you
have baked 196 MB into the image and will unpack it again anyway, into the home directory, on the
first request.

*`--chown` on the `COPY`.* The runtime user is `10001`, and javacpp will at least stat and open those
files. Copying them as root and then dropping privileges works for reading, but a cache directory the
process cannot write is one it cannot repair if the copy is ever incomplete. Own it.

*`-Djava.io.tmpdir=/tmp` with a writable `/tmp`.* Under `readOnlyRootFilesystem` you must mount an
`emptyDir` (or `--tmpfs /tmp` under plain Docker) for the JVM's own temporary and perf files. Note
where the *other* writes go: `Models.fetch` does not use `java.io.tmpdir` at all --- it creates its
temp file beside the target and moves it into place after verifying, so it is the model directory
that has to be writable, not `/tmp`. The javacpp cache is fine read-only after the warm-up: it must
be writable on first use, and the first use already happened in stage 2.

*The thread-pool environment variables.* Chapter 36 worked through this: OpenCV and OpenBLAS each
maintain an internal pool, and in a service already handling several requests at once those inner
pools oversubscribe the cores and cost you latency rather than buying throughput. Pinning them to one
thread each and letting your request executor own the parallelism is the right default *for a
concurrent server*. For a batch job running one large pipeline at a time, do the opposite and leave
them on.

#sidebar("Alpine, and the honest answer")[
The instinct that produced the multi-stage build --- make the image smaller --- points next at
Alpine, and here it stops working.

The Linux payload in the bytedeco classifier jars is compiled against glibc. Alpine's libc is musl,
which is not ABI-compatible with it, and javacpp's platform detection does not distinguish the two:
`Loader.getPlatform` reports `linux-x86_64` on Alpine exactly as it does on Debian, so scalacv
extracts the glibc payload and hands it to a linker that cannot satisfy it. The `UnsatisfiedLinkError`
becomes a `CvError.NativesMissing` telling you to add dependencies you demonstrably already have ---
misleading, but honestly so: "the jar is missing" and "the jar is here and unusable" look identical
from inside the loader.

There are compatibility shims that make glibc binaries run under musl. Installing one costs most of
the size you came to Alpine for and buys a configuration nobody upstream tests. The unglamorous
answer is the correct one: use a glibc base --- `eclipse-temurin:17-jre`, or a `debian:*-slim` with a
JRE --- and spend the size budget on the 196 MB cache and the single classifier pair instead. Both
are real wins, and neither risks a link failure at boot.
]

#sect("Budgeting memory when the heap is not the footprint")

A container memory limit is enforced against the cgroup's total resident set. It counts the heap, and
it counts every byte of pixel data OpenCV allocated through its own `cv::fastMalloc`, and it has no
interest in which is which. `-Xmx` bounds one of those terms. Sizing a pod from `-Xmx` alone is how
you get the flat heap graph and the restart.

The failure mode is worth recognising by sight, because it looks like nothing. There is no
`OutOfMemoryError`, because the JVM never ran out of heap, and no exception in your logs, because the
process did not fail --- it was killed, by the kernel, between two instructions. The pod status says
`OOMKilled`, the exit code is 137, and the heap dashboard is a flat line right up to the restart.
Chapter 1 has the underlying measurement:
2000 unreleased 1000×1000 three-channel `Mat`s reached 5,865 MB of RSS where the released version
stayed at 144 MB, and the heap was never under pressure in either case.

#figure-table("A container memory budget. Every term is measured, not guessed.")[
#tbl(
  columns: (auto, 1fr, auto),
  [*Term*], [*Where the number comes from*], [*Example*],
  [JVM heap], [`-Xmx`, or `-XX:MaxRAMPercentage` of the limit], [600 MB],
  [Native pixels at peak],
  [bytes per in-flight pipeline × maximum concurrency. A 1920×1080 BGR frame is 6.2 MB; a pipeline
   holding six of them at once is 37 MB],
  [8 × 37 = 296 MB],
  [Loaded native code and allocator arenas],
  [process RSS at idle, after `OpenCv.load()`, minus the heap],
  [measure it],
  [JVM overhead],
  [metaspace, code cache, thread stacks, GC structures --- `-XX:NativeMemoryTracking=summary`],
  [\~250 MB],
  [Headroom], [so the ceiling below fires before the kernel does], [20%],
)
]

The third row is the one people want a constant for, and there is not an honest one to give. The
196 MB in the cache is what the extraction writes to *disk*; what counts against RSS is the portion
of those libraries the process actually maps in, which depends on which OpenCV modules your pipeline
touches. Read it once from your own image, at idle, immediately after `OpenCv.load()` returns ---
Chapter 38 showed the cheap way, the second field of `/proc/self/statm` multiplied by the page size,
which is the same source scalacv's own leak harness uses.

With a total, set the container limit above it and set the JVM's own physical ceiling *below* it, so
that a leak produces a diagnostic instead of a kill.

#example("The two ceilings, and which counter each one watches.")[
```bash
java -Xmx600m -Dorg.bytedeco.javacpp.maxPhysicalBytes=1600M -jar app.jar
# ...in a pod whose memory limit is 2Gi.
```
]

#memory[
`-Dorg.bytedeco.javacpp.maxBytes` is the wrong knob and it looks like the right one. It is checked
against `Pointer.totalBytes()`, javacpp's own allocation counter, which is blind to the
`org.opencv.core.Mat` buffers scalacv actually manages --- a `Mat` leak sails straight past it and the
process dies with the ceiling reporting healthy numbers. `maxPhysicalBytes` is RSS-based and does see
them. Set that one.

When it fires it throws `java.lang.OutOfMemoryError`, not a `CvError`, so no `Either` in this library
catches it and no `Cv.attempt` turns it into a value. Chapter 39 covers what that means for a request
handler. Treat it as a backstop that produces a good diagnostic at the moment of failure, not as a
substitute for releasing images.
]

#sect("Models: in the image, on a volume, or nowhere at all")

Chapter 23 covered `Models.fetch` and `ModelSpec` in full; what changes at deployment time is only
where the file comes from. Three options, and the first two are the same mechanism.

Baking the model in makes the container self-contained: `COPY models/ /opt/models/`, and the pod
needs no network to become ready. It costs image size --- SFace is about 37 MB, YuNet 232,589 bytes
--- and makes a model update an image rebuild, which for a pinned, checksummed artefact is arguably
the correct coupling. Mounting them instead keeps the image thin and lets several services share one
copy, at the cost of a volume that has to exist and be populated before the first pod schedules.

The third case is the one that decides your `ModelSpec`. In an air-gapped deployment there is no
outbound route at all, and a fetch that reaches for a URL does not fail fast --- it waits out a
15-second connect timeout per mirror, on the boot path, and up to sixty seconds more for a mirror
that connects and then stalls. `Models.fetch` handles `file://` by falling out of the HTTP client
onto a plain stream, so a model already on disk is another source rather than a special case, and
mirrors are tried strictly in order.

#example("A spec that works air-gapped and still works when the network is there.")[
```scala
val yunet = ModelSpec(
  fileName = "face_detection_yunet_2023mar.onnx",
  urls = Seq(
    "file:///opt/models/face_detection_yunet_2023mar.onnx",  // the baked copy first…
    "https://media.githubusercontent.com/media/opencv/opencv_zoo/main/" +
      "models/face_detection_yunet/face_detection_yunet_2023mar.onnx"  // …then the mirror
  ),
  sha256 = "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4",
  sizeBytes = Some(232589L)    // checked first: one stat, and a better message than a hash mismatch
)
```
]

The SHA-256 is verified on download *and* on every cache hit, which is what makes a shared model
volume safe to point several pods at: a truncated or half-written file is rejected and re-fetched
rather than loaded as a model. The pinned `sizeBytes` is checked before the digest, which is what
turns the classic deployment failure --- a proxy answering the model request with an HTML error page,
or a Git LFS host serving a 131-byte pointer file, both with HTTP 200 --- into "expected 232589 bytes,
got 131" instead of a checksum mismatch that reads like tampering.

Cascade XML needs none of this: it travels inside the bytedeco classifier jar you already depend on,
under `share/opencv4/haarcascades/`, and `Cascades.load` extracts the one you name on demand. The
exception is `windows-x86_64`, whose jar ships an empty `share/` directory --- there, and only there,
you provision the XML yourself and load it with `Cascades.loadFrom`. It is a property of the upstream
jar, not of your build, so no dependency you add will fix it.

#sect("Fail at boot, not on the first request")

`OpenCv.load()` is idempotent and thread-safe, so calling it costs nothing after the first time,
which makes it tempting to call it lazily at the point of first use. Do not. A native problem that
surfaces on the first request surfaces as a 500, after the load balancer has been told the pod is
healthy, during a rollout that is by then halfway done.

Call it at boot, before the server binds its port, and let a failure fail the process. The error you
get is designed for exactly this moment: `CvError.NativesMissing` carries a copy-pasteable pair of
dependency lines naming the platform the container is actually on, which is a far better thing to
find in a crash-loop log than an `UnsatisfiedLinkError` stack.

That error has a second form, and it only ever shows up in a deployment. If the natives are on the
classpath but another classloader in the same JVM has already loaded them, the message says so and
tells you to move scalacv to a classloader both sides share --- a servlet container's shared library
directory rather than each application's `WEB-INF/lib`. Loading a second copy is not an available
answer: two copies of `libopencv_java` each keep their own globals, and a `Mat` allocated by one is
meaningless to the other. One fat jar in one container never meets this; three war files in an
application server meet it on the second deployment.

Now the readiness endpoint. The version everybody writes first is this:

#example("A readiness probe that proves the JVM is running and nothing else.")[
```scala
// WRONG: true the instant the HTTP server binds, natives or no natives.
def ready: Boolean = true
```
]

It answers a question nobody asked. The process being up says nothing about whether the natives
extracted, the cache directory was writable, the model arrived, or a pixel can cross JNI at all. Make
the probe do the smallest real piece of work instead.

#example("A warm-up that runs once at boot, and a probe that exercises a real pixel.")[
```scala
import scalacv.*
import scala.util.Using

object Warmup:

  @volatile private var warm = false

  /** Called once, before the server binds. A failure here should kill the process. */
  def run(): Either[CvError, Unit] =
    OpenCv.load()                       // throws CvError.NativesMissing if the natives are absent
    probe().map(_ => warm = true)

  /** One pixel, all the way through: allocate, convert, encode, release. */
  def probe(): Either[CvError, Array[Byte]] =
    Cv.attempt("readiness") {
      Using.resource(Image.blank(1, 1, Scalar.White))(_.gray.bytes(".png"))
    }.flatMap(identity)

  /** What the HTTP handler answers with: the boot probe's verdict, not a fresh guess. */
  def ready: Boolean = OpenCv.isLoaded && warm
```
]

`Image.blank(1, 1, Scalar.White)` allocates a real three-channel `Mat`, `gray` runs a real `cvtColor`
across JNI, and `bytes(".png")` runs a real encoder and releases the image as its terminal step. Four
native round trips on a single pixel, and the `Using.resource` closes the source image even though
`gray` already consumed it --- `close` delegates to `Managed.release`, which is idempotent, so the
belt-and-braces version is safe rather than a double free. `Cv.attempt` turns anything OpenCV throws
into a `Left` naming the operation, so a probe that fails reports which stage failed instead of
unwinding a `CvException` through your HTTP handler.

If your service also runs a DNN, add one warm-up `Dnn.forward` on a dummy blob here too --- it
returns a `Managed[Mat]`, so release it. The first inference on a `Net` pays a one-time
layer-allocation cost that has no business landing on a user's request.

#tip[
Split the two probes. A Kubernetes `startupProbe` with a generous `failureThreshold` covers the
extraction and model-fetch window without forcing you to loosen the `readinessProbe` interval that
protects you for the rest of the pod's life. With a pre-warmed cache the startup window is short
enough that this is cheap insurance rather than a workaround.
]

#sect("Kubernetes: requests, limits, and the pool you did not configure")

Two fields on a pod spec deserve more thought than they usually get for this workload.

*Memory: set the request equal to the limit.* The scheduler places pods by request; the kernel kills
by limit. For a workload whose memory is mostly off-heap the gap between them is not a safety margin,
it is a lottery --- the pod runs fine on a quiet node and is killed on a busy one, with nothing in
your code or traffic to explain the difference. Equal request and limit gets the pod the `Guaranteed`
QoS class and makes the failure reproducible, which is the first requirement for fixing it.

*CPU: think hard before setting a limit at all.* A CPU limit is enforced by CFS quota, which works by
stopping every runnable thread in the cgroup until the next period. That interacts badly with a
library running its own thread pool: OpenCV sizes that pool from the CPU count it observes, which is
the machine's, not your quota. Give a pod a 500-millicore limit on a 32-core node and one bilateral
filter can fan out to dozens of threads allowed 50 ms of CPU per 100 ms period between them --- they
run, get throttled as a group, and the operation's tail latency goes up while the CPU *usage* graph
sits reassuringly under the limit.

#example("Pinning the pools so the quota and the parallelism agree.")[
```yaml
resources:
  requests: { memory: 2Gi, cpu: "1" }
  limits:   { memory: 2Gi }          # no CPU limit; the request reserves the share
env:
  - { name: OPENBLAS_NUM_THREADS, value: "1" }
  - { name: OMP_NUM_THREADS,      value: "1" }
securityContext:
  runAsNonRoot: true
  runAsUser: 10001
  readOnlyRootFilesystem: true
```
]

From code the equivalent lever is `org.opencv.core.Core.setNumThreads`, called before you spread work
across your own executor. Which way to set it is a measurement, not a rule: Chapter 35's
`ConfigProbeBench` steps a heavy operation through 1, 2, 4 and your CPU count precisely because the
answer is a property of your hardware and your concurrency, and publishing one machine's result would
be actively misleading.

#sect("Rolling upgrades, and models that do not collide")

A rolling upgrade runs two versions of your service at once, briefly, on the same nodes and against
the same volumes. Three things about this deployment survive that, and it is worth knowing why.

The javacpp cache survives because extraction is content-addressed and concurrent readers are safe.
Two images built months apart, sharing one mounted cache directory, each find or write their own
payload without disturbing the other. If the natives version changed between them, the new one
extracts alongside the old rather than over it.

Models survive because a `ModelSpec` pins a *file name* as well as a checksum, and the names carry
their provenance: `face_detection_yunet_2023mar.onnx` is a different file from a 2024 revision, not a
newer version of the same one. Upgrading a model is therefore adding a file, never overwriting one,
and a shared model volume can hold both while the two versions of the service coexist. Resist the
temptation to normalise the name to `yunet.onnx` --- the moment you do, two versions of the service
point one path at two different files, each one's cache check fails against the other's pinned hash,
and for the length of the rollout the two pods re-download over each other. `Models.fetch` at least
makes that loud rather than corrupting: it downloads to a temp file beside the target and moves it
into place only after the size and digest verify, so no reader ever sees a truncated model.

The old pods survive because your readiness probe does real work. A new image with a broken classifier
line, a missing model, or a cache directory it cannot write fails its probe, the rollout stalls with
the old replicas still serving, and you get a `CrashLoopBackOff` naming the actual problem instead of
a 50% error rate that takes ten minutes to attribute.

#figure-table("The deployment checklist, and where each item was earned.")[
#tbl(
  columns: (1fr, auto),
  [*Check*], [*Chapter*],
  [One classifier pair, not `opencv-platform`], [2, 41],
  [`cachedir` relocated, and warmed in a build layer], [41],
  [`maxPhysicalBytes` set below the container memory limit], [35, 39, 41],
  [Inner thread pools pinned when the server is the one running concurrently], [36],
  [Models provisioned via `file://` or baked in; checksums pinned], [23],
  [`OpenCv.load()` at boot; readiness gated on a real pixel operation], [41],
  [Every request scopes its images (`Image.reading`, `Managed.scope`, `Camera.using`)], [5],
  [Decode and codec failures handled as `Either`, with fallbacks], [6, 20],
  [Process RSS on a dashboard, as the leak alarm], [38],
)
]

#sect("When it still goes wrong")

Everything above is the configuration that prevents the failures this library's shape makes likely.
It does not prevent the failures that come from the machine, the base image, the classloader or the
codec build --- an `UnsatisfiedLinkError` that names no library, a `Left` from a recorder complaining
about a codec that exists on your laptop and not on the node, an OpenCV warning printed to stderr for
a call that returned successfully. Chapter 42, #emph[Troubleshooting], is the field guide to reading those
messages: what each one actually means, which of them are benign, and the shortest path from the text
in your log to the line in your build file that caused it.
