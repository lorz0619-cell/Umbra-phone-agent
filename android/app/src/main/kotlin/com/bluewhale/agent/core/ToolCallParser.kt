package com.bluewhale.agent.core

import com.bluewhale.agent.model.AgentDecision
import com.bluewhale.agent.model.PhoneAction
import com.bluewhale.agent.model.SystemCapability
import org.json.JSONArray
import org.json.JSONObject

/** Parses OpenAI-compatible function calls into the closed v2 action algebra. */
object ToolCallParser {
    fun parseMessage(message: JSONObject): AgentDecision {
        val rationale = contentText(message.opt("content"))
        val calls = message.optJSONArray("tool_calls")
        if (calls != null && calls.length() > 0) {
            require(calls.length() == 1) {
                "每轮只允许一个手机动作，模型返回了 ${calls.length()} 个 tool calls"
            }
            val function = calls.getJSONObject(0).getJSONObject("function")
            val name = function.getString("name")
            val rawArguments = function.opt("arguments")
            val arguments =
                when (rawArguments) {
                    is JSONObject -> rawArguments
                    is String -> JSONObject(rawArguments.ifBlank { "{}" })
                    else -> JSONObject()
                }
            return parseFunction(name, arguments, rationale)
        }

        // Compatibility path for providers that advertise tools but serialize the
        // selected call as a JSON content object.
        val content = rationale.trim()
        val jsonStart = content.indexOf('{')
        val jsonEnd = content.lastIndexOf('}')
        require(jsonStart >= 0 && jsonEnd > jsonStart) {
            "模型没有返回 function call 或可解析的 JSON 动作"
        }
        val fallback = JSONObject(content.substring(jsonStart, jsonEnd + 1))
        val name = fallback.optString("name", fallback.optString("tool"))
        val arguments =
            fallback.optJSONObject("arguments")
                ?: fallback.optJSONObject("parameters")
                ?: fallback
        return parseFunction(name, arguments, fallback.optString("reason"))
    }

