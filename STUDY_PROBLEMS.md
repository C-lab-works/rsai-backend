# rsai-backend メンテナンス習得 練習問題集

このコードベース（Java 21 / Gate フレームワーク / Cloudflare / Cloud Run）を自力でメンテできるようになるための問題集。
初級→中級→上級の順に取り組むと、最終的にバグ対応が独力でできるレベルに到達できる。

---

## 初級（50問）— コードが読める・用語が分かるレベル

### Gate フレームワーク基礎（Q1〜Q10）

**Q1.** `@GateController` アノテーションはどのような役割を持つか。`gate.scan()` との関係を含めて答えよ。

**Q2.** 次のメソッドは何をするか。`ctx.result("pong")` と `ctx.json(Map.of("ok", true))` の違いも述べよ。
```java
@GetMapping("/ping")
public void ping(Context ctx) {
    ctx.result("pong");
}
```

**Q3.** `ctx.pathParam("id")` と `ctx.query("limit")` はどちらもリクエストから値を取り出すが、それぞれ何の違いがあるか。以下のリクエストを例に答えよ。
```
GET /admin/instances/abc-123/metrics?limit=40
```

**Q4.** `gate.before()` と `gate.after()` に Handler を登録した場合、実行順序はどうなるか。また `before` フィルタで例外が `throw` された場合、`after` フィルタは実行されるか。

**Q5.** `ctx.halt()` を呼んだとき何が起きるか。`throw new RuntimeException()` との違いを述べよ。

**Q6.** 次のコードでなぜ `ctx.status(401)` だけではルート処理が止まらないのか。
```java
gate.before(ctx -> {
    if (ctx.requestHeader("Authorization") == null) {
        ctx.status(401).result("Unauthorized");
        // ← throw も halt() も呼んでいない
    }
});
```

**Q7.** HTTP レスポンスのデフォルトステータスコードは何か。`ctx.status()` を呼ばなかった場合を答えよ。

**Q8.** `gate.register(new DataController())` と `gate.scan("dev.gate")` の違いを説明せよ。

**Q9.** `@WsMapping("/chat")` アノテーションを持つメソッドのシグネチャはどうなるか。通常の `@GetMapping` との違いを述べよ。

**Q10.** ルーティングで完全一致ルートとパターンルートが両方存在する場合、どちらが優先されるか。また `/users/` と `/users` は同じルートにマッチするか。

---

### 設定・起動フロー（Q11〜Q20）

**Q11.** アプリのバージョン文字列はどこから取得されるか。`version.txt` をソースに含めていない理由も答えよ（`Main.java:202` 付近のコメント参照）。

**Q12.** DB 初期化（`Database.init()`）がネットワーク障害で失敗した場合、アプリはどう振る舞うか。再試行間隔はどう変化するか。

**Q13.** `APP_READY` フラグが `false` の間、`/health` エンドポイントは何を返すか。このフラグが役に立つシチュエーションを述べよ。

**Q14.** `startDatabaseInit()` がバックグラウンドスレッドで行われているため、`gate.start()` が返った直後に `/events` へリクエストが来た場合何が起きるか。

**Q15.** `config.yml` が存在しない場合、アプリはクラッシュするか。その挙動を答えよ。

**Q16.** MySQL のパスワードを `config.yml` に書かずに設定したい。どの環境変数を使えばよいか。

**Q17.** `Main.bg` スレッドプールのスレッド数はいくつか。スレッド名の命名パターンと `daemon` に設定している理由も答えよ。

**Q18.** Cloud SQL（GCP）に接続する設定方法を `config.yml` のフィールド名で答えよ。`host`/`port` 設定との優先関係は？

**Q19.** JVM シャットダウン時に DB 接続プールを閉じるコードはどこにあるか（ファイル名と処理の仕組みを答えよ）。

**Q20.** バックグラウンドで定期更新されるデータとその間隔を一覧にせよ（`Main.startBackgroundJobs()` 参照）。

---

### エンドポイントとキャッシュ（Q21〜Q30）

**Q21.** `/events` レスポンスの `Cache-Control` ヘッダの値を答えよ。`s-maxage` と `max-age` の違いは何か。

**Q22.** クライアントが `If-None-Match: "abc123"` ヘッダを付けてリクエストした場合、サーバーはどう応答するか。`304` を返すときのレスポンスボディは？

