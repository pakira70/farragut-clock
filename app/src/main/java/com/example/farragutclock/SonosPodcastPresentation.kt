package com.example.farragutclock

/** Display-only normalization; Sonos metadata retains the complete original episode title. */
internal fun sonosPodcastEpisodeTitle(track: SonosTrack): String {
  val series = track.podcastSeries.trim()
  if (series.isEmpty()) return track.title
  val title = track.title.trim()
  if (!title.startsWith(series, ignoreCase = true)) return track.title
  val suffix = title.substring(series.length)
  if (suffix.isEmpty()) return ""
  val episode = suffix.trimStart()
  if (episode.isEmpty()) return ""
  if (episode.first() in PODCAST_TITLE_SEPARATORS) return episode.drop(1).trimStart()
  // Keep an episode number as part of the title. Whitespace must separate it from the series,
  // and a word boundary prevents stripping a merely similar title such as "Series 268th ...".
  if (suffix.first().isWhitespace() && PODCAST_EPISODE_NUMBER.containsMatchIn(episode)) return episode
  return track.title
}

private val PODCAST_TITLE_SEPARATORS = setOf(':', '-', '–', '—', '|', '·')
private val PODCAST_EPISODE_NUMBER = Regex("^#?[0-9]+(?=[\\s:.,\\-–—|·)]|$)")
