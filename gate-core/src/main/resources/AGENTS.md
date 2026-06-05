<!-- Parent: ../../../../../../gate-core/AGENTS.md -->
<!-- Generated: 2026-06-01 | Updated: 2026-06-01 -->

# resources

## Purpose
アプリケーションのランタイムリソースファイル群。設定・DBスキーマ・宣言的ルート定義・GraalVM native image メタデータを格納する。

## Key Files

| File | Description |
|------|-------------|
| `config.yml` | アプリ設定（ポート・DB接続・CORS等）。`ConfigLoader` が起動時に読み込む |
| `routes.yaml` | 宣言的 GET ルート定義。`YamlRouteLoader` が `Gate` に自動登録する |
| `schema.sql` | MySQL テーブル定義。`DataSeeder` が初回起動時に適用 |
| `logback.xml` | Logback ロギング設定（Cloud Run の Cloud Logging 向けフォーマット） |

## Subdirectories

| Directory | Purpose |
|-----------|---------|
| `META-INF/native-image/dev.gate/` | GraalVM native image 設定（reflect-config, resources-config, native-image.properties） |

## For AI Agents

### Working In This Directory
- `routes.yaml` に新しい GET エンドポイントを追加する場合、`table` と `columns` が実 DB に存在することを確認すること（`AdminController#validateRoutesYamlDb` と同じルール）
- テーブル名・カラム名は英数字とアンダースコアのみ許可（`^[a-zA-Z_][a-zA-Z0-9_]*$`）
- native image でリフレクションが必要な新規クラスは `reflect-config.json` に追加する

### Common Patterns
- `routes.yaml` のフォーマット:
  ```yaml
  routes:
    - path: /api/example
      table: example_table
      columns: [id, name, value]
  ```

<!-- MANUAL: -->
