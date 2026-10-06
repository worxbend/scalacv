package scalacv

/** A video container/codec, as a FOURCC — pure bit packing, no native call, so a codec can be named before
  * `OpenCv.load()`.
  */
enum Codec(val fourcc: Int):

  /** MPEG-4 Part 2 in an `.mp4`. Smaller files than [[Mjpg]], but it needs a videoio linked against FFmpeg or
    * a platform MPEG-4 encoder, and that is not a given: the `org.bytedeco` `linux-x86_64` and
    * `windows-x86_64` payloads this project builds against ship no FFmpeg plugin at all, so opening a writer
    * for this codec fails there. Take the `Left` from [[Recorder.open]] seriously rather than assuming it.
    */
  case Mp4v extends Codec(Codec.of('m', 'p', '4', 'v'))

  /** H.264 in an `.mp4`. Best compression, but only if the build ships an H.264 encoder. */
  case Avc1 extends Codec(Codec.of('a', 'v', 'c', '1'))

  /** Motion-JPEG in an `.avi` — large files, but it is served by videoio's built-in MJPEG writer and so needs
    * no FFmpeg, no GStreamer and no system codec. That makes it the one combination that opens on every
    * build, which is why it is the default for [[Recorder.open]], [[Recorder.using]], [[Camera.recordTo]] and
    * `Animation.record`.
    *
    * The container is part of the bargain: MJPG opens only in an `.avi`, so a path ending in `.mp4` or `.mkv`
    * fails to open even though the codec itself is available.
    */
  case Mjpg extends Codec(Codec.of('M', 'J', 'P', 'G'))

  /** Xvid MPEG-4 in an `.avi`. */
  case Xvid extends Codec(Codec.of('X', 'V', 'I', 'D'))

object Codec:
  private def of(a: Char, b: Char, c: Char, d: Char): Int =
    (a.toInt & 0xff) | ((b.toInt & 0xff) << 8) | ((c.toInt & 0xff) << 16) | ((d.toInt & 0xff) << 24)
