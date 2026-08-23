package com.example.farragutclock

import android.content.Context
import android.util.Log
import com.google.transit.realtime.GtfsRealtime.FeedMessage
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.TimeZone
import java.util.zip.ZipFile

internal data class TransitArrival(
  val arrivalTimeMillis: Long,
  val tripId: String?,
  val destination: String,
  val tracked: Boolean,
  val directionId: Int? = null,
)

internal class TransitRepository(context: Context) {
  private val cacheDirectory = context.cacheDir
  private var staticLookup: StaticGtfsLookup? = null
  private var lastStaticRefreshAttemptMillis: Long = 0L

  suspend fun refreshArrivals(
    nowMillis: Long = System.currentTimeMillis(),
  ): Map<String, List<TransitArrival>> =
    withContext(Dispatchers.IO) {
      val lookup = ensureStaticLookup(nowMillis)
      val realtimeArrivals =
        try {
          fetchRealtimeArrivals(lookup, nowMillis)
        } catch (_: Exception) {
          null
        }

      if (realtimeArrivals == null && lookup == null) {
        error("No SEPTA arrival source was available")
      }

      val refreshedRoutes = mutableMapOf<String, List<TransitArrival>>()
      for (stop in TransitConfig.stops) {
        val predicted = realtimeArrivals?.get(stop.displayRouteName).orEmpty()
        val scheduled =
          if (lookup == null || lookup.isFeedExpired(nowMillis)) {
            emptyList()
          } else {
            lookup.scheduledArrivals(stop, nowMillis)
          }
        val combined =
          if (TransitRules.hasEnoughLivePredictions(predicted, nowMillis, MAX_ARRIVALS)) {
            predicted
          } else {
            predicted + scheduled
          }
        refreshedRoutes[stop.displayRouteName] = normalizeArrivals(combined, nowMillis)
      }

      refreshedRoutes
    }

  private fun fetchRealtimeArrivals(
    lookup: StaticGtfsLookup?,
    nowMillis: Long,
  ): Map<String, List<TransitArrival>> {
    val feed =
      readUrl(TransitConfig.gtfsRealtimeTripUpdatesUrl) { input ->
        FeedMessage.parseFrom(input)
      }
    val stopsByRouteId = TransitConfig.stops.associateBy { it.currentGtfsRouteId }
    val arrivals =
      TransitConfig.stops.associate {
        it.displayRouteName to mutableListOf<TransitArrival>()
      }

    for (entity in feed.entityList) {
      if (!entity.hasTripUpdate()) continue

      val tripUpdate = entity.tripUpdate
      if (!tripUpdate.trip.hasRouteId()) continue

      val stop = stopsByRouteId[tripUpdate.trip.routeId] ?: continue
      val tripId =
        tripUpdate.trip.tripId.takeIf {
          tripUpdate.trip.hasTripId() && it.isNotBlank()
        }
      val directionId =
        if (tripUpdate.trip.hasDirectionId()) {
          tripUpdate.trip.directionId
        } else {
          null
        }
      for (stopUpdate in tripUpdate.stopTimeUpdateList) {
        if (!stopUpdate.hasStopId() || stopUpdate.stopId != stop.stopId) continue

        stopUpdate.predictedTimeMillis()?.let { predictedTime ->
          arrivals
            .getValue(stop.displayRouteName)
            .add(
              TransitArrival(
                arrivalTimeMillis = predictedTime,
                tripId = tripId,
                destination =
                  lookup
                    ?.headsignFor(tripId, stop.stopId, stop.currentGtfsRouteId, directionId)
                    .orEmpty()
                    .ifBlank { UNKNOWN_DESTINATION },
                tracked = true,
                directionId = directionId,
              ),
            )
        }
      }
    }

    return arrivals.mapValues { (_, times) -> normalizeArrivals(times, nowMillis) }
  }

