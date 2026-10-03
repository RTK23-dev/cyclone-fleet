package com.cyclone.mobile.secrets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.cyclone.mobile.ui.v32.CycloneSectionTitle
import com.cyclone.mobile.ui.v32.CycloneSimpleCard
import com.cyclone.mobile.ui.v32.CycloneStatusPill



/**
 * Self-contained mount target for the root Settings screen. It reads metadata only.
 * Replace opens the Secrets Card; Delete removes the encrypted record without exposing a value.
 */
@Composable
fun VaultSettingsPanel(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cardState by SecretsCardRuntime.state.collectAsState()
    var refreshSerial by remember { mutableStateOf(0) }
    val slots = remember(cardState?.visible, cardState?.lastStatus, refreshSerial) {
        SecretsVaultRuntime.allSlots(context)
    }

    VaultSettingsSection(
        slots = slots,
        onReplace = { key ->
            SecretsCardRuntime.request(
                context = context,
                request = SecretRequestMetadata(
                    placeId = key.placeId,
                    persona = key.persona,
                    slot = key.slotName,
                    reason = "Replace saved slot",
                ),
            )
        },
        onDelete = { key ->
            SecretsVaultRuntime.delete(context, key)
            refreshSerial += 1
        },
        modifier = modifier,
    )
}

internal data class VaultSlotRowUi(
    val key: SecretSlotKey,
    val placeLabel: String,
    val personaLabel: String,
    val slotLabel: String,
    val statusLabel: String,
    val present: Boolean,
) {
    fun renderedStrings(): List<String> =
        listOf(placeLabel, personaLabel, slotLabel, statusLabel)
}

internal object VaultSettingsPresenter {
    fun rows(slots: List<SecretSlotMetadata>): List<VaultSlotRowUi> =
        slots.sortedWith(
            compareBy<SecretSlotMetadata> { it.key.placeId }
                .thenBy { it.key.persona.wireValue }
                .thenBy { it.key.slotName.lowercase() },
        ).map { metadata ->
            VaultSlotRowUi(
                key = metadata.key,
                placeLabel = metadata.key.placeId,
                personaLabel = metadata.key.persona.wireValue,
                slotLabel = metadata.key.slotName,
                statusLabel = if (metadata.present) "Present" else "Missing",
                present = metadata.present,
            )
        }
}

/**
 * Mountable Vault settings section. The root Settings navigation intentionally remains untouched
 * during parallel Run-1 work so Agent 003 can add Atlas UI without a collision.
 */
@Composable
fun VaultSettingsSection(
    slots: List<SecretSlotMetadata>,
    onReplace: (SecretSlotKey) -> Unit,
    onDelete: (SecretSlotKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(com.cyclone.mobile.ui.v32.CycloneSpacing.Section)) {
        CycloneSectionTitle("Vault")
        DeviceKeyCard()
        if (slots.isEmpty()) {
            CycloneSimpleCard(Modifier.fillMaxWidth()) {
                Text(
                    "No secret slots have been requested yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@Column
        }

        VaultSettingsPresenter.rows(slots).forEach { row ->
            CycloneSimpleCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(row.slotLabel, style = MaterialTheme.typography.titleSmall)
                        Text(
                            row.placeLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Persona: " + row.personaLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    CycloneStatusPill(row.statusLabel, positive = row.present)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { onReplace(row.key) }) {
                        Text("Replace")
                    }
                    TextButton(
                        onClick = { onDelete(row.key) },
                        enabled = row.present,
                    ) {
                        Text("Delete")
                    }
                }
            }
        }
    }
}

/**
 * Plan 33 C2: this phone's key for passwords sent from the Command Center. The owner compares the fingerprint with the
 * one Glass shows before trusting the phone. Public information only.
 */
@Composable
private fun DeviceKeyCard() {
    val key = remember { runCatching { DeviceKey.ensure() }.getOrNull() }
    CycloneSimpleCard(Modifier.fillMaxWidth()) {
        Text("Command Center key", style = MaterialTheme.typography.titleSmall)
        Text(
            key?.fingerprint ?: "Not available on this phone",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        )
        Text(
            if (key == null) "Passwords can't be sent to this phone from the PC." else
                "Glass shows the same letters under Vault → Phones. Trust this phone only if they match." +
                    if (key.strongBox) " Kept in this phone's StrongBox chip." else " Kept in this phone's secure hardware.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
