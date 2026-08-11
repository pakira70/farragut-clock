package com.example.farragutclock

internal data class TransitStopConfig(
  val displayRouteName: String,
  val displayStopName: String,
  val stopId: String,
  val currentGtfsRouteId: String,
  val legacyRouteId: String,
)

internal object TransitConfig {
  const val gtfsRealtimeTripUpdatesUrl =
    "https://www3.septa.org/gtfsrt/septa-pa-us/Trip/rtTripUpdates.pb"
  const val staticGtfsUrl = "https://www3.septa.org/developer/gtfs_public.zip"
  const val staticBusArchiveName = "google_bus.zip"
  const val agencyTimeZone = "America/New_York"

  // These exact MVP stops and current-to-legacy route mappings are authoritative.
  val stops =
    listOf(
      TransitStopConfig(
        displayRouteName = "T2",
        displayStopName = "Baltimore Ave & 46th St",
        stopId = "20872",
        currentGtfsRouteId = "T2",
        legacyRouteId = "34",
      ),
      TransitStopConfig(
        displayRouteName = "T3",
        displayStopName = "Chester Ave & 46th St",
        stopId = "20799",
        currentGtfsRouteId = "T3",
        legacyRouteId = "13",
      ),
    )
}
