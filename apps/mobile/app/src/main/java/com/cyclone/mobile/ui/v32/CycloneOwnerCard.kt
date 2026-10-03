package com.cyclone.mobile.ui.v32

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.mind.mission.OwnerField
import com.cyclone.mobile.owner.MomentAction
import com.cyclone.mobile.owner.MomentKind
import com.cyclone.mobile.owner.OwnerMoment
import com.cyclone.mobile.task.TaskCommand
import com.cyclone.mobile.task.TaskCommands

/**
 * The one card family through which any task talks to its owner (Owner Moments), the same in the overlay, in Ask and
 * behind the notification, whichever engine runs the task. Every button is a Task Kit command to that engine. The
 * card offers the two ways forward a person expects: give Cyclone what it needs right here, or take the phone and do
 * it yourself. Secrets never come through this card; they have the Secrets Card.
 */
@Composable
fun CycloneOwnerCard(moment: OwnerMoment, modifier: Modifier = Modifier, framed: Boolean = true) {
    val content: @Composable () -> Unit = { OwnerCardContent(moment) }
    if (framed) CycloneSignatureCard(modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { content() } }
    else Column(modifier.fillMaxWidth()) { content() }
}

@Composable
private fun OwnerCardContent(moment: OwnerMoment) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val send: (TaskCommand) -> Unit = { command -> TaskCommands.send(context, moment.taskId, command) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(moment.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(moment.text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            }
            moment.dismissal?.let { dismissal ->
                IconButton(onClick = { send(dismissal.command) }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = dismissal.label)
                }
            }
        }
        when (moment.kind) {
            MomentKind.VALUES -> ValuesBody(moment, send)
            MomentKind.QUESTION -> QuestionBody(moment, send)
            MomentKind.SECRET -> {
                Text("Use the Secrets Card on screen. Cyclone never sees what you enter.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ActionRow(moment.actions, send)
            }
            MomentKind.APPROVAL -> {
                // Plan 26 (A42-7): a background task's approval shows what is being approved.
                com.cyclone.mobile.ui.overlay.BackgroundGlimpse(moment.taskId)
                ActionRow(moment.actions, send)
            }
            MomentKind.HANDOVER -> ActionRow(moment.actions, send)
        }
    }
}

@Composable
private fun ValuesBody(moment: OwnerMoment, send: (TaskCommand) -> Unit) {
    val values = remember(moment.requestId) { mutableStateMapOf<String, String>() }
    var remember by remember(moment.requestId) { mutableStateOf(false) }
    moment.fields.forEachIndexed { index, field ->
        if (field.kind == "choice" && field.choices.isNotEmpty()) {
            Text(field.label, style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                field.choices.forEach { choice ->
                    CycloneLiquidFilterChip(selected = values[field.label] == choice, onClick = { values[field.label] = choice }, label = choice)
                }
            }
        } else {
            OutlinedTextField(
                value = values[field.label].orEmpty(),
                onValueChange = { values[field.label] = it.take(300) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(field.label) },
                placeholder = OwnerCardCopy.placeholder(field)?.let { hint -> { Text(hint) } },
                singleLine = field.kind != "address",
                keyboardOptions = OwnerCardCopy.keyboard(field, last = index == moment.fields.lastIndex),
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = remember, onCheckedChange = { remember = it })
        Text("Remember for next time", style = MaterialTheme.typography.bodySmall)
    }
    ActionRow(moment.actions, send, ready = values.values.any { it.isNotBlank() }) {
        TaskCommand.Fill(values.filterValues { it.isNotBlank() }.toMap(), remember)
    }
}

@Composable
private fun QuestionBody(moment: OwnerMoment, send: (TaskCommand) -> Unit) {
    var answer by remember(moment.requestId) { mutableStateOf("") }
    if (moment.choices.isNotEmpty()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            moment.choices.forEach { choice ->
                CycloneLiquidFilterChip(selected = false, onClick = { send(TaskCommand.Reply(choice)) }, label = choice)
            }
        }
    }
    OutlinedTextField(answer, { answer = it.take(500) }, Modifier.fillMaxWidth(), label = { Text("Your answer") },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send))
    ActionRow(moment.actions, send, ready = answer.isNotBlank()) { TaskCommand.Reply(answer.trim()) }
}

/**
 * The moment's buttons: secondary ones outlined, the primary one filled. A button whose command needs what the owner
 * typed ([MomentAction.needsInput]) takes it from [input] and is enabled only when [ready].
 */
@Composable
private fun ActionRow(actions: List<MomentAction>, send: (TaskCommand) -> Unit, ready: Boolean = true, input: () -> TaskCommand? = { null }) {
    if (actions.isEmpty()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        actions.forEach { action ->
            val onClick = { (if (action.needsInput) input() else action.command)?.let(send); Unit }
            val modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = action.label }
            if (action.primary) Button(onClick = onClick, enabled = !action.needsInput || ready, modifier = modifier, shape = RoundedCornerShape(16.dp)) { Text(action.label) }
            else OutlinedButton(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(16.dp)) { Text(action.label) }
        }
    }
}

/** Words and keyboards for the owner card; kept apart from the composables so they are unit-testable. */
object OwnerCardCopy {
    fun placeholder(field: OwnerField): String? = when (field.kind) {
        "date" -> "e.g. 12 March 1990"
        "email" -> "name@example.com"
        "phone" -> "+31 6 12345678"
        else -> null
    }

    fun keyboard(field: OwnerField, last: Boolean): KeyboardOptions = KeyboardOptions(
        keyboardType = when (field.kind) {
            "email" -> KeyboardType.Email
            "phone" -> KeyboardType.Phone
            "number" -> KeyboardType.Number
            else -> KeyboardType.Text
        },
        capitalization = if (field.kind in setOf("name", "address", "text")) KeyboardCapitalization.Words else KeyboardCapitalization.None,
        imeAction = if (last) ImeAction.Done else ImeAction.Next,
    )

}
