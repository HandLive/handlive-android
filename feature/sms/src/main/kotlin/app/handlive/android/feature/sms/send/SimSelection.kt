package app.handlive.android.feature.sms.send

/** The SIM an `sms/send` goes out on, whether it may send, and the valid `sub_id` values for `details.sims`. */
class SimChoice(
    val subId: Int?,
    val available: Boolean,
    val valid: List<Int>,
)

/**
 * SMS-04 API 1 logic 3: a given `sub_id` must be an active SIM; without one, the default SMS SIM, else the only SIM,
 * and several SIMs with no default are refused. Without `READ_PHONE_STATE` the list is unknown: only requests without
 * `sub_id` are accepted, sent with the default SIM.
 */
object SimSelection {
    fun choose(
        sims: SimChoices,
        requested: Int?,
    ): SimChoice {
        val active = sims.active()
        val valid = active.orEmpty().map { it.subId }
        val default = sims.defaultSmsSubId()
        return when {
            active == null && requested == null -> SimChoice(default, available = true, valid)
            active == null -> SimChoice(requested, available = false, valid)
            requested != null -> SimChoice(requested, requested in valid, valid)
            default != null && default in valid -> SimChoice(default, available = true, valid)
            valid.size == 1 -> SimChoice(valid.single(), available = true, valid)
            else -> SimChoice(null, available = false, valid)
        }
    }
}
