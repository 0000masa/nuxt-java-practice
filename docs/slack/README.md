# Slack にアラートを流し、デプロイを承認する

> 方針 → [ADR-0011](../adr/0011-slack-notification-with-chatbot.md)(アラート)/ [ADR-0014](../adr/0014-slack-approval-with-lambda.md)(デプロイ承認)
> 検知層そのものの設計 → [ADR-0010](../adr/0010-monitoring-in-ephemeral-stack.md) / [フェーズ14 の設計書](../superpowers/specs/2026-08-28-phase14-monitoring-design.md)

CloudFormation スタックが作る CloudWatch アラームと RDS イベント、そしてデプロイパイプラインの通知を Slack に流すための手順書。**AWS 側はスタックが作るので、ここに書くのは Slack 側の手動作業と、そこで得た値を渡すところまで。**

**仕組みが 2 つある。** アラートは AWS 製のアプリ(Amazon Q Developer、旧 AWS Chatbot)に任せ、デプロイ承認は**自作の Slack App と Lambda** でやる。フェーズ17 で後者だけ切り替えた(→ [ADR-0014](../adr/0014-slack-approval-with-lambda.md))。Chatbot でやってみた記録 → [chatbot-approval-attempt.md](../notes/aws-code-service/chatbot-approval-attempt.md)。

## 0. 全体像

```
[アラート]  app.yml(作り捨て)
CloudWatch アラーム 7 本 ─┐
RDS イベント購読 ─────────┤
                          ↓
              SNS トピック 2 本
                          ↓
   Amazon Q Developer(旧 AWS Chatbot)      ← AWS 製アプリ。コードを書かない
                          ↓
              #njp-alerts-ecs / #njp-alerts-rds

[デプロイ承認]  pipeline.yml(常駐)
CodeStarNotifications ─→ SNS ─→ Lambda(notify) ─→ Incoming Webhook ─→ #njp-deploy
                                                                          │ ボタンを押す
                                                    Lambda(interaction) ←─┘ Function URL
                                                          ↓
                                                   PutApprovalResult
```

| 経路 | Slack チャンネル | 流れてくるもの | Slack からの操作 |
|---|---|---|---|
| SNS `...-ecs-task-shortage` | `#njp-alerts-ecs` | ECS のタスク数不足(D) | できない(`AWSDenyAll`) |
| SNS `...-rds-alerts` | `#njp-alerts-rds` | RDS のメトリクス(A)・ログ由来(B)・イベント購読(C) | できない(`AWSDenyAll`) |
| CodeStarNotifications → SNS → Lambda | `#njp-deploy` | デプロイの承認待ち・成否 | **承認・却下できる** |

**Slack に入れるアプリが 2 つになる。** AWS 製の Amazon Q Developer(アラート用)と、自分で作る App(承認用)。無料プランのアプリ枠 10 個のうち 2 つを使う。

**`#njp-deploy` の仕組みだけ別スタックにある。** アラート用 2 本は `app.yml`(作り捨て)、承認用は `pipeline.yml`(常駐)。**アラート側を Lambda に寄せなかったのは、作り捨てスタックに Lambda を置くと環境を建て直すたびに作り直しになるから**(→ ADR-0014)。

**チャンネルの分割単位は SNS トピックの分割単位。** Chatbot 側にフィルタ機能は無く、「どのアラームがどのチャンネルに出るか」はテンプレートで各アラームの `AlarmActions` にどちらのトピックを指定しているかで決まる。チャンネルを増やしたければ、まずトピックを増やすことになる。

**stg と prod は同じチャンネルを使う。** 通知にはアラーム名(`nuxt-java-practice-stg-rds-cpu-high` の形)が入るので環境は判別できる。分けたくなったら `params/prod.json` のチャンネル ID を差し替えるだけでよい。

## 1. アラートは Chatbot、承認は自作 App

