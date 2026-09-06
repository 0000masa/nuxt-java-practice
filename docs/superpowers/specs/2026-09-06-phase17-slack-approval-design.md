# フェーズ17: デプロイ承認を自作 Slack App + Lambda に移す

日付: 2026-09-06
ステータス: 実装済み(実機未検証)

方針 → [ADR-0014](../../adr/0014-slack-approval-with-lambda.md)(自作 Slack App + Lambda への移行)。
[ADR-0011](../../adr/0011-slack-notification-with-chatbot.md)(アラートは Chatbot)と
[ADR-0013](../../adr/0013-app-deploy-with-code-services.md)(Code 系デプロイ)は **supersede しない**。
やめた方式の実機記録 → [chatbot-approval-attempt.md](../../notes/aws-code-service/chatbot-approval-attempt.md)

## 1. 目的

フェーズ16 で組んだ「Slack でデプロイを承認する」形を、**Chatbot から自作 Slack App + Lambda に載せ替える。**

目的は 2 つあり、**順序に意味がある。**

1. **会社でも使われている一般的な構成(SNS → Lambda → Slack)を自分で組んで理解する。** 学習用リポジトリとしての主目的
2. **承認者はインフラ担当ではなくアプリ開発担当。** 押せないボタンが並び、承認に 2 手かかり、関係ない通知にまでボタンが出る UI では承認の担い手を広げられない

2 番目は「使いづらい」ではなく「要件を満たさない」という話として扱う。

## 2. いま何が動いているか(フェーズ16 の到達点)

| 経路 | 実装 |
| --- | --- |
| パイプラインの通知 | `AWS::CodeStarNotifications::NotificationRule` → Chatbot(`TargetType: AWSChatbotSlack`)→ `#njp-deploy` |
| 承認の操作 | Chatbot のカスタムアクションボタン、または `@Amazon Q` へのコマンド |
| 権限の境界 | `AWS::Chatbot::SlackChannelConfiguration` の `GuardrailPolicies` とチャンネルロールの **AND** |
| アラートの通知 | CloudWatch アラーム → SNS → Chatbot → `#njp-alerts-ecs` / `#njp-alerts-rds`(`app.yml`) |

**承認自体は成立していた。** 動かなかったのではなく、UI が要件を満たさなかった。

## 3. 制約として確定した事実

**すべて実機で確かめたもの。**詳細と再現手順は
[chatbot-approval-attempt.md](../../notes/aws-code-service/chatbot-approval-attempt.md)。

### 3-1. Chatbot の通知カードに承認ボタンは自動では付かない

Chatbot は通知の種類に応じた既製ボタンを出すので「何も出ない」わけではない。
**承認だけが用意されていない。**

### 3-2. カスタムアクションで使える通知変数に承認トークンが無い

使えるのは `$Action` / `$CustomData` / `$ExternalEntityLink` / `$Pipeline` / `$Stage` の 5 つ。
`put-approval-result` は `--token` が必須なので、**1 コマンドでは完結しない。**

「Add new variable」で `$Token` を足すと押下時に確認画面で値を入れられるため、
**2 手(トークン表示ボタン → 承認ボタンに貼る)なら成立する。**

### 3-3. 既製ボタン `Get info` / `Start Pipeline` は消せない

`Get info` は `codepipeline:GetPipeline` を叩くが、`GuardrailPolicies` が許しているのは
`GetPipelineState` なので **`AccessDenied` になる。**
**ガードレールが AWS 製のボタンにも効いている証拠**であって、直すべき不具合ではない。
だが**押せないボタンを消す手段が無い**。Chatbot をそのチャンネルで使うのをやめる以外にない。

### 3-4. カスタムアクションは通知の種類を問わず全通知に付く

「Manual Approval action FAILED」の通知にまで承認・却下ボタンが並ぶ。

### 3-5. 古い通知のボタンでも、新しいトークンを入れれば通る

`$Pipeline` / `$Stage` / `$Action` はこのリポジトリでは常に同じ値なので、
**どの通知のボタンを押したかが承認対象に影響しない。**
誤承認は起きない(トークンが同定している)が、**文脈が担保されない。**

### 3-6. CLI action はシェルを通さない

