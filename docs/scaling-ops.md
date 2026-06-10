# スケール運用手順(学園祭当日向け)

高トラフィック(同時1万人以上)を捌くための運用メモ。
アーキテクチャ前提: 公開 GET は全てメモリキャッシュ + ETag/304/gzip 配信で、
Cloudflare の Cache Rule(設定済み)がオリジン到達リクエストを大幅に削減する。

## 当日の MIN_SCALE 引き上げ(コールドスタート回避)

通常時は `MIN_SCALE=0`(アイドル時インスタンス0、初回アクセスに約5秒のコールドスタート)。
当日朝に引き上げ、終了後に戻す。

```bash
# 即時反映(リビジョン再デプロイ不要)
gcloud run services update rsai-backend \
  --min-instances=2 --region=asia-northeast1 --project=<PROJECT_ID>

# 終了後に戻す
gcloud run services update rsai-backend \
  --min-instances=0 --region=asia-northeast1 --project=<PROJECT_ID>
```

注意: 恒久値は GitHub repo variable `MIN_SCALE` が握っており、**次回 master デプロイで
variable の値に上書きされる**。当日中にデプロイする可能性があるなら variable も合わせて変更する
(Settings → Secrets and variables → Actions → Variables → `MIN_SCALE`)。

コスト目安: `cpu-throttling: false`(always-allocated)のため、min-instance 1 あたり
4vCPU/2GiB 常時課金で月 $180 前後。当日だけなら数ドル。

## 現在のキャパシティ設定(deploy.yml)

| 項目 | 値 | 意味 |
|---|---|---|
| containerConcurrency | 600 | 1インスタンスの同時リクエスト数 |
| maxScale | 30 | 最大インスタンス数(理論上 18,000 同時) |
| cpu / memory | 4 / 2Gi | startup-cpu-boost + always-allocated |
| DB_POOL_SIZE | 5 | HikariCP 上限/インスタンス(30×5=150 ≦ MySQL max_connections 151) |
| acceptQueueSize | 512 | Jetty TCP accept キュー(コード側デフォルト) |
| PUBLIC_BASE_URL | https://v2.r-sai2026.site | 書き込み起点 purge の URL 構築用(cloudrun のみ) |

## 書き込み起点の自動 purge(エッジ TTL 5 分の前提)

公開エンドポイントの `s-maxage` は一律 300 秒。鮮度は TTL ではなく書き込み起点の purge で担保する:

- **混雑更新** (`POST /congestion/{code}`) → 自インスタンス即時 refresh + 全インスタンス broadcast(約4秒で追従)
  + CF へ `/congestion` の URL purge(伝播の取りこぼし対策で 10 秒後に再 purge)
- **管理画面のテーブル編集 / 書き込み SQL** → `CacheSync` が 3 秒コアレッシングで
  「全キャッシュ再構築 + broadcast + CF purge_everything」を自動実行(クリアボタンの押し忘れ対策)
- `/admin/cache/clear` は従来どおり手動の保険として残存

注意:
- CF の URL purge は完全一致。クエリ文字列付きでキャッシュされた変種は purge 対象外なので、
  Cache Rule のキャッシュキーは「クエリ文字列を無視」に設定しておくこと
- ブラウザ側 `max-age` は purge できないため短いまま(30〜60 秒)
- `PUBLIC_BASE_URL` 未設定の環境(debug)では URL purge は無効(s-maxage で自然失効)

## Cloudflare キャッシュの動作確認

```bash
curl -s -D - -o /dev/null -H "X-API-Key: <READ_ONLY_KEY>" https://<prod-host>/congestion
```

- `cf-cache-status: HIT` … エッジから配信(オリジン無負荷)
- `MISS` / `EXPIRED` … オリジン到達。TTL は各エンドポイントの `s-maxage`(congestion=30s, events/food/map=300s)
- `REVALIDATED` … ETag 再検証で 304(ボディ転送なし)
- 全エンドポイントが `ETag` / `Vary: Accept-Encoding` を返すこと

データ更新を即時反映したいときは管理画面の「キャッシュクリア」
(`POST /admin/cache/clear` — オリジンキャッシュ再構築 + CF purge_everything)を使う。

## 負荷テスト(debug 環境)

CF を経由させず、tatsunote2 上で直接オリジンを叩く:

```bash
ssh root@tatsunote2
source /opt/rsai-debug.env   # READ_ONLY_KEY を取得
hey -z 30s -c 200 \
  -H "X-API-Key: $READ_ONLY_KEY" -H "Accept-Encoding: gzip" \
  http://localhost:8082/congestion
```

- 計測対象: RPS / p99 / 転送量。`-H "If-None-Match: <ETag>"` を付ければ 304 経路を計測できる
- hey が無い場合: `curl -Lo /usr/local/bin/hey https://hey-release.s3.us-east-2.amazonaws.com/hey_linux_amd64 && chmod +x /usr/local/bin/hey`
- 本番(Cloud Run)への負荷テストは CF・課金に影響するため原則行わない
