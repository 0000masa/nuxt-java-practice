# デプロイ承認を自作 Slack App + Lambda で行う

日付: 2026-09-06
ステータス: accepted

**[ADR-0011](./0011-slack-notification-with-chatbot.md) と [ADR-0013](./0013-app-deploy-with-code-services.md) を supersede しない。**
どちらも大部分が有効で、変わるのは 2 点だけ —— ADR-0011 の「アプリケーション以外のコードを持たない」という
**原則の適用範囲**と、ADR-0013 の帰結 6 が定めた**承認 UI の実現手段**。

## 決定

`#njp-deploy` の Chatbot をやめ、**デプロイ承認を自作の Slack App と Lambda 2 つで行う。**

- 通知の経路を **CodeStarNotifications → SNS → Lambda → Incoming Webhook** に変える
- Slack のボタン押下を **Lambda Function URL**(`AuthType: NONE`)で受け、
  `GetPipelineState` でトークンを取ってから `PutApprovalResult` を呼ぶ
- **Lambda は 2 つに分ける。** 通知側は承認権限を持たない。
  **公開エンドポイントを持つ関数だけが承認権限を持つ**
- Slack App は **Incoming Webhook + `response_url`。** bot トークンは使わない
- コードは `lambda/slack-approval/` に置き、**素の `AWS::Lambda::Function` +
  `aws cloudformation package`** で S3 経由デプロイ。**SAM は使わない**
- **zip の置き場はテンプレート置き場と分け、Lambda 専用の常駐バケットを新設する**(→ 結果 7)。
  あわせて `pipeline.yml` 自身も `deploy --s3-bucket` で S3 経由にし、`app.yml` と渡し方を揃える
- **アラート用の Chatbot 2 本(`app.yml`)はそのまま。** 触るのは `pipeline.yml` だけ
- `pipeline.yml` から `AWS::Chatbot::SlackChannelConfiguration` /
  `ChatbotGuardrailPolicy` / `ChatbotApproveRole` を削除する

手順 → [docs/slack/README.md](../slack/README.md)、
設計 → [フェーズ17 の設計書](../superpowers/specs/2026-09-06-phase17-slack-approval-design.md)、
やめた方式の記録 → [Chatbot で承認をやってみた記録](../notes/aws-code-service/chatbot-approval-attempt.md)

## 背景と理由

ADR-0013 の帰結 6 は「Lambda は増えない」と書き、承認は Chatbot のコマンドか
**カスタムアクションボタン**で行うとした。**フェーズ16 を実機で動かして、その前提が崩れた。**

### 実機で分かったこと

- **通知カードに承認ボタンは自動では付かない。** Chatbot は通知の種類に応じた既製ボタンを出すが、
  承認だけ用意されていない
- **カスタムアクションで承認ボタンを作っても 1 クリックにならない。** 押したときに使える通知変数は
  `$Action` / `$CustomData` / `$ExternalEntityLink` / `$Pipeline` / `$Stage` の 5 つで、
  **承認トークンが無い。** `put-approval-result --token` は必須なので、固定のコマンドとして書けない
- **2 手なら成立する。** 「Add new variable」で `$Token` を足すと、押した時点で Chatbot が確認画面を
  出して値を入れさせる。「トークン表示ボタン → 承認ボタンに貼る」で承認できることは確認済み
- **`Get info` / `Start Pipeline` という既製ボタンが付き、押すと `AccessDenied` になる。**
  `GuardrailPolicies` で許していないため。**既製ボタンは消せない**
  (そのチャンネルで Chatbot を使うのをやめる以外に手が無い)
- **カスタムアクションは通知の種類を問わず全通知に付く。**
  「Manual Approval action FAILED」の通知にまで承認・却下ボタンが並ぶ
- **古い通知のボタンを押しても、新しいトークンを入れれば通る。**
  `$Pipeline` / `$Stage` / `$Action` は構成上いつも同じ値なので、
  どの通知のボタンを押したかが承認対象に影響しない。誤承認は起きないが**文脈が担保されない**

### 覆す理由は 2 つ。順序も含めて記録する

**1. 会社で使われている形を自分で組んで理解する(主)。**
SNS → Lambda → Slack は世の中で最も一般的な構成で、Slack App の作り方、interactivity の受け口、
署名検証、`put-approval-result` までを自分で書くことになる。**ADR-0013 が
「学習価値は高い」と認めつつ採らなかった案そのもの。** このリポジトリは学習用であり、
「会社のコードを読むときの補助線を自分の手で作る」ことが目的として成立する。

