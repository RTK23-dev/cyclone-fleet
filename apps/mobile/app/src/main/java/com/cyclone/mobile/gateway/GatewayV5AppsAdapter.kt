package com.cyclone.mobile.gateway

import android.content.Context
import android.content.Intent
import com.cyclone.mobile.brain.graphv2.AppVersionEvidence
import com.cyclone.mobile.brain.graphv2.AtlasMapStatus
import com.cyclone.mobile.brain.graphv2.AtlasPlaceKey
import com.cyclone.mobile.brain.graphv2.AtlasPlaceKind
import com.cyclone.mobile.brain.graphv2.AtlasStore
import org.json.JSONArray
import org.json.JSONObject

/** A launchable app on this phone. Package facts only; nothing about the user's data. */
internal data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long?,
)

/** What the phone's Atlas knows about one place in one persona. */
internal data class MappedPlace(
    val placeId: String,
    val kind: AtlasPlaceKind,
    val label: String,
    val packageName: String?,
    val origin: String?,
    val persona: String,
    val mapStatus: AtlasMapStatus,
    val rooms: Int,
    val doors: Int,
    val lastVerifiedAtEpochMillis: Long?,
    /** Door count per app version the doors were learned on. */
    val doorsByVersion: Map<AppVersionEvidence, Int>,
)

/**
 * V5 `apps.list`: every launchable app plus every Atlas place, with installed version, mapped versions and
 * whether the map was learned on a different version than the one installed now (`needsRemap`).
 *
 * Read-only. Glass shows it; the phone owns both the package facts and the Atlas.
 */
internal object GatewayV5AppsAdapter {
    const val MAX_APPS = 600
    private const val MAX_VERSIONS = 12

    /** Seams for JVM tests; production reads PackageManager and the durable Atlas. */
    @Volatile internal var installedApps: () -> List<InstalledApp> = { emptyList() }
    @Volatile internal var mappedPlaces: () -> List<MappedPlace> = { emptyList() }
    /** Scenario health counts per app (from the knowledge adapter); null when unknown. */
    @Volatile internal var scenarioHealth: (String) -> Map<String, Int>? = { null }

    fun install(context: Context, store: AtlasStore) {
        val app = context.applicationContext
        installedApps = { launchableApps(app) }
        mappedPlaces = { mappedPlaces(store) }
    }

    fun dispatch(op: String, args: JSONObject): JSONObject = when (op) {
        "apps.list" -> list(args)
        else -> throw GatewayProtocolException("UNKNOWN_OPERATION", "Unsupported apps operation: $op")
    }

    fun list(args: JSONObject): JSONObject {
        if (args.length() != 0) throw GatewayProtocolException("INVALID_REQUEST", "apps.list takes no arguments.")
        val installed = installedApps().associateBy { it.packageName }
        val places = mappedPlaces().groupBy { it.placeId }
        val packagePlaceIds = installed.keys.map { "package:$it" }
        val ids = (packagePlaceIds + places.keys).distinct()
        val apps = ids.mapNotNull { id ->
            val personas = places[id].orEmpty()
            val app = personas.firstOrNull()?.packageName?.let(installed::get) ?: installed[id.removePrefix("package:")]
            entry(id, app?.takeIf { id.startsWith("package:") }, personas)
        }
            .sortedWith(compareBy({ it.optString("label").lowercase() }, { it.optString("placeId") }))
            .take(MAX_APPS)
        return JSONObject().put("apps", JSONArray(apps)).put("truncated", ids.size > MAX_APPS)
    }

