package com.cyclone.mobile.ui.v32

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.runtime.background.TaskPhase
import com.cyclone.mobile.runtime.background.WorkspaceTaskUi
import com.cyclone.mobile.runtime.background.WorkspaceTasks
import com.cyclone.mobile.runtime.session.SessionKernel
import com.cyclone.mobile.runtime.workspaces.CycloneProfileRecord
import com.cyclone.mobile.runtime.workspaces.Layer2Workspaces
import com.cyclone.mobile.runtime.workspaces.ProfileRegistryStore
import com.cyclone.mobile.runtime.workspaces.ProfilePresentationPolicy
import com.cyclone.mobile.ui.overlay.OverlayChromeRuntime
import com.cyclone.mobile.runtime.workspaces.ProfileSetupRuntime
import com.cyclone.mobile.runtime.workspaces.Workspace
import com.cyclone.mobile.runtime.workspaces.WorkspaceState
import com.cyclone.mobile.ui.ProfileSetupPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ProfilesTab { ACTIVE, ALL, GROUPS, DELETED }

internal data class ProfileCluster(
    val key: String,
    val recordId: String?,
    val label: String,
    val androidUserId: Int,
    val ready: Boolean,
    val workspaces: List<Workspace>,
    val packages: Set<String>,
    val active: Boolean,
    val waiting: Boolean,
    val current: Boolean = false,
    val owner: Boolean = false,
)

private data class AppProfileGroup(
    val packageName: String,
    val profiles: List<ProfileCluster>,
) {
    val activeCount: Int get() = profiles.count { it.active }
}

internal fun buildProfileClusters(
    allWorkspaces: List<Workspace>,
    allRecords: List<CycloneProfileRecord>,
    waiting: Set<String>,
    activeWorkspaceId: String?,
    processUser: Int,
    ownerUser: Int?,
    verifiedCurrentUser: Int?,
    foregroundExecuting: Boolean,
): List<ProfileCluster> {
    // Plan 40 P1: profiles in Recently deleted are hidden everywhere but the trash.
    val trashedUsers = allRecords.filter { it.inTrash }.mapNotNull { it.androidUserId }.toSet()
    val records = allRecords.filterNot { it.inTrash }
    val workspaces = allWorkspaces.filterNot { it.androidUserId in trashedUsers }
    val recordByUser = records.mapNotNull { record -> record.androidUserId?.let { it to record } }.toMap()
    val workspacesByUser = workspaces.groupBy { it.androidUserId }
    val userIds = ProfilePresentationPolicy.visibleUsers(workspacesByUser.keys, recordByUser.keys,
        records.map { it.parentUserId }.toSet(), processUser, ownerUser)
    val clusters = userIds.map { userId ->
        val record = recordByUser[userId]
        val spaces = workspacesByUser[userId].orEmpty().sortedBy { it.label.lowercase() }
        val isWaiting = spaces.any { it.id in waiting }
        val isActive = ProfilePresentationPolicy.foregroundActive(userId, processUser, foregroundExecuting) || isWaiting || spaces.any { it.state == WorkspaceState.running || it.state == WorkspaceState.gated } ||
            spaces.any { it.id == activeWorkspaceId }
        ProfileCluster(
            key = record?.id ?: "android-user-$userId",
            recordId = record?.id,
            label = record?.label ?: if (userId == ownerUser || (ownerUser == null && userId == 0)) "Profile A" else "Phone profile",
            androidUserId = userId,
            ready = record?.ready ?: (userId == processUser || userId == ownerUser || spaces.isNotEmpty()),
            workspaces = spaces,
            packages = (record?.packages.orEmpty() + spaces.map { it.appPackage }).toSet(),
            active = isActive,
            waiting = isWaiting,
            current = ProfilePresentationPolicy.isCurrent(userId, verifiedCurrentUser),
            owner = userId == ownerUser,
        )
    }.toMutableList()

    records.filter { it.androidUserId == null }.forEach { record ->
        clusters += ProfileCluster(
            key = record.id,
            recordId = record.id,
            label = record.label,
            androidUserId = -1,
            ready = false,
            workspaces = emptyList(),
            packages = record.packages,
            active = false,
            waiting = false,
        )
    }
    return clusters.distinctBy { it.key }
}

