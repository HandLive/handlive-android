package app.handlive.android.core.protocol.relay

import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.id.UuidBytes
import app.handlive.android.core.protocol.protocolRequire

/**
 * The binary routing frame of the relay (0.4.3): `"HR"` (0x48 0x52) ‖ `ver` 0x01 ‖ `op` 0x01 (forward) ‖ the 16-byte
 * `device_id` — the destination in a frame a device sends, the source in a frame the relay delivers — ‖ the HL frame,
 * intact (0.5.2). Only the header is checked here; the HL frame is the stream channel's business.
 */
class RelayFrame(
    val deviceId: String,
    val inner: ByteArray,
) {
    fun encode(): ByteArray = header(deviceId) + inner

    companion object {
        const val MAGIC_0: Byte = 0x48
        const val MAGIC_1: Byte = 0x52
        const val VERSION: Byte = 0x01
        const val OP_FORWARD: Byte = 0x01
        const val HEADER_SIZE = 20

        fun header(deviceId: String): ByteArray =
            byteArrayOf(MAGIC_0, MAGIC_1, VERSION, OP_FORWARD) + UuidBytes.toBytes(deviceId)

        /** A frame the relay delivers; a wrong magic, version or op, or nothing after the header → `BAD_REQUEST`. */
        fun decode(frame: ByteArray): RelayFrame {
            protocolRequire(frame.size > HEADER_SIZE, ErrorCode.BAD_REQUEST, "HR frame too short")
            protocolRequire(frame[0] == MAGIC_0 && frame[1] == MAGIC_1, ErrorCode.BAD_REQUEST, "bad HR magic")
            protocolRequire(frame[2] == VERSION, ErrorCode.BAD_REQUEST, "unsupported HR version")
            protocolRequire(frame[3] == OP_FORWARD, ErrorCode.BAD_REQUEST, "unknown HR op")
            val deviceId = UuidBytes.fromBytes(frame.copyOfRange(4, HEADER_SIZE))
            return RelayFrame(deviceId, frame.copyOfRange(HEADER_SIZE, frame.size))
        }
    }
}
