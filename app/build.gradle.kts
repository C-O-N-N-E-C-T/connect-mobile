import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// local.properties is git-ignored (the repo is PUBLIC). Missing keys default to "" so the app still
// builds and shows "not configured" instead of failing.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun localProp(key: String): String = localProps.getProperty(key, "").trim()

fun javaString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.connect.mobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.connect.mobile"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SUPABASE_URL", javaString(localProp("SUPABASE_URL")))
        buildConfigField("String", "SUPABASE_ANON_KEY", javaString(localProp("SUPABASE_ANON_KEY")))
        buildConfigField("String", "API_BASE_URL", javaString(localProp("API_BASE_URL")))
    }

    buildTypes {
        debug {
            // Debug only: fake OSP-60 backend (default on). Set CONNECT_FAKE_BACKEND=false to use API_BASE_URL.
            val useFake = localProp("CONNECT_FAKE_BACKEND").ifEmpty { "true" }.toBoolean()
            buildConfigField("boolean", "USE_FAKE_BACKEND", useFake.toString())
        }
        release {
            // Release never uses the fake (the class is not even in the release source set).
            buildConfigField("boolean", "USE_FAKE_BACKEND", "false")
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = false
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)

    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
