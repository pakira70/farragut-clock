package com.example.farragutclock

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * WHYY's player uses this lookup for the on-air program, rather than individual audio tracks.
 * Source: https://whyy.org/radio-schedule/ and its public player script's ajaxShowUpdate request.
 */
internal class WhyyProgramRepository(
  private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000L },
  private val fetch: suspend () -> ByteArray = { fetchWhyyProgram() },
) {
  private data class Cached(val attemptedAtMillis: Long, val program: String?)
  private val mutex = Mutex()
  private var cached: Cached? = null

  suspend fun currentProgram(): String? = mutex.withLock {
    currentCoroutineContext().ensureActive()
    val now = elapsedMillis()
    cached?.let { entry ->
      val age = now - entry.attemptedAtMillis
      if (age >= 0L && age < CACHE_MILLIS) return@withLock entry.program
    }
    // Expired successes cannot survive a failed refresh or cancellation.
    cached = null
    val program = try {
      parseWhyyProgram(fetch())
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: IOException) {
      null
    }
    currentCoroutineContext().ensureActive()
    cached = Cached(elapsedMillis(), program)
    program
  }

  internal companion object {
    const val CACHE_MILLIS = 60_000L
  }
}

internal const val WHYY_PROGRAM_MAX_BYTES = 4 * 1024
private const val WHYY_PROGRAM_MAX_CHARS = 160
private const val WHYY_PROGRAM_TIMEOUT_MILLIS = 3_000L
private const val WHYY_PROGRAM_URL = "https://whyy.org/wp-admin/admin-ajax.php"
private const val WHYY_PROGRAM_FORM = "action=whyy_radio_check_radio_ajax&post_var=true"

/** Accept one short text title; error pages, escaped markup, and control characters fail closed. */
internal fun parseWhyyProgram(bytes: ByteArray): String? {
  if (bytes.isEmpty() || bytes.size > WHYY_PROGRAM_MAX_BYTES) return null
  val raw = try {
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
      .trim(' ', '\t', '\r', '\n')
  } catch (_: java.nio.charset.CharacterCodingException) { return null }
  fun invalidCharacters(value: String): Boolean = value.any {
    Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() ||
      it == '\u2028' || it == '\u2029' || it == '<' || it == '>'
  }
  if (invalidCharacters(raw)) return null
  val decoded = StringBuilder()
  var position = 0
  val entity = Regex("&([^;\\s&]{1,32});")
  for (match in entity.findAll(raw)) {
    decoded.append(raw, position, match.range.first)
    val token = match.groupValues[1]
    val replacement = when (token) {
      "amp" -> "&"
      "quot" -> "\""
      "apos" -> "'"
      "lt" -> "<"
      "gt" -> ">"
      "nbsp" -> " "
      else -> {
        val digits = if (token.startsWith("#x", true)) token.substring(2) else token.removePrefix("#")
        val radix = if (token.startsWith("#x", true)) 16 else 10
        val validDigits = digits.isNotEmpty() && digits.all {
          if (radix == 16) it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' else it in '0'..'9'
        }
        val code = if (token.startsWith('#') && validDigits) digits.toIntOrNull(radix) else null
        if (code == null || !Character.isValidCodePoint(code) || code in 0xd800..0xdfff) return null
        String(Character.toChars(code))
      }
    }
    decoded.append(replacement)
    position = match.range.last + 1
  }
  decoded.append(raw, position, raw.length)
  val title = decoded.toString().trim()
  if (title.isEmpty() || title.length > WHYY_PROGRAM_MAX_CHARS || invalidCharacters(title) ||
    title.none(Char::isLetter) || title.startsWith('{') || title.startsWith('[')) return null
  val lower = title.lowercase(Locale.ROOT)
  if (lower in setOf("null", "false", "true", "not found", "forbidden", "access denied", "bad gateway",
      "service unavailable", "internal server error", "unauthorized", "too many requests") ||
    Regex("^(?:[45][0-9]{2}\\b|error\\b|warning\\b|fatal error\\b|request blocked\\b|" +
      "there has been a critical error\\b|the site is experiencing technical difficulties\\b)")
      .containsMatchIn(lower)) return null
  return title
}

private suspend fun fetchWhyyProgram(): ByteArray = withContext(Dispatchers.IO) {
  val coroutine = currentCoroutineContext()
  val started = System.nanoTime()
  fun remainingTimeout(): Int {
    coroutine.ensureActive()
    val remaining = WHYY_PROGRAM_TIMEOUT_MILLIS - (System.nanoTime() - started) / 1_000_000L
    if (remaining <= 0L) throw SocketTimeoutException("WHYY program lookup timed out.")
    return remaining.toInt()
  }
  val connection = URL(WHYY_PROGRAM_URL).openConnection() as HttpsURLConnection
  try {
    connection.instanceFollowRedirects = false
    connection.useCaches = false
    connection.requestMethod = "POST"
    connection.doOutput = true
    connection.connectTimeout = remainingTimeout()
    connection.readTimeout = remainingTimeout()
    connection.setRequestProperty("Accept", "text/plain, text/html")
    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
    connection.setRequestProperty("User-Agent", "FarragutClock")
    val body = WHYY_PROGRAM_FORM.toByteArray(Charsets.UTF_8)
    connection.setFixedLengthStreamingMode(body.size)
    connection.outputStream.use { it.write(body) }
    connection.readTimeout = remainingTimeout()
    if (connection.responseCode != HttpsURLConnection.HTTP_OK) {
      connection.errorStream?.close()
      throw IOException("WHYY program lookup returned an unsuccessful response.")
    }
    if (connection.contentLengthLong > WHYY_PROGRAM_MAX_BYTES) throw IOException("WHYY response is too large.")
    connection.inputStream.use { input ->
      val output = ByteArrayOutputStream()
      val buffer = ByteArray(1024)
      while (true) {
        connection.readTimeout = remainingTimeout()
        val count = input.read(buffer, 0, minOf(buffer.size, WHYY_PROGRAM_MAX_BYTES + 1 - output.size()))
        if (count < 0) break
        if (count == 0) {
          val byte = input.read()
          if (byte < 0) break
          output.write(byte)
        } else output.write(buffer, 0, count)
        if (output.size() > WHYY_PROGRAM_MAX_BYTES) throw IOException("WHYY response is too large.")
      }
      remainingTimeout()
      output.toByteArray()
    }
  } finally { connection.disconnect() }
}