実行されるのは AWS CLI コマンド 1 本だけ。コマンド置換(`$(...)`)も `&&` も `|` も使えない。
変数は実行前の文字列置換にすぎないので、**前のコマンドの出力を次に渡せない。**
→ 3-2 の 2 手を 1 手に縮める道が、Chatbot の中には無い。

### 3-7. `{{resolve:ssm-secure:...}}` は Lambda の環境変数では使えない

対応しているリソース・プロパティが限られている。
非 secure の `{{resolve:ssm:...}}` で展開すると**テンプレートにも Lambda のコンソール画面にも平文で出る。**
→ **環境変数にはパラメータ名だけを入れ、Lambda が実行時に読む**以外の形が無い。

## 4. 決定

### 決定1 リンク方式ではなく対話ボタン方式にする

Lambda が投稿するのは**コンソールへのリンク**ではなく、**Approve / Reject ボタン付きのメッセージ**。
押下は Slack から HTTPS のコールバックで飛んでくるので、**受け口が要る。**

リンク方式なら公開エンドポイントを持たずに済み、押せないボタンが消える効果も得られるが、
**「1 クリックで承認できない」という不満がそのまま残る。**
世の中で「SNS → Lambda → Slack」と呼ばれているのも対話ボタン方式のほうなので、
目的 1(会社の構成を学ぶ)からもこちらを採る。

**代償**: 常時公開の HTTPS エンドポイントが 1 つ増える(→ 決定7)。

### 決定2 パイプライン通知を全部 Lambda に寄せる

`#njp-deploy` に届く通知は**承認待ちも成功も失敗も、すべて Lambda が整形して投稿する。**
`pipeline.yml` から `AWS::Chatbot::SlackChannelConfiguration` / `ChatbotGuardrailPolicy` /
`ChatbotApproveRole` の 3 リソースを削除する。

**「承認だけ Lambda、成功・失敗は Chatbot のまま」を捨てた理由は 2 つ。**

1. **3-3 の既製ボタンが残る。** カスタムアクションを消しても `Get info` / `Start Pipeline` は消えない。
   Chatbot をそのチャンネルで使うのをやめない限り、押せないボタンは出続ける
2. **同じチャンネルに 2 種類の見た目が混ざる。** 承認は Lambda が組んだメッセージ、
   成功・失敗は Chatbot の定型カード。分かりやすさを目的にした改修としてちぐはぐ

工事量の差も見た目ほど大きくない。`Targets` の `TargetType` を `AWSChatbotSlack` → `SNS` に
差し替えるだけで入口は変わる。増えるのは Lambda 側の分岐処理だけ。

### 決定3 アラート 2 本(`app.yml` の Chatbot)は据え置く

`#njp-alerts-ecs` / `#njp-alerts-rds` は Chatbot のまま。ADR-0011 はここについては有効なまま。

**技術的な理由がある。アラートの通知経路は `app.yml`(作り捨てスタック)にある。**
ここに Lambda を持ち込むと、

- **環境を建て直すたびに Lambda も作り直し**になる
- zip を S3 に上げる処理が `cfn-apply.yml` 側にも必要になり、**構築フローが重くなる**

一方、承認用の Lambda は `pipeline.yml`(常駐)に置けるので建て直しの影響を受けない。
**この非対称性があるので、「揃えたい」以上の理由が無いのに工事量が倍以上になる。**

**代償**: Slack のアプリ枠を 2 つ使う(Amazon Q Developer + 自作。10 個中 2 個)。
見た目も揃わないが、**チャンネルが分かれているので並んで見えることはない。**

### 決定4 `CodeStarNotifications` → SNS に一本化する

承認アクションの `Configuration.NotificationArn`(SNS トピックを指定するとメッセージに
`approval.token` が載る)は**使わない。**

世の中の「Slack で CodePipeline を承認する」記事がこの経路を使うのは Chatbot 以前からある
古い形だから。**トークンは Lambda が押下時に取れば足りる**(決定5)ので、
入口を `CodeStarNotifications` 1 つに揃えるほうが素直になる。

- SNS トピックが 1 本で済む
- 承認待ち・成功・失敗が同じ経路を通るので、Lambda の入口が 1 つになる

### 決定5 承認トークンは押下時に取得する

`interaction` 関数が `GetPipelineState` を呼び、`Approve` ステージの `latestExecution.token` を読む。

