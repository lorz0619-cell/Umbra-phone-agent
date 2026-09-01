package com.bluewhale.agent.core

import com.bluewhale.agent.model.ActionHistoryEntry
import com.bluewhale.agent.model.AgentDecision
import com.bluewhale.agent.model.PerceptionSnapshot

interface DecisionModel {
    suspend fun nextDecision(
        task: String,
        observation: PerceptionSnapshot,
        history: List<ActionHistoryEntry>,
        feedback: String?,
    ): AgentDecision

    /**
     * A dedicated recovery decision made after Kotlin has detected a failed hypothesis.
     * Implementations may use a stronger prompt/model; the default keeps custom models compatible.
     */
    suspend fun reflectDecision(
        task: String,
        observation: PerceptionSnapshot,
        history: List<ActionHistoryEntry>,
        trigger: String,
        evidence: String,
        correction: String,
        blockedActions: Set<String>,
    ): AgentDecision =
        nextDecision(
            task = task,
            observation = observation,
            history = history,
            feedback =
                "[REFLECTION] trigger=$trigger；证据：$evidence；纠错要求：$correction；" +
                    "禁止重复：${blockedActions.joinToString()}",
        )

    /**
     * Final dead-end adjudication. The implementation must decide whether a human can
     * safely continue (TakeOver) or whether the task should end with an explicit result.
     */
    suspend fun terminalDecision(
        task: String,
        observation: PerceptionSnapshot,
        history: List<ActionHistoryEntry>,
        trigger: String,
        evidence: String,
        blockedActions: Set<String>,
    ): AgentDecision =
        nextDecision(
            task = task,
            observation = observation,
            history = history,
            feedback =
                "[TERMINAL_ADJUDICATION] trigger=$trigger；证据：$evidence；" +
                    "请只选择 Take_over 请求人工接管，或 complete_task 明确终止。" +
                    "禁止重复：${blockedActions.joinToString()}",
        )
}
