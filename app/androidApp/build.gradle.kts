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

// Coil 3.5 transitively pulls androidx.lifecycle 2.11.0, whose AAR metadata demands AGP 9.1.0 while
// this project is pinned to AGP 9.0.1 (see libs.versions.toml). Keep the atomic lifecycle group at
// the AGP-9.0.1-compatible 2.9.4 (already present in the dependency graph); Coil's Compose
// integration only relies on stable lifecycle APIs available since 2.8.
configurations.all {
    resolutionStrategy.eachDependency {
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