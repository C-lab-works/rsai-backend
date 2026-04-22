plugins {
    id("java-library")
    id("application")
    id("io.github.goooler.shadow") version "8.1.8"
}

application {
    mainClass.set("dev.gate.Main")
}

group = "dev.gate"
version = "1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation("org.eclipse.jetty:jetty-server:11.0.20")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0") {
        exclude(group = "net.bytebuddy")
    }
    implementation("org.yaml:snakeyaml:2.2")
    implementation("org.eclipse.jetty.websocket:websocket-jetty-server:11.0.20")
    implementation("org.eclipse.jetty.websocket:websocket-jetty-api:11.0.20")
    implementation("org.eclipse.jetty.websocket:websocket-servlet:11.0.20")
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.4.11")

    // Database
    implementation("com.mysql:mysql-connector-j:8.4.0")
    implementation("com.zaxxer:HikariCP:5.1.0")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}
