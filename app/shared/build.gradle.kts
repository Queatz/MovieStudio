import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
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
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jsMain.dependencies {
            implementation(libs.wrappers.browser)
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

