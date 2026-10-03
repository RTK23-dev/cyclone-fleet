package com.cyclone.mobile.ui.v32

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.content.Context
import android.content.Intent
import com.cyclone.mobile.ui.overlay.CycloneAiSettingsActivity
import com.cyclone.mobile.ui.overlay.OverlayExternalInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ai.OpenRouterCatalogStore
import com.cyclone.mobile.ai.OpenRouterModelPreset
import com.cyclone.mobile.ai.OpenRouterModelPresets

private fun modelSubtitle(model: OpenRouterModelPreset): String = when (model.id) {
    OpenRouterModelPresets.DEFAULT.id -> "Default · fast everyday tasks"
    OpenRouterModelPresets.GPT_6_ASTRA.id -> "Best for complex phone tasks"
    OpenRouterModelPresets.GPT_5_6_SOL.id -> "Fast and efficient"
    OpenRouterModelPresets.CLAUDE_FABLE_5_1.id -> "Deep analysis"
    OpenRouterModelPresets.GEMINI_3_8_FLASH.id -> "Fast multimodal"
    OpenRouterModelPresets.MUSE_SPARK_1_3_CONTRIBUTOR.id -> "Lower cost · contributes training data"
    OpenRouterModelPresets.MUSE_SPARK_1_3.id -> "Balanced multimodal"
    OpenRouterModelPresets.GLM_5_3_FLASH.id -> "Fast alternative"
    else -> "Cyclone model"
}

internal fun cycloneShortModelLabel(label: String): String {
    val core = label.substringAfter(':').trim().ifBlank { label }
    return core.split(Regex("\\s+")).filter { it.isNotBlank() }.take(2).joinToString(" ").ifBlank { "Cyclone" }
}

private enum class OverlaySettingsStep { MODEL, INTELLIGENCE }

@Composable
fun CycloneModelIntelligencePanel(
    modelId: String,
    effort: String,
    showModelSelector: Boolean = true,
    onChange: (String, String) -> Unit,
) {
    if (showModelSelector) OverlaySettingsWizard(modelId, effort, onChange)
    else StandardIntelligencePanel(modelId, effort, onChange)
}

@Composable
internal fun CycloneModelPickerList(
    modelId: String,
    onChange: (String, String) -> Unit,
    onDismiss: () -> Unit = {},
) {
    val context = LocalContext.current
    val catalogRevision by OpenRouterCatalogStore.revision.collectAsState()
    val pickerModels = remember(catalogRevision) { OpenRouterCatalogStore.picker(context) }
    val currentModel = V39AiChatContract.modelForStored(modelId).let { model ->
        model.takeIf { candidate -> pickerModels.any { it.id == candidate.id } }
            ?: OpenRouterCatalogStore.preset(context, OpenRouterCatalogStore.activeId(context))
    }
    ModelPickerRows(pickerModels, currentModel) { option ->
        OpenRouterCatalogStore.setActive(context, option.id)
        onChange(
            V39AiChatContract.storageId(option),
            OpenRouterCatalogStore.reasoningSelection(context, option.id).orEmpty(),
        )
        onDismiss()
    }
}

@Composable
private fun ModelPickerRows(
    models: List<OpenRouterModelPreset>,
    currentModel: OpenRouterModelPreset,
    onSelect: (OpenRouterModelPreset) -> Unit,
) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        if (models.isEmpty()) {
            Text(
                "No enabled models are available yet.",
                Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { openCycloneModelSettings(context) }) { Text("Open Settings") }
            return@Column
        }
        models.forEachIndexed { index, option ->
            val selected = V39AiChatContract.storageId(option) == V39AiChatContract.storageId(currentModel)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(option) }
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (selected) {
                    Icon(Icons.Rounded.Check, "Selected", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                } else {
                    Spacer(Modifier.size(18.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(option.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        modelSubtitle(option),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (index != models.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 38.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f),
                )
            }
        }
    }
}

