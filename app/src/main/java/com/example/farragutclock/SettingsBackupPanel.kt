package com.example.farragutclock

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SettingsBackupPanel(onRestored: () -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var busy by remember { mutableStateOf(false) }
  var status by remember { mutableStateOf("") }
  var error by remember { mutableStateOf(false) }
  var restoredYtm by remember { mutableStateOf(false) }
  val exportDone = stringResource(R.string.settings_backup_exported)
  val restoreDone = stringResource(R.string.settings_backup_restored)
  val exportFailed = stringResource(R.string.settings_backup_export_failed)
  val importFailed = stringResource(R.string.settings_backup_import_failed)
  val invalidFile = stringResource(R.string.settings_backup_invalid)
  val versionUnsupported = stringResource(R.string.settings_backup_version)
  val tooLarge = stringResource(R.string.settings_backup_too_large)
  val settingsFailed = stringResource(R.string.settings_backup_setup_failed)

  val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
    if (uri != null) scope.launch {
      busy = true
      error = false
      status = ""
      try {
        withContext(Dispatchers.IO) { SettingsBackupStorage.write(context, uri) }
        status = exportDone
      } catch (cancelled: CancellationException) { throw cancelled
      } catch (_: Exception) {
        status = exportFailed
        error = true
      } finally { busy = false }
    }
  }
  val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) scope.launch {
      busy = true
      error = false
      status = ""
      try {
        val settings = withContext(Dispatchers.IO) {
          val importContext = currentCoroutineContext()
          SettingsBackupStorage.readAndRestore(context, uri) { importContext.ensureActive() }
        }
        restoredYtm = settings.recognitionEnabled && settings.recognitionProvider == RecognitionProvider.YOUTUBE_MUSIC
        status = restoreDone
        onRestored()
      } catch (cancelled: CancellationException) { throw cancelled
      } catch (exception: SettingsBackupFormatException) {
        status = when (exception.problem) {
          SettingsBackupProblem.INVALID -> invalidFile
          SettingsBackupProblem.UNSUPPORTED_VERSION -> versionUnsupported
          SettingsBackupProblem.TOO_LARGE -> tooLarge
        }
        error = true
      } catch (_: Exception) {
        status = importFailed
        error = true
      } finally { busy = false }
    }
  }

  Column(modifier) {
    Text(stringResource(R.string.settings_backup_title), style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.settings_backup_help), style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedButton(
        onClick = {
          try { export.launch("FarragutClock-settings.json")
          } catch (_: Exception) { status = exportFailed; error = true }
        },
        enabled = !busy,
        modifier = Modifier.weight(1f),
      ) { Text(stringResource(R.string.settings_backup_export)) }
      OutlinedButton(
        onClick = {
          try { restore.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
          } catch (_: Exception) { status = importFailed; error = true }
        },
        enabled = !busy,
        modifier = Modifier.weight(1f),
      ) { Text(stringResource(R.string.settings_backup_import)) }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    Text(
      stringResource(R.string.settings_backup_limitations),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (status.isNotEmpty()) Text(
      status,
      style = MaterialTheme.typography.bodySmall,
      color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (restoredYtm) {
      Text(stringResource(R.string.settings_backup_ytm_reminder), style = MaterialTheme.typography.bodySmall)
      TextButton(onClick = {
        try { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) { status = settingsFailed; error = true }
      }) { Text(stringResource(R.string.settings_backup_android_setup)) }
    }
  }
}
