package com.cyclone.mobile.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestIntentRouterTest {
    @Test
    fun attachedImageQuestionsStayChat() {
        assertEquals(RequestIntent.CHAT, RequestIntentRouter.route("How many bananas are in this picture?", true).intent)
        assertEquals(RequestIntent.CHAT, RequestIntentRouter.route("What is in this photo?", true).intent)
    }

    @Test
    fun generalKnowledgeAndWritingStayChat() {
        listOf(
            "What is 2+2?",
            "Summarize this text",
            "Explain quantum computing",
            "Write an email to my manager",
            "What's the capital of France?",
            "How do I open Chrome?",
            "Explain how to open Chrome",
        ).forEach { prompt ->
            val result = RequestIntentRouter.route(prompt)
            assertEquals(prompt, RequestIntent.CHAT, result.intent)
            assertFalse(prompt, result.requiresAndroidAccess)
            assertFalse(prompt, result.classifierProviderRequest)
        }
    }

    @Test
    fun obviousAndroidRequestsRouteToPhoneTask() {
        listOf(
            "Open Chrome",
            "Open Settings",
            "Open Android Settings",
            "Check Instagram to see if I'm logged in",
            "Turn on Wi-Fi",
            "Could you turn off Bluetooth",
            "Check my notifications",
            "Check my phone notifications",
            "Open Instagram and search for coffee",
            "Go to Starbucks and prepare my usual order",
            "Can you open Chrome?",
            "Open Firefox",
        ).forEach { prompt ->
            val result = RequestIntentRouter.route(prompt)
            assertEquals(prompt, RequestIntent.PHONE_TASK, result.intent)
            assertTrue(prompt, result.requiresAndroidAccess)
            assertFalse(prompt, result.classifierProviderRequest)
        }
    }

    @Test
    fun explicitSearchAssignmentsRouteToPhoneTask() {
        listOf(
            "search for ganamstyle popularity today",
            "search on google",
            "Search for cool sneakers",
            "Look up Gangnam Style popularity today",
            "browse for new running shoes",
            "Can you search for coffee shops?",
            "find a hotel close by",
            "look when I have that appointment",
            "What's on my calendar today",
        ).forEach { prompt ->
            val result = RequestIntentRouter.route(prompt)
            assertEquals(prompt, RequestIntent.PHONE_TASK, result.intent)
            assertTrue(prompt, result.requiresAndroidAccess)
        }
    }

    @Test
    fun attachmentAloneNeverForcesPhoneTask() {
        val result = RequestIntentRouter.route("", hasAttachment = true)
        assertEquals(RequestIntent.CHAT, result.intent)
        assertEquals(AttachmentRelevance.CHAT_CONTEXT, result.attachmentRelevance)
    }

    @Test
    fun explicitPhoneReferenceKeepsAttachmentOnPhoneRoute() {
        val result = RequestIntentRouter.route("Use this image and post it on Instagram", hasAttachment = true)
        assertEquals(RequestIntent.PHONE_TASK, result.intent)
        assertEquals(AttachmentRelevance.PHONE_REFERENCE, result.attachmentRelevance)
    }

    @Test
    fun ambiguousConsequentialRequestFallsBackToClarifyingChat() {
        val result = RequestIntentRouter.route("Order me a coffee")
        assertEquals(RequestIntent.CHAT, result.intent)
        assertEquals(RequestIntentConfidence.LOW, result.confidence)
        assertFalse(result.requiresAndroidAccess)
        assertTrue(result.reason.contains("clarify", ignoreCase = true))
    }

    @Test
    fun dispatchNeverQueuesChatAndNeverReplacesActiveTask() {
        val chat = RequestIntentRouter.route("What is 2+2?")
        val phone = RequestIntentRouter.route("Open Chrome")

        assertEquals(RequestDispatch.CHAT, RequestIntentRouter.dispatch(chat, canStartPhoneTask = true))
        assertEquals(RequestDispatch.CHAT, RequestIntentRouter.dispatch(chat, canStartPhoneTask = false))
        assertEquals(RequestDispatch.START_PHONE_TASK, RequestIntentRouter.dispatch(phone, canStartPhoneTask = true))
        assertEquals(RequestDispatch.QUEUE_PHONE_TASK, RequestIntentRouter.dispatch(phone, canStartPhoneTask = false))
    }
}