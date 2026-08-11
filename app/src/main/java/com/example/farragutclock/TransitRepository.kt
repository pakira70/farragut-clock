package com.example.farragutclock

import com.google.transit.realtime.GtfsRealtime.FeedMessage
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.TimeZone

internal class TransitRepository {
  suspend fun refreshArrivals(
    nowMillis: Long = System.currentTimeMillis(),
  ): Map<String, List<Long>> =
    withContext(Dispatchers.IO) {
      val realtimeArrivals =
        try {
          fetchRealtimeArrivals(nowMillis)
        } catch (_: Exception) {
          null
        }

      val refreshedRoutes = mutableMapOf<String, List<Long>>()
      for (stop in TransitConfig.stops) {
        val predicted = realtimeArrivals?.get(stop.displayRouteName).orEmpty()
        if (predicted.size >= MAX_ARRIVALS) {
          refreshedRoutes[stop.displayRouteName] = predicted
          continue
        }

        val scheduled =
          try {
            fetchScheduledArrivals(stop, nowMillis)
          } catch (_: Exception) {
            null
          }

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

  private fun fetchRealtimeArrivals(nowMillis: Long): Map<String, List<Long>> {
    val feed =
      readUrl(TransitConfig.gtfsRealtimeTripUpdatesUrl) { input ->
        FeedMessage.parseFrom(input)
      }
    val stopsByRouteId = TransitConfig.stops.associateBy { it.currentGtfsRouteId }
    val arrivals = TransitConfig.stops.associate { it.displayRouteName to mutableListOf<Long>() }

    for (entity in feed.entityList) {
      if (!entity.hasTripUpdate()) continue

      val tripUpdate = entity.tripUpdate
      if (!tripUpdate.trip.hasRouteId()) continue

      val stop = stopsByRouteId[tripUpdate.trip.routeId] ?: continue
      for (stopUpdate in tripUpdate.stopTimeUpdateList) {
        if (!stopUpdate.hasStopId() || stopUpdate.stopId != stop.stopId) continue

        stopUpdate.predictedTimeMillis()?.let { predictedTime ->
          arrivals.getValue(stop.displayRouteName).add(predictedTime)
        }
      }
    }

    return arrivals.mapValues { (_, times) -> normalizeArrivals(times, nowMillis) }
  }

  private fun fetchScheduledArrivals(
    stop: TransitStopConfig,
    nowMillis: Long,
  ): List<Long> {
    val routeId =
      URLEncoder.encode(stop.currentGtfsRouteId, StandardCharsets.UTF_8.name())
    val url = "${TransitConfig.scheduledSmsUrl}?req1=${stop.stopId}&req2=$routeId"
    val response = readUrl(url) { input -> input.bufferedReader().use { it.readText() } }
    val agencyTimeZone = TimeZone.getTimeZone(TransitConfig.agencyTimeZone)

    val arrivals =
      SCHEDULE_TIME_REGEX.findAll(response).mapNotNull { match ->
        val (hour, minute) = match.groupValues[1].split(":").map(String::toInt)
        Calendar.getInstance(agencyTimeZone)
          .apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
          }
          .timeInMillis
          .takeIf { it >= nowMillis - PAST_ARRIVAL_GRACE_MILLIS }
      }

    return normalizeArrivals(arrivals.toList(), nowMillis)
  }

  private fun normalizeArrivals(arrivals: List<Long>, nowMillis: Long): List<Long> =
    arrivals
      .asSequence()
      .filter { it >= nowMillis - PAST_ARRIVAL_GRACE_MILLIS }
      .distinct()
      .sorted()
      .take(MAX_ARRIVALS)
      .toList()

  private fun StopTimeUpdate.predictedTimeMillis(): Long? =
    when {
      hasArrival() && arrival.hasTime() -> arrival.time * MILLIS_PER_SECOND
      hasDeparture() && departure.hasTime() -> departure.time * MILLIS_PER_SECOND
      else -> null
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

    private const val MAX_ARRIVALS = 2
    private const val MILLIS_PER_SECOND = 1_000L
    private const val PAST_ARRIVAL_GRACE_MILLIS = 60_000L
    private const val NETWORK_TIMEOUT_MILLIS = 10_000
    private val SCHEDULE_TIME_REGEX = Regex("""@\s*(\d{1,2}:\d{2})""")
  }
}
