# 環境変数とログ — ビルド時の値が実行時の値になるまで、と足跡の在り処

> **このノートは実機未検証。** フェーズ16 のパイプラインはまだ一度も回っていない
> (→ [implementation-progress.md](../../development/implementation-progress.md))。
> 確からしさを 3 段階で書き分ける。**仕様** = 公式に明記 / **推定** = 仕様から導いた /
> **未検証** = 実機で確かめる。

ファイルとアーティファクトは [files-artifacts-and-paths.md](files-artifacts-and-paths.md)、
ロールは [roles.md](roles.md)。
環境変数の一般論(そもそも環境変数とは / `.env` は誰が読むのか)は
[env-vars-basics.md](../env-vars-basics.md)、このリポジトリの方針は
[development/README.md](../../development/README.md) の「環境変数の方針」。

---

## 0. 名前の規則 — 固定・既定・任意

| 名前 | 区分 | 備考 |
|---|---|---|
| `CODEBUILD_SRC_DIR` / `CODEBUILD_RESOLVED_SOURCE_VERSION` など | **固定** | CodeBuild が用意する。`CODEBUILD_` 始まりは予約 |
| `AWS_DEFAULT_REGION` / `AWS_REGION` | **固定** | 2 つあり、用途が違う(→ §2-2) |
| `PROJECT_NAME` / `ENV_NAME` / `ECR_REPOSITORY` / `STACK_NAME` | **任意** | このリポジトリが `pipeline.yml` で渡しているだけ |
| `/tmp/build.env` | **任意** | CodeBuild の機能ではない。自前のファイル(→ §4) |
| `exported-variables` に並べる名前 | **任意** | ただし後続から参照するには `Namespace` が要る(→ §4-2) |
| `DB_HOST` / `FLYWAY_ENABLED` などコンテナの環境変数 | **任意** | アプリ(Spring Boot)が読む名前。AWS は関知しない |
| ロググループ名 `/aws/codebuild/...` / `/ecs/...` | **任意** | 既定は `/aws/codebuild/<プロジェクト名>` |
| ロググループ名 `/aws/chatbot/<ConfigurationName>` | **固定** | Chatbot が自分で作る(→ §7-3) |

**`CODEBUILD_` 始まりは自分で作らないこと。**

