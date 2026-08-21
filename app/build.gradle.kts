import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * Signing configuration.
 *
 * By default the build uses the throwaway development key committed in
 * `keystore/mosaic-dev.jks` (see docs/SIGNING.md). To sign with a real key,
 * drop a `signing-release.properties` file at the repository root with:
 *
 *   storeFile=/absolute/path/to/your.jks
 *   storePassword=...
 *   keyAlias=...
 *   keyPassword=...
 *
 * That file is git-ignored and takes precedence over the development key.
 */
val signingProps = Properties().apply {
    val real = rootProject.file("signing-release.properties")
    val dev = rootProject.file("keystore/mosaic-dev.properties")
    when {
        real.exists() -> real.inputStream().use { load(it) }
        dev.exists() -> dev.inputStream().use { load(it) }
    }
}
val hasSigning = signingProps.getProperty("storeFile") != null

android {
    namespace = "app.mosaic.privatevault"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.mosaic.privatevault"
        // Android 12. Everything below that lacks the Keystore / biometric
        // guarantees this app relies on, and it keeps the security code simple.
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // No analytics, no crash reporter, no tracker: nothing to configure.
        // French-only UI; there is no half-translated second locale to ship.
        resourceConfigurations += listOf("fr")
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = rootProject.file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
                // minSdk 31 makes the v1 JAR signature dead weight. v3 is what
                // allows the signing key to be rotated later without
                // reinstalling, which matters for a side-loaded private app.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    // A single universal APK is what gets side-loaded; the AAB carries the splits.
    bundle {
        language { enableSplit = false }
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
        }
    }

    buildFeatures {
        compose = true
        // Needed only for BuildConfig.DEBUG, which gates all logging.
        buildConfig = true
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        checkReleaseBuilds = true
        // Dependency-freshness advisories are informational and change weekly;
        // they are not a signal about this codebase. Upgrades happen
        // deliberately, in their own commit, with the test suite as the gate.
        disable += setOf(
            "MissingTranslation",
            "GradleDependency",
            "NewerVersionAvailable",
            "AndroidGradlePluginVersion",
            // Lint suggests dropping the -v26 qualifier on the adaptive-icon
            // folder because minSdk is 31. AAPT2 then fails to resolve
            // mipmap/ic_launcher at all, so the qualifier stays.
            "ObsoleteSdkInt",
        )
        htmlReport = true
        textReport = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.sqlite)
    implementation(libs.sqlcipher.android)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
