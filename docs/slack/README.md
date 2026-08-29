# Slack にアラートを流す(Amazon Q Developer in chat applications)

> 方針 → [ADR-0011](../adr/0011-slack-notification-with-chatbot.md)、検知層そのものの設計 → [ADR-0010](../adr/0010-monitoring-in-ephemeral-stack.md) / [フェーズ14 の設計書](../superpowers/specs/2026-08-28-phase14-monitoring-design.md)

CloudFormation スタックが作る CloudWatch アラームと RDS イベントを、Slack の 2 チャンネルに流すための手順書。**AWS 側は `cfn-apply` が作るので、ここに書くのは Slack 側の手動作業と、そこで得た ID を `params` に書き写すところまで。**

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

| SNS トピック | Slack チャンネル | 流れてくるもの |
|---|---|---|
| `nuxt-java-practice-stg-ecs-task-shortage` | `#njp-alerts-ecs` | ECS のタスク数不足(D) |
| `nuxt-java-practice-stg-rds-alerts` | `#njp-alerts-rds` | RDS のメトリクス(A)・ログ由来(B)・イベント購読(C) |

**チャンネルの分割単位は SNS トピックの分割単位。** Chatbot 側にフィルタ機能は無く、「どのアラームがどのチャンネルに出るか」はテンプレートで各アラームの `AlarmActions` にどちらのトピックを指定しているかで決まる。チャンネルを増やしたければ、まずトピックを増やすことになる。

**stg と prod は同じチャンネルを使う。** 通知にはアラーム名(`nuxt-java-practice-stg-rds-cpu-high` の形)が入るので環境は判別できる。分けたくなったら `params/prod.json` のチャンネル ID を差し替えるだけでよい。

## 1. なぜ Incoming Webhook を使わないのか

Slack の通知というと「カスタムアプリを作って Incoming Webhook の URL を発行する」形が一般的だが、**この構成では使わない。**

**SNS の HTTPS 購読で webhook URL を直接叩くことはできない。** SNS は購読を作るとき `SubscriptionConfirmation` を POST して `SubscribeURL` を踏ませるが、Slack の webhook は確認応答を返さないので購読が永久に `PendingConfirmation` のままになる。仮に確認できたとしても、SNS が送る JSON のエンベロープは Slack が期待するペイロード形式ではないので `invalid_payload` で弾かれる。

したがって webhook を使う構成では、**SNS と Slack の間に整形役(Lambda など)を挟むことが必須**になる。Chatbot はその整形役を AWS 側が持っているので、**コードを 1 行も書かずに済む。**これが webhook ではなく Chatbot を選んだ理由(詳細と落とした案 → [ADR-0011](../adr/0011-slack-notification-with-chatbot.md))。

その結果、**Slack に入れるのは自分で作るカスタムアプリではなく、App Directory にある公式の「Amazon Q Developer」アプリ 1 つ**になる。無料プランのアプリ枠 10 個のうち 1 つを使う。

## 2. Slack にチャンネルを 2 つ作る

ワークスペース「自分用」に **public チャンネル**を 2 つ作る。

- `njp-alerts-ecs`
- `njp-alerts-rds`

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

**2 つのチャンネルそれぞれで**アプリを追加する。メッセージ入力欄に `/invite` と打つと候補が出るので、**「エージェントとアプリをこのチャンネルに追加する」**を選び、一覧から **Amazon Q Developer** を選ぶ。

`/invite @Amazon Q` とテキストで打ち切る形は勧めない。アプリ名に空白が入るうえ、メンションが候補から確定されていないと**ただの人の招待コマンドとして解釈されて弾かれる**。上の UI から選ぶほうが確実。

**これを忘れるとスタックは成功するのに通知だけ届かない。** チャンネル ID は実在するのでリソースの作成は通り、投稿の段になって初めて失敗する。

## 5. チャンネル ID を 2 つ控える

Slack の左ペインでチャンネル名を右クリック → **リンクをコピー**。URL の末尾がチャンネル ID。

```
https://自分用.slack.com/archives/C0123ABCDEF
                                  ~~~~~~~~~~~ これ
```

`C` で始まる英数字の文字列。**チャンネル名(`njp-alerts-ecs`)ではなく ID を使う。**

## 6. `params` に書き写す

`cloudformation/params/stg.json` と `prod.json` の 3 つのプレースホルダを置き換える。

```json
{ "ParameterKey": "SlackWorkspaceId",  "ParameterValue": "T0123ABCDEF" },
{ "ParameterKey": "SlackChannelIdEcs", "ParameterValue": "C0123ABCDEF" },
{ "ParameterKey": "SlackChannelIdRds", "ParameterValue": "C0456GHIJKL" },
```

**この 3 つは秘密ではないので `params` に平文で置く。** ID を知っていても、ワークスペースを認可済みの AWS アカウントからでなければ使えない。「知っていれば誰でも投稿できる」webhook URL とはここが違う。`HostedZoneId` と同じ扱いで、GitHub の Environment secret にはしない(→ [ADR-0011](../adr/0011-slack-notification-with-chatbot.md))。

置き換え忘れると **Change Set の作成が `Parameter 'SlackWorkspaceId' must match pattern` で落ちる**(テンプレート側に `AllowedPattern` を付けてあるため)。プレースホルダのまま構築が成功して無音になるより、止まるほうがマシという判断。

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

**毎回踏む手作業は無い。**これがメール通知から移った一番の実利で、以前は建てるたびに SNS の購読確認メールを 2 通踏む必要があり、踏み忘れた系統は無音のままだった(→ [ADR-0011](../adr/0011-slack-notification-with-chatbot.md))。

**チャンネル設定を常駐にはできない。** トピック名が固定なので ARN は毎回同じになり、設定を手動で作って使い回せそうに見えるが、Chatbot の設定は対象トピックに自分自身を購読させる形で動くため、**撤収でトピックが消えるとその購読も失われる。**次に建てても Chatbot 側から繋ぎ直すまで無音になるので、設定はスタック内に置いて毎回作り直している。

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
| 転送が失敗している理由を知りたい | CloudWatch Logs の `/aws/chatbot/...`。テンプレートは `LoggingLevel: ERROR` にしてある |
| Slack から AWS のコマンドを打ちたい | 意図的に塞いである。`GuardrailPolicies` に `AWSDenyAll` を入れているので、緩めるならそこを変える(→ [ADR-0011](../adr/0011-slack-notification-with-chatbot.md)) |
