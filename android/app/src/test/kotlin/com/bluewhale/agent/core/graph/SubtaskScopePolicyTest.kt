package com.bluewhale.agent.core.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtaskScopePolicyTest {
    @Test
    fun `recovery cannot rename a failed subtask to reset its budget`() {
        val result =
            SubtaskScopePolicy.resolve(
                currentLabel = "submit_search",
                currentKey = "submit_search",
                proposedLabel = "tap_search_button_again",
                expectedOutcome = "进入搜索结果页",
                fallback = "搜索霸王茶姬",
                allowTransition = false,
            )

        assertEquals("submit_search", result.label)
        assertEquals("submit_search", result.key)
        assertFalse(result.changed)
    }

    @Test
    fun `verified progress allows transition to the next subtask`() {
        val result =
            SubtaskScopePolicy.resolve(
                currentLabel = "submit_search",
                currentKey = "submit_search",
                proposedLabel = "select_store",
                expectedOutcome = "进入店铺页",
                fallback = "选择店铺",
                allowTransition = true,
            )

        assertEquals("select_store", result.label)
        assertEquals("select_store", result.key)
        assertTrue(result.changed)
    }

    @Test
    fun `three recovery reflections remain executable`() {
        for (count in 0..SubtaskScopePolicy.MAX_RECOVERY_ATTEMPTS) {
            assertFalse(
                "reflection count $count should not enter terminal adjudication",
                SubtaskScopePolicy.shouldTerminalAdjudicate(count),
            )
        }
    }

    @Test
    fun `fourth reflection asks terminal adjudicator`() {
        assertTrue(
            SubtaskScopePolicy.shouldTerminalAdjudicate(
                SubtaskScopePolicy.MAX_RECOVERY_ATTEMPTS + 1,
            ),
        )
    }
}
