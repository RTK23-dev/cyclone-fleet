package com.cyclone.mobile.ui.v32

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cyclone.mobile.gateway.GatewaySettingsActivity
import com.cyclone.mobile.market.ConnectionState
import com.cyclone.mobile.market.InputKind
import com.cyclone.mobile.market.InstalledListing
import com.cyclone.mobile.market.MarketConnection
import com.cyclone.mobile.market.MarketError
import com.cyclone.mobile.market.MarketListing
import com.cyclone.mobile.market.MarketRules
import com.cyclone.mobile.market.Marketplace

/**
 * Cyclone Marketplace on the phone: recipes and connections, laid out like a store (featured, for you, from Cyclone,
 * connections) with an "N installed" entry to manage what you have. Adding saves your inputs; nothing runs until Run.
 */
@Composable
fun CycloneMarketplacePage(context: Context, onBack: () -> Unit, onModelSettings: () -> Unit) {
    val revision by Marketplace.revision.collectAsState()
    val catalog = remember(revision) { Marketplace.ownerSkills(context); Marketplace.catalog() }
    val installed = remember(revision) { Marketplace.installs(context).list().associateBy { it.id } }
    val apps = remember { Marketplace.installedApps(context) }
    val connections = remember(revision) { Marketplace.connections(context) }
    var query by rememberSaveable { mutableStateOf("") }
    var showInstalled by rememberSaveable { mutableStateOf(false) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }

    BackHandler { if (showInstalled) showInstalled = false else onBack() }

    val suggestions = remember(revision) { MarketRules.suggestions(catalog, apps, installed.keys).take(6) }
    val filtered = query.trim().let { q ->
        if (q.isBlank()) emptyList() else catalog.filter {
            it.name.contains(q, true) || it.summary.contains(q, true) || it.category.contains(q, true) ||
                it.apps.any { pkg -> apps[pkg]?.contains(q, true) == true }
        }
    }

    LazyColumn(contentPadding = cyclonePageInsets(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { CycloneBackRow(if (showInstalled) "Marketplace" else "Routines") { if (showInstalled) showInstalled = false else onBack() } }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (showInstalled) "Installed" else "Marketplace", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                if (!showInstalled) InstalledChip(installed.size) { showInstalled = true }
            }
        }
        if (showInstalled) {
            val mine = catalog.filter { it.id in installed }
            if (mine.isEmpty()) item {
                Text("Nothing added yet. Add a recipe from the Marketplace and it appears here.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(mine, key = { it.id }) { listing ->
                ListingRow(listing, installed[listing.id], reason = installed[listing.id]?.let { runsLabel(it) }) { open = listing.id }
            }
            return@LazyColumn
        }
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it.take(60) }, singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                placeholder = { Text("Search recipes and connections") },
                shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotBlank()) {
            if (filtered.isEmpty()) item { Text("No matches.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(filtered, key = { "q-" + it.id }) { listing -> ListingRow(listing, installed[listing.id]) { open = listing.id } }
            return@LazyColumn
        }
        item { CycloneSectionTitle("Featured") }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 8.dp)) {
                items(catalog.filter { it.featured }, key = { "f-" + it.id }) { listing -> FeaturedCard(listing) { open = listing.id } }
            }
        }
        if (suggestions.isNotEmpty()) {
            item { CycloneSectionTitle("For you") }
            items(suggestions, key = { "s-" + it.first.id }) { (listing, reason) ->
                ListingRow(listing, installed[listing.id], reason = reason) { open = listing.id }
            }
        }
        val saved = catalog.filter { it.publisher.id == "owner" }
        if (saved.isNotEmpty()) {
            item { CycloneSectionTitle("Your skills") }
            items(saved, key = { "y-" + it.id }) { listing ->
                ListingRow(listing, installed[listing.id], reason = installed[listing.id]?.let { runsLabel(it) }) { open = listing.id }
            }
        }
        item { CycloneSectionTitle("From Cyclone") }
        items(catalog.filter { it.publisher.id != "owner" }, key = { "c-" + it.id }) { listing -> ListingRow(listing, installed[listing.id]) { open = listing.id } }
        item { CycloneSectionTitle("Connections") }
        items(connections, key = { "k-" + it.id }) { connection ->
            ConnectionRow(connection) {
                when (connection.id) {
                    "openrouter" -> onModelSettings()
                    else -> runCatching { context.startActivity(Intent(context, GatewaySettingsActivity::class.java)) }
                }
            }
        }
        item {
            Text("Recipes run as Cyclone missions: Cyclone still asks before sending, deleting or paying, and passwords only go through the Secrets Card.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    catalog.firstOrNull { it.id == open }?.let { listing ->
        ListingSheet(context, listing, installed[listing.id], apps, onDismiss = { open = null })
    }
}

