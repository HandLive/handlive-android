package app.handlive.android.core.data

import android.content.Context
import app.handlive.android.core.crypto.identity.DeviceIdentity
import app.handlive.android.core.crypto.identity.DeviceIdentityStore
import app.handlive.android.core.crypto.keystore.AndroidKeystoreSecretStore
import app.handlive.android.core.crypto.keystore.SecretStore
import app.handlive.android.core.data.db.HandLiveDatabase
import app.handlive.android.core.data.db.PushOutboxDao
import app.handlive.android.core.data.db.SmsObserverStateDao
import app.handlive.android.core.data.pairing.PairStore
import app.handlive.android.core.data.pairing.RelayPairs
import app.handlive.android.core.data.settings.SettingsStore

/**
 * Process-wide data singletons shared by the service, the UI and the clipboard components (one Room instance,
 * one DataStore per file). Members backed by the Android Keystore ([secrets], [identity], the sealer inside
 * [pairs]) are created on first use, which must happen off the main thread.
 */
class HandLiveData private constructor(
    context: Context,
) {
    val database: HandLiveDatabase = HandLiveDatabase.open(context)
    val settings: SettingsStore = SettingsStore.create(context)
    val secrets: SecretStore by lazy { AndroidKeystoreSecretStore.create(context) }
    val pairs: PairStore = PairStore(database.pairedDevices(), { AndroidKeystoreSecretStore.sealer(context) })

    /** `sms_observer_state` (SMS-02): the last SMS `_id` the observer processed. */
    val smsObserverState: SmsObserverStateDao get() = database.smsObserverState()

    /** `push_outbox` (CONN-04): pushes waiting for a retry. */
    val pushOutbox: PushOutboxDao get() = database.pushOutbox()

    /** The same pairs as the relay sees them: registration, tombstones, push targets (Phase 2). */
    val relayPairs: RelayPairs = RelayPairs(database.pairedDevices(), { AndroidKeystoreSecretStore.sealer(context) })

    /** `ik_sig`, `ik_dh` and `device_id` (SET-01 step 2); loads or creates the keys on first access. */
    val identity: DeviceIdentity by lazy { DeviceIdentityStore.loadOrCreate(secrets) }

    companion object {
        @Volatile
        private var instance: HandLiveData? = null

        fun get(context: Context): HandLiveData =
            instance ?: synchronized(this) {
                instance ?: HandLiveData(context.applicationContext).also { instance = it }
            }
    }
}
