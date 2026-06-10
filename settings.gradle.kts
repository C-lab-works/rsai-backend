rootProject.name = "tatsu-gate"

include("gate-core")

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // toolchain (JDK 25) がローカルに無い環境でも自動ダウンロードできるようにする。
    // Docker ビルドではコンテナの GraalVM 25 が languageVersion に合致するため取得は走らない。
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