**通知時に取ってボタンへ埋め込む案を捨てた理由。**
パイプラインは `ExecutionMode: SUPERSEDED`(フェーズ16 の決定12)なので、
承認待ちのまま新しい push が来ると**後発が先発を追い越す。**
埋め込んでいると**古い実行のトークンを持ったままのボタン**が残る。
押下時に読めば、常に「いま保留中の承認」が対象になる。

**副産物として `notify` 側の AWS 権限が減る。** トークンのために `GetPipelineState` を
持たせる必要がなくなった。

**終わっている承認は `token` が返らない**ので、`findToken` が `undefined` を返したら
「この承認はすでに終わっています」と Slack に返す。**ボタンが残っているメッセージを
押されても壊れない**(→ ADR-0014 の「結果として生じること 3」)。

### 決定6 Lambda を 2 つに分ける

| 関数 | 起動 | AWS 権限 |
| --- | --- | --- |
| `<プロジェクト>-<env>-slack-notify` | SNS サブスクリプション | `codepipeline:GetPipelineExecution` / `ssm:GetParameter` / `kms:Decrypt` |
| `<プロジェクト>-<env>-slack-interaction` | Function URL | `codepipeline:GetPipelineState` + `PutApprovalResult` / `ssm:GetParameter` / `kms:Decrypt` |

**1 つにまとめない理由は最小権限。** まとめると、SNS から呼ばれるだけの経路にも承認権限が付いてくる。
分ければ **公開エンドポイントを持つ関数だけが承認権限を持つ**形に閉じられる。

**`notify` が `GetPipelineExecution` を持っているのは設計から動いた点**(→ 5-2)。
当初は権限ゼロの想定だった。

`kms:Decrypt` は `Resource: "*"` だが `kms:ViaService` を `ssm.<リージョン>.amazonaws.com` に
限定しているので、**SSM 経由の復号以外には使えない。**

### 決定7 Function URL(`AuthType: NONE`)+ 署名検証 + `ReservedConcurrentExecutions: 5`

**API Gateway を捨てた理由。** Slack が必要とするのは「POST を受ける URL 1 本」だけ。
WAF もレート制限もカスタムドメインも使わないのに、API + ルート + 統合 + 権限の 4 リソースが増える。

**`AuthType: NONE` にせざるを得ない理由。** Slack は AWS の IAM 署名(SigV4)を付けられない。
`AWS_IAM` にすると Slack からのリクエストが全部 403 になる。

**「認証しない」であって「無防備」ではない。** 守りは 2 枚。

1. **Slack の署名検証**(HMAC-SHA256 + 5 分のタイムスタンプ窓)— これが本体。
   `interaction/verify.mjs`。**署名検証を通る前に AWS を一切叩かない**
2. **`ReservedConcurrentExecutions: 5`** — 叩かれ続けても**同時実行数の上限で課金が青天井にならない**

`InteractionUrlPermission`(`lambda:InvokeFunctionUrl` / `Principal: "*"` / `FunctionUrlAuthType: NONE`)を
忘れると **URL は出来るのに 403 になる。**

### 決定8 Incoming Webhook + `response_url`(bot トークンを使わない)

| | 採用 | 捨てた案 |
| --- | --- | --- |
| 投稿 | Incoming Webhook に POST | `chat.postMessage`(bot トークン) |
| 必要な secret | webhook URL + signing secret | bot トークン + signing secret |
| スコープ | `incoming-webhook` のみ | `chat:write` など |

**投稿先は `#njp-deploy` 1 つだけ**なので「webhook URL は 1 チャンネル固定」は制約にならない。
**漏れたときの被害も小さい**(bot トークンは任意のチャンネルに投稿できる)。

**押した後のメッセージ差し替えは `replace_original` で行う。**
`block_actions` への HTTP 応答本文に `replace_original: true` を付けると、
押されたメッセージがそのまま差し替わる。**ボタンが消える。**

```
[承認前]  <パイプライン名> のデプロイ承認をお願いします  [ 承認 ] [ 却下 ]
             ↓ 押す
[承認後]  :white_check_mark: 承認しました — <パイプライン名> / @<ユーザー>
```

`response_url` に POST する方法もあるが、**HTTP 応答で済むならその 1 往復が要らない。**

