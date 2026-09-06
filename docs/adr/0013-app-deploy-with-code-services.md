# アプリのデプロイを Code 系に移し、タスク定義の所有権を CloudFormation の外に出す

日付: 2026-09-05
ステータス: accepted

**[ADR-0007](./0007-app-deploy-inside-cloudformation.md) を supersede する**(`main` ブランチにおいて。
`github-actions-deploy` ブランチでは ADR-0007 が引き続き有効 → [ADR-0012](./0012-deploy-method-per-branch.md))

**デプロイ承認の実現手段は [ADR-0014](./0014-slack-approval-with-lambda.md) で変更した**(下記「結果として生じること」6 の
「Slack 承認は Chatbot で行う」「Lambda は増えない」は覆っている。Code 系に移すという決定そのものは有効)

## 決定

`main` ブランチで、**アプリのイメージ更新を CodePipeline + CodeBuild + CodeDeploy で行う。**

- ECS サービスを **`DeploymentController: CODE_DEPLOY`** にする。ネイティブ Blue/Green は捨てる
- `Service.TaskDefinition` を **family 名の固定文字列**にする(`!Ref` をやめるので `DependsOn` を明示)
- **`taskdef.json` / `appspec.yaml` / `taskdef-migrate.json` を Git に置く。** タスク定義の 2 代目以降の正はこちら
- `app.yml` の `AppTaskDefinition` / `MigrateTaskDefinition` は**初回構築でだけ使われる定義**として残す
- **Code 系が担うのはアプリのデプロイだけ。** 構築 `cfn-deploy.yml` / 撤収 `cfn-destroy.yml` /
  DB タスク `db-task.yml` は GitHub Actions のまま
- **パイプラインは常駐の別スタック `cloudformation/pipeline.yml`。** CodeDeploy の Application と
  DeploymentGroup だけ `app.yml`(作り捨て)に残す
- **`Export` / `ImportValue` は使わない。** 2 つのスタックは命名規則で繋ぐ

設計と一次情報 → [フェーズ16 の設計書](../superpowers/specs/2026-09-05-phase16-codepipeline-design.md)

## 背景と理由

ADR-0007 は「アプリのデプロイを IaC の内側に置くか外に出すか」を設計判断として整理し、**内側**を選んだ。
理由は 3 つ(デプロイ頻度が低い / タスク定義がスタック出力に強く依存する / 外側の利点が小さい)。
**その分析は今も正しい。** 今回ひっくり返すのは、**会社で使われていた形を学ぶ**という別の目的が加わったから。

そして CodeDeploy を選んだ時点で、**内側に留まる道は技術的に閉じる。**

### ECS の API がタスク定義の更新を拒否する

CODE_DEPLOY 制御のサービスに対して `UpdateService` が受け付けるのは
**desired count / deployment configuration / health check grace period / 配置制約 / タグだけ。**

> Invalid request provided: Unable to update task definition on services with a CODE_DEPLOY deployment controller.
> Use AWS CodeDeploy to trigger a new deployment.

`Service.TaskDefinition` を `!Ref AppTaskDefinition` のままにすると、`ImageTag` を変えた瞬間に
タスク定義が新リビジョンになり、Service に新しい ARN が渡り、**スタック更新が必ず落ちる。**
family 名の固定文字列にするしかない。**これは ADR-0007 が「採らなかった選択肢」の 1 番目そのもの**であり、
ADR-0007 はそれを「タスク定義の所有者が 2 つになる」という理由で退けていた。今それを引き受ける。

### `CodeDeployToECS` アクションが `taskdef.json` を必須で要求する

`TaskDefinitionTemplateArtifact` は **Required: Yes**。新リビジョンを register するのは
Deploy アクション自身で、`appspec.yaml` の `TaskDefinition: <TASK_DEFINITION>` を生成した ARN で置換する。
CodeBuild が register して ARN を appspec に埋める、という形は取れない。

一時は「`taskdef.json` を CodeBuild が最新 ACTIVE から生成する」案も検討した
(スタック出力への依存を初代が運んでくれるので Outputs を増やさずに済む)。**採らなかったのは、
会社で使われていた形が「両方 Git に置く」だったから。** 学ぶ対象がそれである以上、そちらを取る。

