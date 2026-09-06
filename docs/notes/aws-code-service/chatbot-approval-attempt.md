# Chatbot でデプロイ承認をやってみた記録(フェーズ16→17)

> **この方式は採用していない。** フェーズ17 で自作 Slack App + Lambda に切り替えた
> (→ [ADR-0014](../../adr/0014-slack-approval-with-lambda.md))。
> ここは実機で分かったことの記録で、手順書ではない。
> 現行の承認手順 → [docs/slack/README.md](../../slack/README.md)。

Amazon Q Developer in chat applications(旧 AWS Chatbot)に CodePipeline の
手動承認を任せようとして、**2 手までは成立したが 1 手にはできなかった**。
その過程で分かった Chatbot の性質をまとめる。

パイプラインの通知経路そのものは [env-vars-and-logs.md](env-vars-and-logs.md) §7、
ロールの分担は [roles.md](roles.md)。

---

## 1. 承認ボタンは自動では出ない

**通知カードに Approve / Reject のボタンは付かない。**
Chatbot は通知の種類に応じた既製のボタンを出すので「何も出ない」わけではなく、
**承認だけが用意されていない。** CodePipeline の通知に最初から付くのは 2 つ。

| 既製のボタン | 実行されるコマンド | 承認用チャンネルでは |
|---|---|---|
| `Get info` | `codepipeline get-pipeline --name <パイプライン>` | **`AccessDenied` で失敗する**(→ §2) |
| `Start Pipeline` | `codepipeline start-pipeline-execution` 相当 | 同じく許可していない |

**この 2 つは消せない。** チャンネル設定から Chatbot を外す以外に方法がない。
「押しても失敗するボタンが常に並んでいる」状態になるのがフェーズ17 でやめた理由の 1 つ。

**承認だけ既製ボタンが無いのは、おそらくトークンのせい。**
`Get info` も `Start Pipeline` もパイプライン名だけで実行できるが、
承認は通知に無い値(トークン)を要求する(→ §3)。

---

## 2. `GuardrailPolicies` は AWS 製のボタンにも効く

`Get info` を押すと、AWS が用意したボタンであってもガードレールで弾かれる。

```
User: ...assumed-role/<プロジェクト>-<env>-chatbot-approve-role/chatbot-session-slack-<SlackユーザーID>
is not authorized to perform: codepipeline:GetPipeline ...
because no identity-based policy allows ...
```

許していたのは `GetPipelineState` であって `GetPipeline` ではない。
**1 文字違いで弾かれているのは、ガードレールが設計どおり効いている証拠。**

エラーに**ロール名と Slack のセッション ID**が出るので、**誰が叩いたかを追える**。
Chatbot 方式は「Slack ユーザーがロールを引き受けて AWS を叩く」形なので、
CloudTrail に押した人が残る。この点は Lambda 方式で失われた
(→ [ADR-0014](../../adr/0014-slack-approval-with-lambda.md) の代償)。

> **`GuardrailPolicies` を省略すると `AdministratorAccess` が既定で適用される。**
> **必ず明示すること。** これはアラート用のチャンネル設定にも同じく効く
> (あちらは `AWSDenyAll` を明示している → [ADR-0011](../../adr/0011-slack-notification-with-chatbot.md))。

---

## 3. カスタムアクションで使える変数は 5 つ。トークンは無い

「Custom action」で CLI コマンドのボタンは作れる。
ただし押したときに使える通知変数は次の 5 つだけ。

| 変数 | 中身の例 |
|---|---|
| `$Pipeline` | `<プロジェクト>-<env>-app` |
| `$Stage` / `$Action` | `Approve` / `Approve` |
| `$CustomData` | 承認アクションの `CustomData`(イメージタグを入れてあった) |
| `$ExternalEntityLink` | 空 |

**`put-approval-result` に必須の `--token` が無い。** 値は承認 1 件ごとに変わるため、
固定のコマンドとして書けない。

### トークンが何を指しているか

**「どの承認ゲートか」ではなく「そのゲートの、どの回か」。**
パイプライン名・ステージ名・アクション名は構成を変えない限り不変なので直書きできるが、
トークンだけは実行のたびに変わる。おかげで**古い実行を誤って承認する事故**と
**二重承認**が防がれている。

- **IAM** が見るのは「承認してよい人か」(認可)
- **トークン**が見るのは「どの承認について言っているのか」(同定)

