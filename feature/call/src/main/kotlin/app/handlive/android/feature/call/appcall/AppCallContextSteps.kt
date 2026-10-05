package app.handlive.android.feature.call.appcall

import app.handlive.android.core.protocol.call.AppCallEndReason
import app.handlive.android.core.protocol.call.AppCallState
import app.handlive.android.feature.call.CallConstants

/**
 * [this] became ongoing through [notification], the in-call notification, at [at] (its post time): it holds the call
 * now and its end action is the call's. The caller is kept; a `CallStyle` in-call notification fills it in when the
 * ringing one had none. Any other in-call notification is never asked for its caller: its title is not a caller.
 */
internal fun AppCallContext.becameOngoing(
    notification: AppNotification,
    callStyle: Boolean,
    at: Long,
): AppCallContext =
    copy(
        state = AppCallState.ONGOING,
        answeredAt = at,
        notificationKey = notification.key,
        caller = caller ?: if (callStyle) callerOf(notification) else null,
        answer = null,
        decline = null,
        end = AppCallEndAction.select(notification),
        unlinkedAt = null,
    )

/**
 * [this] holds [notification], posted again at [at]: a repost of the ringing one (its caller and intents are read
 * again, the wait for an in-call notification is over), the ringing one turned `CallStyle` ongoing, or an update of
 * the in-call one (its end action is read again); `null` when nothing changes.
 */
internal fun AppCallContext.updatedBy(
    notification: AppNotification,
    shape: AppCallShape?,
    at: Long,
): AppCallContext? =
    when {
        state == AppCallState.ONGOING && shape != AppCallShape.RINGING -> {
            copy(end = AppCallEndAction.select(notification), detached = false)
        }

        state != AppCallState.RINGING -> {
            null
        }

        shape == AppCallShape.ONGOING -> {
            becameOngoing(notification, callStyle = true, at = at)
        }

        shape == AppCallShape.RINGING -> {
            copy(
                caller = callerOf(notification) ?: caller,
                answer = notification.intents.answer,
                decline = notification.intents.decline,
                vouched = notification.vouched,
                unlinkedAt = null,
            )
        }

        else -> {
            null
        }
    }

/**
 * [this], ongoing, lost its in-call notification without the app removing it: it goes on without an end action until
 * [notification], a new ongoing notification of its package, holds it again.
 */
internal fun AppCallContext.reattachedTo(notification: AppNotification): AppCallContext =
    copy(notificationKey = notification.key, end = AppCallEndAction.select(notification), detached = false)

/** [this] ended at [at] for [reason]; only a call seen answered and then ended keeps its answer time. */
internal fun AppCallContext.endedAs(
    reason: String,
    at: Long,
): AppCallContext =
    copy(
        state = AppCallState.ENDED,
        endedAt = at,
        endReason = reason,
        answeredAt = answeredAt.takeIf { reason == AppCallEndReason.ENDED },
        answer = null,
        decline = null,
        end = null,
        unlinkedAt = null,
        detached = false,
    )

/** The caller as the clients show it, cut to the wire limit; `null` when absent or blank. */
internal fun callerOf(notification: AppNotification): String? =
    notification
        .caller()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { cut(it, CallConstants.APP_CALL_CALLER_MAX) }

/** The app's label cut to the wire limit; the package name when the label is blank. */
internal fun labelOf(
    labels: AppLabels,
    packageName: String,
): String = cut(labels.label(packageName).ifBlank { packageName }, CallConstants.APP_CALL_LABEL_MAX)

/** At most [max] characters, never ending in half of a surrogate pair. */
private fun cut(
    text: String,
    max: Int,
): String = if (text.length <= max) text else text.take(max).dropLastWhile { it.isHighSurrogate() }