> Do not set any environment variable with a name that starts with `CODEBUILD_`.
> This prefix is reserved for internal use.
>
> — [Buildspec syntax](https://docs.aws.amazon.com/codebuild/latest/userguide/build-spec-ref.html)

---

## 1. 4 つの層がある

**「環境変数」と一言で言っても、このパイプラインには性質の違うものが 4 層ある。**
一番の見どころは、**層 A・B の値がどうやって層 D になるか**という橋渡し。

```
層A  CodeBuild の組み込み          CODEBUILD_SRC_DIR / AWS_DEFAULT_REGION
     (AWS が用意)                  CODEBUILD_RESOLVED_SOURCE_VERSION
                    │
層B  プロジェクトが渡す            PROJECT_NAME / ENV_NAME
     (pipeline.yml)                ECR_REPOSITORY / STACK_NAME
                    │
                    ├─→ 層C  フェーズ間で引き継ぐ
                    │         /tmp/build.env と exported-variables
                    │         (pre_build → build → post_build)
                    ↓
              render() が sed で埋める ←── スタックの Outputs / Parameters
                    ↓
層D  コンテナに入る                deploy/taskdef.json の environment / secrets
     (ECS が注入)                  DB_HOST / FLYWAY_ENABLED / DB_PASSWORD ...
```

**層 D の値は Git に無い。** `deploy/taskdef.json` が持っているのは
「どの環境変数があるか」という**構造だけ**で、値は `__DB_HOST__` のようなプレースホルダ。
値は毎回スタックから取る。二重管理を構造だけに限定して、値のずれを起こさないための設計
(→ 設計書の実装差分表「`taskdef.json` の値」の行)。

---

## 2. 層 A — CodeBuild の組み込み

このリポジトリが実際に使っているのは 2 つだけ。

### 2-1. `CODEBUILD_RESOLVED_SOURCE_VERSION` — イメージタグの素

`deploy/buildspec.yml` の pre_build がこう書いている。

```
IMAGE_TAG="${CODEBUILD_RESOLVED_SOURCE_VERSION:0:7}"
```

**仕様** ただし中身はソースの種類で変わる。

> **CODEBUILD_RESOLVED_SOURCE_VERSION** — The version identifier of a build's source code.
> The contents depends on the source code repository:
> - CodeCommit, GitHub, ... — This variable contains the commit ID.
> - **CodePipeline — This variable contains the source revision provided by CodePipeline.**
>
> When applicable, the `CODEBUILD_RESOLVED_SOURCE_VERSION` variable is only available
> after the `DOWNLOAD_SOURCE` phase.
>
> — [Environment variables in build environments](https://docs.aws.amazon.com/codebuild/latest/userguide/build-env-ref-env-vars.html)

**未検証** このパイプラインは `Source.Type: CODEPIPELINE` なので、後者に当たる。
「CodePipeline が提供するソースリビジョン」が完全な commit SHA なのかは明記が無い。
**7 文字を切っている前提が崩れると、タグが変な形になる。**
`ecr-push.yml`(GitHub Actions 版)が `${GITHUB_SHA::7}` を使っていたのと揃えるための処理。

**`DOWNLOAD_SOURCE` の後でしか使えない**ので、`env` セクションでは参照できず、
pre_build 以降のコマンドの中でしか読めない。

### 2-2. `AWS_DEFAULT_REGION` と `AWS_REGION` は別物

**仕様** 値は同じだが、想定利用者が違う。

> **AWS_DEFAULT_REGION** — The AWS Region where the build is running. This environment variable
> is used primarily by **the AWS CLI**.
>
> **AWS_REGION** — ... This environment variable is used primarily by **the AWS SDKs**.

`buildspec.yml` は `aws` コマンドと、`taskdef.json` の `__AWS_REGION__` 置換の両方で
`AWS_DEFAULT_REGION` を使っている。**推定** 値が同じなのでどちらでも動くが、
`awslogs-region` に入るのは「SDK が使う側」なので、厳密には `AWS_REGION` のほうが筋がよい。

### 2-3. 使っていないが役に立つもの

| 変数 | 中身 | 使いどころ |
|---|---|---|
| `CODEBUILD_BUILD_ID` | `プロジェクト名:UUID` | **ログストリーム名がこれ**(→ §7-1) |
| `CODEBUILD_INITIATOR` | CodePipeline 起動なら `codepipeline/<パイプライン名>` | 手動ビルドと区別する |
| `CODEBUILD_BUILD_NUMBER` | 連番 | イメージタグに足す案もある |
| `CODEBUILD_SRC_DIR` | `/tmp/src123456789/src` | パスの基準(→ [files-artifacts-and-paths.md](files-artifacts-and-paths.md) §4) |

---

## 3. 層 B — プロジェクトが渡す

`pipeline.yml` の `BuildProject` の `Environment.EnvironmentVariables` に 4 つ書いてある。

| 変数 | 値 | 使い道 |
|---|---|---|
| `PROJECT_NAME` | `nuxt-java-practice` | family 名・ロール ARN の組み立て |
| `ENV_NAME` | `stg` / `prod` | 同上 |
| `ECR_REPOSITORY` | ECR のリポジトリ名 | push 先とタグ存在チェック |
| `STACK_NAME` | `${ProjectName}-${EnvName}` | `describe-stacks` の対象 |

**`STACK_NAME` は `cfn-apply.yml` が組み立てるスタック名と同じ規則で書かれている。**
`Export` / `ImportValue` を使わず命名規則で繋ぐ方針の一部(→ ADR-0007)。

### 3-1. 同名が衝突したときの優先順位

**仕様** 3 か所で定義でき、上が勝つ。

> - The value in the **start build operation call** takes highest precedence.
> - The value in the **build project definition** takes next precedence.
> - The value in the **buildspec declaration** takes lowest precedence.

つまり `pipeline.yml`(プロジェクト定義)に書いた値は、`buildspec.yml` の
`env.variables` より強い。**このリポジトリは `env.variables` を使っていない**ので衝突は起きない。

**なお `env.parameter-store` / `env.secrets-manager` も使っていない。**
ビルド中に秘密が要らないため(ECR の認証はロールで済む)。

---

## 4. 層 C — フェーズ間で引き継ぐ

### 4-1. `/tmp/build.env` は AWS の機能ではない

`buildspec.yml` の pre_build の最後にこう書いてある。

```
# 後続フェーズは別プロセスなので、シェル変数は引き継がれない。ファイルに書いて渡す
{ echo "export IMAGE_TAG=$IMAGE_TAG"; ... } > /tmp/build.env
```

build と post_build は先頭で `. /tmp/build.env` して読み直す。

**仕様** buildspec 0.2 では 1 フェーズ内のコマンドは同じシェルで走る。

> In version 0.1, AWS CodeBuild runs each build command in a separate instance of the default shell
> in the build environment. **In version 0.2, CodeBuild runs all build commands in the same instance
> of the default shell** in the build environment.

**推定** 「同じシェル」と書かれているのはコマンドの粒度についてで、
フェーズを跨いで環境が保たれるとは書かれていない。だからファイル経由にしている。
`/tmp` が残ることには依存している(同じビルドコンテナ内なので残る)。

**この書き方の副作用。** `set -euo pipefail` も `. /tmp/build.env` も
フェーズごとに書き直す必要がある。1 フェーズ 1 つの大きな `|` ブロックになっているのはそのため。

### 4-2. `exported-variables` は「宣言しただけ」では後続から参照できない

`buildspec.yml` の冒頭はこう宣言している。

```yaml
env:
  exported-variables:
    - IMAGE_TAG
    - IMAGE_URI
    - IMAGE_PUSHED
```

**仕様** CodeBuild 側は、export された環境変数をそのまま変数として産出する。

> CodeBuild actions produce as variables all environment variables that were exported as part of the build.

**仕様** だが CodePipeline 側で参照するには、そのアクションに `Namespace` が要る。

> **If the namespace isn't specified, the variables produced by the action are not available
> to be referenced in any downstream action configuration.**
>
> — [Working with variables](https://docs.aws.amazon.com/codepipeline/latest/userguide/actions-variables.html)

参照の構文は `#{<名前空間>.<キー>}`。公式のアクション宣言例には `Namespace: DeployVariables` が入っている。

**つまり成立には 2 つが揃っている必要がある。片方だけでは何も起きない。**

| 要る側 | 書く場所 | このリポジトリの値 |
|---|---|---|
| 変数を**産出**する | `buildspec.yml` の `env.exported-variables` | `IMAGE_TAG` / `IMAGE_URI` / `IMAGE_PUSHED` |
| 変数を**参照可能にする** | `pipeline.yml` の Build アクションの `Namespace` | `BuildVariables` |

**このノートを書いている過程で、`Namespace` が抜けていたことが分かって足した。**
宣言だけあって名前空間が無い状態では、`buildspec.yml` のコメントが言う
「承認の通知や後続アクションから参照できるようにしておく」は半分しか成立していなかった。

現在は Approve アクションの `CustomData` がこう書いている。

```yaml
CustomData: !Sub "${ProjectName} (${EnvName}) をデプロイします。イメージ: #{BuildVariables.IMAGE_TAG} / push: #{BuildVariables.IMAGE_PUSHED}。..."
```

**`#{...}` と `${...}` が同じ文字列に同居している点に注意。**
`${ProjectName}` は CloudFormation の `!Sub` がスタック更新時に展開し、
`#{BuildVariables.IMAGE_TAG}` は CodePipeline が実行時に展開する。
**展開する主体もタイミングも違う**ので衝突しない(`!Sub` は `#{` を見ない)。

**名前空間が無くても、実行詳細でなら値を見られる。**

> You can view the details for each action execution to see the values for each output variable
> that was generated by the action in execution-time.

CLI なら `aws codepipeline list-action-executions` の `outputVariables` に出る。
参照できないだけで、産出そのものは行われている。

**未検証** `exported-variables` がフェーズを跨いだ `export`(= `. /tmp/build.env` 経由)でも
拾われるかは確かめていない。**ここが崩れると `CustomData` の変数が空文字か
未解決のまま残る**ので、承認メッセージを最初に見たときの確認点になる。

---

## 5. 層 D — コンテナに入る

### 5-1. `environment` と `secrets` は入り方が違う

`deploy/taskdef.json` は 2 つのリストを持っている。

| | 書かれ方 | 誰が取ってくるか |
|---|---|---|
| `environment` | **タスク定義に平文で残る** | 誰も。定義そのものが値 |
| `secrets` | SSM のパラメータ ARN が書かれる | **task-execution-role**(→ [roles.md](roles.md) §3-4) |

```json
"environment": [ { "name": "DB_HOST", "value": "__DB_HOST__" }, ... ],
"secrets":     [ { "name": "DB_PASSWORD", "valueFrom": "__SSM_ARN_PREFIX__app_db_password" } ]
```

**`environment` に秘密を書いてはいけない。** タスク定義は
`aws ecs describe-task-definition` で誰でも読めるうえ、リビジョンとして永久に残る。

**`secrets` を取るのはコンテナではなく ECS エージェント。**
だからタスクロールではなく**実行ロール**に `ssm:GetParameters` が要る。
`app.yml` の `TaskExecutionRole` の `ReadSecrets` がそれ。

**推定** 注入はタスク起動時に一度だけなので、**SSM の値を後から変えても動いているタスクには反映されない。**
反映するにはタスクを入れ替える(= デプロイし直す)必要がある。

### 5-2. アプリと migrate で値が違う

同じ `render()` が両方を埋めるが、埋まる先が違う。

| 変数 | `taskdef.json`(アプリ) | `taskdef-migrate.json` |
|---|---|---|
| `FLYWAY_ENABLED` | `false` | `true` |
| `APP_TASK` | (無し) | `migrate` |
| `FLYWAY_DB_USER` / `FLYWAY_DB_PASSWORD` | (無し) | あり(DDL 権限を持つ `migrate` ユーザー) |
| `MAIL_*` / `S3_*` / `GOOGLE_*` | あり | (無し) |

**DB ユーザーが 2 系統あるのは ADR-0005 の設計。** アプリは DML だけ、
Flyway は DDL を打てる別ユーザーで走らせる。

### 5-3. 埋め忘れは `grep` で止めている

`buildspec.yml` の post_build は、`render()` の直後にこう書いている。

```
if left=$(grep -ho '__[A-Z0-9_]*__' deploy/taskdef.json deploy/taskdef-migrate.json | sort -u) && [ -n "$left" ]; then
  echo "埋まっていないプレースホルダがあります:" >&2
  ...
```

**これが無いと、埋まらないまま register されて「`__DB_HOST__` に接続できない」という
分かりにくいクラッシュになる。** スタックは緑、パイプラインも緑、タスクだけが落ちる。

**同じ形の守りが `app.yml` 側にもある。** `SlackWorkspaceId` の `AllowedPattern` は
プレースホルダのまま構築が成功して無音になるより、Change Set の作成で止まるほうを選んでいる
(→ [docs/slack/README.md](../../slack/README.md) §6)。

### 5-4. `app.yml` と `taskdef.json` は構造だけ二重管理になっている

**環境変数・secrets・CPU/メモリを変えるときは、両方直すこと。**
`app.yml` の `AppTaskDefinition` は初回構築でだけ使われ、2 代目以降は
`deploy/taskdef.json` が正になる。片方だけ直すと
**「構築直後の初回起動だけ古い定義で立ち上がってクラッシュする」**という気づきにくい壊れ方をする
(→ `app.yml` の `AppTaskDefinition` のコメント、ADR-0013)。

---

## 6. ログ — パイプライン 1 回分の足跡はどこにあるか

| 見たいもの | 場所 | リージョン | 保持期間を持つのは |
|---|---|---|---|
| ビルドの出力(`echo` / `docker build` / `aws`) | `/aws/codebuild/<プロジェクト>-<env>-build` | ap-northeast-1 | `pipeline.yml` の `BuildLogGroup` |
| **パイプラインの進行** | **CloudWatch Logs には出ない**(→ §7-1) | — | — |
| **デプロイの進行(タスクセット・切り替え)** | **CloudWatch Logs には出ない**(CodeDeploy 画面) | — | — |
| アプリ / migrate の標準出力 | `/ecs/<プロジェクト>-<env>` | ap-northeast-1 | `app.yml` の `EcsLogGroup` |
| Slack 転送の失敗・承認コマンドの監査 | `/aws/chatbot/<ConfigurationName>` | **us-east-1** | **誰も**(既定で無期限) |
| 誰がどの API を叩いたか | CloudTrail | — | 証跡の設定 |

**ログストリームの分かれ方も違う。**

- CodeBuild — ストリーム名は `CODEBUILD_BUILD_ID` の UUID 部分。ビルド 1 回 = 1 ストリーム
- ECS — `awslogs-stream-prefix` で分かれる。アプリは `app`、migrate は `db-migrate`。
  同じロググループの中でタスクごとにストリームが増える

### 6-1. ロググループを CloudFormation で先に作っている理由

CodeBuild も ECS も、指定が無ければ自分でロググループを作る。
それでも `pipeline.yml` と `app.yml` が `AWS::Logs::LogGroup` を明示的に持っているのは、
**保持期間を設定するため。**

サービスに作らせると `RetentionInDays` を指定できず、既定の**無期限**になる。
CloudWatch Logs は保存量で課金されるので、作り捨ての検証環境では効いてくる。

**これが `roles.md` §3-2 の「`logs:CreateLogGroup` を渡していない」に繋がる。**
CodeBuild に作る権限を渡さなければ、うっかり無期限のグループができることもない。

---

## 7. 出ないもの

### 7-1. CodePipeline は CloudWatch Logs にログを出さない

**仕様** 公式が挙げる監視手段は 4 つで、CloudWatch Logs は入っていない。

> You can use the following tools to monitor your CodePipeline pipelines and their resources:
> - **EventBridge event bus events** — ... detects changes in your pipeline, stage, or action execution status.
> - **Notifications for pipeline events in the Developer Tools console** — ...
> - **AWS CloudTrail** — Use CloudTrail to capture API calls made by or on behalf of CodePipeline ...
> - **Console and CLI** — You can use the CodePipeline console and CLI to view details about the status
>   of a pipeline or a particular pipeline execution.
>
> — [Monitoring pipelines](https://docs.aws.amazon.com/codepipeline/latest/userguide/monitoring.html)

**推定** したがって `/aws/codepipeline/...` のようなロググループは存在しない。
「パイプラインが失敗した理由」を追うときの入口は次の 3 つ。

1. **コンソールの View history** — どのステージのどのアクションで止まったか
2. **`aws codepipeline list-action-executions`** — 失敗したアクションの `outputVariables` とエラー
3. **失敗したアクションが委譲した先のログ** — Build なら `/aws/codebuild/...`、Deploy なら CodeDeploy 画面

**このリポジトリは 1・2 を待たずに済むようにしてある。**
`pipeline.yml` の `PipelineNotificationRule` が
`pipeline-execution-failed` と `action-execution-failed` を Slack に流す。

### 7-2. CodeDeploy の ECS デプロイにも詳細ログは無い

**推定** ECS の Blue/Green で `appspec.yaml` の `Hooks` を書けばライフサイクルの各点で
処理を挟めるが、**ECS の Hooks は Lambda 関数でしか実装できない。**
このリポジトリは Hooks を書いていない(アプリケーション以外のコードを持たない方針 → ADR-0011)。

結果として、デプロイ中に見られるのは CodeDeploy コンソールのライフサイクルイベントの
成否だけになる。**タスクが起動しなかった理由は CodeDeploy ではなく `/ecs/...` に出る。**

### 7-3. Chatbot のログはスタックの外・別リージョンにある

**ロググループ名は `/aws/chatbot/<ConfigurationName>` で、作られるのは us-east-1。**
このスタックのリージョン(ap-northeast-1)ではない。

- **Chatbot が自分で作る。** CloudFormation ではないので、**撤収しても消えない**
- **保持期間は既定の無期限。** テンプレートの `LogRetentionDays` は効かない
- **`LoggingLevel: NONE` にしてもロググループは消えない。**
  コマンド実行の監査ログは常時有効で無効化できない。**Slack からの承認は「コマンドの実行」**なので、
  承認するたびに監査イベントが出る

詳細と、コンソールで探すときの注意は [docs/slack/README.md](../../slack/README.md) §8-2。

**承認が権限で弾かれた理由が出るのはここだけ。** ガードレールポリシーとチャンネルロールの
AND(→ [roles.md](roles.md) §3-6)で落ちたとき、Slack 側には素っ気ないエラーしか出ない。

---

## 8. 実機で確かめること

- [ ] `CODEBUILD_RESOLVED_SOURCE_VERSION` が CodePipeline 経由で完全な commit SHA になるか(§2-1)
- [ ] `exported-variables` が `. /tmp/build.env` 経由の `export` でも拾われるか(§4-2)
- [ ] Slack の承認メッセージで `#{BuildVariables.IMAGE_TAG}` が実際のタグに解決されるか。
      未解決のまま出るなら上の項目が原因(§4-2)
- [ ] `/aws/chatbot/...`(us-east-1)にどれだけ書き込まれるか。承認 1 回あたりの行数
- [ ] Deploy が失敗したとき、CodeDeploy 画面と `/ecs/...` のどちらに原因が出るか(§7-2)
- [ ] `secrets` の注入が起動時一度きりで、SSM を更新しても反映されないこと(§5-1)
