package com.example.farragutclock

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SettingsBackupCodecTest {
  private val settings = SettingsBackup(
    true, true, 0.12f, setOf("RINCON_LIVING", "RINCON_KITCHEN"), RecognitionProvider.YOUTUBE_MUSIC, true,
  )
  private fun json(value: SettingsBackup = settings) = SettingsBackupCodec.encode(value).toString(Charsets.UTF_8)

  @Test
  fun roundtripIncludesEveryChoiceForEachProvider() {
    RecognitionProvider.entries.forEach { provider ->
      val expected = settings.copy(recognitionProvider = provider)
      assertEquals(expected, SettingsBackupCodec.read(ByteArrayInputStream(SettingsBackupCodec.encode(expected))))
    }
  }

  @Test
  fun defaultRoomsAndExplicitlyDisabledRoomsRemainDistinct() {
    val defaults = settings.copy(selectedSonosRoomIds = null)
    val disabled = settings.copy(selectedSonosRoomIds = emptySet())
    assertNotEquals(json(defaults), json(disabled))
    assertEquals(defaults, SettingsBackupCodec.decode(SettingsBackupCodec.encode(defaults)))
    assertEquals(disabled, SettingsBackupCodec.decode(SettingsBackupCodec.encode(disabled)))
  }

  @Test
  fun exportsOnlyAllowlistedChoicesInDeterministicOrder() {
    val text = json()
    assertEquals(text, json(settings.copy(selectedSonosRoomIds = settings.selectedSonosRoomIds!!.reversed().toSet())))
    listOf("token", "apiKey", "credential", "quota", "trial", "recording", "permission", "screenshot").forEach {
      assertFalse(text.contains(it, ignoreCase = true))
    }
    assertTrue(text.contains("\"version\": 1"))
  }

  @Test
  fun rejectsUnexpectedFieldsRatherThanImportingCredentialsOrUsage() {
    rejects(json().replace("\"version\": 1,", "\"version\": 1, \"apiToken\": \"not-a-real-key\","))
    rejects(json().replace("\"enabled\": true", "\"enabled\": true, \"trial_requests\": 0"))
    rejects(json().replace("\"enabled\": true", "\"enabled\": true, \"accessibilityEnabled\": true"))
  }

  @Test
  fun rejectsMissingFieldsDuplicatesAndTrailingDocuments() {
    rejects(json().replace("\"use24HourTime\": true,", ""))
    rejects(json().replace("\"enabled\": true", "\"enabled\": false, \"enabled\": true"))
    rejects(json() + "{}")
    rejects(json().replace("\"display\": {", "\"display\": { /* comment */"))
    rejects(json().replace("\"enabled\": true", "\"enabled\": true,"))
  }

  @Test
  fun rejectsTypeCoercionUnknownProvidersAndVersions() {
    rejects(json().replace("\"enabled\": true", "\"enabled\": \"true\""))
    rejects(json().replace("\"nightBrightness\": 0.12", "\"nightBrightness\": \"0.12\""))
    rejects(json().replace("YOUTUBE_MUSIC", "UNKNOWN_PROVIDER"))
    rejects(json().replace("\"version\": 1", "\"version\": 2"), SettingsBackupProblem.UNSUPPORTED_VERSION)
    rejects(json().replace("\"format\": \"farragut-clock-settings\"", "\"format\": \"another-app\""))
  }

  @Test
  fun rejectsBrightnessOutsideDisplayRangeAndNonfiniteValues() {
    listOf("0", "-1", "0.31", "1e100", "NaN", "null").forEach {
      rejects(json().replace("\"nightBrightness\": 0.12", "\"nightBrightness\": $it"))
    }
    listOf(ClockPreferences.MIN_NIGHT_BRIGHTNESS, ClockPreferences.MAX_NIGHT_BRIGHTNESS).forEach {
      val expected = settings.copy(nightBrightness = it)
      assertEquals(expected, SettingsBackupCodec.decode(SettingsBackupCodec.encode(expected)))
    }
  }

  @Test
  fun rejectsInvalidDuplicateAndExcessiveRoomIdentifiers() {
    listOf("\"\"", "\"room/../../file\"", "\"room with space\"", "\"room\\nname\"", "42", "null").forEach {
      rejects(json().replace("[\"RINCON_KITCHEN\", \"RINCON_LIVING\"]", "[$it]"))
    }
    rejects(json().replace("RINCON_LIVING", "RINCON_KITCHEN"))
    rejects(json().replace("RINCON_LIVING", "x".repeat(129)))
    rejects(json().replace("[\"RINCON_KITCHEN\", \"RINCON_LIVING\"]", (1..65).joinToString(",", "[", "]") { "\"room$it\"" }))
  }

  @Test
  fun rejectsMalformedUtf8EscapesOversizedFilesAndExcessNesting() {
    rejects(byteArrayOf(0xc3.toByte(), 0x28))
    rejects(json().replace("RINCON_LIVING", "RINCON_\\u+041"))
    rejects(json().replace("RINCON_LIVING", "RINCON_\\q"))
    rejects(" ".repeat(SettingsBackupCodec.MAX_BYTES + 1), SettingsBackupProblem.TOO_LARGE)
    try {
      SettingsBackupCodec.read(ByteArrayInputStream(ByteArray(SettingsBackupCodec.MAX_BYTES + 1)))
      fail("Oversized streams must be rejected.")
    } catch (exception: SettingsBackupFormatException) {
      assertEquals(SettingsBackupProblem.TOO_LARGE, exception.problem)
    }
    rejects("[".repeat(12) + "null" + "]".repeat(12))
  }

  private fun rejects(text: String, expected: SettingsBackupProblem = SettingsBackupProblem.INVALID) =
    rejects(text.toByteArray(Charsets.UTF_8), expected)

  private fun rejects(bytes: ByteArray, expected: SettingsBackupProblem = SettingsBackupProblem.INVALID) {
    try {
      SettingsBackupCodec.decode(bytes)
      fail("Invalid backup must be rejected.")
    } catch (exception: SettingsBackupFormatException) {
      assertEquals(expected, exception.problem)
    }
  }
}
