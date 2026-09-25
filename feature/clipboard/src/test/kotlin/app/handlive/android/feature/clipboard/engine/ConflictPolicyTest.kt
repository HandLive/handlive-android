package app.handlive.android.feature.clipboard.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** QC8: the 500 ms window on the receiver's clock, then `origin_ts` and `origin_device_id`; both sides converge. */
class ConflictPolicyTest {
    private val now = 1_727_150_000_000L
    private val mac = "5b1f8c2e-9a4d-8e6f-a1b2-c3d4e5f60718"
    private val phone = "8c7d6e5f-4a3b-8c2d-9e1f-0a1b2c3d4e5f"

    private fun local(
        changedAgo: Long,
        originTs: Long = now - changedAgo,
        acknowledgedBy: Set<String> = emptySet(),
    ) = LocalChange("local", now - changedAgo, originTs, phone, acknowledgedBy)

    private fun decide(
        local: LocalChange?,
        originTs: Long = now,
        origin: String = mac,
    ) = ConflictPolicy.decide(originTs, origin, "pair-mac", local, now)

    @Test
    fun noUnacknowledgedLocalChangeApplies() {
        assertEquals(ConflictDecision.APPLY, decide(null))
        assertEquals(ConflictDecision.APPLY, decide(local(changedAgo = 100, acknowledgedBy = setOf("pair-mac"))))
    }

    @Test
    fun aChangeWithinTheWindowKeepsTheLocalContentAndNotifies() {
        assertEquals(ConflictDecision.KEEP_LOCAL_AND_NOTIFY, decide(local(changedAgo = 0)))
        assertEquals(ConflictDecision.KEEP_LOCAL_AND_NOTIFY, decide(local(changedAgo = ConflictPolicy.WINDOW_MILLIS)))
    }

    @Test
    fun crossingClipsKeepTheLargerOriginTsThenTheLargerDeviceId() {
        val older = local(changedAgo = 2_000, originTs = now - 2_000)
        assertEquals(ConflictDecision.APPLY, decide(older, originTs = now - 1_000))
        assertEquals(ConflictDecision.KEEP_LOCAL_SILENTLY, decide(older, originTs = now - 3_000))
        // Equal origin_ts: "8c7d…" (phone) > "5b1f…" (Mac), so the phone's clip wins on both sides.
        assertEquals(ConflictDecision.KEEP_LOCAL_SILENTLY, decide(older, originTs = now - 2_000, origin = mac))
        val macLocal = LocalChange("local", now - 2_000, now - 2_000, mac, emptySet())
        assertEquals(ConflictDecision.APPLY, ConflictPolicy.decide(now - 2_000, phone, "pair-phone", macLocal, now))
    }
}
