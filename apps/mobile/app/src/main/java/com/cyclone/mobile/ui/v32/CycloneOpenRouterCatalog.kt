package com.cyclone.mobile.ui.v32

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ai.CatalogModel
import com.cyclone.mobile.ai.OpenRouterCatalog
import com.cyclone.mobile.ai.OpenRouterCatalogClient
import com.cyclone.mobile.ai.OpenRouterCatalogStore
import com.cyclone.mobile.ai.OpenRouterModelAvailability
import com.cyclone.mobile.ai.OpenRouterSecretStore
import com.cyclone.mobile.ai.ProviderFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Selection is local and free: catalog requests never run a model completion. */
@Composable
internal fun CycloneOpenRouterCatalog(context: Context, onSelectionChanged: () -> Unit = {}) {
    val revision by OpenRouterCatalogStore.revision.collectAsState()
    val models = remember(revision) { OpenRouterCatalogStore.models(context) }
    val selected = remember(revision) { OpenRouterCatalogStore.selectedIds(context) }
    var query by rememberSaveable { mutableStateOf("") }
    var onlySelected by rememberSaveable { mutableStateOf(false) }
    var keyGeneration by remember { mutableStateOf(0) }
    var refresh by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var hasKey by remember { mutableStateOf(OpenRouterSecretStore.hasKey(context)) }

    LaunchedEffect(keyGeneration, refresh) {
        error = null
        loading = hasKey
        if (!hasKey) return@LaunchedEffect
        val key = OpenRouterSecretStore.read(context)
        OpenRouterCatalogStore.invalidateAvailability(context)
        try {
            val fetched = withContext(Dispatchers.IO) {
                val result = OpenRouterCatalogClient().fetch(key)
                ensureActive()
                if (key != OpenRouterSecretStore.read(context)) return@withContext null
                OpenRouterCatalogStore.saveCatalog(context, result.models)
                result.availableIds?.let { OpenRouterCatalogStore.saveAvailability(context, key, it) }
                result
            }
            if (fetched != null) {
                error = fetched.warning?.let { "Catalog loaded, but account availability is not verified. $it" }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = ProviderFailure.sanitize(failure.message ?: "Could not load the catalog. Try refreshing.")
        } finally {
            loading = false
        }
    }

    val rows = remember(models, selected, query, onlySelected) {
        val known = models.map { it.id }.toSet()
        val missing = (selected - known).map { CatalogModel(it, it, false, true, 0, null) }
        OpenRouterCatalog.search(models + missing, query).filter { !onlySelected || it.id in selected }
    }
    val key = remember(revision, keyGeneration) { OpenRouterSecretStore.read(context) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CycloneApiKeyEditor(context) {
            OpenRouterCatalogStore.invalidateAvailability(context)
            hasKey = OpenRouterSecretStore.hasKey(context)
            keyGeneration++
        }

        CycloneLiquidSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search OpenRouter models",
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "${models.size} models · ${selected.size} selected",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CycloneLiquidTextAction(
                label = if (loading) "Refreshing…" else "Refresh",
                onClick = { refresh++ },
                enabled = hasKey && !loading,
            )
        }

        CycloneLiquidFilterChip(
            selected = onlySelected,
            label = "Selected only",
            onClick = { onlySelected = !onlySelected },
        )

        Text(
            "Tap a model to add or remove it from your picker. A green check means selected.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!hasKey) {
            Text(
                "Add your OpenRouter API key to load and verify the catalog.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

        if (rows.isEmpty()) {
            Text(
                if (models.isEmpty()) "Your catalog will appear here." else "No matching models.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
                items(rows, key = { it.id }) { model ->
                    val checked = model.id in selected
                    val listed = models.any { it.id == model.id }
                    val availability = if (hasKey) {
                        OpenRouterCatalogStore.availability(context, key, model.id)
                    } else OpenRouterModelAvailability.UNKNOWN
                    val canSelect = checked || (hasKey && listed && model.textOutput && availability == OpenRouterModelAvailability.AVAILABLE)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(checked, enabled = canSelect, role = Role.Checkbox) { value ->
                                try {
                                    OpenRouterCatalogStore.select(context, model.id, value)
                                    onSelectionChanged()
                                } catch (failure: Exception) {
                                    error = ProviderFailure.sanitize(
                                        failure.message ?: "Could not save your model selection. Please try again.",
                                    )
                                }
                            }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(model.name, style = MaterialTheme.typography.bodyMedium)
                            Text(model.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                when {
                                    !listed -> "No longer in catalog · uncheck to remove"
                                    !model.textOutput -> "Not a chat model"
                                    availability == OpenRouterModelAvailability.UNKNOWN -> "Access not verified for this API key"
                                    availability == OpenRouterModelAvailability.UNAVAILABLE -> "Unavailable under this API key's account settings"
                                    model.imageInput -> "Available · text + images"
                                    else -> "Available · text only"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            if (checked) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                            contentDescription = if (checked) "Selected" else "Not selected",
                            modifier = Modifier.size(24.dp),
                            tint = if (checked) Color(0xFF2EAD62) else MaterialTheme.colorScheme.outline,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                }
            }
        }
    }
}
