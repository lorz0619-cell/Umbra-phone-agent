package com.bluewhale.agent.core

import com.bluewhale.agent.model.DeviceAction
import com.bluewhale.agent.model.AccessibilityElement
import com.bluewhale.agent.model.PerceptionSnapshot
import com.bluewhale.agent.model.PhoneAction
import com.bluewhale.agent.model.SystemCapability
import java.net.URI
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime

/** Validates model output and converts normalized coordinates to device pixels. */
object ActionValidator {
    fun validate(
        action: PhoneAction,
        observation: PerceptionSnapshot,
    ): Result<DeviceAction> =
        validate(
            action = action,
            width = observation.screen.width,
            height = observation.screen.height,
            elements = observation.accessibility.elements,
        )

    fun validate(
        action: PhoneAction,
        width: Int,
        height: Int,
        elements: List<AccessibilityElement> = emptyList(),
    ): Result<DeviceAction> = runCatching {
        require(width > 0 && height > 0) { "屏幕尺寸无效：${width}x$height" }

        when (action) {
            is PhoneAction.Launch -> {
                require(action.app.isNotBlank()) { "Launch 缺少应用名" }
                DeviceAction.Launch(action.app.trim())
            }
            is PhoneAction.Tap -> {
                validateTap(action, width, height, elements)
            }
            is PhoneAction.Type -> {
                validateType(action, width, height, elements)
            }
            is PhoneAction.Swipe -> {
                validateCoordinate(action.startX, "start_x")
                validateCoordinate(action.startY, "start_y")
                validateCoordinate(action.endX, "end_x")
                validateCoordinate(action.endY, "end_y")
                require(action.startX != action.endX || action.startY != action.endY) {
                    "Swipe 起点和终点不能相同"
                }
                DeviceAction.Swipe(
                    startX = scale(action.startX, width),
                    startY = scale(action.startY, height),
                    endX = scale(action.endX, width),
                    endY = scale(action.endY, height),
                    durationMs = action.durationMs.coerceIn(100, 2_000),
                )
            }
            PhoneAction.Back -> DeviceAction.Back
            is PhoneAction.Wait ->
                DeviceAction.Wait(action.durationMs.coerceIn(300, 15_000))
            is PhoneAction.TakeOver -> {
                require(action.message.isNotBlank()) { "Take_over 必须说明接管原因" }
                DeviceAction.TakeOver(action.message.trim())
            }
            is PhoneAction.SystemTool -> {
                validateSystemCapability(action.capability)
                DeviceAction.SystemTool(action.capability)
            }
        }
    }

