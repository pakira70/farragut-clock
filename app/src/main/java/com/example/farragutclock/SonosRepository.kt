package com.example.farragutclock

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** Read-only local Sonos monitoring. No account, cloud service, or playback changes. */
internal class SonosRepository(context: Context) {
  private val applicationContext = context.applicationContext
  private val topologyMutex = Mutex()
  private var knownHosts = emptyList<String>()
  private var networkSignature = ""
  private var lastDiscoveryMillis = 0L
  private val whyyPrograms = WhyyProgramRepository()

  suspend fun discoverRooms(): List<SonosRoom> = withContext(Dispatchers.IO) {
    topologyMutex.withLock { loadRooms(forceDiscovery = true) }
  }

  suspend fun refreshPlayback(selectedRoomIds: Set<String>?): List<SonosPlayback> =
    withContext(Dispatchers.IO) {
      if (selectedRoomIds?.isEmpty() == true) return@withContext emptyList()
      val rooms = topologyMutex.withLock { loadRooms(forceDiscovery = false) }
      val groups = selectedSonosGroups(rooms, selectedRoomIds)
      val results = coroutineScope {
        groups.map { selectedRooms ->
          async {
            try {
              Result.success(readPlayback(selectedRooms))
            } catch (exception: CancellationException) {
              throw exception
            } catch (exception: Exception) {
              Result.failure<SonosPlayback?>(exception)
            }
          }
        }.awaitAll()
      }
      if (results.isNotEmpty() && results.all { it.isFailure }) {
        throw results.first().exceptionOrNull()!!
      }
      results.mapNotNull { it.getOrNull() }
    }

  private suspend fun loadRooms(forceDiscovery: Boolean): List<SonosRoom> {
    currentCoroutineContext().ensureActive()
    val signature = localNetworkSignature()
    if (signature != networkSignature) {
      knownHosts = emptyList()
      lastDiscoveryMillis = 0L
      networkSignature = signature
    }
    val now = System.nanoTime() / 1_000_000L
    if (forceDiscovery || knownHosts.isEmpty() || now - lastDiscoveryMillis >= DISCOVERY_CACHE_MILLIS) {
      // A network change cleared old seeds above. A missed multicast reply on the same
      // LAN should not discard reachable speakers; topology requests verify the seeds.
      knownHosts = sonosHostsAfterDiscovery(discoverHosts(), knownHosts)
      lastDiscoveryMillis = now
    }
    if (knownHosts.isEmpty()) return emptyList()

    var lastFailure: Exception? = null
    for (host in knownHosts.take(MAX_TOPOLOGY_ATTEMPTS)) {
      currentCoroutineContext().ensureActive()
      try {
        val response = soap(host, "ZoneGroupTopology", "GetZoneGroupState")
        val rooms = SonosXml.rooms(SonosXml.value(response, "ZoneGroupState"))
        if (rooms.isNotEmpty()) {
          knownHosts = (rooms.map { it.coordinatorHost } + rooms.map { it.host }).distinct()
          return rooms
        }
      } catch (exception: CancellationException) {
        throw exception
      } catch (exception: Exception) {
        lastFailure = exception
      }
    }
    knownHosts = emptyList()
    if (lastFailure != null) throw lastFailure
    return emptyList()
  }

