#import "../lib/book.typ": *

#chapter(
  "Lifetimes: Managed, Releasable, and Scope",
  subtitle: [How a library frees native memory the JVM cannot see, and refuses to free it twice.],
)

A service decodes frames from a camera, runs a detector over each one, writes a JPEG, and repeats.
It runs for six minutes and gets OOM-killed. You take a heap dump and find nothing: the heap is flat
at 300 MB, the collector has run happily throughout, and the largest retained set is a logging
buffer. Meanwhile the resident set size of the process, which is the number the kernel actually
killed you over, went to six gigabytes.

Nothing is wrong with your Java. The problem is that an OpenCV `Mat` holds its pixels off-heap and
keeps about forty bytes of Java object on-heap to point at them. A 1000 × 1000 three-channel image is
three megabytes of `cv::fastMalloc` behind forty bytes the collector can see. The collector runs when
the heap comes under pressure, and heap pressure has nothing to do with native pressure --- so the
loop exhausts the machine while the JVM stays relaxed. Measured on this project's own test machine:
2000 × `Mat(1000, 1000, CV_8UC3)`, references dropped, no explicit `System.gc()`, and the process
finishes at #strong[5 865 MB] of RSS. The identical loop calling `release()` finishes at
#strong[144 MB]. That is 41×, and it reproduces on JDK 21 and JDK 25 alike.

The mechanism that is supposed to save you here is `finalize()`, and the generated OpenCV bindings do
carry one. It is not disabled --- that is a common myth --- but it only runs when the collector runs,
and the loop above is exactly the case where it will not. Waiting for a finalizer to
reclaim native memory is waiting for an event whose trigger is uncorrelated with the thing you need
reclaimed. It is also deprecated for removal.

There is a second problem behind the first, and it is worse. Of the 188 `org.opencv.*` types that own
native memory, exactly #strong[three] --- `Mat`, `VideoCapture` and `VideoWriter` --- expose a public
`release()`. `CascadeClassifier`, `Net`, `QRCodeDetector`, `ArucoDetector`, `KalmanFilter` and 180
others do not. There is no method to call. So a library that wants to free them has to answer two
separate questions: #emph[when] does a native object get freed, and #emph[how]. `Managed[A]` answers
the first. `Releasable[A]` answers the second. `Managed.scope` answers the case where one operation
needs six live handles at once and none of them may outlive it. This chapter is those three types,
and it is the chapter the rest of the book leans on.

#sect("The failure this replaces")

Start with the mistake, because the shape of the mistake explains every design decision that follows.

Written the way OpenCV is normally written from Java, a frame loop looks like this, and it leaks:

#example("The version that gets your container OOM-killed.")[
```scala
// WRONG — nothing here is ever freed, and nothing will free it for you.
while running do
  val frame = Mat()
  capture.read(frame)
  val gray = Mat()
  Imgproc.cvtColor(frame, gray, Imgproc.COLOR_BGR2GRAY)
  val faces = MatOfRect()
  detector.detectMultiScale(gray, faces)
```
]

That is a leak, which is at least a failure you can watch happen. The interesting thing is what
happens when you notice and start freeing by hand: the failure changes character entirely. Calling a
method on an OpenCV object whose native memory you have already freed segfaults from native code.
There is no stack trace, no exception to catch, no test report --- the JVM writes an `hs_err` file
and the process is gone. Freeing the same pointer twice is undefined behaviour that merely
#emph[often] happens to survive, which is worse, because it means your test suite passes and
production does not. Both of these were reproduced on this project's machine before the guards
existed; the double free was found in an unrelated test suite, where its only symptom was a worker
process that died without writing a result.

That is the standard against which `Managed` should be judged. It is not competing with a neat
exception; it is competing with a signal.

#memory[
  A native handle has two ways to hurt you and they pull in opposite directions. Free it too late ---
  or never --- and you leak megabytes per iteration behind forty visible bytes. Free it twice, or
  touch it after freeing, and you corrupt the heap or crash the process with no Java-side evidence at
  all. Any lifetime scheme has to close both, and closing one carelessly opens the other.
]

#sect("`Managed[A]`")

`Managed[A]` owns one native object and releases it exactly once. It is a `final class` implementing
`AutoCloseable`, and its entire state is an `AtomicReference[A | Null]` holding the object, the
`Releasable[A]` it was constructed with, a `Class[?]` captured at construction, and a nullable
`Throwable` used only for diagnostics. The constructor's `initial` parameter is deliberately not
among them; the subsection below says why.

