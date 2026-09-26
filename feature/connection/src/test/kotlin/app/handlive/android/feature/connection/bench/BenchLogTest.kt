package app.handlive.android.feature.connection.bench

import org.junit.Assert.assertEquals
import org.junit.Test

/** The `HLBENCH/1` line format that `shared/tools/bench/bench_log.py` parses. */
class BenchLogTest {
    @Test
    fun lineMatchesTheBenchFormat() {
        val line =
            BenchLog.format(
                1_727_151_101_000,
                9_001_000_000_000,
                "8c7d6e5f",
                BenchEvent.CLIP_READ,
                listOf(
                    "clip" to "0192f3e0-5a21-7b3c-9d4e-1f2a3b4c5d6e",
                    "kind" to "text",
                    "bytes" to 27,
                    "source" to "auto",
                ),
            )
        assertEquals(
            "HLBENCH/1 wall=1727151101000 mono=9001000000000 dev=8c7d6e5f role=android ev=clip_read " +
                "clip=0192f3e0-5a21-7b3c-9d4e-1f2a3b4c5d6e kind=text bytes=27 source=auto",
            line,
        )
    }

    @Test
    fun valuesNeverContainSpaces() {
        val line = BenchLog.format(1, 2, "00000000", BenchEvent.NET, listOf("change" to "up now"))
        assertEquals("HLBENCH/1 wall=1 mono=2 dev=00000000 role=android ev=net change=up_now", line)
    }
}
