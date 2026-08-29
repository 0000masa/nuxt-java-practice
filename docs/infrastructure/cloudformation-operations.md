# CloudFormation の運用手順(手動セットアップ・構築・撤収)

フェーズ13 で作った `cloudformation/app.yml` を動かすための手順。設計の背景は [2026-08-19-phase13-cloudformation-design.md](../superpowers/specs/2026-08-19-phase13-cloudformation-design.md)、全体方針は [README.md](./README.md)。

**新しい PC でも AWS アカウント側の設定はやり直さなくてよい**(アカウントに残る)。やり直しが必要なのは Google Cloud Console だけ。

---

## 0. 全体像

```
[一度だけやる手動セットアップ]
  1. Route53 ホストゾーン       … 既に作成済み
  2. ECR リポジトリ             … フェーズ11 で作成済み
  3. OIDC プロバイダ            … フェーズ11 で作成済み
  4. IAM ロール(ECR push 用)   … フェーズ11 で作成済み
  5. IAM ロール 3 つ(今回)     … §2
  6. テンプレート置き場の S3     … §3
  7. SSM の SecureString 4 つ   … §4
  8. GitHub の Environment      … §5
  9. Slack と AWS を接続        … docs/slack/README.md
 10. params/stg.json を埋める   … §6
 11. Google Cloud Console       … §7

[環境を建てる]        §8
[変更を反映する]      §9
[撤収する]            §10
[監視・検知]          §11
[詰まったとき]        §12
```

**ワークフローは 5 本あるが、AWS を叩くのは 3 本だけ。** `cfn-apply.yml`(CloudFormation)/ `db-task.yml`(ECS Run Task)/ `cfn-destroy.yml`(削除)が実際の操作を持ち、`cfn-deploy.yml`(構築)は **`workflow_call` で前の 2 本を呼ぶ順序だけ**を持っている(→ [ADR-0009](../adr/0009-cfn-apply-as-the-single-cloudformation-caller.md))。`ecr-push.yml` はイメージの push 専用。

---

## 1. 手動管理にしているものと、その理由

| リソース | 理由 |
|---|---|
| Route53 ホストゾーン | 作り直すと NS レコードが変わり、X-Server 側の再設定と DNS 伝播待ちが発生する |
| ECR リポジトリ | スタックより先に存在していないと push もデプロイもできない |
| OIDC プロバイダ・IAM ロール | スタックに入れると循環依存になる(→ [github-actions-oidc.md](./github-actions-oidc.md))。GitHub Actions が引き受けるロールは、それが操作する対象の外に置く |
| SSM の SecureString | 外部サービス由来の値(Google)と、人が決める値(app / migrate のパスワード)なので AWS では生成できない |
| テンプレート置き場の S3 バケット | `app.yml` が 54,178 バイトあり、**リクエストに直接載せられる上限 51,200 バイトを超えている**。CloudFormation がテンプレートを読める場所は S3(か SSM ドキュメント)だけなので中継が必要(→ §3) |
| Slack ワークスペースの認可とチャンネル | **認可はコンソールでしか行えない**(CloudFormation 不可)。チャンネルと `/invite` も Slack 側の操作。いずれも 1 回きりで、スタックを作り直しても消えない(→ [docs/slack/README.md](../slack/README.md)) |

RDS のマスターパスワードは**手動管理ではない**。`ManageMasterUserPassword: true` により RDS が生成して Secrets Manager が保持し、DB を削除するとシークレットも一緒に消える。

---

## 2. IAM ロールを 3 つ作る

**サービスロール方式**を採っている。Actions が持つのは「CloudFormation を叩く権限」だけで、リソースを作るのは CloudFormation が引き受けるロール。Actions の一時クレデンシャルが漏れても、**テンプレートに書かれていないことはできない**。

```
cfn-*.yml が引き受けるロール(cloudformation:* + PassRole)
  ↓ create-change-set --role-arn
CloudFormation サービスロール(リソース作成権限)
  ↓
AWS リソース

db-task.yml が引き受けるロール(ecs:RunTask + PassRole + logs 読み取り)
  ↓ ecs run-task
ECS タスク(db-ops / db-migrate)
```

**Actions 側のロールは 2 つに分けている。** `db-task.yml` は任意 SQL を流せるワークフローなので、
その実行に `cloudformation:*` を持つクレデンシャルを降ろさない(→ §2-3)。

> **どのコマンドにどの権限が要るのか、`--role-arn` がスタックに紐づくとはどういうことか、`iam:PassRole` は何を審査している権限なのか** — この節のポリシーがなぜこの形なのかの解説 → [コマンドと IAM 権限](../notes/cloudformation/iam-roles-and-command-permissions.md)

### 2-1. CloudFormation サービスロール

`nuxt-java-practice-cfn-service-stg` を作る。信頼するのは CloudFormation 自身。

```bash
aws iam create-role \
  --role-name nuxt-java-practice-cfn-service-stg \
  --description "CloudFormation service role for the stg stack" \
  --assume-role-policy-document '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Principal": { "Service": "cloudformation.amazonaws.com" },
      "Action": "sts:AssumeRole"
    }]
  }'
```

権限は **`AdministratorAccess`(AWS 管理ポリシー)1 本**にする。

```bash
aws iam attach-role-policy \
  --role-name nuxt-java-practice-cfn-service-stg \
  --policy-arn arn:aws:iam::aws:policy/AdministratorAccess
```

