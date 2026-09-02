package com.bluewhale.agent.core.graph

import com.bluewhale.agent.core.ActionValidator
import com.bluewhale.agent.core.DecisionModel
import com.bluewhale.agent.model.ActionHistoryEntry
import com.bluewhale.agent.model.ActionResult
import com.bluewhale.agent.model.AgentDecision
import com.bluewhale.agent.model.AgentPhase
import com.bluewhale.agent.model.AgentRunState
import com.bluewhale.agent.model.AgentTraceEvent
import com.bluewhale.agent.model.AgentTraceKind
import com.bluewhale.agent.model.AgentTraceLevel
import com.bluewhale.agent.model.DeviceAction
import com.bluewhale.agent.model.PerceptionSnapshot
import com.bluewhale.agent.model.PhoneAction
import com.bluewhale.agent.model.SystemCapability
import com.bluewhale.agent.model.VerificationResult
import com.bluewhale.agent.perception.PerceptionEngine
import com.bluewhale.agent.platform.AgentPlatform
import com.bluewhale.agent.verification.ActionVerifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

enum class AgentNode {
    PERCEIVE,
    PLAN,
    VALIDATE,
    EXECUTE,
    VERIFY,
    ROUTE,
    REFLECT,
    REPLAN,
    END,
}

data class AgentGraphState(
    val task: String,
    val node: AgentNode = AgentNode.PERCEIVE,
    val step: Int = 0,
    val observation: PerceptionSnapshot? = null,
    val requestedAction: PhoneAction? = null,
    val deviceAction: DeviceAction? = null,
    val executionResult: ActionResult? = null,
    val verification: VerificationResult? = null,
    val history: List<ActionHistoryEntry> = emptyList(),
    val feedback: String? = null,
    val decisionRationale: String = "",
    val expectedOutcome: String = "",
    val actionPageSignature: String = "",
    val pendingReflection: ReflectionAssessment? = null,
    val activeSubtask: String = "",
    val activeSubtaskKey: String = "",
    val subtaskReflectionCount: Int = 0,
    val blockedActionKeys: Set<String> = emptySet(),
    val pendingTypedTextHash: Int? = null,
    val committedTextHashes: Set<Int> = emptySet(),
    val consecutiveActionFailures: Int = 0,
    val consecutivePlanningFailures: Int = 0,
    val lastActionSignature: String? = null,
    val repeatedActionCount: Int = 0,
    val repeatedWaitCount: Int = 0,
    val repeatedTapCoordinateCount: Int = 0,
    val repeatedTypeFailureCount: Int = 0,
    val lastTapCoordinateKey: String? = null,
    val candidateAttemptsInEpisode: Int = 0,
    val lastCandidateFingerprint: String? = null,
    val tapTargetSignature: String? = null,
    val tapTargetAttempts: Int = 0,
    val lastFailedTapX: Int? = null,
    val lastFailedTapY: Int? = null,
    val nearbyTapAttempts: Int = 0,
    val blockedTapCoordinates: Set<String> = emptySet(),
    val consecutiveNoProgressWaits: Int = 0,
    val consecutiveNoVisualProgress: Int = 0,
    val finalMessage: String? = null,
    val finalPhase: AgentPhase? = null,
)

data class GraphCommand(
    val state: AgentGraphState,
    val next: AgentNode,
)

data class GraphRunResult(
    val message: String,
    val phase: AgentPhase,
    val step: Int = 0,
    val packageName: String? = null,
)

/**
 * Small Kotlin StateGraph inspired by LangGraph.
 *
 * Nodes own work and state updates; the edge table owns allowed control flow.
 * Runtime edge validation prevents a node from jumping to an undeclared phase.
 */
