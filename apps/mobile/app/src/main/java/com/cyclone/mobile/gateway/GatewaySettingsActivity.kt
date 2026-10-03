package com.cyclone.mobile.gateway

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ui.v32.CycloneTheme as CycloneV32Theme
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.delay

/** Modern, compact control center for Cyclone's USB-only PC bridge and Live Phone plane. */
class GatewaySettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GatewayRuntime.startPairingBootstrap(this)
        setContent {
            CycloneV32Theme {
                GatewayControlCenter(
                    context = this,
                    onClose = { finish() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GatewayControlCenter(
    context: Context,
    onClose: () -> Unit,
) {
    var refreshTick by remember { mutableIntStateOf(0) }
    var setupOpen by remember { mutableStateOf(false) }
    var technicalOpen by remember { mutableStateOf(false) }

    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) {
        while (true) {
            delay(750)
            refreshTick++
        }
    }

    val status = remember(refreshTick) { GatewayRuntime.status(context) }
    val enabled = status.optBoolean("gatewayEnabled")
    val accessibilityReady = status.optBoolean("accessibilityConnected")
    val phoneControlReady = if (status.has("phoneControlReady")) {
        status.optBoolean("phoneControlReady")
    } else {
        accessibilityReady
    }
    val phoneControlNeedsRepair = status.optBoolean("phoneControlNeedsRepair")
    val bootstrapReady = status.optBoolean("pairingBootstrapListening")
    val productionAuthority = status.optBoolean("productionActionAuthorityBound")
    val session = status.optJSONObject("connectedSession")
    val connected = session?.optBoolean("connected") == true
    val trust = status.optJSONObject("trust")
    val trustState = trust?.optString("trustState").orEmpty()
    val trustedPcCount = trust?.optInt("trustedPcCount", 0) ?: 0
    val trustedSessionCount = trust?.optInt("activeSessionCount", 0) ?: 0
    val pendingTrust = remember(refreshTick) { GatewayV33TrustManager.pendingForUser(context) }
    val linkedPcs = remember(refreshTick) { runCatching { GatewayV33TrustManager.linkedPcs(context) }.getOrDefault(emptyList()) }
    var logoutCandidate by remember { mutableStateOf<GatewayTrustedPc?>(null) }
    val clipboardEnabled = remember(refreshTick) { GatewayDesktopPreferences.clipboardEnabled(context) }
    val pairingCode = remember(refreshTick) { GatewayDesktopPairingManager.codeForUser() }
    val pairingExpiresAt = remember(refreshTick) { GatewayDesktopPairingManager.expiresAtForUser() }
    val livePhoneState = remember(refreshTick) { LivePhoneMode.uiState(context) }
    val nextActionJson = status.optJSONObject("nextAction")
    val nextAction = nextActionJson?.optString("code")?.takeIf { it.isNotBlank() }?.let {
        GatewayReadyNextAction(
            code = it,
            title = nextActionJson.optString("title"),
            body = nextActionJson.optString("body"),
            actionLabel = nextActionJson.optString("actionLabel"),
        )
    }

    val trustReady = trustedPcCount > 0 && pendingTrust == null
    val trustOnlyMissing = enabled && bootstrapReady && phoneControlReady && !trustReady && pendingTrust == null
    val overallStatus = when {
        !enabled -> "Off"
        connected -> "Connected"
        bootstrapReady && phoneControlReady -> "Ready for PC"
        else -> "Needs setup"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("PC Gateway", fontWeight = FontWeight.SemiBold)
                        Text(
                            "USB + phone control",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back to Cyclone")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                LivePhoneCard(
                    state = livePhoneState,
                    onStateChanged = { next ->
                        when (next) {
                            LivePhoneUiState.ON -> LivePhoneMode.setPaused(context, paused = false)
                            LivePhoneUiState.PAUSED -> LivePhoneMode.setPaused(context, paused = true)
                            LivePhoneUiState.OFF -> LivePhoneMode.setPaused(context, paused = true, stopped = true)
                        }
                        refreshTick++
                    },
                )
            }

            item {
                Card(
                    shape = RoundedCornerShape(26.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.primary,
                            ) {
                                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.Usb, null, Modifier.size(23.dp))
                                }
                            }
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Text("PC Gateway", style = MaterialTheme.typography.titleLarge)
                                Text(
                                    overallStatus,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = enabled,
                                onCheckedChange = { turnOn ->
                                    runCatching {
                                        if (turnOn) GatewayRuntime.enable(context) else GatewayRuntime.disable(context)
                                    }.onFailure {
                                        Toast.makeText(
                                            context,
                                            it.message ?: "Could not change Gateway state",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                    refreshTick++
                                },
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))

                        GatewayStatusLine(
                            icon = Icons.Rounded.Link,
                            title = "USB bridge",
                            value = if (bootstrapReady) "Ready" else "Starting",
                            ready = bootstrapReady,
                        )
                        GatewayStatusLine(
                            icon = Icons.Rounded.PhoneAndroid,
                            title = "Phone control",
                            value = when {
                                phoneControlReady -> "Ready"
                                phoneControlNeedsRepair -> "Needs repair"
                                else -> "Off"
                            },
                            ready = phoneControlReady,
                            attention = phoneControlNeedsRepair,
                        )
                        GatewayStatusLine(
                            icon = Icons.Rounded.Computer,
                            title = "Cyclone AI trust",
                            value = when {
                                pendingTrust != null -> "Waiting for you"
                                connected -> "Session active"
                                trustedPcCount > 0 -> "Trusted"
                                else -> "Not trusted"
                            },
                            ready = trustReady,
                            attention = false,
                        )

                        when {
                            pendingTrust != null -> {
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ) {
                                    Column(
                                        Modifier.fillMaxWidth().padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(9.dp),
                                    ) {
                                        Text("Allow ${pendingTrust.pcLabel}?", fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "Check that ${pendingTrust.pcLabel} shows the same code, then allow it.",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        Text(
                                            GatewayTrustPrompt.formatCode(GatewayTrustProtocolV33.matchCode(pendingTrust)),
                                            style = MaterialTheme.typography.headlineMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(
                                                onClick = {
                                                    val accepted = GatewayV33TrustManager.decideTrust(
                                                        context,
                                                        pendingTrust.challengeId,
                                                        true,
                                                    )
                                                    Toast.makeText(
                                                        context,
                                                        if (accepted) "PC allowed" else "Trust request expired",
                                                        Toast.LENGTH_SHORT,
                                                    ).show()
                                                    refreshTick++
                                                },
                                                modifier = Modifier.weight(1f),
                                            ) { Text("Allow") }
                                            OutlinedButton(
                                                onClick = {
                                                    GatewayV33TrustManager.decideTrust(context, pendingTrust.challengeId, false)
                                                    refreshTick++
                                                },
                                                modifier = Modifier.weight(1f),
                                            ) { Text("Not now") }
                                        }
                                    }
                                }
                            }

                            trustOnlyMissing -> {
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Rounded.Security, null, Modifier.size(21.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text("One step left", fontWeight = FontWeight.SemiBold)
                                            Text(
                                                "Choose this phone in Cyclone One to request AI trust.",
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                                }
                            }

                            nextAction != null -> {
                                GatewayFixAction(
                                    action = nextAction,
                                    gatewayEnabled = enabled,
                                    onTurnOnGateway = {
                                        runCatching { GatewayRuntime.enable(context) }.onFailure {
                                            Toast.makeText(
                                                context,
                                                it.message ?: "Could not turn on Gateway",
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        }
                                        refreshTick++
                                    },
                                    onOpenAccessibility = {
                                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                    },
                                )
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 13.dp, bottom = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ) {
                            Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.ContentPaste, null, Modifier.size(19.dp))
                            }
                        }
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Clipboard paste", fontWeight = FontWeight.SemiBold)
                            Text(
                                "PC → phone. Passwords, OTPs and tokens stay blocked.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = clipboardEnabled,
                            onCheckedChange = {
                                GatewayDesktopPreferences.setClipboardEnabled(context, it)
                                refreshTick++
                            },
                        )
                    }
                }
            }

            if (trustedPcCount > 0 || connected) {
                item {
                    Card(
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(9.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Linked PCs", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "$trustedPcCount linked · $trustedSessionCount active",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            val share by com.cyclone.mobile.capture.LiveCaptureSessionManager.state.collectAsState()
                            val sharingScreen = share.scope == com.cyclone.mobile.capture.CaptureScope.WHOLE_DISPLAY &&
                                share.phase in setOf(com.cyclone.mobile.capture.ScreenSharePhase.STARTING, com.cyclone.mobile.capture.ScreenSharePhase.LIVE)
                            if (sharingScreen) {
                                OutlinedButton(
                                    onClick = { com.cyclone.mobile.capture.LiveCaptureService.stop(context); refreshTick++ },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Stop sharing screen with PCs") }
                            } else {
                                Button(
                                    onClick = { com.cyclone.mobile.capture.LiveScreenShare.start(context, revealOverlay = false) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Share screen with PC (Wi-Fi)") }
                            }
                            linkedPcs.forEach { pc ->
                                val active = remember(refreshTick, pc.trustId) { GatewayV33TrustManager.isSessionActive(context, pc.trustId) }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Computer, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(9.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(pc.pcLabel.ifBlank { "PC" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            if (active) "Active now" else linkedPcLastUsed(pc),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(onClick = { logoutCandidate = pc }) { Text("Log out") }
                                }
                            }
                            if (connected) {
                                OutlinedButton(
                                    onClick = {
                                        GatewayRuntime.disconnect()
                                        refreshTick++
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Disconnect current session") }
                            }
                            TextButton(
                                onClick = {
                                    val count = GatewayV33TrustManager.revokeAllLocal(context)
                                    GatewayRuntime.disconnect()
                                    Toast.makeText(
                                        context,
                                        if (count > 0) "Revoked $count trusted PC${if (count == 1) "" else "s"}" else "No trusted PCs to revoke",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    refreshTick++
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Log out all PCs") }
                            logoutCandidate?.let { pc ->
                                AlertDialog(
                                    onDismissRequest = { logoutCandidate = null },
                                    title = { Text("Log out ${pc.pcLabel.ifBlank { "this PC" }}?") },
                                    text = { Text("Glass and Cyclone One on that PC lose access to this phone. Connecting again needs a new code and Allow.") },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            val ok = GatewayV33TrustManager.revokeOneLocal(context, pc.trustId)
                                            Toast.makeText(context, if (ok) "Logged out ${pc.pcLabel.ifBlank { "the PC" }}" else "That PC was already logged out", Toast.LENGTH_SHORT).show()
                                            logoutCandidate = null
                                            refreshTick++
                                        }) { Text("Log out") }
                                    },
                                    dismissButton = { TextButton(onClick = { logoutCandidate = null }) { Text("Cancel") } },
                                )
                            }
                        }
                    }
                }
            }

            status.optString("lastSafeError").takeIf { it.isNotBlank() && it != "null" }?.let { safeError ->
                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Rounded.Error, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(Modifier.width(9.dp))
                            Column {
                                Text("Connection needs attention", fontWeight = FontWeight.SemiBold)
                                Text(safeError, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            item {
                ExpandButton(
                    title = "How to connect",
                    expanded = setupOpen,
                    onClick = { setupOpen = !setupOpen },
                )
            }

            if (setupOpen) {
                item {
                    Card(
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            GatewayStep("1", "Connect USB", "Allow Android USB debugging once.")
                            GatewayStep("2", "Choose this phone", "Select it in Cyclone One on your PC.")
                            GatewayStep("3", "Allow the PC", "Approve the Cyclone AI trust request here.")
                        }
                    }
                }
            }

            item {
                ExpandButton(
                    title = "Technical details",
                    expanded = technicalOpen,
                    onClick = { technicalOpen = !technicalOpen },
                )
            }

            if (technicalOpen) {
                item {
                    Card(
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("Bridge diagnostics", fontWeight = FontWeight.SemiBold)
                            DiagnosticLine("Socket", status.optString("socketLifecycleState").ifBlank { "UNKNOWN" })
                            DiagnosticLine("Gateway", if (enabled) "ENABLED" else "DISABLED")
                            DiagnosticLine("ADB clients", (session?.optInt("clientCount") ?: 0).toString())
                            DiagnosticLine("AI trust", trustState.ifBlank { "UNKNOWN" })
                            DiagnosticLine("Semantic", status.optString("semanticObservationState").ifBlank { "UNKNOWN" })
                            DiagnosticLine(
                                "Action authority",
                                status.optString("actionAuthorityState").ifBlank {
                                    if (productionAuthority) "READY" else "DEGRADED"
                                },
                            )
                            DiagnosticLine("Clipboard", if (clipboardEnabled) "PC → PHONE" else "OFF")
                            DiagnosticLine("Protocol", status.optString("protocolVersion").ifBlank { "Unknown" })

                            if (pairingCode != null) {
                                HorizontalDivider(Modifier.padding(vertical = 5.dp))
                                Text("Legacy fallback pairing", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Only for transition companions or non-USB fallback.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                OutlinedButton(
                                    onClick = { scanDesktopPairingQr(context) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Scan fallback QR")
                                }
                                Text(
                                    pairingCode,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                val seconds = (((pairingExpiresAt ?: 0L) - System.currentTimeMillis())
                                    .coerceAtLeast(0L) / 1000L)
                                Text(
                                    "Expires in about ${seconds}s.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            HorizontalDivider(Modifier.padding(vertical = 5.dp))
                            Text(
                                "USB localabstract only · no phone LAN listener · no arbitrary shell/root tools. Trust/session secrets and typed values are excluded from diagnostics.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LivePhoneCard(
    state: LivePhoneUiState,
    onStateChanged: (LivePhoneUiState) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.primary,
                ) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.PhoneAndroid, null, Modifier.size(23.dp))
                    }
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("Live phone", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Cloud AI agents · visible screen",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusChip(
                    text = when (state) {
                        LivePhoneUiState.ON -> "ON"
                        LivePhoneUiState.PAUSED -> "PAUSED"
                        LivePhoneUiState.OFF -> "OFF"
                    },
                    active = state == LivePhoneUiState.ON,
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LivePhoneChoice("On", state == LivePhoneUiState.ON, Modifier.weight(1f)) {
                    onStateChanged(LivePhoneUiState.ON)
                }
                LivePhoneChoice("Pause", state == LivePhoneUiState.PAUSED, Modifier.weight(1f)) {
                    onStateChanged(LivePhoneUiState.PAUSED)
                }
                LivePhoneChoice("Off", state == LivePhoneUiState.OFF, Modifier.weight(1f)) {
                    onStateChanged(LivePhoneUiState.OFF)
                }
            }
        }
    }
}

@Composable
private fun LivePhoneChoice(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Box(Modifier.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun StatusChip(text: String, active: Boolean) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun GatewayStatusLine(
    icon: ImageVector,
    title: String,
    value: String,
    ready: Boolean,
    attention: Boolean = false,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, modifier = Modifier.size(21.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
        Surface(
            modifier = Modifier.size(8.dp),
            shape = CircleShape,
            color = when {
                ready -> MaterialTheme.colorScheme.secondary
                attention -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.outline
            },
        ) {}
        Spacer(Modifier.width(7.dp))
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun GatewayFixAction(
    action: GatewayReadyNextAction,
    gatewayEnabled: Boolean,
    onTurnOnGateway: () -> Unit,
    onOpenAccessibility: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(action.title, fontWeight = FontWeight.SemiBold)
                Text(action.body, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Button(
                onClick = {
                    when (action.code) {
                        GatewayReadyDoctor.TURN_ON_GATEWAY -> onTurnOnGateway()
                        GatewayReadyDoctor.FIX_USB_BRIDGE -> if (!gatewayEnabled) onTurnOnGateway()
                        GatewayReadyDoctor.REPAIR_PHONE_CONTROL,
                        GatewayReadyDoctor.ENABLE_PHONE_CONTROL,
                        -> onOpenAccessibility()
                        else -> Unit
                    }
                },
                contentPadding = PaddingValues(horizontal = 13.dp, vertical = 7.dp),
            ) {
                Text(action.actionLabel, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ExpandButton(
    title: String,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(title, modifier = Modifier.weight(1f))
        Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
    }
}

@Composable
private fun GatewayStep(number: String, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Text(
                number,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
    }
}

private fun scanDesktopPairingQr(context: Context) {
    val activity = context as? ComponentActivity ?: return
    val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .enableAutoZoom()
        .build()
    GmsBarcodeScanning.getClient(activity, options).startScan()
        .addOnSuccessListener { barcode ->
            val approved = barcode.rawValue?.let(GatewayDesktopPairingManager::approveQrPayload) == true
            Toast.makeText(
                context,
                if (approved) "Fallback pairing approved. Return to Cyclone on your PC."
                else "That fallback QR is invalid or expired. Request a new one on your PC.",
                Toast.LENGTH_LONG,
            ).show()
        }
        .addOnFailureListener {
            Toast.makeText(context, "QR scanner unavailable. Use the fallback code below.", Toast.LENGTH_LONG).show()
        }
}

/** "Last used 5 min ago" for a linked PC; the clock comes from the phone. */
internal fun linkedPcLastUsed(pc: GatewayTrustedPc, now: Long = System.currentTimeMillis()): String {
    val at = maxOf(pc.lastSessionAtMs, pc.createdAtMs)
    if (at <= 0L) return "Not used yet"
    val minutes = ((now - at).coerceAtLeast(0L) / 60_000L)
    return when {
        minutes < 1 -> "Last used just now"
        minutes < 60 -> "Last used $minutes min ago"
        minutes < 48 * 60 -> "Last used ${minutes / 60} h ago"
        else -> "Last used ${minutes / (24 * 60)} days ago"
    }
}
