package com.example.farragutclock

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Owned by the visible clock session, including a covered YTM recognition handoff. */
internal class LineInRecognitionMonitor(
  private val context: Context,
  private val scope: CoroutineScope,
  private val beginMicrophoneSession: () -> Boolean,
  private val endMicrophoneSession: () -> Unit,
  private val onChanged: () -> Unit,
) {
  private val state = LineInRecognitionState()
  private val client = SongRecognitionClient(context)
  private var target: LineInRecognitionTarget? = null
  private var lastObservationMillis = 0L
  private var captureJob: Job? = null
  private var timerJob: Job? = null
  private var blocked = false
  private var blockedUtcDay = Long.MIN_VALUE
  private var provider = RecognitionPreferences.provider(context)

  var status: String = ""
    private set

  fun start() {
    timerJob = scope.launch {
      while (isActive) {
        tick()
        delay(1_000)
      }
    }
  }

  /** Pass fresh, raw Sonos observations, without the display's pause grace period. */
  fun updatePlayback(playback: List<SonosPlayback>) {
    val now = SystemClock.elapsedRealtime()
    lastObservationMillis = now
    val nextProvider = RecognitionPreferences.provider(context)
    if (nextProvider != provider) {
      captureJob?.cancel()
      captureJob = null
      target = null
      state.clear()
      blocked = false
      status = ""
      endMicrophoneSession()
      provider = nextProvider
    }
    val next = if (RecognitionPreferences.isEnabled(context)) lineInRecognitionTarget(playback) else null
    if (next != target) {
      captureJob?.cancel()
      target = next
      state.updateSource(next?.sourceKey, now)
      status = ""
      if (next == null) endMicrophoneSession()
      onChanged()
    }
  }

  fun displayedPlayback(playback: List<SonosPlayback>): List<SonosPlayback> {
    val active = target ?: return playback
    val song = state.currentSong(SystemClock.elapsedRealtime()) ?: return playback
    return playback.map { item ->
      if (item.isLineInRecognitionEligible && item.sourceUri.trim() == active.sourceUri) {
        item.copy(current = SonosTrack(song.title, song.artist))
      } else item
    }
  }

  private fun tick() {
    val now = SystemClock.elapsedRealtime()
    val utcDay = Math.floorDiv(System.currentTimeMillis(), 86_400_000L)
    if (blocked && utcDay > blockedUtcDay) blocked = false
    if (!RecognitionPreferences.isEnabled(context) || now - lastObservationMillis > 10_000) {
      if (target != null) updatePlayback(emptyList())
      return
    }
    if (target == null) return
    // Expire the visual match even if network polling is currently blocked.
    onChanged()
    if (blocked || captureJob?.isCompleted == false || !state.shouldStart(now)) return
    val attempt = state.beginAttempt(now) ?: return
    val attemptProvider = provider
    captureJob = scope.launch {
      var sample: MicrophoneSample? = null
      try {
        if (!isCurrentAttempt(attempt, attemptProvider)) return@launch
        if (attemptProvider == RecognitionProvider.YOUTUBE_MUSIC) {
          // Google owns listening; the clock neither records nor consumes provider API credits.
          endMicrophoneSession()
          if (!YouTubeMusicBridgeService.isConnected.value) {
            status = "Enable the YouTube Music bridge in Accessibility settings."
            state.failAttempt(attempt, SystemClock.elapsedRealtime())
            return@launch
          }
          status = "Identifying with YouTube Music…"
          onChanged()
          val song = YouTubeMusicBridgeService.recognizeAutomatically(context)
          if (!isActive || !isCurrentAttempt(attempt, attemptProvider)) return@launch
          if (state.completeAttempt(attempt, song, SystemClock.elapsedRealtime())) {
            status = if (song == null) "No match yet. Listening again shortly." else ""
          }
          return@launch
        }
        val available = client.hasAvailableRequest(attemptProvider)
        if (!isActive || !isCurrentAttempt(attempt, attemptProvider)) return@launch
        if (!available) {
          blocked = true
          blockedUtcDay = utcDay
          status = if (RecognitionPreferences.apiToken(context) != null) {
            if (RecognitionPreferences.provider(context) == RecognitionProvider.AUDD) {
              "The 300 free AudD evaluation requests are used up."
            } else {
              "The monthly free recognition allowance is used up."
            }
          } else {
            "Add a recognition key in settings to continue."
          }
          state.failAttempt(attempt, SystemClock.elapsedRealtime())
          return@launch
        }
        if (!isCurrentAttempt(attempt, attemptProvider) || !isActive) return@launch
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED || !beginMicrophoneSession()) {
          blocked = true
          blockedUtcDay = utcDay
          status = "Open settings to allow microphone recognition."
          state.failAttempt(attempt, SystemClock.elapsedRealtime())
          return@launch
        }
        status = "Identifying song…"
        onChanged()
        sample = captureMicrophoneSample(
          context,
          if (attemptProvider == RecognitionProvider.AUDIO_TAG) RecognitionPreferences.AUDIO_TAG_SAMPLE_SECONDS else 10,
          // AudioTag's documented native format avoids server-side WAV conversion.
          preferredSampleRate = if (attemptProvider == RecognitionProvider.AUDIO_TAG) 8_000 else 44_100,
        ) { _, _ -> }
        if (!isCurrentAttempt(attempt, attemptProvider) || !isActive) return@launch
        // Quiet or silenced input does not consume a recognition-service request.
        val tooQuiet = sample.peakDbFS < -65f
        val song = if (tooQuiet) null else client.recognize(sample.wavBytes, attemptProvider)
        if (!isCurrentAttempt(attempt, attemptProvider) || !isActive) return@launch
        if (state.completeAttempt(attempt, song, SystemClock.elapsedRealtime())) {
          status = when {
            tooQuiet -> "Music is too quiet to identify."
            song == null -> "No match yet. Listening again shortly."
            else -> ""
          }
        }
      } catch (exception: CancellationException) {
        state.cancelAttempt(attempt, SystemClock.elapsedRealtime())
        throw exception
      } catch (exception: RecognitionLimitException) {
        if (!isCurrentAttempt(attempt, attemptProvider)) return@launch
        blocked = true
        blockedUtcDay = utcDay
        status = exception.message ?: "Recognition limit reached. Check settings."
        state.failAttempt(attempt, SystemClock.elapsedRealtime())
      } catch (_: Exception) {
        if (!isCurrentAttempt(attempt, attemptProvider)) return@launch
        status = "Recognition will try again shortly."
        state.failAttempt(attempt, SystemClock.elapsedRealtime())
      } finally {
        sample?.wavBytes?.fill(0)
        state.cancelAttempt(attempt, SystemClock.elapsedRealtime())
        onChanged()
      }
    }
  }

  private fun isCurrentAttempt(attempt: LineInRecognitionState.Attempt, attemptProvider: RecognitionProvider): Boolean =
    state.isCurrentAttempt(attempt) && RecognitionPreferences.isEnabled(context) &&
      RecognitionPreferences.provider(context) == attemptProvider &&
      SystemClock.elapsedRealtime() - lastObservationMillis <= 10_000

  fun close() {
    timerJob?.cancel()
    captureJob?.cancel()
    target = null
    state.clear()
    endMicrophoneSession()
  }
}
