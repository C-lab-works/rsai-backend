# rsai-backend API リファレンス

Base URL: `https://azure.c-lab.works`

---

## 認証

| ヘッダー | 必須 | 説明 |
|---|---|---|
| `X-API-Key` | 全エンドポイント（`/health` を除く） | 管理キーは全操作可能、読み取り専用キーは GET のみ許可 |
| `CF-Access-Jwt-Assertion` | `/admin/*` のみ | Cloudflare Access から自動付与される JWT。管理エンドポイントへのアクセス時に必須 |

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

タイムテーブル表示に必要なデータをまとめて返す。5 分キャッシュ。

**レスポンス**
```json
{
  "categories": [
    { "id": 1, "name": "ステージ系" }
  ],
  "locations": [
    { "id": 1, "name": "体育館", "floor": 1, "svgId": "gym", "isStage": true, "tracksCongestion": true }
  ],
  "projects": [
    { "id": 1, "title": "ステージ企画", "organizer": "実行委員会", "description": null, "imageUrl": null, "locationId": 2 }
  ],
  "projectCategories": [
    { "projectId": 1, "categoryId": 1 }
  ],
  "timetables": [
    { "id": 1, "projectId": 1, "locationId": 2, "date": "2026-07-04", "isAllDay": false, "start": "10:00:00", "end": "11:00:00" }
  ]
}
```

> `projects[].locationId` — 拠点が未設定の場合は省略される。  
> `timetables[].start` / `.end` — `isAllDay` が `true` の場合は省略される。  
> `projectCategories` — project と category の多対多リレーション。

---

### `GET /food`

飲食店舗とメニューを返す。5 分キャッシュ。

**レスポンス**
```json
{
  "foods": [
    { "id": 1, "name": "キッチンカー店舗", "description": "店舗説明", "imageUrl": null }
  ],
  "menus": [
    { "id": 1, "foodId": 1, "name": "メニュー名", "price": 500, "description": null, "isSoldOut": false }
  ]
}
```

> `menus[].price` — 未設定の場合は省略される。  
> `menus[].isSoldOut` — 未設定の場合は省略される。

---

### `GET /map`

マップ表示用の場所一覧を返す。5 分キャッシュ。

**レスポンス**
```json
{
  "locations": [
    { "id": 1, "name": "体育館", "floor": 1, "svgId": "gym", "isStage": true, "tracksCongestion": true }
  ]
}
```

---

### `GET /announcements`

現在表示期間内のお知らせを返す（緊急順 → 新しい順）。

**レスポンス**
```json
{
  "announcements": [
    { "id": 2, "content": "【緊急】お知らせ内容", "isEmergency": true },
    { "id": 1, "content": "通常お知らせ内容", "isEmergency": false, "displayFrom": "2026-07-01 00:00:00", "displayUntil": "2026-07-05 23:59:59" }
  ]
}
```

> `displayFrom` / `displayUntil` — 設定されている場合のみ含まれる。

---

## 混雑情報

### `GET /locations`

混雑度管理対象の場所一覧（`tracks_congestion = 1`）を返す。各場所の直近企画名を含む。

**レスポンス**
```json
[
  { "id": 1, "name": "体育館", "floor": 1, "svgId": "gym", "project": "演劇" }
]
```

> `svgId` / `project` — 設定されていない場合は省略される。

---

### `GET /congestion`

全場所の現在の混雑レベルを返す。

**レスポンス**
```json
[
  { "location_id": 1, "level": 1, "updated_at": "2026-07-04 10:30:00", "updated_by": "admin@example.com" }
]
```

| `level` | 意味 |
|---|---|
| `0` | 空き |
| `1` | 普通 |
| `2` | 混み |

---

### `POST /congestion/{id}`

場所の混雑レベルを更新する（upsert）。`updated_by` には CF Access JWT のメールアドレスが記録される。

**パスパラメータ**

