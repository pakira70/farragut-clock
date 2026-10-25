package com.example.farragutclock

import java.util.Collections

/** Keeps a tap's display choice for the duration of uninterrupted Sonos playback. */
internal class SonosDisplayState {
  var playback: List<SonosPlayback> = emptyList()
    private set

  val isPlaying: Boolean
    get() = playback.isNotEmpty()

  val isSplit: Boolean
    get() = isPlaying && !fullClockRequested

  private var fullClockRequested = false
  private var emptySinceMillis: Long? = null

  /** [nowMillis] must come from a monotonic clock, such as elapsedRealtime. */
  fun update(items: List<SonosPlayback>, nowMillis: Long) {
    if (items.isNotEmpty()) {
      playback =
        Collections.unmodifiableList(
          items.map { item ->
            item.copy(roomNames = Collections.unmodifiableList(item.roomNames.toList()))
          },
        )
      emptySinceMillis = null
      return
    }

    if (!isPlaying) return

    val emptySince = emptySinceMillis
    if (emptySince == null) {
      emptySinceMillis = nowMillis
    } else if (nowMillis - emptySince >= STOP_GRACE_MILLIS) {
      clear()
    }
  }

  fun toggle() {
    if (isPlaying) fullClockRequested = !fullClockRequested
  }

  /** Also used when room selection changes, so deselected rooms disappear immediately. */
  fun clear() {
    playback = emptyList()
    fullClockRequested = false
    emptySinceMillis = null
  }

  private companion object {
    const val STOP_GRACE_MILLIS = 6_000L
  }
}
