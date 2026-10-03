package com.cyclone.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.runtime.workspaces.ProfileRegistryStore

@Composable
fun RootFeaturesCard() {
    var show by rememberSaveable { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val count = remember(show) { ProfileRegistryStore.records(context).count { it.ready } }
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Rounded.Person, null, modifier = Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Profiles", style = MaterialTheme.typography.titleLarge)
            Text(
                if (count == 0) "Create another phone space for separate accounts and app data."
                else "$count ${if (count == 1) "profile" else "profiles"} ready to open from Cyclone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Button(onClick = { show = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (count == 0) "Add profile" else "Manage profiles")
            }
        }
    }
    if (show) ProfileSetupPage { show = false }
}

@Composable
fun ProfileSetupPage(onClose: () -> Unit) = ProfileSetup429(onClose)