**SNS の HTTPS 購読で Slack の webhook URL を直接叩くことはできない。** SNS は購読を作るとき `SubscriptionConfirmation` を POST して `SubscribeURL` を踏ませるが、Slack の webhook は確認応答を返さないので購読が永久に `PendingConfirmation` のままになる。仮に確認できたとしても、SNS が送る JSON のエンベロープは Slack が期待するペイロード形式ではないので `invalid_payload` で弾かれる。

したがって webhook を使う構成では、**SNS と Slack の間に整形役(Lambda)を挟むことが必須**になる。**この一点が、2 つの仕組みが分かれた理由。**

| | アラート | デプロイ承認 |
|---|---|---|
| 整形役 | **AWS 製の Chatbot**(コードを書かない) | **自作の Lambda** |
| Slack のアプリ | Amazon Q Developer(App Directory) | **自分で作る App** |
| 判断 | [ADR-0011](../adr/0011-slack-notification-with-chatbot.md) | [ADR-0014](../adr/0014-slack-approval-with-lambda.md) |

**アラートは通知が一方向で、整形も定型で足りる**ので、コードを持たずに済む Chatbot がそのまま最良のまま。ADR-0011 の判断は変えていない。

**承認は Slack から AWS を操作する双方向の経路**で、そこが Chatbot では成立しなかった。承認トークンが通知変数に無く 1 クリックで完結せず、押しても `AccessDenied` になる既製ボタンが消せず、カスタムアクションは全通知に付いてしまう。**承認者はインフラ担当ではなくアプリ開発担当**なので、「押していいものが一目で分かる」ことを優先して自作に切り替えた。踏んだ内容の記録 → [chatbot-approval-attempt.md](../notes/aws-code-service/chatbot-approval-attempt.md)。

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

**アラート用の 2 つ(`#njp-alerts-ecs` / `#njp-alerts-rds`)で**アプリを追加する。メッセージ入力欄に `/invite` と打つと候補が出るので、**「エージェントとアプリをこのチャンネルに追加する」**を選び、一覧から **Amazon Q Developer** を選ぶ。

`/invite @Amazon Q` とテキストで打ち切る形は勧めない。アプリ名に空白が入るうえ、メンションが候補から確定されていないと**ただの人の招待コマンドとして解釈されて弾かれる**。上の UI から選ぶほうが確実。

**これを忘れるとスタックは成功するのに通知だけ届かない。** チャンネル ID は実在するのでリソースの作成は通り、投稿の段になって初めて失敗する。

**`#njp-deploy` には Amazon Q Developer は要らない。** 承認は自作 App が担う(→ §6-2)。フェーズ16 で招待していたなら退出させてよい(残っていても害はない)。

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

**`pipeline-stg.json` に Slack の ID は書かない。** 承認用の Lambda は webhook URL に投稿するだけで、ワークスペースもチャンネルも知らない。控えた `#njp-deploy` のチャンネル ID は §6-2 の Slack App 側で使う。

**これらは秘密ではないので `params` に平文で置く。** ID を知っていても、ワークスペースを認可済みの AWS アカウントからでなければ使えない。「知っていれば誰でも投稿できる」webhook URL とはここが違う。`HostedZoneId` と同じ扱いで、GitHub の Environment secret にはしない(→ [ADR-0011](../adr/0011-slack-notification-with-chatbot.md))。

置き換え忘れると **Change Set の作成が `Parameter 'SlackWorkspaceId' must match pattern` で落ちる**(テンプレート側に `AllowedPattern` を付けてあるため)。プレースホルダのまま構築が成功して無音になるより、止まるほうがマシという判断。

## 6-2. 承認用の Slack App を作る(1 回きり・常駐)

**アラート用の Amazon Q Developer とは別に、自分で App を 1 つ作る。** ここで得るのは 2 つの値で、どちらも SSM の SecureString に入れる(→ [運用手順 §4](../infrastructure/cloudformation-operations.md))。

