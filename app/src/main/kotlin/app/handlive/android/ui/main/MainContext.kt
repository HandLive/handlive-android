package app.handlive.android.ui.main

import app.handlive.android.core.design.component.HLFeedbackState
import app.handlive.android.ui.AppDependencies

/**
 * Everything a route needs: the singletons, navigation, the HUD, the phone's current state and the [trip] to a
 * restricted system page.
 */
class MainContext(
    val dependencies: AppDependencies,
    val stack: MutableList<Route>,
    val feedback: HLFeedbackState,
    val banners: StatusBanners,
    val trip: SpecialAccessTrip = SpecialAccessTrip(),
) {
    fun push(route: Route) {
        stack.add(route)
    }

    fun pop() {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }
}
