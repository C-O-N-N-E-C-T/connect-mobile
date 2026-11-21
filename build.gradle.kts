// Kotlin comes only from these Gradle plugins (version catalog); never install Kotlin/Gradle system-wide.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
