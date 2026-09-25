package app.handlive.android.feature.pairing.invite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PAIR-01 API 1 and E1. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PairingInviteTest {
    @Test
    fun specExampleParsesWithItsRendezvous() {
        val invite = checkNotNull(PairingInvite.parse(EXAMPLE))
        assertEquals("MacBook của Lan", invite.clientName)
        assertEquals(32, invite.clientDhPublicKey.size)
        assertEquals(32, invite.pairingSecret.size)
        assertEquals(16, invite.rendezvous?.size)
        assertEquals(8, invite.pairingRequestHint.length)
    }

    @Test
    fun unknownParametersAreIgnored() {
        assertNotNull(PairingInvite.parse("$EXAMPLE&future=1"))
    }

    @Test
    fun wrongOrMissingPartsAreInvalid() {
        listOf(
            EXAMPLE.replace("v=1", "v=2"),
            EXAMPLE.replace("handlive://", "https://"),
            EXAMPLE.replace("//pair", "//other"),
            EXAMPLE.replace("&ps=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", ""),
            EXAMPLE.replace("pk=q83vEjRWeJC7zN3u_wARIjNEVWZ3iJmqu8zd7v8AESI", "pk=q83vEjRWeJC7zN3u"),
            EXAMPLE.replace("rv=Eh8kKS4zOD1CR0xRVltgZQ", "rv=Eh8k"),
            EXAMPLE.replace("d=MacBook%20c%E1%BB%A7a%20Lan", "d=" + "x".repeat(65)),
            "not a code",
        ).forEach { assertNull(it, PairingInvite.parse(it)) }
    }

    private companion object {
        const val EXAMPLE =
            "handlive://pair?v=1&pk=q83vEjRWeJC7zN3u_wARIjNEVWZ3iJmqu8zd7v8AESI" +
                "&ps=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8&d=MacBook%20c%E1%BB%A7a%20Lan" +
                "&rv=Eh8kKS4zOD1CR0xRVltgZQ"
    }
}