  private fun ensureStaticLookup(nowMillis: Long): StaticGtfsLookup? {
    val archive = File(cacheDirectory, STATIC_BUS_ARCHIVE_CACHE_NAME)
    if (staticLookup == null && archive.exists()) {
      try {
        staticLookup = parseStaticLookup(archive).lookup
      } catch (exception: Exception) {
        Log.w(TAG, "Failed to parse cached SEPTA static GTFS", exception)
      }
    }

    val feedExpired = staticLookup?.isFeedExpired(nowMillis) == true
    val archiveIsStale =
      !archive.exists() ||
        nowMillis - archive.lastModified() >= STATIC_REFRESH_INTERVAL_MILLIS ||
        feedExpired
    val retryInterval =
      if (feedExpired || !archive.exists()) {
        STATIC_REFRESH_EXPIRED_RETRY_MILLIS
      } else {
        STATIC_REFRESH_RETRY_INTERVAL_MILLIS
      }

    if (
      archiveIsStale &&
        nowMillis - lastStaticRefreshAttemptMillis >= retryInterval
    ) {
      lastStaticRefreshAttemptMillis = nowMillis
      tryPromoteStaticArchive(archive, nowMillis)
    }

    return staticLookup
  }

  private fun tryPromoteStaticArchive(archive: File, nowMillis: Long) {
    val downloaded = File.createTempFile("septa_bus_new_", ".zip", cacheDirectory)
    try {
      downloadStaticBusArchive(downloaded, nowMillis)
      val parsed = parseStaticLookup(downloaded, persistScheduleCache = false)
      downloaded.copyTo(archive, overwrite = true)
      archive.setLastModified(nowMillis)
      if (parsed.stopCalls.isNotEmpty()) {
        writeScheduleCache(File(cacheDirectory, STATIC_SCHEDULE_CACHE_NAME), parsed.stopCalls)
      } else {
        File(cacheDirectory, STATIC_SCHEDULE_CACHE_NAME).delete()
      }
      staticLookup = parsed.lookup
      Log.i(TAG, "Promoted new SEPTA static GTFS")
    } catch (exception: Exception) {
      Log.w(TAG, "Failed to refresh SEPTA static GTFS; keeping previous archive", exception)
    } finally {
      downloaded.delete()
    }
  }

  private fun downloadStaticBusArchive(destination: File, nowMillis: Long) {
    val outerArchive = File.createTempFile("septa_gtfs_", ".zip", cacheDirectory)
    val nestedArchive = File.createTempFile("septa_bus_", ".zip", cacheDirectory)
    try {
      downloadUrlToFile(TransitConfig.staticGtfsUrl, outerArchive)
      ZipFile(outerArchive).use { zip ->
        val entry =
          zip.getEntry(TransitConfig.staticBusArchiveName)
            ?: error("${TransitConfig.staticBusArchiveName} is missing")
        zip.getInputStream(entry).use { input ->
          FileOutputStream(nestedArchive).use { output -> input.copyTo(output) }
        }
      }
      nestedArchive.copyTo(destination, overwrite = true)
      destination.setLastModified(nowMillis)
    } finally {
      outerArchive.delete()
      nestedArchive.delete()
    }
  }

  private fun downloadUrlToFile(url: String, destination: File) {
    readUrl(url) { input ->
      FileOutputStream(destination).use { output -> input.copyTo(output) }
    }
  }

