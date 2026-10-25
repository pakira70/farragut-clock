package com.example.farragutclock

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun RecognitionSettings(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val scope = rememberCoroutineScope()
  var enabled by remember { mutableStateOf(RecognitionPreferences.isEnabled(context)) }
  var provider by remember { mutableStateOf(RecognitionPreferences.provider(context)) }
  val bridgeConnected by YouTubeMusicBridgeService.isConnected.collectAsStateWithLifecycle()
  var bridgeConfigured by remember { mutableStateOf(isYtmBridgeConfigured(context)) }
  var youtubeMusicAvailable by remember { mutableStateOf(isYtmAvailable(context)) }
  var pendingYtmEnable by rememberSaveable { mutableStateOf(false) }
  var hasToken by remember { mutableStateOf(false) }
  var trialRemaining by remember { mutableIntStateOf(RecognitionPreferences.remainingTrialRequests(context)) }
  var audDRemaining by remember { mutableIntStateOf(RecognitionPreferences.remainingAudDRequests(context)) }
  var audioTagRemaining by remember { mutableIntStateOf(RecognitionPreferences.remainingAudioTagRequests(context)) }
  // A saved credential is never loaded into the text field or the UI hierarchy.
  var tokenInput by remember { mutableStateOf("") }
  var saving by remember { mutableStateOf(false) }
  var status by remember { mutableStateOf("") }
  var isError by remember { mutableStateOf(false) }
  val permissionNeeded = stringResource(R.string.recognition_auto_permission_needed)
  val keySaved = stringResource(R.string.recognition_key_saved)
  val keySaveFailed = stringResource(R.string.recognition_key_save_failed)
  val keyRemoved = stringResource(
    if (provider == RecognitionProvider.AUDD) R.string.recognition_key_removed else R.string.recognition_audio_tag_key_removed,
  )
  val dashboardFailed = stringResource(
    if (provider == RecognitionProvider.AUDD) R.string.recognition_dashboard_failed else R.string.recognition_audio_tag_dashboard_failed,
  )
  val ytmConnecting = stringResource(R.string.ytm_auto_connecting)
  val ytmSetupNeeded = stringResource(R.string.ytm_auto_setup_needed)
  val ytmSettingsUnavailable = stringResource(R.string.ytm_auto_settings_unavailable)
  val ytmUnavailable = stringResource(
    if (Build.VERSION.SDK_INT < 34) R.string.ytm_android_required else R.string.ytm_app_required,
  )

  fun saveEnabled(value: Boolean) {
    RecognitionPreferences.setEnabled(context, value)
    enabled = value
  }

  val microphonePermission = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { granted ->
    saveEnabled(granted)
    isError = !granted
    status = if (granted) "" else permissionNeeded
  }

  fun openYtmBridgeSettings() {
    try {
      context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    } catch (_: Exception) {
      status = ytmSettingsUnavailable
      isError = true
    }
  }

  fun changeEnabled(value: Boolean) {
    status = ""
    isError = false
    if (!value) {
      pendingYtmEnable = false
      saveEnabled(false)
    } else if (provider == RecognitionProvider.YOUTUBE_MUSIC) {
      when {
        !youtubeMusicAvailable -> {
          saveEnabled(false)
          status = ytmUnavailable
          isError = true
        }
        bridgeConnected -> {
          pendingYtmEnable = false
          saveEnabled(true)
        }
        else -> {
          saveEnabled(false)
          pendingYtmEnable = true
          bridgeConfigured = isYtmBridgeConfigured(context)
          status = if (bridgeConfigured) ytmConnecting else ytmSetupNeeded
          // An explicitly enabled bridge only needs time to connect, not another setup prompt.
          if (!bridgeConfigured) openYtmBridgeSettings()
        }
      }
    } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
      saveEnabled(true)
    } else {
      microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }
  }

  fun changeProvider(selected: RecognitionProvider) {
    if (selected == provider || saving) return
    RecognitionPreferences.setProvider(context, selected)
    provider = selected
    pendingYtmEnable = false
    tokenInput = ""
    hasToken = false
    status = ""
    isError = false
    trialRemaining = RecognitionPreferences.remainingTrialRequests(context)
    audDRemaining = RecognitionPreferences.remainingAudDRequests(context)
    audioTagRemaining = RecognitionPreferences.remainingAudioTagRequests(context)
    scope.launch {
      val stored = withContext(Dispatchers.IO) { RecognitionPreferences.apiToken(context) != null }
      if (provider == selected) hasToken = stored
    }
    if (enabled) changeEnabled(true)
  }

  LaunchedEffect(bridgeConnected, provider, pendingYtmEnable, youtubeMusicAvailable) {
    if (bridgeConnected) bridgeConfigured = true
    if (provider == RecognitionProvider.YOUTUBE_MUSIC && pendingYtmEnable && bridgeConnected && youtubeMusicAvailable) {
      pendingYtmEnable = false
      saveEnabled(true)
      status = ""
      isError = false
    }
  }

  LaunchedEffect(lifecycle) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      enabled = RecognitionPreferences.isEnabled(context)
      provider = RecognitionPreferences.provider(context)
      bridgeConfigured = isYtmBridgeConfigured(context)
      youtubeMusicAvailable = isYtmAvailable(context)
      hasToken = withContext(Dispatchers.IO) { RecognitionPreferences.apiToken(context) != null }
      trialRemaining = RecognitionPreferences.remainingTrialRequests(context)
      audDRemaining = RecognitionPreferences.remainingAudDRequests(context)
      audioTagRemaining = RecognitionPreferences.remainingAudioTagRequests(context)
      awaitCancellation()
    }
  }

  Column(modifier) {
    Text(stringResource(R.string.recognition_auto_title), style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
      onClick = {
        context.startActivity(Intent(context, YtmRecognitionTestActivity::class.java))
      },
      modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.ytm_prototype_settings_button)) }
    Text(
      stringResource(R.string.ytm_prototype_settings_help),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    Text(
      stringResource(if (provider == RecognitionProvider.YOUTUBE_MUSIC) R.string.ytm_auto_help else R.string.recognition_auto_help),
      style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(8.dp))
    Column(Modifier.fillMaxWidth().selectableGroup()) {
      listOf(RecognitionProvider.YOUTUBE_MUSIC, RecognitionProvider.AUDD, RecognitionProvider.AUDIO_TAG).forEach { option ->
        Row(
          modifier = Modifier.fillMaxWidth().selectable(
            selected = provider == option,
            enabled = !saving,
            role = Role.RadioButton,
            onClick = { changeProvider(option) },
          ).padding(vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          RadioButton(selected = provider == option, onClick = null, enabled = !saving)
          Text(
            stringResource(when (option) {
              RecognitionProvider.YOUTUBE_MUSIC -> R.string.ytm_auto_provider
              RecognitionProvider.AUDD -> R.string.recognition_provider_audd
              RecognitionProvider.AUDIO_TAG -> R.string.recognition_provider_audio_tag
            }),
            modifier = Modifier.padding(start = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
          )
        }
      }
    }
    Row(
      modifier = Modifier.fillMaxWidth().toggleable(
        value = enabled,
        role = Role.Switch,
        onValueChange = ::changeEnabled,
      ).padding(vertical = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        stringResource(R.string.recognition_auto_enable),
        modifier = Modifier.weight(1f).padding(end = 16.dp),
        style = MaterialTheme.typography.bodyLarge,
      )
      Switch(checked = enabled, onCheckedChange = null)
    }
    if (provider == RecognitionProvider.YOUTUBE_MUSIC) {
      Text(
        when {
          !youtubeMusicAvailable -> ytmUnavailable
          bridgeConnected -> stringResource(R.string.ytm_auto_ready)
          bridgeConfigured -> ytmConnecting
          else -> ytmSetupNeeded
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      TextButton(onClick = ::openYtmBridgeSettings, enabled = youtubeMusicAvailable) {
        Text(stringResource(R.string.ytm_auto_bridge_settings))
      }
    } else {
      Text(
        when {
          provider == RecognitionProvider.AUDIO_TAG && hasToken ->
            stringResource(R.string.recognition_audio_tag_remaining, audioTagRemaining)
          provider == RecognitionProvider.AUDIO_TAG ->
            stringResource(R.string.recognition_audio_tag_key_needed)
          hasToken -> stringResource(R.string.recognition_key_in_use, audDRemaining)
          else -> stringResource(R.string.recognition_trial_remaining, trialRemaining)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(12.dp))
      OutlinedTextField(
        value = tokenInput,
        onValueChange = { tokenInput = it },
        enabled = !saving,
        label = {
          Text(stringResource(
            if (provider == RecognitionProvider.AUDD) R.string.recognition_key_label else R.string.recognition_audio_tag_key_label,
          ))
        },
        placeholder = {
          Text(stringResource(if (hasToken) R.string.recognition_key_saved_placeholder else R.string.recognition_key_placeholder))
        },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
          enabled = !saving && tokenInput.isNotBlank(),
          onClick = {
            val newToken = tokenInput
            saving = true
            scope.launch {
              try {
                withContext(Dispatchers.IO) { RecognitionPreferences.setApiToken(context, newToken) }
                tokenInput = ""
                hasToken = true
                audDRemaining = RecognitionPreferences.remainingAudDRequests(context)
                status = keySaved
                isError = false
              } catch (exception: CancellationException) {
                throw exception
              } catch (_: Exception) {
                status = keySaveFailed
                isError = true
              } finally {
                saving = false
              }
            }
          },
        ) { Text(stringResource(R.string.recognition_key_save)) }
        if (hasToken) {
          TextButton(
            enabled = !saving,
            onClick = {
              saving = true
              scope.launch {
                try {
                  withContext(Dispatchers.IO) { RecognitionPreferences.setApiToken(context, null) }
                  hasToken = false
                  tokenInput = ""
                  trialRemaining = RecognitionPreferences.remainingTrialRequests(context)
                  audDRemaining = RecognitionPreferences.remainingAudDRequests(context)
                  audioTagRemaining = RecognitionPreferences.remainingAudioTagRequests(context)
                  status = keyRemoved
                  isError = false
                } catch (exception: CancellationException) {
                  throw exception
                } catch (_: Exception) {
                  status = keySaveFailed
                  isError = true
                } finally {
                  saving = false
                }
              }
            },
          ) { Text(stringResource(R.string.recognition_key_remove)) }
        }
        TextButton(onClick = {
          try {
            val dashboard = if (provider == RecognitionProvider.AUDD) "https://dashboard.audd.io/" else "https://audiotag.info/apisection"
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dashboard)))
          } catch (_: Exception) {
            status = dashboardFailed
            isError = true
          }
        }) {
          Text(stringResource(
            if (provider == RecognitionProvider.AUDD) R.string.recognition_get_key else R.string.recognition_get_audio_tag_key,
          ))
        }
      }
    }
    if (status.isNotEmpty()) {
      Text(
        status,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

private fun isYtmBridgeConfigured(context: Context): Boolean = try {
  val component = ComponentName(context, YouTubeMusicBridgeService::class.java)
  Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
} catch (_: Exception) {
  false
}

@Suppress("DEPRECATION")
private fun isYtmAvailable(context: Context): Boolean =
  Build.VERSION.SDK_INT >= 34 && try {
    context.packageManager.getApplicationInfo("com.google.android.apps.youtube.music", 0).enabled
  } catch (_: PackageManager.NameNotFoundException) {
    false
  }
