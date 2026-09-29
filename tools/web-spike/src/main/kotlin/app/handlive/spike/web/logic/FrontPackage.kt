package app.handlive.spike.web.logic

/**
 * Picks the package of the window the user is looking at. [rootInActiveWindow] alone can keep returning the
 * browser after HOME on Android 16 (One UI) while the launcher is in front, so the leave poll never fires.
 * Prefer the focused window, then the active one, then the topmost application window from [windows].
 */
object FrontPackage {
    /** Minimal view of an accessibility window for the leave poll (keeps unit tests free of the framework). */
    data class Window(
        val packageName: String?,
        val focused: Boolean,
        val active: Boolean,
        val layer: Int,
        val application: Boolean,
    )

    fun of(
        windows: List<Window>?,
        rootPackage: String?,
    ): String? {
        val fromWindows = fromWindows(windows)
        if (fromWindows != null) return fromWindows
        return rootPackage
    }

    private fun fromWindows(windows: List<Window>?): String? {
        if (windows.isNullOrEmpty()) return null
        val application = windows.filter { it.application }
        val pool = application.ifEmpty { windows }
        val pick =
            pool.firstOrNull { it.focused }
                ?: pool.firstOrNull { it.active }
                ?: pool.maxByOrNull { it.layer }
        return pick?.packageName
    }
}
