@file:OptIn(ExperimentalUnsignedTypes::class)

package app.handlive.android.core.crypto.primitives

/** Khớp [Field25519.LIMB_COUNT] — tách riêng vì hằng số top-level private không đọc được hằng số companion. */
private const val LIMB_COUNT = 8
private const val LIMB_BITS = 32
private const val WORD_MASK = 0xFFFFFFFFuL

/** `2^256 ≡ 38 (mod q)` — hệ số dùng để gập phần vượt 256-bit của một tích/tổng thô vào phần thấp trước khi
 * rút gọn (xem [mulMod], [addMod]). */
private const val FOLD_CONSTANT = 38u

/** Số vòng cố định để [reduceBounded] đưa một giá trị tối đa vài lần q về canonical `[0, q)`. */
private const val REDUCE_ROUNDS = 4

/** Số vòng cố định để [mulMod] gập hết phần cao 256-bit của tích thô vào phần thấp. */
private const val MUL_FOLD_ROUNDS = 6

/**
 * GF(2^255 − 19), trường Curve25519 sống trong đó. Tink không có số học trường, nên [CPaceX25519SHA512]
 * (Elligator2, `calculate_generator`) cần tự viết nó. **Hằng thời gian để Trang soát**: mọi phép toán ở dưới
 * (từ [addRaw]/[subRaw] tới [power]) là một dãy cố định các phép cộng/nhân/mask bit — không `if`/`when` hay chỉ
 * số mảng theo một giá trị trường (ở đây có thể mang bí mật suy từ PIN), chỉ theo chỉ số vòng lặp hay một số mũ
 * công khai cố định ([inverseExponent]/[legendreExponent], luôn đúng 255 vòng mỗi lần gọi bất kể cơ số bí mật).
 * Rút gọn chọn giữa "trừ q" và "giữ nguyên" bằng mask ([select]), không bao giờ bằng nhánh. Các hằng số công
 * khai dựng trong [Companion] (q, [inverseExponent], [legendreExponent], [inverseOfTwo]) suy ra từ q — một số
 * nguyên tố công khai, không mang bí mật — nên các hàm dựng chúng một lần lúc nạp lớp ĐƯỢC PHÉP dùng số học
 * thường có nhánh; chúng tách riêng, không dùng lại cho giá trị trường bí mật.
 *
 * 8 limb 32-bit, little-endian (`limbs[0]` = bit 0-31), luôn giữ dạng canonical (< q) giữa các phép toán — limb
 * 32-bit (không phải 64-bit như bản Swift tương ứng, `Field25519.swift`) để mọi tích limb×limb nằm gọn trong
 * [ULong] (64-bit) không tràn, tránh phải tự viết phép nhân rộng 64×64→128-bit mà JVM không có sẵn ở minSdk 29
 * (`Math.multiplyHigh` chỉ có từ API 31).
 */
