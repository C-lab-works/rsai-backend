# rsai-backend API リファレンス

Base URL: `https://api.example.com`

---

## 認証

| ヘッダー | 対象 | 説明 |
|---|---|---|
| `X-API-Key` | `/health` を除く全エンドポイント | |
| `X-Firebase-AppCheck` | `/stars`、`/push-token` | Firebase App Check トークン |
| CF Access JWT | `POST /congestion/{code}`、`POST /events/delays/{projectId}` | Cloudflare Access セッション Cookie または Bearer トークン |

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

タイムテーブル表示に必要なデータをまとめて返す。60 秒キャッシュ。

**レスポンス**
```json
{
  "categories": [
    { "id": 1, "name": "ステージ系" }
  ],
  "locations": [
    { "id": 2, "name": "場所名", "floor": 1, "location_code": "02", "x": 158.5, "y": 218.0 }
  ],
  "projects": [
    {
      "id": 1,
      "title": "企画名",
      "organizer": "主催者",
      "location_id": 15,
      "bookmark_count": 42,
      "afterparty": 0,
      "delay": { "delay_minutes": 10, "note": "準備中", "updated_at": "2026-06-30 10:00:00" }
    }
  ],
  "project_categories": [
    { "project_id": 1, "category_id": 2 }
  ],
  "timetables": [
    { "id": 16, "project_id": 30, "location_id": 2, "date": "YYYY-MM-DD", "is_all_day": false, "start": "10:00:00", "end": "10:20:00" }
  ]
}
```

> `projects[].location_id` — 拠点が未設定の場合は省略される。  
> `projects[].organizer` / `.description` / `.image_url` — 設定されている場合のみ含まれる。  
> `projects[].bookmark_count` — ブックマーク（スター）数。  
> `projects[].afterparty` — 後夜祭対象企画フラグ（`0` または `1`）。  
> `projects[].delay` — 遅延情報が登録されている場合のみ含まれる。`delay_minutes` / `note` / `updated_at` は各フィールドが設定されている場合のみ含まれる。  
> `timetables[].start` / `.end` — `is_all_day` が `true` の場合は省略される。  
> `project_categories` — project と category の多対多リレーション。  
> `locations[].location_code` — 場所の識別コード（例: `"02"`, `"T1J"`）。  
> `locations[].x` / `.y` — マップ上のピクセル座標。設定されていない場合は省略される。

---

### `GET /food`

飲食店舗とメニューを返す。60 秒キャッシュ。

**レスポンス**
```json
{
  "items": [
    {
      "id": 1,
      "name": "店舗名",
      "info": "店舗の説明",
      "icon": "https://example.com/icon.jpg",
      "blurhash": "LKO2?U%2Tw=w]~RBVZRi};RPxuwH",
      "location_code": "02",
      "bookmark_count": 10,
      "afterparty_location": "後夜祭場所名",
      "subicons": [
        { "id": 1, "url": "https://example.com/subicon1.png", "blurhash": "..." }
      ],
      "sns": [
        { "id": 1, "platform": "x", "url": "https://x.com/example" }
      ],
      "menus": [
        { "id": 1, "name": "メニュー名", "price": 200, "image_url": "https://example.com/menu.jpg", "blurhash": "...", "allergen": "卵,小麦" }
      ]
    }
  ]
}
```

> `location_code` — 設定されている場合のみ含まれる。  
> `blurhash` (店舗 / subicons / menus) — 設定されている場合のみ含まれる。  
> `afterparty_location` — 後夜祭の配置場所。設定されている場合のみ含まれる。  
> `subicons` — 複数登録可能なサブアイコンの配列。  
> `sns` — 複数登録可能なSNSアカウント情報の配列。`platform` は設定されている場合のみ含まれる。  
> `menus[].image_url` / `.allergen` — 設定されている場合のみ含まれる。  
> `bookmark_count` — ブックマーク（スター）数。

---

### `GET /map`

マップ表示用の場所一覧を返す。60 秒キャッシュ。

**レスポンス**
```json
{
  "locations": [
    { "id": 2, "name": "場所名A", "floor": 1, "location_code": "02", "type": "stage", "x": 158.5, "y": 218.0 },
    { "id": 22, "name": "場所名B", "floor": 1, "location_code": "S1", "type": "food", "svg_id": 2, "x": 249.0, "y": 175.0 }
  ]
}
```

> `locations[].location_code` — 場所の識別コード（例: `"02"`, `"T1J"`）。  
> `locations[].type` — 場所の種別（例: `"stage"`, `"food"` など）。  
> `locations[].svg_id` — SVG アイコン ID（整数）。設定されている場合のみ含まれる。  
> `locations[].x` / `.y` — マップ上のピクセル座標。設定されていない場合は省略される。