役割が違うので、権限があってもトークンが無ければ承認できない。

なお実機では、トークンの値は `ActionExecutionId` と一致していた
(手動承認のトークンがそのアクション実行の ID そのものだから)。
**仕様として保証されてはいないので、コピーするなら `Token:` の行を見ること。**

---

## 4. 変数を足せば「2 手」にはできる(実機確認済み)

「Add new variable」で通知に無い変数を足すと、**押した時点で Chatbot が確認画面を出し、
そこで値を入れられる**。空文字のまま実行されることはなかった。

```
① トークンを表示するボタン → 出力の Token: をコピー
② 承認 / 却下ボタン        → 確認画面の Token に貼って実行
```

作ったのは 3 つ。①②は実際に動かし、③は②の `status` を変えるだけ。

| ボタン | CLI action の中身 |
|---|---|
| `ShowApprovalToken` | `codepipeline get-pipeline-state --name $Pipeline --region <リージョン>` |
| `RejectDeploy` | `codepipeline put-approval-result --pipeline-name $Pipeline --stage-name $Stage --action-name $Action --token $Token --result summary="rejected from Slack",status=Rejected --region <リージョン>` |
| `ApproveDeploy` | 同上で `status=Approved` |

### 確認画面の見え方

```
Command Action
  Action:   Approve
  Pipeline: <プロジェクト>-<env>-app
  Stage:    Approve
  Token:    <①でコピーした値>   ← ここに入れる
  I can run the command:
    codepipeline put-approval-result --pipeline-name ... --token ... --status=Rejected ...
  Run in account: 123456789012
  [Select different variables]
```

通知から来る `$Action` / `$Pipeline` / `$Stage` は埋まった状態で、
**実行するコマンド全文が見えてから確定できる**。「Select different variables」で入れ直せる。

### 実行後の見え方

Slack に「I ran the command …(role / account / region 付き)」が出る。
**読み取り専用コマンドは "I ran the read-only command …" と表示され、書き込み系と区別される。**

却下すると通知が 2 通来る。

- `CodePipeline Manual Approval action FAILED`
- `1 action failed in stage: Approve. Additional Information: rejected from Slack`

2 通目の `Additional Information` は `--result summary=` に書いた文字列。
**`summary` を空にすると「なぜ落ちたか」が通知から読めなくなる**ので、
経路が分かる文言を入れておくとよい。

> **API の応答は却下でも `ApprovedAt` というフィールド名で返る**
> (「承認処理を行った時刻」という意味)。却下したのに承認されたように見えるが、
> パイプラインの実行は `Failed` で終わっているので問題ない。

### `--query` は結局試していない

出力が切られるようなら `--query` でトークンだけ抜くつもりだったが、
**4 ステージ分がそのまま届いたので不要だった。**
Chatbot が JMESPath を受けるかどうかは**未確認のまま**。

---

## 5. なぜ 1 手にできないのか

**CLI action が実行するのは AWS CLI コマンド 1 本で、シェルを通していない。**
そのため `--token $(aws codepipeline get-pipeline-state ...)` のようなコマンド置換も、
`&&` での連結も、パイプも使えない。

`$Token` は Chatbot が**実行前に文字列を差し替えているだけ**で、値を計算する仕組みではない。
**前のコマンドの出力を次に渡す手段が無い**、というのが 2 手になる理由。

1 手にする道は 2 つあった。

| 方法 | 当時の判断 |
|---|---|
| **Lambda action** | 採らなかった。アプリ以外のコードを持たない方針(→ [ADR-0011](../../adr/0011-slack-notification-with-chatbot.md)) |
| **Automation runbook action** | **理屈上は可能。未検証。** SSM Automation は複数ステップを持ち、`aws:executeAwsApi` の出力を次のステップに渡せる。宣言的なドキュメントなのでアプリコードにはあたらず、`AWS::SSM::Document` として IaC 化もできた |

Automation を採らなかったのは費用対効果。SSM ドキュメントと Automation 用ロールで
テンプレートが 40〜60 行伸び、ガードレールに `ssm:StartAutomationExecution` と
`iam:PassRole` を足すことになる。**1 人で使う環境で「2 手が 1 手になる」ための投資としては重い。**

なお **`aws:executeAwsApi` が `PutApprovalResult` を扱えるかは確かめていない。**
この案を蒸し返すなら、そこが最初の関門になる。

---

## 6. やめた理由

