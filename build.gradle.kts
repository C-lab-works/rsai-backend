plugins {
    id("java")
}

group = "dev.gate"
version = "1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    implementation("org.eclipse.jetty:jetty-server:11.0.20")
    compileOnly("jakarta.servlet:jakarta.servlet-api:5.0.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
}

tasks.test {
    useJUnitPlatform()
}