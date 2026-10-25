package com.example.farragutclock

import android.content.Context
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineStart
import org.json.JSONObject

internal data class RecognizedSong(val title: String, val artist: String)
internal class RecognitionLimitException(message: String) : IllegalStateException(message)

/** One HTTPS request per sample; trial requests are durably bounded across restarts. */
internal class SongRecognitionClient(private val context: Context) {
  suspend fun hasAvailableRequest(
    expectedProvider: RecognitionProvider = RecognitionPreferences.provider(context),
  ): Boolean = withContext(Dispatchers.IO) {
    val token = RecognitionPreferences.apiToken(context, expectedProvider)
    val available = when (expectedProvider) {
      RecognitionProvider.AUDD ->
        if (token != null) RecognitionPreferences.remainingAudDRequests(context) > 0
        else RecognitionPreferences.remainingTrialRequests(context) > 0
      RecognitionProvider.AUDIO_TAG -> token != null && RecognitionPreferences.remainingAudioTagRequests(context) > 0
      RecognitionProvider.YOUTUBE_MUSIC -> false // YTM owns listening through its separate bridge.
    }
    RecognitionPreferences.requireProvider(context, expectedProvider)
    available
  }

  suspend fun recognize(
    wavBytes: ByteArray,
    expectedProvider: RecognitionProvider = RecognitionPreferences.provider(context),
  ): RecognizedSong? = withContext(Dispatchers.IO) {
    val provider = expectedProvider
    RecognitionPreferences.requireProvider(context, provider)
    check(provider != RecognitionProvider.YOUTUBE_MUSIC) { "Use the YouTube Music recognition bridge for this provider." }
    val savedToken = RecognitionPreferences.apiToken(context, provider)
    currentCoroutineContext().ensureActive()
    RecognitionPreferences.requireProvider(context, provider)
    if (provider == RecognitionProvider.AUDIO_TAG) {
      val key = savedToken ?: throw RecognitionLimitException("Add your free AudioTag account key in settings.")
      if (!RecognitionPreferences.reserveAudioTagRequest(context)) {
        throw RecognitionLimitException("The monthly free recognition allowance is used up. No audio was uploaded.")
      }
      currentCoroutineContext().ensureActive()
      RecognitionPreferences.requireProvider(context, provider)
      val song = recognizeAudioTag(wavBytes, key)
      RecognitionPreferences.requireProvider(context, provider)
      return@withContext song
    }
    val token = if (savedToken != null) {
      if (!RecognitionPreferences.reserveAudDRequest(context)) {
        throw RecognitionLimitException("The 300 free AudD evaluation requests are used up. No audio was uploaded.")
      }
      savedToken
    } else {
      if (!RecognitionPreferences.reserveTrialRequest(context)) {
        throw RecognitionLimitException("The free trial allowance is used up. Add an account key in settings to continue.")
      }
      "test"
    }
    currentCoroutineContext().ensureActive()
    RecognitionPreferences.requireProvider(context, provider)
    val song = recognizeAudD(wavBytes, token)
    RecognitionPreferences.requireProvider(context, provider)
    song
  }

  private suspend fun recognizeAudD(wavBytes: ByteArray, token: String): RecognizedSong? = coroutineScope {
    RecognitionPreferences.requireProvider(context, RecognitionProvider.AUDD)
    val boundary = "FarragutClock${UUID.randomUUID()}"
    val connection = URL("https://api.audd.io/").openConnection() as HttpURLConnection
    // Cancellation closes a blocked upload/read as well as stopping further work.
    val cancellationWatcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
      try { awaitCancellation() } finally { connection.disconnect() }
    }
    try {
      connection.requestMethod = "POST"
      connection.connectTimeout = 15_000
      connection.readTimeout = 20_000
      connection.instanceFollowRedirects = false
      connection.doOutput = true
      connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
      val prefix = (
        "--$boundary\r\n" +
          "Content-Disposition: form-data; name=\"api_token\"\r\n\r\n$token\r\n" +
          "--$boundary\r\n" +
          "Content-Disposition: form-data; name=\"file\"; filename=\"sample.wav\"\r\n" +
          "Content-Type: audio/wav\r\n\r\n"
        ).toByteArray(Charsets.UTF_8)
      val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
      connection.setFixedLengthStreamingMode(prefix.size + wavBytes.size + suffix.size)
      currentCoroutineContext().ensureActive()
      connection.outputStream.use { output ->
        RecognitionPreferences.requireProvider(context, RecognitionProvider.AUDD)
        output.write(prefix)
        var offset = 0
        while (offset < wavBytes.size) {
          currentCoroutineContext().ensureActive()
          RecognitionPreferences.requireProvider(context, RecognitionProvider.AUDD)
          val count = minOf(16_384, wavBytes.size - offset)
          output.write(wavBytes, offset, count)
          offset += count
        }
        output.write(suffix)
      }
      val status = connection.responseCode
      if (status == 401 || status == 403 || status == 429) {
        throw RecognitionLimitException("The recognition account is unavailable or has reached its limit. Check settings.")
      }
      if (status !in 200..299) {
        error("The recognition service could not complete the test (HTTP $status). Please try later.")
      }
      val body = connection.inputStream.bufferedReader().use { reader ->
        val response = StringBuilder()
        val buffer = CharArray(4096)
        while (true) {
          currentCoroutineContext().ensureActive()
          val count = reader.read(buffer)
          if (count < 0) break
          check(response.length + count <= 262_144) { "Recognition response exceeded limit." }
          response.append(buffer, 0, count)
        }
        response.toString()
      }
      currentCoroutineContext().ensureActive()
      RecognitionPreferences.requireProvider(context, RecognitionProvider.AUDD)
      parseRecognitionResponse(body)
    } catch (exception: Exception) {
      currentCoroutineContext().ensureActive()
      throw exception
    } finally {
      cancellationWatcher.cancel()
      connection.disconnect()
    }
  }
}

internal fun parseRecognitionResponse(body: String): RecognizedSong? {
  val response = JSONObject(body)
  if (response.optString("status") != "success") {
    val apiError = response.optJSONObject("error")
    val code = apiError?.optInt("error_code", -1) ?: -1
    // Do not expose arbitrary server text, which can contain request details.
    if (isAudDAccountLimitError(code)) {
      throw RecognitionLimitException("The recognition key is unavailable or has reached its limit. Check settings.")
    }
    throw IOException("The recognition service could not complete the request (code $code). Please try later.")
  }
  val result = response.optJSONObject("result") ?: return null
  val title = result.optString("title").trim()
  val artist = result.optString("artist").trim()
  return if (title.isNotEmpty()) RecognizedSong(title, artist) else null
}

/** Official SDK groups authentication, quota and subscription errors in this range. */
internal fun isAudDAccountLimitError(code: Int): Boolean = code in 900..905
