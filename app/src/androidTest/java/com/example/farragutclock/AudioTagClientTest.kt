package com.example.farragutclock

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Offline fixtures only: these tests never record audio or contact a provider. */
@RunWith(AndroidJUnit4::class)
class AudioTagClientTest {
  @Test
  fun recognizesWaitingAndNoMatchResponses() {
    assertEquals(AudioTagResult.Waiting, parseAudioTagResult("""{"success":true,"result":"wait"}"""))
    assertEquals(AudioTagResult.Complete(null), parseAudioTagResult("""{"success":true,"result":"not found"}"""))
  }

  @Test
  fun documentedNoMatchErrorIsNormalizedOnlyForResultPolling() {
    for (message in listOf("no recognition result found for the token", "no recognition result found for token")) {
      val body = """{"success":false,"error":"$message"}"""
      assertEquals(AudioTagResult.Complete(null), parseAudioTagResult(body))
      assertEquals(
        AudioTagResult.Complete(null),
        parseAudioTagResult("""{"success":true,"result":"$message"}"""),
      )
      assertThrows(IOException::class.java) { requireAudioTagFreeAllowance(body) }
      assertThrows(IOException::class.java) { parseAudioTagUploadToken(body) }
    }
    assertThrows(IOException::class.java) {
      parseAudioTagResult("""{"success":false,"error":"no recognition result found for the token: unexpected detail"}""")
    }
  }

  @Test
  fun selectsHighestScoredCandidateAndFirstUsableMetadata() {
    val result = parseAudioTagResult("""{"success":true,"result":"found","data":[
      {"confidence":285,"tracks":[["Lower candidate","Artist"]]},
      {"confidence":321,"tracks":[["",""],["Hello Mr. Monkey","Arabesque","Grand Collection",2001],["Compilation title","Various"]]}
    ]}""")
    assertEquals(AudioTagResult.Complete(RecognizedSong("Hello Mr. Monkey", "Arabesque")), result)
  }

  @Test
  fun rejectsUnscoredAndMalformedCandidates() {
    val result = parseAudioTagResult("""{"success":true,"result":"found","data":[
      {"tracks":[["No score","Artist"]]},
      {"confidence":0,"tracks":[["Zero score","Artist"]]},
      {"confidence":100,"tracks":[["No artist",""]]}
    ]}""")
    assertEquals(AudioTagResult.Complete(null), result)
  }

  @Test
  fun rejectsConflictingBestMatches() {
    val result = parseAudioTagResult("""{"success":true,"result":"found","data":[
      {"confidence":100,"tracks":[["Song one","Artist"]]},
      {"confidence":100,"tracks":[["Song two","Artist"]]}
    ]}""")
    assertEquals(AudioTagResult.Complete(null), result)
  }

  @Test
  fun accountErrorsStopButServiceErrorsCanRetry() {
    assertThrows(RecognitionLimitException::class.java) {
      parseAudioTagResult("""{"success":false,"error":"api key inactive"}""")
    }
    assertThrows(RecognitionLimitException::class.java) {
      parseAudioTagResult("""{"success":false,"error":"credit balance exhausted"}""")
    }
    assertThrows(IOException::class.java) {
      parseAudioTagResult("""{"success":false,"error":"service temporarily not available"}""")
    }
  }

  @Test
  fun serverNotReadyKeepsPollingWithoutReuploading() {
    val body = """{"success":false,"error":"internal error: server is not ready"}"""
    assertEquals(AudioTagResult.Waiting, parseAudioTagResult(body))
    assertThrows(IOException::class.java) { requireAudioTagFreeAllowance(body) }
    assertThrows(IOException::class.java) { parseAudioTagUploadToken(body) }
  }

  @Test
  fun documentedErrorsHaveSafeStageDiagnosticsAndUnknownTextStaysPrivate() {
    val upload = assertThrows(IOException::class.java) {
      parseAudioTagUploadToken("""{"success":false,"error":"audio duration is too short"}""")
    }
    assertTrue(upload.message!!.contains("uploading the sample"))
    assertTrue(upload.message!!.contains("recording too short"))
    val shortAudioAlias = assertThrows(IOException::class.java) {
      parseAudioTagUploadToken("""{"success":false,"error":"audio is too short"}""")
    }
    assertEquals(upload.message, shortAudioAlias.message)
    val budget = assertThrows(IOException::class.java) {
      requireAudioTagFreeAllowance("""{"success":false,"error":"api key stats not found"}""")
    }
    assertTrue(budget.message!!.contains("checking the free allowance"))
    val result = assertThrows(IOException::class.java) {
      parseAudioTagResult("""{"success":false,"error":"internal error: server recognition error"}""")
    }
    assertTrue(result.message!!.contains("waiting for identification"))
    assertTrue(result.message!!.contains("recognition server failed"))
    val unknown = assertThrows(IOException::class.java) {
      parseAudioTagResult("""{"success":false,"error":"unexpected echoed-secret-123"}""")
    }
    assertFalse(unknown.message!!.contains("echoed-secret-123"))
    assertTrue(unknown.message!!.contains("unrecognized error"))
  }

  @Test
  fun freeAllowanceMustBeExplicitAndCoverWholeSample() {
    requireAudioTagFreeAllowance("""{"success":true,"identification_free_sec_remainder":15}""")
    assertThrows(RecognitionLimitException::class.java) {
      requireAudioTagFreeAllowance("""{"success":true,"identification_free_sec_remainder":14}""")
    }
    assertThrows(RecognitionLimitException::class.java) {
      requireAudioTagFreeAllowance("""{"success":true,"current_credit_balance":100}""")
    }
  }

  @Test
  fun uploadRequiresTokenAndMalformedResponsesAreRetryable() {
    assertEquals("job-id", parseAudioTagUploadToken("""{"success":true,"token":"job-id"}"""))
    assertThrows(IOException::class.java) { parseAudioTagUploadToken("""{"success":true}""") }
    assertThrows(IOException::class.java) { parseAudioTagResult("not JSON") }
  }
}
