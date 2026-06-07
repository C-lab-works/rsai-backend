<!-- Parent: ../../../../../../gate-core/AGENTS.md -->
<!-- Generated: 2026-06-07 | Updated: 2026-06-07 -->

# dev.gate (テストパッケージ)

## Purpose
JUnit 5 ユニットテスト群。DB 不要な純粋関数・バリデーションロジックの回帰テストを中心とする。

## Key Files

| File | Description |
|------|-------------|
| `AdminControllerSqlTest.java` | `AdminController` の SQL 検証ロジック（`normalizeSql`, `matchedBlockedFragment`, `isWriteKeyword`, `splitStatements`, `stripSqlComments`）の回帰テスト |

## Subdirectories

| Directory | Purpose |
|-----------|---------|
| `core/` | フレームワーク core のテスト（`YamlRouteLoader` キャッシュ設定テスト等） |

## For AI Agents

### Working In This Directory
- 実行: プロジェクトルートから `./gradlew test`
- `AdminControllerSqlTest` はDB接続不要。package-private ヘルパメソッドのみをテストする
- 新しいテストを追加する際は同じパッケージ（`dev.gate`）に配置し、テスト対象の package-private メソッドに直接アクセスする
- 統合テスト（実 MySQL 接続が必要なもの）はここには置かず、別途 `@Tag("integration")` で区別すること

### Testing Requirements
- `./gradlew test` でパスすることを確認してからコミットする
- テスト追加時は必ず境界値・エスケープ処理・コメント混入などの edge case を網羅すること（SQL インジェクション防止ロジックのため）

<!-- MANUAL: -->