  private suspend fun readPlayback(rooms: List<SonosRoom>): SonosPlayback? {
    val host = rooms.first().coordinatorHost
    val transport = soap(host, "AVTransport", "GetTransportInfo", INSTANCE_ID)
    if (SonosXml.value(transport, "CurrentTransportState") != "PLAYING") return null
    val position = soap(host, "AVTransport", "GetPositionInfo", INSTANCE_ID)
    val positionUri = SonosXml.value(position, "TrackURI")
    val positionMetadata = SonosXml.value(position, "TrackMetaData")
    val positionTrack = SonosXml.track(positionMetadata, positionUri)
    val media = if (positionTrack == null || positionUri.isBlank() || SonosXml.isRadioUri(positionUri)) {
      optionalSoap(host, "AVTransport", "GetMediaInfo", INSTANCE_ID)
    } else {
      null
    }
    val uri = positionUri.ifBlank { media?.let { SonosXml.value(it, "CurrentURI") }.orEmpty() }
    val stationMetadata = media?.let { SonosXml.value(it, "CurrentURIMetaData") }.orEmpty()
    var current = SonosXml.radioTrack(positionMetadata, stationMetadata, uri)
      ?: SonosTrack(when (sonosPlaybackSource(uri)) {
          SonosPlaybackSource.TV_AUDIO -> "TV Audio"
          SonosPlaybackSource.LINE_IN -> "Line-in"
          SonosPlaybackSource.OTHER -> "Sonos audio"
        })
    if (positionUri.isNotBlank() && SonosXml.needsWhyyProgram(positionMetadata, stationMetadata, uri)) {
      val program = whyyPrograms.currentProgram()
      currentCoroutineContext().ensureActive()
      // A lookup may outlast a station/source change. Never attach WHYY's program to its successor.
      val refreshed = soap(host, "AVTransport", "GetPositionInfo", INSTANCE_ID)
      if (SonosXml.value(refreshed, "TrackURI") != positionUri) return null
      current = SonosXml.radioTrack(SonosXml.value(refreshed, "TrackMetaData"), stationMetadata, uri, program)
        ?: return null
    }
    // A paused track must not enter playback mode if it stopped during metadata requests.
    val latest = soap(host, "AVTransport", "GetTransportInfo", INSTANCE_ID)
    if (SonosXml.value(latest, "CurrentTransportState") != "PLAYING") return null
    return SonosPlayback(
      roomNames = rooms.map(SonosRoom::name),
      current = current,
      groupId = rooms.first().coordinatorId,
      sourceUri = uri,
    )
  }

  private suspend fun optionalSoap(host: String, service: String, action: String, arguments: String): Document? =
    try {
      soap(host, service, action, arguments)
    } catch (exception: CancellationException) {
      throw exception
    } catch (_: Exception) {
      null
    }

  private suspend fun soap(host: String, service: String, action: String, arguments: String = ""): Document {
    currentCoroutineContext().ensureActive()
    require(isPrivateIpv4(host)) { "Sonos endpoint must be on the local network" }
    val path = when (service) {
      "AVTransport" -> "MediaRenderer/AVTransport/Control"
      "ZoneGroupTopology" -> "ZoneGroupTopology/Control"
      else -> error("Unsupported Sonos service")
    }
    val serviceType = "urn:schemas-upnp-org:service:$service:1"
    val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
      "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
      "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
      "<u:$action xmlns:u=\"$serviceType\">$arguments</u:$action></s:Body></s:Envelope>"
    val connection = URL("http://$host:1400/$path").openConnection() as HttpURLConnection
    try {
      connection.connectTimeout = NETWORK_TIMEOUT_MILLIS
      connection.readTimeout = NETWORK_TIMEOUT_MILLIS
      connection.instanceFollowRedirects = false
      connection.requestMethod = "POST"
      connection.doOutput = true
      connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
      connection.setRequestProperty("SOAPACTION", "\"$serviceType#$action\"")
      connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
      if (connection.responseCode !in 200..299) throw IOException("Sonos returned HTTP ${connection.responseCode}")
      val xml = connection.inputStream.bufferedReader().use { reader ->
        val buffer = CharArray(MAX_XML_CHARS + 1)
        var count = 0
        while (count < buffer.size) {
          currentCoroutineContext().ensureActive()
          val read = reader.read(buffer, count, buffer.size - count)
          if (read < 0) break
          count += read
        }
        require(count <= MAX_XML_CHARS) { "Sonos response exceeded limit" }
        String(buffer, 0, count)
      }
      currentCoroutineContext().ensureActive()
      return SonosXml.document(xml)
    } finally {
      connection.disconnect()
    }
  }

