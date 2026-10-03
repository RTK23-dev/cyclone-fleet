package com.cyclone.mobile.runtime.workspaces

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.Base64

/** Plan 40 P2, Cyclone Carry: what crosses between profiles, how it merges, and how it is sealed. */
class CarryRulesTest {
    private val skills = CarryRules.tables.single { it.name == "micro_skills" }
    private val notes = CarryRules.tables.single { it.name == "user_notes" }

    private fun skill(name: String = "Open Chats", params: String = "{\"selector\":\"Chats\"}") = JSONObject()
        .put("signature", "s1").put("name", name).put("tool", "phone.tap").put("params_json", params).put("goal_hints", "chats")
        .put("from_package", "com.whatsapp").put("from_fingerprint", "ab12").put("to_package", JSONObject.NULL).put("to_fingerprint", JSONObject.NULL)
        .put("success_count", 3).put("failure_count", 0).put("confidence", 0.9).put("source", "RUN").put("last_used_at", 100)

    @Test fun theMostRecentlyUsedRowWinsAndNotesAreOnlyAdded() {
        assertTrue(CarryRules.takes(skills, null, 5))
        assertTrue(CarryRules.takes(skills, 4, 5))
        assertFalse(CarryRules.takes(skills, 5, 5))
        assertFalse(CarryRules.takes(skills, 9, 5))
        assertTrue(CarryRules.takes(notes, null, 5))
        assertFalse(CarryRules.takes(notes, 1, 5))
    }

    @Test fun rowsTravelWholeAndNeverWithSecrets() {
        assertTrue(CarryRules.safeRow(skills, skill()))
        assertFalse(CarryRules.safeRow(skills, skill().apply { remove("confidence") }))
        assertFalse(CarryRules.safeRow(skills, skill().put("signature", "")))
        assertFalse(CarryRules.safeRow(skills, skill(params = "{\"text\":\"password: Zomer2024\"}")))
        assertFalse(CarryRules.safeRow(skills, skill(name = "Type 4111 1111 1111 1111")))
        assertFalse(CarryRules.safeRow(notes, JSONObject().put("id", "n1").put("text", "the verification code is 123456")
            .put("source", "USER").put("created_at", 1)))
    }

    @Test fun onlyPortableSettingsTravel() {
        assertTrue(CarryRules.carriesSetting("cyclone_drive", "button_dp", 64))
        assertTrue(CarryRules.carriesSetting("cyclone_drive", "voice", "alloy"))
        assertTrue(CarryRules.carriesSetting("cyclone_drive", "spot_ai_x", 0.4f))
        assertTrue(CarryRules.carriesSetting("cyclone_ui", "visual_quality", "LITE"))
        assertFalse(CarryRules.carriesSetting("cyclone_ui", "visual_quality_auto_stepped_down", true))
        // Never the AI keys, gateway pairing or anything that sounds private.
        assertFalse(CarryRules.carriesSetting("cyclone_ai", "openrouter_model", "x"))
        assertFalse(CarryRules.carriesSetting("cyclone_gateway", "device_token", "x"))
        assertFalse(CarryRules.carriesSetting("cyclone_drive", "api_key", "x"))
        assertFalse(CarryRules.carriesSetting("cyclone_drive", "session_token", "x"))
        assertFalse(CarryRules.carriesSetting("cyclone_drive", "voice", "sk-or-v1-abcdefghijklmnop"))
        assertFalse(CarryRules.carriesSetting("cyclone_drive", "models", setOf("a")))
        assertFalse(CarryRules.settings.keys.any { it.contains("ai") || it.contains("gateway") || it.contains("secret") || it.contains("vault") })
    }

    @Test fun theLineSaysWhatCame() {
        assertEquals("Brought 12 memories, 40 skills and your settings from Work",
            CarryRules.line(CarryReport(1, "Work", 12, 0, 40, 3)))
        assertEquals("Brought 1 memory from Profile A", CarryRules.line(CarryReport(1, "Profile A", 1, 0, 0, 0)))
        assertEquals("Forgot 2 as you did in Work", CarryRules.line(CarryReport(1, "Work", 0, 2, 0, 0)))
        assertNull(CarryRules.line(CarryReport(1, "Work", 0, 0, 0, 0)))
        val report = CarryReport(7, "Work", 1, 2, 3, 4)
        assertEquals(report, CarryReport.fromJson(report.toJson()))
    }

    @Test fun aBundleOpensOnlyForItsProfileAndSwitch() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)
        val plain = ByteArray(40_000) { (it % 251).toByte() } // well past what RSA alone can carry
        val sealed = ProfileTransferCipher.seal(publicKey, plain, CarryRules.cipherContext(10, "n1"))
        assertFalse(sealed.toString().contains(String(plain.copyOf(64), Charsets.ISO_8859_1)))
        assertArrayEquals(plain, ProfileTransferCipher.open(pair.private, sealed, CarryRules.cipherContext(10, "n1")))
        assertTrue(runCatching { ProfileTransferCipher.open(pair.private, sealed, CarryRules.cipherContext(11, "n1")) }.isFailure)
        assertTrue(runCatching { ProfileTransferCipher.open(pair.private, sealed, CarryRules.cipherContext(10, "n2")) }.isFailure)
    }
}