Two guarantees fall out of that reference, and both exist because getting them wrong is a JVM crash
rather than an exception. First, release is a compare-and-set: `getAndSet(null)` admits exactly one
caller, so a second `release()` is a no-op instead of a double free, and sixty-four threads racing to
release the same handle produce exactly one free. Second, access after release throws
`IllegalStateException` on the Scala side, before anything crosses JNI.

#example("The whole contract, in five lines.")[
```scala
val m = Managed(Mat(64, 64, CvType.CV_8UC3))
m.get.rows              // 64 — the underlying object, borrowed
m.release()             // frees the pixel buffer
m.release()             // no-op; the CAS already took the reference
m.get                   // throws IllegalStateException, not SIGSEGV
```
]

The API is small enough to put in one table.

#figure-table("The `Managed[A]` surface.")[
#tbl(
  columns: (auto, 1fr),
  [*Member*], [*What it does*],
  [`get: A`], [The underlying object, borrowed. Throws `IllegalStateException` if the handle has been released or consumed --- deliberately eager, because the alternative is a segfault.],
  [`use[B](f: A => B): B`], [Runs `f` on the object and releases afterwards, in a `finally`, so the release happens on an exception too. Prefer this over holding a handle.],
  [`release(): Unit`], [Frees the native memory through the `Releasable` supplied at construction. Idempotent.],
  [`close(): Unit`], [`release()`, under the name `AutoCloseable` wants. This is what makes the whole of `scala.util.Using` accept a `Managed`.],
  [`isReleased: Boolean`], [Whether the reference has been taken --- by a release or by a transfer.],
  [`toString: String`], [`Managed(<released>)` or `Managed(…)`, from a single snapshot of the reference so a concurrent release cannot make it print `Managed(null)`. Diagnostics only.],
  [`Managed(a)`], [Wraps `a`, requiring a `Releasable[A]` in scope.],
  [`Managed.use(a)(f)`], [Wrap and scope in one call --- the scaladoc's "form to reach for by default". `a` is by-name, so it is not evaluated until the wrap happens.],
  [`Managed.scope(body)`], [Owns several objects for the duration of `body`. Its own section below.],
)
]

Because `Managed` is `AutoCloseable`, `scala.util.Using` and `Using.Manager` already accept it
through `Using.Releasable.AutoCloseableIsReleasable`. The library deliberately does not define its
own `given` for that, which sounds like a missed opportunity and is not: defining one made every
`use(Managed(...))` call ambiguous between the two instances.

#subsect("Why a `Class`, and not the object")

One field in `Managed` looks redundant and is not. The class captures `initial.getClass` into a
private `ofType` at construction so that the error message can name the type --- "this Mat has
already been released" --- without the `Managed` holding a reference to the object itself.

Keeping the constructor parameter alive would defeat the point of nulling the reference in
`release()`: the released object would stay strongly reachable for as long as the handle lived, and
the small `cv::Mat` header it owns --- the part the collector reclaims rather than `release()` ---
could never be collected. A `Class` costs one field and its classloader already keeps it alive.

#subsect("`take`, and why it is not yours to call")

There is one more method on `Managed`, and it is `private[scalacv]`:

```scala
private[scalacv] def take(): A
```

`take()` hands the object out and leaves the handle spent #emph[without freeing it]. Ownership
transfers to the caller. It exists for the high-level `Image`, which threads one live `Mat` through a
chain of handles: an in-place step takes the `Mat` out of the current handle, mutates it, and rewraps
it in a new one. The predecessor handle is now spent --- `get` throws --- yet nothing has been freed
and nothing has been copied. `Image.paint` is the internal that does this; the drawing verbs built on
it, and `Image.managed`, are what you see of it from outside.

`take` is deliberately not `release()`, which would free the very object being transferred, and it is
deliberately not public. A public `take()` would be a hole straight through the ownership model: a
caller could extract a live `Mat` from a handle, drop the handle, and have no owner left to free it.
If you want a second independent object, `Image.copy` clones the pixel buffer and gives you one.

A pure transform such as `img.gray` spends its receiver differently: `Image.transform` runs the
mid-level op on the borrowed `Mat`, wraps the fresh `Mat` it produced, and releases the source in a
`finally`. Either way the receiver ends up spent, which is the move semantics `Image` presents; only
the in-place path transfers a live pointer, and only that path needs `take`.

