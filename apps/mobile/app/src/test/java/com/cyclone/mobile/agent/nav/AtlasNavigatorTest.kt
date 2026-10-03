package com.cyclone.mobile.agent.nav

import com.cyclone.mobile.brain.graphv2.*
import com.cyclone.mobile.mapping.crawl.MappingDanger
import com.cyclone.mobile.mapping.crawl.MappingDoorKind
import com.cyclone.mobile.mapping.crawl.VerifiedStructure
import com.cyclone.mobile.mapping.run.AtlasStoreMappingPort
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AtlasNavigatorTest {
    private val directory = Files.createTempDirectory("atlas-nav").toFile()
    private val store = AtlasStore(File(directory, "atlas.json"))
    private val navigator = AtlasNavigator()
    private val place = "package:com.google.android.gm"
    private val home = "screen:home:aaaaaaaaaaaaaaaa"
    private val account = "screen:account:bbbbbbbbbbbbbbbb"
    private val door = "door:account:1111111111111111"
    private val port = AtlasStoreMappingPort(store, place, "Gmail", clock = { 1000L })
    private val target = AtlasNavigator.Target("live-element", setOf(AtlasStoreMappingPort.doorDigest(door)), true)

    init {
        port.recordVerified(place, "mapping", VerifiedStructure(home, account, door,
            MappingDoorKind.ACCOUNT, "before", "after"))
    }

    @After fun clean() { directory.deleteRecursively() }

    private fun next(room: String = home, targets: List<AtlasNavigator.Target> = listOf(target)) =
        navigator.next(store, place, room, "account", "fresh-observation", targets)

    @Test fun realMapperDataResolvesOneLiveDoorAndChecksTheLanding() {
        val step = next()!!
        assertEquals("live-element", step.elementId)
        assertEquals(account, step.expectedRoom)
        navigator.dispatched(step)
        assertTrue(navigator.verified(step, place, account, true))
        assertNull(next()) // even an accepted click cannot be blindly repeated from the old room
    }

    @Test fun missingAmbiguousUnsafeOrWrongRoomNeverReplays() {
        assertNull(next(targets = emptyList()))
        assertNull(next(targets = listOf(target, target.copy(elementId = "duplicate"))))
        assertNull(next(targets = listOf(target.copy(safe = false))))
        assertNull(next(room = "screen:unknown:cccccccccccccccc"))
    }

    @Test fun unchangedOrCrossPlaceLandingDoesNotProveSuccess() {
        val step = next()!!
        navigator.dispatched(step)
        assertFalse(navigator.verified(step, place, home, true))
        assertNull(next())
        assertFalse(navigator.verified(step, "package:another.app", account, true))
    }

    @Test fun dangerousRouteIsNeverAutomatic() {
        port.markDanger(place, "mapping", account, "door:menu:2222222222222222", MappingDanger.PAY)
        assertNull(next())
    }

    @Test fun wrongMappedRoomReroutesToTheOriginalDestination() {
        val detour = "screen:menu:cccccccccccccccc"
        val recoveryDoor = "door:account:3333333333333333"
        port.recordVerified(place, "mapping", VerifiedStructure(detour, account, recoveryDoor,
            MappingDoorKind.ACCOUNT, "detour", "account"))
        val first = next()!!
        navigator.dispatched(first)
        assertFalse(navigator.verified(first, place, detour, true))
        val second = next(detour, listOf(AtlasNavigator.Target("recovery-element",
            setOf(AtlasStoreMappingPort.doorDigest(recoveryDoor)), true)))!!
        assertTrue(second.rerouted)
        assertEquals(account, second.targetRoom)
        assertEquals(detour, second.fromRoom)
        assertEquals("recovery-element", second.elementId)
    }

    @Test fun unmappedDetourOrNoRouteFallsBackWithoutAnotherTap() {
        val first = next()!!
        navigator.dispatched(first)
        navigator.verified(first, place, "screen:menu:dddddddddddddddd", true)
        assertNull(next("screen:menu:dddddddddddddddd"))
    }
}
