import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Same signing material as the watch: one applicationId means Play requires one upload key.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) load(keystorePropsFile.inputStream())
}

/** Same key as the watch, found the same way -- see the note in app/build.gradle.kts. */
val keystoreFile: File? = keystoreProps.getProperty("storeFile")?.let { path ->
    sequenceOf(File(path), rootProject.file(path), rootProject.file("app/$path"))
        .firstOrNull { it.exists() }
}

android {
    // Distinct namespace so the two apps get their own R and BuildConfig, but the *same*
    // applicationId — Play treats them as one app and hands each device the bundle that fits.
    namespace = "uk.co.maybeitssoftware.londondockcompass.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "uk.co.maybeitssoftware.londondockcompass"
        // Lower than the watch's floor: a phone app has no reason to require Wear OS 3.
        minSdk = 26
        targetSdk = 36
        versionCode = 102000
        versionName = "1.2.0"
    }

    signingConfigs {
        keystoreFile?.let { keystore ->
            create("release") {
                storeFile = keystore
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures { compose = true }

    lint {
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion")
        warningsAsErrors = true
        abortOnError = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    testImplementation(libs.junit)
    debugImplementation(libs.ui.tooling)
}
