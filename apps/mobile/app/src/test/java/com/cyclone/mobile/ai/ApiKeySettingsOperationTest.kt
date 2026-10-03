package com.cyclone.mobile.ai

import org.junit.Assert.*
import org.junit.Test

class ApiKeySettingsOperationTest {
    @Test fun invalidKeyDoesNotReachStorage() {
        var writes = 0
        val error = ApiKeySettingsOperation.save("not-a-key", { writes++ }, { "" })
        assertNotNull(error)
        assertEquals(0, writes)
    }
    @Test fun storageExceptionNeverEscapesOrLeaksSecret() {
        val secret = "sk-or-test-private"
        val error = ApiKeySettingsOperation.save(secret, { throw IllegalStateException(secret) }, { "" })
        assertNotNull(error)
        assertFalse(error!!.contains(secret))
    }
    @Test fun successRequiresExactReadback() {
        assertNotNull(ApiKeySettingsOperation.save("sk-or-test", {}, { "sk-or-old" }))
        assertNull(ApiKeySettingsOperation.save(" sk-or-test ", {}, { "sk-or-test" }))
    }
    @Test fun removalFailureIsReportedWithoutExceptionDetails() {
        assertNotNull(ApiKeySettingsOperation.remove { throw SecurityException("secret") })
        assertNull(ApiKeySettingsOperation.remove {})
    }
}
