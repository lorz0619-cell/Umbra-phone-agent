package com.bluewhale.agent.core

import android.graphics.Bitmap
import android.util.Base64
import com.bluewhale.agent.model.AutoGlmConfig
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AutoGlmClient(
    private val config: AutoGlmConfig,
) {
    suspend fun nextAction(
        bitmap: Bitmap,
        task: String,
        hint: String? = null,
    ): String = withContext(Dispatchers.IO) {
        require(config.apiKey.isNotBlank()) { "缺少 AutoGLM API Key" }

        val connection =
            URL("${config.baseUrl.trimEnd('/')}/chat/completions").openConnection() as
                HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")

            val userContent =
                JSONArray()
                    .put(
                        JSONObject()
                            .put("type", "image_url")
                            .put(
                                "image_url",
                                JSONObject()
                                    .put("url", dataUrl(bitmap)),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("type", "text")
                            .put("text", task),
                    )

            val requestBody =
                JSONObject()
                    .put("model", config.model)
                    .put("temperature", 0)
                    .put("max_tokens", 2048)
                    .put(
                        "messages",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("role", "system")
                                    .put("content", systemPrompt(hint)),
                            )
                            .put(
                                JSONObject()
                                    .put("role", "user")
                                    .put("content", userContent),
                            ),
                    )
                    .toString()

            connection.outputStream.use { output ->
                output.write(requestBody.toByteArray(StandardCharsets.UTF_8))
            }

            val status = connection.responseCode
            val stream =
                if (status in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
            val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw IllegalStateException("AutoGLM 请求失败：HTTP $status\n$body")
            }

            val root = JSONObject(body)
            val choices = root.optJSONArray("choices")
            val first = choices?.optJSONObject(0)
                ?: throw IllegalStateException("AutoGLM 没有返回 choices")
            first
                .optJSONObject("message")
                ?.optString("content")
                ?.trim()
                .orEmpty()
                .ifEmpty { throw IllegalStateException("AutoGLM 返回内容为空") }
        } finally {
            connection.disconnect()
        }
    }
    private fun systemPrompt(hint: String? = null): String {
        val base =
            """
            你是一个手机智能体分析专家，根据当前屏幕截图决定下一步操作。
            严格只输出下一步，格式为：
            {一句简短推理}
            do(action="Launch", app="应用名")
            或 do(action="Tap", element=[x,y])
            或 do(action="Type", text="内容")
            或 do(action="Swipe", start=[x1,y1], end=[x2,y2])
            或 do(action="Back") / do(action="Home") / do(action="Enter") / do(action="Wait", duration="1 seconds")
            或 finish(message="完成原因")
            坐标范围为左上角(0,0)到右下角(999,999)。
            不要输出历史动作，不要重复相同内容。
            执行 Launch 后，如果当前截图与上一张截图相比已经发生变化，就认为应用已打开，不要再次 Launch，请直接继续下一步。
            使用静默输入法时，如果目标是输入框，点击后可能不会出现可见键盘，但仍表示输入框已聚焦；如果目标是链接、按钮或选项，页面通常会明显跳转。请根据目标类型判断下一步。
            如果页面未加载完成，先 Wait。如果页面无关，先 Back。
            Type 会自动替换整个输入框内容，不需要先清空旧文本。如果输入框已聚焦，请直接输出 Type。
            执行 Type 后不要立即 finish。继续观察当前页面，根据用户目标选择下一步，例如点击推荐结果、发送、确认、搜索或按需使用 Enter。
            在发送消息任务中，点击“发送”按钮后，请至少等待 2 秒再判断结果，避免截图延迟导致重复发送。
            最后一行必须包含 do(action=...) 或 finish(...)。
            """.trimIndent()
        return if (hint.isNullOrBlank()) base else "$base\n\n当前额外提示：$hint"
    }
    private fun dataUrl(bitmap: Bitmap): String {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, output)
        val encoded = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        return "data:image/png;base64,$encoded"
    }
}
