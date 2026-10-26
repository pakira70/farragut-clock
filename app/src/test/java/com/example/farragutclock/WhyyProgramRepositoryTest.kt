package com.example.farragutclock

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhyyProgramRepositoryTest {
  private fun parse(text: String) = parseWhyyProgram(text.toByteArray(Charsets.UTF_8))

  @Test fun acceptsProgramTitlesWithoutAssumingOneSpecificSchedule() {
    listOf("Morning Edition", "1A", "BBC Newshour", "Wait Wait...Don't Tell Me", "The Connection with Marty Moss-Coane")
      .forEach { assertEquals(it, parse("  $it  ")) }
    assertEquals("Morning Edition", parse("\r\n  Morning Edition\t\n"))
    assertEquals("A".repeat(160), parse("A".repeat(160)))
    assertNull(parse("A".repeat(161)))
  }

  @Test fun decodesBasicAndNumericEntitiesWithoutAllowingEscapedMarkupOrControls() {
    assertEquals("Here & Now", parse("Here &amp; Now"))
    assertEquals("Wait Wait...Don't Tell Me", parse("Wait Wait...Don&#039;t Tell Me"))
    assertEquals("The 'New' Show", parse("The &apos;New&apos; Show"))
    assertEquals("Radio “Show”", parse("Radio &#x201c;Show&#8221;"))
    assertEquals("A B", parse("A&nbsp;B"))
    listOf("&lt;b&gt;Morning Edition&lt;/b&gt;", "Morning&#10;Edition", "Morning&#x202e;Edition",
      "Morning &unknown; Edition", "Morning &#x+041; Edition", "Morning &#55296; Edition")
      .forEach { assertNull(it, parse(it)) }
  }

  @Test fun rejectsEmptyWordPressErrorsMarkupAndPlainErrorResponses() {
    listOf("", "  ", "0", "-1", "null", "false", "<p>Morning Edition</p>", "<!doctype html>",
      "Error establishing a database connection", "Warning: broken service", "403 Forbidden", "Bad Gateway",
      "Service Unavailable", "There has been a critical error on this website.", "{\"title\":\"Morning Edition\"}")
      .forEach { assertNull(it, parse(it)) }
  }

  @Test fun rejectsRawControlsMalformedUtf8AndOversizedBodies() {
    listOf("Morning\nEdition", "Morning\rEdition", "Morning\tEdition", "Morning\u0000Edition",
      "Morning\u0085Edition", "Morning\u2028Edition", "Morning\u202eEdition")
      .forEach { assertNull(it, parse(it)) }
    assertNull(parseWhyyProgram(byteArrayOf(0xc3.toByte(), 0x28)))
    assertNull(parseWhyyProgram(ByteArray(WHYY_PROGRAM_MAX_BYTES + 1) { 'A'.code.toByte() }))
  }

  @Test fun cachesSuccessForSixtySecondsAndRefreshesAtTheBoundary() = runTest {
    var now = 100L
    var calls = 0
    val repository = WhyyProgramRepository({ now }) {
      calls++
      (if (calls == 1) "First Program" else "Second Program").toByteArray()
    }
    assertEquals("First Program", repository.currentProgram())
    now += 59_999L
    assertEquals("First Program", repository.currentProgram())
    assertEquals(1, calls)
    now++
    assertEquals("Second Program", repository.currentProgram())
    assertEquals(2, calls)
  }

  @Test fun cachesFailedRefreshWithoutKeepingThePreviousProgramAndRecovers() = runTest {
    var now = 0L
    var calls = 0
    val repository = WhyyProgramRepository({ now }) {
      calls++
      if (calls == 2) throw IOException("offline")
      "Current Program".toByteArray()
    }
    assertEquals("Current Program", repository.currentProgram())
    now = 60_000L
    assertNull(repository.currentProgram())
    now = 119_999L
    assertNull(repository.currentProgram())
    assertEquals(2, calls)
    now = 120_000L
    assertEquals("Current Program", repository.currentProgram())
    assertEquals(3, calls)
  }

  @Test fun invalidResponseIsAlsoNegativelyCachedAndBackwardClockCannotKeepStaleData() = runTest {
    var now = 100L
    var calls = 0
    val repository = WhyyProgramRepository({ now }) {
      calls++
      if (calls == 1) "0".toByteArray() else "Current Program".toByteArray()
    }
    assertNull(repository.currentProgram())
    assertNull(repository.currentProgram())
    assertEquals(1, calls)
    now = 99L
    assertEquals("Current Program", repository.currentProgram())
    assertEquals(2, calls)
  }

  @Test fun cancellationPropagatesAndDoesNotBecomeACachedFailure() = runTest {
    var calls = 0
    val repository = WhyyProgramRepository({ 0L }) {
      calls++
      if (calls == 1) throw CancellationException("cancelled")
      "Current Program".toByteArray()
    }
    try {
      repository.currentProgram()
      throw AssertionError("Cancellation must escape the repository.")
    } catch (_: CancellationException) { }
    assertEquals("Current Program", repository.currentProgram())
    assertEquals(2, calls)
  }

  @Test fun cancelledCallerCannotCacheAResponseEvenIfFetchIgnoresCancellation() = runTest {
    val started = CompletableDeferred<Unit>()
    val neverReady = CompletableDeferred<Unit>()
    var calls = 0
    val repository = WhyyProgramRepository({ 0L }) {
      calls++
      if (calls == 1) {
        started.complete(Unit)
        try { neverReady.await() } catch (_: CancellationException) { }
        "Cancelled Program".toByteArray()
      } else "Current Program".toByteArray()
    }
    val cancelled = async { repository.currentProgram() }
    started.await()
    cancelled.cancel()
    cancelled.join()
    assertTrue(cancelled.isCancelled)
    assertEquals("Current Program", repository.currentProgram())
    assertEquals(2, calls)
  }

  @Test fun concurrentLookupsShareOneInFlightRequest() = runTest {
    val started = CompletableDeferred<Unit>()
    val response = CompletableDeferred<ByteArray>()
    var calls = 0
    val repository = WhyyProgramRepository({ 0L }) {
      calls++
      started.complete(Unit)
      response.await()
    }
    val first = async { repository.currentProgram() }
    started.await()
    val second = async { repository.currentProgram() }
    response.complete("Current Program".toByteArray())
    assertEquals("Current Program", first.await())
    assertEquals("Current Program", second.await())
    assertEquals(1, calls)
  }
}
