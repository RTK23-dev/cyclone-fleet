package com.cyclone.mobile.ui.v32

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V5SettingsMountTest {
    @Test fun appMapsAndVaultHaveRootSettingsRoutes() {
        val root = source("ui/v32/CycloneSettings426.kt")
        assertTrue(root.contains("Settings426Row(\"App Maps\""))
        assertTrue(root.contains("\"App Maps\" -> item { AppMapsSettingsSection(context, refreshTick) }"))
        assertTrue(root.contains("Settings426Row(\"Vault\""))
        assertTrue(root.contains("\"Vault\" -> item { VaultSettingsPanel() }"))
    }

    @Test fun appMapsDoesNotOfferAnUnwiredAction() {
        val maps = source("ui/v32/AppMapsSettings.kt")
        assertTrue(maps.contains("onOpenOnGlass: ((String, String) -> Unit)? = null"))
        assertTrue(maps.contains("if (onOpenOnGlass != null)"))
        assertFalse(maps.contains("Start Mapping · coming soon"))
    }

    @Test fun appMapsStartsStopsAndReportsThroughTheMappingRuntime() {
        val maps = source("ui/v32/AppMapsSettings.kt")
        assertTrue(maps.contains("MappingDriverRuntime.startForPackage(context, packageName)"))
        assertTrue(maps.contains("MappingDriverRuntime.stop(context, run.jobId)"))
        assertTrue(maps.contains("MappingDriverRuntime.pause(context, run.jobId)"))
        assertTrue(maps.contains("MappingDriverRuntime.resume(context, run.jobId)"))
        assertTrue(maps.contains("MappingDriverRuntime.report(context)"))
        // Mapping never claims to run on a live map, and never shows raw values.
        assertFalse(maps.contains("persona = \"live\""))
    }

    @Test fun vaultSettingsLoadsSlotMetadataOnly() {
        val panel = source("secrets/VaultSettingsSection.kt")
        assertTrue(panel.contains("SecretsVaultRuntime.allSlots(context)"))
        assertTrue(panel.contains("VaultSettingsPresenter.rows(slots)"))
        assertFalse(panel.contains("readSecret"))
        assertFalse(panel.contains("decrypt"))
    }

    private fun source(relative: String): String {
        val path = "src/main/java/com/cyclone/mobile/$relative"
        return sequenceOf(File(path), File("apps/mobile/app/$path"))
            .first { it.isFile }.readText()
    }
}
