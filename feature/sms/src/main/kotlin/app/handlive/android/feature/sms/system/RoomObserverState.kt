package app.handlive.android.feature.sms.system

import app.handlive.android.core.data.db.SmsObserverStateDao
import app.handlive.android.core.data.db.SmsObserverStateEntity
import app.handlive.android.feature.sms.observe.ObserverState

/** `sms_observer_state` in Room (0.9.1, SMS-02 step 12 Query). */
class RoomObserverState(
    private val dao: SmsObserverStateDao,
    private val clock: () -> Long,
) : ObserverState {
    override suspend fun lastSmsId(): Long? = dao.lastSmsId()

    override suspend fun write(lastSmsId: Long) =
        dao.write(SmsObserverStateEntity(lastSmsId = lastSmsId, updatedAt = clock()))
}
