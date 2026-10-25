package com.example.farragutclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LineInRecognitionStateTest {
  private val firstSong = RecognizedSong("Every Little Thing She Does Is Magic", "The Police")
  private val secondSong = RecognizedSong("Next song", "Another artist")

  @Test
  fun inactiveSourcesNeverStartAndFirstActiveSourceStartsImmediately() {
    val state = LineInRecognitionState()
    assertFalse(state.shouldStart(0))
    assertNull(state.beginAttempt(0))
    state.updateSource("  ", 100)
    assertFalse(state.shouldStart(100))

    state.updateSource("living-room:turntable", 1_000)
    assertTrue(state.shouldStart(1_000))
    assertNotNull(state.beginAttempt(1_000))
  }

  @Test
  fun captureCannotOverlapEvenWhenAnotherIntervalHasPassed() {
    val state = activeState()
    val first = checkNotNull(state.beginAttempt(0))
    assertFalse(state.shouldStart(120_000))
    assertNull(state.beginAttempt(120_000))
    assertTrue(state.completeAttempt(first, firstSong, 10_000))
    assertFalse(state.shouldStart(59_999))
    assertNotNull(state.beginAttempt(60_000))
  }

  @Test
  fun repeatedPollsForSameSourcePreservePendingAttemptAndConfirmedSong() {
    val state = activeState()
    val attempt = checkNotNull(state.beginAttempt(0))
    state.updateSource("living-room:turntable", 3_000)
    assertNull(state.beginAttempt(3_000))
    assertTrue(state.completeAttempt(attempt, firstSong, 10_000))
    state.updateSource("living-room:turntable", 12_000)
    assertEquals(firstSong, state.currentSong(12_000))
  }

  @Test
  fun confirmedSongExpiresFromCaptureRatherThanDelayedServerResponse() {
    val state = activeState()
    val attempt = checkNotNull(state.beginAttempt(0))
    state.completeAttempt(attempt, firstSong, 30_000)
    assertEquals(firstSong, state.currentSong(89_999))
    assertNull(state.currentSong(90_000))
  }

  @Test
  fun responseThatIsAlreadyStaleDoesNotDisplaySong() {
    val state = activeState()
    val attempt = checkNotNull(state.beginAttempt(0))
    assertTrue(state.completeAttempt(attempt, firstSong, 90_000))
    assertNull(state.currentSong(90_000))
  }

  @Test
  fun noMatchClearsPreviouslyRecognizedSongImmediately() {
    val state = activeState()
    state.completeAttempt(checkNotNull(state.beginAttempt(0)), firstSong, 10_000)
    val next = checkNotNull(state.beginAttempt(60_000))
    assertEquals(firstSong, state.currentSong(65_000))
    assertTrue(state.completeAttempt(next, null, 70_000))
    assertNull(state.currentSong(70_000))
  }

  @Test
  fun blankSongIsTreatedAsNoMatch() {
    val state = activeState()
    state.completeAttempt(checkNotNull(state.beginAttempt(0)), RecognizedSong(" ", "Artist"), 10_000)
    assertNull(state.currentSong(10_000))
  }

  @Test
  fun sourceChangeClearsSongAndIgnoresLateResponseFromPreviousSource() {
    val state = activeState()
    state.completeAttempt(checkNotNull(state.beginAttempt(0)), firstSong, 10_000)
    val oldAttempt = checkNotNull(state.beginAttempt(60_000))
    assertTrue(state.isCurrentAttempt(oldAttempt))
    state.updateSource("kitchen:cd-player", 62_000)
    assertFalse(state.isCurrentAttempt(oldAttempt))
    assertNull(state.currentSong(62_000))
    val currentAttempt = checkNotNull(state.beginAttempt(62_000))
    assertTrue(state.isCurrentAttempt(currentAttempt))
    assertFalse(state.completeAttempt(oldAttempt, firstSong, 70_000))
    assertFalse(state.failAttempt(oldAttempt, 70_000))
    assertTrue(state.completeAttempt(currentAttempt, secondSong, 72_000))
    assertEquals(secondSong, state.currentSong(72_000))
  }

  @Test
  fun stoppingAndRestartingSameSourceInvalidatesOldAttempt() {
    val state = activeState()
    val oldAttempt = checkNotNull(state.beginAttempt(0))
    state.updateSource(null, 2_000)
    assertFalse(state.shouldStart(2_000))
    state.updateSource("living-room:turntable", 3_000)
    val currentAttempt = checkNotNull(state.beginAttempt(3_000))
    assertFalse(state.completeAttempt(oldAttempt, firstSong, 10_000))
    assertTrue(state.completeAttempt(currentAttempt, secondSong, 13_000))
    assertEquals(secondSong, state.currentSong(13_000))
  }

  @Test
  fun completedAttemptCannotOverwriteANewerMatch() {
    val state = activeState()
    val first = checkNotNull(state.beginAttempt(0))
    state.completeAttempt(first, firstSong, 10_000)
    val second = checkNotNull(state.beginAttempt(60_000))
    state.completeAttempt(second, secondSong, 70_000)
    assertFalse(state.completeAttempt(first, firstSong, 75_000))
    assertEquals(secondSong, state.currentSong(75_000))
  }

  @Test
  fun failureBacksOffAndDoesNotKeepAnExpiredSong() {
    val state = activeState()
    state.completeAttempt(checkNotNull(state.beginAttempt(0)), firstSong, 10_000)
    val next = checkNotNull(state.beginAttempt(60_000))
    assertTrue(state.failAttempt(next, 70_000))
    assertEquals(firstSong, state.currentSong(70_000))
    assertNull(state.currentSong(90_000))
    assertFalse(state.shouldStart(189_999))
    assertTrue(state.shouldStart(190_000))
  }

  @Test
  fun cancellationRejectsLateResponseAndWaitsBeforeRetrying() {
    val state = activeState()
    val attempt = checkNotNull(state.beginAttempt(0))
    assertTrue(state.cancelAttempt(attempt, 2_000))
    assertFalse(state.completeAttempt(attempt, firstSong, 10_000))
    assertFalse(state.shouldStart(61_999))
    assertTrue(state.shouldStart(62_000))
  }

  @Test
  fun clearRemovesMatchAndInvalidatesPendingWork() {
    val state = activeState()
    state.completeAttempt(checkNotNull(state.beginAttempt(0)), firstSong, 10_000)
    val attempt = checkNotNull(state.beginAttempt(60_000))
    state.clear()
    assertNull(state.currentSong(70_000))
    assertFalse(state.shouldStart(70_000))
    assertFalse(state.completeAttempt(attempt, secondSong, 70_000))
  }

  @Test
  fun targetIncludesOnlyEligibleLineInRooms() {
    assertNull(lineInRecognitionTarget(emptyList()))
    assertNull(lineInRecognitionTarget(listOf(playback("Family Room"), playback("Main Bath"))))
    assertNull(lineInRecognitionTarget(listOf(playback("Living Room", "x-sonos-htastream:tv"))))
    assertNull(lineInRecognitionTarget(listOf(playback("Kitchen", "x-sonosapi-radio:station"))))

    val livingRoom = playback("Living Room", groupId = "living-group")
    assertEquals(
      LineInRecognitionTarget("living-group|x-rincon-stream:turntable", "x-rincon-stream:turntable"),
      lineInRecognitionTarget(listOf(livingRoom, playback("Main Bath", "x-rincon-stream:other"))),
    )
  }

  @Test
  fun sameInputInTwoEligibleGroupsSharesOneTargetAndOrderDoesNotChangeIdentity() {
    val livingRoom = playback("Living Room", groupId = "group-z")
    val kitchen = playback("Kitchen", groupId = "group-a")
    val target = lineInRecognitionTarget(listOf(livingRoom, kitchen))
    assertNotNull(target)
    assertEquals("x-rincon-stream:turntable", target?.sourceUri)
    assertEquals("group-a|x-rincon-stream:turntable\ngroup-z|x-rincon-stream:turntable", target?.sourceKey)
    assertEquals(target, lineInRecognitionTarget(listOf(kitchen, livingRoom, kitchen)))
  }

  @Test
  fun differentInputsInEligibleRoomsAreAmbiguousAndHaveNoRecognitionTarget() {
    assertNull(
      lineInRecognitionTarget(
        listOf(
          playback("Living Room", "x-rincon-stream:turntable", "living-group"),
          playback("Kitchen", "x-rincon-stream:cd-player", "kitchen-group"),
        ),
      ),
    )
  }

  @Test
  fun groupingSourceAndSelectedGroupChangesInvalidateTargetIdentity() {
    val livingRoom = playback("Living Room", groupId = "living-group")
    val kitchen = playback("Kitchen", groupId = "kitchen-group")
    val both = checkNotNull(lineInRecognitionTarget(listOf(livingRoom, kitchen)))
    val livingOnly = checkNotNull(lineInRecognitionTarget(listOf(livingRoom)))
    val regrouped = checkNotNull(lineInRecognitionTarget(listOf(livingRoom.copy(groupId = "new-group"))))
    val changedInput = checkNotNull(lineInRecognitionTarget(listOf(livingRoom.copy(sourceUri = "x-rincon-stream:cd-player"))))
    assertFalse(both.sourceKey == livingOnly.sourceKey)
    assertFalse(regrouped.sourceKey == livingOnly.sourceKey)
    assertFalse(changedInput.sourceKey == livingOnly.sourceKey)
  }

  @Test
  fun targetFailsClosedWhenEligiblePlaybackHasNoStableGroupIdentity() {
    assertNull(lineInRecognitionTarget(listOf(playback("Living Room", groupId = ""))))
    assertNull(
      lineInRecognitionTarget(
        listOf(playback("Living Room", groupId = "living-group"), playback("Kitchen", groupId = "")),
      ),
    )
  }

  private fun activeState() = LineInRecognitionState().apply {
    updateSource("living-room:turntable", 0)
  }

  private fun playback(
    room: String,
    sourceUri: String = "x-rincon-stream:turntable",
    groupId: String = "group",
  ) = SonosPlayback(
    roomNames = listOf(room),
    current = SonosTrack("Line-in"),
    groupId = groupId,
    sourceUri = sourceUri,
  )
}
