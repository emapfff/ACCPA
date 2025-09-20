plugins {
    kotlin("jvm") version "1.8.0"
    application
}

group = "org.stella"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.antlr:antlr4:4.9.3")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.2")
    implementation(kotlin("script-runtime"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("org.stella.MainKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