private fun buildAppGroups(profiles: List<ProfileCluster>): List<AppProfileGroup> =
    profiles.flatMap { profile -> profile.packages.map { it to profile } }
        .groupBy({ it.first }, { it.second })
        .map { (pkg, members) -> AppProfileGroup(pkg, members.distinctBy { it.key }) }
        .sortedWith(compareByDescending<AppProfileGroup> { it.activeCount }.thenByDescending { it.profiles.size }.thenBy { it.packageName })

@Composable
fun CycloneProfilesPage(context: Context, refreshTick: Int, onAsk: () -> Unit) {
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(ProfilesTab.ALL) }
    var selectedProfileKey by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedGroupPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var waiting by remember { mutableStateOf(emptySet<String>()) }
    var busy by remember { mutableStateOf(false) }
    var switchMessage by remember { mutableStateOf("") }
    var setup by remember { mutableStateOf(false) }
    var workspaces by remember { mutableStateOf(emptyList<Workspace>()) }
    var records by remember { mutableStateOf(emptyList<CycloneProfileRecord>()) }
    var error by remember { mutableStateOf("") }
    // Plan 40 P1: Recently deleted, the automatic backups, and which dialog is open.
    var backups by remember { mutableStateOf(emptyList<com.cyclone.mobile.runtime.workspaces.ProfileBackup>()) }
    var confirm by remember { mutableStateOf<Pair<String, String>?>(null) }
    var looking by remember { mutableStateOf<String?>(null) }
    // Plan 40 P2: what the last switch carried here, and whether the last carry out of here arrived.
    var carried by remember { mutableStateOf<com.cyclone.mobile.runtime.workspaces.CarryReport?>(null) }
    var carryFailed by remember { mutableStateOf(false) }

    var ownerUser by remember { mutableStateOf<Int?>(null) }
    var verifiedCurrentUser by remember { mutableStateOf<Int?>(null) }
    val processUser = ProfileSetupRuntime.currentUserId()
    val overlayActivity by OverlayChromeRuntime.activity.collectAsState()
    val foregroundExecuting = remember(overlayActivity) { OverlayChromeRuntime.hasExecutingTask() }
    LaunchedEffect(refreshTick) {
        verifiedCurrentUser = null
        carried = com.cyclone.mobile.runtime.workspaces.ProfileCarry.lastReport(context)
        carryFailed = com.cyclone.mobile.runtime.workspaces.ProfileCarry.lastSentFailed(context)
        withContext(Dispatchers.IO) { runCatching { ProfileSetupRuntime.visibleProfileIdentity() } }
            .onSuccess { (owner, current) -> ownerUser = owner; verifiedCurrentUser = current }
    }

    val task by WorkspaceTasks.state.collectAsState()
    val profileSetup by ProfileSetupRuntime.state.collectAsState()
    val revision by Layer2Workspaces.engine.revision.collectAsState()

    fun refreshProfiles() {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    Layer2Workspaces.initialize(context)
                    Triple(
                        Layer2Workspaces.engine.snapshot(),
                        Layer2Workspaces.engine.queue().toSet(),
                        ProfileRegistryStore.records(context),
                    )
                }
            }
            loaded.onSuccess { (spaces, queued, saved) ->
                workspaces = spaces
                waiting = queued
                records = saved
                error = ""
            }.onFailure {
                error = "Profiles couldn't load. Open profile setup to repair."
            }
            backups = withContext(Dispatchers.IO) { com.cyclone.mobile.runtime.workspaces.ProfileBackups.list(context) }
        }
    }

    LaunchedEffect(refreshTick, revision, profileSetup.ready, setup) { refreshProfiles() }

    val clusters = remember(workspaces, records, waiting, task?.workspaceId, ownerUser, verifiedCurrentUser, foregroundExecuting) {
        buildProfileClusters(workspaces, records, waiting, task?.workspaceId, processUser, ownerUser, verifiedCurrentUser, foregroundExecuting)
    }
    val activeProfiles = clusters.filter { it.active }.sortedBy { it.label.lowercase() }
    val allProfiles = clusters.sortedWith(compareByDescending<ProfileCluster> { it.current }.thenByDescending { it.active }.thenBy { it.label.lowercase() })
    val appGroups = remember(clusters) { buildAppGroups(clusters) }
    val selectedProfile = clusters.firstOrNull { it.key == selectedProfileKey }

    BackHandler(selectedProfileKey != null || selectedGroupPackage != null) {
        if (selectedProfileKey != null) selectedProfileKey = null else selectedGroupPackage = null
    }

    fun openProfile(profile: ProfileCluster) {
        if (busy) return
        val openingMain = profile.owner
        val recordId = profile.recordId
        if (!profile.ready || (!openingMain && recordId == null)) {
            error = "Finish setting up ${profile.label} before opening it."
            setup = true
            return
        }
        busy = true
        switchMessage = "Checking profile access…"
        error = ""
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ProfileSetupRuntime.openProfile(context, if (openingMain) null else recordId) { message ->
                        scope.launch { switchMessage = message }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message?.take(160) ?: "Couldn't open ${profile.label}."
            } finally {
                busy = false
            }
        }
    }

    /** Runs a lifecycle step off the main thread, with its progress line, then reloads. */
    fun lifecycle(start: String, step: (onProgress: (String) -> Unit) -> Unit) {
        if (busy) return
        busy = true
        switchMessage = start
        error = ""
        scope.launch {
            try {
                withContext(Dispatchers.IO) { step { message -> scope.launch { switchMessage = message } } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message?.take(200) ?: "That didn't work. Nothing was changed."
            } finally {
                busy = false
                refreshProfiles()
            }
        }
    }
    val trash = remember(records) { com.cyclone.mobile.runtime.workspaces.ProfileTrash.order(records) }

    // The open dialog, if any: rename, or a confirmation ("remove:<id>", "delete:<id>", "empty", "backup:<folder>").
    looking?.let { id ->
        records.singleOrNull { it.id == id }?.let { record ->
            ProfileLookDialog(record, onSave = { label, emoji, color ->
                looking = null
                runCatching { com.cyclone.mobile.runtime.workspaces.ProfileRegistryStore.setLook(context, id, label, emoji, color) }
                    .onFailure { error = it.message.orEmpty() }
                refreshProfiles()
            }, onDismiss = { looking = null })
        }
    }
    confirm?.let { (kind, target) ->
        val label = records.singleOrNull { it.id == target }?.label ?: "this profile"
        when (kind) {
            "remove" -> ProfileConfirmDialog(ProfileLifecycleCopy.REMOVE_TITLE, ProfileLifecycleCopy.removeBody(label), "Remove",
                onConfirm = {
                    confirm = null
                    selectedProfileKey = null
                    lifecycle("Removing $label…") { com.cyclone.mobile.runtime.workspaces.ProfileLifecycle.remove(context, target) }
                }, onDismiss = { confirm = null })
            "delete" -> ProfileConfirmDialog(ProfileLifecycleCopy.DELETE_TITLE, ProfileLifecycleCopy.deleteBody(label), "Delete",
                onConfirm = {
                    confirm = null
                    lifecycle("Backing up $label…") { progress -> com.cyclone.mobile.runtime.workspaces.ProfileLifecycle.deleteNow(context, target, progress) }
                }, onDismiss = { confirm = null })
            "empty" -> ProfileConfirmDialog(ProfileLifecycleCopy.EMPTY_TITLE, ProfileLifecycleCopy.EMPTY_BODY, "Delete all",
                onConfirm = {
                    confirm = null
                    lifecycle("Emptying Recently deleted…") { progress -> com.cyclone.mobile.runtime.workspaces.ProfileLifecycle.emptyTrash(context, progress) }
                }, onDismiss = { confirm = null })
            "backup" -> ProfileConfirmDialog("Delete this backup?", "The backup is removed from the phone. This can't be undone.", "Delete",
                onConfirm = {
                    confirm = null
                    lifecycle("Deleting the backup…") { com.cyclone.mobile.runtime.workspaces.ProfileBackups.delete(context, target) }
                }, onDismiss = { confirm = null })
        }
    }

    fun manageProfile(profile: ProfileCluster) {
        val recordId = profile.recordId
        if (recordId != null) {
            runCatching { ProfileSetupRuntime.selectProfile(context, recordId) }
                .onFailure { error = it.message.orEmpty() }
        }
        setup = true
    }

    if (selectedProfile != null) {
        val exactTask = task?.takeIf { current -> selectedProfile.workspaces.any { it.id == current.workspaceId } }
        ProfileDetail429(
            context = context,
            profile = selectedProfile,
            task = exactTask,
            busy = busy,
            switchMessage = switchMessage,
            error = error,
            onBack = { selectedProfileKey = null },
            onOpenProfile = { openProfile(selectedProfile) },
            onManage = { manageProfile(selectedProfile) },
            canStartTask = ProfilePresentationPolicy.canStartTask(selectedProfile.androidUserId, processUser, verifiedCurrentUser, busy),
            onAsk = onAsk,
            onRename = selectedProfile.recordId?.let { id -> { looking = id } },
            // The main profile and the one you're in can't be removed; the runtime checks again with root.
            onRemove = selectedProfile.recordId?.takeIf { !selectedProfile.owner && !selectedProfile.current }?.let { id -> { confirm = "remove" to id } },
        )
        if (setup) ProfileSetupPage { setup = false; refreshProfiles() }
        return
    }

    selectedGroupPackage?.let { pkg ->
        val group = appGroups.firstOrNull { it.packageName == pkg }
        if (group != null) {
            AppGroupDetail429(
                context = context,
                group = group,
                onBack = { selectedGroupPackage = null },
                onOpenProfile = ::openProfile,
                onProfileDetail = { selectedProfileKey = it.key },
            )
            if (setup) ProfileSetupPage { setup = false; refreshProfiles() }
            return
        }
    }

    LazyColumn(
        contentPadding = cyclonePageInsets(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            CyclonePageHeader(
                title = "Profiles",
                subtitle = clusters.firstOrNull { it.current }?.let { "Current profile: ${it.label}" } ?: "Current profile not verified",
                trailing = {
                    FilledIconButton(onClick = { setup = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.Add, "Add profile", modifier = Modifier.size(22.dp))
                    }
                },
            )
        }

        item {
            CycloneLiquidTextAction(
                label = "New task in current profile",
                onClick = onAsk,
                enabled = ProfilePresentationPolicy.canStartTask(processUser, processUser, verifiedCurrentUser, busy),
                prominent = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item { CyclonePendingRequests() }
        item {
            CycloneSegmentedControl(
                listOf("Active (${activeProfiles.size})", "All profiles", "Groups") +
                    if (trash.isNotEmpty() || backups.isNotEmpty() || tab == ProfilesTab.DELETED) listOf("Deleted (${trash.size})") else emptyList(),
                tab.ordinal,
                { tab = ProfilesTab.entries[it] },
            )
        }

        if (tab == ProfilesTab.ALL && (carried != null || carryFailed)) item { ProfileCarryNote(carried, carryFailed) }

        if (activeProfiles.size > 1) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .62f),
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Rounded.Sync, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(
                            "${activeProfiles.size} profiles have work · Cyclone rotates safely between them",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        profileSetup.issue?.takeIf { !profileSetup.busy && !profileSetup.ready }?.let { issue ->
            item {
                CycloneSurface(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(issue.headline, style = MaterialTheme.typography.titleMedium)
                        Text(issue.reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (issue.retryUseful) {
                            Button(onClick = { setup = true }, modifier = Modifier.fillMaxWidth()) { Text(issue.action) }
                        } else {
                            Text(issue.action, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        if (busy) item { Text(switchMessage, style = MaterialTheme.typography.bodySmall) }
        if (error.isNotBlank()) item {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        when (tab) {
            ProfilesTab.ACTIVE -> {
                if (activeProfiles.isEmpty()) {
                    item {
                        QuietProfilesEmpty(
                            title = "Nothing active right now",
                            body = "Profiles doing work will appear here with their app, progress, and an Open profile action.",
                        )
                    }
                } else {
                    items(activeProfiles, key = { "active-${it.key}" }) { profile ->
                        val exactTask = task?.takeIf { current -> profile.workspaces.any { it.id == current.workspaceId } }
                        ActiveProfileCard429(
                            context = context,
                            profile = profile,
                            task = exactTask,
                            onOpenProfile = { openProfile(profile) },
                            onDetail = { selectedProfileKey = profile.key },
                        )
                    }
                }
            }

            ProfilesTab.ALL -> {
                if (allProfiles.isEmpty()) {
                    item {
                        QuietProfilesEmpty(
                            title = "Add your first profile",
                            body = "Create a separate phone space, name it, and choose the apps you want inside.",
                            action = { Button(onClick = { setup = true }) { Text("Add profile") } },
                        )
                    }
                } else {
                    items(allProfiles, key = { "all-${it.key}" }) { profile ->
                        ProfileIdentityCard429(
                            profile = profile,
                            onOpen = { selectedProfileKey = profile.key },
                        )
                    }
                }
            }

            ProfilesTab.DELETED -> {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(ProfileLifecycleCopy.TRASH_NOTE, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (trash.isNotEmpty()) CycloneLiquidTextAction("Delete all", { confirm = "empty" to "" }, enabled = !busy)
                    }
                }
                if (trash.isEmpty()) {
                    item { QuietProfilesEmpty(title = "Nothing recently deleted", body = "Profiles you remove wait here for ${com.cyclone.mobile.runtime.workspaces.ProfileTrash.TRASH_DAYS} days.") }
                }
                val now = System.currentTimeMillis()
                items(trash, key = { "trash-${it.id}" }) { record ->
                    ProfileTrashCard(record, now, busy,
                        onRestore = { lifecycle("Restoring ${record.label}…") { com.cyclone.mobile.runtime.workspaces.ProfileRegistryStore.restore(context, record.id) } },
                        onDelete = { confirm = "delete" to record.id })
                }
                if (backups.isNotEmpty()) {
                    item { CycloneSectionTitle("Backups") }
                    items(backups, key = { "backup-${it.folder}" }) { backup ->
                        ProfileBackupRow(context, backup, busy) { confirm = "backup" to backup.folder }
                    }
                }
            }

            ProfilesTab.GROUPS -> {
                if (appGroups.isEmpty()) {
                    item {
                        QuietProfilesEmpty(
                            title = "No app groups yet",
                            body = "Once profiles contain apps, Cyclone groups the same app across profiles here.",
                        )
                    }
                } else {
                    items(appGroups, key = { "group-${it.packageName}" }) { group ->
                        AppGroupCard429(
                            context = context,
                            group = group,
                            onOpen = { selectedGroupPackage = group.packageName },
                        )
                    }
                }
            }
        }
    }

    if (setup) ProfileSetupPage { setup = false; refreshProfiles() }
}

@Composable
private fun ActiveProfileCard429(
    context: Context,
    profile: ProfileCluster,
    task: WorkspaceTaskUi?,
    onOpenProfile: () -> Unit,
    onDetail: () -> Unit,
) {
    val activeWorkspace = profile.workspaces.firstOrNull { it.id == task?.workspaceId }
        ?: profile.workspaces.firstOrNull { it.state == WorkspaceState.running || it.state == WorkspaceState.gated }
        ?: profile.workspaces.firstOrNull()
    val packageName = task?.packageName ?: activeWorkspace?.appPackage ?: profile.packages.firstOrNull()
    val app = packageName?.let { appLabel(context, it) } ?: "Profile"
    val status = when {
        task?.phase == TaskPhase.REVIEW || profile.workspaces.any { it.state == WorkspaceState.gated } -> "Needs you"
        profile.waiting && task == null -> "Queued"
        else -> "Live"
    }

    Card(
        onClick = onDetail,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    CycloneAppIcon(packageName, Modifier.padding(7.dp).size(36.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "$app · ${profile.label}",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        task?.subtitle?.takeIf { it.isNotBlank() }
                            ?: if (profile.waiting) "Waiting for its next turn" else "Working in this profile",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ProfileStatePill429(status)
            }

            if (task?.working == true || status == "Live") {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp))
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (task != null) {
                    FilledTonalButton(
                        onClick = { UiTask(task).open(context) },
                        modifier = Modifier.weight(1f),
                    ) { Text("View progress") }
                }
                Button(onClick = onOpenProfile, modifier = Modifier.weight(1f)) { Text("Open profile") }
            }
        }
    }
}

@Composable
private fun ProfileIdentityCard429(profile: ProfileCluster, onOpen: () -> Unit) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Person, null, Modifier.size(23.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(profile.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${profile.packages.size} ${if (profile.packages.size == 1) "app" else "apps"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ProfileStatePill429(if (profile.current) "Current" else if (!profile.ready) "Setup" else if (profile.active) "Live" else "Ready")
                Icon(Icons.Rounded.ChevronRight, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .65f))
            }
            if (profile.packages.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    profile.packages.take(5).forEach { pkg -> CycloneAppIcon(pkg, Modifier.size(27.dp)) }
                    if (profile.packages.size > 5) {
                        Text("+${profile.packages.size - 5}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppGroupCard429(context: Context, group: AppProfileGroup, onOpen: () -> Unit) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                CycloneAppIcon(group.packageName, Modifier.padding(7.dp).size(36.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(appLabel(context, group.packageName), style = MaterialTheme.typography.titleMedium)
                Text(
                    "${group.profiles.size} ${if (group.profiles.size == 1) "profile" else "profiles"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (group.activeCount > 0) ProfileStatePill429("${group.activeCount} live")
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .65f))
        }
    }
}

@Composable
private fun AppGroupDetail429(
    context: Context,
    group: AppProfileGroup,
    onBack: () -> Unit,
    onOpenProfile: (ProfileCluster) -> Unit,
    onProfileDetail: (ProfileCluster) -> Unit,
) {
    LazyColumn(
        contentPadding = cyclonePageInsets(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { CycloneBackRow("Groups", onBack) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CycloneAppIcon(group.packageName, Modifier.size(46.dp))
                Column(Modifier.weight(1f)) {
                    Text(appLabel(context, group.packageName), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "${group.profiles.size} ${if (group.profiles.size == 1) "profile" else "profiles"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(group.profiles.sortedBy { it.label.lowercase() }, key = { "group-profile-${it.key}" }) { profile ->
            Card(
                onClick = { onProfileDetail(profile) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Person, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(profile.label, style = MaterialTheme.typography.titleSmall)
                        Text(if (profile.active) "Working now" else "Ready", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(enabled = profile.ready, onClick = { onOpenProfile(profile) }) { Text("Open") }
                }
            }
        }
    }
}

@Composable
private fun ProfileDetail429(
    context: Context,
    profile: ProfileCluster,
    task: WorkspaceTaskUi?,
    busy: Boolean,
    switchMessage: String,
    error: String,
    onBack: () -> Unit,
    onOpenProfile: () -> Unit,
    onManage: () -> Unit,
    canStartTask: Boolean,
    onAsk: () -> Unit,
    onRename: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var localMessage by remember { mutableStateOf("") }
    LazyColumn(
        contentPadding = cyclonePageInsets(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { CycloneBackRow("Profiles", onBack) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Person, null, Modifier.size(25.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(profile.label, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "${profile.packages.size} ${if (profile.packages.size == 1) "app" else "apps"} in this phone space",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ProfileStatePill429(if (profile.current) "Current" else if (!profile.ready) "Setup" else if (profile.active) "Live" else "Ready")
            }
        }

        if (task != null) item { CycloneTaskProgress(task) }

        if (busy) item { Text(switchMessage, style = MaterialTheme.typography.bodySmall) }

        item {
            CycloneLiquidTextAction(
                label = "Open profile",
                onClick = onOpenProfile,
                enabled = profile.ready && !busy,
                prominent = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item {
            CycloneLiquidTextAction(
                label = "New task in ${profile.label}",
                onClick = onAsk,
                enabled = canStartTask,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!canStartTask) Text("Open this profile first to start a task here.", style = MaterialTheme.typography.bodySmall)
        }
        // Plan 40 P1: rename (name, emoji, colour) and remove, which goes to Recently deleted first.
        if (onRename != null || onRemove != null) item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onRename != null) CycloneLiquidTextAction("Rename", onRename, Modifier.weight(1f), enabled = !busy)
                if (onRemove != null) CycloneLiquidTextAction("Remove profile", onRemove, Modifier.weight(1f), enabled = !busy)
            }
        }
        item { CycloneSectionTitle("Apps") }
        if (profile.workspaces.isEmpty() && profile.packages.isEmpty()) {
            item { Text(if (profile.owner) "Your installed apps are available through Ask Cyclone." else "Choose apps to finish this profile.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(profile.workspaces, key = { "profile-app-${it.id}" }) { workspace ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Row(
                    Modifier.padding(13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CycloneAppIcon(workspace.appPackage, Modifier.size(40.dp))
                    Column(Modifier.weight(1f)) {
                        Text(appLabel(context, workspace.appPackage), style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (workspace.state == WorkspaceState.running) "Working now" else "Ready",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = {
                        scope.launch {
                            localMessage = "Opening ${appLabel(context, workspace.appPackage)}…"
                            val result = withContext(Dispatchers.IO) { SessionKernel.switchWorkspace(context, workspace.id) }
                            localMessage = if (result.ok) "Opened ${appLabel(context, workspace.appPackage)}" else "Couldn't open that app in ${profile.label}."
                        }
                    }) { Text("Open") }
                }
            }
        }
        if (profile.workspaces.isEmpty()) {
            items(profile.packages.toList().sorted(), key = { "profile-package-$it" }) { pkg ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CycloneAppIcon(pkg, Modifier.size(40.dp))
                        Text(appLabel(context, pkg), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Text("Setup", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        item {
            CycloneLiquidTextAction(
                label = "Manage apps & profile",
                onClick = onManage,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (localMessage.isNotBlank()) item { Text(localMessage, style = MaterialTheme.typography.bodySmall) }
        if (error.isNotBlank()) item { Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun ProfileStatePill429(label: String) {
    val positive = label.equals("Ready", true) || label.equals("Live", true) || label.endsWith("live", true)
    val needs = label.equals("Needs you", true) || label.equals("Setup", true)
    val container = when {
        positive -> MaterialTheme.colorScheme.secondaryContainer
        needs -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when {
        positive -> MaterialTheme.colorScheme.onSecondaryContainer
        needs -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(999.dp), color = container, contentColor = content) {
        Row(
            Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (positive) Surface(Modifier.size(6.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondary) {}
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun QuietProfilesEmpty(
    title: String,
    body: String,
    action: (@Composable () -> Unit)? = null,
) {
    CycloneSimpleCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Apps, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        action?.invoke()
    }
}
