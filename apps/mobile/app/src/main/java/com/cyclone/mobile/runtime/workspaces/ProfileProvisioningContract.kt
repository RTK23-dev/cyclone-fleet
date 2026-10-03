package com.cyclone.mobile.runtime.workspaces

enum class ProfileProvisioningAuthority { ROOT, SHIZUKU_SHELL }

enum class ProfileSetupFailureKind {
    ROOT_UNAVAILABLE,
    ROOT_DENIED,
    ROOT_COMMAND_FAILED,
    MANAGED_PROFILE_UNSUPPORTED,
    MAX_USERS_REACHED,
    MAX_PROFILES_REACHED,
    PROFILE_CREATION_REJECTED,
    PROFILE_ALREADY_EXISTS,
    PROFILE_START_FAILED,
    PROFILE_NOT_UNLOCKED,
    PACKAGE_INSTALL_FAILED,
    PROFILE_VERIFICATION_FAILED,
    STORAGE_FAILURE,
    OEM_RESTRICTION,
    UNKNOWN_PLATFORM_FAILURE,
}

data class ProfileSetupFailure(
    val kind: ProfileSetupFailureKind,
    val headline: String,
    val reason: String,
    val action: String,
    val retryUseful: Boolean,
    internal val platformMessage: String? = null,
) {
    fun compactMessage(): String = listOf(headline, reason, action).filter { it.isNotBlank() }.joinToString("\n")
}

data class ProfileCommandResult(
    val operation: ProfileSetupOperation,
    val exitCode: Int?,
    internal val output: String,
    val classifiedResult: ProfileSetupFailureKind?,
    val sanitizedPlatformMessage: String?,
    val retryUseful: Boolean,
) {
    val successful: Boolean get() = classifiedResult == null
}

data class ProfileProvisioningCapabilities(
    val rootUsable: Boolean,
    val shizukuAuthorized: Boolean,
    /**
     * False in 4.2.2 unless a fixed, typed shell-identity broker is actually wired and probed.
     * Authorization by itself must never be presented as Profile B readiness.
     */
    val shizukuProfileProbeVerified: Boolean,
    val parentUserId: Int?,
    val parentCanHostManagedProfile: Boolean,
    val maxUsersReported: Int?,
    val existingUserCount: Int,
    val existingManagedProfilesForParent: Int,
    val managedUsersFeature: Boolean,
    val addManagedProfileRestricted: Boolean,
    val lowRamDevice: Boolean,
) {
    val authority: ProfileProvisioningAuthority? get() = when {
        rootUsable -> ProfileProvisioningAuthority.ROOT
        shizukuAuthorized && shizukuProfileProbeVerified -> ProfileProvisioningAuthority.SHIZUKU_SHELL
        else -> null
    }
    val profileCreationReady: Boolean get() = authority != null && parentUserId != null && parentCanHostManagedProfile
}

object ProfileFailureClassifier {
    private const val PLATFORM_LIMIT = 180

