package com.example.farragutclock

import org.junit.Assert.assertEquals
import org.junit.Test

class SonosPodcastMetadataTest {
  @Test fun liveShapedSonosPodcastProvidesSeriesWithoutArtistOrAlbum() {
    val xml = metadata("""
      <dc:title>Stopping the Steal with Eric Holder</dc:title>
      <r:podcast>Pod Save America</r:podcast>
    """, "object.item.audioItem.podcast")
    assertEquals(SonosTrack("Stopping the Steal with Eric Holder", podcastSeries = "Pod Save America"),
      SonosXml.track(xml, "x-sonos-http:local-episode"))
  }

  @Test fun explicitSonosPodcastFieldKeepsEpisodeAndProducerSeparate() {
    val xml = """
      <mediaMetadata xmlns="http://www.sonos.com/Services/1.1">
        <itemType>track</itemType><semanticType>episode.podcast</semanticType>
        <title>The new episode</title>
        <trackMetadata><podcast>A Show &amp; Its Stories</podcast><producer>Producer</producer></trackMetadata>
      </mediaMetadata>
    """.trimIndent()
    assertEquals(SonosTrack("The new episode", podcastSeries = "A Show & Its Stories"), SonosXml.track(xml))
  }

  @Test fun dedicatedDidlPodcastFieldTakesPriorityOverAlbum() {
    val track = SonosXml.track(metadata("""
      <dc:title>Episode 12</dc:title><dc:creator>Host</dc:creator>
      <upnp:album>Other album field</upnp:album><r:podcast>The named podcast</r:podcast>
    """))
    assertEquals(SonosTrack("Episode 12", "Host", "Other album field", "The named podcast"), track)
  }

  @Test fun explicitPodcastSemanticTypeAllowsSeriesInAlbum() {
    val track = SonosXml.track(metadata("""
      <dc:title>Episode 12</dc:title><upnp:album>The series</upnp:album>
      <r:semanticType>episode.podcast</r:semanticType>
    """))
    assertEquals("The series", track?.podcastSeries)
  }

  @Test fun sonosRecordedShowClassUsesAlbumThenRinconSeriesDescription() {
    val fields = """
      <dc:title>Episode 12</dc:title><r:description>The recorded show</r:description>
    """
    assertEquals("The recorded show", SonosXml.track(metadata(fields,
      "object.item.audioItem.musicTrack.recentShow"))?.podcastSeries)
    assertEquals("Album series", SonosXml.track(metadata(fields + "<upnp:album>Album series</upnp:album>",
      "object.item.audioItem.musicTrack.recentShow"))?.podcastSeries)
  }

  @Test fun explicitSpotifyEpisodeIdentityAllowsTheProvidedSeriesAlbum() {
    val xml = metadata("<dc:title>Episode</dc:title><upnp:album>The series</upnp:album>")
    assertEquals("The series", SonosXml.track(xml, "spotify:episode:AbC123")?.podcastSeries)
    assertEquals("The series", SonosXml.track(xml,
      "x-sonos-spotify:spotify%3aepisode%3aAbC123?sid=9&amp;flags=0")?.podcastSeries)
    assertEquals("", SonosXml.track(xml, "spotify:track:AbC123")?.podcastSeries)
    assertEquals("", SonosXml.track(xml,
      "https://example.com/music.mp3?text=spotify:episode:AbC123")?.podcastSeries)
  }

  @Test fun episodeResourceCanIdentifyThePodcastWhenTransportUriIsAQueue() {
    val xml = metadata("""
      <dc:title>Episode</dc:title><upnp:album>The series</upnp:album>
      <res>spotify:episode:AbC123</res>
    """)
    assertEquals("The series", SonosXml.track(xml, "x-rincon-queue:TEST#0")?.podcastSeries)
  }

  @Test fun missingSeriesDoesNotInventItFromTheEpisodeTitleOrProducer() {
    val track = SonosXml.track(metadata("""
      <dc:title>A podcast episode title</dc:title><dc:creator>The host</dc:creator>
      <r:semanticType>episode.podcast</r:semanticType>
      <dc:description>A long episode summary</dc:description>
    """))
    assertEquals(SonosTrack("A podcast episode title", "The host"), track)
  }

  @Test fun ordinaryAlbumAndPodcastWordsDoNotClassifyMusic() {
    val track = SonosXml.track(metadata("""
      <dc:title>The Podcast Song</dc:title><dc:creator>Podcast Band</dc:creator>
      <upnp:album>Podcast Stories: The Album</upnp:album>
    """))
    assertEquals(SonosTrack("The Podcast Song", "Podcast Band", "Podcast Stories: The Album"), track)
  }

  @Test fun kexpMusicAndAirBreaksKeepExistingRadioParsingWithoutSeries() {
    val music = metadata("""
      <dc:title>KEXP</dc:title><r:streamContent>Artist - Song</r:streamContent>
      <upnp:album>Some Album</upnp:album><r:description>KEXP Radio</r:description>
    """, "object.item.audioItem.audioBroadcast")
    assertEquals(SonosTrack("Song", "Artist", "Some Album"), SonosXml.track(music))
    val airBreak = metadata("""
      <dc:title>KEXP</dc:title><r:streamContent>You're listening to KEXP</r:streamContent>
      <r:description>A radio program</r:description>
    """, "object.item.audioItem.audioBroadcast")
    assertEquals(SonosTrack("You're listening to KEXP"), SonosXml.track(airBreak))
  }

  @Test fun queueCannotBorrowTheNextItemsPodcastSeries() {
    val xml = """
      <DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/"
        xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"
        xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/">
        <item><dc:title>First song</dc:title><upnp:album>First album</upnp:album></item>
        <item><dc:title>Next episode</dc:title><r:podcast>Next series</r:podcast></item>
      </DIDL-Lite>
    """.trimIndent()
    assertEquals(SonosTrack("First song", album = "First album"), SonosXml.track(xml))
  }

  private fun metadata(fields: String, itemClass: String = "object.item.audioItem.musicTrack") = """
    <DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/"
      xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"
      xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/">
      <item>$fields<upnp:class>$itemClass</upnp:class></item>
    </DIDL-Lite>
  """.trimIndent()
}
