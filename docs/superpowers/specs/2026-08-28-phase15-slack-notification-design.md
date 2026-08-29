# フェーズ15: アラートの通知先を Slack に移す(Amazon Q Developer in chat applications)

日付: 2026-08-28
方針 → [ADR-0011](../../adr/0011-slack-notification-with-chatbot.md)
前提 → [フェーズ14 の設計書](./2026-08-28-phase14-monitoring-design.md) / [ADR-0010](../../adr/0010-monitoring-in-ephemeral-stack.md)
手順 → [docs/slack/README.md](../../slack/README.md)

---

## 1. スコープ

**やること**

- SNS のメール購読 2 本と `AlertEmail` パラメータを削除する
- `AWS::Chatbot::SlackChannelConfiguration` 2 本 + Chatbot 用 IAM ロール 1 本を追加する
- Slack の 2 種類の ID を `params/<env>.json` から受け取る
- ワークフローから `ALERT_EMAIL` の受け渡しとガードを削除し、締めのサマリを Slack 向けに書き換える
- Slack 側の手動作業の手順書を新設する

**やらないこと**

- **アラームの構成そのものの変更。** アラーム 7 本・メトリクスフィルタ 2 本・RDS イベント購読・SNS トピック 2 本は**一切触らない**。今回は宛先の付け替えだけ
- **トピックの再編(チャンネルを緊急度で分ける)**。軸を変えるのは検知層の設計変更であって、宛先の付け替えではない → ADR-0011
- **E(アプリのエラーログ通知)**。配線方式を変えても実現しないことを確認した(決定7)
- **ログアーカイブ(F)まわり**。今回の変更と無関係
- **実機確認**。このリポジトリはまだ AWS 上で一度も建てていない

---

## 2. 決定一覧

### 決定1: 配線は Chatbot に任せ、webhook も Lambda も使わない

**SNS の HTTPS 購読で Slack の webhook URL を直接叩く構成は成立しない。** SNS は購読作成時に `SubscriptionConfirmation` を POST して `SubscribeURL` を踏ませるが、Slack の webhook は確認応答を返さないので購読が永久に `PendingConfirmation` になる。仮に確認できても、SNS の JSON エンベロープは Slack が期待する形式ではない。

したがって webhook を使うなら整形役が必須になる。**その整形役を AWS 側に持たせる**という判断。理由と落とした案(Lambda インライン / EventBridge API Destination)→ [ADR-0011](../../adr/0011-slack-notification-with-chatbot.md)。

**Slack に入るのはカスタムアプリではなく、App Directory の公式「Amazon Q Developer」アプリ 1 つ。** 無料プランのアプリ枠 10 個のうち 1 つを使う。

### 決定2: SNS トピック 2 本と分割の軸はそのまま

`ecs-task-shortage`(D)と `rds-alerts`(A・B・C)。**Chatbot ではチャンネルの分割単位 = SNS トピックの分割単位**で、Chatbot 側にフィルタ機能は無い。

チャンネルは `#njp-alerts-ecs` / `#njp-alerts-rds` の 2 つ。**`njp` 接頭辞**は、ワークスペースが個人用の汎用ワークスペースで、後から別プロジェクトの通知を足しても衝突しないようにするため。**public チャンネル**にしているのは、private だと「アプリを招待し忘れて無音」の経路が増えるだけで実利が無いから。

### 決定3: stg と prod は同じチャンネルを共用する

テンプレートは環境ごとにチャンネル ID を受け取る形にし、`stg.json` と `prod.json` に同じ値を書く。通知にはアラーム名(`nuxt-java-practice-stg-rds-cpu-high`)が入るので環境は判別できる。**分けたくなったら `params` の値を差し替えるだけ**で、テンプレートは変えなくてよい。

### 決定4: チャンネル設定と IAM ロールはスタック内

**常駐にはできない。** トピック名が固定なので撤収後も ARN は変わらず、設定を手動で作って使い回せそうに見えるが、**Chatbot の設定は対象トピックに自分自身を購読させる形で動く**ため、撤収でトピックが消えると購読も失われる。次に建てても Chatbot 側から繋ぎ直すまで無音になり、ADR-0010 の帰結 1 が形を変えて再発する。

**Chatbot の API エンドポイントは us-east-2 の 1 本しかない**が、ap-northeast-1 のスタックから作っても内部的にそちらを呼ぶので、リージョンを合わせる必要はない。東京は対応リージョンに含まれる。

### 決定5: Slack の 2 種類の ID は `params` に平文で置く

**秘密ではないため。** ワークスペース ID もチャンネル ID も、それを知っているだけでは何もできない(ワークスペースを認可済みの AWS アカウントからしか使えない)。「知っていれば誰でも投稿できる」webhook URL とはここが違う。`HostedZoneId` と同じ「秘密ではないがアカウント固有」の値として扱う。

落とした案:

- **GitHub の Environment secret**(`AlertEmail` と同じ経路)— 秘密でないもののためにガードと積み込みの仕組みを 3 値ぶん維持することになる。環境ごとに値を変えたいときに Environment を見に行く必要も出る
- **常駐 SSM の平文 String を `{{resolve:ssm:...}}` で読む** — 手動管理の SSM が 4 つ → 7 つに増える。ADR-0010 が一貫して避けてきた「常駐リソースを増やさない」方針と衝突する

**`AllowedPattern` を付ける。** `params` のプレースホルダ(`REPLACE_WITH_...`)のままだと Change Set の作成で落ちる。付けないと Chatbot リソースの作成まで進んでから同じ理由で失敗するので、**手前で止める**。かつて `AlertEmail` に `Default` を置かなかったのと同じ判断。

### 決定6: `GuardrailPolicies` は `AWSDenyAll`

