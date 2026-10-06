package scalacv

/** Reports how this artifact was built. Present from the first commit so the build has a real compilation
  * unit and so a consumer can report an accurate version in a bug report.
  *
  * Every value here is **generated from `build.mill`'s `Deps` block** rather than typed out a second time —
  * see `core.generatedSources`. That is not tidiness: a version written down in two places is a version that
  * eventually disagrees with itself, and the failure is silent. A dependency bump used to move the build and
  * leave this object (and the "add these lines" help text in [[OpenCv]] and `scalacv.vision.Cascades`)
  * quoting the previous release, which is precisely the number a bug report or a broken classpath depends on
  * being right.
  */
object Build:

  /** The Scala version scalacv was compiled with. */
  val scalaVersion: String = BuildValues.scalaVersion

  /** The OpenCV release behind the bindings, in OpenCV's own numbering (`major.minor.patch`). */
  val openCvVersion: String = BuildValues.openCvVersion

  /** The version of the `org.bytedeco:opencv` artifact scalacv is built against: the OpenCV release paired
    * with the javacpp release that produced the bindings, `<opencv>-<javacpp>`. This, not [[openCvVersion]],
    * is the string that goes in a build file — the natives for your platform must resolve at exactly it,
    * since the Java API and the JNI shim it calls have to come from the same javacpp build.
    */
  val openCvArtifactVersion: String = BuildValues.openCvArtifactVersion

  /** The version of the `org.bytedeco:openblas` artifact the natives need — `libopencv_core` links
    * `libopenblas.so.0`, which ships only in openblas's own classifier jar. See [[OpenCv]].
    */
  val openBlasArtifactVersion: String = BuildValues.openBlasArtifactVersion
