package com.bluewhale.agent.perception

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.bluewhale.agent.model.AccessibilityElement
import com.bluewhale.agent.model.AccessibilitySnapshot

/**
 * Converts Android's mutable node graph into a small immutable observation.
 *
 * Only actionable or labelled visible nodes are retained. This gives the VLM
 * semantic anchors without sending the full, often repetitive, accessibility tree.
 */
object AccessibilityTreeExtractor {
    fun extract(
        root: AccessibilityNodeInfo?,
        maxElements: Int = 120,
    ): AccessibilitySnapshot {
        if (root == null) return AccessibilitySnapshot()

        val elements = mutableListOf<AccessibilityElement>()
        var focusedText: String? = null
        var hash = 1125899906842597L

        fun visit(node: AccessibilityNodeInfo) {
            if (elements.size >= maxElements) return
            val text = node.text?.toString().orEmpty().trim()
            val description = node.contentDescription?.toString().orEmpty().trim()
            val resourceId = node.viewIdResourceName.orEmpty()
            val meaningful =
                node.isVisibleToUser &&
                    (text.isNotBlank() ||
                        description.isNotBlank() ||
                        resourceId.isNotBlank() ||
                        node.isClickable ||
                        node.isEditable)

            if (node.isFocused && node.isEditable) focusedText = text
            if (meaningful) {
                val bounds = Rect().also(node::getBoundsInScreen)
                val element =
                    AccessibilityElement(
                        index = elements.size,
                        className = node.className?.toString().orEmpty(),
                        text = text,
                        contentDescription = description,
                        resourceId = resourceId,
                        bounds = bounds,
                        clickable = node.isClickable,
                        editable = node.isEditable,
                        focused = node.isFocused,
                    )
                elements += element
                hash = hash * 31 + element.promptLine().hashCode()
            }

            for (index in 0 until node.childCount) {
                if (elements.size >= maxElements) break
                val child = node.getChild(index) ?: continue
                try {
                    visit(child)
                } finally {
                    child.recycle()
                }
            }
        }

        visit(root)
        return AccessibilitySnapshot(
            packageName = root.packageName?.toString(),
            elements = elements,
            focusedText = focusedText,
            treeHash = hash,
        )
    }
}
