package scalacv

import java.io.File
import java.net.URLClassLoader
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit

class MissingNativesTest extends munit.FunSuite:

  test("missing natives retain dependency guidance on initial load and retry"):
    def urls(loader: ClassLoader): List[String] =
      if loader == null then Nil
      else
        val own = loader match
          case u: URLClassLoader => u.getURLs.toList.map(u => Path.of(u.toURI).toString)
          case _ => Nil
        own ::: urls(loader.getParent)
    val cp = (System.getProperty("java.class.path").split(File.pathSeparator).toList :::
      urls(getClass.getClassLoader)).distinct
      .filterNot(_.matches(".*-(linux|macosx|windows|android|ios)-[^/]+\\.jar"))
      .mkString(File.pathSeparator)
    val output = Files.createTempFile("scalacv-missing-natives", ".log")
    val process = new ProcessBuilder(
      Path.of(System.getProperty("java.home"), "bin", "java").toString,
      "-Dorg.bytedeco.javacpp.platform=missing-natives",
      "-cp",
      cp,
      "scalacv.MissingNativesProbe"
    ).redirectErrorStream(true).redirectOutput(output.toFile).start()
    try
      assert(process.waitFor(20, TimeUnit.SECONDS), "native loading timed out")
      val log = Files.readString(output)
      assertEquals(process.exitValue(), 0, log)
      assertEquals(
        log.linesIterator.filter(_.startsWith("cause=")).toList,
        List("cause=UnsatisfiedLinkError", "cause=NoClassDefFoundError")
      )
    finally
      if process.isAlive then
        process.destroyForcibly()
        process.waitFor(10, TimeUnit.SECONDS): Unit
      Files.deleteIfExists(output): Unit

object MissingNativesProbe:
  def main(args: Array[String]): Unit =
    for _ <- 1 to 2 do
      try
        OpenCv.load()
        throw AssertionError("loading without native jars must fail")
      catch
        case error: CvError.NativesMissing =>
          require(!OpenCv.isLoaded, "failed loading must not mark natives as loaded")
          require(error.getMessage.contains(Build.openCvArtifactVersion), error.getMessage)
          require(error.getMessage.contains(Build.openBlasArtifactVersion), error.getMessage)
          println("cause=" + Option(error.getCause).map(_.getClass.getSimpleName).getOrElse("null"))
