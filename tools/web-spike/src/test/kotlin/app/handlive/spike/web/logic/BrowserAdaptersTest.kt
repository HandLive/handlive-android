package app.handlive.spike.web.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A hand-built node for the adapter rules; the service uses the real accessibility tree. */
private data class Node(
    override val viewId: String? = null,
    override val className: String? = "android.view.View",
    override val text: CharSequence? = null,
    override val contentDescription: CharSequence? = null,
    override val isFocused: Boolean = false,
    override val isEditable: Boolean = false,
    override val children: List<UiNode> = emptyList(),
) : UiNode

class BrowserAdaptersTest {
    @Test
    fun adapterIsSelectedByPackageName() {
        Browser.entries.forEach { browser ->
            assertSame(browser, BrowserAdapters.forPackage(browser.packageName)!!.browser)
        }
        assertNull(BrowserAdapters.forPackage("com.android.launcher3"))
        assertNull(BrowserAdapters.forPackage("com.chrome.beta"))
        assertNull(BrowserAdapters.forPackage(null))
    }

    @Test
    fun wireIdsMatchTheProtocolList() {
        assertEquals(
            listOf("chrome", "samsung", "firefox", "edge", "brave", "opera", "vivaldi", "duckduckgo"),
            Browser.entries.map { it.wireId },
        )
    }

    @Test
    fun serviceConfigurationOmitsPackageNamesSoHomeCanEndAPage() {
        // packageNames would hide the launcher from the leave poll on Android 16; browsers are filtered in code.
        val xml = File("src/main/res/xml/browser_pages_accessibility.xml").readText()
        assertTrue(!xml.contains("android:packageNames="))
        assertTrue(xml.contains("flagRetrieveInteractiveWindows"))
        assertTrue(xml.contains("typeWindowsChanged"))
        assertTrue(xml.contains("android:canRetrieveWindowContent=\"true\""))
        assertTrue(xml.contains("android:notificationTimeout=\"500\""))
        assertEquals(
            listOf(
                "com.android.chrome",
                "com.sec.android.app.sbrowser",
                "org.mozilla.firefox",
                "com.microsoft.emmx",
                "com.brave.browser",
                "com.opera.browser",
                "com.vivaldi.browser",
                "com.duckduckgo.mobile.android",
            ).sorted(),
            BrowserAdapters.packageNames.sorted(),
        )
    }

    @Test
    fun urlBarIsFoundByKnownIdFirst() {
        val bar = Node("com.android.chrome:id/url_bar", "android.widget.EditText", "example.com", isEditable = true)
        val root = Node(children = listOf(Node("com.android.chrome:id/toolbar", children = listOf(bar))))
        val match = BrowserAdapters.forPackage("com.android.chrome")!!.findUrlBar(root)!!
        assertSame(bar, match.node)
        assertEquals(FoundVia.VIEW_ID, match.via)
        assertEquals("com.android.chrome:id/url_bar", match.source)
    }

    @Test
    fun fallbackFindsTheEditTextHoldingAnAddress() {
        val search = Node(null, "android.widget.EditText", "weather", isEditable = true)
        val bar = Node(null, "android.widget.EditText", "https://example.org/a", isEditable = true)
        val root = Node(children = listOf(search, bar))
        val match = BrowserAdapters.forPackage("org.mozilla.firefox")!!.findUrlBar(root)!!
        assertSame(bar, match.node)
        assertEquals(FoundVia.FALLBACK, match.via)
        assertEquals("android.widget.EditText", match.source)
    }

    @Test
    fun privateMarkersAndUnknownLayouts() {
        val chrome = BrowserAdapters.forPackage("com.android.chrome")!!
        val bar = Node("com.android.chrome:id/url_bar", "android.widget.EditText", "example.com")
        val normal = Node(children = listOf(bar))
        assertEquals(PrivateState.Normal, chrome.privateState(normal, chrome.findUrlBar(normal)!!))

        val incognito = Node(children = listOf(Node("com.android.chrome:id/incognito_badge"), bar))
        assertEquals(
            PrivateState.Private("id:incognito_badge"),
            chrome.privateState(incognito, chrome.findUrlBar(incognito)!!),
        )

        val samsung = BrowserAdapters.forPackage("com.sec.android.app.sbrowser")!!
        val secret =
            Node(
                children =
                    listOf(
                        Node(contentDescription = "Secret mode on"),
                        Node(null, "android.widget.EditText", "example.com", isEditable = true),
                    ),
            )
        assertEquals(
            PrivateState.Private("desc:secret mode"),
            samsung.privateState(secret, samsung.findUrlBar(secret)!!),
        )

        val unknownLayout = Node(children = listOf(Node(null, "android.widget.EditText", "example.com")))
        assertEquals(PrivateState.Unknown, samsung.privateState(unknownLayout, samsung.findUrlBar(unknownLayout)!!))
    }
}