**Q23.** `DataController` がレスポンスを gzip 圧縮して返す条件を答えよ。

**Q24.** 初回起動直後（キャッシュがまだ空の状態）に `/events` へリクエストが来た場合、何が返るか。

**Q25.** `CacheEntry` レコードが保持する3つのフィールドを答えよ。それぞれの用途は何か。

**Q26.** `DataController.refreshAll()` はどのように events・food・map を並列更新するか。`Future` の役割も説明せよ。

**Q27.** `/announcements` のキャッシュ更新間隔と `Cache-Control` 設定を答えよ。`/events` と異なる理由を推測せよ。

**Q28.** `AnnouncementsController.cache` が `ConcurrentHashMap` ではなく `AtomicReference<CacheEntry>` を使っている理由を述べよ。

**Q29.** `DataController.cache` への書き込みと読み取りはスレッドセーフか。その根拠を答えよ。

**Q30.** `Cache-Control: no-store` を返しているエンドポイントを2つ挙げ、その理由を述べよ。

---

### Cloudflare・セキュリティ基礎（Q31〜Q40）

**Q31.** `SKIP_CF_IP_CHECK=true` はいつ使うか。本番環境でこの設定を有効にすることが危険な理由も答えよ。

**Q32.** `X-Forwarded-For` ヘッダとは何か。プロキシを複数経由した場合の値の形式を答えよ。

**Q33.** `CloudflareIpFilter` が IP チェックを免除するパスを答えよ。なぜそのパスを免除するか。

**Q34.** `ORIGIN_SHARED_SECRET` 環境変数を設定した場合、IP チェックとの関係はどうなるか。設定していない場合の警告メッセージの内容も答えよ。

**Q35.** `CF_ACCESS_DEV_DISABLE=true` を設定した場合、`/admin` エンドポイントへのアクセスはどうなるか。

**Q36.** `ADMIN_EMAILS` が空のまま CF Access が有効な状態でアプリを起動しようとするとどうなるか（`CfAccessAuth.java:104` 付近参照）。

**Q37.** Cloudflare Access の JWT はどのリクエストヘッダで送られてくるか。

**Q38.** `CfAccessAuth.handle()` が `/health` パスをスキップする理由を答えよ。

**Q39.** `OPTIONS` リクエストを `CfAccessAuth` がスキップする理由を答えよ。スキップしないとどんな問題が起きるか。

**Q40.** `ADMIN_EMAILS` に含まれないメールアドレスのユーザが `/admin/tables` にアクセスしようとすると、何ステータスが返るか。処理フローを追って答えよ。

---

### AdminController 基礎（Q41〜Q50）

**Q41.** `GET /admin/tables` が返す情報を答えよ。ソース上で使われている SQL も確認せよ。

**Q42.** `POST /admin/sql` で実行できる SQL コマンドの種類（ホワイトリスト）を答えよ。`CREATE TABLE` は実行できるか。

**Q43.** インスタンスにコマンドを送信するエンドポイント（HTTP メソッドとパス）を答えよ。レスポンスとして何が返るか。

**Q44.** `POST /admin/instances/{id}/command` で送ったコマンドの実行結果を確認するにはどうするか（エンドポイントとポーリングの仕組み）。

**Q45.** `rejectInvalidInstanceId()` が「不正」と判断する instanceId の例を3つ挙げよ。許容される文字セットも答えよ。

**Q46.** `POST /admin/cache/clear` が実行する処理を順番に列挙せよ（キャッシュ更新以外の処理も含む）。

**Q47.** `/admin/instances` で「stopped」と判定されるインスタンスの条件を2つ挙げよ（`listInstances()` 参照）。

**Q48.** テーブルの行を更新するエンドポイントの HTTP メソッドとパターンを答えよ。`{pk}` には何が入るか。

**Q49.** `AuditLog.write()` を呼んでいる AdminController のメソッドを3つ挙げ、それぞれ何を記録するか答えよ。

**Q50.** `DiscordWebhook.sendAdminOp()` を呼ぶ条件（AdminController 内）を答えよ。`SELECT` 文を実行した場合も通知されるか。

---

## 中級（50問）— ロジックが追える・修正ができるレベル

### ミドルウェアと処理フロー（Q51〜Q60）

**Q51.** `Main.java` でミドルウェアが `SecurityHeaders → CloudflareIpFilter → ApiKeyAuth → CfAccessAuth` の順で登録されている理由を述べよ。順序を入れ替えた場合の問題点も指摘せよ。

