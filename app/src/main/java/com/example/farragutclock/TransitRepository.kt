package com.example.farragutclock

import android.content.Context
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

      val refreshedRoutes = mutableMapOf<String, List<TransitArrival>>()
      for (stop in TransitConfig.stops) {
        val predicted = realtimeArrivals?.get(stop.displayRouteName).orEmpty()
        if (predicted.size >= MAX_ARRIVALS) {
          refreshedRoutes[stop.displayRouteName] = predicted
          continue
        }

        val scheduled = lookup?.scheduledArrivals(stop, nowMillis)

        when {
          scheduled != null ->
            refreshedRoutes[stop.displayRouteName] =
              normalizeArrivals(predicted + scheduled, nowMillis)
          predicted.isNotEmpty() -> refreshedRoutes[stop.displayRouteName] = predicted
        }
      }

      if (realtimeArrivals == null && refreshedRoutes.isEmpty()) {
        error("No SEPTA arrival source was available")
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
                  tripId
                    ?.let { lookup?.headsignFor(it, stop.stopId) }
                    .orEmpty()
                    .ifBlank { UNKNOWN_DESTINATION },
              ),
            )
        }
      }
    }

    return arrivals.mapValues { (_, times) -> normalizeArrivals(times, nowMillis) }
  }

  private fun ensureStaticLookup(nowMillis: Long): StaticGtfsLookup? {
    val archive = File(cacheDirectory, STATIC_BUS_ARCHIVE_CACHE_NAME)
    val archiveIsStale =
      !archive.exists() || nowMillis - archive.lastModified() >= STATIC_REFRESH_INTERVAL_MILLIS
    var archiveRefreshed = false

    if (
      archiveIsStale &&
        nowMillis - lastStaticRefreshAttemptMillis >= STATIC_REFRESH_RETRY_INTERVAL_MILLIS
    ) {
      lastStaticRefreshAttemptMillis = nowMillis
      try {
        downloadStaticBusArchive(archive, nowMillis)
        archiveRefreshed = true
      } catch (_: Exception) {
        // A previously cached archive remains usable if refreshing fails.
      }
    }

    if ((staticLookup == null || archiveRefreshed) && archive.exists()) {
      try {
        staticLookup = parseStaticLookup(archive)
      } catch (_: Exception) {
        // Keep the previously parsed lookup if a replacement cannot be parsed.
      }
    }

    return staticLookup
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

  private fun parseStaticLookup(archive: File): StaticGtfsLookup =
    ZipFile(archive).use { zip ->
      val trips = parseTrips(zip)
      val calendars = parseCalendars(zip)
      val calendarExceptions = parseCalendarExceptions(zip)
      val headsigns = mutableMapOf<TripStopKey, String>()
      val stopCalls = mutableListOf<StaticStopCall>()
      val selectedStopIds = TransitConfig.stops.mapTo(mutableSetOf()) { it.stopId }
      val selectedStopMarkers = selectedStopIds.map { ",$it," }

      zip.readerFor("stop_times.txt").use { reader ->
        val headers = reader.readCsvHeaders()
        val tripIdIndex = headers.requiredIndex("trip_id")
        val arrivalTimeIndex = headers.requiredIndex("arrival_time")
        val stopIdIndex = headers.requiredIndex("stop_id")
        val stopHeadsignIndex = headers.requiredIndex("stop_headsign")

        reader.forEachLine { line ->
          if (selectedStopMarkers.none(line::contains)) return@forEachLine

          val fields = parseCsvLine(line)
          val stopId = fields.valueAt(stopIdIndex)
          if (stopId !in selectedStopIds) return@forEachLine

          val tripId = fields.valueAt(tripIdIndex)
          val trip = trips[tripId] ?: return@forEachLine
          val destination =
            fields
              .valueAt(stopHeadsignIndex)
              .trim()
              .ifBlank { trip.tripHeadsign }
              .ifBlank { UNKNOWN_DESTINATION }
          val arrivalSeconds =
            parseGtfsTime(fields.valueAt(arrivalTimeIndex)) ?: return@forEachLine

          headsigns[TripStopKey(tripId, stopId)] = destination
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

      StaticGtfsLookup(
        headsigns = headsigns,
        stopCallsByRoute = stopCalls.groupBy { it.routeName },
        calendars = calendars,
        calendarExceptions = calendarExceptions,
      )
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
        val fields = parseCsvLine(line)
        calendars[fields.valueAt(serviceIdIndex)] =
          ServiceCalendar(
            startDate = fields.valueAt(startDateIndex).toInt(),
            endDate = fields.valueAt(endDateIndex).toInt(),
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
        val fields = parseCsvLine(line)
        val date = fields.valueAt(dateIndex).toInt()
        exceptions
          .getOrPut(date, ::mutableMapOf)
          .put(
            fields.valueAt(serviceIdIndex),
            fields.valueAt(exceptionTypeIndex).toInt(),
          )
      }
    }
    return exceptions
  }

  private fun normalizeArrivals(
    arrivals: List<TransitArrival>,
    nowMillis: Long,
  ): List<TransitArrival> =
    arrivals
      .asSequence()
      .filter { it.arrivalTimeMillis >= nowMillis - PAST_ARRIVAL_GRACE_MILLIS }
      .distinctBy { it.tripId ?: "${it.arrivalTimeMillis}:${it.destination}" }
      .sortedBy { it.arrivalTimeMillis }
      .take(MAX_ARRIVALS)
      .toList()

  private fun StopTimeUpdate.predictedTimeMillis(): Long? =
    when {
      hasArrival() && arrival.hasTime() -> arrival.time * MILLIS_PER_SECOND
      hasDeparture() && departure.hasTime() -> departure.time * MILLIS_PER_SECOND
      else -> null
    }

  private fun ZipFile.readerFor(entryName: String) =
    getInputStream(getEntry(entryName) ?: error("$entryName is missing")).bufferedReader()

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

    private const val UNKNOWN_DESTINATION = "destination unknown"
    private const val STATIC_BUS_ARCHIVE_CACHE_NAME = "septa_google_bus.zip"
    private const val STATIC_REFRESH_INTERVAL_MILLIS = 24L * 60L * 60L * 1_000L
    private const val STATIC_REFRESH_RETRY_INTERVAL_MILLIS = 6L * 60L * 60L * 1_000L
    private const val MAX_ARRIVALS = 2
    private const val MILLIS_PER_SECOND = 1_000L
    private const val PAST_ARRIVAL_GRACE_MILLIS = 60_000L
    private const val NETWORK_TIMEOUT_MILLIS = 10_000
  }
}

