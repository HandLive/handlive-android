package app.handlive.android.core.strings

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

/**
 * The generated Android resources say exactly what the catalog says (detailed design 0.12.2, card A1.5): every
 * Android string exists under its key-derived name in English (default) and Vietnamese, arguments land in their
 * placeholders, plurals pick CLDR forms, and nothing outside the catalog is generated. The catalog is read here with
 * an independent JSON parser, not the build generator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GeneratedStringsMatchCatalogTest {
    private val catalog =
        Json.parseToJsonElement(File(requireNotNull(System.getProperty("hl.catalog.file"))).readText()).jsonObject
    private val androidStrings =
        catalog.getValue("strings").jsonArray.map { it.jsonObject }.filter { entry ->
            entry.getValue("platforms").jsonArray.any { it.jsonPrimitive.content == "android" }
        }
    private val base: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun everyAndroidStringResolvesToTheCatalogTextInEnglishAndVietnamese() {
        listOf("en", "vi").forEach { lang ->
            val context = localized(lang)
            androidStrings.filter { it.getValue(lang) is JsonPrimitive }.forEach { entry ->
                val key = entry.getValue("key").jsonPrimitive.content
                val args = sampleArgs(entry)
                val expected = fill(entry.getValue(lang).jsonPrimitive.content, args)
                val id = context.resources.getIdentifier(key.replace('.', '_'), "string", context.packageName)
                val actual =
                    if (args.isEmpty()) {
                        context.getString(id)
                    } else {
                        context.getString(id, *args.values.toTypedArray())
                    }
                assertEquals("$lang $key", expected, actual)
            }
        }
    }

    @Test
    fun pluralsPickTheCldrFormsOfEachLanguage() {
        listOf("en", "vi").forEach { lang ->
            val context = localized(lang)
            androidStrings.filter { it.getValue(lang) is JsonObject }.forEach { assertPlural(context, lang, it) }
        }
    }

    private fun assertPlural(
        context: Context,
        lang: String,
        entry: JsonObject,
    ) {
        val key = entry.getValue("key").jsonPrimitive.content
        val forms = entry.getValue(lang).jsonObject.mapValues { it.value.jsonPrimitive.content }
        val id = context.resources.getIdentifier(key.replace('.', '_'), "plurals", context.packageName)
        listOf(1, 5).forEach { count ->
            val form = if (lang == "en" && count == 1) "one" else "other"
            val expected = forms.getValue(form).replace("{count}", count.toString())
            assertEquals("$lang $key $count", expected, context.resources.getQuantityString(id, count, count))
        }
    }

    @Test
    fun onlyCatalogStringsAreGenerated() {
        val expected =
            androidStrings
                .map {
                    it
                        .getValue("key")
                        .jsonPrimitive.content
                        .replace('.', '_')
                }.toSortedSet()
        val generated = (R.string::class.java.fields + R.plurals::class.java.fields).map { it.name }.toSortedSet()
        assertEquals(expected, generated)
    }

    private fun localized(lang: String): Context =
        base.createConfigurationContext(
            Configuration(base.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(lang))
            },
        )

    /** Distinct values per argument, in `args` order: names get a marker, `int` gets a number. */
    private fun sampleArgs(entry: JsonObject): Map<String, Any> =
        entry["args"]
            ?.jsonArray
            .orEmpty()
            .mapIndexed { index, arg ->
                val name =
                    arg.jsonObject
                        .getValue("name")
                        .jsonPrimitive.content
                val type =
                    arg.jsonObject
                        .getValue("type")
                        .jsonPrimitive.content
                name to (if (type == "int") index + SAMPLE_INT else "‹$name›")
            }.toMap()

    private fun fill(
        text: String,
        args: Map<String, Any>,
    ): String = args.entries.fold(text) { acc, (name, value) -> acc.replace("{$name}", value.toString()) }

    private companion object {
        const val SAMPLE_INT = 41
    }
}
