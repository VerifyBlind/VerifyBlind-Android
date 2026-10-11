package com.verifyblind.mobile.util

import com.google.android.play.core.integrity.model.StandardIntegrityErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegrityRetryPolicyTest {

    @Test
    fun `internal error is retried with growing delays then given up`() {
        val code = StandardIntegrityErrorCode.INTERNAL_ERROR
        assertEquals(500L, IntegrityRetryPolicy.delayBeforeRetry(code, 0))
        assertEquals(1_500L, IntegrityRetryPolicy.delayBeforeRetry(code, 1))
        assertEquals(3_000L, IntegrityRetryPolicy.delayBeforeRetry(code, 2))
        assertNull(IntegrityRetryPolicy.delayBeforeRetry(code, 3))
    }

    @Test
    fun `transient codes are retried`() {
        listOf(
            StandardIntegrityErrorCode.GOOGLE_SERVER_UNAVAILABLE,
            StandardIntegrityErrorCode.CLIENT_TRANSIENT_ERROR,
            StandardIntegrityErrorCode.INTEGRITY_TOKEN_PROVIDER_INVALID,
        ).forEach { assertEquals(500L, IntegrityRetryPolicy.delayBeforeRetry(it, 0)) }
    }

    @Test
    fun `permanent codes, network and unknown exceptions fail fast`() {
        listOf(
            StandardIntegrityErrorCode.CANNOT_BIND_TO_SERVICE,
            StandardIntegrityErrorCode.PLAY_SERVICES_VERSION_OUTDATED,
            StandardIntegrityErrorCode.TOO_MANY_REQUESTS,
            StandardIntegrityErrorCode.NETWORK_ERROR,
            StandardIntegrityErrorCode.API_NOT_AVAILABLE,
            null,
        ).forEach { assertNull("kod $it", IntegrityRetryPolicy.delayBeforeRetry(it, 0)) }
    }

    @Test
    fun `only provider invalid drops the cached provider`() {
        assertTrue(IntegrityRetryPolicy.invalidatesProvider(StandardIntegrityErrorCode.INTEGRITY_TOKEN_PROVIDER_INVALID))
        assertFalse(IntegrityRetryPolicy.invalidatesProvider(StandardIntegrityErrorCode.INTERNAL_ERROR))
        assertFalse(IntegrityRetryPolicy.invalidatesProvider(null))
    }
}
