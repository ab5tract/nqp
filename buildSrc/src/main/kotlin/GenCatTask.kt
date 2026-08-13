import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Concatenates NQP sources with #?if preprocessing (see [GenCat]).
 *
 * [sourcePaths] holds the repo-relative paths exactly as the Makefile passes
 * them to gen-cat.pl — they appear verbatim in `#line 1 NQP::<path>` markers,
 * so they are an @Input in their own right. [rootDir] resolves them to files.
 */
@CacheableTask
abstract class GenCatTask : DefaultTask() {
    @get:Input
    abstract val backend: Property<String>

    @get:Input
    abstract val stage: Property<String>

    @get:Input
    abstract val sourcePaths: ListProperty<String>

    @get:Input
    abstract val rootDir: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun run() {
        val root = File(rootDir.get())
        val sources = sourcePaths.get().map { it to File(root, it) }
        output.get().asFile.bufferedWriter(Charsets.UTF_8).use { out ->
            GenCat.concat(backend.get(), stage.get(), sources, out)
        }
    }
}
