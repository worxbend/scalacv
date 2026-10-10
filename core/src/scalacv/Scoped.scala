package scalacv

/** The one open → use → close ceremony behind the `Either`-returning scoped entry points — [[Camera.using]],
  * [[Recorder.using]] and [[Image.reading]].
  *
  * The semantics are exactly what those sites used to spell out by hand: a failed open is passed straight
  * through (there is nothing to close); on a successful open the whole `use` body runs inside [[Cv.attempt]]
  * under `operation`, so a [[CvError.NativeCall]] thrown inside the block comes back as a `Left` rather than
  * escaping past the `Either`; and the resource is closed in a `finally`, so it is released on success, on a
  * `Left`, and on a propagating exception alike. Programmer errors (`IllegalArgumentException`,
  * use-after-close) still throw, per the policy in [[Cv]].
  */
private[scalacv] object Scoped:

  /** Runs `use` over a successfully opened resource and closes it afterwards, under the [[Cv.attempt]] guard
    * described above.
    */
  def using[A, R <: AutoCloseable](opened: Either[CvError, R], operation: String)(
      use: R => A
  ): Either[CvError, A] =
    opened.flatMap: resource =>
      Cv.attempt(operation)(scala.util.Using.resource(resource)(use))