  private suspend fun discoverHosts(): List<String> {
    val manager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    val lock = manager?.createMulticastLock("farragut-sonos-discovery")?.apply {
      setReferenceCounted(false)
    }
    try {
      lock?.acquire()
      val hosts = linkedSetOf<String>()
      DatagramSocket().use { socket ->
        socket.soTimeout = 250
        val message = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
          "MAN: \"ssdp:discover\"\r\nMX: 1\r\nST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n\r\n"
        val bytes = message.toByteArray(Charsets.US_ASCII)
        repeat(2) { socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("239.255.255.250"), 1900)) }
        val deadline = System.nanoTime() + DISCOVERY_TIMEOUT_MILLIS * 1_000_000L
        while (System.nanoTime() < deadline && hosts.size < MAX_HOSTS) {
          currentCoroutineContext().ensureActive()
          val packet = DatagramPacket(ByteArray(8192), 8192)
          try {
            socket.receive(packet)
            val response = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
            SonosXml.discoveryHost(response)?.let(hosts::add)
          } catch (_: SocketTimeoutException) {
            // Periodically check cancellation while waiting for unicast SSDP responses.
          }
        }
      }
      return hosts.toList()
    } finally {
      if (lock?.isHeld == true) lock.release()
    }
  }

  private fun localNetworkSignature(): String =
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
      .filter { it.isUp && !it.isLoopback }
      .flatMap { network -> network.inetAddresses.toList().filterIsInstance<Inet4Address>().map { "${network.name}:${it.hostAddress}" } }
      .sorted().joinToString("|")

  companion object {
    const val POLL_INTERVAL_MILLIS = 3_000L
    private const val DISCOVERY_CACHE_MILLIS = 60_000L
    private const val DISCOVERY_TIMEOUT_MILLIS = 2_000L
    private const val NETWORK_TIMEOUT_MILLIS = 2_000
    private const val MAX_HOSTS = 32
    private const val MAX_TOPOLOGY_ATTEMPTS = 3
    private const val MAX_XML_CHARS = 256 * 1024
    private const val INSTANCE_ID = "<InstanceID>0</InstanceID>"
  }
}

/** Namespace-aware parsing shared with JVM tests; rejects DTDs and external entities. */
internal object SonosXml {
  fun document(xml: String): Document {
    require(xml.length <= 256 * 1024 && !xml.contains("<!DOCTYPE", ignoreCase = true)) { "Unsupported Sonos XML" }
    val factory = DocumentBuilderFactory.newInstance().apply {
      isNamespaceAware = true
      isExpandEntityReferences = false
    }
    return factory.newDocumentBuilder().apply {
      setEntityResolver { _, _ -> InputSource(StringReader("")) }
    }.parse(InputSource(StringReader(xml)))
  }

  fun value(document: Document, name: String): String =
    document.getElementsByTagNameNS("*", name).item(0)?.textContent?.trim().orEmpty()

  fun rooms(xml: String): List<SonosRoom> {
    val result = mutableListOf<SonosRoom>()
    val groups = document(xml).getElementsByTagNameNS("*", "ZoneGroup")
    for (index in 0 until groups.length) {
      val group = groups.item(index) as Element
      val coordinatorId = group.getAttribute("Coordinator")
      val members = group.getElementsByTagNameNS("*", "ZoneGroupMember")
      val coordinator = (0 until members.length).map { members.item(it) as Element }
        .firstOrNull { it.getAttribute("UUID") == coordinatorId } ?: continue
      val coordinatorHost = locationHost(coordinator.getAttribute("Location")) ?: continue
      for (memberIndex in 0 until members.length) {
        val member = members.item(memberIndex) as Element
        if (member.getAttribute("Invisible") == "1" || member.getAttribute("IsZoneBridge") == "1") continue
        val id = member.getAttribute("UUID").takeIf(String::isNotBlank) ?: continue
        val name = member.getAttribute("ZoneName").takeIf(String::isNotBlank) ?: continue
        val host = locationHost(member.getAttribute("Location")) ?: continue
        result += SonosRoom(id, name, host, coordinatorId, coordinatorHost)
      }
    }
    return result.distinctBy(SonosRoom::id).sortedBy { it.name.lowercase() }
  }