    fun parseFunction(
        rawName: String,
        arguments: JSONObject,
        rationale: String = "",
    ): AgentDecision {
        val name = rawName.trim().lowercase()
        val actionRationale = arguments.optString("reason").ifBlank { rationale }
        val expectedOutcome = arguments.optString("expected_outcome")
        val subtask = arguments.optString("subtask")
        val failureCause = arguments.optString("failure_cause")
        val strategyChange = arguments.optString("strategy_change")
        fun act(action: PhoneAction): AgentDecision =
            AgentDecision.Act(
                action = action,
                subtask = subtask,
                rationale = actionRationale,
                expectedOutcome = expectedOutcome,
                failureCause = failureCause,
                strategyChange = strategyChange,
            )

        return when (name) {
            "launch" -> act(PhoneAction.Launch(arguments.requiredText("app")))
            "tap" ->
                act(
                    PhoneAction.Tap(
                        x = arguments.requiredInt("x"),
                        y = arguments.requiredInt("y"),
                        elementIndex =
                            arguments.optInt("element_index", -1).takeIf { it >= 0 },
                        targetBounds =
                            arguments.optJSONObject("target_box")?.let { box ->
                                PhoneAction.NormalizedBounds(
                                    left = box.requiredInt("left"),
                                    top = box.requiredInt("top"),
                                    right = box.requiredInt("right"),
                                    bottom = box.requiredInt("bottom"),
                                )
                            },
                        targetDescription = arguments.optString("target_description"),
                    ),
                )
            "type" ->
                act(
                    PhoneAction.Type(
                        text = arguments.requiredText("text", allowBlank = true),
                        elementIndex =
                            arguments.optInt("element_index", -1).takeIf { it >= 0 },
                        targetBounds =
                            arguments.optJSONObject("target_box")?.let { box ->
                                PhoneAction.NormalizedBounds(
                                    left = box.requiredInt("left"),
                                    top = box.requiredInt("top"),
                                    right = box.requiredInt("right"),
                                    bottom = box.requiredInt("bottom"),
                                )
                            },
                        targetDescription = arguments.optString("target_description"),
                    ),
                )
            "swipe" ->
                act(
                    PhoneAction.Swipe(
                        startX = arguments.requiredInt("start_x"),
                        startY = arguments.requiredInt("start_y"),
                        endX = arguments.requiredInt("end_x"),
                        endY = arguments.requiredInt("end_y"),
                        durationMs = arguments.optInt("duration_ms", 300),
                    ),
                )
            "back" -> act(PhoneAction.Back)
            "wait" ->
                act(
                    PhoneAction.Wait(arguments.optInt("duration_ms", 2_000)),
                )
            "take_over", "takeover" ->
                act(
                    PhoneAction.TakeOver(arguments.requiredText("message")),
                )
            "navigate" ->
                act(
                    PhoneAction.SystemTool(
                        SystemCapability.Navigate(
                            destination = arguments.requiredText("destination"),
                            travelMode = arguments.optString("travel_mode", "driving"),
                            mapApp = arguments.optString("map_app", "auto"),
                        ),
                    ),
                )
            "set_alarm" ->
                act(
                    PhoneAction.SystemTool(
                        SystemCapability.SetAlarm(
                            hour = arguments.requiredInt("hour"),
                            minute = arguments.requiredInt("minute"),
                            label = arguments.optString("label"),
                            repeatDays = arguments.optJSONArray("repeat_days").stringList(),
                        ),
                    ),
                )
            "set_timer" ->
                act(
                    PhoneAction.SystemTool(
                        SystemCapability.SetTimer(
                            durationSeconds = arguments.requiredInt("duration_seconds"),
                            label = arguments.optString("label"),
                        ),
                    ),
                )
            "create_calendar_event" ->
                act(
                    PhoneAction.SystemTool(
                        SystemCapability.CreateCalendarEvent(
                            title = arguments.requiredText("title"),
                            startTime = arguments.requiredText("start_time"),
                            endTime = arguments.optString("end_time").ifBlank { null },
                            location = arguments.optString("location"),
                            description = arguments.optString("description"),
                            allDay = arguments.optBoolean("all_day", false),
                        ),
                    ),
                )
            "create_contact" ->
                act(
                    PhoneAction.SystemTool(
                        SystemCapability.CreateContact(
                            name = arguments.requiredText("name"),
                            phone = arguments.optString("phone"),
                            email = arguments.optString("email"),
                        ),
                    ),
                )
            "compose_sms" ->
                act(
                    PhoneAction.SystemTool(
                        SystemCapability.ComposeSms(
                            recipient = arguments.requiredText("recipient"),
                            body = arguments.requiredText("body", allowBlank = true),
                        ),
                    ),
                )
            "dial_phone" ->
                act(
                    PhoneAction.SystemTool(
                        SystemCapability.DialPhone(arguments.requiredText("phone_number")),
                    ),
                )
            "open_camera" ->
                act(
                    PhoneAction.SystemTool(
                        SystemCapability.OpenCamera(arguments.optString("mode", "photo")),
                    ),
                )
            "open_url" ->
                act(PhoneAction.SystemTool(SystemCapability.OpenUrl(arguments.requiredText("url"))))
            "web_search" ->
                act(
                    PhoneAction.SystemTool(
                        SystemCapability.WebSearch(arguments.requiredText("query")),
                    ),
                )
            "open_system_settings" ->
                act(
                    PhoneAction.SystemTool(
                        SystemCapability.OpenSystemSettings(arguments.optString("page", "settings")),
                    ),
                )
            "compose_email" ->
                act(
                    PhoneAction.SystemTool(
                        SystemCapability.ComposeEmail(
                            recipient = arguments.requiredText("recipient"),
                            subject = arguments.optString("subject"),
                            body = arguments.optString("body"),
                        ),
                    ),
                )
            "share_text" ->
                act(
                    PhoneAction.SystemTool(
                        SystemCapability.ShareText(
                            text = arguments.requiredText("text"),
                            title = arguments.optString("title"),
                        ),
                    ),
                )
            "play_media" ->
                act(
                    PhoneAction.SystemTool(
                        SystemCapability.PlayMedia(arguments.requiredText("query")),
                    ),
                )
            "complete_task", "finish" ->
                AgentDecision.Complete(
                    message = arguments.requiredText("message"),
                    success = arguments.optBoolean("success", true),
                )
            else -> error("模型请求了未注册工具：$rawName")
        }
    }

    private fun JSONObject.requiredText(
        key: String,
        allowBlank: Boolean = false,
    ): String {
        require(has(key) && !isNull(key)) { "缺少参数：$key" }
        val value = get(key).toString()
        require(allowBlank || value.isNotBlank()) { "参数不能为空：$key" }
        return value
    }

    private fun JSONObject.requiredInt(key: String): Int {
        require(has(key) && !isNull(key)) { "缺少参数：$key" }
        return when (val value = get(key)) {
            is Number -> value.toInt()
            else -> value.toString().toIntOrNull() ?: error("参数不是整数：$key=$value")
        }
    }

    private fun JSONArray?.stringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    private fun contentText(content: Any?): String =
        when (content) {
            is String -> content
            is JSONArray ->
                buildString {
                    for (index in 0 until content.length()) {
                        val item = content.optJSONObject(index) ?: continue
                        if (item.optString("type") == "text") {
                            append(item.optString("text"))
                        }
                    }
                }
            else -> ""
        }
}
