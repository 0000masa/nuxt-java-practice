# CloudFormation のコマンドと IAM 権限 — 誰の資格情報で動いているのか

`cfn-apply.yml` / `cfn-destroy.yml` / `db-task.yml` が叩く AWS API を **IAM の側から**読むノート。「このコマンドを通すのに何の権限が要るのか」「`--role-arn` を書いた行と書いていない行で何が変わるのか」「`iam:PassRole` とは結局どういう権限なのか」に答えることが目的。

コマンドのオプションや `Status` の読み方は [CloudFormation の CLI コマンドを読み解く](./cli-commands-and-change-sets.md) にある。あちらが**コマンド軸**、こちらが**IAM 軸**で、同じコマンド表を別の切り口で見る関係になっている。ポリシーを実際に貼るコマンドは [手順書 §2](../../infrastructure/cloudformation-operations.md)。

このノートも記述の確からしさを 3 段階で書き分ける。

- **仕様** — 公式ドキュメントまたは aws-cli / botocore のソースに書かれていること。リンクと引用を付ける
- **傾向** — 実務でよく見る形。根拠が弱いので断定しない
- **未検証** — まだ実物で確かめていない(→ §12 に一覧)

要点は 3 つ。

1. **`cloudformation:*` は「リソースを作る権限」ではない。** CloudFormation という**取次に指示を出す**権限でしかない。GitHub Actions が引き受けるロールには `ec2:*` も `rds:*` も `ecs:*` も 1 つも無く、それ単体では VPC ひとつ作れない
2. **`--role-arn` はスタックに紐づく。** 引数として書いているのは `create-change-set` と `delete-stack` の 2 箇所だけなのに、実際にリソースを作る `execute-change-set` もサービスロールで動く。一度渡すとスタックの属性として残るため
3. **`iam:PassRole` は呼べる API ではない。** 「ロール ARN を AWS サービスに手渡す」ときに IAM が裏で審査する権限で、渡し先を `iam:PassedToService` で縛らないと権限昇格の経路になる

関連ノート: [CloudFormation の CLI コマンドを読み解く](./cli-commands-and-change-sets.md) / [Terraform 経験者のための CloudFormation](./terraform-to-cloudformation.md) / [テンプレートの分割と置き場](./templates-and-prerequisites.md) / [手順書 §2](../../infrastructure/cloudformation-operations.md) / [テンプレートの書き方(YAML の文法と組み込み関数)](./template-syntax-and-functions.md)

---

## 1. 3 層に分けて見る

権限の話が混乱するのは、**「誰が」の層が 3 つあるのに 1 つに見えるから**。

```
[1] GitHub Actions のジョブ
      OIDC で AssumeRole → 一時クレデンシャルを持つ
      持っている権限: cloudformation:* / iam:PassRole(1 本だけ)/ S3 の一部
      できること: CloudFormation に「やってくれ」と頼むこと
         │
         │  aws cloudformation create-change-set --role-arn <サービスロール>
         ▼
[2] CloudFormation サービス
      渡されたサービスロールを AssumeRole して動く
      持っている権限: AdministratorAccess
      できること: テンプレートに書かれたリソースを作る
         │
         │  ec2:CreateVpc / rds:CreateDBInstance / ecs:CreateService ...
         ▼
[3] AWS リソース
      VPC / RDS / ECS / ALB / S3 ...
```

**[1] と [2] は別の資格情報で動いている。** [1] のクレデンシャルが漏れても [3] には手が届かない。届くのは [2] 経由だけで、[2] は**テンプレートに書かれた通りにしか動かない**。

Terraform だと [1] と [2] が同じ資格情報になる(`terraform apply` を実行する主体が直接 API を叩く)ので、この分離は CloudFormation 特有の形。→ [Terraform 経験者のための CloudFormation](./terraform-to-cloudformation.md)

---

## 2. ロールは 3 本ある

| ロール | 誰が引き受けるか | 権限の中身 | 使うワークフロー |
|---|---|---|---|
| `nuxt-java-practice-gha-cfn-stg` | GitHub Actions(OIDC) | インライン `DeployStack`(4 文) | `cfn-apply` / `cfn-destroy` |
| `nuxt-java-practice-cfn-service-stg` | **CloudFormation**(`--role-arn` で渡す) | 管理ポリシー `AdministratorAccess` | 上の 2 本から間接的に |
| `nuxt-java-practice-gha-dbtask-stg` | GitHub Actions(OIDC) | インライン `RunDbTask`(5 文) | `db-task` |