フェーズ17 で自作 Slack App + Lambda に切り替えた。決め手は 4 つ。

1. **1 クリックで承認できない。** トークンが通知変数に無く、シェルも使えないので構造的に 2 手
2. **押しても失敗する既製ボタンが消せない。** `Get info` / `Start Pipeline` はチャンネルから
   Chatbot を外さない限り残る
3. **カスタムアクションは通知の種類を問わず全通知に付く。** 「デプロイ成功」の通知にまで
   承認ボタンが並ぶ。トークンが要るので誤承認そのものは起きないが、
   **どの通知のボタンを押したかが承認対象に影響しない**ので、文脈が担保できない
4. **承認者はアプリ開発担当。** インフラ担当だけが使うなら「そういうもの」で済むが、
   承認の担い手を広げるなら**押していいものが一目で分かる**ことを優先すべきだと判断した

**カスタムアクションはコンソールで作る手動リソース**でもあった。
CloudFormation の管理外なので、ワークスペースを作り直したら再作成が要る。
**`AWS::Chatbot::CustomAction` で IaC 化できるかは未確認のまま。**

---

## 7. やめた後に残るもの — スタックの外にできた 2 つ

**Chatbot 方式のリソースをテンプレートから消しても、AWS 側に 2 つ残った。**
どちらも**スタックが作ったものではない**ので、スタックの更新でも削除でも道連れにならない。
フェーズ17 から数日後に SNS のトピック一覧で見つけて手で消した。

| 残ったもの | 誰が作ったか | リージョン |
|---|---|---|
| SNS トピック `CodeStarNotifications-<チャンネル設定名>-<40 桁の 16 進>` | CodeStar Notifications | スタックと同じ |
| ロググループ `/aws/chatbot/<チャンネル設定名>` | Chatbot | **us-east-1** |

### なぜ SNS トピックができるのか

**`TargetType: AWSChatbotSlack` は「SNS を挟まない」書き方であって、
実際に SNS を通らないわけではない。** CodeStar Notifications は Chatbot を宛先に指定されると、
**配送用の SNS トピックを自分で作って間に挟む。** だからトピック名に
`AWS::Chatbot::SlackChannelConfiguration` の `ConfigurationName` が入る
(パイプライン名でもデプロイグループ名でもない)。末尾の 16 進は宛先のハッシュ。

**このトピックは CloudFormation の管理外**なので、`DeployApprovalChannel` を消しても残る。
購読(Chatbot への配送先)だけが消えて、空のトピックが居座る形になる。

### 消してよいかの確かめ方

```bash
# 購読が空か
aws sns list-subscriptions-by-topic --topic-arn <トピックのARN>

# このトピックを宛先にしている通知ルールが無いか
aws codestar-notifications list-notification-rules
aws codestar-notifications describe-notification-rule --arn <ルールのARN> \
  --query '{name:Name,targets:Targets[].TargetAddress}'
```

現行(フェーズ17)のルールは 1 本で、宛先は `pipeline.yml` の
`PipelineNotificationTopic`(`<プロジェクト>-<env>-pipeline-notifications`)。
これ以外を指すルールが無ければ、`aws sns delete-topic` してよい。

### ロググループの方

`LoggingLevel: ERROR` を書いた分だけ Chatbot が us-east-1 に作る(→ [docs/slack/README.md](../../slack/README.md) §8-2)。
**保持期間は無期限**で、これもスタックの外。

```bash
aws logs describe-log-groups --region us-east-1 \
  --log-group-name-prefix /aws/chatbot/<プロジェクト> \
  --query 'logGroups[].{name:logGroupName,retention:retentionInDays,bytes:storedBytes}'
```

**アラート用(`app.yml` の 2 本)のロググループは現役なので消さないこと。**
承認チャンネル(`-deploy`)の分だけを消す。
なお**エラーが一度も出ていないチャンネル設定はロググループ自体が作られない**ので、
一覧に出てこないことがある(`-ecs-task-shortage` がそうだった)。

### 教訓

**AWS が「自分で作ってくれる」リソースは、CloudFormation の撤収から漏れる。**
Chatbot・CodeStar Notifications のように**サービスが裏でリソースを立てる**組み合わせを
やめるときは、テンプレートから消しただけで終わりにせず、コンソールか CLI で
現物を数えること。作り捨て運用のつもりでも、こういうものが少しずつ残っていく。