**2. 承認者はインフラ担当ではなくアプリ開発担当(従)。**
インフラ担当が使うなら、2 手でも押せないボタンが並んでいても「そういうもの」で済む。
**押していいものが一目で分からない UI では、承認の担い手を広げられない。**
これは「使いづらい」ではなく**要件を満たさない**という話で、実務であれば運用に支障が出る。

**ADR-0011 の原則を捨てるわけではない。** アラート通知は今も Chatbot のままで、
「コードを書かずに済むならそうする」判断は生きている。今回覆すのは**適用範囲**であり、
「承認 UI という、AWS 側の既製品では要件を満たせない領域についてはコードを持つ」と読み替える。

## 結果として生じること

### 1. CloudTrail から承認者が追えなくなる

Chatbot 方式では、承認は **Slack ユーザーがロールを引き受けて AWS の API を叩く**形だったので、
`assumed-role/...-chatbot-approve-role/chatbot-session-slack-<ユーザーID>` が CloudTrail に残った。

Lambda 方式では**実行主体が Lambda のロール 1 つ**になり、AWS 側から見ると誰が押しても同じになる。

代わりに、**Slack のコールバックに入っている押した人の情報を `PutApprovalResult` の
`summary` に入れる。**

```
summary="Approved by @<slack のユーザー名> via Slack"
```

これは**パイプラインの実行履歴と、その後の通知に出る**。
CloudTrail を掘らないと分からなかったものが通知から読めるようになるので、**実用上はむしろ改善**。
ただし「AWS の監査証跡としての帰属」は失われるので、代償として記録しておく。

なお**「誰が押せるか」は変わっていない。** Chatbot 方式も `UserRoleRequired: false` で
チャンネルにいる全員が承認できた。境界は今も「`#njp-deploy` に誰を入れるか」で引く。

### 2. 常時公開の HTTPS エンドポイントが 1 つ増える

Slack はボタン押下を HTTPS で送ってくるので、受け口が要る。**AWS の IAM 認証は使えない**
(Slack は署名を付けられない)ため、Function URL は `AuthType: NONE` になる。

守りは 2 枚。

- **Slack の署名検証**(HMAC-SHA256、タイムスタンプ 5 分の窓)。これが本体
- **`ReservedConcurrentExecutions: 5`。** 叩かれ続けても同時実行数の上限で課金を面積で抑える

**WAF もレート制限も付けられない。** 付けるなら API Gateway を挟むことになるが、
Slack が要求するのは POST を受ける URL 1 本だけなので採らない。

`CLAUDE.md` の「常時公開はしない」はアプリ環境についての方針であり、
パイプラインのスタックは既に常駐なので矛盾はしない。それでも**公開面が 1 つ増えたことは事実**として残す。

### 3. 承認済み以外のメッセージにはボタンが残る

押した後は `replace_original` で元のメッセージを差し替えるので、**Slack で承認したものはボタンが消える。**
Chatbot 方式で問題になった「古い通知にボタンが残る」は、この経路については構造的に解決する。

**残るのは 2 つの場合。**

- コンソールで承認・却下したとき
- 7 日でタイムアウトしたとき

どちらも Slack 側は何が起きたか知らないため、ボタンが残る。消すには bot トークンで `chat.update` を
呼ぶ必要があり、**投稿時の `ts` をどこかに保存する仕組み**(DynamoDB か SSM)が要る。

**古いボタンを押しても「この承認はすでに終わっています」と返す**ようにしてあるので、許容する。

### 4. 手動作業が 3 段になる

**Function URL はスタックを作るまで決まらない。** ところが Slack App の Interactivity には
その URL を登録する必要があるので、順序が固定される。

```
① Slack App を作る          → signing secret と webhook URL を控える
② SSM に SecureString を 2 つ作る
③ pipeline-apply.yml を実行  → Function URL が Outputs に出る
④ Slack App の Interactivity に ③ の URL を登録
```

**③ と ④ の間はボタンを押しても Slack がどこにも送れない。** 1 回きりの作業なので実害は無いが、
手順として書き残す必要がある。

**②を忘れると「緑なのに機能していない」が再発する。** スタックは成功し、
ボタンを押したときに初めて落ちる。しかも Slack には何も出ず CloudWatch Logs を見に行くことになる。
そこで `pipeline-apply.yml` に**事前チェック**を足す(CodeStar Connections の `AVAILABLE`
チェックと同じ考え方)。副作用として `gha-cfn-stg` ロールに `ssm:GetParameter` の追加が要る。

**さらに結果 7 の専用バケットも手で作る。** まとめると、着手前の手動作業は
**Slack App / SecureString 2 つ / S3 バケット 1 つ / IAM 2 文(`CheckSlackSecrets` と
`PutLambdaCode`)**、そして**デプロイ後**に Interactivity の URL 登録。

