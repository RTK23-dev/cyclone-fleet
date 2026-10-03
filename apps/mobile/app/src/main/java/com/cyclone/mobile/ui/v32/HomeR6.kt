package com.cyclone.mobile.ui.v32

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cyclone.mobile.runtime.background.WorkspaceTasks
import com.cyclone.mobile.runtime.workspaces.Layer2Workspaces
import com.cyclone.mobile.runtime.workspaces.ProfileRegistryStore
import com.cyclone.mobile.runtime.workspaces.ProfileSetupRuntime
import com.cyclone.mobile.ui.overlay.OverlayChromeRuntime
import com.cyclone.mobile.ui.v32.ask.AskCalm
import com.cyclone.mobile.ui.v32.ask.AskGlass
import com.cyclone.mobile.ui.v32.ask.AskRoundChip
import com.cyclone.mobile.ui.v32.ask.AskTextShadow
import com.cyclone.mobile.ui.v32.ask.GlassTier
import com.cyclone.mobile.ui.v32.ask.askGlass
import com.kyant.capsule.ContinuousCapsule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * R6 Home header (docs/design/redesign/rounds/R6-calm.md): Settings on the left, the search pill in the middle, the
 * Cyclone mark on the right (Ask Cyclone), like a banking app's top row.
 */
@Composable
internal fun HomeTopBar(onSettings: () -> Unit, onSearch: () -> Unit, onAi: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AskRoundChip("Settings", onSettings, shineOffset = 0.1f) {
            Icon(Icons.Rounded.Menu, null, Modifier.size(22.dp), tint = AskGlass.Ink)
        }
        Row(
            Modifier.weight(1f).heightIn(min = 46.dp)
                .askGlass(23.dp, GlassTier.CHROME, 0.15f, ContinuousCapsule)
                .clip(ContinuousCapsule)
                .clickable(role = Role.Button, onClickLabel = "Search", onClick = onSearch)
                .semantics { contentDescription = "Search Cyclone" }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Rounded.Search, null, Modifier.size(21.dp), tint = AskGlass.Ink)
            Text("Search", color = AskGlass.Muted, fontSize = 16.sp)
        }
        AskRoundChip("Open Ask Cyclone", onAi, shineOffset = 0.15f) { CycloneOrbitMark(Modifier.size(22.dp)) }
    }
}

/** A profile as the Home slider shows it. */
internal data class HomeProfile(
    val key: String,
    val label: String,
    val packages: List<String>,
    val ready: Boolean,
    val current: Boolean,
    val owner: Boolean,
    val active: Boolean,
)

/** Pure: the slider's pages, the profile in use first, then running ones, then by name. Never empty. */
internal object HomeProfiles {
    val THIS_PHONE = HomeProfile("this-phone", "This phone", emptyList(), ready = true, current = true, owner = true, active = false)

    fun order(profiles: List<HomeProfile>): List<HomeProfile> =
        profiles.sortedWith(compareByDescending<HomeProfile> { it.current }.thenByDescending { it.active }.thenByDescending { it.owner }.thenBy { it.label.lowercase() })
            .ifEmpty { listOf(THIS_PHONE) }

    /** The small line over the name: "Profile · In use", "Profile · Working", "Profile · Setting up". */
    fun kind(profile: HomeProfile): String = "Profile · " + when {
        !profile.ready -> "Setting up"
        profile.active -> "Working"
        profile.current -> "In use"
        else -> "Ready"
    }

    /** The line under the name: how many apps live in it. */
    fun apps(profile: HomeProfile): String = when (val n = profile.packages.size) {
        0 -> if (profile.owner) "Your main profile" else "No apps yet"
        1 -> "1 app"
        else -> "$n apps"
    }
}

/** Loads the same profiles the Profiles tab shows (its own model), off the main thread. */
@Composable
internal fun rememberHomeProfiles(context: Context, refreshTick: Int): List<HomeProfile> {
    var profiles by remember { mutableStateOf(emptyList<HomeProfile>()) }
    val task by WorkspaceTasks.state.collectAsState()
    LaunchedEffect(refreshTick, task?.workspaceId) {
        profiles = withContext(Dispatchers.IO) {
            runCatching {
                Layer2Workspaces.initialize(context)
                val identity = runCatching { ProfileSetupRuntime.visibleProfileIdentity() }.getOrNull()
                buildProfileClusters(
                    allWorkspaces = Layer2Workspaces.engine.snapshot(),
                    allRecords = ProfileRegistryStore.records(context),
                    waiting = Layer2Workspaces.engine.queue().toSet(),
                    activeWorkspaceId = task?.workspaceId,
                    processUser = ProfileSetupRuntime.currentUserId(),
                    ownerUser = identity?.first,
                    verifiedCurrentUser = identity?.second,
                    foregroundExecuting = OverlayChromeRuntime.hasExecutingTask(),
                ).map { HomeProfile(it.key, it.label, it.packages.sorted(), it.ready, it.current, it.owner, it.active) }
            }.getOrDefault(emptyList())
        }
    }
    return HomeProfiles.order(profiles)
}