    private fun entry(placeId: String, app: InstalledApp?, personas: List<MappedPlace>): JSONObject? {
        val first = personas.firstOrNull()
        val kind = first?.kind ?: AtlasPlaceKind.PACKAGE
        val label = (app?.label?.takeIf { it.isNotBlank() } ?: first?.label ?: placeId.substringAfter(':')).take(80)
        val versions = personas.flatMap { it.doorsByVersion.entries }
            .groupBy({ it.key.stableIdentity to it.key }, { it.value })
            .map { (key, counts) -> key.second to counts.sum() }
            .filter { (version, _) -> version.stableIdentity.isNotBlank() }
            .sortedWith(compareByDescending<Pair<AppVersionEvidence, Int>> { it.first.versionCode ?: -1L }.thenByDescending { it.first.versionName.orEmpty() })
            .take(MAX_VERSIONS)
        val mapped = personas.any { it.rooms > 0 }
        val installedIdentity = app?.let { AppVersionEvidence(it.packageName, it.versionName, it.versionCode).stableIdentity }
        val needsRemap = mapped && !installedIdentity.isNullOrBlank() && versions.isNotEmpty() &&
            versions.none { it.first.stableIdentity == installedIdentity }
        return JSONObject()
            .put("placeId", placeId)
            .put("kind", kind.wireValue)
            .put("label", label)
            .put("packageName", (app?.packageName ?: first?.packageName) ?: JSONObject.NULL)
            .put("origin", first?.origin ?: JSONObject.NULL)
            .put("installed", if (kind == AtlasPlaceKind.PACKAGE) app != null else JSONObject.NULL)
            .put("installedVersion", app?.let { version(it.versionName, it.versionCode) } ?: JSONObject.NULL)
            .put("mapStatus", bestStatus(personas).wireValue)
            .put("rooms", personas.maxOfOrNull { it.rooms } ?: 0)
            .put("doors", personas.maxOfOrNull { it.doors } ?: 0)
            .put("lastVerifiedAt", personas.mapNotNull { it.lastVerifiedAtEpochMillis }.maxOrNull() ?: JSONObject.NULL)
            .put("needsRemap", needsRemap)
            .put("personas", JSONArray(personas.sortedBy { it.persona }.map { place ->
                JSONObject()
                    .put("persona", place.persona)
                    .put("mapStatus", place.mapStatus.wireValue)
                    .put("rooms", place.rooms)
                    .put("doors", place.doors)
                    .put("lastVerifiedAt", place.lastVerifiedAtEpochMillis ?: JSONObject.NULL)
            }))
            .put("mappedVersions", JSONArray(versions.map { (version, doors) ->
                version(version.versionName, version.versionCode).put("doors", doors)
            }))
            .apply {
                if (mapped && placeId.startsWith("package:")) scenarioHealth(placeId)?.let { counts ->
                    put("scenarios", JSONObject().apply {
                        listOf("passing", "warning", "critical", "untested").forEach { put(it, counts[it] ?: 0) }
                    })
                }
            }
    }

    private fun version(name: String?, code: Long?): JSONObject = JSONObject()
        .put("versionName", name?.take(64) ?: JSONObject.NULL)
        .put("versionCode", code ?: JSONObject.NULL)

    private val statusRank = listOf(AtlasMapStatus.MAPPED, AtlasMapStatus.PARTIAL, AtlasMapStatus.STALE, AtlasMapStatus.UNMAPPED)

    private fun bestStatus(personas: List<MappedPlace>): AtlasMapStatus =
        personas.map { it.mapStatus }.minByOrNull(statusRank::indexOf) ?: AtlasMapStatus.UNMAPPED

    private fun launchableApps(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .mapNotNull { info -> info.activityInfo?.packageName?.let { it to info } }
            .filter { (pkg, _) -> pkg != context.packageName }
            .distinctBy { it.first }
            .map { (pkg, info) ->
                val packageInfo = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
                InstalledApp(
                    packageName = pkg,
                    label = info.loadLabel(pm).toString(),
                    versionName = packageInfo?.versionName,
                    versionCode = packageInfo?.longVersionCode,
                )
            }
    }

    private fun mappedPlaces(store: AtlasStore): List<MappedPlace> = store.places().map { summary ->
        val place = summary.place
        val snapshot = store.snapshot(AtlasPlaceKey(place.id, place.persona))
        val doorsByVersion = snapshot?.edges.orEmpty()
            .mapNotNull { it.evidence.appVersion }
            .groupingBy { it }
            .eachCount()
        MappedPlace(
            placeId = place.id,
            kind = place.kind,
            label = place.label,
            packageName = place.packageName,
            origin = place.origin,
            persona = place.persona.wireValue,
            mapStatus = place.mapStatus,
            rooms = summary.screenCount,
            doors = summary.edgeCount,
            lastVerifiedAtEpochMillis = place.lastVerifiedAtEpochMillis,
            doorsByVersion = doorsByVersion,
        )
    }
}
