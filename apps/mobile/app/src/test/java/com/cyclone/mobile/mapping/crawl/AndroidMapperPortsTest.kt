package com.cyclone.mobile.mapping.crawl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidMapperPortsTest {
    @Test
    fun ordinaryContentChangesDoNotChangeStructuralFingerprintOrDoorKey() {
        val alice = MappingStructuralProjection.project(
            observationId = "obs-a",
            sessionId = "workspace-map",
            displayId = 7,
            rawFingerprint = "raw-a",
            elements = listOf(contentRow("obs-a", "Alice Example")),
        )
        val bob = MappingStructuralProjection.project(
            observationId = "obs-b",
            sessionId = "workspace-map",
            displayId = 7,
            rawFingerprint = "raw-b",
            elements = listOf(contentRow("obs-b", "Bob Person")),
        )

        assertEquals(alice.structuralFingerprint, bob.structuralFingerprint)
        assertEquals(alice.doors.single().key, bob.doors.single().key)
        assertEquals(MappingDoorKind.STRUCTURAL_SAMPLE, alice.doors.single().kind)
        assertFalse(alice.doors.single().key.contains("Alice"))
        assertFalse(bob.doors.single().key.contains("Bob"))
    }

    @Test
    fun structuralResourceChangeChangesFingerprint() {
        val first = MappingStructuralProjection.project(
            "obs-a",
            "workspace-map",
            7,
            "raw-a",
            listOf(
                RawMappingElement(
                    elementId = "semantic:obs-a:settings",
                    role = "button",
                    resourceId = "com.example:id/settings",
                    className = "android.widget.Button",
                    path = "/0/1",
                    clickable = true,
                ),
            ),
        )
        val second = MappingStructuralProjection.project(
            "obs-b",
            "workspace-map",
            7,
            "raw-b",
            listOf(
                RawMappingElement(
                    elementId = "semantic:obs-b:search",
                    role = "button",
                    resourceId = "com.example:id/search",
                    className = "android.widget.Button",
                    path = "/0/1",
                    clickable = true,
                ),
            ),
        )

        assertNotEquals(first.structuralFingerprint, second.structuralFingerprint)
    }

    @Test
    fun settingsButtonBecomesStructuralDoorWithoutPersistingRenderedLabel() {
        val rendered = "Settings for Alice Example"
        val projected = MappingStructuralProjection.project(
            "obs-a",
            "default-foreground",
            0,
            "raw-a",
            listOf(
                RawMappingElement(
                    elementId = "semantic:obs-a:settings",
                    label = rendered,
                    semanticName = "Settings",
                    role = "button",
                    resourceId = "com.example:id/settings_button",
                    className = "android.widget.Button",
                    path = "/0/4",
                    clickable = true,
                ),
            ),
        )

        val door = projected.doors.single()
        assertEquals(MappingDoorKind.SETTINGS, door.kind)
        assertTrue(door.key.startsWith("door:settings:"))
        assertFalse(door.key.contains(rendered))
        assertFalse(projected.structuralFingerprint.contains(rendered))
    }

    @Test
    fun onlyOneUnknownContentRowIsEligibleAsStructuralSample() {
        val projected = MappingStructuralProjection.project(
            "obs-a",
            "workspace-map",
            7,
            "raw-a",
            listOf(
                contentRow("obs-a", "Alice Example", path = "/0/1"),
                contentRow("obs-a", "Bob Person", path = "/0/2"),
                contentRow("obs-a", "Carol Person", path = "/0/3"),
            ),
        )

        assertEquals(1, projected.doors.count { it.kind == MappingDoorKind.STRUCTURAL_SAMPLE })
        assertEquals(2, projected.doors.count { it.kind == MappingDoorKind.CONTENT_ROW })
        assertEquals(StructuralScreenPurpose.LIST, projected.purpose)
    }

    @Test
    fun editableFieldsAreNeverExplorationDoors() {
        val projected = MappingStructuralProjection.project(
            "obs-a",
            "workspace-map",
            7,
            "raw-a",
            listOf(
                RawMappingElement(
                    elementId = "semantic:obs-a:password",
                    label = "Password",
                    semanticName = "Password",
                    role = "textbox",
                    resourceId = "com.example:id/password",
                    className = "android.widget.EditText",
                    path = "/0/2",
                    clickable = true,
                    editable = true,
                ),
            ),
        )

        assertTrue(projected.doors.isEmpty())
    }

    private fun contentRow(
        observationId: String,
        label: String,
        path: String = "/0/1",
    ) = RawMappingElement(
        elementId = "semantic:$observationId:row",
        label = label,
        semanticName = label,
        role = "listitem",
        resourceId = "com.example:id/thread_row",
        className = "android.widget.LinearLayout",
        path = path,
        clickable = true,
        editable = false,
        enabled = true,
        visible = true,
    )
}
