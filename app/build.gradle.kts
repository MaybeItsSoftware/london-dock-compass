import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Load signing credentials from keystore.properties (kept out of version control).
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) load(keystorePropsFile.inputStream())
}

/**
 * Finds the upload keystore whichever way storeFile is written.
 *
 * CI writes an absolute path; local setups have historically used a bare filename that resolved
 * only because Gradle read it relative to this module. Now that two modules sign with the same
 * key, "relative to whoever is asking" is no longer good enough.
 */
val keystoreFile: File? = keystoreProps.getProperty("storeFile")?.let { path ->
    sequenceOf(File(path), rootProject.file(path), rootProject.file("app/$path"))
        .firstOrNull { it.exists() }
}

android {
    namespace = "uk.co.maybeitssoftware.londondockcompass"
    compileSdk = 36

    defaultConfig {
        applicationId = "uk.co.maybeitssoftware.londondockcompass"
        minSdk = 30
        targetSdk = 36
        // The odd slot: one applicationId ships two bundles and Play wants a unique code for
        // each. Wear takes the higher one so a device matching both gets the watch build.
        // scripts/set-gradle-version.sh keeps this and :mobile in step.
        versionCode = 102001
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
        // Currency checks are time-varying: they start failing the day someone else ships
        // something -- an unrelated library publishing a release, or Google publishing a new API
        // level, which is what OldTargetApi turned targetSdk = 36 into. Not a signal worth
        // breaking a build over, and keeping them out is what makes the rest safe to enforce.
        // Bumping compileSdk/targetSdk stays a deliberate decision, not one a lint release forces.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "OldTargetApi")
        warningsAsErrors = true
        abortOnError = true
    }
}

// kotlinOptions was removed in Kotlin 2.4; the compiler options DSL replaces it.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    // Geometry, ranking, the TfL client, the caches and the cross-device sync.
    implementation(project(":core"))

    implementation(libs.wear)
    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.wear.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.core.splashscreen)
    implementation(libs.watchface.complications.data.source.ktx)
    implementation(libs.wear.compose.material)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.wear.tiles)
    implementation(libs.protolayout)
    implementation(libs.protolayout.material)
    implementation(libs.protolayout.expression)
    implementation(libs.concurrent.futures.ktx)
    // Tiles hand back ListenableFuture; without real Guava the API type is an empty stub.
    implementation(libs.guava)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)
}