---

### `GET /announcements`

お知らせ一覧を返す（緊急順 → 新しい順）。

**レスポンス**
```json
{
  "announcements": [
    { "id": 2, "title": "緊急", "content": "【緊急】お知らせ内容", "is_emergency": true },
    { "id": 1, "title": "通常", "content": "通常お知らせ内容", "is_emergency": false }
  ]
}
```

---

## 混雑情報

### `GET /congestion`

全場所一覧と現在の混雑レベルをまとめて返す。30 秒キャッシュ。

**レスポンス**
```json
[
  {
    "location_id": 2,
    "location_code": "02",
    "name": "場所名",
    "floor": 1,
    "type": "stage",
    "svg_id": 5,
    "x": 43.0,
    "y": 141.5,
    "level": 1,
    "updated_at": "YYYY-MM-DD HH:MM:SS",
    "project": "企画名"
  }
]
```

> `svg_id` / `x` / `y` — 設定されていない場合は省略される。  
> `type` — 場所の種別。  
> `updated_at` — 混雑度が未設定の場合は省略される。  
> `project` — 直近の企画名。設定されていない場合は省略される。  
> `level` — 混雑度が未設定の場所は `0`（空き）を返す。

| `level` | 意味 |
|---|---|
| `0` | 未設定 / 空き |
| `1` | 普通 |
| `2` | やや混み |
| `3` | 混み |
| `4` | かなり混み |
| `5` | 非常に混み |
| `6` | 入場制限 |

---

### `POST /congestion/{code}`

指定した場所の混雑レベルを更新する。CF Access 認証必須。

**パスパラメータ**

| パラメータ | 説明 |
|---|---|
| `code` | 場所の識別コード（`location_code`）例: `"02"`, `"T1J"` |

**リクエストボディ**
```json
{ "level": 1 }
```

**レスポンス**
```json
{ "ok": true, "location_code": "02", "level": 1 }
```

---

## 遅延情報

### `POST /events/delays/{projectId}`

指定した企画の遅延情報を更新する。CF Access 認証必須。

**パスパラメータ**

| パラメータ | 説明 |
|---|---|
| `projectId` | 企画の ID（整数） |

**リクエストボディ**
```json
{ "delay_minutes": 10, "note": "準備中" }
```

| フィールド | 型 | 必須 | 説明 |
|---|---|---|---|
| `delay_minutes` | `number \| null` | 任意 | 遅延分数（0〜999）。`null` で遅延解除 |
| `note` | `string \| null` | 任意 | 遅延理由など（最大 255 文字） |

**レスポンス**
```json
{ "ok": true, "project_id": 1 }
```

---

## Stars（ブックマーク）

Firebase App Check トークン（`X-Firebase-AppCheck`）と重複排除用 ID（`X-Request-Id`、UUID v4）が必須。

### `POST /stars`

企画またはフードトラックをブックマーク登録する。

**リクエストボディ**
```json
{ "type": "project", "id": 1 }
```

| フィールド | 値 | 説明 |
|---|---|---|
| `type` | `"project"` \| `"foodtruck"` | 対象の種別 |
| `id` | 整数 | 対象の ID |

**レスポンス**
```json
{ "ok": true }
```

---

### `DELETE /stars`

ブックマークを解除する。リクエストボディは `POST /stars` と同じ。

**レスポンス**
```json
{ "ok": true }
```

---

## プッシュトークン

### `POST /push-token`

Expo プッシュトークンを登録する。

**リクエストボディ**
```json
{ "token": "ExponentPushToken[xxxxxxxxxxxxxxxxxxxxxx]", "platform": "ios" }
```

| フィールド | 型 | 必須 | 説明 |
|---|---|---|---|
| `token` | `string` | 必須 | `ExponentPushToken[...]` 形式のトークン |
| `platform` | `"ios"` \| `"android"` | 任意 | プラットフォーム |

**レスポンス**
```json
{ "ok": true }
```

> トークンは非同期でバッチ書き込みされる。既存トークンは `platform` を上書き更新する。

---

## エラーレスポンス

| ステータス | 意味 |
|---|---|
| `400` | リクエストが不正（制約違反・バリデーションエラー・不正な SQL 等） |
| `401` | 認証未提示または無効・期限切れ |
| `403` | 権限不足（読み取り専用キーでの書き込み、ブロック済みデバイス等） |
| `404` | 該当するリソースが存在しない |
| `409` | 重複リクエスト（`X-Request-Id` 重複）または競合 |
| `503` | DB 接続エラーなどサービス側の障害、またはウォームアップ中 |

**エラーボディ例**
```json
{ "error": "Foreign key constraint violation" }
```
