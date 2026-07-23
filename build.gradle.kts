plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(ktorLibs.plugins.ktor)
}

group = "com.yuhan8954"
version = "1.0.0-SNAPSHOT"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

kotlin {
    jvmToolchain(21)
}
dependencies {
    implementation(ktorLibs.server.config.yaml)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.netty)
    implementation("io.ktor:ktor-server-auth:3.5.0")
    implementation("io.ktor:ktor-server-call-logging:3.5.0")
    implementation("io.ktor:ktor-server-content-negotiation:3.5.0")
    implementation("io.ktor:ktor-server-cors:3.5.0")
    implementation("io.ktor:ktor-server-default-headers:3.5.0")
    implementation("io.ktor:ktor-server-sessions:3.5.0")
    implementation("io.ktor:ktor-server-status-pages:3.5.0")
    implementation("io.ktor:ktor-server-websockets:3.5.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.server.testHost)
    testImplementation("io.ktor:ktor-client-content-negotiation:3.5.0")
    testImplementation("io.ktor:ktor-client-websockets:3.5.0")
}