**Q52.** `Gate.java` の `service()` メソッドを読み、before フィルタで `halt()` が呼ばれた場合の処理フローを図示せよ（ルートハンドラ・after フィルタの実行有無を含む）。

**Q53.** after フィルタが例外を throw した場合、そのエラーはどこへ行くか。エラーハンドラに届くか。

**Q54.** `gate.before(metrics::startTimer)` はどのように動くか。`startTimer()` はどの情報をどこに保存するか。また Virtual Thread で `ThreadLocal` を使わない理由を答えよ。

**Q55.** CORS のプリフライト（OPTIONS）リクエストはどのように処理されるか。before フィルタ → OPTIONS 処理 → after フィルタの順を追って説明せよ。

**Q56.** `ApiKeyAuth`（コード未読でも推論可）が `OPTIONS` をスキップしなかった場合、何が起きるか。ブラウザの動作を踏まえて答えよ。

**Q57.** エラーハンドラ自身が例外を throw した場合どうなるか（`Gate.java` と `README_ja.md` の記述を参照）。

**Q58.** `record()` メソッドが after フィルタとして動作し、5xx エラーと 429 を Discord に通知する仕組みを追え。`/health` パスが除外されている理由も述べよ。

**Q59.** `CfAccessAuth.handle()` において `POST /congestion/{id}` へのリクエストで JWT トークンが不正だった場合、ハンドラ（CongestionController）は実行されるか。実行される場合、email attribute はどうなるか。

**Q60.** `gate.before()` に登録したフィルタが `halt()` なしに例外を投げた場合と `halt()` を呼んだ場合で after フィルタの実行順序に違いはあるか。

---

### キャッシュ詳細（Q61〜Q70）

**Q61.** `CfAccessAuth` の JWKS キャッシュ TTL は1時間だが、50分ごとにプリフェッチを実行している理由を答えよ。TTL の10分前に更新する設計の意図は何か。

**Q62.** `getPublicKey()` でダブルチェックロッキングを実装している理由と、`ReentrantLock` を使っている理由を答えよ（`synchronized` ではなく）。

**Q63.** JWT 検証結果を `verificationCache` にキャッシュしているが、**インフラ障害**（JWKS 取得失敗）の場合はキャッシュしない理由を答えよ。**署名検証失敗**の場合はどうか。

**Q64.** `CloudflareIpFilter` の `ipMatchCache` の上限は何件か。Caffeine に `executor(Runnable::run)` を指定している意味は何か。

**Q65.** `DataController.refreshKey()` が新しい `CacheEntry` を `cache.put()` する処理はアトミックか。更新中に別スレッドが古いキャッシュを読んでしまう問題は発生するか、その理由を答えよ。

**Q66.** `stale-while-revalidate=600` の意味を説明せよ。CDN 側でどのように機能するか。

**Q67.** `AnnouncementsController` のキャッシュが `AtomicReference` であることで、`cache.set()` と `cache.get()` はどの程度のスレッドセーフ性が保証されるか。部分的な更新（破損したオブジェクト）は発生しうるか。

**Q68.** `RequestMetrics.record()` でスロットの時間繰り上がり（1時間に1回）を検出する仕組みを説明せよ。ロックの取り方と `AtomicLong` の使い方に注目せよ。

**Q69.** `refreshSnapshot()` が `admin/stats` エンドポイントから毎回呼ばれているが、スナップショットは DB から取り直すため負荷が高い。この設計の問題点と改善案を述べよ。

**Q70.** `Router` のパターンキャッシュ（`patternCache`）のサイズ制限が 1024 である理由を考えよ。新しいルートが登録された際にキャッシュが全削除される理由も答えよ。

---

### 並行処理（Q71〜Q80）

**Q71.** `Main.bg` スレッドプールをデーモンスレッドに設定している理由は何か。デーモンでない場合の問題は何か。

**Q72.** `startDatabaseInit()` が別スレッドで動く理由を説明せよ。同期実行にした場合の問題は何か。

**Q73.** `sendCommand()` で Firestore への書き込みを `CompletableFuture.runAsync()` で非同期実行している理由を答えよ（コメントも参照）。

**Q74.** `DataController.refreshAll()` で `Future.get()` を順次呼ぶとき、events の Future が先に例外を出し food の Future が後から正常終了した場合、呼び出し元には何が伝わるか。