| 得る値 | 何に使うか | 置き場 |
|---|---|---|
| **Incoming Webhook URL** | Lambda が `#njp-deploy` に投稿する宛先 | `/nuxt-java-practice/<env>/slack_webhook_url` |
| **Signing Secret** | Slack から来たリクエストが本物か確かめる | `/nuxt-java-practice/<env>/slack_signing_secret` |

**どちらも資格情報。** webhook URL は知っている人なら誰でもそのチャンネルに投稿でき、signing secret は**漏れると公開エンドポイントへのリクエストを偽装できる**。`params` には絶対に置かない。

### 手順

1. <https://api.slack.com/apps> → **Create New App** → **Blank app**
   - 選択肢は **AI Agent** / **Starter app** / **From a manifest** / **Blank app** の 4 つ。
     **一番下の `Blank app`** を選ぶ
   - **以前は `From scratch` という名前だった。** 古い記事はその名前で書かれているので読み替える
2. 名前(例 `njp-deploy-approver`)とワークスペースを選ぶ
3. 左メニュー **Incoming Webhooks** → トグルを **On** → **Add New Webhook to Workspace**
   → 投稿先に **`#njp-deploy`** を選ぶ → 発行された URL を控える
4. 左メニュー **Basic Information** → **App Credentials** の **Signing Secret** を控える
5. 控えた 2 つを SSM に入れる(→ [運用手順 §4](../infrastructure/cloudformation-operations.md))

**Interactivity の設定はまだできない。** 登録する URL は Lambda を作らないと決まらないので、§6-3 で戻ってくる。

**Bot Token(`xoxb-`)は発行しない。** 投稿は webhook、押された後の返信は Slack が渡してくる `response_url` で足りる。**bot トークンは漏れると任意のチャンネルに投稿できてしまう**ので、要らないなら作らないほうがよい(→ ADR-0014)。

## 6-3. デプロイ後に Interactivity を設定する

**Function URL はスタックを作るまで決まらない。** そのため手順が 3 段になる。

```
① Slack App を作る(§6-2)      → webhook URL と signing secret
② SSM に入れて pipeline-apply を実行 → Outputs に Function URL が出る
③ その URL を Slack App に登録   ← ここ
```

1. `pipeline-apply.yml` のジョブサマリ、または次のコマンドで URL を取る

```bash
aws cloudformation describe-stacks --stack-name nuxt-java-practice-stg-pipeline \
  --query "Stacks[0].Outputs[?OutputKey=='SlackInteractionUrl'].OutputValue" --output text
```

2. Slack App の左メニュー **Interactivity & Shortcuts** → トグルを **On**
3. **Request URL** に ① の URL を貼って **Save Changes**

**②と③の間はボタンを押しても何も起きない。** Slack が送り先を知らないため。1 回きりの作業なので実害はないが、順序として意識しておく。

**URL はスタックを作り直すと変わる。** `pipeline-destroy.yml` で消して建て直したら、③をやり直すこと。

### 承認・却下のしかた

`#njp-deploy` に届く承認待ちのメッセージにボタンが付く。

```
nuxt-java-practice-stg-app のデプロイ承認をお願いします。
コミット: `1a2b3c4`
> fix: buildspec のシェルを bash に固定する

[ 承認 ]  [ 却下 ]        コンソールで開く
```

- **承認には確認ダイアログが挟まる。** 押し間違いが本番のリスナー切り替えに直結するため
- **押すとボタンが消え、結果に置き換わる。** 古いボタンが残らない
- **誰が押したかは CodePipeline 側に残る。** `PutApprovalResult` の `summary` に Slack のユーザー名を入れているので、その後の通知の `Additional Information` に出る
- **`#njp-deploy` にいる人は誰でも押せる。** 境界は「チャンネルに誰を入れるか」で引く(→ ADR-0014)

