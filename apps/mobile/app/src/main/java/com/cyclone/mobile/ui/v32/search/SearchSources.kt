package com.cyclone.mobile.ui.v32.search

import android.content.Context
import android.content.Intent
import com.cyclone.mobile.automation.AutomationRuntime
import com.cyclone.mobile.brain.AdaptiveBrainRuntime
import com.cyclone.mobile.mind.mission.MindMissions
import com.cyclone.mobile.runtime.workspaces.ProfileRegistryStore
import com.cyclone.mobile.ui.v32.ask.AskCopy
import com.cyclone.mobile.ui.v32.v32TriggerSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gathers what the smart search can find, on the phone, off the main thread. Every source is read in place from the
 * store that already owns it; the search keeps its list in memory while the sheet is open and writes nothing.
 * One failing source never empties the others.
 */
object SearchSources {
    suspend fun gather(context: Context): List<SearchItem> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        buildList {
            addAll(SettingsIndex.items)
            addAll(safe { runs(app, now) })
            addAll(safe { routines() })
            addAll(safe { skills() })
            addAll(safe { apps(app) })
            addAll(safe { profiles(app) })
        }
    }

    private fun runs(context: Context, now: Long): List<SearchItem> {
        MindMissions.refresh(context)
        return MindMissions.history.value.take(80).map { mission ->
            SearchItem(
                category = SearchCategory.RUNS,
                target = mission.id,
                title = mission.goal.trim().ifEmpty { "Untitled run" },
                subtitle = AskCopy.runLine(mission.status.name, mission.updatedAtMs, now),
                // The summary helps a search find the run ("the one that booked the table"); it stays in memory.
                keywords = listOfNotNull(mission.summary.take(160).takeIf(String::isNotBlank)),
                recency = mission.updatedAtMs,
            )
        }
    }

    private fun routines(): List<SearchItem> = AutomationRuntime.store.listAutomations().map { routine ->
        SearchItem(
            category = SearchCategory.ROUTINES,
            target = routine.id,
            title = routine.name,
            subtitle = routine.v32TriggerSummary() + if (routine.enabled) "" else " · Off",
            keywords = routine.steps.map { it.name },
            packageName = routine.appPackages.firstOrNull(),
        )
    }

    private fun skills(): List<SearchItem> = AdaptiveBrainRuntime.store.listMicroSkills(160)
        .filter { it.successCount > 0 }
        .map { skill ->
            SearchItem(
                category = SearchCategory.SKILLS,
                target = skill.signature,
                title = skill.name,
                subtitle = "${skill.successCount} successful ${if (skill.successCount == 1) "use" else "uses"}",
                keywords = skill.goalHints.split(',', '\n').map(String::trim).filter(String::isNotEmpty).take(8),
                packageName = skill.fromPackage,
            )
        }

    /** The apps on the home screen, marked when Cyclone has already learned its way around them. */
    private fun apps(context: Context): List<SearchItem> {
        val learned = runCatching { AdaptiveBrainRuntime.store.listApps() }.getOrDefault(emptyList())
            .filter { it.openSuccessCount > 0 }.map { it.packageName }.toSet()
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { (pkg, _) -> pkg != context.packageName }
            .distinctBy { it.first }
            .map { (pkg, label) ->
                SearchItem(
                    category = SearchCategory.APPS,
                    target = pkg,
                    title = label,
                    subtitle = if (pkg in learned) "Cyclone knows this app" else "App",
                    keywords = listOf(pkg.substringAfterLast('.')),
                    packageName = pkg,
                )
            }
    }

    private fun profiles(context: Context): List<SearchItem> = ProfileRegistryStore.records(context).filterNot { it.inTrash }.map { record ->
        SearchItem(
            category = SearchCategory.PROFILES,
            target = record.id,
            title = record.label,
            subtitle = "${record.packages.size} ${if (record.packages.size == 1) "app" else "apps"}" + if (record.ready) "" else " · Setting up",
        )
    }

    private inline fun safe(block: () -> List<SearchItem>): List<SearchItem> = runCatching(block).getOrDefault(emptyList())
}
