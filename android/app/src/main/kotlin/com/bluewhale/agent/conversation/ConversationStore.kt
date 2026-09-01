package com.bluewhale.agent.conversation

import android.content.Context
import com.bluewhale.agent.model.AgentPhase
import com.bluewhale.agent.model.TargetMode
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class ConversationRole { USER, ASSISTANT }

enum class ConversationKind { CHAT, TASK, ERROR }

data class ConversationMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: ConversationRole,
    val text: String,
    val kind: ConversationKind = ConversationKind.CHAT,
    val timestampMs: Long = System.currentTimeMillis(),
)

/** Append-only conversation timeline, persisted independently from an Agent run. */
object ConversationStore {
    private const val PREFS_NAME = "umbra_conversation"
    private const val KEY_MESSAGES = "messages_v1"
    private const val MAX_MESSAGES = 100

    private val lock = Any()
    private val _messages = MutableStateFlow<List<ConversationMessage>>(emptyList())
    val messages: StateFlow<List<ConversationMessage>> = _messages.asStateFlow()

    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) {
        if (appContext != null) return
        synchronized(lock) {
            if (appContext != null) return
            appContext = context.applicationContext
            val raw = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.getString(KEY_MESSAGES, null)
            _messages.value = decode(raw).ifEmpty { listOf(welcomeMessage()) }
        }
    }

    fun appendUser(text: String, kind: ConversationKind = ConversationKind.CHAT) =
        append(ConversationMessage(role = ConversationRole.USER, text = text, kind = kind))

    fun appendAssistant(text: String, kind: ConversationKind = ConversationKind.CHAT) =
        append(ConversationMessage(role = ConversationRole.ASSISTANT, text = text, kind = kind))

    fun recent(limit: Int = 16): List<ConversationMessage> = _messages.value.takeLast(limit)

    fun clear() {
        synchronized(lock) {
            _messages.value = listOf(welcomeMessage())
            persistLocked()
        }
    }

    private fun append(message: ConversationMessage) {
        if (message.text.isBlank()) return
        synchronized(lock) {
            _messages.value = (_messages.value + message).takeLast(MAX_MESSAGES)
            persistLocked()
        }
    }

    private fun persistLocked() {
        val context = appContext ?: return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_MESSAGES, encode(_messages.value)).apply()
    }

    private fun welcomeMessage() = ConversationMessage(
        role = ConversationRole.ASSISTANT,
        text = "你好，我是 Umbra。可以和我聊天，也可以让我在所选屏幕中操作手机。",
    )

    internal fun encode(messages: List<ConversationMessage>): String = JSONArray().apply {
        messages.forEach { message ->
            put(JSONObject().put("id", message.id).put("role", message.role.name)
                .put("text", message.text).put("kind", message.kind.name)
                .put("timestamp_ms", message.timestampMs))
        }
    }.toString()

    internal fun decode(raw: String?): List<ConversationMessage> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val text = item.optString("text").trim()
                    if (text.isEmpty()) continue
                    add(ConversationMessage(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        role = runCatching { ConversationRole.valueOf(item.optString("role")) }
                            .getOrDefault(ConversationRole.ASSISTANT),
                        text = text,
                        kind = runCatching { ConversationKind.valueOf(item.optString("kind")) }
                            .getOrDefault(ConversationKind.CHAT),
                        timestampMs = item.optLong("timestamp_ms", System.currentTimeMillis()),
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }
}


data class TaskMemoryRecord(
    val task: String,
    val mode: String,
    val phase: String,
    val step: Int,
    val finalMessage: String,
    val packageName: String?,
    val timestampMs: Long = System.currentTimeMillis(),
)

object TaskMemoryStore {
    private const val PREFS_NAME = "umbra_task_memory"
    private const val KEY_RECORDS = "records_v1"
    private const val MAX_RECORDS = 6

    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        if (appContext != null) return
        synchronized(this) {
            if (appContext != null) return
            appContext = context.applicationContext
        }
    }

    fun record(
        task: String,
        mode: TargetMode,
        phase: AgentPhase,
        step: Int,
        finalMessage: String,
        packageName: String?,
    ) {
        if (task.isBlank() && finalMessage.isBlank()) return
        val record =
            TaskMemoryRecord(
                task = task,
                mode = mode.name,
                phase = phase.name,
                step = step,
                finalMessage = finalMessage,
                packageName = packageName,
            )
        synchronized(this) {
            val context = appContext ?: return
            val existing = decode(context)
            val updated = (existing + record).takeLast(MAX_RECORDS)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_RECORDS, encode(updated))
                .apply()
        }
    }

    fun recentPrompt(maxChars: Int = 1_600): String {
        val context = appContext ?: return ""
        val records = decode(context).takeLast(3)
        if (records.isEmpty()) return ""
        return buildString {
            appendLine("最近任务记忆（只用于理解延续和避免重复）：")
            records.forEach { record ->
                val outcome = if (record.phase == AgentPhase.COMPLETE.name) "完成" else "结束"
                val packageSuffix = record.packageName?.let { "，包名=$it" }.orEmpty()
                appendLine(
                    "[$outcome] ${record.task.ifBlank { record.finalMessage }} " +
                        "-> ${record.finalMessage}$packageSuffix",
                )
            }
        }.takeLast(maxChars)
    }

    fun latestTask(): String? {
        val context = appContext ?: return null
        return decode(context).lastOrNull()?.task?.takeIf(String::isNotBlank)
    }

    fun clear() {
        synchronized(this) {
            appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.edit()
                ?.remove(KEY_RECORDS)
                ?.apply()
        }
    }

    private fun decode(context: Context): List<TaskMemoryRecord> {
        val raw =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_RECORDS, null)
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        TaskMemoryRecord(
                            task = item.optString("task"),
                            mode = item.optString("mode"),
                            phase = item.optString("phase"),
                            step = item.optInt("step"),
                            finalMessage = item.optString("final_message"),
                            packageName = item.optString("package_name").ifBlank { null },
                            timestampMs = item.optLong("timestamp_ms", System.currentTimeMillis()),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encode(records: List<TaskMemoryRecord>): String =
        JSONArray().apply {
            records.forEach { record ->
                put(
                    JSONObject()
                        .put("task", record.task)
                        .put("mode", record.mode)
                        .put("phase", record.phase)
                        .put("step", record.step)
                        .put("final_message", record.finalMessage)
                        .put("package_name", record.packageName.orEmpty())
                        .put("timestamp_ms", record.timestampMs),
                )
            }
        }.toString()
}
