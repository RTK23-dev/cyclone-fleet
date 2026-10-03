package com.cyclone.mobile.runtime.workspaces

enum class ProfileSetupOperation {
    VERIFY_ROOT,
    CURRENT_USER,
    LIST_USERS,
    GET_MAX_USERS,
    CREATE_MANAGED_PROFILE,
    CREATE_SECONDARY_USER,
    SWITCH_USER,
    START_PROFILE,
    PROFILE_STATE,
    INSTALL_EXISTING_PACKAGE,
    LIST_PROFILE_PACKAGE,
    MARK_SETUP_COMPLETE,
    READ_SETUP_COMPLETE,
    // Plan 40 P1: the profile lifecycle. Only ProfileLifecycle uses these, after its ownership guards.
    STOP_USER,
    REMOVE_USER,
    MEASURE_APP_DATA,
    BACKUP_APP_DATA,
    OWN_BACKUP,
    LABEL_BACKUP,
    // Plan 43 T4: the profile app manager (ProfileApps only, after its ownership guards).
    LIST_PROFILE_APPS,
    UNINSTALL_FOR_PROFILE,
}

/**
 * A closed profile-provisioning command. There is deliberately no public constructor that accepts
 * executable or shell text; every instance comes from one of the fixed factories below.
 */
class ProfileSetupCommand private constructor(
    val operation: ProfileSetupOperation,
    private val argv: List<String>,
) {
    internal fun tokens(): List<String> = argv.toList()

    companion object {
        internal fun fixed(operation: ProfileSetupOperation, vararg argv: String): ProfileSetupCommand =
            ProfileSetupCommand(operation, argv.toList())
    }
}

/** Fixed profile provisioning verbs only. No arbitrary root/shell surface. */
object ProfileSetupPlan {
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    private val namePattern = Regex("Cyclone_[a-f0-9]{16}")
    private val shellTokenPattern = Regex("[A-Za-z0-9_./:-]+")

    fun validProfileName(name: String): Boolean = name.matches(namePattern)

    /** A backup folder inside Cyclone's own files: `…/com.cyclone.mobile/files/profile-backups/Cyclone_<hex>-<ms>`. */
    private val backupDirPattern = Regex("(/data/user/[0-9]+|/data/data)/com\\.cyclone\\.mobile/files/profile-backups/Cyclone_[a-f0-9]{16}-[0-9]{10,14}")
    fun validBackupDir(path: String): Boolean = path.matches(backupDirPattern)
    private val dataRootPattern = Regex("/data/(user|user_de)/([0-9]+)")
    fun validPackageName(packageName: String): Boolean = packageName.matches(packagePattern)

    fun verifyRoot(): ProfileSetupCommand = ProfileSetupCommand.fixed(
        ProfileSetupOperation.VERIFY_ROOT,
        "/system/bin/id",
    )

    fun currentUser(): ProfileSetupCommand = ProfileSetupCommand.fixed(
        ProfileSetupOperation.CURRENT_USER,
        "/system/bin/am", "get-current-user",
    )

    fun listUsers(): ProfileSetupCommand = ProfileSetupCommand.fixed(
        ProfileSetupOperation.LIST_USERS,
        "/system/bin/cmd", "user", "list", "--all", "--verbose",
    )

    fun getMaxUsers(): ProfileSetupCommand = ProfileSetupCommand.fixed(
        ProfileSetupOperation.GET_MAX_USERS,
        "/system/bin/pm", "get-max-users",
    )

    fun createManagedProfile(parentUserId: Int, name: String): ProfileSetupCommand {
        require(parentUserId >= 0 && validProfileName(name))
        return ProfileSetupCommand.fixed(
            ProfileSetupOperation.CREATE_MANAGED_PROFILE,
            "/system/bin/pm", "create-user", "--profileOf", parentUserId.toString(), "--managed", name,
        )
    }

    fun createSecondaryUser(name: String): ProfileSetupCommand {
        require(validProfileName(name))
        return ProfileSetupCommand.fixed(ProfileSetupOperation.CREATE_SECONDARY_USER,
            "/system/bin/pm", "create-user", name)
    }

    fun switchUser(userId: Int): ProfileSetupCommand {
        require(userId >= 0)
        return ProfileSetupCommand.fixed(ProfileSetupOperation.SWITCH_USER,
            "/system/bin/am", "switch-user", userId.toString())
    }

    fun startProfile(userId: Int): ProfileSetupCommand {
        require(userId > 0)
        return ProfileSetupCommand.fixed(
            ProfileSetupOperation.START_PROFILE,
            "/system/bin/am", "start-user", "-w", userId.toString(),
        )
    }

