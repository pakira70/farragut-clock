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
import javax.xml.parsers.DocumentBuilderFactory

/** Read-only local Sonos monitoring. No account, cloud service, or playback changes. */
internal class SonosRepository(context: Context) {
  private val applicationContext = context.applicationContext
  private val topologyMutex = Mutex()
  private var knownHosts = emptyList<String>()
  private var networkSignature = ""
  private var lastDiscoveryMillis = 0L

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
    val positionTrack = SonosXml.track(SonosXml.value(position, "TrackMetaData"))
    val positionUri = SonosXml.value(position, "TrackURI")
    val media = if (positionTrack == null || positionUri.isBlank()) {
      optionalSoap(host, "AVTransport", "GetMediaInfo", INSTANCE_ID)
    } else {
      null
    }
    val uri = positionUri.ifBlank { media?.let { SonosXml.value(it, "CurrentURI") }.orEmpty() }
    val current = positionTrack
      ?: media?.let { SonosXml.track(SonosXml.value(it, "CurrentURIMetaData")) }
      ?: SonosTrack(when (sonosPlaybackSource(uri)) {
          SonosPlaybackSource.TV_AUDIO -> "TV Audio"
          SonosPlaybackSource.LINE_IN -> "Line-in"
          SonosPlaybackSource.OTHER -> "Sonos audio"
        })
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

  fun track(xml: String): SonosTrack? {
    if (xml.isBlank() || xml == "NOT_IMPLEMENTED") return null
    return try {
      val metadata = document(xml)
      val stream = value(metadata, "streamContent").takeUnless { it.isBlank() || it.startsWith("ZPSTR_") }
      val artist = value(metadata, "creator").ifBlank { value(metadata, "artist") }
      val title = value(metadata, "title")
      when {
        stream != null && artist.isBlank() && stream.contains(" - ") ->
          SonosTrack(stream.substringAfter(" - "), stream.substringBefore(" - "), value(metadata, "album"))
        stream != null && artist.isBlank() -> SonosTrack(stream, album = value(metadata, "album"))
        title.isNotBlank() -> SonosTrack(title, artist, value(metadata, "album"))
        else -> null
      }
    } catch (_: Exception) {
      null
    }
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
