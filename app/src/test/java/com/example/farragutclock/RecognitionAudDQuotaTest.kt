package com.example.farragutclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecognitionAudDQuotaTest {
  @Test
  fun allowsExactlyThreeHundredRequestsAndRejectsTheNext() {
    var quota = RecognitionAudDQuota(0)
    repeat(300) { used ->
      assertEquals(300 - used, quota.remaining())
      quota = checkNotNull(quota.reserve())
    }
    assertEquals(300, quota.used)
    assertEquals(0, quota.remaining())
    assertNull(quota.reserve())
  }

  @Test
  fun restoringStoredLifetimeUsagePreservesTheAllowance() {
    val stored = checkNotNull(RecognitionAudDQuota(299).reserve()).used
    val restored = RecognitionAudDQuota(stored)
    assertEquals(0, restored.remaining())
    assertNull(restored.reserve())
    // This quota deliberately accepts no key, provider or date that could reset stored usage.
    assertEquals(123, RecognitionAudDQuota(177).remaining())
  }

  @Test
  fun invalidCountsCannotIncreaseTheLimitOrOverflowOnReservation() {
    assertEquals(300, RecognitionAudDQuota(Int.MIN_VALUE).remaining())
    assertEquals(RecognitionAudDQuota(1), RecognitionAudDQuota(-10).reserve())
    assertEquals(0, RecognitionAudDQuota(301).remaining())
    assertNull(RecognitionAudDQuota(301).reserve())
    assertEquals(0, RecognitionAudDQuota(Int.MAX_VALUE).remaining())
    assertNull(RecognitionAudDQuota(Int.MAX_VALUE).reserve())
  }
}
