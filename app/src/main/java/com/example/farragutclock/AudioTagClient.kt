package com.example.farragutclock

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONException
import org.json.JSONObject

/** AudioTag API v2.6: free-budget check, one upload, then bounded result polling. */
internal suspend fun recognizeAudioTag(wavBytes: ByteArray, apiKey: String): RecognizedSong? =
  try {
    withTimeout(45_000L) {
      val key = apiKey.trim().takeIf { it.isNotEmpty() }
        ?: throw RecognitionLimitException("Add an AudioTag API key in settings to continue.")
      val account = audioTagFormRequest(mapOf("apikey" to key, "action" to "stat"), AudioTagStage.BUDGET)
      requireAudioTagFreeAllowance(account)

      val boundary = "FarragutAudioTag${UUID.randomUUID()}"
      val fields = mapOf(
        "apikey" to key,
        "action" to "identify",
        "start_time" to "0",
        "time_len" to RecognitionPreferences.AUDIO_TAG_SAMPLE_SECONDS.toString(),
      )
      val prefix = buildString {
        fields.forEach { (name, value) ->
          append("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
        }
        append("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"sample.wav\"\r\n")
        append("Content-Type: audio/wav\r\n\r\n")
      }.toByteArray(Charsets.UTF_8)
      val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
      val uploaded = audioTagRequest("multipart/form-data; boundary=$boundary", listOf(prefix, wavBytes, suffix), AudioTagStage.UPLOAD)
      val token = parseAudioTagUploadToken(uploaded)
      repeat(20) {
        delay(2_000L)
        val resultBody = audioTagFormRequest(mapOf("apikey" to key, "action" to "get_result", "token" to token), AudioTagStage.RESULT)
        when (val result = parseAudioTagResult(resultBody)) {
          AudioTagResult.Waiting -> Unit
          is AudioTagResult.Complete -> return@withTimeout result.song
        }
      }
      throw IOException("AudioTag is taking too long to identify this sample. Please try later.")
    }
  } catch (_: TimeoutCancellationException) {
    // A provider deadline is retryable; actual caller cancellation must still propagate.
    currentCoroutineContext().ensureActive()
    throw IOException("AudioTag is taking too long to identify this sample. Please try later.")
  }

private enum class AudioTagStage(val description: String) {
  BUDGET("checking the free allowance"),
  UPLOAD("uploading the sample"),
  RESULT("waiting for identification"),
}

private suspend fun audioTagFormRequest(fields: Map<String, String>, stage: AudioTagStage): String {
  val body = fields.entries.joinToString("&") { (name, value) ->
    "${URLEncoder.encode(name, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
  }.toByteArray(Charsets.UTF_8)
  return audioTagRequest("application/x-www-form-urlencoded; charset=UTF-8", listOf(body), stage)
}

private suspend fun audioTagRequest(contentType: String, parts: List<ByteArray>, stage: AudioTagStage): String =
  withContext(Dispatchers.IO) {
    coroutineScope {
      val connection = URL("https://audiotag.info/api").openConnection() as HttpURLConnection
      val cancellationWatcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { connection.disconnect() }
      }
      try {
        connection.requestMethod = "POST"
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = false
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", contentType)
        connection.setFixedLengthStreamingMode(parts.sumOf { it.size })
        currentCoroutineContext().ensureActive()
        connection.outputStream.use { output ->
          parts.forEach { bytes ->
            var offset = 0
            while (offset < bytes.size) {
              currentCoroutineContext().ensureActive()
              val count = minOf(16_384, bytes.size - offset)
              output.write(bytes, offset, count)
              offset += count
            }
          }
        }
        val status = connection.responseCode
        if (status == 401 || status == 403) {
          throw RecognitionLimitException("AudioTag could not use this API key. Check the key in settings.")
        }
        if (status !in 200..299) throw IOException("AudioTag is unavailable while ${stage.description} (HTTP $status). Please try later.")
        connection.inputStream.bufferedReader().use { reader ->
          val text = StringBuilder()
          val buffer = CharArray(4_096)
          while (true) {
            currentCoroutineContext().ensureActive()
            val count = reader.read(buffer)
            if (count < 0) break
            if (text.length + count > 262_144) throw IOException("AudioTag returned an oversized response.")
            text.append(buffer, 0, count)
          }
          text.toString()
        }
      } finally {
        cancellationWatcher.cancel()
        connection.disconnect()
      }
    }
  }

internal sealed interface AudioTagResult {
  data object Waiting : AudioTagResult
  data class Complete(val song: RecognizedSong?) : AudioTagResult
}

internal fun requireAudioTagFreeAllowance(body: String) {
  val response = audioTagResponse(body, AudioTagStage.BUDGET)
  val remaining = response.optDouble("identification_free_sec_remainder", Double.NaN)
  if (!remaining.isFinite() || remaining < RecognitionPreferences.AUDIO_TAG_SAMPLE_SECONDS) {
    throw RecognitionLimitException("AudioTag's free allowance is unavailable or used up. No audio was uploaded.")
  }
}

