package app.handlive.android.feature.connection.discovery

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** CONN-01 API 1: TXT changes re-register at most once a second; failures retry after 5 s, 30 s, then 5 min. */
@OptIn(ExperimentalCoroutinesApi::class)
class MdnsAdvertiserTest {
    private class FakeRegistrar(
        var succeed: Boolean = true,
    ) : MdnsRegistrar {
        val calls = mutableListOf<String>()
        val registered = mutableListOf<MdnsRegistration>()

        override suspend fun register(registration: MdnsRegistration): Boolean {
            calls += "register"
            if (succeed) registered += registration
            return succeed
        }

        override suspend fun unregister() {
            calls += "unregister"
        }
    }

    private fun registration(hints: List<String>) = MdnsRegistration("HL-4f9a2c", 47800, MdnsTxtRecord(hints))

    @Test
    fun txtWithoutPairsCarriesOnlyTheVersionAndPairingKeysOnlyDuringAWindow() {
        assertEquals(mapOf("v" to "1"), MdnsTxtRecord().attributes())
        assertEquals(
            mapOf("v" to "1", "h" to "1a2b3c4d,77e0aa19", "pr" to "0badf00d"),
            MdnsTxtRecord(listOf("1a2b3c4d", "77e0aa19"), pairingRequest = "0badf00d").attributes(),
        )
        assertEquals(mapOf("v" to "1", "pm" to "1"), MdnsTxtRecord(pinMode = true).attributes())
    }

    @Test
    fun changesWithinASecondAreCoalescedIntoOneReRegistration() =
        runTest {
            val registrar = FakeRegistrar()
            val advertiser = advertiser(registrar)
            advertiser.advertise(registration(emptyList()))
            runCurrent()
            assertEquals(listOf("register"), registrar.calls)

            advertiser.advertise(registration(listOf("11111111")))
            advertiser.advertise(registration(listOf("22222222")))
            advanceTimeBy(500)
            assertEquals(1, registrar.registered.size)
            settle()
            assertEquals(listOf("register", "unregister", "register"), registrar.calls)
            assertEquals(
                listOf("22222222"),
                registrar.registered
                    .last()
                    .txt.hints,
            )
        }

    @Test
    fun failedRegistrationIsRetriedAfter5sThen30sThenEvery5Minutes() =
        runTest {
            val registrar = FakeRegistrar(succeed = false)
            advertiser(registrar).advertise(registration(emptyList()))
            runCurrent()
            assertEquals(1, registrar.calls.size)
            advanceTimeBy(5_001)
            assertEquals(2, registrar.calls.size)
            advanceTimeBy(30_001)
            assertEquals(3, registrar.calls.size)
            advanceTimeBy(300_001)
            assertEquals(4, registrar.calls.size)
            registrar.succeed = true
            advanceTimeBy(300_001)
            assertEquals(5, registrar.calls.size)
            assertEquals(1, registrar.registered.size)
        }

    @Test
    fun anUnchangedRegistrationIsNotRegisteredTwice() =
        runTest {
            val registrar = FakeRegistrar()
            val advertiser = advertiser(registrar)
            advertiser.advertise(registration(listOf("11111111")))
            settle()
            advertiser.advertise(registration(listOf("11111111")))
            settle()
            assertEquals(listOf("register"), registrar.calls)
            // A new network generation forces a fresh registration.
            advertiser.advertise(registration(listOf("11111111")).copy(networkGeneration = 1))
            settle()
            assertEquals(listOf("register", "unregister", "register"), registrar.calls)
        }

    /** The advertiser runs in `backgroundScope`, which `advanceUntilIdle` ignores: advance past the 1 s gap instead. */
    private fun TestScope.settle() {
        advanceTimeBy(SETTLE_MILLIS)
        runCurrent()
    }

    private fun TestScope.advertiser(registrar: MdnsRegistrar) =
        MdnsAdvertiser(registrar, backgroundScope, clock = { testScheduler.currentTime })

    private companion object {
        const val SETTLE_MILLIS = 2_000L
    }
}
