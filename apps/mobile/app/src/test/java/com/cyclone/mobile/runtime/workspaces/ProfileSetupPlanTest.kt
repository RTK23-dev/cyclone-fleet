package com.cyclone.mobile.runtime.workspaces

import org.junit.Assert.*
import org.junit.Test

class ProfileSetupPlanTest {
    private val name = "Cyclone_0123456789abcdef"

    private fun parent(id: Int = 0) = ProfileUserRecord(
        id = id, name = "Owner", profile = false, managed = false, parentId = null, running = true, partial = false,
    )

    private fun owned(id: Int = 10, parent: Int = 0, name: String = this.name) = ProfileUserRecord(
        id = id, name = name, profile = true, managed = true, parentId = parent, running = true, partial = false,
    )

    private fun journal(userId: Int? = null, stage: ProfileSetupStage = ProfileSetupStage.PLANNED) = ProfileJournalSnapshot(
        profileName = name,
        parentUserId = 0,
        profileUserId = userId,
        selectedPackages = setOf("com.example.one", "com.example.two"),
        stage = stage,
    )

    @Test fun rootProbePassesButCreateUserExitsNonzero() {
        assertNull(ProfileFailureClassifier.fromCommand(ProfileSetupOperation.VERIFY_ROOT, 0, "uid=0(root) gid=0(root)"))
        assertEquals(
            ProfileSetupFailureKind.PROFILE_CREATION_REJECTED,
            ProfileFailureClassifier.fromCommand(ProfileSetupOperation.CREATE_MANAGED_PROFILE, 1, "Error: create failed")?.kind,
        )
    }

    @Test fun actualAndroidFailureReasonSurvivesClassification() {
        val failure = requireNotNull(ProfileFailureClassifier.fromCommand(
            ProfileSetupOperation.CREATE_MANAGED_PROFILE,
            1,
            "Error: maximum profiles reached for android.os.usertype.profile.MANAGED",
        ))
        assertTrue(failure.platformMessage.orEmpty().contains("maximum profiles reached", ignoreCase = true))
        assertTrue(failure.platformMessage.orEmpty().length <= 180)
    }

    @Test fun maxProfileAndUserFailuresHaveExactUserFacingErrors() {
        val profile = requireNotNull(ProfileFailureClassifier.fromCommand(
            ProfileSetupOperation.CREATE_MANAGED_PROFILE, 1, "Error: maximum profiles reached"))
        assertEquals(ProfileSetupFailureKind.MAX_PROFILES_REACHED, profile.kind)
        assertEquals("Profile limit reached", profile.headline)
        val users = requireNotNull(ProfileFailureClassifier.fromCommand(
            ProfileSetupOperation.CREATE_MANAGED_PROFILE, 1, "Error: maximum number of users reached"))
        assertEquals(ProfileSetupFailureKind.MAX_USERS_REACHED, users.kind)
        assertEquals("User limit reached", users.headline)
    }

    @Test fun rootDeniedIsNotProfileUnsupported() {
        val failure = requireNotNull(ProfileFailureClassifier.fromCommand(
            ProfileSetupOperation.VERIFY_ROOT, 1, "su: permission denied"))
        assertEquals(ProfileSetupFailureKind.ROOT_DENIED, failure.kind)
        assertNotEquals(ProfileSetupFailureKind.MANAGED_PROFILE_UNSUPPORTED, failure.kind)
    }

    @Test fun existingCycloneOwnedProfileResumesInsteadOfDuplicating() {
        val decision = ProfileRecovery.resolve(journal(), 0, listOf(parent(), owned()))
        assertTrue(decision is ProfileRecoveryDecision.Resume)
        assertEquals(10, (decision as ProfileRecoveryDecision.Resume).user.id)
    }

    @Test fun journaledUserMismatchFailsClosed() {
        val decision = ProfileRecovery.resolve(journal(userId = 11), 0, listOf(parent(), owned(id = 10)))
        assertTrue(decision is ProfileRecoveryDecision.Fail)
        assertEquals(
            ProfileSetupFailureKind.PROFILE_VERIFICATION_FAILED,
            (decision as ProfileRecoveryDecision.Fail).failure.kind,
        )
    }

    @Test fun successfulCreateIsRelistedAndVerified() {
        assertNull(ProfileRecovery.verifyCreated(10, name, 0, listOf(parent(), owned())))
        assertEquals(
            ProfileSetupFailureKind.PROFILE_VERIFICATION_FAILED,
            ProfileRecovery.verifyCreated(10, name, 0, listOf(parent(), owned(parent = 11)))?.kind,
        )
    }

    @Test fun packageInstallFailureResumesSafely() {
        val installFailure = ProfileFailureClassifier.fromCommand(
            ProfileSetupOperation.INSTALL_EXISTING_PACKAGE, 1, "Failure [INSTALL_FAILED_USER_RESTRICTED]")
        assertEquals(ProfileSetupFailureKind.PACKAGE_INSTALL_FAILED, installFailure?.kind)
        val decision = ProfileRecovery.resolve(
            journal(userId = 10, stage = ProfileSetupStage.PROFILE_STARTED),
            0,
            listOf(parent(), owned()),
        )
        assertTrue(decision is ProfileRecoveryDecision.Resume)
        assertEquals(listOf("com.example.two"), ProfileInstallResume.remaining(journal().selectedPackages, setOf("com.example.one")))
    }