### 5. secret は SSM に置き、Lambda が実行時に読む

**CloudFormation の `{{resolve:ssm-secure:...}}` は Lambda の環境変数では使えない**
(対応しているリソース・プロパティが限られている)。`{{resolve:ssm:...}}` で展開すると
テンプレートにも Lambda のコンソール画面にも平文で出る。

したがって**環境変数にはパラメータ名だけを入れ、Lambda がコールドスタート時に SSM から読む。**
ECS のタスク定義が `Secrets` / `ValueFrom` で自動的にやっていることを、Lambda では自分で書くという違い。

パスは**環境ごと**(`/nuxt-java-practice/<env>/`)にする。当面 stg と prod で同じ値を入れることになるが、
prod でチャンネルを分けたくなったときに値を差し替えるだけで済む。
手順書の「SSM に SecureString を 4 つ作る」は **6 つ**になる。

### 6. リポジトリに 3 つ目のコードが増える

`lambda/` を新設し、`CLAUDE.md` のフォルダ構成にも追記する。

**依存ゼロで書ける。** 署名検証は `node:crypto`、Slack への POST は組み込みの `fetch`、
AWS SDK v3 は Lambda ランタイムが同梱している。**`npm install` もバンドラも要らず、
zip は `.mjs` を固めるだけ。** 代償として SDK のバージョンは AWS 任せになる。

**テストは署名検証だけ書く。** この関数は**壊れても正常に見える**
(常に `true` を返しても Slack からの通信は通る)ので、実機で確かめられない。
`node:test` で固定し、`pipeline-apply.yml` がデプロイ前に走らせる。
メッセージの見た目は Slack を見れば分かるのでテストしない。

### 7. S3 の置き場を 2 つに分ける

zip も `pipeline.yml` も S3 を経由するが、**別のバケットに置く。**

```
s3://nuxt-java-practice-lambda-artifacts-<アカウントID>/slack-approval/<ハッシュ>   ← zip
s3://nuxt-java-practice-cfn-templates-<アカウントID>/templates/<ハッシュ>            ← テンプレート
```

**分ける理由は、保存要件が正反対だから。**

| | テンプレート | Lambda の zip |
|---|---|---|
| CloudFormation が持つもの | **中身のコピー**(`aws cloudformation get-template` で読める) | **在り処だけ**(`S3Bucket` / `S3Key`) |
| S3 の実体が消えると | 何も起きない | **ロールバックが失敗する** |
| あるべき設定 | 30 日で削除 | **削除しない** |

**Lambda 関数そのものは作成時に zip のコピーを持つ**ので、S3 のオブジェクトを消しても
稼働中の関数は動き続ける。**壊れるのは CloudFormation がロールバック・置換をするとき** ——
前のテンプレートが持つ古い `S3Key` をもう一度取りに行くため、そこに実体が無いと失敗する。

**同居させると「静かに壊れる」。** 1 つのバケットにプレフィックスで同居させることもできるが、
将来ライフサイクルを触ったとき(`Prefix` を外す、バケット全体に一括ルールを足す)
**zip が消えても誰も気づかない。** 気づくのはロールバックが必要になった最悪のタイミング。
**バケットが分かれていれば、そもそも同じ設定が届かない。**

専用バケットの設定は 3 つとも「消さない」に寄せてある。

- **ライフサイクルを一切設定しない。** `package` はオブジェクト名を中身のハッシュにし、
  同名があればアップロードを飛ばすので、増えるのは**コードを変えたときだけ**。
  数 KB なので溜めても実害が無い。**削除ルールを書かないこと自体が設定として意味を持つ**
- **バージョニングも有効にしない。** ハッシュ名なので同じキーが上書きされることがなく、守る対象が無い
- **`pipeline-destroy.yml` では触らない。** スタックの外にある常駐リソースであり、
  かつ **stg と prod で共用している**ので、片方の撤収がもう片方のロールバックを壊す

`gha-cfn-stg` には `PutLambdaCode`(`s3:PutObject` / `s3:GetObject`)を足す。
**`s3:DeleteObject` は入れない** —— 消せる権限を持たせないこと自体が設定になる。
`GetObject` が要るのは、`package` がアップロード前に `HeadObject` で存在確認するため。

**`pipeline.yml` を S3 経由にしたのは統一性のため。** 40,131 バイトで上限 51,200 に
収まるので技術的な必要は無い。それでも揃えるのは、`app.yml` と渡し方が違う理由が
説明しづらいことと、**上限に触れた日に `DeployBucketRequiredError` で足を止めない**ため
(残り 11 KB。日本語コメントは 1 文字 3 バイトなので減りが速い)。
**`create-change-set` 方式には寄せない** —— 差分表示と Replacement ガードは
RDS も ECS も無い `pipeline.yml` には意味が無く、ADR-0009 の読み替えはそのまま維持する。