**承認ボタンにだけ `confirm` を付けている。** 押し間違いが本番デプロイに直結するため。
却下には付けない(やり直せる)。

### 決定9 素の `AWS::Lambda::Function` + `aws cloudformation package`

テンプレートには**ローカルパスのまま**書く。

```yaml
Code: ../lambda/slack-approval/notify   # 相対パスはテンプレートの位置から解決される
```

`pipeline-apply.yml` が `aws cloudformation package` を呼ぶと、zip 化 → S3 アップロード →
`Code` を `S3Bucket` / `S3Key` に差し替えたテンプレートが出力される。
**`sam` CLI は要らない。**`package` は AWS CLI の標準コマンド。

**zip は Lambda 専用のバケットに置く**(`--s3-prefix slack-approval`)。
テンプレート置き場を間借りする案もあったが、**保存要件が正反対なので分けた**(→ 5-2)。
バケットは手動管理の常駐リソースで、`gha-cfn-stg` に `PutLambdaCode` を足す必要がある。

**SAM を捨てた理由。** 同じファイル・同じスタックに書ける点は SAM も同じで、
`--s3-bucket` を指定すれば専用バケットも作られない。**当初考えていたほどの障害は無い。**
それでも採らないのは、`AWS::Serverless::Function` が **IAM ロールやロググループを暗黙に生成する**から。
`pipeline.yml` は IAM ポリシーの 1 文ごとに「なぜこの権限が要るのか」をコメントで残す書き方で
通してきたので、暗黙生成はその方針と逆行する。`sam local` も、
署名検証とコールバックが絡む以上どのみち実機確認になる。

**`Code.ZipFile`(テンプレート直書き)も捨てた。** 管理外の S3 オブジェクトがゼロになるが
4,096 文字制限があり、テストも lint もできない。目的 1 と逆方向。

**Node.js 22 / 依存ゼロ。**

- **AWS SDK v3 は runtime が同梱している**(nodejs18.x 以降)。`npm install` もバンドラも要らず、
  zip は `.mjs` を固めるだけ。**代償として SDK のバージョンは AWS 任せになる**
- 署名検証は `node:crypto`、Slack への POST は組み込みの `fetch`
- **Node 22 はこのリポジトリに既にある**(frontend)。Python を足すと 3 言語目になる。
  Java はコールドスタートもビルドも重い

### 決定10 テストは署名検証だけ書く

`lambda/slack-approval/test/verify.test.mjs`(`node:test`。10 ケース)。

**理由は「壊れても正常に見える」から。**
署名検証を常に `true` にしても Slack からのリクエストは通るので、動作確認では気づけない。
タイムスタンプの窓を見忘れても、生ボディではなくパース後の文字列で HMAC を計算しても同じ。
**そして通ってしまえば、公開エンドポイントで誰でもデプロイを承認できる。**

逆に Slack のメッセージの見た目や通知の分岐は **Slack を見れば分かる**のでテストしない。

**`pipeline-apply.yml` がスタックを反映する前に走らせる。** 落ちたら S3 へのアップロードも
デプロイも行われない。実行場所は GitHub Actions の runner(ホストに何も要らない)。

テストファイルを `test/` に分けているのは、**`Code:` が指すディレクトリに入れると zip に混ざるから。**

### 決定11 承認者は限定せず、`summary` に Slack ユーザー名を記録する

**誰が押せるかは変わらない。** Chatbot 方式も `UserRoleRequired: false` で
「チャンネルにいる全員が承認できる」状態だった。境界は**`#njp-deploy` に誰を入れるか**で引く。
目的 2(アプリ開発担当も承認できるように)からも、AWS 側で絞る方向は逆行する。

**失われるのは CloudTrail の帰属。** Chatbot 方式では
`assumed-role/<チャンネルロール>/chatbot-session-slack-<SlackユーザーID>` が残っていたが、
Lambda 方式で残るのは Lambda のロールだけになる。

**埋め合わせとして `PutApprovalResult` の `summary` にユーザー名を入れる。**

```
summary: "Approved by @<ユーザー> via Slack"
```

これは**パイプラインの実行履歴と、その後の通知に出る**。
CloudTrail を掘らないと分からなかったものが通知で読めるようになるので、**実用上はむしろ改善。**

