# Spring のアプリケーションイベントとキュー — 「依頼をどこに預けるか」で並べて理解する

`AuthService` が `eventPublisher.publishEvent(...)` を呼んでいるのを見て湧く疑問 — 「これはキューなのか?」「ECS でタスクを 2 つに増やしたら、2 つのタスクが同じイベントを拾ってメールが 2 通届かないか?」「Laravel でキューをコンテナの中に持って痛い目に遭ったが、これは同じ問題を抱えていないか?」「そもそも Laravel や Hono にも、キューとは別のイベント機能があるのか?」 — に答える学習メモ。

**「後回しにしたい処理の依頼を、どこに預けるか」**という 1 本の軸で 4 段階に並べる。Spring / Laravel / Hono の違いも、この段階表のどこに何を持つか、として位置づける。

対象ファイル: [AuthService.java](../../../../backend/src/main/java/com/example/app/auth/AuthService.java) / [AuthMailSender.java](../../../../backend/src/main/java/com/example/app/auth/AuthMailSender.java) / [AuthMailRequestedEvent.java](../../../../backend/src/main/java/com/example/app/auth/AuthMailRequestedEvent.java)

> **このメモのコード例と検証について**
> Spring 側のコードはこのリポジトリの実物。「`AFTER_COMMIT` のリスナーは同一スレッドで同期実行される」という中核の主張は、**この開発環境で実測して確認した**(→ [実測](#実測-after_commit-は同じスレッドで同期実行される)の章に手順と結果を載せた)。
> 一方 **アウトボックスの実装例、Laravel / Hono のコード例はこのリポジトリに存在せず実行検証していない参考コード**。
> 対象バージョンは Spring Boot 4.1.0 / Java 21(`build.gradle` で確認)、Laravel 13.x、Hono v4。Laravel のキュー周りはバージョンで API 名が変わっている領域なので、使う前に公式ドキュメントで裏を取ること(リンクは執筆時点のもの)。

## まず結論(3 行)

1. **Spring のアプリケーションイベントはキューではない。** 溜まらず、保存されず、リトライもされず、**既定では同期実行**。実体は「相手を名指ししないメソッド呼び出し」でしかない。
2. **ECS で複数タスクになっても二重送信は起きない。** イベントは JVM の外に一切出ないので、他のタスクは存在すら知らない。**心配すべきは逆向きで、コミット直後にプロセスが死ぬとメールは永久に失われる**(リトライする者がいない)。
3. **イベントとキューは競合する選択肢ではない。** イベントは「**誰に頼むかをコードから切り離す**」設計の道具、キューは「**依頼を預かって守る**」運用の道具。Laravel の `ShouldQueue` は、この 2 つを 1 行で繋ぐ仕掛けになっている。

## 主軸: 依頼をどこに預けるか — 4 段階

| | 段階 | 依頼の置き場所 | プロセスが死んだとき失うもの | リトライ | レスポンスを待たせるか |
|---|---|---|---|---|---|
| **①** | **ローカルイベント** | **どこにも置かない**(その場で実行) | 処理中の 1 件 | なし | **待たせる** |
| **②** | **メモリキュー**(`@Async`) | JVM のメモリ(スレッドプールの待ち行列) | 溜まっていた**全件** | なし | 待たせない |
| **③** | **DB アウトボックス** | 業務データと同じ DB | **なし** | あり | 待たせない |
| **④** | **外部キュー**(SQS 等) | 外部のブローカー | **なし** | あり | 待たせない |

**このプロジェクトは現在①にいる。** そして「①より②が進歩」ではないところが、この表のいちばん面白い点。**②は①より失うものが大きい**。①は「溜める場所を持たない」ので、失うのは常に最大 1 件だが、②はメモリに待ち行列ができるので、コンテナが消えるとそこに並んでいた全部が消える。**Laravel でコンテナ内にキューを持って痛い目に遭ったのは、まさに②の形。**

つまり**「速くしたいから `@Async` を付ける」は、耐久性の観点では後退になりうる。** 消失を本気で防ぐなら③以降に行くしかなく、③と④の分かれ目は「別プロセス・別サービスに処理を渡したいか」だけ。

各フレームワークがこの表のどこに何を用意しているかは[後半の対比表](#laravel-との対比--3-層に分かれている)にまとめた。

## 実測: `AFTER_COMMIT` は「同じスレッドで同期実行」される

このメモで一番大事な事実。**イベントにしても、リスナーが終わるまで HTTP レスポンスは返らない。**

`AuthMailSender.onAuthMailRequested` に一時的に `Thread.sleep(3000)` とスレッド名のログを仕込み、`POST /api/auth/signup` を実行して確かめた。

```
[THREADPROBE] publish 前     thread=http-nio-8080-exec-9  time=1786012868047
[THREADPROBE] listener 開始  thread=http-nio-8080-exec-9  time=1786012868056
                                    ↑ 同じスレッド        ↑ 差は 9ms(この間にコミット)

http_status=201  レスポンスまでの所要時間=3.571980秒
                                          ↑ 仕込んだ 3 秒がそのまま出た
```

読み取れることが 2 つある。

1. **`publishEvent` を呼んだスレッドと、リスナーが動くスレッドが同一**(`http-nio-8080-exec-9` は Tomcat のリクエスト処理スレッド)。別スレッドに逃がされてはいない。
2. **リスナーの所要時間がそのままレスポンス時間に乗る。** 3 秒待たせたら 3.57 秒かかった。つまり実際の運用では **SMTP との往復時間だけ、ユーザーは登録ボタンを押したまま待たされている**。

`@TransactionalEventListener(phase = AFTER_COMMIT)` が保証しているのは**順序**(コミットの後に呼ぶ)だけで、**別スレッドに逃がすことではない**。「イベント = 非同期」という直感が最も裏切られる箇所。

時系列にするとこうなる。

```
HTTP リクエストを処理しているスレッド(1 本しかない)
│
├─ signup() 開始 ── トランザクション開始
│    ├─ DELETE / INSERT / トークン発行
│    └─ publishEvent()      ← まだ何も起きない。「コミット後に呼べ」と予約するだけ
├─ signup() を抜ける ── COMMIT ✓
├─ onAuthMailRequested()    ← ここで初めて実行。同じスレッド
│    └─ mailSender.send()   ← SMTP と通信。2 秒かかれば 2 秒待つ
└─ HTTP レスポンスを返す     ← SMTP が終わるまで返らない
```

「**DB トランザクションは短く保てるが、レスポンスは速くならない**」— この 2 つは別の話なので分けて理解する。設計の[決定 7](../../../superpowers/specs/2026-08-05-phase3-auth-design.md) が言っているのは前者だけ。

公式: <https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html>

## ① ローカルイベント — どこにも預けない(このプロジェクトの現在地)

```java
// AuthService.issueVerificationMail
String rawToken = authTokenService.issue(user, AuthTokenPurpose.EMAIL_VERIFICATION,
        AuthTokenService.EMAIL_VERIFICATION_TTL);
eventPublisher.publishEvent(
        new AuthMailRequestedEvent(user.getEmail(), AuthTokenPurpose.EMAIL_VERIFICATION, rawToken));
```

`publishEvent` の中で起きているのは、これだけ。

```
publishEvent(event)
   │
   ├─ Spring が起動時に作った「リスナー一覧」から、引数の型が一致するものを探す
   │
   └─ 見つかったリスナーを、その場で、同じスレッドで、順番に呼ぶ
```

これは **Observer パターン**(監視者パターン)という古典的な設計手法の実装で、目的は「A が B を直接知らなくても済むようにする」ことだけ。**処理を後回しにすることは目的に入っていない。**

比喩にすると差がはっきりする。

- **ローカルイベント** — オフィスで隣の席の人に付箋を渡し、その人が処理し終わるのを立って待っている。相手が誰かは知らなくてよいが、待つ
- **キュー** — 郵便ポストに投函する。郵便局が中身を保管し、配達に失敗しても再試行し、それでも駄目なら不在票(DLQ)を残す。投函した人は即座に立ち去れる

共通しているのは「相手を名指ししない」の一点だけ。

### それでも設計としては正しく効いている

耐久性は無いが、**責務の分離としては十分に仕事をしている**。`AuthService` は SMTP・件名・本文・リンクの組み立てを一切知らない。`AuthMailSender` はユーザー登録の事情を知らない。メール本文を HTML 化しても `AuthService` は無変更で済む。

なお `AuthMailRequestedEvent` が `rawToken`(生のトークン)を運んでいるのは必然。DB には SHA-256 ハッシュしか無く、リスナーが後から DB を引いて生の値を取り出すことは原理的にできない(→ [AuthTokenService.java](../../../../backend/src/main/java/com/example/app/auth/AuthTokenService.java))。

### 失敗をわざと握りつぶしている

```java
try {
    mailSender.send(message);
} catch (MailException e) {
    log.error("認証メールの送信に失敗しました。宛先={} 用途={}", event.toEmail(), event.purpose(), e);
}
```

コミット後に例外を投げると、**DB 上は登録が成立しているのに利用者には 500 が返る**という最も分かりにくい状態になる。記録を残し、[確認メールの再送 API](../../../api/README.md) に誘導するほうが復帰しやすい。**「①を選ぶ = 人間による再送を復旧手段として引き受ける」**ということ。

## ② メモリキュー(`@Async`) — JVM のメモリに預ける

「レスポンスが SMTP を待つのは嫌だ」と思ったとき、まず手が伸びるのがこれ。

```java
@Async  // ← これを足す(別途 @EnableAsync が必要)
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
void onAuthMailRequested(AuthMailRequestedEvent event) { ... }
```

`@Async` はスレッドプールに処理を投げる。**スレッドプールは内部にメモリ上の待ち行列を持っている** — つまり「コンテナの中に状態を持つキュー」そのもの。レスポンスは即座に返るようになるが、代償が 2 つある。

- **タスクが消えると待ち行列ごと消える。** これが Laravel で `redis` などをコンテナ内に置いて踏んだ問題と同じ形
- **例外が呼び出し元に伝わらない。** 別スレッドで投げられた例外は誰も受け取らないので、ログを出さないと完全に消える

**このプロジェクトは `@Async` も `@EnableAsync` も使っていない**(`grep` で確認済み)。結果として、レスポンスは遅いが、失う可能性のあるメールは常に最大 1 件に抑えられている。

> **①と②のどちらがマシかは要件次第。** メール 1 通なら①(遅いが被害は最小)、大量に捌くなら②では足りず③へ行くべき。「②は①と③の中間」ではなく、**②は①より耐久性が低い**ことを押さえておく。

## ③ DB アウトボックス — 業務データと同じ DB に預ける

**「消失させない」を満たす最小の構成。** 名前は難しいが、やることは「送信依頼をメモリではなく DB に置く」だけ。

```
1 つのトランザクションの中で:
  ├─ users に INSERT
  └─ outbox_messages に「このメールを送れ」を INSERT   ← ここが肝
       DB トランザクションなので、両方成功か両方失敗のどちらかにしかならない

別のスケジューラが:
  └─ outbox_messages の未送信行を読んで送信 → 成功したら送信済みにする
       途中で落ちても行は残っているので、次回そのまま拾い直される
```

テーブルはこの程度で足りる。

| カラム | 用途 |
|---|---|
| `id` | 主キー |
| `payload` | 宛先・用途・生トークンを JSON などで持つ |
| `status` | `PENDING` / `SENT` / `FAILED` |
| `attempts` | 試行回数。上限を超えたら `FAILED` にして通報 |
| `created_at` / `sent_at` | 記録と滞留時間の監視 |

```java
// ※ このリポジトリには存在しない参考コード(未検証)
@Scheduled(fixedDelay = 30_000)
void flushOutbox() {
    for (OutboxMessage m : outboxRepository.findTop100ByStatusOrderById(Status.PENDING)) {
        try {
            mailSender.send(toMessage(m));
            m.markSent(LocalDateTime.now());   // JPA のダーティチェックで UPDATE される
        } catch (MailException e) {
            m.markFailed();                    // attempts を増やす。次回また拾われる
            log.error("outbox の送信に失敗 id={}", m.getId(), e);
        }
    }
}
```

ECS で複数タスクが動く場合は、同じ行を 2 つのタスクが同時に拾わないよう `SELECT ... FOR UPDATE SKIP LOCKED` などで排他する必要がある。**①では考える必要すら無かった「二重処理」が、③から現実の問題として登場する。**

### 代わりに生まれる問題: 今度は「2 通届く」ようになる

`send()` は成功したが `markSent()` の直前にプロセスが落ちると、その行は `PENDING` のまま残り、次回もう一度送られる。**③以降は「失われない」代わりに「重複しうる」**。これは分散システムの一般的な性質で、避けられないトレードオフ。

だから受け取る側に**冪等性**(同じ処理を何度実行しても結果が変わらない性質)が要る。メール送信は「2 通届く」を完全には防げないが、この設計ではトークンが使い捨てなので**実害は「同じリンクのメールが 2 通来る」だけ**で済む。

## ④ 外部キュー(SQS 等) — 外に預ける

③との違いは**「処理を別プロセス・別サービスに渡せるか」**の一点。DLQ・可視性タイムアウト・キュー深さのメトリクスといった運用機能が付いてくるのも大きい。

**注意すべきは、④にしても③が不要にはならないこと。**

```
users に INSERT → COMMIT ✓
   │
   │ ← ここで落ちると、SQS への送信が行われない = 消失
   ↓
sqsTemplate.send(...)
```

「DB とキューという 2 つのものを、1 つのトランザクションで同時に更新することはできない」という根本問題は④でも残る。だから実務では **③(アウトボックス)を土台にして、そこから④へ流す**構成になる。「SQS を使えば安全」ではない。

**Laravel の `database` ドライバは、実はこの③にかなり近い性質を持っていた**(ジョブが DB にあるので、コンテナが死んでも残る)。`redis` をコンテナ内に置いていた場合が②に相当する。SQS に移した判断は正しいが、**危険度は使っていたドライバによって違った**、というのがこの表から見える整理。

## ECS で複数タスクになったらどうなるか

### 二重送信は起きない — イベントは JVM の外に出られない

```
        ALB
      ┌──┴──┐          リクエストは必ずどちらか 1 つに振られる
      ▼     ▼
  ┌────────┐  ┌────────┐
  │ Task A │  │ Task B │
  │ JVM    │  │ JVM    │
  │ ┌────┐ │  │ ┌────┐ │
  │ │掲示板│ │  │ │掲示板│ │  ← 掲示板は JVM ごとに別物。互いに見えない
  │ └────┘ │  │ └────┘ │
  └────────┘  └────────┘
```

`/api/auth/signup` を Task A が処理したなら、イベントは Task A のメモリ内の掲示板に貼られ、Task A の `AuthMailSender` だけが反応する。Task B はそのイベントが発生したことを知る手段を持たない。**メールは 1 通だけ送られる。**

二重実行が問題になるのは、SQS のように**複数のワーカーが同じキューを覗く**構成のとき(SQS 標準キューは at-least-once 配信なので重複して受信しうる)。①には構造上その心配がない。

### 起きるのは消失のほう

```
COMMIT ✓(DB にユーザーが作られた)
  │
  │ ← この瞬間にタスクが停止(デプロイ・スケールイン・OOM・SIGKILL)
  ↓
onAuthMailRequested() が呼ばれない → メールは永久に送られない
```

イベントはどこにも保存されていないので、次に起動したタスクは「送るべきメールがあった」ことを知る手段がない。**このプロジェクトはこれを受容し、[確認メールの再送](../../../api/README.md)を復旧手段として用意している。**

なお `cloudformation/` はまだ空で ECS のタスク数は定義されていない。ここは「将来複数タスクにしたら」という仮定の話。

## Laravel との対比 — 3 層に分かれている

Laravel は **イベント / キュー行き / ドライバ** の 3 層がきれいに分かれていて、今回の段階表を理解する教材として非常に良い。

### 第 1 層: イベントとリスナー(Spring と同じ。既定で同期)

```php
// 発火側。Spring の publishEvent に相当
event(new UserRegistered($user));

// 受け取り側。この状態では同期実行
class SendVerificationMail
{
    public function handle(UserRegistered $event) { /* ... */ }
}
```

公式ドキュメントが「Queueing listeners can be beneficial if your listener is going to perform a **slow task such as sending an email**」と書いていることが、**キューにしなければメール送信で待たされる**= 既定は同期であることの裏返しになっている。

### 第 2 層: `ShouldQueue` — インターフェース 1 つでキュー行きになる

```php
use Illuminate\Contracts\Queue\ShouldQueue;

class SendVerificationMail implements ShouldQueue   // ← これだけ
{
    public function handle(UserRegistered $event) { /* ... */ }
}
```

**ここが Laravel の白眉。** 段階表の①と②〜④の境界が、**インターフェースの有無という型で表現されている**。`ShouldQueue` を外せば①に戻り、付ければキューに乗る。Spring にはこの橋渡しが用意されていないので、アウトボックスなり SQS なりを自分で組む必要がある。

### 第 3 層: ドライバ — どこに預けるかはここで決まる

`ShouldQueue` を付けても、**実際にどこへ預けられるかは接続設定次第**。段階表と対応させるとこうなる。

| Laravel のドライバ | 段階表 | 挙動 |
|---|---|---|
| `sync` | ①相当 | キューを経由せずその場で即実行(開発・テスト用) |
| `deferred` | ①と②の間 | HTTP レスポンスを返した後に同一プロセスで処理する(Laravel 13 の新顔) |
| `redis` | ② | Redis に預ける。**コンテナ内に置けば②そのもの** |
| `database` | ③に近い | DB のテーブルに預ける。コンテナが死んでも行は残る |
| `sqs` | ④ | Amazon SQS に預ける |

**`sync` ドライバの挙動が、Spring のローカルイベントとほぼ同じ**と考えると腑に落ちる。「Spring にキューが無い」のではなく、「Spring が標準で用意しているのは `sync` 相当だけ」という捉え方が近い。

### コミット後に投げる仕掛けも両方にある

Spring の `AFTER_COMMIT` に相当するものが Laravel にも 2 種類ある。

| | 何に付けるか | 意味 |
|---|---|---|
| `ShouldDispatchAfterCommit` | **イベント**クラス | トランザクションがコミットされるまでイベントを発火しない |
| `ShouldQueueAfterCommit` | **リスナー**クラス | コミットされるまでキューに投入しない |
| `after_commit` 設定 | 接続設定 | 上記をその接続の既定にする |

公式は `after_commit` について「Laravel will wait until the open parent database transactions have been committed before actually dispatching the job」「If a transaction is rolled back, dispatched jobs are discarded」と説明している。**ロールバックしたら破棄される**という点まで Spring の `AFTER_COMMIT` と同じ思想。

> **バージョン注意**: 以前は `public $afterCommit = true;` というプロパティで指定していたが、Laravel 13 のドキュメントでは `ShouldQueueAfterCommit` インターフェースの形で説明されている。古い記事を参照するときは要注意。

公式: <https://laravel.com/docs/13.x/events> / <https://laravel.com/docs/13.x/queues>

## Hono — コアには無い

Hono はルーティングとミドルウェアだけの薄いフレームワークで、**DI コンテナもイベントバスもキューも持たない**。代替手段は動かす環境によって変わる。

| 手段 | 段階表 | 備考 |
|---|---|---|
| Node.js 標準の `EventEmitter` | ① | 言語が持っている in-process イベントバス |
| `@hono/event-emitter` | ① | Hono の middleware パッケージ。コアには merge されていない |
| `c.executionCtx.waitUntil(promise)` | ①と②の間 | Cloudflare Workers。レスポンス返却後も処理を継続できるが永続性は無い |
| Cloudflare Queues | ④ | プラットフォーム側の永続キュー |

**一般化するとこうなる。**

| フレームワークの性格 | in-process イベント |
|---|---|
| フルスタック(Spring / Laravel / Rails / NestJS) | 標準機能として持っている |
| 薄いルーター(Hono / Express / Fastify) | 持たない。言語標準やライブラリで補う |

DI コンテナを持つフレームワークは「部品同士の結合を緩める」ことに関心があるので、Observer パターンの実装をほぼ必ず備えている。薄いフレームワークはそこに関心が無いので持たない、という分かれ方。

参考: <https://hono.dev/docs/middleware/third-party> / <https://www.npmjs.com/package/@hono/event-emitter>

## つまずきポイント

- **「イベントだから非同期」ではない。** Spring も Laravel も既定は同期。実測のとおり、リスナーが 3 秒かかればレスポンスも 3 秒遅れる。イベントにしても速くはならない。
- **`AFTER_COMMIT` が保証するのは順序だけ。** 「コミットの後に呼ぶ」であって「別スレッドで呼ぶ」ではない。速くしたいなら `@Async` が別途必要。
- **`@Async` を「単なる高速化」と思わない。** メモリ上の待ち行列ができるので、耐久性は**下がる**。段階表の②が①より弱いのはこのため。
- **`AFTER_COMMIT` のリスナー内で DB を書いても保存されない。** トランザクションはもう終わっているのでダーティチェックが働かない。書くなら `@Transactional(propagation = Propagation.REQUIRES_NEW)` で新しいトランザクションを開く。
- **トランザクションが無いとリスナーは呼ばれない。** 公式に「If no transaction is running, the listener is not invoked at all」とある。`@Transactional` の付いていないメソッドから publish すると**何も起きない**(テストで引っかかりやすい)。必要なら `fallbackExecution = true` で上書きできる。
- **リスナーの参照が 0 件でも未使用ではない。** `@TransactionalEventListener` の付いたメソッドはコード上のどこからも呼ばれていない。探すときは**引数のイベント型で grep する**。
- **ローカルイベントを分散システムの通信手段にしない。** JVM をまたげないので、処理を別サービスに切り出した瞬間に届かなくなる。
- **「SQS を使えば消えない」ではない。** DB とキューを 1 つのトランザクションで更新できない以上、④でもアウトボックスが要る。
- **③以降は「消えない」代わりに「重複しうる」。** 失う心配と重複の心配はトレードオフの関係にあり、両方をゼロにはできない。

## 用語集

- **アプリケーションイベント** — 同一プロセス内で「〜が起きた」を公表する仕組み。実体は相手を名指ししないメソッド呼び出し
- **Observer パターン** — 「通知する側」と「受け取る側」を疎結合にする設計手法。イベント機能はこれの実装
- **`ApplicationEventPublisher`** — Spring が提供するイベント公表用の部品。掲示板に相当する。DI で受け取って使う
- **`@TransactionalEventListener`** — イベントを受け取るメソッドに付ける Spring のアノテーション。トランザクションの状態を見て呼ぶタイミングを決められる
- **`TransactionPhase`** — 呼ぶタイミングの指定。`BEFORE_COMMIT` / `AFTER_COMMIT`(既定) / `AFTER_ROLLBACK` / `AFTER_COMPLETION` の 4 つ
- **`fallbackExecution`** — トランザクションが無いときでもリスナーを呼ぶかの設定。既定は `false`(呼ばれない)
- **`@Async` / `@EnableAsync`** — Spring でメソッドを別スレッドで実行させる指定。内部にメモリ上の待ち行列を持つスレッドプールを使う
- **キュー(メッセージキュー)** — 処理依頼を外部に預けて保管・再試行してもらう仕組み。SQS / RabbitMQ / Laravel queue など
- **トランザクショナル・アウトボックス** — 送信依頼を業務データと同じトランザクションで DB に書き、別プロセスが読んで送るパターン。消失を防ぐ最小構成
- **DLQ(デッドレターキュー)** — 何度も失敗したメッセージを退避させる先。キューの機能で、ローカルイベントには相当物が無い
- **at-least-once 配信** — 「少なくとも 1 回は届く」保証。重複しうるので受け取る側に冪等性が要る
- **冪等性(べきとうせい)** — 同じ処理を何度実行しても結果が変わらない性質。キューやアウトボックスを使うときの必須設計
- **`SKIP LOCKED`** — ロックされている行を待たずに飛ばして取得する SQL の句。複数ワーカーで同じ行を掴まないために使う
- **`ShouldQueue`** — Laravel でリスナーをキュー経由にするインターフェース。付け外しで①とキュー方式を切り替えられる
- **`ShouldDispatchAfterCommit` / `ShouldQueueAfterCommit`** — Laravel でコミット後まで発火/投入を遅らせるインターフェース。Spring の `AFTER_COMMIT` に相当
- **`sync` ドライバ** — Laravel でキューを経由せずその場で実行する設定。Spring のローカルイベントに最も近い挙動
- **`deferred` ドライバ** — Laravel 13 の、HTTP レスポンス返却後に同一プロセスで処理するドライバ。Cloudflare の `waitUntil` と発想が近い
- **`waitUntil()`** — Cloudflare Workers の API。レスポンス返却後も非同期処理を継続させる。永続性は無い
- **`SimpleMailMessage` / `JavaMailSender`** — Spring のプレーンテキストメールの入れ物と SMTP 送信インターフェース
- **Mailpit** — 開発用のメール受信サーバー。外部に送らず `http://localhost:8025` で中身を確認できる

## 関連

- `AuthMailSender` の本文組み立て・リンク生成の詳細 → [AuthMailSender.java](../../../../backend/src/main/java/com/example/app/auth/AuthMailSender.java) のコメント
- なぜ生のトークンをイベントで運ぶ必要があるのか(DB にはハッシュしか無い) → [AuthTokenService.java](../../../../backend/src/main/java/com/example/app/auth/AuthTokenService.java) のコメント
- メール送信をコミット後にするという設計判断(決定 7) → [2026-08-05-phase3-auth-design.md](../../../superpowers/specs/2026-08-05-phase3-auth-design.md)
- `@Transactional` とダーティチェック(なぜ `save()` を呼ばなくても UPDATE されるか) → [AuthTokenService.java](../../../../backend/src/main/java/com/example/app/auth/AuthTokenService.java) の `invalidateUnused`
- Spring と Laravel の設計思想の違い(別の比較軸) → [repository-and-entity-vs-laravel-model.md](./repository-and-entity-vs-laravel-model.md) / [exception-handling-vs-other-frameworks.md](./exception-handling-vs-other-frameworks.md)
