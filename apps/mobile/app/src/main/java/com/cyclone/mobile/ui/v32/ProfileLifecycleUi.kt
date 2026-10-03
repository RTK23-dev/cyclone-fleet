package com.cyclone.mobile.ui.v32

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cyclone.mobile.runtime.workspaces.CarryReport
import com.cyclone.mobile.runtime.workspaces.CarryRules
import com.cyclone.mobile.runtime.workspaces.CycloneProfileRecord
import com.cyclone.mobile.runtime.workspaces.ProfileBackup
import com.cyclone.mobile.runtime.workspaces.ProfileTrash
import java.text.DateFormat
import java.util.Date

/** The colours a profile can wear (plan 40 P1), the same family as Settings' group tiles. */
internal val ProfileColors: List<Long> = listOf(0xFF3E7BFA, 0xFF7B61FF, 0xFF30B85A, 0xFFFF9F0A, 0xFFFF6B5E, 0xFF32ADE6, 0xFFE056FD, 0xFF8E8E93)

/** One profile in Recently deleted: what it is, how long it stays, Restore and Delete now. */
@Composable
internal fun ProfileTrashCard(
    record: CycloneProfileRecord,
    nowMs: Long,
    busy: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    CycloneSimpleCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProfileBadge(record.emoji, record.color, record.label)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(record.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${record.packages.size} ${if (record.packages.size == 1) "app" else "apps"} · ${ProfileTrash.line(record.removedAtMs ?: nowMs, nowMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CycloneLiquidTextAction("Restore", onRestore, Modifier.weight(1f), enabled = !busy, prominent = true)
            CycloneLiquidTextAction("Delete now", onDelete, Modifier.weight(1f), enabled = !busy)
        }
    }
}

/** An automatic backup: which profile, when, how big, and what didn't fit. */
@Composable
internal fun ProfileBackupRow(context: Context, backup: ProfileBackup, busy: Boolean, onDelete: () -> Unit) {
    CycloneSimpleCard(Modifier.fillMaxWidth()) {
        Text(backup.label, style = MaterialTheme.typography.titleSmall)
        Text(
            "Backed up ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(backup.createdAtMs))} · " +
                Formatter.formatShortFileSize(context, backup.bytes) + " · ${backup.packages.size} apps" +
                if (backup.skipped.isNotEmpty()) " · ${backup.skipped.size} didn't fit" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CycloneLiquidTextAction("Delete backup", onDelete, Modifier.fillMaxWidth(), enabled = !busy)
    }
}

/** A round badge in the profile's colour with its emoji, or its first letter. */
@Composable
internal fun ProfileBadge(emoji: String?, color: Long?, label: String, size: Int = 44) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Color(color ?: 0xFF3E7BFA)),
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji ?: label.take(1).uppercase(), fontSize = (size * 0.45f).sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

/** Name, emoji and colour in one small sheet. */
@Composable
internal fun ProfileLookDialog(
    record: CycloneProfileRecord,
    onSave: (label: String, emoji: String?, color: Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf(record.label) }
    var emoji by remember { mutableStateOf(record.emoji.orEmpty()) }
    var color by remember { mutableStateOf(record.color ?: ProfileColors.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ProfileBadge(emoji.ifBlank { null }, color, label.ifBlank { "?" }, 52)
                    OutlinedTextField(label, { label = it.take(40) }, Modifier.weight(1f), label = { Text("Name") }, singleLine = true)
                }
                OutlinedTextField(emoji, { emoji = it.take(16) }, Modifier.fillMaxWidth(), label = { Text("Emoji (optional)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfileColors.forEach { c ->
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(Color(c))
                                .then(if (c == color) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
                                .clickable(role = Role.RadioButton) { color = c }
                                .semantics { contentDescription = if (c == color) "Colour, selected" else "Colour" },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(label, emoji.ifBlank { null }, color) }, enabled = label.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A plain confirmation with the consequence spelled out. */
@Composable
internal fun ProfileConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Plan 40 P2: one quiet line about Cyclone Carry: what the last switch brought into this profile, and a note when the
 * last carry out of it didn't arrive (the switch itself still happened).
 */
@Composable
internal fun ProfileCarryNote(report: CarryReport?, sentFailed: Boolean) {
    val line = report?.let { r -> CarryRules.line(r)?.let { "$it · ${DateUtils.getRelativeTimeSpanString(r.atMs)}" } }
    CycloneSimpleCard(Modifier.fillMaxWidth()) {
        Text("Cyclone Carry", style = MaterialTheme.typography.titleSmall)
        Text(
            listOfNotNull(
                line ?: "Your memory, skills and settings go with you each time you switch profiles.",
                if (sentFailed) "Last time, what you learned here couldn't be carried to the next profile. It will go with your next switch." else null,
            ).joinToString("\n"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The words for each lifecycle step, in one place (and pinned by tests). */
internal object ProfileLifecycleCopy {
    const val REMOVE_TITLE = "Remove this profile?"
    fun removeBody(label: String) =
        "$label stops and moves to Recently deleted. It stays there ${ProfileTrash.TRASH_DAYS} days, untouched: Restore brings it back " +
            "exactly as it was. After that, Cyclone backs it up automatically and deletes it."
    const val DELETE_TITLE = "Delete for good?"
    fun deleteBody(label: String) =
        "Cyclone backs up $label first (its apps' data and Cyclone's own, as much as fits), then Android deletes the profile. " +
            "This can't be undone; the backup is kept ${ProfileTrash.BACKUP_DAYS} days."
    const val EMPTY_TITLE = "Delete all?"
    const val EMPTY_BODY = "Every profile in Recently deleted is backed up, then deleted for good."
    const val TRASH_NOTE = "Removed profiles stay here ${ProfileTrash.TRASH_DAYS} days. Then Cyclone backs each one up and deletes it."
}