  private fun parseStaticLookup(
    archive: File,
    persistScheduleCache: Boolean = true,
  ): ParsedStaticGtfs =
    ZipFile(archive).use { zip ->
      val trips = parseTrips(zip)
      val tripHeadsigns =
        trips.mapValues { (_, trip) -> trip.tripHeadsign }.filterValues { it.isNotBlank() }
      val directionDestinations =
        try {
          parseDirections(zip)
        } catch (_: Exception) {
          emptyMap()
        }
      val feedDates = parseFeedInfo(zip)
      val headsigns = mutableMapOf<TripStopKey, String>()
      val stopCalls = mutableListOf<StaticStopCall>()
      var calendars = emptyMap<String, ServiceCalendar>()
      var calendarExceptions = emptyMap<Int, Map<String, Int>>()

      try {
        calendars = parseCalendars(zip)
        calendarExceptions = parseCalendarExceptions(zip)
        val scheduleCache = File(cacheDirectory, STATIC_SCHEDULE_CACHE_NAME)
        if (!loadScheduleCache(scheduleCache, archive, headsigns, stopCalls)) {
          parseStopTimes(zip, trips, headsigns, stopCalls)
          if (persistScheduleCache && stopCalls.isNotEmpty()) {
            writeScheduleCache(scheduleCache, stopCalls)
          }
        }
        Log.i(TAG, "Loaded ${stopCalls.size} scheduled stop calls")
      } catch (failure: Throwable) {
        Log.w(TAG, "Failed to parse SEPTA stop schedules; using trip headsigns only", failure)
      }

      Log.i(
        TAG,
        "Loaded ${tripHeadsigns.size} SEPTA trip headsigns " +
          "(feed ${feedDates.startDate}-${feedDates.endDate})",
      )
      ParsedStaticGtfs(
        lookup =
          StaticGtfsLookup(
            stopHeadsigns = headsigns,
            tripHeadsigns = tripHeadsigns,
            directionDestinations = directionDestinations,
            defaultHeadsignsByStop = defaultHeadsignsByStop(stopCalls),
            stopCallsByRoute = stopCalls.groupBy { it.routeName },
            calendars = calendars,
            calendarExceptions = calendarExceptions,
            feedStartDate = feedDates.startDate,
            feedEndDate = feedDates.endDate,
          ),
        stopCalls = stopCalls.toList(),
      )
    }

  private fun parseFeedInfo(zip: ZipFile): FeedDates {
    val entry = zip.getEntry("feed_info.txt") ?: return FeedDates(null, null)
    return zip.getInputStream(entry).bufferedReader().use { reader ->
      val headers = reader.readCsvHeaders()
      val startIndex = headers.indexOf("feed_start_date")
      val endIndex = headers.indexOf("feed_end_date")
      val line = reader.readLine() ?: return FeedDates(null, null)
      if (line.isBlank()) return FeedDates(null, null)
      val fields = parseCsvLine(line)
      FeedDates(
        startDate = if (startIndex >= 0) fields.valueAt(startIndex).toIntOrNull() else null,
        endDate = if (endIndex >= 0) fields.valueAt(endIndex).toIntOrNull() else null,
      )
    }
  }

  private fun parseStopTimes(
    zip: ZipFile,
    trips: Map<String, StaticTrip>,
    headsigns: MutableMap<TripStopKey, String>,
    stopCalls: MutableList<StaticStopCall>,
  ) {
    val selectedStopIds = TransitConfig.stops.mapTo(mutableSetOf()) { it.stopId }
    val selectedStopMarkers = selectedStopIds.map { ",$it,".toByteArray(Charsets.US_ASCII) }
    var tripIdIndex = -1
    var arrivalTimeIndex = -1
    var stopIdIndex = -1
    var stopHeadsignIndex = -1
    var headersReady = false

    zip.forEachAsciiLine("stop_times.txt") { lineBytes, length ->
      if (!headersReady) {
        val headers =
          parseCsvLine(String(lineBytes, 0, length, Charsets.UTF_8)).mapIndexed { index, value ->
            if (index == 0) value.removePrefix("\uFEFF") else value
          }
        tripIdIndex = headers.requiredIndex("trip_id")
        arrivalTimeIndex = headers.requiredIndex("arrival_time")
        stopIdIndex = headers.requiredIndex("stop_id")
        stopHeadsignIndex = headers.requiredIndex("stop_headsign")
        headersReady = true
        return@forEachAsciiLine
      }
      if (!lineBytes.containsMarker(length, selectedStopMarkers)) return@forEachAsciiLine

      val fields = parseCsvLine(String(lineBytes, 0, length, Charsets.UTF_8))
      val stopId = fields.valueAt(stopIdIndex)
      if (stopId !in selectedStopIds) return@forEachAsciiLine

      val tripId = fields.valueAt(tripIdIndex)
      val trip = trips[tripId] ?: return@forEachAsciiLine
      val destination =
        fields
          .valueAt(stopHeadsignIndex)
          .trim()
          .ifBlank { trip.tripHeadsign }
          .ifBlank { UNKNOWN_DESTINATION }
      if (destination != UNKNOWN_DESTINATION) {
        headsigns[TripStopKey(tripId, stopId)] = destination
      }
      val arrivalSeconds =
        parseGtfsTime(fields.valueAt(arrivalTimeIndex)) ?: return@forEachAsciiLine

      stopCalls +=
        StaticStopCall(
          tripId = tripId,
          routeName = trip.routeName,
          stopId = stopId,
          serviceId = trip.serviceId,
          arrivalSeconds = arrivalSeconds,
          destination = destination,
        )
    }
  }

