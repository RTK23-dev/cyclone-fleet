package com.cyclone.mobile.runtime.workspaces

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Base64
import com.cyclone.mobile.ai.OpenRouterSecretStore
import org.json.JSONObject
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher

/** Trusted, user-initiated profile preparation. No shell API is exported to an AI or IPC client. */
internal object ProfileBootstrapRuntime {
    private const val PKG = ProfileBootstrapContract.PACKAGE
    private const val SERVICE = "$PKG/.runtime.workspaces.ProfileBootstrapService"
    private const val SHIZUKU_PERMISSION = "moe.shizuku.manager.permission.API_V23"
    private const val MAX_OUTPUT = 262144
    const val CARRY_ACTION = "com.cyclone.PROFILE_CARRY"

    fun prepare(context: Context, target: Int, profile: String) = prepareInternal(context, target, profile, false)

    /**
     * Plan 40 P2: brings what this profile's Cyclone knows (memory, skills, settings) into [target]'s, in any
     * direction, the main profile included. The bundle is sealed for [target]'s Keystore key and bound to this switch;
     * [target]'s Cyclone takes it in and answers with the nonce. Callers treat a failure as "nothing was carried".
     */
    fun carry(context: Context, target: Int): CarryReport {
        require(context.packageName == PKG)
        val source = ProfileSetupRuntime.currentUserId()
        require(target >= 0 && target != source)
        check(run("/system/bin/am", "get-started-user-state", "$target").contains("RUNNING_UNLOCKED")) { "That profile is locked." }
        val targetUid = packageUid(target, PKG)
        val folder = "/data/user_de/$target/$PKG/files"
        run("/system/bin/rm", "-f", "$folder/profile-bootstrap-public.txt", "$folder/carry-inbox.json", "$folder/carry-result.json")
        run("/system/bin/am", "start-foreground-service", "--user", "$target", "-n", SERVICE)
        val publicKey = poll { runCatching { run("/system/bin/cat", "$folder/profile-bootstrap-public.txt").trim().takeIf { it.isNotBlank() } }.getOrNull() }
        val nonce = UUID.randomUUID().toString()
        val plain = ProfileCarry.pack(context).toString().toByteArray(Charsets.UTF_8)
        val sealed = try { ProfileTransferCipher.seal(publicKey, plain, CarryRules.cipherContext(target, nonce)) } finally { plain.fill(0) }
        val envelope = JSONObject().put("source", source).put("target", target).put("nonce", nonce).put("bundle", sealed)
        writePrivate("$folder/carry-inbox.json", targetUid, envelope.toString())
        run("/system/bin/am", "start-foreground-service", "--user", "$target", "-n", SERVICE, "-a", CARRY_ACTION)
        return poll(tries = 150) {
            val ack = runCatching { JSONObject(run("/system/bin/cat", "$folder/carry-result.json")) }.getOrNull()
            if (ack?.optString("nonce") == nonce && ack.optInt("user", -1) == target) {
                check(ack.optBoolean("ok")) { "The other profile's Cyclone couldn't take in what was carried." }
                CarryReport.fromJson(ack.getJSONObject("report"))
            } else null
        }
    }

    fun requestRepair(context: Context) {
        val target = ProfileSetupRuntime.currentUserId()
        require(target > 0)
        val owner = ProfileSetupRuntime.profileAUserId()
        check(owner != target) { "You are already in the main profile." }
        run("/system/bin/am", "start-user", "-w", "$owner")
        run("/system/bin/am", "start-foreground-service", "--user", "$owner", "-n", SERVICE,
            "-a", "com.cyclone.PROFILE_REPAIR", "--ei", "target", "$target")
    }

    fun reportRepairFailure(target: Int) {
        if (target > 0 && run("/system/bin/am", "get-current-user").trim() == target.toString()) {
            run("/system/bin/am", "start", "--user", "$target", "-n", "$PKG/.ui.ProfileRescueActivity", "--ez", "repair_failed", "true")
        }
    }

    fun repairFromOwner(context: Context, target: Int) {
        check(!com.cyclone.mobile.runtime.background.WorkspaceTasks.hasCurrentTask() &&
            !com.cyclone.mobile.ui.overlay.OverlayChromeRuntime.hasExecutingTask() && !Layer2Workspaces.gated()) { "Finish the current task first." }
        val source = ProfileSetupRuntime.currentUserId()
        val record = ProfileRegistryStore.records(context).single { it.androidUserId == target && it.parentUserId == source }
        val users = ProfileSetupParser.users(run("/system/bin/cmd", "user", "list", "--all", "--verbose"))
        check(users.any { it.id == target && it.name == record.id && ProfileRecovery.validOwned(it, source, true) })
        synchronized(Layer2Workspaces.engine.mutationLock) { prepareInternal(context, target, record.id, true) }
        run("/system/bin/am", "start", "--user", "$target", "-n", "$PKG/.MainActivity")
    }