#sect("Several handles at once: `Managed.scope`")

`Managed.use` scopes exactly one object. Real OpenCV calls frequently want more than one. `solvePnP`
takes object points, image points, a camera matrix, distortion coefficients and two output vectors
--- six native objects, all of which someone has to free, none of which the caller wants back.

The obvious way to write that in Scala is the one with a hole in it:

#example("The `try`/`finally` shape, which is not as safe as it looks.")[
```scala
// WRONG — every allocation before the `try` is unguarded.
val objectPoints = MatOfPoint3f(model*)
val imagePoints  = MatOfPoint2f(points*)
val camera       = buildCameraMatrix()   // if this throws, the two above are stranded
val rvec         = Mat()
val tvec         = Mat()
try
  Calib3d.solvePnP(objectPoints, imagePoints, camera, dist, rvec, tvec)
finally
  objectPoints.release(); imagePoints.release(); camera.release()
```
]

The `finally` only protects what was allocated before control reached the `try`. A constructor that
throws part-way --- a degenerate landmark set, a bad calibration file --- strands everything already
built. Nesting six `Managed.use` blocks closes that hole and buries the two interesting lines under
six levels of indentation.

`Managed.scope` is both, correctly. It hands your body a `Scope`, conventionally named `own`, which
registers an object and returns it, so a scoped acquisition reads as an ordinary binding.

#example("Head-pose estimation, as the library actually writes it.")[
```scala
Managed.scope: own =>
  val objectPoints = own(MatOfPoint3f(model*))
  val imagePoints  = own(MatOfPoint2f(face.landmarks.map(_.toCv)*))
  val camera       = own(intrinsics.cameraMatrix)
  val distortion   = own(intrinsics.distCoeffs)
  val rvec         = own(Mat())
  val tvec         = own(Mat())
  Cv.attempt("solvePnP") {
    val ok = Calib3d.solvePnP(
      objectPoints, imagePoints, camera, distortion, rvec, tvec,
      false, Calib3d.SOLVEPNP_EPNP
    )
    if !ok then None
    else
      val rotation = own(Mat())
      Calib3d.Rodrigues(rvec, rotation)
      val euler = Calib3d.RQDecomp3x3(rotation, own(Mat()), own(Mat()))
      Some(HeadPose(yaw = euler(1), pitch = euler(0), roll = euler(2)))
  }.getOrElse(None)
```
]

That is `HeadPose.estimate` from `vision`, with the `org.opencv.calib3d` prefixes folded into
`Calib3d.` for width. Copy the shape, not the snippet: `Intrinsics.cameraMatrix`, `distCoeffs` and
the `_.toCv` on a landmark are all `private[scalacv]`. Everything structural is public ---
`Managed.scope`, `own`, `Cv.attempt`.

The important word is #emph[registered]. Each object joins the scope the moment it is created, not at
the end of a declaration block, so a throw anywhere --- in a later constructor, in the native call, in
the decode afterwards --- releases everything acquired so far, in reverse acquisition order. Note the
three `own(Mat())` calls that appear part-way down, inside the `else` branch: they are registered
no less reliably than the six at the top, because registration is a function call rather than a
syntactic position. Underneath, `scope` is `Using.Manager`, so the exception a failing body throws
propagates unchanged and a failure raised by a #emph[release] is attached to it as a suppressed
exception rather than replacing it. The original cause is never lost.

`own` hands back the object itself rather than a `Managed`, which is a deliberate asymmetry with the
rest of the library. A scoped handle has no second owner to be protected from: the scope releases it
exactly once, at the end, so there is nothing for a wrapper to guard against and every `.get` it
would force you to write would be noise.

#subsect("`adopt`, for values that arrive already wrapped")

Every mid-level `Ops` transform hands back a `Managed[Mat]`. Passing one to `own` would wrap a
handle in a second handle. `adopt` takes over the existing one instead, so the `Mat` is still
released exactly once:

#example("Mixing raw constructions and mid-level results in one scope.")[
```scala
val edgePixels = Managed.scope: own =>
  val src   = own(Mat.zeros(64, 64, CvType.CV_8UC3))
  val gray  = own.adopt(src.cvtColor(ColorConversion.BgrToGray))
  val edges = own.adopt(gray.canny(80, 160))
  Core.countNonZero(edges)
```
]