### 8. Slack のアプリ枠を 1 つ使う

無料プランは 10 個まで。Amazon Q Developer(アラート用に残る)と合わせて **2/10**。

## 検討したが採らなかった選択肢

- **Chatbot のカスタムアクションで 2 手運用を続ける** — 実機で成立することは確認済みで、
  コードを 1 行も持たずに済む。採らなかったのは、**既製ボタンが消せず、
  カスタムアクションが全通知に付く**問題が残るから。承認者をアプリ開発担当に広げるという
  目的に対して、この 2 つは直接の障害になる

- **SSM Automation runbook** — カスタムアクションの 3 つ目の型。`aws:executeAwsApi` を
  複数ステップ繋げられるので、トークン取得と承認を 1 つのボタンにまとめられる。
  **宣言的なドキュメントなのでアプリコードにあたらず、ADR-0011 の原則を保ったまま
  1 クリックにできる唯一の道**だった。採らなかったのは費用対効果 —— SSM ドキュメントと
  Automation 用ロールでテンプレートが 40〜60 行伸び、`GuardrailPolicies` に
  `ssm:StartAutomationExecution` と `iam:PassRole` を足すことになる。
  **1 人で使う環境で「2 手が 1 手になる」ための投資として重い。**
  そして今回の主目的(会社の構成を学ぶ)には繋がらない

- **承認アクションの `NotificationArn` → SNS** — 承認トークンが載る古い経路で、
  世の中の記事はほぼこれを使っている。採らなかったのは、**CodeStarNotifications に
  一本化するほうが通知の入口が 1 つで済む**から。トークンは Lambda が押下時に
  `get-pipeline-state` で取れば足りる。むしろ**押下時に取るほうが正しい** ——
  通知に埋め込むと `SUPERSEDED` で実行が入れ替わったとき古いトークンを持ち続ける

- **SAM** — 当初は「別ファイル・別スタックになる」と誤解していたが、実際は
  `Transform` を 1 行足すだけで**同じファイル・同じスタック**に書ける。`sam` CLI すら
  必須ではない(`aws cloudformation package` で処理できる)。採らなかったのは、
  `AWS::Serverless::Function` が **IAM ロールやロググループを暗黙に生成する**から。
  IAM ポリシーの 1 文ごとに理由をコメントで残してきた `pipeline.yml` の書き方と逆行する。
  `Role:` を明示すれば避けられるが、そうすると SAM を使う意味が薄れる。
  `sam local` も、署名検証とコールバックが絡む以上どのみち実機確認になる

- **`Code.ZipFile`(テンプレート直書き)** — CloudFormation 管理外の S3 オブジェクトが
  ゼロになる。採らなかったのは **4,096 文字制限**と、テストも lint もできないこと。
  署名検証を YAML に埋め込むのは「会社の構成を学ぶ」という動機と逆方向

- **API Gateway で受ける** — WAF とレート制限を付けられる。採らなかったのは、
  Slack が要求するのは POST を受ける URL 1 本だけで、API + ルート + 統合 + 権限と
  リソースが増えるわりに得るものが少ないため。Function URL は追加課金も無い

- **bot トークン方式** — `chat.postMessage` / `chat.update` が使えるので、
  コンソール承認やタイムアウトのときもボタンを消せる。採らなかったのは、
  **漏れたときの被害が webhook URL より大きい**から(任意のチャンネルに投稿できる)。
  投稿先は `#njp-deploy` 1 つだけなので、チャンネル固定は制約にならない

- **アラート 2 本も Lambda に寄せる** — 見た目が揃い、Chatbot 依存が完全に消える。
  採らなかったのは、**アラートの通知経路が `app.yml`(作り捨てスタック)にある**から。
  Lambda を持ち込むと環境を建て直すたびに作り直しになり、zip を S3 に上げる処理を
  `cfn-apply.yml` 側にも書くことになる。承認用の Lambda は `pipeline.yml`(常駐)に
  置けるので建て直しの影響を受けない。**この非対称性がある以上、揃えたいという理由だけでは割に合わない**

- **承認できる Slack ユーザーを Lambda で絞る** — 「Slack には入れるが承認はさせたくない人」に
  対応できる。採らなかったのは、そういう人はチャンネルに入れなければよいだけだから。
  Chatbot 方式の `UserRoleRequired: false` と同じ強度を維持する。
  そもそも**承認の担い手を広げる**のが今回の目的なので、AWS 側で絞る方向は逆行する
