package com.cyclone.mobile.runtime.background

import org.junit.Assert.*
import org.junit.Test

class ProfileDestinationPresentationTest {
    @Test fun currentProfileExistsWithoutAppWorkspaces() {
        assertEquals(listOf(WorkspaceDestinationHint("Profile A", 0)), ProfileDestinationPresentation.from(0, emptyList()))
    }
    @Test fun currentProfileIsNotAssumedToBeUserZero() {
        assertEquals(listOf(WorkspaceDestinationHint("Profile A", 10), WorkspaceDestinationHint("Profile B", 12)),
            ProfileDestinationPresentation.from(10, listOf(12, 10, 12)))
    }
    @Test fun multipleVisibleProfilesAreNotTruncatedToOne() {
        assertEquals(listOf(0, 10, 12), ProfileDestinationPresentation.from(0, listOf(12, -1, 10)).map { it.androidUserId })
    }
}