`adopt` is a separate name rather than an overload of `apply` for a reason worth knowing: `Managed[A]`
is itself an `A` as far as overload resolution is concerned, so the compiler would have to choose
between the two candidates before searching for the `Releasable` that distinguishes them. A separate
name sidesteps a resolution puzzle that would have had a silently wrong answer.

#warning[
  Nothing acquired inside a `scope` may escape it. The value the body returns must be plain data --- a
  number, a `Seq[Double]`, a case class --- or an object owned somewhere else. Returning a scoped
  `Mat` gives you a handle to freed memory, and the next method call on it is a segfault. This is the
  same rule `use` already carries, applied to a group; in the listing above, `countNonZero` returns an
  `Int`, and in the head-pose listing the body returns a `HeadPose` of three doubles.
]

#sect("`Releasable[A]`: how a thing gets freed")

`Managed` decides #emph[when]. `Releasable` decides #emph[how], and it is a one-method typeclass:

```scala
trait Releasable[-A]:
  def release(a: A): Unit
```

It is contravariant in `A`, so an instance written for a supertype serves every subtype. Which
instance applies to a given OpenCV type is not a style choice --- it is dictated by what the
generated Java binding exposes --- and there are three shapes.

#figure-table("The three ways a `Releasable` gets built.")[
#tbl(
  columns: (auto, auto, 1fr),
  [*Shape*], [*Applies to*], [*How it frees*],
  [The three built-in `given`s], [`Mat`, `VideoCapture`, `VideoWriter`], [Calls the type's own public `release()`. Nothing reflective, nothing to configure.],
  [`Releasable.handle(getNativeAddr)`], [A binding whose address is not a `long` field called `nativeObj`], [Reads the address through the function you supply, then goes through the shared free sequence --- which still disarms a `nativeObj` field and calls the class's own private `delete(long)`.],
  [`Releasable.nativeHandle`], [The other 185 `org.opencv.*` types], [Reads `nativeObj` reflectively, then goes through the same sequence. No accessor to supply, so none to get wrong.],
)
]

The three built-in instances need no import ceremony --- they live in the `Releasable` companion, so
they are found by the implicit search automatically. `Managed(Mat(...))` compiles with nothing but
`import scalacv.*`.

For the other 185, you declare an instance where you need one. The library does this at fourteen
sites across `vision` and `graphs`, and more again in its tests and examples; they all look alike:

```scala
private given Releasable[KalmanFilter] = Releasable.nativeHandle
```

Some are public, so you can borrow them: `Cascades` exposes `given Releasable[CascadeClassifier]`,
`Dnn` exposes `given Releasable[Net]`, `FaceDetect` exposes `given Releasable[FaceDetectorYN]`. They
live in those objects rather than in the `Releasable` companion, so `import scalacv.*` does not find
them --- name them, with `import Cascades.given` or `import Dnn.given`.

#subsect("What `nativeHandle` actually does")

`nativeHandle` takes no accessor. It reads the address out of the binding's own `nativeObj` field
reflectively, walking up the class hierarchy for a field of that name and type `long`, caching the
opened `java.lang.reflect.Field` per class in a `ConcurrentHashMap`.

Reading a field rather than calling `getNativeObjAddr` sounds like the weaker choice and is not. The
disarm step described in the next section has to reflect on that exact field on this exact code path
anyway, so the `Field` is already open and cached by the time the address is wanted --- reading it
costs nothing beyond a lookup that was going to happen regardless. It also fails in the same place,
with the same message, as the disarm would, rather than succeeding at reading an address the disarm
then refuses to neutralise.

The reason it exists at all is duplication. Every one of the 185 types spells its accessor
identically, so the `handle` call naming it was written out the same way at every site --- and every
one of those was a chance to hand one type another type's accessor, which compiles cleanly and frees
the wrong pointer. `nativeHandle` cannot be given the wrong one. `handle` stays for a binding whose
address lives somewhere other than a `nativeObj` field; it lifts no other requirement, because the
free sequence it shares still disarms `nativeObj` and still calls the class's own `delete(long)`.