    private fun prepareInternal(context: Context, target: Int, profile: String, repairingActiveTarget: Boolean) {
        require(context.packageName == PKG && ProfileSetupPlan.validProfileName(profile))
        val source = ProfileSetupRuntime.currentUserId()
        require(target > 0 && target != source)
        check(run("/system/bin/am", "get-current-user").trim() == (if (repairingActiveTarget) target else source).toString()) { "Open Cyclone in your active phone profile before preparing another." }
        val installedSupport = ProfileRequiredPackages.supportAllowlist.filter { pkg ->
            runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.isSuccess
        }.toSet()
        (installedSupport + PKG).forEach { pkg ->
            run("/system/bin/cmd", "package", "install-existing", "--user", "$target", pkg)
            check(ProfileSetupParser.packages(run("/system/bin/pm", "list", "packages", "--user", "$target", pkg)).contains(pkg)) {
                "A required app wasn't installed in this profile."
            }
            run("/system/bin/pm", "enable", "--user", "$target", pkg)
        }
        run("/system/bin/am", "start-user", "-w", "$target")
        check(run("/system/bin/am", "get-started-user-state", "$target").contains("RUNNING_UNLOCKED")) {
            "Unlock this profile once so Android can open its protected settings."
        }
        val targetUid = packageUid(target, PKG)
        val sourceUid = context.applicationInfo.uid
        prepareMagisk(source, target, sourceUid, targetUid, installedSupport)

        val folder = "/data/user_de/$target/$PKG/files"
        // Force-stop only the destination Cyclone process before import, never another app.
        run("/system/bin/am", "force-stop", "--user", "$target", PKG)
        run("/system/bin/rm", "-f", "$folder/profile-bootstrap-public.txt", "$folder/profile-bootstrap-result.json", "$folder/profile-bootstrap.json")
        mirrorGrants(context, target)
        run("/system/bin/am", "start-foreground-service", "--user", "$target", "-n", SERVICE)
        val publicKey = poll { runCatching { run("/system/bin/cat", "$folder/profile-bootstrap-public.txt").trim().takeIf { it.isNotBlank() } }.getOrNull() }
        val nonce = UUID.randomUUID().toString()
        val preferences = context.getSharedPreferences("cyclone_ai", Context.MODE_PRIVATE).all
        val ai = JSONObject()
        ProfileBootstrapContract.portablePreferences(preferences).forEach { (key, value) -> ai.put(key, value) }
        val payload = JSONObject().put("source", source).put("target", target).put("profile", profile).put("nonce", nonce)
            .put("ai", ai).put("profiles", context.getSharedPreferences("cyclone_profile_registry", Context.MODE_PRIVATE).getString("profiles", "[]"))
        payload.put("permissions", org.json.JSONArray((ProfileBootstrapContract.permissions + SHIZUKU_PERMISSION)
            .filter { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }))
        payload.put("overlay", Settings.canDrawOverlays(context))
        payload.put("accessibility", containsCyclone(Settings.Secure.getString(context.contentResolver, "enabled_accessibility_services").orEmpty(), "CycloneAccessibilityService"))
        payload.put("listener", containsCyclone(Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty(), "CycloneNotificationListener"))
        val apiKey = OpenRouterSecretStore.read(context)
        if (apiKey.isNotBlank()) {
            val bytes = apiKey.toByteArray(Charsets.UTF_8)
            try { payload.put("key", ProfileTransferCipher.encrypt(publicKey, bytes)) } finally { bytes.fill(0) }
        }
        writePrivate("$folder/profile-bootstrap.json", targetUid, payload.toString())
        run("/system/bin/am", "start-foreground-service", "--user", "$target", "-n", SERVICE)
        poll {
            val ack = runCatching { JSONObject(run("/system/bin/cat", "$folder/profile-bootstrap-result.json")) }.getOrNull()
            if (ack?.optString("nonce") == nonce && ack.optInt("user") == target && ack.optBoolean("ok")) "ready" else null
        }
        // Prove destination root policy, not just the database write. Nested su originates as its UID.
        check(run("su", "$targetUid", "-c", "su -c /system/bin/id").contains("uid=0")) {
            "This profile cannot return through Cyclone yet. The switch was cancelled."
        }
    }