@Composable
private fun OverlaySettingsWizard(
    modelId: String,
    effort: String,
    onChange: (String, String) -> Unit,
) {
    val context = LocalContext.current
    var step by remember { mutableStateOf(OverlaySettingsStep.MODEL) }
    var modelListOpen by remember { mutableStateOf(false) }
    val catalogRevision by OpenRouterCatalogStore.revision.collectAsState()
    val pickerModels = remember(catalogRevision) { OpenRouterCatalogStore.picker(context) }
    val currentModel = V39AiChatContract.modelForStored(modelId).let { model ->
        model.takeIf { candidate -> pickerModels.any { it.id == candidate.id } }
            ?: OpenRouterCatalogStore.preset(context, OpenRouterCatalogStore.activeId(context))
    }
    val reasoningOptions = remember(catalogRevision, currentModel.id) {
        OpenRouterCatalogStore.reasoningOptions(context, currentModel.id)
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            OverlaySettingsStep.entries.forEach { item ->
                val active = item == step
                Text(
                    when (item) {
                        OverlaySettingsStep.MODEL -> "Model"
                        OverlaySettingsStep.INTELLIGENCE -> "Intelligence"
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        .clickable(role = Role.Tab) { step = item }.padding(vertical = 14.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        when (step) {
            OverlaySettingsStep.MODEL -> {
                CycloneLiquidMenuTrigger(
                    title = "Model",
                    value = currentModel.label,
                    onClick = { modelListOpen = !modelListOpen },
                    modifier = Modifier.fillMaxWidth(),
                    compact = true,
                )
                if (modelListOpen) {
                    ModelPickerRows(pickerModels, currentModel) { option ->
                        OpenRouterCatalogStore.setActive(context, option.id)
                        val exact = OpenRouterCatalogStore.reasoningSelection(context, option.id).orEmpty()
                        onChange(V39AiChatContract.storageId(option), exact)
                        modelListOpen = false
                        step = OverlaySettingsStep.INTELLIGENCE
                    }
                }
            }

            OverlaySettingsStep.INTELLIGENCE -> {
                if (currentModel.id.isBlank()) {
                    Text("Choose an available model first.", style = MaterialTheme.typography.bodySmall)
                } else {
                    CycloneReasoningSelector(currentModel.id, compact = true) { selected ->
                        onChange(V39AiChatContract.storageId(currentModel), selected)
                    }
                    if (reasoningOptions.isEmpty()) {
                        Text(
                            "This model manages its own intelligence level.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        TextButton(onClick = { openCycloneModelSettings(context) }) {
            Text("Manage models in Settings")
        }
    }
}

@Composable
private fun StandardIntelligencePanel(modelId: String, effort: String, onChange: (String, String) -> Unit) {
    val context = LocalContext.current
    val canonical = OpenRouterCatalogStore.canonicalId(modelId)

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (canonical.isBlank()) {
            Text("Choose an available model first.", style = MaterialTheme.typography.bodySmall)
        } else {
            CycloneReasoningSelector(canonical, compact = true, showHelper = false) { selected ->
                onChange(modelId, selected)
            }
        }
    }
}

/** Compact header trigger. Expansion is an overlay owned by the page so the canvas does not reflow. */
@Composable
fun CycloneModelPill(
    modelId: String,
    effort: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    compactHeader: Boolean = false,
    expandInLayout: Boolean = true,
    expanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    onChange: (String, String) -> Unit,
) {
    val context = LocalContext.current
    var internalOpen by remember { mutableStateOf(false) }
    val open = expanded ?: internalOpen
    fun setOpen(value: Boolean) {
        if (expanded == null) internalOpen = value
        onExpandedChange?.invoke(value)
    }
    val catalogRevision by OpenRouterCatalogStore.revision.collectAsState()
    val pickerModels = remember(catalogRevision) { OpenRouterCatalogStore.picker(context) }
    val currentModel = V39AiChatContract.modelForStored(modelId).let { model ->
        model.takeIf { candidate -> pickerModels.any { it.id == candidate.id } }
            ?: OpenRouterCatalogStore.preset(context, OpenRouterCatalogStore.activeId(context))
    }
    val selectedEffort = remember(catalogRevision, currentModel.id) { OpenRouterCatalogStore.reasoningSelection(context, currentModel.id) }
    val defaultEffort = remember(catalogRevision, currentModel.id) { OpenRouterCatalogStore.defaultReasoningEffort(context, currentModel.id) }
    val intelligenceLabel = selectedEffort?.let(::reasoningEffortLabel)
        ?: defaultEffort?.let { "${reasoningEffortLabel(it)} default" }
        ?: "Model controlled"

    Column(modifier, horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (compactHeader) {
            Row(
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clickable(enabled = enabled, role = Role.Button) { setOpen(!open) }
                    .padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    cycloneShortModelLabel(currentModel.label.ifBlank { "Cyclone" }),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = "Choose model",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            CycloneLiquidMenuTrigger(
                title = "Model",
                value = "${currentModel.label} · $intelligenceLabel",
                onClick = { if (enabled) setOpen(!open) },
                modifier = Modifier.widthIn(max = 240.dp),
                compact = true,
                enabled = enabled,
            )
        }
        if (open && expandInLayout) {
            CycloneLiquidPanel(
                modifier = Modifier.widthIn(min = 268.dp, max = 320.dp),
                cornerRadius = 22.dp,
                contentPadding = PaddingValues(6.dp),
            ) {
                ModelPickerRows(pickerModels, currentModel) { option ->
                    OpenRouterCatalogStore.setActive(context, option.id)
                    onChange(
                        V39AiChatContract.storageId(option),
                        OpenRouterCatalogStore.reasoningSelection(context, option.id).orEmpty(),
                    )
                    setOpen(false)
                }
            }
        }
    }
}

/**
 * Legacy compact host retained for callers that need Tune + optional model pill. Expansion stays in
 * the same Compose hierarchy, so it cannot reproduce the popup/backdrop crash from 4.4.4.
 */
@Composable
fun CycloneIntelligenceControls(
    enabled: Boolean = true,
    showModelPill: Boolean = true,
    onChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    var intelligenceOpen by remember { mutableStateOf(false) }
    val catalogRevision by OpenRouterCatalogStore.revision.collectAsState()
    var model by remember(catalogRevision) {
        mutableStateOf(V39AiChatContract.modelForStored(OpenRouterCatalogStore.activeId(context)))
    }

    fun persist(modelId: String, effort: String) {
        val resolved = V39AiChatContract.modelForStored(modelId)
        if (resolved.id.isNotBlank()) {
            OpenRouterCatalogStore.setActive(context, resolved.id)
            model = OpenRouterCatalogStore.preset(context, resolved.id)
        }
        onChanged()
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CycloneTrayIconAction(
                onClick = { intelligenceOpen = !intelligenceOpen },
                enabled = enabled,
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    Icons.Rounded.Tune,
                    "Intelligence and phone autonomy",
                    Modifier.size(20.dp),
                    tint = if (intelligenceOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showModelPill) {
                CycloneModelPill(
                    modelId = V39AiChatContract.storageId(model),
                    effort = OpenRouterCatalogStore.reasoningSelection(context, model.id).orEmpty(),
                    enabled = enabled,
                    onChange = ::persist,
                )
            }
        }
        if (intelligenceOpen) {
            CycloneLiquidPanel(
                modifier = Modifier.widthIn(min = 268.dp, max = 320.dp),
                cornerRadius = 22.dp,
                contentPadding = PaddingValues(6.dp),
            ) {
                CycloneModelIntelligencePanel(
                    modelId = V39AiChatContract.storageId(model),
                    effort = OpenRouterCatalogStore.reasoningSelection(context, model.id).orEmpty(),
                    showModelSelector = false,
                    onChange = ::persist,
                )
            }
        }
    }
}

/** Opens the real catalog/API configuration, including from an accessibility overlay. */
private fun openCycloneModelSettings(context: Context) {
    OverlayExternalInteraction.active.value = true
    runCatching {
        context.startActivity(Intent(context, CycloneAiSettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        OverlayExternalInteraction.active.value = false
        android.widget.Toast.makeText(context, "Settings could not be opened.", android.widget.Toast.LENGTH_SHORT).show()
    }
}