**Q75.** `AtomicBoolean APP_READY` と通常の `boolean` フィールドの違いを Java のメモリモデルの観点から説明せよ。`volatile` でも十分か。

**Q76.** `dataFailCount.incrementAndGet()` で Discord 通知を「1回目と5の倍数回」に限定している理由を答えよ。

**Q77.** `startDatabaseInit()` のバックオフ戦略（初回2秒→最大30秒）をコードで確認し、`Math.min(backoffMs * 2, 30_000L)` の意味を説明せよ。

**Q78.** `initFutures` でエラーが複数発生した場合、どのエラーが最終的に throw されるか。残りのエラーはどうなるか。

**Q79.** `InstanceManager.get().init()` に渡されるコールバック `() -> APP_READY.set(false)` はいつ呼ばれるか。その目的は何か。

**Q80.** `RequestMetrics.scheduler` が `ScheduledExecutorService` でシングルスレッドなのはなぜか。複数スレッドにした場合の問題は何か。

---

### SQL と DB 操作（Q81〜Q90）

**Q81.** `splitStatements()` がシングルクォート、ダブルクォート、バッククォートで囲まれた文字列内のセミコロンを正しく扱う仕組みを説明せよ。

**Q82.** `normalizeSql()` が行う3つの処理をコード（`AdminController.java:781`）から説明せよ。なぜ大文字化・空白圧縮をするのか。

**Q83.** DDL（ALTER TABLE）を含む SQL の場合にトランザクション（`conn.setAutoCommit(false)`）を使わない理由を答えよ。

**Q84.** `resolveTableName()` が `PreparedStatement` を使ってテーブル名を確認し、その結果を SQL に直接埋め込んでいる設計を説明せよ。これが安全な理由は何か。

**Q85.** `getPkColumn()` は複合主キーのテーブルでどう動くか。`ResultSet.next()` を1回しか呼ばない場合の制限を述べよ。

**Q86.** `getColumnValue()` で `TINYINT` と `BIT` を `rs.getObject()` ではなく `rs.getInt()` で取得する理由を答えよ。

**Q87.** `normalizeValue()` が `Boolean` を `1` または `0` に変換する理由を答えよ。MySQL との型の関係も述べよ。

**Q88.** `getTable()` が `LIMIT 500` で行数を制限しているが、`execSql()` では `MAX_RESULT_ROWS = 1000` を使っている。この差異の理由を推測せよ。

**Q89.** `execSql()` で `s.setQueryTimeout(30)` を設定している理由と、これが機能しない場合（JDBC ドライバの実装依存）について答えよ。

**Q90.** `flushEndpoints()` で `lastFlushedEp.get(e.getKey()).add(diff)` を呼ぶが、`diff` を加算するのはなぜか（`diff` ではなく累計 `current` を設定しないのはなぜか）。

---

### セキュリティ詳細（Q91〜Q100）

**Q91.** Cloudflare Access JWT の3つのパーツ（header・payload・signature）をどのように検証しているか、`verifyAndExtractEmail()` を読んで手順を答えよ。

**Q92.** JWT の `exp`・`iat`・`nbf` クレームの役割と、このコードでの検証ロジック（閾値含む）を答えよ。`CLOCK_SKEW_LEEWAY_SECS=30` の意味は何か。

**Q93.** `isWriteKeyword()` が判定する SQL キーワードと、Discord 通知との関係を答えよ。`SELECT` だけ実行した場合は通知されるか。

**Q94.** `BLOCKED_SQL_FRAGMENTS` がブロックする操作（`INTO OUTFILE` 等）は何を防ごうとしているか。これらの操作が危険な理由も答えよ。

**Q95.** `escapeSqlIdentifier()` がバックティックをエスケープしているが、`IDENTIFIER_PATTERN` チェックが先に通った場合、このエスケープは実際に必要か。多層防御の観点から答えよ。

**Q96.** `CidrBlock.matches()` のビット演算処理（`prefix / 8`・`prefix % 8`・マスク計算）を、`173.245.48.0/20` を例に追って説明せよ。

**Q97.** `XFF` ヘッダの「最右端の非プライベート IP」を検証する理由を答えよ。「最左端」や「最右端」ではなく「最右端の非プライベート」を選ぶ根拠は何か。

