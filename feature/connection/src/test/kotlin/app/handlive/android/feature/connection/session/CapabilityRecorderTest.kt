package app.handlive.android.feature.connection.session

import app.handlive.android.core.protocol.capability.CapabilityData
import app.handlive.android.core.protocol.capability.CapabilityFeatures
import app.handlive.android.core.protocol.capability.SmsFeature
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CONN-01 step 10: the capability of a session reaches `features_json`, even when the session ends at once. */
class CapabilityRecorderTest {
    private val capability = MutableStateFlow<CapabilityData?>(null)
    private val records = mutableListOf<CapabilityData>()

    @Test
    fun aSessionThatEndsRightAfterHelloStillLeavesItsCapability() =
        runTest {
            val recorder = CapabilityRecorder(capability) { records += it }
            recorder.start(backgroundScope)
            // `capability/hello`, then `session/bye` before the recorder ever ran.
            capability.value = HELLO
            recorder.finish()
            assertEquals(listOf(HELLO), records)
        }

    @Test
    fun aWriteCutShortByTheEndOfTheSessionIsMadeAgain() =
        runTest {
            val recorder =
                CapabilityRecorder(capability) {
                    delay(WRITE_MILLIS)
                    records += it
                }
            recorder.start(backgroundScope)
            capability.value = HELLO
            runCurrent()
            recorder.finish()
            assertEquals(listOf(HELLO), records)
        }

    @Test
    fun eachCapabilityIsWrittenOnceWhileTheSessionRuns() =
        runTest {
            val recorder = CapabilityRecorder(capability) { records += it }
            recorder.start(backgroundScope)
            capability.value = HELLO
            runCurrent()
            capability.value = UPDATE
            runCurrent()
            recorder.finish()
            assertEquals(listOf(HELLO, UPDATE), records)
        }

    @Test
    fun aSessionWithoutCapabilityWritesNothing() =
        runTest {
            val recorder = CapabilityRecorder(capability) { records += it }
            recorder.start(backgroundScope)
            recorder.finish()
            assertTrue(records.isEmpty())
        }

    private companion object {
        const val WRITE_MILLIS = 50L

        val HELLO =
            CapabilityData(1, "1.0.0 (100)", "ios", "18.0", "iPhone16,1", CapabilityFeatures(sms = SmsFeature(true)))
        val UPDATE = HELLO.copy(features = CapabilityFeatures(sms = SmsFeature(false)))
    }
}
