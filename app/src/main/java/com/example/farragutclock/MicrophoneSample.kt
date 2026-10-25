package com.example.farragutclock

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class MicrophoneSample(
  val wavBytes: ByteArray,
  val durationSeconds: Float,
  val rmsDbFS: Float,
  val peakDbFS: Float,
  val clippingPercent: Float,
)

/** One bounded foreground recording; samples never touch persistent storage. */
@SuppressLint("MissingPermission") // Checked immediately before opening AudioRecord.
internal suspend fun captureMicrophoneSample(
  context: Context,
  durationSeconds: Int,
  preferredSampleRate: Int = 44_100,
  onProgress: (seconds: Int, levelDb: Float) -> Unit,
): MicrophoneSample = withContext(Dispatchers.IO) {
  require(durationSeconds in 1..30)
  check(
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
      PackageManager.PERMISSION_GRANTED
  ) { "Allow microphone access before starting the test." }

  val audioManager = context.getSystemService(AudioManager::class.java)
  val builtInMic =
    audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
      .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
      ?: error("The tablet's built-in microphone is unavailable.")
  // Android recommends VOICE_RECOGNITION when raw input is unavailable: it avoids
  // the speech noise suppression and automatic gain control that can alter music.
  val source =
    if (audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") {
      MediaRecorder.AudioSource.UNPROCESSED
    } else {
      MediaRecorder.AudioSource.VOICE_RECOGNITION
    }
  val (recorder, sampleRate) = openMicrophoneRecorder(source, preferredSampleRate)
  val samples = ShortArray(sampleRate * durationSeconds)
  try {
    check(recorder.setPreferredDevice(builtInMic)) { "Could not select the tablet microphone." }
    recorder.startRecording()
    check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
      "The microphone could not start. Check whether another app is using it."
    }
    val startedAt = SystemClock.elapsedRealtime()
    var recorded = 0
    onProgress(0, -96f)
    while (recorded < samples.size) {
      currentCoroutineContext().ensureActive()
      check(SystemClock.elapsedRealtime() - startedAt < (durationSeconds + 5) * 1_000L) {
        "The microphone stopped delivering audio. Please try again."
      }
      // Nonblocking reads plus a short suspending wait keep cancellation responsive.
      val count =
        recorder.read(
          samples,
          recorded,
          minOf(sampleRate / 10, samples.size - recorded),
          AudioRecord.READ_NON_BLOCKING,
        )
      check(count >= 0) { "The microphone disconnected or could not read audio ($count)." }
      if (count == 0) {
        delay(20)
      } else {
        val levels = pcm16Levels(samples, recorded, count)
        recorded += count
        onProgress(recorded / sampleRate, levels.rmsDbFS)
      }
    }
    val levels = pcm16Levels(samples)
    MicrophoneSample(
      wavBytes = pcm16Wav(samples, sampleRate),
      durationSeconds = samples.size.toFloat() / sampleRate,
      rmsDbFS = levels.rmsDbFS,
      peakDbFS = levels.peakDbFS,
      clippingPercent = levels.clippingPercent,
    )
  } finally {
    try {
      if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
    } finally {
      recorder.release()
      samples.fill(0)
    }
  }
}

@SuppressLint("MissingPermission")
private fun openMicrophoneRecorder(source: Int, preferredSampleRate: Int): Pair<AudioRecord, Int> {
  for (sampleRate in listOf(preferredSampleRate, 44_100, 48_000, 16_000).distinct()) {
    val minimum =
      AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
      )
    if (minimum <= 0) continue
    val recorder =
      try {
        AudioRecord.Builder()
          .setAudioSource(source)
          .setAudioFormat(
            AudioFormat.Builder()
              .setSampleRate(sampleRate)
              .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
              .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
              .build()
          )
          .setBufferSizeInBytes(maxOf(minimum * 2, sampleRate / 5 * 2))
          .build()
      } catch (_: IllegalArgumentException) {
        continue
      } catch (_: UnsupportedOperationException) {
        continue
      }
    if (recorder.state == AudioRecord.STATE_INITIALIZED) return recorder to sampleRate
    recorder.release()
  }
  error("The tablet cannot open a supported microphone recording format.")
}

internal data class MicrophoneLevels(
  val rmsDbFS: Float,
  val peakDbFS: Float,
  val clippingPercent: Float,
)

internal fun pcm16Levels(
  samples: ShortArray,
  offset: Int = 0,
  count: Int = samples.size - offset,
): MicrophoneLevels {
  require(offset >= 0 && count >= 0 && offset <= samples.size - count)
  var squared = 0.0
  var peak = 0
  var clipped = 0
  for (index in offset until offset + count) {
    val value = samples[index].toInt()
    val magnitude = abs(value)
    squared += value.toDouble() * value
    peak = maxOf(peak, magnitude)
    if (magnitude >= 32_760) clipped++
  }
  fun dbFS(amplitude: Double): Float =
    if (amplitude <= 0.0) -96f else (20 * log10(amplitude / 32_768.0)).toFloat().coerceAtLeast(-96f)
  return MicrophoneLevels(
    rmsDbFS = dbFS(if (count == 0) 0.0 else sqrt(squared / count)),
    peakDbFS = dbFS(peak.toDouble()),
    clippingPercent = if (count == 0) 0f else 100f * clipped / count,
  )
}

/** PCM WAV is accepted by the recognition service and preserves the measured signal. */
internal fun pcm16Wav(samples: ShortArray, sampleRate: Int): ByteArray {
  require(sampleRate > 0)
  val dataLength = samples.size * 2
  return ByteBuffer.allocate(44 + dataLength).order(ByteOrder.LITTLE_ENDIAN).apply {
    put("RIFF".toByteArray(Charsets.US_ASCII))
    putInt(36 + dataLength)
    put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
    putInt(16)
    putShort(1.toShort()) // PCM
    putShort(1.toShort()) // mono
    putInt(sampleRate)
    putInt(sampleRate * 2)
    putShort(2.toShort())
    putShort(16.toShort())
    put("data".toByteArray(Charsets.US_ASCII))
    putInt(dataLength)
    samples.forEach { putShort(it) }
  }.array()
}
