package com.example.farragutclock

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.farragutclock.theme.FarragutClockTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    enableEdgeToEdge()
    setContent {
      FarragutClockTheme {
        val context = LocalContext.current
        var restoreGeneration by rememberSaveable { mutableIntStateOf(0) }
        val backupRevision by SettingsBackupStorage.revision.collectAsStateWithLifecycle()
        var use24HourTime by remember(restoreGeneration, backupRevision) {
          mutableStateOf(ClockPreferences.uses24HourTime(context))
        }
        var useTrolleyDisplay by remember(restoreGeneration, backupRevision) {
          mutableStateOf(ClockPreferences.usesTrolleyDisplay(context))
        }
        var nightBrightness by remember(restoreGeneration, backupRevision) {
          mutableStateOf(ClockPreferences.nightBrightness(context))
        }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          Column(
            modifier =
              Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
                .padding(32.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Text(
              text = stringResource(R.string.launcher_title),
              style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
              text = stringResource(R.string.launcher_message),
              textAlign = TextAlign.Center,
              style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(32.dp))
            SettingsBackupPanel(
              onRestored = { restoreGeneration++ },
              modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(24.dp))
            Row(
              modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Text(
                text = stringResource(R.string.use_24_hour_time),
                style = MaterialTheme.typography.bodyLarge,
              )
              Switch(
                checked = use24HourTime,
                onCheckedChange = { enabled ->
                  use24HourTime = enabled
                  ClockPreferences.setUses24HourTime(context, enabled)
                },
              )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
              modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Text(
                text = stringResource(R.string.use_trolley_display),
                style = MaterialTheme.typography.bodyLarge,
              )
              Switch(
                checked = useTrolleyDisplay,
                onCheckedChange = { enabled ->
                  useTrolleyDisplay = enabled
                  ClockPreferences.setUsesTrolleyDisplay(context, enabled)
                },
              )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Column(modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
              Text(
                text = stringResource(R.string.night_brightness),
                style = MaterialTheme.typography.bodyLarge,
              )
              Slider(
                value = nightBrightness,
                onValueChange = { brightness ->
                  nightBrightness = brightness
                  ClockPreferences.setNightBrightness(context, brightness)
                },
                valueRange =
                  ClockPreferences.MIN_NIGHT_BRIGHTNESS..
                    ClockPreferences.MAX_NIGHT_BRIGHTNESS,
              )
            }
            Spacer(modifier = Modifier.height(32.dp))
            key(restoreGeneration, backupRevision) {
              SonosSettings(modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth())
              Spacer(modifier = Modifier.height(24.dp))
              RecognitionSettings(modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth())
            }
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(onClick = {
              val testActivity =
                if (RecognitionPreferences.provider(context) == RecognitionProvider.YOUTUBE_MUSIC) {
                  YtmRecognitionTestActivity::class.java
                } else {
                  SongRecognitionTestActivity::class.java
                }
              context.startActivity(Intent(context, testActivity))
            }) {
              Text(stringResource(R.string.recognition_test_title))
            }
          }
        }
      }
    }
  }
}
