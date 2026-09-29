package app.handlive.spike.web.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundCheckTest {
    private val isBrowser: (String) -> Boolean = { it == "com.android.chrome" }

    @Test
    fun aBrowserInFrontKeepsThePage() {
        val check = ForegroundCheck(isBrowser)
        assertEquals(ForegroundCheck.Verdict.STAY, check.observe("com.android.chrome"))
    }

    @Test
    fun anotherAppInFrontEndsThePageAtOnce() {
        val check = ForegroundCheck(isBrowser)
        assertEquals(ForegroundCheck.Verdict.LEFT, check.observe("com.google.android.apps.nexuslauncher"))
    }

    @Test
    fun oneMissingWindowIsNotALeave() {
        // The active window root is briefly null while the keyboard closes or a window animates (seen on API 29).
        val check = ForegroundCheck(isBrowser)
        assertEquals(ForegroundCheck.Verdict.STAY, check.observe(null))
        assertEquals(ForegroundCheck.Verdict.STAY, check.observe("com.android.chrome"))
        assertEquals(ForegroundCheck.Verdict.STAY, check.observe(null))
    }

    @Test
    fun missingWindowsInARowEndThePage() {
        val check = ForegroundCheck(isBrowser, nullPollsToLeave = 2)
        assertEquals(ForegroundCheck.Verdict.STAY, check.observe(null))
        assertEquals(ForegroundCheck.Verdict.LEFT, check.observe(null))
    }

    @Test
    fun resetForgetsEarlierMissingWindows() {
        val check = ForegroundCheck(isBrowser, nullPollsToLeave = 2)
        check.observe(null)
        check.reset()
        assertEquals(ForegroundCheck.Verdict.STAY, check.observe(null))
    }
}
