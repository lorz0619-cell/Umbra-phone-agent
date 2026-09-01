package com.bluewhale.agent.debug

import android.util.Log
import com.bluewhale.agent.BuildConfig
import com.bluewhale.agent.model.AgentTraceEvent
import com.bluewhale.agent.model.AgentTraceLevel
import org.json.JSONObject

// One JSON object per Logcat line. The PowerShell monitor consumes this stable prefix.
object AgentLogcat {
    const val TAG = "UmbraAgent"
    const val PREFIX = "UMBRA_EVENT "
    private const val MAX_FIELD_LENGTH = 800

    private val sensitiveFields =
        setOf(
            "task",
            "text",
            "focused_text",
            "rationale",
            "expected_outcome",
            "target_description",
            "target_label",
            "evidence",
            "correction",
            "failure_cause",
            "strategy_change",
            "result",
            "diagnosis",
        )

    fun write(event: AgentTraceEvent) {
        val protectedFields =
            if (BuildConfig.DEBUG) {
                event.fields
            } else {
                event.fields.mapValues { (key, value) ->
                    if (key in sensitiveFields) "<redacted:${value.length}>" else value
                }
            }
        val fields = protectedFields.mapValues { (_, value) -> value.fitLogcatField() }
        val protectedMessage =
            if (BuildConfig.DEBUG) {
                event.message
            } else {
                "<redacted:${event.message.length}>"
            }
        val payload =
            JSONObject()
                .put("version", 1)
                .put("time_ms", System.currentTimeMillis())
                .put("kind", event.kind.name)
                .put("level", event.level.name)
                .put("step", event.step)
                .put("phase", event.phase.name)
                .put("phase_label", event.phase.label)
                .put("title", event.title)
                .put("message", protectedMessage.fitLogcatField())
                .put("fields", JSONObject(fields))
                .toString()
        val line = PREFIX + payload
        when (event.level) {
            AgentTraceLevel.INFO -> Log.i(TAG, line)
            AgentTraceLevel.WARNING -> Log.w(TAG, line)
            AgentTraceLevel.ERROR -> Log.e(TAG, line)
        }
    }

    private fun String.fitLogcatField(): String =
        if (length <= MAX_FIELD_LENGTH) {
            this
        } else {
            "${take(MAX_FIELD_LENGTH)}…<truncated:$length>"
        }
}
