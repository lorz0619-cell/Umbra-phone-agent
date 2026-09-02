package com.bluewhale.agent.core

import android.graphics.Bitmap
import android.util.Base64
import com.bluewhale.agent.model.ActionHistoryEntry
import com.bluewhale.agent.model.AgentDecision
import com.bluewhale.agent.model.PerceptionSnapshot
import com.bluewhale.agent.model.VlmConfig
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Provider-neutral Chat Completions client.
 *
 * The model supplies perception and judgment; Kotlin owns the action vocabulary,
 * JSON schemas, validation, execution, verification and circuit breaking.
 */
class OpenAiCompatibleVlmClient(
    private val config: VlmConfig,
    private val conversationContext: String = "",
    private val deviceProfile: String = "",
) : DecisionModel {
    override suspend fun nextDecision(
        task: String,
        observation: PerceptionSnapshot,
        history: List<ActionHistoryEntry>,
        feedback: String?,
    ): AgentDecision =
        requestDecision(
            task = task,
            observation = observation,
            history = history,
            feedback = feedback,
            systemPrompt = SYSTEM_PROMPT,
            reflectionMode = false,
            terminalMode = false,
        )

    override suspend fun reflectDecision(
        task: String,
        observation: PerceptionSnapshot,
        history: List<ActionHistoryEntry>,
        trigger: String,
        evidence: String,
        correction: String,
        blockedActions: Set<String>,
        candidateHints: List<String>,
    ): AgentDecision =
        requestDecision(
            task = task,
            observation = observation,
            history = history,
            feedback =
                buildString {
                    appendLine("[REFLECTION]")
                    appendLine("触发原因：$trigger")
                    appendLine("失败证据：$evidence")
                    appendLine("纠错要求：$correction")
                    appendLine("可选候选路径：")
                    candidateHints.forEach { appendLine("- $it") }
                    appendLine("禁止的精确页面-动作：")
                    blockedActions.forEach { appendLine("- ${it.substringAfter('|')}") }
                    append("必须根据最新截图提出可执行的新假设；不能只改参数格式。")
                },
            systemPrompt = REFLECTION_SYSTEM_PROMPT,
            reflectionMode = true,
            terminalMode = false,
        )

    override suspend fun terminalDecision(
        task: String,
        observation: PerceptionSnapshot,
        history: List<ActionHistoryEntry>,
        trigger: String,
        evidence: String,
        blockedActions: Set<String>,
    ): AgentDecision =
        requestDecision(
            task = task,
            observation = observation,
            history = history,
            feedback =
                buildString {
                    appendLine("[TERMINAL_ADJUDICATION]")
                    appendLine("持续失败原因：$trigger")
                    appendLine("证据：$evidence")
                    appendLine("已排除的页面-动作：")
                    blockedActions.forEach { appendLine("- ${it.substringAfter('|')}") }
                    append("不能再提出普通设备动作；判断用户接管是否有意义，否则明确结束任务。")
                },
            systemPrompt = TERMINAL_SYSTEM_PROMPT,
            reflectionMode = false,
            terminalMode = true,
        )

    private suspend fun requestDecision(
        task: String,
        observation: PerceptionSnapshot,
        history: List<ActionHistoryEntry>,
        feedback: String?,
        systemPrompt: String,
        reflectionMode: Boolean,
        terminalMode: Boolean,
    ): AgentDecision = withContext(Dispatchers.IO) {
        require(config.apiKey.isNotBlank()) { "缺少通用 VLM API Key" }
        require(config.baseUrl.isNotBlank()) { "缺少通用 VLM Base URL" }
        require(config.model.isNotBlank()) { "缺少通用 VLM 模型名" }

        val connection = URL(completionsUrl(config.baseUrl)).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 30_000
            connection.readTimeout = 90_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")

            // Action planning is deliberately non-thinking: DeepSeek rejects
            // explicit tool_choice in thinking mode, while the graph requires
            // one deterministic structured action per planning turn.
            val requestBody =
                JSONObject()
                    .put("model", config.model)
                    .put("temperature", 0)
                    .put("max_tokens", 768)
                    .put("thinking", JSONObject().put("type", "disabled"))
                    .put("tools", toolSchemas(reflectionMode, terminalMode))
                    .put("tool_choice", "required")
                    .put(
                        "messages",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("role", "system")
                                    .put("content", systemPrompt),
                            )
                            .put(
                                JSONObject()
                                    .put("role", "user")
                                    .put("content", userContent(task, observation, history, feedback)),
                            ),
                    )

            connection.outputStream.use { output ->
                output.write(requestBody.toString().toByteArray(StandardCharsets.UTF_8))
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw IllegalStateException("VLM 请求失败：HTTP $status\n${body.take(2_000)}")
            }
            val message =
                JSONObject(body)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?: error("VLM 响应缺少 choices[0].message")
            ToolCallParser.parseMessage(message)
        } finally {
            connection.disconnect()
        }
    }

    private fun userContent(
        task: String,
        observation: PerceptionSnapshot,
        history: List<ActionHistoryEntry>,
        feedback: String?,
    ): JSONArray {
        val semantic =
            buildString {
                if (conversationContext.isNotBlank()) {
                    appendLine("相关会话上下文（用于理解指代，不代表动作已经完成）：")
                    appendLine(conversationContext.takeLast(4_000))
                }
                if (deviceProfile.isNotBlank()) {
                    appendLine("设备与显示参数：$deviceProfile")
                }
                appendLine(
                    "当前本地时间：" +
                        ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                )
                appendLine("用户目标：$task")
                appendLine("当前包名：${observation.accessibility.packageName ?: "未知"}")
                appendLine("屏幕尺寸：${observation.screen.width}x${observation.screen.height}")
                appendLine(
                    "截图帧：id=${observation.screen.frameId} fresh=${observation.screen.isFresh} " +
                        "source=${observation.screen.source}",
                )
                if (history.isNotEmpty()) {
                    appendLine("最近动作（只用于避免重复）：")
                    history.takeLast(12).forEach {
                        appendLine(
                            "step=${it.step} subtask=${it.subtask} action=${it.action} verified=${it.verified} " +
                                "reason=${it.rationale} expected=${it.expectedOutcome} result=${it.result}",
                        )
                    }
                }
                if (!feedback.isNullOrBlank()) {
                    appendLine("上一轮验证反馈：$feedback")
                }
                appendLine("当前无障碍语义树：")
                append(observation.accessibility.promptText())
                appendLine()
                append("请结合截图和语义树，只调用一个已注册工具。")
            }
        return JSONArray()
            .put(JSONObject().put("type", "text").put("text", semantic))
            .put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", dataUrl(observation.screen.bitmap))),
            )
    }

    private fun dataUrl(bitmap: Bitmap): String {
        val longest = maxOf(bitmap.width, bitmap.height)
        val scaled =
            if (longest > MAX_IMAGE_DIMENSION) {
                val ratio = MAX_IMAGE_DIMENSION.toFloat() / longest
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt().coerceAtLeast(1),
                    (bitmap.height * ratio).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                bitmap
            }
        return try {
            val output = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            "data:image/jpeg;base64," +
                Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun completionsUrl(baseUrl: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        return if (normalized.endsWith("/chat/completions")) {
            normalized
        } else {
            "$normalized/chat/completions"
        }
    }

    private fun toolSchemas(
        reflectionMode: Boolean,
        terminalMode: Boolean,
    ): JSONArray {
        if (terminalMode) {
            return JSONArray()
                .put(
                    tool(
                        "take_over",
                        "当前任务仍可由用户在现有页面继续时，请求人工接管。",
                        properties("message" to string("说明当前页面、失败原因和用户需要完成的操作")),
                        "message",
                    ),
                )
                .put(
                    tool(
                        "complete_task",
                        "只有接管也无法推进、目标不可达或已经有明确失败结论时结束任务。",
                        properties(
                            "message" to string("清楚说明停止原因和已有进展"),
                            "success" to JSONObject().put("type", "boolean"),
                        ),
                        "message",
                        "success",
                    ),
                )
        }
        return JSONArray()
            .put(tool("launch", "直接启动指定应用。", properties("app" to string("应用显示名或包名")), "app"))
            .put(
                tool(
                    "navigate",
                    "使用 Android 地图 Intent 打开目的地导航预览；用户在地图中确认路线和开始导航。",
                    properties(
                        "destination" to string("目的地名称或地址，例如“无锡一中”"),
                        "travel_mode" to
                            enumString("出行方式", "driving", "walking", "bicycling", "transit"),
                        "map_app" to enumString("地图偏好", "auto", "amap", "baidu", "tencent"),
                    ),
                    "destination",
                ),
            )
            .put(
                tool(
                    "set_alarm",
                    "通过系统时钟创建闹钟。只用于用户给出明确时间的请求。",
                    properties(
                        "hour" to integer("24 小时制小时", 0, 23),
                        "minute" to integer("分钟", 0, 59),
                        "label" to string("闹钟标签，可留空"),
                        "repeat_days" to
                            JSONObject()
                                .put("type", "array")
                                .put(
                                    "items",
                                    enumString(
                                        "重复星期",
                                        "MON",
                                        "TUE",
                                        "WED",
                                        "THU",
                                        "FRI",
                                        "SAT",
                                        "SUN",
                                    ),
                                )
                                .put("maxItems", 7),
                    ),
                    "hour",
                    "minute",
                ),
            )
            .put(
                tool(
                    "set_timer",
                    "通过系统时钟创建倒计时。",
                    properties(
                        "duration_seconds" to integer("倒计时总秒数", 1, 86_400),
                        "label" to string("计时器标签，可留空"),
                    ),
                    "duration_seconds",
                ),
            )
            .put(
                tool(
                    "create_calendar_event",
                    "打开系统日历的新建事件确认页并预填内容；不会静默保存。",
                    properties(
                        "title" to string("事件标题"),
                        "start_time" to string("带时区的 ISO-8601 开始时间"),
                        "end_time" to string("可选的带时区 ISO-8601 结束时间"),
                        "location" to string("地点，可留空"),
                        "description" to string("备注，可留空"),
                        "all_day" to JSONObject().put("type", "boolean"),
                    ),
                    "title",
                    "start_time",
                ),
            )
            .put(
                tool(
                    "create_contact",
                    "打开系统联系人新建确认页并预填内容；不会静默保存。",
                    properties(
                        "name" to string("联系人姓名"),
                        "phone" to string("电话号码，可留空"),
                        "email" to string("邮箱，可留空"),
                    ),
                    "name",
                ),
            )
            .put(
                tool(
                    "compose_sms",
                    "打开短信撰写页并预填收件人与正文；不会自动发送。",
                    properties(
                        "recipient" to string("电话号码"),
                        "body" to string("短信正文"),
                    ),
                    "recipient",
                    "body",
                ),
            )
            .put(
                tool(
                    "dial_phone",
                    "打开系统拨号确认页并填入号码；不会直接拨出。",
                    properties("phone_number" to string("电话号码")),
                    "phone_number",
                ),
            )
            .put(
                tool(
                    "open_camera",
                    "打开系统相机到拍照或录像准备状态；不会自动拍摄。",
                    properties("mode" to enumString("相机模式", "photo", "video")),
                ),
            )
            .put(
                tool(
                    "open_url",
                    "使用系统浏览器打开明确的 http/https URL。",
                    properties("url" to string("完整的 http 或 https URL")),
                    "url",
                ),
            )
            .put(
                tool(
                    "web_search",
                    "使用系统搜索处理明确的网页搜索请求。",
                    properties("query" to string("搜索关键词")),
                    "query",
                ),
            )
            .put(
                tool(
                    "open_system_settings",
                    "直接打开白名单中的 Android 系统设置页。",
                    properties(
                        "page" to
                            enumString(
                                "设置页",
                                "settings",
                                "wifi",
                                "bluetooth",
                                "location",
                                "accessibility",
                                "notification",
                                "display",
                                "sound",
                                "battery",
                                "apps",
                            ),
                    ),
                ),
            )
            .put(
                tool(
                    "compose_email",
                    "打开邮件撰写页并预填内容；不会自动发送。",
                    properties(
                        "recipient" to string("收件人邮箱"),
                        "subject" to string("主题，可留空"),
                        "body" to string("正文，可留空"),
                    ),
                    "recipient",
                ),
            )
            .put(
                tool(
                    "share_text",
                    "打开 Android 系统分享面板并预填文本；由用户选择接收应用。",
                    properties(
                        "text" to string("要分享的文本"),
                        "title" to string("分享面板标题，可留空"),
                    ),
                    "text",
                ),
            )
            .put(
                tool(
                    "play_media",
                    "让支持的媒体应用搜索并准备播放指定内容。",
                    properties("query" to string("歌曲、播客或媒体名称")),
                    "query",
                ),
            )
            .put(
                tool(
                    "tap",
                    "点击目标。无障碍树中存在目标时优先传 element_index，否则使用截图归一化坐标。",
                    properties(
                        "x" to coordinate("横坐标"),
                        "y" to coordinate("纵坐标"),
                        "element_index" to
                            JSONObject()
                                .put("type", "integer")
                                .put("minimum", 0)
                                .put("description", "可选的无障碍节点 #index，用于按精确边界中心点击"),
                        "target_box" to
                            JSONObject()
                                .put("type", "object")
                                .put("description", "视觉目标的归一化边界框；没有可用 element_index 时必须提供")
                                .put(
                                    "properties",
                                    properties(
                                        "left" to coordinate("目标左边界"),
                                        "top" to coordinate("目标上边界"),
                                        "right" to coordinate("目标右边界"),
                                        "bottom" to coordinate("目标下边界"),
                                    ),
                                )
                                .put(
                                    "required",
                                    JSONArray(listOf("left", "top", "right", "bottom")),
                                )
                                .put("additionalProperties", false),
                        "target_description" to
                            string("目标的简短语义名称，例如“顶部搜索按钮”；用于安全重试和日志"),
                    ),
                    "x",
                    "y",
                    "target_description",
                ),
            )
            .put(
                tool(
                    "type",
                    "直接向指定输入框写入文本。目标可见时应直接 Type，不要先 Tap；优先 element_index，否则提供视觉 target_box。",
                    properties(
                        "text" to string("完整文本"),
                        "element_index" to
                            JSONObject()
                                .put("type", "integer")
                                .put("minimum", 0)
                                .put("description", "可选的可编辑无障碍节点 #index"),
                        "target_box" to
                            JSONObject()
                                .put("type", "object")
                                .put("description", "无可用可编辑节点时，输入框的归一化边界框")
                                .put(
                                    "properties",
                                    properties(
                                        "left" to coordinate("输入框左边界"),
                                        "top" to coordinate("输入框上边界"),
                                        "right" to coordinate("输入框右边界"),
                                        "bottom" to coordinate("输入框下边界"),
                                    ),
                                )
                                .put(
                                    "required",
                                    JSONArray(listOf("left", "top", "right", "bottom")),
                                )
                                .put("additionalProperties", false),
                        "target_description" to
                            string("输入框的简短语义名称，例如“消息输入框”"),
                    ),
                    "text",
                ),
            )
            .put(
                tool(
                    "swipe",
                    "在屏幕上从起点滑到终点，坐标范围 0..999。",
                    properties(
                        "start_x" to coordinate("起点横坐标"),
                        "start_y" to coordinate("起点纵坐标"),
                        "end_x" to coordinate("终点横坐标"),
                        "end_y" to coordinate("终点纵坐标"),
                        "duration_ms" to integer("持续时间毫秒", 100, 2_000),
                    ),
                    "start_x",
                    "start_y",
                    "end_x",
                    "end_y",
                ),
            )
            .put(tool("back", "返回上一页。", JSONObject()))
            .put(
                tool(
                    "wait",
                    "等待页面加载、动画或异步更新后重新感知。",
                    properties("duration_ms" to integer("等待毫秒", 300, 15_000)),
                ),
            )
            .put(
                tool(
                    "take_over",
                    "遇到登录、验证码、权限确认或需要人工判断时暂停并请求人工接管。",
                    properties("message" to string("需要人工处理的原因和操作")),
                    "message",
                ),
            )
            .put(
                tool(
                    "complete_task",
                    "只有当前截图/语义树已明确证明目标完成或不可完成时才结束。",
                    properties(
                        "message" to string("完成结果或失败原因"),
                        "success" to JSONObject().put("type", "boolean"),
                    ),
                    "message",
                    "success",
                ),
            )
            .also { schemas ->
                if (reflectionMode) {
                    for (index in 0 until schemas.length()) {
                        val function = schemas.getJSONObject(index).getJSONObject("function")
                        if (function.getString("name") == "complete_task") continue
                        val parameters = function.getJSONObject("parameters")
                        parameters
                            .getJSONObject("properties")
                            .put(
                                "failure_cause",
                                string("基于最新截图与历史证据，对上次失败原因的一句话判断"),
                            )
                            .put(
                                "strategy_change",
                                string("本次相对失败动作发生的实质变化，不能只是补字段或换说法"),
                            )
                        parameters.getJSONArray("required")
                            .put("failure_cause")
                            .put("strategy_change")
                    }
                }
            }
    }

    private fun tool(
        name: String,
        description: String,
        properties: JSONObject,
        vararg required: String,
    ): JSONObject {
        val requiredNames = required.toMutableList()
        if (name != "complete_task") {
            properties
                .put(
                    "subtask",
                    string(
                        "当前稳定子任务 ID，例如 submit_search、select_product；" +
                            "在该中间目标真正完成前必须逐字保持不变",
                    ),
                )
                .put("reason", string("一句话说明当前可观察证据以及为什么选择此动作"))
                .put("expected_outcome", string("一句话说明动作成功后应出现的可验证变化"))
            requiredNames += "subtask"
            requiredNames += "reason"
            requiredNames += "expected_outcome"
        }
        val parameters =
            JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("additionalProperties", false)
        if (requiredNames.isNotEmpty()) parameters.put("required", JSONArray(requiredNames))
        return JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("parameters", parameters),
            )
    }

    private fun properties(vararg values: Pair<String, JSONObject>): JSONObject =
        JSONObject().apply { values.forEach { (name, schema) -> put(name, schema) } }

    private fun string(description: String): JSONObject =
        JSONObject().put("type", "string").put("description", description)

    private fun enumString(
        description: String,
        vararg values: String,
    ): JSONObject =
        string(description).put("enum", JSONArray(values.toList()))

    private fun coordinate(description: String): JSONObject = integer(description, 0, 999)

    private fun integer(description: String, minimum: Int, maximum: Int): JSONObject =
        JSONObject()
            .put("type", "integer")
            .put("description", description)
            .put("minimum", minimum)
            .put("maximum", maximum)

    private companion object {
        const val MAX_IMAGE_DIMENSION = 2_560
        const val JPEG_QUALITY = 90

        val SYSTEM_PROMPT =
            """
            你是运行在 Android 手机上的通用视觉操作规划器。Kotlin 状态机负责执行和验证，你只负责基于最新截图、无障碍树、目标与验证反馈选择下一步。

            规则：
            1. 每轮必须且只能调用一个已注册 function；禁止输出未注册动作。
            1a. 若用户目标可由 navigate、set_alarm、set_timer、create_calendar_event、create_contact、compose_sms、dial_phone、open_camera、open_url、web_search、open_system_settings、compose_email、share_text 或 play_media 完成，必须优先调用对应系统工具，不要退化为 Launch + 截图点击。
            1b. 系统工具是固定白名单：不得构造任意 Intent。短信、电话、日历、联系人、邮件、分享、相机和导航只负责打开预填或确认界面；禁止声称已经发送、拨出、保存、拍摄或开始导航。
            1c. 每个动作必须填写稳定的 subtask ID，表示当前中间目标，例如 launch_taobao、submit_search、select_product、confirm_order。在截图明确证明该中间目标完成前，subtask 必须逐字保持不变；禁止通过改名绕过反思次数。
            1d. 动作执行成功不等于当前 subtask 已推进。Wait、输入、点击聚焦等动作即使执行成功，只要页面没有产生包名、语义树或显著视觉推进，仍必须按未推进处理。
            2. 先确认当前应用和页面，再行动。输入框已经可见时，应直接调用带 element_index 或 target_box 的 Type，由执行器定向写入；不要为了“聚焦”机械地先 Tap。
            2a. 用户明确要求打开或启动某个应用时，优先调用 launch；只有 launch 明确失败后才使用桌面搜索。
            3. 不得假定动作成功；下一轮会提供 Kotlin 后置验证结果。
            4. 验证失败或动作成功但 subtask 未推进时，换一种可解释的策略，不要机械重复同一坐标。
            4a. 每个动作必须填写 reason 和 expected_outcome：只写可观察依据、决策理由和预期验证信号，不输出冗长思维过程。
            4b. 出现 [REFLECTION] 反馈时，它具有最高优先级；必须避开被禁止的页面-动作组合，并明确选择不同策略。
            4c. 同一 subtask 最多进行 3 次普通反思恢复；第 4 次会进入终局裁决，只允许 take_over 或 complete_task。
            5. 页面仍在加载时调用 wait；登录、验证码、支付、敏感确认调用 take_over。
            5a. 创建闹钟/计时器前必须有明确时间或时长；日历时间使用当前本地时间推导，并输出带时区 ISO-8601。信息不足时先在对话层向用户追问，不能猜测关键参数。
            6. 只有当前观察已明确证明目标完成时调用 complete_task。
            7. 坐标统一使用 0..999 的归一化坐标。
            8. 页面加载、动画未结束或预期结果尚未出现时调用 wait，通常等待 2000ms，不要猜测或重复点击。
            9. 无障碍树包含目标时，Tap 必须优先传目标的 #index 作为 element_index，由执行器点击精确边界中心。
            10. 每次 Tap 都要给出 target_description。视觉目标没有无障碍节点时还必须提供 target_box；x/y 必须是该边界框中心，禁止把截图像素值直接当作归一化坐标。
            11. 坐标换算示例：目标中心位于屏幕宽度 80%、高度 10%，应输出 x=800,y=100，与截图缩放后的像素尺寸无关。
            12. 如果 Provider 无法生成原生 tool_call，content 必须是单个 JSON 对象：{"name":"工具名","arguments":{...},"reason":"..."}，禁止 Markdown。
            13. 历史中某段文本已经 Type 成功且“发送”Tap 已验证成功时，禁止再次输入或发送同一文本；先检查消息气泡，单消息任务应 complete_task。
            """.trimIndent()

        val REFLECTION_SYSTEM_PROMPT =
            """
            你是 Android 手机 Agent 的故障反思与恢复规划器。Kotlin 已检测到当前动作假设失败，并在调用你之前重新获取了最新截图和无障碍树。

            你的职责不是复述禁止项，而是：
            1. 比较失败前后的证据，判断更可能是定位错误、焦点不可观察、页面未稳定、遮挡、滚动位置错误，还是动作已经生效但验证信号不足。
            2. 只调用一个工具提出可执行的新假设，并填写 failure_cause、strategy_change、reason、expected_outcome。
            2a. 反思恢复必须沿用最近历史中的同一个 subtask ID，直到截图证明该子任务已完成；不能新建或改名来重置恢复预算。
            2b. 动作执行成功不等于子任务推进：只有包名、语义树或显著视觉状态向前变化，才允许切换到下一个 subtask。
            2c. 当 trigger 为 repeated_wait、repeated_tap_coordinate 或 repeated_type_failure 时，必须把刚刚重复的路径视为已失效并降低其权重；repeated_tap_coordinate 应继续 Tap，但必须换成不同坐标/节点，repeated_wait 应停止单纯等待，repeated_type_failure 应切换输入焦点或策略。
            3. 新策略必须发生实质变化：优先直接 Type 到输入节点/输入框、改用 element_index、改用明显不同的视觉区域、Wait 后重感知、Swipe 暴露目标、Back 恢复页面或 Take_over。
            4. 如果输入框已经可见，优先使用带目标定位的 Type，不要再次 Tap 输入框来证明焦点。
            5. 不能把“补上 target_box”、改写 target_description 或对相同边界做微小偏移冒充新策略。
            6. 禁止重复反馈列出的精确页面-动作组合；若确实只能使用同一语义目标，必须选择明显不同的位置桶或不同动作类型。
            7. 不得假定动作成功；只描述可观察的验证信号。每轮只能调用一个已注册工具。
            8. 只有最新截图明确证明任务完成时才 complete_task。
            9. 坐标和边界均使用 0..999 归一化坐标。
            10. 同一 subtask 最多进行 3 次普通恢复；如果仍不能证明推进，则接受进入终局裁决，不要靠微小偏移或改名拖延。
            """.trimIndent()

        val TERMINAL_SYSTEM_PROMPT =
            """
            你是 Android 手机 Agent 反思层的终局裁决器。恢复规划已连续失败，当前必须停止普通动作循环。

            你只能调用一个工具：
            1. 当前页面仍可由人操作（支付、验证码、登录、复杂选择、模型定位持续失败）时调用 take_over，清楚告诉用户要做什么。
            2. 接管也无意义、目标不可达、应用不可用或已有明确失败结论时调用 complete_task，success=false。
            3. 只有最新截图明确证明目标已完成时 complete_task 才可 success=true。
            4. 不得再调用 Tap、Type、Swipe、Back、Wait 或 Launch，不得把计数器本身当作失败原因。
            """.trimIndent()
    }
}
