<!-- Parent: ../../../../../../gate-core/AGENTS.md -->
<!-- Generated: 2026-06-26 | Updated: 2026-06-26 -->

# core (テスト)

## Purpose
Gate フレームワークの `core/` パッケージに対する JUnit 5 ユニットテスト群。HTTP キャッシュ・TTL キャッシュ・WebSocket アップグレード・YAML ルートローダーの動作を検証する。

## Key Files

| File | Description |
|------|-------------|
| `HttpCacheEtagMatchTest.java` | ETag ベースのキャッシュ検証テスト（`If-None-Match` → 304 応答） |
| `HttpCacheServeTest.java` | キャッシュヒット・ミス・TTL 期限切れのシナリオテスト |
| `TtlCacheTest.java` | `TtlCache` の保存・取得・TTL 失効動作のテスト |
| `WsUpgradeAuthTest.java` | WebSocket アップグレードリクエストの認証検証テスト |
| `YamlRouteLoaderCacheTest.java` | `YamlRouteLoader` のルート定義読み込みとキャッシュ動作のテスト |

## For AI Agents

### Working In This Directory
- `./gradlew test`（プロジェクトルートから）で全テストを実行
- DB 接続を必要とするテストは統合テストとして分類され、実 MySQL が必要
- 新規クラスを `core/` に追加した場合は対応するテストをここに追加する

### Common Patterns
- テストクラス名は `<対象クラス>Test.java` の命名規則に従う
- JUnit 5 (`@Test`, `@BeforeEach` 等) を使用

<!-- MANUAL: -->
