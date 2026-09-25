package app.handlive.android.core.crypto.primitives

/**
 * Argon2id version 0x13 (RFC 9106), for `K_pin` of the PIN pairing fallback (0.6.2: t = 3, m = 64 MiB, p = 4,
 * 32-byte output). Neither Tink nor Android ship Argon2; this implementation follows the reference `fill_segment`
 * step by step and is checked against the RFC 9106 §5.3 vector and an independent implementation in tests.
 * Lanes are computed one after the other: the PIN path is a rare fallback and determinism beats speed here.
 */
object Argon2id {
    const val VERSION = 0x13
    private const val TYPE_ID = 2
    private const val BLOCK_LONGS = 128
    private const val BLOCK_BYTES = 1024
    private const val SYNC_POINTS = 4
    private const val ADDRESSES_IN_BLOCK = 128
    private const val PREHASH_BYTES = 64
    private const val LOW_32 = 0xffffffffL
    private const val HALF_BITS = 32
    private const val MIN_TAG_BYTES = 4
    private const val MIN_KIB_PER_LANE = 8

    /** Words of the address generator's input block (RFC 9106 §3.4.1.2). */
    private const val INPUT_PASS = 0
    private const val INPUT_LANE = 1
    private const val INPUT_SLICE = 2
    private const val INPUT_BLOCKS = 3
    private const val INPUT_PASSES = 4
    private const val INPUT_TYPE = 5
    private const val INPUT_COUNTER = 6

    class Parameters(
        val passes: Int,
        val memoryKiB: Int,
        val parallelism: Int,
        val tagLength: Int,
    ) {
        init {
            require(passes >= 1 && parallelism >= 1 && tagLength >= MIN_TAG_BYTES) { "invalid Argon2 parameters" }
            require(memoryKiB >= MIN_KIB_PER_LANE * parallelism) { "memory must be at least 8 KiB per lane" }
        }
    }

    fun hash(
        password: ByteArray,
        salt: ByteArray,
        parameters: Parameters,
        secret: ByteArray = ByteArray(0),
        associatedData: ByteArray = ByteArray(0),
    ): ByteArray {
        val lanes = parameters.parallelism
        val laneLength = parameters.memoryKiB / (SYNC_POINTS * lanes) * SYNC_POINTS
        val memory = Memory(lanes, laneLength)
        val h0 = initialHash(password, salt, parameters, secret, associatedData)
        for (lane in 0 until lanes) {
            for (column in 0..1) {
                val block = variableHash(BLOCK_BYTES, h0 + Blake2b.intLe(column) + Blake2b.intLe(lane))
                memory.load(lane, column, block)
            }
        }
        val filler = SegmentFiller(memory, parameters.passes)
        for (pass in 0 until parameters.passes) {
            for (slice in 0 until SYNC_POINTS) {
                for (lane in 0 until lanes) filler.fill(pass, slice, lane)
            }
        }
        val last = LongArray(BLOCK_LONGS)
        for (lane in 0 until lanes) memory.xorInto(last, lane, laneLength - 1)
        return variableHash(parameters.tagLength, toBytes(last))
    }

    private fun initialHash(
        password: ByteArray,
        salt: ByteArray,
        parameters: Parameters,
        secret: ByteArray,
        associatedData: ByteArray,
    ): ByteArray =
        Blake2b(PREHASH_BYTES)
            .updateInt(parameters.parallelism)
            .updateInt(parameters.tagLength)
            .updateInt(parameters.memoryKiB)
            .updateInt(parameters.passes)
            .updateInt(VERSION)
            .updateInt(TYPE_ID)
            .updateInt(password.size)
            .update(password)
            .updateInt(salt.size)
            .update(salt)
            .updateInt(secret.size)
            .update(secret)
            .updateInt(associatedData.size)
            .update(associatedData)
            .digest()

