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

// Integration tests always run against the offline MockAIService, even when real Qwen
// credentials are present in `.env` (keeps the suite deterministic and network-free).
tasks.withType<Test>().configureEach {
    systemProperty("moviestudio.forceMockAI", "true")
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
    
    // ArangoDB & Alibaba OSS
    implementation("com.arangodb:arangodb-java-driver:7.1.0")
    implementation("com.aliyun.oss:aliyun-sdk-oss:3.17.4")
    implementation("javax.xml.bind:jaxb-api:2.3.1")
    implementation("org.glassfish.jaxb:jaxb-runtime:2.3.3")

    // Loads secrets straight from the .env file (no need to `source` it manually)
    implementation("io.github.cdimascio:dotenv-kotlin:6.5.1")

    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotlin.testJunit)
}
