package app.handlive.android.core.strings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * No user-facing text outside the catalog (C20, 0.12.5). Lint's HardcodedText covers XML, but lint does not see the
 * generated resource directory, so MissingTranslation cannot guard hand-written resources, and nothing in lint
 * covers Compose or Kotlin calls. This test fails when a module
 * - declares its own `<string>` or `<plurals>` resource (every string is generated from the catalog in both
 *   languages), or
 * - passes a string literal with letters to a UI text API in main sources (Compose text, notification texts,
 *   toasts, accessibility labels).
 */
class UiTextSourceGuardTest {
    private val root = File(requireNotNull(System.getProperty("hl.android.root")))
    private val modules =
        root
            .walkTopDown()
            .onEnter { it.name !in SKIPPED_DIRS }
            .filter { it.isDirectory && it.name == "main" && it.parentFile?.name == "src" }
            .toList()

    @Test
    fun noModuleDeclaresItsOwnStringResources() {
        val offending =
            modules.flatMap { main ->
                main
                    .resolve("res")
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "xml" && it.parentFile?.name?.startsWith("values") == true }
                    .filter { file -> RESOURCE_ELEMENT.containsMatchIn(file.readText()) }
                    .map { it.relativeTo(root).path }
                    .toList()
            }
        assertEquals(
            "string resources must come from ../shared/strings/ui-strings.json",
            emptyList<String>(),
            offending,
        )
    }

    @Test
    fun noUiTextLiteralsInKotlinSources() {
        val offending =
            modules.flatMap { main ->
                main
                    .resolve("kotlin")
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .flatMap { file ->
                        file.readLines().mapIndexedNotNull { index, line ->
                            "${file.relativeTo(
                                root,
                            ).path}:${index + 1}".takeIf { UI_TEXT_LITERAL.containsMatchIn(line) }
                        }
                    }.toList()
            }
        assertEquals("UI text must come from string resources", emptyList<String>(), offending)
    }

    private companion object {
        val SKIPPED_DIRS = setOf("build", ".gradle", ".git", ".kotlin", "buildSrc")
        val RESOURCE_ELEMENT = Regex("<(string|plurals|string-array)\\b")

        /**
         * A UI text API called with a literal that contains a letter (empty strings and placeholders pass). Compose
         * animation `label`s are debugging names, not UI text, so `label =` is not matched.
         */
        val UI_TEXT_LITERAL =
            Regex(
                "(BasicText|Text|HLButton)\\(\\s*(text\\s*=\\s*)?\"[^\"]*\\p{L}|" +
                    "\\b(text|title|footer|value|contentDescription|stateDescription|unavailableReason)" +
                    "\\s*=\\s*\"[^\"]*\\p{L}|" +
                    "\\.(setContentTitle|setContentText|setTicker|setText|setTitle|setSubtitle|setLabel)\\(\\s*\"|" +
                    "Toast\\.makeText\\([^,]+,\\s*\"",
            )
    }
}