実際に調べたところ、**必要な Outputs はほぼ揃っていた。** `DbEndpoint` も `ImageCdnDomain` も既にあり、
バケット名も IAM ロール名も命名規則で予測できる。Output が無いのは `DB_PORT` だけ。
ADR-0007 の理由 2(スタック出力への依存)が想定していたほどのコストは発生しなかった。

## 結果として生じること

### 1. タスク定義が 2 か所にある。これは回避できない

`AWS::ECS::Service` はタスク定義の指定が必須なので、`app.yml` から `AppTaskDefinition` を消せない。
`WebDesiredCount=0` で作る 1 段目でも要る。

**片方だけ直すと「構築直後の初回起動だけ古い定義で立ち上がってクラッシュする」という壊れ方をする。**
スタックは緑、パイプラインも緑、なのに最初のタスクだけ落ちる。

規律として次を守る。

> **`app.yml` の `AppTaskDefinition` / `MigrateTaskDefinition` は、初回構築でだけ使われる定義である。
> 環境変数・Secrets・CPU/メモリを変えるときは、`taskdef.json` / `taskdef-migrate.json` も必ず揃える。**

`app.yml` 側にも同じ趣旨のコメントを置く。

### 2. migrate 用タスク定義の連動が切れるので、明示的に繋ぎ直す

**現行方式にはこの問題が無い。** アプリと migrate のタスク定義はどちらも同じ `!Ref ImageTag` から
イメージ URI を組み立てるので、`cfn-apply.yml` にタグを渡せば 2 本が必ず一緒に新しくなる。
**所有者が 1 つだから連動していた。**

アプリ側だけ外に出すと、`db-task.yml` が Outputs から読む `MigrateTaskDefinition`
(= `!Ref` のリビジョン固定 ARN)が構築時のまま取り残され、**古いイメージで Flyway が走る。**
`ecs run-task` の `containerOverrides` に `image` は無いので回避できない。

したがって `taskdef-migrate.json` も Git に置いて CodeBuild が register し、Outputs を family 名に変え、
`db-task.yml` は family を `run-task` する(リビジョン省略 = 最新 ACTIVE)。

**副次的に、現行方式の弱点が 1 つ解ける。** 現行は通常更新でタスク定義の更新とサービスのロールアウトが
同一のスタック更新なので migrate をアプリより先に流せない(構築フローだけが 5 段に分けて順序を作っている)。
新方式では「Build → migrate → 承認 → デプロイ」の順序が自然に作れる。

### 3. ALB の構成が変わり、「想定ホスト名以外は 403」が失われる

**CodeDeploy はリスナーの既定アクションしか切り替えられない**(リスナールール ARN は取れない)。
現行の「既定は 403 / ホスト名一致のルールだけが forward」構成は、残したまま繋ぐと
**デプロイは成功するのにトラフィックが切り替わらない**という壊れ方をする。

そこで既定アクションを `forward → TargetGroupBlue` に変え、`ProductionListenerRule` を削除する。
失われる防御は**再現しない**。ALB のルール条件に否定形が無く、`*.elb.amazonaws.com` を弾く肯定形は書けても
IP 直アクセスを拾えないため。完全にやるなら WAF の `NotStatement` だが、
**実務でその目的だけに WAF を入れることはない**(真面目に塞ぐのは CloudFront が前段にある構成で、
手段もオリジンカスタムヘッダー検証やマネージドプレフィックスリスト)。**このリポジトリに前段の CloudFront は無い。**

判断の理由は `app.yml` の ALB のところにコメントとして残す。

### 4. 常駐の手動リソースが 1 つ増える

**CodeStar Connections。** CloudFormation で作れるが、作った直後は `PENDING` で、
コンソールで GitHub との握手を人がやるまで `AVAILABLE` にならない。**ADR-0011 の Slack ワークスペース認可と
まったく同じ形**で、しかも `PENDING` のままでもスタック作成は成功する。

`pipeline-destroy.yml` を持つ以上、スタックの中に入れると建て直すたびに握手をやり直すことになるので、
**手動の常駐リソースとして 1 回だけ作る。** ECR・ホストゾーン・テンプレートバケット・
Slack ワークスペース認可に続く 5 つ目。

### 5. ADR-0009 の「唯一の呼び出し元」の範囲が変わる

スタックが 2 つになるので、`pipeline.yml` を適用する `pipeline-apply.yml` が
`aws cloudformation` を叩く 2 か所目になる。ADR-0009 の趣旨(Change Set の差分表示・
Replacement ガード・前提チェックを 1 か所に集約する)は `app.yml` に対しては維持されるので、
**ADR-0009 の範囲を「`app.yml` を叩くのは `cfn-apply.yml` だけ」と読み替える追記をする。**

