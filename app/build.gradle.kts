import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "jp.povo.manager"
    compileSdk = 37

    defaultConfig {
        applicationId = "jp.povo.manager"
        minSdk = 26
        targetSdk = 37
        /*
         * Taken from the release tag in CI, and only defaulted locally.
         *
         * These must match the tag the APK is attached to. If they do not, the
         * in-app updater compares the release's tag against the *installed*
         * BuildConfig.VERSION_NAME, finds it lower, and offers the same update
         * forever — installing it changes nothing it can see.
         *
         * versionCode is derived from the same string rather than tracked
         * separately, so the two cannot drift. The packing allows 0..99 for
         * minor and patch, which is ample and keeps the value monotonic.
         */
        // The `v` is stripped here as well as in the workflow, so passing a
        // raw tag by hand cannot put it into the user-visible version string.
        val version = providers.gradleProperty("povo.versionName")
            .getOrElse("0.1.0")
            .removePrefix("v")
        versionName = version
        versionCode = version
            .takeWhile { it.isDigit() || it == '.' }
            .split('.')
            .map { it.toIntOrNull() ?: 0 }
            .let { parts ->
                val (major, minor, patch) = List(3) { parts.getOrElse(it) { 0 } }
                major * 10_000 + minor * 100 + patch
            }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Where the in-app updater looks for releases. A property rather than a
        // constant so a fork does not have to patch source to point at its own
        // repository (see gradle.properties).
        val updateRepo = providers.gradleProperty("povo.updateRepo").getOrElse("uirooong/povo-android")
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }

    signingConfigs {
        /*
         * The release key, resolved from — in order — CI environment variables,
         * a local keystore.properties, and finally the debug key.
         *
         * The fallback matters for local work: R8 stripping the JNA bridge is a
         * failure that only shows at runtime, so the minified build has to stay
         * installable without the real key on hand. It is *not* good enough for
         * anything distributed, because Android refuses to upgrade an installed
         * app whose signature changed — a release signed with a different key
         * is one users must uninstall and reinstall, losing their sessions. CI
         * therefore fails loudly rather than falling back; see the workflows.
         */
        create("releaseLocal") {
            val env = System.getenv()
            val ciStore = env["POVO_KEYSTORE_PATH"]
            val props = rootProject.file("keystore.properties")
            when {
                ciStore != null -> {
                    storeFile = File(ciStore)
                    storePassword = env["POVO_KEYSTORE_PASSWORD"]
                    keyAlias = env["POVO_KEY_ALIAS"]
                    keyPassword = env["POVO_KEY_PASSWORD"]
                }

                props.exists() -> {
                    val values = Properties().apply { props.inputStream().use(::load) }
                    storeFile = rootProject.file(values.getProperty("storeFile"))
                    storePassword = values.getProperty("storePassword")
                    keyAlias = values.getProperty("keyAlias")
                    keyPassword = values.getProperty("keyPassword")
                }

                else -> {
                    val debugStore = File(System.getProperty("user.home"), ".android/debug.keystore")
                    if (debugStore.exists()) {
                        storeFile = debugStore
                        storePassword = "android"
                        keyAlias = "androiddebugkey"
                        keyPassword = "android"
                    }
                }
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            signingConfig = signingConfigs.getByName("releaseLocal")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        // Off by default in AGP 9; the debug-only spike entry point reads
        // BuildConfig.DEBUG to keep itself out of release builds.
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // The Glance renderer runs under Robolectric, which needs to boot an
            // Android environment first. That comfortably exceeds runTest's 10s
            // default on a cold JVM, and did fail intermittently inside a full
            // `gradlew build`. The work is bounded, so a generous ceiling costs
            // nothing and removes the flake.
            it.systemProperty("kotlinx.coroutines.test.default_timeout", "60s")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core-povo"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.glance.appwidget.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.test.junit)
}
