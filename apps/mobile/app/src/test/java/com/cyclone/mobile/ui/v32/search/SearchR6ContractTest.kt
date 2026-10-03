package com.cyclone.mobile.ui.v32.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** R6 (docs/design/redesign/rounds/R6-calm.md): the smart search's sources, sheet and where each result opens. */
class SearchR6ContractTest {
    private fun source(relative: String): String = listOf(
        File("src/main/java/$relative"), File("app/src/main/java/$relative"), File("apps/mobile/app/src/main/java/$relative"),
    ).firstOrNull(File::isFile)?.readText() ?: error("Could not locate $relative")

    private val app get() = source("com/cyclone/mobile/ui/v32/CycloneV32App.kt")

    @Test fun everyKindHasASourceAndNoneWritesAnything() {
        val sources = source("com/cyclone/mobile/ui/v32/search/SearchSources.kt")
        listOf("addAll(SettingsIndex.items)", "addAll(safe { runs(app, now) })", "addAll(safe { routines() })",
            "addAll(safe { skills() })", "addAll(safe { apps(app) })", "addAll(safe { profiles(app) })").forEach {
            assertTrue(it, sources.contains(it))
        }
        assertTrue(sources.contains("withContext(Dispatchers.IO)"))
        listOf("getSharedPreferences", ".edit()", "writeText(", "FileOutputStream", "Log.").forEach {
            assertFalse("search sources use $it", sources.contains(it))
        }
    }

    @Test fun eachResultOpensItsOwnPlace() {
        val open = app.substringAfter("private fun openSearchResult(").substringBefore("\n}\n")
        assertTrue(open.contains("SearchCategory.SETTINGS -> onSettings(item.target)"))
        assertTrue(open.contains("V39AiChatSessionRuntime.pendingOpenRun = item.target"))
        assertTrue(open.contains("RoutinesNav.pending = item.target"))
        assertTrue(open.contains("getLaunchIntentForPackage(item.target)"))
        // Opening an app is the owner's own tap, like a launcher; the search never acts on the phone for the model.
        assertFalse(open.contains("PhoneToolExecutor"))
        assertTrue(app.contains("if (section == \"Set up Cyclone\") setupOpen = true"))
        // The page picks up what the search left for it.
        assertTrue(source("com/cyclone/mobile/ui/v32/CycloneV39AiChatPage.kt").contains("LaunchedEffect(session.pendingOpenRun) {"))
        assertTrue(source("com/cyclone/mobile/ui/v32/CycloneRoutinesPage.kt").contains("RoutinesNav.pending?.let { selected = it; RoutinesNav.pending = null }"))
    }

    @Test fun searchOpensFromHomeAndSettings() {
        assertTrue(app.contains("onSearch = { searchOpen = true }"))
        assertTrue(app.contains("com.cyclone.mobile.ui.v32.search.CycloneSearchSheet("))
        assertTrue(source("com/cyclone/mobile/ui/v32/CycloneV32Components.kt").contains("AskRoundChip(\"Search\", onSearch"))
        val sheet = source("com/cyclone/mobile/ui/v32/search/SearchSheet.kt")
        assertTrue(sheet.contains(".askGlass(28.dp, GlassTier.CHROME"))
        assertTrue(sheet.contains("SearchChip(\"All\", counts.values.sum(), only == null)"))
        assertTrue(sheet.contains("Ask Cyclone: “\$query”"))
    }

    @Test fun everySettingsEntryIsARealSection() {
        val settings = source("com/cyclone/mobile/ui/v32/CycloneSettings426.kt")
        SettingsIndex.items.map { it.target }.forEach { id ->
            val present = settings.contains("Settings426Row(\"$id\"") || (id == "Set up Cyclone" && settings.contains("SET_UP_CYCLONE = \"Set up Cyclone\"")) ||
                (id == "Visual quality" && settings.contains("VISUAL_QUALITY = \"Visual quality\""))
            assertTrue("no settings row for $id", present)
        }
    }
}
