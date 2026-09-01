package com.bluewhale.agent.core.graph

data class SubtaskScopeResolution(
    val label: String,
    val key: String,
    val changed: Boolean,
)

/**
 * Keeps recovery budgets scoped to one observable intermediate goal.
 *
 * A failed action cannot reset the budget merely by renaming its subtask. A transition is
 * accepted only after the graph has made verified progress (or when the first scope is created).
 */
object SubtaskScopePolicy {
    const val MAX_RECOVERY_ATTEMPTS = 5

    fun resolve(
        currentLabel: String,
        currentKey: String,
        proposedLabel: String,
        expectedOutcome: String,
        fallback: String,
        allowTransition: Boolean,
    ): SubtaskScopeResolution {
        val candidate =
            proposedLabel.trim()
                .ifBlank { expectedOutcome.trim() }
                .ifBlank { fallback.trim() }
                .ifBlank { "current_subtask" }
                .take(MAX_LABEL_LENGTH)
        val candidateKey = key(candidate)
        if (currentKey.isNotBlank() && !allowTransition && candidateKey != currentKey) {
            return SubtaskScopeResolution(
                label = currentLabel.ifBlank { candidate },
                key = currentKey,
                changed = false,
            )
        }
        return SubtaskScopeResolution(
            label = candidate,
            key = candidateKey,
            changed = currentKey.isNotBlank() && candidateKey != currentKey,
        )
    }

    fun shouldTerminalAdjudicate(reflectionCount: Int): Boolean =
        reflectionCount > MAX_RECOVERY_ATTEMPTS

    internal fun key(value: String): String =
        value
            .trim()
            .lowercase()
            .map { character -> if (character.isLetterOrDigit()) character else '_' }
            .joinToString("")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(MAX_KEY_LENGTH)
            .ifBlank { "current_subtask" }

    private const val MAX_LABEL_LENGTH = 120
    private const val MAX_KEY_LENGTH = 96
}