**Actions 側が 2 本に分かれているのが設計上の要点。** `db-task.yml` は任意 SQL を流せるワークフローなので、その実行に `cloudformation:*` を持つクレデンシャルを降ろさない。ジョブが `role-to-assume` に渡す secret が違うだけで、同じ Environment `stg` から引き受けている。

```
cfn-apply.yml:129    role-to-assume: ${{ secrets.AWS_CFN_DEPLOY_ROLE_ARN }}
cfn-destroy.yml:49   role-to-assume: ${{ secrets.AWS_CFN_DEPLOY_ROLE_ARN }}
db-task.yml:84       role-to-assume: ${{ secrets.AWS_DB_TASK_ROLE_ARN }}
```

**サービスロールを引き受けるのは人でも Actions でもない。** `cfn-service-stg` の信頼ポリシーは `Service: cloudformation.amazonaws.com` なので、CloudFormation サービスだけが引き受けられる。Actions のロールがこれを `sts:AssumeRole` することはできないし、その必要も無い。

---

## 3. 信頼ポリシーと権限ポリシーは別物

手順書 §2 で 1 つのロールに対して 2 種類のコマンドを打つのは、**設定する対象が違う**から。ここを混ぜると「ポリシーを貼ったのに `AssumeRoleWithWebIdentity` で落ちる」といった読み違いが起きる。

| | 信頼ポリシー(assume role policy) | 権限ポリシー(identity-based policy) |
|---|---|---|
| 答える問い | **誰がこのロールになれるか** | **このロールで何ができるか** |
| 設定するコマンド | `create-role --assume-role-policy-document`<br>`update-assume-role-policy` | `put-role-policy`(インライン)<br>`attach-role-policy`(管理ポリシー) |
| ロールに付く数 | 必ず 1 つ | インライン複数 + 管理ポリシー複数 |
| 書く内容 | `Principal`(誰)と `Condition`(どんな条件で) | `Action` / `Resource` / `Condition` |
| 失敗したときのエラー | `Not authorized to perform sts:AssumeRoleWithWebIdentity` | `is not authorized to perform: <action>` |

**`gha-cfn-stg` の場合。**

```
create-role  --assume-role-policy-document  → 「main ブランチの、stg Environment のジョブだけ」
put-role-policy --policy-name DeployStack   → 「CloudFormation を叩ける / サービスロールを渡せる / S3 を少し」
```

**この 2 つは掛け算で効く。** 片方だけでは意味を持たない。§10 でもう一度この点に戻る。

**管理ポリシーとインラインポリシーはコマンドの系統が違う。** `attach-role-policy` / `detach-role-policy` が管理ポリシー(AWS またはアカウントが持つ独立したポリシーを参照する)、`put-role-policy` / `delete-role-policy` がインライン(ロールに直接埋め込む)。`cfn-service-stg` だけが管理ポリシー(`AdministratorAccess`)を使っている。

---

## 4. コマンド別・必要権限の対応表

本題。`cfn-apply.yml` と `cfn-destroy.yml` が叩く AWS API を全部並べる。

