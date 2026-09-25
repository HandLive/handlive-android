package app.handlive.android.core.design.component

/** One button of [HLActionSheet]; a destructive one is `destructive-text` and comes first. */
class HLSheetAction(
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)
