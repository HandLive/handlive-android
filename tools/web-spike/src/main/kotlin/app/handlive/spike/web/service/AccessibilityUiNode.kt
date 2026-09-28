package app.handlive.spike.web.service

import android.view.accessibility.AccessibilityNodeInfo
import app.handlive.spike.web.logic.UiNode

/**
 * [UiNode] over a live `AccessibilityNodeInfo`. Children are fetched lazily (each one is an IPC to the browser), and
 * [findByViewId] uses the framework's search, one IPC for the whole window. [counter] counts the nodes touched so the
 * spike can report the cost per page.
 */
class AccessibilityUiNode(
    private val info: AccessibilityNodeInfo,
    private val counter: NodeCounter,
) : UiNode {
    init {
        counter.nodes++
    }

    override val viewId: String? get() = info.viewIdResourceName
    override val className: String? get() = info.className?.toString()
    override val text: CharSequence? get() = info.text
    override val contentDescription: CharSequence? get() = info.contentDescription
    override val isFocused: Boolean get() = info.isFocused
    override val isEditable: Boolean get() = info.isEditable

    override val children: List<UiNode> by lazy {
        (0 until info.childCount).mapNotNull { index -> info.getChild(index)?.let { AccessibilityUiNode(it, counter) } }
    }

    override fun findByViewId(viewId: String): List<UiNode> =
        info.findAccessibilityNodeInfosByViewId(viewId).orEmpty().map { AccessibilityUiNode(it, counter) }

    val windowId: Int get() = info.windowId
}

/** Number of accessibility nodes the service touched since start: a proxy for its IPC cost. */
class NodeCounter {
    var nodes = 0L
}
