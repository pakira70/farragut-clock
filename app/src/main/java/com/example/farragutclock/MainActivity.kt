package com.example.farragutclock

import android.os.Bundle
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.farragutclock.theme.FarragutClockTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    enableEdgeToEdge()
    setContent {
      FarragutClockTheme {
        val context = LocalContext.current
        var use24HourTime by remember {
          mutableStateOf(ClockPreferences.uses24HourTime(context))
        }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
            verticalArrangement = Arrangement.Center,
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
          }
        }
      }
    }
  }
}
