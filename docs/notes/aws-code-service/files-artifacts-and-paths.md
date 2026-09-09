# ファイル・アーティファクト・パス — Code 系サービスは何をどこから読むか

> **このノートは実機未検証。** フェーズ16 のパイプラインはまだ一度も回っていない
> (→ [implementation-progress.md](../../development/implementation-progress.md))。
> 公式ドキュメントとこのリポジトリのテンプレートの読み取りに基づく。
> 記述の確からしさを 3 段階で書き分ける。
>
> - **仕様** — 公式ドキュメントに明記がある(原文と URL を引く)
> - **推定** — 仕様から導いたが、そのものの明記は見つけていない
> - **未検証** — 実機で確かめる

方針 → [ADR-0013](../../adr/0013-app-deploy-with-code-services.md)、
設計 → [フェーズ16 の設計書](../../superpowers/specs/2026-09-05-phase16-codepipeline-design.md)。
ロールは [roles.md](roles.md)、環境変数とログは [env-vars-and-logs.md](env-vars-and-logs.md)。

テンプレートの箇所はリソース名とプロパティ名で指す。**行番号は使わない**
(過去に `app.yml` の行番号参照が一週間でズレて修正が要ったため)。

---

## 0. このノートの言葉

紛らわしい語が 3 つある。先に固定する。

| 語 | このノートでの意味 |
|---|---|
| **アーティファクト** | ステージ間を S3 経由で渡る zip。**Docker イメージのことではない。** イメージは ECR に置かれ、アーティファクトには入らない。`SourceArtifact` のような名前は zip に付けたあだ名で、**置き場のことではない**(→ §3-2) |
| **ソースルート** | CodeBuild がソースを展開した先。環境変数 `CODEBUILD_SRC_DIR` が指す。**リポジトリ直下と同じとは限らない**(→ §4) |
| **タスクセット(Task Set)** | CodeDeploy が green 側に作るタスクの束。ECS サービスの中に blue と green の 2 つが並ぶ |

「リビジョン」も 2 系統ある。**タスク定義のリビジョン**(`:12` のような連番)と、
**CodeDeploy のアプリケーションリビジョン**(デプロイ 1 回分の入力)は別物。

---

## 1. 名前の規則 — 固定・既定・任意

**「その名前じゃないと動かないのか」は 3 種類に分かれる。**

| 名前 | 区分 | パスを書かないとどこが読まれるか | 変えるなら |
|---|---|---|---|
| `buildspec.yml` | **既定** | **ソースルート直下**の `./buildspec.yml` | `AWS::CodeBuild::Project` の `Source.BuildSpec` |
| `taskdef.json` | **既定** | **`TaskDefinitionTemplateArtifact` に指定したアーティファクトのルート直下**の `./taskdef.json` | Deploy アクションの `TaskDefinitionTemplatePath` |
| `appspec.yaml` | **既定** | **`AppSpecTemplateArtifact` に指定したアーティファクトのルート直下**の `./appspec.yaml` | Deploy アクションの `AppSpecTemplatePath` |
| `<TASK_DEFINITION>` | **固定** | —(ファイルではなく `appspec.yaml` の中の文字列) | **変えられない** |
| `imageDetail.json` | **固定** | ECR ソースアクションが出力したアーティファクトのルート直下 | 変えられない(このリポジトリは使っていない → §6) |
| `<IMAGE1_NAME>` | **任意** | —(ファイルではなく `taskdef.json` の中の文字列) | `Image1ContainerName` に書いた名前がそのままプレースホルダ名になる |
| `taskdef-migrate.json` | **任意** | **—(そもそも誰も探さない。既定という概念が無い)** | AWS は関知しない。`buildspec.yml` が `file://` で読むだけ |
| `SourceArtifact` / `BuildArtifact` | **任意** | — | テンプレート内で字面が揃っていればよい |
| `Source` / `Build` / `Approve` / `Deploy`(ステージ名・アクション名) | **任意** | — | ただし `Triggers` の `SourceActionName` はソースアクション名と一致必須 |
| `__DB_HOST__` などの二重アンダースコア | **任意** | — | このリポジトリの `buildspec.yml` が `sed` で置換しているだけ。AWS の機能ではない |

### 1-1. 既定名は「規約」ではない

**仕様** buildspec について、公式はこう書いている。

