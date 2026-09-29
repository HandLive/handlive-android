package app.handlive.spike.web.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrontPackageTest {
    @Test
    fun prefersFocusedApplicationWindowOverStaleActiveRoot() {
        val chrome = FrontPackage.Window("com.android.chrome", focused = false, active = false, layer = 1, application = true)
        val launcher =
            FrontPackage.Window("com.sec.android.app.launcher", focused = true, active = true, layer = 2, application = true)
        assertEquals(
            "com.sec.android.app.launcher",
            FrontPackage.of(listOf(chrome, launcher), rootPackage = "com.android.chrome"),
        )
    }

    @Test
    fun fallsBackToRootWhenWindowsAreEmpty() {
        assertEquals("com.android.chrome", FrontPackage.of(emptyList(), "com.android.chrome"))
        assertNull(FrontPackage.of(null, null))
    }

    @Test
    fun usesActiveWindowWhenNoneIsFocused() {
        val chrome = FrontPackage.Window("com.android.chrome", focused = false, active = false, layer = 1, application = true)
        val settings = FrontPackage.Window("com.android.settings", focused = false, active = true, layer = 5, application = true)
        assertEquals("com.android.settings", FrontPackage.of(listOf(chrome, settings), null))
    }
}
