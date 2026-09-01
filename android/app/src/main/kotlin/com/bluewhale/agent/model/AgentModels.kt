package com.bluewhale.agent.model

import android.graphics.Bitmap
import android.graphics.Rect

const val DEFAULT_VLM_BASE_URL = "https://api.deepseek.com"
const val DEFAULT_VLM_MODEL = "deepseek-v4-flash-vision-exp"

enum class TargetMode(val label: String) {
    MAIN_SCREEN("主屏"),
    VIRTUAL_DISPLAY("虚拟屏"),
}

/** Runtime configuration for any OpenAI-compatible multimodal model. */
data class VlmConfig(
    val apiKey: String,
    val baseUrl: String = DEFAULT_VLM_BASE_URL,
    val model: String = DEFAULT_VLM_MODEL,
    val maxSteps: Int = 40,
    val maxConsecutiveFailures: Int = 5,
)

/** The only device actions that a model is allowed to request in v2. */
sealed interface PhoneAction {
    val toolName: String

    data class NormalizedBounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    data class Launch(val app: String) : PhoneAction {
        override val toolName = "launch"
    }

    /** Coordinates use the normalized 0..999 convention retained from v1. */
    data class Tap(
        val x: Int,
        val y: Int,
        val elementIndex: Int? = null,
        val targetBounds: NormalizedBounds? = null,
        val targetDescription: String = "",
    ) : PhoneAction {
        override val toolName = "tap"
    }

    data class Type(
        val text: String,
        val elementIndex: Int? = null,
        val targetBounds: NormalizedBounds? = null,
        val targetDescription: String = "",
    ) : PhoneAction {
        override val toolName = "type"
    }

    data class Swipe(
        val startX: Int,
        val startY: Int,
        val endX: Int,
        val endY: Int,
        val durationMs: Int = 300,
    ) : PhoneAction {
        override val toolName = "swipe"
    }

    data object Back : PhoneAction {
        override val toolName = "back"
    }

    data class Wait(val durationMs: Int = 2_000) : PhoneAction {
        override val toolName = "wait"
    }

    data class TakeOver(val message: String) : PhoneAction {
        override val toolName = "take_over"
    }

    data class SystemTool(val capability: SystemCapability) : PhoneAction {
        override val toolName = capability.toolName
    }
}

/** Closed, allow-listed Android capabilities. The model never supplies raw Intent fields. */
sealed interface SystemCapability {
    val toolName: String
    val displayName: String
        get() = toolName
    val requiresUserInteraction: Boolean

    data class Navigate(
        val destination: String,
        val travelMode: String = "driving",
        val mapApp: String = "auto",
    ) : SystemCapability {
        override val toolName = "navigate"
        override val requiresUserInteraction = true
    }

    data class SetAlarm(
        val hour: Int,
        val minute: Int,
        val label: String = "",
        val repeatDays: List<String> = emptyList(),
    ) : SystemCapability {
        override val toolName = "set_alarm"
        override val requiresUserInteraction = false
    }

    data class SetTimer(
        val durationSeconds: Int,
        val label: String = "",
    ) : SystemCapability {
        override val toolName = "set_timer"
        override val requiresUserInteraction = false
    }

    data class CreateCalendarEvent(
        val title: String,
        val startTime: String,
        val endTime: String? = null,
        val location: String = "",
        val description: String = "",
        val allDay: Boolean = false,
    ) : SystemCapability {
        override val toolName = "create_calendar_event"
        override val requiresUserInteraction = true
    }

    data class CreateContact(
        val name: String,
        val phone: String = "",
        val email: String = "",
    ) : SystemCapability {
        override val toolName = "create_contact"
        override val requiresUserInteraction = true
    }

    data class ComposeSms(val recipient: String, val body: String) : SystemCapability {
        override val toolName = "compose_sms"
        override val requiresUserInteraction = true
    }

    data class DialPhone(val phoneNumber: String) : SystemCapability {
        override val toolName = "dial_phone"
        override val requiresUserInteraction = true
    }

    data class OpenCamera(val mode: String = "photo") : SystemCapability {
        override val toolName = "open_camera"
        override val requiresUserInteraction = true
    }

    data class OpenUrl(val url: String) : SystemCapability {
        override val toolName = "open_url"
        override val requiresUserInteraction = false
    }

    data class WebSearch(val query: String) : SystemCapability {
        override val toolName = "web_search"
        override val requiresUserInteraction = false
    }

    data class OpenSystemSettings(val page: String = "settings") : SystemCapability {
        override val toolName = "open_system_settings"
        override val requiresUserInteraction = false
    }

    data class ComposeEmail(
        val recipient: String,
        val subject: String = "",
        val body: String = "",
    ) : SystemCapability {
        override val toolName = "compose_email"
        override val requiresUserInteraction = true
    }

    data class ShareText(val text: String, val title: String = "") : SystemCapability {
        override val toolName = "share_text"
        override val requiresUserInteraction = true
    }

    data class PlayMedia(val query: String) : SystemCapability {
        override val toolName = "play_media"
        override val requiresUserInteraction = false
    }
}

/** A model either proposes exactly one action or declares the task complete. */
sealed interface AgentDecision {
    data class Act(
        val action: PhoneAction,
        val subtask: String = "",
        val rationale: String = "",
        val expectedOutcome: String = "",
        val failureCause: String = "",
        val strategyChange: String = "",
    ) : AgentDecision

