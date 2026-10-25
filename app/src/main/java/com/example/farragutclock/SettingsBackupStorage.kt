package com.example.farragutclock

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Imports use one commit, so display and recognition choices cannot be restored separately. */
internal object SettingsBackupStorage {
  internal const val PREFERENCES_NAME = "settings_backup_preferences"
  private const val VERSION_KEY = "settings_version"
  private val restoredRevision = MutableStateFlow(0L)
  val revision: StateFlow<Long> = restoredRevision.asStateFlow()

  fun preferences(context: Context, legacyName: String): SharedPreferences {
    val restored = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    return if (restored.getInt(VERSION_KEY, 0) == SettingsBackupCodec.VERSION) restored
    else context.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
  }

  fun snapshot(context: Context): SettingsBackup = synchronized(RecognitionPreferences) {
    synchronized(ClockPreferences) {
      SettingsBackupCodec.validate(SettingsBackup(
        ClockPreferences.uses24HourTime(context),
        ClockPreferences.usesTrolleyDisplay(context),
        ClockPreferences.nightBrightness(context),
        ClockPreferences.selectedSonosRoomIds(context),
        RecognitionPreferences.provider(context),
        RecognitionPreferences.isEnabled(context),
      ))
    }
  }

  fun write(context: Context, uri: Uri) {
    val bytes = SettingsBackupCodec.encode(snapshot(context))
    val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Could not open backup.")
    output.use { it.write(bytes); it.flush() }
  }

  fun readAndRestore(context: Context, uri: Uri, checkActive: () -> Unit = {}): SettingsBackup {
    val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Could not open backup.")
    val settings = input.use(SettingsBackupCodec::read)
    checkActive()
    restore(context, settings, checkActive)
    return settings
  }

  fun restore(context: Context, settings: SettingsBackup, checkActive: () -> Unit = {}) {
    // Fully validate before obtaining an editor or touching any saved options.
    val safe = SettingsBackupCodec.validate(settings)
    synchronized(RecognitionPreferences) {
      synchronized(ClockPreferences) {
        checkActive()
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val before = preferences.all.toMap()
        val editor = preferences.edit()
          .putInt(VERSION_KEY, SettingsBackupCodec.VERSION)
          .putBoolean(ClockPreferences.KEY_USE_24_HOUR_TIME, safe.use24HourTime)
          .putBoolean(ClockPreferences.KEY_USE_TROLLEY_DISPLAY, safe.useTrolleyDisplay)
          .putFloat(ClockPreferences.KEY_NIGHT_BRIGHTNESS, safe.nightBrightness)
          .putString(RecognitionPreferences.PROVIDER, safe.recognitionProvider.name)
          .putBoolean(RecognitionPreferences.ENABLED, safe.recognitionEnabled)
        // Absence in the complete imported store explicitly means household defaults.
        if (safe.selectedSonosRoomIds == null) editor.remove(ClockPreferences.KEY_SELECTED_SONOS_ROOM_IDS)
        else editor.putStringSet(ClockPreferences.KEY_SELECTED_SONOS_ROOM_IDS, safe.selectedSonosRoomIds)
        checkActive()
        if (!editor.commit()) {
          // SharedPreferences updates memory even if persistence fails. Roll that map back too.
          val rollback = preferences.edit().clear()
          before.forEach { (key, value) ->
            when (value) {
              is String -> rollback.putString(key, value)
              is Boolean -> rollback.putBoolean(key, value)
              is Float -> rollback.putFloat(key, value)
              is Int -> rollback.putInt(key, value)
              is Long -> rollback.putLong(key, value)
              is Set<*> -> rollback.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
          }
          rollback.commit()
          throw IOException("Could not save restored settings.")
        }
        // A completed commit must refresh a replacement Activity even if its old scope cancelled.
        restoredRevision.value = restoredRevision.value + 1L
      }
    }
  }
}
