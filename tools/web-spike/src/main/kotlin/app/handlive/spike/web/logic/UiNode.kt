package app.handlive.spike.web.logic

/**
 * The parts of an accessibility node the adapters read. The service wraps `AccessibilityNodeInfo` in it; JVM tests
 * build small trees by hand, so the adapter rules run without a device.
 */
interface UiNode {
    /** Fully qualified view id (`com.android.chrome:id/url_bar`) or null (Compose and web content often have none). */
    val viewId: String?
    val className: String?
    val text: CharSequence?
    val contentDescription: CharSequence?
    val isFocused: Boolean
    val isEditable: Boolean
    val children: List<UiNode>

    /**
     * Nodes below this one with the fully qualified [viewId]. The service answers it with one
     * `findAccessibilityNodeInfosByViewId` call; the default walks the tree.
     */
    fun findByViewId(viewId: String): List<UiNode> {
        val found = mutableListOf<UiNode>()
        walk { node, _ ->
            if (node.viewId == viewId) found += node
            true
        }
        return found
    }
}

/** Depth-first walk that stops at [maxNodes] so a huge web page tree cannot stall the service. */
fun UiNode.walk(
    maxNodes: Int = 400,
    visit: (UiNode, Int) -> Boolean,
) {
    var seen = 0
    val stack = ArrayDeque<Pair<UiNode, Int>>()
    stack.addLast(this to 0)
    while (stack.isNotEmpty() && seen < maxNodes) {
        val (node, depth) = stack.removeLast()
        seen++
        if (!visit(node, depth)) return
        node.children.asReversed().forEach { stack.addLast(it to depth + 1) }
    }
}

/** The short id after `:id/`, or null. */
val UiNode.shortId: String?
    get() = viewId?.substringAfter(":id/", viewId ?: "")?.takeIf { it.isNotEmpty() }
