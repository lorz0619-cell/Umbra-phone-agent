package com.bluewhale.agent.verification

import com.bluewhale.agent.model.ActionResult
import com.bluewhale.agent.model.DeviceAction
import com.bluewhale.agent.model.PerceptionSnapshot
import com.bluewhale.agent.model.PhoneAction
import com.bluewhale.agent.model.VerificationResult

/** Deterministic post-action checks. The VLM never self-certifies its own action. */
class ActionVerifier {
    fun verify(
        requested: PhoneAction,
        executed: DeviceAction,
        executionResult: ActionResult,
        before: PerceptionSnapshot,
        after: PerceptionSnapshot,
    ): VerificationResult {
        if (executionResult is ActionResult.Failure) {
            return VerificationResult(
                success = false,
                message = "动作执行失败：${executionResult.message}",
            )
        }
        executionResult as ActionResult.Success

        val visualScore = visualChangeScore(before.visualFingerprint, after.visualFingerprint)
        val packageChanged =
            before.accessibility.packageName != after.accessibility.packageName &&
                after.accessibility.packageName != null
        val treeChanged =
            before.accessibility.treeHash != after.accessibility.treeHash &&
                after.accessibility.treeHash != 0L

        return when (requested) {
            is PhoneAction.Wait ->
                success("等待完成并重新感知页面", visualScore, packageChanged, treeChanged)
            is PhoneAction.TakeOver ->
                success("已暂停自动执行，等待人工接管", visualScore, packageChanged, treeChanged)
            is PhoneAction.SystemTool -> {
                if (executionResult.metadata["system_verified"] == "true") {
                    success("系统工具已由 Android 执行器确认分发", visualScore, packageChanged, treeChanged)
                } else {
                    failure("系统工具没有返回可验证的分发结果", visualScore, packageChanged, treeChanged)
                }
            }
            is PhoneAction.Type -> {
                val expected = requested.text
                val focused = after.accessibility.focusedText.orEmpty()
                val visible =
                    after.accessibility.elements.any {
                        it.text.contains(expected) || it.contentDescription.contains(expected)
                    }
                val executorVerified =
                    executionResult.metadata["verified"] == "true" ||
                        executionResult.message.contains("已验证")
                val targetedDispatchWithVisualEvidence =
                    executionResult.metadata["input_dispatched"] == "true" &&
                        visualScore >= TYPE_VISUAL_THRESHOLD
                if (
                    focused.contains(expected) ||
                    visible ||
                    executorVerified ||
                    targetedDispatchWithVisualEvidence
                ) {
                    success("输入内容已通过无障碍树/执行器校验", visualScore, packageChanged, treeChanged)
                } else {
                    failure(
                        "未在输入焦点或页面语义树中确认目标文本，禁止假定 Type 成功",
                        visualScore,
                        packageChanged,
                        treeChanged,
                    )
                }
            }
            is PhoneAction.Launch -> {
                val expectedPackage = executionResult.metadata["packageName"]
                val actualPackage = after.accessibility.packageName
                val packageMatches =
                    expectedPackage != null &&
                        (actualPackage == expectedPackage ||
                            actualPackage?.startsWith(expectedPackage) == true)
                val packageMismatch =
                    expectedPackage != null &&
                        actualPackage != null &&
                        !packageMatches
                if (packageMismatch) {
                    failure(
                        "Launch 后前台包名不匹配：期望 $expectedPackage，实际 $actualPackage",
                        visualScore,
                        packageChanged,
                        treeChanged,
                    )
                } else if (packageMatches || packageChanged || visualScore >= LAUNCH_VISUAL_THRESHOLD) {
                    success("前台应用或屏幕已确认发生变化", visualScore, packageChanged, treeChanged)
                } else {
                    failure("Launch 后前台应用和屏幕均未确认变化", visualScore, packageChanged, treeChanged)
                }
            }
            is PhoneAction.Tap -> {
                val focusChanged =
                    before.accessibility.focusedText != after.accessibility.focusedText
                if (visualScore >= TAP_VISUAL_THRESHOLD || treeChanged || packageChanged || focusChanged) {
                    success("点击后页面/焦点变化已确认", visualScore, packageChanged, treeChanged)
                } else {
                    failure("Tap 后未检测到页面、语义树或焦点变化", visualScore, packageChanged, treeChanged)
                }
            }
            is PhoneAction.Swipe -> {
                if (visualScore >= SWIPE_VISUAL_THRESHOLD || treeChanged || packageChanged) {
                    success("滑动后的页面变化已确认", visualScore, packageChanged, treeChanged)
                } else {
                    failure("Swipe 后页面没有可确认变化", visualScore, packageChanged, treeChanged)
                }
            }
            PhoneAction.Back -> {
                if (visualScore >= BACK_VISUAL_THRESHOLD || treeChanged || packageChanged) {
                    success("返回后的页面变化已确认", visualScore, packageChanged, treeChanged)
                } else {
                    failure("Back 后页面没有可确认变化", visualScore, packageChanged, treeChanged)
                }
            }
        }
    }

    private fun success(
        message: String,
        score: Double,
        packageChanged: Boolean,
        treeChanged: Boolean,
    ) = VerificationResult(true, message, score, packageChanged, treeChanged)

    private fun failure(
        message: String,
        score: Double,
        packageChanged: Boolean,
        treeChanged: Boolean,
    ) = VerificationResult(false, message, score, packageChanged, treeChanged)

    companion object {
        private const val TAP_VISUAL_THRESHOLD = 0.006
        private const val TYPE_VISUAL_THRESHOLD = 0.003
        private const val SWIPE_VISUAL_THRESHOLD = 0.01
        private const val BACK_VISUAL_THRESHOLD = 0.008
        private const val LAUNCH_VISUAL_THRESHOLD = 0.015

        fun visualChangeScore(before: IntArray, after: IntArray): Double {
            if (before.isEmpty() || before.size != after.size) return 1.0
            val difference =
                before.indices.sumOf { index ->
                    kotlin.math.abs(before[index] - after[index]).toDouble()
                }
            return difference / before.size / 255.0
        }
    }
}
