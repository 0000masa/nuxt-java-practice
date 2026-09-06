# ロール — パイプラインが回る間、誰の資格情報で動いているのか

> **このノートは実機未検証。** フェーズ16 のパイプラインはまだ一度も回っていない
> (→ [implementation-progress.md](../../development/implementation-progress.md))。
> 確からしさを 3 段階で書き分ける。**仕様** = 公式に明記 / **推定** = 仕様から導いた /
> **未検証** = 実機で確かめる。

ファイルとアーティファクトの流れは [files-artifacts-and-paths.md](files-artifacts-and-paths.md)、
環境変数とログは [env-vars-and-logs.md](env-vars-and-logs.md)。

**このノートが扱うのは「パイプラインが回る間」だけ。**
スタックを建てる側(GitHub Actions の OIDC → CloudFormation → リソース)は
[iam-roles-and-command-permissions.md](../cloudformation/iam-roles-and-command-permissions.md) が扱っている。
両者は交わらない。**CloudFormation はロールを作るだけで、パイプラインが回るときには登場しない。**

---

## 0. 名前の規則 — 固定・既定・任意

| 名前 | 区分 | 備考 |
|---|---|---|
| ロール名(`...-codebuild-role` など) | **任意** | `RoleName` 自体が省略可(省略すると CloudFormation が生成する) |
| サービスプリンシパル(`codebuild.amazonaws.com` など) | **固定** | AWS が決めている。ここを間違えると `AssumeRole` が通らない |
| AWS 管理ポリシーの ARN(`AWSCodeDeployRoleForECS` など) | **固定** | 名前が中身の識別子そのもの |
| `Sid`(`EcrPush` / `PassTaskRoles` など) | **任意** | 人間が読むためのラベル。英数字のみ |
| `iam:PassedToService` の値 | **固定** | 渡し先サービスのプリンシパル名 |

---

## 1. 6 本ある。どこで効くか

**1 回のデプロイで 6 本のロールがバトンを渡す。**

```
[Source]  CodePipeline サービスロール
            └ codestar-connections:UseConnection で GitHub から取る

[Build]   CodePipeline サービスロール
            └ codebuild:StartBuild でビルドを頼む
          CodeBuild サービスロール          ← ここでロールが替わる
            └ ECR push / DescribeStacks / RegisterTaskDefinition(migrate)

[Approve] Chatbot のチャンネルロール + ガードレールポリシー
            └ codepipeline:PutApprovalResult(Slack から)

[Deploy]  CodePipeline サービスロール
            └ RegisterTaskDefinition(アプリ) → codedeploy:CreateDeployment
          CodeDeploy サービスロール          ← ここでまた替わる
            └ タスクセット作成・ALB リスナー付け替え
          task-execution-role                ← ECS がタスクを起動するとき
            └ ECR pull / ログ / SSM から secrets 取得
          task-role                          ← アプリが動き出してから
            └ SES / S3 / ECS Exec
```

**「頼む側」と「やる側」で必ずロールが替わる。**
CodePipeline は CodeDeploy に依頼するだけで、実際にタスクセットを作るのは CodeDeploy 自身。
だから CodePipeline のロールに ECS のタスクセット作成権限は要らない。

| # | ロール | 定義場所 | 引き受けるサービス |
|---|---|---|---|
| 1 | `...-codepipeline-role` | `pipeline.yml`(常駐) | `codepipeline.amazonaws.com` |
| 2 | `...-codebuild-role` | `pipeline.yml`(常駐) | `codebuild.amazonaws.com` |
| 3 | `...-codedeploy-role` | `app.yml`(作り捨て) | `codedeploy.amazonaws.com` |
| 4 | `...-task-execution-role` | `app.yml`(作り捨て) | `ecs-tasks.amazonaws.com` |
| 5 | `...-task-role` | `app.yml`(作り捨て) | `ecs-tasks.amazonaws.com` |
| 6 | `...-chatbot-approve-role` | `pipeline.yml`(常駐) | `chatbot.amazonaws.com` |

**3 が `app.yml` 側にあるのは、デプロイグループが `app.yml` にあるから。**
デプロイグループは ALB のリスナー ARN を参照する必要があり、リスナー ARN は生成 ID を含むので
命名規則では組み立てられない(→ `pipeline.yml` 冒頭のコメント)。

---

## 2. 信頼ポリシーと権限ポリシーは別物

ロールの定義には性質の違う 2 つが入っている。

