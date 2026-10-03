package com.cyclone.mobile.runtime.workspaces

import org.junit.Assert.*
import org.junit.Test

class ProfilePresentationPolicyTest {
    @Test fun ownerExistsWithoutWorkspacesOrSavedRecord() {
        assertEquals(setOf(0, 12), ProfilePresentationPolicy.visibleUsers(emptySet(), setOf(12), setOf(0), 0, 0))
    }
    @Test fun alternateProcessStillIncludesOwner() {
        assertEquals(setOf(0, 12), ProfilePresentationPolicy.visibleUsers(emptySet(), emptySet(), emptySet(), 12, 0))
    }
    @Test fun unknownRootIdentityDoesNotInventCurrentProfile() {
        assertFalse(ProfilePresentationPolicy.isCurrent(12, null))
        assertFalse(ProfilePresentationPolicy.isCurrent(12, 0))
        assertTrue(ProfilePresentationPolicy.isCurrent(0, 0))
    }
    @Test fun foregroundActivityBelongsToProcessNotSelectedAlternate() {
        assertTrue(ProfilePresentationPolicy.foregroundActive(0, 0, true))
        assertFalse(ProfilePresentationPolicy.foregroundActive(12, 0, true))
        assertFalse(ProfilePresentationPolicy.foregroundActive(0, 0, false))
    }
    @Test fun taskEntryRequiresVerifiedCurrentProcessAndNoSwitch() {
        assertTrue(ProfilePresentationPolicy.canStartTask(12, 12, 12, false))
        assertFalse(ProfilePresentationPolicy.canStartTask(12, 0, 0, false))
        assertFalse(ProfilePresentationPolicy.canStartTask(0, 0, null, false))
        assertFalse(ProfilePresentationPolicy.canStartTask(0, 0, 12, false))
        assertFalse(ProfilePresentationPolicy.canStartTask(0, 0, 0, true))
    }
    @Test fun nonzeroMainUserIsPreserved() {
        assertEquals(setOf(10, 12), ProfilePresentationPolicy.visibleUsers(emptySet(), setOf(12), setOf(10), 10, 10))
    }
}
