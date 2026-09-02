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
    fun detectsConsecutiveSameActionEvenWhenVerified() {
        val history =
            listOf(
                entry(1, "Wait(2000ms)", "wait:2000", "page-a", "page-a", true),
                entry(2, "Wait(2000ms)", "wait:2000", "page-a", "page-a", true),
                entry(3, "Wait(2000ms)", "wait:2000", "page-a", "page-a", true),
            )

        val result = ReflectionPolicy.assess(history, 0, 0)

        assertEquals("single_action_loop", result?.trigger)
        assertEquals(1, result?.blockedActionKeys?.size)
    }

    @Test
    fun detectsThreeStateLoop() {
        val history =
            listOf(
                entry(1, "Tap(a)", "tap:a", "page-a", "page-b", true),
                entry(2, "Tap(b)", "tap:b", "page-b", "page-c", true),
                entry(3, "Tap(c)", "tap:c", "page-c", "page-a", true),
                entry(4, "Tap(a)", "tap:a", "page-a", "page-b", true),
                entry(5, "Tap(b)", "tap:b", "page-b", "page-c", true),
                entry(6, "Tap(c)", "tap:c", "page-c", "page-a", true),
            )

        val result = ReflectionPolicy.assess(history, 0, 0)

        assertEquals("three_state_loop", result?.trigger)
        assertEquals(3, result?.blockedActionKeys?.size)
    }

    @Test
    fun detectsFourStateLoop() {
        val history =
            listOf(
                entry(1, "Tap(a)", "tap:a", "page-a", "page-b", true),
                entry(2, "Tap(b)", "tap:b", "page-b", "page-c", true),
                entry(3, "Tap(c)", "tap:c", "page-c", "page-d", true),
                entry(4, "Tap(d)", "tap:d", "page-d", "page-a", true),
                entry(5, "Tap(a)", "tap:a", "page-a", "page-b", true),
                entry(6, "Tap(b)", "tap:b", "page-b", "page-c", true),
                entry(7, "Tap(c)", "tap:c", "page-c", "page-d", true),
                entry(8, "Tap(d)", "tap:d", "page-d", "page-a", true),
            )

        val result = ReflectionPolicy.assess(history, 0, 0)

        assertEquals("four_state_loop", result?.trigger)
        assertEquals(4, result?.blockedActionKeys?.size)
    }

    @Test
    fun detectsRepeatedWaitEvenWithVisualProgress() {
        val history =
            listOf(
                entry(1, "Wait(3000ms)", "wait:3000", "page-a", "page-a", true),
            )

        val result =
            ReflectionPolicy.assess(
                history = history,
                tapTargetAttempts = 0,
                nearbyTapAttempts = 0,
                repeatedWaitCount = 3,
            )

        assertEquals("repeated_wait", result?.trigger)
    }

    @Test
    fun detectsRepeatedTapCoordinate() {
        val history =
            listOf(
                entry(1, "Tap(search)", "tap:element:84", "page-a", "page-a", true),
            )

        val result =
            ReflectionPolicy.assess(
                history = history,
                tapTargetAttempts = 0,
                nearbyTapAttempts = 0,
                repeatedTapCoordinateCount = 4,
                currentTapCoordinateKey = "tap:coord:465:267",
            )

        assertEquals("repeated_tap_coordinate", result?.trigger)
        assertTrue(result?.blockedTapCoordinates?.contains("tap:coord:465:267") == true)
    }

    @Test
    fun detectsRepeatedTypeFailure() {
        val history =
            listOf(
                entry(1, "Type", "type:1:element:12", "page-a", "page-a", false),
            )

        val result =
            ReflectionPolicy.assess(
                history = history,
                tapTargetAttempts = 0,
                nearbyTapAttempts = 0,
                repeatedTypeFailureCount = 3,
            )

        assertEquals("repeated_type_failure", result?.trigger)
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
        actionSucceeded = verified,
        subtaskProgressed = false,
    )
}
