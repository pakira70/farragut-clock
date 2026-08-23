package com.example.farragutclock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitRulesTest {
  @Test
  fun feedExpiredOnlyAfterEndDate() {
    assertFalse(TransitRules.isFeedExpired(20260822, 20270220))
    assertFalse(TransitRules.isFeedExpired(20270220, 20270220))
    assertTrue(TransitRules.isFeedExpired(20270221, 20270220))
    assertFalse(TransitRules.isFeedExpired(20260822, null))
  }

  @Test
  fun feedNotYetStartedBeforeStartDate() {
    assertTrue(TransitRules.isFeedNotYetStarted(20260822, 20260823))
    assertFalse(TransitRules.isFeedNotYetStarted(20260823, 20260823))
    assertFalse(TransitRules.isFeedNotYetStarted(20260822, null))
  }

  @Test
  fun calendarStartsWhenFeedHasNotBegun() {
    assertTrue(TransitRules.calendarHasStarted(20260822, 20260823, feedNotYetStarted = true))
    assertFalse(TransitRules.calendarHasStarted(20260822, 20260823, feedNotYetStarted = false))
    assertTrue(TransitRules.calendarHasStarted(20260823, 20260823, feedNotYetStarted = false))
  }

  @Test
  fun sameTripMatchesOnIdOrNearbyTime() {
    assertTrue(
      TransitRules.isLikelySameTrip("823654", 0, 1_000L, "823654", 0, 10_000L),
    )
    assertTrue(
      TransitRules.isLikelySameTrip("823654", 0, 1_000L, "970006", 0, 120_000L),
    )
    assertFalse(
      TransitRules.isLikelySameTrip("823654", 0, 1_000L, "970006", 1, 120_000L),
    )
    assertFalse(
      TransitRules.isLikelySameTrip("823654", 0, 1_000L, "970006", 0, 5 * 60_000L),
    )
  }

  @Test
  fun imminentPredictionsIgnoreFarFutureRealtime() {
    val now = 1_000_000L
    assertTrue(TransitRules.isImminentPrediction(now + 10 * 60_000L, now))
    assertFalse(TransitRules.isImminentPrediction(now + 200 * 60_000L, now))
  }

  @Test
  fun displayBecomesStaleAfterTwoAndAHalfMinutes() {
    val now = 10_000_000L
    assertTrue(TransitRules.isDisplayStale(null, now))
    assertFalse(TransitRules.isDisplayStale(now - 60_000L, now))
    assertTrue(TransitRules.isDisplayStale(now - 151_000L, now))
  }
}