### 決定12 SSM の SecureString は環境ごとのパスに置き、実行時に読む

3-7 のとおり、環境変数に入れられるのは**パラメータ名だけ。**

| 環境変数 | 値 |
| --- | --- |
| `SLACK_WEBHOOK_URL_PARAM` | `<SsmParameterPath>slack_webhook_url` |
| `SLACK_SIGNING_SECRET_PARAM` | `<SsmParameterPath>slack_signing_secret` |

コールドスタート時に 1 回だけ `GetParameter`(`WithDecryption: true`)を呼び、
モジュールスコープに保持して使い回す。

**パスは環境ごと**(`/<プロジェクト>/<env>/`)。既存の SecureString 4 つと同じ並びに置ける。
prod を足したときにチャンネルを分けられる。当面は同じ値を入れておけばよく、
これは `AWS_CODESTAR_CONNECTION_ARN` で「stg と prod は同じ値でよい」と決めたのと同じ扱い。

**環境共通のパス(`/<プロジェクト>/slack/`)を捨てたのは、後から環境ごとに移すほうが高くつくから。**
パイプラインのパラメータもテンプレートも直すことになる。

### 決定13 `pipeline-apply.yml` に SecureString の存在チェックを足す

無いままでも**スタックの作成は成功する。** 気づくのは Slack のボタンを押したときで、
しかも Slack には何も出ず CloudWatch Logs を見に行くことになる。
**`REPLACE_ME_` のチェックや CodeStar Connections の `AVAILABLE` チェックと同じ壊れ方。**

```bash
for name in slack_webhook_url slack_signing_secret; do
  aws ssm get-parameter --name "${path}${name}" >/dev/null 2>&1 || exit 1
done
```

**`--with-decryption` を付けない。** 存在を見るだけで値は要らないので `kms:Decrypt` も要らず、
**うっかり値をログに出す経路も塞げる。**

**代償**: `gha-cfn-stg` の `DeployStack` に `ssm:GetParameter` を足す
(`CheckSlackSecrets`)。**IAM の手動変更がもう一度必要になる。**

## 5. 変更・新規のファイル

### 新規

| ファイル | 中身 |
| --- | --- |
| `lambda/slack-approval/notify/index.mjs` | SNS を受けて Slack に投稿。承認待ちならボタン付き、それ以外は結果カード |
| `lambda/slack-approval/interaction/index.mjs` | Function URL を受けて署名検証 → トークン取得 → `PutApprovalResult` → メッセージ差し替え |
| `lambda/slack-approval/interaction/verify.mjs` | Slack の署名検証。**この関数が唯一の防御** |
| `lambda/slack-approval/test/verify.test.mjs` | 署名検証のテスト 10 件(`node:test`) |
| `docs/adr/0014-slack-approval-with-lambda.md` | 方針 |
| `docs/notes/aws-code-service/chatbot-approval-attempt.md` | やめた方式の実機記録 |

### `cloudformation/pipeline.yml`

| 箇所 | 変更 |
| --- | --- |
| `SlackWorkspaceId` / `SlackChannelIdDeploy` | **パラメータごと削除。** 投稿先は webhook URL が持つのでテンプレートはチャンネルを知らない |
| `SsmParameterPath` | 新規パラメータ(`AllowedPattern: ^/.*/$`。`app.yml` と同じ書式) |
| `ChatbotGuardrailPolicy` / `ChatbotApproveRole` / `DeployApprovalChannel` | **削除** |
| `PipelineNotificationTopic` / `PipelineNotificationTopicPolicy` | 新規。トピックポリシーは `codestar-notifications.amazonaws.com` に `sns:Publish` を許し、`aws:SourceAccount` で他アカウントからの撃ち込みを塞ぐ |
| `NotifyLogGroup` / `NotifyFunctionRole` / `NotifyFunction` / `NotifySubscription` / `NotifyInvokePermission` | 新規 |
| `InteractionLogGroup` / `InteractionFunctionRole` / `InteractionFunction` / `InteractionFunctionUrl` / `InteractionUrlPermission` | 新規 |
| `PipelineNotificationRule` の `Targets` | `TargetType: AWSChatbotSlack` → **`SNS`**。`EventTypeIds` は 6 つとも据え置き |
| Outputs `SlackInteractionUrl` | 新規。**Slack App の Interactivity に登録する値** |

