package com.cyclone.mobile.gateway

import com.cyclone.mobile.brain.graphv2.AppVersionEvidence
import com.cyclone.mobile.brain.graphv2.AtlasMapStatus
import com.cyclone.mobile.brain.graphv2.AtlasPlaceKind
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GatewayV5AppsAdapterTest {
    private var installed = listOf<InstalledApp>()
    private var mapped = listOf<MappedPlace>()

    @Before
    fun install() {
        GatewayV5AppsAdapter.installedApps = { installed }
        GatewayV5AppsAdapter.mappedPlaces = { mapped }
    }

    @After
    fun reset() {
        GatewayV5AppsAdapter.installedApps = { emptyList() }
        GatewayV5AppsAdapter.mappedPlaces = { emptyList() }
    }

    private fun gmailVersion(code: Long, name: String) = AppVersionEvidence(GMAIL, name, code)

    private fun gmailMap(persona: String, rooms: Int, doors: Map<AppVersionEvidence, Int>, status: AtlasMapStatus = AtlasMapStatus.MAPPED) =
        MappedPlace(
            placeId = "package:$GMAIL",
            kind = AtlasPlaceKind.PACKAGE,
            label = "Gmail",
            packageName = GMAIL,
            origin = null,
            persona = persona,
            mapStatus = status,
            rooms = rooms,
            doors = doors.values.sum(),
            lastVerifiedAtEpochMillis = 1_000L,
            doorsByVersion = doors,
        )

    private fun apps(): List<JSONObject> {
        val array = GatewayV5AppsAdapter.dispatch("apps.list", JSONObject()).getJSONArray("apps")
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    @Test
    fun listsEveryLaunchableAppEvenWhenNothingIsMapped() {
        installed = listOf(
            InstalledApp(CLOCK, "Clock", "8.1", 81L),
            InstalledApp(GMAIL, "Gmail", "2026.09.01", 900L),
        )
        val apps = apps()
        assertEquals(listOf("Clock", "Gmail"), apps.map { it.getString("label") })
        val clock = apps.first()
        assertEquals("package:$CLOCK", clock.getString("placeId"))
        assertEquals("unmapped", clock.getString("mapStatus"))
        assertEquals(0, clock.getInt("rooms"))
        assertTrue(clock.getBoolean("installed"))
        assertEquals(81L, clock.getJSONObject("installedVersion").getLong("versionCode"))
        assertFalse(clock.getBoolean("needsRemap"))
        assertEquals(0, clock.getJSONArray("mappedVersions").length())
    }

    @Test
    fun mappedVersionsAndNeedsRemapFollowTheInstalledVersion() {
        installed = listOf(InstalledApp(GMAIL, "Gmail", "2026.09.01", 900L))
        mapped = listOf(gmailMap("mapping", rooms = 6, doors = mapOf(gmailVersion(880L, "2026.08.01") to 9, gmailVersion(870L, "2026.07.20") to 2)))
        val gmail = apps().single()
        assertEquals("mapped", gmail.getString("mapStatus"))
        assertEquals(6, gmail.getInt("rooms"))
        assertTrue("installed 900 has no verified map", gmail.getBoolean("needsRemap"))
        val versions = gmail.getJSONArray("mappedVersions")
        assertEquals(880L, versions.getJSONObject(0).getLong("versionCode"))
        assertEquals(9, versions.getJSONObject(0).getInt("doors"))

        mapped = listOf(gmailMap("mapping", rooms = 6, doors = mapOf(gmailVersion(900L, "2026.09.01") to 4, gmailVersion(880L, "2026.08.01") to 9)))
        assertFalse(apps().single().getBoolean("needsRemap"))
    }

    @Test
    fun personasStaySeparateAndTheBestStatusWins() {
        installed = listOf(InstalledApp(GMAIL, "Gmail", "2026.09.01", 900L))
        mapped = listOf(
            gmailMap("live", rooms = 2, doors = mapOf(gmailVersion(900L, "2026.09.01") to 1), status = AtlasMapStatus.PARTIAL),
            gmailMap("mapping", rooms = 7, doors = mapOf(gmailVersion(900L, "2026.09.01") to 5)),
        )
        val gmail = apps().single()
        assertEquals("mapped", gmail.getString("mapStatus"))
        assertEquals(7, gmail.getInt("rooms"))
        val personas = gmail.getJSONArray("personas")
        assertEquals(listOf("live", "mapping"), (0 until personas.length()).map { personas.getJSONObject(it).getString("persona") })
        assertEquals("doors per version add up across personas", 6, gmail.getJSONArray("mappedVersions").getJSONObject(0).getInt("doors"))
    }

    @Test
    fun webPlacesAndUninstalledMappedAppsAreStillListed() {
        mapped = listOf(
            gmailMap("mapping", rooms = 3, doors = mapOf(gmailVersion(880L, "2026.08.01") to 2)),
            MappedPlace(
                placeId = "chrome:https://www.facebook.com",
                kind = AtlasPlaceKind.CHROME_ORIGIN,
                label = "www.facebook.com",
                packageName = null,
                origin = "https://www.facebook.com",
                persona = "mapping",
                mapStatus = AtlasMapStatus.PARTIAL,
                rooms = 4,
                doors = 3,
                lastVerifiedAtEpochMillis = null,
                doorsByVersion = emptyMap(),
            ),
        )
        val apps = apps().associateBy { it.getString("placeId") }
        val gmail = apps.getValue("package:$GMAIL")
        assertFalse(gmail.getBoolean("installed"))
        assertTrue(gmail.isNull("installedVersion"))
        assertFalse("no installed version, nothing to compare", gmail.getBoolean("needsRemap"))
        val web = apps.getValue("chrome:https://www.facebook.com")
        assertEquals("chrome-origin", web.getString("kind"))
        assertTrue(web.isNull("installed"))
        assertEquals("https://www.facebook.com", web.getString("origin"))
    }

    @Test
    fun argumentsAreRejectedAndTheOpIsReadOnlyAndAdvertised() {
        val error = runCatching { GatewayV5AppsAdapter.dispatch("apps.list", JSONObject().put("all", true)) }.exceptionOrNull()
        assertEquals("INVALID_REQUEST", (error as GatewayProtocolException).code)
        assertTrue("apps.list" in GatewayProtocol.operations)
        assertTrue("apps.list" in GatewayProtocol.legacyReadOnlyOperations)
    }

    @Test
    fun outputCarriesPackageFactsOnly() {
        installed = listOf(InstalledApp(GMAIL, "Gmail", "2026.09.01", 900L))
        mapped = listOf(gmailMap("live", rooms = 3, doors = mapOf(gmailVersion(900L, "2026.09.01") to 2)))
        val text = GatewayV5AppsAdapter.dispatch("apps.list", JSONObject()).toString()
        assertFalse(text.contains("screen:"))
        assertFalse(text.contains("door:"))
        assertFalse(text.contains("@"))
        assertFalse(text.contains("selector", ignoreCase = true))
    }

    private companion object {
        const val GMAIL = "com.google.android.gm"
        const val CLOCK = "com.google.android.deskclock"
    }
}
