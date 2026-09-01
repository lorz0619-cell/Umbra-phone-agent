package com.bluewhale.agent.perception

import android.graphics.Bitmap
import com.bluewhale.agent.model.AccessibilitySnapshot
import com.bluewhale.agent.model.PerceptionSnapshot
import com.bluewhale.agent.platform.AgentPlatform
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope

/** Hybrid perception: a visual frame plus a compact semantic accessibility tree. */
class PerceptionEngine(
    private val fingerprintColumns: Int = 24,
    private val fingerprintRows: Int = 24,
) {
    suspend fun capture(platform: AgentPlatform): PerceptionSnapshot = coroutineScope {
        val screenDeferred = async { platform.captureScreen() }
        val treeDeferred =
            async {
                try {
                    platform.captureAccessibility()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    AccessibilitySnapshot()
                }
            }
        val screen = screenDeferred.await()
        PerceptionSnapshot(
            screen = screen,
            accessibility = treeDeferred.await(),
            visualFingerprint = fingerprint(screen.bitmap),
        )
    }

    fun fingerprint(bitmap: Bitmap): IntArray {
        val values = IntArray(fingerprintColumns * fingerprintRows)
        var index = 0
        for (row in 0 until fingerprintRows) {
            val y = ((row + 0.5) * bitmap.height / fingerprintRows)
                .toInt()
                .coerceIn(0, bitmap.height - 1)
            for (column in 0 until fingerprintColumns) {
                val x = ((column + 0.5) * bitmap.width / fingerprintColumns)
                    .toInt()
                    .coerceIn(0, bitmap.width - 1)
                val color = bitmap.getPixel(x, y)
                val red = color shr 16 and 0xff
                val green = color shr 8 and 0xff
                val blue = color and 0xff
                values[index++] = (red * 299 + green * 587 + blue * 114) / 1000
            }
        }
        return values
    }
}
