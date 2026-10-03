package com.cyclone.mobile.runtime.workspaces

import org.junit.Assert.*
import org.junit.Test

class ProfileStructural427Test {
    private val token = "Cyclone_0123456789abcdef"
    @Test fun fullUserCommandsAreTypedAndAllowOwnerReturn() {
        assertEquals("/system/bin/pm create-user $token", ProfileSetupPlan.shell(ProfileSetupPlan.createSecondaryUser(token)))
        assertEquals("/system/bin/am switch-user 12", ProfileSetupPlan.shell(ProfileSetupPlan.switchUser(12)))
        assertEquals("/system/bin/am switch-user 0", ProfileSetupPlan.shell(ProfileSetupPlan.switchUser(0)))
    }
    @Test fun failedCreationResumesExactJournaledFullUser() {
        val user = ProfileUserRecord(12, token, false, false, null, false, false)
        val journal = ProfileJournalSnapshot(token, 0, 12, setOf("com.android.chrome"), ProfileSetupStage.PROFILE_CREATED, true)
        assertEquals(ProfileRecoveryDecision.Resume(user), ProfileRecovery.resolve(journal, 0, listOf(user)))
        assertTrue(ProfileRecovery.resolve(journal, 0, listOf(user.copy(name = "Corporate"))) is ProfileRecoveryDecision.Fail)
    }
    @Test fun incompleteJournalRecoversOnlyItsGeneratedIdentity() {
        val user = ProfileUserRecord(12, token, false, false, null, false, false)
        val journal = ProfileJournalSnapshot(token, 0, null, emptySet(), ProfileSetupStage.PLANNED, true)
        assertEquals(ProfileRecoveryDecision.Resume(user), ProfileRecovery.resolve(journal, 0, listOf(user)))
        assertEquals(ProfileRecoveryDecision.Create, ProfileRecovery.resolve(journal, 0, listOf(user.copy(name = "Rooted Clone"))))
    }
    @Test fun unrelatedManagedProfileCannotBecomeOwnedFullUser() {
        val user = ProfileUserRecord(12, token, true, true, 0, false, false)
        assertFalse(ProfileRecovery.validOwned(user, 0, true))
    }
    @Test fun cycloneAndInstalledSupportAppsAreAutomaticallyIncluded() {
        assertEquals(setOf("com.cyclone.mobile", "com.android.chrome", "com.instagram.android"),
            ProfileRequiredPackages.resolve("com.cyclone.mobile", setOf("com.android.chrome", "com.instagram.android")))
        assertEquals(
            setOf("com.cyclone.mobile", "com.android.chrome", "moe.shizuku.privileged.api", "me.weishu.kernelsu"),
            ProfileRequiredPackages.resolve(
                "com.cyclone.mobile",
                setOf("com.android.chrome"),
                setOf("moe.shizuku.privileged.api", "me.weishu.kernelsu"),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ProfileRequiredPackages.resolve("com.cyclone.mobile", emptySet(), setOf("com.example.randomroot"))
        }
    }
    @Test fun secondaryUserLimitIsTyped() {
        assertEquals(ProfileSetupFailureKind.MAX_USERS_REACHED,
            ProfileFailureClassifier.fromCommand(ProfileSetupOperation.CREATE_SECONDARY_USER, 1,
                "Error: maximum number of users")?.kind)
    }
    @Test fun existingManagedProfileDoesNotBlockFullSecondaryUser() {
        val owner = ProfileUserRecord(0, "Owner", false, false, null, true, false, "android.os.usertype.full.SYSTEM")
        val work = ProfileUserRecord(11, "Rooted Clone", true, true, 0, true, false, "android.os.usertype.profile.MANAGED")
        assertNull(SecondaryUserProvisioningPolicy.failure(0, 0, listOf(owner, work), 4))
    }
    @Test fun mainUserParserPrefersSystemFullUser() {
        val secondary = ProfileUserRecord(12, token, false, false, null, true, false, "android.os.usertype.full.SECONDARY")
        val owner = ProfileUserRecord(0, "Owner", false, false, null, true, false, "android.os.usertype.full.SYSTEM")
        assertEquals(0, ProfileSetupParser.mainUserId(listOf(secondary, owner)))
        assertEquals(ProfileSetupFailureKind.PROFILE_VERIFICATION_FAILED,
            SecondaryUserProvisioningPolicy.failure(12, 12, listOf(secondary, owner), 4)?.kind)
    }
    @Test fun requiredCyclonePackageDoesNotNeedWorkspaceRegistration() {
        val user = ProfileUserRecord(12, token, false, false, null, true, false)
        val evidence = ProfileReadyEvidence(
            user, 0, true,
            selectedPackages = setOf("com.cyclone.mobile", "com.android.chrome"),
            installedPackages = setOf("com.cyclone.mobile", "com.android.chrome"),
            workspaceUserIdsByPackage = mapOf("com.android.chrome" to setOf(12)),
            setupComplete = true, journalUserId = 12, secondaryUser = true,
            workspacePackages = setOf("com.android.chrome"),
        )
        assertNull(ProfileReadyVerifier.failure(evidence))
    }
}
