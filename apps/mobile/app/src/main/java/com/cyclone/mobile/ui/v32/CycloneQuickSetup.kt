package com.cyclone.mobile.ui.v32

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.permissions.RootQuickSetup
import com.cyclone.mobile.runtime.background.BackgroundSetup
import com.cyclone.mobile.runtime.background.BackgroundSetupActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun CycloneQuickSetup(context: Context, refresh: () -> Unit, openPermissions: () -> Unit) {
    var independent by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var checks by remember { mutableStateOf(RootQuickSetup.checks(context)) }
    var background by remember { mutableStateOf(BackgroundSetup.read(context)) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        while (true) {
            checks = RootQuickSetup.checks(context)
            background = BackgroundSetup.read(context)
            delay(1000)
        }
    }

    val readyCount = checks.count { it.ready }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Set up Cyclone with root", style = MaterialTheme.typography.titleLarge)
            Text(
                "Enable the phone access Cyclone needs in one guided pass. You can review every permission afterward.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Independent phone autonomy", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Routine actions can continue without repeated prompts. Sensitive actions still require confirmation.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    CycloneLiquidToggle(
                        checked = independent,
                        onCheckedChange = { independent = it },
                        enabled = !busy,
                    )
                }

                CycloneLiquidTextAction(
                    label = if (busy) "Setting up…" else if (readyCount == checks.size && checks.isNotEmpty()) "Run setup again" else "Enable with root",
                    enabled = !busy,
                    prominent = true,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                message = withContext(Dispatchers.IO) {
                                    RootQuickSetup.apply(context.applicationContext, independent)
                                }
                                checks = RootQuickSetup.checks(context)
                                refresh()
                            } finally {
                                busy = false
                            }
                        }
                    },
                )

                message?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Phone access", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(
                        "$readyCount/${checks.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (checks.isNotEmpty() && readyCount == checks.size) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                checks.forEach { check ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            if (check.ready) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (check.ready) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(19.dp),
                        )
                        Text(check.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        if (!check.ready) {
                            Text("Needs attention", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                TextButton(onClick = openPermissions, modifier = Modifier.align(Alignment.End)) {
                    Text("Review permissions")
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = if (background.setupFailure == null) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .55f)
            else MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (background.setupFailure == null) "Background work is ready" else "Background work needs setup",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    if (background.setupFailure == null) {
                        "Cyclone can keep a task running in a separate workspace while you use your phone."
                    } else {
                        background.setupFailure.orEmpty()
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CycloneLiquidTextAction(
                    label = if (background.setupFailure == null) "Review background setup" else "Finish background setup",
                    onClick = { context.startActivity(Intent(context, BackgroundSetupActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Text(
            "Some Android-owned approval screens still open separately. Screen sharing always asks again for each sharing session.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