`pipeline.yml` には RDS も ECS も無く、失われるデータも無いので `aws cloudformation deploy` で足りる。

### 6. Lambda は増えない

ADR-0011 の「アプリケーション以外のコードを持ちたくない」は維持される。

- Slack 承認は **Chatbot** で行う(コマンドまたはカスタムアクションボタン)。承認専用チャンネルを
  1 つ足し、`GuardrailPolicies` を `codepipeline:PutApprovalResult` などに絞る。
  **既存のアラート用 2 本は `AWSDenyAll` のまま**
- appspec の Hooks(`AfterAllowTestTraffic` など)は ECS では Lambda でしか実装できないので**使わない**

代償として、**CodeDeploy には「デプロイ途中で人を待つ」仕組みが無い**ことを受け入れる。
待ち時間はデプロイ設定に埋め込まれた固定値で、承認はデプロイの前(Build と Deploy の間)にしか置けない。

### 7. デプロイの速さは変わらない、むしろ遅くなる

ADR-0007 が内側の欠点として挙げた「1 リリースあたりスタック更新 2〜5 分」は消えるが、
代わりに CodeBuild のフルビルド(5〜8 分)が毎回乗る。
**GitHub Actions のキャッシュ(`type=gha`)は CodeBuild からは使えない**(認証情報がランナーにしかない)。
`LOCAL_DOCKER_LAYER_CACHE` は入れるが、AWS 自身が「ビルドが稀なら向かない」と書いているとおり
このリポジトリではほぼ当たらない。**速さは目的ではないので受け入れる。**

## 検討したが採らなかった選択肢

- **パイプラインの最終段を CloudFormation デプロイアクションにする** — ADR-0007 をまるごと守れて、
  ネイティブ Blue/Green も残せる。CodePipeline / CodeBuild / 手動承認 / Slack も学べる。
  採らなかったのは、**CodeDeploy だけ学べない**から。学習目標の 3 分の 1 が消える

- **パイプラインを `app.yml` の中(作り捨て)に置く** — ADR-0011 で Chatbot のチャンネル設定を
  スタック内に置いたのと同じ論理。一度はこれを採ったが、**鶏と卵**で撤回した。
  パイプラインがスタックの中にあると、そのスタックを作るのに必要な最初のイメージを作る手段が無い。
  実務でもパイプラインは IaC 管理するが**アプリのスタックとは別に置く**のが定番で、
  理由(ライフサイクルが違う / 自己参照になる / アカウントを分ける)はこのリポジトリにも当てはまる。
  Chatbot は「通知の受け口」でライフサイクルが SNS トピックに従属していたが、
  **パイプラインは環境より長生きすべきもの**である

- **`taskdef.json` を CodeBuild が最新 ACTIVE から生成する** — 環境変数と ARN の二重管理が起きず、
  `describe-task-definition` で取った初代がスタック出力への依存を運んでくれる。
  技術的にはこちらのほうが安全だった。**採らなかったのは、会社で使われていた形が「両方 Git」だったから**

- **CodeDeploy に Canary / Linear と CloudWatch アラーム連動を入れる** — CodeDeploy らしさが一番出る。
  採らなかったのは、まず最小構成(`ECSAllAtOnce` / `TerminationWaitTime 0` /
  `DEPLOYMENT_FAILURE` のみ)で通すことを優先したため。`DeploymentConfigName` と
  `AutoRollbackConfiguration` を差し替えるだけで後から入る

- **ECR にキャッシュ専用リポジトリを作る**(`type=registry,mode=max`)— 確実に当たるキャッシュが得られ、
  「`mode=max` が必須」という既存の知見をそのまま活かせる。採らなかったのは、
  常駐リソースが 1 つ増えるため(タグを上書きするので既存リポジトリには相乗りできず、`IMMUTABLE` も外すことになる)

- **Slack 承認を SNS → Lambda → Incoming Webhook + API Gateway で作る** — Slack App の作り方、
  interactivity の受け口、署名検証、`put-approval-result` まで自分で書けるので学習価値は高い。
  採らなかったのは ADR-0011 の「アプリケーション以外のコードを持ちたくない」を反転させることになるため
