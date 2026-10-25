package com.example.farragutclock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudDAccountErrorTest {
  @Test
  fun authenticationQuotaAndSubscriptionErrorsStopRecognition() {
    for (code in listOf(900, 901, 902, 903, 904, 905)) {
      assertTrue("Account error $code must stop uploads", isAudDAccountLimitError(code))
    }
  }

  @Test
  fun audioAndServiceErrorsDoNotMasqueradeAsAccountExhaustion() {
    for (code in listOf(-1, 100, 300, 400, 500, 600, 611, 700, 906, 1000)) {
      assertFalse("Non-account error $code is not an account limit", isAudDAccountLimitError(code))
    }
  }
}
