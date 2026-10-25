package com.example.farragutclock

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Only user choices belong here. Credentials, provider usage, and Android grants are excluded. */
internal data class SettingsBackup(
  val use24HourTime: Boolean,
  val useTrolleyDisplay: Boolean,
  val nightBrightness: Float,
  val selectedSonosRoomIds: Set<String>?,
  val recognitionProvider: RecognitionProvider,
  val recognitionEnabled: Boolean,
)

internal enum class SettingsBackupProblem { INVALID, UNSUPPORTED_VERSION, TOO_LARGE }
internal class SettingsBackupFormatException(val problem: SettingsBackupProblem) :
  IllegalArgumentException("The settings backup is not supported.")

/** A small, strict JSON format with no Android dependency or permissive value coercion. */
internal object SettingsBackupCodec {
  const val VERSION = 1
  const val MAX_BYTES = 16 * 1024
  const val MAX_ROOMS = 64
  private const val FORMAT = "farragut-clock-settings"
  private val roomId = Regex("[A-Za-z0-9_.:-]{1,128}")

  fun validate(settings: SettingsBackup): SettingsBackup {
    if (!settings.nightBrightness.isFinite() ||
        settings.nightBrightness !in ClockPreferences.MIN_NIGHT_BRIGHTNESS..ClockPreferences.MAX_NIGHT_BRIGHTNESS) invalid()
    settings.selectedSonosRoomIds?.let { rooms ->
      if (rooms.size > MAX_ROOMS || rooms.any { !roomId.matches(it) }) invalid()
    }
    return settings.copy(selectedSonosRoomIds = settings.selectedSonosRoomIds?.toSet())
  }

  fun encode(settings: SettingsBackup): ByteArray {
    val safe = validate(settings)
    val rooms = safe.selectedSonosRoomIds?.sorted()?.joinToString(", ", "[", "]") { "\"$it\"" } ?: "null"
    return """
      |{
      |  "format": "$FORMAT",
      |  "version": $VERSION,
      |  "display": {
      |    "use24HourTime": ${safe.use24HourTime},
      |    "useTrolleyDisplay": ${safe.useTrolleyDisplay},
      |    "nightBrightness": ${safe.nightBrightness}
      |  },
      |  "sonos": { "selectedRoomIds": $rooms },
      |  "recognition": {
      |    "provider": "${safe.recognitionProvider.name}",
      |    "enabled": ${safe.recognitionEnabled}
      |  }
      |}
      |
    """.trimMargin().toByteArray(Charsets.UTF_8).also {
      if (it.size > MAX_BYTES) tooLarge()
    }
  }

