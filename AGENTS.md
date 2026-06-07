<!-- Generated: 2026-06-01 | Updated: 2026-06-07 -->

# rsai-backend

## Purpose
学校祭用の公開 API・管理パネル向けバックエンドサービス。Java 21 + Jetty HTTP サーバーで構築され、GraalVM native image としてパッケージ化して Cloud Run 上で稼働する。MySQL でイベントデータを管理し、Firestore でインスタンスのリアルタイム状態を保持する。

## Key Files

| File | Description |
|------|-------------|
| `Dockerfile` | GraalVM native image ビルドと Cloud Run 用コンテナイメージ定義 |
| `.dockerignore` | Docker ビルドコンテキスト除外設定 |
| `gradlew` / `gradlew.bat` | Gradle Wrapper スクリプト（ビルドのエントリポイント） |
| `API.md` | 公開 API エンドポイントのリファレンスドキュメント |

## Subdirectories

| Directory | Purpose |
|-----------|---------|
| `gate-core/` | メインの Java ソースモジュール（コントローラー・ミドルウェア・独自フレームワーク） (see `gate-core/AGENTS.md`) |
| `.github/` | CI/CD ワークフローとセキュリティレビュースクリプト (see `.github/AGENTS.md`) |
| `deploy/` | nginx 等デプロイ設定テンプレート (see `deploy/AGENTS.md`) |
| `docs/` | 補足ドキュメント（フローダイアグラム等） (see `docs/AGENTS.md`) |
| `tools/` | 開発・運用補助スクリプト（負荷テスト等） (see `tools/AGENTS.md`) |

## For AI Agents

### Working In This Directory(not recommended)
- ビルド: `./gradlew shadowJar`（JVM fat-jar）または `./gradlew nativeCompile`（native image）
- native image は Linux x64 + GraalVM が必要。ローカルでは `./gradlew run` で JVM モード実行可能
- 環境変数は `gate-core/src/main/resources/config.yml` と実行時 env で管理
- Cloudflare IP フィルタをスキップするには `SKIP_CF_IP_CHECK=true` を設定

## Working with debug branch
- ビルド: debugブランチにpushしたら(ssh root@tatsunote2)にデプロイされます。(tailscale、コンテナ名:rsai-debug)
- CFあり、debugv2.tatsut.jp (本番環境になるべく寄せています。)
- 環境変数は、 /opt/rsai-debug.env で、そこに書かれていないものは本番環境と同じgithub secretsの内容が使われます。
- 管理者ページのテストは不可能です。(代用手段を検討中)
- DBはtatsunote2で動いてるデモDBに接続されます。

### Testing Requirements
- `./gradlew test` で JUnit 5 テストを実行
- 統合テストは実 MySQL 接続が必要

### Common Patterns
- HTTP ルートは `@GetMapping` / `@PostMapping` 等のアノテーションでコントローラーに宣言
- 宣言的 GET ルートは `routes.yaml` で定義し `YamlRouteLoader` が自動登録
- ミドルウェアは `Main.java` の `gate.before()` / `gate.after()` に順序依存で登録

## Dependencies

### External
- Java 21 (GraalVM) — 言語・ランタイム
- Jetty 11.0.20 — HTTP サーバー（+ WebSocket）
- Jackson 2.17.0 — JSON シリアライゼーション
- SnakeYAML 2.2 — YAML パース
- HikariCP 5.1.0 + mysql-connector-j 8.3.0 — MySQL 接続プール
- Caffeine 3.1.8 — インメモリ LRU キャッシュ（IP マッチキャッシュ）
- Logback 1.5.13 / SLF4J 2.0.9 — ロギング

<!-- MANUAL: -->
