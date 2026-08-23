package com.example.farragutclock

internal object TransitRules {
  const val ARRIVAL_STALE_AFTER_MILLIS = 150_000L
  const val PREDICTION_HORIZON_MILLIS = 45L * 60L * 1_000L
  const val SAME_TRIP_WINDOW_MILLIS = 4L * 60L * 1_000L
  const val PAST_ARRIVAL_GRACE_MILLIS = 60_000L

  fun isFeedExpired(todayDate: Int, feedEndDate: Int?): Boolean =
    feedEndDate != null && todayDate > feedEndDate

  fun isFeedNotYetStarted(todayDate: Int, feedStartDate: Int?): Boolean =
    feedStartDate != null && todayDate < feedStartDate

  fun calendarHasStarted(
    serviceDate: Int,
    calendarStartDate: Int,
    feedNotYetStarted: Boolean,
  ): Boolean = serviceDate >= calendarStartDate || feedNotYetStarted

  fun isLikelySameTrip(
    firstTripId: String?,
    firstDirectionId: Int?,
    firstArrivalMillis: Long,
    secondTripId: String?,
    secondDirectionId: Int?,
    secondArrivalMillis: Long,
  ): Boolean {
    if (firstTripId != null && firstTripId == secondTripId) return true
    if (
      firstDirectionId != null &&
        secondDirectionId != null &&
        firstDirectionId != secondDirectionId
    ) {
      return false
    }
    return kotlin.math.abs(firstArrivalMillis - secondArrivalMillis) < SAME_TRIP_WINDOW_MILLIS
  }

  fun isImminentPrediction(arrivalTimeMillis: Long, nowMillis: Long): Boolean {
    val millisUntilArrival = arrivalTimeMillis - nowMillis
    return millisUntilArrival >= -PAST_ARRIVAL_GRACE_MILLIS &&
      millisUntilArrival <= PREDICTION_HORIZON_MILLIS
  }

  fun hasEnoughLivePredictions(
    predicted: List<TransitArrival>,
    nowMillis: Long,
    needed: Int = 2,
  ): Boolean =
    predicted.count { arrival ->
      arrival.tracked && isImminentPrediction(arrival.arrivalTimeMillis, nowMillis)
    } >= needed

  fun isDisplayStale(lastSuccessMillis: Long?, nowMillis: Long): Boolean =
    lastSuccessMillis == null || nowMillis - lastSuccessMillis >= ARRIVAL_STALE_AFTER_MILLIS ||
      nowMillis < lastSuccessMillis
}
