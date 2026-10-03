package com.cyclone.mobile.gateway

import com.cyclone.mobile.runtime.workspaces.ProfileApps
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 43 T4: profiles over the gateway: list, apps, switch, and one Cyclone profile's apps. */
class GatewayV5ProfilesAdapterTest {
    private val b = "Cyclone_0123456789abcdef"
    private fun code(block: () -> Unit): String = (runCatching(block).exceptionOrNull() as GatewayProtocolException).code

    @Test fun profilesAppsAndSwitchingGoOutInTheirShape() {
        val switched = mutableListOf<String>()
        val changed = mutableListOf<Triple<String, String, String>>()
        GatewayV5ProfilesAdapter.profiles = {
            listOf(ProfileApps.Profile("main", "Profile A", null, null, 0, true, current = false, inTrash = false),
                ProfileApps.Profile(b, "Brand B", "🛍", 0xFF7C4DFFL, 11, true, current = true, inTrash = false))
        }
        GatewayV5ProfilesAdapter.apps = { listOf(ProfileApps.App("com.instagram.android", "Instagram")) to listOf(ProfileApps.App("com.whatsapp", "WhatsApp")) }
        GatewayV5ProfilesAdapter.switchTo = { switched += it }
        GatewayV5ProfilesAdapter.install = { id, pkg -> changed += Triple(id, pkg, "install") }
        GatewayV5ProfilesAdapter.remove = { id, pkg -> changed += Triple(id, pkg, "remove") }

        val list = GatewayV5ProfilesAdapter.dispatch("profiles.list", JSONObject())
        assertEquals(b, list.getString("current"))
        val brand = list.getJSONArray("profiles").getJSONObject(1)
        assertEquals("#FF7C4DFF", brand.getString("color"))
        assertEquals(setOf("id", "label", "emoji", "color", "ready", "current", "inTrash"), brand.keys().asSequence().toSet())

        val apps = GatewayV5ProfilesAdapter.dispatch("profiles.apps", JSONObject().put("profileId", b))
        assertEquals("com.whatsapp", apps.getJSONArray("available").getJSONObject(0).getString("package"))
        assertFalse(apps.getBoolean("truncated"))

        assertTrue(GatewayV5ProfilesAdapter.dispatch("profiles.switch", JSONObject().put("profileId", "main")).getBoolean("switched"))
        assertEquals(listOf("main"), switched)

        GatewayV5ProfilesAdapter.dispatch("profiles.app", JSONObject().put("profileId", b).put("package", "com.whatsapp").put("action", "install"))
        GatewayV5ProfilesAdapter.dispatch("profiles.app", JSONObject().put("profileId", b).put("package", "com.whatsapp").put("action", "remove"))
        assertEquals(listOf("install", "remove"), changed.map { it.third })

        assertEquals("INVALID_REQUEST", code { GatewayV5ProfilesAdapter.dispatch("profiles.switch", JSONObject().put("profileId", "10")) })
        assertEquals("INVALID_REQUEST", code {
            GatewayV5ProfilesAdapter.dispatch("profiles.app", JSONObject().put("profileId", "main").put("package", "com.whatsapp").put("action", "install"))
        })
        assertEquals("INVALID_REQUEST", code {
            GatewayV5ProfilesAdapter.dispatch("profiles.app", JSONObject().put("profileId", b).put("package", "com.whatsapp").put("action", "wipe"))
        })
        GatewayV5ProfilesAdapter.switchTo = { throw ProfileApps.Refused("ASK_BUSY", "busy") }
        assertEquals("ASK_BUSY", code { GatewayV5ProfilesAdapter.dispatch("profiles.switch", JSONObject().put("profileId", b)) })
        GatewayV5ProfilesAdapter.switchTo = { throw IllegalStateException("root denied") }
        assertEquals("CAPABILITY_UNAVAILABLE", code { GatewayV5ProfilesAdapter.dispatch("profiles.switch", JSONObject().put("profileId", b)) })
        assertTrue(GatewayProtocol.operations.containsAll(listOf("profiles.list", "profiles.apps", "profiles.switch", "profiles.app")))
    }
}
