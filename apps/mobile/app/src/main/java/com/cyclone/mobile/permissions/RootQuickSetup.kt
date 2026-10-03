package com.cyclone.mobile.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import com.cyclone.mobile.ai.CycloneAiAccessProfile
import com.cyclone.mobile.ai.CycloneAiAccessProfileStore
import com.cyclone.mobile.runtime.background.TaskPhase
import com.cyclone.mobile.runtime.background.WorkspaceTasks
import com.cyclone.mobile.runtime.workspaces.Layer2Workspaces
import com.cyclone.mobile.runtime.workspaces.ProfileBootstrapContract
import java.util.concurrent.TimeUnit

/** Trusted, user-initiated setup only. Never registered as an AI/root-shell tool. */
object RootQuickSetup {
    data class Check(val label: String, val ready: Boolean)
    fun checks(context: Context): List<Check> = listOf(
        Check("Phone control", CyclonePermissionSetup.phoneControlReady(context)),
        Check("Appear on top", CyclonePermissionSetup.overlayEnabled(context)),
        Check("Task notifications", CyclonePermissionSetup.resultNotificationsEnabled(context)),
        Check("Notification access", CyclonePermissionSetup.notificationAccessEnabled(context)),
        Check("Microphone", context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED),
        Check("Calendar", CyclonePermissionSetup.calendarEnabled(context)),
        Check("Background battery access", CyclonePermissionSetup.batteryUnrestricted(context)),
        Check("Precise routine timing", CyclonePermissionSetup.exactTimingEnabled(context)),
        Check("Agent keyboard", CyclonePermissionSetup.agentKeyboardEnabled(context)),
    )

    fun apply(context: Context, independent: Boolean): String = synchronized(Layer2Workspaces.engine.mutationLock) {
        val user = Process.myUid() / 100000
        fun eligible() = !Layer2Workspaces.gated() &&
            !com.cyclone.mobile.ui.overlay.OverlayChromeRuntime.hasExecutingTask() && WorkspaceTasks.state.value?.phase.let {
            it == null || it in setOf(TaskPhase.DONE, TaskPhase.FAILED, TaskPhase.STOPPED)
        }
        if (!eligible()) return@synchronized "Finish or close the current task before setup."
        try {
            check(run(listOf("id", "-u")).trim() == "0")
            check(run(listOf("am", "get-current-user")).trim() == user.toString())
            val existing = run(listOf("settings", "--user", "$user", "get", "secure", "enabled_accessibility_services")).trim()
            val commands = RootQuickSetupPlan.commands(user, existing, CyclonePermissionSetup.phoneControlSnapshot(context).needsRepair)
            var failures = 0
            for (command in commands) {
                check(eligible())
                check(run(listOf("am", "get-current-user")).trim() == user.toString())
                try { run(command) } catch (_: Exception) { failures++ }
            }
            check(eligible())
            check(run(listOf("am", "get-current-user")).trim() == user.toString())
            if (independent) CycloneAiAccessProfileStore.write(context, CycloneAiAccessProfile.FULL)
            // Binding is asynchronous; the UI polls real readiness after returning.
            if (failures == 0) "Setup applied. Checking Android access…"
            else "Some access needs your help. Check the rows below."
        } catch (_: Exception) {
            "Setup stopped. Allow Cyclone in your root manager and keep this profile open, then retry."
        }
    }

    private fun run(args: List<String>): String {
        val command = args.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        process.outputStream.close()
        val output = StringBuilder()
        val reader = Thread {
            runCatching { process.inputStream.bufferedReader().useLines { lines -> lines.forEach {
                synchronized(output) { if (output.length < 16384) output.append(it.take(4096)).append('\n') }
            } } }
        }.apply { isDaemon = true; start() }
        try {
            check(process.waitFor(25, TimeUnit.SECONDS))
            reader.join(1000)
            check(process.exitValue() == 0)
            return synchronized(output) { output.toString() }
        } finally { process.destroy(); if (process.isAlive) process.destroyForcibly() }
    }
}

internal object RootQuickSetupPlan {
    fun commands(user: Int, existingServices: String, repairAccessibility: Boolean = false): List<List<String>> {
        require(user >= 0)
        val pkg = ProfileBootstrapContract.PACKAGE
        val services = ProfileBootstrapContract.mergeServiceList(existingServices, ProfileBootstrapContract.ACCESSIBILITY)
        return buildList {
            if (repairAccessibility) {
                val others = existingServices.split(':').filter {
                    it.isNotBlank() && it != "null" && it != ProfileBootstrapContract.ACCESSIBILITY &&
                        it != "$pkg/$pkg.CycloneAccessibilityService"
                }.joinToString(":")
                add(listOf("settings", "--user", "$user", "put", "secure", "enabled_accessibility_services", others))
            }
            ProfileBootstrapContract.permissions.forEach { add(listOf("pm", "grant", "--user", "$user", pkg, it)) }
            listOf("SYSTEM_ALERT_WINDOW", "SCHEDULE_EXACT_ALARM").forEach {
                add(listOf("cmd", "appops", "set", "--user", "$user", pkg, it, "allow"))
            }
            add(listOf("cmd", "deviceidle", "whitelist", "+$pkg"))
            add(listOf("cmd", "notification", "allow_listener", ProfileBootstrapContract.LISTENER, "$user"))
            add(listOf("ime", "enable", "--user", "$user", "$pkg/com.mobilerun.portal.input.MobilerunKeyboardIME"))
            add(listOf("settings", "--user", "$user", "put", "secure", "enabled_accessibility_services", services))
            add(listOf("settings", "--user", "$user", "put", "secure", "accessibility_enabled", "1"))
        }
    }
}
