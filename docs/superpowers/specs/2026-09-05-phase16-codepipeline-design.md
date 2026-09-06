# フェーズ16: CodePipeline / CodeBuild / CodeDeploy によるデプロイ

日付: 2026-09-05
ステータス: 実装済み(実機未検証)

方針 → [ADR-0012](../../adr/0012-deploy-method-per-branch.md)(ブランチ分割)、
[ADR-0013](../../adr/0013-app-deploy-with-code-services.md)(Code 系への移行)

## 1. 目的

会社で使われていた **CodePipeline + CodeBuild + CodeDeploy によるデプロイ**を、このリポジトリで再現して学ぶ。
あわせて「デプロイの途中で Slack に通知し、Slack 上で承認が下りたら実際にデプロイする」形を成立させる。

## 2. いま何が動いているか

| ワークフロー | 役割 |
| --- | --- |
| `ecr-push.yml` | イメージをビルドして ECR に push |
| `cfn-deploy.yml` | 構築(5 段: deploy-zero → create-db-users → migrate → deploy-service → summary) |
| `cfn-apply.yml` | スタックの反映(CloudFormation を叩く唯一の場所 → ADR-0009) |
| `cfn-destroy.yml` | 撤収 |
| `db-task.yml` | ECS Run Task(create-db-users / migrate / 任意 SQL) |

ECS サービスは **ネイティブ Blue/Green**(`DeploymentConfiguration.Strategy: BLUE_GREEN`)で、
リリースは **CloudFormation のスタック更新**として行われる(ADR-0007)。

## 3. 制約として確定した事実

設計中に一次情報で確かめたもの。すべて設計を左右した。

### 3-1. CODE_DEPLOY 制御のサービスはタスク定義を CloudFormation から更新できない

ECS の `UpdateService` API は、CODE_DEPLOY 制御のサービスに対して
**desired count / deployment configuration / health check grace period / 配置制約 / タグしか受け付けない。**

> Invalid request provided: Unable to update task definition on services with a CODE_DEPLOY deployment controller. Use AWS CodeDeploy to trigger a new deployment.

→ `Service.TaskDefinition` を `!Ref AppTaskDefinition`(リビジョン付き ARN)のままにすると、
`ImageTag` を変えた瞬間にスタック更新が必ず落ちる。**family 名の固定文字列にするしかない。**
これは ADR-0007 が「採らなかった選択肢」の 1 番目そのものである。

