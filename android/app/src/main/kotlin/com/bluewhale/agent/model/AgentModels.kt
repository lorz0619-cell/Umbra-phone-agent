package com.bluewhale.agent.model

import android.graphics.Bitmap

enum class TargetMode(val label: String) {
    MAIN_SCREEN("主屏"),
    VIRTUAL_DISPLAY("虚拟屏"),
}

data class AutoGlmConfig(
    val apiKey: String,
    val baseUrl: String = "https://open.bigmodel.cn/api/paas/v4",
    val model: String = "autoglm-phone",
    val maxSteps: Int = 40,
)

data class AgentAction(
    val kind: String,
    val params: Map<String, Any?> = emptyMap(),
    val message: String = "",
)

data class ScreenCapture(
    val width: Int,
    val height: Int,
    val bitmap: Bitmap,
)

sealed interface ActionResult {
    data class Success(val message: String) : ActionResult
    data class Failure(val message: String, val cause: Throwable? = null) : ActionResult
}

data class AgentRunState(
    val running: Boolean = false,
    val task: String = "",
    val mode: TargetMode? = null,
    val step: Int = 0,
    val status: String = "未运行",
    val logs: List<String> = emptyList(),
)