  private fun loadScheduleCache(
    cache: File,
    archive: File,
    headsigns: MutableMap<TripStopKey, String>,
    stopCalls: MutableList<StaticStopCall>,
  ): Boolean {
    if (!cache.exists() || cache.lastModified() < archive.lastModified()) return false
    return try {
      cache.bufferedReader().use { reader ->
        if (reader.readLine() != SCHEDULE_CACHE_VERSION) return false
        val loadedCalls = mutableListOf<StaticStopCall>()
        val loadedHeadsigns = mutableMapOf<TripStopKey, String>()
        while (true) {
          val line = reader.readLine() ?: break
          val fields = line.split('\t')
          if (fields.size != 6) return false
          val call =
            StaticStopCall(
              tripId = fields[0],
              routeName = fields[1],
              stopId = fields[2],
              serviceId = fields[3],
              arrivalSeconds = fields[4].toInt(),
              destination = fields[5],
            )
          loadedCalls += call
          if (call.destination != UNKNOWN_DESTINATION) {
            loadedHeadsigns[TripStopKey(call.tripId, call.stopId)] = call.destination
          }
        }
        headsigns.putAll(loadedHeadsigns)
        stopCalls += loadedCalls
        true
      }
    } catch (_: Exception) {
      false
    }
  }

  private fun writeScheduleCache(cache: File, stopCalls: List<StaticStopCall>) {
    cache.bufferedWriter().use { writer ->
      writer.appendLine(SCHEDULE_CACHE_VERSION)
      for (call in stopCalls) {
        writer.append(call.tripId).append('\t')
        writer.append(call.routeName).append('\t')
        writer.append(call.stopId).append('\t')
        writer.append(call.serviceId).append('\t')
        writer.append(call.arrivalSeconds.toString()).append('\t')
        writer.appendLine(call.destination)
      }
    }
    cache.setLastModified(System.currentTimeMillis())
  }

  private fun parseTrips(zip: ZipFile): Map<String, StaticTrip> {
    val routeNamesByGtfsId =
      TransitConfig.stops.associate { it.currentGtfsRouteId to it.displayRouteName }
    val selectedRoutePrefixes = routeNamesByGtfsId.keys.map { "$it," }
    val trips = mutableMapOf<String, StaticTrip>()

    zip.readerFor("trips.txt").use { reader ->
      val headers = reader.readCsvHeaders()
      val routeIdIndex = headers.requiredIndex("route_id")
      val serviceIdIndex = headers.requiredIndex("service_id")
      val tripIdIndex = headers.requiredIndex("trip_id")
      val tripHeadsignIndex = headers.requiredIndex("trip_headsign")

      reader.forEachLine { line ->
        if (selectedRoutePrefixes.none(line::startsWith)) return@forEachLine

        val fields = parseCsvLine(line)
        val routeName = routeNamesByGtfsId[fields.valueAt(routeIdIndex)] ?: return@forEachLine
        val tripId = fields.valueAt(tripIdIndex)
        trips[tripId] =
          StaticTrip(
            routeName = routeName,
            serviceId = fields.valueAt(serviceIdIndex),
            tripHeadsign = fields.valueAt(tripHeadsignIndex).trim(),
          )
      }
    }

    return trips
  }

