<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-06-01 | Updated: 2026-06-01 -->

# .github

## Purpose
GitHub Actions CI/CD パイプラインの定義と、自動化スクリプトを格納する。デプロイ（Cloud Run）とセキュリティレビュー（AI による diff 解析）の2本立て。

## Key Files

| File | Description |
|------|-------------|
| `security_review_ignore.txt` | セキュリティレビューで無視するファイルパターンのリスト |

## Subdirectories

| Directory | Purpose |
|-----------|---------|
| `scripts/` | Python 自動化スクリプト（AIセキュリティレビュー等） (see `scripts/AGENTS.md`) |
| `workflows/` | GitHub Actions ワークフロー定義 (see `workflows/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- セキュリティレビューのシークレットは GitHub Secrets で管理: `NVIDIA_API_KEY`, `GEMINI_API_KEY`, `SAKURA_API_KEY`, `DISCORD_WEBHOOK`
- `security_review_ignore.txt` に `fnmatch` パターン形式でパスを追記するとレビュー対象から除外できる

<!-- MANUAL: -->