| パラメータ | 型 | 説明 |
|---|---|---|
| `id` | integer | 場所 ID |

**リクエストボディ**
```json
{ "level": 1 }
```

**レスポンス**
```json
{ "ok": true, "location_id": 1, "level": 1 }
```

---

## 管理 API

> 以下のエンドポイントは管理キー（`X-API-Key`）および Cloudflare Access JWT（`CF-Access-Jwt-Assertion`）の両方が必要。

### `GET /admin/tables`

データベース内のテーブル一覧と行数を返す。

**レスポンス**
```json
[
  { "name": "projects", "rowCount": 5 }
]
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
  "cols": [
    { "name": "id", "type": "INT", "pk": true },
    { "name": "title", "type": "VARCHAR" }
  ],
  "rows": [
    { "id": 1, "title": "ステージ企画" }
  ]
}
```

---

### `POST /admin/tables/{table}`

テーブルに行を挿入する。

**リクエストボディ**
```json
{ "title": "新規プロジェクト", "organizer": "〇〇部" }
```

**レスポンス（AUTO_INCREMENT あり）**
```json
{ "id": 6 }
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

任意の SQL を実行する（セミコロンで複数文区切り可）。最後に実行した文の結果を返す。

**リクエストボディ**
```json
{ "sql": "SELECT * FROM projects" }
```

**レスポンス（SELECT）**
```json
{
  "cols": [
    { "name": "id", "type": "int" },
    { "name": "title", "type": "varchar" }
  ],
  "rows": [
    { "id": 1, "title": "ステージ企画" }
  ]
}
```

**レスポンス（UPDATE / INSERT / DELETE）**
```json
{ "affected": 2 }
```

---

### `POST /admin/ddl/tables`

新規テーブルを作成する（`CREATE TABLE IF NOT EXISTS`）。

**リクエストボディ**
```json
{
  "name": "new_table",
  "columns": [
    { "name": "id",    "type": "INT",         "pk": true, "autoIncrement": true, "notNull": true },
    { "name": "title", "type": "VARCHAR(255)", "notNull": true },
    { "name": "memo",  "type": "TEXT",         "notNull": false }
  ]
}
```

**使用可能な型**

`INT` / `BIGINT` / `VARCHAR(255)` / `VARCHAR(100)` / `TEXT` / `TINYINT(1)` / `FLOAT` / `DOUBLE` / `DATE` / `DATETIME` / `TIME`

**レスポンス**
```json
{ "ok": true }
```

---

### `POST /admin/ddl/tables/{table}/columns`

既存テーブルにカラムを追加する（`ALTER TABLE ADD COLUMN`）。

**リクエストボディ**
```json
{ "name": "new_col", "type": "VARCHAR(255)", "notNull": false, "defaultValue": "" }
```

> `defaultValue` — 英数字・`.` `-` `_` のみ使用可能。空文字または省略で DEFAULT なし。

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
  "maxInstances": 10,
  "chart": [10, 15, 8, 20],
  "endpoints": [
    { "method": "GET", "path": "/events", "count": 300 }
  ],
  "system": [
    { "name": "Database",   "status": "ok", "value": "Connected" },
    { "name": "API Server", "status": "ok", "value": "Running" }
  ]
}
```

> `chart` — 直近 24 時間の時間帯別リクエスト数（インデックス 0 が最古）。  
> `endpoints` — `/admin` パスを除く上位 10 エンドポイント。

---

## エラーレスポンス

| ステータス | 意味 |
|---|---|
| `400` | リクエストが不正（制約違反・バリデーションエラー・不正な SQL 等） |
| `401` | `X-API-Key` が未指定もしくは不正、または CF Access JWT が無効・期限切れ |
| `403` | 読み取り専用キーで書き込み操作を試みた |
| `404` | 該当するエンドポイントが存在しない |
| `503` | DB 接続エラーなどサービス側の障害 |

**エラーボディ例**
```json
{ "error": "Foreign key constraint violation" }
```
