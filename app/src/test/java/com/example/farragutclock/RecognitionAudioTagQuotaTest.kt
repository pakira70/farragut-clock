package com.example.farragutclock

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecognitionAudioTagQuotaTest {
  private fun utc(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
    GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
      clear()
      set(year, month - 1, day, hour, minute)
    }.timeInMillis

  @Test
  fun allowsExactlySevenHundredFifteenSecondSamplesEachMonth() {
    val now = utc(2026, 10, 24)
    var quota = RecognitionAudioTagQuota(Long.MIN_VALUE, 0)
    repeat(700) { index ->
      assertEquals(700 - index, quota.remaining(now))
      quota = quota.reserve(now)!!
    }
    assertEquals(0, quota.remaining(now))
    assertNull(quota.reserve(now))
  }

  @Test
  fun nextUtcMonthRestoresAllowanceAtTheBoundary() {
    val quota = RecognitionAudioTagQuota(2026L * 12 + Calendar.OCTOBER, 700)
    assertEquals(0, quota.remaining(utc(2026, 10, 31, 23, 59)))
    assertEquals(700, quota.remaining(utc(2026, 11, 1)))
    assertEquals(
      RecognitionAudioTagQuota(2026L * 12 + Calendar.NOVEMBER, 1),
      quota.reserve(utc(2026, 11, 1)),
    )
  }

  @Test
  fun nextYearRolloverWorksAndClockReversalDoesNotResetQuota() {
    val quota = RecognitionAudioTagQuota(2026L * 12 + Calendar.DECEMBER, 700)
    assertEquals(0, quota.remaining(utc(2026, 11, 1)))
    assertNull(quota.reserve(utc(2026, 11, 1)))
    assertEquals(700, quota.remaining(utc(2027, 1, 1)))
  }

  @Test
  fun invalidStoredCountCannotCreateMoreThanTheMonthlyAllowance() {
    val now = utc(2026, 10, 24)
    val month = 2026L * 12 + Calendar.OCTOBER
    assertEquals(700, RecognitionAudioTagQuota(month, -100).remaining(now))
    assertEquals(RecognitionAudioTagQuota(month, 1), RecognitionAudioTagQuota(month, -100).reserve(now))
    assertEquals(0, RecognitionAudioTagQuota(month, Int.MAX_VALUE).remaining(now))
  }
}
