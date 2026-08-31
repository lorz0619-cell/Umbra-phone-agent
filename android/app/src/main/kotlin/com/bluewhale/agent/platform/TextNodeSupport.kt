package com.bluewhale.agent.platform

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

object TextNodeSupport {
    fun canAcceptText(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        return node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
    }

    fun findFocusedTextNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null

        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null) {
            if (canAcceptText(focused)) return focused
            focused.recycle()
        }

        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isFocused && canAcceptText(node)) return node
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                val found = search(child)
                if (found != null) return found
            }
            return null
        }
        return search(root)
    }

    fun findTextNodeAt(
        root: AccessibilityNodeInfo?,
        x: Int,
        y: Int,
    ): AccessibilityNodeInfo? {
        if (root == null) return null
        val bounds = Rect()

        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            node.getBoundsInScreen(bounds)
            if (!bounds.contains(x, y)) return null

            for (index in node.childCount - 1 downTo 0) {
                val child = node.getChild(index) ?: continue
                val found = search(child)
                if (found != null) return found
            }

            return if (canAcceptText(node)) node else null
        }
        return search(root)
    }

    fun findFirstTextNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        if (canAcceptText(root)) return root
        for (index in 0 until root.childCount) {
            val child = root.getChild(index) ?: continue
            val found = findFirstTextNode(child)
            if (found != null) return found
        }
        return null
    }

    fun writeText(node: AccessibilityNodeInfo, text: String): Boolean {
        val arguments =
            Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text,
                )
            }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        if (!ok) return false

        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
            },
        )
        return true
    }
    fun containsText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!node.refresh()) return false
        val actual = node.text?.toString() ?: ""
        return actual.contains(text) || actual == text
    }
}