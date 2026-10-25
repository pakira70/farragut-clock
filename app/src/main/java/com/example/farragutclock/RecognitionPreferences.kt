package com.example.farragutclock

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal enum class RecognitionProvider { AUDD, AUDIO_TAG, YOUTUBE_MUSIC }
internal class RecognitionProviderChangedException : IllegalStateException(
  "The recognition provider changed. Start a new recognition test.",
)

/** Recognition consent and bounded trial usage; user credentials never enter shared preferences. */
internal object RecognitionPreferences {
  private const val PREFERENCES = "recognition_preferences"
  internal const val ENABLED = "enabled"
  internal const val PROVIDER = "provider"
  private const val TRIAL_DAY = "trial_day_utc"
  private const val TRIAL_COUNT = "trial_requests"
  private const val AUDD_COUNT = "audd_requests_lifetime"
  private const val AUDIO_TAG_MONTH = "audiotag_month_utc"
  private const val AUDIO_TAG_COUNT = "audiotag_requests"
  private const val TOKEN_FILE = "recognition_credential"
  private const val KEY_ALIAS = "farragut_recognition_credential_v1"
  private const val TOKEN_VERSION = 1
  const val DAILY_TRIAL_REQUESTS = 5
  const val AUDD_FREE_REQUESTS = 300
  const val AUDIO_TAG_SAMPLE_SECONDS = 15
  const val MONTHLY_AUDIO_TAG_REQUESTS = 700

  fun sampleDurationSeconds(context: Context): Int = when (provider(context)) {
    RecognitionProvider.AUDD -> 10
    RecognitionProvider.AUDIO_TAG -> AUDIO_TAG_SAMPLE_SECONDS
    RecognitionProvider.YOUTUBE_MUSIC -> 0 // YouTube Music owns its microphone capture.
  }

  @Synchronized
  fun provider(context: Context): RecognitionProvider =
    runCatching {
      RecognitionProvider.valueOf(
        SettingsBackupStorage.preferences(context, PREFERENCES)
          .getString(PROVIDER, RecognitionProvider.AUDD.name)!!,
      )
    }.getOrDefault(RecognitionProvider.AUDD)

  @Synchronized
  fun setProvider(context: Context, provider: RecognitionProvider) {
    SettingsBackupStorage.preferences(context, PREFERENCES)
      .edit().putString(PROVIDER, provider.name).apply()
  }

  @Synchronized
  fun isEnabled(context: Context): Boolean =
    SettingsBackupStorage.preferences(context, PREFERENCES).getBoolean(ENABLED, false)

  @Synchronized
  fun setEnabled(context: Context, enabled: Boolean) {
    SettingsBackupStorage.preferences(context, PREFERENCES)
      .edit().putBoolean(ENABLED, enabled).apply()
  }

  /** Returns only the current provider's saved key, for settings display. */
  @Synchronized
  fun apiToken(context: Context): String? = readApiToken(context, provider(context))

  /** Checks provider identity and reads its key under the same lock used by setProvider. */
  @Synchronized
  fun apiToken(context: Context, expectedProvider: RecognitionProvider): String? {
    requireProvider(context, expectedProvider)
    return readApiToken(context, expectedProvider)
  }

  @Synchronized
  fun requireProvider(context: Context, expectedProvider: RecognitionProvider) {
    if (provider(context) != expectedProvider) throw RecognitionProviderChangedException()
  }

  private fun readApiToken(context: Context, provider: RecognitionProvider): String? {
    if (provider == RecognitionProvider.YOUTUBE_MUSIC) return null
    val file = tokenFile(context, provider)
    if (!file.baseFile.exists()) return null
    return try {
      val payload = file.readFully()
      require(payload.size in 30..4096 && payload[0].toInt() == TOKEN_VERSION)
      val ivSize = payload[1].toInt() and 0xff
      require(ivSize in 12..16 && payload.size > 2 + ivSize)
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(
        Cipher.DECRYPT_MODE,
        encryptionKey(provider),
        GCMParameterSpec(128, payload.copyOfRange(2, 2 + ivSize)),
      )
      val plaintext = cipher.doFinal(payload, 2 + ivSize, payload.size - 2 - ivSize)
      try {
        String(plaintext, Charsets.UTF_8).takeIf { it.isNotBlank() }
      } finally {
        plaintext.fill(0)
      }
    } catch (_: Exception) {
      // A missing Keystore key after device restore must not crash settings or a dream.
      null
    }
  }