private fun runsLabel(entry: InstalledListing): String =
    if (entry.runs == 0) "Added, not run yet" else "Ran ${entry.runs} time${if (entry.runs == 1) "" else "s"}"

@Composable
private fun InstalledChip(count: Int, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).clickable(onClick = onClick)
            .border(.8.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = "$count installed" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("$count installed", style = MaterialTheme.typography.labelLarge)
        Icon(Icons.Rounded.ChevronRight, null, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun Glyph(glyph: String, size: Int) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size / 3).dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = .14f)),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, fontSize = (size * .45f).sp, textAlign = TextAlign.Center) }
}

@Composable
private fun FeaturedCard(listing: MarketListing, onClick: () -> Unit) {
    CycloneSimpleCard(Modifier.width(158.dp).clickable(onClick = onClick)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Glyph(listing.glyph, 64)
            Text("${listing.publisher.name}'s", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(listing.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ListingRow(listing: MarketListing, entry: InstalledListing?, reason: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Glyph(listing.glyph, 46)
        Column(Modifier.weight(1f)) {
            Text(listing.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(reason ?: listing.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        OutlinedButton(onClick = onClick, shape = RoundedCornerShape(18.dp), contentPadding = PaddingValues(horizontal = 14.dp)) {
            Text(if (entry != null) "Open" else "Add")
        }
    }
}

@Composable
private fun ConnectionRow(connection: MarketConnection, onAction: () -> Unit) {
    CycloneSimpleCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Glyph(connection.glyph, 42)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(connection.name, style = MaterialTheme.typography.titleSmall)
                    CycloneStatusPill(when (connection.state) {
                        ConnectionState.CONNECTED -> "Connected"
                        ConnectionState.NEEDS_SETUP -> "Set up"
                        ConnectionState.OFF -> "Off"
                    }, positive = connection.state == ConnectionState.CONNECTED)
                }
                Text(connection.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onAction) { Text(if (connection.state == ConnectionState.CONNECTED) "Manage" else "Set up") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListingSheet(
    context: Context,
    listing: MarketListing,
    entry: InstalledListing?,
    apps: Map<String, String>,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val values = remember(listing.id, entry) {
        mutableStateMapOf<String, String>().apply { listing.inputs.forEach { put(it.name, entry?.inputs?.get(it.name) ?: it.default) } }
    }
    var message by remember(listing.id) { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Glyph(listing.glyph, 56)
                Column(Modifier.weight(1f)) {
                    Text(listing.name, style = MaterialTheme.typography.titleLarge)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("by ${listing.publisher.name} · ${listing.category}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (listing.publisher.verified) Icon(Icons.Rounded.Verified, "Verified publisher", Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Text(listing.summary, style = MaterialTheme.typography.bodyLarge)
            Disclosure("What it does", listing.does)
            if (listing.apps.isNotEmpty()) Disclosure("Apps it uses", listing.apps.map { apps[it] ?: it })
            Disclosure("Asks you first", listing.asksFirst.ifEmpty { listOf("Nothing: it does not send, delete or pay.") })
            Text("“${listing.goal}”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listing.inputs.forEach { input ->
                if (input.kind == InputKind.CHOICE) {
                    Text(input.label, style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        input.choices.forEach { choice ->
                            FilterChip(selected = values[input.name] == choice, onClick = { values[input.name] = choice }, label = { Text(choice) })
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = values[input.name].orEmpty(), onValueChange = { values[input.name] = it.take(MarketRules.MAX_INPUT) },
                        label = { Text(input.label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = if (input.kind == InputKind.NUMBER) KeyboardType.Number else KeyboardType.Text),
                    )
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val save = {
                    message = try {
                        Marketplace.add(context, listing.id, values.toMap(), "phone"); null
                    } catch (error: MarketError) { error.message }
                }
                if (entry == null) {
                    Button(onClick = { save() }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Add") }
                } else {
                    OutlinedButton(onClick = {
                        Marketplace.remove(context, listing.id)
                        onDismiss()
                    }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Remove") }
                    Button(onClick = {
                        save()
                        if (message == null) {
                            val refusal = Marketplace.run(context, listing.id)
                            if (refusal == null) onDismiss() else message = refusal.message
                        }
                    }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Run") }
                }
            }
        }
    }
}

@Composable
private fun Disclosure(title: String, lines: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        lines.forEach { line ->
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.padding(top = 7.dp).size(5.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                Text(line, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
