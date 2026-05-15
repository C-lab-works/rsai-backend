# rsai-backend API リファレンス

Base URL: `https://api.r-sai2026.site`

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

タイムテーブル表示に必要なデータをまとめて返す。5 分キャッシュ。

**レスポンス**
```json
{
  "categories": [
    { "id": 1, "name": "ステージ系" }
  ],
  "locations": [
    { "id": 2, "name": "Co-tan", "floor": 1, "isStage": true, "tracksCongestion": true, "x": 43.02042086431134, "y": 141.52233128287835 }
  ],
  "projects": [
    { "id": 1, "title": "中一企画1", "organizer": "1-1", "locationId": 15 }
  ],
  "projectCategories": [
    { "projectId": 1, "categoryId": 2 }
  ],
  "timetables": [
    { "id": 16, "projectId": 30, "locationId": 2, "date": "2026-07-04", "isAllDay": false, "start": "10:00:00", "end": "10:20:00" }
  ]
}
```

> `projects[].locationId` — 拠点が未設定の場合は省略される。  
> `projects[].organizer` / `.description` / `.imageUrl` — 設定されている場合のみ含まれる。  
> `timetables[].start` / `.end` — `isAllDay` が `true` の場合は省略される。  
> `projectCategories` — project と category の多対多リレーション。  
> `locations[].svgId` — SVG 要素 ID。設定されている場合のみ含まれる。  
> `locations[].x` / `.y` — 緯度・経度。設定されていない場合は省略される。

---

### `GET /food`

飲食店舗とメニューを返す。5 分キャッシュ。

**レスポンス**
```json
{
  "foods": [
    { "id": 1, "name": "保護者会", "description": "店舗の説明", "imageUrl": "https://example.com/image.jpg" },
    { "id": 2, "name": "test商店" }
  ],
  "menus": [
    { "id": 1, "foodId": 1, "name": "フランクフルト", "price": 200, "description": "美味しい", "isSoldOut": false },
    { "id": 3, "foodId": 2, "name": "いろはす", "price": 700, "isSoldOut": false }
  ]
}
```

> `foods[].description` / `.imageUrl` — 設定されている場合のみ含まれる。  
> `menus[].price` / `.description` / `.isSoldOut` — 設定されている場合のみ含まれる。

---

### `GET /map`

マップ表示用の場所一覧を返す。5 分キャッシュ。

**レスポンス**
```json
{
  "locations": [
    { "id": 2, "name": "Co-tan", "floor": 1, "isStage": true, "tracksCongestion": true, "x": 43.02042086431134, "y": 141.52233128287835 }
  ]
}
```

> `locations[].svgId` — SVG 要素 ID。設定されている場合のみ含まれる。  
> `locations[].x` / `.y` — 緯度・経度。設定されていない場合は省略される。

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
  { "id": 2, "name": "Co-tan", "floor": 1, "x": 43.02042086431134, "y": 141.52233128287835, "project": "高2企画4" }
]
```

> `svgId` / `x` / `y` / `project` — 設定されていない場合は省略される。

---

### `GET /congestion`

全場所の現在の混雑レベルを返す。

**レスポンス**
```json
[
  { "location_id": 1, "level": 1, "updated_at": "2026-07-04 10:30:00" }
]
```

| `level` | 意味 |
|---|---|
| `0` | 空き |
| `1` | 普通 |
| `2` | 混み |

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