    data class Complete(
        val message: String,
        val success: Boolean = true,
    ) : AgentDecision
}

/** Pixel-space actions are produced only after Kotlin preflight validation. */
sealed interface DeviceAction {
    data class Launch(val app: String) : DeviceAction
    data class Tap(
        val x: Int,
        val y: Int,
        val targetElementIndex: Int? = null,
        val targetLabel: String? = null,
        val strategy: String = "visual_coordinate",
    ) : DeviceAction
    data class Type(
        val text: String,
        val targetX: Int? = null,
        val targetY: Int? = null,
        val targetElementIndex: Int? = null,
        val targetLabel: String? = null,
        val strategy: String = "focused_input",
    ) : DeviceAction
    data class Swipe(
        val startX: Int,
        val startY: Int,
        val endX: Int,
        val endY: Int,
        val durationMs: Int,
    ) : DeviceAction
    data object Back : DeviceAction
    data class Wait(val durationMs: Int) : DeviceAction
    data class TakeOver(val message: String) : DeviceAction
    data class SystemTool(val capability: SystemCapability) : DeviceAction
}

data class ScreenCapture(
    val width: Int,
    val height: Int,
    val bitmap: Bitmap,
    val frameId: Long = 0L,
    val capturedAtElapsedRealtimeMs: Long = 0L,
    val isFresh: Boolean = true,
    val source: String = "unknown",
)

data class AccessibilityElement(
    val index: Int,
    val className: String,
    val text: String,
    val contentDescription: String,
    val resourceId: String,
    val bounds: Rect,
    val clickable: Boolean,
    val editable: Boolean,
    val focused: Boolean,
) {
    fun promptLine(): String {
        val labels =
            listOfNotNull(
                text.takeIf { it.isNotBlank() }?.let { "text=${it.take(160)}" },
                contentDescription.takeIf { it.isNotBlank() }?.let { "desc=${it.take(160)}" },
                resourceId.takeIf { it.isNotBlank() }?.let { "id=${it.takeLast(100)}" },
            ).joinToString(" ")
        return "#$index ${className.substringAfterLast('.')} " +
            "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}] " +
            "clickable=$clickable editable=$editable focused=$focused $labels"
    }
}

data class AccessibilitySnapshot(
    val packageName: String? = null,
    val elements: List<AccessibilityElement> = emptyList(),
    val focusedText: String? = null,
    val treeHash: Long = 0L,
) {
    fun promptText(): String =
        if (elements.isEmpty()) {
            "(无可用无障碍节点，请主要依据截图)"
        } else {
            elements.joinToString("\n") { it.promptLine() }
        }
}

data class PerceptionSnapshot(
    val screen: ScreenCapture,
    val accessibility: AccessibilitySnapshot,
    val visualFingerprint: IntArray,
) {
    fun recycle() {
        if (!screen.bitmap.isRecycled) screen.bitmap.recycle()
    }
}

sealed interface ActionResult {
    data class Success(
        val message: String,
        val metadata: Map<String, String> = emptyMap(),
    ) : ActionResult

    data class Failure(val message: String, val cause: Throwable? = null) : ActionResult
}

data class VerificationResult(
    val success: Boolean,
    val message: String,
    val visualChangeScore: Double = 0.0,
    val packageChanged: Boolean = false,
    val treeChanged: Boolean = false,
)

enum class AgentTraceKind {
    TASK,
    PHASE,
    PERCEPTION,
    DECISION,
    VALIDATION,
    EXECUTION,
    VERIFICATION,
    ROUTING,
    REFLECTION,
    COMPLETE,
    ERROR,
}

enum class AgentTraceLevel {
    INFO,
    WARNING,
    ERROR,
}

// Transport-neutral graph event. Android serialization belongs to the service layer.
data class AgentTraceEvent(
    val kind: AgentTraceKind,
    val step: Int,
    val phase: AgentPhase,
    val title: String,
    val message: String = "",
    val fields: Map<String, String> = emptyMap(),
    val level: AgentTraceLevel = AgentTraceLevel.INFO,
)

data class ActionHistoryEntry(
    val step: Int,
    val action: String,
    val result: String,
    val verified: Boolean,
    val packageName: String?,
    val actionKey: String = action,
    val beforeStateSignature: String = "",
    val afterStateSignature: String = "",
    val rationale: String = "",
    val expectedOutcome: String = "",
    val subtask: String = "",
)

enum class AgentPhase(val label: String) {
    IDLE("未运行"),
    PERCEIVE("感知屏幕与无障碍树"),
    PLAN("模型规划结构化动作"),
    VALIDATE("校验动作参数"),
    EXECUTE("执行动作"),
    VERIFY("验证动作结果"),
    ROUTE("路由下一节点"),
    REFLECT("反思失败与循环"),
    COMPLETE("任务完成"),
    TAKEOVER("等待人工接管"),
    FAILED("任务失败"),
}

data class AgentRunState(
    val running: Boolean = false,
    val task: String = "",
    val mode: TargetMode? = null,
    val step: Int = 0,
    val phase: AgentPhase = AgentPhase.IDLE,
    val status: String = "未运行",
    val verification: String = "",
    val awaitingTakeover: Boolean = false,
    val logs: List<String> = emptyList(),
)
