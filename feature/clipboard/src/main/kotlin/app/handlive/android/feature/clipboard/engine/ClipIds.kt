package app.handlive.android.feature.clipboard.engine

import app.handlive.android.core.protocol.envelope.EnvelopeCodec

/**
 * `clip_id` and `transfer_id` are canonical UUIDv7 (04-clipboard CLIP-01 API 5, CLIP-03 API 3; schema `uuid-v7`).
 * They name files in `cache/clip/`, so an id from a peer is checked before anything else uses it.
 */
object ClipIds {
    fun isValid(id: String): Boolean = EnvelopeCodec.isUuidV7(id)
}