  /** AES/GCM ciphertext is excluded from backup, as is its Android Keystore key. */
  @Synchronized
  fun setApiToken(context: Context, token: String?) {
    val provider = provider(context)
    val cleaned = token?.trim().orEmpty()
    if (provider == RecognitionProvider.YOUTUBE_MUSIC) {
      require(cleaned.isEmpty()) { "YouTube Music does not use a recognition API key." }
      return
    }
    val file = tokenFile(context, provider)
    if (cleaned.isEmpty()) {
      file.delete()
      return
    }
    require(cleaned.length <= 512 && cleaned.all { it.code in 33..126 }) {
      "The API key must contain only printable characters without spaces."
    }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(provider))
    val plaintext = cleaned.toByteArray(Charsets.UTF_8)
    val ciphertext = try {
      cipher.doFinal(plaintext)
    } finally {
      plaintext.fill(0)
    }
    val payload = byteArrayOf(TOKEN_VERSION.toByte(), cipher.iv.size.toByte()) + cipher.iv + ciphertext
    val output = file.startWrite()
    try {
      output.write(payload)
      file.finishWrite(output)
    } catch (exception: Exception) {
      file.failWrite(output)
      throw exception
    }
  }

  @Synchronized
  fun remainingTrialRequests(context: Context): Int =
    quota(context).remaining(System.currentTimeMillis())

  /** Reserve durably before sending. Cancellation/network errors still count as an attempt. */
  @Synchronized
  fun reserveTrialRequest(context: Context): Boolean {
    requireProvider(context, RecognitionProvider.AUDD)
    val reserved = quota(context).reserve(System.currentTimeMillis()) ?: return false
    return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
      .putLong(TRIAL_DAY, reserved.dayUtc)
      .putInt(TRIAL_COUNT, reserved.used)
      .commit()
  }

  /** The saved-key evaluation allowance is a lifetime total, independent of key or date. */
  @Synchronized
  fun remainingAudDRequests(context: Context): Int = audDQuota(context).remaining()

  /** Reserve before upload; key changes, provider changes and failed requests never reset usage. */
  @Synchronized
  fun reserveAudDRequest(context: Context): Boolean {
    requireProvider(context, RecognitionProvider.AUDD)
    val reserved = audDQuota(context).reserve() ?: return false
    return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
      .putInt(AUDD_COUNT, reserved.used)
      .commit()
  }

  private fun audDQuota(context: Context): RecognitionAudDQuota =
    RecognitionAudDQuota(
      context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getInt(AUDD_COUNT, 0),
    )

  @Synchronized
  fun remainingAudioTagRequests(context: Context): Int =
    audioTagQuota(context).remaining(System.currentTimeMillis())

  /** Hard cap stays below AudioTag's free three-hour monthly allowance for fifteen-second clips. */
  @Synchronized
  fun reserveAudioTagRequest(context: Context): Boolean {
    requireProvider(context, RecognitionProvider.AUDIO_TAG)
    val reserved = audioTagQuota(context).reserve(System.currentTimeMillis()) ?: return false
    return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
      .putLong(AUDIO_TAG_MONTH, reserved.monthUtc)
      .putInt(AUDIO_TAG_COUNT, reserved.used)
      .commit()
  }

  private fun audioTagQuota(context: Context): RecognitionAudioTagQuota {
    val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    return RecognitionAudioTagQuota(
      preferences.getLong(AUDIO_TAG_MONTH, Long.MIN_VALUE),
      preferences.getInt(AUDIO_TAG_COUNT, 0),
    )
  }

  private fun quota(context: Context): RecognitionTrialQuota {
    val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    return RecognitionTrialQuota(
      preferences.getLong(TRIAL_DAY, Long.MIN_VALUE),
      preferences.getInt(TRIAL_COUNT, 0),
    )
  }

  private fun tokenFile(context: Context, provider: RecognitionProvider): AtomicFile =
    AtomicFile(File(context.noBackupFilesDir, when (provider) {
      RecognitionProvider.AUDD -> TOKEN_FILE
      RecognitionProvider.AUDIO_TAG -> "${TOKEN_FILE}_audiotag"
      RecognitionProvider.YOUTUBE_MUSIC -> error("YouTube Music does not store an API key.")
    }))

  private fun encryptionKey(provider: RecognitionProvider): SecretKey {
    val alias = when (provider) {
      RecognitionProvider.AUDD -> KEY_ALIAS
      RecognitionProvider.AUDIO_TAG -> "${KEY_ALIAS}_audiotag"
      RecognitionProvider.YOUTUBE_MUSIC -> error("YouTube Music does not store an API key.")
    }
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey(alias, null) as? SecretKey)?.let { return it }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
      init(
        KeyGenParameterSpec.Builder(
          alias,
          KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
          .setKeySize(256)
          .build(),
      )
    }.generateKey()
  }
}