| 実行箇所 | コマンド | 呼び出し元に要る権限 | `--role-arn` | 実リソースに触るか |
|---|---|---|---|---|
| `cfn-apply.yml:141` | `describe-stacks` | `cloudformation:DescribeStacks` | — | ✗ |
| `cfn-apply.yml:196` | `sts get-caller-identity` | **不要**(→ §4-3) | — | ✗ |
| `cfn-apply.yml:200` | `s3 cp` | `s3:PutObject` | — | ✗(テンプレート置き場) |
| `cfn-apply.yml:240` | `create-change-set` | `cloudformation:CreateChangeSet` + **`iam:PassRole`** | **✅ 明示** | ✗(差分を計算するだけ) |
| `cfn-apply.yml:253` | `wait change-set-create-complete` | `cloudformation:DescribeChangeSet`(→ §4-2) | — | ✗ |
| `cfn-apply.yml:256, 289, 305` | `describe-change-set` | `cloudformation:DescribeChangeSet` | — | ✗ |
| `cfn-apply.yml:264, 370, 379` | `delete-change-set` | `cloudformation:DeleteChangeSet` | — | ✗ |
| `cfn-apply.yml:395` | **`execute-change-set`** | `cloudformation:ExecuteChangeSet` | ❌ 書かない | **✅ ここで作る** |
| `cfn-apply.yml:406` | `wait stack-create-complete` 他 | `cloudformation:DescribeStacks` | — | ✗ |
| `cfn-apply.yml:408` | `describe-stack-events` | `cloudformation:DescribeStackEvents` | — | ✗ |
| `cfn-destroy.yml:56, 85` | `describe-stacks` | `cloudformation:DescribeStacks` | — | ✗ |
| `cfn-destroy.yml:95` | `s3 rm --recursive` | `s3:ListBucket` + `s3:DeleteObject`(→ §4-4) | — | **✅ 中身を消す** |
| `cfn-destroy.yml:105` | **`delete-stack`** | `cloudformation:DeleteStack` + `iam:PassRole` | **✅ 明示** | **✅ ここで消す** |
| `cfn-destroy.yml:107` | `wait stack-delete-complete` | `cloudformation:DescribeStacks` | — | ✗ |
| `cfn-destroy.yml:118` | `describe-stack-events` | `cloudformation:DescribeStackEvents` | — | ✗ |

**「実リソースに触る」が ✅ なのは 4 行だけ。** そのうち `execute-change-set` と `delete-stack` は**サービスロールが実行主体**で、`s3 rm` の 2 つだけが Actions のロール自身の権限で動いている。だから `EmptyBuckets` という文が `DeployStack` ポリシーに必要になる。

ポリシーが `cloudformation:*` とワイルドカードなのは、この表の 7 種類(+ 将来増える分)を個別に列挙する意味が薄いから。**列挙してもできることは変わらない**(この表以外に CloudFormation API から実リソースへ届く経路が無い)ので、`cloudformation:*` にしても爆発半径は増えない。サービスロール側で `AdministratorAccess` を選んだ判断(→ [手順書 §2-1](../../infrastructure/cloudformation-operations.md))とは、性質が違う話なので混同しないこと。

### 4-1. 読むだけのコマンドは CloudFormation の台帳しか触らない

`describe-stacks` / `describe-change-set` / `describe-stack-events` は、**CloudFormation が自分で持っている記録**を返すだけで、EC2 や RDS には問い合わせない。だから `cloudformation:Describe*` があれば通り、サービスロールは一切関係しない。

`delete-change-set` も同じ側にいる。**「まだ実行していない変更案を捨てる」だけ**で、スタックにもリソースにも触らない(→ [CLI ノート §8-2](./cli-commands-and-change-sets.md))。`cfn-apply.yml` が 3 箇所でこれを呼ぶのは、差分ゼロのとき・`dry_run` のとき・Replacement で止めたときの後片付けで、いずれも実リソースは動いていない。

**例外がひとつある。** `--deployment-mode REVERT_DRIFT` を使うと CloudFormation が実物を読みに行くので、**リソースの `Describe*` 権限が要る**。このリポジトリでは使っていない。詳細と、その権限がどちらのロールに必要なのかが未確定である点 → [CLI ノート §5-8](./cli-commands-and-change-sets.md)。

### 4-2. `wait` は AWS の API ではない

**仕様:** `aws cloudformation wait stack-create-complete` は AWS 側に「待つ」という API があるわけではなく、**aws-cli が対応する `Describe*` を一定間隔で呼び続けている**だけ。botocore の waiter 定義(`cloudformation/2010-05-15/waiters-2.json`)に、どの API を何秒間隔で何回呼ぶかが書かれている。

| waiter | 裏で呼ばれる API | 要る権限 |
|---|---|---|
| `change-set-create-complete` | `DescribeChangeSet` | `cloudformation:DescribeChangeSet` |
| `stack-create-complete` / `stack-update-complete` / `stack-delete-complete` | `DescribeStacks` | `cloudformation:DescribeStacks` |