    fun profileState(userId: Int): ProfileSetupCommand {
        require(userId > 0)
        return ProfileSetupCommand.fixed(
            ProfileSetupOperation.PROFILE_STATE,
            "/system/bin/am", "get-started-user-state", userId.toString(),
        )
    }

    fun installExisting(userId: Int, packageName: String): ProfileSetupCommand {
        require(userId > 0 && validPackageName(packageName))
        return ProfileSetupCommand.fixed(
            ProfileSetupOperation.INSTALL_EXISTING_PACKAGE,
            "/system/bin/cmd", "package", "install-existing", "--user", userId.toString(), packageName,
        )
    }

    /** Plan 43 T4: the apps a profile has (third-party packages, for that Android user only). */
    fun listProfileApps(userId: Int): ProfileSetupCommand {
        require(userId >= 0)
        return ProfileSetupCommand.fixed(ProfileSetupOperation.LIST_PROFILE_APPS, "/system/bin/pm", "list", "packages", "-3", "--user", userId.toString())
    }

    /** Plan 43 T4: removes an app from one Cyclone profile only; the app stays in every other profile. */
    fun uninstallForProfile(userId: Int, packageName: String): ProfileSetupCommand {
        require(userId > 0 && validPackageName(packageName) && packageName != "com.cyclone.mobile")
        return ProfileSetupCommand.fixed(ProfileSetupOperation.UNINSTALL_FOR_PROFILE, "/system/bin/pm", "uninstall", "--user", userId.toString(), packageName)
    }

    fun listProfilePackage(userId: Int, packageName: String): ProfileSetupCommand {
        require(userId > 0 && validPackageName(packageName))
        return ProfileSetupCommand.fixed(
            ProfileSetupOperation.LIST_PROFILE_PACKAGE,
            "/system/bin/pm", "list", "packages", "--user", userId.toString(), packageName,
        )
    }

    fun markSetupComplete(userId: Int): ProfileSetupCommand {
        require(userId > 0)
        return ProfileSetupCommand.fixed(
            ProfileSetupOperation.MARK_SETUP_COMPLETE,
            "/system/bin/settings", "--user", userId.toString(), "put", "secure", "user_setup_complete", "1",
        )
    }

    fun readSetupComplete(userId: Int): ProfileSetupCommand {
        require(userId > 0)
        return ProfileSetupCommand.fixed(
            ProfileSetupOperation.READ_SETUP_COMPLETE,
            "/system/bin/settings", "--user", userId.toString(), "get", "secure", "user_setup_complete",
        )
    }

    /** Stops a profile Cyclone made (it keeps all its data): the first step of Remove, fully undoable. */
    fun stopUser(userId: Int): ProfileSetupCommand {
        require(userId > 0)
        return ProfileSetupCommand.fixed(ProfileSetupOperation.STOP_USER, "/system/bin/am", "stop-user", "-w", userId.toString())
    }

    /** Deletes a profile for good. Never user 0; ProfileLifecycle also refuses the main and the current profile. */
    fun removeUser(userId: Int): ProfileSetupCommand {
        require(userId > 0)
        return ProfileSetupCommand.fixed(ProfileSetupOperation.REMOVE_USER, "/system/bin/pm", "remove-user", userId.toString())
    }

    /** How big one app's data is in a profile, in KB, before a backup decides what fits. */
    fun measureAppData(userId: Int, packageName: String): ProfileSetupCommand {
        require(userId > 0 && validPackageName(packageName))
        return ProfileSetupCommand.fixed(ProfileSetupOperation.MEASURE_APP_DATA, "/system/bin/du", "-sk", "/data/user/$userId/$packageName")
    }

    /** One app's data (credential storage `user`, or device storage `user_de`) archived into a backup folder. */
    fun backupAppData(userId: Int, packageName: String, deviceStorage: Boolean, backupDir: String): ProfileSetupCommand {
        require(userId > 0 && validPackageName(packageName) && validBackupDir(backupDir))
        val root = if (deviceStorage) "user_de" else "user"
        val kind = if (deviceStorage) "de" else "ce"
        return ProfileSetupCommand.fixed(ProfileSetupOperation.BACKUP_APP_DATA,
            "/system/bin/tar", "-cf", "$backupDir/$kind-$packageName.tar", "-C", "/data/$root/$userId", packageName)
    }

    /** Hands a finished backup folder to Cyclone (owner and label), so it can list and delete it without root. */
    fun ownBackup(backupDir: String, uid: Int): ProfileSetupCommand {
        require(validBackupDir(backupDir) && uid >= 10_000)
        return ProfileSetupCommand.fixed(ProfileSetupOperation.OWN_BACKUP, "/system/bin/chown", "-R", "$uid:$uid", backupDir)
    }

