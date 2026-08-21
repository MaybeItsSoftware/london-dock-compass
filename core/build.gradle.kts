plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Everything both apps agree about: the geometry, the ranking, the TfL client, the caches and the
 * offline fallback. No UI toolkit at all — the watch draws with Wear Compose and protolayout, the
 * phone with Material 3, and neither of those opinions belongs down here.
 */
android {
    namespace = "uk.co.maybeitssoftware.londondockcompass.core"
    compileSdk = 36

    defaultConfig {
        // Lower than the watch's floor on purpose. Wear OS 3 sets API 30 for :app, but nothing in
        // here needs it — java.time.Instant, at API 26, is the highest bar this module clears —
        // and a phone should not inherit a watch's minimum.
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

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
    api(libs.kotlinx.serialization.json)
    api(libs.google.play.services.location)
    // The Data Layer, for keeping saved docks and the pinned destination in step across devices.
    api(libs.play.services.wearable)
    implementation(libs.androidx.core.ktx)
    implementation(libs.davidmoten.geo)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    testImplementation(libs.junit)
}
