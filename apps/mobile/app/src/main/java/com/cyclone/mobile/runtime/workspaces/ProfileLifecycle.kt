package com.cyclone.mobile.runtime.workspaces

import android.content.Context
import android.os.StatFs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One automatic backup of a profile, taken just before Android removed it for good. */
data class ProfileBackup(
    val folder: String,
    val profileId: String,
    val label: String,
    val createdAtMs: Long,
    val packages: List<String>,
    val skipped: List<String>,
    val bytes: Long,
)

/**
 * Plan 40 P1: remove, restore and delete profiles like photos in a photos app.
 *
 * - [remove] stops the profile and moves it to Recently deleted. Nothing is deleted, so [ProfileRegistryStore.restore]
 *   brings it back exactly as it was.
 * - [deleteNow] (from Recently deleted, or by itself after [ProfileTrash.TRASH_DAYS]) first backs the profile up
 *   automatically (its apps' data and Cyclone's own, as much as fits), then asks Android to remove it.
 *
 * Trusted local UI actions only, like switching profiles: no model tool, no gateway call, and never while a task runs.
 */
object ProfileLifecycle {
    private const val PKG = "com.cyclone.mobile"

    /** Stops the profile and moves it to Recently deleted. */
    fun remove(context: Context, id: String) = synchronized(Layer2Workspaces.engine.mutationLock) {
        val record = guarded(context, id)
        ProfileSetupRuntime.runBestEffort(ProfileSetupPlan.stopUser(record.androidUserId!!))
        ProfileRegistryStore.markRemoved(context, id, System.currentTimeMillis())
    }

    /** Backs the profile up, then removes it from the phone for good. Only from Recently deleted. */
    fun deleteNow(context: Context, id: String, onProgress: (String) -> Unit = {}): ProfileBackup =
        synchronized(Layer2Workspaces.engine.mutationLock) {
            val record = guarded(context, id)
            check(record.inTrash) { "Remove the profile first; it goes to Recently deleted before it can be deleted." }
            val user = record.androidUserId!!
            onProgress("Backing up ${record.label}…")
            // The backup comes first and must succeed: if it can't be made, nothing is deleted (it stays in the trash).
            val backup = runCatching { backup(context, record, onProgress) }
                .getOrElse { error("Couldn't back up ${record.label}, so nothing was deleted. Free some space and try again.") }
            onProgress("Deleting ${record.label}…")
            ProfileSetupRuntime.runRequired(ProfileSetupPlan.removeUser(user))
            check(ProfileSetupRuntime.listUsersRequired().none { it.id == user }) { "Android didn't remove the profile. Nothing was forgotten." }
            Layer2Workspaces.engine.forgetUser(user)
            ProfileRegistryStore.drop(context, id)
            backup
        }

    /** Empties Recently deleted (each profile is backed up first). Returns how many were deleted. */
    fun emptyTrash(context: Context, onProgress: (String) -> Unit = {}): Int =
        ProfileTrash.order(ProfileRegistryStore.records(context)).count { record ->
            runCatching { deleteNow(context, record.id, onProgress) }.isSuccess
        }

    /**
     * Runs when Cyclone opens: profiles past their 7 days are deleted (with their automatic backup), and backups past
     * their 30 days are cleared. Cheap when there is nothing to do: no root call at all.
     */
    fun tidy(context: Context, nowMs: Long = System.currentTimeMillis()) {
        ProfileTrash.order(ProfileRegistryStore.records(context))
            .filter { ProfileTrash.expired(it.removedAtMs!!, nowMs) }
            .forEach { runCatching { deleteNow(context, it.id) } }
        ProfileBackups.list(context).filter { ProfileTrash.backupExpired(it.createdAtMs, nowMs) }
            .forEach { runCatching { ProfileBackups.delete(context, it.folder) } }
    }

    private fun guarded(context: Context, id: String): CycloneProfileRecord {
        val record = ProfileRegistryStore.records(context).singleOrNull { it.id == id } ?: error("Profile not found.")
        ProfileSetupRuntime.runRequired(ProfileSetupPlan.verifyRoot())
        val users = ProfileSetupRuntime.listUsersRequired()
        val taskRunning = Layer2Workspaces.gated() ||
            com.cyclone.mobile.ui.overlay.OverlayChromeRuntime.hasExecutingTask() ||
            com.cyclone.mobile.runtime.background.WorkspaceTasks.hasCurrentTask()
        val current = ProfileSetupParser.currentUserId(ProfileSetupRuntime.runRequired(ProfileSetupPlan.currentUser()))
            ?: error("Android did not identify the current profile.")
        ProfileTrash.refusal(record, users.singleOrNull { it.id == record.androidUserId }, ProfileSetupParser.mainUserId(users),
            current, taskRunning)?.let { error(it) }
        return record
    }

