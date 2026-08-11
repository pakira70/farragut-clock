package com.example.farragutclock

import android.content.Context
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
    )
  }

  fun isStale(weather: WeatherSnapshot, nowMillis: Long = System.currentTimeMillis()): Boolean {
    val age = nowMillis - weather.fetchedAtMillis
    return age < 0L || age >= CACHE_MAX_AGE_MILLIS
  }

  suspend fun refreshWeather(): WeatherSnapshot =
    withContext(Dispatchers.IO) {
      val connection = URL(buildForecastUrl()).openConnection() as HttpURLConnection
      try {
        connection.connectTimeout = NETWORK_TIMEOUT_MILLIS
        connection.readTimeout = NETWORK_TIMEOUT_MILLIS
        connection.requestMethod = "GET"

        if (connection.responseCode !in 200..299) {
          error("Open-Meteo returned HTTP ${connection.responseCode}")
        }

        val response = connection.inputStream.bufferedReader().use { it.readText() }
        parseWeather(response).also(::cacheWeather)
      } finally {
        connection.disconnect()
      }
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

  private fun cacheWeather(weather: WeatherSnapshot) {
    preferences
      .edit()
      .putFloat(KEY_TEMPERATURE, weather.temperatureF.toFloat())
      .putInt(KEY_WEATHER_CODE, weather.weatherCode)
      .putFloat(KEY_HIGH, weather.highF.toFloat())
      .putFloat(KEY_LOW, weather.lowF.toFloat())
      .putLong(KEY_FETCHED_AT, weather.fetchedAtMillis)
      .apply()
  }

  internal companion object {
    const val CACHE_MAX_AGE_MILLIS = 30L * 60L * 1_000L

    private const val OPEN_METEO_URL = "https://api.open-meteo.com/v1/forecast"
    private const val NETWORK_TIMEOUT_MILLIS = 10_000
    private const val PREFERENCES_NAME = "weather_cache"
    private const val KEY_TEMPERATURE = "temperature"
    private const val KEY_WEATHER_CODE = "weather_code"
    private const val KEY_HIGH = "high"
    private const val KEY_LOW = "low"
    private const val KEY_FETCHED_AT = "fetched_at"
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
