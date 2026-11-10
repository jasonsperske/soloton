package dev.soloton.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.util.Base64
import javax.inject.Inject

/** Writes the base64 release keystore from the environment to a file under build/. Never cached. */
@DisableCachingByDefault(because = "Holds a secret")
abstract class DecodeKeystoreTask : DefaultTask() {
    @get:Input
    @get:Optional
    abstract val keystoreBase64: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun decode() {
        val bytes = Base64.getMimeDecoder().decode(keystoreBase64.get())
        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeBytes(bytes)
        }
    }
}

/** Unpacks gdx-platform-*-natives-<abi>.jar files into <outputDir>/<abi>/lib*.so. */
@CacheableTask
abstract class ExtractGdxNativesTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val nativeJars: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val archives: ArchiveOperations

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun extract() {
        val out = outputDir.get().asFile
        fs.delete { delete(out) }
        val pattern = Regex("""natives-(.+)\.jar$""")
        nativeJars.files.forEach { jar ->
            val abi = pattern.find(jar.name)?.groupValues?.get(1)
                ?: error("Unexpected natives jar name: ${jar.name}")
            fs.copy {
                from(archives.zipTree(jar)) { include("*.so") }
                into(out.resolve(abi))
            }
        }
    }
}