#sidebar("Why `Mat` is not freed the way everything else is")[
  It would be tidier if all 188 types went through one mechanism, and the library deliberately does
  not do that.

  `Mat` exposes no accessible `delete(long)` --- only its superclass's private `CleanableMat.n_delete`
  --- so the bridge the other 185 use is not available to it in the first place. But even if it were,
  it would not be worth taking. `Mat.release()` drops the reference to the pixel #emph[buffer] at
  once, and the buffer is the multi-megabyte allocation that actually costs you memory. What
  `release()` leaves behind is the `cv::Mat` #emph[header], roughly a hundred bytes, reclaimed by the
  inherited `CleanableMat.finalize()` whenever the collector next gets round to it.

  A hundred bytes on a collector's schedule is a real cost and a very small one. Paying reflection,
  a cached `MethodHandle` and a finalizer disarm to reclaim it a little sooner is not a trade worth
  making. The part that matters is already gone the instant `release()` returns.
]

#sect("The finalizer, and the double free it would cause")

Here is the part that makes freeing the 185 types genuinely difficult rather than merely fiddly.

Every one of those generated handle classes carries this, verbatim:

```java
protected void finalize() throws Throwable { delete(this.nativeObj); }
```

It is unconditional. It does not check whether the pointer is still live, because from the binding's
point of view there was never a way for it not to be. So if the library reads the address, calls
`delete(addr)`, and then drops the Java object, `delete` runs #emph[twice] on the same address: once
from the library, and once from the finalizer thread whenever the collector next runs. That is heap
corruption. It does not fail at the second delete --- it surfaces as a `double free or corruption`
message or a SIGSEGV somewhere else entirely, at an unpredictable later moment, which is exactly how
it was originally found.

`Managed`'s compare-and-set is no defence here. It makes the library's #emph[own] release
idempotent; it knows nothing about a finalizer running on another thread against an address it has
never seen.

The fix is to zero the field first. `NativeFinalizer.disarm` writes `0L` into `nativeObj`, so the
finalizer's eventual `delete(this.nativeObj)` becomes `delete(0)`, which C++ defines as a no-op on a
null pointer. The order in the shared free sequence is therefore fixed, and commented as such in the
source:

#example("The release sequence both non-`Mat` shapes share.")[
```scala
private def free[A <: AnyRef](a: A, addr: Long): Unit =
  if addr != 0L then
    // Disarm BEFORE deleting, never after: between the two there is a window in which the
    // finalizer could run against a pointer we have already freed.
    NativeFinalizer.disarm(a)
    NativeDelete.of(a.getClass).invokeExact(addr): Unit
```
]

Read the guard first: an address of zero means the object was already freed, or never held one, so
the whole sequence is skipped. Then note the order. Reversing those two lines would leave a window
--- short, but real --- in which the collector could run the finalizer against a pointer already
handed to `delete`. The library's own test asserts the mechanism rather than inferring it from
survival: allocate a `CascadeClassifier`, check `getNativeObjAddr` is non-zero, release it through a
`Managed`, and assert `getNativeObjAddr` is now exactly zero.

#memory[
  This is why you must not free one of the 185 handle types by reaching for its private `delete` bridge
  yourself, even if you find it. Freeing without disarming is a double free with a delay fuse on it,
  and the delay is however long it takes the collector to notice a forty-byte object. Put the handle in
  a `Managed` with a `Releasable.nativeHandle` instance and let the ordering be someone else's problem.
]

#subsect("When reflection is refused")

Both reflective steps can fail, and neither degrades quietly. `delete(long)` is private API with no
compatibility promise, and `setAccessible` stops working the moment OpenCV's classes are loaded from
a named module rather than the classpath --- something a consumer controls through `--add-opens` and
the library cannot.

Every one of those failures throws `CvError.NativesMissing` rather than falling back to the
collector, on the grounds that an unbounded leak which looks like success is a worse outcome than a
loud failure. There are five distinct refusals, and the message tells them apart.

#figure-table("Every way the reflective release refuses, and what it says.")[
#tbl(
  columns: (auto, 1fr),
  [*What could not be done*], [*What the message reports*],
  [No `long nativeObj` anywhere in the class hierarchy], [The type has no field to disarm, so it cannot be freed safely. This is the one case no accessor argument fixes --- `handle` shares the same disarm.],
  [`setAccessible` on `nativeObj` refused], [The field cannot be made writable, with the computed `--add-opens` remedy. `nativeHandle` also fails here when it goes to read the address, because it reads through the same cached `Field`.],
  [Writing `0L` into `nativeObj` refused], [Refusing to free it, "because the binding's finalizer would then free it a second time".],
  [The class declares no `delete(long)`], [These bindings are a build the library does not know how to free; the message asks for the bytedeco version.],
  [`setAccessible` on `delete(long)` refused], [The bridge cannot be opened, with the same remedy and a line saying the library fails here rather than falling back to the collector, "because that fallback does not reclaim native memory in any useful timeframe".],
)
]

