package app.handlive.android.feature.call

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Group 6 rules and 0.6.5: numbers, contact names and DTMF digits never reach a log. The call module writes no log at
 * all — the only lines it produces are the `HLBENCH/1` events of [CallBenchTrace], checked in [CallBenchTraceTest].
 */
class CallLogPrivacyTest {
    @Test
    fun theCallModuleWritesNoLog() {
        val sources = File("src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("sources found", sources.size > 10)
        val offenders =
            sources.flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    "${file.name}:${index + 1}".takeIf { FORBIDDEN.any { pattern -> pattern.containsMatchIn(line) } }
                }
            }
        assertTrue("logging calls: $offenders", offenders.isEmpty())
    }

    private companion object {
        val FORBIDDEN =
            listOf(
                Regex("""\bandroid\.util\.Log\b"""),
                Regex("""\bLog\.[vdiwe]\("""),
                Regex("""\bprintln\("""),
                Regex("""\bprintStackTrace\("""),
                Regex("""\bSystem\.(out|err)\b"""),
                Regex("""\bTimber\b"""),
            )
    }
}
