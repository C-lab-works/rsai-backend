# rsai-backend API リファレンス

Base URL: `https://azure.c-lab.works`  
すべてのエンドポイントは `X-API-Key` ヘッダーによる認証が必要です。

---

## 認証

| ヘッダー | 説明 |
|---|---|
| `X-API-Key` | 必須。管理キーは全操作可能、読み取り専用キーは GET のみ許可 |
| `CF-Access-Jwt-Assertion` | 管理パネル操作時に Cloudflare Access から自動付与される JWT |

---

## 公開エンドポイント

### `GET /health`
サーバーの死活確認。認証不要。

**レスポンス**
```json
{ "status": "ok" }
```

---

## アプリデータ

### `GET /events`
タイムテーブル表示に必要なデータをまとめて返す。5分キャッシュ。

**レスポンス**
```json
{
  "categories": [{ "id": 1, "name": "ステージ" }],
  "locations": [{ "id": 1, "name": "アリーナ", "floor": 2, "svgId": "arena", "isStage": true, "tracksCongestion": true }],
  "projects": [{ "id": 1, "categoryId": 1, "title": "バンド演奏", "organizer": "音楽部", "description": "...", "imageUrl": null }],
  "timetables": [{ "id": 1, "projectId": 1, "locationId": 1, "date": "2026-11-01", "isAllDay": false, "start": "10:00:00", "end": "11:00:00" }]
}
```

---

### `GET /food`
飲食カテゴリのプロジェクト・タイムテーブルのみを返す。5分キャッシュ。

**レスポンス**
```json
{
  "projects": [{ "id": 5, "title": "たこ焼き", "organizer": "〇〇部", "description": "..." }],
  "timetables": [{ "id": 10, "projectId": 5, "locationId": 3, "date": "2026-11-01", "isAllDay": false, "start": "10:00:00", "end": "15:00:00" }]
}
```

---

### `GET /map`
マップ表示用の場所一覧を返す。5分キャッシュ。

**レスポンス**
```json
{
  "locations": [{ "id": 1, "name": "アリーナ", "floor": 2, "svgId": "arena", "isStage": true, "tracksCongestion": true }]
}
```

---

### `GET /announcements`
現在表示期間内のお知らせを返す（緊急順 → 新しい順）。

**レスポンス**
```json
{
  "announcements": [
    { "id": 1, "content": "〇〇のお知らせ", "isEmergency": false, "displayFrom": "2026-11-01 00:00:00", "displayUntil": null }
  ]
}
```

---

## 混雑情報

### `GET /locations`
混雑度管理対象の場所一覧（`tracks_congestion = 1`）を返す。

**レスポンス**
```json
[
  { "id": 1, "name": "アリーナ", "floor": 2, "svgId": "arena", "project": "バンド演奏" }
]
```

---

### `GET /congestion`
全場所の現在の混雑レベルを返す。

**レスポンス**
```json
[
  { "location_id": 1, "level": 1, "updated_at": "2026-11-01 10:30:00", "updated_by": "admin@example.com" }
]
```

`level`: `0` = 空き / `1` = 普通 / `2` = 混み

---

### `POST /congestion/{id}`
場所の混雑レベルを更新する（upsert）。`updated_by` は CF Access JWT のメールアドレス。

**パスパラメータ**
| パラメータ | 説明 |
|---|---|
| `id` | 場所 ID |

**リクエストボディ**
```json
{ "level": 1 }
```

**レスポンス**
```json
{ "ok": true, "location_id": 1, "level": 1 }
```

---

## 管理 API（DB 操作）

> 以下のエンドポイントは管理パネルからのみ使用。管理キーが必要。

### `GET /admin/tables`
データベース内のテーブル一覧と行数を返す。

**レスポンス**
```json
[{ "name": "projects", "rowCount": 42 }]
```

---

### `GET /admin/tables/{table}`
テーブルのカラム情報と全行データ（最大 500 行）を返す。

**パスパラメータ**
| パラメータ | 説明 |
|---|---|
| `table` | テーブル名（英数字・アンダースコアのみ） |