/**
 * The profile slider (R6): one profile per page, centred like an account balance. Swipe between profiles; a tap
 * opens Profiles. The dots under it show where you are.
 */
@Composable
internal fun ProfileSlider(profiles: List<HomeProfile>, onOpen: (HomeProfile) -> Unit) {
    val pager = rememberPagerState { profiles.size }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        HorizontalPager(pager, Modifier.fillMaxWidth(), key = { profiles[it].key }) { page ->
            val profile = profiles[page]
            Column(
                Modifier.fillMaxWidth().clip(ContinuousCapsule)
                    .clickable(role = Role.Button, onClickLabel = "Open Profiles", onClick = { onOpen(profile) })
                    .semantics { contentDescription = "${profile.label}. ${HomeProfiles.kind(profile)}. ${HomeProfiles.apps(profile)}" }
                    .padding(vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(HomeProfiles.kind(profile), color = AskGlass.Ink.copy(alpha = 0.86f), fontSize = 16.sp, style = TextStyle(shadow = AskTextShadow))
                Text(
                    profile.label,
                    color = AskGlass.Ink,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.8).sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp),
                    style = TextStyle(shadow = AskTextShadow),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (profile.packages.isNotEmpty()) {
                        Box(Modifier.width((22 + 16 * (profile.packages.take(4).size - 1)).dp).height(24.dp)) {
                            profile.packages.take(4).forEachIndexed { i, pkg ->
                                Box(
                                    Modifier.offset(x = (16 * i).dp).size(24.dp).clip(CircleShape)
                                        .background(AskCalm.Middle).border(1.5.dp, AskCalm.Upper, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) { CycloneAppIcon(pkg, Modifier.size(18.dp)) }
                            }
                        }
                    }
                    Text(HomeProfiles.apps(profile), color = AskGlass.Ink.copy(alpha = 0.9f), fontSize = 16.sp, style = TextStyle(shadow = AskTextShadow))
                }
            }
        }
        if (profiles.size > 1) PageDots(profiles.size, pager.currentPage)
    }
}

/** The dots in a soft capsule, like the account slider's. The current one is a longer white bar. */
@Composable
private fun PageDots(count: Int, current: Int) {
    Row(
        Modifier.clip(ContinuousCapsule).background(AskCalm.Veil).padding(horizontal = 14.dp, vertical = 9.dp)
            .semantics { contentDescription = "Profile ${current + 1} of $count" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count.coerceAtMost(8)) { i ->
            val on = i == current
            val width by animateDpAsState(if (on) 18.dp else 7.dp, label = "Dot width")
            val color by animateColorAsState(if (on) Color.White else Color.White.copy(alpha = 0.45f), label = "Dot colour")
            Box(Modifier.width(width).height(7.dp).clip(ContinuousCapsule).background(color))
        }
    }
}

/** The four round actions under the slider (Ask, Routines, Brain, More), each a white veil circle with its word. */
@Composable
internal fun HomeActions(onAi: () -> Unit, onRoutines: () -> Unit, onBrain: () -> Unit, onMore: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        HomeAction(Icons.Rounded.AutoAwesome, "Ask", onAi)
        HomeAction(Icons.Rounded.Bolt, "Routines", onRoutines)
        HomeAction(Icons.Rounded.Psychology, "Brain", onBrain)
        HomeAction(Icons.Rounded.MoreHoriz, "More", onMore)
    }
}

@Composable
private fun HomeAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        // A soft rounded press area: a capsule here would clip the corners of a wide label ("Routines").
        Modifier.widthIn(min = 76.dp).clip(com.kyant.capsule.ContinuousRoundedRectangle(18.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(58.dp).clip(CircleShape).background(AskCalm.Veil).border(0.8.dp, AskCalm.VeilRim, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(24.dp), tint = AskGlass.Ink) }
        Text(label, color = AskGlass.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false,
            style = TextStyle(shadow = AskTextShadow))
    }
}
