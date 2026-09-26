package app.handlive.android.feature.sms.provider

import app.handlive.android.core.protocol.sms.SmsBox
import app.handlive.android.core.protocol.sms.SmsMessageData
import app.handlive.android.core.protocol.sms.SmsThreadData
import app.handlive.android.core.protocol.sms.SmsUnreadEntry
import app.handlive.android.feature.sms.SmsConstants

/**
 * Builds the `message` and `thread` objects of 5.1.5 from provider rows, for one sync page, one history page or one
 * observer round ([of]). Contact names are looked up once per address and cached for the scope (SMS-01 API 1
 * logic 9); canonical addresses are read for the conversations the scope needs and cached; the unread messages are
 * read once per scope, when first needed.
 */
class SmsObjects private constructor(
    private val provider: SmsProvider,
    private val numbers: AddressNormalizer,
    private val contacts: ContactNames?,
    private val countryIso: String?,
) {
    private val names = LruCache<String, String?>(SmsConstants.CONTACT_CACHE_SIZE)
    private val canonical = HashMap<Long, String?>()
    private val unreadRows by lazy { provider.unreadInbox() }

    /** Unread inbox messages per conversation, from one query (SMS-01 step 5). */
    val unreadCounts: Map<Long, Int> by lazy { unreadRows.groupingBy { it.threadId }.eachCount() }

    /** `unread` of the last sync page and the SMS-05 snapshot: every conversation with unread inbox messages. */
    val unreadEntries: List<SmsUnreadEntry> by lazy { unreadEntries(unreadRows) }

    /** The `message` object; `null` for drafts and unknown types, which never leave the phone. */
    fun message(
        row: SmsRow,
        localId: String? = null,
    ): SmsMessageData? {
        val box = boxOf(row.type) ?: return null
        return SmsMessageData(
            messageKey = messageKey(row.id),
            threadId = row.threadId,
            address = numbers.e164OrSelf(row.address.orEmpty(), countryIso),
            body = row.body.orEmpty(),
            box = box,
            ts = row.date,
            tsSent = row.dateSent.takeIf { it > 0 },
            read = row.read,
            subId = row.subId.takeIf { it >= 0 },
            localId = localId,
        )
    }

    /**
     * The `thread` object, recomputed from the provider (SMS-01 API 1 logic 6): addresses from the conversation's
     * `recipient_ids`, names from the contacts, the newest SMS as snippet and `last_ts`, the unread inbox count.
     * [conversation] is passed when already read (first sync); `null` for a conversation with no SMS at all.
     */
    fun thread(
        threadId: Long,
        conversation: ConversationRow? = null,
    ): SmsThreadData? {
        val newest = provider.newestMessage(threadId) ?: return null
        val recipients = (conversation ?: provider.conversations(listOf(threadId))[threadId])?.recipientIds.orEmpty()
        val raw = addressesOf(recipients).ifEmpty { listOfNotNull(newest.address) }
        val addresses = raw.map { numbers.e164OrSelf(it, countryIso) }
        return SmsThreadData(
            threadId = threadId,
            addresses = addresses,
            displayName = displayName(addresses),
            snippet = newest.body.orEmpty().takeCodePoints(SmsConstants.SNIPPET_MAX),
            lastTs = newest.date,
            unreadCount = unreadCounts[threadId] ?: 0,
        )
    }

    /** Reads the canonical addresses of many conversations at once (a first sync page lists up to 500). */
    fun prefetchAddresses(conversations: Collection<ConversationRow>) {
        addressesOf(conversations.flatMap { it.recipientIds })
    }

    private fun addressesOf(ids: List<Long>): List<String> {
        val missing = ids.filterNot { it in canonical }.distinct()
        if (missing.isNotEmpty()) {
            val found = provider.canonicalAddresses(missing)
            missing.forEach { canonical[it] = found[it] }
        }
        return ids.mapNotNull { canonical[it] }
    }

    /**
     * 5.1.5 `display_name`: the contact name; several addresses → the names joined with ", " (an address that is not a
     * contact appears as its number); `null` when no address is a contact or `READ_CONTACTS` is missing (E3).
     */
    private fun displayName(addresses: List<String>): String? {
        val found =
            contacts?.let { lookup ->
                addresses.map { address -> names.getOrPut(address) { lookup.lookup(address) } }
            }
        return found
            ?.takeIf { names -> names.any { it != null } }
            ?.let { names -> addresses.zip(names).joinToString(", ") { (address, name) -> name ?: address } }
    }

    companion object {
        /** A scope reads the canonical addresses and the unread messages at most once. */
        fun of(
            provider: SmsProvider,
            numbers: AddressNormalizer,
            contacts: ContactNames?,
            countryIso: String?,
        ) = SmsObjects(provider, numbers, contacts, countryIso)

        /** `message_key` = `sms:<_id>` (0.2). */
        fun messageKey(id: Long): String = "sms:$id"

        /** `box` from the `type` column (5.1.5): 1 inbox, 2 sent, 4 outbox, 5 failed, 6 queued; 3 (draft) never. */
        fun boxOf(type: Int): String? =
            when (type) {
                SmsType.INBOX -> SmsBox.INBOX
                SmsType.SENT -> SmsBox.SENT
                SmsType.OUTBOX -> SmsBox.OUTBOX
                SmsType.FAILED -> SmsBox.FAILED
                SmsType.QUEUED -> SmsBox.QUEUED
                else -> null
            }

        /**
         * Per conversation: the unread count and `read_up_to_ts` = the date of the oldest unread message minus 1
         * (SMS-05 API 1), ordered by `thread_id`.
         */
        fun unreadEntries(rows: List<UnreadRow>): List<SmsUnreadEntry> =
            rows
                .groupBy { it.threadId }
                .toSortedMap()
                .map { (threadId, unread) -> SmsUnreadEntry(threadId, unread.size, unread.minOf { it.date } - 1) }
    }
}

/** The first [max] characters (code points) of this string, never splitting a surrogate pair. */
fun String.takeCodePoints(max: Int): String =
    if (codePointCount(0, length) <= max) this else substring(0, offsetByCodePoints(0, max))

/** A small access-ordered LRU map; not thread-safe (one per scope). */
private class LruCache<K, V>(
    private val capacity: Int,
) {
    private val map =
        object : LinkedHashMap<K, V>(INITIAL, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > capacity
        }

    fun getOrPut(
        key: K,
        compute: () -> V,
    ): V {
        if (map.containsKey(key)) {
            @Suppress("UNCHECKED_CAST")
            return map[key] as V
        }
        return compute().also { map[key] = it }
    }

    private companion object {
        const val INITIAL = 16
        const val LOAD_FACTOR = 0.75f
    }
}
