plugins {
    `java-library`
}

group = property("modGroup") as String
version = property("modVersion") as String

base {
    archivesName = property("modId") as String
}

repositories {
    mavenCentral()
    maven("https://nexus.gtnewhorizons.com/repository/releases/")
}

dependencies {
    // Provided by the game at runtime (RFB ships with lwjgl3ify, ASM ships with RFB).
    // Transitive deps are disabled: we only need the API classes for compilation.
    compileOnly("com.gtnewhorizons.retrofuturabootstrap:RetroFuturaBootstrap:1.1.0") { isTransitive = false }
    compileOnly("org.ow2.asm:asm-tree:9.6")
    // Compile against the ancient log4j the game actually ships (2.0-beta9) so any accidental
    // use of newer Logger overloads becomes a compile error instead of a runtime crash.
    compileOnly("org.apache.logging.log4j:log4j-api:2.0-beta9")
    compileOnly("org.jetbrains:annotations:24.0.0")
}

java {
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 8
}
