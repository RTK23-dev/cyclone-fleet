package com.cyclone.mobile.automation
import org.junit.Assert.*
import org.junit.Test
class RoutineAssociationsTest {
    @Test fun explicitPackagesAreDistinctAcrossApps() {
        val r = AutomationDefinition(name = "Follow up", trigger = TriggerDefinition(TriggerType.APP_OPENED, mapOf("packageName" to "com.gmail")), steps = listOf(StepDefinition(name = "Open", type = StepType.PHONE_TOOL, parameters = mapOf("package" to "com.whatsapp"))))
        assertEquals(listOf("com.gmail", "com.whatsapp"), RoutineAssociations.infer(r))
    }
    @Test fun namesAndFreeTextAreNotGuessed() {
        val r = AutomationDefinition(name = "Instagram", trigger = TriggerDefinition(TriggerType.MANUAL), steps = listOf(StepDefinition(name = "com.instagram.android", type = StepType.WAIT)))
        assertTrue(RoutineAssociations.infer(r).isEmpty())
    }
    @Test fun legacyJsonMigratesWithoutDuplicatingRoutine() {
        val r = AutomationDefinition(id = "canonical", name = "Mail", trigger = TriggerDefinition(TriggerType.APP_OPENED, mapOf("packageName" to "com.mail")), steps = emptyList())
        val json = AutomationCodec.automationToJson(r).apply { remove("appPackages"); remove("categories"); remove("associationVersion") }
        val migrated = AutomationCodec.automationFromJson(json)
        assertEquals("canonical", migrated.id)
        assertEquals(listOf("com.mail"), migrated.appPackages)
        assertEquals(1, migrated.associationVersion)
    }
    @Test fun explicitEmptyAssociationsStayEmpty() {
        val r = AutomationDefinition(name = "Mail", trigger = TriggerDefinition(TriggerType.APP_OPENED, mapOf("packageName" to "com.mail")), steps = emptyList(), associationVersion = 1)
        assertTrue(AutomationCodec.automationFromJson(AutomationCodec.automationToJson(r)).appPackages.isEmpty())
    }
}
