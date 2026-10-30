package app.handlive.android.core.protocol.bluetooth

/** Thân bản ghi pre-auth, plaintext, byte đầu = kind (0.4.5 "Link records" bảng). */
object BtPreauthRecord {
    const val KIND_CHALLENGE = 0x01
    const val KIND_NOISE1 = 0x02
    const val KIND_NOISE2 = 0x03
    const val KIND_CPACE1 = 0x04
    const val KIND_CPACE2 = 0x05
    const val KIND_REJECT = 0x7F
    private const val BYTE_MASK = 0xFF

    class Decoded(
        val kind: Int,
        val payload: ByteArray,
    )

    fun encode(
        kind: Int,
        payload: ByteArray = ByteArray(0),
    ): ByteArray {
        val body = ByteArray(1 + payload.size)
        body[0] = kind.toByte()
        payload.copyInto(body, destinationOffset = 1)
        return body
    }

    /** [body] đã qua [BtRecordCodec.requireWithinBound]; thân rỗng (không cả byte kind) → đóng kênh. */
    fun decode(body: ByteArray): Decoded {
        if (body.isEmpty()) throw BtLinkException("empty pre-auth record")
        return Decoded(body[0].toInt() and BYTE_MASK, body.copyOfRange(1, body.size))
    }
}
