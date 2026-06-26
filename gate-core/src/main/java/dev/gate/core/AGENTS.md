<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-06-01 | Updated: 2026-06-26 -->

# core (独自フレームワーク)

## Purpose
軽量 HTTP フレームワーク "Gate" の実装。Jetty をラップしてルーティング・コンテキスト・ミドルウェア・WebSocket・データベース接続プールを提供する。このフレームワーク自体がプロダクトコードの一部であり、外部ライブラリではない。

## Key Files

| File | Description |
|------|-------------|
| `Gate.java` | フレームワークのコア。ルート登録・ミドルウェア管理・Jetty サーバー起動。virtual threads で動作 |
| `Router.java` | パスパラメータ対応のルートマッチャー（`:id` スタイル） |
| `Context.java` | リクエスト/レスポンスのラッパー。`ctx.json()`, `ctx.pathParam()`, `ctx.query()` 等を提供 |
| `Handler.java` | `void handle(Context ctx)` の関数インターフェース |
| `Database.java` | HikariCP コネクションプール管理。`Database.init()` で初期化、`Database.getConnection()` で取得 |
| `ConfigLoader.java` | `config.yml` を読み込み `Config` オブジェクトに変換 |
| `Config.java` | アプリ設定の POJO（ポート・DB設定・CORS等） |
| `YamlRouteLoader.java` | `routes.yaml` を解析して `Gate` に GET ルートを登録。テーブル名・カラム名を allowlist 検証 |
| `Logger.java` | SLF4J ラッパー。クラスをコンストラクタ引数に取るシンプルなロガー |
| `ErrorHandler.java` | 未処理例外のハンドラインターフェース |
| `Http.java` | 共有 `HttpClient` シングルトン。`CfPurge` や `FirebaseAppCheckAuth` 等から参照される |
| `Json.java` | 共有 `ObjectMapper` シングルトン。全クラスで Jackson 設定を統一するために使用 |
| `TtlCache.java` | TTL 付きシンプルなインメモリキャッシュ。期限切れエントリを遅延削除する軽量実装 |
| `ClientErrorException.java` | 400 系クライアントエラーを表すチェック例外。`ErrorHandler` でキャッチして 4xx を返す |
| `HttpCache.java` | HTTP レスポンスキャッシュユーティリティ |
| `GateServer.java` | 起動済み Jetty Server のラッパー（停止等） |
| `WsAdapter.java` / `WsContext.java` / `WsHandler.java` | WebSocket サポート |

## For AI Agents

### Working In This Directory
- `Gate.java` の `start()` は一度しか呼べない（`started` フラグで保護）。設定変更は `start()` 前に行うこと
- `Database.getConnection()` は必ず try-with-resources で使用すること（コネクションリーク防止）
- `YamlRouteLoader` はテーブル名・カラム名を `^[a-zA-Z_][a-zA-Z0-9_]*$` パターンで検証。SQL インジェクション対策のため、バックティッククォートではなく allowlist で防護している
- virtual threads（`Thread.ofVirtual()`）を使用しているため、スレッドブロッキング IO は問題ない

### Common Patterns
- ミドルウェアは `Handler` インターフェースを実装し `gate.before()` に渡す
- `ctx.isHalted()` が true の場合はルートハンドラが実行されない（`CloudflareIpFilter` 等が halt する）
- CORS はオリジン単位で `Access-Control-Allow-Origin` をエコーバック（ワイルドカード不可）

## Dependencies

### External
- Jetty 11.0.20 — HTTP サーバー基盤
- HikariCP 5.1.0 — コネクションプール
- SnakeYAML 2.2 — routes.yaml パース
- Jackson 2.17.0 — JSON レスポンス生成

<!-- MANUAL: -->