The second and third rows are the ones that matter most. If the address can be read but `nativeObj` cannot
be written, the library #emph[refuses to delete at all]: a leak is recoverable where a corrupted heap
is not.

The remedy is computed from the class's own module and package rather than guessed at:

```text
cannot make org.opencv.objdetect.CascadeClassifier.nativeObj writable
(InaccessibleObjectException).

scalacv must zero this field before freeing the object, because the binding's
finalizer calls delete(nativeObj) unconditionally and would otherwise free the
same pointer twice.

  --add-opens <module>/org.opencv.objdetect=ALL-UNNAMED

scalacv refuses to free the object rather than risk corrupting the heap.
```

`<module>` there stands for whatever `cls.getModule.getName` returns, because `addOpensRemedy`
computes the line at the moment it fails rather than hard-coding a guess. If the class is in the
unnamed module --- OpenCV on the classpath, which is the normal case --- there is no module to open
and therefore no flag that would help. The library says so in those words, and asks for a bug report,
instead of printing an `--add-opens` line with a `null` module name in it.

#sect("Finding the line that spent a handle")

The commonest lifetime error in practice is not a leak and not a double free. It is reuse: an `Image`
touched after a transform already consumed it. The `IllegalStateException` fires at the reuse, which
is almost never the line you need to look at.

#example("The error arrives one line too late to be useful.")[
```scala
val img  = Image.blank(16, 16)
val gray = img.gray     // this line spent `img`
gray.close()
img.width               // …and this line throws
```
]

The default message does what it can with that: it names the type, states the fix, and points at the
flag that would locate the cause.

```text
this Mat has already been released or consumed — using it now would crash the
JVM from native code. A high-level Image is spent by any transform (gray/blur/…)
or terminal (write/bytes/close); call `.copy` before the first use if you need it
twice. Run with -Dscalacv.trackOwnership=true to record where it was consumed.
```

It names `Mat`, not `Image`, and that is not a slip: an `Image` wraps a `Managed[Mat]`, and the
captured `Class` is the class of the thing actually owned. The rest of the sentence is written for
the case that produces it --- an `Image` used after a move --- which is why a `Managed[Mat]` talks
about transforms, terminals and `.copy`.

Start the JVM with `-Dscalacv.trackOwnership=true` and every handle that is spent --- by a `release()`
or by an internal `take()` --- records a `Throwable` at the moment it is spent. When a spent handle is
later touched, that `Throwable` is attached as the #emph[cause] of the `IllegalStateException`, so the
stack trace you get names the transform or terminal that actually consumed the handle rather than only
the line that tripped over the result. The trailing sentence about the flag disappears from the
message, because it is no longer needed.

#tip[
  The flag is read once at class load through `java.lang.Boolean.getBoolean("scalacv.trackOwnership")`,
  so it must be set on the command line --- setting the property from inside `main` is too late. Leave
  it off in production: it allocates a `Throwable` every time a handle is spent, which on a frame loop
  is every frame. The read that consumes it lives only on the already-failing path, so a program that
  never misuses a handle pays nothing for having the feature, only for having it switched on.
]

#sect("Which level to reach for")

Four tiers, and the right answer is nearly always the first one.

#figure-table("Choosing an ownership tool.")[
#tbl(
  columns: (auto, 1fr),
  [*Reach for*], [*When*],
  [`Image.reading(path) { … }`], [Almost always. The block's image is released when it returns --- on success, on failure, and on exception --- and every intermediate in a transform chain frees itself as the next stage consumes it. Keep borrows and lazy work inside the callback; explicit ownership escapes still need cleanup.],
  [`Managed.use(a) { … }` or `.use`], [One native object, one scope, and the result is not a native object. The mid-level `Ops` layer hands you `Managed[Mat]` values that fit this directly.],
  [`Managed.scope { own => … }`], [Two or more native objects live at the same time, and the answer is plain data. Anything that calls into `Calib3d`, or builds a detector plus its inputs plus its outputs.],
  [A bare `Managed` you hold], [A handle whose lifetime genuinely outlives any lexical scope --- a detector loaded once at startup and used for the life of the service. Close it in your shutdown path.],
)
]

