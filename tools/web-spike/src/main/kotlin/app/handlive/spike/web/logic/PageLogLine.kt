package app.handlive.spike.web.logic

import java.security.MessageDigest

/**
 * One `HLWEB` log line: `HLWEB key=value key=value …`, values without spaces. Full addresses are never written: only
 * the host and a salted hash of the full address (so two lines about the same page can be matched on one device).
 */
object PageLogLine {
    const val TAG = "HLWEB"

    /** Column order of the CSV export; keys missing from a line stay empty. */
    val columns =
        listOf(
            "ts",
            "ev",
            "browser",
            "ver",
            "api",
            "host",
            "hash",
            "host_only",
            "private",
            "marker",
            "via",
            "source",
            "reason",
            "events",
            "cpu_ms",
            "nodes",
        )

    fun format(fields: List<Pair<String, Any?>>): String =
        buildString {
            append(TAG)
            fields.forEach { (key, value) ->
                if (value != null) append(' ').append(key).append('=').append(clean(value.toString()))
            }
        }

    /** Parses a line written by [format]; null when it is not an `HLWEB` line. */
    fun parse(line: String): Map<String, String>? {
        val start = line.indexOf("$TAG ")
        if (start < 0) return null
        return line
            .substring(start + TAG.length + 1)
            .split(' ')
            .mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            .toMap()
    }

    fun csvHeader(): String = columns.joinToString(",")

    fun csvRow(fields: Map<String, String>): String =
        columns.joinToString(",") { key ->
            val value = fields[key].orEmpty()
            if (value.any { it == ',' || it == '"' }) "\"" + value.replace("\"", "\"\"") + "\"" else value
        }

    /** First 12 hex digits of SHA-256(salt + address). The salt is per install, so hashes do not leave the device. */
    fun hash(
        url: String,
        salt: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256").digest((salt + url).toByteArray(Charsets.UTF_8))
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }

    private fun clean(value: String) = value.map { if (it.isWhitespace() || it == '=') '_' else it }.joinToString("")
}
