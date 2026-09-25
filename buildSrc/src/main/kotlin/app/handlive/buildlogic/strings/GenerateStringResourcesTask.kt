package app.handlive.buildlogic.strings

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Generates `values/strings.xml` (English, default) and `values-<lang>/strings.xml` from the UI string catalog into
 * the build directory (detailed design 0.12.2). Nothing generated is committed: every build regenerates from the
 * catalog, so resources cannot drift from it.
 */
@CacheableTask
abstract class GenerateStringResourcesTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val catalogFile: RegularFileProperty

    /** Only strings whose `platforms` contain this value are generated (`android`). */
    @get:Input
    abstract val platform: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val catalog = UiStringCatalogParser.parse(catalogFile.get().asFile)
        val root = outputDirectory.get().asFile
        root.deleteRecursively()
        AndroidStringResourceWriter(catalog, platform.get()).files().forEach { (path, text) ->
            root.resolve(path).apply { parentFile.mkdirs() }.writeText(text)
        }
    }
}
