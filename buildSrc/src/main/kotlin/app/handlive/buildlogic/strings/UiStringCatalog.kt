package app.handlive.buildlogic.strings

import groovy.json.JsonSlurper
import java.io.File

/** A translation: plain text, or CLDR plural forms (`one`, `other`…) selected by the `int` argument `count`. */
sealed interface Translation {
    data class Text(val text: String) : Translation

    data class Plural(val forms: Map<String, String>) : Translation
}

data class StringArg(val name: String, val type: String)

/** One entry of `shared/strings/ui-strings.json` (detailed design 0.12.1). */
data class UiString(
    val key: String,
    val translations: Map<String, Translation>,
    val args: List<StringArg>,
    val platforms: Set<String>,
    val comment: String,
)

data class UiStringCatalog(
    val sourceLanguage: String,
    val languages: List<String>,
    val strings: List<UiString>,
)

/** Invalid catalog data: the generator stops the build instead of emitting wrong resources. */
class UiStringCatalogException(message: String) : RuntimeException(message)

/**
 * Reads the catalog. `check_strings.py` in handlive-shared is the full checker (0.12.5); this parser re-checks only
 * what would make the Android resources wrong: missing languages, placeholders without an argument, bad plurals.
 */
object UiStringCatalogParser {
    private val KEY = Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+){1,4}$")
    private val PLACEHOLDER = Regex("\\{([a-z0-9_]+)}")
    private val PLURAL_CATEGORIES = setOf("zero", "one", "two", "few", "many", "other")
    private val ARG_TYPES = setOf("string", "int", "double")

    fun parse(file: File): UiStringCatalog {
        val root = JsonSlurper().parse(file) as? Map<*, *> ?: fail("the catalog root is not an object")
        val languages = (root["languages"] as? List<*>)?.map { it as String } ?: fail("missing `languages`")
        val source = root["source_language"] as? String ?: fail("missing `source_language`")
        if (source !in languages) fail("source language $source is not in `languages`")
        val entries = root["strings"] as? List<*> ?: fail("missing `strings`")
        val strings = entries.map { parseEntry(it as? Map<*, *> ?: fail("a string entry is not an object"), languages) }
        val duplicates = strings.groupBy { it.key.replace('.', '_') }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) fail("keys collide as resource names: $duplicates")
        return UiStringCatalog(source, languages, strings)
    }

    private fun parseEntry(entry: Map<*, *>, languages: List<String>): UiString {
        val key = entry["key"] as? String ?: fail("an entry has no `key`")
        if (!KEY.matches(key)) fail("$key: invalid key")
        val args =
            (entry["args"] as? List<*>).orEmpty().map {
                val arg = it as? Map<*, *> ?: fail("$key: invalid `args`")
                val type = arg["type"] as? String ?: fail("$key: argument without type")
                if (type !in ARG_TYPES) fail("$key: unknown argument type $type")
                StringArg(arg["name"] as? String ?: fail("$key: argument without name"), type)
            }
        val translations = languages.associateWith { lang -> parseTranslation(key, lang, entry[lang]) }
        translations.forEach { (lang, translation) -> checkPlaceholders(key, lang, translation, args) }
        val platforms = (entry["platforms"] as? List<*>)?.map { it as String }?.toSet() ?: fail("$key: no platforms")
        return UiString(key, translations, args, platforms, entry["comment"] as? String ?: fail("$key: no comment"))
    }

    private fun parseTranslation(key: String, lang: String, value: Any?): Translation =
        when (value) {
            is String -> {
                if (value.isBlank()) fail("$key: empty `$lang`")
                Translation.Text(value)
            }

            is Map<*, *> -> {
                val forms = value.entries.associate { (k, v) -> (k as String) to (v as? String ?: fail("$key: $lang.$k")) }
                if (forms.keys.any { it !in PLURAL_CATEGORIES } || "other" !in forms) fail("$key: invalid plural in $lang")
                Translation.Plural(forms)
            }

            else -> {
                fail("$key: missing `$lang`")
            }
        }

    private fun checkPlaceholders(key: String, lang: String, translation: Translation, args: List<StringArg>) {
        val names = args.map { it.name }.toSet()
        val texts = if (translation is Translation.Plural) translation.forms.values else listOf((translation as Translation.Text).text)
        texts.forEach { text ->
            val used = PLACEHOLDER.findAll(text).map { it.groupValues[1] }.toSet()
            if (!names.containsAll(used)) fail("$key: `$lang` uses placeholders ${used - names} without `args`")
        }
        if (translation is Translation.Plural && args.none { it.name == "count" && it.type == "int" }) {
            fail("$key: a plural needs the int argument `count`")
        }
    }

    private fun fail(message: String): Nothing = throw UiStringCatalogException(message)
}
