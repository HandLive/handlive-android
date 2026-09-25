package app.handlive.android.feature.clipboard.module

import app.handlive.android.core.protocol.clipboard.ClipboardValues
import app.handlive.android.core.protocol.id.UuidV7Generator
import app.handlive.android.feature.clipboard.ClipMessage
import app.handlive.android.feature.clipboard.engine.ChunkSource
import app.handlive.android.feature.clipboard.engine.Clip
import app.handlive.android.feature.clipboard.engine.ClipContent
import app.handlive.android.feature.clipboard.engine.ClipLimits
import app.handlive.android.feature.clipboard.engine.ClipOrigin
import app.handlive.android.feature.clipboard.engine.SensitiveContent
import app.handlive.android.feature.connection.bench.BenchEvent
import app.handlive.android.feature.connection.bench.BenchLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Local clips of the phone (CLIP-01 steps 5–9, CLIP-03 steps 2–6): echo check (QC4, E9), sensitive content (QC3),
 * size (QC5), then a new `clip_id` that becomes the latest clip (QC7) and goes to every client (QC6). Manual actions
 * get their result in place (field 11); the automatic path stays silent except for size limits.
 */
class LocalClipIntake(
    private val context: ClipContext,
    private val trace: ClipboardTrace,
    private val sender: ClipSender,
) {
    private class Held(
        val content: ClipContent,
        val source: String,
        val at: Long,
    )

    private val ids = UuidV7Generator(context.clock.wall)
    private val notices = context.platform.notices
    private var held: Held? = null

    suspend fun onRead(read: LocalRead) {
        when {
            // "Sync Clipboard" off: nothing is read for sending (the tile is inactive, the button hidden).
            !context.settings.value.clipboardEnabled -> (read as? LocalRead.Image)?.file?.delete()

            read is LocalRead.Text -> onText(read)

            read is LocalRead.Image -> onImage(read)

            read is LocalRead.Failed -> onFailed(read)
        }
    }

    /** QC3 "Send Anyway": the held content goes out with `sensitive = true` while it is under 120 s old. */
    suspend fun sendAnyway() {
        val current = held?.takeIf { context.clock.wall() - it.at <= ClipLimits.STALE_AFTER_MILLIS } ?: return
        held = null
        send(current.content, sensitive = true, source = current.source)
    }

    /** "Send Again" (CLIP-01 API 6 logic 3): same content, new `clip_id`, while it is still in memory. */
    fun sendAgain(clipId: String) {
        val clip = context.state.find(clipId) ?: return
        publish(clip.resend(ids.next(), context.clock.wall()), manual = true)
    }

    private suspend fun onText(read: LocalRead.Text) {
        val content = ClipContent.Text(read.text)
        trace.markChanged(content.sha256)
        val settings = context.settings.value
        val sensitive = read.sensitiveExtra || SensitiveContent.looksLikeCardNumber(read.text)
        when {
            read.text.isEmpty() -> onFailed(LocalRead.Failed(ReadFailure.EMPTY_OR_NOT_TEXT, read.source))
            context.state.loopGuard.isEcho(content.sha256, read.source == ClipboardValues.SOURCE_AUTO) -> Unit
            settings.clipBlockSensitive && sensitive -> hold(content, read.source)
            else -> send(content, sensitive = false, source = read.source)
        }
    }

    private suspend fun onImage(read: LocalRead.Image) {
        val settings = context.settings.value
        val content = if (settings.clipSendImages) normalize(read) else null
        content?.let { trace.markChanged(it.sha256) }
        when {
            // E1: images off on this phone — skipped without a notification.
            !settings.clipSendImages -> read.file.delete()

            content == null -> onFailed(LocalRead.Failed(ReadFailure.IMAGE_UNREADABLE, read.source))

            context.state.loopGuard.isEcho(
                content.sha256,
                read.source == ClipboardValues.SOURCE_AUTO,
            ) -> content.file.delete()

            // QC3 for images: the IS_SENSITIVE extra only, no Luhn check (CLIP-03 API 1 logic 5).
            settings.clipBlockSensitive && read.sensitiveExtra -> hold(content, read.source)

            else -> send(content, sensitive = false, source = read.source)
        }
    }

    /** CLIP-03 step 4 on the IO dispatcher: PNG/JPEG as they are, other formats to PNG; SHA-256 of the result. */
    private suspend fun normalize(read: LocalRead.Image): ClipContent.FileBacked? =
        withContext(context.platform.io) {
            val image =
                context.platform.images.normalize(
                    read.file,
                    read.mime,
                    context.platform.files.converted(ids.next()),
                )
            if (image?.file != read.file) read.file.delete()
            image?.let {
                ClipContent.FileBacked(
                    it.file,
                    it.mime,
                    it.file.length(),
                    ChunkSource.sha256Of(it.file),
                    it.width,
                    it.height,
                )
            }
        }

    private fun onFailed(read: LocalRead.Failed) {
        val manual = read.source != ClipboardValues.SOURCE_AUTO
        val message =
            when (read.reason) {
                ReadFailure.EMPTY_OR_NOT_TEXT -> ClipMessage.EmptyOrNotText.takeIf { manual }
                ReadFailure.IMAGE_TOO_LARGE -> ClipMessage.ImageTooLarge
                ReadFailure.IMAGE_UNREADABLE -> ClipMessage.ImageUnreadable.takeIf { manual }
                ReadFailure.PERMISSION_LOST -> null
            }
        message?.let(notices::show)
    }

    /** QC3: blocked, notified, kept in memory for at most `CLIP_STALE_AFTER`; a newer block replaces it. */
    private fun hold(
        content: ClipContent,
        source: String,
    ) {
        (held?.content as? ClipContent.FileBacked)?.file?.delete()
        val entry = Held(content, source, context.clock.wall()).also { held = it }
        notices.sensitiveBlocked()
        context.scope.launch {
            delay(ClipLimits.STALE_AFTER_MILLIS)
            if (held === entry) {
                held = null
                (content as? ClipContent.FileBacked)?.file?.delete()
            }
        }
    }

    /** Steps 8–9: size (QC5), then a new clip for every client. */
    private suspend fun send(
        content: ClipContent,
        sensitive: Boolean,
        source: String,
    ) {
        val limit = if (content is ClipContent.Text) ClipLimits.MAX_TEXT_BYTES else ClipLimits.MAX_IMAGE_BYTES
        if (content.size > limit) {
            (content as? ClipContent.FileBacked)?.file?.delete()
            notices.show(if (content is ClipContent.Text) ClipMessage.TextTooLarge else ClipMessage.ImageTooLarge)
            return
        }
        val now = context.clock.wall()
        val clip =
            Clip(ids.next(), content, sensitive, ClipOrigin(context.network.local().deviceId, source, now), null, now)
        publish(clip, manual = source != ClipboardValues.SOURCE_AUTO)
    }

    private fun publish(
        clip: Clip,
        manual: Boolean,
    ) {
        BenchLog.event(
            BenchEvent.CLIP_READ,
            "clip" to clip.clipId,
            "kind" to clip.kind,
            "bytes" to clip.content.size,
            "source" to clip.origin.source,
        )
        context.state.remember(clip)
        context.state.loopGuard.onSent(clip.content.sha256)
        trace.markChanged()
        val targets = sender.distribute(clip, manual)
        if (targets == 0 && manual) notices.show(ClipMessage.NotConnected)
        (clip.content as? ClipContent.FileBacked)?.let { content ->
            // CLIP-03 API 1 logic 4: a local image stays for replay (QC7), then goes.
            context.scope.launch {
                delay(ClipLimits.STALE_AFTER_MILLIS)
                content.file.delete()
            }
        }
    }
}
