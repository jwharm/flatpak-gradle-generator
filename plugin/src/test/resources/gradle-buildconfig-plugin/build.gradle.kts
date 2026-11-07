plugins {
    id("io.github.jwharm.flatpak-gradle-generator")
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("com.github.gmazzo.buildconfig") version "6.1.2"
}

buildConfig {
    buildConfigField("APP_NAME", project.name)
    buildConfigField("APP_VERSION", provider { "\"${project.version}\"" })
}

repositories {
    mavenCentral()
}

tasks.flatpakGradleGenerator {
    outputFile = file("%s")
}
