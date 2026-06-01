<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-06-01 | Updated: 2026-06-01 -->

# workflows

## Purpose
GitHub Actions ワークフロー定義。Cloud Run へのデプロイと、AI によるセキュリティレビューの2種類を管理する。

## Key Files

| File | Description |
|------|-------------|
| `cloud-run-deploy.yml` | master push 時に GraalVM native image をビルドし Cloud Run へデプロイ |
| `security-review.yml` | PR・push 時に `security_review.py` を実行して AI セキュリティレビューを投稿 |

## For AI Agents

### Working In This Directory
- `cloud-run-deploy.yml` は `GITHUB_WORKFLOW_FILE` 環境変数で `AdminController` から参照される（デプロイ状況を管理パネルに表示するため）
- セキュリティレビューは DeepSeek → Gemini → Qwen3-Coder の順にフォールバック

<!-- MANUAL: -->
