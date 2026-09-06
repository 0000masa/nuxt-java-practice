# Slack にアラートを流し、デプロイを承認する(Amazon Q Developer in chat applications)

> 方針 → [ADR-0011](../adr/0011-slack-notification-with-chatbot.md)(アラート)/ [ADR-0013](../adr/0013-app-deploy-with-code-services.md)(デプロイ承認)
> 検知層そのものの設計 → [ADR-0010](../adr/0010-monitoring-in-ephemeral-stack.md) / [フェーズ14 の設計書](../superpowers/specs/2026-08-28-phase14-monitoring-design.md)

CloudFormation スタックが作る CloudWatch アラームと RDS イベント、そしてデプロイパイプラインの通知を Slack に流すための手順書。**AWS 側はスタックが作るので、ここに書くのは Slack 側の手動作業と、そこで得た ID を `params` に書き写すところまで。**

チャンネルは 3 つある。**うち 1 つ(`#njp-deploy`)だけは、Slack から AWS を操作できる。**

## 0. 全体像

```
CloudWatch アラーム 7 本 ─┐
RDS イベント購読 ─────────┤
                          ↓
              SNS トピック 2 本(スタック内)
                          ↓
   Amazon Q Developer in chat applications(旧 AWS Chatbot)
                          ↓
              Slack チャンネル 2 つ
```

| 経路 | Slack チャンネル | 流れてくるもの | Slack からの操作 |
|---|---|---|---|
| SNS `...-ecs-task-shortage` | `#njp-alerts-ecs` | ECS のタスク数不足(D) | できない(`AWSDenyAll`) |
| SNS `...-rds-alerts` | `#njp-alerts-rds` | RDS のメトリクス(A)・ログ由来(B)・イベント購読(C) | できない(`AWSDenyAll`) |
| CodeStar Notifications | `#njp-deploy` | デプロイの承認待ち・成否 | **承認できる** |

**`#njp-deploy` だけ SNS を挟まない。** CodeStar Notifications は Chatbot のチャンネル設定を直接ターゲットにできる(`TargetType: AWSChatbotSlack`)。アラート系が SNS を挟んでいるのは、CloudWatch アラームが SNS にしか送れないからで、こちらにはその制約が無い。

**`#njp-deploy` の設定だけ別スタックにある。** アラート用 2 本は `app.yml`(作り捨て)、承認用は `pipeline.yml`(常駐)。パイプラインと同じライフサイクルだから。

**チャンネルの分割単位は SNS トピックの分割単位。** Chatbot 側にフィルタ機能は無く、「どのアラームがどのチャンネルに出るか」はテンプレートで各アラームの `AlarmActions` にどちらのトピックを指定しているかで決まる。チャンネルを増やしたければ、まずトピックを増やすことになる。

**stg と prod は同じチャンネルを使う。** 通知にはアラーム名(`nuxt-java-practice-stg-rds-cpu-high` の形)が入るので環境は判別できる。分けたくなったら `params/prod.json` のチャンネル ID を差し替えるだけでよい。

## 1. なぜ Incoming Webhook を使わないのか

Slack の通知というと「カスタムアプリを作って Incoming Webhook の URL を発行する」形が一般的だが、**この構成では使わない。**

**SNS の HTTPS 購読で webhook URL を直接叩くことはできない。** SNS は購読を作るとき `SubscriptionConfirmation` を POST して `SubscribeURL` を踏ませるが、Slack の webhook は確認応答を返さないので購読が永久に `PendingConfirmation` のままになる。仮に確認できたとしても、SNS が送る JSON のエンベロープは Slack が期待するペイロード形式ではないので `invalid_payload` で弾かれる。

したがって webhook を使う構成では、**SNS と Slack の間に整形役(Lambda など)を挟むことが必須**になる。Chatbot はその整形役を AWS 側が持っているので、**コードを 1 行も書かずに済む。**これが webhook ではなく Chatbot を選んだ理由(詳細と落とした案 → [ADR-0011](../adr/0011-slack-notification-with-chatbot.md))。

その結果、**Slack に入れるのは自分で作るカスタムアプリではなく、App Directory にある公式の「Amazon Q Developer」アプリ 1 つ**になる。無料プランのアプリ枠 10 個のうち 1 つを使う。

