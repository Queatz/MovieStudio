import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

// Compose Multiplatform 1.12.0-beta01 (and CMP material3 1.12.0-alpha03) map their Android target
// onto androidx.compose 1.12.0-beta01 / androidx.compose.material3 1.5.0-alpha22, whose AAR metadata
// demands AGP 9.1.0 while this project is pinned to AGP 9.0.1 (see libs.versions.toml). The 1.12
// upgrade is only needed for the Skia-based Desktop/Web font fallback fix and the matching Web
// material3 fix; Android resolves emoji/fallback glyphs through the OS font machinery, so keep the
// Android androidx.compose(.material/.material3) artifacts on the AGP-9.0.1-compatible versions
// (1.11.2 / material3 1.5.0-alpha13).
//
// Coil 3.5 transitively pulls androidx.lifecycle 2.11.0, whose AAR metadata likewise demands AGP
// 9.1.0. Keep the atomic lifecycle group at the AGP-9.0.1-compatible 2.9.4 (already present in the
// dependency graph); Coil's Compose integration only relies on stable lifecycle APIs available
// since 2.8.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group in setOf(
                "androidx.compose.animation",
                "androidx.compose.foundation",
                "androidx.compose.material",
                "androidx.compose.runtime",
                "androidx.compose.ui",
            )
        ) {
            useVersion("1.11.2")
            because("androidx.compose 1.12.0-beta01 requires AGP 9.1.0; project uses AGP 9.0.1")
        }
        if (requested.group == "androidx.compose.material3") {
            useVersion("1.5.0-alpha13")
            because("androidx.compose.material3 1.5.0-alpha22 requires AGP 9.1.0; project uses AGP 9.0.1")
        }
        if (requested.group == "androidx.lifecycle") {
            useVersion("2.9.4")
            because("androidx.lifecycle 2.11.0 requires AGP 9.1.0; project uses AGP 9.0.1")
        }
    }
}

dependencies {
    implementation(projects.app.shared)

    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "app.moviestudio"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.moviestudio"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}