**権限を絞ったときに落とし穴になる。** `cloudformation:CreateChangeSet` だけ許して `DescribeChangeSet` を落とすと、作成は成功するのに次の `wait` の行で `AccessDeniedException` になる。**「叩いているコマンドの数」と「要る権限の数」が一致しない**のはこれが理由。

同じことが `db-task.yml:183` の `aws ecs wait tasks-stopped` にも当てはまり、これは `ecs:DescribeTasks` を消費する。だから `RunDbTask` ポリシーに `WatchDbTask` の文が要る(→ [手順書 §2-3](../../infrastructure/cloudformation-operations.md))。

### 4-3. `sts get-caller-identity` は権限が要らない

`cfn-apply.yml:196` はテンプレート置き場のバケット名を組み立てるためにアカウント ID を引いている。

```bash
account=$(aws sts get-caller-identity --query Account --output text)
bucket="nuxt-java-practice-cfn-templates-${account}"
```

`DeployStack` ポリシーに `sts:GetCallerIdentity` は書かれていないのに通る。

**仕様:** [`GetCallerIdentity` の API リファレンス](https://docs.aws.amazon.com/STS/latest/APIReference/API_GetCallerIdentity.html)より。

> No permissions are required to perform this operation. If an administrator attaches a policy to your identity, you can use `GetCallerIdentity` to determine the identity you are using... permissions are not required because the same information is returned when access is denied.

**「アクセスを拒否したときのエラーメッセージに同じ情報が載っているから、隠す意味がない」**という理屈。だから明示的に `Deny` しても効かない。

このおかげでワークフローに設定を渡さずに済んでいる(バケット名が規則から導ける → [ADR-0008](../../adr/0008-template-bucket-as-resident-resource.md))。

### 4-4. S3 の 2 つ — なぜ `ListBucket` が要るのか

`DeployStack` ポリシーには S3 の文が 2 つある。用途が別なので分けてある。

| 文 | 対象バケット | Action | 使う場所 |
|---|---|---|---|
| `PutTemplate` | `...-cfn-templates-${ACCOUNT_ID}` | `s3:PutObject` / `s3:GetObject` | `cfn-apply.yml:200` |
| `EmptyBuckets` | `...-stg-images` / `...-stg-logs-archive` | `s3:ListBucket` / `s3:DeleteObject` | `cfn-destroy.yml:95` |

**`aws s3 rm --recursive` に `ListBucket` が要るのが引っかかりやすい。** 「消すだけなのになぜ一覧の権限?」という話だが、`s3 rm --recursive` は高レベルコマンドで、内部で `ListObjectsV2` を呼んで**消す対象のキーを列挙してから** `DeleteObject` を撃っている。列挙できないと 0 件とみなして黙って成功し、バケットが空にならないまま `delete-stack` に進んで `DELETE_FAILED` になる。

**Resource の書き方が 2 通り必要な点にも注意。** `s3:ListBucket` は**バケット**に対する操作、`s3:DeleteObject` は**オブジェクト**に対する操作なので、ARN が `arn:aws:s3:::bucket` と `arn:aws:s3:::bucket/*` の 2 つ要る。ポリシーに 2 バケット × 2 行 = 4 つ ARN が並んでいるのはそのため。

**なぜ撤収ワークフローがこれをやるのか。** 素の CloudFormation には Terraform の `force_destroy` に当たる仕組みが無く、中身の入った S3 バケットは削除できない。だから外から空にしている。**フェーズ14 でログアーカイブのバケットが増えたとき、ここに ARN を足し忘れると撤収が必ず失敗する**という形で表面化した。

---

## 5. `--role-arn` はスタックに紐づく

表(§4)で不思議に見えるのが、**実際にリソースを作る `execute-change-set` に `--role-arn` が無い**こと。

```bash
# cfn-apply.yml:240-248 — 差分を計算するだけなのに渡している
aws cloudformation create-change-set \
  --stack-name "$stack" \
  --role-arn "${{ secrets.AWS_CFN_SERVICE_ROLE_ARN }}" \
  ...

# cfn-apply.yml:395 — 実際に作る側なのに渡していない
aws cloudformation execute-change-set \
  --stack-name "$stack" --change-set-name "${{ steps.cs.outputs.name }}"
```

**仕様:** [`CreateChangeSet` の `RoleARN`](https://docs.aws.amazon.com/AWSCloudFormation/latest/APIReference/API_CreateChangeSet.html) より。

> **CloudFormation uses this role for all future operations on the stack.** ... If you don't specify a value, CloudFormation uses the role that was previously associated with the stack.

つまり **`--role-arn` は「そのコマンドのための引数」ではなく「スタックの属性」**。一度渡すとスタックに `RoleARN` として保存され、以降の操作で省略しても同じロールが使われる。`describe-stacks` の出力にも `RoleARN` フィールドとして出てくる。

だから紐づけの機会は `create-change-set`(= スタックを初めて触るとき)にあればよく、`execute-change-set` は**渡すためのオプションをそもそも持っていない**。

引用の全文と、`--role-arn` を 2 段構成にした設計上の理由 → [CLI ノート §5-5](./cli-commands-and-change-sets.md)。

**`delete-stack` で明示しているのは保険。** `cfn-destroy.yml:99-103` のコメントが理由を書いている。紐づいていないスタック(コンソールから手で作った場合など)に当たったとき、省略すると Actions のロールの一時セッションで削除が試みられ、`cloudformation:*` しか無いのでリソースを消せずに `DELETE_FAILED` になる。明示すればその分岐が消える。→ [CLI ノート §9-2](./cli-commands-and-change-sets.md)

### 5-1. 渡さなかったらどうなるか

同じ引用の続き。

> If no role is available, CloudFormation uses a temporary session that is generated from your user credentials.

**「サービスロールが無ければ呼び出し元の権限で動く」**というのがフォールバックの挙動。ここが分離設計の要になる。

このリポジトリで `--role-arn` を落とすと、CloudFormation は `gha-cfn-stg`(`cloudformation:*` + `iam:PassRole` + S3 少々)の権限で VPC や RDS を作ろうとして、**1 つ目のリソースで `CREATE_FAILED` になる**。

裏返すと、**Actions の一時クレデンシャルが漏れても攻撃者は AWS リソースを作れない**。作れるのは「CloudFormation にテンプレートを実行させること」だけで、そのテンプレートは main ブランチのものに限られる(→ §10)。手順書が言う「テンプレートに書かれていないことはできない」の実体はこれ。

**未検証:** `execute-change-set` の実行時に、呼び出し元に対して `iam:PassRole` が再度審査されるのか。PassRole は「渡す」瞬間に審査される権限なので `create-change-set` の時点で完了していると読めるが、公式に「実行時には不要」と明記した記述は見つけていない。現行のポリシーは両方の解釈で通るので実害は無い。

---

## 6. `iam:PassRole` は呼べる API ではない

`aws iam pass-role` というコマンドは無い。**`iam:PassRole` は「ロール ARN を AWS サービスに手渡す」操作のときに IAM が裏で審査する権限**で、単独では何も起こせない。

**仕様:** [Pass a role to an AWS service(IAM ユーザーガイド)](https://docs.aws.amazon.com/IAM/latest/UserGuide/id_roles_use_passrole.html)より。

> To pass a role (and its permissions) to an AWS service, a user must have permissions to pass the role to the service. This helps administrators ensure that only approved users can configure a service with a role that grants permissions. To allow a user to pass a role to an AWS service, you must grant the `PassRole` permission to the user's IAM user, role, or group.

**なぜ独立した権限になっているか。** ロールを渡すと、**渡した先が自分の持たない権限を発揮する**。ここでは `cloudformation:*` しか持たない主体が、`AdministratorAccess` を持つロールを CloudFormation に渡している。この許可が無条件だと「弱い権限の主体が、強いロールを持つ処理を起動して、その処理越しに強い権限を使う」という典型的な権限昇格が成立してしまう。

権限が無いときのエラー(**傾向**。ARN 部分は環境で変わる):

```
An error occurred (AccessDenied) when calling the CreateChangeSet operation:
User: arn:aws:sts::${ACCOUNT_ID}:assumed-role/nuxt-java-practice-gha-cfn-stg/GitHubActions
is not authorized to perform: iam:PassRole on resource:
arn:aws:iam::${ACCOUNT_ID}:role/nuxt-java-practice-cfn-service-stg
```

**`cloudformation:CreateChangeSet` を持っていても、これだけで落ちる。** 「CFn の権限は付けたのにコマンドが通らない」という詰まり方をするのはこのパターン。

### 6-1. 絞り方は 2 段になっている

```json
{
  "Sid": "PassServiceRole",
  "Effect": "Allow",
  "Action": "iam:PassRole",
  "Resource": "arn:aws:iam::${ACCOUNT_ID}:role/nuxt-java-practice-cfn-service-stg",
  "Condition": { "StringEquals": { "iam:PassedToService": "cloudformation.amazonaws.com" } }
}
```

| 絞る軸 | 書く場所 | 意味 |
|---|---|---|
| **どのロールを** | `Resource` | 渡せるのは `cfn-service-stg` の 1 本だけ。アカウント内の他のロール(ECR push 用など)は渡せない |
| **どのサービスに** | `Condition: iam:PassedToService` | 渡せる相手は CloudFormation だけ。ECS や Lambda に同じロールを渡して、そっち経由で管理者権限を使う迂回ができない |

**`iam:PassedToService` が無いと片肺になる。** 「`cfn-service-stg` を渡す」ことは許されたままなので、ロール ARN を受け取る他のサービス(ECS のタスク定義、Lambda の実行ロール、EC2 のインスタンスプロファイルなど)に渡し、そこから管理者権限で任意の操作ができてしまう。**`Resource` だけでは防げない**のがポイント。

### 6-2. 同じ形が `db-task.yml` にも出る

`gha-dbtask-stg` の `PassTaskRoles` は、渡し先が違うだけで構造が同じ。

| | `PassServiceRole`(`gha-cfn-stg`) | `PassTaskRoles`(`gha-dbtask-stg`) |
|---|---|---|
| 渡す場所 | `create-change-set --role-arn` | `ecs run-task`(タスク定義の `ExecutionRoleArn`) |
| 渡し先サービス | `cloudformation.amazonaws.com` | `ecs-tasks.amazonaws.com` |
| 渡せるロール | 1 本(サービスロール) | 2 本(`task-execution-role` / `db-ops-execution-role`) |
| 渡した先が持つ強さ | `AdministratorAccess` | SSM の SecureString / RDS のマスターシークレットの読み取り |

**`run-task` のほうは「渡している」ことが見えにくい。** コマンドラインに ARN が現れず、**タスク定義に書かれた `ExecutionRoleArn` を ECS に引き受けさせる**形になるため。それでも IAM から見れば同じ「渡す」操作なので `iam:PassRole` が審査される。→ [手順書 §2-3](../../infrastructure/cloudformation-operations.md)

**渡せるロールを 2 本に限定しているのが最小権限の実体。** 無条件に許すと、マスターシークレットを読める `db-ops-execution-role` を任意のタスク定義に付けて起動できてしまい、「マスターに触るのは `db-ops` だけ」という設計(→ [ADR-0005](../../adr/0005-separate-db-users-for-app-and-migration.md))が崩れる。

---

## 7. `--capabilities` は IAM 権限ではない

`cfn-apply.yml:246` に `--capabilities CAPABILITY_NAMED_IAM` があるので、これも権限設定に見えるが**別物**。

| | `--capabilities` | IAM ポリシー |
|---|---|---|
| 何をするか | 「このテンプレートは IAM リソースを作りますよ」という**呼び出し元の承認** | できる / できないの判定 |
| 誰が見るか | CloudFormation | IAM |
| 足りないと | `InsufficientCapabilities` エラー | `AccessDenied` エラー |

**`--capabilities` を付けても権限は増えない。** IAM リソースを作る権限はサービスロール側(`AdministratorAccess`)が持っていて、`--capabilities` は「知らないうちに IAM ロールが作られていた」を防ぐための確認にすぎない。逆に、`--capabilities` を付けても権限が無ければ普通に失敗する。

3 つの値の使い分けと `CAPABILITY_AUTO_EXPAND` が Change Set で無視される話 → [CLI ノート §5-4](./cli-commands-and-change-sets.md)。

---

## 8. なぜ CloudFormation は `Resource: "*"` で、ECS は ARN まで絞れるのか

同じ「Actions が引き受けるロール」なのに、絞り方が対照的になっている。

```json
// gha-cfn-stg — 絞っていない
{ "Action": "cloudformation:*", "Resource": "*" }

// gha-dbtask-stg — 全部特定している
{ "Action": "ecs:RunTask",
  "Resource": ["arn:aws:ecs:ap-northeast-1:${ACCOUNT_ID}:task-definition/nuxt-java-practice-stg-db-ops:*", ...],
  "Condition": { "ArnEquals": { "ecs:cluster": "arn:...:cluster/nuxt-java-practice-stg-cluster" } } }
```

理由は 3 つ。

1. **スタック ARN は作る前に確定しない。** `arn:aws:cloudformation:ap-northeast-1:123456789012:stack/nuxt-java-practice-stg/<ランダムな UUID>` の形で、末尾は CloudFormation が採番する。だから `stack/nuxt-java-practice-stg/*` のようにワイルドカードを使うことになる(`db-task.yml` 用の `ReadStackOutputs` はまさにこの形で、**`/*` はこの UUID 部分**)
2. **対象がスタックだけではない。** Change Set は別のリソース型で、`create-change-set` は「まだ存在しないスタックの、まだ存在しない Change Set」を作る。作る前のものを ARN で特定するのは書きづらい
3. **絞る実益が薄い。** §4 の表のとおり、CloudFormation API から実リソースに届く経路はサービスロール経由しかない。スタック名で絞っても「別スタックを消される」ことは防げるが、このアカウントには対象スタックしかいない

**`gha-dbtask-stg` 側は逆に、既に存在するリソースだけを相手にする。** クラスタもタスク定義もロググループもスタックが作った実物で、名前が決まっている。だから全部 ARN で特定できるし、**任意 SQL を流せるワークフローなので特定する価値がある**。

**代償もある。** 絞った側は追随が要る。タスク定義を増やしただけで `run-task` が `AccessDeniedException` になるので、`app.yml` を触ったら IAM 側にも足すことを忘れないこと(手順書 §2-3 に注意書きがある)。

---

## 9. 権限不足の現れ方は 2 系統ある

**どちらの層で落ちたかで、出るものも出るタイミングも違う。** ここを見分けられると調査が速い。

| | ① API 層 | ② スタックイベント層 |
|---|---|---|
| 足りないのは | **呼び出し元**(`gha-cfn-stg`)の権限 | **サービスロール**(`cfn-service-stg`)の権限 |
| いつ分かるか | **即座**(コマンドが返らない) | **リソースを作り始めてから**(15〜25 分後もありうる) |
| 何が出るか | `AccessDeniedException` / `InsufficientCapabilities` | `CREATE_FAILED` と `ResourceStatusReason` |
| どこで見るか | Actions のログ、コマンドの直後 | `describe-stack-events`(`cfn-apply.yml:408` が自動で出す) |
| 後始末 | 不要(何も作られていない) | **`ROLLBACK_COMPLETE` のスタックが残る**。`cfn-destroy` してから建て直し |

②の例(**傾向**。文言はリソース型によって変わる):

```
CREATE_FAILED  NatGateway
  Resource handler returned message: "You are not authorized to perform this operation.
  (Service: Ec2, Status Code: 403, ...)"
```

**②が高くつくのが、サービスロールの権限を列挙しなかった理由。** 権限不足がリソース作成の途中でしか分からず、しかも RDS と NAT Gateway を含むスタックは作成に 15〜25 分かかる。1 つ足りないたびに「失敗 → 削除 → 権限追加 → 建て直し」を往復することになる。だから `AdministratorAccess` 1 本にしている(判断の前提と、それが安全とは限らないこと → [手順書 §2-1](../../infrastructure/cloudformation-operations.md))。

**①は `dry_run=true` で先に潰せる。** Change Set の作成だけを試すので、`cloudformation:CreateChangeSet` と `iam:PassRole` と `s3:PutObject` が揃っているかを数十秒で確かめられる。**ただし②は潰せない**(Change Set の作成はリソースに触らないため)。→ [手順書 §8](../../infrastructure/cloudformation-operations.md)

---

## 10. この設計が守っているものと、その前提

ここまでを踏まえて、**「`cloudformation:*` + 管理者ロールへの `PassRole`」がどれだけ強いのか**を正直に見ておく。

**権限ポリシーだけを見ると、実質的に管理者と同じ。** 任意のテンプレートを持ち込めるなら、そこに「自分を管理者にする IAM ユーザー」を書いて CloudFormation に作らせればよい。`gha-cfn-stg` 自身が `iam:CreateUser` を持たないことは、この経路を塞がない。

**それでも境界が成立しているのは、テンプレートの出所が縛られているから。**

```
信頼ポリシー(§3)          誰がこのロールになれるか
  sub: repo:.../nuxt-java-practice@...:environment:stg   ← このリポジトリの stg 環境のジョブだけ
  ref: refs/heads/main                                    ← main ブランチのワークフローだけ
        ↓ 掛け算
権限ポリシー(§4-§6)      そのジョブが何をできるか
  cloudformation:* + PassRole(1 本)                     ← main のテンプレートを CFn に実行させる
```

つまり手順書が言う「テンプレートに書かれていないことはできない」は、**正確には「main ブランチのテンプレートに書かれていないことはできない」**。この前提が崩れる経路(main への直 push、`workflow_dispatch` を任意ブランチから叩けること、Environment の protection rules が GitHub Free のプライベートリポジトリで使えないこと)は、**IAM 側では防げない**。ブランチ制限を `sub` ではなく `ref` クレームで掛けているのは、この最後の防波堤を AWS 側に置くため(→ [手順書 §2-2](../../infrastructure/cloudformation-operations.md))。

**読み方としての結論は「権限ポリシー単体で強さを評価しない」。** 信頼ポリシーとセットで初めて意味を持つ。逆に、信頼ポリシーだけを厳しくしても権限ポリシーが緩ければ、そのリポジトリの main を通せる人は全員管理者と同じ。

---

## 11. エラー文言から見どころを引く

| エラー | どの層か | 見るところ |
|---|---|---|
| `Not authorized to perform sts:AssumeRoleWithWebIdentity` | **信頼**ポリシー | `sub` の形。`environment:` を使うと末尾が `environment:stg` になる(→ §3・[github-actions-oidc.md](../../infrastructure/github-actions-oidc.md) §8) |
| `is not authorized to perform: iam:PassRole on resource:` | 権限ポリシー / ① | `PassServiceRole` の `Resource` がロール名と一致しているか。`iam:PassedToService` の値が渡し先と合っているか(→ §6) |
| `is not authorized to perform: cloudformation:XxxYyy` | 権限ポリシー / ① | `wait` が裏で呼ぶ `Describe*` を落としていないか(→ §4-2) |
| `InsufficientCapabilities` | CloudFormation | 権限ではない。`--capabilities` の値(→ §7) |
| `CREATE_FAILED` + `You are not authorized to perform this operation` | ② | **サービスロール側**の権限。`AdministratorAccess` が付いているか(`list-attached-role-policies` で確認) |
| `DELETE_FAILED` で S3 の権限エラー | 権限ポリシー / ① | `EmptyBuckets` に 2 バケット × 2 種類の ARN が揃っているか(→ §4-4) |
| `DELETE_FAILED` でリソースが消せない | ② | `delete-stack` に `--role-arn` が渡っているか、スタックに `RoleARN` が紐づいているか(→ §5) |
| `run-task` が `AccessDeniedException` | 権限ポリシー / ① | タスク定義を増やして IAM 側に足し忘れていないか(→ §8) |

---

## 12. 実測して確定させたいこと

- **`execute-change-set` の実行時に `iam:PassRole` が再審査されるか**(→ §5-1)。現行ポリシーはどちらでも通るので、切り分けるには `create-change-set` の後で `PassServiceRole` の文を外して `execute-change-set` を流す必要がある
- **サービスロールが紐づいたスタックに対して、別のサービスロールを渡す `create-change-set` を投げたときの挙動**。上書きされるはずだが確かめていない
- **`--deployment-mode REVERT_DRIFT` の実状態読み取りが、どちらのロールの資格情報で行われるか**(→ §4-1・[CLI ノート §5-8](./cli-commands-and-change-sets.md))
