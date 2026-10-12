# SmartLifecycle — Spring に「起動の最後に始めて、終了の最初に止めて」と頼む仕組み

`SqsReceiveLoop` が `implements SmartLifecycle` しているのを見て湧く疑問 — 「`start()` や `stop()` をどこからも呼んでいないのに、なぜ受信が始まって、止まるのか?」「Spring が呼んでくれるなら、いつ、どの順番で呼ぶのか?」「親の `Lifecycle` と何が違うのか?」 — に答える学習メモ。

対象ファイル: [SqsReceiveLoop.java](../../../../backend/src/main/java/com/example/app/common/queue/SqsReceiveLoop.java) / [AuthMailQueueConfig.java](../../../../backend/src/main/java/com/example/app/auth/AuthMailQueueConfig.java)

> **このメモのコード例と検証について**
> コードはこのリポジトリの実物。`start()` / `stop()` が呼ばれる順番は、**worker コンテナのログで実測した**(→ [実測](#実測-devtools-の再起動で-stop--start-の順番を見る))。
> ただし実測は devtools の再起動によるもので、**ECS が送る SIGTERM による終了ではない**。本物の SIGTERM での止まり方は stg で確かめる。
> 対象バージョンは Spring Boot 4.1.0 / Java 21(`build.gradle` で確認)。

## まず結論(3 行)

1. **Bean として登録された `SmartLifecycle` は、Spring が勝手に `start()` と `stop()` を呼ぶ。** 自分で呼ぶコードは要らない。SIGTERM を `stop()` に繋ぐのも Spring Boot がやる。
2. **`start()` は全 Bean ができた後(起動の最後)、`stop()` は Bean を破棄する前(終了の最初)。** だから受信ループが動いている間は、DB のプールや SES のクライアントが必ず使える。
3. **`Lifecycle` との違いは「自動で起動するか」と「順番を指定できるか」。** `Lifecycle` だけだと、起動時に `start()` は呼ばれない。

## 前提: Bean になっていること

インターフェースを実装しただけでは何も起きない。**Spring が管理している Bean** であることが条件。

このリポジトリでは `SqsReceiveLoop` に `@Component` を付けず、`AuthMailQueueConfig` の `@Bean` メソッドが `new SqsReceiveLoop(...)` を返して Bean にしている。
`AuthMailQueueConfig` には `@ConditionalOnProperty(name = "app.role", havingValue = "worker")` が付いているので、

- `APP_ROLE=worker` → Bean になる → Spring が `start()` を呼ぶ → 受信が始まる
- `APP_ROLE=web` → クラスごと読み込まれない → Bean が無い → 受信しない

という切り替えが、`SmartLifecycle` の「Bean なら呼ばれる」という性質だけで成り立っている。

## 呼ばれるタイミング

```
起動:
  全 Bean を作る(DataSource、SqsClient、AuthMailWorker、SqsReceiveLoop 自身…)
    → Web サーバーの start()(Tomcat が 8080 で受付開始)
    → SqsReceiveLoop の start()(受信スレッドを立てる)
    → 「Started Application in ... seconds」

終了(ECS が SIGTERM を送る / devtools が再起動する):
  SqsReceiveLoop の stop()(新しい受信をやめ、処理中のメッセージを終えてから戻る)
    → Web サーバーのグレースフルシャットダウン
    → Bean の破棄(DB のコネクションプールを閉じる など)
    → JVM 終了
```

- **SIGTERM を `stop()` に繋いでいるのは Spring Boot。** 起動時に JVM のシャットダウンフックを登録していて、SIGTERM を受けるとアプリのコンテキストを閉じる。その過程で `stop()` が呼ばれる。シグナルを扱うコードを自分で書く必要は無い
- **`stop()` を呼ぶのは main スレッドではない。** シャットダウンフックや devtools のスレッドから呼ばれる。だから `SqsReceiveLoop` は、`stop()` を呼ぶスレッドと受信スレッドの 2 本で `running` を共有することになり、`volatile` が要る(`SqsReceiveLoop` のフィールド `running` のコメント)

## Spring が見ているメソッド

`SqsReceiveLoop` が実装しているのは `start()` / `stop()` / `isRunning()` の 3 つ。残りはインターフェースの既定値のまま。

| メソッド | 役割 | `SqsReceiveLoop` では |
| --- | --- | --- |
| `start()` | 起動時に呼ばれる | 受信スレッド `sqs-receiver-auth-mail` を立てる |
| `stop()` | 終了時に呼ばれる | `running = false`、待ちの最中なら割り込み、受信スレッドの終わりを最大 25 秒待つ |
| `isRunning()` | 今動いているか。**Spring は `false` のときだけ `start()` を、`true` のときだけ `stop()` を呼ぶ** | `running` を返す |
| `isAutoStartup()` | `true` なら起動時に自動で `start()` を呼ぶ | 既定の `true` |
| `getPhase()` | 起動・終了の順番。**大きいほど後に起動し、先に止まる** | 既定の `Integer.MAX_VALUE`(最後に起動し、最初に止まる) |
| `stop(Runnable callback)` | 非同期で止めるための版。Spring が実際に呼ぶのはこちら | 既定の実装が `stop()` を呼び、終わったら `callback` を呼ぶ |

### `getPhase()` と Web サーバーの関係

Spring Boot の組み込み Web サーバーも、内部では `SmartLifecycle` で起動・停止している。Web サーバーの phase は `Integer.MAX_VALUE` より小さいので、

- 起動: **Tomcat が先**、受信ループが後
- 終了: **受信ループが先**、Tomcat のグレースフルシャットダウンが後

になる。[実測](#実測-devtools-の再起動で-stop--start-の順番を見る)のログにこの順番がそのまま出ている。

### `stop()` が長引くとき

`stop(Runnable callback)` の既定の実装は `stop()` を**呼んだスレッドでそのまま**実行する。つまり `SqsReceiveLoop.stop()` が受信スレッドの終わりを待っている間(最大 `STOP_WAIT` = 25 秒)、終了処理はそこで止まって待つ。

- Spring Boot には 1 つの phase の終了を待つ上限 `spring.lifecycle.timeout-per-shutdown-phase`(既定 30 秒)がある。このリポジトリでは設定しておらず既定のまま。`STOP_WAIT` の 25 秒はこれより短い
- ECS 側にも、SIGTERM から SIGKILL までの猶予 `stopTimeout` がある。ロングポーリングの最中に止められると最大 20 秒ほど抜けられないので、ワーカーのタスク定義では 60 秒にする(設計書 §5-3)

## `SqsReceiveLoop` の `stop()` がしていること(要点)

詳しい実装はコードのコメントにある。ここでは `SmartLifecycle` との関係だけ押さえる。

1. **`running = false`** — 受信ループの `while (running)` が次の周回で抜ける
2. **待ちの最中なら割り込む** — ロングポーリングや再試行の待ち(`RETRY_DELAY`)を早く抜けさせる。処理中(DB や SES と通信中)には割り込まない
3. **受信スレッドの終わりを待つ** — 処理中のメッセージを最後まで終えて削除してから戻る。`stop()` が戻ったら、Spring は次の Bean の停止に進んでよい

逆向きの話として、**`stop()` を呼ばれていないのに受信スレッドが終わってしまった場合**(`OutOfMemoryError` など)は、`run()` の `finally` が `running` がまだ `true` であることで気づき、`onUnexpectedExit` でアプリごと終了させる。ワーカーでは `AuthMailQueueConfig.exit()` が別スレッドで `SpringApplication.exit` を呼び、その中で Spring がまた `stop()` を呼ぶ。このとき

- 先に `running = false` にしてあるので、`isRunning()` は「止まっている」と答える
- 受信スレッドが自分自身の終わりを待たないよう、`stop()` は `Thread.currentThread() == thread` を見て戻る

という 2 つの備えがある。

## `Lifecycle` との違い

`SmartLifecycle` は `Lifecycle` を拡張したインターフェース。

| | `Lifecycle` | `SmartLifecycle` |
| --- | --- | --- |
| 起動時に `start()` が呼ばれるか | **呼ばれない**(`context.start()` を明示的に呼んだときだけ) | 呼ばれる(`isAutoStartup()` が `true` なら) |
| 終了時に `stop()` が呼ばれるか | 呼ばれる | 呼ばれる |
| 順番の指定 | できない | `getPhase()` で指定できる |
| 非同期の停止 | できない | `stop(Runnable callback)` |

**アプリの起動に合わせて自動で動かしたいなら `SmartLifecycle` を使う。** 名前の「Smart」は、自動起動と順番の制御が付いていることを指す。

### 似た仕組みとの使い分け

| 仕組み | 向いている用途 |
| --- | --- |
| `SmartLifecycle` | **動き続けるもの**(受信ループ、スケジューラ、サーバー)を起動・停止する。止めるときに「処理中のものを終えてから」が要る |
| `@PostConstruct` / `@PreDestroy` | **その Bean 1 つの初期化と後始末**。他の Bean が全部揃っている保証は無い(`@PostConstruct` の時点では、まだ作られていない Bean がある) |
| `ApplicationRunner` / `CommandLineRunner` | 起動後に**一度だけ**走らせる処理(このリポジトリの `TaskRunner` のようなもの)。止める仕組みは無い |

受信ループを `@PostConstruct` で始めると、ほかの Bean がまだ揃っていないうちにメッセージを受け取ってしまう可能性がある。`SmartLifecycle` の `start()` なら全 Bean が揃った後なので、その心配が無い。

## 実測: devtools の再起動で stop → start の順番を見る

worker コンテナは devtools で動いているので、`./gradlew classes` などでクラスが変わると、**アプリのコンテキストを閉じて作り直す**(JVM はそのまま)。コンテキストを閉じる処理は SIGTERM のときと同じなので、`stop()` と `start()` が呼ばれる順番を観察できる。

```bash
docker compose logs worker | grep -E "Tomcat started|受信を開始|Started |受信を停止|Graceful"
```

結果(2026-10-11、時刻以外の前置きは省略):

```
14:32:45.528 [Thread-13]     SqsReceiveLoop  : [auth-mail] 受信を停止しました                     ← stop()
14:32:45.532 [Thread-13]     GracefulShutdown: Commencing graceful shutdown. Waiting for active requests to complete
14:32:45.533 [tomcat-shutdown] GracefulShutdown: Graceful shutdown complete
14:32:46.504 [restartedMain] TomcatWebServer : Tomcat started on port 8080 (http) with context path '/'
14:32:46.505 [restartedMain] SqsReceiveLoop  : [auth-mail] 受信を開始しました。queueUrl=http://elasticmq:9324/000000000000/auth-mail  ← start()
14:32:46.525 [restartedMain] Application     : Started Application in 0.9 seconds
```

読み取れること:

- **終了: 受信ループの `stop()` が先、Tomcat のグレースフルシャットダウンが後。** `getPhase()` が最大なので最初に止まる
- **起動: Tomcat が先、受信ループの `start()` が後。** そして「Started Application」はさらに後。`start()` は起動処理の一部として、起動完了の宣言より前に呼ばれる
- **`stop()` は `restartedMain`(devtools が作る main 相当のスレッド)ではなく、別のスレッド `Thread-13` から呼ばれている。** `running` を `volatile` にしている理由がここで見える

**ローカルで SIGTERM の止まり方を見られない理由**: `docker compose stop worker` で SIGTERM を送っても、受け取るのは `gradlew bootRun` を動かしている Gradle で、アプリの JVM はシャットダウン処理を通らない(設計書 §5-3)。止まり方の確認は `SqsReceiveLoopTest`(`stop()` を直接呼ぶ)と stg で行う。

## つまずきポイント

- **`implements SmartLifecycle` しても `@Component` も `@Bean` も無ければ、何も呼ばれない。** エラーも出ないので気づきにくい
- **`isRunning()` を正しく返さないと、`stop()` が呼ばれない。** 常に `false` を返していると、Spring は「止まっている」と判断して終了時に `stop()` を飛ばす
- **`stop()` の中で無限に待たない。** 終了処理全体がそこで止まる。待つなら上限を付ける(`thread.join(STOP_WAIT)`)
- **`start()` の中で長い処理をしない。** `start()` が戻るまで起動が終わらない。ループは別スレッドで回し、`start()` はスレッドを立てたらすぐ戻る(`new Thread(this::run, ...).start()`)

## 用語集

| 用語 | 意味 |
| --- | --- |
| Bean | Spring が作って管理しているオブジェクト。`@Component` や `@Bean` で登録する |
| アプリケーションコンテキスト | Bean の入れ物。起動時に作られ(refresh)、終了時に閉じられる(close)。`start()` は refresh の最後、`stop()` は close の最初に呼ばれる |
| phase | `getPhase()` が返す数字。起動は小さい順、終了は大きい順 |
| シャットダウンフック | JVM が終了するとき(SIGTERM を受けたときなど)に走らせる処理。Spring Boot がコンテキストを閉じる処理を登録している |
| グレースフルシャットダウン | 新しい仕事を受け付けず、処理中のものを終えてから止まること |

## 関連

- [process-and-thread.md](../../server/process-and-thread.md) — 受信スレッドと HTTP のスレッドが同じプロセスの中で動いていること
- [application-events-vs-queues.md](./application-events-vs-queues.md) — 認証メールをキューに移した背景
- [local-observation.md](../../sqs/local-observation.md) — ローカルでキューの動きを観察した記録
- [設計書(フェーズ20)](../../../superpowers/specs/2026-10-11-phase20-sqs-auth-mail-design.md) — 決定12(止め方)、決定13(想定外の終了)、§5-3(`stopTimeout` を 60 秒にする理由)
- Spring Framework 公式「Startup and Shutdown Callbacks」 <https://docs.spring.io/spring-framework/reference/core/beans/factory-nature.html#beans-factory-lifecycle-processor>
- Spring Boot 公式「Graceful Shutdown」 <https://docs.spring.io/spring-boot/reference/web/graceful-shutdown.html>
