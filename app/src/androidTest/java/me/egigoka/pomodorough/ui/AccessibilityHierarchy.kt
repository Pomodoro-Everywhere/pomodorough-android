package me.egigoka.pomodorough.ui

import android.app.UiAutomation
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

internal fun assertAccessibleEditableValue(composeRule: ComposeTestRule, label: String, value: String) {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    automation.waitForIdle(500, 5_000)
    // The compose rule advances Compose frames only when the test syncs. The keyboard inset change that
    // keeps the focused field in view can land after the last sync, which leaves the field outside the
    // laid-out viewport and absent from the platform tree. Sync until the platform tree catches up; a
    // timeout is reported by the hierarchy assertion below.
    runCatching { composeRule.waitUntil(timeoutMillis = 5_000) { automation.findInput(label) != null } }
    val labelNode = requireNotNull(automation.findInput(label)) {
        "Missing platform input label: $label\n${accessibilityHierarchy()}"
    }
    val node = if (labelNode.isEditable) labelNode else requireNotNull(labelNode.parent)
    assertEquals(accessibilityHierarchy(), "android.widget.EditText", node.className.toString())
    assertEquals(value, node.text.toString())
    assertTrue("Input must remain editable", node.isEditable)
    assertTrue(node.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT))
}

private fun UiAutomation.findInput(label: String): AccessibilityNodeInfo? {
    if (Build.VERSION.SDK_INT >= 34) clearCache()
    return rootInActiveWindow?.findByContentDescription(label)
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
