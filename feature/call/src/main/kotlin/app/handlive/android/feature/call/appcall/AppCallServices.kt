package app.handlive.android.feature.call.appcall

import app.handlive.android.feature.call.module.CallTrace

/** What the app calls of [app.handlive.android.feature.call.module.CallModule] work with (CALL-05). */
class AppCallServices(
    val access: AppCallAccess,
    val tracker: AppCallTracker,
    val broadcaster: AppCallBroadcaster,
    val actions: AppCallActions,
    val tap: TapToAnswerNotifier,
    val trace: CallTrace = CallTrace.NONE,
)
