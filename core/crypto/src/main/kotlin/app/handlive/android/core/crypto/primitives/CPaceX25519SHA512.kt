@file:OptIn(ExperimentalUnsignedTypes::class)

package app.handlive.android.core.crypto.primitives

import java.security.GeneralSecurityException
import java.security.MessageDigest

/**
 * CPACE-X25519-SHA512 (draft-irtf-cfrg-cpace-21 Appendix B.1, suite `G_X25519`): [calculateGenerator] (Elligator2
 * qua [Field25519], RFC 9380), [scalarMultVfy] (X25519 với từ chối điểm bậc thấp) và [isk] (section 7.2) — ghép
 * đôi bằng PIN qua Bluetooth không mạng (00-common-specs.md 0.4.5 "PIN (PAIR-01 luồng C)"), để PIN không bao
 * giờ đi qua sóng. Chỉ cài transcript khởi tạo/trả lời ([transcriptIR]): HandLive luôn có vai cố định (C = M-APP
 * gửi `cpace1` trước, S = A-SVC trả lời bằng `cpace2`), không bao giờ dùng biến thể song hành (`transcript_oc`)
 * của draft. Số học trường: [Field25519].
 */
object CPaceX25519SHA512 {
    private val dsi = "CPace255".toByteArray(Charsets.US_ASCII)
    private val dsiIsk = dsi + "_ISK".toByteArray(Charsets.US_ASCII)

    /** Kích thước khối nhập của SHA-512 — [generatorString] đệm 0 tới đây để thời gian băm không phụ thuộc độ
     * dài thật của `prs` (một PIN bí mật). */
    private const val HASH_INPUT_BLOCK_BYTES = 128

    /** LEB128 độ dài ‖ data (draft Appendix A.1.1). */
    private fun prependLength(data: ByteArray): ByteArray {
        val encoded = ArrayList<Byte>(data.size / 128 + 1)
        var length = data.size
        while (true) {
            encoded.add(if (length < 0x80) length.toByte() else ((length and 0x7F) or 0x80).toByte())
            length = length ushr 7
            if (length == 0) break
        }
        return encoded.toByteArray() + data
    }

    /** "length-value": nối các phần, mỗi phần có độ dài LEB128 đặt trước (draft Appendix A.1.3). */
    private fun lengthValueConcat(vararg parts: ByteArray): ByteArray =
        parts.fold(ByteArray(0)) { acc, part -> acc + prependLength(part) }

    /**
     * Draft Appendix A.2: `lv_cat(DSI, PRS, zero_bytes(len_zpad), CI, sid)` — đệm 0 để `DSI ‖ PRS` lấp đầy khối
     * băm đầu tiên, làm số lần gọi hàm nén của SHA-512 (và thời gian) không phụ thuộc độ dài thật của `prs`.
     */
    internal fun generatorString(
        prs: ByteArray,
        ci: ByteArray,
        sid: ByteArray,
    ): ByteArray {
        val lenZpad = maxOf(0, HASH_INPUT_BLOCK_BYTES - 1 - prependLength(prs).size - prependLength(dsi).size)
        return lengthValueConcat(dsi, prs, ByteArray(lenZpad), ci, sid)
    }

    /** RFC 7748 `decodeUCoordinate` cho trường 255-bit (draft Appendix A.4): che bit cao nhất của byte cuối (32
     * byte little-endian) rồi dựng [Field25519]. */
    private fun decodeUCoordinate(bytes: ByteArray): Field25519 {
        val masked = bytes.copyOf()
        masked[Field25519.FIELD_SIZE_BYTES - 1] = (masked[Field25519.FIELD_SIZE_BYTES - 1].toInt() and 0x7F).toByte()
        return Field25519.fromLittleEndianBytes(masked)
    }

