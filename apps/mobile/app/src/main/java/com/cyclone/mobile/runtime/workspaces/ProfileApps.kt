package com.cyclone.mobile.runtime.workspaces

import android.content.Context
import android.content.pm.PackageManager

/**
 * Plan 43 (T4): the phone's profiles for the PC. Glass lists them under the phone, switches the phone between them, and
 * manages each Cyclone profile's apps (add an app Profile A already has, remove one from that profile only).
 *
 * Only profiles Cyclone created are managed: every call re-reads Android's user list and checks the profile is the
 * exact, owned one from Cyclone's registry (`ProfileRecovery.validOwned`). Profile A is listed and can be switched to,
 * but its own apps are the owner's to manage on the phone. Every root command is a fixed verb from [ProfileSetupPlan].
 */
object ProfileApps {
    const val MAIN = "main"

    data class Profile(
        val id: String, val label: String, val emoji: String?, val color: Long?, val androidUserId: Int?, val ready: Boolean,
        val current: Boolean, val inTrash: Boolean,
    )

    data class App(val packageName: String, val label: String)

    class Refused(val code: String, message: String) : IllegalStateException(message)

    /** Seams for JVM tests. */
    internal var run: (ProfileSetupCommand) -> String = { ProfileSetupRuntime.runRequired(it) }
    internal var users: () -> List<ProfileUserRecord> = { ProfileSetupRuntime.listUsersRequired() }
    internal var records: (Context) -> List<CycloneProfileRecord> = { ProfileRegistryStore.records(it) }
    internal var prepare: (Context, Int, String) -> Unit = { c, target, id -> ProfileBootstrapRuntime.prepare(c, target, id) }
    internal var gated: () -> Boolean = { Layer2Workspaces.gated() }
    internal var busy: () -> Boolean = { com.cyclone.mobile.mind.mission.MindMissions.live.value != null }
    internal var sleep: (Long) -> Unit = { Thread.sleep(it) }
    internal var label: (Context, String) -> String = { c, pkg ->
        runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)).toString() }
            .getOrDefault(pkg)
    }

    fun current(): Int? = ProfileSetupParser.currentUserId(run(ProfileSetupPlan.currentUser()))

    fun profiles(context: Context): List<Profile> {
        val list = users()
        val main = ProfileSetupParser.mainUserId(list)
        val now = current()
        val owned = records(context).map { r ->
            val user = r.androidUserId?.let { id -> list.singleOrNull { it.id == id && it.name == r.id } }
            val valid = user != null && ProfileRecovery.validOwned(user, r.parentUserId, r.secondaryUser)
            Profile(r.id, r.label, r.emoji, r.color, r.androidUserId.takeIf { valid }, r.ready && valid && !r.inTrash,
                current = valid && now == r.androidUserId, inTrash = r.inTrash)
        }
        return listOf(Profile(MAIN, CarryRules.MAIN_LABEL, null, null, main, main != null, current = now != null && now == main, inTrash = false)) + owned
    }

    /** The apps a profile has, and (for a Cyclone profile) the ones Profile A has that it could get. */
    fun apps(context: Context, profileId: String): Pair<List<App>, List<App>> {
        val target = resolve(context, profileId)
        val installed = ProfileSetupParser.packages(run(ProfileSetupPlan.listProfileApps(target.user)))
            .filter { it != context.packageName }.sorted()
        val available = if (profileId == MAIN) emptyList() else {
            val main = target.main ?: return installed.map { App(it, label(context, it)) } to emptyList()
            ProfileSetupParser.packages(run(ProfileSetupPlan.listProfileApps(main))).filter { it !in installed && it != context.packageName }.sorted()
        }
        return installed.map { App(it, label(context, it)) } to available.map { App(it, label(context, it)) }
    }

    /** Puts an app Profile A already has into a Cyclone profile (Android's install-existing: no download, no store). */
    fun install(context: Context, profileId: String, packageName: String) {
        val target = owned(context, profileId)
        requirePackage(packageName)
        val main = target.main ?: throw Refused("CAPABILITY_UNAVAILABLE", "Android did not identify Profile A.")
        if (packageName !in ProfileSetupParser.packages(run(ProfileSetupPlan.listProfileApps(main)))) {
            throw Refused("CAPABILITY_UNAVAILABLE", "Profile A doesn't have $packageName. Install it there first.")
        }
        run(ProfileSetupPlan.installExisting(target.user, packageName))
        if (packageName !in ProfileSetupParser.packages(run(ProfileSetupPlan.listProfileApps(target.user)))) {
            throw Refused("CAPABILITY_UNAVAILABLE", "Android did not add $packageName to the profile.")
        }
    }

    /** Removes an app from one Cyclone profile; it stays in Profile A and every other profile. */
    fun remove(context: Context, profileId: String, packageName: String) {
        val target = owned(context, profileId)
        requirePackage(packageName)
        run(ProfileSetupPlan.uninstallForProfile(target.user, packageName))
    }

    /**
     * Switches the phone to a profile, the way the phone's own Profiles page does (prepare Cyclone there, ask Android,
     * wait for it to confirm), but from the PC: it works whichever profile is in front now.
     */
    fun switchTo(context: Context, profileId: String): Int {
        if (busy()) throw Refused("ASK_BUSY", "Cyclone is running a task on the phone. Switch when it is done.")
        if (gated()) throw Refused("HUMAN_HAS_CONTROL", "A request on the phone is waiting for your approval. Answer it first.")
        val target = resolve(context, profileId)
        if (current() == target.user) return target.user
        // Memory and skills travel only with a switch made on the phone itself (Cyclone Carry); from the PC the profile
        // gets Cyclone ready there and nothing else.
        if (profileId != MAIN) prepare(context, target.user, profileId)
        run(ProfileSetupPlan.switchUser(target.user))
        repeat(40) {
            if (current() == target.user) return target.user
            sleep(150)
        }
        throw Refused("DEVICE_NOT_READY", "Android hasn't finished switching profiles yet. Check the phone.")
    }

    private data class Target(val user: Int, val main: Int?)

    private fun resolve(context: Context, profileId: String): Target {
        val list = users()
        val main = ProfileSetupParser.mainUserId(list)
        if (profileId == MAIN) return Target(main ?: throw Refused("CAPABILITY_UNAVAILABLE", "Android did not identify Profile A."), main)
        return owned(context, profileId, list)
    }

    private fun owned(context: Context, profileId: String, list: List<ProfileUserRecord> = users()): Target {
        if (!ProfileSetupPlan.validProfileName(profileId)) throw Refused("INVALID_REQUEST", "Name a Cyclone profile, or main for Profile A.")
        val record = records(context).singleOrNull { it.id == profileId } ?: throw Refused("RUN_NOT_FOUND", "No such profile on this phone.")
        if (record.inTrash) throw Refused("CAPABILITY_UNAVAILABLE", "${record.label} is in Recently deleted. Restore it on the phone first.")
        val userId = record.androidUserId ?: throw Refused("CAPABILITY_UNAVAILABLE", "${record.label} still needs setup on the phone.")
        val user = list.singleOrNull { it.id == userId && it.name == record.id }
        if (user == null || !ProfileRecovery.validOwned(user, record.parentUserId, record.secondaryUser)) {
            throw Refused("CAPABILITY_UNAVAILABLE", "${record.label} needs repair on the phone.")
        }
        if (!record.ready) throw Refused("CAPABILITY_UNAVAILABLE", "${record.label} still needs setup on the phone.")
        return Target(userId, ProfileSetupParser.mainUserId(list))
    }

    private fun requirePackage(packageName: String) {
        if (!ProfileSetupPlan.validPackageName(packageName) || packageName == "com.cyclone.mobile") {
            throw Refused("INVALID_REQUEST", "Name an app by its package, not Cyclone itself.")
        }
    }
}