**省略すると `AdministratorAccess` が既定で適用される。** これは明確な地雷なので必ず明示する。

通知を受け取るだけなら権限は一切要らないので全拒否にし、「通知専用である」ことをテンプレート上で明示する。Slack から AWS を操作したくなったら意図的にここを緩める。

`IamRoleArn` に渡すロールは別物(Chatbot 自身が引き受ける)で、AWS の `AWS-Chatbot-NotificationsOnly-Policy` と同じ `cloudwatch:Describe*` / `Get*` / `List*` だけを持たせる。アラーム通知にグラフを添えるために要る。信頼するのは `chatbot.amazonaws.com`。

### 決定7: E(アプリのエラーログ通知)は Chatbot でも実現しない

**CloudWatch Logs のサブスクリプションフィルタは SNS に送れない。** 送信先に指定できるのは Kinesis Data Streams / Firehose / Lambda / OpenSearch の 4 つだけで、Chatbot は SNS からしか受け取らない。**この 2 つは直接繋がらない。**

Chatbot の「カスタム通知」(所定の JSON を SNS に publish するとカード表示される)を使う手もあるが、**publish する主体が要る**ので結局 Lambda になる。

したがって **ADR-0010 の「E は移植しない」という結論は、配線方式を変えても変わらない。**

**あわせて ADR-0010 の記述を 1 つ訂正した。** 「CloudFormation で Lambda を持つとコード zip の置き場が常駐 S3 として増える」は事実として誤りで、`Code.ZipFile` にインラインで書ける(Python / Node.js、4096 文字以内)。**結論は変わらないが、理由が間違っていた。**

### 決定8: メールは完全に置き換える(併用しない)

併用すると **ADR-0010 の帰結 1(建てるたびに購読確認を 2 通踏む)がそのまま残る**ので、移す理由の半分を捨てることになる。SES の `EmailIdentity` はアプリのメール送信用なので無関係に残る。

### 決定9: IAM の手動作業は発生しない

リソースを実際に作るのは `--role-arn` で渡す CloudFormation サービスロールで、こちらは `AdministratorAccess`(→ [手順書 §2-1](../../infrastructure/cloudformation-operations.md))。Actions が引き受けるロールは `cloudformation:*` と `iam:PassRole` と S3 しか持たないという設計がそのまま効いていて、**`chatbot:*` を足す必要はない。**

フェーズ14 では `EmptyBuckets` に ARN を足す手動作業が発生したが、今回は**ゼロ**。

---

## 3. 変更したファイル

| ファイル | 変更 |
|---|---|
| `cloudformation/app.yml` | `AlertEmail` を削除し Slack の 3 パラメータを追加。購読 2 本を削除し Chatbot 設定 2 本 + IAM ロール 1 本を追加。出力 2 つ追加 |
| `cloudformation/params/{stg,prod}.json` | Slack の 3 値(プレースホルダ)を追加 |
| `.github/workflows/cfn-apply.yml` | `ALERT_EMAIL` の env / ガード / `--parameters` への積み込みを削除 |
| `.github/workflows/cfn-deploy.yml` | 締めのサマリの購読確認リマインダを Slack 向けに書き換え |
| `docs/slack/README.md` | **新設。** Slack 側の手動作業の手順書 |
| `docs/adr/0011-slack-notification-with-chatbot.md` | **新設。** 方針 |
| `docs/adr/0010-monitoring-in-ephemeral-stack.md` | 帰結 1 を解消済みに更新。Lambda の記述を訂正 |
| `docs/infrastructure/cloudformation-operations.md` | secret 一覧・§6・§11-1・トラブルシュート表 |
| `cloudformation/README.md` | パラメータの渡し元の表 |

`app.yml` は 82,361 → **86,936 バイト**。パラメータ 41 → 43、リソース 83 → 84、出力 16 → 18。

---

## 4. 着手前に必要な手動作業

**Slack 側の 2 つだけ。どちらも 1 回きりで、スタックを作り直しても消えない。**

1. **AWS コンソールで Slack ワークスペースを認可する**(CloudFormation では不可)。ここでワークスペース ID が得られる
2. **チャンネルを 2 つ作り、それぞれで `/invite @Amazon Q`**。ここでチャンネル ID が得られる

得た 3 つの ID を `params/{stg,prod}.json` のプレースホルダと置き換える。手順 → [docs/slack/README.md](../../slack/README.md)。

**GitHub の Environment secret は 1 つ減る**(`ALERT_EMAIL` が不要になる)。IAM の変更は無い(決定9)。

---

## 5. 実機で確かめること(未検証)

このリポジトリはまだ AWS 上で一度も建てていない。以下は設計時の想定で、**実測で覆る可能性がある。**

- **`/invite @Amazon Q` を忘れた状態で、スタックの作成が成功するかどうか。** 成功する(= 投稿の段で初めて失敗する)と見込んでいる。もし作成時に検証されるなら、それは想定より親切な挙動
- **Chatbot が RDS イベント購読のメッセージをどう表示するか。** CloudWatch アラームは専用のカード表示になるが、RDS イベントは対応サービスの一覧に含まれるものの表示形式は未確認。素の JSON が出る可能性がある
- **初回構築時の「OK になりました」通知 7 通が、Slack でどれくらいうるさいか。** メールより軽いという前提で据え置いたが、実際に見て判断する
- **`LoggingLevel: ERROR` で `/aws/chatbot/...` にどれだけログが出るか。** CloudWatch Logs は課金対象なので、無ければ `NONE` に落とす
- **ConfigurationName の一意性の範囲。** アカウント内で一意と読んだが、リージョンをまたぐかどうかは未確認(stg / prod を同時に建てないので実害は無い見込み)