## 2. Slack にチャンネルを 3 つ作る

ワークスペース「自分用」に **public チャンネル**を 3 つ作る。

- `njp-alerts-ecs`
- `njp-alerts-rds`
- `njp-deploy` — デプロイの承認と通知(フェーズ16 で追加)

**private でも動くが public にしている。** 1 人のワークスペースで private にする実利が無く、private にすると「アプリを招待し忘れて無音」という経路が 1 つ増えるため。

## 3. AWS にワークスペースを認可する(1 回きり・常駐)

**この作業は AWS コンソールでしか行えない。CloudFormation では自動化できない。** スタックを作り直しても認可は消えないので、必要なのは最初の 1 回だけ。ホストゾーンや ECR と同じ「手動管理の常駐リソース」に相当する。

1. Slack の左メニューから **自動化(Automations)** → **アプリを追加(Browse Apps Directory)** を開く
   - 左メニューに見当たらなければ **その他(More)** の中にある
2. `Amazon Q Developer` を探して **追加(Add)** する
3. AWS コンソールで <https://console.aws.amazon.com/chatbot/> を開く
4. **チャットクライアントを設定(Configure a chat client)** で **Slack** を選び、**設定(Configure)**
5. Slack の認可画面に飛ぶので、右上のドロップダウンから **「自分用」ワークスペース**を選び、**許可する(Allow)**
6. 戻ってきた **ワークスペースの詳細(Workspace details)** ページに出ている **ワークスペース ID** を控える
   - `T` で始まる英数字大文字の文字列(例: `T0123ABCDEF`)

> ワークスペース管理者がアプリの承認制を有効にしている場合は、承認が必要になる。自分のワークスペースなら自分で承認できる。

**ここから先、コンソールでチャンネルの設定(Configure new channel)は行わない。** それはスタックが `AWS::Chatbot::SlackChannelConfiguration` で作る。ここで手動で作ってしまうと、同じ `ConfigurationName` をスタックが作れずに `CREATE_FAILED` になる。

## 4. 各チャンネルにアプリを招待する

**3 つのチャンネルそれぞれで**アプリを追加する。メッセージ入力欄に `/invite` と打つと候補が出るので、**「エージェントとアプリをこのチャンネルに追加する」**を選び、一覧から **Amazon Q Developer** を選ぶ。

`/invite @Amazon Q` とテキストで打ち切る形は勧めない。アプリ名に空白が入るうえ、メンションが候補から確定されていないと**ただの人の招待コマンドとして解釈されて弾かれる**。上の UI から選ぶほうが確実。

**これを忘れるとスタックは成功するのに通知だけ届かない。** チャンネル ID は実在するのでリソースの作成は通り、投稿の段になって初めて失敗する。

## 5. チャンネル ID を 3 つ控える

Slack の左ペインでチャンネル名を右クリック → **リンクをコピー**。URL の末尾がチャンネル ID。

```
https://自分用.slack.com/archives/C0123ABCDEF
                                  ~~~~~~~~~~~ これ
```

`C` で始まる英数字の文字列。**チャンネル名(`njp-alerts-ecs`)ではなく ID を使う。**

## 6. `params` に書き写す

`cloudformation/params/stg.json` と `prod.json`(アラート用)。

```json
{ "ParameterKey": "SlackWorkspaceId",  "ParameterValue": "T0123ABCDEF" },
{ "ParameterKey": "SlackChannelIdEcs", "ParameterValue": "C0123ABCDEF" },
{ "ParameterKey": "SlackChannelIdRds", "ParameterValue": "C0456GHIJKL" },
```

`cloudformation/params/pipeline-stg.json` と `pipeline-prod.json`(承認用)。

```json
{ "ParameterKey": "SlackWorkspaceId",     "ParameterValue": "T0123ABCDEF" },
{ "ParameterKey": "SlackChannelIdDeploy", "ParameterValue": "C0789MNOPQR" },
```

**これらは秘密ではないので `params` に平文で置く。** ID を知っていても、ワークスペースを認可済みの AWS アカウントからでなければ使えない。「知っていれば誰でも投稿できる」webhook URL とはここが違う。`HostedZoneId` と同じ扱いで、GitHub の Environment secret にはしない(→ [ADR-0011](../adr/0011-slack-notification-with-chatbot.md))。