internal class Field25519(
    val limbs: UIntArray,
) {
    init {
        require(limbs.size == LIMB_COUNT) { "Field25519 cần đúng $LIMB_COUNT limb" }
    }

    operator fun plus(other: Field25519): Field25519 = Field25519(addMod(limbs, other.limbs))

    operator fun minus(other: Field25519): Field25519 = Field25519(subMod(limbs, other.limbs))

    operator fun times(other: Field25519): Field25519 = Field25519(mulMod(limbs, other.limbs))

    /**
     * Bình phương-và-nhân theo số mũ công khai CỐ ĐỊNH ([inverseExponent]/[legendreExponent]) — luôn đúng 255
     * vòng bình phương, mỗi vòng theo sau một phép nhân có-điều-kiện bằng mask (không nhánh), nên dãy phép toán
     * không bao giờ phụ thuộc vào `this` (cơ số bí mật).
     */
    private fun power(exponent: UIntArray): Field25519 {
        var result = one
        for (bitIndex in FIELD_BITS - 1 downTo 0) {
            result *= result
            val bit = (exponent[bitIndex / LIMB_BITS] shr (bitIndex % LIMB_BITS)) and 1u
            val candidate = result * this
            result = Field25519(selectLimbs(mask(bit), candidate.limbs, result.limbs))
        }
        return result
    }

    /** `this^-1 mod q` (0 ánh xạ về 0, đúng định lý nhỏ Fermat áp cho `a^(q-2)`). */
    fun inverted(): Field25519 = power(inverseExponent)

    /** Ký hiệu Legendre (tiêu chuẩn Euler): `1` nếu `this` là bình phương khác 0 mod q, `q−1` (≡ `-1`) nếu không
     * phải, `0` nếu `this` là `0`. */
    fun legendreSymbol(): Field25519 = power(legendreExponent)

    /** Little-endian 32 byte — nghịch đảo của [fromLittleEndianBytes]. */
    fun toLittleEndianBytes(): ByteArray {
        val out = ByteArray(FIELD_SIZE_BYTES)
        for (limbIndex in 0 until LIMB_COUNT) {
            var word = limbs[limbIndex]
            for (byteIndex in 0 until BYTES_PER_LIMB) {
                out[limbIndex * BYTES_PER_LIMB + byteIndex] = word.toByte()
                word = word shr BITS_PER_BYTE
            }
        }
        return out
    }

    companion object {
        const val LIMB_COUNT = 8
        const val FIELD_SIZE_BYTES = 32
        private const val BITS_PER_BYTE = 8
        private const val BYTES_PER_LIMB = 4
        private const val FIELD_BITS = 255

        /** `2^255 − 19`, 8 limb 32-bit little-endian. */
        val q =
            Field25519(
                uintArrayOf(
                    0xFFFFFFEDu, 0xFFFFFFFFu, 0xFFFFFFFFu, 0xFFFFFFFFu,
                    0xFFFFFFFFu, 0xFFFFFFFFu, 0xFFFFFFFFu, 0x7FFFFFFFu,
                ),
            )
        val zero = Field25519(UIntArray(LIMB_COUNT))
        val one = Field25519(UIntArray(LIMB_COUNT).also { it[0] = 1u })

        /** `q − 2`: số mũ công khai cố định của nghịch đảo Fermat (`a^(q-2) ≡ a^-1 mod q`, `a ≠ 0`). */
        private val inverseExponent: UIntArray = minusSmallPublicConstant(q.limbs, 2u)

        /** `(q − 1) / 2`: số mũ công khai cố định của tiêu chuẩn Euler (ký hiệu Legendre). q lẻ nên đây là phép
         * dịch phải chẵn, không mất bit. */
        private val legendreExponent: UIntArray = shiftRightOnePublic(minusSmallPublicConstant(q.limbs, 1u))

        /** `2^-1 mod q = (q+1)/2` (q lẻ nên q+1 chẵn) — Elligator2 cần ở bước kết hợp, rẻ hơn nghịch đảo 2 mỗi
         * lần gọi. */
        val inverseOfTwo: Field25519 = Field25519(shiftRightOnePublic(addSmallPublicConstant(q.limbs, 1u)))

        /** RFC 7748 `decodeUCoordinate` cho trường 255-bit: little-endian 32 byte, bit cao nhất của byte cuối ĐÃ
         * được caller che trước khi gọi (nên giá trị luôn < 2^255, cần tối đa một lần rút gọn ở dưới). */
        fun fromLittleEndianBytes(bytes: ByteArray): Field25519 {
            require(bytes.size == FIELD_SIZE_BYTES) { "Field25519 cần đúng $FIELD_SIZE_BYTES byte" }
            val limbs = UIntArray(LIMB_COUNT)
            for (limbIndex in 0 until LIMB_COUNT) {
                var word = 0u
                for (byteIndex in BYTES_PER_LIMB - 1 downTo 0) {
                    word = (word shl BITS_PER_BYTE) or bytes[limbIndex * BYTES_PER_LIMB + byteIndex].toUByte().toUInt()
                }
                limbs[limbIndex] = word
            }
            return Field25519(reduceBounded(limbs))
        }

        // --- Dựng hằng số CÔNG KHAI (q, inverseExponent, legendreExponent, inverseOfTwo) lúc nạp lớp: q là số
        // nguyên tố công khai, không mang bí mật, nên 3 hàm dưới dùng nhánh thường — KHÔNG được dùng cho một
        // giá trị trường bí mật (ya/yb/K/r suy từ PIN). ---

        private fun minusSmallPublicConstant(
            a: UIntArray,
            k: UInt,
        ): UIntArray {
            val diff = UIntArray(LIMB_COUNT)
            var borrow = k.toLong()
            for (i in 0 until LIMB_COUNT) {
                var limb = a[i].toLong() - borrow
                borrow = if (limb < 0) 1L else 0L
                if (limb < 0) limb += 1L shl LIMB_BITS
                diff[i] = limb.toUInt()
            }
            return diff
        }

        private fun addSmallPublicConstant(
            a: UIntArray,
            k: UInt,
        ): UIntArray {
            val sum = UIntArray(LIMB_COUNT)
            var carry = k.toULong()
            for (i in 0 until LIMB_COUNT) {
                val total = a[i].toULong() + carry
                sum[i] = (total and WORD_MASK).toUInt()
                carry = total shr LIMB_BITS
            }
            return sum
        }

        private fun shiftRightOnePublic(a: UIntArray): UIntArray {
            val out = UIntArray(LIMB_COUNT)
            var carry = 0u
            for (i in LIMB_COUNT - 1 downTo 0) {
                out[i] = (a[i] shr 1) or (carry shl (LIMB_BITS - 1))
                carry = a[i] and 1u
            }
            return out
        }
    }
}

