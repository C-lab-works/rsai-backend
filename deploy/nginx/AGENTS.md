<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-06-07 | Updated: 2026-06-07 -->

# nginx

## Purpose
debug 環境（tatsunote2）向けの nginx リバースプロキシ設定テンプレート。Cloud Run と同等のヘッダー構成を再現し、debugv2.tatsut.jp に HTTPS でアクセスできるようにする。

## Key Files

| File | Description |
|------|-------------|
| `debugv2.tatsut.jp.conf.tmpl` | nginx server block 定義。HTTP→HTTPS リダイレクト + HTTPS プロキシ（port 8082 → upstream） |

## For AI Agents

### Working In This Directory
- `.conf.tmpl` は実際の nginx では `.conf` にリネームして `/etc/nginx/sites-enabled/` に配置する
- upstream は `tatsunote2:8082`（Docker コンテナの公開ポート）。スケール時はここにサーバーを追加する
- SSL 証明書は tatsunote2 上の Let's Encrypt (`/etc/letsencrypt/live/debugv2.tatsut.jp/`) から読み込む
- `proxy_set_header X-Forwarded-For` で本物のクライアント IP を転送するため、`CloudflareIpFilter` が正しく動作する

<!-- MANUAL: -->