    @Test fun selectedAppDisappearsDuringSetup() {
        val failure = ProfileSelectionVerifier.failure(
            setOf("com.example.one", "com.example.two"),
            setOf("com.example.one"),
        )
        assertEquals(ProfileSetupFailureKind.PACKAGE_INSTALL_FAILED, failure?.kind)
        assertTrue(failure?.platformMessage.orEmpty().contains("com.example.two"))
    }

    @Test fun cancellationLeavesRecoverableState() {
        val saved = journal(userId = 10, stage = ProfileSetupStage.PROFILE_CREATED)
        val decision = ProfileRecovery.resolve(saved, 0, listOf(parent(), owned()))
        assertTrue(decision is ProfileRecoveryDecision.Resume)
        assertEquals(10, (decision as ProfileRecoveryDecision.Resume).user.id)
    }

    @Test fun profileAIsNeverTargetedForDestructiveCleanup() {
        val commands = listOf(
            ProfileSetupPlan.verifyRoot(),
            ProfileSetupPlan.currentUser(),
            ProfileSetupPlan.listUsers(),
            ProfileSetupPlan.getMaxUsers(),
            ProfileSetupPlan.createManagedProfile(0, name),
            ProfileSetupPlan.startProfile(10),
            ProfileSetupPlan.profileState(10),
            ProfileSetupPlan.installExisting(10, "com.example.one"),
            ProfileSetupPlan.listProfilePackage(10, "com.example.one"),
            ProfileSetupPlan.markSetupComplete(10),
            ProfileSetupPlan.readSetupComplete(10),
        )
        commands.forEach { command ->
            val shell = ProfileSetupPlan.shell(command).lowercase()
            assertFalse(shell.contains("remove-user"))
            assertFalse(shell.contains("delete-user"))
            assertFalse(shell.contains(" remove "))
            assertFalse(shell.contains(" delete "))
        }
        assertTrue(runCatching { ProfileSetupPlan.installExisting(0, "com.example.one") }.isFailure)
        assertTrue(runCatching { ProfileSetupPlan.startProfile(0) }.isFailure)
        assertTrue(runCatching { ProfileSetupPlan.markSetupComplete(0) }.isFailure)
    }

    @Test fun shizukuAuthorizationIsNotFalselyProfileReadiness() {
        val capabilities = ProfileProvisioningCapabilities(
            rootUsable = false,
            shizukuAuthorized = true,
            shizukuProfileProbeVerified = false,
            parentUserId = 0,
            parentCanHostManagedProfile = true,
            maxUsersReported = 4,
            existingUserCount = 1,
            existingManagedProfilesForParent = 0,
            managedUsersFeature = true,
            addManagedProfileRestricted = false,
            lowRamDevice = false,
        )
        assertNull(capabilities.authority)
        assertFalse(capabilities.profileCreationReady)
    }

    @Test fun typedCommandPlanningRejectsArbitraryShellData() {
        val forged = ProfileSetupCommand.fixed(
            ProfileSetupOperation.CREATE_MANAGED_PROFILE,
            "/system/bin/sh", "-c", "reboot",
        )
        assertTrue(runCatching { ProfileSetupPlan.shell(forged) }.isFailure)
        assertTrue(runCatching { ProfileSetupPlan.installExisting(10, "com.example.one;reboot") }.isFailure)
        assertTrue(ProfileSetupPlan.acceptsOnlyTyped(ProfileSetupPlan.createManagedProfile(0, name)))
    }

    @Test fun successfulReadyStateRequiresFullVerification() {
        val base = ProfileReadyEvidence(
            user = owned(),
            parentUserId = 0,
            runningUnlocked = true,
            selectedPackages = setOf("com.example.one", "com.example.two"),
            installedPackages = setOf("com.example.one", "com.example.two"),
            workspaceUserIdsByPackage = mapOf("com.example.one" to setOf(10), "com.example.two" to setOf(10)),
            setupComplete = true,
            journalUserId = 10,
        )
        assertNull(ProfileReadyVerifier.failure(base))
        assertEquals(ProfileSetupFailureKind.PROFILE_NOT_UNLOCKED, ProfileReadyVerifier.failure(base.copy(runningUnlocked = false))?.kind)
        assertEquals(
            ProfileSetupFailureKind.PACKAGE_INSTALL_FAILED,
            ProfileReadyVerifier.failure(base.copy(installedPackages = setOf("com.example.one")))?.kind,
        )
        assertEquals(
            ProfileSetupFailureKind.PROFILE_VERIFICATION_FAILED,
            ProfileReadyVerifier.failure(base.copy(workspaceUserIdsByPackage = mapOf("com.example.one" to setOf(10), "com.example.two" to setOf(11))))?.kind,
        )
        assertEquals(ProfileSetupFailureKind.PROFILE_VERIFICATION_FAILED, ProfileReadyVerifier.failure(base.copy(setupComplete = false))?.kind)
        assertEquals(ProfileSetupFailureKind.PROFILE_VERIFICATION_FAILED, ProfileReadyVerifier.failure(base.copy(journalUserId = 11))?.kind)
    }