  private fun parseDirections(zip: ZipFile): Map<RouteDirectionKey, String> {
    val entry = zip.getEntry("directions.txt") ?: return emptyMap()
    return try {
      val destinations = mutableMapOf<RouteDirectionKey, String>()
      zip.getInputStream(entry).bufferedReader().use { reader ->
        val headers = reader.readCsvHeaders()
        val routeIdIndex = headers.requiredIndex("route_id")
        val directionIdIndex = headers.requiredIndex("direction_id")
        val destinationIndex =
          headers.indexOf("direction_destination").takeIf { it >= 0 }
            ?: headers.requiredIndex("direction")

        reader.forEachLine { line ->
          if (line.isBlank()) return@forEachLine
          val fields = parseCsvLine(line)
          val routeId = fields.valueAt(routeIdIndex)
          val directionId = fields.valueAt(directionIdIndex).toIntOrNull() ?: return@forEachLine
          val destination = fields.valueAt(destinationIndex).trim()
          if (routeId.isBlank() || destination.isBlank()) return@forEachLine
          destinations[RouteDirectionKey(routeId, directionId)] = destination
        }
      }
      destinations
    } catch (_: Exception) {
      emptyMap()
    }
  }

  private fun defaultHeadsignsByStop(stopCalls: List<StaticStopCall>): Map<String, String> =
    stopCalls
      .filter { it.destination != UNKNOWN_DESTINATION }
      .groupBy { it.stopId }
      .mapNotNull { (stopId, calls) ->
        calls
          .groupingBy { it.destination }
          .eachCount()
          .maxByOrNull { it.value }
          ?.key
          ?.let { stopId to it }
      }
      .toMap()

  private fun parseCalendars(zip: ZipFile): Map<String, ServiceCalendar> {
    val calendars = mutableMapOf<String, ServiceCalendar>()
    zip.readerFor("calendar.txt").use { reader ->
      val headers = reader.readCsvHeaders()
      val serviceIdIndex = headers.requiredIndex("service_id")
      val startDateIndex = headers.requiredIndex("start_date")
      val endDateIndex = headers.requiredIndex("end_date")
      val dayIndexes =
        mapOf(
          Calendar.MONDAY to headers.requiredIndex("monday"),
          Calendar.TUESDAY to headers.requiredIndex("tuesday"),
          Calendar.WEDNESDAY to headers.requiredIndex("wednesday"),
          Calendar.THURSDAY to headers.requiredIndex("thursday"),
          Calendar.FRIDAY to headers.requiredIndex("friday"),
          Calendar.SATURDAY to headers.requiredIndex("saturday"),
          Calendar.SUNDAY to headers.requiredIndex("sunday"),
        )

      reader.forEachLine { line ->
        if (line.isBlank()) return@forEachLine
        val fields = parseCsvLine(line)
        val startDate = fields.valueAt(startDateIndex).toIntOrNull() ?: return@forEachLine
        val endDate = fields.valueAt(endDateIndex).toIntOrNull() ?: return@forEachLine
        val serviceId = fields.valueAt(serviceIdIndex)
        if (serviceId.isBlank()) return@forEachLine
        calendars[serviceId] =
          ServiceCalendar(
            startDate = startDate,
            endDate = endDate,
            activeDays =
              dayIndexes.mapValues { (_, index) -> fields.valueAt(index) == "1" },
          )
      }
    }
    return calendars
  }

  private fun parseCalendarExceptions(zip: ZipFile): Map<Int, Map<String, Int>> {
    val exceptions = mutableMapOf<Int, MutableMap<String, Int>>()
    zip.readerFor("calendar_dates.txt").use { reader ->
      val headers = reader.readCsvHeaders()
      val serviceIdIndex = headers.requiredIndex("service_id")
      val dateIndex = headers.requiredIndex("date")
      val exceptionTypeIndex = headers.requiredIndex("exception_type")

      reader.forEachLine { line ->
        if (line.isBlank()) return@forEachLine
        val fields = parseCsvLine(line)
        val date = fields.valueAt(dateIndex).toIntOrNull() ?: return@forEachLine
        val serviceId = fields.valueAt(serviceIdIndex)
        val exceptionType = fields.valueAt(exceptionTypeIndex).toIntOrNull() ?: return@forEachLine
        if (serviceId.isBlank()) return@forEachLine
        exceptions.getOrPut(date, ::mutableMapOf)[serviceId] = exceptionType
      }
    }
    return exceptions
  }