**コンソールで承認した場合と 7 日でタイムアウトした場合だけ、Slack のボタンが残る。** Slack 側は何が起きたか知らないため。押しても「この承認はすでに終わっています」と返るだけで、誤って承認されることはない。

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
| 承認用の Slack App(§6-2) | **残る** | **Interactivity の URL だけ登録し直す** → §6-3 |
| SNS トピック 2 本(アラート) | 消える | スタックが同じ名前で作り直す |
| Chatbot のチャンネル設定 2 つ | 消える | スタックが作り直す |
| CloudWatch Logs の `/aws/chatbot/...`(us-east-1) | **残る**(スタックの外にあるため) | 何もしなくてよい → §8-2 |

**承認まわりは `pipeline.yml`(常駐)にあるので、アプリのスタックを撤収しても消えない。** 消えるのは `pipeline-destroy.yml` を実行したときだけで、そのときは Function URL が変わるので §6-3 をやり直す。

**毎回踏む手作業は無い。**これがメール通知から移った一番の実利で、以前は建てるたびに SNS の購読確認メールを 2 通踏む必要があり、踏み忘れた系統は無音のままだった(→ [ADR-0011](../adr/0011-slack-notification-with-chatbot.md))。

**チャンネル設定を常駐にはできない。** トピック名が固定なので ARN は毎回同じになり、設定を手動で作って使い回せそうに見えるが、Chatbot の設定は対象トピックに自分自身を購読させる形で動くため、**撤収でトピックが消えるとその購読も失われる。**次に建てても Chatbot 側から繋ぎ直すまで無音になるので、設定はスタック内に置いて毎回作り直している。

## 8-2. Chatbot のログは us-east-1 に、スタックの外に残る

**ロググループ名は `/aws/chatbot/<ConfigurationName>`。** このリポジトリでは 3 つできる。

| チャンネル設定 | ロググループ |
|---|---|
| `nuxt-java-practice-<env>-ecs-task-shortage`(`app.yml`) | `/aws/chatbot/nuxt-java-practice-<env>-ecs-task-shortage` |
| `nuxt-java-practice-<env>-rds-alerts`(`app.yml`) | `/aws/chatbot/nuxt-java-practice-<env>-rds-alerts` |

**フェーズ17 で 3 つ目(`...-deploy`)は無くなった。** 承認用の Chatbot 設定を消したため。**ただし過去のログは us-east-1 に残り続ける**(下記のとおりスタックの外にあるので、消したければ手で消す)。承認まわりの新しいログは `/aws/lambda/nuxt-java-practice-<env>-slack-notify` と `...-slack-interaction` に、**ap-northeast-1 で、スタック管理下で**出る。

