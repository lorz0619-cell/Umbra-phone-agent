package com.bluewhale.agent.core.graph

import com.bluewhale.agent.model.ActionHistoryEntry

data class ReflectionAssessment(
    val trigger: String,
    val evidence: String,
    val correction: String,
    val blockedActionKeys: Set<String> = emptySet(),
)

/**
 * Deterministic guard that decides when the graph must stop executing and reflect.
 *
 * The VLM still chooses the corrected action, but it cannot silently repeat an action/page
 * combination that Kotlin has already identified as stuck or cyclic.
 */
object ReflectionPolicy {
    fun assess(
        history: List<ActionHistoryEntry>,
        tapTargetAttempts: Int,
        nearbyTapAttempts: Int,
    ): ReflectionAssessment? {
        val current = history.lastOrNull() ?: return null
        if (!current.verified && (tapTargetAttempts >= 2 || nearbyTapAttempts >= 2)) {
            return ReflectionAssessment(
                trigger = "tap_stuck",
                evidence =
                    "同一目标已执行 $tapTargetAttempts 次，邻近区域连续尝试 $nearbyTapAttempts 次，后置验证仍失败",
                correction =
                    "停止当前定位假设；重新读取无障碍节点和截图。输入框可见时直接定向 Type，" +
                        "否则改用元素节点、明显不同区域、Back、Swipe、Wait 或 Take_over",
                blockedActionKeys = setOf(current.pageActionKey()),
            )
        }

        val recent = history.takeLast(4)
        if (recent.size == 4) {
            val first = recent[0]
            val second = recent[1]
            val third = recent[2]
            val fourth = recent[3]
            val repeatedActions =
                first.actionKey == third.actionKey &&
                    second.actionKey == fourth.actionKey &&
                    first.actionKey != second.actionKey
            val repeatedPages =
                first.beforeStateSignature.isBlank() ||
                    second.beforeStateSignature.isBlank() ||
                    (
                        first.beforeStateSignature == third.beforeStateSignature &&
                            second.beforeStateSignature == fourth.beforeStateSignature
                    )
            if (repeatedActions && repeatedPages) {
                return ReflectionAssessment(
                    trigger = "two_state_loop",
                    evidence =
                        "最近四步形成 A→B→A→B 循环：${first.action} ↔ ${second.action}；" +
                            "单步虽然可能验证成功，但任务没有推进",
                    correction =
                        "放弃这两个页面-动作组合，重新核对用户目标与当前页面，选择第三种路径或在目标已完成时结束任务",
                    blockedActionKeys = recent.mapTo(linkedSetOf()) { it.pageActionKey() },
                )
            }
        }
        return null
    }

    fun pageActionKey(
        pageSignature: String,
        actionKey: String,
    ): String = "$pageSignature|$actionKey"

    private fun ActionHistoryEntry.pageActionKey(): String =
        pageActionKey(beforeStateSignature, actionKey)
}