    /** H' of RFC 9106 §3.3: BLAKE2b with any output length, chaining 64-byte digests and keeping their first halves. */
    internal fun variableHash(
        length: Int,
        input: ByteArray,
    ): ByteArray {
        if (length <= Blake2b.MAX_OUTPUT) return Blake2b.hash(length, Blake2b.intLe(length), input)
        val half = Blake2b.MAX_OUTPUT / 2
        val rounds = (length + half - 1) / half - 2
        val out = ByteArray(length)
        var v = Blake2b.hash(Blake2b.MAX_OUTPUT, Blake2b.intLe(length), input)
        System.arraycopy(v, 0, out, 0, half)
        for (i in 1 until rounds) {
            v = Blake2b.hash(Blake2b.MAX_OUTPUT, v)
            System.arraycopy(v, 0, out, i * half, half)
        }
        val lastLength = length - half * rounds
        System.arraycopy(Blake2b.hash(lastLength, v), 0, out, half * rounds, lastLength)
        return out
    }

    /** All blocks, lane after lane, as 64-bit little-endian words. */
    private class Memory(
        val lanes: Int,
        val laneLength: Int,
    ) {
        val words = LongArray(lanes * laneLength * BLOCK_LONGS)

        fun offset(
            lane: Int,
            column: Int,
        ) = (lane * laneLength + column) * BLOCK_LONGS

        fun load(
            lane: Int,
            column: Int,
            bytes: ByteArray,
        ) {
            val base = offset(lane, column)
            for (i in 0 until BLOCK_LONGS) words[base + i] = Blake2b.readLongLe(bytes, i * Long.SIZE_BYTES)
        }

        fun xorInto(
            target: LongArray,
            lane: Int,
            column: Int,
        ) {
            val base = offset(lane, column)
            for (i in 0 until BLOCK_LONGS) target[i] = target[i] xor words[base + i]
        }
    }

    /** `fill_segment` of the reference implementation for Argon2id. */
    private class SegmentFiller(
        private val memory: Memory,
        private val passes: Int,
    ) {
        private val segmentLength = memory.laneLength / SYNC_POINTS
        private val scratch = LongArray(BLOCK_LONGS)
        private val input = LongArray(BLOCK_LONGS)
        private val address = LongArray(BLOCK_LONGS)
        private val zero = LongArray(BLOCK_LONGS)

        fun fill(
            pass: Int,
            slice: Int,
            lane: Int,
        ) {
            val independent = pass == 0 && slice < 2
            if (independent) startAddresses(pass, slice, lane)
            val start = if (pass == 0 && slice == 0) 2 else 0
            if (independent && start == 2) nextAddresses()
            for (index in start until segmentLength) {
                val column = slice * segmentLength + index
                val previous = if (column == 0) memory.laneLength - 1 else column - 1
                val pseudoRandom =
                    if (independent) {
                        if (index % ADDRESSES_IN_BLOCK == 0) nextAddresses()
                        address[index % ADDRESSES_IN_BLOCK]
                    } else {
                        memory.words[memory.offset(lane, previous)]
                    }
                val refLane =
                    if (pass == 0 && slice == 0) lane else ((pseudoRandom ushr HALF_BITS) % memory.lanes).toInt()
                val refColumn = referenceColumn(pass, slice, index, pseudoRandom and LOW_32, refLane == lane)
                fillBlock(
                    memory.offset(lane, previous),
                    memory.offset(refLane, refColumn),
                    memory.offset(lane, column),
                    xorWithTarget = pass > 0,
                )
            }
        }

        /** `index_alpha`: map J1 onto the blocks this position may reference (RFC 9106 §3.4.1.2). */
        private fun referenceColumn(
            pass: Int,
            slice: Int,
            index: Int,
            j1: Long,
            sameLane: Boolean,
        ): Int {
            val areaSize: Long =
                if (pass == 0) {
                    when {
                        slice == 0 -> index - 1L
                        sameLane -> slice * segmentLength + index - 1L
                        else -> slice * segmentLength + if (index == 0) -1L else 0L
                    }
                } else if (sameLane) {
                    memory.laneLength - segmentLength + index - 1L
                } else {
                    memory.laneLength - segmentLength + if (index == 0) -1L else 0L
                }
            var relative = (j1 * j1) ushr HALF_BITS
            relative = areaSize - 1 - ((areaSize * relative) ushr HALF_BITS)
            val start = if (pass == 0 || slice == SYNC_POINTS - 1) 0L else (slice + 1L) * segmentLength
            return ((start + relative) % memory.laneLength).toInt()
        }

        private fun startAddresses(
            pass: Int,
            slice: Int,
            lane: Int,
        ) {
            input.fill(0)
            input[INPUT_PASS] = pass.toLong()
            input[INPUT_LANE] = lane.toLong()
            input[INPUT_SLICE] = slice.toLong()
            input[INPUT_BLOCKS] = (memory.lanes * memory.laneLength).toLong()
            input[INPUT_PASSES] = passes.toLong()
            input[INPUT_TYPE] = TYPE_ID.toLong()
        }

        /** Address block = G(0, G(0, input)) with the counter of the input block incremented first. */
        private fun nextAddresses() {
            input[INPUT_COUNTER]++
            compress(zero, input, address)
            compress(zero, address, address)
        }

        private fun fillBlock(
            previous: Int,
            reference: Int,
            target: Int,
            xorWithTarget: Boolean,
        ) {
            val words = memory.words
            val r = LongArray(BLOCK_LONGS) { words[previous + it] xor words[reference + it] }
            for (i in 0 until BLOCK_LONGS) scratch[i] = if (xorWithTarget) r[i] xor words[target + i] else r[i]
            permute(r)
            for (i in 0 until BLOCK_LONGS) words[target + i] = scratch[i] xor r[i]
        }

        /** G(x, y) into [out] for the address generator (no memory access). */
        private fun compress(
            x: LongArray,
            y: LongArray,
            out: LongArray,
        ) {
            val r = LongArray(BLOCK_LONGS) { x[it] xor y[it] }
            val kept = r.copyOf()
            permute(r)
            for (i in 0 until BLOCK_LONGS) out[i] = kept[i] xor r[i]
        }
    }