    private fun prepareMagisk(source: Int, target: Int, sourceUid: Int, targetUid: Int, support: Set<String>) {
        check(runCatching { run("magisk", "-V").trim().toInt() >= 26000 }.getOrDefault(false)) {
            "Automatic profile root setup currently requires Magisk 26 or newer. The switch was cancelled."
        }
        val allowed = "SELECT policy FROM policies WHERE uid=$sourceUid AND policy=2 AND (until=0 OR until>strftime('%s','now'));"
        check(run("magisk", "--sqlite", allowed).contains("policy=2")) { "Cyclone needs a saved root grant in this profile before sharing that access." }
        val mode = run("magisk", "--sqlite", "SELECT value FROM settings WHERE key='multiuser_mode';")
        if (!mode.contains("value=1") && !mode.contains("value=2")) {
            run("magisk", "--sqlite", "INSERT OR REPLACE INTO settings (key,value) VALUES ('multiuser_mode',2);")
        }
        fun inherit(uid: Int, destination: Int) {
            run("magisk", "--sqlite", "INSERT OR REPLACE INTO policies (uid,policy,until,logging,notification) SELECT $destination,policy,until,logging,notification FROM policies WHERE uid=$uid AND policy=2 AND (until=0 OR until>strftime('%s','now'));")
        }
        inherit(sourceUid, targetUid)
        // Only installed allowlisted support apps with an existing owner grant inherit it.
        support.forEach { pkg -> inherit(packageUid(source, pkg), packageUid(target, pkg)) }
    }

    private fun mirrorGrants(context: Context, target: Int) {
        (ProfileBootstrapContract.permissions + SHIZUKU_PERMISSION).forEach { permission ->
            if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                run("/system/bin/pm", "grant", "--user", "$target", PKG, permission)
            }
        }
        if (Settings.canDrawOverlays(context)) run("/system/bin/cmd", "appops", "set", "--user", "$target", PKG, "SYSTEM_ALERT_WINDOW", "allow")
        if (context.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms())
            run("/system/bin/cmd", "appops", "set", "--user", "$target", PKG, "SCHEDULE_EXACT_ALARM", "allow")
        if (context.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(PKG))
            run("/system/bin/cmd", "deviceidle", "whitelist", "+$PKG")
        if (context.getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT))
            run("/system/bin/cmd", "role", "add-role-holder", "--user", "$target", android.app.role.RoleManager.ROLE_ASSISTANT, PKG)
        val ownAccessibility = Settings.Secure.getString(context.contentResolver, "enabled_accessibility_services").orEmpty()
        if (containsCyclone(ownAccessibility, "CycloneAccessibilityService")) {
            val existing = run("/system/bin/settings", "--user", "$target", "get", "secure", "enabled_accessibility_services").trim()
            run("/system/bin/settings", "--user", "$target", "put", "secure", "enabled_accessibility_services",
                ProfileBootstrapContract.mergeServiceList(existing, ProfileBootstrapContract.ACCESSIBILITY))
            run("/system/bin/settings", "--user", "$target", "put", "secure", "accessibility_enabled", "1")
        }
        val ownListeners = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        if (containsCyclone(ownListeners, "CycloneNotificationListener")) {
            run("/system/bin/cmd", "notification", "allow_listener", ProfileBootstrapContract.LISTENER, "$target")
        }
    }

    private fun containsCyclone(list: String, service: String) = list.split(':').any {
        it == "$PKG/.$service" || it == "$PKG/$PKG.$service"
    }

    private fun packageUid(user: Int, pkg: String): Int {
        val text = run("/system/bin/pm", "list", "packages", "-U", "--user", "$user", pkg)
        val uid = Regex("package:" + Regex.escape(pkg) + "\\s+uid:(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
        check(uid != null && ProfileBootstrapContract.userId(uid) == user) { "Android couldn't verify a required app's profile identity." }
        return uid
    }

    private fun <T : Any> poll(tries: Int = 30, read: () -> T?): T {
        repeat(tries) { read()?.let { return it }; Thread.sleep(200) }
        error("Cyclone couldn't verify the receiving profile. Your current profile was kept open.")
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    private fun run(vararg args: String): String = execute(args.joinToString(" ", transform = ::quote))

    private fun writePrivate(path: String, uid: Int, data: String) {
        require(path.matches(Regex("/data/user_de/[0-9]+/com\\.cyclone\\.mobile/files/(profile-bootstrap|carry-inbox)\\.json")))
        execute("umask 077; cat > ${quote(path)} && chown $uid:$uid ${quote(path)} && restorecon ${quote(path)}", data)
    }

    private fun execute(command: String, input: String? = null): String {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread {
            runCatching { process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                synchronized(output) { if (output.length < MAX_OUTPUT) output.appendLine(line) }
            } } }
        }.apply { isDaemon = true; start() }
        try {
            process.outputStream.bufferedWriter().use { writer -> input?.let(writer::write) }
            check(process.waitFor(20, TimeUnit.SECONDS)) { "Profile preparation timed out. No switch was made." }
            reader.join(500)
            val text = synchronized(output) { output.toString() }
            check(process.exitValue() == 0 && !text.lineSequence().any { it.trim().startsWith("Error:") }) {
                "Android couldn't complete a required profile setup step. No switch was made."
            }
            return text
        } finally { process.destroyForcibly() }
    }
}
