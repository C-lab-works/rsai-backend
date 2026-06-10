plugins {
    id("application")
    id("com.gradleup.shadow") version "9.4.1"
    id("org.graalvm.buildtools.native") version "1.1.1"
}

application {
    mainClass.set("dev.gate.Main")
}

group = "dev.gate"
version = "1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
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

    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

    // データベース（gate-core のコンパイルに必要）
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("com.mysql:mysql-connector-j:8.3.0")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

graalvmNative {
    metadataRepository {
        enabled.set(true)
    }
    binaries {
        named("main") {
            imageName.set("app")
            mainClass.set("dev.gate.Main")
            buildArgs.addAll(
                "--no-fallback",
                "--initialize-at-build-time=org.slf4j",
                "-H:+UnlockExperimentalVMOptions",
                "-H:+AddAllCharsets",
                // libc 以外を静的リンク（旧 -H:+StaticExecutableWithDynamicLibC の安定版 API。
                // GraalVM 25 で旧オプションは非推奨）。ランタイムイメージは glibc が必要 (oraclelinux:10-slim)。
                "--static-nolibc",
                // G1 GC を使用。Oracle GraalVM (GFTC) でのみ利用可。
                // Linux x64 でのみサポート (Cloud Run は satisfies)。
                // - Serial GC より stop-the-world ポーズが短く、並列 GC でスループットも高い。
                // - 世代型を採用しており、リクエストスコープの短命オブジェクトが多いサーバーワークロードに適している。
                // トレードオフ: G1 のメタデータ分だけメモリ使用量がそもそも多い (そのためコンテナメモリを 2GiB へ拡張済)。
                "--gc=G1",
                // ヒープ上限を 1536 MB に明示。Cloud Run コンテナメモリ 2 GiB に対し、
                // スタック / Direct バッファ / ネイティブ部分用に ~500 MB ヘッドルームを残す。
                // それ以上を使うようになったら OOM-kill の手前で GC が動くようにし、
                // コンテナクラッシュより OutOfMemoryError のほうがトリアージしやすい。
                "-R:MaxHeapSize=1536m",
                // 最大最適化（Oracle GraalVM GFTC 専用）。
                // GraalVM for JDK 24+ では -O3 で GraalNN（ML ベースの静的プロファイル推論）が
                // 自動有効になる（実プロファイルを --pgo で渡した場合のみ ML 推論は無効化される）。
                "-O3"
            )
        }
    }
}
