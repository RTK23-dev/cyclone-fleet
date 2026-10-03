package com.cyclone.mobile.runtime.workspaces

/**
 * Plan 40 P1: Recently deleted, like a photos app's. A removed profile is stopped and hidden but untouched, so Restore
 * brings it back exactly as it was. After [TRASH_DAYS] (or on "Delete now") Cyclone backs it up automatically and
 * then Android removes it for good. Backups are kept [BACKUP_DAYS]. Pure, so every rule is tested without a phone.
 */
object ProfileTrash {
    const val TRASH_DAYS = 7
    const val BACKUP_DAYS = 30
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Whole days left before a removed profile is deleted for good (0 on its last day). */
    fun daysLeft(removedAtMs: Long, nowMs: Long): Int =
        ((removedAtMs + TRASH_DAYS * DAY_MS - nowMs).coerceAtLeast(0L) / DAY_MS).toInt()

    fun expired(removedAtMs: Long, nowMs: Long): Boolean = nowMs - removedAtMs >= TRASH_DAYS * DAY_MS

    fun backupExpired(createdAtMs: Long, nowMs: Long): Boolean = nowMs - createdAtMs >= BACKUP_DAYS * DAY_MS

    /** The line under a removed profile: "Deletes in 6 days", "Deletes tomorrow", "Deletes today". */
    fun line(removedAtMs: Long, nowMs: Long): String = when (val days = daysLeft(removedAtMs, nowMs)) {
        0 -> "Deletes today"
        1 -> "Deletes tomorrow"
        else -> "Deletes in $days days"
    }

    /** Oldest first, as a trash bin reads: what goes next is on top. */
    fun order(records: List<CycloneProfileRecord>): List<CycloneProfileRecord> =
        records.filter { it.inTrash }.sortedBy { it.removedAtMs }

    /**
     * Why a profile can't be removed, or null when it can. Cyclone removes only profiles it made (the `Cyclone_…` name
     * and the right parent), never the main profile or the one you're in, and never while a task runs.
     */
    fun refusal(
        record: CycloneProfileRecord,
        user: ProfileUserRecord?,
        mainUserId: Int?,
        currentUserId: Int,
        taskRunning: Boolean,
    ): String? {
        val target = record.androidUserId
        return when {
            taskRunning -> "Finish the current task first."
            // Every profile's Cyclone carries a copy of the registry; only the main profile's manages profiles.
            mainUserId == null || currentUserId != mainUserId -> "Open the main profile to remove or delete profiles."
            target == null || target <= 0 -> "This profile was never created on the phone."
            target == mainUserId || target == 0 -> "The main profile can't be removed."
            target == currentUserId -> "Switch to another profile before removing this one."
            user == null -> "Android doesn't list this profile any more."
            user.name != record.id || !ProfileRecovery.validOwned(user, record.parentUserId, record.secondaryUser) ->
                "Cyclone can only remove profiles it created."
            else -> null
        }
    }

    /**
     * What an automatic backup takes: every app in the profile plus Cyclone itself, largest first dropped until it
     * fits in [freeKb] with a margin. Cyclone's own data always goes in (it is small and it is the workhorse).
     */
    fun planBackup(sizesKb: Map<String, Long>, cyclonePackage: String, freeKb: Long): Pair<List<String>, List<String>> {
        val budget = (freeKb * 0.7).toLong()
        val take = mutableListOf<String>()
        val skip = mutableListOf<String>()
        var used = sizesKb[cyclonePackage] ?: 0L
        if (sizesKb.containsKey(cyclonePackage)) take += cyclonePackage
        sizesKb.filterKeys { it != cyclonePackage }.entries.sortedBy { it.value }.forEach { (pkg, kb) ->
            if (used + kb <= budget) { take += pkg; used += kb } else skip += pkg
        }
        return take to skip
    }
}
