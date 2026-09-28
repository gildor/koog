plugins { kotlin("jvm") version "2.3.10" }
repositories { mavenCentral() }
kotlin { jvmToolchain(21) }
dependencies {
    implementation("io.github.gildor.koog:native-sessions:0.1.0-SNAPSHOT")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}
tasks.test {
    useJUnitPlatform()
    systemProperty("native.bridge.fixture", file("../bridges/test/fake-bridge.mjs").absolutePath)
}