置き換え忘れると **Change Set の作成が `Parameter 'SlackWorkspaceId' must match pattern` で落ちる**(テンプレート側に `AllowedPattern` を付けてあるため)。プレースホルダのまま構築が成功して無音になるより、止まるほうがマシという判断。

## 6-2. デプロイ承認だけは権限を持つ

アラート用 2 本の `GuardrailPolicies` は **`AWSDenyAll`** のままにしてある。通知の一方向だけなら権限はゼロでよい、という [ADR-0011](../adr/0011-slack-notification-with-chatbot.md) の判断は変えない。

一方 `#njp-deploy` は承認のために **`codepipeline:PutApprovalResult`** が要る。そこであちらを緩めるのではなく、**承認専用の 3 本目**を `pipeline.yml` に建てて、権限をそこだけに閉じ込めている。

- チャンネルロールと `GuardrailPolicies` は **AND** で効く。どちらにも同じ権限が要る
- **`GuardrailPolicies` を省略すると `AdministratorAccess` が既定で適用される。** 必ず明示する
- 許可しているのは `PutApprovalResult` / `GetPipelineState` / `GetPipelineExecution` の 3 つで、対象もこのパイプラインに限定してある

承認の操作は、通知に付くボタンか `@Amazon Q` へのコマンドで行う。**どちらの見え方になるかは実機で確認する**(→ [フェーズ16 の設計書 §6](../superpowers/specs/2026-09-05-phase16-codepipeline-design.md))。

## 7. 反映して確かめる

`params` を commit・push したうえで、Actions から **「CloudFormation スタックを反映(更新のみ)」**(`cfn-apply`)を実行する。まだ環境を建てていなければ、通常どおり `cfn-deploy` で建てる(→ [cloudformation-operations.md §8](../infrastructure/cloudformation-operations.md))。

確認は AWS コンソールから送れる。

1. <https://console.aws.amazon.com/chatbot/> → 設定済みのチャンネルを選ぶ
2. **テストメッセージを送信(Send test message)**
3. Slack にカードが届けば配線は通っている

**建てた直後は「OK になりました」通知が 7 通届く。異常ではない。** 新規作成されたアラームは `INSUFFICIENT_DATA` から始まり、正常と判定されると `OK` に遷移するので、異常が一度も起きていなくても `OKActions` が発火する(→ [ADR-0010](../adr/0010-monitoring-in-ephemeral-stack.md))。

## 8. 撤収と再構築で何が起きるか

| もの | 撤収すると | 次に建てるとき |
|---|---|---|
| ワークスペースの認可(§3) | **残る** | 何もしなくてよい |
| Slack のチャンネルとアプリの追加(§2・§4) | **残る** | 何もしなくてよい |
| SNS トピック 2 本 | 消える | スタックが同じ名前で作り直す |
| Chatbot のチャンネル設定 | 消える | スタックが作り直す |
| CloudWatch Logs の `/aws/chatbot/...`(us-east-1) | **残る**(スタックの外にあるため) | 何もしなくてよい → §8-2 |

**毎回踏む手作業は無い。**これがメール通知から移った一番の実利で、以前は建てるたびに SNS の購読確認メールを 2 通踏む必要があり、踏み忘れた系統は無音のままだった(→ [ADR-0011](../adr/0011-slack-notification-with-chatbot.md))。

**チャンネル設定を常駐にはできない。** トピック名が固定なので ARN は毎回同じになり、設定を手動で作って使い回せそうに見えるが、Chatbot の設定は対象トピックに自分自身を購読させる形で動くため、**撤収でトピックが消えるとその購読も失われる。**次に建てても Chatbot 側から繋ぎ直すまで無音になるので、設定はスタック内に置いて毎回作り直している。

## 8-2. Chatbot のログは us-east-1 に、スタックの外に残る

**ロググループ名は `/aws/chatbot/<ConfigurationName>`。** このリポジトリでは 3 つできる。

