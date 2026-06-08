# rsai-backend 学習ガイド

STUDY_PROBLEMS.md の問題を解くための知識をまとめたノート。
コードを読みながら照らし合わせると理解が深まる。

---

## 目次

1. [プロジェクト全体像](#1-プロジェクト全体像)
2. [Gate フレームワーク](#2-gate-フレームワーク)
3. [起動フロー](#3-起動フロー)
4. [ミドルウェアチェーン](#4-ミドルウェアチェーン)
5. [認証レイヤー](#5-認証レイヤー)
6. [コントローラーとキャッシュ設計](#6-コントローラーとキャッシュ設計)
7. [AdminController 詳解](#7-admincontroller-詳解)
8. [並行処理・バックグラウンドジョブ](#8-並行処理バックグラウンドジョブ)
9. [RequestMetrics（メトリクス永続化）](#9-requestmetricsメトリクス永続化)
10. [セキュリティ設計のポイント](#10-セキュリティ設計のポイント)
11. [データベース操作パターン](#11-データベース操作パターン)
12. [よく聞かれる「なぜ？」集](#12-よく聞かれるなぜ集)

---

## 1. プロジェクト全体像

### 何をするシステムか

立命祭（大学祭）2026 の来場者向け API バックエンド。以下の情報を配信する：

| エンドポイント | 内容 |
|---|---|
| `GET /events` | イベント・出展者・タイムテーブル |
| `GET /food` | 飲食ブース・メニュー |
| `GET /map` | 会場マップ・場所情報 |
| `GET /congestion` | 各エリアの混雑度 |
| `GET /announcements` | お知らせ・緊急情報 |
| `GET /health` | ヘルスチェック |
| `/admin/*` | 管理者専用（DB操作・インスタンス管理等） |

### 技術スタック

```
Java 21 (Virtual Threads)
  └── Gate（自作 HTTP フレームワーク、Jetty ベース）
      ├── MySQL + HikariCP（DB 接続プール）
      ├── Cloudflare（CDN、IP フィルタ、Access 認証）
      ├── Google Cloud Run（コンテナ実行）
      └── Firestore（マルチインスタンス調整）
```

### パッケージ構成

```
dev.gate/
├── core/          Gate フレームワーク本体
│   ├── Gate.java       サーバー起動・ルート登録
│   ├── Context.java    リクエスト/レスポンスの入れ物
│   ├── Router.java     URL マッチング
│   ├── Database.java   HikariCP ラッパー
│   ├── Config.java     設定値クラス
│   └── HttpCache.java  gzip・ETag ユーティリティ
├── mapping/       HTTP メソッドアノテーション（@GetMapping 等）
├── annotation/    @GateController・スキャナ
├── logging/       ログフィルタ
├── Main.java      エントリーポイント
├── AdminController.java    管理者 API
├── DataController.java     /events /food /map
├── AnnouncementsController.java  /announcements
├── CongestionController.java     /congestion
├── CfAccessAuth.java       Cloudflare Access JWT 検証
├── CloudflareIpFilter.java IP 許可リスト
├── ApiKeyAuth.java         API キー認証
├── RequestMetrics.java     リクエスト統計
├── InstanceManager.java    Cloud Run マルチインスタンス管理
├── FirestoreRest.java      Firestore REST API クライアント
├── AuditLog.java           操作ログ
├── DiscordWebhook.java     Discord 通知
└── SecurityHeaders.java    セキュリティヘッダ付与
```

---

## 2. Gate フレームワーク

### コアコンセプト

Gate は Spring Boot などの重いフレームワークを使わず、Jetty を直接ラップした軽量フレームワーク。Java 21 の **Virtual Threads** を使ってスループットを確保している。

### ルート登録の2方式

**アノテーション方式**（本プロジェクトはこちら）
```java
@GateController
public class DataController {
    @GetMapping("/events")
    public void events(Context ctx) { ... }

    @PostMapping("/congestion/{code}")
    public void updateCongestion(Context ctx) { ... }
}

// Main.java で登録
gate.register(new DataController());
```

**プログラム方式**（ラムダ、ヘルスエンドポイント等）
```java
gate.get("/health", ctx -> {
    ctx.json(Map.of("status", "ok"));
});
```

### Context API — リクエスト読み取り

```java
ctx.path()              // リクエストパス（例: "/events"）
ctx.method()            // HTTP メソッド（例: "GET"）
ctx.pathParam("id")     // パスパラメータ（/users/{id} の {id}）
ctx.query("limit")      // クエリパラメータ（?limit=40）
ctx.requestHeader("X-API-Key")  // リクエストヘッダ
ctx.body()              // リクエストボディ（String、最大 1MB）
ctx.bodyAs(Map.class)   // JSON → オブジェクト変換（Jackson）
```

### Context API — レスポンス書き込み

すべてメソッドチェーン可能（`this` を返す）。

```java
ctx.status(404)                      // ステータスコード設定（デフォルト 200）
ctx.result("hello")                  // text/plain レスポンス
ctx.json(Map.of("ok", true))         // application/json レスポンス
ctx.jsonBytes(byte[])                // 既シリアライズ済みバイト（キャッシュ活用）
ctx.header("ETag", "abc")            // レスポンスヘッダ設定
ctx.halt()                           // 処理中断（例外なし）

// チェーン例
ctx.status(404).json(Map.of("error", "not found"));
```

### パスパラメータのルール

```java
@GetMapping("/admin/instances/{id}/command/{requestId}")
```

- `{id}` → `ctx.pathParam("id")`
- 空の `{}` は登録時に `IllegalArgumentException`
- 同じパターン内で同名パラメータは `IllegalArgumentException`
- 末尾スラッシュは除去（`/users/` = `/users`）
- **完全一致ルートが常にパターンルートより優先**

### ミドルウェアの実行順序

```
before フィルタ（登録順）
  ↓ （例外 or halt → ループ中断）
OPTIONS なら 204 で終了
  ↓
ルートハンドラ
  ↓
after フィルタ（登録順）★ 例外があっても必ず実行
  ↓
レスポンス書き込み
```

重要な点：
- `before` で `ctx.status(401)` を設定するだけではルートは止まらない → `throw` か `ctx.halt()` が必要
- `after` フィルタは各々が try/catch で囲まれているため、1つが失敗しても残りは実行される
- `after` フィルタ内で throw された例外はエラーハンドラに届かない（ログに記録されて握りつぶし）

### halt() vs throw の違い

| | `ctx.halt()` | `throw new RuntimeException()` |
|---|---|---|
| after フィルタ | 実行される | 実行される |
| エラーハンドラ | 呼ばれない | 呼ばれる |
| ログ | 残らない | エラーログが出る |
| 用途 | 認証拒否などの正常な中断 | 予期しないエラー |

---

## 3. 起動フロー

### main() の処理順

```
1. loadVersion()          APP_VERSION 環境変数を読む
2. ConfigLoader.load()    config.yml を読む（失敗してもデフォルト値で続行）
3. new Gate()             フレームワーク初期化
4. new CfAccessAuth()     環境変数チェック（未設定ならクラッシュ）
5. gate.before(...)       ミドルウェア登録
6. gate.get("/health")    ヘルスエンドポイント登録
7. gate.register(...)     各コントローラー登録
8. YamlRouteLoader.load() routes.yaml から宣言的ルート登録
9. startDatabaseInit()    DB初期化を別スレッドで開始（★ノンブロッキング）
10. gate.start(port)      HTTP サーバー起動 ← ここでリクエストを受け付け始める
```

**ポイント**: DB 初期化（ステップ9）がバックグラウンドで動くため、アプリ起動直後に `/events` へリクエストが来てもキャッシュが空なので 503 が返る。`APP_READY` が `true` になるまでは `/health` が 503 を返す。

### startDatabaseInit() の流れ

```java
// 別スレッド（"db-init"）で実行
while (true) {
    try {
        Database.init(dbConfig);      // HikariCP プール初期化
        DataSeeder.seed();            // 初期データ投入
        // 並列で初回キャッシュ充填
        //   DataController.refreshAll()
        //   CongestionController.refreshCache()
        //   AnnouncementsController.refreshCache()
        //   YamlRouteLoader.refreshAll()
        //   cfAccessAuth.prefetchJwks()
        APP_READY.set(true);          // ヘルスチェック OK
        startBackgroundJobs();        // 定期ポーリング開始
        return;
    } catch (Exception e) {
        // 失敗したらバックオフして再試行
        // 2秒 → 4秒 → 8秒 → ... → 最大30秒
    }
}
```

### バックオフ計算

```java
backoffMs = Math.min(backoffMs * 2, 30_000L);
// 2000 → 4000 → 8000 → 16000 → 30000（以降 30 秒固定）
```

DB が一時的にダウンしていても自動回復する設計。

### APP_VERSION について

```java
String version = System.getenv("APP_VERSION");
return (version == null || version.isEmpty()) ? "null" : version;
```

バージョンをソースファイル（`version.txt` 等）に書き込まないのは、**GraalVM nativeCompile のレイヤーキャッシュ**を壊さないため。デプロイ時に環境変数で注入する。

---

## 4. ミドルウェアチェーン

### 登録順と役割

```java
// Main.java での登録順
gate.before(SecurityHeaders.get());      // 1. セキュリティヘッダ付与
gate.before(new CloudflareIpFilter());   // 2. IP 許可リストチェック
gate.before(new ApiKeyAuth());           // 3. API キー認証
gate.before(cfAccessAuth);              // 4. CF Access JWT 認証
gate.before(metrics::startTimer);       // 5. タイマー開始
gate.after(metrics::record);            // A. メトリクス記録
```

### SecurityHeaders（一番先の理由）

認証フィルタが `halt()` で 401/403 を返した場合でも、セキュリティヘッダ（`X-Content-Type-Options` など）がレスポンスに含まれるようにするため。

### CloudflareIpFilter

Cloudflare の IP アドレスレンジ以外からのアクセスを拒否する。

```
リクエスト到着
  └── /health → スキップ
  └── ORIGIN_SHARED_SECRET 設定あり → X-Origin-Secret ヘッダを検証（一致なら IP チェック不要）
  └── なし → XFF の最右端の非プライベート IP を取得 → Cloudflare CIDR と比較
           → 不一致 → 403 + halt()
```

### ApiKeyAuth

```java
// X-API-Key ヘッダを確認
// OPTIONS はスキップ（CORS プリフライト）
// /health はスキップ

// adminKeyBytes と一致 → 全アクセス許可
// readOnlyKeyBytes と一致
//   → /admin パスなら 403
//   → GET 以外なら 403
//   → GET は許可
// どちらとも一致しない → 401 + halt()
```

`MessageDigest.isEqual()` でタイミング攻撃を防いでいる（後述）。

### CfAccessAuth

`ApiKeyAuth` を通過したリクエストに対して、さらに JWT を検証する。

```
パス        | 処理
/health     | スキップ
OPTIONS     | スキップ（プリフライト）
/admin/*    | CF-Access-Jwt-Assertion ヘッダ必須
            | JWT 検証 → email が ADMIN_EMAILS になければ 403
            | 通過した場合 → ctx.setAttribute("cf_verified_email", email)
POST /congestion/* | JWT があれば email を opportunistic に抽出（失敗しても 401 にしない）
                   | email が null のとき → CongestionController で 401
その他      | スキップ
```

---

## 5. 認証レイヤー

### 2段階認証の設計

```
API キー（X-API-Key）
  └── 全エンドポイントへのアクセス制御（公開 API vs 管理者 API）

Cloudflare Access JWT（CF-Access-Jwt-Assertion）
  └── 管理者メールアドレスの確認
  └── 操作ログへのメール記録
```

### JWT 検証の手順（CfAccessAuth.verifyAndExtractEmail）

JWT は `header.payload.signature` の3パーツ（Base64URL エンコード）。

```java
// 1. 3パーツに分割
String[] parts = token.split("\\.");

// 2. header から kid（鍵ID）と alg（アルゴリズム）を取得
//    → alg は RS256 のみ許可

// 3. kid に対応する公開鍵を取得（Cloudflare JWKS エンドポイントから）

// 4. 署名検証
//    signedData = parts[0] + "." + parts[1]（UTF-8 バイト列）
//    sig.verify(sigBytes)  ← SHA256withRSA

// 5. クレーム検証
//    exp（有効期限）: now <= exp + 30秒（クロックスキュー許容）
//    iat（発行時刻）: now - iat <= 86400秒（24時間以内）
//    nbf（有効開始）: now >= nbf - 60秒
//    iss（発行者）:  teamDomain と一致すること
//    aud（対象）:    audience と一致すること（文字列・配列どちらも対応）
//    email:         クレームが存在すること

// 6. email を返す
```

### JWKS キャッシュとプリフェッチ

```
JWKS TTL = 1時間
プリフェッチ間隔 = 50分（TTL の10分前に更新）
```

ダブルチェックロッキングで複数スレッドが同時に JWKS を取りに行くのを防ぐ：

```java
// ロック外で高速チェック
if (cacheValid && key != null) return key;

// キャッシュが古い or キーが見つからない → ロックを取ってから再チェック
jwksLock.lock();
try {
    if (cacheValid && ...) return key;  // ロック取得中に他スレッドが更新済みかも
    refreshKeysLocked();                // 実際の取得
} finally {
    jwksLock.unlock();
}
```

### JWT 検証キャッシュ

同じ JWT トークンを何度も検証するのはムダなので結果をキャッシュする：

```java
// キャッシュキー = JWT トークン文字列（全体）
// キャッシュ値  = VerificationResult(email, expiresAtEpochSec)

// ヒット → exp より前なら return
// ミス  → 検証実行

// 成功 → email + exp でキャッシュ
// 確定的な失敗（署名不正等）→ email=null で 60秒キャッシュ（ネガティブキャッシュ）
// インフラ障害（JWKS 取得失敗）→ キャッシュしない（次リクエストでリトライ）
```

ネガティブキャッシュしない理由：JWKS 取得に失敗しただけでは JWT が「不正」とは確定しないため、60秒間正当ユーザーをロックアウトしてしまう。

### Cloudflare IP フィルタの仕組み

```
XFF: 1.2.3.4, 10.0.0.1, 172.16.0.1
              ^^^^^^^^^^^^^^^^^^^
              プライベートアドレス（除外）

最右端の非プライベート IP → 1.2.3.4 → Cloudflare CIDR と照合
```

CIDR マッチング（例: `173.245.48.0/20`）：
```java
// prefix = 20
// フルバイト数 = 20 / 8 = 2 → 最初の2バイトを完全比較
// 残りビット数 = 20 % 8 = 4 → 3バイト目の上位4ビットを比較
int mask = 0xFF & (0xFF << (8 - remainder));  // 0xF0
(addrBytes[2] & mask) == (networkBytes[2] & mask)
```

### タイミング攻撃対策

```java
// 危険な比較（長さや内容の違いで実行時間が変わる）
providedKey.equals(secretKey)

// 安全な比較（常に全バイトを比較する）
MessageDigest.isEqual(provided.getBytes(UTF_8), secretKeyBytes)
```

攻撃者がレスポンス時間を計測してキーを1バイトずつ推測するのを防ぐ。

---

## 6. コントローラーとキャッシュ設計

### キャッシュの3層構造

```
CDN（Cloudflare）
  └── Cache-Control ヘッダで制御
      └── s-maxage: CDN のキャッシュ期間
      └── stale-while-revalidate: 古いコンテンツを返しながら裏でリフレッシュ

アプリ（インメモリキャッシュ）
  └── ConcurrentHashMap or AtomicReference
      └── バックグラウンドポーラが定期更新

DB（MySQL）
  └── 正規データ
```

### Cache-Control の設定比較

| エンドポイント | max-age | s-maxage | stale-while-revalidate | 更新間隔 |
|---|---|---|---|---|
| `/events`, `/food`, `/map` | 60s | 300s | 600s | 30秒 |
| `/congestion` | 30s | 30s | 60s | 30秒 |
| `/announcements` | 30s | 60s | 120s | 60秒 |
| `/admin/*` | `no-store` | — | — | — |

`no-store` = キャッシュ禁止（管理画面は常に最新が必要）

### DataController のキャッシュ更新

```java
// ConcurrentHashMap<String, CacheEntry>
// キー: "events" / "food" / "map"
// 値: CacheEntry(byte[] json, byte[] jsonGzip, String etag)

// 更新は put（アトミック）→ 読み取り中に古い値が見える可能性はあるが、
// 部分的に壊れたオブジェクトが見える危険はない
cache.put(key, new CacheEntry(json, gzip, etag));
```

### ETag と 304 Not Modified

```java
// サーバーが返す ETag: "a3f8d2..." (コンテンツの SHA 等)
// クライアントが送る: If-None-Match: "a3f8d2..."

// サーバー側の処理
if (entry.etag().equals(ctx.requestHeader("If-None-Match"))) {
    ctx.status(304);  // ボディなし
    return;
}
// ETag が異なる → フルレスポンス返す
```

### gzip 圧縮

```java
String ae = ctx.requestHeader("Accept-Encoding");
if (ae != null && ae.contains("gzip")) {
    ctx.header("Content-Encoding", "gzip").jsonBytes(entry.jsonGzip());
} else {
    ctx.jsonBytes(entry.json());
}
```

`Vary: Accept-Encoding` ヘッダを返しているのは、CDN が gzip 版と非 gzip 版を別々にキャッシュするため。

### AtomicReference vs ConcurrentHashMap

| | `ConcurrentHashMap` | `AtomicReference` |
|---|---|---|
| 使用クラス | `DataController` | `AnnouncementsController`, `CongestionController` |
| キー数 | 複数（events/food/map） | 単一 |
| 原子性 | `put()` 単位で原子的 | `set()` で参照ごと原子的に交換 |

---

## 7. AdminController 詳解

### エンドポイント一覧

| メソッド | パス | 内容 |
|---|---|---|
| GET | `/admin/instances` | インスタンス一覧（Firestore から） |
| POST | `/admin/instances/{id}/command` | コマンド送信（非同期） |
| GET | `/admin/instances/{id}/command/{requestId}` | コマンド結果取得 |
| GET | `/admin/instances/{id}/metrics` | メトリクス履歴 |
| DELETE | `/admin/instances/{id}` | 停止済みインスタンス削除 |
| POST | `/admin/cache/clear` | 全キャッシュ強制更新 |
| GET | `/admin/tables` | テーブル一覧 |
| GET | `/admin/tables/{table}` | テーブル内容取得 |
| PUT | `/admin/tables/{table}/{pk}` | 行更新 |
| DELETE | `/admin/tables/{table}/{pk}` | 行削除 |
| POST | `/admin/tables/{table}` | 行挿入 |
| POST | `/admin/ddl/tables` | テーブル作成 |
| POST | `/admin/ddl/tables/{table}/columns` | カラム追加 |
| POST | `/admin/sql` | SQL 直接実行 |
| GET | `/admin/stats` | リクエスト統計 |
| GET | `/admin/stats/daily` | 日別統計 |
| GET | `/admin/yaml/routes` | routes.yaml 取得（GitHub から） |
| PUT | `/admin/yaml/routes` | routes.yaml 更新（GitHub へ） |
| GET | `/admin/yaml/status` | GitHub Actions 最新実行状況 |
| GET | `/admin/debug/503` | 意図的 503（デバッグ用） |

### SQL 実行の安全策（execSql）

```
入力 SQL
  ↓
splitStatements()   セミコロンで分割（文字列リテラル考慮）
  ↓
各ステートメントに対して:
  normalizeSql()    コメント除去 → 大文字化 → 空白圧縮
  matchedBlockedFragment()  INTO OUTFILE 等をブロック
  ALLOWED_SQL_KEYWORDS チェック（SELECT/INSERT/UPDATE/DELETE/SHOW/DESC/ALTER 等）
  ALTER の場合は ALTER TABLE のみ許可
  ↓
DDL なし → setAutoCommit(false)（トランザクション）
DDL あり → 暗黙コミット（ロールバック不可）
  ↓
実行 → タイムアウト 30秒
  ↓
DDL なし → commit（エラーなら rollback）
  ↓
WRITE 操作（INSERT/UPDATE/DELETE/ALTER）→ Discord 通知
監査ログ記録
```

### SQL ホワイトリスト

```java
Set<String> ALLOWED_SQL_KEYWORDS = Set.of(
    "SELECT", "INSERT", "UPDATE", "DELETE",
    "SHOW", "DESCRIBE", "DESC", "EXPLAIN", "ANALYZE",
    "ALTER"
);
// CREATE, DROP, TRUNCATE は許可されていない
// ALTER は ALTER TABLE のみ（ALTER USER 等は不可）
```

### ブロックリスト（ファイルアクセス系）

```java
List<String> BLOCKED_SQL_FRAGMENTS = List.of(
    "INTO OUTFILE", "INTO DUMPFILE", "LOAD_FILE", "LOAD DATA"
);
```

これらはサーバーファイルシステムへのアクセスに使えるため禁止。

### テーブル操作の安全策

テーブル名・カラム名はユーザー入力をそのまま SQL に埋め込む危険性がある。このコードでの対策：

```java
// 1. パターンマッチ（英数字とアンダースコアのみ）
Pattern IDENTIFIER_PATTERN = Pattern.compile("[a-zA-Z0-9_]+");

// 2. resolveTableName() で DB に実際に存在するか確認
// → 存在チェック兼、DB が正規化した名前を取得

// 3. バッククォートで囲んで SQL に埋め込む
"SELECT * FROM `" + resolvedTable + "`"

// 4. escapeSqlIdentifier() でバッククォート自体をエスケープ（多層防御）
identifier.replace("`", "``")
```

### インスタンス管理（Firestore 連携）

```
コマンド送信（POST /admin/instances/{id}/command）
  └── Firestore の instances/{id} ドキュメントに cmd フィールドを書き込み
  └── 非同期（CompletableFuture.runAsync）で実行 → 202 を即座に返す
  └── requestId を返す

結果取得（GET /admin/instances/{id}/command/{requestId}）
  └── Firestore から instances/{id} を取得
  └── res.requestId が一致したら結果を返す
  └── まだなら 202（pending）
```

インスタンスが Firestore の `instances/{id}` を監視して cmd を受信し、実行結果を `res` フィールドに書き込む（InstanceManager の役割）。

---

## 8. 並行処理・バックグラウンドジョブ

### スレッドの種類

| スレッド名 | 種類 | 役割 |
|---|---|---|
| `bg-poller-N` | ScheduledExecutorService（8本） | 定期ポーリング・キャッシュ更新 |
| `db-init` | 単独スレッド（デーモン） | DB 初期化とリトライ |
| `metrics-flush` | ScheduledExecutorService（1本） | メトリクス DB 書き込み |
| `metrics-shutdown` | ShutdownHook | 終了時の最終フラッシュ |
| Jetty Virtual Threads | VirtualThread per request | HTTP リクエスト処理 |

### デーモンスレッドとは

```java
t.setDaemon(true);
```

JVM 終了時にデーモンスレッドは強制終了される。メインスレッドが終わってもバックグラウンドジョブがアプリを生かし続けることがないようにするため。

### バックグラウンドジョブ一覧

```java
// 30秒ごとにイベント・フード・マップ更新
bg.scheduleAtFixedRate(() -> dataController.refreshAll(), 30, 30, SECONDS);

// 60秒ごとにお知らせ更新
bg.scheduleAtFixedRate(() -> AnnouncementsController.refreshCache(), 60, 60, SECONDS);

// 30秒ごとに混雑情報更新
bg.scheduleAtFixedRate(() -> CongestionController.refreshCache(), 30, 30, SECONDS);

// 50分ごとに JWKS 先取り更新
bg.scheduleAtFixedRate(cfAccessAuth::prefetchJwks, 50, 50, MINUTES);

// routes.yaml のキャッシュルートを各TTL間隔で更新
YamlRouteLoader.startBackgroundRefreshes(bg);

// 30秒ごとにインスタンスメトリクス記録
bg.scheduleAtFixedRate(() -> InstanceManager.get().recordMetrics(), 30, 30, SECONDS);
```

### エラー通知の条件

```java
int fails = dataFailCount.incrementAndGet();
if (fails == 1 || fails % 5 == 0) {
    DiscordWebhook.sendError(...);
}
// 1回目と5の倍数回目のみ通知（通知スパムを防ぐ）
```

### 並列初期化の仕組み

```java
List<Future<?>> initFutures = new ArrayList<>();
initFutures.add(bg.submit(() -> { new DataController().refreshAll(); return null; }));
initFutures.add(bg.submit(() -> { CongestionController.refreshCache(); return null; }));
// ...

// 全て完了を待つ
Exception initError = null;
for (Future<?> f : initFutures) {
    try { f.get(); }
    catch (ExecutionException ex) {
        if (initError == null) initError = (Exception) ex.getCause();  // 最初のエラーのみ保持
    }
}
if (initError != null) throw initError;
```

### Virtual Thread と `synchronized` の注意点

```java
// Virtual Thread で synchronized を使うと
// キャリアスレッド（OS スレッド）がピン留めされて高負荷時にスループット低下

// 対策: ReentrantLock を使う
private final ReentrantLock slotLocks = new ReentrantLock();
// Virtual Thread は ReentrantLock でブロックされても
// キャリアスレッドを手放してくれる
```

---

## 9. RequestMetrics（メトリクス永続化）

### データ構造

```
時間帯別カウント（24時間分のリングバッファ）
  hourlyCounts[slot] / hourlyErrors[slot]
  slotHour[slot] = そのスロットが担当する "epoch hour"

エンドポイント別カウント（当日分）
  endpointCounts: ConcurrentHashMap<"GET /events", LongAdder>

レイテンシヒストグラム
  histogram[bucket]: 0ms, 10ms, 25ms, 50ms, 100ms, 200ms, 500ms, 1000ms, 2000ms, 5000ms
```

### スナップショットパターン

```java
// 書き込み: 高頻度（全リクエスト）
// 読み取り: 低頻度（admin/stats アクセス時）

// 解決策: 45秒ごとに DB へフラッシュ → スナップショット更新
// 読み取りはスナップショットから返す（ロックなし）
private volatile MetricsSnapshot snapshot;

// スナップショット交換
snapshot = new MetricsSnapshot(total, errors, hourly, percents, top);
// volatile で参照の可視性を保証
```

### 時間繰り上がり処理（スロットリセット）

```java
// 高速パス（99.99%のケース）: ロックなし
if (slotHour.get(slot) == hour) {
    hourlyCounts[slot].incrementAndGet();
    return;
}

// 低速パス（1時間に1回/スロット）: ロックあり
slotLocks[slot].lock();
try {
    if (slotHour.get(slot) != hour) {  // ダブルチェック
        hourlyCounts[slot].set(1);      // リセット
        slotHour.set(slot, hour);       // 公開
    } else {
        hourlyCounts[slot].incrementAndGet();  // 先行スレッドがリセット済み
    }
} finally { slotLocks[slot].unlock(); }
```

### フラッシュの仕組み（差分書き込み）

全カウントを毎回書くのではなく、**前回フラッシュからの差分**のみを DB に追記する。

```java
long current = hourlyCounts[s].get();
long diff    = current - lastFlushedReq[s];
if (diff <= 0) continue;  // 変化なしはスキップ
lastFlushedReq[s] = current;
// DB: ON DUPLICATE KEY UPDATE requests = requests + ?（差分を加算）
```

---

## 10. セキュリティ設計のポイント

### 多層防御の考え方

単一の防御が破られても次の層が止める設計：

```
層1: CloudflareIpFilter  → Cloudflare 以外の IP をブロック
層2: ORIGIN_SHARED_SECRET → 第三者 CF ゾーン経由をブロック
層3: ApiKeyAuth          → API キーなしをブロック
層4: CfAccessAuth        → 管理者メール以外をブロック
層5: IDENTIFIER_PATTERN  → SQL インジェクションの文字を排除
層6: escapeSqlIdentifier → バッククォートを二重化（念のため）
層7: resolveTableName    → DB が実際に持つテーブル名のみ使用
```

### SQL インジェクション対策

ユーザー入力を SQL に埋め込む場合は常に `PreparedStatement` を使う：

```java
// 危険
"SELECT * FROM users WHERE id = " + userId

// 安全
PreparedStatement ps = conn.prepareStatement("SELECT * FROM users WHERE id = ?");
ps.setInt(1, userId);
```

テーブル名・カラム名はバインドパラメータで渡せないため、別の方法で保護：
1. ホワイトリスト（`IDENTIFIER_PATTERN`）でフィルタ
2. DB から取得した実際の名前を使う（`resolveTableName`）
3. バッククォートで囲む

### CORS とプリフライト

```
ブラウザの動作:
  複雑なリクエスト（POST with JSON 等）の前に OPTIONS を送る

このコードの処理:
  全 before フィルタを通過（CloudflareIpFilter は OPTIONS もチェック）
  ApiKeyAuth・CfAccessAuth は OPTIONS をスキップ（ブラウザはプリフライトに認証ヘッダを付けないため）
  OPTIONS なら 204 を返す
```

### ヘッダインジェクション防止

```java
public Context header(String key, String value) {
    if (key.contains("\r") || key.contains("\n") ||
        value.contains("\r") || value.contains("\n")) {
        throw new IllegalArgumentException("Header contains illegal characters");
    }
    ...
}
```

`\r\n` を含むヘッダを返すと HTTP レスポンス分割攻撃（Response Splitting）になるため。

### パストラバーサル防止

```java
// Gate.java の service() メソッド
if (path.contains("..")) {
    response.sendError(400);
    return;
}
```

`../../etc/passwd` のようなパスを拒否。Jetty の正規化に加えた多層防御。

---

## 11. データベース操作パターン

### 基本パターン（try-with-resources）

```java
try (Connection conn = Database.getConnection();
     PreparedStatement ps = conn.prepareStatement("SELECT * FROM users WHERE id = ?")) {
    ps.setInt(1, id);
    try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
            // rs.getString("name") など
        }
    }
} // conn と ps は自動クローズ
```

### NULL 安全な値取得

```java
// DOUBLE カラム（NULL あり）
double x = rs.getDouble("x");
if (!rs.wasNull()) node.put("x", x);  // ← wasNull() で NULL チェック

// INT カラム（NULL あり）
int locId = rs.getInt("location_id");
if (!rs.wasNull()) p.put("location_id", locId);
```

`rs.getDouble()` は NULL の場合 0.0 を返す。`wasNull()` を呼ばないと NULL と 0.0 を区別できない。

### TINYINT の扱い

```java
// TINYINT は MySQL では -128〜127 の整数
// しかし JDBC ドライバによっては Boolean で返すことがある
// 明示的に getInt() で取ることで一貫した挙動を保証
if (type == Types.TINYINT || type == Types.BIT) {
    int v = rs.getInt(i);
    return rs.wasNull() ? null : v;
}
```

### トランザクション

```java
conn.setAutoCommit(false);
try {
    // 複数の書き込み処理
    conn.commit();
} catch (Exception e) {
    conn.rollback();
    throw e;
} finally {
    conn.setAutoCommit(true);
}
```

**DDL（ALTER TABLE 等）は MySQL で暗黙コミットされるため**、`rollback()` が効かない。このコードでは DDL がある場合はトランザクションを使わない設計にしている。

### ON DUPLICATE KEY UPDATE（Upsert）

```java
// 存在すれば UPDATE、なければ INSERT
"INSERT INTO congestion_status (location_code, level, ...) VALUES (?, ?, ...) " +
"ON DUPLICATE KEY UPDATE level = VALUES(level), ..."
```

冪等な更新に使う。同じキーで何度実行しても最終状態は同じ。

### DatabaseMetaData の活用

```java
// テーブルの主キー取得
conn.getMetaData().getPrimaryKeys(null, null, tableName)

// カラム一覧取得
conn.getMetaData().getColumns(null, null, tableName, null)
```

スキーマ情報を動的に取得できるため、管理画面のような汎用 DB ブラウザが作れる。

---

## 12. よく聞かれる「なぜ？」集

### なぜ DB 初期化を別スレッドで行うのか

HTTP サーバー（`gate.start()`）を先に起動することで、DB がまだ準備できていない間も `/health` エンドポイントが応答できる。Cloud Run の起動ヘルスチェックが `/health` を叩くため、DB 待ちでタイムアウトしないようにしている。

### なぜ `ReentrantLock` を `synchronized` の代わりに使うのか

Java 21 の Virtual Thread は `synchronized` ブロック内でブロックされると、OS スレッド（キャリアスレッド）がピン留めされる。`ReentrantLock` ではピン留めが起きないため、高並列時のスループットが落ちない。

### なぜ `AtomicReference` で参照を丸ごと交換するのか

```java
// ダメなパターン（非アトミック）
cache.setJson(newJson);
cache.setGzip(newGzip);  // ← ここで別スレッドが読むと json と gzip が不一致

// 正しいパターン（アトミック）
AtomicReference<CacheEntry> cache = ...;
cache.set(new CacheEntry(json, gzip, etag));  // 参照の交換は原子的
```

新しい `CacheEntry` を作ってから参照を一気に交換するため、読み取り側が中途半端な状態を見ることがない。

### なぜ `volatile` だけでは不十分なケースがあるのか

`volatile` は単一の読み書きを原子的にするが、複数フィールドへの操作をまとめて原子的にはできない。`AtomicReference` はオブジェクト参照の交換を原子的にするが、その中のフィールドは保護しない（だから不変オブジェクト `record` と組み合わせる）。

### なぜ ETag にハッシュを使うのか

コンテンツが変わっていない場合（DB の内容が同じ）は同じ ETag が生成される。クライアントは `If-None-Match` で持っている ETag を送り、一致すれば 304（ボディなし）で返せる。転送量を削減できる。

### なぜ DDL を含む SQL はトランザクションにしないのか

MySQL の DDL（`CREATE TABLE`, `ALTER TABLE` 等）は実行と同時に暗黙的にコミットされる。`conn.setAutoCommit(false)` にしていても DDL は取り消せないため、混在させるとロールバック時の動作が直感と異なる。DDL がある場合はオートコミットのままで実行する。

### なぜ `MessageDigest.isEqual()` を使うのか（タイミング攻撃）

通常の文字列比較（`equals()`）は最初に違う文字が見つかった時点でリターンする。攻撃者はレスポンス時間を計測することで「何文字目まで合っているか」を推測できる。`MessageDigest.isEqual()` は全バイトを必ず比較するため時間が一定になる。

### なぜ `stale-while-revalidate` を使うのか

```
キャッシュが古い（max-age 超過）
  └── stale-while-revalidate の期間内 → 古いコンテンツを即座に返す（ユーザーに待たせない）
                                        + 裏でリフレッシュ
  └── stale-while-revalidate も超過  → リフレッシュが完了するまで待つ（or エラー）
```

祭り当日に大量アクセスがあっても、CDN がオリジンサーバー（このアプリ）への問い合わせを分散しながら新鮮なコンテンツを返し続けられる。

### なぜ XFF の「最右端の非プライベート IP」を使うのか

```
攻撃者が XFF を偽装する場合:
  X-Forwarded-For: 1.1.1.1, [偽装した CF IP]

  → 最左端（1.1.1.1）を使ったら偽装し放題
  → 最右端（偽装した CF IP）を使ったら同様

正しい方法:
  Cloudflare が末尾に本物の CF IP を追記する
  → その手前の「最右端の非プライベート IP」が真のオリジン IP
  → プライベートアドレスは中間プロキシの内部 IP なのでスキップ
```

ただしこの対策も `ORIGIN_SHARED_SECRET` なしでは「第三者の CF ゾーン経由」は防げない（公式の CF IP から来ているのは本物だが、自分のゾーン向けのリクエストかは分からない）。

---

## 付録：環境変数一覧

| 変数名 | 必須 | 説明 |
|---|---|---|
| `API_KEY` | ✅ | 全 API 認証キー |
| `READ_ONLY_KEY` | | 読み取り専用キー |
| `CF_ACCESS_AUD` | ✅（本番） | CF Access Application AUD |
| `CF_ACCESS_TEAM_DOMAIN` | ✅（本番） | CF Access チームドメイン |
| `ADMIN_EMAILS` | ✅（本番） | 管理者メール（カンマ区切り） |
| `CF_ACCESS_DEV_DISABLE` | | `true` で JWT 検証スキップ（開発用） |
| `SKIP_CF_IP_CHECK` | | `true` で IP チェックスキップ（開発用） |
| `ORIGIN_SHARED_SECRET` | 推奨 | CF Transform Rule で注入するシークレット |
| `DB_HOST` | | DB ホスト（config.yml 上書き） |
| `DB_PORT` | | DB ポート |
| `DB_NAME` | | DB 名 |
| `DB_USER` | | DB ユーザー |
| `DB_PASSWORD` | | DB パスワード |
| `CLOUD_SQL_INSTANCE` | | Cloud SQL インスタンス名 |
| `APP_VERSION` | | アプリバージョン（デプロイ時注入） |
| `BUILD_SHA` | | Git コミット SHA（デプロイ時注入） |
| `HOSTNAME` | | Cloud Run インスタンス ID（自動設定） |
| `RUNMODE` | | `azure` で GcpMetricsController 無効 |
| `CF_API_TOKEN` | | Cloudflare キャッシュパージ用トークン |
| `CF_ZONE_ID` | | Cloudflare ゾーン ID |
| `GITHUB_PAT` | | routes.yaml 編集用 GitHub PAT |
| `GITHUB_OWNER` | | GitHub オーナー名 |
| `GITHUB_REPO` | | GitHub リポジトリ名 |
| `GITHUB_BRANCH` | | 対象ブランチ |
| `GITHUB_YAML_PATH` | | routes.yaml のパス |
| `GITHUB_WORKFLOW_FILE` | | GitHub Actions ワークフローファイル名 |
