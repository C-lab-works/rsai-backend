<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-06-01 | Updated: 2026-06-07 -->

# workflows

## Purpose
GitHub Actions ワークフロー定義。master/debug ブランチへのデプロイと、AI によるセキュリティレビューの2種類を管理する。

## Key Files

| File | Description |
|------|-------------|
| `deploy.yml` | master push → Cloud Run (GraalVM native image), debug push → tatsunote2 self-hosted runner |
| `security-review.yml` | PR・push 時に `security_review.py` を実行して AI セキュリティレビューを投稿 |

## For AI Agents

### Working In This Directory
- `deploy.yml` は `GITHUB_WORKFLOW_FILE` 環境変数で `AdminController` から参照される（デプロイ状況を管理パネルに表示するため）。ファイル名を変更する場合は `AdminController.GITHUB_WORKFLOW_FILE` も更新すること
- `deploy.yml` は Spot VM（tatsunote2）を起動してから self-hosted runner 上でビルドする。start-runner ジョブがタイムアウト（5分）すると後続ジョブも失敗する
- セキュリティレビューは DeepSeek → Gemini → Qwen3-Coder の順にフォールバック

<!-- MANUAL: -->