### `.github/workflows/pipeline-apply.yml`

| 箇所 | 変更 |
| --- | --- |
| 冒頭 | `node --test lambda/slack-approval/test/*.test.mjs`(AssumeRole より前。AWS に触らないため) |
| 「前提を確かめる」 | SecureString 2 つの存在チェックを追加(→ 決定13) |
| 新規ステップ | `aws cloudformation package`(**Lambda 専用バケット** / `--s3-prefix slack-approval`。zip 化と S3 アップロード) |
| 「スタックを反映する」 | `--template-file` を `package` の出力に変更。あわせて **`--s3-bucket`(テンプレート置き場)を追加**し、`app.yml` と渡し方を揃える |
| 「前提を確かめる」 | `aws sts get-caller-identity` を 1 回だけ引いて `account` を outputs に置く(バケット名の組み立てに 2 か所で使う) |
| 「結果をサマリに出す」 | `SlackInteractionUrl` を出す(初回に Slack App へ登録するため) |

### `cloudformation/params/pipeline-{stg,prod}.json`

`SlackWorkspaceId` / `SlackChannelIdDeploy` を削除し、`SsmParameterPath` を追加。

### その他のドキュメント

`docs/slack/README.md`(Slack App の作成手順)、
`docs/infrastructure/cloudformation-operations.md`(§4 の SecureString が 4 → 6、
§3 が「S3 バケットを 2 つ作る」に、§2-2 に `CheckSlackSecrets` と `PutLambdaCode`)、
`docs/test/README.md`(Lambda のテスト)、`CLAUDE.md`(`lambda/` をフォルダ構成に追加)。

## 5-2. 実装で確定したこと(設計から動いた点)

| 項目 | 設計時 | 実装 | 理由 |
| --- | --- | --- | --- |
| `notify` の AWS 権限 | **ゼロ**(投稿するだけ) | **`codepipeline:GetPipelineExecution` を 1 つ持たせた** | 「どのコミットを承認しようとしているのか」をメッセージに出すため。`CustomData` を `additionalAttributes` から拾う案もあったが、**フィールド名が実機未確認**なうえ、実行を読めば**コミット SHA とコミットメッセージ**が取れて情報量も多い |
| メッセージ差し替えの手段 | `response_url` に POST | **HTTP 応答本文に `replace_original`** | `block_actions` はその場の応答でも元メッセージを置き換えられる。**1 往復減る**。`response_url` は押下時に発行されるので、時間が経ってからでも使える(30 分 / 5 回)という利点はあるが、今回は不要 |
| 承認ボタンの確認ダイアログ | 設計になし | **承認にだけ `confirm` を付けた** | 押し間違いが本番デプロイに直結する。却下はやり直せるので付けない |
| 押下時に承認が終わっていた場合 | 設計になし | **`findToken` が空なら「すでに終わっています」を返す** | コンソールで承認した場合とタイムアウトした場合、Slack のメッセージにボタンが残る(ADR-0014 の「結果 3」)。押されても壊れないようにした |
| テストファイルの置き場 | `interaction/` 配下 | **`test/` に分けた** | `Code:` が指すディレクトリに入れると **zip に混ざる** |
| SNS トピックの保護 | 設計になし | **`aws:SourceAccount` 条件を付けた** | `codestar-notifications.amazonaws.com` に `sns:Publish` を開ける以上、他アカウントのルールから撃ち込まれない条件を足す |
| `kms:Decrypt` の絞り方 | 未定 | **`kms:ViaService` で SSM 経由に限定** | 既定の SSM キーはエイリアスしか無く ARN で絞りにくい。**経由するサービスで絞れば同じ効果**になる |
| zip の置き場 | テンプレート置き場を `lambda/` プレフィックスで間借り | **Lambda 専用バケットを新設**(`...-lambda-artifacts-<アカウントID>` / `slack-approval/`) | **保存要件が正反対**だった。テンプレートは CloudFormation が中身を写し取るので消えても困らない(30 日で削除)が、zip はスタックが `S3Key` で参照し続け、**消えるとロールバックが失敗する**。同居させると将来ライフサイクルを触ったときに**静かに壊れる** |
| `pipeline.yml` の渡し方 | 直接渡す(40,131 バイトで上限内) | **`deploy --s3-bucket` で S3 経由** | `app.yml` と揃える。技術的な必要は無いが、上限まで残り 11 KB で**日本語コメントは 1 文字 3 バイト**。超えた日に `DeployBucketRequiredError` で足を止めない先回り |

