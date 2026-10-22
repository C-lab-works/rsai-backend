<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-06-01 | Updated: 2026-06-01 -->

# gate-core

## Purpose
バックエンドの唯一の Gradle サブモジュール。Java 25 ソースコード全体、リソースファイル、GraalVM native image 設定を含む。独自軽量フレームワーク "Gate" を内包し、Jetty をラップした HTTP ルーティング・ミドルウェア・DI を提供する。

## Key Files

| File | Description |
|------|-------------|
| `build.gradle.kts` | Gradle ビルド定義（依存関係・GraalVM 設定・Shadow JAR） |

## Subdirectories

| Directory | Purpose |
|-----------|---------|
| `src/main/java/dev/gate/` | Java ソースコードのメインパッケージ (see `src/main/java/dev/gate/AGENTS.md`) |
| `src/main/resources/` | 設定ファイル・YAML ルート・DB スキーマ・native-image 設定 (see `src/main/resources/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- 依存追加は `build.gradle.kts` の `dependencies {}` ブロックに追加し、GraalVM reflect-config も必要に応じて更新
- `--gc=G1` と `-R:MaxHeapSize=1536m` は Cloud Run 時代（2 GiB コンテナ）に決めた値。現在の本番 rsai-2026 の LXC はメモリ 1.5 GiB なので、変更時は実メモリとの兼ね合いに注意
- native image でリフレクションが必要なクラスは `src/main/resources/META-INF/native-image/dev.gate/reflect-config.json` に登録

### Testing Requirements
- `./gradlew test`（プロジェクトルートから）

### Common Patterns
- ビルド成果物: `build/libs/gate-core-1.0-SNAPSHOT-all.jar`（Shadow JAR）
- native image 成果物: `build/native/nativeCompile/app`

## Dependencies

### External
- `com.gradleup.shadow` — fat JAR ビルド
- `org.graalvm.buildtools.native` — native image ビルド

<!-- MANUAL: -->