  fun read(input: InputStream): SettingsBackup {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
      val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
      if (count < 0) break
      if (count == 0) {
        val byte = input.read()
        if (byte < 0) break
        output.write(byte)
      } else output.write(buffer, 0, count)
      if (output.size() > MAX_BYTES) tooLarge()
    }
    return decode(output.toByteArray())
  }

  fun decode(bytes: ByteArray): SettingsBackup {
    if (bytes.size > MAX_BYTES) tooLarge()
    val text = try {
      Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) { invalid() }
    val root = Parser(text).parse().objectWith("format", "version", "display", "sonos", "recognition")
    if (root.getValue("format").text() != FORMAT) invalid()
    if ((root.getValue("version") as? NumberValue)?.value != VERSION.toString()) {
      throw SettingsBackupFormatException(SettingsBackupProblem.UNSUPPORTED_VERSION)
    }
    val display = root.getValue("display").objectWith("use24HourTime", "useTrolleyDisplay", "nightBrightness")
    val sonos = root.getValue("sonos").objectWith("selectedRoomIds")
    val recognition = root.getValue("recognition").objectWith("provider", "enabled")
    val selected = when (val value = sonos.getValue("selectedRoomIds")) {
      NullValue -> null
      is ArrayValue -> value.values.map { it.text() }.let { ids ->
        if (ids.size != ids.toSet().size) invalid()
        ids.toSet()
      }
      else -> invalid()
    }
    val brightness = (display.getValue("nightBrightness") as? NumberValue)?.value?.toFloatOrNull() ?: invalid()
    val provider = try {
      RecognitionProvider.valueOf(recognition.getValue("provider").text())
    } catch (_: IllegalArgumentException) { invalid() }
    return validate(SettingsBackup(
      display.getValue("use24HourTime").boolean(),
      display.getValue("useTrolleyDisplay").boolean(),
      brightness,
      selected,
      provider,
      recognition.getValue("enabled").boolean(),
    ))
  }

  private sealed interface Value
  private data class ObjectValue(val values: Map<String, Value>) : Value
  private data class ArrayValue(val values: List<Value>) : Value
  private data class TextValue(val value: String) : Value
  private data class NumberValue(val value: String) : Value
  private data class BooleanValue(val value: Boolean) : Value
  private data object NullValue : Value

  private fun Value.objectWith(vararg keys: String): Map<String, Value> {
    val values = (this as? ObjectValue)?.values ?: invalid()
    if (values.keys != keys.toSet()) invalid()
    return values
  }
  private fun Value.text(): String = (this as? TextValue)?.value ?: invalid()
  private fun Value.boolean(): Boolean = (this as? BooleanValue)?.value ?: invalid()
  private fun invalid(): Nothing = throw SettingsBackupFormatException(SettingsBackupProblem.INVALID)
  private fun tooLarge(): Nothing = throw SettingsBackupFormatException(SettingsBackupProblem.TOO_LARGE)

  private class Parser(private val text: String) {
    private var position = 0
    private val number = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

    fun parse(): Value {
      val value = value(0)
      whitespace()
      if (position != text.length) invalid()
      return value
    }

    private fun value(depth: Int): Value {
      if (depth > 4) invalid()
      whitespace()
      return when (peek()) {
        '{' -> {
          position++
          val values = linkedMapOf<String, Value>()
          if (!take('}')) {
            do {
              whitespace()
              val key = string()
              if (key in values || values.size >= MAX_ROOMS) invalid()
              expect(':')
              values[key] = value(depth + 1)
            } while (take(','))
            expect('}')
          }
          ObjectValue(values)
        }
        '[' -> {
          position++
          val values = mutableListOf<Value>()
          if (!take(']')) {
            do {
              if (values.size >= MAX_ROOMS) invalid()
              values += value(depth + 1)
            } while (take(','))
            expect(']')
          }
          ArrayValue(values)
        }
        '"' -> TextValue(string())
        't' -> literal("true", BooleanValue(true))
        'f' -> literal("false", BooleanValue(false))
        'n' -> literal("null", NullValue)
        '-', in '0'..'9' -> {
          val match = number.find(text, position)?.takeIf { it.range.first == position } ?: invalid()
          if (match.value.length > 64) invalid()
          position = match.range.last + 1
          NumberValue(match.value)
        }
        else -> invalid()
      }
    }

    private fun string(): String {
      if (peek() != '"') invalid()
      position++
      val result = StringBuilder()
      while (position < text.length) {
        val next = text[position++]
        if (next == '"') return result.toString()
        if (next.code < 32) invalid()
        if (next != '\\') result.append(next) else {
          if (position >= text.length) invalid()
          result.append(when (val escaped = text[position++]) {
            '"', '\\', '/' -> escaped
            'b' -> '\b'
            'f' -> '\u000c'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
              if (position + 4 > text.length) invalid()
              val hex = text.substring(position, position + 4)
              if (hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) invalid()
              val code = hex.toInt(16)
              position += 4
              code.toChar()
            }
            else -> invalid()
          })
        }
        if (result.length > 256) invalid()
      }
      invalid()
    }

    private fun <T : Value> literal(expected: String, value: T): T {
      if (!text.startsWith(expected, position)) invalid()
      position += expected.length
      return value
    }
    private fun peek(): Char? = text.getOrNull(position)
    private fun whitespace() { while (peek() == ' ' || peek() == '\n' || peek() == '\r' || peek() == '\t') position++ }
    private fun take(character: Char): Boolean {
      whitespace()
      if (peek() != character) return false
      position++
      return true
    }
    private fun expect(character: Char) { if (!take(character)) invalid() }
  }
}