In the current implementation, consuming transforms, copies and `.managed`/`pipe` keep the
callback scope responsible for their live successors, even after the original wrapper is spent.
Use `img.detach` to transfer one branch out explicitly, and close the returned image yourself;
other branches remain scoped. The custom release strategy moves with its owner. These fixes are
newer than the 0.4.1 source tag. Raw Mat allocations and arbitrary concurrent use are not covered.

The one thing to avoid is a scope whose result is the thing the scope owns. It is easy to write by
accident, because `use` returns whatever the body returns and the type checker is perfectly happy:

#example("A leak the compiler will not catch.")[
```scala
// WRONG — `use` frees the blur output, then returns the canny Mat, which now
// outlives its own Managed and has no owner left to free it.
src.gaussianBlur(Size(5, 5)).use(_.canny(50, 150))

// RIGHT — `pipe` feeds the intermediate forward and frees it, handing back an
// owned Managed. Reach for `use` only at the terminal stage that produces a
// non-Mat: a count, a Seq[Rect], some bytes.
src.gaussianBlur(Size(5, 5)).pipe(_.canny(50, 150))
```
]

#subsect("What getting it wrong looks like from outside")

You will not see a leak in a heap dump, and you will not see it in JavaCPP's own accounting either.
This project's memory audit established that `Pointer.totalBytes()` is #strong[blind] to these
allocations: a deliberate 1.4 GB `org.opencv.core.Mat` leak moved it by zero bytes, because those
buffers come from OpenCV's own JNI through `cv::fastMalloc` rather than through JavaCPP's tracked
allocators. The `-Dorg.bytedeco.javacpp.maxBytes` budget derived from `totalBytes()` cannot gate a
leak it cannot see.

The signal that does see them is process RSS. The library's own leak suite reads it from
`/proc/self/statm` on Linux, falling back to `Pointer.physicalBytes()` elsewhere, and runs each
workload 40 times to warm up and then 300 times measured, asserting that RSS grows by no more than
48 MB across the measured run. The tolerance is bounded rather than zero on purpose --- allocator
arenas and the JIT code cache mean RSS never returns exactly to baseline --- but a per-iteration leak
of even a modest `Mat` clears 48 MB long before iteration 300, while a correct workload stays flat.
That suite gets a JVM to itself, because RSS is process-global and any other suite running
concurrently would contaminate it.

The failure mode is dramatic when it is a handle type rather than a `Mat`. Four thousand leaked
`KalmanFilter`s measured #strong[54 GB]. The same four thousand, released, measured #strong[86 MB].

#tip[
  To catch a leak in your own workload before RSS catches it for you, cap JavaCPP's physical budget
  and run: `java -Dorg.bytedeco.javacpp.maxPhysicalBytes=512M -jar your-app.jar`. That ceiling is
  RSS-derived, so unlike `maxBytes` it does see these buffers. A leak then fails fast and loud instead
  of slowly eating the machine. Run the same workload under both a tight and a generous cap, so you
  can tell a genuine leak from a ceiling that is too small for your working set. Note what the cap
  throws when it fires: a `java.lang.OutOfMemoryError`, not a `CvError`, so no `Either` in this
  library catches it and no `Cv.attempt` turns it into a `Left`. In a container, set it comfortably
  below the container's own limit, so the JVM produces that error with a stack trace instead of the
  kernel producing an OOM kill with nothing.
]

#sect("Next")

Every guard in this chapter throws. `Managed.get` on a spent handle throws `IllegalStateException`;
`Releasable.nativeHandle` throws `CvError.NativesMissing` rather than leaking or corrupting the heap.
That is not the library's answer to every failure, and it is not meant to be --- a missing file, an
unreadable JPEG and a `cvtColor` given the wrong channel count are not bugs in your program, and they
do not deserve an exception. Chapter 6, #emph[The Error Model], draws that line: which failures come
back as `Either[CvError, A]` values you are expected to handle, which ones throw because they mean
your code is wrong, and why the library refuses to blur the two. You have already met one of its
cases in this chapter --- `CvError.NativesMissing`, the one the reflective release path raises when
the module system will not let it do its job.
