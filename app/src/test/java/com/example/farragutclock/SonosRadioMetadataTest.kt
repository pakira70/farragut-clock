package com.example.farragutclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SonosRadioMetadataTest {
  private val source = "x-rincon-mp3radio://https://whyy.streamguys1.com/whyy_tunein"
  private val technical = metadata("<d:title>whyy_tunein</d:title><u:class>object.item</u:class>")
  private val station = metadata("<d:title>WHYY</d:title><u:class>object.item.audioItem.audioBroadcast</u:class>")

  @Test fun typedStationReplacesOnlyTheVerifiedTechnicalTitle() {
    assertEquals(SonosTrack("WHYY"), SonosXml.radioTrack(technical, station, source))
    assertTrue(SonosXml.needsWhyyProgram(technical, station, source))
    assertEquals(SonosTrack("whyy_tunein"), SonosXml.radioTrack(technical,
      metadata("<d:title>WHYY</d:title><u:class>object.item</u:class>"), source, "Morning Edition"))
  }

  @Test fun officialFallbackAddsProgramAndSeparateStationIdentity() {
    assertEquals(SonosTrack("Morning Edition", radioStation = "WHYY"),
      SonosXml.radioTrack(technical, station, source, "Morning Edition"))
    assertEquals(SonosTrack("WHYY"), SonosXml.radioTrack(technical, station, source, null))
  }

  @Test fun actualSongAndUsefulShowTitlesWinWithoutStationOrFallbackChanges() {
    val song = metadata("<d:title>Song</d:title><d:creator>Artist</d:creator><u:album>Album</u:album>")
    val show = metadata("<d:title>Actual Sonos Show</d:title>")
    for (xml in listOf(song, show)) {
      assertFalse(SonosXml.needsWhyyProgram(xml, station, source))
      assertEquals(SonosXml.track(xml, source), SonosXml.radioTrack(xml, station, source, "External Show"))
    }
  }

  @Test fun kexpStreamSongMetadataIsPreservedExactly() {
    val xml = metadata("<d:title>kexp</d:title><r:streamContent>Artist - Song</r:streamContent>")
    val kexp = metadata("<d:title>KEXP</d:title><u:class>object.item.audioItem.audioBroadcast</u:class>")
    val uri = "x-rincon-mp3radio://https://kexp.example/stream/kexp"
    assertFalse(SonosXml.needsWhyyProgram(xml, kexp, uri))
    assertEquals(SonosTrack("Song", "Artist"), SonosXml.radioTrack(xml, kexp, uri, "External Show"))
  }

  @Test fun podcastIdentityAndTitleCannotBeOverwrittenByRadioFallback() {
    val xml = metadata("<d:title>whyy_tunein</d:title><u:album>Podcast Series</u:album><r:semanticType>episode.podcast</r:semanticType>")
    val expected = SonosTrack("whyy_tunein", album = "Podcast Series", podcastSeries = "Podcast Series")
    assertFalse(SonosXml.needsWhyyProgram(xml, station, source))
    assertEquals(expected, SonosXml.radioTrack(xml, station, source, "External Show"))
    assertEquals(expected, SonosXml.radioTrack(xml, station, "spotify:episode:Abc123", "External Show"))
  }

  @Test fun actualRadioShowMetadataBeatsExternalFallback() {
    val xml = metadata("<d:title>whyy_tunein</d:title><r:radioShowMd>Wait, Wait... Don’t Tell Me,p1234</r:radioShowMd>")
    assertFalse(SonosXml.needsWhyyProgram(xml, station, source))
    assertEquals(SonosTrack("Wait, Wait... Don’t Tell Me", radioStation = "WHYY"),
      SonosXml.radioTrack(xml, station, source, "External Show"))
  }

  @Test fun streamShowMetadataIsAlreadyUsefulAndDoesNotTriggerExternalLookup() {
    val xml = metadata("<d:title>whyy_tunein</d:title><r:streamContent>Actual Show</r:streamContent>")
    assertFalse(SonosXml.needsWhyyProgram(xml, station, source))
    assertEquals(SonosTrack("Actual Show"), SonosXml.radioTrack(xml, station, source, "External Show"))
  }

  @Test fun exactTrustedSourceAndWhyyContextAreBothRequired() {
    val lookalikes = listOf(
      source.replace("whyy.streamguys1.com", "whyy.streamguys1.com.example.com"),
      source.replace("whyy.streamguys1.com", "example.com"),
      source.replace("/whyy_tunein", "/other/whyy_tunein"),
      source.replace("/whyy_tunein", "/whyy_tunein_other"),
      source.replace("https://", "https://user@"),
      source.replace("https://", "http://"),
      source + "#different-source",
    )
    for (uri in lookalikes) {
      assertFalse(uri, SonosXml.needsWhyyProgram(technical, station, uri))
      assertFalse(SonosXml.radioTrack(technical, station, uri, "External Show")?.title == "External Show")
    }
    val otherStation = metadata("<d:title>KEXP</d:title><u:class>object.item.audioItem.audioBroadcast</u:class>")
    assertFalse(SonosXml.needsWhyyProgram(technical, otherStation, source))
    assertEquals(SonosTrack("KEXP"), SonosXml.radioTrack(technical, otherStation, source, "External Show"))
  }

  @Test fun stationNameIsNotUsedForUnrelatedTitlesOrNonRadioSources() {
    val unrelated = metadata("<d:title>Different source title</d:title>")
    assertEquals(SonosTrack("Different source title"), SonosXml.radioTrack(unrelated, station, source, "External Show"))
    assertFalse(SonosXml.needsWhyyProgram(unrelated, station, source))
    assertEquals(SonosTrack("whyy_tunein"), SonosXml.radioTrack(technical, station, "spotify:track:Abc", "External Show"))
    assertFalse(SonosXml.isRadioUri("spotify:episode:Abc"))
    assertFalse(SonosXml.isRadioUri("x-rincon-stream:RINCON_INPUT"))
  }

  private fun metadata(fields: String) = """
    <DIDL-Lite xmlns:d="http://purl.org/dc/elements/1.1/" xmlns:u="urn:schemas-upnp-org:metadata-1-0/upnp/"
      xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/"><item>$fields</item></DIDL-Lite>
  """.trimIndent()
}
