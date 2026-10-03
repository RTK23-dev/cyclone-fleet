package com.cyclone.mobile.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.ImageView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.runtime.session.SessionKernel
import com.cyclone.mobile.runtime.workspaces.CycloneProfileRecord
import com.cyclone.mobile.runtime.workspaces.Layer2Workspaces
import com.cyclone.mobile.runtime.workspaces.ProfileApp
import com.cyclone.mobile.runtime.workspaces.ProfileRegistryStore
import com.cyclone.mobile.runtime.workspaces.ProfileSetupRuntime
import com.cyclone.mobile.runtime.workspaces.RootProbe
import com.cyclone.mobile.runtime.workspaces.RootStatus
import com.cyclone.mobile.runtime.workspaces.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PROFILE_SETUP_PREFS = "cyclone_profile_setup"

@Composable
internal fun ProfileSetup429(onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val progress by ProfileSetupRuntime.state.collectAsState()
    val initialRecord = remember {
        ProfileRegistryStore.records(context).firstOrNull { it.androidUserId == ProfileSetupRuntime.existingUser(context) }
    }
    var page by rememberSaveable { mutableIntStateOf(if (ProfileSetupRuntime.existingUser(context) != null) 3 else 0) }
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf(emptyList<ProfileApp>()) }
    var selected by remember { mutableStateOf(ProfileSetupRuntime.selectedPackages(context)) }
    var query by rememberSaveable { mutableStateOf("") }
    var registered by remember { mutableStateOf(emptyList<Workspace>()) }
    var records by remember { mutableStateOf(ProfileRegistryStore.records(context)) }
    var onMainProfile by remember { mutableStateOf<Boolean?>(null) }
    var profileName by rememberSaveable {
        mutableStateOf(initialRecord?.label ?: suggestedProfileName(ProfileRegistryStore.records(context).size))
    }
    var renaming by rememberSaveable { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                runCatching {
                    Layer2Workspaces.initialize(context)
                    Layer2Workspaces.engine.snapshot()
                }.getOrDefault(emptyList())
            }
            registered = snapshot
            records = ProfileRegistryStore.records(context)
            val current = records.firstOrNull { it.androidUserId == ProfileSetupRuntime.existingUser(context) }
            if (current != null && !renaming) profileName = current.label
        }
    }

    fun persistFriendlyName(): Boolean {
        return runCatching {
            val clean = ProfileRegistryStore.cleanLabel(profileName)
            val setupPrefs = context.getSharedPreferences(PROFILE_SETUP_PREFS, Context.MODE_PRIVATE)
            val currentId = setupPrefs.getString("name", null)
            val saved = ProfileRegistryStore.records(context)
            check(saved.none { it.id != currentId && it.label.equals(clean, ignoreCase = true) }) {
                "Another profile already uses that name."
            }
            check(setupPrefs.edit().putString(ProfileRegistryStore.JOURNAL_DISPLAY_LABEL, clean).commit()) {
                "Couldn't save the profile name."
            }
            if (currentId != null && saved.any { it.id == currentId }) {
                ProfileRegistryStore.rename(context, currentId, clean)
            }
            profileName = clean
            message = ""
            true
        }.getOrElse {
            message = it.message ?: "Choose another profile name."
            false
        }
    }

    fun chooseApps() {
        if (!persistFriendlyName()) return
        checking = true
        message = "Checking your phone…"
        scope.launch {
            val root = withContext(Dispatchers.IO) { RootProbe.check() }
            if (root == RootStatus.ROOTED) {
                withContext(Dispatchers.IO) { ProfileSetupRuntime.refreshExisting(context) }
                apps = withContext(Dispatchers.IO) { ProfileSetupRuntime.apps(context) }
                selected = selected.intersect(apps.map { it.packageName }.toSet())
                page = 1
                message = ""
            } else {
                message = "Cyclone needs root access to create another phone profile. Allow the root request, then try again."
            }
            checking = false
        }
    }

    fun openWholeProfile(record: CycloneProfileRecord) {
        scope.launch {
            message = "Opening ${record.label}…"
            message = withContext(Dispatchers.IO) {
                runCatching {
                    ProfileSetupRuntime.openProfile(context, record.id)
                    "Opened ${record.label}"
                }.getOrElse { it.message ?: "Couldn't open ${record.label}." }
            }
        }
    }

    fun openWorkspace(workspace: Workspace) {
        if (CycloneAccessibilityService.instance == null) {
            message = "Allow phone control so Cyclone can open the right app in this profile."
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        scope.launch {
            message = "Opening ${workspace.label}…"
            val result = withContext(Dispatchers.IO) { SessionKernel.switchWorkspace(context, workspace.id) }
            message = if (result.ok) "Opened ${workspace.label}" else "Couldn't safely open that app in this profile."
        }
    }

    LaunchedEffect(progress.ready, progress.userId) {
        if (progress.ready) {
            persistFriendlyName()
            page = 3
            refresh()
        }
    }
    LaunchedEffect(Unit) {
        onMainProfile = withContext(Dispatchers.IO) {
            runCatching { ProfileSetupRuntime.currentUserId() == ProfileSetupRuntime.profileAUserId() }.getOrNull()
        }
        refresh()
    }

    Dialog(
        onDismissRequest = {
            if (progress.busy) ProfileSetupRuntime.stop()
            onClose()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                ProfileSetupTopBar(
                    title = when (page) {
                        0 -> "New profile"
                        1 -> "Choose apps"
                        2 -> "Review profile"
                        else -> profileName
                    },
                    onBack = {
                        if (progress.busy) {
                            ProfileSetupRuntime.stop()
                        } else if (page == 1 || page == 2) {
                            page -= 1
                            message = ""
                        } else {
                            onClose()
                        }
                    },
                    onClose = {
                        if (progress.busy) ProfileSetupRuntime.stop()
                        onClose()
                    },
                )

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (progress.busy) {
                        CreatingProfileState(profileName, progress.message, progress.completed, progress.total) {
                            ProfileSetupRuntime.stop()
                        }
                    } else {
                        when (page) {
                            0 -> NameProfileStep(
                                name = profileName,
                                onName = { profileName = it },
                                onSuggestion = { profileName = it },
                                onContinue = ::chooseApps,
                                onMainProfile = onMainProfile,
                                onReturnMain = {
                                    scope.launch {
                                        message = withContext(Dispatchers.IO) {
                                            runCatching {
                                                ProfileSetupRuntime.openProfile(context, null)
                                                "Returned to Main"
                                            }.getOrElse { it.message ?: "Couldn't return to Main." }
                                        }
                                    }
                                },
                            )

                            1 -> ChooseAppsStep(
                                apps = apps,
                                selected = selected,
                                query = query,
                                onQuery = { query = it },
                                onToggle = { pkg, checked -> selected = if (checked) selected + pkg else selected - pkg },
                                onContinue = { if (selected.isNotEmpty()) page = 2 },
                            )

                            2 -> ReviewProfileStep(
                                context = context,
                                name = profileName,
                                apps = apps.filter { it.packageName in selected },
                                onCreate = {
                                    if (persistFriendlyName()) {
                                        message = ""
                                        ProfileSetupRuntime.create(context, apps.filter { it.packageName in selected })
                                    }
                                },
                                onChangeApps = { page = 1 },
                            )

                            else -> {
                                val currentUser = ProfileSetupRuntime.existingUser(context)
                                val record = records.firstOrNull { it.androidUserId == currentUser }
                                    ?: records.firstOrNull { it.label == profileName }
                                val spaces = registered.filter { it.androidUserId == currentUser }
                                ReadyProfileStep(
                                    context = context,
                                    record = record,
                                    profileName = profileName,
                                    workspaces = spaces,
                                    packageNames = record?.packages.orEmpty(),
                                    renaming = renaming,
                                    onRenaming = { renaming = it },
                                    onName = { profileName = it },
                                    onSaveName = {
                                        if (persistFriendlyName()) {
                                            renaming = false
                                            refresh()
                                        }
                                    },
                                    onOpenProfile = { record?.let(::openWholeProfile) },
                                    onOpenWorkspace = ::openWorkspace,
                                    onManageApps = ::chooseApps,
                                    onAddProfile = {
                                        runCatching { ProfileSetupRuntime.beginAnotherProfile(context) }
                                            .onSuccess {
                                                selected = emptySet()
                                                profileName = suggestedProfileName(ProfileRegistryStore.records(context).size)
                                                page = 0
                                                message = ""
                                                refresh()
                                            }
                                            .onFailure { message = it.message.orEmpty() }
                                    },
                                    onReturnMain = {
                                        scope.launch {
                                            message = withContext(Dispatchers.IO) {
                                                runCatching {
                                                    ProfileSetupRuntime.openProfile(context, null)
                                                    "Returned to Main"
                                                }.getOrElse { it.message ?: "Couldn't return to Main." }
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                if (checking) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
                if (message.isNotBlank()) {
                    Text(
                        message,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (progress.issue != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!progress.busy && !progress.ready && progress.message.isNotBlank() && progress.issue != null) {
                    Text(
                        progress.message,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileSetupTopBar(title: String, onBack: () -> Unit, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Rounded.ArrowBack, "Back", modifier = Modifier.size(21.dp))
        }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = onClose, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Rounded.Close, "Close", modifier = Modifier.size(21.dp))
        }
    }
}

@Composable
private fun NameProfileStep(
    name: String,
    onName: (String) -> Unit,
    onSuggestion: (String) -> Unit,
    onContinue: () -> Unit,
    onMainProfile: Boolean?,
    onReturnMain: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Name your profile", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Use a name you will recognize when Cyclone is working across several accounts.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 40) onName(it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Profile name") },
                placeholder = { Text("Creator 1") },
                shape = RoundedCornerShape(18.dp),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Creator", "Store", "Personal").forEach { suggestion ->
                    AssistChip(onClick = { onSuggestion(suggestion) }, label = { Text(suggestion) })
                }
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .58f)),
                shape = RoundedCornerShape(20.dp),
            ) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                        Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Person, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(21.dp))
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("A separate phone space", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Cyclone keeps this profile's app data separate from Main and can switch you into it with one tap.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            Button(onClick = onContinue, enabled = name.isNotBlank() && onMainProfile != false, modifier = Modifier.fillMaxWidth()) {
                Text("Choose apps")
            }
        }
        if (onMainProfile == false) {
            item {
                OutlinedButton(onClick = onReturnMain, modifier = Modifier.fillMaxWidth()) { Text("Return to Main first") }
            }
        }
    }
}

