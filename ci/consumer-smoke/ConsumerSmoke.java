// A deliberately minimal *external consumer* of the published scalacv artifacts, written in Java on
// purpose: it needs no Scala compiler, and calling the Scala `object` method through its generated
// static forwarder (scalacv.OpenCv.load()) proves the published bytecode is callable from plain JVM
// code. CI resolves this against all four published coordinates -- com.worxbend:scalacv_3,
// scalacv-vision_3, scalacv-graphs_3 and scalacv-zio_3 -- fetched from a *clean* coursier cache plus
// the two documented natives coordinates, then compiles and runs it.
//
// The point: each published POM carries only the classifier-less opencv, because Mill cannot emit a
// <classifier>. A consumer that adds the natives line must still be able to load. The old "publishLocal
// dry-run" gate passed against a POM that shipped zero .so files; this does not.
//
// core is exercised by loading natives and allocating a Mat across JNI. vision and graphs are exercised
// by linking one public type from each (scalacv.vision.QrCode, scalacv.graphs.Color) -- referencing the
// class literals forces javac to resolve those jars and the runtime to load the classes. scalacv-zio_3 is
// deliberately not referenced here: its public surface is a package object (scalacv.zio) whose synthetic
// holder class (`package$package`) cannot be named from Java source, so its resolution is exercised by
// `cs fetch` listing the coordinate rather than by a symbol reference. The companion
// ScalaConsumerSmoke.scala additionally compiles and executes all four APIs at the Scala/JDK floor.
public class ConsumerSmoke {
  public static void main(String[] args) {
    scalacv.OpenCv.load();
    // Allocate across JNI -- Core.VERSION is a constant and proves nothing.
    org.opencv.core.Mat m = new org.opencv.core.Mat(8, 8, org.opencv.core.CvType.CV_8UC3);
    if (m.rows() != 8 || m.empty()) {
      throw new RuntimeException("expected a non-empty 8x8 Mat across JNI, got " + m.rows() + "x" + m.cols());
    }
    m.release();

    // Link one public type from vision and one from graphs, so a failure to resolve either published
    // artifact surfaces here rather than passing silently. Class literals force both the compile-time
    // classpath entry and the runtime class load.
    Class<?> visionType = scalacv.vision.QrCode.class;
    Class<?> graphsType = scalacv.graphs.Color.class;
    if (visionType == null || graphsType == null) {
      throw new RuntimeException("vision/graphs types did not link");
    }

    // The success signal is this line, not the exit code: OpenCV/OpenBLAS native teardown at JVM
    // shutdown occasionally exits non-zero after a fully successful run. CI greps for it.
    System.out.println("CONSUMER-OK");
  }
}
