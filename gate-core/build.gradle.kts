plugins {
    id("application")
    id("com.gradleup.shadow") version "9.4.1"
    id("org.graalvm.buildtools.native") version "0.10.3"
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
    implementation("ch.qos.logback:logback-classic:1.5.13")

    // データベース（gate-core のコンパイルに必要）
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("com.mysql:mysql-connector-j:8.3.0")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("app")
            mainClass.set("dev.gate.Main")
            buildArgs.addAll(
                "--no-fallback",
                "--initialize-at-build-time=org.slf4j",
                "-H:+ReportExceptionStackTraces",
                "-H:+UnlockExperimentalVMOptions",
                "-H:+AddAllCharsets",
                "-H:+StaticExecutableWithDynamicLibC",
                // G1 GC: 2 vCPU コンテナと 7000 req/s ピークを想定した場合、
                // シリアル GC よりスループットが出やすく、GC ポーズも短くなる。
                // Linux x86_64 でのみサポートされている (Cloud Run は Linux x86_64)。
                "--gc=G1",
                // ヒープ上限を 1536 MB に明示。Cloud Run コンテナメモリ 2 GiB に対し、
                // スタック / Direct バッファ / ネイティブ部分用に ~500 MB ヘッドルームを残す。
                // それ以上を使うようになったら OOM-kill の手前で GC が動くようにし、
                // コンテナクラッシュより OutOfMemoryError のほうがトリアージしやすい。
                "-R:MaxHeapSize=1536m"
            )
        }
    }
}