/** No rollover or credential identity: changing a key cannot grant another free trial in this app. */
internal data class RecognitionAudDQuota(val used: Int) {
  fun remaining(): Int =
    (RecognitionPreferences.AUDD_FREE_REQUESTS - used.coerceAtLeast(0))
      .coerceIn(0, RecognitionPreferences.AUDD_FREE_REQUESTS)

  fun reserve(): RecognitionAudDQuota? =
    if (remaining() > 0) copy(used = used.coerceAtLeast(0) + 1) else null
}

/** UTC month rollover, without resetting allowance when the device clock moves backward. */
internal data class RecognitionAudioTagQuota(val monthUtc: Long, val used: Int) {
  private fun current(nowMillis: Long): RecognitionAudioTagQuota {
    val date = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply { timeInMillis = nowMillis }
    val nowMonth = date.get(Calendar.YEAR).toLong() * 12 + date.get(Calendar.MONTH)
    return if (nowMonth > monthUtc) RecognitionAudioTagQuota(nowMonth, 0) else this
  }

  fun remaining(nowMillis: Long): Int =
    (RecognitionPreferences.MONTHLY_AUDIO_TAG_REQUESTS - current(nowMillis).used.coerceAtLeast(0))
      .coerceIn(0, RecognitionPreferences.MONTHLY_AUDIO_TAG_REQUESTS)

  fun reserve(nowMillis: Long): RecognitionAudioTagQuota? {
    val quota = current(nowMillis)
    return if (quota.remaining(nowMillis) > 0) quota.copy(used = quota.used.coerceAtLeast(0) + 1) else null
  }
}

/** UTC rollover, with no extra allowance if the device clock moves backward. */
internal data class RecognitionTrialQuota(val dayUtc: Long, val used: Int) {
  private fun current(nowMillis: Long): RecognitionTrialQuota {
    val nowDay = Math.floorDiv(nowMillis, 86_400_000L)
    return if (nowDay > dayUtc) RecognitionTrialQuota(nowDay, 0) else this
  }

  fun remaining(nowMillis: Long): Int =
    (RecognitionPreferences.DAILY_TRIAL_REQUESTS - current(nowMillis).used.coerceAtLeast(0))
      .coerceIn(0, RecognitionPreferences.DAILY_TRIAL_REQUESTS)

  fun reserve(nowMillis: Long): RecognitionTrialQuota? {
    val quota = current(nowMillis)
    return if (quota.remaining(nowMillis) > 0) quota.copy(used = quota.used.coerceAtLeast(0) + 1) else null
  }
}
