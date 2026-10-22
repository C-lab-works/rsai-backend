<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-06-01 | Updated: 2026-06-07 -->

# workflows

## Purpose
GitHub Actions ワークフロー定義。ビルド・本番デプロイ・debug 環境へのデプロイと、AI によるセキュリティレビューを管理する。

## Key Files

| File | Description |
|------|-------------|
| `deploy.yml` | master / debug への push でビルド・テスト・GHCR push。master は rsai-2026 へ自動デプロイ、debug 環境へのデプロイは手動実行のみ |
| `security-review.yml` | PR・push 時に `security_review.py` を実行して AI セキュリティレビューを投稿 |

## For AI Agents

### Working In This Directory
- `deploy.yml` は `GITHUB_WORKFLOW_FILE` 環境変数で `AdminController` から参照される（デプロイ状況を管理パネルに表示するため）。ファイル名を変更する場合は `AdminController.GITHUB_WORKFLOW_FILE` も更新すること
- `deploy.yml` のジョブ構成:
  - `build`: ubuntu-latest。テスト実行、GHCR へのイメージ push、バイナリ・ビルドログ・テストレポートをアーティファクトとして90日保管する
  - `deploy-prod`: master のみ。self-hosted runner(ラベル `rsai-2026`)で `sudo /usr/local/sbin/rsai-backend-deploy <sha>` を実行する。このスクリプトが `:prod` と `:prev` のタグを切り替え、ヘルスチェックに失敗したら自動で切り戻す
  - `deploy-debug`: 手動実行(workflow_dispatch)で `deploy_debug=true` のときだけ動く。self-hosted runner(ラベル `tatsunote2`)が必要
  - `sync-debug`: master のビルド成功後、master を debug にマージして push する(`[skip ci]` 付きのため再実行されない)
- セキュリティレビューは DeepSeek → Gemini → Qwen3-Coder の順にフォールバック

<!-- MANUAL: -->
