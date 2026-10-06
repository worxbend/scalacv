package scalacv.vision.internal

import java.io.File

import scalacv.{Cv, CvError, Releasable}

/** The one way a native model object is loaded from a file in this module.
  *
  * Every loader in `scalacv.vision` — [[scalacv.vision.Cascades]], [[scalacv.vision.Dnn]],
  * [[scalacv.vision.FaceDetect]], [[scalacv.vision.FaceRecognizer]] — used to hand-roll the same four-step
  * guard, and the copies had drifted: three of the four checked the result for `null`/`empty`, one did not.
  * That drift is the whole reason this exists. OpenCV's model factories fail in two silently different ways
  * depending on the class — `CascadeClassifier` constructs an *empty* object for a bad path and never says
  * so, while the factory methods of the 185 handle types return `null` — and a loader that misses its class's
  * failure mode hands the caller an object that detects nothing, forever, on every frame, with no error.
  * Converging here means the guard is written once, and a new loader cannot forget half of it.
  */
private[vision] object ModelLoader:

  /** Loads a native model handle from `path`, or explains why not.
    *
    * The spine, in order:
    *
    *   1. **The path is checked before OpenCV sees it.** Not redundant with the native check — it is the only
    *      way a missing file gets an error that names the file rather than quoting a C++ source location, and
    *      for `CascadeClassifier` it is the only check at all (a bad path constructs an empty classifier
    *      silently). The unreadable-but-present case is reported separately, because "no such file" and
    *      "permission denied" are fixed differently.
    *   1. **Creation and validation run inside [[Cv.attempt]]**, so a throwing importer or native validator
    *      is a `Left` rather than an unhandled `CvException`. A validation exception still releases the newly
    *      created handle; programmer errors propagate unchanged and cleanup failures are suppressed on them.
    *   1. **The result is vetted before it is handed out.** A `null` return — the quiet failure mode of the
    *      generated factory methods — or a handle `validate` rejects (e.g. `classifier.empty()`) is a `Left`,
    *      with the handle released first: the handle is real even when the model is not, and leaking it on
    *      the error path is exactly the kind of leak that goes unnoticed.
    *
    * What comes back is **caller-owned**: wrap it in a [[scalacv.Managed]] (or your own type) immediately.
    *
    * @param path
    *   the model file. Reported verbatim in every failure, so callers always know which file was blamed.
    * @param describe
    *   how the native call is named if it throws ([[Cv.attempt]]'s operation), and how the null-return
    *   failure names it. Convention at the call sites: a gerund phrase carrying the path, e.g.
    *   `"reading an ONNX model from '$path'"`.
    * @param missingDetails
    *   the `LoadFailed` details when `path` is not a regular file. Per call site, because the useful advice
    *   is model-specific ("fetch it with FaceDetect.downloadModel(dir)").
    * @param validate
    *   `Some(reason)` rejects a successfully created handle — the hook for `CascadeClassifier`'s silent-empty
    *   failure mode. Called only on a non-null handle; when it rejects, the handle is released through its
    *   [[Releasable]] before the `Left` is returned.
    */
  def loadNative[A](
      path: String,
      describe: String,
      missingDetails: => String,
      validate: A => Option[String] = (_: A) => None
  )(create: => A)(using releasable: Releasable[A]): Either[CvError, A] =
    val file = File(path)
    if !file.isFile then Left(CvError.LoadFailed(path, missingDetails))
    else if !file.canRead then Left(CvError.LoadFailed(path, "the file exists but is not readable"))
    else
      val result = Cv.attempt[Either[CvError, A]](describe):
        val a = create
        if a == null then
          Left(
            CvError.LoadFailed(
              path,
              s"$describe returned null — OpenCV's factory reports this failure by returning " +
                "nothing rather than throwing, so scalacv reports it here instead"
            )
          )
        else
          val validation =
            try validate(a)
            catch
              case error: Throwable =>
                try releasable.release(a)
                catch
                  case releaseError: Throwable =>
                    if releaseError ne error then error.addSuppressed(releaseError)
                throw error
          validation match
            case Some(reason) =>
              // The handle is real even though the model is not, so it still has to be freed.
              releasable.release(a)
              Left(CvError.LoadFailed(path, reason))
            case None => Right(a)
      result.flatMap(identity)
