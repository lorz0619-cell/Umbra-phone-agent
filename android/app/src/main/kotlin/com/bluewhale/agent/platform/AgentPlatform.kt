package com.bluewhale.agent.platform

import android.graphics.Bitmap
import com.bluewhale.agent.model.ActionResult
import com.bluewhale.agent.model.AgentAction
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

    suspend fun performAction(action: AgentAction): ActionResult

    suspend fun isTextInputFocused(): Boolean = false
}