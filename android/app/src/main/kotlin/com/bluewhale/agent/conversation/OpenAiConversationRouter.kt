package com.bluewhale.agent.conversation

import com.bluewhale.agent.model.VlmConfig
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

sealed interface ConversationRoute {
    data class Chat(val reply: String) : ConversationRoute
    data class Task(val task: String) : ConversationRoute
}

/** Uses the configured model without tools to separate conversation from phone work. */
class OpenAiConversationRouter(private val config: VlmConfig) {
    suspend fun route(input: String, history: List<ConversationMessage>): ConversationRoute =
        withContext(Dispatchers.IO) {
            require(config.apiKey.isNotBlank()) { "缺少通用模型 API Key" }
            val connection = URL(completionsUrl(config.baseUrl)).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 30_000
                connection.readTimeout = 90_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")

                val messages = JSONArray().put(
                    JSONObject().put("role", "system").put("content", SYSTEM_PROMPT),
                )
                history.takeLast(16).forEach { message ->
                    messages.put(JSONObject()
                        .put("role", if (message.role == ConversationRole.USER) "user" else "assistant")
                        .put("content", message.text))
                }
                if (history.lastOrNull()?.role != ConversationRole.USER || history.last().text != input) {
                    messages.put(JSONObject().put("role", "user").put("content", input))
                }
                val request = JSONObject()
                    .put("model", config.model)
                    .put("temperature", 0.2)
                    .put("max_tokens", 768)
                    .put("thinking", JSONObject().put("type", "disabled"))
                    .put("response_format", JSONObject().put("type", "json_object"))
                    .put("messages", messages)
                connection.outputStream.use {
                    it.write(request.toString().toByteArray(StandardCharsets.UTF_8))
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (status !in 200..299) {
                    throw IllegalStateException("对话请求失败：HTTP $status\n${body.take(1_000)}")
                }
                val content = JSONObject(body).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content")
                parse(content, input)
            } finally {
                connection.disconnect()
            }
        }

    internal fun parse(content: String, original: String): ConversationRoute {
        val clean = content.trim().removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        if (clean.isBlank()) return fallback(original)
        val json =
            runCatching { JSONObject(clean) }.getOrElse {
                return if (clean.startsWith("{") || clean.startsWith("[")) {
                    fallback(original)
                } else {
                    ConversationRoute.Chat(clean)
                }
            }
        return when (json.optString("route").lowercase()) {
            "task" -> ConversationRoute.Task(json.optString("task").ifBlank { original })
            else -> ConversationRoute.Chat(
                json.optString("reply").ifBlank { "我在。你想聊些什么？" },
            )
        }
    }

    private fun fallback(original: String): ConversationRoute {
        val looksLikeTask =
            Regex(
                """(打开|启动|点击|输入|搜索|滑动|返回|进入|发送|关闭|导航|带我去|闹钟|计时|倒计时|日历|日程|联系人|短信|拨号|打电话|拍照|录像|分享|播放).{0,80}(应用|app|手机|屏幕|微信|qq|浏览器|设置|地址|地点|分钟|小时|联系人|短信|相机|音乐)?""",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(original)
        return if (looksLikeTask) {
            ConversationRoute.Task(original)
        } else {
            ConversationRoute.Chat("模型暂时没有返回有效内容，请再说一次。")
        }
    }

    private fun completionsUrl(baseUrl: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        return if (normalized.endsWith("/chat/completions")) normalized
        else "$normalized/chat/completions"
    }

    private companion object {
        val SYSTEM_PROMPT = """
            你是 Umbra phone-agent 的会话入口。你能正常多轮聊天，也能把明确的手机操作请求交给设备 Agent。
            请只返回 JSON，不要 Markdown：
            - 普通问候、知识问答、讨论、追问、解释、写作等：{"route":"chat","reply":"自然、直接的回复"}
            - 用户明确要求操作手机，或要求导航、设置闹钟/计时器、创建日历事件/联系人、撰写短信/邮件、准备拨号、打开相机、打开网址/系统设置、分享文本、播放媒体：{"route":"task","task":"结合上下文补全后的明确任务"}
            对闹钟、日历等关键参数不足的请求，应先 route=chat 追问缺失的时间、日期、对象或内容；不得替用户猜测。
            不要因为提到应用名或手机就自动判为任务；必须有明确执行意图。要利用之前的对话解析“它”“刚才那个”等指代。
        """.trimIndent()
    }
}
