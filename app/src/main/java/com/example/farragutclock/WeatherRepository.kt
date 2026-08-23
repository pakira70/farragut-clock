package com.example.farragutclock

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets

internal data class WeatherSnapshot(
  val temperatureF: Double,
  val weatherCode: Int,
  val highF: Double,
  val lowF: Double,
  val fetchedAtMillis: Long,
  val usAqi: Int? = null,
)

internal class WeatherRepository(context: Context) {
  private val preferences =
    context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

  fun cachedWeather(): WeatherSnapshot? {
    if (!preferences.contains(KEY_FETCHED_AT)) return null

    return WeatherSnapshot(
      temperatureF = preferences.getFloat(KEY_TEMPERATURE, 0f).toDouble(),
      weatherCode = preferences.getInt(KEY_WEATHER_CODE, 0),
      highF = preferences.getFloat(KEY_HIGH, 0f).toDouble(),
      lowF = preferences.getFloat(KEY_LOW, 0f).toDouble(),
      fetchedAtMillis = preferences.getLong(KEY_FETCHED_AT, 0L),
      usAqi =
        if (preferences.contains(KEY_US_AQI)) {
          preferences.getInt(KEY_US_AQI, 0)
        } else {
          null
        },
    )
  }

  fun isStale(weather: WeatherSnapshot, nowMillis: Long = System.currentTimeMillis()): Boolean {
    val age = nowMillis - weather.fetchedAtMillis
    return weather.usAqi == null || age < 0L || age >= CACHE_MAX_AGE_MILLIS
  }

  fun isDisplayExpired(
    weather: WeatherSnapshot,
    nowMillis: Long = System.currentTimeMillis(),
  ): Boolean {
    val age = nowMillis - weather.fetchedAtMillis
    return age < 0L || age >= DISPLAY_MAX_AGE_MILLIS
  }

  suspend fun refreshWeather(): WeatherSnapshot =
    withContext(Dispatchers.IO) {
      val weather = fetchJson(buildForecastUrl(), ::parseWeather)
      val usAqi =
        try {
          fetchJson(buildAirQualityUrl(), ::parseUsAqi)
        } catch (exception: Exception) {
          Log.w(TAG, "Failed to fetch Open-Meteo US AQI", exception)
          null
        }
      weather.copy(usAqi = usAqi).also(::cacheWeather)
    }

  private fun buildForecastUrl(): String {
    val encodedTimezone =
      URLEncoder.encode(WeatherConfig.timezone, StandardCharsets.UTF_8.name())
    return "$OPEN_METEO_URL" +
      "?latitude=${WeatherConfig.latitude}" +
      "&longitude=${WeatherConfig.longitude}" +
      "&current=temperature_2m,weather_code" +
      "&daily=temperature_2m_max,temperature_2m_min" +
      "&temperature_unit=fahrenheit" +
      "&timezone=$encodedTimezone" +
      "&forecast_days=1"
  }

  private fun buildAirQualityUrl(): String {
    val encodedTimezone =
      URLEncoder.encode(WeatherConfig.timezone, StandardCharsets.UTF_8.name())
    return "$OPEN_METEO_AIR_QUALITY_URL" +
      "?latitude=${WeatherConfig.latitude}" +
      "&longitude=${WeatherConfig.longitude}" +
      "&current=us_aqi" +
      "&timezone=$encodedTimezone"
  }

  private fun parseWeather(response: String): WeatherSnapshot {
    val root = JSONObject(response)
    val current = root.getJSONObject("current")
    val daily = root.getJSONObject("daily")

    return WeatherSnapshot(
      temperatureF = current.getDouble("temperature_2m"),
      weatherCode = current.getInt("weather_code"),
      highF = daily.getJSONArray("temperature_2m_max").getDouble(0),
      lowF = daily.getJSONArray("temperature_2m_min").getDouble(0),
      fetchedAtMillis = System.currentTimeMillis(),
    )
  }

  private fun parseUsAqi(response: String): Int {
    val current = JSONObject(response).getJSONObject("current")
    if (current.isNull("us_aqi")) error("Open-Meteo omitted us_aqi")
    return current.getDouble("us_aqi").toInt()
  }

  private fun <T> fetchJson(url: String, parse: (String) -> T): T {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
      connection.connectTimeout = NETWORK_TIMEOUT_MILLIS
      connection.readTimeout = NETWORK_TIMEOUT_MILLIS
      connection.requestMethod = "GET"
      connection.setRequestProperty("User-Agent", "farragut-clock")
      connection.setRequestProperty("Accept", "application/json")

      if (connection.responseCode !in 200..299) {
        error("Open-Meteo returned HTTP ${connection.responseCode}")
      }

      return connection.inputStream.bufferedReader().use { it.readText() }.let(parse)
    } finally {
      connection.disconnect()
    }
  }

  private fun cacheWeather(weather: WeatherSnapshot) {
    val editor =
      preferences
        .edit()
        .putFloat(KEY_TEMPERATURE, weather.temperatureF.toFloat())
        .putInt(KEY_WEATHER_CODE, weather.weatherCode)
        .putFloat(KEY_HIGH, weather.highF.toFloat())
        .putFloat(KEY_LOW, weather.lowF.toFloat())
        .putLong(KEY_FETCHED_AT, weather.fetchedAtMillis)
    if (weather.usAqi == null) {
      editor.remove(KEY_US_AQI)
    } else {
      editor.putInt(KEY_US_AQI, weather.usAqi)
    }
    editor.apply()
  }

  internal companion object {
    const val CACHE_MAX_AGE_MILLIS = 30L * 60L * 1_000L
    const val DISPLAY_MAX_AGE_MILLIS = 2L * 60L * 60L * 1_000L
    private const val TAG = "FarragutWeather"

    private const val OPEN_METEO_URL = "https://api.open-meteo.com/v1/forecast"
    private const val OPEN_METEO_AIR_QUALITY_URL =
      "https://air-quality-api.open-meteo.com/v1/air-quality"
    private const val NETWORK_TIMEOUT_MILLIS = 10_000
    private const val PREFERENCES_NAME = "weather_cache"
    private const val KEY_TEMPERATURE = "temperature"
    private const val KEY_WEATHER_CODE = "weather_code"
    private const val KEY_HIGH = "high"
    private const val KEY_LOW = "low"
    private const val KEY_FETCHED_AT = "fetched_at"
    private const val KEY_US_AQI = "us_aqi"
  }
}

internal fun weatherCondition(weatherCode: Int): String =
  when (weatherCode) {
    0 -> "Clear"
    1, 2 -> "Mostly clear"
    3 -> "Cloudy"
    45, 48 -> "Fog"
    51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> "Rain"
    71, 73, 75, 77, 85, 86 -> "Snow"
    95, 96, 99 -> "Thunderstorms"
    else -> "Cloudy"
  }

internal fun usAqiCategory(usAqi: Int): String =
  when {
    usAqi <= 50 -> "Good"
    usAqi <= 100 -> "Moderate"
    usAqi <= 150 -> "Sensitive"
    usAqi <= 200 -> "Unhealthy"
    usAqi <= 300 -> "Very unhealthy"
    else -> "Hazardous"
  }