| | 何を書くか | 書く場所 |
|---|---|---|
| **信頼ポリシー** | **誰がこのロールになれるか** | `AssumeRolePolicyDocument` |
| **権限ポリシー** | **なった人が何をできるか** | `Policies` / `ManagedPolicyArns` |

**どちらが欠けても動かないが、エラーの出方が違う。**
信頼ポリシーが違うと、そもそもロールを引き受けられずサービスが起動しない。
権限ポリシーが足りないと、起動はして途中の API 呼び出しで `AccessDenied` になる
(→ [iam-roles-and-command-permissions.md](../cloudformation/iam-roles-and-command-permissions.md) §3)。

**4 と 5 は信頼ポリシーが同じ**(`ecs-tasks.amazonaws.com`)。
違うのは権限ポリシーと、タスク定義のどのフィールドに書かれるか(`executionRoleArn` か `taskRoleArn` か)だけ。

---

## 3. 1 本ずつ

### 3-1. CodePipeline サービスロール

**パイプライン全体のオーケストレーターで、一番広い。** `pipeline.yml` の `CodePipelineServiceRole`。

| Sid | できること | なぜ要るか |
|---|---|---|
| `Artifacts` | アーティファクトバケットの読み書き | ステージ間の受け渡しそのもの |
| `UseGitHubConnection` | `codestar-connections:UseConnection` | Source アクションが GitHub から取る |
| `StartBuild` | `codebuild:StartBuild` / `BatchGetBuilds` | Build を起動して結果を待つ |
| `CodeDeployDeployment` | `codedeploy:CreateDeployment` / `GetDeployment` | Deploy を依頼して結果を待つ |
| `CodeDeployApplication` | `GetApplication` / `GetApplicationRevision` / `RegisterApplicationRevision` | appspec をリビジョンとして登録する |
| `CodeDeployConfig` | `codedeploy:GetDeploymentConfig` | `CodeDeployDefault.ECSAllAtOnce` を読む |
| `RegisterAppTaskDefinition` | `ecs:RegisterTaskDefinition` | **アプリのタスク定義を作るのはこのロール** |
| `PassTaskRoles` | `iam:PassRole` × 2 本 | → §4 |

**仕様** この構成は公式の最小権限例とほぼ同じ。