出典: [UpdateService](https://docs.aws.amazon.com/AmazonECS/latest/APIReference/API_UpdateService.html) /
[aws-cdk#23564](https://github.com/aws/aws-cdk/issues/23564) / [aws-cdk#36012](https://github.com/aws/aws-cdk/issues/36012)

### 3-2. CodeDeploy はリスナーの既定アクションしか切り替えられない

`TrafficRoute.ListenerArns` の定義は「**The Amazon Resource Name (ARN) of one listener.**」で、
**リスナールール ARN は取れない。** AWS 自身が両者の差を明記している。

> CodeDeploy requires separate listeners for different services and for production and test endpoints,
> **ECS blue/green operates at the listener rule level**, which means that you can benefit from using a
> single listener with advanced request routing based on host name, HTTP headers, path, method, query string or source IP.

→ 現行の「既定は 403 fixed-response / ホスト名一致のルール(priority 100)だけが blue/green へ forward」構成は成立しない。
残したまま繋ぐと**デプロイは成功するのにトラフィックが一切切り替わらない**(ルールが既定より先に評価されるため)。

出典: [TrafficRoute](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-properties-codedeploy-deploymentgroup-trafficroute.html) /
[移行ブログ](https://aws.amazon.com/blogs/containers/migrating-from-aws-codedeploy-to-amazon-ecs-for-blue-green-deployments)

### 3-3. `CodeDeployToECS` アクションは `taskdef.json` を必須で要求する

`TaskDefinitionTemplateArtifact` は **Required: Yes**。タスク定義の新リビジョンを register するのは
CodeBuild ではなく **Deploy アクション自身**で、appspec の `TaskDefinition: <TASK_DEFINITION>` を
生成した ARN で置換する。

出典: [ECS/CodeDeploy blue-green アクションリファレンス](https://docs.aws.amazon.com/codepipeline/latest/userguide/action-reference-ECSbluegreen.html)

### 3-4. パイプライン作成時の初回実行は無効化できない

`DetectChanges: false` は以後の変更検知を止めるだけで、作成時の 1 回は `CreatePipeline` トリガーとして必ず走る。
→ 手動承認ステージがそのまま安全弁になる(承認しなければ Deploy に進まない)。

出典: [re:Post](https://repost.aws/questions/QUPaolaMjTSGyRmNHdiNZcMQ/prevent-codepipeline-from-executing-on-create) /
[Start a pipeline in CodePipeline](https://docs.aws.amazon.com/codepipeline/latest/userguide/pipelines-about-starting.html)

### 3-5. `Triggers` を書くと既定の変更検知は無効になる

> When a trigger configuration is specified, default change detection for repository and branch commits is disabled.

→ フィルタが唯一の自動起動条件になる。手動の `StartPipelineExecution` はこれとは無関係にいつでも叩けるので、
**「フィルタに合う push で自動 + いつでも手動」が両立する。** 必要なのは `PipelineType: V2`。

### 3-6. CodeBuild のローカルキャッシュはビルドが稀だと当たらない

> Local caching stores a cache locally on **a build host that is available to that build host only**. ...
> **This is not the best option if your builds are infrequent.**

Docker レイヤーキャッシュは Linux 限定で `privileged` 必須。**CodeBuild を VPC に入れると使えない。**

出典: [Local caching](https://docs.aws.amazon.com/codebuild/latest/userguide/caching-local.html)

### 3-7. ECS の appspec の Hooks は Lambda でしか実装できない

そして CodeDeploy の ECS Blue/Green には**デプロイ途中で人を待つ仕組みが無い**。
待ち時間はデプロイ設定に埋め込まれた固定値で、人が「よし」と言うまで止める口は Lambda フックしかない。
→ 今回は Hooks を使わないので **Lambda はゼロのまま**(ADR-0011 の判断を維持できる)。

## 4. 決定

### 決定1 デプロイ方式はブランチで分ける

`main` = Code 系、`github-actions-deploy` = 現行の GitHub Actions 方式(**凍結**)。
→ [ADR-0012](../../adr/0012-deploy-method-per-branch.md)

当初は `app.yml` に `DeploymentControllerType` パラメータを持たせて `Fn::If` で切り替える案だったが、
分岐が `DeploymentController` / `DeploymentConfiguration` / `LoadBalancers[].AdvancedConfiguration` の
3 プロパティに広がるため取りやめた。作り捨て運用なので「作成時に選ぶ」で足りるところまでは正しく、
そこからさらに「ブランチで分ければテンプレートに分岐が要らない」に進んだ。

### 決定2 Code 系が担うのはアプリのデプロイだけ

構築(`cfn-deploy.yml`)・撤収(`cfn-destroy.yml`)・DB タスク(`db-task.yml`)は GitHub Actions のまま。
`terraform/` の参考リポジトリと同じ「インフラは IaC、アプリのデプロイは別系統」の分担になる。

### 決定3 ECS サービスを `DeploymentController: CODE_DEPLOY` にする

ネイティブ Blue/Green(`Strategy: BLUE_GREEN` / `BakeTimeInMinutes` / `DeploymentCircuitBreaker` /
`LoadBalancers[].AdvancedConfiguration`)はすべて外す。`EcsInfrastructureRoleForLoadBalancers` も不要になる。

### 決定4 `Service.TaskDefinition` は family 名の固定文字列にする

`!Sub ${ProjectName}-${EnvName}-app`。3-1 の制約から他に選択肢が無い。
`!Ref` をやめると CloudFormation が依存を推論できないので **`DependsOn: AppTaskDefinition` を明示する。**

### 決定5 `taskdef.json` と `appspec.yaml` を Git に置く

`CodeDeployToECS` アクションが要求する形(3-3)であり、会社で使われていた形でもある。
プレースホルダに差し込む値は CodeBuild が `describe-stacks` で引く。

**Outputs はほぼ既存のもので足りる。**

| `taskdef.json` に要る値 | 入手方法 |
| --- | --- |
| `DB_HOST` | Outputs の `DbEndpoint`(既存) |
| `IMAGE_CDN_DOMAIN` | Outputs の `ImageCdnDomain`(既存) |
| `S3_BUCKET` | `${ProjectName}-${EnvName}-images` で予測可能 |
| `ExecutionRoleArn` / `TaskRoleArn` | `RoleName` が `${ProjectName}-${EnvName}-task-execution-role` / `-task-role` |
| SSM の ARN | `SsmParameterPath` が `params/<env>.json` にある |
| `DB_PORT` | 唯一 Output が無い。3306 固定にするか Output を 1 つ足す |

### 決定6 `app.yml` の初代タスク定義との二重管理は受け入れる

`AWS::ECS::Service` はタスク定義の指定が必須なので、`app.yml` から `AppTaskDefinition` を消せない。
結果として **同じ内容が `app.yml` と `taskdef.json` の 2 か所にある。**

片方だけ直すと「構築直後の初回起動だけ古い定義で立ち上がってクラッシュする」という気づきにくい壊れ方をする。
`app.yml` 側に「**これは初回構築でだけ使われる定義。変えるときは `taskdef.json` も必ず揃える**」と
コメントを書き、ADR-0013 にも規律として記録する。

### 決定7 migrate 用タスク定義も Git に置き、`db-task.yml` は family を参照する

**現行方式にはこの問題が無い。** `app.yml:1215`(アプリ)と `app.yml:1276`(migrate)はどちらも同じ
`!Ref ImageTag` からイメージ URI を組み立てるので、`cfn-apply.yml` にタグを渡せば 2 本が必ず一緒に新しくなる。
**タスク定義の所有者が 1 つだから連動していた。**

アプリ側だけ外に出すと連動が切れ、`db-task.yml` は Outputs の `MigrateTaskDefinition`
(= `!Ref` のリビジョン固定 ARN)を読むので、**構築時の古いイメージで Flyway が走る。**
`ecs run-task` の `containerOverrides` に `image` は無いので回避できない。

したがって:

- `taskdef-migrate.json` を Git に置き、CodeBuild が `register-task-definition` する
  (`CodeDeployToECS` アクションは migrate の面倒を見ないので、こちらは CodeBuild が自分でやる)
- `app.yml` の Outputs `MigrateTaskDefinition` をリビジョン固定 ARN から **family 名**に変える
- `db-task.yml` は family を `run-task` する(リビジョン省略 = 最新 ACTIVE)

`DbOpsTaskDefinition` はアプリのイメージを使っていない(`ImageTag` を参照しているのは 2 つだけ)ので触らない。

**「Build → migrate → 承認 → デプロイ」の順序が自然に作れる**という副次的な利点がある。
現行方式は通常更新でタスク定義の更新とサービスのロールアウトが同一スタック更新なので、
migrate をアプリより先に流せない(構築フローだけが 5 段に分けて順序を作っている)。

### 決定8 パイプラインは常駐の別スタック `cloudformation/pipeline.yml` に置く

当初は `app.yml` の中(作り捨て)に置く案だったが、**鶏と卵**が生じるので取りやめた。
パイプラインがスタックの中にあると、スタックを作るのに必要な最初のイメージを作る手段が無くなる。

実務でもパイプラインは IaC 管理するが、**アプリのスタックとは別に置く**のが定番である。理由は 3 つ。

1. ライフサイクルが違う(環境を作り直してもパイプラインは残るべき)
2. 自己参照になる(パイプラインが自分の入っているスタックを更新する形は避けたい)
3. CI/CD 基盤をツーリングアカウント、デプロイ先を dev/stg/prod の別アカウントに置く構成が多い

ADR-0011 の Chatbot を作り捨て側に置いた前例は当てはまらない。Chatbot は「通知の受け口」で
ライフサイクルが SNS トピックに従属していたが、**パイプラインは環境より長生きすべきもの**である。

### 決定9 CodeDeploy の Application と DeploymentGroup は `app.yml` に残す

`AWS::CodeDeploy::DeploymentGroup` は **ALB のリスナー ARN と ECS サービスを参照する。**
リスナー ARN は生成 ID を含むので命名規則では組み立てられない。

一方、パイプラインの Deploy アクションが必要とするのは `ApplicationName` と `DeploymentGroupName` という
**文字列だけ**で、これは `${ProjectName}-${EnvName}-...` で予測できる。

→ **`Export` / `ImportValue` は使わない。** ADR-0007 が退けた「エクスポート元は参照側が消えるまで削除できない」
問題も起きず、撤収運用と衝突しない。

| スタック | 中身 |
| --- | --- |
| `pipeline.yml`(常駐) | CodePipeline / CodeBuild / アーティファクト用 S3 / IAM ロール / 承認用 Chatbot 設定 / NotificationRule |
| `app.yml`(作り捨て) | CodeDeploy の Application + DeploymentGroup / ECS サービス / ALB / アラート用 Chatbot 設定 2 本 |

### 決定10 CodeStar Connections は手動の常駐リソースにする

`AWS::CodeStarConnections::Connection` は CloudFormation で作れるが、**作った直後は `PENDING` で、
AWS コンソールで GitHub との握手を人がやるまで `AVAILABLE` にならない。**
ADR-0011 の Slack ワークスペース認可とまったく同じ形である。

`pipeline-destroy.yml` を用意する以上、接続をスタックに入れると**建て直すたびに握手をやり直す**ことになる。
しかも `PENDING` のままでもスタック作成は成功するので、ADR-0011 が嫌っていた「緑なのに機能していない」形になる。

→ コンソールで 1 回作り、ARN を `pipeline.yml` のパラメータで渡す。
ECR・ホストゾーン・テンプレートバケット・Slack ワークスペース認可に続く **5 つ目の常駐手動リソース。**

**ARN の渡し方だけ実装で変えた(→ 5-2)。** 当初は `params/pipeline-<env>.json` に平文で置くつもりだったが、
ARN は `arn:aws:codeconnections:<リージョン>:<アカウントID>:connection/<uuid>` の形で **AWS アカウント ID を含む。**
このリポジトリは public なので、IAM ロールの ARN と同じく GitHub の Environment secret
`AWS_CODESTAR_CONNECTION_ARN` に置き、`pipeline-apply.yml` が `--parameter-overrides` で足す。

### 決定11 `pipeline-apply.yml` と `pipeline-destroy.yml` を新設する

`pipeline.yml` の適用は GitHub Actions から行う(既に OIDC で CloudFormation を叩ける口がある)。
`aws cloudformation deploy` で済ませ、`cfn-apply.yml` は汎用化しない。

`pipeline.yml` には RDS も ECS も無くパラメータも少ないので、`cfn-apply.yml` が持つ
Replacement ガードや `WebDesiredCount` の前提チェックが意味を持たないため。
**ADR-0009 の範囲を「`app.yml` を叩くのは `cfn-apply.yml` だけ」と明確化する追記をする。**

`pipeline-destroy.yml` は **アーティファクト用 S3 バケットを空にしてから `delete-stack` する**
(`cfn-destroy.yml:83-110` の `empty_buckets` と同じ理由。空でないバケットがあると 15 分待たされた末に `DELETE_FAILED`)。

### 決定12 トリガーは V2 の `Triggers` + 手動起動の併用

```yaml
PipelineType: V2
Triggers:
  - ProviderType: CodeStarSourceConnection
    GitConfiguration:
      SourceActionName: Source
      Push:
        - Branches:
            Includes: [main]
          FilePaths:
            Includes:
              - backend/**
              - frontend/**
              - docker/**
              - .dockerignore     # context が `.` なのでイメージの中身に効く
              - buildspec.yml     # ビルド手順を直したら走らせたい
```

`cloudformation/**` は**入れない**。テンプレートの反映は `cfn-apply.yml` の仕事なので。

### 決定13 ステージは Source → Build → Approve → Deploy

承認時点で「何をデプロイするのか」がイメージタグとして確定しているのが利点。
パイプライン作成時の初回実行(3-4)もここで止まるので、`cfn-deploy.yml` の 5 段フローを追い越さない。

### 決定14 CodeDeploy は最小構成にする

`CodeDeployDefault.ECSAllAtOnce` / `TerminationWaitTimeInMinutes: 0` /
`AutoRollbackConfiguration` は `DEPLOYMENT_FAILURE` のみ。テストリスナーは作らない。

stg の `BakeTimeInMinutes: 0` と同じ振る舞いになる。段階的移行(Canary / Linear)と
CloudWatch アラーム連動(`DEPLOYMENT_STOP_ON_ALARM`)は**意図的に見送った**。
必要になれば `DeploymentConfigName` と `AutoRollbackConfiguration` を差し替えるだけで入る。

### 決定15 ALB は既定アクションを forward に変え、ホスト名防御はしない

- `HttpsListener` の `DefaultActions` を `fixed-response 403` から **`forward → TargetGroupBlue`** に変える
- `ProductionListenerRule` を**削除する**

これで失われる「想定ホスト名以外は 403」は**再現しない。** 理由:

- ALB のリスナールール条件(host-header / path-pattern / http-header / method / query-string / source-ip)は
  すべて「一致したら」で**否定形が書けない**。`*.elb.amazonaws.com` を弾く肯定形のルールは書けるが、
  **IP 直アクセスは `Host` が IP になるので拾えない**
- 完全にやるなら WAF の `NotStatement` しかないが、**実務でその目的だけに WAF を入れることはない**。
  真面目に塞ぐのは CloudFront を前段に置いている構成で、手段も WAF ではなくオリジンカスタムヘッダー検証や
  マネージドプレフィックスリスト。**このリポジトリに前段の CloudFront は無い**(CloudFront は画像専用)ので、
  迂回されて困るものが存在しない
- 証明書はクライアント側の検証にしかならない(ALB は SNI 不一致でもデフォルト証明書でハンドシェイクを完了する)が、
  **クローラは証明書エラーで入れない**のでインデックスもされない。SEO 上の実害も無い
- stg は Basic 認証(WAF)がある。prod はそもそも公開している

`app.yml` の ALB のところに**この判断の理由をコメントとして書き残す。**

### 決定16 キャッシュは `LOCAL_DOCKER_LAYER_CACHE`

```yaml
Cache:
  Type: LOCAL
  Modes: [LOCAL_DOCKER_LAYER_CACHE]
```

3-6 のとおり**このリポジトリの使い方ではほぼ当たらない**が、外部リソースも権限も増えないので入れておく。
**ビルド時間は 5〜8 分を前提に設計する。**

ECR にキャッシュ専用リポジトリを作って `type=registry,mode=max` を使えば確実に当たるが、
常駐リソースが 1 つ増えるので採らなかった(タグを上書きするため既存リポジトリには相乗りできず、
`IMMUTABLE` も外すことになる)。

### 決定17 イメージタグは短縮 SHA のまま、buildspec で存在チェックする

`CODEBUILD_RESOLVED_SOURCE_VERSION` の先頭 7 文字。ECR は `IMMUTABLE` なので同じコミットで 2 回回すと
push が必ず失敗する。`describe-images` で既にあればビルドと push を飛ばし、
`taskdef.json` のレンダリングだけ行ってデプロイに進む(`ecr-push.yml:49-66` と同じ考え方)。

イメージとコミットが 1 対 1 に保たれる性質を維持する。

### 決定18 Slack 承認は Chatbot、承認専用チャンネルを別に建てる

**既存の Chatbot 設定 2 本は `GuardrailPolicies: AWSDenyAll`** なので、そのままでは絶対に承認できない
(承認には `codepipeline:PutApprovalResult` が要る)。ADR-0011 は「通知の一方向だけだから権限ゼロ」と
意図的にそう決めたので、**そこは緩めず、承認専用の 3 本目を建てる。**

- チャンネル `#njp-deploy` を新設し、`/invite @Amazon Q` する(1 回きり)
- `AWS::CodeStarNotifications::NotificationRule` で **承認待ち + 失敗**のイベントを SNS → Chatbot に流す
- チャンネルロールと `GuardrailPolicies` は `codepipeline:PutApprovalResult` と
  `codepipeline:GetPipelineState` に絞ったカスタムポリシー
- ワークスペース ID は `params` にある既存の値を使う。チャンネル ID を 1 つ足す

**Lambda は要らない。** Chatbot は「チャットから AWS CLI コマンドを実行する」機能と
「通知にカスタムアクションボタンを付ける」機能を持つので、承認はコマンドかボタンで完結する。

なお **SNS の HTTPS 購読で Slack の Incoming Webhook を直接叩く構成は成立しない**(ADR-0011 参照)。
webhook でやるなら SNS → Lambda → webhook に加えて、Slack の interactivity を受ける
API Gateway + Lambda を用意して `put-approval-result` を叩くことになる。**採らない。**

### 決定19 `ecr-push.yml` を削除し、`cfn-apply.yml` の `image_tag` は `workflow_call` にだけ残す

- `ecr-push.yml` は CodeBuild に置き換わるので `main` から削除する
- `cfn-apply.yml` の `image_tag` は **`workflow_dispatch` から外し、`workflow_call` にだけ残す**

CODE_DEPLOY のサービスでは `Service.TaskDefinition` が固定文字列なので、`image_tag` を変えて
`cfn-apply.yml` を流しても**新しいリビジョンが register されるだけでデプロイはされない。**
UI から渡せると「タグを変えたのに反映されない」という混乱を生む。一方 `cfn-deploy.yml` からの
CREATE では省略できない(`UsePreviousValue` にできる前の値が無い)。

これは `web_desired_count` / `allow_missing_stack` / `allow_zero_desired_count` と**まったく同じ形**で、
「人間が UI から解除する手段が無いことが安全弁になる」という既存の設計をそのまま延長する。

### 決定20 ブートストラップは承認ステージで止める

`pipeline.yml` が常駐なので、**スタックが無い状態でもパイプラインは回せる。**

1. パイプラインを起動する。Source → Build が通り、**承認待ちで止まる**
   (承認しないので Deploy に行かず、CodeDeploy アプリが無くても失敗しない)
2. これで ECR にイメージができている
3. そのタグを指定して `cfn-deploy.yml` を流す
   (1 段目 `DesiredCount=0` → `create-db-users` → **migrate はイメージがあるので通る** → `deploy-service`)
4. 以降は普通にパイプラインを回す

**手動承認ステージがブートストラップの門を兼ねる。**

## 5. 変更するファイル

### `cloudformation/app.yml`

| 箇所 | 変更 |
| --- | --- |
| `HttpsListener` | 既定アクションを `fixed-response 403` → `forward TargetGroupBlue`。判断の理由をコメントで残す |
| `ProductionListenerRule` | 削除 |
| `Service` | `DeploymentController: {Type: CODE_DEPLOY}` を追加。`DeploymentConfiguration` と `LoadBalancers[].AdvancedConfiguration` を削除。`TaskDefinition` を family 文字列に。`DependsOn: AppTaskDefinition` |
| `EcsInfrastructureRoleForLoadBalancers` | 削除(ネイティブ Blue/Green 専用) |
| `BakeTimeInMinutes` | パラメータごと削除。`params/*.json` からも消す |
| `AppTaskDefinition` | 残す。「初回構築でだけ使われる」コメントを追加 |
| Outputs `MigrateTaskDefinition` | `!Ref` から family 名に変更 |
| 新規 | `AWS::CodeDeploy::Application` / `AWS::CodeDeploy::DeploymentGroup` / CodeDeploy 用 IAM ロール |
| 新規 Outputs | `DbPort`(3306 固定にするなら不要) |

### `.github/workflows/`

| ファイル | 変更 |
| --- | --- |
| `ecr-push.yml` | 削除 |
| `cfn-apply.yml` | `workflow_dispatch` の `image_tag` を削除 |
| `db-task.yml` | migrate のタスク定義を family 参照に変更 |
| `pipeline-apply.yml` | 新規 |
| `pipeline-destroy.yml` | 新規(アーティファクトバケットを空にしてから削除) |

### 新規ファイル

| ファイル | 中身 |
| --- | --- |
| `cloudformation/pipeline.yml` | CodePipeline / CodeBuild / アーティファクト S3 / IAM / 承認用 Chatbot 設定 / NotificationRule |
| `cloudformation/params/pipeline-<env>.json` | パラメータ(リポジトリ名・ブランチ名・Slack チャンネル ID など。**Connection ARN は入れない** → 5-2) |
| `buildspec.yml` | ビルド手順(タグ決定 → 存在チェック → build/push → taskdef レンダリング → migrate 用 register) |
| `appspec.yaml` | `TaskDefinition: <TASK_DEFINITION>` / ContainerName: app / ContainerPort: 8080 |
| `taskdef.json` | アプリのタスク定義(プレースホルダ入り) |
| `taskdef-migrate.json` | migrate のタスク定義(プレースホルダ入り) |

## 5-2. 実装で確定したこと(設計から動いた点)

| 項目 | 設計時 | 実装 | 理由 |
| --- | --- | --- | --- |
| パイプラインのスタック名 | 未定 | `nuxt-java-practice-<env>-pipeline` | アプリのスタックと並べたときに見分けが付く |
| `taskdef` / `appspec` の置き場 | 未定 | **リポジトリ直下** | `CodeDeployToECS` の既定パス(`taskdef.json` / `appspec.yaml`)に合わせた |
| CodeStar Connection ARN の渡し方 | `params/pipeline-<env>.json` に平文 | **GitHub の Environment secret `AWS_CODESTAR_CONNECTION_ARN`** | ARN にアカウント ID が入る。このリポジトリは public で、しかも params は `--parameter-overrides` に展開されるので Actions のログにも出る。Secret 経由なら両方 `***` にマスクされる(→ [github-secrets.md](../../infrastructure/github-secrets.md) §2-3) |
| 承認通知の経路 | NotificationRule → SNS → Chatbot | **NotificationRule → Chatbot(SNS なし)** | CodeStar Notifications は `TargetType: AWSChatbotSlack` で Chatbot を直接ターゲットにできる。SNS を挟んでいるアラート系は、CloudWatch アラームが SNS にしか送れないからで、こちらにその制約は無い |
| イメージのビルド方法 | buildx | **素の `docker build`** | `LOCAL_DOCKER_LAYER_CACHE` は Docker デーモンのレイヤーキャッシュで、buildx の `docker-container` ドライバは独自のキャッシュを持つため当たらない。`--provenance=false` も不要になった(素の `docker build` はアテステーションを付けない) |
| `taskdef.json` の値 | Git に固定値 | **構造だけ Git、値はスタックから差し込む** | プレースホルダ(`__DB_HOST__` など)を CodeBuild が `describe-stacks` の Outputs / Parameters で埋める。二重管理を「どの環境変数があるか」という構造だけに限定し、値のずれは起こさない |
| 埋め忘れの検出 | 設計になし | **残った `__...__` があれば Build を落とす** | 埋まらないまま register すると「`__DB_HOST__` に接続できない」という遠回りなクラッシュになる |
| `DbPort` | Output を足すか 3306 固定か | **Output を足した** | 他の値と同じく `describe-stacks` 一発で取れるほうが buildspec が単純になる |
| 初回構築時の Build | 設計になし | **スタックが無ければレンダリングと register を飛ばして成功で抜ける** | 決定20 のブートストラップを Build が明示的に扱う。飛ばしたことはログに出す |

### 引っかかりやすい罠を 1 つ、テンプレートに書き残した

`AWS::CodeDeploy::DeploymentGroup` のリファレンス冒頭には次の注意書きがある。

> Amazon ECS blue/green deployments through CodeDeploy do not use the `AWS::CodeDeploy::DeploymentGroup` resource.
> To perform Amazon ECS blue/green deployments, use the `AWS::CodeDeploy::BlueGreen` hook.

**これは「CloudFormation のスタック更新として ECS の Blue/Green デプロイを *実行* する」話であって、
デプロイグループを *作る* 話ではない。** ECS 用の `ECSServices` と
`LoadBalancerInfo.TargetGroupPairInfoList` というプロパティが存在すること自体がその証拠で、
CDK の `EcsDeploymentGroup` もこのリソースをそのまま合成する。
そして `AWS::CodeDeploy::BlueGreen` フックは、ADR-0007 が
「タスク定義の更新と他リソースの更新を同一スタック更新に混ぜられない」として退けたもの。

将来この注意書きを読んで不安になる読み手のために、`app.yml` の該当箇所にコメントとして残した。

## 6. 実測で覆りうる項目

実機で確かめて、この節を更新する。**0 番が一番危ない。**

0. **`cfn-deploy.yml` の 4 段目(`WebDesiredCount` を 0 → 1 に上げる更新)が通るか。**
   CODE_DEPLOY 制御のサービスに対して、CloudFormation の ECS ハンドラが `UpdateService` に
   `taskDefinition` を含めてしまうと、変更していなくても
   `Unable to update task definition on services with a CODE_DEPLOY deployment controller` で落ちる。
   `Service.TaskDefinition` は静的な文字列なので差分は出ないはずだが、ハンドラの実装は公開されていない。
   **落ちた場合の逃げ道**: 4 段目を CloudFormation ではなく `aws ecs update-service --desired-count` に
   置き換える(CODE_DEPLOY でも desired count の更新は許されている)。ただし ADR-0009 の
   「CloudFormation を叩くのは 1 か所」とは別に、ECS を直接叩く経路が 1 つ増えることになる。
1. **Chatbot が承認ボタンを自動で出すのか、カスタムアクションとして自分で作るのか。**
   コマンド(`@aws codepipeline put-approval-result ...`)で承認できることは確実だが、
   ボタンの出方は AWS ドキュメントの本文が取得できず未確定
2. **`GuardrailPolicies` をどこまで絞ると承認が通るか。** `PutApprovalResult` だけで足りるのか、
   `GetPipelineState` も要るのか
3. **`CodeDeployToECS` が register したタスク定義と、`app.yml` の初代の family が正しく揃うか**
4. **`describe-task-definition` の出力から落とすべき読み取り専用フィールドの正確な集合**
   (`taskDefinitionArn` / `revision` / `status` / `requiresAttributes` / `compatibilities` /
   `registeredAt` / `registeredBy`。決定5 では Git の taskdef.json を使うので直接は要らないが、
   migrate 用の register で触る可能性がある)
5. **`LOCAL_DOCKER_LAYER_CACHE` が実際に当たるかどうか**(当たらない前提で設計しているが、実測は残す)
6. **CodeBuild の compute type**(`BUILD_GENERAL1_SMALL` で `npm ci` + Gradle が現実的な時間で終わるか)
7. **`DisableInboundStageTransitions` を使わずに初回実行が承認待ちで綺麗に止まるか**
8. **`AWS::CodeDeploy::DeploymentGroup` で ECS のデプロイグループが実際に作れるか。**
   上の注意書きの読み方が正しいことは状況証拠で固めてあるが、実機では確かめていない
9. **`docker build`(buildx なし)で `LOCAL_DOCKER_LAYER_CACHE` が実際に効くか**
10. **`exported-variables`(`IMAGE_TAG` など)が CodeBuild のフェーズ跨ぎで期待どおり拾われるか。**
   フェーズごとにシェルが分かれるため、`/tmp/build.env` を経由して各フェーズで export し直している