**Q98.** `MessageDigest.isEqual()` を `origin_secret` の比較に使っているが、通常の `String.equals()` との違いは何か。タイミング攻撃について説明せよ。

**Q99.** `CfAccessAuth` で `audience` クレームが文字列の場合と配列の場合の両方を検証しているコードを確認し、これが必要な理由を述べよ。

**Q100.** `CF_ACCESS_DEV_DISABLE=true` の状態で `/admin/sql` にアクセスした場合（CF Access JWT なし）、どうなるか。`handle()` の分岐を追って答えよ。

---

## 上級（50問）— バグ対応・セキュリティ分析ができるレベル

### バグ発見・修正（Q101〜Q120）

**Q101.** `splitStatements()` はシングルクォート内のバックスラッシュエスケープ（`\'`）を処理しているが、バッククォート（`` ` ``）内のエスケープは処理していない。
- この違いが問題になるケースを具体的な SQL 例で示せ。
- 実際に影響が出る可能性があるか評価せよ（このアプリの用途を踏まえて）。

**Q102.** `getTable()` の `ORDER BY` 節は次のように構築される。
```java
String order = ("desc".equalsIgnoreCase(sort) && pkCol != null)
    ? " ORDER BY `" + pkCol + "` DESC" : "";
```
`getPkColumn()` が `null` を返す場合（主キーなしテーブル）、このコードは安全か。SQL インジェクションの余地はあるか。

**Q103.** `stripSqlComments()` は `--` 形式のコメントを空白に置換するが、文字列リテラル内（例: `'hello -- world'`）の `--` も除去してしまう。
- 実際のユースケースで誤検知が発生するケースを示せ。
- このバグが `execSql()` のブロックリストチェックに与える影響を評価せよ。

**Q104.** `listInstances()` と `countRunningInstances()` で `lastSeen` のカットオフ時間が異なる。
```java
// listInstances: age >= 30 なら continue（表示しない）
// countRunningInstances: Instant.now().minusSeconds(30) より後か確認
```
この差異の影響を調べ、一方の値が 30 秒ちょうどのとき両者の結果が一致するか確認せよ。

**Q105.** `startDatabaseInit()` の `initFutures` ループで `InterruptedException` を再割り込み（`Thread.currentThread().interrupt()`）した後、`throw ex` はなぜ `throws InterruptedException` を必要とするのに、ラムダ内では checked exception を throw できるか調べよ。また interrupt 後に後続の `Future.get()` が呼ばれる可能性はあるか。

**Q106.** `putYamlRoutes()` で `email == null || email.isBlank()` の場合に `"unknown@admin"` を使っている。
```java
if (email == null || email.isBlank()) email = "unknown@admin";
```
このとき `ghPutFile()` の `committer.name` は `"unknown"` になる。CF_ACCESS_DEV_DISABLE=true の本番ミスを除き、実際に `email` が null になりうるケースはあるか調べよ。

**Q107.** `getCommandResult()` は instanceId を次のように Firestore パスへ連結する。
```java
FirestoreRest.get().get("instances/" + instanceId)
```
`rejectInvalidInstanceId()` は `INSTANCE_ID_PATTERN = [a-zA-Z0-9_-]{1,256}` でチェックしている。このパターンが通過させてしまうが Firestore のパス区切りとして問題になる文字は何か。

**Q108.** `stats()` が `getTopEndpoints()` の結果から `/admin` で始まるパスを除外している。
```java
if (path.startsWith("/admin")) continue;
```
しかし `getTopEndpoints()` は全エンドポイントの上位10件を取得してから除外する。除外後に10件未満になる場合の設計的問題を指摘せよ。

**Q109.** `dailyStats()` は JST（`Asia/Tokyo`）で今日・昨日を判定する。Cloud Run のコンテナのタイムゾーンがデフォルト（UTC）のとき、JST の 0:00〜1:00 の間にリクエストが来た場合の挙動を確認せよ。このコードは正しく動くか。

**Q110.** `normalizeValue()` は `Boolean` のみを変換する。
```java
private Object normalizeValue(Object val) {
    if (val instanceof Boolean b) return b ? 1 : 0;
    return val;
}
```
Jackson がリクエスト JSON から `Map<String, Object>` を生成した場合、整数はどの Java 型になるか。これが MySQL の BIGINT カラムへの `setObject()` で問題になるか調べよ。

**Q111.** `addColumn()` でデフォルト値を SQL に埋め込む処理を確認せよ。
```java
sb.append(" DEFAULT '").append(defaultVal.replace("'", "''")).append("'");
```
`DEFAULT_VALUE_PATTERN` が通過させる文字（`[a-zA-Z0-9._\-]+`）でシングルクォートが含まれないため `replace("'", "''")` は実質不要である。では `DEFAULT_VALUE_PATTERN` が **通過させてしまうが意図しない** 値の例を1つ挙げよ（ヒント: MySQL の予約語・関数名）。

**Q112.** `verifyAndExtractEmailCached()` で `exp = 0`（JWT に exp クレームなし）の場合のキャッシュ期間を答えよ。このケースで問題が起きる可能性はあるか。

**Q113.** `countRunningInstances()` が例外で `0` を返す。管理画面の「instances: 0」が「Firestore 障害」なのか「実際にゼロ」なのかを区別できないことの運用上の問題を述べよ。

**Q114.** DB 初期化スレッドがバックオフ中（`Thread.sleep(backoffMs)`）に JVM がシャットダウンされた場合の処理フローを答えよ。`InterruptedException` のハンドリングは適切か。

**Q115.** `clearCache()` が例外を投げる場合（例: DB 接続失敗）、キャッシュは部分的に更新されている可能性がある。具体的にどの順で更新されるかをコードで確認し、中断した場合の整合性問題を述べよ。

**Q116.** `createTable()` で `notNull: true` かつ `defaultValue` がない場合、生成される SQL に `DEFAULT` 句が付かない。AUTO_INCREMENT でも PK でもないカラムにこの条件が当てはまる場合、MySQL はどう振る舞うか。

**Q117.** `startBackgroundJobs()` の Discord 通知条件 `fails == 1 || fails % 5 == 0` において `AtomicInteger` の `fails` が `Integer.MAX_VALUE`（約21億）を超えた場合、通知条件はどうなるか。

**Q118.** `sendCommand()` の Firestore 書き込みが非同期で失敗しても HTTP 202 を返す。フロントエンドがこの場合に期待する動作（コマンドが実行される）は満たされないが、これを検出・通知する仕組みはあるか。あるとすればどこか。

**Q119.** `IDENTIFIER_PATTERN = [a-zA-Z0-9_]+` は先頭が数字のテーブル名（例: `123abc`）を許可する。MySQL では先頭が数字のテーブル名はバッククォートなしで使えるか。このコードでは `` ` `` で囲んでいるので問題ないが、他に許可すべきでない名前の例を考えよ。

