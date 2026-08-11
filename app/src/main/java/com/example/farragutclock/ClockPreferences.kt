package com.example.farragutclock

import android.content.Context

internal object ClockPreferences {
  private const val PREFERENCES_NAME = "clock_preferences"
  private const val KEY_USE_24_HOUR_TIME = "use_24_hour_time"

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
}
