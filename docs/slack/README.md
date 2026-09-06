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

### 承認ボタンは自動では出ない。カスタムアクションで補う(実機で確認済み)

**通知カードに Approve / Reject のボタンは付かない。** Chatbot は通知の種類に応じた既製のボタンを出すので「何も出ない」わけではなく、**承認だけが用意されていない。** CodePipeline の通知に最初から付くのは次の 2 つ。

| 既製のボタン | 実行されるコマンド | このチャンネルでは |
|---|---|---|
| `Get info` | `codepipeline get-pipeline --name <パイプライン>` | **`AccessDenied` で失敗する**(下記) |
| `Start Pipeline` | `codepipeline start-pipeline-execution` 相当 | 同じく許可していない |

**`GuardrailPolicies` は AWS 製のボタンにも効く。** `Get info` を押すと実際にこうなる。

```
User: ...assumed-role/nuxt-java-practice-stg-chatbot-approve-role/chatbot-session-slack-U... is not
authorized to perform: codepipeline:GetPipeline ... because no identity-based policy allows ...
```

許しているのは `GetPipelineState` であって `GetPipeline` ではない。**1 文字違いで弾かれているのは、ガードレールが設計どおり効いている証拠**なので直さない(パイプラインの定義を読みたいならコンソールのほうが速い)。エラーには**ロール名と Slack のセッション ID** も出るので、誰が叩いたかを追える。

**承認だけ既製ボタンが無いのは、おそらくトークンのせい。** `Get info` も `Start Pipeline` もパイプライン名だけで実行できるが、承認は通知に無い値を要求する。

**自分でボタンを作ることはできるが、1 クリックで承認は完結しない。** Chatbot の「Custom action」で CLI コマンドのボタンは作れる。ただし押したときに使える通知変数は次の 5 つしかない。

| 変数 | 中身の例 |
|---|---|
| `$Pipeline` | `nuxt-java-practice-stg-app` |
| `$Stage` / `$Action` | `Approve` / `Approve` |
| `$CustomData` | `pipeline.yml` の `CustomData`(イメージタグが入っている) |
| `$ExternalEntityLink` | 空 |

**承認トークンが無い。** `put-approval-result` は `--token` が必須で、値は承認 1 件ごとに変わるため、固定のコマンドとして書けない。

**トークンは「どの承認ゲートか」ではなく「そのゲートの、どの回か」を指している。** パイプライン名・ステージ名・アクション名は構成を変えない限り不変なので直書きできるが、トークンだけは実行のたびに変わる。おかげで**古い実行を誤って承認する事故**と**二重承認**が防がれている。IAM が「承認してよい人か」を見るのに対し、トークンは「どの承認について言っているのか」を見ている。

**ただし「2 手」にはできる。トークンを自分で渡せばよい。** カスタムアクションで変数を追加すると、ボタンを押したときに **Chatbot が値を確認する画面を出す**ので、そこにトークンを貼れる。手順 → **§6-3**。

結果として、承認の手段は 3 つある。

| 手段 | 手数 | 準備 |
|---|---|---|
| **カスタムアクションボタン** | 2 クリック + 貼り付け | §6-3 で 3 つ作る |
| **Slack でコマンドを打つ** | 2 コマンド | 不要 |
| **コンソール** | 画面を開いて 1 クリック | 不要 |

コマンドで打つ場合はこの 2 本。

```
@Amazon Q aws codepipeline get-pipeline-state --name nuxt-java-practice-stg-app --region ap-northeast-1
@Amazon Q aws codepipeline put-approval-result --pipeline-name nuxt-java-practice-stg-app --stage-name Approve --action-name Approve --token <上で見えたトークン> --result summary="",status=Approved --region ap-northeast-1
```

`status` は `Approved` / `Rejected`。`GuardrailPolicies` が `GetPipelineState` も許しているのは、この 1 コマンド目のため。

