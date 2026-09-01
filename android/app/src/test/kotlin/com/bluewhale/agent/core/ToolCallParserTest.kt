package com.bluewhale.agent.core

import com.bluewhale.agent.model.AgentDecision
import com.bluewhale.agent.model.PhoneAction
import com.bluewhale.agent.model.SystemCapability
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ToolCallParserTest {
    @Test
    fun parsesTapFunctionCall() {
        val message =
            JSONObject()
                .put("content", "点击搜索框")
                .put(
                    "tool_calls",
                    JSONArray().put(
                        JSONObject().put(
                            "function",
                            JSONObject()
                                .put("name", "tap")
                                .put("arguments", """{"x":500,"y":250}"""),
                        ),
                    ),
                )

        val decision = ToolCallParser.parseMessage(message) as AgentDecision.Act

        assertEquals(PhoneAction.Tap(500, 250), decision.action)
        assertEquals("点击搜索框", decision.rationale)
    }

    @Test
    fun parsesTapElementIndexAndDefaultWait() {
        val tap =
            ToolCallParser.parseFunction(
                "tap",
                JSONObject().put("x", 501).put("y", 249).put("element_index", 7),
            ) as AgentDecision.Act
        val wait =
            ToolCallParser.parseFunction("wait", JSONObject()) as AgentDecision.Act

        assertEquals(PhoneAction.Tap(501, 249, elementIndex = 7), tap.action)
        assertEquals(PhoneAction.Wait(2_000), wait.action)
    }

    @Test
    fun readsDecisionExplanationFromToolArguments() {
        val decision =
            ToolCallParser.parseFunction(
                "wait",
                JSONObject()
                    .put("duration_ms", 900)
                    .put("subtask", "wait_page_load")
                    .put("reason", "页面仍显示加载动画")
                    .put("expected_outcome", "加载动画消失并出现内容"),
                rationale = "content fallback",
            ) as AgentDecision.Act

        assertEquals("页面仍显示加载动画", decision.rationale)
        assertEquals("加载动画消失并出现内容", decision.expectedOutcome)
        assertEquals("wait_page_load", decision.subtask)
    }

    @Test
    fun parsesTargetedTypeAndReflectionExplanation() {
        val decision =
            ToolCallParser.parseFunction(
                "type",
                JSONObject()
                    .put("text", "生日快乐")
                    .put(
                        "target_box",
                        JSONObject()
                            .put("left", 120)
                            .put("top", 850)
                            .put("right", 820)
                            .put("bottom", 960),
                    )
                    .put("target_description", "消息输入框")
                    .put("failure_cause", "键盘被隐藏导致 Tap 没有视觉变化")
                    .put("strategy_change", "跳过聚焦点击，直接向输入框写入"),
            ) as AgentDecision.Act

        assertEquals(
            PhoneAction.Type(
                text = "生日快乐",
                targetBounds = PhoneAction.NormalizedBounds(120, 850, 820, 960),
                targetDescription = "消息输入框",
            ),
            decision.action,
        )
        assertEquals("键盘被隐藏导致 Tap 没有视觉变化", decision.failureCause)
        assertEquals("跳过聚焦点击，直接向输入框写入", decision.strategyChange)
    }

    @Test
    fun parsesVisualTapBoundsAndJsonInsideMarkdown() {
        val tap =
            ToolCallParser.parseFunction(
                "tap",
                JSONObject()
                    .put("x", 500)
                    .put("y", 300)
                    .put(
                        "target_box",
                        JSONObject()
                            .put("left", 400)
                            .put("top", 200)
                            .put("right", 600)
                            .put("bottom", 400),
                    ),
            ) as AgentDecision.Act
        val fallback =
            ToolCallParser.parseMessage(
                JSONObject().put(
                    "content",
                    """```json
                    {"name":"wait","arguments":{"duration_ms":900}}
                    ```""".trimIndent(),
                ),
            ) as AgentDecision.Act

        assertEquals(
            PhoneAction.Tap(
                500,
                300,
                targetBounds = PhoneAction.NormalizedBounds(400, 200, 600, 400),
            ),
            tap.action,
        )
        assertEquals(PhoneAction.Wait(900), fallback.action)
    }

    @Test
    fun parsesTakeOverAndCompletion() {
        val takeover =
            ToolCallParser.parseFunction(
                "take_over",
                JSONObject().put("message", "需要验证码"),
            ) as AgentDecision.Act
        val complete =
            ToolCallParser.parseFunction(
                "complete_task",
                JSONObject().put("message", "已完成").put("success", true),
            ) as AgentDecision.Complete

        assertEquals(PhoneAction.TakeOver("需要验证码"), takeover.action)
        assertEquals("已完成", complete.message)
        assertEquals(true, complete.success)
    }

    @Test
    fun parsesLowRiskSystemTools() {
        val alarm =
            ToolCallParser.parseFunction(
                "set_alarm",
                JSONObject()
                    .put("hour", 7)
                    .put("minute", 30)
                    .put("label", "起床")
                    .put("repeat_days", JSONArray(listOf("MON", "FRI"))),
            ) as AgentDecision.Act
        val url =
            ToolCallParser.parseFunction(
                "open_url",
                JSONObject().put("url", "https://example.com"),
            ) as AgentDecision.Act

        assertEquals(
            PhoneAction.SystemTool(
                SystemCapability.SetAlarm(7, 30, "起床", listOf("MON", "FRI")),
            ),
            alarm.action,
        )
        assertEquals(
            PhoneAction.SystemTool(SystemCapability.OpenUrl("https://example.com")),
            url.action,
        )
    }

    @Test
    fun parsesMediumRiskPrefillTools() {
        val navigation =
            ToolCallParser.parseFunction(
                "navigate",
                JSONObject()
                    .put("destination", "无锡一中")
                    .put("travel_mode", "driving")
                    .put("map_app", "auto"),
            ) as AgentDecision.Act
        val calendar =
            ToolCallParser.parseFunction(
                "create_calendar_event",
                JSONObject()
                    .put("title", "项目评审")
                    .put("start_time", "2026-09-02T09:00:00+08:00")
                    .put("location", "会议室"),
            ) as AgentDecision.Act

        assertEquals(
            PhoneAction.SystemTool(
                SystemCapability.Navigate("无锡一中", "driving", "auto"),
            ),
            navigation.action,
        )
        assertEquals(
            PhoneAction.SystemTool(
                SystemCapability.CreateCalendarEvent(
                    title = "项目评审",
                    startTime = "2026-09-02T09:00:00+08:00",
                    location = "会议室",
                ),
            ),
            calendar.action,
        )
    }

    @Test
    fun rejectsMultipleActionsAndUnknownTools() {
        val calls =
            JSONArray()
                .put(call("back", "{}"))
                .put(call("wait", """{"duration_ms":1000}"""))
        val message = JSONObject().put("tool_calls", calls)

        assertThrows(IllegalArgumentException::class.java) {
            ToolCallParser.parseMessage(message)
        }
        assertThrows(IllegalStateException::class.java) {
            ToolCallParser.parseFunction("home", JSONObject())
        }
    }

    private fun call(name: String, arguments: String): JSONObject =
        JSONObject().put(
            "function",
            JSONObject().put("name", name).put("arguments", arguments),
        )
}