// --- Hằng thời gian: mọi hàm dưới đây chỉ dùng cộng/trừ/nhân limb và mask bit — không nhánh, không chỉ số mảng
// theo giá trị bí mật. Dùng cho [Field25519.plus]/[minus]/[times]/[power], áp lên giá trị trường có thể bí mật
// (ya, yb, K, r suy từ PIN qua CPace). ---

/** Mask toàn-0 hoặc toàn-1 từ một bit 0/1 — không nhánh. */
private fun mask(bit: UInt): UInt = 0u - bit

/** `a` khi [maskValue] toàn-1, `b` khi toàn-0. */
private fun select(
    maskValue: UInt,
    a: UInt,
    b: UInt,
): UInt = (a and maskValue) or (b and maskValue.inv())

private fun selectLimbs(
    maskValue: UInt,
    a: UIntArray,
    b: UIntArray,
): UIntArray = UIntArray(LIMB_COUNT) { select(maskValue, a[it], b[it]) }

private fun addRaw(
    a: UIntArray,
    b: UIntArray,
): Pair<UIntArray, UInt> {
    val sum = UIntArray(LIMB_COUNT)
    var carry = 0uL
    for (i in 0 until LIMB_COUNT) {
        val total = a[i].toULong() + b[i].toULong() + carry
        sum[i] = (total and WORD_MASK).toUInt()
        carry = total shr LIMB_BITS
    }
    return sum to carry.toUInt()
}

/**
 * `a[i] - b[i]`, limb theo limb, với mượn lan sang limb kế — không nhánh: `(a[i] hoặc 2^32) - b[i] - mượn` luôn
 * không âm (vì số bị trừ tối đa là `2^32`), bit 32 của kết quả cho biết có mượn hay không mà không cần so sánh.
 */
private fun subRaw(
    a: UIntArray,
    b: UIntArray,
): Pair<UIntArray, UInt> {
    val diff = UIntArray(LIMB_COUNT)
    var borrow = 0uL
    for (i in 0 until LIMB_COUNT) {
        val raw = (a[i].toULong() or (1uL shl LIMB_BITS)) - b[i].toULong() - borrow
        diff[i] = (raw and WORD_MASK).toUInt()
        borrow = 1uL - ((raw shr LIMB_BITS) and 1uL)
    }
    return diff to borrow.toUInt()
}

/**
 * Đưa một giá trị tối đa vài lần q (do [addRaw]/[subRaw] ở các hàm gọi dưới tạo ra) về canonical `[0, q)`: một
 * số vòng CỐ ĐỊNH ([REDUCE_ROUNDS]) "trừ q, chỉ giữ phép trừ nếu không mượn" — luôn đúng số vòng đó, không bao
 * giờ ít hơn, nên chi phí không hé lộ đầu vào lớn tới đâu.
 */
private fun reduceBounded(value: UIntArray): UIntArray {
    var v = value
    repeat(REDUCE_ROUNDS) {
        val (diff, borrow) = subRaw(v, Field25519.q.limbs)
        v = selectLimbs(mask(1u - borrow), diff, v)
    }
    return v
}

