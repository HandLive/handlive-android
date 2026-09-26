package app.handlive.android.core.crypto

import app.handlive.android.core.crypto.derivation.DeviceIdDerivation
import app.handlive.android.core.crypto.identity.DeviceIdentityStore
import app.handlive.android.core.crypto.keystore.AeadSecretSealer
import app.handlive.android.core.crypto.keystore.AeadSecretStore
import app.handlive.android.core.crypto.keystore.SecretBlobBackend
import app.handlive.android.core.crypto.primitives.Ed25519Keys
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException

/** SET-01 API 1: identity keys are created once and reloaded; `device_id` derives from `ik_sig_pub` (0.2). */
class DeviceIdentityStoreTest {
    private val backend = MemoryBackend()

    @Test
    fun identityIsCreatedOnceAndReloadedUnchanged() {
        val store = AeadSecretStore(newAead(), backend)
        val first = DeviceIdentityStore.loadOrCreate(store)
        val second = DeviceIdentityStore.loadOrCreate(store)
        assertEquals(first.deviceId, second.deviceId)
        assertArrayEquals(first.dhPublicKey, second.dhPublicKey)
        assertEquals(DeviceIdDerivation.deviceId(first.signingPublicKey), first.deviceId)
        val message = "HLPAIR1".toByteArray()
        assertTrue(Ed25519Keys.verify(first.signingPublicKey, message, second.sign(message)))
    }

    @Test
    fun keysSealedUnderALostMasterKeyAreReplaced() {
        val lost = DeviceIdentityStore.loadOrCreate(AeadSecretStore(newAead(), backend))
        val replaced = DeviceIdentityStore.loadOrCreate(AeadSecretStore(newAead(), backend))
        assertNotEquals(lost.deviceId, replaced.deviceId)
    }

    @Test
    fun sealedValueIsBoundToItsContext() {
        val sealer = AeadSecretSealer(newAead())
        val sealed = sealer.seal(ByteArray(32) { 9 }, "prk/a")
        assertArrayEquals(ByteArray(32) { 9 }, sealer.open(sealed, "prk/a"))
        assertThrows(GeneralSecurityException::class.java) { sealer.open(sealed, "prk/b") }
    }

    private class MemoryBackend : SecretBlobBackend {
        private val blobs = HashMap<String, ByteArray>()

        override fun read(name: String): ByteArray? = blobs[name]

        override fun write(
            name: String,
            blob: ByteArray,
        ) {
            blobs[name] = blob
        }

        override fun remove(name: String) {
            blobs.remove(name)
        }
    }

    private companion object {
        fun newAead(): Aead {
            AeadConfig.register()
            return KeysetHandle
                .generateNew(PredefinedAeadParameters.AES256_GCM)
                .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        }
    }
}