    private fun validateSystemCapability(capability: SystemCapability) {
        fun text(value: String, name: String, max: Int = 1_000, allowBlank: Boolean = false) {
            require(allowBlank || value.isNotBlank()) { "$name 不能为空" }
            require(value.length <= max) { "$name 过长" }
            require(!value.contains('\u0000')) { "$name 包含非法字符" }
        }
        when (capability) {
            is SystemCapability.Navigate -> {
                text(capability.destination, "destination", 500)
                require(capability.travelMode.lowercase() in setOf("driving", "walking", "bicycling", "transit")) {
                    "travel_mode 仅支持 driving/walking/bicycling/transit"
                }
                require(capability.mapApp.lowercase() in setOf("auto", "amap", "baidu", "tencent")) {
                    "map_app 不在白名单中"
                }
            }
            is SystemCapability.SetAlarm -> {
                require(capability.hour in 0..23) { "闹钟 hour 必须在 0..23" }
                require(capability.minute in 0..59) { "闹钟 minute 必须在 0..59" }
                text(capability.label, "label", 200, allowBlank = true)
                val allowed = setOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
                require(capability.repeatDays.all { it.uppercase() in allowed }) {
                    "repeat_days 必须使用 MON..SUN"
                }
            }
            is SystemCapability.SetTimer -> {
                require(capability.durationSeconds in 1..86_400) { "计时长度必须在 1 秒到 24 小时" }
                text(capability.label, "label", 200, allowBlank = true)
            }
            is SystemCapability.CreateCalendarEvent -> {
                text(capability.title, "title", 300)
                val start = parseDateTime(capability.startTime)
                capability.endTime?.let { endTime ->
                    require(parseDateTime(endTime) > start) { "日历结束时间必须晚于开始时间" }
                }
                text(capability.location, "location", 500, allowBlank = true)
                text(capability.description, "description", 2_000, allowBlank = true)
            }
            is SystemCapability.CreateContact -> {
                text(capability.name, "name", 200)
                text(capability.phone, "phone", 80, allowBlank = true)
                text(capability.email, "email", 320, allowBlank = true)
            }
            is SystemCapability.ComposeSms -> {
                text(capability.recipient, "recipient", 100)
                text(capability.body, "body", 4_000, allowBlank = true)
            }
            is SystemCapability.DialPhone -> text(capability.phoneNumber, "phone_number", 100)
            is SystemCapability.OpenCamera ->
                require(capability.mode.lowercase() in setOf("photo", "video")) {
                    "camera mode 仅支持 photo/video"
                }
            is SystemCapability.OpenUrl -> {
                text(capability.url, "url", 2_000)
                val parsed = runCatching { URI(capability.url) }.getOrNull()
                val scheme = parsed?.scheme?.lowercase()
                require(scheme in setOf("http", "https")) { "只允许打开 http/https URL" }
                require(!parsed?.host.isNullOrBlank()) { "URL 必须包含有效主机名" }
            }
            is SystemCapability.WebSearch -> text(capability.query, "query", 1_000)
            is SystemCapability.OpenSystemSettings ->
                require(
                    capability.page.lowercase() in
                        setOf(
                            "settings",
                            "wifi",
                            "bluetooth",
                            "location",
                            "notification",
                            "display",
                            "sound",
                            "battery",
                            "accessibility",
                            "apps",
                        ),
                ) { "settings page 不在白名单中" }
            is SystemCapability.ComposeEmail -> {
                text(capability.recipient, "recipient", 320)
                text(capability.subject, "subject", 500, allowBlank = true)
                text(capability.body, "body", 8_000, allowBlank = true)
            }
            is SystemCapability.ShareText -> {
                text(capability.text, "text", 8_000)
                text(capability.title, "title", 300, allowBlank = true)
            }
            is SystemCapability.PlayMedia -> text(capability.query, "query", 500)
        }
    }

