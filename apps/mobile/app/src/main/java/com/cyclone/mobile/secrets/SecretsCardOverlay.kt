package com.cyclone.mobile.secrets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ui.v32.CycloneSignatureCard

@Composable
internal fun SecretsCardOverlay(
    state: SecretsCardUiState,
    modifier: Modifier = Modifier,
) {
    var draft by remember(state.request.placeId, state.request.persona, state.request.slot) {
        mutableStateOf("")
    }

    DisposableEffect(state.request.placeId, state.request.persona, state.request.slot) {
        onDispose { draft = "" }
    }

    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    ) {
        CycloneSignatureCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Rounded.Key,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            SecretsCardCopy.title(state),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            SecretsCardCopy.slotLine(state),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = SecretsCardRuntime::cancel,
                        enabled = !state.busy,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = "Cancel")
                    }
                }

                Text(
                    SecretsCardCopy.reasonLine(state),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    SecretsCardCopy.personaLine(state),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (state.hasStoredSlot && state.canUseStored) {
                    Button(
                        onClick = SecretsCardRuntime::useStored,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text("Use saved " + state.request.slot)
                    }
                    Text(
                        "The saved value stays hidden.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(if (state.hasStoredSlot) "Replace " + state.request.slot else state.request.slot)
                    },
                    placeholder = { Text("Enter value") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                )

                Button(
                    onClick = {
                        val submitted = draft.toCharArray()
                        draft = ""
                        SecretsCardRuntime.submit(submitted)
                    },
                    enabled = draft.isNotEmpty() && !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(if (state.hasStoredSlot) "Replace and continue" else "Save and continue")
                }

                SecretsCardCopy.errorLine(state)?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                TextButton(
                    onClick = SecretsCardRuntime::skip,
                    enabled = !state.busy,
                    modifier = Modifier.align(Alignment.End).heightIn(min = 44.dp),
                ) {
                    Text("Skip")
                }
            }
        }
    }
}
