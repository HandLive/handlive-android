package app.handlive.buildlogic.strings

/**
 * Writes Android string resources from the catalog (detailed design 0.12.2): resource name = key with `.` → `_`;
 * plurals → `<plurals>`; `{name}` → `%1$s` / `%1$d` in `args` order; `'`, `"`, a leading `@` or `?`, backslashes and
 * line breaks are escaped; a literal `%` becomes `%%` in strings with arguments (a string without arguments is never
 * formatted, so it keeps `%` and is marked `formatted="false"`).
 */
class AndroidStringResourceWriter(
    private val catalog: UiStringCatalog,
    private val platform: String,
) {
    /** Resource directory → file text: `values` for the source language, `values-<lang>` for the others. */
    fun files(): Map<String, String> =
        catalog.languages.associate { lang ->
            val dir = if (lang == catalog.sourceLanguage) "values" else "values-$lang"
            "$dir/strings.xml" to document(lang)
        }

    private fun document(lang: String): String =
        buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            append("<!-- Generated from shared/strings/ui-strings.json by :core:strings:generateStringResources. ")
            append("Do not edit. -->\n")
            append("<resources>\n")
            catalog.strings.filter { platform in it.platforms }.forEach { string ->
                append("    <!-- ").append(xmlComment(string.comment)).append(" -->\n")
                when (val translation = string.translations.getValue(lang)) {
                    is Translation.Text -> appendString(string, translation.text)
                    is Translation.Plural -> appendPlural(string, translation.forms)
                }
            }
            append("</resources>\n")
        }

    private fun StringBuilder.appendString(string: UiString, text: String) {
        val formattedAttribute = if (string.args.isEmpty() && text.contains('%')) " formatted=\"false\"" else ""
        append("    <string name=\"").append(resourceName(string.key)).append('"').append(formattedAttribute).append('>')
        append(value(string, text)).append("</string>\n")
    }

    private fun StringBuilder.appendPlural(string: UiString, forms: Map<String, String>) {
        append("    <plurals name=\"").append(resourceName(string.key)).append("\">\n")
        PLURAL_ORDER.filter { it in forms }.forEach { quantity ->
            append("        <item quantity=\"").append(quantity).append("\">")
            append(value(string, forms.getValue(quantity))).append("</item>\n")
        }
        append("    </plurals>\n")
    }

    /** Escapes for Android's string parser, then for XML, then turns `{name}` into positional format arguments. */
    private fun value(string: UiString, text: String): String {
        var escaped =
            text
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
        if (escaped.startsWith("@") || escaped.startsWith("?")) escaped = "\\" + escaped
        if (string.args.isNotEmpty()) escaped = escaped.replace("%", "%%")
        escaped = escaped.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        string.args.forEachIndexed { index, arg ->
            escaped = escaped.replace("{${arg.name}}", "%${index + 1}\$${conversion(arg.type)}")
        }
        return escaped
    }

    companion object {
        private val PLURAL_ORDER = listOf("zero", "one", "two", "few", "many", "other")

        fun resourceName(key: String): String = key.replace('.', '_')

        /** Dates, times and numbers are formatted by the caller (0.12.3), so only `int` is a number conversion. */
        private fun conversion(type: String): String = if (type == "int") "d" else "s"

        private fun xmlComment(text: String): String = text.replace("--", "—")
    }
}