@Composable
private fun ChooseAppsStep(
    apps: List<ProfileApp>,
    selected: Set<String>,
    query: String,
    onQuery: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onContinue: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Choose apps", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Each app starts with fresh data in this profile. You can add more later.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                placeholder = { Text("Search apps") },
                shape = RoundedCornerShape(18.dp),
            )
        }
        val filtered = apps.filter { it.label.contains(query, true) }
        if (filtered.isEmpty()) {
            item { Text("No matching apps found.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(filtered, key = { it.packageName }) { app ->
            AppPickerRow429(app, app.packageName in selected) { checked -> onToggle(app.packageName, checked) }
        }
        item {
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onContinue,
                enabled = selected.isNotEmpty() && selected.size <= 50,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text("Continue with ${selected.size} ${if (selected.size == 1) "app" else "apps"}")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AppPickerRow429(app: ProfileApp, checked: Boolean, onChecked: (Boolean) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onChecked(!checked) },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProfileAppIcon429(context, app.packageName, Modifier.size(40.dp))
            Text(app.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Checkbox(checked = checked, onCheckedChange = onChecked)
        }
    }
}

@Composable
private fun ReviewProfileStep(
    context: Context,
    name: String,
    apps: List<ProfileApp>,
    onCreate: () -> Unit,
    onChangeApps: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Create $name", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "${apps.size} ${if (apps.size == 1) "app" else "apps"} will be available with separate app data.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(apps.take(8), key = { "review-${it.packageName}" }) { app ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProfileAppIcon429(context, app.packageName, Modifier.size(36.dp))
                Text(app.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
            }
        }
        if (apps.size > 8) item { Text("+${apps.size - 8} more apps", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text("Create $name") }
        }
        item {
            TextButton(onClick = onChangeApps, modifier = Modifier.fillMaxWidth()) { Text("Change apps") }
        }
    }
}