private fun addMod(
    a: UIntArray,
    b: UIntArray,
): UIntArray {
    val (sum, carry) = addRaw(a, b)
    // 2^256 ≡ 38 (mod q): gập carry của phép cộng thô vào trước khi rút gọn (chỉ để phòng xa — không xảy ra với
    // hai đầu vào < q đã canonical, vì a+b < 2q < 2^256, nhưng giữ hàm đúng theo nghĩa của riêng nó).
    val carryLimbs = UIntArray(LIMB_COUNT).also { it[0] = FOLD_CONSTANT * carry }
    val (adjusted, _) = addRaw(sum, carryLimbs)
    return reduceBounded(adjusted)
}

private fun subMod(
    a: UIntArray,
    b: UIntArray,
): UIntArray {
    val (diff, borrow) = subRaw(a, b)
    val (diffPlusQ, _) = addRaw(diff, Field25519.q.limbs) // cố ý tràn mod 2^256 — xem bất biến ở reduceBounded.
    return reduceBounded(selectLimbs(mask(borrow), diffPlusQ, diff))
}

/**
 * Cộng [value] (< 2^32) vào `result[index]`, lan carry qua TOÀN BỘ vị trí còn lại tới một biên CỐ ĐỊNH
 * (`result.size`, không bao giờ dừng sớm khi "carry == 0") — số limb bị đụng tới chỉ phụ thuộc `index`/
 * `result.size`, không phụ thuộc giá trị thực của carry, nên không hé lộ limb nào của một giá trị bí mật thực
 * sự có mượn/nhớ.
 */
private fun addAt(
    result: UIntArray,
    index: Int,
    value: UInt,
) {
    var carry = value.toULong()
    for (i in index until result.size) {
        val sum = result[i].toULong() + carry
        result[i] = (sum and WORD_MASK).toUInt()
        carry = sum shr LIMB_BITS
    }
}

/** Nhân thường (schoolbook) 8×8 limb → 16 limb. */
private fun mulRaw(
    a: UIntArray,
    b: UIntArray,
): UIntArray {
    val result = UIntArray(LIMB_COUNT * 2)
    for (i in 0 until LIMB_COUNT) {
        for (j in 0 until LIMB_COUNT) {
            val product = a[i].toULong() * b[j].toULong()
            addAt(result, i + j, (product and WORD_MASK).toUInt())
            if (i + j + 1 < result.size) addAt(result, i + j + 1, (product shr LIMB_BITS).toUInt())
        }
    }
    return result
}

/**
 * 8 limb × hằng số nhỏ → 9 limb (limb thứ 9 giữ phần không vừa 8 limb thấp — không bao giờ bị cắt mất, khác một
 * phép nhân 8-limb bình thường sẽ làm).
 */
private fun mulBySmallConstant(
    a: UIntArray,
    k: UInt,
): UIntArray {
    val result = UIntArray(LIMB_COUNT + 1)
    for (i in 0 until LIMB_COUNT) {
        val product = a[i].toULong() * k.toULong()
        addAt(result, i, (product and WORD_MASK).toUInt())
        addAt(result, i + 1, (product shr LIMB_BITS).toUInt())
    }
    return result
}

/**
 * `a * b mod q`. Tích thô chia thành `lo + hi · 2^256`; vì `2^256 ≡ 38 (mod q)`, gập `38 · hi` vào `lo` và lặp
 * lại co `hi` lại dần. Một số vòng CỐ ĐỊNH ([MUL_FOLD_ROUNDS], không phải lặp "tới khi hi = 0" theo dữ liệu) đủ
 * đưa `hi` về một limb rồi về 0 với mọi tích của hai giá trị < q — đã xác nhận bằng toàn bộ bộ vector
 * `cpace.json`, kể cả hai phép nhân gần q mà `calculate_generator` thực hiện.
 */
private fun mulMod(
    a: UIntArray,
    b: UIntArray,
): UIntArray {
    val product = mulRaw(a, b)
    var lo = product.copyOfRange(0, LIMB_COUNT)
    var hi = product.copyOfRange(LIMB_COUNT, LIMB_COUNT * 2)
    repeat(MUL_FOLD_ROUNDS) {
        val scaled = mulBySmallConstant(hi, FOLD_CONSTANT)
        val (sum, carryOut) = addRaw(lo, scaled.copyOfRange(0, LIMB_COUNT))
        lo = sum
        hi = UIntArray(LIMB_COUNT).also { it[0] = scaled[LIMB_COUNT] + carryOut }
    }
    return reduceBounded(lo)
}
