package com.cyclone.mobile.ui.v32.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.withStyle
import com.cyclone.mobile.ui.v32.CycloneAppIcon
import com.cyclone.mobile.ui.v32.ask.AskDim
import com.cyclone.mobile.ui.v32.ask.AskGlass
import com.cyclone.mobile.ui.v32.ask.GlassTier
import com.cyclone.mobile.ui.v32.ask.askGlass
import com.kyant.capsule.ContinuousCapsule
import com.kyant.capsule.ContinuousRoundedRectangle

/**
 * R6 smart search: one sheet over any page, in the model selector's style. Type, and results come grouped by kind
 * (Settings, Recent runs, Routines, Skills, Apps, Profiles); the chips narrow to one kind and show how many each has.
 * An empty field shows recent runs and the settings people open most. With no match, the query can go to Cyclone.
 */
@Composable
fun CycloneSearchSheet(
    onResult: (SearchItem) -> Unit,
    onAsk: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var items by remember { mutableStateOf(SettingsIndex.items) }
    LaunchedEffect(Unit) { items = SearchSources.gather(context) }
    var query by remember { mutableStateOf("") }
    var only by remember { mutableStateOf<SearchCategory?>(null) }
    val groups = remember(query, only, items) { CycloneSearch.search(query, items, only) }
    val counts = remember(query, items) { CycloneSearch.counts(query, items) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    androidx.activity.compose.BackHandler { onDismiss() }

    Box(Modifier.fillMaxSize()) {
        AskDim(onDismiss)
        Column(
            Modifier.align(Alignment.TopCenter).statusBarsPadding().imePadding()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .fillMaxWidth()
                .askGlass(28.dp, GlassTier.CHROME, 0.2f, smoke = AskGlass.SHEET_SMOKE + 0.14f)
                .clip(ContinuousRoundedRectangle(28.dp))
                .semantics { contentDescription = "Search Cyclone" }
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // The field: a darker pill inside the sheet, like the Ask bar's words.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = 48.dp)
                    .clip(ContinuousCapsule).background(AskGlass.Veil).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Rounded.Search, null, Modifier.size(20.dp), tint = AskGlass.Muted)
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Search settings, runs, routines, apps…", color = AskGlass.Faint, fontSize = 16.sp, maxLines = 1)
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it.take(80) },
                        singleLine = true,
                        textStyle = TextStyle(color = AskGlass.Ink, fontSize = 16.sp),
                        cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            groups.firstOrNull()?.hits?.firstOrNull()?.let { onResult(it.item) } ?: if (query.isNotBlank()) onAsk(query.trim()) else Unit
                        }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Search" },
                    )
                }
                if (query.isNotEmpty()) {
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Clear") { query = "" },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Close, "Clear", Modifier.size(18.dp), tint = AskGlass.Muted) }
                }
            }

            if (query.isNotBlank() && counts.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SearchChip("All", counts.values.sum(), only == null) { only = null }
                    SearchCategory.entries.filter { (counts[it] ?: 0) > 0 }.forEach { category ->
                        SearchChip(category.label, counts[category] ?: 0, only == category) { only = if (only == category) null else category }
                    }
                }
            }

            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    query.isBlank() -> {
                        val recent = items.filter { it.category == SearchCategory.RUNS }.sortedByDescending { it.recency }.take(3)
                        if (recent.isNotEmpty()) {
                            item { SearchLabel("Recent runs") }
                            items(recent, key = { "recent-${it.target}" }) { SearchRow(it, "", onResult) }
                        }
                        item { SearchLabel("Settings") }
                        items(SettingsIndex.items.filter { it.target in SUGGESTED }, key = { "suggest-${it.target}" }) { SearchRow(it, "", onResult) }
                    }
                    groups.isEmpty() -> item {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Nothing in Cyclone matches “${query.trim()}”.", color = AskGlass.Muted, fontSize = 14.sp)
                            AskRow(query.trim(), onAsk)
                        }
                    }
                    else -> {
                        groups.forEach { group ->
                            item(key = "label-${group.category}") {
                                SearchLabel(group.category.label, if (group.more > 0) "All ${group.hits.size + group.more}" else null) { only = group.category }
                            }
                            items(group.hits, key = { "${group.category}-${it.item.target}" }) { SearchRow(it.item, query, onResult) }
                        }
                        item(key = "ask") { Box(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) { AskRow(query.trim(), onAsk) } }
                    }
                }
            }
        }
    }
}

