package com.example.farragutclock

import java.text.Normalizer
import java.util.Locale

/** Only the small title/artist/album region of a verified YTM result is passed here. */
internal data class YtmOcrLine(
  val text: String,
  val left: Int,
  val top: Int,
  val right: Int,
  val bottom: Int,
)

/**
 * Accessibility supplies the title; OCR must independently find that exact title followed by an
 * artist and an album/year footer. Missing or changed layout is a failed read, never a guessed song.
 */
internal fun parseYtmResult(
  titleHint: String,
  lines: List<YtmOcrLine>,
  imageWidth: Int,
  imageHeight: Int,
): RecognizedSong? {
  val title = cleanYtmText(titleHint)
  val titleKey = ytmTextKey(title)
  if (titleKey.isEmpty() || title.length > 300 || imageWidth <= 0 || imageHeight <= 0) return null
  val ordered = lines.mapNotNull {
    // ML Kit may round an edge glyph one or two pixels beyond the source crop.
    if (it.text.isBlank() || it.left < -2 || it.top < -2 ||
        it.right.toLong() > imageWidth.toLong() + 2 ||
        it.bottom.toLong() > imageHeight.toLong() + 2) return@mapNotNull null
    it.copy(
      left = it.left.coerceIn(0, imageWidth), top = it.top.coerceIn(0, imageHeight),
      right = it.right.coerceIn(0, imageWidth), bottom = it.bottom.coerceIn(0, imageHeight),
    ).takeIf { line -> line.right > line.left && line.bottom > line.top }
  }.sortedWith(compareBy<YtmOcrLine> { it.top }.thenBy { it.left })

  val titleRanges = mutableListOf<IntRange>()
  for (start in ordered.indices) {
    for (count in 1..3) {
      val end = start + count - 1
      if (end >= ordered.size) break
      val group = ordered.subList(start, end + 1)
      if (group.zipWithNext().any { (first, next) ->
          next.top < first.bottom - 3 ||
            next.top - first.bottom > maxOf(30, first.bottom - first.top) ||
            kotlin.math.abs(next.left - first.left) > maxOf(24, imageWidth / 8)
        }) break
      if (ytmTextKey(group.joinToString(" ") { it.text }) == titleKey) {
        titleRanges += start..end
      }
    }
  }
  // An album-art inscription or repeated title must not silently select the wrong region.
  val range = titleRanges.singleOrNull() ?: return null
  val titleLine = ordered[range.first]
  val titleBottom = ordered[range.last].bottom
  val artistLine = ordered.getOrNull(range.last + 1) ?: return null
  val footerLine = ordered.getOrNull(range.last + 2) ?: return null
  val height = (titleLine.bottom - titleLine.top).coerceAtLeast(1)
  if (artistLine.top < titleBottom - 2 || artistLine.top - titleBottom > maxOf(32, height * 2)) return null
  if (footerLine.top < artistLine.bottom - 2 ||
      footerLine.top - artistLine.bottom > maxOf(32, height * 2)) return null
  if (kotlin.math.abs(artistLine.left - titleLine.left) > maxOf(24, imageWidth / 8) ||
      kotlin.math.abs(footerLine.left - titleLine.left) > maxOf(24, imageWidth / 8)) return null
  val artist = cleanYtmText(artistLine.text)
  val artistKey = ytmTextKey(artist)
  if (artist.length !in 1..200 || artistKey.isEmpty() || artistKey == titleKey ||
      artistKey in YTM_CONTROL_LABELS || artist.none { it.isLetter() } ||
      YTM_ALBUM_YEAR.containsMatchIn(artist)) return null
  if (!YTM_ALBUM_YEAR.containsMatchIn(cleanYtmText(footerLine.text))) return null
  return RecognizedSong(title, artist)
}

internal fun cleanYtmText(value: String): String = value.trim().replace(Regex("\\s+"), " ")

internal fun ytmTextKey(value: String): String =
  Normalizer.normalize(value, Normalizer.Form.NFKD)
    .replace(Regex("\\p{M}+"), "")
    .lowercase(Locale.ROOT)
    .filter { it.isLetterOrDigit() }

// ML Kit may render the small album/year middot as a dash or omit it entirely.
private val YTM_ALBUM_YEAR = Regex("(?:[·•|–—-]\\s*|\\s+|^)(?:19|20)\\d{2}(?:\\b|$)")
private val YTM_CONTROL_LABELS = setOf(
  "search", "searchanothersong", "like", "likethisvideo", "unlike", "save", "savetoplaylist",
  "play", "playall", "listening", "tryagain", "cancel", "soundsearch", "download", "more",
)
