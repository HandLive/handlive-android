package app.handlive.spike.call.logic

/**
 * One `HLCALL` log line: `HLCALL key=value …` in field order. Null fields are left out, booleans become 1/0, lists are
 * joined with `|`, and values with spaces or quotes are quoted with `"` and `\` escaping, so `grep`/`awk` can read the
 * file.
 */
object CallLogLine {
    const val TAG = "HLCALL"

    fun format(fields: List<Pair<String, Any?>>): String =
        buildString {
            append(TAG)
            for ((name, value) in fields) {
                if (value == null) continue
                append(' ').append(name).append('=').append(render(value))
            }
        }

    private fun render(value: Any): String {
        val text =
            when (value) {
                is Boolean -> if (value) "1" else "0"
                is Collection<*> -> value.joinToString("|")
                else -> value.toString()
            }
        if (text.isEmpty()) return "\"\""
        if (text.none { it == ' ' || it == '"' || it == '\\' }) return text
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
