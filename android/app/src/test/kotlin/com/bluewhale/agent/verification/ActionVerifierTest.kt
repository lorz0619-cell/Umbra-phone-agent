package com.bluewhale.agent.verification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionVerifierTest {
    @Test
    fun visualChangeScoreIsNormalized() {
        assertEquals(
            0.0,
            ActionVerifier.visualChangeScore(
                intArrayOf(10, 20, 30),
                intArrayOf(10, 20, 30),
            ),
            0.000001,
        )
        assertTrue(
            ActionVerifier.visualChangeScore(
                intArrayOf(0, 0, 0),
                intArrayOf(255, 255, 255),
            ) > 0.99,
        )
    }

    @Test
    fun incompatibleFingerprintsFailClosed() {
        assertEquals(
            1.0,
            ActionVerifier.visualChangeScore(intArrayOf(1), intArrayOf(1, 2)),
            0.0,
        )
    }
}
