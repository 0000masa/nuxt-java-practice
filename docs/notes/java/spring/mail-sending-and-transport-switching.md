# メール送信はどう切り替わっているのか — 起動時に `MailSender` が 1 個決まるまで

`AuthMailSender` の依存が `JavaMailSender` から `MailSender` に変わったのを見て湧く疑問 — 「`MailSender` を使うと、環境変数によって SMTP か SES の API かが分岐するということ?」「メールの設定は `MailSenderConfig` と `SesMailSender` の 2 ファイルに書かれているということ?」「メール送信に関わるファイルはそれぞれ何をしていて、どこから呼ばれているのか?」「本番とローカルはどこで切り替わっているのか?」 — に答える学習メモ。

**「アプリの起動が終わるまでに、`MailSender` 型の Bean が 1 個に決まるまでの道のり」**という 1 本の軸で全体を貫く。ファイルの役割も、この道のりのどこに立っているか、として位置づける。

対象ファイル: [AuthMailSender.java](../../../../backend/src/main/java/com/example/app/auth/AuthMailSender.java) / [MailSenderConfig.java](../../../../backend/src/main/java/com/example/app/config/MailSenderConfig.java) / [SesMailSender.java](../../../../backend/src/main/java/com/example/app/config/SesMailSender.java) / [AppProperties.java](../../../../backend/src/main/java/com/example/app/config/AppProperties.java) / [application.yml](../../../../backend/src/main/resources/application.yml) / [build.gradle](../../../../backend/build.gradle) / [docker-compose.yml](../../../../docker-compose.yml) / [cloudformation/app.yml](../../../../cloudformation/app.yml)