    fun fromCommand(
        operation: ProfileSetupOperation,
        exitCode: Int?,
        output: String,
        unavailable: Boolean = false,
        timedOut: Boolean = false,
    ): ProfileSetupFailure? {
        val text = output.take(8_192)
        val lower = text.lowercase()
        if (unavailable) return failure(ProfileSetupFailureKind.ROOT_UNAVAILABLE, sanitize(text))
        if (timedOut) return when (operation) {
            ProfileSetupOperation.START_PROFILE, ProfileSetupOperation.PROFILE_STATE ->
                failure(ProfileSetupFailureKind.PROFILE_START_FAILED, "Android did not finish starting Profile B in time.")
            ProfileSetupOperation.INSTALL_EXISTING_PACKAGE, ProfileSetupOperation.LIST_PROFILE_PACKAGE,
            ProfileSetupOperation.LIST_PROFILE_APPS, ProfileSetupOperation.UNINSTALL_FOR_PROFILE ->
                failure(ProfileSetupFailureKind.PACKAGE_INSTALL_FAILED, "Android did not finish adding the app in time.")
            else -> failure(ProfileSetupFailureKind.ROOT_COMMAND_FAILED, "The privileged Android operation timed out.")
        }

        if (operation == ProfileSetupOperation.VERIFY_ROOT) {
            if (exitCode == 0 && Regex("(?:^|\\s)uid=0(?:\\D|$)").containsMatchIn(text)) return null
            if (rootDenied(lower)) return failure(ProfileSetupFailureKind.ROOT_DENIED, sanitize(text))
            return if (exitCode == -127 || lower.contains("not found") || lower.contains("no such file")) {
                failure(ProfileSetupFailureKind.ROOT_UNAVAILABLE, sanitize(text))
            } else failure(ProfileSetupFailureKind.ROOT_COMMAND_FAILED, sanitize(text) ?: "The privileged identity did not report uid 0.")
        }

        val textualFailure = text.lineSequence().any { line ->
            val trimmed = line.trim()
            trimmed.startsWith("Error:", true) || trimmed.startsWith("Failure", true) ||
                trimmed.contains("Exception", true)
        }
        if (exitCode == 0 && !textualFailure) return null
        val platform = sanitize(text)
        if (rootDenied(lower)) return failure(ProfileSetupFailureKind.ROOT_DENIED, platform)

        if (operation in setOf(ProfileSetupOperation.CREATE_MANAGED_PROFILE, ProfileSetupOperation.CREATE_SECONDARY_USER)) {
            if (lower.contains("already exists")) return failure(ProfileSetupFailureKind.PROFILE_ALREADY_EXISTS, platform)
            if (lower.contains("add more profiles") || lower.contains("maximum profiles") ||
                lower.contains("max profiles") || lower.contains("profile limit") ||
                (lower.contains("profile.managed") && lower.contains("maximum number of that type"))) {
                return failure(ProfileSetupFailureKind.MAX_PROFILES_REACHED, platform)
            }
            if (lower.contains("maximum user limit") || lower.contains("maximum number of users") ||
                lower.contains("user limit reached") || lower.contains("max users")) {
                return failure(ProfileSetupFailureKind.MAX_USERS_REACHED, platform)
            }
            if (lower.contains("not enough space") || lower.contains("low storage") || lower.contains("no space left")) {
                return failure(ProfileSetupFailureKind.STORAGE_FAILURE, platform)
            }
            if (lower.contains("profiles cannot be created") || lower.contains("cannot have profile") ||
                lower.contains("managed profile is not supported") || lower.contains("managed profiles not supported") ||
                lower.contains("doesn't support managed") ||
                (lower.contains("disabled type") && lower.contains("profile.managed"))) {
                return failure(ProfileSetupFailureKind.MANAGED_PROFILE_UNSUPPORTED, platform)
            }
            if (lower.contains("disallow_add_managed_profile") || lower.contains("user restriction") ||
                lower.contains("device policy") || lower.contains("admin policy") || lower.contains("oem") ||
                lower.contains("securityexception") || lower.contains("permission denial") ||
                (lower.contains("not allowed") && (lower.contains("profile") || lower.contains("user")))) {
                return failure(ProfileSetupFailureKind.OEM_RESTRICTION, platform)
            }
            return failure(ProfileSetupFailureKind.PROFILE_CREATION_REJECTED, platform)
        }

        return when (operation) {
            ProfileSetupOperation.START_PROFILE, ProfileSetupOperation.SWITCH_USER -> failure(ProfileSetupFailureKind.PROFILE_START_FAILED, platform)
            ProfileSetupOperation.PROFILE_STATE -> failure(ProfileSetupFailureKind.PROFILE_NOT_UNLOCKED, platform)
            ProfileSetupOperation.INSTALL_EXISTING_PACKAGE, ProfileSetupOperation.LIST_PROFILE_PACKAGE,
            ProfileSetupOperation.LIST_PROFILE_APPS, ProfileSetupOperation.UNINSTALL_FOR_PROFILE ->
                failure(ProfileSetupFailureKind.PACKAGE_INSTALL_FAILED, platform)
            ProfileSetupOperation.MARK_SETUP_COMPLETE, ProfileSetupOperation.READ_SETUP_COMPLETE,
            ProfileSetupOperation.LIST_USERS, ProfileSetupOperation.CURRENT_USER ->
                failure(ProfileSetupFailureKind.PROFILE_VERIFICATION_FAILED, platform)
            ProfileSetupOperation.GET_MAX_USERS -> failure(ProfileSetupFailureKind.ROOT_COMMAND_FAILED, platform)
            ProfileSetupOperation.STOP_USER, ProfileSetupOperation.REMOVE_USER ->
                failure(ProfileSetupFailureKind.ROOT_COMMAND_FAILED, platform)
            ProfileSetupOperation.MEASURE_APP_DATA, ProfileSetupOperation.BACKUP_APP_DATA,
            ProfileSetupOperation.OWN_BACKUP, ProfileSetupOperation.LABEL_BACKUP ->
                failure(ProfileSetupFailureKind.STORAGE_FAILURE, platform)
            ProfileSetupOperation.VERIFY_ROOT -> error("handled above")
            ProfileSetupOperation.CREATE_MANAGED_PROFILE, ProfileSetupOperation.CREATE_SECONDARY_USER -> error("handled above")
        }
    }

