// Top-level build file where you can add configuration options common to all sub-projects/modules.
// Every plugin any module uses is resolved once here, so the modules can apply them by alias
// without a version and Gradle never has to reconcile two.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}