    @Test fun verboseAndroidUserListingPreservesTypeAndParent() {
        val output = """
            2 users:
              0: id=0, name=Owner, type=android.os.usertype.full.SYSTEM, flags=ADMIN|FULL|INITIALIZED|MAIN|SYSTEM (running) (current)
              10: id=10, name=$name, type=android.os.usertype.profile.MANAGED, flags=INITIALIZED|MANAGED_PROFILE|PROFILE (parentId=0) (running)
        """.trimIndent()
        val users = ProfileSetupParser.users(output)
        assertEquals(2, users.size)
        assertTrue(users[1].managed)
        assertTrue(users[1].profile)
        assertEquals(0, users[1].parentId)
        assertEquals(10, ProfileSetupParser.ownedUser(users, name, 0)?.id)
    }

    @Test fun managedProfileParentIsRejectedBeforeCreate() {
        val result = ProfileCapabilityEvaluator.evaluate(
            rootUsable = true,
            shizukuAuthorized = true,
            shizukuProfileProbeVerified = false,
            appUserId = 10,
            reportedCurrentUserId = 10,
            users = listOf(owned(id = 10, parent = 0, name = "Work")),
            maxUsersReported = 4,
        )
        assertEquals(ProfileSetupFailureKind.MANAGED_PROFILE_UNSUPPORTED, result.failure?.kind)
    }

    @Test fun currentUserMismatchFailsClosed() {
        val result = ProfileCapabilityEvaluator.evaluate(
            rootUsable = true,
            shizukuAuthorized = false,
            shizukuProfileProbeVerified = false,
            appUserId = 0,
            reportedCurrentUserId = 10,
            users = listOf(parent()),
            maxUsersReported = 4,
        )
        assertEquals(ProfileSetupFailureKind.PROFILE_VERIFICATION_FAILED, result.failure?.kind)
    }

    @Test fun oemRestrictionStaysDistinct() {
        val failure = ProfileFailureClassifier.fromCommand(
            ProfileSetupOperation.CREATE_MANAGED_PROFILE,
            1,
            "Error: DISALLOW_ADD_MANAGED_PROFILE user restriction",
        )
        assertEquals(ProfileSetupFailureKind.OEM_RESTRICTION, failure?.kind)
    }

    @Test fun managedUsersFeatureAndRestrictionArePreflightCapabilities() {
        val unsupported = ProfileCapabilityEvaluator.evaluate(
            rootUsable = true,
            shizukuAuthorized = false,
            shizukuProfileProbeVerified = false,
            appUserId = 0,
            reportedCurrentUserId = 0,
            users = listOf(parent()),
            maxUsersReported = 4,
            managedUsersFeature = false,
        )
        assertEquals(ProfileSetupFailureKind.MANAGED_PROFILE_UNSUPPORTED, unsupported.failure?.kind)
        assertFalse(unsupported.capabilities.parentCanHostManagedProfile)

        val restricted = ProfileCapabilityEvaluator.evaluate(
            rootUsable = true,
            shizukuAuthorized = false,
            shizukuProfileProbeVerified = false,
            appUserId = 0,
            reportedCurrentUserId = 0,
            users = listOf(parent()),
            maxUsersReported = 4,
            addManagedProfileRestricted = true,
        )
        assertEquals(ProfileSetupFailureKind.OEM_RESTRICTION, restricted.failure?.kind)
        assertFalse(restricted.capabilities.parentCanHostManagedProfile)
    }

    @Test fun alreadyExistsIsRecoverableClassification() {
        val failure = requireNotNull(ProfileFailureClassifier.fromCommand(
            ProfileSetupOperation.CREATE_MANAGED_PROFILE,
            1,
            "Error: user already exists",
        ))
        assertEquals(ProfileSetupFailureKind.PROFILE_ALREADY_EXISTS, failure.kind)
        assertTrue(failure.retryUseful)
    }

    @Test fun theProfileAppManagerHasTwoFixedVerbsOnly() {
        assertEquals("/system/bin/pm list packages -3 --user 11", ProfileSetupPlan.shell(ProfileSetupPlan.listProfileApps(11)))
        assertEquals("/system/bin/pm uninstall --user 11 com.whatsapp", ProfileSetupPlan.shell(ProfileSetupPlan.uninstallForProfile(11, "com.whatsapp")))
        assertTrue(runCatching { ProfileSetupPlan.uninstallForProfile(0, "com.whatsapp") }.isFailure)
        assertTrue(runCatching { ProfileSetupPlan.uninstallForProfile(11, "com.cyclone.mobile") }.isFailure)
        assertTrue(runCatching { ProfileSetupPlan.uninstallForProfile(11, "com.x;rm -rf /") }.isFailure)
    }
}