### 引っかかりやすい罠を 2 つ、テンプレートに書き残した

**1. `Code:` の相対パスはテンプレートの位置から解決される。**
`cloudformation/pipeline.yml` に書くので `../lambda/slack-approval/notify` になる。
リポジトリ直下からの相対パスではない。

**2. `InteractionUrlPermission` を忘れると URL は出来るのに 403 になる。**
`AWS::Lambda::Url` はエンドポイントを作るだけで、**関数を呼ぶ権限は別に要求される。**
`FunctionUrlAuthType: NONE` を `AWS::Lambda::Permission` 側にも揃えないと通らない。

## 6. 実測で覆りうる項目

実機で確かめて、この節を更新する。**0 番が一番危ない。**

0. **`CodeStarNotifications` が SNS に流すメッセージの `detail` の正確な形。**
   `notify/index.mjs` は `detail.type.category === "Approval"` かつ
   `detail.state` が `STARTED` のときを承認待ちと判定しているが、**実機未確認。**
   外れると**承認待ちの通知にボタンが付かず、結果カードとして流れる。**
   `detail.stage` / `detail.action` / `detail["execution-id"]` のキー名も同様。
   **生の `Sns.Message` を `console.log` に出したうえで汎用メッセージに落ちるようにしてある**ので、
   **初回の通知で `/aws/lambda/<プロジェクト>-<env>-slack-notify` を見て確かめること。**
1. **`artifactRevisions[0].revisionSummary` の形。**
   `CodeStarSourceConnection` では JSON 文字列(`CommitMessage` キー)で入ってくる前提で
   `parseRevisionSummary` を書いているが、実機未確認。
   **外れても素の文字列として扱うので落ちはしない**(見た目が崩れるだけ)。
   なお `get-pipeline-state` の出力ではこの形が確認できている。
2. **Slack の 3 秒応答制限に間に合うか。**
   `interaction` は SSM(コールドスタート時のみ)→ `GetPipelineState` → `PutApprovalResult` →
   応答、と AWS API を 2〜3 本叩く。**間に合わないと Slack にエラー表示が出る。**
   `Timeout` は 10 秒に設定しているが、これは Lambda 側の上限であって Slack の制限とは別。
   間に合わない場合は、**先に 200 を返してから `response_url` に POST する形**に組み替える
   (`response_url` は 30 分 / 5 回まで有効)。
3. ~~`package` が上げた zip の掃除~~ **決着済み。掃除しない。**
   `pipeline-destroy.yml` でスタックを消しても zip は残るが、**それが正しい**。
   ロールバックは古い `S3Key` を取りに行くので、消す仕掛けを入れると静かに壊れる。
   専用バケットにはライフサイクルもバージョニングも設定せず、`pipeline-destroy.yml` からも触らない
   (**stg と prod で共用しているので、片方の撤収がもう片方を壊す**)。
   増えるのはコードを変えたときだけ(`package` は中身のハッシュで重複を飛ばす)なので、数 KB 単位。
4. **`ReservedConcurrentExecutions: 5` が実運用の邪魔にならないか。**
   1 人で使う分には十分だが、**アカウント全体の同時実行数からこの 5 が予約で差し引かれる。**
   他に Lambda を置いていないので今は影響しない。
5. **Incoming Webhook の投稿が Block Kit のボタンを正しく描画するか。**
   `blocks` に `actions` を含むメッセージを webhook で投げられることは仕様上問題ないが、
   **Interactivity が有効な Slack App でないとボタンが押せない**ので、
   App 側の設定(決定1 の受け口の登録)とセットで確かめる。
6. **`node --test` に渡すグロブが GitHub Actions の runner で展開されるか。**
   `node --test <ディレクトリ>` はディレクトリをモジュールとして解決しようとして落ちるため、
   `lambda/slack-approval/test/*.test.mjs` とファイルグロブで渡している。
   **シェルが展開する前提**なので、`shell` の既定が変わると壊れる。
