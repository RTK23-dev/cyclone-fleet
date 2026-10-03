package com.cyclone.mobile.ui.v32

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ai.OpenRouterCatalogStore

enum class ReasoningSelectorMode { MODEL_CONTROLLED, PILLS, MENU }

fun reasoningSelectorMode(options: List<String>): ReasoningSelectorMode = when {
    options.isEmpty() -> ReasoningSelectorMode.MODEL_CONTROLLED
    options.size <= 3 -> ReasoningSelectorMode.PILLS
    else -> ReasoningSelectorMode.MENU
}

internal fun reasoningEffortLabel(value: String): String = value.replace('_', ' ').split(' ')
    .joinToString(" ") { token -> token.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }

/** Exact OpenRouter reasoning surface; provider tokens are never rounded or invented. */
@Composable
internal fun CycloneReasoningSelector(
    modelId: String,
    compact: Boolean = false,
    showHelper: Boolean = true,
    onChanged: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val revision by OpenRouterCatalogStore.revision.collectAsState()
    val canonical = remember(modelId) { OpenRouterCatalogStore.canonicalId(modelId) }
    val options = remember(revision, canonical) { OpenRouterCatalogStore.reasoningOptions(context, canonical) }
    val selected = remember(revision, canonical) { OpenRouterCatalogStore.reasoningSelection(context, canonical) }
    val defaultEffort = remember(revision, canonical) { OpenRouterCatalogStore.defaultReasoningEffort(context, canonical) }
    var menuOpen by remember(canonical) { mutableStateOf(false) }

    fun choose(value: String?) {
        OpenRouterCatalogStore.setReasoningEffort(context, canonical, value)
        onChanged(value.orEmpty())
    }

    when (reasoningSelectorMode(options)) {
        ReasoningSelectorMode.MODEL_CONTROLLED -> {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "Model controlled",
                    style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (showHelper) {
                    Text(
                        "This model does not expose selectable intelligence levels, so Cyclone leaves reasoning under the model's control.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        ReasoningSelectorMode.PILLS -> {
            val selectedIndex = selected?.let(options::indexOf)?.takeIf { it >= 0 }
            Column(verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)) {
                CycloneLiquidChoiceBar(
                    options = options.map(::reasoningEffortLabel),
                    selectedIndex = selectedIndex,
                    onSelect = { index -> choose(options[index]) },
                    modifier = Modifier.fillMaxWidth(),
                    compact = compact,
                )
                if (showHelper) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            if (selected == null) {
                                "Model default${defaultEffort?.let { ": ${reasoningEffortLabel(it)}" }.orEmpty()}"
                            } else {
                                "Exact provider level · ${reasoningEffortLabel(selected)}"
                            },
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (selected != null) {
                            CycloneLiquidTextAction(label = "Default", onClick = { choose(null) })
                        }
                    }
                }
            }
        }

        ReasoningSelectorMode.MENU -> {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                CycloneLiquidMenuTrigger(
                    title = "Intelligence",
                    value = selected?.let(::reasoningEffortLabel)
                        ?: "Model default${defaultEffort?.let { " · ${reasoningEffortLabel(it)}" }.orEmpty()}",
                    onClick = { menuOpen = !menuOpen },
                    modifier = Modifier.fillMaxWidth(),
                    compact = compact,
                )
                if (menuOpen) {
                    val values: List<String?> = if (showHelper) listOf(null) + options else options
                    values.forEachIndexed { index, effort ->
                        val active = if (effort == null) selected == null else selected == effort
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    choose(effort)
                                    menuOpen = false
                                }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                if (effort == null) {
                                    "Model default${defaultEffort?.let { " · ${reasoningEffortLabel(it)}" }.orEmpty()}"
                                } else reasoningEffortLabel(effort),
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (active) {
                                Icon(Icons.Rounded.Check, "Selected", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        if (index != values.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 12.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f),
                            )
                        }
                    }
                }
            }
        }
    }
}
