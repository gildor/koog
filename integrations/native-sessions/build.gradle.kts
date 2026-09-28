plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    `java-library`
    jacoco
}

group = "io.github.gildor.koog"
version = "0.1.0-SNAPSHOT"

repositories { mavenCentral() }

kotlin { jvmToolchain(21) }

dependencies {
    api("ai.koog:agents-core:1.3.0")
    api("com.github:copilot-sdk-java:1.0.14")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    api("com.fasterxml.jackson.core:jackson-databind:2.22.2")

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
    testImplementation("ai.koog:agents-test:1.3.0")
    testImplementation("ai.koog:agents-cli:1.3.0-beta")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.mockito:mockito-core:5.18.0")
}

tasks.test { useJUnitPlatform() }
val installBridgeDependencies by tasks.registering(Exec::class) {
    workingDir("bridges")
    commandLine("npm", "ci", "--no-audit", "--no-fund")
    inputs.files("bridges/package.json", "bridges/package-lock.json")
    outputs.dir("bridges/node_modules")
}
val bridgeTest by tasks.registering(Exec::class) {
    dependsOn(installBridgeDependencies)
    workingDir("bridges")
    commandLine("node", "--test", "test/protocol.test.mjs")
}
jacoco { toolVersion = "0.8.13" }
tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports { xml.required = true; html.required = true }
}
tasks.check { dependsOn(tasks.jacocoTestReport, bridgeTest) }
tasks.register<JacocoReport>("jacocoCombinedReport") {
    // Opt-in live execution must happen first; it consumes provider quota.
    dependsOn(tasks.test)
    executionData(fileTree("build/jacoco") { include("*.exec") })
    sourceSets(sourceSets.main.get())
    reports { xml.required = true; html.required = true }
}

// JVM-only integration: provide the same test entry point as Koog modules.
tasks.register("jvmTest") { dependsOn(tasks.test) }
