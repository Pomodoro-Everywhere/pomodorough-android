package me.egigoka.pomodorough.ui

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

internal fun assertAccessibleEditableValue(label: String, value: String) {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    automation.waitForIdle(500, 5_000)
    if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
    val labelNode = requireNotNull(automation.rootInActiveWindow?.findByContentDescription(label)) {
        "Missing platform input label: $label\n${accessibilityHierarchy()}"
    }
    val node = if (labelNode.isEditable) labelNode else requireNotNull(labelNode.parent)
    assertEquals(accessibilityHierarchy(), "android.widget.EditText", node.className.toString())
    assertEquals(value, node.text.toString())
    assertTrue("Input must remain editable", node.isEditable)
    assertTrue(node.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT))
}

private fun AccessibilityNodeInfo.findByContentDescription(label: String): AccessibilityNodeInfo? {
    if (contentDescription?.toString() == label) return this
    repeat(childCount) { index ->
        getChild(index)?.findByContentDescription(label)?.let { return it }
    }
    return null
}

internal fun accessibilityHierarchy(): String = buildString {
    appendLine("Platform accessibility hierarchy at failure:")
    val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
    if (root == null) appendLine("No active window") else appendAccessibilityNode(root)
}

private fun StringBuilder.appendAccessibilityNode(node: AccessibilityNodeInfo, depth: Int = 0) {
    val bounds = Rect().also(node::getBoundsInScreen)
    append("${"  ".repeat(depth)}${node.className} $bounds")
    append(" text=${node.text} label=${node.contentDescription} hint=${node.hintText}")
    appendLine(" focusable=${node.isFocusable} clickable=${node.isClickable} enabled=${node.isEnabled}")
    repeat(node.childCount) { index ->
        node.getChild(index)?.let { appendAccessibilityNode(it, depth + 1) }
    }
}