**レスポンス**
```json
{
  "cols": [{ "name": "id", "type": "INT", "pk": true }, { "name": "title", "type": "VARCHAR" }],
  "rows": [{ "id": 1, "title": "テスト" }]
}
```

---

### `POST /admin/tables/{table}`
テーブルに行を挿入する。PK を省略すると AUTO_INCREMENT が使用される。

**リクエストボディ**
```json
{ "title": "新規プロジェクト", "category_id": 1 }
```

**レスポンス（AUTO_INCREMENT あり）**
```json
{ "id": 43 }
```

**レスポンス（AUTO_INCREMENT なし）**
```json
{ "ok": true }
```

---

### `PUT /admin/tables/{table}/{pk}`
指定した PK の行を更新する。

**パスパラメータ**
| パラメータ | 説明 |
|---|---|
| `table` | テーブル名 |
| `pk` | 主キーの値 |

**リクエストボディ**
```json
{ "title": "更新後タイトル", "organizer": "〇〇部" }
```

**レスポンス**
```json
{ "updated": 1 }
```

---

### `DELETE /admin/tables/{table}/{pk}`
指定した PK の行を削除する。

**レスポンス**
```json
{ "deleted": 1 }
```

---

### `POST /admin/sql`
任意の SQL を実行する（セミコロンで複数文区切り可）。SELECT は結果セットを返し、UPDATE/INSERT/DELETE は影響行数を返す。

**リクエストボディ**
```json
{ "sql": "SELECT * FROM projects WHERE category_id = 1" }
```

**レスポンス（SELECT）**
```json
{
  "type": "select",
  "cols": ["id", "title"],
  "rows": [{ "id": 1, "title": "バンド演奏" }]
}
```

**レスポンス（UPDATE/INSERT/DELETE）**
```json
{ "type": "update", "affected": 2 }
```

---

### `POST /admin/ddl/tables`
新規テーブルを作成する（`CREATE TABLE IF NOT EXISTS`）。

**リクエストボディ**
```json
{
  "name": "new_table",
  "columns": [
    { "name": "id",    "type": "INT",          "pk": true, "autoIncrement": true, "notNull": true },
    { "name": "title", "type": "VARCHAR(255)",  "notNull": true },
    { "name": "memo",  "type": "TEXT",          "notNull": false }
  ]
}
```

**使用可能な型**
`INT` / `BIGINT` / `VARCHAR(255)` / `VARCHAR(100)` / `TEXT` / `TINYINT(1)` / `FLOAT` / `DOUBLE` / `DATE` / `DATETIME` / `TIME`

**レスポンス**
```json
{ "ok": true, "table": "new_table" }
```

---

### `POST /admin/ddl/tables/{table}/columns`
既存テーブルにカラムを追加する（`ALTER TABLE ADD COLUMN`）。

**リクエストボディ**
```json
{ "name": "new_col", "type": "VARCHAR(255)", "notNull": false, "defaultValue": null }
```

**レスポンス**
```json
{ "ok": true }
```

---

### `GET /admin/stats`
リクエストメトリクスとシステム状態を返す。

**レスポンス**
```json
{
  "totalRequests": 1200,
  "errorRate": 0.02,
  "p50ms": 45,
  "p95ms": 180,
  "instances": 1,
  "maxInstances": 3,
  "chart": [10, 15, 8, 20],
  "endpoints": [{ "method": "GET", "path": "/events", "count": 300 }],
  "system": [{ "name": "DB", "status": "ok", "value": "connected" }]
}
```

---

## エラーレスポンス

| ステータス | 意味 |
|---|---|
| `400` | リクエストが不正（制約違反、バリデーションエラー等） |
| `401` | `X-API-Key` が未指定または不正 |
| `403` | 読み取り専用キーで書き込み操作を試みた |
| `503` | DB 接続エラーなどのサービス側の障害 |

**エラーボディ例**
```json
{ "error": "id の値が制約に違反しています" }
```