中身は [AdministratorAccess](https://docs.aws.amazon.com/aws-managed-policy/latest/reference/AdministratorAccess.html) のとおり 1 文だけ。

```json
{ "Version": "2012-10-17", "Statement": [{ "Effect": "Allow", "Action": "*", "Resource": "*" }] }
```

**列挙をやめた理由は維持コスト。** テンプレートにリソース型を足すたびに権限も足すことになり、しかも権限不足は `CREATE_FAILED` になってから分かる。スタックを作り直しながら往復することになる。

**「列挙しても実質管理者だから危険度は同じ」ではない。** 列挙版は爆発半径が書いたサービス(`ec2` / `rds` / `ecs` など)に限られるが、`AdministratorAccess` は IAM ユーザーの作成、他スタックのリソース削除、Organizations の操作まで通る。**同じではないと分かったうえで採る判断。** 許容する前提は 3 つ。

- 検証専用の単一スタックで、常時公開しない
- `iam:CreateRole` / `iam:PassRole` を持たせた時点で、列挙版にもすでに昇格経路があった
- **境界は別のところにある。** Actions が引き受けるロールが持つのは `cloudformation:*` と `iam:PassRole` だけなので、「テンプレートに書かれていないことはできない」という設計(→ 設計書 決定16)は変わらない

**SCP と permission boundary は `AdministratorAccess` の上を行く。** Organizations の SCP や permission boundary で拒否されているものは、このポリシーがあっても通らない。

#### すでにロールを作ってしまったとき

作成時にインラインポリシー(`--policy-name CfnProvision`)を入れた場合は、管理ポリシーを付けてからインラインを消す。

```bash
ROLE=nuxt-java-practice-cfn-service-stg

# 1. 先に付ける(権限が空になる瞬間を作らない)
aws iam attach-role-policy --role-name "$ROLE" \
  --policy-arn arn:aws:iam::aws:policy/AdministratorAccess

# 2. 列挙していたインラインポリシーを消す
aws iam delete-role-policy --role-name "$ROLE" --policy-name CfnProvision

# 3. 確認(管理ポリシーは AdministratorAccess の 1 つ、インラインは空になる)
aws iam list-attached-role-policies --role-name "$ROLE"
aws iam list-role-policies --role-name "$ROLE"
```

**`attach-role-policy` と `delete-role-policy` は別系統のコマンド。** 前者は管理ポリシー(AWS が持つポリシーを参照する)、後者はインラインポリシー(ロールに直接埋め込む)を対象にする。作成時に使った `put-role-policy` はインライン側。**信頼ポリシーは変更不要**なので `update-assume-role-policy` は要らない。

#### 最小権限に戻すなら必要になるもの(参考)

列挙に戻すときのために、実際に必要だった 3 点を残す。

- `secretsmanager:CreateSecret` / `TagResource` / `kms:DescribeKey` は **`ManageMasterUserPassword` に必須**(RDS ユーザーガイドに明記されている)
- `iam:CreateServiceLinkedRole` は ECS・RDS・Application Auto Scaling が初回にサービスリンクロールを作るのに要る
- `iam:PassRole` は、作ったロール(実行ロール / タスクロール)を ECS に渡すのに要る

### 2-2. GitHub Actions が引き受けるロール

`nuxt-java-practice-gha-cfn-stg` を作る。**信頼ポリシーが今回の要点。**

```bash
# ${ACCOUNT_ID} は自分のアカウント ID に置き換える
aws iam create-role \
  --role-name nuxt-java-practice-gha-cfn-stg \
  --description "GitHub Actions: deploy the stg CloudFormation stack" \
  --assume-role-policy-document '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Principal": {
        "Federated": "arn:aws:iam::${ACCOUNT_ID}:oidc-provider/token.actions.githubusercontent.com"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
          "token.actions.githubusercontent.com:sub": "repo:0000masa@134136756/nuxt-java-practice@1303585339:environment:stg"
        },
        "StringLike": {
          "token.actions.githubusercontent.com:ref": "refs/heads/main"
        }
      }
    }]
  }'
```

**`sub` の形に 2 つ注意点がある。**

1. **オーナー ID とリポジトリ ID が入る。** `repo:0000masa@134136756/nuxt-java-practice@1303585339:...` の形。GitHub の「不変サブジェクトクレーム」で 2026-07-15 以降に作られたリポジトリに自動適用される(フェーズ11 で判明した仕様 → [github-actions-oidc.md](./github-actions-oidc.md) §8)
2. **`environment:` を指定すると `sub` の末尾が `ref:...` ではなく `environment:...` に変わる。** そのため**ブランチ制限は `sub` ではなく別クレーム `ref` の条件**で掛ける。GitHub Free のプライベートリポジトリでは Environment の protection rules(ブランチ制限・required reviewers)が使えないので、AWS 側で縛る形になる

権限ポリシー:

```bash
aws iam put-role-policy \
  --role-name nuxt-java-practice-gha-cfn-stg \
  --policy-name DeployStack \
  --policy-document '{
    "Version": "2012-10-17",
    "Statement": [
      {
        "Sid": "CloudFormation",
        "Effect": "Allow",
        "Action": "cloudformation:*",
        "Resource": "*"
      },
      {
        "Sid": "PassServiceRole",
        "Effect": "Allow",
        "Action": "iam:PassRole",
        "Resource": "arn:aws:iam::${ACCOUNT_ID}:role/nuxt-java-practice-cfn-service-stg",
        "Condition": {
          "StringEquals": { "iam:PassedToService": "cloudformation.amazonaws.com" }
        }
      },
      {
        "Sid": "PutTemplate",
        "Effect": "Allow",
        "Action": ["s3:PutObject", "s3:GetObject"],
        "Resource": "arn:aws:s3:::nuxt-java-practice-cfn-templates-${ACCOUNT_ID}/*"
      },
      {
        "Sid": "EmptyBuckets",
        "Effect": "Allow",
        "Action": ["s3:ListBucket", "s3:DeleteObject"],
        "Resource": [
          "arn:aws:s3:::nuxt-java-practice-stg-images",
          "arn:aws:s3:::nuxt-java-practice-stg-images/*",
          "arn:aws:s3:::nuxt-java-practice-stg-logs-archive",
          "arn:aws:s3:::nuxt-java-practice-stg-logs-archive/*"
        ]
      }
    ]
  }'
```

**`iam:PassedToService` の条件を付けているのが要点。** これが無いと「ロールを渡す」権限がどのサービスにでも使えてしまい、権限昇格の経路になる。

`PutTemplate` はテンプレートを S3 に置くために要る(→ §3)。**CloudFormation サービスロール側は `s3:*` を持っているので追加不要**(置いたテンプレートを読むのは CloudFormation)。

**`EmptyBuckets` にはバケットが 2 つ並ぶ。** 素の CloudFormation に `force_destroy` 相当が無いので、撤収ワークフローが `aws s3 rm --recursive` で空にしてから消している。**フェーズ14 でログアーカイブのバケットが増えたので、ここに ARN を足していないと撤収が必ず失敗する。**

**`--description` に日本語は使えない**(`[	

 -~¡-ÿ]*` の制約。フェーズ11 で踏んだ)。

### 2-3. `db-task.yml` が引き受けるロール

`nuxt-java-practice-gha-dbtask-stg` を作る。**信頼ポリシーは §2-2 と同じ**(同じリポジトリの同じ Environment から引き受けるため)。

```bash
# ${ACCOUNT_ID} は自分のアカウント ID に置き換える
aws iam create-role \
  --role-name nuxt-java-practice-gha-dbtask-stg \
  --description "GitHub Actions: run one-off DB tasks on the stg stack" \
  --assume-role-policy-document '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Principal": {
        "Federated": "arn:aws:iam::${ACCOUNT_ID}:oidc-provider/token.actions.githubusercontent.com"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
          "token.actions.githubusercontent.com:sub": "repo:0000masa@134136756/nuxt-java-practice@1303585339:environment:stg"
        },
        "StringLike": {
          "token.actions.githubusercontent.com:ref": "refs/heads/main"
        }
      }
    }]
  }'
```

権限ポリシー。**`db-task.yml` が実際に呼ぶ 5 つだけ**で、すべてリソースを特定している。

```bash
aws iam put-role-policy \
  --role-name nuxt-java-practice-gha-dbtask-stg \
  --policy-name RunDbTask \
  --policy-document '{
    "Version": "2012-10-17",
    "Statement": [
      {
        "Sid": "ReadStackOutputs",
        "Effect": "Allow",
        "Action": "cloudformation:DescribeStacks",
        "Resource": "arn:aws:cloudformation:ap-northeast-1:${ACCOUNT_ID}:stack/nuxt-java-practice-stg/*"
      },
      {
        "Sid": "RunDbTask",
        "Effect": "Allow",
        "Action": "ecs:RunTask",
        "Resource": [
          "arn:aws:ecs:ap-northeast-1:${ACCOUNT_ID}:task-definition/nuxt-java-practice-stg-db-ops:*",
          "arn:aws:ecs:ap-northeast-1:${ACCOUNT_ID}:task-definition/nuxt-java-practice-stg-db-migrate:*"
        ],
        "Condition": {
          "ArnEquals": {
            "ecs:cluster": "arn:aws:ecs:ap-northeast-1:${ACCOUNT_ID}:cluster/nuxt-java-practice-stg-cluster"
          }
        }
      },
      {
        "Sid": "WatchDbTask",
        "Effect": "Allow",
        "Action": "ecs:DescribeTasks",
        "Resource": "arn:aws:ecs:ap-northeast-1:${ACCOUNT_ID}:task/nuxt-java-practice-stg-cluster/*",
        "Condition": {
          "ArnEquals": {
            "ecs:cluster": "arn:aws:ecs:ap-northeast-1:${ACCOUNT_ID}:cluster/nuxt-java-practice-stg-cluster"
          }
        }
      },
      {
        "Sid": "PassTaskRoles",
        "Effect": "Allow",
        "Action": "iam:PassRole",
        "Resource": [
          "arn:aws:iam::${ACCOUNT_ID}:role/nuxt-java-practice-stg-task-execution-role",
          "arn:aws:iam::${ACCOUNT_ID}:role/nuxt-java-practice-stg-db-ops-execution-role"
        ],
        "Condition": {
          "StringEquals": { "iam:PassedToService": "ecs-tasks.amazonaws.com" }
        }
      },
      {
        "Sid": "ReadTaskLogs",
        "Effect": "Allow",
        "Action": "logs:GetLogEvents",
        "Resource": "arn:aws:logs:ap-northeast-1:${ACCOUNT_ID}:log-group:/ecs/nuxt-java-practice-stg:*"
      }
    ]
  }'
```

**`iam:PassRole` が要るのは、ロールを「引き受ける」からではなく「ECS に渡す」から。** タスク定義には `ExecutionRoleArn` と `TaskRoleArn` が書かれていて、`run-task` を呼ぶとその 2 本を ECS(`ecs-tasks.amazonaws.com`)に引き受けさせることになる。この許可が独立しているのは権限昇格を防ぐためで、無条件に許すと弱い権限の主体が強いロールのタスクを起動して、そのタスク越しに強い権限を使えてしまう。ここで 2 本に限定しているのは、実行ロールが **SSM の SecureString** を読み、`db-ops-execution-role` に至っては **RDS のマスターパスワードのシークレット**まで読めるため。

渡し先の**タスク定義**は `nuxt-java-practice-stg-db-ops` と `nuxt-java-practice-stg-db-migrate` の 2 つ。どちらもタスクロールを持たず(AWS を呼ばないので不要)、**実行ロールだけが違う**。

| タスク定義 | 実行ロール | 何を読めるか |
|---|---|---|
| `...-db-migrate` | `...-task-execution-role`(アプリと共有) | SSM の SecureString 4 つ |
| `...-db-ops` | `...-db-ops-execution-role`(専用) | DB のパスワード 2 つ + **RDS のマスターシークレット** |

**実行ロールを分けているのが最小権限の実体。** 共有ロールにマスターシークレットを入れると、アプリのタスク定義からもマスターの値を注入できてしまい、「マスターに触るのは `db-ops` だけ」が成立しない(→ [ADR-0005](../adr/0005-separate-db-users-for-app-and-migration.md))。

| | 実行ロール(`ExecutionRoleArn`) | タスクロール(`TaskRoleArn`) |
|---|---|---|
| 誰が使うか | **ECS エージェント**(タスクを起動するために) | **コンテナの中のプロセス**(AWS SDK で AWS を呼ぶために) |
| 何に使うか | ECR から pull / ログを書く / `Secrets` の値を取ってコンテナに渡す | アプリなら SES 送信・S3 の読み書き・ECS Exec |
| 省略 | できない | できる(AWS を呼ばないタスクなら不要) |

**DB への接続にはどちらのロールも関係しない。** RDS には MySQL のユーザー名とパスワードで繋ぐ。パスワードは `Secrets` 経由で実行ロールが取得し、コンテナには環境変数として渡るだけなので、コンテナ自身は AWS を一度も呼ばない。`db-migrate` がタスクロールを持たないのはそのため(Flyway は AWS API を使わない)。ネットワーク到達性は SG(ECS → RDS の 3306)が担保している。
同じ形は AWS のマネージドポリシー [`AmazonEC2ContainerServiceEventsRole`](https://docs.aws.amazon.com/aws-managed-policy/latest/reference/AmazonEC2ContainerServiceEventsRole.html)(EventBridge が ECS タスクを起動するためのロール)でも使われている。

**`ecs:RunTask` のリソースはタスク定義。** [Identity-based policy examples for Amazon ECS](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/security_iam_id-based-policy-examples.html) に「The resources for `RunTask` are task definitions」と明記されていて、クラスタは `ecs:cluster` 条件で絞る。`:*` は「そのファミリーの全リビジョン」の意味で、スタックを更新するたびにリビジョンが上がるので必須。`ecs:DescribeTasks` 側のリソースはタスク ARN(`task/<クラスタ名>/<タスク ID>`)で、タスク ID は実行のたびに変わるのでワイルドカードにしている。

**タスク定義を増やしたら、このポリシーにも足すこと。** 絞った代償で、`app.yml` に DB タスクを追加しただけでは `run-task` が `AccessDeniedException` になる。

#### 既存のロールから権限を移すときの順序

`nuxt-java-practice-gha-cfn-stg` から `RunDbTask` / `PassTaskRoles` / `ReadTaskLogs` を外す作業は、**新しいロールに切り替わってから**行う。

```
1. 新ロールを作る(上の 2 コマンド)
2. Environment stg に AWS_DB_TASK_ROLE_ARN を登録する(→ §5)
3. db-task.yml の変更を main に反映する
4. gha-cfn-stg のポリシーを §2-2 の内容(3 つを外したもの)に貼り替える
```

4 を先にやると、その間 `db-task.yml` が動かなくなる。

---

## 3. テンプレート置き場の S3 バケットを作る

`cloudformation/app.yml` は **54,178 バイト**あり、CloudFormation が**リクエストに直接受け取れる上限 51,200 バイト**を超えている。`TemplateURL` で渡せる場所は **S3 バケットか Systems Manager ドキュメントだけ**で、GitHub の raw URL は渡せない(`CreateChangeSet` の API リファレンスに「S3 の静的ウェブサイト URL は非対応」とまで書かれている)。

**GitHub が唯一の正であることは変わらない。** ここに置くオブジェクトはビルド成果物と同じ受け渡し物で、30 日で自動削除する。アプリのコードを GitHub で管理しつつ、ビルドしたイメージを ECR に置いているのと同じ関係。**Terraform の tfstate 用バケットとは性質が違う**(あちらは失うと管理不能になる状態)→ [テンプレートの分割と置き場](../notes/cloudformation/templates-and-prerequisites.md)。

このバケットを常駐させる決定と却下案 → [ADR-0008](../adr/0008-template-bucket-as-resident-resource.md)。

```bash
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
BUCKET=nuxt-java-practice-cfn-templates-$ACCOUNT_ID

# バケット名はグローバルに一意でなければならないのでアカウント ID を付ける。
# ワークフローも同じ規則で名前を組み立てるので、設定を渡す必要がない。
aws s3api create-bucket --bucket "$BUCKET" \
  --region ap-northeast-1 \
  --create-bucket-configuration LocationConstraint=ap-northeast-1

# 公開しない。テンプレートにはリソース構成が全部書かれている
aws s3api put-public-access-block --bucket "$BUCKET" \
  --public-access-block-configuration \
  'BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true'

# 溜め続ける必要はない。反映のたびに 54 KB が増えるだけなので 30 日で捨てる
aws s3api put-bucket-lifecycle-configuration --bucket "$BUCKET" \
  --lifecycle-configuration '{
    "Rules": [{
      "ID": "expire-templates",
      "Status": "Enabled",
      "Filter": { "Prefix": "templates/" },
      "Expiration": { "Days": 30 }
    }]
  }'
```

暗号化(SSE-S3)は 2023 年 1 月以降に作るバケットで既定で有効なので、明示的な設定は不要。

**このバケットは撤収しても残る。** 中身は数十 KB なので費用は実質ゼロ。

---

## 4. SSM に SecureString を 4 つ作る

```bash
P=/nuxt-java-practice/stg

# DB のユーザー(→ docs/adr/0005)。
# 注意: この値は SQL の文字列リテラルに埋め込まれるので、
# シングルクォートとバックスラッシュを含めないこと(詳細 → §4-1)。
aws ssm put-parameter --type SecureString --name "$P/app_db_password"     --value '<32文字くらいの英数字>'
aws ssm put-parameter --type SecureString --name "$P/migrate_db_password" --value '<32文字くらいの英数字>'

# Google ログイン(→ docs/setup/google-oauth.md)
aws ssm put-parameter --type SecureString --name "$P/google_client_id"     --value '<Google のクライアント ID>'
aws ssm put-parameter --type SecureString --name "$P/google_client_secret" --value '<Google のクライアントシークレット>'
```

RDS のマスターパスワードはここに置かない(RDS が Secrets Manager に作る)。

### 4-1. DB パスワードの長さと使えない文字

**MySQL 側にはほぼ制限が無い。効いてくるのは `app.yml` の経路のほう。**

| | 制限 |
|---|---|
| MySQL のパスワード長 | **実質上限なし。** 平文は保存されず `caching_sha2_password` の固定長ハッシュになる |
| MySQL が禁じる文字 | **無い。** エスケープさえ正しければ `'` も `\` も通る |
| 複雑性の要求 | `validate_password` コンポーネントが入っていれば掛かるが、**RDS MySQL 8.0 の既定パラメータグループには入っていない。** 確かめるなら `db-task`(任意 SQL)で `SELECT * FROM mysql.component;` |
| SSM SecureString の値 | 標準ティアで 4,096 文字まで。32 文字なら余裕 |
| (参考)MySQL のユーザー名 | **32 文字**(`mysql.user.User` が `char(32)`)。MySQL 側で唯一のハードな長さ制限。決めるのは `params` の `DbAppUsername` / `DbMigrateUsername` |

**`'` と `\` が使えないのは MySQL のせいではなく db-ops タスクのせい。** パスワードをエスケープせず SQL の文字列リテラルに直接埋め込んでいる(`IDENTIFIED BY '$APP_DB_PASSWORD'`)ので、この 2 文字だけが構文を壊す。

| 文字 | 可否 |
|---|---|
| `'` | **NG。** リテラルが途切れて構文エラーになる |
| `\` | **NG。** MySQL の文字列リテラルではエスケープ文字 |
| `"` / `` ` `` / `$` / `&` / `;` / `%` / `/` / `+` / `=` などの記号 | **OK。** シェルは変数展開の結果を再解釈しないので、`$` やバッククォートが混ざっても展開されない |
| 空白・改行・非 ASCII | 避ける。動く見込みはあるが試す価値がない |
| `${` という並び | 避ける。`application.yml` の `password: ${DB_PASSWORD:password}` はプレースホルダを再帰的に解決するので、値の中の `${...}` が解釈されうる |

**英数字 32 文字にしておけば全部避けられる。**

```bash
LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32; echo

# base64 でもよい。出力に現れる A-Za-z0-9/+= に ' と \ は含まれない
openssl rand -base64 24
```

**RDS のマスターパスワードのルールはこれとは別物**(8〜41 文字、印字可能 ASCII から `/` `"` `@` と空白を除く)。ただし生成するのは RDS なので、人が満たしにいくことはない。

---

## 5. GitHub 側の設定

**Settings → Environments → New environment** で `stg` を作り、**Environment secrets** に 4 つ登録する。

| Secret 名 | 値 | 使うワークフロー |
|---|---|---|
| `AWS_CFN_DEPLOY_ROLE_ARN` | `nuxt-java-practice-gha-cfn-stg` の ARN | `cfn-apply` / `cfn-destroy` |
| `AWS_CFN_SERVICE_ROLE_ARN` | `nuxt-java-practice-cfn-service-stg` の ARN | 同上(`--role-arn` に渡す) |
| `AWS_DB_TASK_ROLE_ARN` | `nuxt-java-practice-gha-dbtask-stg` の ARN | `db-task` |
| `BASIC_AUTH_CREDENTIAL` | `user:password` の形(コロン区切りの生の文字列) | `cfn-apply` |

**Environment secrets にしているのがポイント。** prod を作るときは Environment `prod` に同じ名前で別の値を入れれば、ワークフローのコードは一切変えずに切り替わる。

Basic 認証の値は `params/stg.json` には置かない。**base64 化はテンプレート側の `Fn::Base64` が行う**ので、ここには生の `user:password` を入れる。

**アラートの通知先はここには無い。** Slack に流すようになり、必要なのはワークスペース ID とチャンネル ID の 3 つだけになった。**いずれも秘密ではないので `params` に平文で置く**(→ §6・[ADR-0011](../adr/0011-slack-notification-with-chatbot.md))。フェーズ14 まであった `ALERT_EMAIL` は不要。

なお **GitHub Free のプライベートリポジトリでは protection rules(required reviewers・ブランチ制限)が使えない。** ブランチ制限は IAM の信頼ポリシー(§2-2)で、任意 SQL の制限はワークフロー側の分岐で埋めている。

---

## 6. `params/stg.json` を埋める

`REPLACE_WITH_...` のプレースホルダが 4 つある。

**`HostedZoneId`**:

```bash
aws route53 list-hosted-zones-by-name --dns-name mylabinfra.com \
  --query 'HostedZones[0].Id' --output text
# /hostedzone/Z0123456789ABCDEFG のように返るので、末尾の Z... だけを使う
```

**`SlackWorkspaceId` / `SlackChannelIdEcs` / `SlackChannelIdRds`**: Slack 側の手動作業で得る → **[docs/slack/README.md](../slack/README.md)**。

置き換え忘れると **Change Set の作成が `must match pattern` で落ちる**(テンプレート側に `AllowedPattern` を付けてある)。プレースホルダのまま構築が成功して通知が無音になるより、止まるほうがマシという判断。

---

## 7. Google Cloud Console と SES

### Google

承認済みリダイレクト URI に **`https://stg.njp.mylabinfra.com/api/login/oauth2/code/google`** を追加する(開発用の `http://localhost:3000/...` は残したまま)。**完全一致**なので末尾スラッシュも含めて正確に。

この URI は `APP_BASE_URL` から組み立てられる(`application.yml` の `redirect-uri`)。テンプレートが `APP_BASE_URL` に `https://<EnvName>.<AppSubdomain>.<DomainName>` を入れるので、両者は必ず一致する。

**触るのはこの 1 欄だけ。「承認済みの JavaScript 生成元」に本番ドメインを足す必要は無い。** このアプリはサーバーサイドの認可コードフロー(`<a href="/api/oauth2/authorization/google">` でフルページ遷移し、トークン交換は Spring Boot がサーバー間で行う)なので、ブラウザの JS が Google を呼ばない → [google-oauth.md](../setup/google-oauth.md)。

### SES

**このアカウントは本番アクセス済み(サンドボックス解除済み)。手動でやることは無い。** 任意の宛先に送れるので、フェーズ13 の設計書にあった「受信アドレスを `create-email-identity` で検証しておく」手順は**不要になった**。

```bash
# 状態を確かめたいとき。ProductionAccessEnabled が true
aws sesv2 get-account --query '{Production:ProductionAccessEnabled,Sending:SendingEnabled}'
```

**送信元(ドメイン)の検証はサンドボックスとは別の話で、解除後も必須。** `stg.njp.mylabinfra.com` の検証は**スタックがやる**(`AWS::SES::EmailIdentity` と Route53 の DKIM CNAME)。ただし後述のとおり検証完了は待たないので、建てた直後は送れない。

#### 解除したことで増える注意

サンドボックスは「事故を防ぐ柵」でもあった。**外れた以上、宛先はこちらの責任で選ぶ。**

- **バウンス率と苦情率がアカウントの評価に効く。** SES はバウンス 5% / 苦情 0.1% を超えると審査対象にし、悪化すると送信を止める。**動作確認で存在しないアドレスを打ち込まないこと。** サンドボックスでは検証済みアドレスにしか飛ばなかったので、そもそも起こらなかった事故
- このアプリは**サインアップ時に入力されたアドレスへ確認メールを送る**。外部から勝手に叩かれないのは Basic 認証 + WAF のおかげであって、SES 側に防御があるわけではない
- 送信上限は上がる(サンドボックスの 1 日 200 通 / 1 秒 1 通から、既定で 1 日 50,000 通 / 1 秒 14 通程度)。**この用途で上限に当たることはない**

---

## 8. 環境を建てる

```
1. Actions → 「ECR へイメージを push」を実行
   → ジョブサマリに出るイメージタグ(短縮 SHA)を控える

2. Actions → 「CloudFormation スタックを作成/更新」を実行
   inputs: env=stg / image_tag=<控えたタグ> / dry_run=false

   中で 5 段動く:
     deploy-zero    … DesiredCount=0 でスタック作成   (cfn-apply.yml を呼ぶ)
     create-db-users … app / migrate ユーザーを作る   (db-task.yml を呼ぶ)
     migrate        … Flyway                          (db-task.yml を呼ぶ)
     deploy-service … DesiredCount を params の値に上げる (cfn-apply.yml を呼ぶ)
     summary        … 締めのサマリ(AWS は叩かない)

3. SES の検証が通るのを待つ(初回のみ。数分〜十数分)
   aws sesv2 get-email-identity --email-identity stg.njp.mylabinfra.com \
     --query VerifiedForSendingStatus

4. ブラウザで https://stg.njp.mylabinfra.com を開く
   → Basic 認証のダイアログが出る。BASIC_AUTH_CREDENTIAL の値を入力する
```

**なぜ 2 段階なのか。** 必要な順序は `RDS → ユーザー作成 → マイグレーション → サービス起動` だが、CloudFormation には「タスクを流してからサービスを起動する」を表現する手段がない。しかも **ECS サービスは安定するまで最大 3 時間ポーリングされる**ので、起動できない状態で作るとスタックが 3 時間後に失敗する。`DesiredCount=0` なら即座に安定するので、その間に Run Task を回す。

**CloudFormation を実際に叩いているのは `cfn-apply.yml`。** このワークフローに aws コマンドは 1 つも無く、`workflow_call` で `cfn-apply.yml` と `db-task.yml` を呼ぶ順序だけを持っている(→ [ADR-0009](../adr/0009-cfn-apply-as-the-single-cloudformation-caller.md))。1 段目と 4 段目に渡している `web_desired_count` / `allow_missing_stack` / `allow_zero_desired_count` は、`cfn-apply.yml` が既存環境を守るために持っている guard を構築フローに対してだけ開ける鍵で、**`workflow_dispatch` には宣言されていない**(だから Actions の UI からは触れない)。

`dry_run=true` にすると Change Set を作って**実行せずに削除して**終わる。ここでの狙いは差分の中身ではなく **pre-flight** で、「CREATE の Change Set が本当に作れるか」= テンプレートが CloudFormation 側の検証を通るか + サービスロールで作れるかを、15〜25 分かかる本番の作成の前に数十秒で確かめられる(`app.yml` は 51,200 バイト超で `validate-template --template-body file://` が使えないので、手元では `cfn-lint` しか掛けられない)。新規作成の差分は全リソースが `Add` になるだけなので、サマリには「新規作成: N リソース」の 1 行しか出さない。2 段目以降は skip される。

**初回作成のときは、リソースを持たないスタックが `REVIEW_IN_PROGRESS` のまま残る。** `CreateChangeSet` を新規スタックに対して実行すると、CloudFormation はスタック ID だけを作ってリソースは何も作らない状態(`REVIEW_IN_PROGRESS`)にする。Change Set を削除してもこのスタックは消えない(`delete-change-set` は Change Set を消すだけで、スタックには触らない → [CLI コマンドのノート §8-2](../notes/cloudformation/cli-commands-and-change-sets.md))。

放置して構わない。**`cfn-apply.yml` の precheck が `REVIEW_IN_PROGRESS` を「スタックが無い」と同じ扱いにして `CREATE` の Change Set を作り直す**ので、次の構築はそのまま通る。もともと `aws cloudformation deploy` が内部で同じことをしていた挙動(aws-cli の `deployer.has_stack` が明示的にこの状態を除いている)を、`deploy` を使わなくなったので自分で持っている。**`dry_run` がスタックを残すことと、CREATE 判定がそのスタックを「無い」とみなすことは依存関係にあるので、片方だけ直すと壊れる。** コンソールから消したければ `aws cloudformation delete-stack --stack-name nuxt-java-practice-stg` でよい(リソースが無いので即座に終わる)。

### 途中で失敗したとき

**基本は「このワークフローをもう一度流す」。** 中途半端な環境は必ず `WebDesiredCount=0` に収束するので、再実行が安全に通る。1 段目は差分ゼロで素通りし(`cfn-apply.yml` は差分ゼロのとき成功して終わる)、4 段目が 0 → params の値に上げ直す。例外は CREATE そのものが失敗したときだけ。

| 失敗した段 | スタックの残り方 | `cfn-apply` を dispatch すると | 正しい次の操作 |
|---|---|---|---|
| 1 段目(新規作成) | `ROLLBACK_COMPLETE` | 「作成に失敗したスタックは更新できません」 | **`cfn-destroy` → `cfn-deploy`** |
| 1 段目(既存への更新) | `UPDATE_ROLLBACK_COMPLETE` / `DesiredCount=0` | 「`WebDesiredCount` が 0 です」 | `cfn-deploy` を再実行 |
| 2 段目(create-db-users) | `CREATE_COMPLETE` / `DesiredCount=0` | 同上 | `db-task`(create-db-users)単体、または `cfn-deploy` 再実行 |
| 3 段目(migrate) | 同上 | 同上 | `db-task`(migrate)単体、または `cfn-deploy` 再実行 |
| 4 段目(サービス起動) | `UPDATE_ROLLBACK_COMPLETE` / **`DesiredCount` は 0 に巻き戻る** | 同上 | 原因を直して `cfn-deploy` 再実行 |

4 段目の巻き戻りは都合が良い方向に働く。CloudFormation が UPDATE をロールバックすると `WebDesiredCount` パラメータも前の値(0)に戻るので、**どこで失敗しても「`DesiredCount=0`」という 1 つの状態に収束**し、`cfn-apply` の precheck の 1 行がそれを全部拾う。

create-db-users の失敗で多いのは **SSM に入れたパスワードに `'` か `\` が含まれている**ケース(SQL の文字列リテラルに埋め込まれるため → §4)。ログは Actions のジョブサマリに出るロググループとタスク ID から辿る。

---

## 9. 変更を反映する(既存の環境に対して)

> 各ステップが叩いている `aws cloudformation` のコマンド、オプション、`Status` / `StatusReason` に何が返るかの解説 → [CloudFormation の CLI コマンドを読み解く](../notes/cloudformation/cli-commands-and-change-sets.md)

既に建っている環境に `cloudformation/app.yml` や `params/*.json` の変更を反映したいとき、**`cfn-deploy.yml` は使わない。** あちらは「何も無い状態から建てる」ための順序(`DesiredCount` を 0 に落として DB を用意してから上げ直す)を持っていて、動いているサービスにそれをやるのは停止に等しい。

```
Actions → 「CloudFormation スタックを反映(更新のみ)」を実行
  inputs: env=stg / image_tag=(空) / dry_run=false / allow_replacement=false

  1. 前提を確かめる       … スタックの存在 / 状態 / DesiredCount≠0
  2. テンプレートを S3 に置く
  3. Change Set を作る
  4. 差分をジョブサマリに出す + Replacement を判定
  5. Change Set を実行して完了を待つ
```

**アプリのイメージを更新するのもこのワークフロー。** `image_tag` に `ecr-push.yml` のサマリに出たタグを入れる。空のままにすると**今デプロイされているタグを維持**したまま、テンプレートと `params` の変更だけが反映される(→ [ADR-0007](../adr/0007-app-deploy-inside-cloudformation.md))。

### 3 本の使い分け

| やりたいこと | 使うワークフロー |
|---|---|
| 何も無い状態から環境を建てる | `cfn-deploy.yml`(構築) |
| `app.yml` / `params` の変更を反映する | **`cfn-apply.yml`(反映)** |
| 新しいイメージをデプロイする | **`cfn-apply.yml`** に `image_tag` を渡す |
| create-db-users / migrate をやり直す、任意 SQL | `db-task.yml` |
| 環境を消す | `cfn-destroy.yml`(撤収) |

### 差分だけ見たいとき

`dry_run=true` で実行すると Change Set を作って差分をサマリに出し、**実行せずに Change Set を削除**して終わる。決定15 のとおり、差分が意味を持つのは既存スタックを更新するときなので、この使い方はここでだけ効く。

### CloudFormation の外から ECS を触らないこと

`aws ecs update-service` を手で叩いても、CloudFormation はドリフトを検知しないので**即座には戻らない**。しかし次にテンプレート側で ECS サービスかタスク定義に差分が出た瞬間、CloudFormation が記憶しているリビジョン(= 古い `ImageTag`)に巻き戻る。緊急で手で叩いたときは、**同じイメージタグを `image_tag` に入れて `cfn-apply.yml` を流し、記憶を合わせておく。**

---

## 10. 撤収する

```
Actions → 「CloudFormation スタックを削除」を実行(confirm に destroy と入力)
  1. 画像バケットを空にする
  2. delete-stack + wait(RDS と NAT があるので 10〜15 分)
```

**このワークフローは stg 専用。** 本番のスタックを消すボタンは作らない。

**残るもの**(手動管理なので消えない): Route53 ホストゾーンとドメイン代 / ECR とイメージ / OIDC プロバイダ / IAM ロール 4 つ / SSM の SecureString 4 つ / ACM 証明書は削除される(発行済みで放置しても無料なので影響なし)

撤収し忘れると課金が続くもの: **NAT Gateway(約 $0.062/時)・RDS・ALB・WAF(Web ACL 月 $5 + ルール 月 $1 の時間割り)**。

---

## 11. 監視・検知(通知を受け取れる状態にする)

> 設計 → [フェーズ14 の設計書](../superpowers/specs/2026-08-28-phase14-monitoring-design.md) / [フェーズ15 の設計書](../superpowers/specs/2026-08-28-phase15-slack-notification-design.md)、方針 → [ADR-0010](../adr/0010-monitoring-in-ephemeral-stack.md) / [ADR-0011](../adr/0011-slack-notification-with-chatbot.md)

スタックはアラーム 7 本と SNS トピック 2 本を作り、**通知は Slack に流れる。**

### 11-1. 通知は Slack。毎回踏む手作業は無い

| トピック | チャンネル | 何が飛んでくるか |
|---|---|---|
| `nuxt-java-practice-stg-ecs-task-shortage` | `#njp-alerts-ecs` | ECS のタスク数不足 |
| `nuxt-java-practice-stg-rds-alerts` | `#njp-alerts-rds` | RDS のログ / メトリクス / イベント |

**フェーズ14 まではメール通知で、建てるたびに SNS の購読確認メールを 2 通踏む必要があった。**踏むまで 1 通も届かないのにスタックは緑になり、片方だけ踏み忘れるとその系統だけ無音になる、という運用だった。Slack のチャンネル転送には購読確認の概念が無いので、**この手作業は丸ごと消えた**(→ [ADR-0011](../adr/0011-slack-notification-with-chatbot.md))。

代わりに **Slack 側に 1 回きりの前提が 2 つある。**どちらもスタックを作り直しても消えないが、**欠けているとスタックは成功するのに通知だけ届かない。**

1. AWS コンソールで Slack ワークスペースを認可してあること(CloudFormation では自動化できない)
2. 2 つのチャンネルそれぞれで `/invite @Amazon Q` を実行してあること

手順と ID の取り方 → **[docs/slack/README.md](../slack/README.md)**。配線の確認は Chatbot コンソールの **テストメッセージを送信** が早い。

`cfn-deploy.yml` の締めのサマリに同じ内容のリマインダが出る。

### 11-2. 建てた直後に届く通知は異常ではない

**「OK になりました」通知が 7 通届く。** CloudWatch アラームのアクションは状態遷移で発火し、新規作成されたアラームは `INSUFFICIENT_DATA` → `OK` を必ず通るため。異常が起きたわけではない。

### 11-3. フェーズ8・10 の実験中はスロークエリアラームが鳴り続ける

`long_query_time` は 1 秒、アラームは「5 分間に 5 件以上」。検索ラボ(フェーズ8)と index 実験(フェーズ10)は**遅いクエリを意図的に出す**のが目的なので、実験中は鳴る。**閾値は緩めていない。人が無視する。**

うるさければ、実験中だけコンソールでアラームのアクションを止められる(CloudWatch → アラーム → **アクション → 無効化**)。CloudFormation から見ればドリフトになるが、撤収すれば消えるので実害は無い。

### 11-4. アーカイブされたログは撤収で消える

`nuxt-java-practice-stg-logs-archive` バケットはスタックの一部なので、**撤収するとアーカイブも全部消える。** ライフサイクル(30 日で Glacier IR / 365 日で削除)は一度も発火しない。**保全機能としては動いていない**(意図した割り切り → ADR-0010)。残したいログがあれば撤収前に自分で落とす。

Firehose のバッファは最大 900 秒なので、**直近 15 分ぶんは S3 に着く前に消える**。配線が正しいかを確かめたいときは、建ててから 15 分ほど待って `aws s3 ls s3://nuxt-java-practice-stg-logs-archive/app-logs/ --recursive` を見る。オブジェクトが 1 つも無く原因を知りたいときは、ロググループ `/aws/kinesisfirehose/nuxt-java-practice-stg-logs-archive` の `S3Delivery` ストリームに配信エラーの理由が出る。

---

## 12. 詰まったときの見どころ

| 症状 | 見るところ |
|---|---|
| `db-task` だけ `Not authorized to perform sts:AssumeRoleWithWebIdentity` | Environment に `AWS_DB_TASK_ROLE_ARN` を登録したか(→ §2-3・§5)。信頼ポリシーは `gha-cfn-stg` と同じ形 |
| `run-task` が `AccessDeniedException` / `is not authorized to perform: iam:PassRole` | §2-3 のポリシーがタスク定義名・クラスタ名・ロール名と一致しているか。**リソースまで絞っているので、タスク定義を増やしたら IAM 側にも足す** |
| `Not authorized to perform sts:AssumeRoleWithWebIdentity` | 信頼ポリシーの `sub`。`environment:` を使うと末尾が `environment:stg` になる。実際のクレームを出す手順 → [github-actions-oidc.md](./github-actions-oidc.md) §8 |
| スタックは成功したのにアプリからメールが送れない | **SES の検証待ち。** `AWS::SES::EmailIdentity` は検証完了を待たずに `CREATE_COMPLETE` になる(アラート通知とは無関係) |
| `Service ARN did not stabilize` | タスクが起動できていない。`create-db-users` / `migrate` を流したか。CloudWatch Logs の `/ecs/nuxt-java-practice-stg` を見る |
| ブラウザに認証ダイアログが出ずアクセスできない | WAF のカスタムレスポンスに `www-authenticate` が入っているか。ALB の `fixed-response` ではヘッダーを付けられないのでこの経路は使えない |
| 検索ラボ(フェーズ8)の検索が弾かれる | WAF にマネージドルールを足していないか。SQL に似たキーワードが SQLi ルールに引っかかる |
| `DELETE_FAILED` で撤収が止まる | バケット 2 つ(画像 / ログアーカイブ)が空になっているか。**ワークフローは 1 回だけ自動でやり直す**ので、それでも止まったら別の原因。詰まったら `delete-stack --deletion-mode FORCE_DELETE_STACK` か `--retain-resources` |
| Run Task が成功したように見えるがマイグレーションされていない | `describe-tasks` の `exitCode` を見ているか。`run-task` は起動するだけで完了を待たない |
| Google ログインで `redirect_uri_mismatch` | Google Console の承認済みリダイレクト URI と `APP_BASE_URL` が一致しているか |
| `Template file size ... must be deployed via an S3 Bucket`(`DeployBucketRequiredError`) | テンプレートが 51,200 バイトを超えている。`deploy` に `--s3-bucket` が渡っているか。バケットを作ったか(→ §3) |
| 反映が `作り直しが含まれています` で止まった | **意図した変更か確かめる。** `Database` が対象なら実行すると中のデータが消える。意図的なら `allow_replacement=true` で再実行 |
| 反映が `差分がありませんでした` で終わる | 既に反映済み。テンプレートを直したつもりで直っていない(コミットし忘れ)可能性も見る |
| 反映が `WebDesiredCount が 0 です` で止まった | `cfn-deploy.yml` が create-db-users / migrate で失敗して止まっている。先に `cfn-deploy.yml` を完走させる |
| 反映が `状態が ROLLBACK_COMPLETE です` で止まった | 作成に失敗したスタックは更新できない。`cfn-destroy.yml` で削除してから建て直す |
| 建てていないのにスタックが `REVIEW_IN_PROGRESS` で存在する | `dry_run=true` で初回の差分を見たときに残る、リソースを持たないスタック。次の構築でそのまま使われるので放置してよい(→ §8) |
| デプロイしたはずのイメージが古いものに戻った | CloudFormation の外から `ecs update-service` していないか(→ §9 の最後) |
| 反映が `Parameter 'SlackWorkspaceId' must match pattern` などで止まった | `params` の `REPLACE_WITH_...` を置き換えたか(→ §6・[docs/slack/README.md](../slack/README.md)) |
| `DELETE_FAILED` で `EmptyBuckets` の権限エラーが出る | `gha-cfn-stg` の `EmptyBuckets` にログアーカイブのバケット ARN を足したか(→ §2-2)。**フェーズ14 で増えた** |
| アラームは `ALARM` になっているのに Slack に来ない | **`/invite @Amazon Q` を忘れていないか**(→ §11-1)。Chatbot コンソールの **テストメッセージを送信** で切り分ける。転送の失敗理由は `/aws/chatbot/...` に出る |
| 建てた直後に「OK になりました」通知が大量に来る | 正常。新規アラームは `INSUFFICIENT_DATA` → `OK` の遷移で `OKActions` が発火する(→ §11-2) |
| スタックの作成が Chatbot のリソースで失敗する | Slack ワークスペースの認可を済ませたか(→ §11-1)。`ConfigurationName` の衝突なら、コンソールで手動のチャンネル設定を作っていないか |
| スロークエリのアラームが鳴り止まない | 検索ラボ / index 実験の最中でないか(→ §11-3)。仕様として鳴らしている |
| ECS タスク数不足のアラームが一度も鳴らない | `ContainerInsights` が有効か。`RunningTaskCount` は Container Insights が発行するもので、`AWS/ECS` には無い |
| ログアーカイブの S3 にオブジェクトが無い | Firehose のバッファは最大 900 秒。15 分待つ。それでも無ければ `/aws/kinesisfirehose/...` の `S3Delivery` ストリームに配信エラーの理由が出る(→ §11-4) |
