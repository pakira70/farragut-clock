package com.example.farragutclock

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

/** The same song panel is used by the screen saver, awake clock and recognition cover. */
internal fun sonosPlaybackGroupView(
  context: Context,
  playback: SonosPlayback,
  multipleGroups: Boolean,
  recognitionStatus: String,
): LinearLayout = LinearLayout(context).apply {
  orientation = LinearLayout.VERTICAL
  gravity = Gravity.CENTER_HORIZONTAL
  val songTypeface = SonosVisualStyle.typeface(context)
  fun addLine(value: String, size: Float, primary: Boolean = false, lines: Int = 1) {
    if (value.isBlank()) return
    addView(TextView(context).apply {
      text = value.uppercase(Locale.ROOT)
      gravity = Gravity.CENTER
      textSize = size
      typeface = songTypeface
      setTextColor(if (primary) SonosVisualStyle.primaryTextColor else SonosVisualStyle.secondaryTextColor)
      includeFontPadding = false
      maxLines = lines
      ellipsize = if (lines == Int.MAX_VALUE) null else TextUtils.TruncateAt.END
    }, LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.MATCH_PARENT,
      LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = (6 * resources.displayMetrics.density).roundToInt() })
  }
  addLine(playback.roomNames.joinToString(" · "), 16f)
  val podcast = playback.current.podcastSeries.isNotBlank()
  addLine(playback.current.podcastSeries, if (multipleGroups) 22f else 26f, lines = Int.MAX_VALUE)
  if (playback.current.radioStation.isNotBlank() &&
      !playback.current.radioStation.equals(playback.current.title, ignoreCase = true)) {
    addLine(playback.current.radioStation, if (multipleGroups) 22f else 26f, lines = Int.MAX_VALUE)
  }
  addLine(sonosPodcastEpisodeTitle(playback.current), if (multipleGroups) 34f else 48f,
    primary = true, lines = if (podcast) Int.MAX_VALUE else 2)
  // Podcast publishers sometimes repeat the series in the creator field.
  if (!playback.current.artist.equals(playback.current.podcastSeries, ignoreCase = true) &&
      !playback.current.artist.equals(playback.current.radioStation, ignoreCase = true)) {
    addLine(playback.current.artist, if (multipleGroups) 22f else 26f)
  }
  if (playback.isLineInRecognitionEligible) addLine(recognitionStatus, 14f, lines = 2)
}

internal fun sonosTrackSummary(track: SonosTrack): String {
  val parts = if (track.radioStation.isNotBlank()) {
    listOf(track.title, track.artist, track.radioStation).filter(String::isNotBlank).distinct()
  } else if (track.podcastSeries.isBlank()) {
    listOf(track.title, track.artist)
  } else {
    listOf(sonosPodcastEpisodeTitle(track), track.podcastSeries).distinct()
  }
  return parts.filter(String::isNotBlank).joinToString(" — ")
}