> **このメモの検証状況**
> 中核の主張(**どちらの経路でも `MailSender` の候補は常に 1 個**)は、**この開発環境で実測して確認した**(→ [実測](#実測-候補は常に-1-個になる)の章に手順と出力を載せた)。`MAIL_TRANSPORT` の綴り間違いが起動時に落ちること、メールのヘルス指標を有効にするとテストが落ちることも実測済み。
> Spring Boot 側の仕組みは**このプロジェクトが実際に使っている jar のソース**(`spring-boot-mail-4.1.0-sources.jar`)を読んで確認した。対象バージョンは Spring Boot 4.1.0 / Java 21 / AWS SDK for Java 2.54.0(`build.gradle` で確認)。**Boot 4 でメールの自動設定は `spring-boot-autoconfigure` から `spring-boot-mail` モジュールへ移っており**、パッケージも `org.springframework.boot.mail.autoconfigure` に変わっている。3.x の記事とはクラスの置き場所が違う。
> 一方 **SES へ実際にメールが届くかは未検証**。AWS のスタックをまだ構築していないため、確認できているのは「SES 経路の Bean が正しく選ばれ、`SesV2Client` が作れる」ところまで。
> Laravel / Node の対比コードは**このリポジトリに存在せず実行検証していない参考コード**。
> **イベントを使って送信をコミット後にずらしている話**はこのメモの担当ではない → [application-events-vs-queues.md](./application-events-vs-queues.md)。あちらが「依頼をどこに預けるか」、こちらが「預かった依頼を誰が送るか」。

## まず結論(3 行)

1. **切り替えているのは `MailSender` ではない。** 型を `MailSender` にしたのは「差し替えられる余地を作った」だけ。実際に選んでいるのは [MailSenderConfig](../../../../backend/src/main/java/com/example/app/config/MailSenderConfig.java) の `@ConditionalOnProperty`。判定は**起動時の 1 回だけ**で、リクエストごとの分岐ではない。
2. **設定は「2 ファイル」ではなく、役割が 3 つに分かれている。** 値(`application.yml` と環境変数)・**選ぶ側**(`MailSenderConfig`)・**実装**(`SesMailSender`)。非対称なのは、**SMTP 側の実装は自分で書いていない**(Boot の自動設定が `JavaMailSenderImpl` を作る)点。だから「SES だけファイルが 2 つある」ように見える。
3. **候補は常に 1 個しか存在しない。** `MailSender` の Bean を自分で登録すると、Boot の自動設定が `@ConditionalOnMissingBean(MailSender.class)` で丸ごと降りる。だから `@Primary` で優先順位を付ける必要はない(**実測して確認し、実際に外した**)。

## 主軸: 起動時に `MailSender` が 1 個決まるまで

4 つの段階を順に通る。**どの段階も起動時に 1 回だけ**通る。

| | 段階 | 誰が動くか | ここで決まること |
|---|---|---|---|
| **①** | 値が決まる | Spring の `Environment` | `app.mail.transport` の値が `smtp` か `ses` か |
| **②** | 自前の `@Configuration` を読む | `MailSenderConfig` の `@ConditionalOnProperty` | **SES 用の Bean 定義を登録するか、飛ばすか** |
| **③** | 自動設定を読む | Boot の `MailSenderAutoConfiguration` | **②で `MailSender` が登録済みなら、自動設定は降りる** |
| **④** | 注入する | DI コンテナ | `AuthMailSender` のコンストラクタに 1 個だけある候補が入る |

### ① 値が決まる

`MAIL_TRANSPORT` という環境変数が `app.mail.transport` というプロパティになるまでの経路。

```
.env(開発)                    ECS タスク定義(本番)
MAIL_TRANSPORT=smtp            MAIL_TRANSPORT=ses
        │                              │
        └──────────┬───────────────────┘
                   ↓
     application.yml:  app.mail.transport: ${MAIL_TRANSPORT:smtp}
                   ↓                        ↑ 環境変数が無ければ smtp
     Environment に "app.mail.transport" = "smtp" | "ses" として載る
```

開発側の値は [.env.example](../../../../.env.example)、本番側の値は [cloudformation/app.yml](../../../../cloudformation/app.yml) の `MAIL_TRANSPORT` で、**アプリのコードにはどちらも書かれていない**。

このプロパティは 2 人の読者に読まれる。役割が違うので、両方あることに意味がある。

| 読む人 | 読み方 | 目的 |
|---|---|---|
| `MailSenderConfig` の `@ConditionalOnProperty` | **文字列として**`"ses"` と比較 | **どの Bean を作るかを決める** |
| `AppProperties.Mail.transport`(enum) | enum に束縛 | **綴りの検証**(→ [後述](#綴り間違いという穴--enum-で受け取る理由)) |

つまり `AppProperties.Mail.transport` は**どのコードも読んでいない**。それでも型に載せているのは、綴り間違いを起動時に落とすため。

### ② 自前の `@Configuration` を読む

`MailSenderConfig` には Bean が 2 つあり、どちらにも同じ条件が付いている。

```java
@Bean
@ConditionalOnProperty(name = "app.mail.transport", havingValue = "ses")
SesV2Client sesV2Client() { ... }          // SES への接続クライアント(AWS SDK)

@Bean
@ConditionalOnProperty(name = "app.mail.transport", havingValue = "ses")
MailSender sesMailSender(SesV2Client sesV2Client) { ... }   // 送信の実装
```

`ses` でなければ**この 2 つの `@Bean` メソッドは呼ばれもしない**。「作ってから捨てる」のではなく、**そもそも Bean 定義として登録されない**。開発環境に AWS の資格情報が無くても起動できるのはこのため(`SesV2Client.create()` はリージョンが解決できないと失敗する)。

### ③ 自動設定を読む — ここが一番の勘所

SMTP 側は自分で 1 行も書いていない。作っているのは Boot で、その入口が `MailSenderAutoConfiguration`(jar のソースから抜粋)。

```java
@AutoConfiguration
@ConditionalOnClass({ MimeMessage.class, MimeType.class, MailSender.class })
@ConditionalOnMissingBean(MailSender.class)          // ★ ここ
@Conditional(MailSenderCondition.class)              // spring.mail.host か jndi-name があること
@Import({ MailSenderJndiConfiguration.class, MailSenderPropertiesConfiguration.class })
public final class MailSenderAutoConfiguration { }
```

`@ConditionalOnMissingBean(MailSender.class)` は「**`MailSender` 型の Bean が他に無ければ動く**」という意味。そして **自動設定はユーザー定義の `@Configuration` より後に処理される**(`@AutoConfiguration` は遅延インポートとして最後に回される)ので、②で `sesMailSender` を登録していれば、この条件は成立せず**自動設定ごと降りる**。

結果、2 経路はこうなる。

```
app.mail.transport = smtp(開発)              app.mail.transport = ses(本番)
──────────────────────────────────           ──────────────────────────────────
②  @ConditionalOnProperty 不成立              ②  成立
    → SES の Bean 定義は登録されない               → sesMailSender を登録
                                                     (SesV2Client も登録)
③  MailSender が無い                          ③  MailSender が既にある
    → 自動設定が動く                               → @ConditionalOnMissingBean 不成立
    → JavaMailSenderImpl("mailSender")             → 自動設定は降りる
       を spring.mail.* から組み立てる               → JavaMailSenderImpl は作られない
──────────────────────────────────           ──────────────────────────────────
④  候補 1 個 → 注入                          ④  候補 1 個 → 注入
```

**`application.yml` の `spring.mail.host: ${SMTP_HOST:localhost}` という既定値は、本番でも残っている。** それでも SMTP 側の Bean は作られない。既定値が効くのは「自動設定が動くかどうか」の条件(`MailSenderCondition`)で、その手前の `@ConditionalOnMissingBean` で降りているため。

### ④ 注入する

`AuthMailSender` は `MailSender` インターフェースだけを知っている。

```java
AuthMailSender(MailSender mailSender, AppProperties appProperties) { ... }
```

**候補が常に 1 個なので `@Qualifier` も `@Primary` も要らない。** ここが今回の変更の要点で、`AuthMailSender` は経路を知らないまま両方で動く。

### 実測: 候補は常に 1 個になる

`ApplicationContext` に何が入っているかを直接数えた。一時的なテストを 1 本置いて実行し、確認後に削除した(再現手順は[末尾](#再現手順--自分で数えてみる)に置いた)。

```
# app.mail.transport=ses(本番の経路)
[PROBE] MailSender beans     = sesMailSender
[PROBE] JavaMailSender beans =                       ← 空。SMTP 側は作られていない
[PROBE] getBean(MailSender)  = com.example.app.config.SesMailSender

# 既定(app.mail.transport=smtp、開発の経路)
[PROBE] MailSender beans     = mailSender
[PROBE] JavaMailSender beans = mailSender            ← 同じ 1 個。JavaMailSender でもある
[PROBE] getBean(MailSender)  = org.springframework.mail.javamail.JavaMailSenderImpl
```

**この実測でコードを 1 か所直した。** `MailSenderConfig` の `sesMailSender` には当初 `@Primary` が付いており、コメントには「本番でも SMTP 側の Bean が作られて候補が 2 つになるので必要」と書いてあった。実際には③のバックオフで候補は 1 個で、**`@Primary` を外しても起動する**(上と同じ出力になることを確認)。付けたままでも動くが、コメントの説明が事実と食い違うので、`@Primary` を削除して「なぜ不要なのか」を書き直した。

## 登場するファイルの役割

メールに関わるファイルは 10 個ある。**「値」「選ぶ」「実装」「使う」「土台」**の 5 つに分かれる。

| ファイル | 分類 | 役割 |
|---|---|---|
| [.env.example](../../../../.env.example) / `.env` | 値 | 開発の `MAIL_TRANSPORT=smtp` / `SMTP_HOST=mailpit` / `MAIL_FROM` |
| [cloudformation/app.yml](../../../../cloudformation/app.yml) | 値 | 本番の `MAIL_TRANSPORT=ses` / `MAIL_FROM`、タスクロールの `ses:SendEmail`、SES ドメインアイデンティティと DKIM |
| [application.yml](../../../../backend/src/main/resources/application.yml) | 値 | `spring.mail.*`(SMTP の宛先)と `app.mail.*`、`management.health.mail.enabled: false` |
| [AppProperties.java](../../../../backend/src/main/java/com/example/app/config/AppProperties.java) | 値 | `app.*` を型付きで受け取る。`Mail(from, transport)`。`transport` は enum で綴りを検証する |
| [MailSenderConfig.java](../../../../backend/src/main/java/com/example/app/config/MailSenderConfig.java) | **選ぶ** | `@ConditionalOnProperty` で SES 経路の Bean を作るか決める。**送信処理は書いていない** |
| [SesMailSender.java](../../../../backend/src/main/java/com/example/app/config/SesMailSender.java) | **実装** | SES の API で送る `MailSender` 実装。**選ぶ判断はしていない** |
| Boot の `JavaMailSenderImpl` | 実装 | SMTP で送る。**自分では書いていない**(自動設定が組み立てる) |
| [AuthMailSender.java](../../../../backend/src/main/java/com/example/app/auth/AuthMailSender.java) | **使う** | 件名・本文・リンクを組み立てて `MailSender.send()` を呼ぶ。経路を知らない |
| [AuthService.java](../../../../backend/src/main/java/com/example/app/auth/AuthService.java) / [AuthMailRequestedEvent.java](../../../../backend/src/main/java/com/example/app/auth/AuthMailRequestedEvent.java) | 使う | 「メールを送る必要が生じた」ことをイベントで知らせる(送信そのものはしない) |
| [build.gradle](../../../../backend/build.gradle) / [docker-compose.yml](../../../../docker-compose.yml) | 土台 | `spring-boot-starter-mail` と AWS SDK の `sesv2`、開発の受け皿 Mailpit |

### 呼び出しの流れ

**起動時**(誰を作るか決まる)と**実行時**(1 通が飛ぶ)を分けて見ると、混ざらない。

```
[起動時 — 1 回だけ]
  MAIL_TRANSPORT → application.yml → Environment
      ├→ MailSenderConfig の @ConditionalOnProperty → SesMailSender を作る / 作らない
      ├→ Boot の自動設定 → JavaMailSenderImpl を作る / 降りる
      └→ AppProperties(enum) → 綴りが違えば起動失敗

[実行時 — メール 1 通ごと]
  POST /api/auth/signup
      → AuthController → AuthService(トランザクション内)
          ├ トークン発行(DB にはハッシュだけ保存)
          └ publishEvent(AuthMailRequestedEvent)      ← まだ送らない
      → COMMIT
      → AuthMailSender.onAuthMailRequested()          ← @TransactionalEventListener(AFTER_COMMIT)
          ├ 件名・本文・リンク(フロントの URL)を組み立て
          └ mailSender.send(SimpleMailMessage)
                ├─ 開発: JavaMailSenderImpl → SMTP → Mailpit(http://localhost:8025 で読む)
                └─ 本番: SesMailSender → SesV2Client.sendEmail() → SES → 実際の受信箱
```

実行時の前半(イベントにしている理由、`AFTER_COMMIT` が同期実行であることの実測)は [application-events-vs-queues.md](./application-events-vs-queues.md) の担当。

## なぜ `JavaMailSender` から `MailSender` に変えたのか

**`JavaMailSender` のままでは差し替えられない。** 型の階層はこうなっている。

```
MailSender                        … send(SimpleMailMessage) だけを要求する(2 メソッド)
   ├── JavaMailSender             … + MimeMessage の生成・送信を 6 メソッド追加(合計 8)
   │      └── JavaMailSenderImpl  … Boot の自動設定が作る SMTP 実装
   └── SesMailSender              … 自作。SES の API で送る
```

`SesMailSender` が `MailSender` だけを実装しているのは、このアプリが送るのが**プレーンテキストのメール 2 種類だけ**で、`MimeMessage`(添付・HTML・複数パートを扱う JavaMail の型)を組み立てる機能が要らないため。もし `JavaMailSender` を実装するなら、使わない `createMimeMessage()` を 2 つと `send(MimeMessage...)` を「呼ばれたら例外」で埋める羽目になる(この 3 つは `default` 実装を持たない抽象メソッドなので、省略できない)。

だから依存の型を**必要な機能の分だけに狭めた**。これは「利用側は使う機能だけを要求する」という一般的な指針(インターフェース分離)の実例で、狭めた結果として**実装を差し替える余地が生まれた**。逆順に読むと分かりやすい: 差し替えたいから狭めたのではなく、**狭めれば差し替えられる**。

添付や HTML が必要になったら、`SendEmailRequest` の `content.raw` に MIME を渡す形へ広げる(`SesMailSender` のコメントに記載)。

## なぜ本番で SMTP を使わないのか

SES はどちらの経路でも使える。**SMTP を選ばなかったのは、資格情報の置き場が増えるから。**

| 経路 | 認証に必要なもの | CloudFormation で作れるか |
|---|---|---|
| SES の SMTP | **SMTP ユーザー名 / パスワード**。パスワードは IAM ユーザーのシークレットキーから HMAC で導出する値 | **作れない**。IAM ユーザーの長期クレデンシャルを手動で常駐させることになる |
| SES の API(採用) | タスクロールの `ses:SendEmail` | 作れる。**長期クレデンシャルが 0 個** |

このプロジェクトは「使い終わったらスタックを削除して撤収する」運用なので、**手動で常駐させるリソースを増やす選択は特に高くつく**(常駐しているのは Route53 ホストゾーンと ECR だけにしたい)。代償として `SesMailSender` を自分で書くことになったが、書いたのは 80 行で、`MailSender` インターフェースのおかげで呼び出し側は無修正で済んだ。

設計の記録 → [2026-08-19-phase13-cloudformation-design.md](../../../superpowers/specs/2026-08-19-phase13-cloudformation-design.md) の決定4。

**コードが正しくても届かない条件**が本番側にはいくつかある(SES のサンドボックス、送信元ドメインの検証、DKIM の CNAME が伝播するまでの数分〜十数分)。特に `AWS::SES::EmailIdentity` は**検証完了を待たずに `CREATE_COMPLETE` になる**ので、「スタックは成功したのにメールだけ来ない」は正常に起こりうる。詳細は同じ設計書の決定5と [cloudformation/README.md](../../../../cloudformation/README.md)。

## 条件付き Bean の読み方

今回の切り替えは、Spring Boot の条件アノテーション 2 つの組み合わせでできている。**この 2 つは向きが逆**。

| アノテーション | 見るもの | 成立する条件 |
|---|---|---|
| `@ConditionalOnProperty` | **設定値** | 指定したプロパティが期待どおりの値であること |
| `@ConditionalOnMissingBean` | **他の Bean** | その型の Bean が**まだ無い**こと |

前者は「利用者が選んだ経路」を表し、後者は「自動設定は自前の定義に譲る」という Boot 全体の作法を表す。**「自分で Bean を定義すれば自動設定が降りる」は Boot が公式に推している仕組み**で、`DataSource` や `ObjectMapper` でも同じ形が使われている。だから③はこのプロジェクト固有の裏技ではなく、依拠していい定石。

### `@ConditionalOnProperty` の 2 つの顔

`havingValue` を書くかどうかで意味が変わる。

```java
@ConditionalOnProperty(name = "app.mail.transport", havingValue = "ses")   // "ses" と一致するときだけ
@ConditionalOnProperty(name = "app.task")                                  // 値が "false" 以外なら成立
```

後者は直感に反する。**「プロパティが存在すれば成立」ではなく「値が `false` でなければ成立」** なので、**空文字でも成立する**。このプロジェクトはこの罠を実際に踏んでいて、[application.yml](../../../../backend/src/main/resources/application.yml) に `app.task` を**書かない**理由としてコメントが残っている(`task: ${APP_TASK:}` と書くと空文字が入り、通常起動でも `TaskRunner` が動いて即終了する)。`matchIfMissing = true` を付ければ「未設定でも成立」に変えられる。

## 綴り間違いという穴 — enum で受け取る理由

切り替えが `@ConditionalOnProperty(havingValue = "ses")` の**文字列一致**である以上、`MAIL_TRANSPORT=sess` と打ち間違えると条件は不成立になる。そして**そのまま起動が成功して SMTP 側が選ばれる**。本番でこれを踏むと、`spring.mail.host` の既定値 `localhost` に接続しようとして失敗し、**ログにだけエラーが残る**(送信失敗は握りつぶす設計のため → 次章)。利用者からは「登録はできたのにメールが来ない」としか見えず、原因に到達しにくい。

対策として `AppProperties.Mail.transport` の型を `String` から enum にした。

```java
public record Mail(String from, Transport transport) {
    public enum Transport { SMTP, SES }
}
```

**実測**(`ApplicationContextRunner` で `app.mail.transport` を変えて束縛させた):

```
[PROBE] ses  -> failure=false value=SES     ← 小文字で書いても束縛される(緩やかな束縛)
[PROBE] smtp -> failure=false value=SMTP
[PROBE] sess -> failure=true
[PROBE] sess root cause = IllegalArgumentException: No enum constant com.example.app.config.AppProperties.Mail.Transport.sess
```

ここで押さえておく点が 2 つある。

- **enum は検証専用で、切り替えには関与していない。** 切り替えるのは `@ConditionalOnProperty` の文字列比較のまま。両者は同じプロパティを別の読み方で読んでいる
- **enum の定数を増やしても切り替えは増えない。** 経路を足すときは `MailSenderConfig` にも `@Bean` を足す必要がある(2 か所を同期させる責任が生まれる)

## メールのヘルス指標を切っている理由

[application.yml](../../../../backend/src/main/resources/application.yml) にこの 2 行がある。

```yaml
management:
  health:
    mail:
      enabled: false
```

**メール関係の Bean がいると、actuator が勝手に SMTP へ接続を試みに行く。** 担当は `MailHealthContributorAutoConfiguration`(jar のソースで確認)。

```java
@ConditionalOnBean(JavaMailSenderImpl.class)
@ConditionalOnEnabledHealthIndicator("mail")        // ← management.health.mail.enabled がこれ
public final class MailHealthContributorAutoConfiguration
        extends CompositeHealthContributorConfiguration<MailHealthIndicator, JavaMailSenderImpl> {

    @Bean
    HealthContributor mailHealthContributor(ConfigurableListableBeanFactory beanFactory) {
        return createContributor(beanFactory, JavaMailSenderImpl.class);   // ★ 具象型で探す
    }
}
```

切っている理由は 2 つある。

1. **本番では意味のある確認にならない。** 有効だと集約の `/api/actuator/health` を叩くたびに SMTP 接続を試すが、本番の送信経路は SES の API。そもそも `JavaMailSenderImpl` が存在しないので指標も作られない
2. **テストが落ちる(実測)。** `enabled: true` に戻して全テストを流すと `AuthFlowTest` の 3 本が失敗する

```
Error creating bean with name 'mailHealthContributor' defined in class path resource
  [org/springframework/boot/mail/autoconfigure/MailHealthContributorAutoConfiguration.class]:
  ... threw exception with message: 'beans' must not be empty
```

原因は上のコードの `createContributor(beanFactory, JavaMailSenderImpl.class)` が**具象型で Bean を探す**こと。`AuthFlowTest` は `@MockitoBean JavaMailSender mailSender` で SMTP の Bean をモックに差し替えており、**モックは `JavaMailSender`(インターフェース)であって `JavaMailSenderImpl` ではない**。条件判定は通るのに、実際の探索が 0 件になって落ちる。

### テストは smtp 側の経路しか見ていない

ここまでの流れの帰結として、**`AuthFlowTest` が確かめているのは開発の経路だけ**。`app.mail.transport` を指定していないので既定の `smtp` で起動し、そこにできる `JavaMailSender` の Bean をモックに差し替えている。`AuthMailSender` の型が `MailSender` に変わってもこのテストがそのまま通るのは、`JavaMailSender` が `MailSender` を継承しており、モックも `MailSender` として注入できるため。

**逆に言えば、`SesMailSender` を通る経路は自動テストで守られていない。** SES への実送信を確かめるには AWS 側の準備(検証済みドメイン、資格情報)が必要なので、ここは実機確認の担当になる。

## 送信に失敗したとき

送信失敗はどちらの経路でも**同じ型**で上がってきて、**同じ場所で握りつぶされる**。

```
経路ごとの失敗                          共通の型                  受け止める場所
──────────────────────────             ──────────────           ──────────────────────
SMTP: 接続不能・タイムアウト     ┐
      (Mailpit が落ちている等)   ├→  MailException  →  AuthMailSender の catch
SES:  未検証の送信元・送信上限     │      ↑                        └→ log.error して終わり
      権限不足・サンドボックス     ┘      │
                                        └ SesMailSender が SDK 固有の例外を
                                          MailSendException に載せ替えている
```

`SesMailSender` が SDK の `RuntimeException` を `MailSendException`(Spring の `MailException` の子)に載せ替えているのは、**呼び出し側の `catch` を経路によらず 1 つに保つため**。載せ替えなければ `AuthMailSender` は AWS SDK の型を知る必要があり、「経路を知らない」という前提が崩れる。

**握りつぶしているのは意図的。** コミット後に例外を投げると、DB 上は登録が成立しているのに利用者には 500 が返る、という最も分かりにくい状態になる。記録を残して確認メールの再送に誘導するほうが復帰しやすい(設計の決定7)。**その代償として、送信失敗は利用者にも監視にも自動では伝わらない**(ログを見るまで気づけない)。

### 届かないときの確認場所

| 症状 | 開発 | 本番 |
|---|---|---|
| メールが届かない | `http://localhost:8025`(Mailpit の受信箱)を見る | CloudWatch Logs のアプリのログストリームで `認証メールの送信に失敗しました` を探す |
| 送信は成功しているのにリンクが違う | `APP_BASE_URL` を確認(本文のリンクは**フロントの URL**) | 同じ。ECS タスク定義の `APP_BASE_URL` |
| ログにエラーが無いのに届かない | Mailpit のコンテナが動いているか | SES 側の条件を疑う(サンドボックス、送信元の検証、DKIM の伝播待ち) |
| 経路そのものを疑うとき | 起動ログの Bean を数える(→ [再現手順](#再現手順--自分で数えてみる)) | 同じ手順を本番の設定値で流す |

## 他のフレームワークではどう切り替えるか

同じ「開発と本番で送信経路を変える」を、他ではどう表現しているか。**参考コードで、このリポジトリでは検証していない。**

| | Spring Boot(このプロジェクト) | Laravel | Node(Nodemailer) |
|---|---|---|---|
| 誰が選ぶか | **DI コンテナ**(条件付き Bean) | フレームワークのファクトリ(`MailManager`) | **自分のコード** |
| いつ選ぶか | **起動時に 1 回** | 初回利用時(遅延解決) | 自分が書いたとき |
| 設定値の役割 | **Bean を作るかどうかを決める** | ドライバ名を選ぶ | 分岐の材料 |
| SES 実装の出どころ | **自分で書く**(`MailSender` 実装 80 行) | フレームワーク同梱(`ses` ドライバ) | ライブラリ同梱(SES トランスポート) |
| 綴り間違いの検出 | enum の束縛で**起動時に落とせる** | 未定義の mailer 名は利用時に例外 | 自分で書かないと検出されない |

```php
// Laravel — config/mail.php に候補を並べ、MAIL_MAILER で名前を選ぶ
'default' => env('MAIL_MAILER', 'smtp'),
'mailers' => [
    'smtp' => ['transport' => 'smtp', 'host' => env('MAIL_HOST')],
    'ses'  => ['transport' => 'ses'],
],
// 利用側は経路を知らない。Mail::to($user)->send(new VerifyEmail($token));
```

```js
// Nodemailer — 「1 個決める」を自分の関数で書く。DI コンテナが無いので、
// この transporter を必要な場所へ自分で配る(モジュールスコープに置く / 引数で渡す)
const transporter = process.env.MAIL_TRANSPORT === 'ses'
  ? nodemailer.createTransport({ SES: { ses: new SESClient({}), aws } })
  : nodemailer.createTransport({ host: process.env.SMTP_HOST, port: 1025 });
```

**構図は [session-store-and-other-frameworks.md](./session-store-and-other-frameworks.md) や [application-events-vs-queues.md](./application-events-vs-queues.md) と同じ。** フルスタックフレームワーク(Spring / Laravel)は「差し替え可能な層」を標準で持ち、薄い土台(Node)は持たないので自分で書く。違いは、Laravel が**名前でドライバを引く**のに対し、Spring は**型で Bean を引く**こと。そのため Spring では「候補が 2 個になると起動に失敗する」という失敗の形があり、逆に「候補が 1 個であること」を起動時に保証できる。

## この型は他でも使える

**「開発はローカルの偽物、本番は AWS の本物」は、このプロジェクトでもう一度出てくる。** 画像保存(フェーズ6)の MinIO と S3 がまったく同じ形。

| | メール(フェーズ13) | 画像(フェーズ6 予定) |
|---|---|---|
| 開発 | Mailpit(SMTP) | MinIO(S3 互換 API) |
| 本番 | SES の API | S3 |
| 共通の型 | `MailSender` | AWS SDK の `S3Client` |

ただし**画像のほうは切り替えが要らない可能性が高い**。MinIO は S3 互換の API を話すので、`S3Client` のエンドポイント設定を変えるだけで済み、実装を 2 つ持つ必要がない。**「共通のインターフェースがすでに 1 つに揃っているなら、条件付き Bean は不要」** ということで、メールで条件付き Bean が必要になったのは SMTP と SES の API が**別のプロトコル**だから。この見極めが先で、機械的に同じ型を持ち込む話ではない。

## 再現手順 — 自分で数えてみる

一時的なテストを 1 本置いて、コンテキストの中身を数える。確認したら消す。

```java
// backend/src/test/java/com/example/app/config/MailSenderSelectionProbeTest.java(一時)
@SpringBootTest(properties = "app.mail.transport=ses")   // 外すと開発の経路になる
class MailSenderSelectionProbeTest {

    static {
        System.setProperty("aws.region", "ap-northeast-1");   // SesV2Client.create() がリージョンを要求する
    }

    @Autowired
    ApplicationContext ctx;

    @Test
    void probe() {
        System.out.println("[PROBE] MailSender beans     = "
                + String.join(", ", ctx.getBeanNamesForType(MailSender.class)));
        System.out.println("[PROBE] JavaMailSender beans = "
                + String.join(", ", ctx.getBeanNamesForType(JavaMailSender.class)));
        System.out.println("[PROBE] getBean(MailSender)  = " + ctx.getBean(MailSender.class).getClass().getName());
    }
}
```

```bash
docker compose exec backend sh ./gradlew test --tests '*MailSenderSelectionProbeTest*' --rerun-tasks
# 標準出力は build/test-results/test/TEST-*.xml に入る(コンソールには出ない)
docker compose exec backend sh -c \
  'grep -o "\[PROBE\][^<]*" build/test-results/test/TEST-com.example.app.config.MailSenderSelectionProbeTest.xml'
```

`AWS_REGION` を渡さずに `ses` で起動すると、`SesV2Client.create()` がリージョンを解決できず起動が失敗する。**開発環境で `MAIL_TRANSPORT=ses` を試すときはリージョンと資格情報の両方が必要**(→ [.env.example](../../../../.env.example) のコメント)。

自動設定がなぜ降りたのかを Boot 自身に説明させることもできる。

```yaml
# application.yml に一時的に足す(確認したら戻す)
logging:
  level:
    org.springframework.boot.autoconfigure.condition: DEBUG
```

## 用語

- **`MailSender`** — Spring のメール送信インターフェース。`send(SimpleMailMessage)` と可変長版の 2 メソッドだけを持つ(`javap` で確認)
- **`JavaMailSender`** — `MailSender` を継承し、`MimeMessage`(添付・HTML)の生成と送信を追加したインターフェース
- **`JavaMailSenderImpl`** — Boot の自動設定が `spring.mail.*` から組み立てる SMTP 実装
- **`SimpleMailMessage`** — 差出人・宛先・件名・本文だけを持つプレーンテキストメールの入れ物
- **`MailException` / `MailSendException`** — Spring のメール送信例外。実行時例外なので `throws` 宣言は要らない
- **`@ConditionalOnProperty`** — 設定値が期待どおりのときだけ Bean を登録する条件。`havingValue` を省くと「値が `false` 以外なら成立」
- **`@ConditionalOnMissingBean`** — その型の Bean が他に無いときだけ登録する条件。自動設定が自前の定義に譲るための仕組み
- **`@AutoConfiguration`** — Boot の自動設定クラスに付く指定。ユーザー定義の `@Configuration` より**後**に処理される
- **バックオフ** — 自動設定が「自前の Bean があるので何もしない」と降りること
- **`@Primary`** — 同じ型の候補が複数あるときに優先されるものを指定する。候補が 1 個なら不要
- **緩やかな束縛(relaxed binding)** — `MAIL_TRANSPORT` / `mail-transport` / `mailTransport` を同じプロパティとして扱う Boot の仕組み。enum は大文字小文字を問わない
- **`SesV2Client`** — AWS SDK for Java v2 の SES クライアント。リージョンと資格情報は SDK の既定の解決順序で決まる
- **SES のサンドボックス** — 検証済みアドレス宛てにしか送れない初期状態。解除には AWS への申請が必要
- **DKIM** — 送信ドメインの正当性を示す署名。SES では CNAME レコード 3 本を立てて検証する
- **Mailpit** — 開発用のメール受信サーバー。外部に送らず `http://localhost:8025` で中身を確認できる
- **`@MockitoBean`** — コンテナ内の Bean をモックに差し替えるテスト用のアノテーション。差し替えはアプリ全体に効く

## 関連

- 送信をコミット後にずらしている理由、`AFTER_COMMIT` が同期実行であることの実測 → [application-events-vs-queues.md](./application-events-vs-queues.md)
- SES を API 経路にした設計判断(決定4)と、SES をスタックに含める代償(決定5) → [2026-08-19-phase13-cloudformation-design.md](../../../superpowers/specs/2026-08-19-phase13-cloudformation-design.md)
- 本番の環境変数・IAM 権限・SES アイデンティティの実物 → [cloudformation/app.yml](../../../../cloudformation/app.yml) / [cloudformation/README.md](../../../../cloudformation/README.md)
- テストの種類(`@SpringBootTest` / `@WebMvcTest` / `@DataJpaTest`)と `app_test` の仕組み → [testing-and-test-database.md](./testing-and-test-database.md)
- 環境変数がコンテナに入るまでの経路 → [env-vars-basics.md](../../env-vars-basics.md)
- 同じ「フルスタック FW は層を持つ / 薄い FW は持たない」構図の別テーマ → [session-store-and-other-frameworks.md](./session-store-and-other-frameworks.md) / [exception-handling-vs-other-frameworks.md](./exception-handling-vs-other-frameworks.md)
