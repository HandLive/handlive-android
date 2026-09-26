package app.handlive.android.feature.sms.testing

import app.handlive.android.feature.sms.provider.AddressNormalizer
import app.handlive.android.feature.sms.provider.ContactNames
import app.handlive.android.feature.sms.provider.ConversationRow
import app.handlive.android.feature.sms.provider.SmsObjects
import app.handlive.android.feature.sms.provider.SmsProvider
import app.handlive.android.feature.sms.provider.SmsRow
import app.handlive.android.feature.sms.provider.SmsType
import app.handlive.android.feature.sms.provider.UnreadRow

const val BASE_TS = 1_727_150_000_000L

/**
 * The Telephony provider in memory, with the semantics of the 05-sms queries: `content://sms` rows, the `threads`
 * table of `conversations?simple=true` and `canonical-addresses`. [queries] counts calls, [failing] throws like a
 * provider that cannot be read.
 */
class FakeSmsProvider : SmsProvider {
    val sms = mutableListOf<SmsRow>()
    val threads = mutableMapOf<Long, ConversationRow>()
    val canonical = mutableMapOf<Long, String>()
    var failing = false
    var queries = 0
    private var nextId = 1L
    private var nextAddressId = 1L

    /** A conversation with [addresses]; its `date` follows its newest message unless [date] is given. */
    fun conversation(
        threadId: Long,
        vararg addresses: String,
        date: Long? = null,
    ) {
        val ids =
            addresses.map { address ->
                canonical.entries.firstOrNull { it.value == address }?.key
                    ?: nextAddressId++.also { canonical[it] = address }
            }
        threads[threadId] = ConversationRow(threadId, date ?: 0, ids)
    }

    /** Adds an SMS with the next `_id` and moves its conversation's date forward. */
    fun add(
        threadId: Long,
        date: Long,
        type: Int = SmsType.INBOX,
        body: String = "tin $date",
        read: Boolean = true,
    ): SmsRow {
        val from = threads[threadId]?.recipientIds?.firstOrNull()?.let { canonical[it] } ?: "+84900000000"
        return put(SmsRow(nextId, threadId, from, body, type, date, 0, read, 1))
    }

    /** Stores [row] as it is (its own `_id`, address, `date_sent`, `sub_id`). */
    fun put(row: SmsRow): SmsRow {
        nextId = maxOf(nextId, row.id + 1)
        sms.removeAll { it.id == row.id }
        sms += row
        threads[row.threadId]?.let { if (it.date < row.date) threads[row.threadId] = it.copy(date = row.date) }
        return row
    }

    fun update(
        id: Long,
        change: (SmsRow) -> SmsRow,
    ) {
        val index = sms.indexOfFirst { it.id == id }
        sms[index] = change(sms[index])
    }

    fun delete(id: Long) {
        sms.removeAll { it.id == id }
    }

    private fun synced() = sms.filter { it.type in SmsType.SYNCED }

    private fun tick() {
        queries++
        check(!failing) { "provider unavailable" }
    }

    override fun maxSmsId(): Long? = tick().let { sms.maxOfOrNull { it.id } }

    override fun conversationsNewestFirst(limit: Int): List<ConversationRow> =
        tick().let { threads.values.sortedByDescending { it.date }.take(limit) }

    override fun conversations(threadIds: Collection<Long>): Map<Long, ConversationRow> =
        tick().let { threads.filterKeys { it in threadIds } }

    override fun conversationExists(threadId: Long): Boolean = tick().let { threadId in threads }

    override fun canonicalAddresses(ids: Collection<Long>): Map<Long, String> =
        tick().let { canonical.filterKeys { it in ids } }

    override fun threadMessages(
        threadId: Long,
        snap: Long,
        skip: Int,
        limit: Int,
    ): List<SmsRow> =
        tick().let {
            synced()
                .filter { it.threadId == threadId && it.id <= snap }
                .sortedWith(compareByDescending<SmsRow> { it.date }.thenByDescending { it.id })
                .drop(skip)
                .take(limit)
        }

    override fun messagesSince(
        cursorId: Long,
        cursorDate: Long,
        afterId: Long,
        snap: Long,
        limit: Int,
    ): List<SmsRow> =
        tick().let {
            synced()
                .filter { (it.id > cursorId || it.date > cursorDate) && it.id > afterId && it.id <= snap }
                .sortedBy { it.id }
                .take(limit)
        }

    override fun maxDate(
        snap: Long,
        since: Pair<Long, Long>?,
    ): Long? =
        tick().let {
            synced()
                .filter { it.id <= snap && (since == null || it.id > since.first || it.date > since.second) }
                .maxOfOrNull { it.date }
        }

    override fun newestMessage(threadId: Long): SmsRow? =
        tick().let {
            synced()
                .filter { it.threadId == threadId }
                .maxWithOrNull(compareBy<SmsRow> { it.date }.thenBy { it.id })
        }

    override fun unreadInbox(): List<UnreadRow> =
        tick().let { sms.filter { it.type == SmsType.INBOX && !it.read }.map { UnreadRow(it.threadId, it.date) } }

    override fun <T> olderMessages(
        threadId: Long,
        beforeTs: Long,
        read: (Sequence<SmsRow>) -> T,
    ): T {
        tick()
        val rows =
            synced()
                .filter { it.threadId == threadId && it.date < beforeTs }
                .sortedWith(compareByDescending<SmsRow> { it.date }.thenByDescending { it.id })
        return read(rows.asSequence())
    }

    override fun rowsAfter(afterId: Long): List<SmsRow> =
        tick().let { sms.filter { it.id > afterId }.sortedBy { it.id } }

    override fun rowsById(ids: Collection<Long>): List<SmsRow> = tick().let { sms.filter { it.id in ids } }
}

/** Numbers of a Vietnamese SIM: `0…` → `+84…`, `+…` kept, letters (sender names) kept, short codes of 3–8 digits. */
class FakeNumbers : AddressNormalizer {
    override fun e164OrSelf(
        address: String,
        countryIso: String?,
    ): String = e164(address, countryIso) ?: address

    override fun recipient(
        address: String,
        countryIso: String?,
    ): String? = if (address.matches(Regex("^[0-9]{3,8}$"))) address else e164(address, countryIso)

    private fun e164(
        address: String,
        countryIso: String?,
    ): String? {
        val digits = address.filter { it.isDigit() || it == '+' }
        return when {
            address.any { it.isLetter() } -> null
            digits.startsWith("+") && digits.length in 9..16 -> digits
            countryIso == "VN" && digits.startsWith("0") && digits.length == 10 -> "+84" + digits.drop(1)
            else -> null
        }
    }
}

class FakeContacts(
    private val names: Map<String, String>,
) : ContactNames {
    var lookups = 0

    override fun lookup(address: String): String? {
        lookups++
        return names[address]
    }
}

/** A scope factory as `SmsFeature` builds it, with a Vietnamese SIM. */
fun objectsOf(
    provider: SmsProvider,
    contacts: ContactNames? = null,
    countryIso: String? = "VN",
): () -> SmsObjects = { SmsObjects.of(provider, FakeNumbers(), contacts, countryIso) }
