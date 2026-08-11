package com.example.farragutclock

import android.content.Context

internal object ClockPreferences {
  const val MIN_NIGHT_BRIGHTNESS = 0.01f
  const val MAX_NIGHT_BRIGHTNESS = 0.30f
  const val DEFAULT_NIGHT_BRIGHTNESS = 0.05f

  private const val PREFERENCES_NAME = "clock_preferences"
  private const val KEY_USE_24_HOUR_TIME = "use_24_hour_time"
  private const val KEY_NIGHT_BRIGHTNESS = "night_brightness"
  private const val KEY_USE_TROLLEY_DISPLAY = "use_trolley_display"

  fun uses24HourTime(context: Context): Boolean =
    context
      .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
      .getBoolean(KEY_USE_24_HOUR_TIME, false)

  fun setUses24HourTime(context: Context, enabled: Boolean) {
    context
      .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
      .edit()
      .putBoolean(KEY_USE_24_HOUR_TIME, enabled)
      .apply()
  }

  fun usesTrolleyDisplay(context: Context): Boolean =
    context
      .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
      .getBoolean(KEY_USE_TROLLEY_DISPLAY, false)

  fun setUsesTrolleyDisplay(context: Context, enabled: Boolean) {
    context
      .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
      .edit()
      .putBoolean(KEY_USE_TROLLEY_DISPLAY, enabled)
      .apply()
  }

  fun nightBrightness(context: Context): Float =
    context
      .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
      .getFloat(KEY_NIGHT_BRIGHTNESS, DEFAULT_NIGHT_BRIGHTNESS)
      .coerceIn(MIN_NIGHT_BRIGHTNESS, MAX_NIGHT_BRIGHTNESS)

  fun setNightBrightness(context: Context, brightness: Float) {
    context
      .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
      .edit()
      .putFloat(
        KEY_NIGHT_BRIGHTNESS,
        brightness.coerceIn(MIN_NIGHT_BRIGHTNESS, MAX_NIGHT_BRIGHTNESS),
      )
      .apply()
  }
}
