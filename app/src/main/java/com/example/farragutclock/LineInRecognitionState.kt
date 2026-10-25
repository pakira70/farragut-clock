package com.example.farragutclock

/**
 * Timing and source isolation for microphone recognition. The caller owns capture/network work
 * and accesses this state from one thread, using a monotonic clock such as elapsedRealtime.
 */
internal class LineInRecognitionState {
  @ConsistentCopyVisibility
  internal data class Attempt internal constructor(
    val sourceKey: String,
    val startedAtMillis: Long,
    internal val generation: Long,
    internal val id: Long,
  )

  private var sourceKey: String? = null
  private var generation = 0L
  private var nextAttemptId = 0L
  private var nextAttemptMillis = 0L
  private var pending: Attempt? = null
  private var song: RecognizedSong? = null
  private var songExpiresMillis = 0L

  /** Null means no eligible Line-In source is currently playing. */
  fun updateSource(sourceKey: String?, nowMillis: Long) {
    val nextSource = sourceKey?.takeIf { it.isNotBlank() }
    if (this.sourceKey == nextSource) return
    clear()
    this.sourceKey = nextSource
    nextAttemptMillis = nowMillis
  }

  fun shouldStart(nowMillis: Long): Boolean =
    sourceKey != null && pending == null && nowMillis >= nextAttemptMillis

  /** Returning null prevents overlapping capture or starting before the next check is due. */
  fun beginAttempt(nowMillis: Long): Attempt? {
    if (!shouldStart(nowMillis)) return null
    return Attempt(checkNotNull(sourceKey), nowMillis, generation, nextAttemptId++).also {
      pending = it
      nextAttemptMillis = nowMillis + CHECK_INTERVAL_MILLIS
    }
  }

  /**
   * A no-match response removes the previous song immediately. Results from an old source or
   * a superseded attempt are ignored. Freshness starts at capture, not at the server response.
   */
  fun completeAttempt(attempt: Attempt, song: RecognizedSong?, nowMillis: Long): Boolean {
    if (!isCurrentAttempt(attempt)) return false
    pending = null
    songExpiresMillis = attempt.startedAtMillis + MATCH_MAX_AGE_MILLIS
    this.song = song?.takeIf { it.title.isNotBlank() && nowMillis < songExpiresMillis }
    return true
  }

  /** A service/capture failure backs off independently of the normal recognition cadence. */
  fun failAttempt(attempt: Attempt, nowMillis: Long): Boolean {
    if (!isCurrentAttempt(attempt)) return false
    pending = null
    nextAttemptMillis = nowMillis + FAILURE_BACKOFF_MILLIS
    // A previously confirmed song may remain until its freshness window expires.
    currentSong(nowMillis)
    return true
  }

  /** Cancelling a capture never accepts a partial sample or immediately loops another capture. */
  fun cancelAttempt(attempt: Attempt, nowMillis: Long): Boolean {
    if (!isCurrentAttempt(attempt)) return false
    pending = null
    nextAttemptMillis = maxOf(nextAttemptMillis, nowMillis + CHECK_INTERVAL_MILLIS)
    currentSong(nowMillis)
    return true
  }

  fun currentSong(nowMillis: Long): RecognizedSong? {
    if (song != null && nowMillis >= songExpiresMillis) song = null
    return song
  }

  /** Invalidates every outstanding attempt, including when the same source later restarts. */
  fun clear() {
    generation++
    sourceKey = null
    pending = null
    song = null
    songExpiresMillis = 0L
    nextAttemptMillis = 0L
  }

  /** Also checked after recording, before uploading a sample to the recognition service. */
  fun isCurrentAttempt(attempt: Attempt): Boolean =
    attempt.generation == generation && attempt.sourceKey == sourceKey && pending == attempt

  internal companion object {
    const val CHECK_INTERVAL_MILLIS = 60_000L
    const val MATCH_MAX_AGE_MILLIS = 90_000L
    const val FAILURE_BACKOFF_MILLIS = 120_000L
  }
}

internal data class LineInRecognitionTarget(val sourceKey: String, val sourceUri: String)

/** A room microphone cannot attribute a match when the followed rooms play different inputs. */
internal fun lineInRecognitionTarget(playback: List<SonosPlayback>): LineInRecognitionTarget? {
  val eligible = playback.filter { it.isLineInRecognitionEligible }
  if (eligible.isEmpty()) return null
  val sourceUris = eligible.map { it.sourceUri.trim() }.distinct()
  if (sourceUris.size != 1) return null
  val keys = eligible.map { it.lineInRecognitionKey ?: return null }.distinct().sorted()
  return LineInRecognitionTarget(sourceKey = keys.joinToString("\n"), sourceUri = sourceUris.single())
}