  private fun normalizeArrivals(
    arrivals: List<TransitArrival>,
    nowMillis: Long,
  ): List<TransitArrival> {
    val upcoming =
      arrivals
        .filter { it.arrivalTimeMillis >= nowMillis - PAST_ARRIVAL_GRACE_MILLIS }
        .sortedBy { it.arrivalTimeMillis }

    val deduped = mutableListOf<TransitArrival>()
    for (arrival in upcoming) {
      val existingIndex = deduped.indexOfFirst { isLikelySameTrip(it, arrival) }
      if (existingIndex < 0) {
        deduped += arrival
        continue
      }
      if (arrival.tracked && !deduped[existingIndex].tracked) {
        deduped[existingIndex] = arrival
      }
    }

    return deduped.take(MAX_ARRIVALS)
  }

  private fun isLikelySameTrip(first: TransitArrival, second: TransitArrival): Boolean =
    TransitRules.isLikelySameTrip(
      first.tripId,
      first.directionId,
      first.arrivalTimeMillis,
      second.tripId,
      second.directionId,
      second.arrivalTimeMillis,
    )

  private fun StopTimeUpdate.predictedTimeMillis(): Long? =
    when {
      hasArrival() && arrival.hasTime() -> arrival.time * MILLIS_PER_SECOND
      hasDeparture() && departure.hasTime() -> departure.time * MILLIS_PER_SECOND
      else -> null
    }

  private fun ZipFile.readerFor(entryName: String) =
    getInputStream(getEntry(entryName) ?: error("$entryName is missing")).bufferedReader()