    internal fun parseDateTime(value: String): Long {
        val clean = value.trim()
        require(clean.isNotBlank()) { "时间不能为空" }
        return runCatching { OffsetDateTime.parse(clean).toInstant().toEpochMilli() }
            .recoverCatching { ZonedDateTime.parse(clean).toInstant().toEpochMilli() }
            .recoverCatching {
                LocalDateTime.parse(clean).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
            .recoverCatching {
                LocalDate.parse(clean).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
            .getOrElse { error("时间格式无效：$value，请使用 ISO-8601") }
    }

    private fun validateCoordinate(value: Int, name: String) {
        require(value in 0..999) { "$name 必须在 0..999，实际为 $value" }
    }

    private fun validateTap(
        action: PhoneAction.Tap,
        width: Int,
        height: Int,
        elements: List<AccessibilityElement>,
    ): DeviceAction.Tap {
        validateCoordinate(action.x, "x")
        validateCoordinate(action.y, "y")
        require(action.targetDescription.isNotBlank()) {
            "Tap 必须提供 target_description 说明点击目标"
        }
        require(action.elementIndex != null || action.targetBounds != null) {
            "视觉 Tap 必须提供 target_box；存在无障碍目标时应提供 element_index"
        }

        val explicitTarget =
            action.elementIndex?.let { requestedIndex ->
                elements.firstOrNull { it.index == requestedIndex }
                    ?: error("Tap 指定的 element_index=$requestedIndex 不存在")
            }
        if (explicitTarget != null) {
            require(!explicitTarget.bounds.isEmpty) {
                "Tap 指定的 element_index=${explicitTarget.index} 边界为空"
            }
            require(!hasEditableSemanticConflict(action.targetDescription, explicitTarget)) {
                "Tap 目标“${action.targetDescription}”与可编辑输入框 #${explicitTarget.index} 语义冲突"
            }
            return explicitTarget.toTap(width, height, "element_index")
        }

        val normalizedPoint =
            action.targetBounds?.let { bounds ->
                validateCoordinate(bounds.left, "target_box.left")
                validateCoordinate(bounds.top, "target_box.top")
                validateCoordinate(bounds.right, "target_box.right")
                validateCoordinate(bounds.bottom, "target_box.bottom")
                require(bounds.left < bounds.right && bounds.top < bounds.bottom) {
                    "Tap target_box 必须具有有效面积"
                }
                ((bounds.left + bounds.right) / 2) to ((bounds.top + bounds.bottom) / 2)
            } ?: (action.x to action.y)
        val approximateX = scale(normalizedPoint.first, width)
        val approximateY = scale(normalizedPoint.second, height)
        val visualStrategy =
            if (action.targetBounds != null) "visual_box_center" else "visual_coordinate"

        val containingTarget =
            elements
                .asSequence()
                .filter { it.clickable || it.editable }
                .filter { !it.bounds.isEmpty && it.bounds.contains(approximateX, approximateY) }
                .minByOrNull { it.bounds.width().toLong() * it.bounds.height().toLong() }
        require(
            containingTarget == null ||
                !hasEditableSemanticConflict(action.targetDescription, containingTarget),
        ) {
            "视觉目标“${action.targetDescription}”落在可编辑输入框 " +
                "#${containingTarget?.index} 内，拒绝错误吸附；请重新定位真实结果"
        }
        return containingTarget
            ?.takeIf { semanticallyMatches(action.targetDescription, it) }
            ?.toTap(width, height, "interactive_bounds")
            ?: DeviceAction.Tap(
                x = approximateX,
                y = approximateY,
                strategy = visualStrategy,
            )
    }

    private fun validateType(
        action: PhoneAction.Type,
        width: Int,
        height: Int,
        elements: List<AccessibilityElement>,
    ): DeviceAction.Type {
        require(action.text.isNotEmpty()) { "Type 文本不能为空" }
        require(action.text.length <= 8_000) { "Type 文本过长" }
        require(
            action.elementIndex == null ||
                action.targetBounds == null,
        ) {
            "Type 的 element_index 与 target_box 只能提供一个"
        }
        if (action.elementIndex != null || action.targetBounds != null) {
            require(action.targetDescription.isNotBlank()) {
                "定向 Type 必须提供 target_description"
            }
        }

        val explicitTarget =
            action.elementIndex?.let { requestedIndex ->
                elements.firstOrNull { it.index == requestedIndex }
                    ?: error("Type 指定的 element_index=$requestedIndex 不存在")
            }
        if (explicitTarget != null) {
            require(explicitTarget.editable) {
                "Type 指定的 element_index=${explicitTarget.index} 不是可编辑节点"
            }
            require(!explicitTarget.bounds.isEmpty) {
                "Type 指定的 element_index=${explicitTarget.index} 边界为空"
            }
            return DeviceAction.Type(
                text = action.text,
                targetX = explicitTarget.bounds.centerX().coerceIn(0, width - 1),
                targetY = explicitTarget.bounds.centerY().coerceIn(0, height - 1),
                targetElementIndex = explicitTarget.index,
                targetLabel = explicitTarget.semanticLabel(),
                strategy = "element_index",
            )
        }

        action.targetBounds?.let { bounds ->
            validateBounds(bounds, "Type")
            val x = scale((bounds.left + bounds.right) / 2, width)
            val y = scale((bounds.top + bounds.bottom) / 2, height)
            val containingEditable =
                elements
                    .asSequence()
                    .filter { it.editable && !it.bounds.isEmpty && it.bounds.contains(x, y) }
                    .minByOrNull { it.bounds.width().toLong() * it.bounds.height().toLong() }
            return DeviceAction.Type(
                text = action.text,
                targetX = containingEditable?.bounds?.centerX()?.coerceIn(0, width - 1) ?: x,
                targetY = containingEditable?.bounds?.centerY()?.coerceIn(0, height - 1) ?: y,
                targetElementIndex = containingEditable?.index,
                targetLabel = containingEditable?.semanticLabel() ?: action.targetDescription,
                strategy =
                    if (containingEditable != null) {
                        "visual_to_editable"
                    } else {
                        "visual_box"
                    },
            )
        }

        val focused = elements.firstOrNull { it.editable && it.focused && !it.bounds.isEmpty }
        require(focused != null) {
            "Type 未提供 element_index/target_box，且当前感知中没有已聚焦的可编辑节点"
        }
        return DeviceAction.Type(
            text = action.text,
            targetX = focused.bounds.centerX().coerceIn(0, width - 1),
            targetY = focused.bounds.centerY().coerceIn(0, height - 1),
            targetElementIndex = focused.index,
            targetLabel = focused.semanticLabel(),
            strategy = "focused_element",
        )
    }

    private fun validateBounds(
        bounds: PhoneAction.NormalizedBounds,
        actionName: String,
    ) {
        validateCoordinate(bounds.left, "target_box.left")
        validateCoordinate(bounds.top, "target_box.top")
        validateCoordinate(bounds.right, "target_box.right")
        validateCoordinate(bounds.bottom, "target_box.bottom")
        require(bounds.left < bounds.right && bounds.top < bounds.bottom) {
            "$actionName target_box 必须具有有效面积"
        }
    }

    private fun hasEditableSemanticConflict(
        targetDescription: String,
        element: AccessibilityElement,
    ): Boolean =
        hasEditableSemanticConflict(targetDescription, element.editable)

    internal fun hasEditableSemanticConflict(
        targetDescription: String,
        editable: Boolean,
    ): Boolean {
        if (!editable) return false
        val target = normalizeSemantic(targetDescription)
        val explicitlyInput =
            listOf("输入框", "搜索框", "文本框", "编辑框").any(target::contains)
        val describesResult =
            listOf("搜索结果", "应用结果", "结果项", "图标", "列表项", "按钮")
                .any(target::contains)
        return describesResult && !explicitlyInput
    }

    private fun semanticallyMatches(
        targetDescription: String,
        element: AccessibilityElement,
    ): Boolean {
        val target = normalizeSemantic(targetDescription)
        val labels =
            listOf(element.text, element.contentDescription, element.resourceId.substringAfterLast('/'))
                .map(::normalizeSemantic)
                .filter { it.length >= 2 }
        return labels.any { label -> target.contains(label) || label.contains(target) }
    }

    private fun normalizeSemantic(value: String): String =
        value.lowercase().replace(Regex("""[\s\-_.:/\\（）()【】\[\]]"""), "")

    private fun AccessibilityElement.toTap(
        width: Int,
        height: Int,
        strategy: String,
    ): DeviceAction.Tap =
        DeviceAction.Tap(
            x = bounds.centerX().coerceIn(0, width - 1),
            y = bounds.centerY().coerceIn(0, height - 1),
            targetElementIndex = index,
            targetLabel =
                text
                    .ifBlank { contentDescription }
                    .ifBlank { resourceId.substringAfterLast('/') }
                    .ifBlank { className.substringAfterLast('.') }
                    .take(160),
            strategy = strategy,
        )

    private fun AccessibilityElement.semanticLabel(): String =
        text
            .ifBlank { contentDescription }
            .ifBlank { resourceId.substringAfterLast('/') }
            .ifBlank { className.substringAfterLast('.') }
            .take(160)

    private fun scale(value: Int, max: Int): Int =
        ((value.toLong() * (max - 1) + 499L) / 999L)
            .toInt()
            .coerceIn(0, max - 1)
}
