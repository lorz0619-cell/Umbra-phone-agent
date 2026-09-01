package com.bluewhale.agent.core.graph

import com.bluewhale.agent.model.ActionHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflectionPolicyTest {
    @Test
    fun detectsVerifiedTwoStateLoop() {
        val history =
            listOf(
                entry(1, "Tap(search)", "tap:search", "page-a", "page-b", true),
                entry(2, "Tap(back)", "tap:back", "page-b", "page-a", true),
                entry(3, "Tap(search)", "tap:search", "page-a", "page-b", true),
                entry(4, "Tap(back)", "tap:back", "page-b", "page-a", true),
            )

        val result = ReflectionPolicy.assess(history, 0, 0)

        assertEquals("two_state_loop", result?.trigger)
        assertEquals(2, result?.blockedActionKeys?.size)
    }

    @Test
    fun detectsRepeatedFailedTapBeforeCircuitBreaker() {
        val history =
            listOf(
                entry(1, "Tap(input)", "tap:visual:input", "chat", "chat", false),
                entry(2, "Tap(input)", "tap:visual:input", "chat", "chat", false),
            )

        val result = ReflectionPolicy.assess(history, tapTargetAttempts = 2, nearbyTapAttempts = 2)

        assertEquals("tap_stuck", result?.trigger)
        assertTrue(result?.blockedActionKeys?.contains("chat|tap:visual:input") == true)
    }

    @Test
    fun ignoresForwardProgress() {
        val history =
            listOf(
                entry(1, "Launch", "launch:qq", "home", "list", true),
                entry(2, "Tap(contact)", "tap:contact", "list", "chat", true),
                entry(3, "Type", "type:123", "chat", "draft", true),
                entry(4, "Tap(send)", "tap:send", "draft", "sent", true),
            )

        assertNull(ReflectionPolicy.assess(history, 0, 0))
    }

    private fun entry(
        step: Int,
        action: String,
        actionKey: String,
        before: String,
        after: String,
        verified: Boolean,
    ) = ActionHistoryEntry(
        step = step,
        action = action,
        result = if (verified) "ok" else "failed",
        verified = verified,
        packageName = "test",
        actionKey = actionKey,
        beforeStateSignature = before,
        afterStateSignature = after,
    )
}
