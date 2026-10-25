package com.example.farragutclock

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.farragutclock.theme.FarragutClockTheme

/** A manual bridge experiment. YouTube Music must remain foreground during its Sound Search. */
class YtmRecognitionTestActivity : ComponentActivity() {
  private var youtubeMusicInstalled by mutableStateOf(false)
  private val isSupported: Boolean get() = Build.VERSION.SDK_INT >= 34

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    updateYouTubeMusicAvailability()
    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() {
        YouTubeMusicBridgeService.cancelRecognition(this@YtmRecognitionTestActivity)
        finish()
      }
    })
    setContent {
      FarragutClockTheme {
        val bridge by YouTubeMusicBridgeService.state.collectAsStateWithLifecycle()
        val connected by YouTubeMusicBridgeService.isConnected.collectAsStateWithLifecycle()
        val active = when (bridge.phase) {
          YtmBridgePhase.NAVIGATING, YtmBridgePhase.LISTENING, YtmBridgePhase.READING -> true
          else -> false
        }
        var launchError by remember { mutableStateOf("") }
        val settingsUnavailable = stringResource(R.string.ytm_settings_unavailable)
        val testUnavailable = stringResource(R.string.ytm_test_unavailable)

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding()
              .verticalScroll(rememberScrollState()).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
          ) {
            Text(stringResource(R.string.ytm_prototype_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.ytm_prototype_help), style = MaterialTheme.typography.bodyLarge)
            Text(
              stringResource(R.string.ytm_prototype_privacy),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!isSupported) {
              Text(stringResource(R.string.ytm_android_required), color = MaterialTheme.colorScheme.error)
            } else if (!youtubeMusicInstalled) {
              Text(stringResource(R.string.ytm_app_required), color = MaterialTheme.colorScheme.error)
            }
            Text(
              stringResource(if (connected) R.string.ytm_bridge_connected else R.string.ytm_bridge_not_connected),
              style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(
              onClick = {
                launchError = ""
                if (!openBridgeSettings()) launchError = settingsUnavailable
              },
              enabled = isSupported && youtubeMusicInstalled && !active,
            ) { Text(stringResource(R.string.ytm_enable_bridge)) }
            Button(
              onClick = {
                launchError = ""
                try {
                  YouTubeMusicBridgeService.startRecognition(this@YtmRecognitionTestActivity)
                } catch (_: Exception) {
                  launchError = testUnavailable
                }
              },
              enabled = isSupported && youtubeMusicInstalled && connected && !active,
              modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.ytm_start_test)) }
            if (active) {
              LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
              TextButton(onClick = {
                YouTubeMusicBridgeService.cancelRecognition(this@YtmRecognitionTestActivity)
              }) { Text(stringResource(R.string.ytm_cancel_test)) }
            }
            if (launchError.isNotEmpty()) {
              Text(launchError, color = MaterialTheme.colorScheme.error)
            }
            if (bridge.message.isNotEmpty()) {
              Text(
                bridge.message,
                style = MaterialTheme.typography.bodyLarge,
                color = if (bridge.phase == YtmBridgePhase.FAILED) MaterialTheme.colorScheme.error
                  else MaterialTheme.colorScheme.onSurface,
              )
            }
            if (bridge.title.isNotEmpty()) {
              Text(bridge.title, style = MaterialTheme.typography.headlineLarge)
            }
            if (bridge.artist.isNotEmpty()) {
              Text(bridge.artist, style = MaterialTheme.typography.titleLarge)
            }
            TextButton(onClick = {
              if (active) YouTubeMusicBridgeService.cancelRecognition(this@YtmRecognitionTestActivity)
              finish()
            }) { Text(stringResource(R.string.recognition_back)) }
          }
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    updateYouTubeMusicAvailability()
  }

  // No onStop cancellation: starting YouTube Music intentionally backgrounds this activity.

  @Suppress("DEPRECATION")
  private fun updateYouTubeMusicAvailability() {
    youtubeMusicInstalled = try {
      packageManager.getApplicationInfo(YOUTUBE_MUSIC_PACKAGE, 0).enabled
    } catch (_: PackageManager.NameNotFoundException) {
      false
    }
  }

  private fun openBridgeSettings(): Boolean {
    return try {
      startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
      true
    } catch (_: Exception) {
      false
    }
  }

  private companion object {
    const val YOUTUBE_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"
  }
}