> For the `CodeDeployToECS` action (blue/green deployments), the following are the minimum
> permissions needed ... — [アクションリファレンス](https://docs.aws.amazon.com/codepipeline/latest/userguide/action-reference-ECSbluegreen.html)

**`ecs:RegisterTaskDefinition` だけ `Resource: "*"` になっている。**
タスク定義はまだ存在しないものを作る API なので、ARN で縛れない。
これは CloudFormation の `Resource: "*"` と同じ性質の話
(→ [iam-roles-and-command-permissions.md](../cloudformation/iam-roles-and-command-permissions.md) §8)。

**このロールに ECS サービスを操作する権限は無い。** タスクセットの作成もリスナーの付け替えも
CodeDeploy がやるので、依頼する側には要らない。

### 3-2. CodeBuild サービスロール

`pipeline.yml` の `CodeBuildServiceRole`。**ビルド中に走る `aws` コマンドは全部このロール。**

| Sid | できること | 使っている箇所(`deploy/buildspec.yml`) |
|---|---|---|
| `Logs` | `CreateLogStream` / `PutLogEvents` | ビルドログの書き込み(暗黙) |
| `Artifacts` | アーティファクトバケットの読み書き | 入出力アーティファクト(暗黙) |
| `EcrAuth` | `ecr:GetAuthorizationToken` | `aws ecr get-login-password` |
| `EcrPush` | `DescribeImages` / レイヤー系 / `PutImage` | 存在チェックと `docker push` |
| `ReadAppStack` | `cloudformation:DescribeStacks` | `render()` が埋める値の取得 |
| `RegisterMigrateTaskDefinition` | `ecs:RegisterTaskDefinition` | migrate のタスク定義登録 |
| `PassTaskExecutionRole` | `iam:PassRole` × 1 本 | → §4 |

**`logs:CreateLogGroup` が無いのは意図的。** ロググループは `pipeline.yml` の `BuildLogGroup` が
CloudFormation で作るので、CodeBuild 自身が作る必要が無い。
自分で作らせると保持期間を設定できず無期限になる → [env-vars-and-logs.md](env-vars-and-logs.md)。

**`ecr:GetAuthorizationToken` だけ `Resource: "*"`。**
認証トークンはレジストリ単位で発行され、リポジトリに紐づかないため絞れない。

**`DescribeStacks` は `app.yml` のスタックだけに絞ってある。**
`arn:aws:cloudformation:...:stack/${ProjectName}-${EnvName}/*` の形。
末尾の `/*` はスタック ID の部分で、スタックを建て直すと変わるのでワイルドカードが要る。

### 3-3. CodeDeploy サービスロール

`app.yml` の `CodeDeployServiceRole`。**中身は AWS 管理ポリシー 1 本だけ。**

```yaml
ManagedPolicyArns:
  - arn:aws:iam::aws:policy/AWSCodeDeployRoleForECS
```

タスクセットの作成、ALB リスナーの既定アクションの書き換え、blue の終了を、このロールでやる。

**ネイティブ Blue/Green から替わった点。** 以前は ECS 自身がリスナールールの重みを
書き換えていたので、`AmazonECSInfrastructureRolePolicyForLoadBalancers` を
`ecs.amazonaws.com` に渡していた。CODE_DEPLOY 制御では切り替えの主体が CodeDeploy に移るので、
ロールごと差し替わっている(→ ADR-0013)。

**推定** 管理ポリシー 1 本で足りているのは、このリポジトリが CodeDeploy に
最小構成しかさせていないから(Lambda フックも CloudWatch アラーム連動も入れていない)。
`DEPLOYMENT_STOP_ON_ALARM` を足すなら `cloudwatch:DescribeAlarms` が要るはず。

### 3-4. task-execution-role — ECS エージェントが使う

`app.yml` の `TaskExecutionRole`。**コンテナが起動する前に、ECS 側で使われる。**

- `AmazonECSTaskExecutionRolePolicy`(管理ポリシー) — ECR から pull、CloudWatch Logs へ書く
- `ReadSecrets`(インライン) — `ssm:GetParameters` で SecureString を読む

**「コンテナの中身」は一切このロールを使わない。**
`taskdef.json` の `secrets` に書いた `DB_PASSWORD` などを SSM から取ってきて
環境変数として注入するのは ECS エージェントで、コンテナではないから。

**アプリと migrate が共有している。** `deploy/taskdef.json` も `deploy/taskdef-migrate.json` も
`executionRoleArn` に同じ `__EXECUTION_ROLE_ARN__` が入り、`render()` が
`...-task-execution-role` を埋める。

**紛らわしい点: `db-ops-execution-role` は migrate では使わない。**
`app.yml` には 3 本目の実行ロール `DbOpsExecutionRole` があり、**RDS のマスターシークレットを
読めるのはこれだけ**だが、これを使うのは `db-task.yml` の `DbOpsTaskDefinition`
(ユーザー作成・任意 SQL)。Flyway を流す migrate タスクは共有の実行ロールを使う
(マスターは要らず、`migrate` ユーザーのパスワードで足りるため → ADR-0005)。

### 3-5. task-role — アプリ自身が使う

`app.yml` の `TaskRole`。**コンテナの中で `AWS_CONTAINER_CREDENTIALS_RELATIVE_URI` 経由で拾われる。**

- `SendEmail` — SES(`identity/${EmailIdentity}` に限定)
- `ImageObjects` / `ImageBucketList` — S3(フェーズ6 の画像用)
- `EcsExec` — `ssmmessages:*`(コンテナに入って調査するため。リソース単位に縛れない)

**`deploy/taskdef-migrate.json` には `taskRoleArn` が無い。** Flyway を流すだけで
AWS の API を叩かないから。この非対称が §4 の PassRole の非対称に直結する。

### 3-6. Chatbot のチャンネルロールとガードレールポリシー

`pipeline.yml` の `ChatbotApproveRole` と `ChatbotGuardrailPolicy`。

**この 2 つは AND で効く。実効権限は両方の積。**

| | 中身 |
|---|---|
| ガードレールポリシー | `codepipeline:PutApprovalResult` / `GetPipelineState` / `GetPipelineExecution`(このパイプラインのみ) |
| チャンネルロール | 上と同じ管理ポリシー **＋** `NotificationsOnly`(`cloudwatch:Describe*` / `Get*` / `List*`) |

**`NotificationsOnly` が片側にしか無いのは意図的。**
通知カードの描画には CloudWatch の読み取りが要るが、ガードレール側で許していないので、
**Slack から人が `cloudwatch` を叩くことはできない**(AND だから)。
「サービスが描画に使う権限」と「人が実行できる操作」を分ける形になっている。

**`GuardrailPolicies` を省略すると `AdministratorAccess` が既定で適用される。**
必ず明示する。アラート用 2 本(`app.yml`)は `AWSDenyAll` を入れていて、これは
「通知の一方向だけなら権限はゼロでよい」という ADR-0011 の判断
(→ [docs/slack/README.md](../../slack/README.md) §6-2)。

---

## 4. `iam:PassRole` が出てくる 2 か所

**タスク定義を register する行為は、そこに書いたロールを ECS に引き渡すことでもある。**
だから `RegisterTaskDefinition` とは別に `iam:PassRole` が審査される。

これが無いと「アカウント内の任意のロールを指定したタスク定義を作れる」= 権限昇格の経路になる。
`RegisterTaskDefinition` が `Resource: "*"` でしか書けないぶん、
**実質的な絞り込みは `PassRole` 側が担っている。**

| register するもの | 主体 | 渡すロール |
|---|---|---|
| `deploy/taskdef.json`(アプリ) | CodePipeline | **2 本** — `task-execution-role` と `task-role` |
| `deploy/taskdef-migrate.json` | CodeBuild | **1 本** — `task-execution-role` のみ |

**非対称なのは §3-5 の通り、migrate に `taskRoleArn` が無いから。**
`taskdef-migrate.json` に `taskRoleArn` を足すときは、CodeBuild のロール側にも
`task-role` を追加しないと register が `AccessDenied` で落ちる。

どちらも `Condition` で渡し先を限定している。**ただし宛先の数が違う。**

| | `iam:PassedToService` |
|---|---|
| CodePipeline の `PassTaskRoles` | `ecs.amazonaws.com` **と** `ecs-tasks.amazonaws.com` |
| CodeBuild の `PassTaskExecutionRole` | `ecs-tasks.amazonaws.com` のみ |

**仕様** 公式が CodeDeployToECS アクションの最小権限として載せているポリシーは 2 つとも並べている。

> ```
> "iam:PassedToService": [
>     "ecs.amazonaws.com",
>     "ecs-tasks.amazonaws.com"
> ]
> ```
> You can also add `ecs-tasks.amazonaws.com` to the list of services under the
> `iam:PassedToService` condition, as shown in the above example.

**推定** 理屈だけなら `ecs-tasks.amazonaws.com` で足りるはず。
タスク定義に書くのは実行ロールとタスクロールで、どちらもそれが引き受けるものだから
(`ecs.amazonaws.com` はサービス側にロールを渡す構成のためのもの)。
だが**本文の書き方は `ecs-tasks` のほうを後付けで説明している**ので、
公式が基準に置いているのは `ecs.amazonaws.com` のほうに見える。
実機未検証の段階で公式の最小構成から引き算する理由が無いので、両方書いてある。

**このノートを書いている過程で `ecs.amazonaws.com` が抜けていたことが分かって足した。**
渡すロールは 2 本に絞ってあるので、宛先が増えても持ち出せる範囲は広がらない。

**CodeBuild 側に足していないのは、あちらが `register-task-definition` を
直接叩くだけで、この公式ポリシーの対象外だから。**
**未検証** ここで `AccessDenied` が出るなら、CodeBuild 側にも同じ追加が要るということになる。

---

## 5. `PassRole` は「呼べる API」ではない

`iam:PassRole` という API は存在しない。`RegisterTaskDefinition` を呼んだときに
IAM 側が暗黙に評価する条件でしかない
(→ [iam-roles-and-command-permissions.md](../cloudformation/iam-roles-and-command-permissions.md) §6)。

**だから CloudTrail には `PassRole` というイベントは出ない。**
足りないときに出るのは `RegisterTaskDefinition` の失敗で、
メッセージの中に渡そうとしたロール ARN が入る形になる。

---

## 6. 実機で確かめること

- [ ] `PassTaskRoles` に `ecs.amazonaws.com` が本当に要るのか(公式に合わせて入れてあるが、
      無くても通るなら外せる)。逆に CodeBuild 側の `PassTaskExecutionRole` で
      `AccessDenied` が出たら、あちらにも足す(§4)
- [ ] `AWSCodeDeployRoleForECS` 1 本でタスクセット作成とリスナー付け替えが通るか
- [ ] Slack の承認が AND(ガードレール ∩ チャンネルロール)で本当に通るか。
      弾かれた理由は `/aws/chatbot/...`(**us-east-1**)に出る → [env-vars-and-logs.md](env-vars-and-logs.md)
- [ ] `DescribeStacks` の Resource 末尾 `/*` がスタック ID に一致するか
      (スタックを建て直したあとにも通るか)
- [ ] 権限不足がどの層のエラーとして現れるか(信頼ポリシー側かポリシー側か)
