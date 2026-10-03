package com.cyclone.mobile.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.runtime.workspaces.ProfileSetupRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Available before setup, profile inventory, Accessibility or PC pairing. */
@Composable
fun ProfileRescueBar() {
    if (ProfileSetupRuntime.currentUserId() == 0) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Work profile",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        error = withContext(Dispatchers.IO) {
                            runCatching { ProfileSetupRuntime.openProfile(context, null) }
                                .exceptionOrNull()?.let { "Root hasn't allowed the return switch. Android's profile switcher is available below." }
                        }
                        busy = false
                    }
                }) { Text(if (busy) "Returning…" else "Return to Profile A") }
            }
            if (error != null) {
                Text(error!!, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent("android.settings.USER_SETTINGS")) }
                        .onFailure { context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
                }) { Text("Open Android profile switcher") }
            }
        }
    }
}