internal fun parseAudioTagUploadToken(body: String): String =
  (audioTagResponse(body, AudioTagStage.UPLOAD).opt("token") as? String)?.trim()?.takeIf { it.isNotEmpty() }
    ?: throw IOException("AudioTag did not accept the sample. Please try later.")

internal fun parseAudioTagResult(body: String): AudioTagResult {
  val response = audioTagJson(body, AudioTagStage.RESULT)
  val noMatchMessages = setOf(
    "no recognition result found for the token",
    "no recognition result found for token",
  )
  // This documented polling error can also mean that the submitted audio had no match.
  // Keep it local to result polling: the same response must not bypass upload/account checks.
  if (!response.optBoolean("success", false) &&
    response.optString("error").trim().lowercase(Locale.ROOT) in noMatchMessages
  ) {
    return AudioTagResult.Complete(null)
  }
  // A not-yet-ready recognition server can be retried using the same already-uploaded job.
  // This stays bounded by the outer 45-second deadline and never causes another upload.
  if (!response.optBoolean("success", false) &&
    response.optString("error").trim().equals("internal error: server is not ready", ignoreCase = true)
  ) {
    return AudioTagResult.Waiting
  }
  requireAudioTagSuccess(response, AudioTagStage.RESULT)
  return when (response.optString("result").trim().lowercase(Locale.ROOT)) {
    "wait" -> AudioTagResult.Waiting
    "not found", "no recognition result found for the token", "no recognition result found for token" -> AudioTagResult.Complete(null)
    "found" -> {
      val data = response.optJSONArray("data") ?: return AudioTagResult.Complete(null)
      val candidates = mutableListOf<Pair<Double, RecognizedSong>>()
      for (index in 0 until data.length()) {
        val candidate = data.optJSONObject(index) ?: continue
        val score = candidate.optDouble("confidence", Double.NaN)
        if (!score.isFinite() || score <= 0) continue
        val tracks = candidate.optJSONArray("tracks") ?: continue
        // Prefer the first complete title/artist entry; alternatives can be compilation metadata.
        for (trackIndex in 0 until tracks.length()) {
          val track = tracks.optJSONArray(trackIndex) ?: continue
          val title = (track.opt(0) as? String).orEmpty().trim()
          val artist = (track.opt(1) as? String).orEmpty().trim()
          if (title.isNotEmpty() && artist.isNotEmpty()) {
            candidates += score to RecognizedSong(title, artist)
            break
          }
        }
      }
      val bestScore = candidates.maxOfOrNull { it.first } ?: return AudioTagResult.Complete(null)
      val best = candidates.filter { it.first == bestScore }.map { it.second }
      val identities = best.map { it.title.lowercase(Locale.ROOT) to it.artist.lowercase(Locale.ROOT) }.distinct()
      // Confidence is an uncalibrated score (official examples exceed 100), not a probability.
      AudioTagResult.Complete(if (identities.size == 1) best.first() else null)
    }
    else -> throw IOException("AudioTag returned an unexpected recognition result. Please try later.")
  }
}

private fun audioTagJson(body: String, stage: AudioTagStage): JSONObject =
  try { JSONObject(body) } catch (_: JSONException) {
    throw IOException("AudioTag returned an invalid response while ${stage.description}. Please try later.")
  }

private fun audioTagResponse(body: String, stage: AudioTagStage): JSONObject =
  requireAudioTagSuccess(audioTagJson(body, stage), stage)

private fun requireAudioTagSuccess(response: JSONObject, stage: AudioTagStage): JSONObject {
  if (!response.optBoolean("success", false)) {
    val message = response.optString("error").trim().lowercase(Locale.ROOT)
    val accountError = message in setOf(
      "api key expired", "api key inactive", "api key unrecognized", "api key invalid or empty",
      "credit balance exhausted", "credit balance insufficient or exhausted",
    )
    if (accountError) {
      throw RecognitionLimitException("AudioTag's API key or free allowance is unavailable. Check settings.")
    }
    // Only exact documented errors receive explanations; never show arbitrary server text.
    val explanation = when (message) {
      "invalid command or parameter" -> "The service rejected the request format."
      "api key stats not found" -> "Account statistics are not available yet."
      "service temporarily not available" -> "The service is temporarily unavailable."
      "internal error: no server response" -> "A recognition server did not respond."
      "internal error: server init error" -> "A recognition server could not start."
      "internal error" -> "The service reported an internal error."
      "no audio found, format invalid or unsupported" -> "The service could not read the audio file."
      "audio duration is too short", "audio is too short" -> "The service considered the recording too short."
      "internal error: could not communicate to the recognition server" -> "The service could not reach its recognition server."
      "internal error: could not process the file" -> "The service could not process the audio file."
      "internal error: server recognition error" -> "The recognition server failed."
      "internal error: server is not ready" -> "The recognition server is not ready."
      else -> "The service returned an unrecognized error."
    }
    throw IOException("AudioTag failed while ${stage.description}. $explanation Please try later.")
  }
  return response
}
