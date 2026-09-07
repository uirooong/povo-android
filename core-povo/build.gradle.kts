import jp.povo.gradle.CargoNdkTask
import jp.povo.gradle.UniffiBindgenTask

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

// ---------------------------------------------------------------------------
// povo-core (Rust) build wiring
//
// Two artifacts come out of the submodule, both written into this module's
// standard source dirs (the layout povo-core/BUILDING.md prescribes) so that no
// sourceSets manipulation is needed. Both paths are gitignored.
//
//   1. per-ABI libpovo_core.so -> src/main/jniLibs/<abi>/
//   2. uniffi Kotlin bindings  -> src/main/kotlin/uniffi/povo_core/
//
// NOTE: the bindings are generated from a HOST build, not from the Android .so.
// povo-core's release profile sets `strip = true`, which removes the
// UNIFFI_META_* symbols that uniffi's --library mode reads. Pointing bindgen at
// the stripped .so exits 0 and silently emits no files. The host debug cdylib is
// unstripped, so that is what we hand to bindgen.
// ---------------------------------------------------------------------------

val ndkVersionUsed = "28.2.13676358"

val povoCoreDir = rootProject.layout.projectDirectory.dir("povo-core")
val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs")
val kotlinSrcDir = layout.projectDirectory.dir("src/main/kotlin")
val bindingsPkgDir = layout.projectDirectory.dir("src/main/kotlin/uniffi")

val abiList: List<String> =
    (providers.gradleProperty("povo.abis").orNull ?: "arm64-v8a")
        .split(",").map(String::trim).filter(String::isNotEmpty)
val cargoProfile: String = providers.gradleProperty("povo.cargoProfile").orNull ?: "release"
val skipRustBuild: Boolean =
    (providers.gradleProperty("povo.skipRustBuild").orNull ?: "false").toBoolean()

/**
 * The new AGP DSL no longer exposes `android.sdkDirectory`, so resolve the SDK
 * the way the Gradle plugin itself does: local.properties first, then the
 * conventional environment variables.
 *
 * This is resolved eagerly to a plain String at configuration time rather than
 * held as a Provider. A Provider built with a `.map { }` lambda declared in a
 * build script carries a reference to the script object, and the configuration
 * cache cannot serialize that. Reading through `providers` still registers the
 * file and the environment variables as proper configuration inputs.
 */
val sdkDirectory: String = run {
    val fromLocalProperties = providers
        .fileContents(rootProject.layout.projectDirectory.file("local.properties"))
        .asText.orNull
        ?.lineSequence()
        ?.map(String::trim)
        ?.firstOrNull { it.startsWith("sdk.dir=") }
        ?.substringAfter("=")
        // local.properties escapes the drive colon and the path separators.
        ?.replace("\\:", ":")
        ?.replace("\\\\", "/")
        ?.takeIf(String::isNotBlank)

    fromLocalProperties
        ?: providers.environmentVariable("ANDROID_HOME").orNull
        ?: providers.environmentVariable("ANDROID_SDK_ROOT").orNull
        ?: error(
            "Android SDK not found. Set sdk.dir in local.properties, " +
                "or the ANDROID_HOME environment variable."
        )
}

/** Rust sources whose change should retrigger a native rebuild. */
fun rustInputs() = files(
    povoCoreDir.file("Cargo.toml"),
    povoCoreDir.dir("crates"),
)

val cargoNdkBuild = tasks.register<CargoNdkTask>("cargoNdkBuild") {
    group = "povo"
    description = "Cross-compiles povo-core into per-ABI .so files for jniLibs."
    rustSources.from(rustInputs())
    abis.set(abiList)
    profile.set(cargoProfile)
    platformLevel.set(26)
    sdkDir.set(sdkDirectory)
    ndkVersion.set(ndkVersionUsed)
    crateDir.set(povoCoreDir)
    outputDir.set(jniLibsDir)
}

val uniffiBindgen = tasks.register<UniffiBindgenTask>("uniffiBindgen") {
    group = "povo"
    description = "Generates the uniffi Kotlin bindings for povo-core."
    rustSources.from(rustInputs())
    crateDir.set(povoCoreDir)
    bindgenOutRoot.set(kotlinSrcDir)
    outputDir.set(bindingsPkgDir)
}

android {
    namespace = "jp.povo.manager.core"
    compileSdk = 37
    ndkVersion = ndkVersionUsed

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

// `povo.skipRustBuild=true` detaches the Rust build from the Android build so
// that Kotlin-only iterations reuse whatever is already in src/main.
tasks.named("preBuild") {
    if (!skipRustBuild) {
        dependsOn(cargoNdkBuild, uniffiBindgen)
    }
}

dependencies {
    // uniffi's Kotlin bindings call into the .so through JNA; the @aar variant
    // carries the native JNA dispatch libs for every Android ABI.
    api(libs.jna) { artifact { type = "aar" } }
    api(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}
