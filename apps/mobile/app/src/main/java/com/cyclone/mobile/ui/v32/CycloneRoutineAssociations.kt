package com.cyclone.mobile.ui.v32

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.automation.*
import com.cyclone.mobile.runtime.workspaces.ProfileApp
import com.cyclone.mobile.runtime.workspaces.ProfileSetupRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CycloneRoutineAssociations(routine: AutomationDefinition, onSaved: () -> Unit) {
    val context = LocalContext.current
    var edit by remember { mutableStateOf(false) }
    var packages by remember(routine.id, routine.appPackages) { mutableStateOf(routine.appPackages.toSet()) }
    var categories by remember(routine.id, routine.categories) { mutableStateOf(routine.categories.joinToString(", ")) }
    var apps by remember { mutableStateOf(emptyList<ProfileApp>()) }
    LaunchedEffect(edit) { if (edit) apps = withContext(Dispatchers.IO) { ProfileSetupRuntime.apps(context) } }
    TextButton(onClick = { edit = true }) { Text("Apps & categories") }
    if (edit) AlertDialog(onDismissRequest = { edit = false }, title = { Text("Apps & categories") }, text = {
        Column {
            OutlinedTextField(categories, { categories = it }, label = { Text("Categories, separated by commas") })
            LazyColumn(Modifier.heightIn(max = 280.dp)) { items(apps, key = { it.packageName }) { app ->
                TextButton(onClick = { packages = if (app.packageName in packages) packages - app.packageName else packages + app.packageName }, modifier = Modifier.fillMaxWidth()) {
                    CycloneAppIcon(app.packageName)
                    Text(app.label, modifier = Modifier.weight(1f).padding(8.dp))
                    if (app.packageName in packages) Text("✓")
                }
            } }
        }
    }, confirmButton = { TextButton(onClick = {
        val current = AutomationRuntime.store.getAutomation(routine.id) ?: routine
        AutomationRuntime.store.saveAutomation(current.copy(associationVersion = 1, appPackages = packages.sorted(), categories = categories.split(',').map { it.trim() }.filter(String::isNotBlank).distinct()))
        edit = false; onSaved()
    }) { Text("Save") } }, dismissButton = { TextButton(onClick = { edit = false }) { Text("Cancel") } })
}
