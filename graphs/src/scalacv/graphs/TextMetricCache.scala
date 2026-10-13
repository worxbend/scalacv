package scalacv.graphs

import scalacv.*

/** Opt-in, session-owned text metrics for one stable backend. No cache is installed on the default renderer.
  * Pass this measurer to [[PictureLayout]] or return it from a custom [[Renderer.textMeasurer]].
  *
  * The backend must return the same metrics for a text/font/effective-scale key until [[clear]] is called.
  * Clear after changing backend configuration and at the end of a session to release retained text; this is
  * reference removal, not secure erasure. Do not share a cache across unrelated privacy domains.
  *
  * All operations, including backend measurements, are synchronized. Concurrent callers through this wrapper
  * are serialized; external backend mutation/access still needs caller coordination. Exceptions are not
  * cached. Least-recently-used entries are evicted to satisfy both limits. Characters are UTF-16 code units,
  * counted per key even when several keys share one string. Oversized texts bypass the cache without
  * eviction; a zero limit disables retention. The limits bound retained keys, not transient measurement
  * allocations.
  */
final class TextMetricCache(
    backend: TextMeasurer,
    val maxEntries: Int = 256,
    val maxChars: Int = 16384
) extends TextMeasurer:
  require(maxEntries >= 0, "maxEntries must be non-negative")
  require(maxChars >= 0, "maxChars must be non-negative")

  private case class Key(text: String, font: Font, scaleBits: Long)
  private val entries = new java.util.LinkedHashMap[Key, TextMetrics](16, 0.75f, true)
  private var characters = 0L

  /** The current number of retained metric keys. */
  def entryCount: Int = synchronized(entries.size())

  /** The total UTF-16 code units retained by the keys. */
  def retainedChars: Long = synchronized(characters)

  /** Invalidates every metric and drops every retained text reference. */
  def clear(): Unit = synchronized {
    entries.clear()
    characters = 0L
  }

  def measure(text: String, font: Font, scale: Double): TextMetrics = synchronized {
    if maxEntries == 0 || maxChars == 0 || text.length > maxChars then backend.measure(text, font, scale)
    else
      val key = Key(text, font, java.lang.Double.doubleToRawLongBits(scale))
      val found = entries.get(key)
      if found != null then found
      else
        val measured = backend.measure(text, font, scale)
        while entries.size() >= maxEntries || characters + text.length > maxChars do
          val oldest = entries.keySet().iterator()
          characters -= oldest.next().text.length
          oldest.remove()
        val _ = entries.put(key, measured)
        characters += text.length
        measured
  }
