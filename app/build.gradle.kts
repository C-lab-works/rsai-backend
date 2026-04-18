plugins {
    id("java")
    id("com.gradleup.shadow") version "8.3.6"
}

group = "dev.gate"
version = "1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation(project(":gate-core"))
    implementation("org.eclipse.jetty:jetty-server:11.0.20")
    implementation("org.eclipse.jetty:jetty-servlet:11.0.20")
    implementation("jakarta.servlet:jakarta.servlet-api:5.0.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    implementation("org.yaml:snakeyaml:2.2")
    implementation("org.eclipse.jetty.websocket:websocket-jetty-server:11.0.20")
    implementation("org.eclipse.jetty.websocket:websocket-jetty-api:11.0.20")
}

tasks.shadowJar {
    manifest {
        attributes["Main-Class"] = "app.Main"
    }
    archiveBaseName.set("app")
    archiveClassifier.set("")
    archiveVersion.set("")
}