    /**
     * The automatic backup: each app's data (credential and device storage) as tar files in Cyclone's own files,
     * largest apps left out when the phone lacks space. Returns what was saved.
     */
    private fun backup(context: Context, record: CycloneProfileRecord, onProgress: (String) -> Unit): ProfileBackup {
        val user = record.androidUserId!!
        val now = System.currentTimeMillis()
        val dir = File(context.filesDir, "profile-backups/${record.id}-$now")
        check(dir.mkdirs() || dir.isDirectory) { "Couldn't make the backup folder." }
        val path = dir.absolutePath
        check(ProfileSetupPlan.validBackupDir(path)) { "Unexpected backup location." }
        val candidates = (record.packages + PKG).filter(ProfileSetupPlan::validPackageName).toSortedSet()
        val sizes = candidates.associateWith { pkg ->
            ProfileSetupRuntime.runBestEffort(ProfileSetupPlan.measureAppData(user, pkg))
                ?.trim()?.substringBefore('\t')?.substringBefore(' ')?.toLongOrNull()
        }.filterValues { it != null }.mapValues { it.value!! }
        val freeKb = StatFs(context.filesDir.absolutePath).availableBytes / 1024
        val (take, skip) = ProfileTrash.planBackup(sizes, PKG, freeKb)
        val saved = take.filter { pkg ->
            onProgress("Backing up ${record.label}: $pkg…")
            val ce = ProfileSetupRuntime.runBestEffort(ProfileSetupPlan.backupAppData(user, pkg, deviceStorage = false, backupDir = path)) != null
            ProfileSetupRuntime.runBestEffort(ProfileSetupPlan.backupAppData(user, pkg, deviceStorage = true, backupDir = path))
            ce
        }
        ProfileSetupRuntime.runBestEffort(ProfileSetupPlan.ownBackup(path, context.applicationInfo.uid))
        ProfileSetupRuntime.runBestEffort(ProfileSetupPlan.labelBackup(path))
        val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val entry = ProfileBackup(dir.name, record.id, record.label, now, saved, skip + (take - saved.toSet()), bytes)
        ProfileBackups.add(context, entry)
        return entry
    }
}

/** The list of automatic backups, in Cyclone's own files. Names and sizes only; the archives stay on the phone. */
object ProfileBackups {
    private fun root(context: Context) = File(context.filesDir, "profile-backups")
    private fun index(context: Context) = File(root(context), "index.json")

    @Synchronized fun list(context: Context): List<ProfileBackup> = runCatching {
        val array = JSONArray(index(context).readText())
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            fun strings(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty()
            ProfileBackup(o.getString("folder"), o.getString("profile"), o.getString("label"), o.getLong("created"),
                strings("packages"), strings("skipped"), o.optLong("bytes"))
        }.sortedByDescending { it.createdAtMs }
    }.getOrDefault(emptyList())

    @Synchronized internal fun add(context: Context, backup: ProfileBackup) = save(context, list(context).filterNot { it.folder == backup.folder } + backup)

    @Synchronized fun delete(context: Context, folder: String) {
        require(Regex("Cyclone_[a-f0-9]{16}-[0-9]{10,14}").matches(folder))
        File(root(context), folder).deleteRecursively()
        save(context, list(context).filterNot { it.folder == folder })
    }

    private fun save(context: Context, backups: List<ProfileBackup>) {
        root(context).mkdirs()
        val array = JSONArray()
        backups.forEach { b ->
            array.put(JSONObject().put("folder", b.folder).put("profile", b.profileId).put("label", b.label)
                .put("created", b.createdAtMs).put("packages", JSONArray(b.packages)).put("skipped", JSONArray(b.skipped))
                .put("bytes", b.bytes))
        }
        val tmp = File(root(context), "index.json.tmp")
        tmp.writeText(array.toString())
        check(tmp.renameTo(index(context))) { "Couldn't save the backup list." }
    }
}