/** The settings people open most, shown before anything is typed. */
private val SUGGESTED = setOf("Model & API", "Visual quality", "Phone control", "Driver mode")

@Composable
private fun SearchChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 36.dp).clip(ContinuousCapsule)
            .then(if (selected) Modifier.background(Color.White.copy(alpha = 0.22f)).border(0.7.dp, Color.White.copy(alpha = 0.4f), ContinuousCapsule)
                else Modifier.background(AskGlass.Veil))
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { stateDescription = if (selected) "Selected" else "Not selected" }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, color = if (selected) AskGlass.Ink else AskGlass.Muted, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        Text("$count", color = AskGlass.Faint, fontSize = 12.5.sp)
    }
}

@Composable
private fun SearchLabel(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), color = AskGlass.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp)
        if (action != null) {
            Text(action, Modifier.clip(ContinuousCapsule).clickable(role = Role.Button, onClick = onAction).padding(horizontal = 8.dp, vertical = 6.dp),
                color = AskGlass.Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SearchRow(item: SearchItem, query: String, onResult: (SearchItem) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(ContinuousRoundedRectangle(16.dp))
            .clickable(role = Role.Button, onClick = { onResult(item) })
            .semantics { contentDescription = "${item.category.label}: ${item.title}. ${item.subtitle}" }
            .heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(38.dp).clip(ContinuousRoundedRectangle(11.dp)).background(tileColor(item.category)), contentAlignment = Alignment.Center) {
            if (item.category == SearchCategory.APPS || (item.category == SearchCategory.SKILLS && item.packageName != null)) {
                CycloneAppIcon(item.packageName, Modifier.size(26.dp))
            } else {
                Icon(icon(item.category), null, Modifier.size(20.dp), tint = Color.White)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(highlight(item.title, query), color = AskGlass.Ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.subtitle.isNotBlank()) Text(item.subtitle, color = AskGlass.Muted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = AskGlass.Faint)
    }
}

@Composable
private fun AskRow(query: String, onAsk: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(ContinuousCapsule).background(Color.White.copy(alpha = 0.12f))
            .clickable(role = Role.Button, onClick = { onAsk(query) }).heightIn(min = 48.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp), tint = AskGlass.Ink)
        Text("Ask Cyclone: “$query”", color = AskGlass.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The part of the title that matched, in semibold. */
private fun highlight(title: String, query: String): AnnotatedString {
    val q = query.trim()
    val at = if (q.isEmpty()) -1 else title.indexOf(q, ignoreCase = true)
    return buildAnnotatedString {
        if (at < 0) {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(title) }
        } else {
            append(title.substring(0, at))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(title.substring(at, at + q.length)) }
            append(title.substring(at + q.length))
        }
    }
}

private fun icon(category: SearchCategory): ImageVector = when (category) {
    SearchCategory.SETTINGS -> Icons.Rounded.Settings
    SearchCategory.RUNS -> Icons.Rounded.History
    SearchCategory.ROUTINES -> Icons.Rounded.Bolt
    SearchCategory.SKILLS -> Icons.Rounded.AutoAwesome
    SearchCategory.APPS -> Icons.Rounded.Apps
    SearchCategory.PROFILES -> Icons.Rounded.Person
}

/** Each kind has its own tile colour, so a result's kind is seen before it is read (as Settings' groups are). */
internal fun tileColor(category: SearchCategory): Color = when (category) {
    SearchCategory.SETTINGS -> Color(0xFF8E8E93)
    SearchCategory.RUNS -> Color(0xFF3E7BFA)
    SearchCategory.ROUTINES -> Color(0xFFFF9F0A)
    SearchCategory.SKILLS -> Color(0xFF7B61FF)
    SearchCategory.APPS -> Color.White.copy(alpha = 0.14f)
    SearchCategory.PROFILES -> Color(0xFF32ADE6)
}
