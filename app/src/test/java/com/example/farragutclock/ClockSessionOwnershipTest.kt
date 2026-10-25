package com.example.farragutclock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockSessionOwnershipTest {
  @Test fun dreamDetachDuringHandoffRetainsTheSession() {
    val state = ClockSessionOwnership()
    val dream = Any()
    state.attach(dream)
    assertTrue(state.beginHandoff())
    assertSame(dream, state.originOwner)
    assertFalse(state.detach(dream))
    assertNull(state.originOwner)
    assertTrue(state.active)
    assertTrue(state.handingOff)
  }

  @Test fun oldHostDepartureCannotCloseTheOverlay() {
    val state = ClockSessionOwnership()
    val dream = Any()
    val overlay = Any()
    state.attach(dream)
    state.beginHandoff()
    state.attach(overlay)
    assertSame(dream, state.originOwner)
    assertFalse(state.detach(dream))
    assertNull(state.originOwner)
    assertSame(overlay, state.owner)
  }

  @Test fun completedHandoffMakesActivityDepartureEndTheSession() {
    val state = ClockSessionOwnership()
    val overlay = Any()
    val activity = Any()
    state.attach(Any())
    state.beginHandoff()
    state.attach(overlay)
    assertTrue(state.finishHandoff(activity))
    assertFalse(state.handingOff)
    assertFalse(state.detach(overlay))
    assertTrue(state.detach(activity))
  }

  @Test fun genuineDeparturePermanentlyRejectsLateReturns() {
    val state = ClockSessionOwnership()
    state.attach(Any())
    state.beginHandoff()
    state.close()
    assertFalse(state.attach(Any()))
    assertFalse(state.finishHandoff(Any()))
    assertFalse(state.beginHandoff())
    assertFalse(state.active)
    assertNull(state.originOwner)
  }

  @Test fun normalDreamDepartureRequestsCleanup() {
    val state = ClockSessionOwnership()
    val dream = Any()
    state.attach(dream)
    assertTrue(state.detach(dream))
    assertFalse(state.beginHandoff())
  }

  @Test fun aSecondAttemptCannotReserveAnExistingHandoff() {
    val state = ClockSessionOwnership()
    state.attach(Any())
    assertTrue(state.beginHandoff())
    assertFalse(state.beginHandoff())
  }

  @Test fun activityReturnRetainsUndetachedDreamUntilItCanBeFinished() {
    val state = ClockSessionOwnership()
    val dream = Any()
    val activity = Any()
    state.attach(dream)
    state.beginHandoff()
    state.attach(Any())
    state.finishHandoff(activity)
    assertSame(dream, state.originOwner)
    assertFalse(state.detach(dream))
    assertNull(state.originOwner)
    assertSame(activity, state.owner)
  }

  @Test fun incomingDreamCannotReplaceTheRecognitionOverlay() {
    val state = ClockSessionOwnership()
    val overlay = Any()
    state.attach(Any())
    state.beginHandoff()
    state.attach(overlay)
    assertFalse(state.mayAttachDream(clockActivityOwned = false))
    assertSame(overlay, state.owner)
  }

  @Test fun incomingDreamCannotReplaceTheReturningClockActivity() {
    val state = ClockSessionOwnership()
    val activity = Any()
    state.attach(Any())
    state.beginHandoff()
    state.finishHandoff(activity)
    assertFalse(state.mayAttachDream(clockActivityOwned = true))
    assertSame(activity, state.owner)
  }
}