**Q120.** `putYamlRoutes()` の `validateRoutesYamlDb()` でカラム名の比較を `col.toLowerCase()` で行っている。
```java
if (!existingCols.contains(col.toLowerCase()))
```
`existingCols` に `rs.getString("COLUMN_NAME").toLowerCase()` で登録しているので一致するが、MySQL の照合順序（collation）が大文字小文字を区別する場合の潜在的な問題を述べよ。

---

### セキュリティ脆弱性分析（Q121〜Q135）

**Q121.** MySQL の `/*!50000 ... */` 形式の実行コメントを `stripSqlComments()` がどう処理するかを `exec = true` の分岐を追って確認せよ。この処理の後でブロックリストチェックが行われるが、展開後の内容がブロックリストを回避できるケースはあるか考えよ。

**Q122.** `ALLOWED_SQL_KEYWORDS` に `ALTER` が含まれている。`ALTER TABLE` 以外の ALTER（例: `ALTER USER`）はどこでブロックされるか。コードを追って確認せよ。

**Q123.** `BLOCKED_SQL_FRAGMENTS` は次の操作をブロックする。
```
"INTO OUTFILE", "INTO DUMPFILE", "LOAD_FILE", "LOAD DATA"
```
`SELECT @variable INTO @target` 形式はブロックされるか確認し、危険性を評価せよ。

**Q124.** JWT の `audience` クレームが文字列の場合と配列の場合を両方処理しているが、`null` の場合はどうなるか。`audNode` が `null` のとき `audMatched` はどうなるか確認せよ。

**Q125.** `CloudflareIpFilter` は `X-Forwarded-For` の最右端の非プライベート IP を検証する。攻撃者が自分の Cloudflare ゾーンをこのサーバに向けることで IP チェックを通過できるか。`ORIGIN_SHARED_SECRET` を設定していない場合と設定した場合に分けて答えよ。