**リージョンは us-east-1 で固定。** このリポジトリのスタックは ap-northeast-1 に建てるが、Chatbot のログはそこには出ない。公式ドキュメントが「ログを見るときは US East (N. Virginia) を指定すること」と明記している(→ [Accessing Amazon CloudWatch Logs](https://docs.aws.amazon.com/chatbot/latest/adminguide/cloudwatch-logs.html))。**コンソールで探して見つからないときは、たいていリージョンを間違えている。**

**ロググループを作るのは Chatbot 自身で、スタックではない。** したがって、

- **撤収しても消えない。** `app.yml` のチャンネル設定は作り捨てだが、ログは残り続ける
- **保持期間は既定の無期限。** テンプレートの `LogRetentionDays` はここには効かない(あれが効くのは `pipeline.yml` が自分で作る CodeBuild のロググループだけ)
- **同じリージョンのスタックに `AWS::Logs::LogGroup` を書いても代わりにはならない。** 作られるのは ap-northeast-1 で、Chatbot が使う us-east-1 のものとは別物になる

**`LoggingLevel: NONE` にしてもロググループは消えない。** コマンド実行の監査ログは常時有効で無効化できないと明記されている。`NONE` で減るのはエラーログのほうだけ。

書き込まれる量はエラーだけなので、放っておいても課金上の実害はほぼ無い。それでも保持期間を付けるなら、**us-east-1 に対する、スタックの外の操作**になる。

## 9. 無料プランで効いてくる制限

| 制限 | 影響 |
|---|---|
| アプリ・インテグレーションは 10 個まで | Amazon Q Developer と自作 App で **2 つ**使う。残り 8 |
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
| Slack から AWS のコマンドを打ちたい | アラート用 2 チャンネルでは意図的に塞いである。`GuardrailPolicies` に `AWSDenyAll` を入れているので、緩めるならそこを変える(→ [ADR-0011](../adr/0011-slack-notification-with-chatbot.md)) |

### 承認まわり(フェーズ17)

**見るログが 2 つに分かれる。** どちらも **ap-northeast-1**(Chatbot と違って us-east-1 ではない)。

**ただし「ログが空」自体が手掛かりになる。** Function URL の認可で弾かれた場合、関数は起動しないのでログは 1 行も出ない。切り分けはこの順で行う。

```bash
# ① 関数そのものは健全か(URL を経由しない)。401 が返れば署名検証まで正しく動いている
aws lambda invoke --function-name nuxt-java-practice-stg-slack-interaction \
  --payload '{}' /tmp/out.json --cli-binary-format raw-in-base64-out && cat /tmp/out.json

# ② URL 経由はどうか。403 なら認可層、401 ならコードまで届いている
curl -s -w '\nstatus=%{http_code}\n' \
  "$(aws lambda get-function-url-config \
      --function-name nuxt-java-practice-stg-slack-interaction \
      --query FunctionUrl --output text)"
```

**①が 401 で②が 403 なら、原因は呼び出し許可で確定。** Slack を疑う必要はない。

| 症状 | 見るところ |
|---|---|
| 承認待ちの通知が Slack に来ない | `/aws/lambda/nuxt-java-practice-<env>-slack-notify`。`slack_webhook_url` が SSM にあるか、値が正しいか。**そもそも SNS まで来ているか**は CodeStarNotifications のルールと SNS トピックのメトリクスで切り分ける |
| 通知は来るがボタンが無い / 内容が汎用的 | 承認待ちだと判定できていない。**`notify` は生の `Sns.Message` をログに出す**ので、`detail` の形を見て判定条件を直す(→ [フェーズ17 の設計書](../superpowers/specs/2026-09-06-phase17-slack-approval-design.md)の未確認事項) |
| ボタンを押すと「このアプリから403が返されました」 | **Function URL の呼び出し許可が足りない。** `curl <Function URL>` を直接叩いて再現するか確かめる(Slack は無関係)。**`lambda:InvokeFunctionUrl` だけでは足りず `lambda:InvokeFunction` も要る**(2 つ目に `FunctionUrlAuthType` 条件は付けられない → [ADR-0014](../adr/0014-slack-approval-with-lambda.md) の結果 8) —— コンソールの関数ページが警告を出してくれる。関数は 1 度も起動しないので**ログには何も残らない**(→ 下記) |
| Interactivity のトグルが On にならない | **上と同じ原因。** Slack は保存時に Request URL の疎通を見るので、403 が返ると受理しない。**Slack 側をいくら触っても直らない** |
| ボタンを押しても何も起きない | **Interactivity の Request URL を登録したか**(→ §6-3)。スタックを建て直した後は URL が変わっている |
| ボタンを押すと Slack にエラーが出る | `/aws/lambda/nuxt-java-practice-<env>-slack-interaction`。`401` なら署名検証で落ちている(`slack_signing_secret` の値違い)。3 秒を超えた場合は Slack 側にタイムアウトが出る |
| 「この承認はすでに終わっています」と返る | 正常。コンソールで承認済みか、タイムアウト済みか、`SUPERSEDED` で実行が入れ替わっている |
| `pipeline-apply` が SecureString で落ちる | §4 の 2 つを作ったか。`gha-cfn-stg` に `CheckSlackSecrets` を足したか(→ [運用手順 §2-2](../infrastructure/cloudformation-operations.md)) |
