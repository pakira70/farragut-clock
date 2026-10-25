package com.example.farragutclock

import android.content.Context

internal object ClockPreferences {
  const val MIN_NIGHT_BRIGHTNESS = 0.01f
  const val MAX_NIGHT_BRIGHTNESS = 0.30f
  const val DEFAULT_NIGHT_BRIGHTNESS = 0.05f

  private const val PREFERENCES_NAME = "clock_preferences"
  internal const val KEY_USE_24_HOUR_TIME = "use_24_hour_time"
  internal const val KEY_NIGHT_BRIGHTNESS = "night_brightness"
  internal const val KEY_USE_TROLLEY_DISPLAY = "use_trolley_display"
  internal const val KEY_SELECTED_SONOS_ROOM_IDS = "selected_sonos_room_ids"

  // No saved selection means the household defaults; an empty saved set disables Sonos.
  @Synchronized
  fun selectedSonosRoomIds(context: Context): Set<String>? =
    SettingsBackupStorage.preferences(context, PREFERENCES_NAME)
      .getStringSet(KEY_SELECTED_SONOS_ROOM_IDS, null)
      ?.toSet()

  @Synchronized
  fun setSelectedSonosRoomIds(context: Context, roomIds: Set<String>) {
    SettingsBackupStorage.preferences(context, PREFERENCES_NAME)
      .edit()
      .putStringSet(KEY_SELECTED_SONOS_ROOM_IDS, roomIds.toSet())
      .apply()
  }

  @Synchronized
  fun uses24HourTime(context: Context): Boolean =
    SettingsBackupStorage.preferences(context, PREFERENCES_NAME)
      .getBoolean(KEY_USE_24_HOUR_TIME, false)

  @Synchronized
  fun setUses24HourTime(context: Context, enabled: Boolean) {
    SettingsBackupStorage.preferences(context, PREFERENCES_NAME)
      .edit()
      .putBoolean(KEY_USE_24_HOUR_TIME, enabled)
      .apply()
  }

  @Synchronized
  fun usesTrolleyDisplay(context: Context): Boolean =
    SettingsBackupStorage.preferences(context, PREFERENCES_NAME)
      .getBoolean(KEY_USE_TROLLEY_DISPLAY, false)

  @Synchronized
  fun setUsesTrolleyDisplay(context: Context, enabled: Boolean) {
    SettingsBackupStorage.preferences(context, PREFERENCES_NAME)
      .edit()
      .putBoolean(KEY_USE_TROLLEY_DISPLAY, enabled)
      .apply()
  }

  @Synchronized
  fun nightBrightness(context: Context): Float =
    SettingsBackupStorage.preferences(context, PREFERENCES_NAME)
      .getFloat(KEY_NIGHT_BRIGHTNESS, DEFAULT_NIGHT_BRIGHTNESS)
      .coerceIn(MIN_NIGHT_BRIGHTNESS, MAX_NIGHT_BRIGHTNESS)

  @Synchronized
  fun setNightBrightness(context: Context, brightness: Float) {
    SettingsBackupStorage.preferences(context, PREFERENCES_NAME)
      .edit()
      .putFloat(
        KEY_NIGHT_BRIGHTNESS,
        brightness.coerceIn(MIN_NIGHT_BRIGHTNESS, MAX_NIGHT_BRIGHTNESS),
      )
      .apply()
  }
}
