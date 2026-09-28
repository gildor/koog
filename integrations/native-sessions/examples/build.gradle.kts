plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}
repositories { mavenCentral() }
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
}
application { mainClass = "io.github.gildor.koog.nativeagents.LiveJobsKt" }
tasks.withType<JavaExec>().configureEach { workingDir(rootProject.projectDir) }
tasks.register<JavaExec>("nativeJobs") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.github.gildor.koog.nativeagents.NativeJobsKt"
}
