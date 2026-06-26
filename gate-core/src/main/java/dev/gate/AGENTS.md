<!-- Parent: ../../../../../../gate-core/AGENTS.md -->
<!-- Generated: 2026-06-01 | Updated: 2026-06-26 -->

# dev.gate (メインパッケージ)

## Purpose
バックエンドの最上位 Java パッケージ。コントローラー・ミドルウェア・サービスクラスを格納する。`Main.java` がエントリポイントで、起動シーケンス・ミドルウェア登録・バックグラウンドジョブを制御する。

## Key Files

### エントリポイント
| File | Description |
|------|-------------|
| `Main.java` | アプリ起動、ミドルウェア登録、バックグラウンドポーラー（30s/60s）管理 |

### コントローラー
| File | Description |
|------|-------------|
| `AdminController.java` | 管理パネル用エンドポイント群（インスタンス管理・SQL実行・キャッシュ・YAML編集）。最も重要かつ複雑なクラス |
| `DataController.java` | イベント・フード・マップデータを MySQL から取得してキャッシュする公開 API |
| `AnnouncementsController.java` | お知らせ一覧の取得・キャッシュ（60秒ポーリング） |
| `CongestionController.java` | 混雑情報の取得・キャッシュ（30秒ポーリング） |
| `StarsController.java` | お気に入り（スター）の追加・削除。書き込みをキューに溜めて定期バッチで DB フラッシュ（`ConcurrentLinkedQueue` + 1分ポーリング） |
| `CfMetricsController.java` | Cloudflare メトリクス取得エンドポイント |
| `GcpMetricsController.java` | GCP（Cloud Run）メトリクス取得（`RUNMODE=azure` 時はスキップ） |

### ミドルウェア・フィルタ
| File | Description |
|------|-------------|
| `CloudflareIpFilter.java` | XFF ヘッダーで Cloudflare IP を検証するフィルタ。Caffeine キャッシュ（最大 50,000 エントリ）でマッチング結果を保持 |
| `ApiKeyAuth.java` | `X-API-Key` ヘッダーによる API キー認証 |
| `CfAccessAuth.java` | Cloudflare Access JWT 検証（管理パネル用）。JWKSを 50 分ごとに事前取得 |
| `FirebaseAppCheckAuth.java` | Firebase App Check JWT 検証ミドルウェア。外部ライブラリ不使用で `java.security.Signature` のみで署名検証。JWKS を 1 時間キャッシュ |
| `SecurityHeaders.java` | レスポンスへのセキュリティヘッダー付与（CSP・HSTS等）。全レスポンスに適用 |
| `RequestIdMiddleware.java` | `/stars` への POST/DELETE の冪等性保証。`X-Request-Id` (UUID) を 30 分 TTL でチェックし重複リクエストを 409 で弾く |
| `RequestMetrics.java` | リクエスト数・レイテンシ・エラー率を計測してメモリに保持 |

### サービス・ユーティリティ
| File | Description |
|------|-------------|
| `FirestoreRest.java` | GCP Firestore REST API クライアント（インスタンス管理・ブロードキャスト用） |
| `InstanceManager.java` | Firestore を通じたインスタンス自己登録・コマンド受信・メトリクス記録 |
| `CacheSync.java` | 書き込み起点のキャッシュ全同期（全コントローラー再構築 + broadcast + CF purge）をコアレッシング（3秒窓）付きで非同期実行 |
| `CfPurge.java` | Cloudflare キャッシュ purge ユーティリティ。`purge_everything` と URL 単位 purge を提供。`CF_API_TOKEN`, `CF_ZONE_ID`, `PUBLIC_BASE_URL` で設定 |
| `AuditLog.java` | 管理操作を `operation_logs` テーブルへ記録するユーティリティ（同期実行） |
| `DiscordWebhook.java` | エラー通知の Discord Webhook 送信ユーティリティ |
| `DataSeeder.java` | 起動時の DB 初期データ投入 |

## Subdirectories

| Directory | Purpose |
|-----------|---------|
| `core/` | 独自 HTTP フレームワーク "Gate"（ルーター・Context・DB・ロガー等） (see `core/AGENTS.md`) |
| `annotation/` | コントローラー・ルートアノテーション定義 (see `annotation/AGENTS.md`) |
| `logging/` | ロギングユーティリティ（ノイズフィルタ等） (see `logging/AGENTS.md`) |
| `mapping/` | HTTP メソッドマッピングアノテーション（@GetMapping等） (see `mapping/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- 新しいコントローラーを追加する場合は `@GateController` アノテーションを付与し、`Main.java` の `gate.register()` に追加する
- ミドルウェアの実行順序は `Main.java` の `gate.before()` 呼び出し順（SecurityHeaders → CloudflareIpFilter → ApiKeyAuth → CfAccessAuth → RequestMetrics）
- `AdminController.execSql` は SELECT 結果を最大 1,000 行に制限（`MAX_RESULT_ROWS`）。超過時は `truncated: true` をレスポンスに付与
- Firestore 操作は全て `FirestoreRest.get()` 経由。長時間かかる処理は `CompletableFuture.runAsync()` で非同期化（Jetty の 30s idle timeout 対策）

### Testing Requirements
- コントローラーのロジック変更後は `./gradlew test` を実行

### Common Patterns
- コントローラーメソッドは `Context ctx` を受け取り、`ctx.json()` / `ctx.status().json()` でレスポンスを返す
- DB 操作は必ず `try (Connection conn = Database.getConnection())` の try-with-resources で行う
- バリデーションパターン（`IDENTIFIER_PATTERN`, `UUID_PATTERN` 等）は `AdminController` の static final に集約

## Dependencies

### Internal
- `core/` — Database, Context, Gate, YamlRouteLoader
- `annotation/` — @GateController

### External
- Jetty — HTTP サーバー
- Jackson — JSON 生成
- HikariCP + MySQL — DB 接続
- Caffeine — IP マッチキャッシュ（CloudflareIpFilter）

<!-- MANUAL: -->