> If you include a buildspec as part of the source code, by default, the buildspec file
> must be named `buildspec.yml` and placed in the root of your source directory.
>
> You can override the default buildspec file name and location.
>
> — [Buildspec file name and storage location](https://docs.aws.amazon.com/codebuild/latest/userguide/build-spec-ref.html)

**仕様** `taskdef.json` / `appspec.yaml` も同じく既定値。

> **TaskDefinitionTemplatePath** / Required: No /
> The default file name is `taskdef.json`. ... If the path is not the default, enter the path and file name.
>
> **AppSpecTemplatePath** / Required: No / The default file name is `appspec.yaml`.
>
> — [CodeDeployToECS アクションリファレンス](https://docs.aws.amazon.com/codepipeline/latest/userguide/action-reference-ECSbluegreen.html)

**既定は「名前」ではなく「名前 + 場所」で決まっている。** 引用が
`must be named buildspec.yml` と `placed in the root of your source directory` を
セットで書いているとおりで、名前が合っていてもルート直下に無ければ既定では見つからない。
**推定** 探すのはそのルート直下だけで、サブディレクトリを再帰的に見には行かない
(「root に置け、さもなくば上書き指定しろ」という書き方からの読み)。

| ファイル | パスを書かないと探される場所 | 「ルート」が何を指すか(→ §4) |
|---|---|---|
| `buildspec.yml` | `./buildspec.yml` | **ソースルート**(`CODEBUILD_SRC_DIR`)。リポジトリ直下とは限らない(→ §4-1) |
| `taskdef.json` | `./taskdef.json` | **`TaskDefinitionTemplateArtifact` に指定したアーティファクトを展開したルート**(→ §4-3) |
| `appspec.yaml` | `./appspec.yaml` | **`AppSpecTemplateArtifact` に指定したアーティファクトを展開したルート**(→ §4-3) |

**3 つとも字面は「ルート直下」だが、基準が違うので指す先も違う。**
`taskdef.json` / `appspec.yaml` の既定はソースの中ではなく、
そのアクションに渡したアーティファクトの中を見る。
**推定** このリポジトリは `BuildArtifact` を渡しているので、
仮にパスを省いたら「`buildspec.yml` の `artifacts.files` が
`taskdef.json` を階層無しで詰めた場合だけ当たる」ことになる(→ §5-2)。

**このリポジトリでは** 4 ファイルを `deploy/` にまとめたので、既定から外れた分を全部明示している。
`pipeline.yml` の `BuildProject` の `Source.BuildSpec` と、`Pipeline` の Deploy アクションの
`TaskDefinitionTemplatePath` / `AppSpecTemplatePath` がそれ。
理由は設計書の実装差分表(「`buildspec` / `taskdef` / `appspec` の置き場」の行)。

### 1-2. `<TASK_DEFINITION>` だけは本当に固定

**仕様** これは既定値ではなく、その文字列でなければ動かない。

> For the value of the `TaskDefinition` field, the placeholder text **must be** `<TASK_DEFINITION>`.
> The `CodeDeployToECS` action replaces this placeholder with the actual ARN of the
> dynamically generated task definition.

`deploy/appspec.yaml` の `TaskDefinition: "<TASK_DEFINITION>"` を別の名前にすると、
置換されないまま CodeDeploy に渡り、タスク定義 ARN として解釈できずに落ちる。

**ここが逆転している点に注意。** `<IMAGE1_NAME>` は名前が固定ではなく、
`Image1ContainerName` に書いた値がそのままプレースホルダ名になる。

> if you set *IMAGE1_NAME* as Image1ContainerName parameter, you should specify the
> placeholder *<IMAGE1_NAME>* as the value of image field in your task definition file.

つまり **appspec 側は固定・taskdef 側は任意**。同じ `<...>` の見た目でも性質が違う。

### 1-3. `appspec.yml` でなければならないのは EC2/オンプレだけ

**仕様** CodeDeploy の AppSpec リファレンスは、ファイル名を縛る記述をコンピュートプラットフォーム別に書いている。

> The AppSpec file for an **EC2/On-Premises** deployment must be named `appspec.yml` ...
> it must be placed in the root of the directory structure of an application's source code.
> Otherwise, deployments fail.
>
> — [AppSpec file reference](https://docs.aws.amazon.com/codedeploy/latest/userguide/reference-appspec-file.html)

**仕様** ここでの `appspec.yml` は §1 の表で言う「既定」ではなく「固定」。
`must be named` と `Otherwise, deployments fail` がそう書いている。
**EC2/オンプレでは名前を変える手段そのものが無い。**
CodePipeline の `AppSpecTemplatePath` は `CodeDeployToECS` アクションのキーであって(→ §1-4)、
EC2 向けの `CodeDeploy` プロバイダーにはこのキーが無い。
縛られているのは 3 つ全部で、**名前(`appspec`)・拡張子(`.yml`)・置き場(ルート直下)**。
`appspec.yaml` と書けば別名なので「AppSpec ファイルが無い」扱いになり、デプロイが落ちる。

**推定** ECS 用の節にはこの規定が無く、代わりに CodePipeline 側が `AppSpecTemplatePath` を
持っている。したがって ECS では名前も場所も自由で、このリポジトリのように
`deploy/appspec.yaml` に置ける。**拡張子が `.yaml` なのも問題にならない。**

**リファレンスに `.yml` と出てくるのは EC2/オンプレの節だけ**で、そこでの `.yml` は
「そう書かないと動かない」意味の `.yml`。ECS の話に持ち込む理由は無い
(逆に言えば、EC2 のつもりで `appspec.yaml` と書いたら落ちる、という向きの注意でもある)。

### 1-4. `Configuration` のキー名はプロバイダーが決める語彙

**アクション宣言は 2 層に分かれている。** 外側は全プロバイダー共通、
`Configuration` の中だけがプロバイダー固有。

```yaml
- Name: Deploy
  ActionTypeId:
    Provider: CodeDeployToECS   # ← これが Configuration の語彙を決める
  Configuration:                # ← ここの中だけプロバイダー固有
    TaskDefinitionTemplateArtifact: BuildArtifact
    ...
  InputArtifacts:               # ← ここから外は全プロバイダー共通
  OutputArtifacts:
  RunOrder: 1
  Namespace: BuildVariables
```

**同じパイプラインの中でも `Configuration` の語彙は毎回変わる。**

| アクション | Provider | 書けるキー |
|---|---|---|
| Source | `CodeStarSourceConnection` | `ConnectionArn` / `FullRepositoryId` / `BranchName` ほか |
| Build | `CodeBuild` | `ProjectName` / `EnvironmentVariables` ほか |
| Approve | `Manual` | `CustomData` / `NotificationArn` / `ExternalEntityLink` |
| Deploy | `CodeDeployToECS` | 下の 8 個 |

**仕様** `CodeDeployToECS` が受け付けるのは 8 個だけで、**キー名は固定・値は任意**。

| キー | 必須 | 既定値 |
|---|---|---|
| `ApplicationName` | **Yes** | — |
| `DeploymentGroupName` | **Yes** | — |
| `TaskDefinitionTemplateArtifact` | **Yes** | — |
| `AppSpecTemplateArtifact` | **Yes** | — |
| `TaskDefinitionTemplatePath` | No | `taskdef.json` |
| `AppSpecTemplatePath` | No | `appspec.yaml` |
| `Image<Number>ArtifactName` | No | — |
| `Image<Number>ContainerName` | No | — |

— [CodeDeployToECS アクションリファレンス](https://docs.aws.amazon.com/codepipeline/latest/userguide/action-reference-ECSbluegreen.html)

**このリポジトリが 4 つ書いているのは「必須の 2 つ」＋「既定から外れたので明示が要る 2 つ」。**
`deploy/` に移していなければ `*TemplatePath` の 2 行は消せる。
`Image<Number>*` を書いていない理由は §6。

**`TaskDefinitionTemplateArtifact` を短い別名にすることはできない。**
これは `deploy/taskdef.json` のようなファイル名(既定値)とは性質が違う。
§1 冒頭の表で言えば **キーは「固定」、値は「任意」**。

---

## 2. 登場するファイルと、誰が読むか

**`deploy/` の 4 ファイルは、読む主体が 3 つに分かれている。**

| ファイル | 読むのは | いつ |
|---|---|---|
| `deploy/buildspec.yml` | **CodeBuild** | Build ステージの最初 |
| `deploy/taskdef.json` | **CodePipeline の Deploy アクション** | Deploy ステージ。ここから新リビジョンを register する |
| `deploy/appspec.yaml` | **CodeDeploy** | Deploy ステージ。Pipeline が中身を書き換えてから渡す |
| `deploy/taskdef-migrate.json` | **`buildspec.yml` 自身** | post_build。`aws ecs register-task-definition --cli-input-json file://...` |

**`taskdef-migrate.json` だけ AWS のどのサービスも知らない。** このリポジトリが勝手に置いて、
自分で `aws` CLI に食わせているだけのファイル。だから名前も場所も完全に自由で、
`Deploy` アクションからは見えない。

### 2-1. アプリのタスク定義を register するのは誰か

**仕様** Deploy アクションが自分で register する。だから `TaskDefinitionTemplateArtifact` は必須。

> **TaskDefinitionTemplateArtifact** / Required: **Yes**
>
> The `CodeDeployToECS` action ... then **dynamically generates a new revision of task definition**

**帰結として、register の主体が 2 つに割れている。**

| タスク定義 | register する主体 | 使うロール |
|---|---|---|
| アプリ(`deploy/taskdef.json`) | **CodePipeline の Deploy アクション** | CodePipeline サービスロール |
| migrate(`deploy/taskdef-migrate.json`) | **CodeBuild**(`buildspec.yml` の post_build) | CodeBuild サービスロール |

`buildspec.yml` が「アプリのタスク定義は register しません」と最後に echo しているのはこのため。
権限も 2 本に分かれる → [roles.md](roles.md)。

### 2-2. Deploy アクションは 5 段階で動く — CodeDeploy が呼ばれるのは最後

**§1-4 の 4 つのキーは「CodeDeploy に渡す設定」ではない。**
CodePipeline が CodeDeploy を呼ぶ**前**にやる下準備の指示で、CodeDeploy はこの 4 つを知らない。

```
CodePipeline の Deploy アクション(CodeDeployToECS)
  1. TaskDefinitionTemplateArtifact / TaskDefinitionTemplatePath から
     taskdef.json を取り出す
  2. それを ecs:RegisterTaskDefinition して、新しいリビジョンの ARN を得る
  3. AppSpecTemplateArtifact / AppSpecTemplatePath から appspec.yaml を取り出す
  4. その中の <TASK_DEFINITION> を 2 の ARN に置換する
  5. ここで初めて CodeDeploy に codedeploy:CreateDeployment を投げる
        ↓
CodeDeploy
  受け取るのは「置換済みの appspec」だけ。
  taskdef.json も、それがどのアーティファクトのどのパスにあったかも知らない。
```

**仕様** 1〜2 と 4 は公式に明記がある。

> The `CodeDeployToECS` action first looks for the task definition file and the AppSpec file
> in the source file repository, next looks for the image in the image repository,
> then **dynamically generates a new revision of task definition**, and finally runs the
> AppSpec commands to deploy the task set and container to the cluster.
>
> For task definition updates, the CodeDeploy `AppSpec.yaml` file contains the `TaskDefinition` property.
> ... **This property will be updated by the `CodeDeployToECS` action after the new task definition is created.**

**この順番が、3 つのことを同時に説明する。**

1. **なぜ `ecs:RegisterTaskDefinition` と `iam:PassRole` が CodePipeline のサービスロールに要るのか**
   — 2 をやるのが CodePipeline だから。CodeDeploy のロール(`AWSCodeDeployRoleForECS`)には入っていない
   → [roles.md](roles.md) §3-1・§4
2. **なぜ `<TASK_DEFINITION>` だけ文字列が固定なのか**
   — 4 の置換先の目印であり、**CodePipeline と CodeDeploy の間の受け渡し口**だから(§1-2)
3. **なぜ appspec に ARN を直接書けないのか**
   — ARN が確定するのは 2 の後。Git に置いた時点では存在しないリビジョンを指すことになる

**「CodeBuild が register して ARN を appspec に埋める」形は取れない。**
`TaskDefinitionTemplateArtifact` が `Required: Yes` なので、Deploy アクションは
どのみち自分で 1〜2 をやる。CodeBuild が先に register しても二重登録になるだけ。
`deploy/appspec.yaml` のコメントが言っているのはこのこと。

**migrate 用だけ CodeBuild で register できるのは、この 5 段階の外にあるから。**
どのアクションも `taskdef-migrate.json` を見ないので、自分で `aws ecs register-task-definition`
を叩くしかない(→ §2-1 の表)。

### 2-3. `deploy/` の外にも参照されるファイルがある

`buildspec.yml` の build フェーズはこう書いている。

```
docker build --platform linux/amd64 -f docker/app/Dockerfile -t "$IMAGE_URI" .
```

`-f docker/app/Dockerfile` も、ビルドコンテキストの `.` も、**`deploy/` ではなくソースルート基準**(→ §4)。
コンテキストが `.` なので `.dockerignore` もソースルートのものが効く。

---

## 3. ステージ間の受け渡し — アーティファクト

### 3-1. 実体は S3 の zip

**仕様** パイプラインは `ArtifactStore` に S3 バケットを持ち、アクションの出力をそこに置き、
次のアクションがそこから取る。`pipeline.yml` の `ArtifactBucket` がそれ。

このリポジトリの流れは 4 ステージ。

```
[Source]  CodeStarSourceConnection
            GitHub のリポジトリ全体を zip 化
            → OutputArtifacts: SourceArtifact ────┐
                                                   │ S3
[Build]   CodeBuild                                │
            InputArtifacts: SourceArtifact ←───────┘
            展開先が CODEBUILD_SRC_DIR(= ソースルート)
            buildspec の artifacts.files に書いたものだけを詰める
            → OutputArtifacts: BuildArtifact ─────┐
                                                   │ S3
[Approve] Manual                                   │
            アーティファクトを持たない(入力も出力も無し)
                                                   │
[Deploy]  CodeDeployToECS                          │
            InputArtifacts: BuildArtifact ←────────┘
            → 出力アーティファクトは 0 個
```

**仕様** Deploy アクションは出力を持たない。

> ## Output artifacts
> + **Number of Artifacts:** `0`
> + **Description:** Output artifacts do not apply for this action type.

### 3-2. アーティファクト名は「置き場」ではない — 置き場は 1 つ

**`SourceArtifact` と `BuildArtifact` が別々の S3 に入っているように読めるが、バケットは 1 つ。**
`ArtifactStore` に書いたバケットがそれで、どちらもその中のオブジェクトでしかない。

**仕様** 公式は「パイプラインを作るときに選んだ **その** バケット」と単数で書いている。

> Actions use input and output artifacts that are **stored in the Amazon S3 artifact bucket you chose
> when you created the pipeline**. CodePipeline zips and transfers the files for input or output
> artifacts as appropriate for the action type in the stage.
>
> — [Input and output artifacts](https://docs.aws.amazon.com/codepipeline/latest/userguide/welcome-introducing-artifacts.html)

**仕様** 増えるのはリージョンをまたぐときだけ。

> you must have **one artifact bucket per Region** where you plan to execute an action

その場合は `ArtifactStore`(単数)ではなく `ArtifactStores`(リージョンごとの複数形)を書く。
このリポジトリは単一リージョンなので `ArtifactStore` 1 つで足りている。

**名前は zip に付けたあだ名にすぎない。** `OutputArtifacts` の `Name` が名前を付け、
後続アクションが `InputArtifacts` に同じ名前を書くと、CodePipeline が実体の S3 キーを
解決して渡す。**キーの形を人間が知らなくてよい**のはこの仕組みのため。

**仕様** 名前で繋ぐことは明記がある。

> **Every output artifact in the pipeline must have a unique name.** Every input artifact for an action
> must match the output artifact of an action earlier in the pipeline, whether that action is
> immediately before the action in a stage or runs in a stage several stages earlier.

名前そのものが AWS の語彙ではない(何でもよい)話は §5-1。

**推定** バケットの中はパイプラインごとのフォルダに分かれ、その下がアーティファクトごとの
フォルダになる。

```
ArtifactBucket
└── <パイプライン名>/
    ├── <Source の出力>/xxxxxxx.zip   GitHub のコードをそのまま固めたもの
    └── <Build の出力>/yyyyyyy.zip    taskdef.json + appspec.yaml
```

**仕様** フォルダ名はアーティファクト名そのものにはならない。公式が「切り詰める」と断っている
(引用中の `bucket names` は、実際にはバケット内のフォルダ名のこと)。

> CodePipeline **truncates artifact names**, which can cause some bucket names to appear similar.
> Even though the artifact name appears to be truncated, CodePipeline maps to the artifact bucket
> in a way that is not affected by artifacts with truncated names. The pipeline can function normally.

**未検証** 何文字で切られるかは書かれていない。上のツリーで `<Source の出力>` と伏せているのは
そのため。実機で見る(→ §7)。

**「GitHub のコードの置き場」と「ステージ間の受け渡し場所」は同じもの。**
§3-1 のフロー図に 2 回出てくる「S3」は、どちらもこの 1 つのバケットを指している。
Source が GitHub のコードを固めて置く先が、そのまま受け渡し場所になっている。
前者は後者の 1 例(Source ステージの出力)でしかなく、別の置き場があるわけではない。
`BuildProject` の `Source.Type: CODEPIPELINE` が取りに行く先もここ(→ §4-1)。

**裏付けは IAM に出ている。** `CodeBuildServiceRole` の `Artifacts` ステートメントは、
`s3:GetObject`(SourceArtifact を読む)と `s3:PutObject`(BuildArtifact を書く)を
**同じ 1 つのバケットに対して**持っている。置き場が 2 つなら 2 つ書く必要がある
(→ [roles.md](roles.md) §3-2)。

**混同しやすい S3 バケットが他に 2 つある。**

| バケット | 作るのは | 使われるのは |
|---|---|---|
| `<ProjectName>-<EnvName>-pipeline-artifacts` | `pipeline.yml` の `ArtifactBucket` | **パイプラインが回るとき** |
| `nuxt-java-practice-lambda-artifacts-<アカウントID>` | 手動(常駐リソース) | スタックを反映するとき |
| `nuxt-java-practice-cfn-templates-<アカウントID>` | 手動(常駐リソース) | スタックを反映するとき |

下 2 つは `pipeline-apply.yml` が `aws cloudformation package` と `deploy` で経由する置き場で、
**パイプラインの実行では 1 度も使われない**。この 2 つを分けている理由(保存要件が正反対で、
同居させるとライフサイクルを 1 つ触っただけで静かに壊れる)は
[ADR-0014](../../adr/0014-slack-approval-with-lambda.md) と
[運用手順](../../infrastructure/cloudformation-operations.md) §3。

### 3-3. BuildArtifact に入るのは 2 ファイルだけ

`deploy/buildspec.yml` の末尾はこうなっている。

```yaml
artifacts:
  files:
    - deploy/taskdef.json
    - deploy/appspec.yaml
```

**ここに書いたものだけが次のステージに渡る。** ソースの他のファイル(`backend/` も `docker/` も)は
BuildArtifact に入らない。Deploy アクションが読めるのはこの 2 つだけ、ということでもある。

**イメージはアーティファクトを通らない。** `docker push` で ECR に入り、
その URI が `deploy/taskdef.json` の `image` に文字列として書き込まれた状態で運ばれる。
S3 を通るのは「タスク定義の下書き」と「デプロイ手順書」だけで、数 KB しかない。

### 3-4. なぜ Deploy が SourceArtifact ではなく BuildArtifact を読むのか

**AWS の公式サンプルは両方 `SourceArtifact` にしている。**

```yaml
# 公式のアクション宣言例
AppSpecTemplateArtifact: SourceArtifact
TaskDefinitionTemplateArtifact: SourceArtifact
```

Git に置いた `taskdef.json` をそのまま使う前提なら、Build を経由する必要が無いからそうなる。

**このリポジトリが `BuildArtifact` にしているのは、`taskdef.json` がプレースホルダ入りだから。**
`buildspec.yml` の post_build にある `render()` が `sed -i` で
`__DB_HOST__` などをスタックの実際の値に書き換える。**書き換えたあとの中身**が要るので、
Source の生ファイルではなく Build の出力を渡している。

この設計の理由(値を Git に持たない)は設計書の実装差分表「`taskdef.json` の値」の行。

---

## 4. パスは何を基準に解決されるか

**基準が 4 つあり、それぞれ別物。** ここが一番間違えやすい。

| 書く場所 | 何からの相対パスか | このリポジトリの値 |
|---|---|---|
| `BuildProject` の `Source.BuildSpec` | **ソースルート**(`CODEBUILD_SRC_DIR`) | `deploy/buildspec.yml` |
| `buildspec.yml` 内のコマンド・`artifacts.files` | **ソースルート**(カレントディレクトリ) | `deploy/taskdef.json` など |
| Deploy アクションの `*TemplatePath` | **アーティファクトの中** | `deploy/taskdef.json` |
| `Pipeline` の `Triggers.FilePaths` | **Git リポジトリのパス** | `deploy/**` |

**字面はどれも `deploy/` で同じだが、意味が違う。** 揃っているのは偶然ではなく、
`artifacts.files` が `base-directory` を使わないよう書いてあるから(→ §5-2)。

### 4-1. 「ソースルート」はリポジトリ直下ではない

**仕様** `CODEBUILD_SRC_DIR` の説明は、値の例まで書いている。

> **CODEBUILD_SRC_DIR** — The directory path that CodeBuild uses for the build
> (for example, `/tmp/src123456789/src`).
>
> — [Environment variables in build environments](https://docs.aws.amazon.com/codebuild/latest/userguide/build-env-ref-env-vars.html)

つまり実体は `/tmp/src.../src` のような一時ディレクトリで、そこにソースが展開される。
`Source.Type: CODEPIPELINE` の場合、展開されるのは **Git のクローンではなく入力アーティファクトの中身**。

**推定** このリポジトリでは Source アクションがリポジトリ全体をアーティファクト化するので、
展開後のルート = リポジトリ直下になり、結果として `deploy/buildspec.yml` で当たる。
**もし Source 側でサブディレクトリだけを渡す構成にしたら、ここがずれる。**

### 4-2. buildspec の場所は、カレントディレクトリに影響しない

**これが `deploy/` へ移したときの一番の落とし穴。**
`buildspec.yml` 自身は `deploy/` にあるが、コマンドが走るときのカレントディレクトリは
**ソースルート**であって `deploy/` ではない。

だから `buildspec.yml` の中は全部ソースルート基準で書く必要がある。

```
docker build ... -f docker/app/Dockerfile .   # deploy/../docker ではない
render deploy/taskdef.json                    # ./taskdef.json ではない
```

**未検証** この前提が崩れていた場合、`docker build` は
`unable to prepare context` か `failed to read dockerfile` で落ちるはず。実際の文言は見ていない。

### 4-3. `*TemplatePath` はアーティファクト内のパス

**仕様** 公式の文言は「pipeline source file location に置かれたファイル名」となっている。

> The file name of the task definition **stored in the pipeline file source location**,
> such as your pipeline's CodeCommit repository. ... If your task definition file has the same
> name and is **stored at the root level in your file repository**, you do not need to provide the file name.

**推定** 公式は Source アクションの出力をそのまま渡す前提で書いているので「リポジトリのルート」と
表現しているが、実際に見るのは `TaskDefinitionTemplateArtifact` で指定したアーティファクトの中。
このリポジトリは `BuildArtifact` を指定しているので、**基準は「BuildArtifact を展開したルート」**になる。

---

## 5. 字面が一致していなければならない組

**片方だけ直すと壊れる箇所の一覧。** どれもテンプレートを跨ぐので、grep しないと気づけない。

| # | 一方 | もう一方 | 基準 |
|---|---|---|---|
| 1 | `Source.BuildSpec: deploy/buildspec.yml` | 実ファイル `deploy/buildspec.yml` | ソースルート |
| 2 | `artifacts.files: deploy/taskdef.json` | `TaskDefinitionTemplatePath: deploy/taskdef.json` | アーティファクト内 |
| 3 | `artifacts.files: deploy/appspec.yaml` | `AppSpecTemplatePath: deploy/appspec.yaml` | アーティファクト内 |
| 4 | `buildspec.yml` の `render deploy/taskdef.json` | 実ファイル | ソースルート |
| 5 | Build の `OutputArtifacts: BuildArtifact` | Deploy の `InputArtifacts` と `*TemplateArtifact` | アーティファクト名 |
| 6 | `Triggers.GitConfiguration.SourceActionName: Source` | Source アクションの `Name: Source` | アクション名 |
| 7 | `appspec.yaml` の `ContainerName: app` / `ContainerPort: 8080` | `taskdef.json` の `containerDefinitions[].name` / `portMappings[].containerPort` | コンテナ定義 |

### 5-1. 5 番だけは「一致すればどんな名前でもよい」

アーティファクト名は AWS が決めた語彙ではない。

**仕様** 公式も、コンソールの既定値にすぎないと書いている。

> When you use the console, the default name for the source action output artifact is `SourceArtifact`.

`MyZip` でも `foo` でも、パイプライン内で揃っていれば動く。

そして名前は**置き場ではない**。実体は 1 つのバケットの中に並んでいる(→ §3-2)。

### 5-2. 2・3 番が成立している理由 — `base-directory` を書いていないこと

**仕様** `artifacts` の `files` は「元のビルド場所、または `base-directory` を設定していればそこ」からの相対で、
`discard-paths: yes` にするとディレクトリ構造が潰れる。

> **artifacts/discard-paths** — Optional. Specifies if the build artifact directories are flattened
> in the output. If this is not specified, or contains `no`, build artifacts are output with their
> directory structure intact.

**推定** このリポジトリは `base-directory` も `discard-paths` も書いていないので、
`deploy/taskdef.json` は**アーティファクトの中でも `deploy/` 付きのまま**入る。
だから `TaskDefinitionTemplatePath` も `deploy/` 付きで一致する。

**もし `discard-paths: yes` を足したら、`*TemplatePath` を `taskdef.json` に直さないと壊れる。**
`base-directory: deploy` を足した場合も同じ。

**未検証** ずれていたときのエラー文言は見ていない。Deploy アクションが
「アーティファクトの中にファイルが無い」と言う形になるはず。

### 5-3. 7 番は AWS の 2 つのファイルを跨ぐ

`deploy/appspec.yaml` の `LoadBalancerInfo` は、どのコンテナのどのポートに
トラフィックを流すかを指定する。ここが `taskdef.json` のコンテナ定義と食い違うと、
**タスクは起動するのにターゲットグループに登録されない**という形で失敗する。

`app.yml` の `TargetGroupBlue` / `TargetGroupGreen` のポート、
`Service` の `LoadBalancers.ContainerName` / `ContainerPort` とも揃っている必要がある。

---

## 6. 採っていない仕組み — `imageDetail.json`

**仕様** Deploy アクションは本来、イメージ URI を `imageDetail.json` から受け取る設計になっている。

> The `CodeDeployToECS` action looks for an `imageDetail.json` file that maps the image URI to the image.
> When you commit a change to your Amazon ECR image repository, the pipeline ECR source action creates
> an `imageDetail.json` file for that commit.

流れとしてはこうなる。

```
ECR ソースアクション → imageDetail.json を生成
  → Image1ArtifactName でそのアーティファクトを指定
  → Image1ContainerName に書いた名前が taskdef.json の <名前> と対応
  → Deploy アクションが taskdef.json の image を置換
```

**このリポジトリは使っていない。** `pipeline.yml` の Deploy アクションに
`Image1ArtifactName` / `Image1ContainerName` を書いていないのはそのため
(どちらも `Required: No`)。理由は、`buildspec.yml` の `render()` が
`__IMAGE__` に完全な URI を直接書き込んでしまうから。置換の余地が残っていない。

**その代わり、ECR をソースアクションに持つ必要も無くなっている。**
`Triggers` の `FilePaths` に `backend/**` などを並べて Git 側の変更で起動しているのは、
「イメージが増えたら回す」ではなく「ソースが変わったら作る」という向きだから。

---

## 7. 実機で確かめること

- [ ] `artifacts.files` が本当に `deploy/` 付きで梱包されるか(§5-2 の推定)
- [ ] アーティファクトの S3 キーがどんな形になるか(§3-2 のツリーは推定。
      アーティファクト名が何文字で切り詰められるかは公式に記載が無い)
- [ ] `deploy/buildspec.yml` に置いてもカレントディレクトリがソースルートのままか(§4-2)
- [ ] 字面がずれたときの Deploy アクションのエラー文言
- [ ] `CODEBUILD_RESOLVED_SOURCE_VERSION` に完全な commit SHA が入るか
      (`buildspec.yml` は先頭 7 文字を切っている。CodePipeline 経由の値は
      「source revision provided by CodePipeline」としか書かれていない → [env-vars-and-logs.md](env-vars-and-logs.md))
- [ ] スタックがまだ無いときに post_build が `exit 0` して、Build ステージが緑になるか
      (`buildspec.yml` のブートストラップ経路)