    fun labelBackup(backupDir: String): ProfileSetupCommand {
        require(validBackupDir(backupDir))
        return ProfileSetupCommand.fixed(ProfileSetupOperation.LABEL_BACKUP, "/system/bin/restorecon", "-R", backupDir)
    }

    internal fun shell(command: ProfileSetupCommand): String {
        val tokens = command.tokens()
        check(tokens.isNotEmpty() && tokens.all { it.matches(shellTokenPattern) })
        check(isExpectedShape(command.operation, tokens))
        return tokens.joinToString(" ")
    }

    /** Test seam proving that a caller cannot smuggle arbitrary shell data into the executor. */
    internal fun acceptsOnlyTyped(command: ProfileSetupCommand): Boolean = runCatching { shell(command) }.isSuccess

    private fun isExpectedShape(operation: ProfileSetupOperation, tokens: List<String>): Boolean = when (operation) {
        ProfileSetupOperation.VERIFY_ROOT -> tokens == listOf("/system/bin/id")
        ProfileSetupOperation.CURRENT_USER -> tokens == listOf("/system/bin/am", "get-current-user")
        ProfileSetupOperation.LIST_USERS -> tokens == listOf("/system/bin/cmd", "user", "list", "--all", "--verbose")
        ProfileSetupOperation.GET_MAX_USERS -> tokens == listOf("/system/bin/pm", "get-max-users")
        ProfileSetupOperation.CREATE_MANAGED_PROFILE -> tokens.size == 6 && tokens[0] == "/system/bin/pm" &&
            tokens[1] == "create-user" && tokens[2] == "--profileOf" && tokens[3].toIntOrNull()?.let { it >= 0 } == true &&
            tokens[4] == "--managed" && validProfileName(tokens[5])
        ProfileSetupOperation.CREATE_SECONDARY_USER -> tokens.size == 3 &&
            tokens.take(2) == listOf("/system/bin/pm", "create-user") && validProfileName(tokens[2])
        ProfileSetupOperation.SWITCH_USER -> tokens.size == 3 &&
            tokens.take(2) == listOf("/system/bin/am", "switch-user") && tokens[2].toIntOrNull()?.let { it >= 0 } == true
        ProfileSetupOperation.START_PROFILE -> tokens.size == 4 && tokens[0] == "/system/bin/am" &&
            tokens[1] == "start-user" && tokens[2] == "-w" && tokens[3].toIntOrNull()?.let { it > 0 } == true
        ProfileSetupOperation.PROFILE_STATE -> tokens.size == 3 && tokens[0] == "/system/bin/am" &&
            tokens[1] == "get-started-user-state" && tokens[2].toIntOrNull()?.let { it > 0 } == true
        ProfileSetupOperation.INSTALL_EXISTING_PACKAGE -> tokens.size == 6 && tokens[0] == "/system/bin/cmd" &&
            tokens[1] == "package" && tokens[2] == "install-existing" && tokens[3] == "--user" &&
            tokens[4].toIntOrNull()?.let { it > 0 } == true && validPackageName(tokens[5])
        ProfileSetupOperation.LIST_PROFILE_APPS -> tokens.size == 6 && tokens.take(4) == listOf("/system/bin/pm", "list", "packages", "-3") &&
            tokens[4] == "--user" && tokens[5].toIntOrNull()?.let { it >= 0 } == true
        ProfileSetupOperation.UNINSTALL_FOR_PROFILE -> tokens.size == 5 && tokens.take(3) == listOf("/system/bin/pm", "uninstall", "--user") &&
            tokens[3].toIntOrNull()?.let { it > 0 } == true && validPackageName(tokens[4]) && tokens[4] != "com.cyclone.mobile"
        ProfileSetupOperation.LIST_PROFILE_PACKAGE -> tokens.size == 6 && tokens[0] == "/system/bin/pm" &&
            tokens[1] == "list" && tokens[2] == "packages" && tokens[3] == "--user" &&
            tokens[4].toIntOrNull()?.let { it > 0 } == true && validPackageName(tokens[5])
        ProfileSetupOperation.MARK_SETUP_COMPLETE -> tokens.size == 7 && tokens[0] == "/system/bin/settings" &&
            tokens[1] == "--user" && tokens[2].toIntOrNull()?.let { it > 0 } == true &&
            tokens.drop(3) == listOf("put", "secure", "user_setup_complete", "1")
        ProfileSetupOperation.READ_SETUP_COMPLETE -> tokens.size == 6 && tokens[0] == "/system/bin/settings" &&
            tokens[1] == "--user" && tokens[2].toIntOrNull()?.let { it > 0 } == true &&
            tokens.drop(3) == listOf("get", "secure", "user_setup_complete")
        ProfileSetupOperation.STOP_USER -> tokens.size == 4 && tokens.take(3) == listOf("/system/bin/am", "stop-user", "-w") &&
            tokens[3].toIntOrNull()?.let { it > 0 } == true
        ProfileSetupOperation.REMOVE_USER -> tokens.size == 3 && tokens.take(2) == listOf("/system/bin/pm", "remove-user") &&
            tokens[2].toIntOrNull()?.let { it > 0 } == true
        ProfileSetupOperation.MEASURE_APP_DATA -> tokens.size == 3 && tokens.take(2) == listOf("/system/bin/du", "-sk") &&
            Regex("/data/user/([1-9][0-9]*)/(.+)").matchEntire(tokens[2])?.groupValues?.get(2)?.let(::validPackageName) == true
        ProfileSetupOperation.BACKUP_APP_DATA -> tokens.size == 6 && tokens.take(2) == listOf("/system/bin/tar", "-cf") && tokens[3] == "-C" &&
            validPackageName(tokens[5]) &&
            dataRootPattern.matchEntire(tokens[4])?.groupValues?.get(2)?.toIntOrNull()?.let { it > 0 } == true &&
            tokens[2].substringBeforeLast('/').let(::validBackupDir) &&
            tokens[2].substringAfterLast('/') == (if (tokens[4].startsWith("/data/user_de/")) "de-" else "ce-") + tokens[5] + ".tar"
        ProfileSetupOperation.OWN_BACKUP -> tokens.size == 4 && tokens.take(2) == listOf("/system/bin/chown", "-R") &&
            Regex("([0-9]{5,}):\\1").matches(tokens[2]) && validBackupDir(tokens[3])
        ProfileSetupOperation.LABEL_BACKUP -> tokens.size == 3 && tokens.take(2) == listOf("/system/bin/restorecon", "-R") &&
            validBackupDir(tokens[2])
    }
}

