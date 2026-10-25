package com.example.farragutclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecognitionTrialQuotaTest {
  private val dayMillis = 86_400_000L

  @Test
  fun allowsExactlyFiveReservationsPerUtcDay() {
    var quota = RecognitionTrialQuota(Long.MIN_VALUE, 0)
    repeat(5) { index ->
      assertEquals(5 - index, quota.remaining(dayMillis * 100))
      quota = quota.reserve(dayMillis * 100)!!
    }
    assertEquals(0, quota.remaining(dayMillis * 100 + dayMillis - 1))
    assertNull(quota.reserve(dayMillis * 100 + dayMillis - 1))
  }

  @Test
  fun nextUtcDayRestoresAllowanceWithoutDependingOnLocalTimezone() {
    val quota = RecognitionTrialQuota(100, 5)
    assertEquals(0, quota.remaining(dayMillis * 101 - 1))
    assertEquals(5, quota.remaining(dayMillis * 101))
    assertEquals(RecognitionTrialQuota(101, 1), quota.reserve(dayMillis * 101))
  }

  @Test
  fun clockMovingBackwardDoesNotCreateNewAllowance() {
    val quota = RecognitionTrialQuota(100, 5)
    assertEquals(0, quota.remaining(dayMillis * 99))
    assertNull(quota.reserve(dayMillis * 99))
  }

  @Test
  fun invalidStoredCountsDoNotExceedTheAllowance() {
    assertEquals(5, RecognitionTrialQuota(100, -10).remaining(dayMillis * 100))
    assertEquals(RecognitionTrialQuota(100, 1), RecognitionTrialQuota(100, -10).reserve(dayMillis * 100))
    assertEquals(0, RecognitionTrialQuota(100, Int.MAX_VALUE).remaining(dayMillis * 100))
  }
}
