package app.handlive.android.feature.call.log

import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.encoding.Base64Codecs
import kotlinx.serialization.Serializable

/** The `log_sync` cursor (CALL-04 API 1 logic 2): b64u of `{"v":1,"id":<largest _ID covered>}`, opaque to clients. */
@Serializable
data class CallLogCursor(
    val v: Int,
    val id: Long,
) {
    fun encode(): String =
        Base64Codecs.encodeB64u(ProtocolJson.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8))

    companion object {
        const val VERSION = 1

        fun at(id: Long) = CallLogCursor(VERSION, id)

        /** `null` for a cursor that cannot be read or has another version (E5: a first sync with `reset`). */
        fun decode(text: String): CallLogCursor? =
            runCatching {
                ProtocolJson.decodeFromString(serializer(), Base64Codecs.decodeB64u(text).toString(Charsets.UTF_8))
            }.getOrNull()?.takeIf { it.v == VERSION && it.id >= 0 }
    }
}
