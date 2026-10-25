package com.example.farragutclock

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Real ContentResolver/file/prefs roundtrips; no microphone, network, or permission changes. */
class SettingsBackupStorageTest {
  private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
  private val preferenceNames = listOf("clock_preferences", "recognition_preferences", SettingsBackupStorage.PREFERENCES_NAME)

  @Test
  fun realFileRoundtripPreservesKeysAndUsageAndNormalSettersKeepWorking() = preservingOriginals {
    val expected = SettingsBackup(true, true, 0.12f, setOf("RINCON_TEST_KITCHEN"), RecognitionProvider.YOUTUBE_MUSIC, false)
    val quotas = context.getSharedPreferences("recognition_preferences", Context.MODE_PRIVATE)
    assertTrue(quotas.edit().putInt("audd_requests_lifetime", 123).putInt("trial_requests", 4)
      .putLong("trial_day_utc", 20_000).putInt("audiotag_requests", 321).putLong("audiotag_month_utc", 24_000).commit())
    val beforeUsage = quotas.all.toMap()
    val beforeKeys = credentials()
    SettingsBackupStorage.restore(context, expected)
    val file = File.createTempFile("settings-roundtrip-", ".json", context.cacheDir)
    try {
      SettingsBackupStorage.write(context, Uri.fromFile(file))
      ClockPreferences.setUses24HourTime(context, false)
      ClockPreferences.setSelectedSonosRoomIds(context, emptySet())
      RecognitionPreferences.setProvider(context, RecognitionProvider.AUDIO_TAG)
      assertFalse(ClockPreferences.uses24HourTime(context))
      assertEquals(RecognitionProvider.AUDIO_TAG, RecognitionPreferences.provider(context))
      val beforeRevision = SettingsBackupStorage.revision.value
      assertEquals(expected, SettingsBackupStorage.readAndRestore(context, Uri.fromFile(file)))
      assertEquals(beforeRevision + 1L, SettingsBackupStorage.revision.value)
      assertEquals(expected, SettingsBackupStorage.snapshot(context))
      assertEquals(beforeUsage, quotas.all.toMap())
      assertCredentialsUnchanged(beforeKeys)
    } finally { file.delete() }
  }

  @Test
  fun invalidFileCannotChangeAnyPreference() = preservingOriginals {
    val before = preferenceNames.associateWith { preferences(it).all.toMap() }
    val beforeKeys = credentials()
    val file = File.createTempFile("settings-invalid-", ".json", context.cacheDir)
    try {
      val valid = SettingsBackupCodec.encode(SettingsBackupStorage.snapshot(context)).toString(Charsets.UTF_8)
      file.writeText(valid.replace("\"enabled\":", "\"unexpectedQuotaReset\": 0, \"enabled\":"))
      try {
        SettingsBackupStorage.readAndRestore(context, Uri.fromFile(file))
        fail("Unknown fields must be rejected before any preference changes.")
      } catch (_: SettingsBackupFormatException) { }
      before.forEach { (name, values) -> assertEquals(values, preferences(name).all.toMap()) }
      assertCredentialsUnchanged(beforeKeys)
    } finally { file.delete() }
  }

  @Test
  fun cancellationAfterFileReadCannotApplySettings() = preservingOriginals {
    val before = preferenceNames.associateWith { preferences(it).all.toMap() }
    val beforeRevision = SettingsBackupStorage.revision.value
    val file = File.createTempFile("settings-cancelled-", ".json", context.cacheDir)
    try {
      file.writeBytes(SettingsBackupCodec.encode(SettingsBackupStorage.snapshot(context).copy(use24HourTime = true)))
      try {
        SettingsBackupStorage.readAndRestore(context, Uri.fromFile(file)) { throw CancellationException() }
        fail("Cancellation must prevent committing a completed read.")
      } catch (_: CancellationException) { }
      before.forEach { (name, values) -> assertEquals(values, preferences(name).all.toMap()) }
      assertEquals(beforeRevision, SettingsBackupStorage.revision.value)
    } finally { file.delete() }
  }

  @Test
  fun restoresDefaultAndExplicitlyDisabledRoomsWithoutFallingBackToLegacySelection() = preservingOriginals {
    val original = SettingsBackupStorage.snapshot(context).copy(recognitionEnabled = false)
    SettingsBackupStorage.restore(context, original.copy(selectedSonosRoomIds = null))
    assertEquals(null, ClockPreferences.selectedSonosRoomIds(context))
    SettingsBackupStorage.restore(context, original.copy(selectedSonosRoomIds = emptySet()))
    assertEquals(emptySet<String>(), ClockPreferences.selectedSonosRoomIds(context))
    ClockPreferences.setSelectedSonosRoomIds(context, setOf("RINCON_TEST_ROOM"))
    assertEquals(setOf("RINCON_TEST_ROOM"), ClockPreferences.selectedSonosRoomIds(context))
  }

  /** Supply a private app file URI to validate the exact exported user backup without bundling IDs. */
  @Test
  fun suppliedCurrentSettingsFileImportsWithoutChangingKeysOrUsage() = preservingOriginals {
    val uri = InstrumentationRegistry.getArguments().getString("settingsBackupFixtureUri") ?: return@preservingOriginals
    val beforeUsage = preferences("recognition_preferences").all.toMap()
    val beforeKeys = credentials()
    val source = Uri.parse(uri)
    val expected = context.contentResolver.openInputStream(source)!!.use(SettingsBackupCodec::read)
    assertEquals(expected, SettingsBackupStorage.readAndRestore(context, source))
    assertEquals(expected, SettingsBackupStorage.snapshot(context))
    assertEquals(beforeUsage, preferences("recognition_preferences").all.toMap())
    assertCredentialsUnchanged(beforeKeys)
  }

  private fun preferences(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

  private fun credentials(): Map<String, ByteArray> = context.noBackupFilesDir.listFiles().orEmpty()
    .filter { it.name.startsWith("recognition_credential") }.associate { it.name to it.readBytes() }

  private fun assertCredentialsUnchanged(before: Map<String, ByteArray>) {
    val after = credentials()
    assertEquals(before.keys, after.keys)
    before.forEach { (name, value) -> assertTrue("Saved credential bytes must be unchanged.", value.contentEquals(after.getValue(name))) }
  }

  private fun preservingOriginals(test: () -> Unit) {
    val before = preferenceNames.associateWith { preferences(it).all.toMap() }
    try { test() } finally {
      before.forEach { (name, values) ->
        val editor = preferences(name).edit().clear()
        values.forEach { (key, value) -> restoreValue(editor, key, value) }
        assertTrue("Original settings must be restored.", editor.commit())
      }
    }
  }

  private fun restoreValue(editor: SharedPreferences.Editor, key: String, value: Any?) {
    when (value) {
      is String -> editor.putString(key, value)
      is Boolean -> editor.putBoolean(key, value)
      is Float -> editor.putFloat(key, value)
      is Int -> editor.putInt(key, value)
      is Long -> editor.putLong(key, value)
      is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
    }
  }
}