**Q126.** `MessageDigest.isEqual()` はタイミング攻撃を防ぐが、`verificationCache` の `getIfPresent(token)` は定数時間か。キャッシュヒット/ミスで応答時間が変わることで何が分かるか。

**Q127.** `adminEmailsRef` が `static volatile` フィールドである。複数の `CfAccessAuth` インスタンスが異なる `ADMIN_EMAILS` 設定で初期化された場合（本番では起きないが）、どのインスタンスの設定が有効になるか。

**Q128.** `CfAccessAuth.handle()` の `/admin` 分岐で `adminEmails.contains(email.toLowerCase())` を使っているが、JWT から取得した `email` は `verifyAndExtractEmail()` で lowercase 変換されているか確認せよ。変換されていない場合、どのようなケースで認証が失敗するか。

**Q129.** `resolveTableName()` でテーブル名を確認後、`getTable()` でそのテーブル名を SQL に直接埋め込む（バッククォートで囲む）。
```java
"SELECT * FROM `" + resolvedTable + "`" + order
```
`resolvedTable` は DB から取得した正確な名前のため SQL インジェクションは不可能だが、別の問題（TOCTOU: Time-Of-Check to Time-Of-Use）を指摘せよ。

**Q130.** `validateRoutesYamlDb()` はテーブルとカラムの存在チェックを行うが、DB ユーザーがそのテーブルに対する権限を持っているかは確認しない。これがどのような問題を引き起こしうるか。

**Q131.** `ghPutFile()` に渡す `sha` パラメータはクライアント（フロントエンド）から送られてくる。攻撃者が任意の sha を送った場合、GitHub API はどう応答するか（409 の意味を踏まえて）。フロントエンドが適切な sha を取得する手順（`GET /admin/yaml/routes`）も含めて評価せよ。

**Q132.** `purgeCfCache()` は `CF_API_TOKEN` を `Authorization: Bearer ...` ヘッダに付けて送る。このトークンがアプリのログに記録されるリスクはあるか。`logger.warn()` の呼び出し箇所を確認せよ。

**Q133.** `DiscordWebhook.sendAdminOp()` の `sqlDetail` 引数には最大200文字の SQL が含まれる。機密データ（パスワードハッシュなど）を含む SELECT 文の結果は通知に含まれるか。`sqlDetail` と `result` の違いを確認せよ。

**Q134.** `buildEvents()` や `buildFood()` の SQL は `Statement.execute()` を使い、ユーザー入力を含まない固定 SQL のため SQL インジェクションのリスクはない。しかし将来フィルタリング機能（クエリパラメータで WHERE 句を追加）を実装する際の注意点を述べよ。

**Q135.** Cloudflare Access JWT の `iat`（issued-at）が 86400 秒以上古い場合を拒否している。Cloudflare の JWT デフォルト TTL（通常24時間）と、このチェックが何を防いでいるかを述べよ。また `CLOCK_SKEW_LEEWAY_SECS` を `exp` チェックにのみ適用し `iat` チェックには適用していない意図を考えよ。

---

### パフォーマンス・設計問題（Q136〜Q145）

**Q136.** `POST /admin/cache/clear` はメインリクエストスレッドで全キャッシュを同期更新する。各 `refreshKey()` が DB クエリを発行するため、DB が遅いとレスポンスまでに数秒かかる可能性がある。この影響とより良い設計を提案せよ。

**Q137.** `execSql()` で `s.setQueryTimeout(30)` を設定しているが、複数のステートメント（セミコロン区切り）がある場合、各ステートメントが最大30秒かかりうる。合計タイムアウトを制御する方法はあるか。

**Q138.** `getTable()` は `SELECT * FROM \`table\`` を実行するが、カラム数が多い・行数が多い（LIMIT 500 まで）テーブルに対するパフォーマンス問題を考えよ。特に `image_url` などの大きなテキストフィールドの扱いを検討せよ。

**Q139.** `DataController.refreshAll()` は `events`・`food`・`map` の3クエリを並列実行するが、`refreshKey()` が `Database.getConnection()` を各タスクで呼ぶ。HikariCP のプールサイズ（デフォルト10）との関係で問題が起きうる状況を考えよ。

**Q140.** `refreshSnapshot()` が `/admin/stats` アクセスのたびに DB クエリ（`metrics_hourly`・`metrics_endpoints`・`metrics_latency_histogram` の3テーブル）を実行する。管理者が連続してダッシュボードを更新した場合の影響を評価せよ。改善案も述べよ。