| チャンネル設定 | ロググループ |
|---|---|
| `nuxt-java-practice-<env>-ecs-task-shortage`(`app.yml`) | `/aws/chatbot/nuxt-java-practice-<env>-ecs-task-shortage` |
| `nuxt-java-practice-<env>-rds-alerts`(`app.yml`) | `/aws/chatbot/nuxt-java-practice-<env>-rds-alerts` |
| `nuxt-java-practice-<env>-deploy`(`pipeline.yml`) | `/aws/chatbot/nuxt-java-practice-<env>-deploy` |

**リージョンは us-east-1 で固定。** このリポジトリのスタックは ap-northeast-1 に建てるが、Chatbot のログはそこには出ない。公式ドキュメントが「ログを見るときは US East (N. Virginia) を指定すること」と明記している(→ [Accessing Amazon CloudWatch Logs](https://docs.aws.amazon.com/chatbot/latest/adminguide/cloudwatch-logs.html))。**コンソールで探して見つからないときは、たいていリージョンを間違えている。**

**ロググループを作るのは Chatbot 自身で、スタックではない。** したがって、

- **撤収しても消えない。** `app.yml` のチャンネル設定は作り捨てだが、ログは残り続ける
- **保持期間は既定の無期限。** テンプレートの `LogRetentionDays` はここには効かない(あれが効くのは `pipeline.yml` が自分で作る CodeBuild のロググループだけ)
- **同じリージョンのスタックに `AWS::Logs::LogGroup` を書いても代わりにはならない。** 作られるのは ap-northeast-1 で、Chatbot が使う us-east-1 のものとは別物になる

**`LoggingLevel: NONE` にしてもロググループは消えない。** コマンド実行の監査ログは常時有効で無効化できないと明記されている。**Slack からの承認は「コマンドの実行」**なので、承認するたびに監査イベントが出る。`NONE` で減るのはエラーログのほうだけ。

書き込まれる量はエラーと承認の監査だけなので、放っておいても課金上の実害はほぼ無い。それでも保持期間を付けるなら、**us-east-1 に対する、スタックの外の操作**になる。

## 9. 無料プランで効いてくる制限

| 制限 | 影響 |
|---|---|
| アプリ・インテグレーションは 10 個まで | Amazon Q Developer が 1 つ使う。残り 9 |
| メッセージ履歴は **90 日** | それより古いアラート履歴は Slack から見えなくなる。**CloudWatch 側にアラーム履歴は残る**ので実害は小さい |
| ストレージ 5 GB | アラート通知はテキストなので当面問題にならない |

有料プランに移る予定は無いので、**「Slack は直近 90 日の通知窓であって、記録の置き場ではない」**という前提で運用する。

## 10. 詰まったときの見どころ

| 症状 | 見るところ |
|---|---|
| Change Set が `must match pattern` で落ちる | `params` のプレースホルダを置き換えたか(→ §6) |
| スタックの作成が Chatbot リソースで失敗する | ワークスペースの認可を済ませたか(→ §3)。ワークスペース ID の取り違えもここで落ちる |
| `ConfigurationName` の衝突で `CREATE_FAILED` | コンソールで手動のチャンネル設定を作っていないか(→ §3 の最後)。同名はアカウント内で 1 つだけ |
| スタックは成功したのに Slack に何も来ない | **チャンネルに Amazon Q Developer を追加し忘れていないか**(→ §4)。次に、コンソールの **テストメッセージを送信** で切り分ける |
| テストメッセージは届くがアラートが来ない | アラーム側の問題。まだ一度も `ALARM` になっていないだけの可能性が高い。`aws cloudwatch describe-alarms` で状態を見る |
| 転送が失敗している理由を知りたい | CloudWatch Logs の `/aws/chatbot/<ConfigurationName>`。テンプレートは `LoggingLevel: ERROR` にしてある。**リージョンは us-east-1**(→ §8-2) |
| Slack から承認を押したのに進まない | 同じく `/aws/chatbot/nuxt-java-practice-<env>-deploy`(us-east-1)。`codepipeline:PutApprovalResult` が `GuardrailPolicies` とチャンネルロールの AND で通っているかを見る(→ §6-2) |
| Slack から AWS のコマンドを打ちたい | 意図的に塞いである。`GuardrailPolicies` に `AWSDenyAll` を入れているので、緩めるならそこを変える(→ [ADR-0011](../adr/0011-slack-notification-with-chatbot.md)) |
