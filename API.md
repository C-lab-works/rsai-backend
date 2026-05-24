# rsai-backend API リファレンス

Base URL: `https://v2.r-sai2026.site`

---

## 認証

| ヘッダー | 必須 | 説明 |
|---|---|---|
| `X-API-Key` | 全エンドポイント（`/health` を除く） | |

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

タイムテーブル表示に必要なデータをまとめて返す。30 秒キャッシュ。

**レスポンス**
```json
{
  "categories": [
    { "id": 1, "name": "ステージ系" }
  ],
  "locations": [
    { "id": 2, "name": "Co-tan", "floor": 1, "location_code": "02", "x": 158.5, "y": 218.0 }
  ],
  "projects": [
    { "id": 1, "title": "中一企画1", "organizer": "1-1", "location_id": 15 }
  ],
  "project_categories": [
    { "project_id": 1, "category_id": 2 }
  ],
  "timetables": [
    { "id": 16, "project_id": 30, "location_id": 2, "date": "2026-07-04", "is_all_day": false, "start": "10:00:00", "end": "10:20:00" }
  ]
}
```

> `projects[].location_id` — 拠点が未設定の場合は省略される。  
> `projects[].organizer` / `.description` / `.image_url` — 設定されている場合のみ含まれる。  
> `timetables[].start` / `.end` — `is_all_day` が `true` の場合は省略される。  
> `project_categories` — project と category の多対多リレーション。  
> `locations[].location_code` — 場所の識別コード（例: `"02"`, `"T1J"`）。  
> `locations[].x` / `.y` — マップ上のピクセル座標。設定されていない場合は省略される。

---

### `GET /food`

飲食店舗とメニューを返す。30 秒キャッシュ。

**レスポンス**
```json
{
  "foods": [
    { "id": 1, "name": "保護者会", "description": "店舗の説明", "image_url": "https://example.com/image.jpg" },
    { "id": 2, "name": "test商店" }
  ],
  "menus": [
    { "id": 1, "food_id": 1, "name": "フランクフルト", "price": 200, "description": "美味しい", "is_sold_out": false },
    { "id": 3, "food_id": 2, "name": "いろはす", "price": 700, "is_sold_out": false }
  ]
}
```

> `foods[].description` / `.image_url` — 設定されている場合のみ含まれる。  
> `menus[].price` / `.description` / `.is_sold_out` — 設定されている場合のみ含まれる。

---

### `GET /map`

マップ表示用の場所一覧を返す。30 秒キャッシュ。

**レスポンス**
```json
{
  "locations": [
    { "id": 2, "name": "Co-tan", "floor": 1, "location_code": "02", "x": 158.5, "y": 218.0 },
    { "id": 22, "name": "階段1-中学教室", "floor": 1, "location_code": "S1JH", "svg_id": 2, "x": 249.0, "y": 175.0 }
  ]
}
```

> `locations[].location_code` — 場所の識別コード（例: `"02"`, `"T1J"`）。  
> `locations[].svg_id` — SVG アイコン ID（整数）。設定されている場合のみ含まれる。  
> `locations[].x` / `.y` — マップ上のピクセル座標。設定されていない場合は省略される。

---

### `GET /announcements`

現在表示期間内のお知らせを返す（緊急順 → 新しい順）。

**レスポンス**
```json
{
  "announcements": [
    { "id": 2, "content": "【緊急】お知らせ内容", "is_emergency": true },
    { "id": 1, "content": "通常お知らせ内容", "is_emergency": false, "display_from": "2026-07-01 00:00:00", "display_until": "2026-07-05 23:59:59" }
  ]
}
```

> `display_from` / `display_until` — 設定されている場合のみ含まれる。

---

## 混雑情報

### `GET /congestion`

全場所一覧と現在の混雑レベルをまとめて返す。10 秒キャッシュ。

**レスポンス**
```json
[
  {
    "location_id": 2,
    "location_code": "02",
    "name": "Co-tan",
    "floor": 1,
    "svg_id": 5,
    "x": 43.02042086431134,
    "y": 141.52233128287835,
    "level": 1,
    "updated_at": "2026-07-04 10:30:00",
    "project": "高2企画4"
  }
]
```

> `svg_id` / `x` / `y` — 設定されていない場合は省略される。  
> `updated_at` — 混雑度が未設定の場合は省略される。  
> `project` — 直近の企画名。設定されていない場合は省略される。  
> `level` — 混雑度が未設定の場所は `0`（空き）を返す。

| `level` | 意味 |
|---|---|
| `0` | 空き |
| `1` | 普通 |
| `2` | 混み |

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
