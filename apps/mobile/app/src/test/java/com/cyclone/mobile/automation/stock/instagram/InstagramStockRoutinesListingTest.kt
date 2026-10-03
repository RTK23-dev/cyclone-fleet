package com.cyclone.mobile.automation.stock.instagram

import com.cyclone.mobile.automation.StepType
import com.cyclone.mobile.automation.TriggerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class InstagramStockRoutinesListingTest {
    @Test
    fun fourInstagramSkillsAreRoutinesRowsUnderInstagram() {
        val routines = InstagramStockSkillCollectionGateway.routines()
        assertEquals(
            listOf(
                InstagramStockSkillIds.REELS_WARMUP,
                InstagramReelsFollowingStockSkill.ID,
                InstagramColdDmsStockSkill.ID,
                InstagramPreparePostStockSkill.ID,
            ),
            routines.map { it.id },
        )
        routines.forEach { routine ->
            assertEquals(TriggerType.MANUAL, routine.trigger.type)
            assertTrue(routine.enabled)
            assertEquals(listOf("com.instagram.android"), routine.appPackages)
            assertEquals(listOf("Instagram"), routine.categories)
            assertEquals(StepType.STOCK_SKILL, routine.steps.single().type)
            assertEquals(routine.id, routine.steps.single().parameters["skillId"])
        }
        assertEquals("5", routines.first().variables.single { it.name == "durationMinutes" }.defaultValue)
    }

    @Test
    fun routinesPageListsSeededStockAutomations() {
        val runtime = sequenceOf(
            File("src/main/java/com/cyclone/mobile/automation/AutomationRuntime.kt"),
            File("apps/mobile/app/src/main/java/com/cyclone/mobile/automation/AutomationRuntime.kt"),
        ).first { it.isFile }.readText()
        val page = sequenceOf(
            File("src/main/java/com/cyclone/mobile/ui/v32/CycloneRoutinesPage.kt"),
            File("apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneRoutinesPage.kt"),
        ).first { it.isFile }.readText()
        assertTrue(runtime.contains("InstagramStockSkillCollectionGateway.routines()"))
        assertTrue(runtime.contains("store.saveAutomation(routine)"))
        assertTrue(page.contains("AutomationRuntime.store.listAutomations()"))
        assertTrue(page.contains("AutomationRuntime.router.runManual"))
    }
}
