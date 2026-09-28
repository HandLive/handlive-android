package app.handlive.android.feature.call.module

import app.handlive.android.feature.call.context.CallTracker
import app.handlive.android.feature.call.log.CallLogEntries
import app.handlive.android.feature.call.log.CallLogRequests
import app.handlive.android.feature.call.log.CallLogWatcher

/** The call log side of the module: `log_sync` pages, the observer's new rows and their `entry` objects. */
class CallLogServices(
    val requests: CallLogRequests,
    val watcher: CallLogWatcher,
    /** Entries of the observer's rounds, with the long-lived name cache of A-CALL. */
    val entries: () -> CallLogEntries,
)

/** What [CallModule] works with: the checks, the context, the Telecom actions, the events out and the call log. */
class CallServices(
    val access: CallAccess,
    val tracker: CallTracker,
    val actions: CallActions,
    val broadcaster: CallBroadcaster,
    val logs: CallLogServices,
    val trace: CallTrace = CallTrace.NONE,
)