data class ProfileUserRecord(
    val id: Int,
    val name: String,
    val profile: Boolean,
    val managed: Boolean,
    val parentId: Int?,
    val running: Boolean,
    val partial: Boolean,
    val userType: String = "",
)

object ProfileSetupParser {
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

    internal fun users(output: String): List<ProfileUserRecord> = output.lineSequence().mapNotNull { line ->
        val id = Regex("\\bid=([0-9]+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
        val name = Regex("\\bname=(.*?),\\s*type=").find(line)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
        val type = Regex("\\btype=([^,]+)").find(line)?.groupValues?.get(1)?.trim().orEmpty()
        val flags = Regex("\\bflags=([^\\s(]+)").find(line)?.groupValues?.get(1)?.split('|').orEmpty()
        val parentId = Regex("\\(parentId=([0-9]+)\\)").find(line)?.groupValues?.get(1)?.toIntOrNull()
        ProfileUserRecord(
            id = id,
            name = name,
            profile = flags.any { it.equals("PROFILE", true) } || type.contains(".profile.", ignoreCase = true),
            managed = type.endsWith("profile.MANAGED", ignoreCase = true) || type.equals("profile.MANAGED", ignoreCase = true),
            parentId = parentId,
            running = line.contains("(running)", ignoreCase = true),
            partial = line.contains("(partial)", ignoreCase = true),
            userType = type,
        )
    }.toList()

    fun mainUserId(users: List<ProfileUserRecord>): Int? {
        val system = users.filter { !it.profile && !it.partial && it.userType.endsWith("full.SYSTEM", ignoreCase = true) }
        if (system.size == 1) return system.single().id
        return users.singleOrNull { it.id == 0 && !it.profile && !it.partial }?.id
    }

    fun currentUserId(output: String): Int? = output.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.matches(Regex("[0-9]+")) }
        ?.toIntOrNull()

    fun maxUsers(output: String): Int? = Regex("(?i)maximum supported users:\\s*([0-9]+)")
        .find(output)?.groupValues?.get(1)?.toIntOrNull()

    fun createdUser(output: String): Int? = Regex("(?i)success:\\s*created user id\\s+([0-9]+)")
        .find(output)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

    fun packages(output: String): Set<String> = output.lineSequence()
        .filter { it.startsWith("package:") }
        .map { it.removePrefix("package:").trim() }
        .filter { it.matches(packagePattern) }
        .toSet()

    internal fun ownedUser(users: List<ProfileUserRecord>, name: String, parentUserId: Int): ProfileUserRecord? {
        require(ProfileSetupPlan.validProfileName(name))
        return users.singleOrNull {
            it.id > 0 && it.id != parentUserId && it.name == name && it.managed && it.parentId == parentUserId && !it.partial
        }
    }
}
