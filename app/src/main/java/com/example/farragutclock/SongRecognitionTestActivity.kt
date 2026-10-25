package com.example.farragutclock

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.farragutclock.theme.FarragutClockTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A foreground-only, one-shot experiment. This does not enable automatic listening. */
class SongRecognitionTestActivity : ComponentActivity() {
  private var testJob: Job? = null
  private var busy by mutableStateOf(false)
  private var status by mutableStateOf("")
  private var signal by mutableFloatStateOf(0f)
  private var song by mutableStateOf<RecognizedSong?>(null)
  private var microphoneSummary by mutableStateOf("")
  private val sampleSeconds: Int
    get() = RecognitionPreferences.sampleDurationSeconds(applicationContext)

  private val microphonePermission =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) {
        runTest()
      } else {
        status = getString(R.string.recognition_permission_needed)
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (routeYouTubeMusicTestIfSelected()) return
    enableEdgeToEdge()
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    setContent {
      FarragutClockTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding()
              .verticalScroll(rememberScrollState()).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
          ) {
            Text(stringResource(R.string.recognition_test_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.recognition_test_help), style = MaterialTheme.typography.bodyLarge)
            Text(
              stringResource(R.string.recognition_test_privacy, sampleSeconds),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(
              onClick = {
                if (!routeYouTubeMusicTestIfSelected()) {
                  if (ContextCompat.checkSelfPermission(this@SongRecognitionTestActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    runTest()
                  } else {
                    microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                  }
                }
              },
              enabled = !busy,
              modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.recognition_listen, sampleSeconds)) }
            if (busy && testJob?.isActive == true) {
              LinearProgressIndicator(progress = { signal }, modifier = Modifier.fillMaxWidth())
              TextButton(onClick = { stopTest() }) { Text(stringResource(R.string.recognition_stop)) }
            }
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodyLarge)
            if (microphoneSummary.isNotEmpty()) {
              Text(microphoneSummary, style = MaterialTheme.typography.bodyMedium)
            }
            song?.let { match ->
              Text(match.title, style = MaterialTheme.typography.headlineLarge)
              Text(match.artist, style = MaterialTheme.typography.titleLarge)
            }
            TextButton(onClick = { finish() }) { Text(stringResource(R.string.recognition_back)) }
          }
        }
      }
    }
  }

  private fun runTest() {
    if (routeYouTubeMusicTestIfSelected()) return
    if (busy) return
    val attemptProvider = RecognitionPreferences.provider(applicationContext)
    val durationSeconds = when (attemptProvider) {
      RecognitionProvider.AUDD -> 10
      RecognitionProvider.AUDIO_TAG -> RecognitionPreferences.AUDIO_TAG_SAMPLE_SECONDS
      RecognitionProvider.YOUTUBE_MUSIC -> {
        routeYouTubeMusicTestIfSelected()
        return
      }
    }
    busy = true
    signal = 0f
    song = null
    microphoneSummary = ""
    status = getString(R.string.recognition_listening, 0, durationSeconds)
    testJob = lifecycleScope.launch {
      var sample: MicrophoneSample? = null
      try {
        RecognitionPreferences.requireProvider(applicationContext, attemptProvider)
        sample = captureMicrophoneSample(
          applicationContext,
          durationSeconds,
          preferredSampleRate = if (attemptProvider == RecognitionProvider.AUDIO_TAG) 8_000 else 44_100,
        ) { seconds, level ->
          runOnUiThread {
            if (busy && testJob?.isActive == true) {
              status = getString(R.string.recognition_listening, seconds, durationSeconds)
              signal = ((level + 65f) / 55f).coerceIn(0f, 1f)
            }
          }
        }
        RecognitionPreferences.requireProvider(applicationContext, attemptProvider)
        microphoneSummary = getString(
          if (sample.peakDbFS < -60f) R.string.recognition_quiet else R.string.recognition_captured,
          durationSeconds,
        )
        status = getString(R.string.recognition_identifying)
        val match = SongRecognitionClient(applicationContext).recognize(sample.wavBytes, attemptProvider)
        RecognitionPreferences.requireProvider(applicationContext, attemptProvider)
        song = match
        status = getString(if (song == null) R.string.recognition_no_match else R.string.recognition_match)
      } catch (exception: CancellationException) {
        throw exception
      } catch (exception: RecognitionProviderChangedException) {
        song = null
        status = exception.message.orEmpty()
        routeYouTubeMusicTestIfSelected()
      } catch (exception: Exception) {
        status = exception.message ?: getString(R.string.recognition_failed)
      } finally {
        sample?.wavBytes?.fill(0)
        busy = false
      }
    }
  }

  private fun stopTest() {
    if (busy) {
      testJob?.cancel()
      status = getString(R.string.recognition_stopped)
    }
  }

  private fun routeYouTubeMusicTestIfSelected(): Boolean {
    if (RecognitionPreferences.provider(this) != RecognitionProvider.YOUTUBE_MUSIC) return false
    if (!isFinishing) {
      stopTest()
      startActivity(Intent(this, YtmRecognitionTestActivity::class.java))
      finish()
    }
    return true
  }

  override fun onResume() {
    super.onResume()
    routeYouTubeMusicTestIfSelected()
  }

  override fun onStop() {
    stopTest()
    super.onStop()
  }
}