**トークン入りの通知が欲しければ別経路になる。** 承認アクションの `NotificationArn` に SNS トピックを指定すると、届くメッセージに `approval.token` が入る。世の中の「Slack で CodePipeline を承認する」記事はこれを Lambda で受けて対話メッセージを組み立てているが、**アプリ以外のコードを持たない方針**(→ [ADR-0011](../adr/0011-slack-notification-with-chatbot.md))なので採らない。appspec の Hooks を捨てたのと同じ判断。

## 6-3. 承認ボタンを作る(カスタムアクション)

> **実機で確認済み(2026-09-06)。** ①②は実際に作って動かした。③は②の `status` を変えるだけ。
> 前提 → §6-2。**カスタムアクションは CloudFormation ではなくコンソールで作る手動作業**なので、
> ワークスペースを作り直したら再作成が要る(→ §8)。

### 承認は 2 手になる

```
① Show token を押す   → 出力の Token: をコピー
② Approve / Reject を押す → 確認画面の Token に貼って実行
```

| ボタン | 何をするか |
|---|---|
| ① `ShowApprovalToken` | `get-pipeline-state` を実行してトークンを表示する |
| ② `RejectDeploy` | トークンを貼って却下する |
| ③ `ApproveDeploy` | トークンを貼って承認する(②の `status` 違い) |

**先に②を作って試すとよい。** 却下は何も壊さない。③は押すと本当にデプロイが走る。

### 入力する値

Chatbot コンソール → 対象のチャンネル設定 → **Custom actions** → 作成。

#### ① トークンを表示する

| 画面 | 項目 | 入れる値 |
|---|---|---|
| Step 1 | Custom action name | `ShowApprovalToken` |
| Step 1 | Custom action button text | `Show token` |
| Step 1 | Custom action type | **CLI action** |
| Step 2 | Define CLI command | 下記 |

```
codepipeline get-pipeline-state --name $Pipeline --region ap-northeast-1
```

- **name は識別子。** 画面の例が `CustomActionName` なので、空白なしの英数字にしておく
- **button text は Slack のボタンに出る文字列。** 日本語が通るかは未確認。**このリポジトリは IAM の `--description` で「日本語は使えない」を踏んでいる**(→ [cloudformation-operations.md](../infrastructure/cloudformation-operations.md) §2-2)ので、まず ASCII で作り、通ってから日本語を試すほうが切り分けやすい
- **`$Pipeline` は直書きでもよい**(このリポジトリはパイプラインが 1 本)。変数にしておくのは prod を足したときに同じボタンを使い回すため
- **`--query` は使わない。** Chatbot が JMESPath を受けるか未確認なので、まず素の出力で試す。出力が長くて Slack 側で切られるようなら、そこで初めて `--query "stageStates[?stageName=='Approve'].actionStates[0].latestExecution.token" --output text` を試す

#### ② 却下する

| 画面 | 項目 | 入れる値 |
|---|---|---|
| Step 1 | Custom action name | `RejectDeploy` |
| Step 1 | Custom action button text | `Reject` |
| Step 1 | Custom action type | **CLI action** |
| Step 2 | Define CLI command | 下記 |
| Step 2 | Add new variable | `Token` |

```
codepipeline put-approval-result --pipeline-name $Pipeline --stage-name $Stage --action-name $Action --token $Token --result summary="rejected from Slack",status=Rejected --region ap-northeast-1
```

- **`$Token` が肝。** 通知変数に無いので「Add new variable」で足す。**押すと Chatbot が確認画面を出し、そこで値を入れられる**(下記)
- `$Pipeline` / `$Stage` / `$Action` は通知から来る。値はそれぞれ `nuxt-java-practice-stg-app` / `Approve` / `Approve`
- `status` は `Approved` / `Rejected` の 2 択。**③を作るときはここだけ `Approved` に変える**
- `summary` は空でもよいが、あとで実行履歴を見たときに経路が分かるので入れておく

### 押したときの見え方

**②③を押すと、実行前に確認画面が出る。**

```
Command Action
  Action:   Approve
  Pipeline: nuxt-java-practice-stg-app
  Stage:    Approve
  Token:    98a49d36-...        ← ここに①でコピーした値を入れる
  I can run the command:
    codepipeline put-approval-result --pipeline-name ... --token ... --status=Rejected ...
  Run in account: 123456789012
  [Select different variables]
```