  fun track(xml: String, sourceUri: String = ""): SonosTrack? {
    if (xml.isBlank() || xml == "NOT_IMPLEMENTED") return null
    return try {
      val metadata = document(xml)
      // Queue metadata can contain multiple items. Do not attach the next episode's series.
      val item = metadata.getElementsByTagNameNS("*", "item").item(0) as? Element ?: metadata.documentElement
      fun field(name: String): String = item.getElementsByTagNameNS("*", name).item(0)
        ?.textContent?.trim().orEmpty()
      val stream = field("streamContent").takeUnless { it.isBlank() || it.startsWith("ZPSTR_") }
      val artist = field("creator").ifBlank { field("artist") }
      val title = field("title")
      val album = field("album")
      val series = podcastSeries(item, sourceUri)
      val track = when {
        stream != null && artist.isBlank() && stream.contains(" - ") ->
          SonosTrack(stream.substringAfter(" - "), stream.substringBefore(" - "), album)
        stream != null && artist.isBlank() -> SonosTrack(stream, album = album)
        title.isNotBlank() -> SonosTrack(title, artist, album)
        else -> null
      }
      track?.copy(podcastSeries = series)
    } catch (_: Exception) {
      null
    }
  }

  fun isRadioUri(sourceUri: String): Boolean = sourceUri.trim().let { uri ->
    listOf("x-rincon-mp3radio://", "x-sonosapi-stream:", "x-sonosapi-radio:")
      .any { uri.startsWith(it, ignoreCase = true) }
  }

  /** Rich song/show/podcast metadata wins; only a verified resource basename is a placeholder. */
  fun radioTrack(
    trackMetadata: String,
    stationMetadata: String,
    sourceUri: String,
    fallbackProgram: String? = null,
  ): SonosTrack? {
    val current = track(trackMetadata, sourceUri) ?: track(stationMetadata, sourceUri) ?: return null
    val station = typedRadioStation(stationMetadata) ?: return current
    if (!isTechnicalRadioTitle(current, trackMetadata, sourceUri)) return current
    val program = radioShow(trackMetadata).ifBlank {
      if (isWhyySource(sourceUri, station)) fallbackProgram?.trim().orEmpty() else ""
    }
    return if (program.isNotBlank()) current.copy(title = program, radioStation = station)
      else current.copy(title = station)
  }

  fun needsWhyyProgram(trackMetadata: String, stationMetadata: String, sourceUri: String): Boolean {
    val station = typedRadioStation(stationMetadata) ?: return false
    val current = track(trackMetadata, sourceUri) ?: return false
    return isWhyySource(sourceUri, station) && isTechnicalRadioTitle(current, trackMetadata, sourceUri) &&
      radioShow(trackMetadata).isBlank()
  }

  private fun typedRadioStation(xml: String): String? {
    return try {
      val item = metadataItem(xml) ?: return null
      val type = metadataField(item, "class")
      if (!type.equals("object.item.audioItem.audioBroadcast", ignoreCase = true)) return null
      metadataField(item, "title").takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }
  }

  private fun isTechnicalRadioTitle(current: SonosTrack, metadata: String, sourceUri: String): Boolean {
    if (!isRadioUri(sourceUri) || current.artist.isNotBlank() || current.album.isNotBlank() ||
      current.podcastSeries.isNotBlank()) return false
    val item = try { metadataItem(metadata) } catch (_: Exception) { null } ?: return false
    val stream = metadataField(item, "streamContent")
    if (stream.isNotBlank() && !stream.startsWith("ZPSTR_")) return false
    val basename = radioUrl(sourceUri)?.path?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: return false
    return current.title.equals(basename, ignoreCase = true)
  }

  private fun isWhyySource(sourceUri: String, station: String): Boolean {
    if (!station.equals("WHYY", ignoreCase = true)) return false
    val url = radioUrl(sourceUri) ?: return false
    return url.scheme.equals("https", ignoreCase = true) && url.host.equals("whyy.streamguys1.com", ignoreCase = true) &&
      url.rawPath == "/whyy_tunein" && url.userInfo == null && url.fragment == null &&
      (url.port == -1 || url.port == 443)
  }

