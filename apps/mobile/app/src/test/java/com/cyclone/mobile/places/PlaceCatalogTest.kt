package com.cyclone.mobile.places

import com.cyclone.mobile.brain.graphv2.AtlasPersona
import com.cyclone.mobile.brain.graphv2.AtlasStore
import com.cyclone.mobile.brain.graphv2.PlaceCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files

class PlaceCatalogTest {
    @Test fun observedNativeAndChromePlacesShareTheAtlasCatalogWithPersonaIsolation() {
        val file = Files.createTempFile("places", ".json").toFile().apply { delete() }
        try {
            AtlasStore(file).use { store ->
                val catalog = PlaceCatalog(store)
                val native = ResolvedPlace("package:com.example.app", "com.example.app", packageName = "com.example.app")
                val origin = ResolvedPlace("chrome:https://example.com", "https://example.com", origin = "https://example.com")
                catalog.recordObserved(native, AtlasPersona.LIVE)
                catalog.recordObserved(origin, AtlasPersona.MAPPING)
                catalog.recordObserved(origin, AtlasPersona.MAPPING)
                assertEquals(listOf("package:com.example.app"), catalog.all(AtlasPersona.LIVE).map { it.place.id })
                assertEquals(listOf("chrome:https://example.com"), catalog.all(AtlasPersona.MAPPING).map { it.place.id })
                assertEquals("https://example.com", catalog.chromeOrigins().single().place.label)
                assertFalse(file.readText().contains("/private?token"))
                assertThrows(IllegalArgumentException::class.java) {
                    catalog.addChromeOrigin("https://example.com/private?token=secret", "Private page", AtlasPersona.LIVE)
                }
            }
        } finally {
            file.delete()
        }
    }
}