private data class TripStopKey(val tripId: String, val stopId: String)

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
  private val headsigns: Map<TripStopKey, String>,
  private val stopCallsByRoute: Map<String, List<StaticStopCall>>,
  private val calendars: Map<String, ServiceCalendar>,
  private val calendarExceptions: Map<Int, Map<String, Int>>,
) {
  fun headsignFor(tripId: String, stopId: String): String? =
    headsigns[TripStopKey(tripId, stopId)]

  fun scheduledArrivals(
    stop: TransitStopConfig,
    nowMillis: Long,
  ): List<TransitArrival> {
    val timeZone = TimeZone.getTimeZone(TransitConfig.agencyTimeZone)
    val calls =
      stopCallsByRoute[stop.displayRouteName]
        .orEmpty()
        .filter { it.stopId == stop.stopId }
    val arrivals = mutableListOf<TransitArrival>()

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
        if (!isServiceActive(call.serviceId, date, dayOfWeek)) continue

        val arrivalTime =
          (serviceDay.clone() as Calendar)
            .apply { add(Calendar.SECOND, call.arrivalSeconds) }
            .timeInMillis
        if (arrivalTime < nowMillis - PAST_GRACE_MILLIS) continue

        arrivals +=
          TransitArrival(
            arrivalTimeMillis = arrivalTime,
            tripId = call.tripId,
            destination = call.destination,
          )
      }
    }

    return arrivals
      .distinctBy(TransitArrival::tripId)
      .sortedBy(TransitArrival::arrivalTimeMillis)
      .take(2)
  }

  private fun isServiceActive(serviceId: String, date: Int, dayOfWeek: Int): Boolean {
    val calendar = calendars[serviceId]
    var active =
      calendar != null &&
        date in calendar.startDate..calendar.endDate &&
        calendar.activeDays[dayOfWeek] == true

    when (calendarExceptions[date]?.get(serviceId)) {
      1 -> active = true
      2 -> active = false
    }
    return active
  }

  private companion object {
    const val PAST_GRACE_MILLIS = 60_000L
  }
}
