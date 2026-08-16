import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * Release signing is driven by an untracked `keystore.properties` at the repository root.
 * See README.md ("Signature de l'APK release"). When the file is absent the release build
 * still succeeds — it simply falls back to the debug signing identity so that contributors
 * can build without owning the production key.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = keystoreProperties.getProperty("storeFile")?.let { file(it).exists() } == true

android {
    namespace = "com.radardeal.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.radardeal.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    androidResources {
        localeFilters += listOf("fr", "en")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
            buildConfigField("boolean", "VERBOSE_LOGGING", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "VERBOSE_LOGGING", "false")
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    // Ship the APK under the name the buyer receives, not "app-release.apk".
    applicationVariants.all {
        val variant = this
        val baseVersion = defaultConfig.versionName
        outputs.all {
            val output = this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output?.outputFileName = if (variant.buildType.name == "release") {
                "RadarDeal-Android-v$baseVersion.apk"
            } else {
                "RadarDeal-Android-v$baseVersion-${variant.buildType.name}.apk"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "META-INF/*.version",
                "kotlin/**",
                "DebugProbesKt.bin",
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true

            all {
                // Each ActivityScenario launch in AppLaunchTest leaves a Compose Recomposer
                // and its coroutines behind — Robolectric does not tear the Android runtime
                // down between tests the way a real device does. Accumulated over a whole
                // suite in one JVM that ends in OutOfMemoryError, which kotlinx-coroutines-test
                // then reports against whichever test starts next rather than the one that
                // caused it. A fresh JVM per test class keeps the leak from accumulating; the
                // heap bump covers the heaviest single class.
                it.forkEvery = 1
                it.maxHeapSize = "1536m"
                it.jvmArgs("-XX:MaxMetaspaceSize=768m")
            }
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = true
        warningsAsErrors = false
        htmlReport = true
        xmlReport = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.webkit)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.animation)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
}