@Composable
private fun CreatingProfileState(name: String, message: String, completed: Int, total: Int, onStop: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Creating $name", style = MaterialTheme.typography.headlineMedium)
        Text(message.ifBlank { "Setting up your phone space…" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(
            progress = { if (total <= 0) 0f else completed.toFloat().div(total).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = onStop) { Text("Pause setup") }
    }
}

@Composable
private fun ReadyProfileStep(
    context: Context,
    record: CycloneProfileRecord?,
    profileName: String,
    workspaces: List<Workspace>,
    packageNames: Set<String>,
    renaming: Boolean,
    onRenaming: (Boolean) -> Unit,
    onName: (String) -> Unit,
    onSaveName: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenWorkspace: (Workspace) -> Unit,
    onManageApps: () -> Unit,
    onAddProfile: () -> Unit,
    onReturnMain: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(shape = RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Person, null, Modifier.size(25.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(profileName, style = MaterialTheme.typography.titleLarge)
                            Text(
                                "${packageNames.size} ${if (packageNames.size == 1) "app" else "apps"} · separate phone space",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(
                                if (record?.ready == true) "Ready" else "Setup",
                                Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                    if (renaming) {
                        OutlinedTextField(
                            value = profileName,
                            onValueChange = { if (it.length <= 40) onName(it) },
                            singleLine = true,
                            label = { Text("Profile name") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onSaveName) { Text("Save name") }
                            TextButton(onClick = { onRenaming(false) }) { Text("Cancel") }
                        }
                    } else {
                        TextButton(onClick = { onRenaming(true) }, contentPadding = PaddingValues(0.dp)) {
                            Icon(Icons.Rounded.Edit, null, Modifier.size(17.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Rename")
                        }
                    }
                }
            }
        }

        item {
            Button(
                onClick = onOpenProfile,
                enabled = record?.ready == true,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Open profile") }
        }

        item { Text("Apps", style = MaterialTheme.typography.titleMedium) }
        if (workspaces.isEmpty() && packageNames.isEmpty()) {
            item { Text("Choose apps to finish this profile.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(workspaces, key = { "ready-${it.id}" }) { workspace ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Row(
                    Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ProfileAppIcon429(context, workspace.appPackage, Modifier.size(40.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(workspace.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Ready in $profileName", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { onOpenWorkspace(workspace) }) { Text("Open") }
                }
            }
        }
        if (workspaces.isEmpty()) {
            items(packageNames.toList().sorted(), key = { "ready-pkg-$it" }) { pkg ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ProfileAppIcon429(context, pkg, Modifier.size(40.dp))
                        Text(profileAppLabel429(context, pkg), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Text("Ready", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }

        item {
            OutlinedButton(onClick = onManageApps, modifier = Modifier.fillMaxWidth()) { Text("Manage apps") }
        }
        item {
            FilledTonalButton(onClick = onAddProfile, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Add, null, Modifier.size(19.dp))
                Spacer(Modifier.size(7.dp))
                Text("Add another profile")
            }
        }
        item {
            TextButton(onClick = onReturnMain, modifier = Modifier.fillMaxWidth()) { Text("Return to Main") }
        }
    }
}

@Composable
private fun ProfileAppIcon429(context: Context, packageName: String, modifier: Modifier = Modifier) {
    val drawable = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
            ?: context.getDrawable(com.cyclone.mobile.R.drawable.ic_cyclone_mark_42)
    }
    AndroidView(
        factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
        update = { it.setImageDrawable(drawable); it.contentDescription = null },
        modifier = modifier,
    )
}

private fun profileAppLabel429(context: Context, packageName: String): String = runCatching {
    val info = context.packageManager.getApplicationInfo(packageName, 0)
    context.packageManager.getApplicationLabel(info).toString()
}.getOrDefault(packageName)

private fun suggestedProfileName(existingCount: Int): String = when (existingCount) {
    0 -> "Profile B"
    1 -> "Profile C"
    2 -> "Profile D"
    else -> "Profile ${existingCount + 2}"
}
