<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-06-01 | Updated: 2026-06-01 -->

# logging

## Purpose
ロギング関連のユーティリティクラス。特に GraalVM native image 環境での Jetty ノイズ抑制に対応する。

## Key Files

| File | Description |
|------|-------------|
| `CancelledKeyExceptionFilter.java` | `CancelledKeyException`（Jetty NIO セレクタの無害なノイズ）をログから除外するフィルタ |
| `LogBuffer.java` | ログメッセージのリングバッファ。管理パネルの `/admin/instances/{id}/command` の `logs` コマンドで最新ログを取得するために使用 |

## For AI Agents

### Working In This Directory
- `CancelledKeyException` は Jetty の NIO セレクタが接続切断時に発生させる無害な例外。`Gate.java` のグローバルハンドラと合わせて二重に抑制している
- `LogBuffer` のサイズ変更時はメモリ消費に注意（native image では JVM ヒープ上限 1536 MB）

<!-- MANUAL: -->
