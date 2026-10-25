package com.example.farragutclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SonosDisplayStateTest {
  @Test
  fun playbackStartsInSplitAndOneTapTogglesEachWay() {
    val state = SonosDisplayState()
    assertFalse(state.isPlaying)
    assertFalse(state.isSplit)

    state.update(listOf(playback("First song")), 0L)
    assertTrue(state.isPlaying)
    assertTrue(state.isSplit)

    state.toggle()
    assertTrue(state.isPlaying)
    assertFalse(state.isSplit)

    state.toggle()
    assertTrue(state.isSplit)
  }

  @Test
  fun trackChangesAndAdditionalRoomsPreserveFullClockChoice() {
    val state = SonosDisplayState()
    state.update(listOf(playback("First song")), 0L)
    state.toggle()

    val next = listOf(playback("Second song"), playback("Kitchen song", "Kitchen"))
    state.update(next, 3_000L)

    assertEquals(next, state.playback)
    assertFalse(state.isSplit)
  }

  @Test
  fun playbackStopsSixSecondsAfterFirstEmptyPoll() {
    val state = SonosDisplayState()
    state.update(listOf(playback("First song")), 0L)

    state.update(emptyList(), 10_000L)
    assertTrue(state.isPlaying)
    state.update(emptyList(), 15_999L)
    assertTrue(state.isPlaying)
    state.update(emptyList(), 16_000L)
    assertFalse(state.isPlaying)
    assertTrue(state.playback.isEmpty())
  }

  @Test
  fun briefPlaybackGapKeepsChoiceAndRestartsGracePeriod() {
    val state = SonosDisplayState()
    state.update(listOf(playback("First song")), 0L)
    state.toggle()
    state.update(emptyList(), 3_000L)
    state.update(listOf(playback("Second song")), 8_000L)
    assertFalse(state.isSplit)

    state.update(emptyList(), 9_000L)
    state.update(emptyList(), 14_999L)
    assertTrue(state.isPlaying)
    state.update(emptyList(), 15_000L)
    assertFalse(state.isPlaying)
  }

  @Test
  fun newPlaybackAfterStopReturnsToSplit() {
    val state = SonosDisplayState()
    state.update(listOf(playback("First song")), 0L)
    state.toggle()
    state.update(emptyList(), 1_000L)
    state.update(emptyList(), 7_000L)
    state.update(listOf(playback("New session")), 8_000L)

    assertTrue(state.isSplit)
  }

  @Test
  fun clearingRoomSelectionRemovesPlaybackImmediatelyAndResetsChoice() {
    val state = SonosDisplayState()
    state.update(listOf(playback("First song")), 0L)
    state.toggle()
    state.clear()

    assertFalse(state.isPlaying)
    assertFalse(state.isSplit)
    assertTrue(state.playback.isEmpty())

    state.update(listOf(playback("Kitchen song", "Kitchen")), 1_000L)
    assertTrue(state.isSplit)
  }

  @Test
  fun tappingWhileIdleDoesNotChangeNextSession() {
    val state = SonosDisplayState()
    state.toggle()
    state.update(emptyList(), 0L)
    state.update(listOf(playback("First song")), 1_000L)

    assertTrue(state.isSplit)
  }

  @Test
  fun callerCannotMutateSavedPlaybackOrRoomNames() {
    val names = mutableListOf("Living Room")
    val items = mutableListOf(SonosPlayback(names, SonosTrack("First song")))
    val state = SonosDisplayState()
    state.update(items, 0L)

    names.clear()
    items.clear()

    assertEquals(1, state.playback.size)
    assertEquals(listOf("Living Room"), state.playback.single().roomNames)
  }

  private fun playback(title: String, room: String = "Living Room") =
    SonosPlayback(roomNames = listOf(room), current = SonosTrack(title = title, artist = "Artist"))
}
