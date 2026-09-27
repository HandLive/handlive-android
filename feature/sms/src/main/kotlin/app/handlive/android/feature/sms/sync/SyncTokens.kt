package app.handlive.android.feature.sms.sync

import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.encoding.Base64Codecs
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * The sync `cursor` (SMS-01 API 1 logic 2): b64u of `{"v":1,"id":<largest _id covered>,"t":<largest date
 * covered>}`. Opaque to the client.
 */
@Serializable
data class SyncCursor(
    val v: Int,
    val id: Long,
    val t: Long,
) {
    constructor(id: Long, t: Long) : this(VERSION, id, t)

    companion object {
        const val VERSION = 1
    }
}

/**
 * The stateless `page_token` (SMS-01 API 1 logic 4): the first sync carries `m` (snapshot mark), `th` (the
 * conversations left, the first one partly sent) and `o` (messages of `th[0]` already sent); a catch-up sync carries
 * `m` and `a` (the last `_id` sent).
 */
@Serializable
data class PageToken(
    val v: Int,
    val m: Long,
    val th: List<Long>? = null,
    val o: Int? = null,
    val a: Long? = null,
) {
    val isFirstSync: Boolean get() = th != null && o != null && a == null
    val isCatchUp: Boolean get() = a != null && th == null && o == null

    companion object {
        fun firstSync(
            snap: Long,
            threads: List<Long>,
            offset: Int,
        ) = PageToken(SyncCursor.VERSION, snap, th = threads, o = offset)

        fun catchUp(
            snap: Long,
            after: Long,
        ) = PageToken(SyncCursor.VERSION, snap, a = after)
    }
}

/** b64u JSON codec of [SyncCursor] and [PageToken]; `null` when a value cannot be read (`SMS_CURSOR_INVALID`). */
object SyncTokens {
    fun encode(cursor: SyncCursor): String = encode(SyncCursor.serializer(), cursor)

    fun encode(token: PageToken): String = encode(PageToken.serializer(), token)

    fun decodeCursor(text: String): SyncCursor? =
        decode(text, SyncCursor.serializer())?.takeIf { it.v == SyncCursor.VERSION && it.id >= 0 && it.t >= 0 }

    /** A token of the other kind than the request ([catchUp]) is as unreadable as a malformed one. */
    fun decodePageToken(
        text: String,
        catchUp: Boolean,
    ): PageToken? =
        decode(text, PageToken.serializer())?.takeIf { token ->
            token.v == SyncCursor.VERSION && token.m >= 0 &&
                if (catchUp) {
                    token.isCatchUp && checkNotNull(token.a) >= 0
                } else {
                    token.isFirstSync && checkNotNull(token.o) >= 0
                }
        }

    private fun <T> encode(
        serializer: KSerializer<T>,
        value: T,
    ): String = Base64Codecs.encodeB64u(ProtocolJson.encodeToString(serializer, value).toByteArray(Charsets.UTF_8))

    private fun <T> decode(
        text: String,
        serializer: KSerializer<T>,
    ): T? =
        runCatching {
            ProtocolJson.decodeFromString(serializer, Base64Codecs.decodeB64u(text).toString(Charsets.UTF_8))
        }.getOrNull()
}