class AgentGraph(
    private val platform: AgentPlatform,
    private val model: DecisionModel,
    private val task: String,
    private val maxSteps: Int,
    private val bootstrapAction: PhoneAction? = null,
    private val maxConsecutiveFailures: Int = 5,
    private val perception: PerceptionEngine = PerceptionEngine(),
    private val verifier: ActionVerifier = ActionVerifier(),
    private val onState: (AgentRunState) -> Unit,
    private val onTrace: (AgentTraceEvent) -> Unit = {},
) {
    private val allowedEdges =
        mapOf(
            AgentNode.PERCEIVE to setOf(AgentNode.PLAN, AgentNode.REPLAN, AgentNode.END),
            AgentNode.PLAN to setOf(AgentNode.VALIDATE, AgentNode.ROUTE, AgentNode.END),
            AgentNode.VALIDATE to setOf(AgentNode.EXECUTE, AgentNode.ROUTE, AgentNode.REFLECT),
            AgentNode.EXECUTE to setOf(AgentNode.VERIFY),
            AgentNode.VERIFY to setOf(AgentNode.ROUTE),
            AgentNode.ROUTE to setOf(AgentNode.PLAN, AgentNode.PERCEIVE, AgentNode.REFLECT, AgentNode.END),
            AgentNode.REFLECT to setOf(AgentNode.PERCEIVE, AgentNode.END),
            AgentNode.REPLAN to
                setOf(AgentNode.VALIDATE, AgentNode.REFLECT, AgentNode.ROUTE, AgentNode.END),
            AgentNode.END to emptySet(),
        )

    suspend fun run(): GraphRunResult {
        var state = AgentGraphState(task = task)
        var terminalPhase = AgentPhase.FAILED
        try {
            trace(
                kind = AgentTraceKind.TASK,
                state = state,
                phase = AgentPhase.PERCEIVE,
                title = "任务开始",
                fields =
                    linkedMapOf(
                        "task" to task,
                        "mode" to platform.mode.label,
                        "max_steps" to maxSteps.toString(),
                        "max_action_failures" to maxConsecutiveFailures.toString(),
                        "max_planning_failures" to MAX_PLANNING_FAILURES.toString(),
                    ),
            )
            platform.start()
            while (state.node != AgentNode.END) {
                val phase = phaseFor(state.node)
                emit(state, phase, phase.label)
                trace(
                    kind = AgentTraceKind.PHASE,
                    state = state,
                    phase = phase,
                    title = phase.label,
                )
                val command =
                    when (state.node) {
                        AgentNode.PERCEIVE -> perceiveNode(state)
                        AgentNode.PLAN -> planNode(state)
                        AgentNode.VALIDATE -> validateNode(state)
                        AgentNode.EXECUTE -> executeNode(state)
                        AgentNode.VERIFY -> verifyNode(state)
                        AgentNode.ROUTE -> routeNode(state)
                        AgentNode.REFLECT -> reflectNode(state)
                        AgentNode.REPLAN -> replanNode(state)
                        AgentNode.END -> GraphCommand(state, AgentNode.END)
                    }
                validateEdge(state.node, command.next)
                state = command.state.copy(node = command.next)
            }

            val rawPhase = state.finalPhase ?: AgentPhase.FAILED
            val rawMessage = state.finalMessage ?: "Agent 图在未给出结果时结束"
            val phase =
                if (platform.mode == com.bluewhale.agent.model.TargetMode.VIRTUAL_DISPLAY && rawPhase == AgentPhase.COMPLETE) {
                    AgentPhase.TAKEOVER
                } else {
                    rawPhase
                }
            terminalPhase = phase
            val message =
                if (phase == AgentPhase.TAKEOVER && rawPhase == AgentPhase.COMPLETE) {
                    "$rawMessage。是否将虚拟屏当前页面接管到主屏？"
                } else {
                    rawMessage
                }
            val terminalKind =
                when (phase) {
                    AgentPhase.COMPLETE -> AgentTraceKind.COMPLETE
                    AgentPhase.TAKEOVER -> AgentTraceKind.TAKEOVER
                    else -> AgentTraceKind.ERROR
                }
            val terminalTitle =
                when (phase) {
                    AgentPhase.COMPLETE -> "任务完成"
                    AgentPhase.TAKEOVER -> "任务等待人工接管"
                    else -> "任务结束"
                }
            val terminalLevel =
                when (phase) {
                    AgentPhase.COMPLETE -> AgentTraceLevel.INFO
                    AgentPhase.TAKEOVER -> AgentTraceLevel.WARNING
                    else -> AgentTraceLevel.ERROR
                }
            trace(
                kind = terminalKind,
                state = state,
                phase = phase,
                title = terminalTitle,
                message = message,
                level = terminalLevel,
            )
            emit(
                state,
                phase,
                message,
                running = false,
                awaitingTakeover = phase == AgentPhase.TAKEOVER,
            )
            return GraphRunResult(
                message = message,
                phase = phase,
                step = state.step,
                packageName = state.observation?.accessibility?.packageName,
            )
        } finally {
            state.observation?.recycle()
            // A virtual display must remain alive while the user decides whether to
            // move its current task to the physical display. AgentService owns cleanup.
            if (terminalPhase != AgentPhase.TAKEOVER) platform.stop()
        }
    }

    private suspend fun perceiveNode(state: AgentGraphState): GraphCommand =
        try {
            val next = capturePerceptionWithRetry(requireFresh = true)
            trace(
                kind = AgentTraceKind.PERCEPTION,
                state = state,
                phase = AgentPhase.PERCEIVE,
                title = "屏幕感知完成",
                fields =
                    linkedMapOf(
                        "screen" to "${next.screen.width}x${next.screen.height}",
                        "package" to next.accessibility.packageName.orEmpty(),
                        "elements" to next.accessibility.elements.size.toString(),
                        "focused_text" to next.accessibility.focusedText.orEmpty(),
                        "tree_hash" to next.accessibility.treeHash.toString(),
                        "frame_id" to next.screen.frameId.toString(),
                        "frame_fresh" to next.screen.isFresh.toString(),
                        "frame_source" to next.screen.source,
                    ),
            )
            state.observation?.recycle()
            GraphCommand(
                state.copy(
                    observation = next,
                    feedback = state.feedback,
                    verification = null,
                ),
                if (state.pendingReflection != null) AgentNode.REPLAN else AgentNode.PLAN,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            GraphCommand(
                state.copy(
                    finalMessage = "感知失败：${error.message ?: error.javaClass.simpleName}",
                    finalPhase = AgentPhase.FAILED,
                ),
                AgentNode.END,
            )
        }

    private suspend fun capturePerceptionWithRetry(
        maxAttempts: Int = 3,
        requireFresh: Boolean = false,
    ): PerceptionSnapshot {
        var lastError: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                val snapshot = perception.capture(platform)
                if (requireFresh && !snapshot.screen.isFresh) {
                    snapshot.recycle()
                    throw IllegalStateException("重新规划前仍未取得虚拟屏新帧")
                }
                return snapshot
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastError = error
                if (attempt < maxAttempts - 1) {
                    delay(350L * (attempt + 1))
                }
            }
        }
        throw lastError ?: IllegalStateException("感知失败")
    }

    private suspend fun planNode(state: AgentGraphState): GraphCommand {
        if (state.step >= maxSteps) {
            return GraphCommand(
                state.copy(
                    finalMessage = "达到最大动作步数 $maxSteps，任务已熔断",
                    finalPhase = AgentPhase.FAILED,
                ),
                AgentNode.END,
            )
        }
        val observation =
            state.observation
                ?: return planningFailure(state, "规划前缺少感知快照")

        return try {
            val useBootstrap =
                bootstrapAction != null &&
                    state.step == 0 &&
                    state.history.isEmpty()
            val decision =
                if (useBootstrap) {
                    AgentDecision.Act(
                        action = requireNotNull(bootstrapAction),
                        subtask = "launch_application",
                        rationale = "Kotlin 检测到明确的已安装应用启动请求",
                        expectedOutcome = "前台包名切换到目标应用且页面稳定",
                    )
                } else {
                    model.nextDecision(
                        task = state.task,
                        observation = observation,
                        history = state.history,
                        feedback = state.feedback,
                    )
                }
            when (
                decision
            ) {
                is AgentDecision.Complete -> {
                    trace(
                        kind =
                            if (decision.success) {
                                AgentTraceKind.COMPLETE
                            } else {
                                AgentTraceKind.ERROR
                            },
                        state = state,
                        phase = if (decision.success) AgentPhase.COMPLETE else AgentPhase.FAILED,
                        title = if (decision.success) "模型确认任务完成" else "模型报告任务失败",
                        message = decision.message,
                        level =
                            if (decision.success) {
                                AgentTraceLevel.INFO
                            } else {
                                AgentTraceLevel.ERROR
                            },
                    )
                    GraphCommand(
                        state.copy(
                            finalMessage = decision.message,
                            finalPhase = if (decision.success) AgentPhase.COMPLETE else AgentPhase.FAILED,
                        ),
                        AgentNode.END,
                    )
                }
                is AgentDecision.Act ->
                    state.copy(step = state.step + 1).let { rawProposedState ->
                        val allowSubtaskTransition =
                            state.activeSubtaskKey.isBlank() ||
                                (
                                    state.consecutiveActionFailures == 0 &&
                                        state.history.lastOrNull()?.subtaskProgressed == true
                                )
                        val scope =
                            SubtaskScopePolicy.resolve(
                                currentLabel = state.activeSubtask,
                                currentKey = state.activeSubtaskKey,
                                proposedLabel = decision.subtask,
                                expectedOutcome = decision.expectedOutcome,
                                fallback = decision.action.toolName,
                                allowTransition = allowSubtaskTransition,
                            )
                        val proposedState =
                            rawProposedState.copy(
                                activeSubtask = scope.label,
                                activeSubtaskKey = scope.key,
                                subtaskReflectionCount =
                                    if (scope.changed) 0 else state.subtaskReflectionCount,
                                blockedActionKeys =
                                    if (scope.changed) emptySet() else state.blockedActionKeys,
                                blockedTapCoordinates =
                                    if (scope.changed) emptySet() else state.blockedTapCoordinates,
                                consecutiveNoVisualProgress =
                                    if (scope.changed) 0 else state.consecutiveNoVisualProgress,
                                repeatedWaitCount =
                                    if (scope.changed) 0 else state.repeatedWaitCount,
                                repeatedTapCoordinateCount =
                                    if (scope.changed) 0 else state.repeatedTapCoordinateCount,
                                repeatedTypeFailureCount =
                                    if (scope.changed) 0 else state.repeatedTypeFailureCount,
                                lastTapCoordinateKey =
                                    if (scope.changed) null else state.lastTapCoordinateKey,
                                candidateAttemptsInEpisode =
                                    if (scope.changed) 0 else state.candidateAttemptsInEpisode,
                                lastCandidateFingerprint =
                                    if (scope.changed) null else state.lastCandidateFingerprint,
                            )
                        trace(
                            kind = AgentTraceKind.DECISION,
                            state = proposedState,
                            phase = AgentPhase.PLAN,
                            title =
                                if (useBootstrap) {
                                    "本地启动策略选择动作"
                                } else {
                                    "模型选择动作"
                                },
                            fields =
                                linkedMapOf<String, String>().apply {
                                    putAll(decision.action.traceFields())
                                    put("subtask", scope.label)
                                    put("subtask_key", scope.key)
                                    put("subtask_changed", scope.changed.toString())
                                    put(
                                        "subtask_reflections",
                                        proposedState.subtaskReflectionCount.toString(),
                                    )
                                    put("rationale", decision.rationale)
                                    put("expected_outcome", decision.expectedOutcome)
                                },
                        )
                        emit(
                            proposedState,
                            AgentPhase.PLAN,
                            "提出动作：${decision.action.describe()}",
                        )
                    GraphCommand(
                        proposedState.copy(
                            requestedAction = decision.action,
                            deviceAction = null,
                            executionResult = null,
                            verification = null,
                            feedback = null,
                            decisionRationale = decision.rationale,
                            expectedOutcome = decision.expectedOutcome,
                            actionPageSignature = observation.pageSignature(),
                            pendingReflection = null,
                        ),
                        AgentNode.VALIDATE,
                    )
                    }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val diagnostic = planningErrorDiagnostic(error)
            planningFailure(state, diagnostic.first, diagnostic.second)
        }
    }

    private fun planningFailure(
        state: AgentGraphState,
        message: String,
        fields: Map<String, String> = emptyMap(),
    ): GraphCommand =
        state.let { failedState ->
            trace(
                kind = AgentTraceKind.ERROR,
                state = failedState,
                phase = AgentPhase.PLAN,
                title = "规划失败",
                message = message,
                fields = fields,
                level = AgentTraceLevel.ERROR,
            )
            GraphCommand(
                failedState.copy(
                    requestedAction = null,
                    deviceAction = null,
                    executionResult = ActionResult.Failure(message),
                    verification = VerificationResult(false, message),
                ),
                AgentNode.ROUTE,
            )
        }

    private fun planningErrorDiagnostic(error: Exception): Pair<String, Map<String, String>> {
        val raw = error.message.orEmpty()
        val unterminated = Regex("""Unterminated string at character (\d+)""").find(raw)
        if (unterminated != null) {
            return (
                "模型返回的 function arguments 被截断，JSON 字符串不完整"
            ) to
                linkedMapOf(
                    "error_class" to error.javaClass.simpleName,
                    "error_position" to unterminated.groupValues[1],
                    "raw_error_length" to raw.length.toString(),
                    "diagnosis" to "长文本动作输出超过本轮生成上限或 Provider 截断",
                )
        }
        return "模型规划失败：${raw.ifBlank { error.javaClass.simpleName }.take(320)}" to
            linkedMapOf(
                "error_class" to error.javaClass.simpleName,
                "raw_error_length" to raw.length.toString(),
            )
    }

    private fun validateNode(state: AgentGraphState): GraphCommand {
        val action =
            state.requestedAction
                ?: return GraphCommand(
                    state.copy(verification = VerificationResult(false, "校验阶段缺少动作")),
                    AgentNode.ROUTE,
                )
        val observation =
            state.observation
                ?: return GraphCommand(
                    state.copy(verification = VerificationResult(false, "校验阶段缺少屏幕快照")),
                    AgentNode.ROUTE,
                )
        val pageActionKey =
            ReflectionPolicy.pageActionKey(state.actionPageSignature, action.reflectionKey())
        val tapCoordinateKey = (action as? PhoneAction.Tap)?.coordinateKey()
        if (tapCoordinateKey != null && tapCoordinateKey in state.blockedTapCoordinates) {
            val blocked =
                ReflectionAssessment(
                    trigger = "blocked_tap_coordinate",
                    evidence = "模型再次选择了反思层已禁止的 Tap 坐标：${action.describe()}",
                    correction = "必须切换坐标、元素路径、动作类型或结束任务",
                    blockedActionKeys = setOf(pageActionKey),
                    blockedTapCoordinates = setOf(tapCoordinateKey),
                )
            return GraphCommand(
                state.copy(
                    executionResult = ActionResult.Failure(blocked.evidence),
                    verification = VerificationResult(false, blocked.evidence),
                    pendingReflection = blocked,
                ),
                AgentNode.REFLECT,
            )
        }
        val protectedActionReflection =
            when {
                pageActionKey in state.blockedActionKeys ->
                    ReflectionAssessment(
                        trigger = "blocked_repeat",
                        evidence = "模型再次选择了反思层已禁止的页面-动作组合：${action.describe()}",
                        correction = "必须选择不同动作类型、不同元素策略或在目标已完成时结束任务",
                        blockedActionKeys = setOf(pageActionKey),
                    )
                action is PhoneAction.Type && action.text.hashCode() in state.committedTextHashes ->
                    ReflectionAssessment(
                        trigger = "duplicate_side_effect",
                        evidence = "该文本已经完成输入并通过“发送”动作验证，拒绝再次输入",
                        correction = "检查消息气泡；若单消息目标已完成则 complete_task，否则生成不同的新内容",
                        blockedActionKeys = setOf(pageActionKey),
                    )
                action is PhoneAction.Tap &&
                    action.isSendAction() &&
                    state.pendingTypedTextHash == null &&
                    state.committedTextHashes.isNotEmpty() ->
                    ReflectionAssessment(
                        trigger = "duplicate_send",
                        evidence = "已有消息发送记录，但当前没有新的已验证输入，拒绝再次点击发送",
                        correction = "检查已发送消息并结束单消息任务；只有完成新的不同文本输入后才能再次发送",
                        blockedActionKeys = setOf(pageActionKey),
                    )
                else -> null
            }
        if (protectedActionReflection != null) {
            return GraphCommand(
                state.copy(
                    executionResult = ActionResult.Failure(protectedActionReflection.evidence),
                    verification = VerificationResult(false, protectedActionReflection.evidence),
                    pendingReflection = protectedActionReflection,
                ),
                AgentNode.REFLECT,
            )
        }
        val validated = ActionValidator.validate(action, observation)
        return validated.fold(
            onSuccess = { deviceAction ->
                trace(
                    kind = AgentTraceKind.VALIDATION,
                    state = state,
                    phase = AgentPhase.VALIDATE,
                    title = "动作参数校验通过",
                    fields =
                        linkedMapOf<String, String>().apply {
                            putAll(action.traceFields())
                            putAll(deviceAction.traceFields())
                        },
                )
                GraphCommand(state.copy(deviceAction = deviceAction), AgentNode.EXECUTE)
            },
            onFailure = {
                trace(
                    kind = AgentTraceKind.ERROR,
                    state = state,
                    phase = AgentPhase.VALIDATE,
                    title = "动作参数校验失败",
                    message = it.message ?: "动作参数非法",
                    fields = action.traceFields(),
                    level = AgentTraceLevel.ERROR,
                )
                GraphCommand(
                    state.copy(
                        executionResult = ActionResult.Failure(it.message ?: "动作参数非法", it),
                        verification =
                            VerificationResult(
                                success = false,
                                message = "动作前置校验失败：${it.message}",
                            ),
                    ),
                    AgentNode.ROUTE,
                )
            },
        )
    }

    private suspend fun executeNode(state: AgentGraphState): GraphCommand {
        val action =
            state.deviceAction
                ?: return GraphCommand(
                    state.copy(executionResult = ActionResult.Failure("执行阶段缺少强类型动作")),
                    AgentNode.VERIFY,
                )
        val result =
            try {
                platform.performAction(action)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ActionResult.Failure(error.message ?: "动作执行异常", error)
            }
        trace(
            kind = AgentTraceKind.EXECUTION,
            state = state,
            phase = AgentPhase.EXECUTE,
            title =
                if (result is ActionResult.Success) {
                    "动作执行成功"
                } else {
                    "动作执行失败"
                },
            message = result.message(),
            fields =
                linkedMapOf<String, String>().apply {
                    putAll(action.traceFields())
                    if (result is ActionResult.Success) putAll(result.metadata)
                },
            level =
                if (result is ActionResult.Success) {
                    AgentTraceLevel.INFO
                } else {
                    AgentTraceLevel.ERROR
                },
        )
        emit(state, AgentPhase.EXECUTE, result.message())
        return GraphCommand(state.copy(executionResult = result), AgentNode.VERIFY)
    }

    private suspend fun verifyNode(state: AgentGraphState): GraphCommand {
        val requested =
            state.requestedAction
                ?: return GraphCommand(
                    state.copy(verification = VerificationResult(false, "验证阶段缺少原始动作")),
                    AgentNode.ROUTE,
                )
        val executed =
            state.deviceAction
                ?: return GraphCommand(
                    state.copy(verification = VerificationResult(false, "验证阶段缺少已校验动作")),
                    AgentNode.ROUTE,
                )
        val result = state.executionResult ?: ActionResult.Failure("执行器未返回结果")
        val before =
            state.observation
                ?: return GraphCommand(
                    state.copy(verification = VerificationResult(false, "验证阶段缺少动作前快照")),
                    AgentNode.ROUTE,
                )

        if (requested is PhoneAction.TakeOver) {
            val verification = verifier.verify(requested, executed, result, before, before)
            return GraphCommand(state.copy(verification = verification), AgentNode.ROUTE)
        }

        val stabilized =
            try {
                awaitStablePage(requested, before)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                before.recycle()
                return GraphCommand(
                    state.copy(
                        observation = null,
                        verification =
                            VerificationResult(
                                false,
                                "动作后重新感知失败：${error.message ?: error.javaClass.simpleName}",
                            ),
                    ),
                    AgentNode.ROUTE,
                )
            }
        val after = stabilized.snapshot
        val verification = verifier.verify(requested, executed, result, before, after)
        before.recycle()
        trace(
            kind = AgentTraceKind.VERIFICATION,
            state = state.copy(observation = after),
            phase = AgentPhase.VERIFY,
            title = if (verification.success) "动作验证通过" else "动作验证失败",
            message = verification.message,
            fields =
                linkedMapOf(
                    "success" to verification.success.toString(),
                    "visual_change" to "%.3f".format(verification.visualChangeScore),
                    "package_changed" to verification.packageChanged.toString(),
                    "tree_changed" to verification.treeChanged.toString(),
                    "package" to after.accessibility.packageName.orEmpty(),
                    "stability_wait_ms" to stabilized.waitedMs.toString(),
                    "stability_samples" to stabilized.samples.toString(),
                    "page_stable" to stabilized.stable.toString(),
                    "last_frame_delta" to "%.4f".format(stabilized.lastFrameDelta),
                ),
            level =
                if (verification.success) {
                    AgentTraceLevel.INFO
                } else {
                    AgentTraceLevel.WARNING
                },
        )
        emit(
            state.copy(observation = after),
            AgentPhase.VERIFY,
            verification.message,
            verification = verification,
        )
        return GraphCommand(
            state.copy(
                observation = after,
                verification = verification,
            ),
            AgentNode.ROUTE,
        )
    }

    private fun reflectNode(state: AgentGraphState): GraphCommand {
        val assessment =
            state.pendingReflection
                ?: return GraphCommand(
                    state.copy(
                        finalMessage = "反思节点缺少触发证据，任务已安全停止",
                        finalPhase = AgentPhase.FAILED,
                    ),
                    AgentNode.END,
                )
        val reflections = state.subtaskReflectionCount + 1
        val blocked = state.blockedActionKeys + assessment.blockedActionKeys
        val blockedTapCoordinates = state.blockedTapCoordinates + assessment.blockedTapCoordinates
        trace(
            kind = AgentTraceKind.REFLECTION,
            state = state,
            phase = AgentPhase.REFLECT,
            title = "检测到失败假设，准备重新感知",
            message = assessment.trigger,
            fields =
                linkedMapOf(
                    "reflection_count" to reflections.toString(),
                    "subtask" to state.activeSubtask,
                    "subtask_key" to state.activeSubtaskKey,
                    "subtask_reflections" to reflections.toString(),
                    "subtask_recovery_limit" to
                        SubtaskScopePolicy.MAX_RECOVERY_ATTEMPTS.toString(),
                    "evidence" to assessment.evidence,
                    "correction" to assessment.correction,
                    "blocked_actions" to
                        assessment.blockedActionKeys
                            .joinToString(" ; ") { it.substringAfter('|') },
                ),
            level = AgentTraceLevel.WARNING,
        )
        return GraphCommand(
            state.copy(
                requestedAction = null,
                deviceAction = null,
                executionResult = null,
                verification = null,
                feedback = null,
                pendingReflection = assessment,
                subtaskReflectionCount = reflections,
                candidateAttemptsInEpisode = 0,
                blockedActionKeys = blocked,
                blockedTapCoordinates = blockedTapCoordinates,
            ),
            AgentNode.PERCEIVE,
        )
    }

    private suspend fun replanNode(state: AgentGraphState): GraphCommand {
        if (state.step >= maxSteps) {
            return GraphCommand(
                state.copy(
                    finalMessage = "达到最大动作步数 $maxSteps，反思恢复已停止",
                    finalPhase = AgentPhase.FAILED,
                ),
                AgentNode.END,
            )
        }
        val assessment =
            state.pendingReflection
                ?: return GraphCommand(
                    state.copy(
                        finalMessage = "反思重规划缺少失败证据，任务已安全停止",
                        finalPhase = AgentPhase.FAILED,
                    ),
                    AgentNode.END,
                )
        val observation =
            state.observation
                ?: return GraphCommand(
                    state.copy(
                        finalMessage = "反思重规划前未取得新感知快照",
                        finalPhase = AgentPhase.FAILED,
                    ),
                    AgentNode.END,
                )
        val blockedActions = state.blockedActionKeys + state.blockedTapCoordinates

        return try {
            val terminalAdjudication =
                SubtaskScopePolicy.shouldTerminalAdjudicate(state.subtaskReflectionCount)
            val decision =
                if (terminalAdjudication) {
                    trace(
                        kind = AgentTraceKind.REFLECTION,
                        state = state,
                        phase = AgentPhase.REFLECT,
                        title = "反思层进行终局裁决",
                        message = "普通恢复策略持续失败，只允许人工接管或明确结束",
                        fields =
                            linkedMapOf(
                                "subtask" to state.activeSubtask,
                                "subtask_key" to state.activeSubtaskKey,
                                "subtask_reflections" to state.subtaskReflectionCount.toString(),
                                "subtask_recovery_limit" to
                                    SubtaskScopePolicy.MAX_RECOVERY_ATTEMPTS.toString(),
                            ),
                        level = AgentTraceLevel.WARNING,
                    )
                    model.terminalDecision(
                        task = state.task,
                        observation = observation,
                        history = state.history,
                        trigger = assessment.trigger,
                        evidence = assessment.evidence,
                        blockedActions = blockedActions,
                    )
                } else {
                    model.reflectDecision(
                        task = state.task,
                        observation = observation,
                        history = state.history,
                        trigger = assessment.trigger,
                        evidence = assessment.evidence,
                        correction = assessment.correction,
                        blockedActions = blockedActions,
                        candidateHints = assessment.candidateHints,
                    )
                }
            when (decision) {
                is AgentDecision.Complete ->
                    GraphCommand(
                        state.copy(
                            pendingReflection = null,
                            finalMessage = decision.message,
                            finalPhase =
                                if (decision.success) AgentPhase.COMPLETE else AgentPhase.FAILED,
                        ),
                        AgentNode.END,
                    )
                is AgentDecision.Act -> {
                    val proposedState = state.copy(step = state.step + 1)
                    val proposedKey =
                        ReflectionPolicy.pageActionKey(
                            observation.pageSignature(),
                            decision.action.reflectionKey(),
                        )
                    val proposedTapCoordinateKey =
                        (decision.action as? PhoneAction.Tap)?.coordinateKey()
                    val proposedFingerprint = decision.action.proposalFingerprint()
                    trace(
                        kind = AgentTraceKind.REFLECTION,
                        state = proposedState,
                        phase = AgentPhase.REFLECT,
                        title = "反思模型提出纠错动作",
                        message = assessment.trigger,
                        fields =
                            linkedMapOf<String, String>().apply {
                                put("failure_cause", decision.failureCause.ifBlank { decision.rationale })
                                put("strategy_change", decision.strategyChange)
                                put("subtask", state.activeSubtask)
                                put("model_subtask", decision.subtask)
                                put(
                                    "subtask_reflections",
                                    state.subtaskReflectionCount.toString(),
                                )
                                put("correction_action", decision.action.describe())
                                put("expected_outcome", decision.expectedOutcome)
                                putAll(decision.action.traceFields())
                            },
                    )
                    if (
                        proposedKey in state.blockedActionKeys ||
                        (
                            proposedTapCoordinateKey != null &&
                                proposedTapCoordinateKey in state.blockedTapCoordinates
                        ) ||
                        proposedFingerprint == state.lastCandidateFingerprint
                    ) {
                        val rejected =
                            ReflectionAssessment(
                                trigger = "reflection_proposal_rejected",
                                evidence =
                                    "反思模型仍提出已禁止的精确页面-动作：${decision.action.describe()}",
                                correction =
                                    "重新检查最新截图并选择不同动作类型、不同节点或明显不同的位置区域；" +
                                        "输入框可见时直接使用定向 Type",
                                blockedActionKeys = setOf(proposedKey),
                                blockedTapCoordinates =
                                    proposedTapCoordinateKey?.let { setOf(it) }.orEmpty(),
                                candidateHints = assessment.candidateHints,
                            )
                        GraphCommand(
                            proposedState.copy(
                                requestedAction = null,
                                deviceAction = null,
                                executionResult = ActionResult.Failure(rejected.evidence),
                                verification = VerificationResult(false, rejected.evidence),
                                pendingReflection = rejected,
                                feedback = null,
                            ),
                            AgentNode.REFLECT,
                        )
                    } else {
                        GraphCommand(
                            proposedState.copy(
                                requestedAction = decision.action,
                                deviceAction = null,
                                executionResult = null,
                                verification = null,
                                feedback = null,
                                decisionRationale =
                                    decision.failureCause
                                        .takeIf { it.isNotBlank() }
                                        ?.let { "$it；${decision.rationale}" }
                                        ?: decision.rationale,
                                expectedOutcome = decision.expectedOutcome,
                                actionPageSignature = observation.pageSignature(),
                                pendingReflection = null,
                                consecutiveActionFailures = 0,
                                candidateAttemptsInEpisode = state.candidateAttemptsInEpisode + 1,
                            ),
                            AgentNode.VALIDATE,
                        )
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val diagnostic = planningErrorDiagnostic(error)
            trace(
                kind = AgentTraceKind.ERROR,
                state = state,
                phase = AgentPhase.REFLECT,
                title = "反思重规划失败",
                message = diagnostic.first,
                fields = diagnostic.second,
                level = AgentTraceLevel.ERROR,
            )
            GraphCommand(
                state.copy(
                    requestedAction = null,
                    executionResult = ActionResult.Failure(diagnostic.first, error),
                    verification = VerificationResult(false, diagnostic.first),
                    pendingReflection = null,
                ),
                AgentNode.ROUTE,
            )
        }
    }

    private fun routeNode(state: AgentGraphState): GraphCommand {
        val action = state.requestedAction
        val verification =
            state.verification
                ?: VerificationResult(false, state.executionResult?.message() ?: "未知失败")
        val history =
            state.history +
                ActionHistoryEntry(
                    step = state.step,
                    action = action?.describe() ?: "planning",
                    result = verification.message,
                    verified = verification.actionSucceeded,
                    packageName = state.observation?.accessibility?.packageName,
                    actionKey = action?.reflectionKey() ?: "planning",
                    beforeStateSignature = state.actionPageSignature,
                    afterStateSignature = state.observation?.pageSignature().orEmpty(),
                    rationale = state.decisionRationale,
                    expectedOutcome = state.expectedOutcome,
                    subtask = state.activeSubtask,
                    actionSucceeded = verification.actionSucceeded,
                    subtaskProgressed = verification.subtaskProgressed,
                )

        if (action == null) {
            val planningFailures = state.consecutivePlanningFailures + 1
            trace(
                kind = AgentTraceKind.ROUTING,
                state = state,
                phase = AgentPhase.ROUTE,
                title = "模型协议失败后重试",
                message = verification.message,
                fields =
                    linkedMapOf(
                        "verified" to "false",
                        "planning_failures" to planningFailures.toString(),
                        "action_failures" to state.consecutiveActionFailures.toString(),
                    ),
                level = AgentTraceLevel.WARNING,
            )
            if (planningFailures >= MAX_PLANNING_FAILURES) {
                return GraphCommand(
                    state.copy(
                        history = history,
                        consecutivePlanningFailures = planningFailures,
                        finalMessage =
                            "模型连续 $planningFailures 次未返回有效 function call，" +
                                "当前模型或接口的结构化动作协议不稳定：${verification.message}",
                        finalPhase = AgentPhase.FAILED,
                    ),
                    AgentNode.END,
                )
            }
            return GraphCommand(
                state.copy(
                    history = history,
                    feedback =
                        "${verification.message}。必须只返回一个已注册 function call；" +
                            "如果接口不支持原生工具调用，返回单个 JSON 动作对象。",
                    consecutivePlanningFailures = planningFailures,
                    lastActionSignature = null,
                    repeatedActionCount = 0,
                ),
                AgentNode.PLAN,
            )
        }

        val isNoProgressWait =
            action is PhoneAction.Wait &&
                verification.actionSucceeded &&
                !verification.subtaskProgressed
        val noProgressWaits = if (isNoProgressWait) state.consecutiveNoProgressWaits + 1 else 0
        val noVisualProgress =
            if (
                action is PhoneAction.TakeOver ||
                action is PhoneAction.SystemTool ||
                verification.subtaskProgressed
            ) {
                0
            } else {
                state.consecutiveNoVisualProgress + 1
            }
        val signature = action.signature()
        val repeated =
            if (signature == state.lastActionSignature) {
                state.repeatedActionCount + 1
            } else {
                1
            }
        val bigVisualChange =
            verification.visualChangeScore >= BIG_VISUAL_CHANGE_THRESHOLD
        val releasedActionKeys =
            if (bigVisualChange) emptySet() else state.blockedActionKeys
        val releasedTapCoordinates =
            if (bigVisualChange) emptySet() else state.blockedTapCoordinates
        val tapCoordinateKey = (action as? PhoneAction.Tap)?.coordinateKey()
        val repeatedWaitCount =
            if (bigVisualChange) {
                0
            } else if (action is PhoneAction.Wait) {
                state.repeatedWaitCount + 1
            } else {
                0
            }
        val repeatedTypeFailureCount =
            if (bigVisualChange) {
                0
            } else if (action is PhoneAction.Type && !verification.actionSucceeded) {
                state.repeatedTypeFailureCount + 1
            } else {
                0
            }
        val repeatedTapCoordinateCount =
            if (bigVisualChange) {
                0
            } else if (action is PhoneAction.Tap && tapCoordinateKey != null) {
                if (tapCoordinateKey == state.lastTapCoordinateKey) {
                    state.repeatedTapCoordinateCount + 1
                } else {
                    1
                }
            } else {
                0
            }
        val loopState =
            state.copy(
                repeatedWaitCount = repeatedWaitCount,
                repeatedTypeFailureCount = repeatedTypeFailureCount,
                repeatedTapCoordinateCount = repeatedTapCoordinateCount,
                lastTapCoordinateKey = if (action is PhoneAction.Tap) tapCoordinateKey else null,
                candidateAttemptsInEpisode =
                    if (bigVisualChange) 0 else state.candidateAttemptsInEpisode,
                lastCandidateFingerprint =
                    when {
                        bigVisualChange -> null
                        state.deviceAction != null -> action.proposalFingerprint()
                        else -> state.lastCandidateFingerprint
                    },
                blockedActionKeys = releasedActionKeys,
                blockedTapCoordinates = releasedTapCoordinates,
            )
        val tapPoint = (action as? PhoneAction.Tap)?.normalizedCenter()
        val tapTargetSignature =
            (action as? PhoneAction.Tap)?.let { tap ->
                val observation = state.observation
                val description = tap.targetDescription.trim().lowercase()
                listOf(
                    observation?.accessibility?.packageName.orEmpty(),
                    observation?.accessibility?.treeHash?.toString().orEmpty(),
                    observation?.visualFingerprint?.contentHashCode()?.toString().orEmpty(),
                    description,
                ).joinToString("|")
            }
        val tapWasExecuted =
            action is PhoneAction.Tap &&
                state.deviceAction is DeviceAction.Tap &&
                state.executionResult is ActionResult.Success
        val tapTargetAttempts =
            if (tapWasExecuted && !verification.actionSucceeded) {
                if (tapTargetSignature == state.tapTargetSignature) {
                    state.tapTargetAttempts + 1
                } else {
                    1
                }
            } else {
                0
            }
        val nearbyTap =
            tapPoint != null &&
                state.lastFailedTapX != null &&
                state.lastFailedTapY != null &&
                squaredDistance(
                    tapPoint.first,
                    tapPoint.second,
                    state.lastFailedTapX,
                    state.lastFailedTapY,
                ) <= NEARBY_TAP_RADIUS * NEARBY_TAP_RADIUS
        val nearbyTapAttempts =
            if (tapWasExecuted && !verification.actionSucceeded) {
                if (nearbyTap) state.nearbyTapAttempts + 1 else 1
            } else {
                0
            }
        val typedTextHash =
            if (verification.actionSucceeded && action is PhoneAction.Type) {
                action.text.hashCode()
            } else {
                state.pendingTypedTextHash
            }
        val committedTextHash =
            if (verification.actionSucceeded && action is PhoneAction.Tap && action.isSendAction()) {
                typedTextHash
            } else {
                null
            }
        val pendingTypedTextHash =
            if (committedTextHash != null) {
                null
            } else {
                typedTextHash
            }
        val committedTextHashes =
            committedTextHash?.let { state.committedTextHashes + it } ?: state.committedTextHashes
        trace(
            kind = AgentTraceKind.ROUTING,
            state = state,
            phase = AgentPhase.ROUTE,
            title = if (verification.actionSucceeded) "继续下一步" else "失败后重新规划",
            message = verification.message,
            fields =
                linkedMapOf(
                    "verified" to verification.actionSucceeded.toString(),
                    "action_failures" to
                        (if (verification.actionSucceeded) 0 else state.consecutiveActionFailures + 1).toString(),
                    "planning_failures" to "0",
                    "repeated_action" to repeated.toString(),
                    "repeated_wait" to repeatedWaitCount.toString(),
                    "repeated_tap_coordinate" to repeatedTapCoordinateCount.toString(),
                    "repeated_type_failure" to repeatedTypeFailureCount.toString(),
                    "no_visual_progress" to noVisualProgress.toString(),
                ).apply {
                    if (action is PhoneAction.Tap) {
                        put("tap_target_attempts", tapTargetAttempts.toString())
                        put("nearby_tap_attempts", nearbyTapAttempts.toString())
                    }
                },
            level =
                if (verification.actionSucceeded) {
                    AgentTraceLevel.INFO
                } else {
                    AgentTraceLevel.WARNING
                },
        )
        if (
            action is PhoneAction.Tap &&
            state.candidateAttemptsInEpisode >= MAX_CANDIDATE_ATTEMPTS_PER_REFLECTION &&
            !bigVisualChange &&
            !verification.subtaskProgressed
        ) {
            return GraphCommand(
                loopState.copy(
                    history = history,
                    pendingReflection =
                        ReflectionAssessment(
                            trigger = "candidate_paths_exhausted",
                            evidence =
                                "当前反思 episode 已尝试 ${state.candidateAttemptsInEpisode} 个不同候选路径，" +
                                    "仍未产生大变化或子任务进展",
                            correction =
                                "重新感知并生成下一组路径候选；若仍没有明显差异，则请求人工接管",
                            blockedActionKeys = emptySet(),
                            candidateHints =
                                tapCandidateHints(
                                    observation = state.observation,
                                    failedPoint = tapPoint,
                                    targetDescription =
                                        (action as? PhoneAction.Tap)?.targetDescription.orEmpty(),
                                ),
                        ),
                ),
                AgentNode.REFLECT,
            )
        }
        if (
            verification.actionSucceeded &&
            noVisualProgress >= NO_PROGRESS_REFLECTION_SIGNAL_ACTIONS
        ) {
            return GraphCommand(
                loopState.copy(
                    history = history,
                    consecutiveNoVisualProgress = noVisualProgress,
                    pendingReflection =
                        ReflectionAssessment(
                            trigger = "mixed_no_progress_loop",
                            evidence =
                                "子任务“${state.activeSubtask}”连续 $noVisualProgress 个动作" +
                                    "未产生视觉、语义树或前台应用推进",
                            correction =
                                "进入当前子任务的恢复规划并尝试实质不同路径；" +
                                    "该信号只触发反思，不直接熔断",
                            blockedActionKeys = emptySet(),
                        ),
                ),
                AgentNode.REFLECT,
            )
        }
        if (verification.actionSucceeded && noProgressWaits > 0) {
            if (noProgressWaits >= MAX_NO_PROGRESS_WAITS) {
                return GraphCommand(
                    loopState.copy(
                        history = history,
                        consecutiveNoProgressWaits = noProgressWaits,
                        consecutiveNoVisualProgress = noVisualProgress,
                        pendingReflection =
                            ReflectionAssessment(
                                trigger = "wait_stuck",
                                evidence = "连续 $noProgressWaits 次 Wait 后页面无任何变化",
                                correction = "判断页面是否可由人工继续；否则换动作或明确结束",
                                blockedActionKeys = emptySet(),
                            ),
                    ),
                    AgentNode.REFLECT,
                )
            }
            return GraphCommand(
                loopState.copy(
                    history = history,
                    feedback =
                        "等待没有带来页面、语义树或包名变化；请改为 tap、swipe、back 或 complete_task。",
                    consecutiveActionFailures = 0,
                    consecutivePlanningFailures = 0,
                    consecutiveNoProgressWaits = noProgressWaits,
                    consecutiveNoVisualProgress = noVisualProgress,
                    lastActionSignature = signature,
                    repeatedActionCount = repeated,
                ),
                AgentNode.PLAN,
            )
        }

        if (action is PhoneAction.TakeOver && verification.actionSucceeded) {
            return GraphCommand(
                state.copy(
                    history = history,
                    finalMessage = action.message,
                    finalPhase = AgentPhase.TAKEOVER,
                ),
                AgentNode.END,
            )
        }

        if (action is PhoneAction.SystemTool && verification.actionSucceeded) {
            val requiresTakeOver =
                platform.mode == com.bluewhale.agent.model.TargetMode.VIRTUAL_DISPLAY &&
                    action.capability.requiresUserInteraction
            return GraphCommand(
                state.copy(
                    history = history,
                    finalMessage =
                        if (requiresTakeOver) {
                            "${state.executionResult?.message().orEmpty()}，等待你确认接管到主屏继续操作。"
                        } else {
                            state.executionResult?.message().orEmpty().ifBlank {
                                "系统工具已执行：${action.capability.displayName}"
                            }
                        },
                    finalPhase =
                        if (requiresTakeOver) AgentPhase.TAKEOVER else AgentPhase.COMPLETE,
                ),
                AgentNode.END,
            )
        }

        if (
            verification.actionSucceeded &&
            action is PhoneAction.Launch &&
            platform.mode == com.bluewhale.agent.model.TargetMode.VIRTUAL_DISPLAY &&
            isLaunchOnlyTask(state.task)
        ) {
            return GraphCommand(
                state.copy(
                    history = history,
                    finalMessage = "${action.app} 已在虚拟屏打开，等待你确认是否接管到主屏。",
                    finalPhase = AgentPhase.TAKEOVER,
                ),
                AgentNode.END,
            )
        }

        val reflection =
            ReflectionPolicy.assess(
                history = history,
                tapTargetAttempts = tapTargetAttempts,
                nearbyTapAttempts = nearbyTapAttempts,
                repeatedWaitCount = repeatedWaitCount,
                repeatedTapCoordinateCount = repeatedTapCoordinateCount,
                repeatedTypeFailureCount = repeatedTypeFailureCount,
                currentTapCoordinateKey = tapCoordinateKey,
            )
        val reflectionWithCandidates =
            if (reflection?.trigger == "repeated_tap_coordinate") {
                reflection.copy(
                    candidateHints =
                        tapCandidateHints(
                            observation = state.observation,
                            failedPoint = tapPoint,
                            targetDescription =
                                (action as? PhoneAction.Tap)?.targetDescription.orEmpty(),
                        ),
                )
            } else {
                reflection
            }
        if (reflectionWithCandidates != null) {
            return GraphCommand(
                loopState.copy(
                    history = history,
                    pendingReflection = reflectionWithCandidates,
                    blockedTapCoordinates =
                        releasedTapCoordinates + reflectionWithCandidates.blockedTapCoordinates,
                    pendingTypedTextHash = pendingTypedTextHash,
                    committedTextHashes = committedTextHashes,
                    consecutiveNoVisualProgress = noVisualProgress,
                    tapTargetSignature =
                        if (action is PhoneAction.Tap) tapTargetSignature else state.tapTargetSignature,
                    tapTargetAttempts =
                        if (action is PhoneAction.Tap) tapTargetAttempts else state.tapTargetAttempts,
                    lastFailedTapX =
                        if (action is PhoneAction.Tap) tapPoint?.first else state.lastFailedTapX,
                    lastFailedTapY =
                        if (action is PhoneAction.Tap) tapPoint?.second else state.lastFailedTapY,
                    nearbyTapAttempts =
                        if (action is PhoneAction.Tap) nearbyTapAttempts else state.nearbyTapAttempts,
                ),
                AgentNode.REFLECT,
            )
        }

        if (!verification.actionSucceeded) {
            if (action is PhoneAction.Tap && tapTargetAttempts >= MAX_TAP_TARGET_ATTEMPTS) {
                return GraphCommand(
                    loopState.copy(
                        history = history,
                    pendingReflection =
                        ReflectionAssessment(
                            trigger = "tap_attempts_exhausted",
                            evidence = "同一页面目标已尝试 $tapTargetAttempts 次仍未生效：${verification.message}",
                            correction = "停止误触并进行终局判断：优先请求人工接管，或明确结束",
                            blockedActionKeys = emptySet(),
                        ),
                ),
                    AgentNode.REFLECT,
                )
            }
            val failures = state.consecutiveActionFailures + 1
            if (failures >= maxConsecutiveFailures) {
                return GraphCommand(
                    loopState.copy(
                        history = history,
                        consecutiveActionFailures = failures,
                        pendingReflection =
                            ReflectionAssessment(
                                trigger = "action_failure_budget_exhausted",
                                evidence = "连续 $failures 次设备动作未通过验证：${verification.message}",
                                correction = "由反思层判断人工接管是否能推进，否则明确结束任务",
                                blockedActionKeys = emptySet(),
                            ),
                    ),
                    AgentNode.REFLECT,
                )
            }
            return GraphCommand(
                loopState.copy(
                    history = history,
                    feedback =
                        if (action is PhoneAction.Tap) {
                            if (tapWasExecuted) {
                                "${verification.message}。这是同一目标第 $tapTargetAttempts 次实际点击后未生效；" +
                                    "重新检查无障碍 element_index 或视觉 target_box。" +
                                    if (nearbyTapAttempts >= MAX_NEARBY_TAP_ATTEMPTS) {
                                        " 已连续点击邻近区域，请改用明显不同的定位或先等待页面稳定。"
                                    } else {
                                        " 不要机械重复相同坐标。"
                                    }
                            } else {
                                "${verification.message}。本轮 Tap 未实际执行，不计入定位重试；" +
                                    "请修正 element_index、target_box 或坐标后重新规划。"
                            }
                        } else {
                            "${verification.message}。不要重复同一动作，请基于当前页面重新判断。"
                        },
                    consecutiveActionFailures = failures,
                    consecutivePlanningFailures = 0,
                    consecutiveNoProgressWaits = 0,
                    consecutiveNoVisualProgress = noVisualProgress,
                    lastActionSignature = signature,
                    repeatedActionCount = repeated,
                    tapTargetSignature =
                        if (action is PhoneAction.Tap) tapTargetSignature else state.tapTargetSignature,
                    tapTargetAttempts =
                        if (action is PhoneAction.Tap) tapTargetAttempts else state.tapTargetAttempts,
                    lastFailedTapX =
                        if (action is PhoneAction.Tap) tapPoint?.first else state.lastFailedTapX,
                    lastFailedTapY =
                        if (action is PhoneAction.Tap) tapPoint?.second else state.lastFailedTapY,
                    nearbyTapAttempts =
                        if (action is PhoneAction.Tap) nearbyTapAttempts else state.nearbyTapAttempts,
                ),
                if (state.observation == null) AgentNode.PERCEIVE else AgentNode.PLAN,
            )
        }

        return GraphCommand(
            loopState.copy(
                history = history,
                feedback =
                    if (committedTextHash != null) {
                        "发送动作已通过验证，文本摘要 hash=$committedTextHash；" +
                            "禁止再次 Type 或发送同一内容。请检查消息气泡，单消息任务应 complete_task。"
                    } else if (noVisualProgress >= NO_VISUAL_PROGRESS_WARNING_ACTIONS) {
                        "页面已连续 $noVisualProgress 个动作没有显著视觉或包名变化；" +
                            "不要继续在同一输入框 Tap/Type/Wait，优先 Launch、Back、换目标或结束任务。"
                    } else {
                        null
                    },
                consecutiveActionFailures = 0,
                consecutivePlanningFailures = 0,
                consecutiveNoProgressWaits = noProgressWaits,
                consecutiveNoVisualProgress = noVisualProgress,
                lastActionSignature = signature,
                repeatedActionCount = repeated,
                pendingTypedTextHash = pendingTypedTextHash,
                committedTextHashes = committedTextHashes,
                tapTargetSignature = null,
                tapTargetAttempts = 0,
                lastFailedTapX = null,
                lastFailedTapY = null,
                nearbyTapAttempts = 0,
            ),
            AgentNode.PLAN,
        )
    }

    private fun validateEdge(from: AgentNode, to: AgentNode) {
        require(to in allowedEdges.getValue(from)) {
            "非法 Agent 图跳转：$from -> $to"
        }
    }

    private fun emit(
        state: AgentGraphState,
        phase: AgentPhase,
        status: String,
        running: Boolean = true,
        awaitingTakeover: Boolean = false,
        verification: VerificationResult? = state.verification,
    ) {
        val verificationText =
            verification?.let {
                "${it.message}（视觉变化=${"%.3f".format(it.visualChangeScore)}，" +
                    "包名变化=${it.packageChanged}，语义树变化=${it.treeChanged}）"
            }.orEmpty()
        onState(
            AgentRunState(
                running = running,
                task = state.task,
                mode = platform.mode,
                step = state.step,
                phase = phase,
                status = status,
                verification = verificationText,
                awaitingTakeover = awaitingTakeover,
            ),
        )
    }

    private fun trace(
        kind: AgentTraceKind,
        state: AgentGraphState,
        phase: AgentPhase,
        title: String,
        message: String = "",
        fields: Map<String, String> = emptyMap(),
        level: AgentTraceLevel = AgentTraceLevel.INFO,
    ) {
        onTrace(
            AgentTraceEvent(
                kind = kind,
                step = state.step,
                phase = phase,
                title = title,
                message = message,
                fields = fields,
                level = level,
            ),
        )
    }

    private fun phaseFor(node: AgentNode): AgentPhase =
        when (node) {
            AgentNode.PERCEIVE -> AgentPhase.PERCEIVE
            AgentNode.PLAN -> AgentPhase.PLAN
            AgentNode.VALIDATE -> AgentPhase.VALIDATE
            AgentNode.EXECUTE -> AgentPhase.EXECUTE
            AgentNode.VERIFY -> AgentPhase.VERIFY
            AgentNode.ROUTE -> AgentPhase.ROUTE
            AgentNode.REFLECT -> AgentPhase.REFLECT
            AgentNode.REPLAN -> AgentPhase.REFLECT
            AgentNode.END -> AgentPhase.COMPLETE
        }

    private suspend fun awaitStablePage(
        action: PhoneAction,
        beforeAction: PerceptionSnapshot,
    ): StabilizedPage {
        val policy = stabilityPolicy(action)
        val startedAt = System.nanoTime()
        delay(policy.initialDelayMs)

        var current: PerceptionSnapshot? = null
        var samples = 0
        var stableSamples = 0
        var lastFrameDelta = 1.0
        try {
            current = captureConsistentPostAction(beforeAction)
            samples = 1
            while (elapsedMs(startedAt) < policy.timeoutMs) {
                delay(STABILITY_POLL_MS)
                val next = captureConsistentPostAction(beforeAction)
                samples += 1
                val previous = requireNotNull(current)
                lastFrameDelta =
                    ActionVerifier.visualChangeScore(
                        previous.visualFingerprint,
                        next.visualFingerprint,
                    )
                val semanticStable =
                    previous.accessibility.packageName == next.accessibility.packageName &&
                        previous.accessibility.treeHash == next.accessibility.treeHash
                stableSamples =
                    if (lastFrameDelta <= STABILITY_VISUAL_THRESHOLD && semanticStable) {
                        stableSamples + 1
                    } else {
                        0
                    }
                previous.recycle()
                current = next
                if (stableSamples >= REQUIRED_STABLE_SAMPLES) {
                    return StabilizedPage(
                        snapshot = next,
                        waitedMs = elapsedMs(startedAt),
                        samples = samples,
                        stable = true,
                        lastFrameDelta = lastFrameDelta,
                    )
                }
            }
            return StabilizedPage(
                snapshot = requireNotNull(current),
                waitedMs = elapsedMs(startedAt),
                samples = samples,
                stable = false,
                lastFrameDelta = lastFrameDelta,
            )
        } catch (error: Exception) {
            current?.recycle()
            throw error
        }
    }

    private suspend fun captureConsistentPostAction(
        beforeAction: PerceptionSnapshot,
    ): PerceptionSnapshot {
        val snapshot = capturePerceptionWithRetry(maxAttempts = 2)
        val semanticChanged =
            beforeAction.accessibility.packageName != snapshot.accessibility.packageName ||
                beforeAction.accessibility.treeHash != snapshot.accessibility.treeHash
        if (!snapshot.screen.isFresh && semanticChanged) {
            snapshot.recycle()
            throw IllegalStateException(
                "无障碍语义已变化，但虚拟屏仍返回缓存旧帧；已拒绝把矛盾观察发送给模型",
            )
        }
        return snapshot
    }

    private fun stabilityPolicy(action: PhoneAction): StabilityPolicy =
        when (action) {
            is PhoneAction.Launch -> StabilityPolicy(initialDelayMs = 1_000L, timeoutMs = 6_000L)
            is PhoneAction.Tap -> StabilityPolicy(initialDelayMs = 450L, timeoutMs = 3_500L)
            is PhoneAction.Type -> StabilityPolicy(initialDelayMs = 300L, timeoutMs = 2_500L)
            is PhoneAction.Swipe -> StabilityPolicy(initialDelayMs = 500L, timeoutMs = 3_500L)
            PhoneAction.Back -> StabilityPolicy(initialDelayMs = 450L, timeoutMs = 3_500L)
            is PhoneAction.Wait -> StabilityPolicy(initialDelayMs = 100L, timeoutMs = 2_000L)
            is PhoneAction.SystemTool ->
                StabilityPolicy(initialDelayMs = 350L, timeoutMs = 2_500L)
            is PhoneAction.TakeOver -> StabilityPolicy(initialDelayMs = 0L, timeoutMs = 0L)
        }

    private fun elapsedMs(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / 1_000_000L

    private fun ActionResult.message(): String =
        when (this) {
            is ActionResult.Success -> message
            is ActionResult.Failure -> message
        }

    private fun PhoneAction.describe(): String =
        when (this) {
            is PhoneAction.Launch -> "Launch(app=$app)"
            is PhoneAction.Tap ->
                "Tap($x,$y,element=${elementIndex ?: "visual"}," +
                    "target=${targetDescription.ifBlank { "unspecified" }})"
            is PhoneAction.Type ->
                "Type(length=${text.length},hash=${text.hashCode()}," +
                    "element=${elementIndex ?: "visual/focused"}," +
                    "target=${targetDescription.ifBlank { "unspecified" }})"
            is PhoneAction.Swipe -> "Swipe($startX,$startY->$endX,$endY)"
            PhoneAction.Back -> "Back"
            is PhoneAction.Wait -> "Wait(${durationMs}ms)"
            is PhoneAction.SystemTool -> "SystemTool(${capability.stableKey()})"
            is PhoneAction.TakeOver -> "Take_over"
        }

    private fun PhoneAction.traceFields(): Map<String, String> =
        when (this) {
            is PhoneAction.Launch ->
                linkedMapOf(
                    "action" to "Launch",
                    "app" to app,
                )
            is PhoneAction.Tap ->
                linkedMapOf<String, String>(
                    "action" to "Tap",
                    "coordinates_normalized" to "($x, $y)",
                ).apply {
                    elementIndex?.let { put("element_index", it.toString()) }
                    targetBounds?.let {
                        put("target_box", "[${it.left},${it.top}][${it.right},${it.bottom}]")
                    }
                    targetDescription.takeIf { it.isNotBlank() }?.let {
                        put("target_description", it)
                    }
                }
            is PhoneAction.Type ->
                linkedMapOf<String, String>(
                    "action" to "Type",
                    "text" to text,
                    "text_length" to text.length.toString(),
                ).apply {
                    elementIndex?.let { put("element_index", it.toString()) }
                    targetBounds?.let {
                        put("target_box", "[${it.left},${it.top}][${it.right},${it.bottom}]")
                    }
                    targetDescription.takeIf { it.isNotBlank() }?.let {
                        put("target_description", it)
                    }
                }
            is PhoneAction.Swipe ->
                linkedMapOf(
                    "action" to "Swipe",
                    "swipe_normalized" to "($startX, $startY) -> ($endX, $endY)",
                    "duration_ms" to durationMs.toString(),
                )
            PhoneAction.Back -> linkedMapOf("action" to "Back")
            is PhoneAction.Wait ->
                linkedMapOf(
                    "action" to "Wait",
                    "duration_ms" to durationMs.toString(),
                )
            is PhoneAction.SystemTool -> capability.traceFields()
            is PhoneAction.TakeOver ->
                linkedMapOf(
                    "action" to "Take_over",
                    "message" to message,
                )
        }

    private fun DeviceAction.traceFields(): Map<String, String> =
        when (this) {
            is DeviceAction.Launch -> linkedMapOf("validated_app" to app)
            is DeviceAction.Tap ->
                linkedMapOf<String, String>(
                    "coordinates_pixels" to "($x, $y)",
                    "tap_strategy" to strategy,
                ).apply {
                    targetElementIndex?.let { put("target_element_index", it.toString()) }
                    targetLabel?.takeIf { it.isNotBlank() }?.let { put("target_label", it) }
                }
            is DeviceAction.Type ->
                linkedMapOf<String, String>(
                    "validated_text_length" to text.length.toString(),
                    "input_strategy" to strategy,
                ).apply {
                    if (targetX != null && targetY != null) {
                        put("input_target_pixels", "($targetX, $targetY)")
                    }
                    targetElementIndex?.let { put("target_element_index", it.toString()) }
                    targetLabel?.takeIf { it.isNotBlank() }?.let { put("target_label", it) }
                }
            is DeviceAction.Swipe ->
                linkedMapOf(
                    "swipe_pixels" to "($startX, $startY) -> ($endX, $endY)",
                    "validated_duration_ms" to durationMs.toString(),
                )
            DeviceAction.Back -> emptyMap()
            is DeviceAction.Wait -> linkedMapOf("validated_duration_ms" to durationMs.toString())
            is DeviceAction.SystemTool ->
                capability.traceFields().mapKeys { (key, _) -> "validated_$key" }
            is DeviceAction.TakeOver -> linkedMapOf("takeover_message" to message)
        }

    private fun PhoneAction.signature(): String = describe()

    private fun PhoneAction.reflectionKey(): String =
        when (this) {
            is PhoneAction.Launch -> "launch:${app.trim().lowercase()}"
            is PhoneAction.Tap -> {
                val target = targetDescription.trim().lowercase().ifBlank { "unspecified" }
                when {
                    elementIndex != null -> "tap:element:$elementIndex:$target"
                    targetBounds != null ->
                        "tap:visual:$target:${targetBounds.locationBucket()}"
                    else -> "tap:coordinate:$x:$y:$target"
                }
            }
            is PhoneAction.Type -> {
                val selector =
                    when {
                        elementIndex != null -> "element:$elementIndex"
                        targetBounds != null ->
                            "visual:${targetDescription.trim().lowercase()}:" +
                                targetBounds.locationBucket()
                        else -> "focused"
                    }
                "type:${text.hashCode()}:$selector"
            }
            is PhoneAction.Swipe -> "swipe:$startX:$startY:$endX:$endY"
            PhoneAction.Back -> "back"
            is PhoneAction.Wait -> "wait:$durationMs"
            is PhoneAction.SystemTool -> "system:${capability.stableKey()}"
            is PhoneAction.TakeOver -> "take_over"
        }

    private fun SystemCapability.stableKey(): String =
        when (this) {
            is SystemCapability.Navigate -> "navigate:${destination.trim().lowercase()}:$travelMode:$mapApp"
            is SystemCapability.SetAlarm -> "alarm:$hour:$minute:${label.trim().lowercase()}:${repeatDays.joinToString(",")}"
            is SystemCapability.SetTimer -> "timer:$durationSeconds:${label.trim().lowercase()}"
            is SystemCapability.CreateCalendarEvent -> "calendar:${title.trim().lowercase()}:$startTime"
            is SystemCapability.CreateContact -> "contact:${name.trim().lowercase()}:$phone:$email"
            is SystemCapability.ComposeSms -> "sms:$recipient:${body.hashCode()}"
            is SystemCapability.DialPhone -> "dial:$phoneNumber"
            is SystemCapability.OpenCamera -> "camera:$mode"
            is SystemCapability.OpenUrl -> "url:${url.trim().lowercase()}"
            is SystemCapability.WebSearch -> "search:${query.trim().lowercase()}"
            is SystemCapability.OpenSystemSettings -> "settings:$page"
            is SystemCapability.ComposeEmail -> "email:$recipient:${subject.hashCode()}:${body.hashCode()}"
            is SystemCapability.ShareText -> "share:${text.hashCode()}:${title.hashCode()}"
            is SystemCapability.PlayMedia -> "media:${query.trim().lowercase()}"
        }

    private fun SystemCapability.traceFields(): Map<String, String> =
        linkedMapOf(
            "action" to "SystemTool",
            "system_tool" to displayName,
            "risk" to if (requiresUserInteraction) "medium" else "low",
            "requires_user_interaction" to requiresUserInteraction.toString(),
            "capability_key" to stableKey(),
        )

    private fun PhoneAction.Tap.isSendAction(): Boolean {
        val label = targetDescription.trim().lowercase()
        return label.contains("发送") || label == "send" || label.contains("send button")
    }

    private fun PerceptionSnapshot.pageSignature(): String {
        val packageName = accessibility.packageName.orEmpty()
        val semantic =
            if (accessibility.treeHash != 0L) {
                accessibility.treeHash.toString()
            } else {
                "visual:${visualFingerprint.contentHashCode()}"
            }
        return "$packageName|$semantic"
    }

    private fun PhoneAction.Tap.normalizedCenter(): Pair<Int, Int> =
        targetBounds?.let {
            ((it.left + it.right) / 2) to ((it.top + it.bottom) / 2)
        } ?: (x to y)

    private fun PhoneAction.Tap.coordinateKey(): String {
        val center = normalizedCenter()
        return "tap:coord:${center.first}:${center.second}"
    }

    private fun PhoneAction.proposalFingerprint(): String =
        when (this) {
            is PhoneAction.Tap -> "tap:${coordinateKey()}"
            is PhoneAction.Wait -> "wait"
            is PhoneAction.Type -> "type"
            else -> reflectionKey()
        }

    private fun tapCandidateHints(
        observation: PerceptionSnapshot?,
        failedPoint: Pair<Int, Int>?,
        targetDescription: String,
    ): List<String> {
        if (observation == null) return emptyList()

        val hints = linkedSetOf<String>()
        val packageName = observation.accessibility.packageName.orEmpty()
        if (packageName == "com.netease.cloudmusic") {
            hints += "网易云路径偏好：先 Back 返回首页，再点击首页顶部放大镜/顶部搜索栏；忽略底部“搜索”导航按钮"
        }
        if (failedPoint != null) {
            val (x, y) = failedPoint
            listOf(
                0 to -12,
                0 to 12,
                -12 to 0,
                12 to 0,
                0 to -24,
                0 to 24,
            ).forEach { (dx, dy) ->
                val nextX = (x + dx).coerceIn(0, 999)
                val nextY = (y + dy).coerceIn(0, 999)
                hints += "tap:($nextX,$nextY)"
            }
        }

        val preferTopSearch =
            packageName == "com.netease.cloudmusic" &&
                (
                    targetDescription.contains("搜索", ignoreCase = true) ||
                        targetDescription.contains("底部", ignoreCase = true)
                )
        observation.accessibility.elements
            .asSequence()
            .filter { it.clickable || it.contentDescription.isNotBlank() || it.text.isNotBlank() }
            .filter { element ->
                if (!preferTopSearch) {
                    true
                } else {
                    val centerY = (element.bounds.top + element.bounds.bottom) / 2
                    (centerY.toDouble() / observation.screen.height * 999).toInt() < 700
                }
            }
            .toList()
            .sortedBy { element ->
                if (failedPoint == null) {
                    0
                } else {
                    val centerX = (element.bounds.left + element.bounds.right) / 2
                    val centerY = (element.bounds.top + element.bounds.bottom) / 2
                    squaredDistance(
                        (centerX.toDouble() / observation.screen.width * 999).toInt(),
                        (centerY.toDouble() / observation.screen.height * 999).toInt(),
                        failedPoint.first,
                        failedPoint.second,
                    )
                }
            }
            .take(5)
            .forEach { element ->
                val label =
                    listOf(
                            element.contentDescription.takeIf { it.isNotBlank() },
                            element.text.takeIf { it.isNotBlank() },
                        )
                        .firstOrNull()
                        .orEmpty()
                hints += "tap element_index=${element.index} label=${label.ifBlank { element.className.substringAfterLast('.') }}"
            }

        hints += "back"
        hints += "swipe_up"
        hints += "wait_1000ms"
        hints += "type_focused"
        return hints.take(8).toList()
    }

    private fun PhoneAction.NormalizedBounds.locationBucket(): String {
        val centerX = (left + right) / 2
        val centerY = (top + bottom) / 2
        return "${centerX / REFLECTION_LOCATION_BUCKET}:${centerY / REFLECTION_LOCATION_BUCKET}"
    }

    private fun squaredDistance(x1: Int, y1: Int, x2: Int, y2: Int): Int {
        val deltaX = x1 - x2
        val deltaY = y1 - y2
        return deltaX * deltaX + deltaY * deltaY
    }

    private fun isLaunchOnlyTask(rawTask: String): Boolean {
        val compact = rawTask.replace(Regex("\\s+"), "")
        return Regex(
            "^(请)?(帮我)?(打开|启动|进入|运行)[^，,。.!！；;]+[。.!！]?$",
            RegexOption.IGNORE_CASE,
        ).matches(compact)
    }

    private companion object {
        const val MAX_PLANNING_FAILURES = 3
        const val MAX_TAP_TARGET_ATTEMPTS = 4
        const val MAX_NEARBY_TAP_ATTEMPTS = 2
        const val NEARBY_TAP_RADIUS = 50
        const val STABILITY_POLL_MS = 300L
        const val STABILITY_VISUAL_THRESHOLD = 0.004
        const val BIG_VISUAL_CHANGE_THRESHOLD = 0.25
        const val MAX_CANDIDATE_ATTEMPTS_PER_REFLECTION = 3
        const val REQUIRED_STABLE_SAMPLES = 2
        const val MAX_NO_PROGRESS_WAITS = 3
        const val NO_VISUAL_PROGRESS_WARNING_ACTIONS = 4
        const val NO_PROGRESS_REFLECTION_SIGNAL_ACTIONS = 8
        const val REFLECTION_LOCATION_BUCKET = 100
    }
}

private data class StabilityPolicy(
    val initialDelayMs: Long,
    val timeoutMs: Long,
)

private data class StabilizedPage(
    val snapshot: PerceptionSnapshot,
    val waitedMs: Long,
    val samples: Int,
    val stable: Boolean,
    val lastFrameDelta: Double,
)
