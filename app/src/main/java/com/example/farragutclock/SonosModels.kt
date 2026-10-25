package com.example.farragutclock

internal data class SonosRoom(
  val id: String,
  val name: String,
  val host: String,
  val coordinatorId: String = id,
  val coordinatorHost: String = host,
)

internal data class SonosTrack(
  val title: String,
  val artist: String = "",
  val album: String = "",
)

internal data class SonosPlayback(
  val roomNames: List<String>,
  val current: SonosTrack,
  val groupId: String = "",
  val sourceUri: String = "",
) {
  val source: SonosPlaybackSource
    get() = sonosPlaybackSource(sourceUri)

  // roomNames contains only the selected members, even when other rooms share the group.
  val isLineInRecognitionEligible: Boolean
    get() = source == SonosPlaybackSource.LINE_IN && roomNames.any(::isDefaultSonosRoom)

  /** Invalidates an in-flight match when regrouping or switching to another Line-In source. */
  val lineInRecognitionKey: String?
    get() = if (isLineInRecognitionEligible && groupId.isNotBlank() && sourceUri.isNotBlank()) {
      "$groupId|$sourceUri"
    } else {
      null
    }
}

internal enum class SonosPlaybackSource {
  OTHER,
  LINE_IN,
  TV_AUDIO,
}

internal fun sonosPlaybackSource(uri: String): SonosPlaybackSource = when {
  uri.trim().startsWith("x-rincon-stream:", ignoreCase = true) -> SonosPlaybackSource.LINE_IN
  uri.trim().startsWith("x-sonos-htastream:", ignoreCase = true) -> SonosPlaybackSource.TV_AUDIO
  else -> SonosPlaybackSource.OTHER
}

internal fun isDefaultSonosRoom(name: String): Boolean =
  name.substringBefore('(').trim().let {
    it.equals("Living Room", ignoreCase = true) || it.equals("Kitchen", ignoreCase = true)
  }

/** Select individual rooms, then resolve each to its current group exactly once. */
internal fun selectedSonosGroups(
  rooms: List<SonosRoom>,
  selectedRoomIds: Set<String>?,
): List<List<SonosRoom>> =
  rooms.filter { room ->
      selectedRoomIds?.contains(room.id) ?: isDefaultSonosRoom(room.name)
    }
    .groupBy { it.coordinatorId }
    .values
    .map { it.sortedBy(SonosRoom::name) }
    .sortedBy { it.first().name }

/** Missed SSDP replies must not discard hosts already verified on this LAN. */
internal fun sonosHostsAfterDiscovery(discovered: List<String>, verified: List<String>): List<String> =
  discovered.ifEmpty { verified }
