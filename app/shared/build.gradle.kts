import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// Compose Multiplatform 1.12.0-beta01 (and CMP material3 1.12.0-alpha03) map their Android target
// onto androidx.compose 1.12.0-beta01 / androidx.compose.material3 1.5.0-alpha22, whose AAR metadata
// demands AGP 9.1.0 while this project is pinned to AGP 9.0.1 (see libs.versions.toml). The 1.12
// upgrade is only needed for the Skia-based Desktop/Web font fallback fix and the matching Web
// material3 fix; Android resolves emoji/fallback glyphs through the OS font machinery, so keep the
// Android androidx.compose(.material/.material3) artifacts on the AGP-9.0.1-compatible versions
// (1.11.2 / material3 1.5.0-alpha13). Likewise, keep androidx.lifecycle (pulled transitively at
// 2.11.0, which also demands AGP 9.1.0) on the compatible 2.9.4.
//
// Scope this to the Android configurations only: the JS/WasmJs targets resolve their own KMP
// variants of these artifacts, so forcing versions there breaks their dependency resolution.
configurations.matching { it.name.contains("android", ignoreCase = true) }.configureEach {
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

val generateBuildConfig = tasks.register("generateBuildConfig") {
    val outputDir = layout.buildDirectory.dir("generated/source/buildconfig/commonMain/kotlin")
    outputs.dir(outputDir)
    val env = (project.findProperty("env") as? String)
        ?: System.getenv("ENV")
        ?: System.getenv("APP_ENV")
        ?: "dev"
    val baseUrl = (project.findProperty("apiBaseUrl") as? String)
        ?: (project.findProperty("baseUrl") as? String)
        ?: System.getenv("API_BASE_URL")
        ?: System.getenv("BASE_URL")
        ?: "http://localhost:8080"

    inputs.property("env", env)
    inputs.property("baseUrl", baseUrl)

    doLast {
        val configFile = outputDir.get().file("app/moviestudio/BuildConfig.kt").asFile
        configFile.parentFile.mkdirs()
        configFile.writeText("""
            package app.moviestudio

            object BuildConfig {
                const val ENV = "$env"
                const val BASE_URL = "$baseUrl"
            }
        """.trimIndent())
    }
}

kotlin {
    jvm()

    js {
        browser()
        binaries.executable()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    androidLibrary {
        namespace = "app.moviestudio.app.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        androidResources {
            enable = true
        }
        withHostTest {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            // Ktor engine so Coil's KtorNetworkFetcherFactory can load network images on Android.
            implementation(libs.ktor.clientOkhttp)
        }
        commonMain {
            kotlin.srcDir(generateBuildConfig)
            dependencies {
                api(projects.core)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.compose.runtime)
                implementation(libs.compose.foundation)
                implementation(libs.compose.material3)
                implementation(libs.compose.ui)
                implementation(libs.compose.components.resources)
                implementation(libs.compose.uiToolingPreview)
                implementation(libs.androidx.lifecycle.viewmodelCompose)
                implementation(libs.androidx.lifecycle.runtimeCompose)
                // Coil 3: standard Compose image component (AsyncImage) with a Ktor network fetcher.
                implementation(libs.coil.compose)
                implementation(libs.coil.network.ktor3)
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmMain.dependencies {
            // Ktor engine so Coil's KtorNetworkFetcherFactory can load network images on desktop.
            implementation(libs.ktor.clientCio)
        }
        jsMain.dependencies {
            implementation(libs.wrappers.browser)
            // Ktor engine so Coil's KtorNetworkFetcherFactory can load network images in the browser.
            implementation(libs.ktor.clientJs)
        }
        wasmJsMain.dependencies {
            // Ktor engine so Coil's KtorNetworkFetcherFactory can load network images in the browser.
            implementation(libs.ktor.clientJs)
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "app.moviestudio.shared.resources"
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}