  private fun ZipFile.forEachAsciiLine(
    entryName: String,
    handle: (ByteArray, Int) -> Unit,
  ) {
    getInputStream(getEntry(entryName) ?: error("$entryName is missing")).buffered(64 * 1024).use { input ->
      var line = ByteArray(512)
      var lineLength = 0
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        for (index in 0 until read) {
          val byte = buffer[index]
          if (byte == NEWLINE_BYTE) {
            var length = lineLength
            if (length > 0 && line[length - 1] == CARRIAGE_RETURN_BYTE) length--
            handle(line, length)
            lineLength = 0
          } else {
            if (lineLength >= line.size) {
              line = line.copyOf(line.size * 2)
            }
            line[lineLength++] = byte
          }
        }
      }
      if (lineLength > 0) {
        var length = lineLength
        if (line[length - 1] == CARRIAGE_RETURN_BYTE) length--
        handle(line, length)
      }
    }
  }

  private fun ByteArray.containsMarker(length: Int, markers: List<ByteArray>): Boolean {
    for (marker in markers) {
      val lastStart = length - marker.size
      if (lastStart < 0) continue
      start@ for (start in 0..lastStart) {
        for (offset in marker.indices) {
          if (this[start + offset] != marker[offset]) continue@start
        }
        return true
      }
    }
    return false
  }

  private fun java.io.BufferedReader.readCsvHeaders(): List<String> =
    parseCsvLine(readLine() ?: error("CSV file is empty")).mapIndexed { index, value ->
      if (index == 0) value.removePrefix("\uFEFF") else value
    }

  private fun List<String>.requiredIndex(columnName: String): Int =
    indexOf(columnName).takeIf { it >= 0 } ?: error("$columnName is missing")

  private fun List<String>.valueAt(index: Int): String = getOrElse(index) { "" }

  private fun parseCsvLine(line: String): List<String> {
    val fields = mutableListOf<String>()
    val value = StringBuilder()
    var insideQuotes = false
    var index = 0

    while (index < line.length) {
      val character = line[index]
      when {
        character == '"' && insideQuotes && line.getOrNull(index + 1) == '"' -> {
          value.append('"')
          index++
        }
        character == '"' -> insideQuotes = !insideQuotes
        character == ',' && !insideQuotes -> {
          fields += value.toString()
          value.clear()
        }
        else -> value.append(character)
      }
      index++
    }
    fields += value.toString()
    return fields
  }

  private fun parseGtfsTime(value: String): Int? {
    val parts = value.split(":")
    if (parts.size != 3) return null
    val hours = parts[0].toIntOrNull() ?: return null
    val minutes = parts[1].toIntOrNull() ?: return null
    val seconds = parts[2].toIntOrNull() ?: return null
    return hours * 3_600 + minutes * 60 + seconds
  }

  private fun <T> readUrl(url: String, read: (InputStream) -> T): T {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
      connection.connectTimeout = NETWORK_TIMEOUT_MILLIS
      connection.readTimeout = NETWORK_TIMEOUT_MILLIS
      connection.requestMethod = "GET"
      connection.setRequestProperty("Accept", "*/*")

      if (connection.responseCode !in 200..299) {
        error("SEPTA returned HTTP ${connection.responseCode}")
      }

      return connection.inputStream.use(read)
    } finally {
      connection.disconnect()
    }
  }

  internal companion object {
    const val REFRESH_INTERVAL_MILLIS = 30_000L
    private const val TAG = "FarragutTransit"

    private const val UNKNOWN_DESTINATION = "destination unknown"
    private const val STATIC_BUS_ARCHIVE_CACHE_NAME = "septa_google_bus.zip"
    private const val STATIC_SCHEDULE_CACHE_NAME = "septa_stop_calls.tsv"
    private const val SCHEDULE_CACHE_VERSION = "v1"
    private const val STATIC_REFRESH_INTERVAL_MILLIS = 24L * 60L * 60L * 1_000L
    private const val STATIC_REFRESH_RETRY_INTERVAL_MILLIS = 6L * 60L * 60L * 1_000L
    private const val STATIC_REFRESH_EXPIRED_RETRY_MILLIS = 15L * 60L * 1_000L
    private const val MAX_ARRIVALS = 2
    private const val MILLIS_PER_SECOND = 1_000L
    private const val PAST_ARRIVAL_GRACE_MILLIS = TransitRules.PAST_ARRIVAL_GRACE_MILLIS
    private const val NETWORK_TIMEOUT_MILLIS = 10_000
    private const val NEWLINE_BYTE: Byte = 0x0A
    private const val CARRIAGE_RETURN_BYTE: Byte = 0x0D
  }
}

private data class ParsedStaticGtfs(
  val lookup: StaticGtfsLookup,
  val stopCalls: List<StaticStopCall>,
)

private data class FeedDates(val startDate: Int?, val endDate: Int?)

private data class TripStopKey(val tripId: String, val stopId: String)

private data class RouteDirectionKey(val routeId: String, val directionId: Int)

private data class StaticTrip(
  val routeName: String,
  val serviceId: String,
  val tripHeadsign: String,
)

private data class StaticStopCall(
  val tripId: String,
  val routeName: String,
  val stopId: String,
  val serviceId: String,
  val arrivalSeconds: Int,
  val destination: String,
)

private data class ServiceCalendar(
  val startDate: Int,
  val endDate: Int,
  val activeDays: Map<Int, Boolean>,
)

