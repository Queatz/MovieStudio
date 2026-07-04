plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlinxSerialization)
}

group = "app.moviestudio"
version = "1.0.0"
application {
    mainClass = "app.moviestudio.ApplicationKt"
}

// The `.env` file lives at the repo root; make sure `:server:run` looks for it there
// instead of the module directory, so secrets load without any manual `source` step.
tasks.withType<JavaExec>().configureEach {
    workingDir = rootProject.projectDir
}

dependencies {
    api(projects.core)
    implementation(libs.logback)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serializationKotlinxJson)
    implementation(libs.ktor.serverWebSockets)
    implementation(libs.ktor.serverCors)

    // Ktor HTTP client (outbound requests to Alibaba Model Studio and media downloads)
    implementation(libs.ktor.clientCore)
    implementation(libs.ktor.clientCio)
    
    // ArangoDB & Alibaba OSS
    implementation(libs.arangodb.java.driver)
    implementation(libs.aliyun.sdk.oss)
    implementation(libs.jaxb.api)
    implementation(libs.jaxb.runtime)

    // Loads secrets straight from the .env file (no need to `source` it manually)
    implementation(libs.dotenv.kotlin)

    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotlin.testJunit)
}
