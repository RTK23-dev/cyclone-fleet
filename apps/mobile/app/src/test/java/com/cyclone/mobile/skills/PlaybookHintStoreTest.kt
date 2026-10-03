package com.cyclone.mobile.skills

import com.cyclone.mobile.runtime.session.ExecutionSession
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class PlaybookHintStoreTest {
    @Test
    fun twoSuccessfulFastPathRunsMergeAndSuccessCountBecomesTwo() {
        val store = PlaybookHintStore.inMemory()
        val first = store.recordSuccess(settingsPlaybook(lastSuccessAtMs = 1_000L), nowMs = 1_000L)
        val second = store.recordSuccess(settingsPlaybook(lastSuccessAtMs = 2_000L), nowMs = 2_000L)

        assertNotNull(first)
        assertNotNull(second)
        assertEquals(1, first!!.successCount)
        assertEquals(2, second!!.successCount)
        assertEquals(first.mergeKey, second.mergeKey)
        assertEquals(PlaybookSource.FAST_PATH, second.source)
        assertFalse(second.userOverride)

        val listed = store.list("com.android.settings")
        assertEquals(1, listed.size)
        val merged = listed.single()
        assertEquals(2, merged.successCount)
        assertEquals("com.android.settings", merged.packageName)
        assertEquals("Open battery settings", merged.goal)
        assertEquals("settings.home", merged.startPageKey)
        assertEquals(ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID, merged.sessionId)
        assertEquals(0, merged.displayId)
        assertEquals(2, merged.steps.size)
        assertEquals("phone.click", merged.steps[0].tool)
        assertEquals("Apps", merged.steps[0].selector.text)
        assertEquals("settings.home", merged.steps[0].beforePageKey)
        assertEquals("settings.apps", merged.steps[0].afterPageKey)
        assertTrue(merged.steps[0].expectedPageChange)
        assertEquals("Then tap Apps", merged.steps[0].nl)
        assertEquals("Battery", merged.steps[1].selector.text)
        assertEquals("settings.apps", merged.steps[1].beforePageKey)
        assertEquals("settings.battery", merged.steps[1].afterPageKey)
        assertEquals("When Settings home → Then tap Apps → Then tap Battery", merged.nlPlaybook)
        assertEquals(2_000L, merged.lastSuccessAtMs)

        val found = store.find(
            packageName = "com.android.settings",
            goal = "Open battery settings",
            startPageKey = "settings.home",
            sessionId = ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID,
            displayId = 0,
        )
        assertEquals(1, found.size)
        assertEquals(2, found.single().successCount)

        val compiled = SkillRouteCompiler.compile(merged)
        assertTrue(compiled.compiled)
        assertEquals(2, compiled.route!!.compiledFromSuccesses)
    }

    @Test
    fun differentSelectorFingerprintsDoNotMergeIntoOnePlaybook() {
        val store = PlaybookHintStore.inMemory()
        val first = store.recordSuccess(settingsPlaybook(textApps = "Apps", textBattery = "Battery"), nowMs = 1_000L)
        val second = store.recordSuccess(
            settingsPlaybook(textApps = "See all apps", textBattery = "Battery usage"),
            nowMs = 2_000L,
        )

        assertNotNull(first)
        assertNotNull(second)
        assertNotEquals(first!!.mergeKey, second!!.mergeKey)
        assertNotEquals(first.steps[0].selector.fingerprint, second.steps[0].selector.fingerprint)

        val listed = store.list("com.android.settings")
        assertEquals(2, listed.size)
        assertEquals(setOf(1, 1), listed.map { it.successCount }.toSet())
        assertEquals(2, listed.map { it.mergeKey }.toSet().size)
        assertEquals(1, first.successCount)
        assertEquals(1, second.successCount)
    }

    @Test
    fun userOverrideMergeReplacesNlPlaybookAndIsPreferred() {
        val store = PlaybookHintStore.inMemory()
        repeat(5) { index ->
            store.recordSuccess(
                settingsPlaybook(
                    textApps = "Applications",
                    textBattery = "Power",
                    lastSuccessAtMs = 100L + index,
                ),
                nowMs = 100L + index,
            )
        }
        store.recordSuccess(settingsPlaybook(lastSuccessAtMs = 50L), nowMs = 50L)

        val overridePlaybook = "User path: Settings home → Apps → Battery"
        val merged = store.mergeUserOverride(
            settingsPlaybook(nlPlaybook = overridePlaybook, lastSuccessAtMs = 9_000L),
            nowMs = 9_000L,
        )

        assertNotNull(merged)
        assertTrue(merged!!.userOverride)
        assertEquals(PlaybookSource.USER_OVERRIDE, merged.source)
        assertEquals(overridePlaybook, merged.nlPlaybook)
        assertEquals("Apps", merged.steps[0].selector.text)
        assertEquals("Battery", merged.steps[1].selector.text)
        assertTrue(merged.successCount >= SkillRouteCompiler.MIN_SUCCESSES)

        val listed = store.list("com.android.settings")
        assertEquals(2, listed.size)
        assertTrue(listed.first().userOverride)
        assertEquals(overridePlaybook, listed.first().nlPlaybook)
        assertEquals(PlaybookSource.USER_OVERRIDE, listed.first().source)
        assertFalse(listed[1].userOverride)
        assertEquals(5, listed[1].successCount)

        val compiled = SkillRouteCompiler.compileReady(store, "com.android.settings", nowMs = 9_000L)
        assertTrue(compiled.isNotEmpty())
        assertEquals(overridePlaybook, compiled.first().nlPlaybook)
    }

    @Test
    fun passwordOtpAndApiKeyParamsAreStrippedAndNeverStored() {
        val store = PlaybookHintStore.inMemory()
        val recorded = store.recordSuccess(
            settingsPlaybook(
                step1Params = mapOf(
                    "account" to "work",
                    "password" to "hunter2",
                    "otp" to "123456",
                    "api_key" to "sk-abcdefghijklmnop",
                ),
                step2Params = mapOf("password" to "hunter2"),
            ),
            nowMs = 1_000L,
        )

        assertNotNull(recorded)
        val step1 = recorded!!.steps[0]
        val step2 = recorded.steps[1]
        assertEquals(mapOf("account" to "work"), step1.params)
        assertFalse(step1.params.containsKey("password"))
        assertFalse(step1.params.containsKey("otp"))
        assertFalse(step1.params.containsKey("api_key"))
        assertTrue(step2.params.isEmpty())
        assertEquals("Apps", step1.selector.text)
        assertEquals("Battery", step2.selector.text)
        assertEquals("phone.click", step2.tool)

        val persisted = store.list("com.android.settings").single()
        assertEquals(mapOf("account" to "work"), persisted.steps[0].params)
        assertTrue(persisted.steps[1].params.isEmpty())
        assertEquals("Battery", persisted.steps[1].selector.text)
        val secretKeys = setOf("password", "otp", "api_key", "apiKey")
        assertFalse(persisted.steps.flatMap { it.params.keys }.any { it in secretKeys })
        assertFalse(persisted.steps.flatMap { it.params.values }.any { value ->
            value.contains("hunter2") || value.contains("123456") || value.contains("sk-")
        })

        val json = persisted.toJson().toString()
        assertFalse(json.contains("hunter2"))
        assertFalse(json.contains("123456"))
        assertFalse(json.contains("sk-abcdefghijklmnop"))
        assertTrue(json.contains("Apps"))
        assertTrue(json.contains("Battery"))
    }

    @Test
    fun namedWorkspaceHintWithDisplayZeroCannotBeRecorded() {
        val store = PlaybookHintStore.inMemory()
        assertFalse(PlaybookSafety.workspaceDisplayLegal("workspace-settings", 0))

        val constructed = runCatching {
            settingsPlaybook(sessionId = "workspace-settings", displayId = 0)
        }.getOrNull()
        val recorded = constructed?.let { store.recordSuccess(it) }
        assertNull(constructed)
        assertNull(recorded)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun coordinateOnlySelectorsCannotBeConstructed() {
        val json = JSONObject().put("x", 120).put("y", 480)
        assertNull(SemanticSelector.fromJson(json))
        assertNull(SemanticSelector.fromMap(mapOf("x" to "120", "y" to "480")))
    }

    @Test
    fun filePersistRoundTripSurvivesList() {
        val directory = Files.createTempDirectory("playbook-hint-store-test").toFile()
        try {
            val store = PlaybookHintStore.files(directory)
            val recorded = store.recordSuccess(settingsPlaybook(lastSuccessAtMs = 1_000L), nowMs = 1_000L)
            assertNotNull(recorded)
            assertEquals(1, recorded!!.successCount)
            assertEquals(1, store.list().size)

            val reloaded = PlaybookHintStore.files(directory)
            val listed = reloaded.list("com.android.settings")
            assertEquals(1, listed.size)
            val hint = listed.single()
            assertEquals(recorded.mergeKey, hint.mergeKey)
            assertEquals("com.android.settings", hint.packageName)
            assertEquals("Open battery settings", hint.goal)
            assertEquals(PlaybookGoal.signature("Open battery settings"), hint.goalSignature)
            assertEquals("settings.home", hint.startPageKey)
            assertEquals(ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID, hint.sessionId)
            assertEquals(0, hint.displayId)
            assertEquals("When Settings home → Then tap Apps → Then tap Battery", hint.nlPlaybook)
            assertEquals(1, hint.successCount)
            assertEquals(PlaybookSource.FAST_PATH, hint.source)
            assertEquals(1_000L, hint.lastSuccessAtMs)
            assertEquals(2, hint.steps.size)
            assertEquals("Then tap Apps", hint.steps[0].nl)
            assertEquals("phone.click", hint.steps[0].tool)
            assertEquals("Apps", hint.steps[0].selector.text)
            assertEquals("settings.home", hint.steps[0].beforePageKey)
            assertEquals("settings.apps", hint.steps[0].afterPageKey)
            assertTrue(hint.steps[0].expectedPageChange)
            assertEquals("Then tap Battery", hint.steps[1].nl)
            assertEquals("Battery", hint.steps[1].selector.text)
            assertEquals("settings.apps", hint.steps[1].beforePageKey)
            assertEquals("settings.battery", hint.steps[1].afterPageKey)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun settingsPlaybook(
        textApps: String = "Apps",
        textBattery: String = "Battery",
        sessionId: String = ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID,
        displayId: Int = ExecutionSession.DEFAULT_DISPLAY_ID,
        nlPlaybook: String = "When Settings home → Then tap Apps → Then tap Battery",
        successCount: Int = 1,
        lastSuccessAtMs: Long = 1_000L,
        source: PlaybookSource = PlaybookSource.FAST_PATH,
        step1Params: Map<String, String> = emptyMap(),
        step2Params: Map<String, String> = emptyMap(),
    ): PlaybookHint {
        val steps = listOf(
            PlaybookHintStep(
                nl = "Then tap Apps",
                tool = "phone.click",
                selector = SemanticSelector(text = textApps),
                beforePageKey = "settings.home",
                afterPageKey = "settings.apps",
                expectedPageChange = true,
                params = step1Params,
            ),
            PlaybookHintStep(
                nl = "Then tap Battery",
                tool = "phone.click",
                selector = SemanticSelector(text = textBattery),
                beforePageKey = "settings.apps",
                afterPageKey = "settings.battery",
                expectedPageChange = true,
                params = step2Params,
            ),
        )
        return PlaybookHint(
            packageName = "com.android.settings",
            goal = "Open battery settings",
            goalSignature = PlaybookGoal.signature("Open battery settings"),
            startPageKey = "settings.home",
            sessionId = sessionId,
            displayId = displayId,
            steps = steps,
            nlPlaybook = nlPlaybook,
            successCount = successCount,
            source = source,
            lastSuccessAtMs = lastSuccessAtMs,
        )
    }
}
