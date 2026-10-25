package com.example.farragutclock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation

@Composable
internal fun SonosSettings(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val repository = remember(context) { SonosRepository(context.applicationContext) }
  var selectedRoomIds by remember {
    mutableStateOf(ClockPreferences.selectedSonosRoomIds(context))
  }
  var rooms by remember { mutableStateOf<List<SonosRoom>>(emptyList()) }
  var isLoading by remember { mutableStateOf(true) }
  var discoveryFailed by remember { mutableStateOf(false) }
  var refreshRequest by remember { mutableIntStateOf(0) }

  LaunchedEffect(repository, lifecycle, refreshRequest) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      isLoading = true
      discoveryFailed = false
      try {
        rooms = repository.discoverRooms().sortedBy { it.name.lowercase() }
      } catch (exception: CancellationException) {
        throw exception
      } catch (_: Exception) {
        discoveryFailed = true
      } finally {
        isLoading = false
      }
      // Refresh once per visible session or explicit refresh, without a background poll.
      awaitCancellation()
    }
  }

  fun saveSelection(ids: Set<String>) {
    selectedRoomIds = ids
    ClockPreferences.setSelectedSonosRoomIds(context, ids)
  }

  Column(modifier = modifier) {
    Text(stringResource(R.string.sonos_rooms), style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.sonos_rooms_help), style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))
    if (selectedRoomIds == null) {
      Text(
        stringResource(R.string.sonos_default_rooms),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else if (selectedRoomIds!!.isEmpty()) {
      Text(
        stringResource(R.string.sonos_disabled),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Spacer(Modifier.height(12.dp))
    if (isLoading) {
      LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      Spacer(Modifier.height(8.dp))
      Text(stringResource(R.string.sonos_discovering), style = MaterialTheme.typography.bodyMedium)
    }
    if (discoveryFailed) {
      Text(
        stringResource(R.string.sonos_discovery_failed),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
      )
    } else if (!isLoading && rooms.isEmpty()) {
      Text(stringResource(R.string.sonos_no_rooms), style = MaterialTheme.typography.bodyMedium)
    }

    rooms.forEach { room ->
      key(room.id) {
        val isSelected = selectedRoomIds?.contains(room.id) ?: isDefaultSonosRoom(room.name)
        Row(
          modifier =
            Modifier.fillMaxWidth()
              .toggleable(
                value = isSelected,
                role = Role.Switch,
                onValueChange = { checked ->
                  val selection =
                    selectedRoomIds
                      ?: rooms.filter { isDefaultSonosRoom(it.name) }.map { it.id }.toSet()
                  saveSelection(if (checked) selection + room.id else selection - room.id)
                },
              )
              .padding(vertical = 8.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            room.name,
            modifier = Modifier.weight(1f).padding(end = 16.dp),
            style = MaterialTheme.typography.bodyLarge,
          )
          Switch(checked = isSelected, onCheckedChange = null)
        }
      }
    }

    val discoveredIds = rooms.map { it.id }.toSet()
    if (!isLoading && selectedRoomIds?.any { it !in discoveredIds } == true) {
      Spacer(Modifier.height(8.dp))
      Text(
        stringResource(R.string.sonos_selected_unavailable),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      TextButton(
        onClick = { refreshRequest++ },
        enabled = !isLoading,
        modifier = Modifier.weight(1f),
      ) {
        Text(stringResource(if (discoveryFailed) R.string.sonos_retry else R.string.sonos_refresh))
      }
      TextButton(
        onClick = { saveSelection(emptySet()) },
        enabled = selectedRoomIds?.isEmpty() != true,
        modifier = Modifier.weight(1f),
      ) {
        Text(stringResource(R.string.sonos_disable))
      }
    }
    Spacer(Modifier.height(8.dp))
    Text(
      stringResource(R.string.sonos_touch_help),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
