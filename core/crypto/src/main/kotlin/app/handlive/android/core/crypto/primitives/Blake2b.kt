package app.handlive.android.core.crypto.primitives

/**
 * BLAKE2b (RFC 7693) without a key, output 1–64 bytes. Needed only by [Argon2id] (RFC 9106 §3.2–3.3), which Tink
 * and the platform do not provide.
 */
internal class Blake2b(
    private val outputLength: Int,
) {
    private val h = IV.copyOf()
    private val buffer = ByteArray(BLOCK_BYTES)
    private var bufferLength = 0
    private var counter = 0L

    init {
        require(outputLength in 1..MAX_OUTPUT) { "BLAKE2b output must be 1..64 bytes" }
        // Parameter block: digest length, key length 0, fanout 1, depth 1 (RFC 7693 §2.5).
        h[0] = h[0] xor (PARAM_FANOUT_DEPTH or outputLength.toLong())
    }

    fun update(input: ByteArray): Blake2b {
        var offset = 0
        while (offset < input.size) {
            if (bufferLength == BLOCK_BYTES) {
                counter += BLOCK_BYTES
                compress(buffer, last = false)
                bufferLength = 0
            }
            val take = minOf(BLOCK_BYTES - bufferLength, input.size - offset)
            System.arraycopy(input, offset, buffer, bufferLength, take)
            bufferLength += take
            offset += take
        }
        return this
    }

    /** Little-endian 32-bit integer, as Argon2 feeds lengths and parameters. */
    fun updateInt(value: Int): Blake2b = update(intLe(value))

    fun digest(): ByteArray {
        counter += bufferLength
        buffer.fill(0, bufferLength, BLOCK_BYTES)
        compress(buffer, last = true)
        val out = ByteArray(outputLength)
        for (i in 0 until outputLength) out[i] = (h[i / LONG_BYTES] ushr (BITS_PER_BYTE * (i % LONG_BYTES))).toByte()
        return out
    }

    private fun compress(
        block: ByteArray,
        last: Boolean,
    ) {
        val m = LongArray(WORDS) { readLongLe(block, it * LONG_BYTES) }
        val v = LongArray(WORDS)
        h.copyInto(v)
        IV.copyInto(v, destinationOffset = WORDS / 2)
        v[COUNTER_LOW] = v[COUNTER_LOW] xor counter
        if (last) v[FINAL_FLAG] = v[FINAL_FLAG].inv()
        for (round in 0 until ROUNDS) {
            val s = SIGMA[round % SIGMA.size]
            MIX_LANES.forEachIndexed { g, lane -> mix(v, lane, m[s[2 * g]], m[s[2 * g + 1]]) }
        }
        for (i in 0 until WORDS / 2) h[i] = h[i] xor v[i] xor v[i + WORDS / 2]
    }

    /** The G function on the words `lane` = (a, b, c, d) of [v] (RFC 7693 §3.1). */
    private fun mix(
        v: LongArray,
        lane: IntArray,
        x: Long,
        y: Long,
    ) {
        val a = lane[0]
        val b = lane[1]
        val c = lane[2]
        val d = lane.last()
        v[a] += v[b] + x
        v[d] = (v[d] xor v[a]).rotateRight(R1)
        v[c] += v[d]
        v[b] = (v[b] xor v[c]).rotateRight(R2)
        v[a] += v[b] + y
        v[d] = (v[d] xor v[a]).rotateRight(R3)
        v[c] += v[d]
        v[b] = (v[b] xor v[c]).rotateRight(R4)
    }

    companion object {
        const val MAX_OUTPUT = 64
        private const val BLOCK_BYTES = 128
        private const val WORDS = 16
        private const val ROUNDS = 12
        private const val LONG_BYTES = 8
        private const val BITS_PER_BYTE = 8
        private const val COUNTER_LOW = 12
        private const val FINAL_FLAG = 14
        private const val PARAM_FANOUT_DEPTH = 0x01010000L
        private const val R1 = 32
        private const val R2 = 24
        private const val R3 = 16
        private const val R4 = 63
        private const val BYTE_MASK = 0xffL

        private val IV =
            longArrayOf(
                0x6a09e667f3bcc908UL.toLong(),
                0xbb67ae8584caa73bUL.toLong(),
                0x3c6ef372fe94f82bUL.toLong(),
                0xa54ff53a5f1d36f1UL.toLong(),
                0x510e527fade682d1UL.toLong(),
                0x9b05688c2b3e6c1fUL.toLong(),
                0x1f83d9abfb41bd6bUL.toLong(),
                0x5be0cd19137e2179UL.toLong(),
            )

        /** Column then diagonal quadruples of one round; Argon2's permutation uses the same order. */
        internal val MIX_LANES =
            arrayOf(
                intArrayOf(0, 4, 8, 12),
                intArrayOf(1, 5, 9, 13),
                intArrayOf(2, 6, 10, 14),
                intArrayOf(3, 7, 11, 15),
                intArrayOf(0, 5, 10, 15),
                intArrayOf(1, 6, 11, 12),
                intArrayOf(2, 7, 8, 13),
                intArrayOf(3, 4, 9, 14),
            )

        private val SIGMA =
            arrayOf(
                intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
                intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
                intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
                intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
                intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
                intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
                intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
                intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
                intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
                intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0),
            )

        fun hash(
            outputLength: Int,
            vararg parts: ByteArray,
        ): ByteArray = Blake2b(outputLength).apply { parts.forEach { update(it) } }.digest()

        internal fun intLe(value: Int): ByteArray =
            ByteArray(Int.SIZE_BYTES) { (value ushr (BITS_PER_BYTE * it)).toByte() }

        internal fun readLongLe(
            bytes: ByteArray,
            offset: Int,
        ): Long {
            var value = 0L
            for (i in LONG_BYTES - 1 downTo 0) {
                value =
                    (value shl BITS_PER_BYTE) or (bytes[offset + i].toLong() and BYTE_MASK)
            }
            return value
        }
    }
}
