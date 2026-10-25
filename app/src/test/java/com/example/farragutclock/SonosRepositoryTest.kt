package com.example.farragutclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SonosRepositoryTest {
  @Test
  fun defaultsFollowOnlyLivingRoomAndKitchen() {
    assertTrue(isDefaultSonosRoom("Living Room"))
    assertTrue(isDefaultSonosRoom(" kitchen (One) "))
    assertFalse(isDefaultSonosRoom("Family Room"))
    assertFalse(isDefaultSonosRoom("Main Bath"))
    assertFalse(isDefaultSonosRoom("Kitchenette"))
  }

  @Test
  fun groupedSelectedRoomsUseCoordinatorOnceAndExcludeUnselectedLabels() {
    val rooms = listOf(
      SonosRoom("living", "Living Room", "192.168.1.2", "family", "192.168.1.4"),
      SonosRoom("kitchen", "Kitchen", "192.168.1.3", "family", "192.168.1.4"),
      SonosRoom("family", "Family Room", "192.168.1.4"),
      SonosRoom("bath", "Main Bath", "192.168.1.5"),
    )
    val selected = selectedSonosGroups(rooms, null)
    assertEquals(1, selected.size)
    assertEquals(listOf("Kitchen", "Living Room"), selected.single().map { it.name })
    assertEquals("192.168.1.4", selected.single().first().coordinatorHost)
    assertEquals(emptyList<List<SonosRoom>>(), selectedSonosGroups(rooms, emptySet()))
    assertEquals(listOf("kitchen"), selectedSonosGroups(rooms, setOf("kitchen")).single().map { it.id })
  }

  @Test
  fun separateSelectedRoomsStaySeparate() {
    val groups = selectedSonosGroups(listOf(
      SonosRoom("living", "Living Room", "192.168.1.2"),
      SonosRoom("kitchen", "Kitchen", "192.168.1.3"),
    ), null)
    assertEquals(2, groups.size)
  }

  @Test
  fun topologyFindsVisibleRoomsAndResolvesCoordinatorDespiteMemberOrder() {
    val xml = """
      <ZoneGroupState><ZoneGroups><ZoneGroup Coordinator="living" ID="group">
        <ZoneGroupMember UUID="kitchen" ZoneName="Kitchen" Location="http://192.168.1.3:1400/xml/device_description.xml"/>
        <ZoneGroupMember UUID="living" ZoneName="Living Room" Location="http://192.168.1.2:1400/xml/device_description.xml">
          <Satellite UUID="satellite" ZoneName="Surround" Location="http://192.168.1.7:1400/xml/device_description.xml"/>
        </ZoneGroupMember>
        <ZoneGroupMember UUID="invisible" ZoneName="Hidden" Invisible="1" Location="http://192.168.1.6:1400/xml/device_description.xml"/>
        <ZoneGroupMember UUID="bridge" ZoneName="Bridge" IsZoneBridge="1" Location="http://192.168.1.8:1400/xml/device_description.xml"/>
      </ZoneGroup></ZoneGroups></ZoneGroupState>
    """.trimIndent()
    val rooms = SonosXml.rooms(xml)
    assertEquals(listOf("Kitchen", "Living Room"), rooms.map { it.name })
    assertEquals("living", rooms.first().coordinatorId)
    assertEquals("192.168.1.2", rooms.first().coordinatorHost)
  }

  @Test
  fun metadataDecodesEntitiesAndUsesNamespaceNotPrefix() {
    val xml = """
      <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:d="http://purl.org/dc/elements/1.1/" xmlns:u="urn:schemas-upnp-org:metadata-1-0/upnp/">
        <item><d:title>Rock &amp; Roll</d:title><d:creator>Artist</d:creator><u:album>Album</u:album></item>
      </DIDL-Lite>
    """.trimIndent()
    assertEquals(SonosTrack("Rock & Roll", "Artist", "Album"), SonosXml.track(xml))
    assertNull(SonosXml.track("NOT_IMPLEMENTED"))
    assertNull(SonosXml.track(""))
  }

  @Test
  fun radioMetadataUsesStreamContent() {
    val xml = """
      <DIDL-Lite xmlns:d="http://purl.org/dc/elements/1.1/" xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/">
        <item><d:title>Radio station</d:title><r:streamContent>Artist - Song</r:streamContent></item>
      </DIDL-Lite>
    """.trimIndent()
    assertEquals(SonosTrack("Song", "Artist"), SonosXml.track(xml))
  }

  @Test
  fun missedDiscoveryPreservesPreviouslyVerifiedHosts() {
    val verified = listOf("192.168.1.2", "192.168.1.3")
    assertEquals(verified, sonosHostsAfterDiscovery(emptyList(), verified))
    assertEquals(listOf("192.168.1.4"), sonosHostsAfterDiscovery(listOf("192.168.1.4"), verified))
    // Network changes clear the verified list before discovery; old LAN seeds cannot return.
    assertTrue(sonosHostsAfterDiscovery(emptyList(), emptyList()).isEmpty())
  }

  @Test
  fun discoveryRejectsExternalUrlsAndAcceptsCaseInsensitiveHeader() {
    assertEquals("192.168.1.2", SonosXml.discoveryHost("HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.2:1400/xml/device_description.xml\r\n"))
    assertNull(SonosXml.discoveryHost("HTTP/1.1 200 OK\r\nLOCATION: http://example.com:1400/device.xml\r\n"))
    assertNull(SonosXml.discoveryHost("HTTP/1.1 200 OK\r\nLOCATION: http://127.0.0.1:1400/device.xml\r\n"))
    assertNull(SonosXml.discoveryHost("HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.2:9000/device.xml\r\n"))
  }

  @Test(expected = IllegalArgumentException::class)
  fun xmlRejectsExternalEntitiesBeforeParsing() {
    SonosXml.document("<!DOCTYPE foo [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><foo>&x;</foo>")
  }
}
