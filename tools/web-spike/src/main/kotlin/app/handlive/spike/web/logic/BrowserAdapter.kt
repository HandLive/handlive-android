package app.handlive.spike.web.logic

/**
 * The browsers the spike watches (W2 `browser` ids). [packageName] is the stable channel; beta and dev channels are
 * left out on purpose so `android:packageNames` stays short.
 */
enum class Browser(
    val wireId: String,
    val packageName: String,
) {
    CHROME("chrome", "com.android.chrome"),
    SAMSUNG("samsung", "com.sec.android.app.sbrowser"),
    FIREFOX("firefox", "org.mozilla.firefox"),
    EDGE("edge", "com.microsoft.emmx"),
    BRAVE("brave", "com.brave.browser"),
    OPERA("opera", "com.opera.browser"),
    VIVALDI("vivaldi", "com.vivaldi.browser"),
    DUCKDUCKGO("duckduckgo", "com.duckduckgo.mobile.android"),
}

/** How the URL bar was found: by a known view id, or by the fallback search. */
enum class FoundVia(
    val logValue: String,
) {
    VIEW_ID("id"),
    FALLBACK("fallback"),
}

/** The URL bar node, how it was found, and its view id (or class name when it has no id) for the log. */
data class UrlBarMatch(
    val node: UiNode,
    val via: FoundVia,
    val source: String,
)

/** Private (incognito, secret, InPrivate) state of the window, with the marker that decided it. */
sealed interface PrivateState {
    data class Private(
        val marker: String,
    ) : PrivateState

    /** No private marker, and the bar was found by a known id: the adapter knows this browser's layout. */
    data object Normal : PrivateState

    /** No private marker, but the layout is not known (fallback): the product must not send (W3 "unknown"). */
    data object Unknown : PrivateState
}

/**
 * Strategy per browser (W3, like the OEM Bluetooth adapters): where the URL bar is and how a private tab shows
 * itself. The tables below are the starting point the spike checks on real devices; the dump mode records the node
 * tree of each browser version so the ids can be corrected.
 */
interface BrowserAdapter {
    val browser: Browser

    fun findUrlBar(root: UiNode): UrlBarMatch?

    fun privateState(
        root: UiNode,
        bar: UrlBarMatch,
    ): PrivateState
}

/**
 * Table-driven adapter: known URL bar ids in order of preference, then the fallback (the first editable text field
 * whose text is a web address); private markers are view id fragments and content description fragments.
 */
open class TableBrowserAdapter(
    override val browser: Browser,
    private val urlBarIds: List<String>,
    private val privateIdHints: List<String>,
    private val privateDescriptionHints: List<String>,
) : BrowserAdapter {
    override fun findUrlBar(root: UiNode): UrlBarMatch? {
        for (shortId in urlBarIds) {
            val fullId = "${browser.packageName}:id/$shortId"
            val node = root.findByViewId(fullId).firstOrNull { it.text?.isNotBlank() == true }
            if (node != null) return UrlBarMatch(node, FoundVia.VIEW_ID, fullId)
        }
        return fallback(root)
    }

    private fun fallback(root: UiNode): UrlBarMatch? {
        var match: UrlBarMatch? = null
        root.walk { node, _ ->
            val editable = node.isEditable || node.className?.endsWith("EditText") == true
            val urlish = node.shortId?.contains("url", ignoreCase = true) == true
            if ((editable || urlish) && UrlNormalizer.normalize(node.text) != null) {
                match = UrlBarMatch(node, FoundVia.FALLBACK, node.viewId ?: node.className ?: "?")
                false
            } else {
                true
            }
        }
        return match
    }

    override fun privateState(
        root: UiNode,
        bar: UrlBarMatch,
    ): PrivateState {
        var marker: String? = null
        root.walk { node, _ ->
            val id = node.shortId?.lowercase()
            val description = node.contentDescription?.toString()?.lowercase()
            marker = privateIdHints.firstOrNull { hint -> id?.contains(hint) == true }?.let { "id:$id" }
                ?: privateDescriptionHints
                    .firstOrNull { hint -> description?.contains(hint) == true }
                    ?.let { "desc:$it" }
            marker == null
        }
        return when {
            marker != null -> PrivateState.Private(marker!!)
            bar.via == FoundVia.VIEW_ID -> PrivateState.Normal
            else -> PrivateState.Unknown
        }
    }
}

/** Adapter selection by package name: the service only hears from these packages (`android:packageNames`). */
object BrowserAdapters {
    // Chromium browsers share Chrome's toolbar ids; each keeps its own name for the private mode.
    private val chromiumBar = listOf("url_bar", "search_box_text")

    val all: List<BrowserAdapter> =
        listOf(
            TableBrowserAdapter(Browser.CHROME, chromiumBar, listOf("incognito"), listOf("incognito")),
            TableBrowserAdapter(
                Browser.SAMSUNG,
                listOf("location_bar_edit_text", "url_bar_text"),
                listOf("secret"),
                listOf("secret mode"),
            ),
            TableBrowserAdapter(
                Browser.FIREFOX,
                listOf("mozac_browser_toolbar_url_view", "url_bar_title"),
                listOf("private"),
                listOf("private browsing", "private tab"),
            ),
            TableBrowserAdapter(Browser.EDGE, chromiumBar, listOf("inprivate", "incognito"), listOf("inprivate")),
            TableBrowserAdapter(Browser.BRAVE, chromiumBar, listOf("incognito", "private"), listOf("private tab")),
            TableBrowserAdapter(Browser.OPERA, listOf("url_field", "url_bar"), listOf("private"), listOf("private")),
            TableBrowserAdapter(Browser.VIVALDI, chromiumBar, listOf("incognito", "private"), listOf("private")),
            // DuckDuckGo has no private mode (every tab is cleared by the Fire button): no markers.
            TableBrowserAdapter(Browser.DUCKDUCKGO, listOf("omnibarTextInput"), emptyList(), emptyList()),
        )

    private val byPackage = all.associateBy { it.browser.packageName }

    fun forPackage(packageName: CharSequence?): BrowserAdapter? = packageName?.let { byPackage[it.toString()] }

    /** The value of `android:packageNames` in the service configuration; a unit test keeps the two in step. */
    val packageNames: List<String> = Browser.entries.map { it.packageName }
}
