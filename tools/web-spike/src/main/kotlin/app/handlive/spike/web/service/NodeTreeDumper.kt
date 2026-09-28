package app.handlive.spike.web.service

import app.handlive.spike.web.logic.UiNode
import app.handlive.spike.web.logic.UrlNormalizer
import app.handlive.spike.web.logic.walk
import java.io.File

/**
 * Dump mode: writes the node tree of the browser window (view ids, classes, flags) to a text file so the adapters'
 * id tables can be built for each browser version. Text and content descriptions are redacted to their length;
 * the only content kept is whether a text is a web address (`url`) and whether a description contains a word that
 * marks a private mode (`private_hint`).
 */
object NodeTreeDumper {
    private const val MAX_NODES = 3_000
    private val privateWords = listOf("incognito", "private", "inprivate", "secret")

    fun dump(
        root: UiNode,
        header: List<String>,
        target: File,
    ): Int {
        var count = 0
        target.bufferedWriter().use { out ->
            header.forEach { out.appendLine("# $it") }
            root.walk(MAX_NODES) { node, depth ->
                count++
                out.appendLine("  ".repeat(depth) + describe(node))
                true
            }
            out.appendLine("# nodes=$count (limit $MAX_NODES)")
        }
        return count
    }

    private fun describe(node: UiNode): String =
        buildString {
            append(node.className?.substringAfterLast('.') ?: "?")
            node.viewId?.let { append(" id=").append(it) }
            if (node.isFocused) append(" focused")
            if (node.isEditable) append(" editable")
            node.text?.let { text ->
                append(" text_len=").append(text.length)
                UrlNormalizer.normalize(text)?.let { append(if (it.hostOnly) " url=host_only" else " url=full") }
            }
            node.contentDescription?.let { description ->
                append(" desc_len=").append(description.length)
                val lower = description.toString().lowercase()
                privateWords.firstOrNull { lower.contains(it) }?.let { append(" private_hint=").append(it) }
            }
        }
}