通知から来る `$Action` / `$Pipeline` / `$Stage` は埋まった状態で、**実行するコマンド全文が見えてから確定できる**。「Select different variables」で入れ直せる。

実行後は Slack に「I ran the command ...(role / account / region 付き)」が出る。**①のような読み取り専用コマンドでは "I ran the **read-only** command" と表示され、書き込み系と区別されている。**

**却下すると通知が 2 通来る。**

- `CodePipeline Manual Approval action FAILED`
- `1 action failed in stage: Approve. Additional Information: rejected from Slack`

2 通目の `Additional Information` は `--result summary=` に書いた文字列。**`summary` を空にすると「なぜ落ちたか」が通知から読めなくなる**ので、経路が分かる文言を入れておく。

> **API の応答は `ApprovedAt` と返る。** 却下でもこのフィールド名になる(「承認処理を行った時刻」という意味)。
> 却下したのに承認されたように見えるが、パイプラインの実行は `Failed` で終わっているので問題ない。

### 結果

**① は成立した(2026-09-06 実機)。**

- **トークンは見えた。** `Approve` ステージ → `ActionName: Approve` → `LatestExecution` の
  **`Token:`** の行。`Status: InProgress` の間だけ出る
- **出力は切られなかった。** 4 ステージ分そのまま届いたので **`--query` は不要**
- **`$Pipeline` は展開された。** 実行されたコマンドが Slack にそのまま表示される
- **Chatbot は読み取り専用コマンドを区別している。** 実行結果に
  「I ran the **read-only** command ...」と出る。どのロール・どのアカウント・どのリージョンで
  実行したかも併記されるので、監査の手掛かりになる

**トークンの値は `ActionExecutionId` と一致する。** 手動承認のトークンがそのアクション実行の ID
そのものだから。ただし仕様として保証されているわけではないので、**コピーするのは `Token:` の行**。

**② も成立した(同日)。**

- **`$Token` は確認画面で入れられた。** 空文字で実行されることはなかった
- **却下は通り、パイプラインの実行は `Failed` で終わった**
- ③(`ApproveDeploy`)は②の `status` を `Approved` に変えるだけで作れる

### なぜ 2 手なのか(1 手にできない理由)

**CLI action が実行するのは AWS CLI コマンド 1 本で、シェルを通していない。** そのため
`--token $(aws codepipeline get-pipeline-state ...)` のようなコマンド置換も、`&&` での連結も、
パイプも使えない。`$Token` は Chatbot が**実行前に文字列を差し替えているだけ**で、
値を計算する仕組みではないから、前のコマンドの出力を次に渡すことはできない。

1 手にするなら残る道は 2 つ。

| 方法 | 可否 |
|---|---|
| **Lambda action** | **採らない。** アプリ以外のコードを持たない方針(→ [ADR-0011](../adr/0011-slack-notification-with-chatbot.md)) |
| **Automation runbook action** | **理屈上は可能。未検討。** SSM Automation は複数ステップを持ち、`aws:executeAwsApi` の出力を次のステップに渡せる。宣言的なドキュメントなのでアプリコードにはあたらず、`AWS::SSM::Document` として `pipeline.yml` に書けば IaC 化も進む |

**Automation を採らないでいる理由は費用対効果。** SSM ドキュメントと Automation 用ロールでテンプレートが
40〜60 行伸び、ガードレールに `ssm:StartAutomationExecution` と `iam:PassRole` を足すことになる。
**1 人で使う環境で「2 手が 1 手になる」ための投資としては重い。** 必要になったら検討する。

**残っている未確認**

- button text に日本語が通るか(ASCII で作ったため未検証)
- `AWS::Chatbot::CustomAction` で IaC 化できるか(できるなら `pipeline.yml` に移して手動作業を減らせる)
- `aws:executeAwsApi` が `PutApprovalResult` を扱えるか(上の Automation 案を試すなら最初に確かめる点)

**成立しなかった場合の代替**は §6-2 のとおり、コンソールか `@Amazon Q` への 2 コマンド。どちらも今のままで動く。

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
