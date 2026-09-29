package app.handlive.spike.web.logic

/**
 * Decides, on each poll while a page is active, whether the browser is still in front. Another app's window ends the
 * page at once. A missing active window (the root is null for a moment while the keyboard closes or a window
 * animates, seen on API 29) ends it only after [nullPollsToLeave] polls in a row, so a transient gap is not reported
 * as `inactive reason=left`. Not thread-safe: the service calls it on the main thread only.
 */
class ForegroundCheck(
    private val isBrowser: (String) -> Boolean,
    private val nullPollsToLeave: Int = 2,
) {
    enum class Verdict { STAY, LEFT }

    private var nullPolls = 0

    /** [frontPackage]: the package of the active window's root, or null when there is no root. */
    fun observe(frontPackage: String?): Verdict {
        if (frontPackage == null) {
            nullPolls++
            return if (nullPolls >= nullPollsToLeave) Verdict.LEFT else Verdict.STAY
        }
        nullPolls = 0
        return if (isBrowser(frontPackage)) Verdict.STAY else Verdict.LEFT
    }

    fun reset() {
        nullPolls = 0
    }
}
