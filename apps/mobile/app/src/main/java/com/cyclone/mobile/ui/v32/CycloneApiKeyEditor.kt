package com.cyclone.mobile.ui.v32

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ai.ApiKeySettingsOperation
import com.cyclone.mobile.ai.OpenRouterSecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared by Settings and the legacy Model & API route. Drafts never enter saved state. */
@Composable
internal fun CycloneApiKeyEditor(context: Context, onChanged: () -> Unit = {}) {
    var hasKey by remember { mutableStateOf(OpenRouterSecretStore.hasKey(context)) }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(if (hasKey) "API key secured" else "Add your OpenRouter key", style = MaterialTheme.typography.titleSmall)
        Text(
            "Protected by Android Keystore",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!hasKey) {
            CycloneLiquidTray(modifier = Modifier.fillMaxWidth(), height = 56.dp, contentPadding = 4.dp) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Key,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it; error = null },
                        enabled = !busy,
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp, vertical = 12.dp)
                            .semantics { contentDescription = "OpenRouter API key" },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { field ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (draft.isEmpty()) {
                                    Text(
                                        "OpenRouter API key",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                                field()
                            }
                        },
                    )
                }
            }
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        val act: () -> Unit = {
            busy = true
            val removing = hasKey
            val submitted = draft
            scope.launch {
                try {
                    error = withContext(Dispatchers.IO) {
                        if (removing) ApiKeySettingsOperation.remove { OpenRouterSecretStore.clear(context) }
                        else ApiKeySettingsOperation.save(
                            submitted,
                            { OpenRouterSecretStore.save(context, it) },
                            { OpenRouterSecretStore.read(context) },
                        )
                    }
                    if (error == null) {
                        draft = ""
                        hasKey = !removing
                        onChanged()
                    }
                } finally {
                    busy = false
                }
            }
        }

        if (hasKey) {
            CycloneLiquidDestructiveAction(
                label = if (busy) "Removing…" else "Remove",
                onClick = act,
                enabled = !busy,
            )
        } else {
            CycloneLiquidTextAction(
                label = if (busy) "Securing…" else "Secure key",
                onClick = act,
                enabled = !busy && draft.isNotBlank(),
                prominent = true,
            )
        }
    }
}
