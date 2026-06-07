<!-- Parent: ../../../../../../../gate-core/src/main/resources/AGENTS.md -->
<!-- Generated: 2026-06-07 | Updated: 2026-06-07 -->

# native-image (dev.gate)

## Purpose
GraalVM native image ビルドに必要なメタデータ設定。リフレクション・リソース・初期化タイミングを明示的に宣言する。

## Key Files

| File | Description |
|------|-------------|
| `reflect-config.json` | native image でリフレクションを使用するクラス・メソッド・フィールドの一覧 |
| `resource-config.json` | native image に同梱するリソースファイル（`config.yml`, `routes.yaml`, `schema.sql`, `logback.xml` 等）の一覧 |
| `native-image.properties` | native image ビルド引数（`--initialize-at-run-time=com.mysql.cj`, `-H:EnableURLProtocols=http,https` 等） |

## For AI Agents

### Working In This Directory
- 新規クラスでリフレクションが必要になった場合（Jackson のデシリアライズ対象 POJO 等）は `reflect-config.json` に追加する
- 新規リソースファイルを追加した場合は `resource-config.json` の `resources.includes` に追加する
- `native-image.properties` の `--initialize-at-run-time` は MySQL コネクタの静的初期化を回避するためのもの。新たに static initializer が問題になるライブラリが現れた場合はここに追加する
- `--gc=G1` と `-R:MaxHeapSize=1536m` は `build.gradle.kts` の `graalvmNative` ブロックで別途指定されており、このファイルには含まれない

<!-- MANUAL: -->
