package com.example.farragutclock

import org.junit.Assert.assertEquals
import org.junit.Test

class SonosPodcastPresentationTest {
  @Test fun livePocketCastsTitleKeepsTheEpisodeNumberAndSubtitle() {
    val track = SonosTrack("In Moscow's Shadows 268: Peace and (Info) War",
      podcastSeries = "In Moscow's Shadows")
    assertEquals("268: Peace and (Info) War", sonosPodcastEpisodeTitle(track))
    assertEquals("In Moscow's Shadows 268: Peace and (Info) War", track.title)
  }

  @Test fun exactSeriesPrefixWithApprovedSeparatorsIsRemovedCaseInsensitively() {
    listOf(":", "-", "–", "—", "|", "·").forEach { separator ->
      assertEquals("The episode", episode("the SERIES $separator The episode"))
      assertEquals("The episode", episode("The Series${separator}The episode"))
    }
  }

  @Test fun seriesOnlyTitlesDoNotProduceADuplicateEpisodeLine() {
    assertEquals("", episode("The Series"))
    assertEquals("", episode("  the series  "))
    assertEquals("", episode("The Series: "))
  }

  @Test fun sharedWordsWithoutAnEpisodeBoundaryStayIntact() {
    val title = "The Daily Lives of Other People"
    assertEquals(title, sonosPodcastEpisodeTitle(SonosTrack(title, podcastSeries = "The Daily")))
    assertEquals("The Series finale", episode("The Series finale"))
    assertEquals("The Serieskeeper: An episode", episode("The Serieskeeper: An episode"))
    assertEquals("The Series268: An episode", episode("The Series268: An episode"))
  }

  @Test fun whitespaceSeparatedEpisodeNumbersKeepTheirOriginalMarkers() {
    listOf("268: The episode", "268 — The episode", "268. The episode", "#268: The episode", "268").forEach {
      assertEquals(it, episode("The Series $it"))
    }
    assertEquals("The Series 268th episode", episode("The Series 268th episode"))
    assertEquals("The Series 268bit", episode("The Series 268bit"))
  }

  @Test fun unrelatedEpisodeAndOrdinaryMusicTitlesRemainUnchanged() {
    assertEquals("An unrelated episode", episode("An unrelated episode"))
    val music = SonosTrack("  The Series — A song  ", artist = "The Series", album = "The Series")
    assertEquals(music.title, sonosPodcastEpisodeTitle(music))
    assertEquals("The Series: A song", sonosPodcastEpisodeTitle(SonosTrack("The Series: A song")))
  }

  private fun episode(title: String) = sonosPodcastEpisodeTitle(SonosTrack(title, podcastSeries = "The Series"))
}