**Q141.** `CfAccessAuth` の `jwksLock` は `ReentrantLock()` のデフォルト（非公平ロック）。高負荷時に多数のスレッドが JWKS 取得待ちになった場合の挙動と、公平ロック（`new ReentrantLock(true)`）との違いを述べよ。

**Q142.** `Main.bg` スレッドプールのスレッドが全て DB 待ちでブロックされた場合（DB ストール時）、`scheduleAtFixedRate` で登録されたタスクはどうなるか。`bg` が8スレッドである理由をこの観点から説明せよ。

**Q143.** `InstanceManager.broadcastCacheRefresh()` が `clearCache()` の中で呼ばれる。他のインスタンスも `clearCache()` を受信してキャッシュ更新を行うとすると、クラスタ全体で N 回の DB 更新が同時発生する可能性がある（N = インスタンス数）。この設計の問題と対策を述べよ。

**Q144.** `getColumnNames()` は `updateRow()` と `insertRow()` の両方から呼ばれる。`getTable()` でも同様の情報を取得している。これが N+1 クエリパターンに該当するか確認し、影響を評価せよ。

**Q145.** `/admin/stats` のシステムステータスチェックで毎回 `SELECT 1` を DB に発行している。HikariCP 自身もコネクション検証クエリを持つ場合、二重チェックになる可能性を考え、設計の整合性を評価せよ。

---

### エラーハンドリング・ロバストネス（Q146〜Q150）

**Q146.** `flushHistogram()` の実装を確認せよ。
```java
lastFlushedHisto[i].addAndGet(diff);
```
`histogram[i].get()` と `lastFlushedHisto[i].addAndGet(diff)` の間に別スレッドが `histogram[i].incrementAndGet()` を呼んだ場合、次回フラッシュ時の `diff` はどうなるか。データが失われることはあるか。

**Q147.** `execSql()` の catch 節で `sanitizeSqlError()` がエラーメッセージを隠蔽して返す。
```java
ctx.status(400).json(Map.of("error", sanitizeSqlError(e)));
```
管理者ツールであるにもかかわらず詳細なエラーを隠す設計の理由と、デバッグ時の問題を述べよ。改善策も提案せよ。

**Q148.** `getInstanceMetrics()` の limit パラメータ解析を確認せよ。
```java
try { limit = Math.max(1, Math.min(200, Integer.parseInt(ctx.query("limit")))); } catch (Exception ignored) {}
```
`ctx.query("limit")` が `null` の場合、`Integer.parseInt(null)` は何をスローするか。このコードで正しく処理されるか答えよ。

**Q149.** `deleteInstance()` で Firestore から削除が成功した後にエラーが起きた場合（例: レスポンス書き込み失敗）、Firestore 上のドキュメントは既に削除されている。この問題を「冪等性」の観点から評価せよ。

**Q150.** `parseAndValidateYaml()` の重複パスチェックを確認せよ。
```java
if (!seenPaths.add(path))
    throw new IllegalArgumentException("Duplicate path in routes.yaml: " + path);
```
`path` の大文字小文字が異なる場合（例: `/Events` と `/events`）は重複として検出されるか。`Gate` のルーターは大文字小文字を区別するか確認し、もし区別しない場合の問題を述べよ。

---

## 解答のヒント

各問題に取り組む際の参照ファイル一覧：

| ファイル | 主に対応する問題 |
|---|---|
| `Main.java` | Q11〜Q20, Q71〜Q80 |
| `gate-core/core/Gate.java` | Q1〜Q10, Q51〜Q60 |
| `gate-core/core/Router.java` | Q10, Q70 |
| `DataController.java` | Q21〜Q30, Q65, Q136, Q139 |
| `AnnouncementsController.java` | Q27〜Q28, Q67 |
| `CloudflareIpFilter.java` | Q31〜Q34, Q96〜Q97, Q125 |
| `CfAccessAuth.java` | Q35〜Q40, Q61〜Q63, Q91〜Q92, Q126〜Q128 |
| `AdminController.java` | Q41〜Q50, Q81〜Q90, Q101〜Q120, Q121〜Q133 |
| `RequestMetrics.java` | Q58, Q68〜Q69, Q80, Q140, Q146 |
| `README_ja.md` | Q4〜Q6, Q57 |