    /**
     * Draft Appendix A.5 (`map_to_curve_elligator2`), chuyên biệt cho Curve25519 (`A = 486662`, `Z = 2`, kết quả
     * `find_z_ell2` của đường cong này, RFC 9380) — ánh xạ một phần tử trường thẳng sang tọa độ Montgomery `u`,
     * cách CPace rút gọn vì chỉ cần `u`, không cần điểm đầy đủ. `epsilon` (ký hiệu Legendre) đã là `1`/`q−1`
     * (≡`-1`)/`0` của chính trường, nên dùng trực tiếp như một giá trị trường ở bước kết hợp dưới — không nhánh
     * theo nó, khác một bản chuyển thẳng pseudocode của draft.
     */
    private fun elligator2(r: Field25519): Field25519 {
        val a = Field25519(UIntArray(Field25519.LIMB_COUNT).also { it[0] = 486_662u })
        val z = Field25519(UIntArray(Field25519.LIMB_COUNT).also { it[0] = 2u })
        val denominator = Field25519.one + (z * r * r)
        val v = Field25519.zero - (a * denominator.inverted())
        val rhs = (v * v * v) + (a * v * v) + v
        val epsilon = rhs.legendreSymbol()
        val oneMinusEpsilon = Field25519.one - epsilon
        return (epsilon * v) - (oneMinusEpsilon * a * Field25519.inverseOfTwo)
    }

    /**
     * `G.calculate_generator(SHA-512, PRS, CI, sid)` của suite `G_X25519` (0.4.5: `g = calculate_generator(...)`,
     * draft §3.2.1). `prs`/`ci`/`sid` không có độ dài cố định trong draft — vector chính thức Appendix B.1 dùng
     * `sid` 16 byte; cách dùng của HandLive (0.4.5 "PIN") luôn truyền `n_s` 32 byte.
     */
    fun calculateGenerator(
        prs: ByteArray,
        ci: ByteArray,
        sid: ByteArray,
    ): ByteArray {
        val hash = MessageDigest.getInstance("SHA-512").digest(generatorString(prs, ci, sid))
        val r = decodeUCoordinate(hash.copyOf(Field25519.FIELD_SIZE_BYTES))
        return elligator2(r).toLittleEndianBytes()
    }

    /**
     * `G_X25519.scalar_mult_vfy = X25519(scalar, point)`; `null` đúng ở điều kiện "G.I — abort" của draft
     * (`point` là điểm bậc thấp, nên bí mật chung sẽ toàn 0 — 0.4.5: "Một `K` toàn 0 → đóng kênh không trả
     * lời"). [X25519Keys.sharedSecret] (Tink) đã ném đúng ở trường hợp đó (đối chiếu với các điểm bậc thấp của
     * draft qua vector `low_order_ya`/`low_order_yb` của `cpace.json`), nên chỉ cần bắt lỗi của nó — không cần
     * tự viết lại phép kiểm điểm bậc thấp ở đây.
     */
    fun scalarMultVfy(
        scalar: ByteArray,
        point: ByteArray,
    ): ByteArray? =
        try {
            X25519Keys.sharedSecret(scalar, point)
        } catch (_: GeneralSecurityException) {
            null
        }

    /** Draft Appendix A.3.4, thứ tự khởi tạo/trả lời (`transcript_ir`) — cố định, vì C của HandLive luôn khởi
     * tạo và S luôn trả lời (0.4.5 "PIN"). */
    fun transcriptIR(
        ya: ByteArray,
        ada: ByteArray,
        yb: ByteArray,
        adb: ByteArray,
    ): ByteArray = lengthValueConcat(ya, ada) + lengthValueConcat(yb, adb)

    /**
     * `ISK = SHA-512(lv_cat(DSI ‖ "_ISK", sid, K) ‖ transcript_ir(Ya, ADa, Yb, ADb))` (draft section 7.2). 6
     * tham số là đúng chữ ký ISK của draft (sid, K, Ya, ADa, Yb, ADb); `k` dưới khớp `K` của draft.
     */
    fun isk(
        sid: ByteArray,
        k: ByteArray,
        ya: ByteArray,
        ada: ByteArray,
        yb: ByteArray,
        adb: ByteArray,
    ): ByteArray {
        val message = lengthValueConcat(dsiIsk, sid, k) + transcriptIR(ya, ada, yb, adb)
        return MessageDigest.getInstance("SHA-512").digest(message)
    }
}
