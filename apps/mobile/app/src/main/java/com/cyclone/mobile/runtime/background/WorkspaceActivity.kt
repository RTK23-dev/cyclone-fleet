package com.cyclone.mobile.runtime.background

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ui.overlay.OverlayExternalInteraction
import com.cyclone.mobile.ui.v32.CycloneIntelligenceTheme
import com.cyclone.mobile.ui.v32.CycloneOrbitMark
import rikka.shizuku.Shizuku

/** Target choice remains explicit. Visual presentation never grants execution authority. */
class WorkspaceActivity : ComponentActivity() {
    /**
     * This Activity is a deliberate full-screen handoff. While it is visible, the accessibility
     * overlay must yield completely rather than visually competing with the app/destination form.
     */
    override fun onStart() {
        super.onStart()
        OverlayExternalInteraction.active.value = true
    }

    override fun onStop() {
        OverlayExternalInteraction.active.value = false
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val apps = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != packageName }.distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(packageManager).toString() }
        setContent {
            CycloneIntelligenceTheme {
                val requested = WorkspaceTasks.resolveQueueTarget(
                    this@WorkspaceActivity,
                    PendingWorkspaceRequest("preview", intent.getStringExtra("goal").orEmpty(), null),
                )
                var selected by rememberSaveable {
                    mutableIntStateOf(apps.indexOfFirst { it.activityInfo.packageName == requested?.packageName }.coerceAtLeast(0))
                }
                var menu by remember { mutableStateOf(false) }
                var goal by rememberSaveable { mutableStateOf(intent.getStringExtra("goal").orEmpty()) }
                var status by remember { mutableStateOf("") }
                var readiness by remember { mutableStateOf(BackgroundSetup.read(this@WorkspaceActivity)) }
                LaunchedEffect(Unit) {
                    while (true) {
                        readiness = BackgroundSetup.read(this@WorkspaceActivity)
                        kotlinx.coroutines.delay(800)
                    }
                }

                Column(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .systemBarsPadding()
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { finish() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                        }
                        Text("Background task", style = MaterialTheme.typography.titleMedium)
                    }

                    CycloneOrbitMark(Modifier.size(52.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(
                            "Set up a background task",
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            "Choose the app and outcome. Cyclone works in a separate workspace while your main screen stays yours.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                        shadowElevation = 1.dp,
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(
                                "App",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Box {
                                OutlinedButton(
                                    onClick = { menu = true },
                                    enabled = apps.isNotEmpty(),
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                ) {
                                    Text(
                                        apps.getOrNull(selected)?.loadLabel(packageManager)?.toString() ?: "No apps available",
                                        modifier = Modifier.weight(1f),
                                    )
                                    Icon(Icons.Rounded.ArrowDropDown, null)
                                }
                                DropdownMenu(
                                    expanded = menu,
                                    onDismissRequest = { menu = false },
                                    modifier = Modifier.heightIn(max = 320.dp),
                                ) {
                                    apps.forEachIndexed { index, app ->
                                        DropdownMenuItem(
                                            text = { Text(app.loadLabel(packageManager).toString()) },
                                            onClick = { selected = index; menu = false },
                                        )
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = goal,
                                onValueChange = { goal = it },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 3,
                                label = { Text("What should Cyclone do?") },
                                shape = RoundedCornerShape(20.dp),
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "You're in control",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "Pause or stop at any time. Cyclone hands the app back for payments and other sensitive steps. Background work requires Android 15 or later and completed background access.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    readiness.setupFailure?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(
                        onClick = { startActivity(Intent(this@WorkspaceActivity, BackgroundSetupActivity::class.java)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) {
                        Text(if (readiness.setupFailure == null) "Review background setup" else "Set up background access")
                    }

                    Button(
                        onClick = {
                            val app = apps.getOrNull(selected)
                            if (goal.isBlank() || app == null) {
                                status = "Choose an app and describe your task."
                            } else if (android.os.Build.VERSION.SDK_INT < 35) {
                                status = "Background work needs Android 15 or later."
                            } else runCatching {
                                check(Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                                    "Enable background access before starting."
                                }
                                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
                                WorkspaceTasks.start(
                                    this@WorkspaceActivity,
                                    goal,
                                    app.activityInfo.packageName,
                                    app.loadLabel(packageManager).toString(),
                                    intent.getStringExtra("pendingRequestId"),
                                )
                                finish()
                            }.onFailure {
                                status = it.message ?: "Couldn't start this task. Please try again."
                            }
                        },
                        enabled = apps.isNotEmpty() && goal.isNotBlank() && readiness.setupFailure == null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) {
                        Text("Start in background")
                    }
                    if (status.isNotBlank()) {
                        Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
