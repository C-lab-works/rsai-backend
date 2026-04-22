plugins {
    id("java")
    id("com.gradleup.shadow") version "8.3.6"
    id("org.graalvm.buildtools.native") version "0.10.3"
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
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("tatsu-gate")
            mainClass.set("app.Main")
            buildArgs.addAll(
                "--no-fallback",
                "--initialize-at-build-time=org.slf4j,ch.qos.logback",
                "-H:+ReportExceptionStackTraces",
                "--enable-url-protocols=http,https"
            )
        }
    }
    metadataRepository {
        enabled.set(true)
    }
}

tasks.shadowJar {
    manifest {
        attributes["Main-Class"] = "app.Main"
    }
    archiveBaseName.set("app")
    archiveClassifier.set("")
    archiveVersion.set("")
}