private class StaticGtfsLookup(
  private val stopHeadsigns: Map<TripStopKey, String>,
  private val tripHeadsigns: Map<String, String>,
  private val directionDestinations: Map<RouteDirectionKey, String>,
  private val defaultHeadsignsByStop: Map<String, String>,
  private val stopCallsByRoute: Map<String, List<StaticStopCall>>,
  private val calendars: Map<String, ServiceCalendar>,
  private val calendarExceptions: Map<Int, Map<String, Int>>,
  private val feedStartDate: Int?,
  private val feedEndDate: Int?,
) {
  fun isFeedExpired(nowMillis: Long): Boolean {
    val todayDate = agencyDate(nowMillis)
    return TransitRules.isFeedExpired(todayDate, feedEndDate)
  }
  fun headsignFor(
    tripId: String?,
    stopId: String,
    routeId: String,
    directionId: Int?,
  ): String? {
    if (tripId != null) {
      stopHeadsigns[TripStopKey(tripId, stopId)]?.let { return it }
      tripHeadsigns[tripId]?.let { return it }
    }
    if (directionId != null) {
      directionDestinations[RouteDirectionKey(routeId, directionId)]?.let { return it }
    }
    return defaultHeadsignsByStop[stopId]
  }

  fun scheduledArrivals(
    stop: TransitStopConfig,
    nowMillis: Long,
  ): List<TransitArrival> {
    if (isFeedExpired(nowMillis)) return emptyList()

    val timeZone = TimeZone.getTimeZone(TransitConfig.agencyTimeZone)
    val calls =
      stopCallsByRoute[stop.displayRouteName]
        .orEmpty()
        .filter { it.stopId == stop.stopId }
    val arrivals = mutableListOf<TransitArrival>()
    val todayDate = agencyDate(nowMillis)
    val feedNotYetStarted =
      TransitRules.isFeedNotYetStarted(todayDate, feedStartDate) ||
        (feedStartDate == null && calendars.values.any { it.startDate > todayDate })

    for (dayOffset in -1..1) {
      val serviceDay =
        Calendar.getInstance(timeZone).apply {
          timeInMillis = nowMillis
          add(Calendar.DAY_OF_MONTH, dayOffset)
          set(Calendar.HOUR_OF_DAY, 0)
          set(Calendar.MINUTE, 0)
          set(Calendar.SECOND, 0)
          set(Calendar.MILLISECOND, 0)
        }
      val date =
        serviceDay.get(Calendar.YEAR) * 10_000 +
          (serviceDay.get(Calendar.MONTH) + 1) * 100 +
          serviceDay.get(Calendar.DAY_OF_MONTH)
      val dayOfWeek = serviceDay.get(Calendar.DAY_OF_WEEK)

      for (call in calls) {
        if (!isServiceActive(call.serviceId, date, dayOfWeek, feedNotYetStarted)) continue

        val arrivalTime =
          (serviceDay.clone() as Calendar)
            .apply { add(Calendar.SECOND, call.arrivalSeconds) }
            .timeInMillis
        if (arrivalTime < nowMillis - PAST_GRACE_MILLIS) continue
        if (arrivalTime - nowMillis > MAX_SCHEDULED_AHEAD_MILLIS) continue

        arrivals +=
          TransitArrival(
            arrivalTimeMillis = arrivalTime,
            tripId = call.tripId,
            destination = call.destination,
            tracked = false,
          )
      }
    }

    return arrivals
      .distinctBy(TransitArrival::tripId)
      .sortedBy(TransitArrival::arrivalTimeMillis)
      .take(2)
  }

  private fun isServiceActive(
    serviceId: String,
    date: Int,
    dayOfWeek: Int,
    feedNotYetStarted: Boolean,
  ): Boolean {
    val calendar = calendars[serviceId]
    var active =
      if (calendar == null) {
        false
      } else {
        TransitRules.calendarHasStarted(date, calendar.startDate, feedNotYetStarted) &&
          date <= calendar.endDate &&
          calendar.activeDays[dayOfWeek] == true
      }

    when (calendarExceptions[date]?.get(serviceId)) {
      1 -> active = true
      2 -> active = false
    }
    return active
  }

  private fun agencyDate(nowMillis: Long): Int {
    val calendar =
      Calendar.getInstance(TimeZone.getTimeZone(TransitConfig.agencyTimeZone)).apply {
        timeInMillis = nowMillis
      }
    return calendar.get(Calendar.YEAR) * 10_000 +
      (calendar.get(Calendar.MONTH) + 1) * 100 +
      calendar.get(Calendar.DAY_OF_MONTH)
  }

  private companion object {
    const val PAST_GRACE_MILLIS = 60_000L
    const val MAX_SCHEDULED_AHEAD_MILLIS = 90L * 60L * 1_000L
  }
}
