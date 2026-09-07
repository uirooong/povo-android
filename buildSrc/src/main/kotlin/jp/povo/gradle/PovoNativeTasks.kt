package jp.povo.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * Builds povo-core (Rust) for the Android ABIs and drops the resulting
 * `libpovo_core.so` files where the Android plugin expects jniLibs.
 *
 * These task types live in buildSrc rather than in the build script because a
 * task class declared inside a `.gradle.kts` captures a reference to the script
 * object, which the configuration cache cannot serialize.
 */
abstract class CargoNdkTask : DefaultTask() {
    @get:Inject
    abstract val execOps: ExecOperations

    @get:InputFiles
    abstract val rustSources: ConfigurableFileCollection

    @get:Input
    abstract val abis: ListProperty<String>

    /** "release" or "debug". */
    @get:Input
    abstract val profile: Property<String>

    /** Android API level to compile against; must match the module's minSdk. */
    @get:Input
    abstract val platformLevel: Property<Int>

    @get:Input
    abstract val sdkDir: Property<String>

    @get:Input
    abstract val ndkVersion: Property<String>

    @get:Internal
    abstract val crateDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        val ndk = File(sdkDir.get()).resolve("ndk").resolve(ndkVersion.get())
        require(ndk.isDirectory) {
            "NDK ${ndkVersion.get()} not found at $ndk. " +
                "Install it with: sdkmanager \"ndk;${ndkVersion.get()}\""
        }

        val args = mutableListOf("ndk", "--platform", platformLevel.get().toString())
        abis.get().forEach { args += listOf("-t", it) }
        args += listOf("-o", outputDir.get().asFile.absolutePath, "build", "-p", "povo-core")
        if (profile.get() == "release") args += "--release"

        execOps.exec {
            commandLine(listOf("cargo") + args)
            workingDir(crateDir.get().asFile)
            environment("ANDROID_NDK_HOME", ndk.absolutePath)
            environment("ANDROID_HOME", sdkDir.get())
        }
    }
}

/**
 * Generates the uniffi Kotlin bindings for povo-core.
 *
 * The bindings are generated from a HOST build, not from the Android `.so`.
 * povo-core's release profile sets `strip = true`, which removes the
 * `UNIFFI_META_*` symbols that uniffi's `--library` mode reads; handing bindgen
 * a stripped library exits 0 and silently writes nothing. The host debug cdylib
 * keeps its symbols, so that is what gets passed in.
 *
 * The Android-flavoured Kotlin backend is selected by the crate's own
 * `uniffi.toml`, which bindgen picks up automatically in `--library` mode.
 */
abstract class UniffiBindgenTask : DefaultTask() {
    @get:Inject
    abstract val execOps: ExecOperations

    @get:InputFiles
    abstract val rustSources: ConfigurableFileCollection

    @get:Internal
    abstract val crateDir: DirectoryProperty

    /** Where bindgen is told to write; it creates a `uniffi/` package underneath. */
    @get:Internal
    abstract val bindgenOutRoot: DirectoryProperty

    /** The package dir bindgen owns. Scoped so hand-written code is never wiped. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val cwd = crateDir.get().asFile
        val os = System.getProperty("os.name").lowercase()
        val hostLib = when {
            os.contains("win") -> "povo_core.dll"
            os.contains("mac") -> "libpovo_core.dylib"
            else -> "libpovo_core.so"
        }

        execOps.exec {
            commandLine("cargo", "build", "-p", "povo-core")
            workingDir(cwd)
        }

        val lib = cwd.resolve("target/debug/$hostLib")
        require(lib.isFile) { "host cdylib not found at $lib" }

        val owned = outputDir.get().asFile
        owned.deleteRecursively()

        execOps.exec {
            commandLine(
                "cargo", "run", "--quiet", "-p", "povo-core", "--bin", "uniffi-bindgen", "--",
                "generate", "--library", lib.absolutePath,
                "--language", "kotlin", "--out-dir", bindgenOutRoot.get().asFile.absolutePath,
            )
            workingDir(cwd)
        }

        val produced = owned.walkTopDown().filter { it.extension == "kt" }.toList()
        check(produced.isNotEmpty()) {
            "uniffi-bindgen produced no Kotlin files. This usually means the library passed " +
                "to --library was stripped of its UNIFFI_META_* symbols."
        }
        logger.lifecycle("uniffi bindings: " + produced.joinToString { it.name })
    }
}