    fun local(kind: ProfileSetupFailureKind, detail: String? = null): ProfileSetupFailure = failure(kind, sanitize(detail.orEmpty()))

    private fun rootDenied(lower: String): Boolean =
        (lower.contains("su") && (lower.contains("denied") || lower.contains("inaccessible") || lower.contains("not allowed"))) ||
            lower.contains("root access rejected") || lower.contains("root access denied") || lower.contains("magisk") && lower.contains("denied")

    internal fun sanitize(raw: String): String? {
        val oneLine = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .replace(Regex("(?i)android\\.os\\.servicespecificexception:\\s*"), "")
            .replace(Regex("(?i)java\\.[A-Za-z0-9_.$]+:\\s*"), "")
            .replace(Regex("(?i)(/system/bin/)?(pm|cmd|am|settings)\\s+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(PLATFORM_LIMIT)
        return oneLine.ifBlank { null }
    }

    private fun failure(kind: ProfileSetupFailureKind, platform: String?): ProfileSetupFailure {
        val base = when (kind) {
            ProfileSetupFailureKind.ROOT_UNAVAILABLE -> ProfileSetupFailure(kind, "Root unavailable",
                "Cyclone couldn't start its privileged profile setup authority.", "Retry after checking your root setup.", true)
            ProfileSetupFailureKind.ROOT_DENIED -> ProfileSetupFailure(kind, "Root needs attention",
                "Root access was requested but the privileged command was denied.", "Retry after allowing Cyclone in your root manager.", true)
            ProfileSetupFailureKind.ROOT_COMMAND_FAILED -> ProfileSetupFailure(kind, "Root check failed",
                "Root was detected, but the fixed privileged Android check did not complete correctly.", "Retry after rechecking Cyclone in your root manager.", true)
            ProfileSetupFailureKind.MANAGED_PROFILE_UNSUPPORTED -> ProfileSetupFailure(kind, "Profile B isn't supported",
                "This phone isn't allowing a managed work profile under your current Profile A.", "Keep using Profile A on this phone.", false)
            ProfileSetupFailureKind.MAX_USERS_REACHED -> ProfileSetupFailure(kind, "User limit reached",
                "Android reports this phone has reached its user limit.", "This phone has reached Android's profile limit.", true)
            ProfileSetupFailureKind.MAX_PROFILES_REACHED -> ProfileSetupFailure(kind, "Profile limit reached",
                "This phone isn't allowing another work profile because its profile limit is reached.", "This phone has reached Android's profile limit.", true)
            ProfileSetupFailureKind.PROFILE_CREATION_REJECTED -> ProfileSetupFailure(kind, "Profile B couldn't be added",
                "Android rejected creation of the isolated Profile B identity.", "Retry after checking Android's user/profile settings.", true)
            ProfileSetupFailureKind.PROFILE_ALREADY_EXISTS -> ProfileSetupFailure(kind, "Profile B already exists",
                "Cyclone found an existing Profile B instead of creating another one.", "Continue the saved Profile B setup.", true)
            ProfileSetupFailureKind.PROFILE_START_FAILED -> ProfileSetupFailure(kind, "Profile B didn't start",
                "Android created Profile B, but it could not be started yet.", "Retry after unlocking the phone.", true)
            ProfileSetupFailureKind.PROFILE_NOT_UNLOCKED -> ProfileSetupFailure(kind, "Profile B needs to finish starting",
                "Profile B exists, but Android does not report it as running and unlocked.", "Retry after unlocking the phone and Profile B.", true)
            ProfileSetupFailureKind.PACKAGE_INSTALL_FAILED -> ProfileSetupFailure(kind, "An app couldn't be added",
                "Profile B is saved, but one selected app could not be verified there.", "Retry after reinstalling the app in Profile A or changing your selection.", true)
            ProfileSetupFailureKind.PROFILE_VERIFICATION_FAILED -> ProfileSetupFailure(kind, "Profile B couldn't be verified",
                "Cyclone stopped rather than target the wrong Android user or incomplete profile.", "Retry Profile B setup.", true)
            ProfileSetupFailureKind.STORAGE_FAILURE -> ProfileSetupFailure(kind, "Storage needs attention",
                "Cyclone couldn't safely save profile setup progress.", "Retry after freeing some storage.", true)
            ProfileSetupFailureKind.OEM_RESTRICTION -> ProfileSetupFailure(kind, "Android is blocking Profile B",
                "A phone or administrator policy is preventing another managed profile.", "Retry after reviewing Android's work-profile or administrator settings.", true)
            ProfileSetupFailureKind.UNKNOWN_PLATFORM_FAILURE -> ProfileSetupFailure(kind, "Profile setup paused",
                "Android returned an unexpected result while preparing Profile B.", "Retry Profile B setup.", true)
        }
        return base.copy(platformMessage = platform)
    }
}

enum class ProfileSetupStage { PLANNED, PROFILE_CREATED, PROFILE_STARTED, APPS_INSTALLED, SETUP_COMPLETE, READY }

data class ProfileJournalSnapshot(
    val profileName: String,
    val parentUserId: Int?,
    val profileUserId: Int?,
    val selectedPackages: Set<String>,
    val stage: ProfileSetupStage,
    val secondaryUser: Boolean = false,
)

sealed class ProfileRecoveryDecision {
    data object Create : ProfileRecoveryDecision()
    data class Resume(val user: ProfileUserRecord) : ProfileRecoveryDecision()
    data class Fail(val failure: ProfileSetupFailure) : ProfileRecoveryDecision()
}

object ProfileRecovery {
    internal fun resolve(
        journal: ProfileJournalSnapshot,
        currentParentUserId: Int,
        users: List<ProfileUserRecord>,
    ): ProfileRecoveryDecision {
        if (!ProfileSetupPlan.validProfileName(journal.profileName)) {
            return ProfileRecoveryDecision.Fail(ProfileFailureClassifier.local(
                ProfileSetupFailureKind.PROFILE_VERIFICATION_FAILED, "The saved Cyclone profile name is invalid."))
        }
        if (journal.parentUserId != null && journal.parentUserId != currentParentUserId) {
            return ProfileRecoveryDecision.Fail(ProfileFailureClassifier.local(
                ProfileSetupFailureKind.PROFILE_VERIFICATION_FAILED, "The saved Profile B belongs to a different Profile A user."))
        }
        val sameName = users.filter { it.name == journal.profileName }
        if (journal.profileUserId != null) {
            val exactId = sameName.singleOrNull { it.id == journal.profileUserId }
            if (sameName.size != 1 || exactId == null || !validOwned(exactId, currentParentUserId, journal.secondaryUser)) {
                return ProfileRecoveryDecision.Fail(ProfileFailureClassifier.local(
                    ProfileSetupFailureKind.PROFILE_VERIFICATION_FAILED, "The journaled Profile B identity no longer matches Android."))
            }
            return ProfileRecoveryDecision.Resume(exactId)
        }
        if (sameName.isEmpty()) return ProfileRecoveryDecision.Create
        val existing = sameName.singleOrNull()
        return if (existing != null && validOwned(existing, currentParentUserId, journal.secondaryUser)) ProfileRecoveryDecision.Resume(existing)
        else ProfileRecoveryDecision.Fail(ProfileFailureClassifier.local(
            ProfileSetupFailureKind.PROFILE_VERIFICATION_FAILED, "Cyclone found an ambiguous or invalid partial Profile B identity."))
    }

    internal fun verifyCreated(
        createdUserId: Int,
        profileName: String,
        parentUserId: Int,
        usersAfterCreate: List<ProfileUserRecord>,
        secondaryUser: Boolean = false,
    ): ProfileSetupFailure? {
        if (createdUserId <= 0 || createdUserId == parentUserId) {
            return ProfileFailureClassifier.local(ProfileSetupFailureKind.PROFILE_VERIFICATION_FAILED, "Android returned an invalid Profile B user id.")
        }
        val exact = usersAfterCreate.singleOrNull { it.id == createdUserId && it.name == profileName }
        return if (exact != null && validOwned(exact, parentUserId, secondaryUser)) null
        else ProfileFailureClassifier.local(ProfileSetupFailureKind.PROFILE_VERIFICATION_FAILED, "Android did not re-list the exact managed Profile B after creation.")
    }

    internal fun validOwned(user: ProfileUserRecord, parent: Int, secondaryUser: Boolean = false): Boolean =
        user.id > 0 && user.id != parent && !user.partial &&
            (if (secondaryUser) !user.profile && user.parentId == null else user.managed && user.parentId == parent)
}

data class ProfileReadyEvidence(
    val user: ProfileUserRecord?,
    val parentUserId: Int,
    val runningUnlocked: Boolean,
    /** Every package Cyclone must verify in the target user, including Cyclone itself. */
    val selectedPackages: Set<String>,
    val installedPackages: Set<String>,
    val workspaceUserIdsByPackage: Map<String, Set<Int>>,
    val setupComplete: Boolean,
    val journalUserId: Int?,
    val secondaryUser: Boolean = false,
    /** Only ordinary user-selected target apps need Layer 2 workspace registrations. */
    val workspacePackages: Set<String> = selectedPackages,
)

object ProfileReadyVerifier {
    internal fun failure(evidence: ProfileReadyEvidence): ProfileSetupFailure? {
        val user = evidence.user ?: return ProfileFailureClassifier.local(ProfileSetupFailureKind.PROFILE_VERIFICATION_FAILED)
        if (!ProfileRecovery.validOwned(user, evidence.parentUserId, evidence.secondaryUser)) {
            return ProfileFailureClassifier.local(ProfileSetupFailureKind.PROFILE_VERIFICATION_FAILED)
        }
        if (!evidence.runningUnlocked) return ProfileFailureClassifier.local(ProfileSetupFailureKind.PROFILE_NOT_UNLOCKED)
        if (!evidence.installedPackages.containsAll(evidence.selectedPackages)) {
            return ProfileFailureClassifier.local(ProfileSetupFailureKind.PACKAGE_INSTALL_FAILED)
        }
        if (evidence.workspacePackages.any { evidence.workspaceUserIdsByPackage[it] != setOf(user.id) }) {
            return ProfileFailureClassifier.local(ProfileSetupFailureKind.PROFILE_VERIFICATION_FAILED)
        }
        if (!evidence.setupComplete || evidence.journalUserId != user.id) {
            return ProfileFailureClassifier.local(ProfileSetupFailureKind.PROFILE_VERIFICATION_FAILED)
        }
        return null
    }
}

object ProfileInstallResume {
    fun remaining(selected: Set<String>, verifiedInstalled: Set<String>): List<String> =
        selected.filterNot { it in verifiedInstalled }.sorted()
}

object ProfileSelectionVerifier {
    fun failure(selected: Set<String>, currentlyInstalled: Set<String>): ProfileSetupFailure? {
        val missing = selected.filterNot { it in currentlyInstalled }.sorted()
        return if (missing.isEmpty()) null else ProfileFailureClassifier.local(
            ProfileSetupFailureKind.PACKAGE_INSTALL_FAILED,
            "A selected app is no longer installed in Profile A: ${missing.first()}",
        )
    }
}

object SecondaryUserProvisioningPolicy {
    fun failure(
        appUserId: Int,
        reportedCurrentUserId: Int?,
        users: List<ProfileUserRecord>,
        maxUsersReported: Int?,
    ): ProfileSetupFailure? {
        val parent = users.singleOrNull { it.id == appUserId }
            ?: return ProfileFailureClassifier.local(ProfileSetupFailureKind.PROFILE_VERIFICATION_FAILED,
                "Android did not re-list the user running Cyclone.")
        if (reportedCurrentUserId != null && reportedCurrentUserId != appUserId) {
            return ProfileFailureClassifier.local(ProfileSetupFailureKind.PROFILE_VERIFICATION_FAILED,
                "Android's current user does not match the user running Cyclone.")
        }
        if (parent.profile || parent.partial || ProfileSetupParser.mainUserId(users) != appUserId) {
            return ProfileFailureClassifier.local(ProfileSetupFailureKind.PROFILE_VERIFICATION_FAILED,
                "Cyclone must create new profiles from your main Android user.")
        }
        val fullUsers = users.count { !it.profile && !it.partial }
        if (maxUsersReported != null && fullUsers >= maxUsersReported) {
            return ProfileFailureClassifier.local(ProfileSetupFailureKind.MAX_USERS_REACHED)
        }
        return null
    }
}

object ProfileCapabilityEvaluator {
    data class Result(
        val capabilities: ProfileProvisioningCapabilities,
        val failure: ProfileSetupFailure? = null,
    )

    fun evaluate(
        rootUsable: Boolean,
        shizukuAuthorized: Boolean,
        shizukuProfileProbeVerified: Boolean,
        appUserId: Int,
        reportedCurrentUserId: Int?,
        users: List<ProfileUserRecord>,
        maxUsersReported: Int?,
        managedUsersFeature: Boolean = true,
        addManagedProfileRestricted: Boolean = false,
        lowRamDevice: Boolean = false,
    ): Result {
        val parent = users.singleOrNull { it.id == appUserId }
        val canHost = parent != null && !parent.profile && !parent.partial && managedUsersFeature && !addManagedProfileRestricted && !lowRamDevice
        val capabilities = ProfileProvisioningCapabilities(
            rootUsable = rootUsable,
            shizukuAuthorized = shizukuAuthorized,
            shizukuProfileProbeVerified = shizukuProfileProbeVerified,
            parentUserId = parent?.id,
            parentCanHostManagedProfile = canHost,
            maxUsersReported = maxUsersReported,
            existingUserCount = users.size,
            existingManagedProfilesForParent = users.count { it.managed && it.parentId == appUserId && !it.partial },
            managedUsersFeature = managedUsersFeature,
            addManagedProfileRestricted = addManagedProfileRestricted,
            lowRamDevice = lowRamDevice,
        )
        val failure = when {
            !rootUsable && !(shizukuAuthorized && shizukuProfileProbeVerified) ->
                ProfileFailureClassifier.local(ProfileSetupFailureKind.ROOT_UNAVAILABLE)
            reportedCurrentUserId != null && reportedCurrentUserId != appUserId ->
                ProfileFailureClassifier.local(ProfileSetupFailureKind.PROFILE_VERIFICATION_FAILED,
                    "Android's current user does not match the user running Cyclone.")
            !managedUsersFeature || lowRamDevice ->
                ProfileFailureClassifier.local(ProfileSetupFailureKind.MANAGED_PROFILE_UNSUPPORTED,
                    "Android does not expose managed-profile support on this phone.")
            addManagedProfileRestricted ->
                ProfileFailureClassifier.local(ProfileSetupFailureKind.OEM_RESTRICTION,
                    "Android reports that adding a managed profile is restricted for Profile A.")
            parent == null -> ProfileFailureClassifier.local(ProfileSetupFailureKind.PROFILE_VERIFICATION_FAILED,
                "Android did not re-list the user running Cyclone.")
            parent.profile -> ProfileFailureClassifier.local(ProfileSetupFailureKind.MANAGED_PROFILE_UNSUPPORTED,
                "Cyclone is already running inside an Android profile; Profile B cannot be nested under it.")
            parent.partial -> ProfileFailureClassifier.local(ProfileSetupFailureKind.PROFILE_VERIFICATION_FAILED,
                "Android reports Profile A as a partial user.")
            else -> null
        }
        return Result(capabilities, failure)
    }
}
