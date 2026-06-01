<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-06-01 | Updated: 2026-06-01 -->

# scripts

## Purpose
GitHub Actions から呼び出される Python 自動化スクリプト群。現在は PR・push をトリガーに AI が diff を解析するセキュリティレビュースクリプトのみを含む。

## Key Files

| File | Description |
|------|-------------|
| `security_review.py` | PR/push の diff を複数 AI プロバイダにフォールバック送信してセキュリティレビューを実行。結果を PR コメントまたは Discord に投稿 |

## For AI Agents

### Working In This Directory
- プロバイダは環境変数 `PROVIDER_N_NAME/KEY/BASE_URL/MODEL` で順序付きフォールバック設定
- 現在のプロバイダ順: DeepSeek V4 Pro（NVIDIA NIM）→ Gemini → Qwen3-Coder（Sakura AI）
- `SECURITY_PROMPT` はレビュー指示。`description`/`recommendation` フィールドはすでに日本語指示済み
- `format_comment()` の出力はそのまま GitHub PR コメントになる
- HIGH severity の発見がある場合 `sys.exit(1)` でワークフローを失敗させる

### Common Patterns
- AI レスポンスは JSON 抽出（`raw.find("{")` ～ `raw.rfind("}")` でブラケットを探す）しているため、モデルが余分なテキストを付けても動作する
- diff は `MAX_DIFF_CHARS`（デフォルト 30,000 字）で切り捨てられる

<!-- MANUAL: -->
