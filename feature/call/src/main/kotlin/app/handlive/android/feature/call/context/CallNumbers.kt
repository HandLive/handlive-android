package app.handlive.android.feature.call.context

/**
 * Numbers and names of group 6: [normalize] gives E.164 for the SIM's country, or the original string when that fails
 * (CALL-01 API 1 logic 7); [name] is the contact name from `PhoneLookup`, `null` without `READ_CONTACTS` or when the
 * number is not a contact (E2). Numbers and names are never logged.
 */
interface CallNumbers {
    fun normalize(
        raw: String,
        subId: Int?,
    ): String

    fun name(number: String): String?
}

/** The SIM name shown with a call (`sim_label`): only when the phone has more than one active SIM. */
fun interface SimLabels {
    fun label(subId: Int): String?
}

/**
 * The contact names of numbers, least recently used first out (CALL-01 Query: 200 numbers in A-CALL memory, never
 * written to disk), so the lookup of a known caller takes no provider query. A number that is no contact is kept too;
 * [clear] forgets everything when the contacts change.
 */
class NameCache(
    private val capacity: Int,
    private val lookup: (String) -> String?,
) {
    private val names = LinkedHashMap<String, Name>(capacity, LOAD_FACTOR, true)

    @Synchronized
    fun name(number: String): String? {
        names[number]?.let { return it.value }
        val value = lookup(number)
        names[number] = Name(value)
        if (names.size > capacity) names.remove(names.keys.first())
        return value
    }

    @Synchronized
    fun clear() = names.clear()

    /** A looked-up name, `null` included. */
    private class Name(
        val value: String?,
    )

    private companion object {
        const val LOAD_FACTOR = 0.75f
    }
}
