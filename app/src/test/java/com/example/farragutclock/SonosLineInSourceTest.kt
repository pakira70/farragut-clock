package com.example.farragutclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SonosLineInSourceTest {
  private val lineIn = SonosPlayback(
    roomNames = listOf("Kitchen", "Living Room"),
    current = SonosTrack("Turntable"),
    groupId = "living",
    sourceUri = "x-rincon-stream:RINCON_LIVING",
  )

  @Test
  fun onlyAnalogLineInUrisAreClassifiedAsLineIn() {
    assertEquals(SonosPlaybackSource.LINE_IN, sonosPlaybackSource("x-rincon-stream:RINCON_LIVING"))
    assertEquals(SonosPlaybackSource.LINE_IN, sonosPlaybackSource(" X-RINCON-STREAM:RINCON_LIVING "))
    assertEquals(SonosPlaybackSource.TV_AUDIO, sonosPlaybackSource("x-sonos-htastream:RINCON_FAMILY:spdif"))
    assertEquals(SonosPlaybackSource.OTHER, sonosPlaybackSource("x-sonosapi-radio:station"))
    assertEquals(SonosPlaybackSource.OTHER, sonosPlaybackSource("x-rincon:RINCON_LIVING"))
    assertEquals(SonosPlaybackSource.OTHER, sonosPlaybackSource("https://example.com/x-rincon-stream:audio"))
    assertEquals(SonosPlaybackSource.OTHER, sonosPlaybackSource(""))
  }

  @Test
  fun eligibilityRequiresEitherApprovedRoomAndLineInRegardlessOfTitle() {
    assertTrue(lineIn.copy(roomNames = listOf("Living Room (Amp)")).isLineInRecognitionEligible)
    assertTrue(lineIn.copy(roomNames = listOf(" kitchen (One) ")).isLineInRecognitionEligible)
    assertTrue(lineIn.copy(current = SonosTrack("Any metadata title", "Some artist")).isLineInRecognitionEligible)
    assertFalse(lineIn.copy(sourceUri = "x-sonosapi-radio:station", current = SonosTrack("Line-in")).isLineInRecognitionEligible)
    assertFalse(lineIn.copy(sourceUri = "x-sonos-htastream:RINCON_FAMILY:spdif").isLineInRecognitionEligible)
  }

  @Test
  fun bathAndFamilyRoomNeverTriggerByThemselves() {
    assertFalse(lineIn.copy(roomNames = listOf("Family Room")).isLineInRecognitionEligible)
    assertFalse(lineIn.copy(roomNames = listOf("Main Bath")).isLineInRecognitionEligible)
    assertFalse(lineIn.copy(roomNames = listOf("Family Room", "Main Bath")).isLineInRecognitionEligible)
    assertFalse(lineIn.copy(roomNames = listOf("Kitchenette")).isLineInRecognitionEligible)
    assertFalse(lineIn.copy(roomNames = emptyList()).isLineInRecognitionEligible)
  }

  @Test
  fun selectedApprovedRoomStillFollowsLineInWhenExcludedRoomCoordinatesGroup() {
    val rooms = listOf(
      SonosRoom("living", "Living Room", "192.168.1.2", "family", "192.168.1.4"),
      SonosRoom("family", "Family Room", "192.168.1.4"),
      SonosRoom("bath", "Main Bath", "192.168.1.5", "family", "192.168.1.4"),
    )
    val selected = selectedSonosGroups(rooms, setOf("living")).single()
    val playback = lineIn.copy(roomNames = selected.map { it.name }, groupId = selected.first().coordinatorId)
    assertEquals(listOf("Living Room"), playback.roomNames)
    assertTrue(playback.isLineInRecognitionEligible)
    assertEquals("family|x-rincon-stream:RINCON_LIVING", playback.lineInRecognitionKey)
  }

  @Test
  fun identityChangesForNewCoordinatorOrAnalogSourceButNotSongMetadata() {
    assertEquals(lineIn.lineInRecognitionKey, lineIn.copy(current = SonosTrack("Identified song", "Artist")).lineInRecognitionKey)
    assertEquals(lineIn.lineInRecognitionKey, lineIn.copy(roomNames = listOf("Living Room")).lineInRecognitionKey)
    assertNotEquals(lineIn.lineInRecognitionKey, lineIn.copy(groupId = "kitchen").lineInRecognitionKey)
    assertNotEquals(lineIn.lineInRecognitionKey, lineIn.copy(sourceUri = "x-rincon-stream:RINCON_OTHER").lineInRecognitionKey)
  }

  @Test
  fun leavingLineInOrApprovedRoomsClearsRecognitionIdentity() {
    assertNull(lineIn.copy(sourceUri = "x-sonosapi-radio:station").lineInRecognitionKey)
    assertNull(lineIn.copy(sourceUri = "x-sonos-htastream:RINCON_FAMILY:spdif").lineInRecognitionKey)
    assertNull(lineIn.copy(roomNames = listOf("Family Room")).lineInRecognitionKey)
    assertNull(lineIn.copy(groupId = "").lineInRecognitionKey)
    assertNull(lineIn.copy(sourceUri = "").lineInRecognitionKey)
  }

  @Test
  fun legacyPlaybackDefaultsRemainNonRecognizable() {
    val legacy = SonosPlayback(listOf("Kitchen"), SonosTrack("Song", "Artist"))
    assertEquals(SonosPlaybackSource.OTHER, legacy.source)
    assertFalse(legacy.isLineInRecognitionEligible)
    assertNull(legacy.lineInRecognitionKey)
  }
}