    /** P applied to the 8 rows then the 8 columns of 16-byte registers (RFC 9106 §3.6). */
    private fun permute(r: LongArray) {
        val rowIndex = IntArray(ROUND_WORDS)
        for (row in 0 until REGISTERS) {
            for (k in 0 until ROUND_WORDS) rowIndex[k] = row * ROUND_WORDS + k
            round(r, rowIndex)
        }
        for (column in 0 until REGISTERS) {
            for (k in 0 until REGISTERS) {
                rowIndex[2 * k] = 2 * column + k * ROUND_WORDS
                rowIndex[2 * k + 1] = 2 * column + k * ROUND_WORDS + 1
            }
            round(r, rowIndex)
        }
    }

    private const val REGISTERS = 8
    private const val ROUND_WORDS = 16

    /** BLAKE2b round without message, with the fBlaMka multiplication (RFC 9106 §3.6). */
    private fun round(
        v: LongArray,
        index: IntArray,
    ) {
        for (lane in Blake2b.MIX_LANES) {
            val a = index[lane[0]]
            val b = index[lane[1]]
            val c = index[lane[2]]
            val d = index[lane.last()]
            v[a] = blaMka(v[a], v[b])
            v[d] = (v[d] xor v[a]).rotateRight(R1)
            v[c] = blaMka(v[c], v[d])
            v[b] = (v[b] xor v[c]).rotateRight(R2)
            v[a] = blaMka(v[a], v[b])
            v[d] = (v[d] xor v[a]).rotateRight(R3)
            v[c] = blaMka(v[c], v[d])
            v[b] = (v[b] xor v[c]).rotateRight(R4)
        }
    }

    private const val R1 = 32
    private const val R2 = 24
    private const val R3 = 16
    private const val R4 = 63

    private fun blaMka(
        x: Long,
        y: Long,
    ): Long = x + y + 2 * (x and LOW_32) * (y and LOW_32)

    private fun toBytes(words: LongArray): ByteArray =
        ByteArray(words.size * Long.SIZE_BYTES) {
            (
                words[it / Long.SIZE_BYTES] ushr
                    (Byte.SIZE_BITS * (it % Long.SIZE_BYTES))
            ).toByte()
        }
}
