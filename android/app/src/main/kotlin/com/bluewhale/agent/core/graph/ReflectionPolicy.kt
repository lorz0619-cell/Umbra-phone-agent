package com.bluewhale.agent.core.graph

import com.bluewhale.agent.model.ActionHistoryEntry

data class ReflectionAssessment(
    val trigger: String,
    val evidence: String,
    val correction: String,
    val blockedActionKeys: Set<String> = emptySet(),
    val blockedTapCoordinates: Set<String> = emptySet(),
    val candidateHints: List<String> = emptyList(),
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
        repeatedWaitCount: Int = 0,
        repeatedTapCoordinateCount: Int = 0,
        repeatedTypeFailureCount: Int = 0,
        currentTapCoordinateKey: String? = null,
    ): ReflectionAssessment? {
        val current = history.lastOrNull() ?: return null
        if (repeatedWaitCount >= REPEATED_WAIT_RUN) {
            return ReflectionAssessment(
                trigger = "repeated_wait",
                evidence =
                    "最近 $repeatedWaitCount 次连续 Wait 后仍未离开等待状态；" +
                        "页面视觉可能有动画，但任务路径没有推进",
                correction =
                    "停止继续 Wait；重新规划为 Tap、Back、Launch 或明确结束任务。" +
                        "仅当截图明确显示仍在加载且没有其他可用动作时才可再 Wait",
                blockedActionKeys = setOf(current.pageActionKey()),
            )
        }
        if (currentTapCoordinateKey != null && repeatedTapCoordinateCount >= REPEATED_TAP_COORDINATE_RUN) {
            return ReflectionAssessment(
                trigger = "repeated_tap_coordinate",
                evidence =
                    "最近 $repeatedTapCoordinateCount 次 Tap 命中同一归一化坐标 " +
                        "${currentTapCoordinateKey.removePrefix("tap:coord:")}，页面没有切换成功",
                correction =
                    "继续使用 Tap，但必须切换成明显不同的坐标、目标节点或交互路径；" +
                        "先重新读取截图边界并计算新中心，不能只改描述。只有无法定位其他目标时才 Back/Wait/Take_over",
                blockedActionKeys = setOf(current.pageActionKey()),
                blockedTapCoordinates = setOf(currentTapCoordinateKey),
            )
        }
        if (repeatedTypeFailureCount >= REPEATED_TYPE_FAILURE_RUN) {
            return ReflectionAssessment(
                trigger = "repeated_type_failure",
                evidence = "最近 $repeatedTypeFailureCount 次 Type 未通过后置验证",
                correction =
                    "重新确认当前输入焦点；改用定向 element_index、切换输入框、" +
                        "先清理/重建焦点，再重新 Type。不要只重放相同输入路径",
                blockedActionKeys = setOf(current.pageActionKey()),
            )
        }
        if (!current.actionSucceeded && (tapTargetAttempts >= 2 || nearbyTapAttempts >= 2)) {
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

        detectConsecutiveSameAction(history)?.let { return it }
        for (period in 2..MAX_CYCLE_PERIOD) {
            detectCycle(history, period)?.let { return it }
        }
        return null
    }

    private fun detectConsecutiveSameAction(
        history: List<ActionHistoryEntry>,
    ): ReflectionAssessment? {
        if (history.size < REPEATED_ACTION_RUN) return null
        val run = history.takeLast(REPEATED_ACTION_RUN)
        val first = run.first()
        val sameAction = run.all { it.actionKey == first.actionKey }
        val noProgress = run.none { it.subtaskProgressed }
        val samePage =
            run.zipWithNext().all { (left, right) ->
                samePage(left.beforeStateSignature, right.beforeStateSignature)
            }
        if (!sameAction || !noProgress || !samePage) return null

        return ReflectionAssessment(
            trigger = "single_action_loop",
            evidence =
                "最近 $REPEATED_ACTION_RUN 步连续重复同一动作且页面未推进：" +
                    run.joinToString(" → ") { it.action },
            correction =
                "停止重复当前动作；重新读取无障碍节点和截图，改用不同动作类型、不同节点或请求人工接管",
            blockedActionKeys = run.mapTo(linkedSetOf()) { it.pageActionKey() },
        )
    }

    private fun detectCycle(
        history: List<ActionHistoryEntry>,
        period: Int,
    ): ReflectionAssessment? {
        val requiredSize = period * 2
        if (history.size < requiredSize) return null

        val recent = history.takeLast(requiredSize)
        val firstCycle = recent.subList(0, period)
        val secondCycle = recent.subList(period, requiredSize)
        val actionKeysMatch =
            firstCycle.indices.all { index ->
                firstCycle[index].actionKey == secondCycle[index].actionKey
            }
        val hasDistinctActionKeys =
            firstCycle.map { it.actionKey }.toSet().size > 1
        val pagesMatch =
            firstCycle.indices.all { index ->
                samePage(
                    firstCycle[index].beforeStateSignature,
                    secondCycle[index].beforeStateSignature,
                )
            }
        val noProgress =
            (firstCycle + secondCycle).none { it.subtaskProgressed }
        if (!actionKeysMatch || !hasDistinctActionKeys || !pagesMatch || !noProgress) {
            return null
        }

        return ReflectionAssessment(
            trigger = cycleTrigger(period),
            evidence =
                "最近 ${requiredSize} 步形成周期为 $period 的循环：" +
                    firstCycle.joinToString(" → ") { it.action } +
                    " 与 " +
                    secondCycle.joinToString(" → ") { it.action } +
                    " 重复；动作可能成功，但子任务没有推进",
            correction =
                "放弃这个周期内的页面-动作组合，重新核对用户目标与当前页面，选择另一条路径或在目标已完成时结束任务",
            blockedActionKeys = secondCycle.mapTo(linkedSetOf()) { it.pageActionKey() },
        )
    }

    private fun cycleTrigger(period: Int): String =
        when (period) {
            2 -> "two_state_loop"
            3 -> "three_state_loop"
            4 -> "four_state_loop"
            else -> "state_loop"
        }

    private fun samePage(left: String, right: String): Boolean =
        left == right || (left.isBlank() && right.isBlank())

    fun pageActionKey(
        pageSignature: String,
        actionKey: String,
    ): String = "$pageSignature|$actionKey"

    private fun ActionHistoryEntry.pageActionKey(): String =
        pageActionKey(beforeStateSignature, actionKey)

    private const val REPEATED_ACTION_RUN = 3
    private const val MAX_CYCLE_PERIOD = 4
    private const val REPEATED_WAIT_RUN = 3
    private const val REPEATED_TAP_COORDINATE_RUN = 4
    private const val REPEATED_TYPE_FAILURE_RUN = 3
}
