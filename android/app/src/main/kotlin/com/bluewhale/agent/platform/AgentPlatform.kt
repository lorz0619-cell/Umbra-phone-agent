package com.bluewhale.agent.platform

import android.graphics.Bitmap
import com.bluewhale.agent.model.AccessibilitySnapshot
import com.bluewhale.agent.model.ActionResult
import com.bluewhale.agent.model.DeviceAction
import com.bluewhale.agent.model.ScreenCapture
import com.bluewhale.agent.model.TargetMode
import kotlinx.coroutines.flow.StateFlow

interface AgentPlatform {
    val mode: TargetMode
    val preview: StateFlow<Bitmap?> get() = kotlinx.coroutines.flow.MutableStateFlow(null)

    fun isAvailable(): Boolean

    suspend fun start()

    suspend fun stop()

    suspend fun captureScreen(): ScreenCapture

    suspend fun captureAccessibility(): AccessibilitySnapshot = AccessibilitySnapshot()

    suspend fun performAction(action: DeviceAction): ActionResult

    suspend fun isTextInputFocused(): Boolean = false

    /** Moves a retained virtual-screen task to the physical display after user approval. */
    suspend fun handoffToMainScreen(): ActionResult =
        ActionResult.Failure("当前执行屏幕不支持迁移接管")
}
