package com.cyclone.mobile.ai

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProviderRequestLifecycleTest {
    private val request = Request.Builder().url("https://fixture.invalid/completions").build()
    private fun context(budget: Long = 3_000) = ProviderRequests.context(UUID.randomUUID().toString(),
        "synthetic-${UUID.randomUUID()}", "exact/model", ProviderRequestPurpose.PHONE_TASK, budget)
    private fun client(reply: (Request) -> Pair<Int, String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val (status, body) = reply(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
            .header("Retry-After", "0").body(body.toResponseBody()).build()
    }.build()

    @Test fun permanentErrorsAndEmbedded403HaveExactlyOneAttempt() {
        listOf(401 to "{}", 402 to "{}", 403 to "{}", 400 to "{}", 200 to "{\"error\":{\"code\":403}}").forEach { expected ->
            var attempts = 0
            val result = ProviderRequests.execute(request, context(), client { attempts++; expected })
            assertEquals(expected.first, result.status)
            assertEquals(1, attempts)
        }
    }
    @Test fun transient503RecoversOnceWithoutChangingRequestOrModel() {
        var attempts = 0
        val ctx = context()
        val result = ProviderRequests.execute(request, ctx, client { actual ->
            assertEquals(request, actual)
            assertEquals("exact/model", ctx.modelId)
            attempts++
            if (attempts == 1) 503 to "{}" else 200 to "{\"ok\":true}"
        })
        assertEquals(2, attempts)
        assertEquals(200, result.status)
    }
    @Test fun repeated429StopsAtTwoAttempts() {
        var attempts = 0
        assertEquals(429, ProviderRequests.execute(request, context(), client { attempts++; 429 to "{}" }).status)
        assertEquals(2, attempts)
    }
    @Test fun lateResponseAfterStopIsDiscardedAndUnrelatedRequestStillWorks() {
        val stopped = context()
        val error = runCatching { ProviderRequests.execute(request, stopped, client {
            stopped.cancellation.cancel(); 200 to "late action"
        }) }.exceptionOrNull()
        assertEquals("provider.cancelled", (error as ProviderLifecycleException).reason)
        assertEquals(200, ProviderRequests.execute(request, context(), client { 200 to "other task" }).status)
    }
    @Test fun cancellationDuringBackoffPreventsSecondAttempt() {
        var attempts = 0
        val cancellation = ProviderCancellation()
        val ctx = context().copy(cancellation = cancellation, onPhase = { phase, _ ->
            if (phase == "provider_backoff") cancellation.cancel()
        })
        val error = runCatching { ProviderRequests.execute(request, ctx, client { attempts++; 503 to "{}" }) }.exceptionOrNull()
        assertEquals("provider.cancelled", (error as ProviderLifecycleException).reason)
        assertEquals(1, attempts)
    }
    @Test fun responseAfterDeadlineCannotEscape() {
        val ctx = context(25)
        val error = runCatching { ProviderRequests.execute(request, ctx, client { Thread.sleep(60); 200 to "late" }) }.exceptionOrNull()
        assertEquals("provider.deadline", (error as ProviderLifecycleException).reason)
    }
    @Test fun pacingAllowsOneHalfOpenProbeAndIsolatesAccounts() {
        var clock = 0L
        val pacing = ProviderPacing { clock }
        pacing.enter("account-a/model")
        assertTrue(runCatching { pacing.enter("account-a/model") }.isFailure)
        pacing.enter("account-b/model")
        pacing.leave("account-a/model", 500)
        assertTrue(runCatching { pacing.enter("account-a/model") }.isFailure)
        clock = 500
        pacing.enter("account-a/model")
        assertTrue(runCatching { pacing.enter("account-a/model") }.isFailure)
    }
    @Test fun contextContainsFingerprintAndNeverApiKey() {
        val ctx = ProviderRequests.context("task", "synthetic-private-key", "model", ProviderRequestPurpose.CHAT)
        assertFalse(ctx.toString().contains("synthetic-private-key"))
        assertNotEquals(ctx.accountFingerprint, ProviderRequests.context("task", "replacement", "model", ProviderRequestPurpose.CHAT).accountFingerprint)
    }
    @Test fun keyReplacementInvalidatesQueuedOldAccountRequests() {
        val ctx = context()
        ProviderRequests.invalidateAccount(ctx.accountFingerprint)
        var attempts = 0
        val error = runCatching { ProviderRequests.execute(request, ctx, client { attempts++; 200 to "{}" }) }.exceptionOrNull()
        assertEquals("provider.cancelled", (error as ProviderLifecycleException).reason)
        assertEquals(0, attempts)
        ProviderRequests.activateAccount(ctx.accountFingerprint)
        assertEquals(200, ProviderRequests.execute(request, ctx, client { 200 to "{}" }).status)
    }
    @Test fun coroutineCancellationCancelsOnlyItsOwnRequest() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val ctx = context()
        var returned = false
        val job = launch(Dispatchers.Default) {
            ProviderRequests.executeAsync(request, ctx, client {
                entered.countDown()
                try { release.await(2, TimeUnit.SECONDS); 200 to "late" } finally { closed.countDown() }
            })
            returned = true
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue(ctx.cancellation.cancelled)
            assertFalse(returned)
            assertEquals(200, ProviderRequests.execute(request, context(), client { 200 to "unrelated" }).status)
        } finally { release.countDown() }
        assertTrue(closed.await(2, TimeUnit.SECONDS))
    }
}
