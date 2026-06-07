<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-06-07 | Updated: 2026-06-07 -->

# deploy

## Purpose
本番・デバッグ環境向けのデプロイ設定ファイル群。現在は debug 環境（tatsunote2）向けの nginx リバースプロキシ設定を含む。

## Subdirectories

| Directory | Purpose |
|-----------|---------|
| `nginx/` | nginx リバースプロキシ設定テンプレート (see `nginx/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- このディレクトリの設定は GitHub Actions `deploy.yml` から直接は参照されない。tatsunote2 上に手動で配置・適用する
- 本番環境（Cloud Run）の nginx 設定は Cloudflare が担うため、このディレクトリには含まれない

<!-- MANUAL: -->
