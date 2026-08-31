package com.bluewhale.agent.core

import android.util.Log
import android.graphics.Bitmap
import com.bluewhale.agent.model.ActionResult
import com.bluewhale.agent.model.AgentAction
import com.bluewhale.agent.model.AgentRunState
import com.bluewhale.agent.model.ScreenCapture
import com.bluewhale.agent.platform.AgentPlatform

private const val TAG = "BluewhaleAgent"

class AgentLoop(
    private val platform: AgentPlatform,
    private val client: AutoGlmClient,
    private val task: String,
    private val maxSteps: Int = 20,
    private val onState: (AgentRunState) -> Unit,
) {
    private var previousAction: AgentAction? = null
    private var previousScreenSignature: Long? = null
    suspend fun run(): String {
        try {
            platform.start()
        } catch (error: Exception) {
            runCatching { platform.stop() }
            throw error
        }
        try {
            repeat(maxSteps) { index ->
                val step = index + 1
                onState(
                    AgentRunState(
                        running = true,
                        task = task,
                        mode = platform.mode,
                        step = step,
                        status = "正在获取屏幕状态",
                    ),
                )

                val capture = platform.captureScreen()
                val currentSignature = screenSignature(capture.bitmap)
                Log.i(TAG, "step=$step mode=${platform.mode} 正在获取屏幕状态")
                try {
                    val screenChanged = previousScreenSignature != null && previousScreenSignature != currentSignature
                    val typeHint = if (previousAction?.kind?.equals("type", true) == true && screenChanged) {
                        "文字已输入。请继续观察当前页面，优先根据搜索推荐、联想词或结果列表选择最符合用户目标的下一步，不要立即 finish。"
                    } else {
                        null
                    }
                    val launchHint = if (previousAction?.kind?.equals("launch", true) == true && screenChanged) {
                        "上一动作 Launch 后界面已经发生变化，说明应用已打开；不要再次 Launch，请直接继续任务。"
                    } else {
                        null
                    }
                    val response = client.nextAction(capture.bitmap, task, launchHint ?: typeHint)
                    Log.i(TAG, "step=$step response=${response.replace("\n", " | ")}")
                    onState(
                        AgentRunState(
                            running = true,
                            task = task,
                            mode = platform.mode,
                            step = step,
                            status = response.lineSequence().lastOrNull().orEmpty(),
                        ),
                    )

                    var parsed = ActionParser.parse(response)
                    Log.i(TAG, "step=$step parsed=${parsed?.kind} params=${parsed?.params}")
                    if (parsed == null) {
                        Log.w(TAG, "step=$step no action parsed, retrying")
                        val retryResponse = client.nextAction(capture.bitmap, task, "上一轮没有输出动作。现在只输出一行 do(action=...) 或 finish(...)，不要任何解释。")
                        Log.i(TAG, "step=$step retryResponse=${retryResponse.replace("\n", " | ")}")
                        parsed = ActionParser.parse(retryResponse)
                    }
                    if (parsed == null) {
                        onState(
                            AgentRunState(
                                running = false,
                                task = task,
                                mode = platform.mode,
                                step = step,
                                status = "无法解析模型动作，已停止",
                            ),
                        )
                        return "无法解析模型动作：$response"
                    }
                    if (parsed.kind.equals("finish", ignoreCase = true)) {
                        onState(
                            AgentRunState(
                                running = false,
                                task = task,
                                mode = platform.mode,
                                step = step,
                                status = parsed.message.ifBlank { "任务完成" },
                            ),
                        )
                        return parsed.message.ifBlank { "任务完成" }
                    }

                    val normalized = normalize(parsed, capture)
                    Log.i(TAG, "step=$step normalized=${normalized.kind} params=${normalized.params}")
                    val result = platform.performAction(normalized)
                    previousAction = normalized
                    previousScreenSignature = currentSignature
                    Log.i(TAG, "step=$step result=$result")
                    when (result) {
                        is ActionResult.Success -> {
                            onState(
                                AgentRunState(
                                    running = true,
                                    task = task,
                                    mode = platform.mode,
                                    step = step,
                                    status = result.message,
                                ),
                            )
                        }
                        is ActionResult.Failure -> {
                            onState(
                                AgentRunState(
                                    running = false,
                                    task = task,
                                    mode = platform.mode,
                                    step = step,
                                    status = result.message,
                                ),
                            )
                            return result.message
                        }
                    }
                } finally {
                    capture.bitmap.recycle()
                }
            }

            onState(
                AgentRunState(
                    running = false,
                    task = task,
                    mode = platform.mode,
                    step = maxSteps,
                    status = "达到最大步数",
                ),
            )
            return "达到最大步数"
        } finally {
            platform.stop()
        }
    }

    private fun screenSignature(bitmap: Bitmap): Long {
        var hash = 1125899906842597L
        val stepX = (bitmap.width / 24).coerceAtLeast(1)
        val stepY = (bitmap.height / 24).coerceAtLeast(1)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                hash = hash * 31 + bitmap.getPixel(x, y)
                x += stepX
            }
            y += stepY
        }
        return hash
    }




    private fun normalize(action: AgentAction, capture: ScreenCapture): AgentAction {
        val kind = action.kind.lowercase()
        return when (kind) {
            "tap", "long press", "double tap" -> {
                val point = action.params.point("element") ?: action.params.point("point")
                    ?: error("${action.kind} 缺少坐标")
                action.copy(
                    params =
                        action.params +
                            mapOf(
                                "x" to scale(point.first, capture.width),
                                "y" to scale(point.second, capture.height),
                            ),
                )
            }
            "swipe" -> {
                val start = action.params.point("start") ?: error("Swipe 缺少 start")
                val end = action.params.point("end") ?: error("Swipe 缺少 end")
                action.copy(
                    params =
                        action.params +
                            mapOf(
                                "startX" to scale(start.first, capture.width),
                                "startY" to scale(start.second, capture.height),
                                "endX" to scale(end.first, capture.width),
                                "endY" to scale(end.second, capture.height),
                            ),
                )
            }
            "wait" -> {
                val raw = action.params["duration"] ?: action.params["durationMs"] ?: "1"
                val seconds =
                    Regex("""\d+(?:\.\d+)?""")
                        .find(raw.toString())
                        ?.value
                        ?.toFloatOrNull()
                        ?: 1f
                action.copy(params = action.params + ("durationMs" to (seconds * 1000).toInt()))
            }
            else -> action
        }
    }

    private fun scale(value: Int, max: Int): Int =
        (value.coerceIn(0, 999) * max / 999).coerceIn(0, max - 1)

    private fun Map<String, Any?>.point(key: String): Pair<Int, Int>? {
        val value = this[key] ?: return null
        if (value !is List<*>) return null
        if (value.size < 2) return null
        val x = (value[0] as? Number)?.toInt() ?: return null
        val y = (value[1] as? Number)?.toInt() ?: return null
        return x to y
    }
}
