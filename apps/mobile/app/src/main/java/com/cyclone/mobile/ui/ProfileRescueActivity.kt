package com.cyclone.mobile.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.MainActivity
import com.cyclone.mobile.runtime.workspaces.ProfileSetupRuntime
import com.cyclone.mobile.runtime.workspaces.ProfileBootstrapRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Deliberately does not initialize agents, learning, pairing or accessibility-dependent UI. */
class ProfileRescueActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            com.cyclone.mobile.ui.v32.CycloneV32Theme {
                val scope = rememberCoroutineScope()
                var busy by remember { mutableStateOf(false) }
                var message by remember { mutableStateOf(if (intent.getBooleanExtra("repair_failed", false)) "Repair could not finish. You can return to Profile A safely below." else "This profile hasn't inherited Cyclone's setup yet.") }
                fun perform(repair: Boolean) {
                    busy = true
                    scope.launch {
                        message = withContext(Dispatchers.IO) {
                            runCatching {
                                if (repair) ProfileBootstrapRuntime.requestRepair(this@ProfileRescueActivity)
                                else ProfileSetupRuntime.openProfile(this@ProfileRescueActivity, null)
                                if (repair) "Repair started. Cyclone will reopen when your settings are ready." else "Returning to Profile A…"
                            }.getOrElse { "Cyclone couldn't complete that action. If root is unavailable here, use Android's profile switcher below." }
                        }
                        busy = false
                    }
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Get back to your Cyclone", style = MaterialTheme.typography.headlineMedium)
                        Text(message)
                        Button(enabled = !busy, onClick = { perform(false) }) { Text("Return to Profile A") }
                        OutlinedButton(enabled = !busy, onClick = { perform(true) }) { Text("Repair from Profile A") }
                        TextButton(onClick = {
                            runCatching { startActivity(Intent("android.settings.USER_SETTINGS")) }
                                .onFailure { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
                        }) { Text("Open Android profile switcher") }
                        TextButton(onClick = {
                            startActivity(Intent(this@ProfileRescueActivity, MainActivity::class.java).putExtra("skip_profile_rescue", true))
                            finish()
                        }) { Text("Continue in this profile") }
                    }
                }
            }
        }
    }
}