  private fun radioUrl(sourceUri: String): URI? = try {
    val source = sourceUri.trim()
    if (!source.startsWith("x-rincon-mp3radio://", ignoreCase = true)) null
    else URI(source.substring("x-rincon-mp3radio://".length))
      .takeIf { it.scheme.equals("https", true) || it.scheme.equals("http", true) }
  } catch (_: Exception) { null }

  private fun radioShow(xml: String): String {
    return try {
      val item = metadataItem(xml) ?: return ""
      val show = metadataField(item, "radioShowMd")
      // Sonos may append the service's program ID after a comma; preserve commas in actual names.
      show.takeUnless { it.startsWith("ZPSTR_") }.orEmpty().replace(Regex(",\\s*p[0-9]+$"), "").trim()
    } catch (_: Exception) { "" }
  }

  private fun metadataItem(xml: String): Element? {
    if (xml.isBlank() || xml == "NOT_IMPLEMENTED") return null
    val metadata = document(xml)
    return metadata.getElementsByTagNameNS("*", "item").item(0) as? Element ?: metadata.documentElement
  }

  private fun metadataField(item: Element, name: String): String =
    item.getElementsByTagNameNS("*", name).item(0)?.textContent?.trim().orEmpty()

  private fun podcastSeries(item: Element, sourceUri: String): String {
    fun field(name: String): String = item.getElementsByTagNameNS("*", name).item(0)
      ?.textContent?.trim().orEmpty()
    // Sonos SMAPI defines podcast separately from album and the individual episode title.
    val explicitSeries = field("podcast")
    if (explicitSeries.isNotBlank()) return explicitSeries
    val semanticType = field("semanticType").lowercase(Locale.ROOT)
    val itemClass = field("class").lowercase(Locale.ROOT)
    val podcastClass = itemClass.startsWith("object.item.audioitem.") &&
      (itemClass.split('.').contains("podcast") || itemClass.endsWith(".recentshow"))
    val isPodcast = semanticType == "episode.podcast" || semanticType == "podcast" ||
      podcastClass || isSpotifyEpisode(sourceUri) || isSpotifyEpisode(field("res"))
    if (!isPodcast) return ""
    val album = field("album")
    if (album.isNotBlank()) return album
    // Sonos recorded-show DIDL may put its series in the Rincon description instead of album.
    // Generic dc:description is often an episode summary, so it is deliberately not a fallback.
    return item.getElementsByTagNameNS("urn:schemas-rinconnetworks-com:metadata-1-0/", "description")
      .item(0)?.textContent?.trim().orEmpty()
  }

  private fun isSpotifyEpisode(sourceUri: String): Boolean {
    val uri = sourceUri.trim()
    val spotifyUri = when {
      uri.startsWith("spotify:", ignoreCase = true) -> uri
      uri.startsWith("x-sonos-spotify:", ignoreCase = true) -> try {
        URLDecoder.decode(uri.substringAfter(':'), "UTF-8")
      } catch (_: IllegalArgumentException) { return false }
      else -> return false
    }
    return Regex("^spotify:episode:[A-Za-z0-9]+(?:[?&#].*)?$", RegexOption.IGNORE_CASE)
      .matches(spotifyUri)
  }

  fun discoveryHost(response: String): String? {
    if (!response.startsWith("HTTP/1.1 200", ignoreCase = true)) return null
    val location = response.lineSequence().firstOrNull { it.startsWith("location:", ignoreCase = true) }
      ?.substringAfter(':')?.trim() ?: return null
    return locationHost(location)
  }

  private fun locationHost(location: String): String? = try {
    val url = URL(location)
    url.host.takeIf { url.protocol == "http" && (url.port == 1400 || url.port == -1) && isPrivateIpv4(it) }
  } catch (_: Exception) {
    null
  }
}

private fun isPrivateIpv4(host: String): Boolean {
  val parts = host.split('.').map { it.toIntOrNull() ?: return false }
  if (parts.size != 4 || parts.any { it !in 0..255 }) return false
  return parts[0] == 10 || (parts[0] == 172 && parts[1] in 16..31) ||
    (parts[0] == 192 && parts[1] == 168) || (parts[0] == 169 && parts[1] == 254)
}
