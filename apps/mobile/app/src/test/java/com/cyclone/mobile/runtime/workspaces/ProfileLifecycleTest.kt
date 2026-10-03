package com.cyclone.mobile.runtime.workspaces

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 40 P1: Recently deleted, automatic backups and the typed commands behind them. */
class ProfileLifecycleTest {
    private val name = "Cyclone_0123456789abcdef"
    private val day = 24L * 60 * 60 * 1000
    private fun record(user: Int? = 10, removedAt: Long? = null, secondary: Boolean = true) =
        CycloneProfileRecord(name, "Work", user, 0, secondary, setOf("com.slack"), "READY", true, removedAtMs = removedAt)
    private fun secondaryUser(id: Int = 10, userName: String = name) =
        ProfileUserRecord(id = id, name = userName, profile = false, managed = false, parentId = null, running = true, partial = false)

    @Test fun `a removed profile stays seven days, then goes`() {
        val removed = 1_000_000L
        assertEquals(7, ProfileTrash.daysLeft(removed, removed))
        // Whole days left, rounded down: "today" only when less than a day is left.
        assertEquals("Deletes in 6 days", ProfileTrash.line(removed, removed + day))
        assertEquals("Deletes in 5 days", ProfileTrash.line(removed, removed + day + 1))
        assertEquals("Deletes tomorrow", ProfileTrash.line(removed, removed + 6 * day - 1))
        assertEquals("Deletes today", ProfileTrash.line(removed, removed + 6 * day + 1))
        assertFalse(ProfileTrash.expired(removed, removed + 7 * day - 1))
        assertTrue(ProfileTrash.expired(removed, removed + 7 * day))
        assertTrue(ProfileTrash.backupExpired(0, 30 * day))
        assertFalse(ProfileTrash.backupExpired(0, 29 * day))
    }

    @Test fun `the trash reads oldest first and holds only removed profiles`() {
        val a = record(removedAt = 5).copy(id = "Cyclone_aaaaaaaaaaaaaaaa")
        val b = record(removedAt = 2).copy(id = "Cyclone_bbbbbbbbbbbbbbbb")
        val live = record().copy(id = "Cyclone_cccccccccccccccc")
        assertEquals(listOf(b.id, a.id), ProfileTrash.order(listOf(a, live, b)).map { it.id })
    }

    @Test fun `only profiles Cyclone made, from the main profile, never the main or current one, never mid-task`() {
        val ok = record()
        assertNull(ProfileTrash.refusal(ok, secondaryUser(), mainUserId = 0, currentUserId = 0, taskRunning = false))
        assertEquals("Finish the current task first.", ProfileTrash.refusal(ok, secondaryUser(), 0, 0, true))
        assertEquals("Open the main profile to remove or delete profiles.", ProfileTrash.refusal(ok, secondaryUser(), 0, 11, false))
        assertEquals("The main profile can't be removed.", ProfileTrash.refusal(record(user = 5), secondaryUser(5), 5, 5, false))
        assertEquals("This profile was never created on the phone.", ProfileTrash.refusal(record(user = 0), secondaryUser(0), 0, 0, false))
        assertEquals("Cyclone can only remove profiles it created.",
            ProfileTrash.refusal(ok, secondaryUser(userName = "Someone's work profile"), 0, 0, false))
        assertEquals("Android doesn't list this profile any more.", ProfileTrash.refusal(ok, null, 0, 0, false))
        assertEquals("This profile was never created on the phone.", ProfileTrash.refusal(record(user = null), null, 0, 0, false))
    }

    @Test fun `a backup keeps Cyclone always and leaves the largest apps out when space is short`() {
        val sizes = mapOf("com.cyclone.mobile" to 5_000L, "com.slack" to 40_000L, "com.whatsapp" to 900_000L, "com.notes" to 2_000L)
        val (take, skip) = ProfileTrash.planBackup(sizes, "com.cyclone.mobile", freeKb = 100_000)
        assertEquals(listOf("com.cyclone.mobile", "com.notes", "com.slack"), take)
        assertEquals(listOf("com.whatsapp"), skip)
        val (all, none) = ProfileTrash.planBackup(sizes, "com.cyclone.mobile", freeKb = 10_000_000)
        assertEquals(4, all.size)
        assertTrue(none.isEmpty())
    }

    @Test fun `the lifecycle commands have one exact shape and never touch user 0`() {
        assertEquals("/system/bin/am stop-user -w 10", ProfileSetupPlan.shell(ProfileSetupPlan.stopUser(10)))
        assertEquals("/system/bin/pm remove-user 10", ProfileSetupPlan.shell(ProfileSetupPlan.removeUser(10)))
        listOf({ ProfileSetupPlan.removeUser(0) }, { ProfileSetupPlan.stopUser(0) }, { ProfileSetupPlan.measureAppData(0, "com.slack") })
            .forEach { assertTrue(runCatching(it).isFailure) }
        val dir = "/data/user/0/com.cyclone.mobile/files/profile-backups/$name-1790000000000"
        assertEquals("/system/bin/tar -cf $dir/ce-com.slack.tar -C /data/user/10 com.slack",
            ProfileSetupPlan.shell(ProfileSetupPlan.backupAppData(10, "com.slack", deviceStorage = false, backupDir = dir)))
        assertEquals("/system/bin/tar -cf $dir/de-com.slack.tar -C /data/user_de/10 com.slack",
            ProfileSetupPlan.shell(ProfileSetupPlan.backupAppData(10, "com.slack", deviceStorage = true, backupDir = dir)))
        // Backups only ever land in Cyclone's own backup folder.
        listOf("/sdcard/x", "/data/user/0/com.other/files/profile-backups/$name-1790000000000", "$dir/../../..").forEach { bad ->
            assertTrue(bad, runCatching { ProfileSetupPlan.backupAppData(10, "com.slack", false, bad) }.isFailure)
            assertTrue(bad, runCatching { ProfileSetupPlan.ownBackup(bad, 10123) }.isFailure)
        }
        assertEquals("/system/bin/chown -R 10123:10123 $dir", ProfileSetupPlan.shell(ProfileSetupPlan.ownBackup(dir, 10123)))
        assertTrue(runCatching { ProfileSetupPlan.ownBackup(dir, 0) }.isFailure)
    }
}
