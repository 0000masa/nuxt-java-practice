# Terraform 経験者のための CloudFormation

Terraform をある程度書ける人が、素の CloudFormation テンプレートを読み書きできるようになるための対訳ノート。このリポジトリには `terraform/`(前に書いた Laravel + nginx の構成)と `cloudformation/app.yml`(今の Spring Boot の構成)が**両方コミットされている**ので、コード例は全部そこから引く。

**注意:** 2 つは別のアプリで、1:1 の移植ではない。ただしリソースの種類(ALB / ECS / RDS / WAF / CloudFront / S3 / SES / SSM)は重なるので、型レベルの対訳としては読める。

このノートは記述の確からしさを 3 段階で書き分ける。

- **仕様** — 公式ドキュメントに書かれていること。リンクと引用を付ける
- **傾向** — 実務でよく見る形。根拠が弱いので断定しない
- **未検証** — まだ実物で確かめていない

要点は 3 つ。

1. **一番大きな違いは state を誰が持つかではなく、「差分を出すときに実物を読むか」。** Terraform は読む、CloudFormation は読まない。ここから `ignore_changes` の有無まで全部が派生する
2. **CloudFormation には `locals` / `data` / `count` / `for_each` / `modules` に相当する仕組みがない。** 代わりに `Parameters` / `Mappings` / `Conditions` / `!Sub` で組む
3. **`terraform plan` に相当するのは Change Set だが、同じものではない。** 比較対象が違い、作成・確認・実行の 3 手に分かれる

関連ノート: [CloudFormation の CLI コマンドを読み解く](./cli-commands-and-change-sets.md) / [ECS のタスク定義は誰が持つか](./ecs-deploy-ownership.md) / [テンプレートの分割と置き場](./templates-and-prerequisites.md)

---

## 1. なぜ別物なのか — state を誰が持つか

Terraform は**自分が作ったものの一覧を自分で持つ**。それが state で、実体は JSON。リモートで運用するなら S3 に置き、同時実行を防ぐロックも自分で用意する。

CloudFormation は**スタックという単位で AWS 側が持つ**。state ファイルは無い。ロックも無い(スタックは一度に 1 つの操作しか受け付けないので、排他制御が仕組みに埋まっている)。

```
Terraform                          CloudFormation
─────────────────────────          ─────────────────────────
*.tf ─── plan/apply ──> AWS API    app.yml ── create/update ──> CloudFormation ──> AWS API
                                                                     │
tfstate ──> S3(自分で管理)                                        スタック(AWS 側が保持)
  + ロック                                                          + 履歴(イベント)
```

これで消えるものと、増えるものがある。

**消えるもの:** state 用 S3 バケットとロックの設計。`terraform init` に相当する手順。プロバイダのバージョン固定(`.terraform.lock.hcl`)。

**増えるもの:** state を直接いじる手段が無いことによる不自由。`terraform state rm` / `terraform state mv` に相当する操作は無い。スタックから 1 つのリソースだけ切り離したいときは、削除時に `--retain-resources` を使うか、リソースのインポート/エクスポート操作を組む(→ §8)。

そして**もっと大きな違いがここから出る。** state を持っていても、Terraform は差分を出すときにそれを信用せず実物を読み直す。CloudFormation は読まない。これが §5 の話で、実務上の影響はこのノートの中で一番大きい。

---

## 2. 概念の対応表

| Terraform | CloudFormation | 備考 |
|---|---|---|
| provider ブロック | **なし** | AWS 専用。`AWS::S3::Bucket` のようなリソース型が最初から全部使える。バージョン固定もない(AWS が更新する) |
| `resource` ブロック | `Resources` の 1 エントリ | |
| アドレス `aws_s3_bucket.main` | **論理 ID**(`ImageBucket` など) | 型名がアドレスに入らない。同じ型を 2 つ置くときは論理 ID で区別する |
| tfstate | スタック | AWS 側が保持。ファイルとして見えない |
| `terraform init` | **なし** | |
| `terraform plan` | Change Set | **同じものではない** → §5。コマンドの詳細 → [別ノート](./cli-commands-and-change-sets.md) |
| `terraform apply` | `create-stack` / `update-stack` / `execute-change-set` | 新規と更新で API が違う → [別ノート §3](./cli-commands-and-change-sets.md) |
| `terraform destroy` | `delete-stack` | |
| `variable` / `*.tfvars` | `Parameters` / `--parameter-overrides` | 平坦な型しか持てない → §4-1 |
| `output` | `Outputs` | `Export` を付けると他スタックから `!ImportValue` で引ける |
| `locals` | **なし** | `Mappings` と `!Sub` で代用 → §4-2 |
| `data` ブロック | **なし** | 外から `Parameters` で渡す → §4-7 |
| `module` | **なし** | ネストスタック / スタック分割 / `AWS::Include` → [別ノート](./templates-and-prerequisites.md) |
| `count` / `for_each` | **なし** | `Fn::ForEach`(要 Transform)か列挙 → §4-4 |
| `dynamic` ブロック | **なし** | 同上 |
| `depends_on` | `DependsOn` | ほぼ同じ → §4-5 |
| `lifecycle.prevent_destroy` | `DeletionPolicy: Retain` + 削除保護 | → §4-6 |
| `lifecycle.ignore_changes` | **なし**(そして要らない) | → §5 と [別ノート](./ecs-deploy-ownership.md) |
| `moved` ブロック | **なし** | 論理 ID を変えると作り直しになる → §7 |
| `terraform import` | リソースのインポート / IaC generator | → §8 |
| provider の `default_tags` | スタックレベルのタグ | 全リソースに自動で伝播する。リソースごとに書かなくてよい |
| `-/+ forces replacement` | `Update requires: Replacement` | 置換されるかが型仕様に定義されている(ただし手で引く) → §6 |
| `terraform state list` | `describe-stack-resources` | |
| state のロック(DynamoDB など) | **なし** | スタックが同時に 1 操作しか受けない |

---

## 3. テンプレートの解剖

トップレベルのキーは 7 つ(+ `Transform`)。`cloudformation/app.yml` は上から順にこう並んでいる。

```yaml
AWSTemplateFormatVersion: "2010-09-09"   # 固定文字列。今も 2010 のまま
Description: 投稿アプリの検証環境(ALB + ECS Fargate + RDS MySQL)
Parameters:   # variable 相当。外から値を受ける
Mappings:     # 定数表。Terraform に直接の相当物がない
Conditions:   # 真偽値の名前付き。count = x ? 1 : 0 の代わり
Resources:    # 唯一の必須セクション
Outputs:      # output 相当
```

**`Description` はトップレベルに 1 つだけ**書ける。Terraform のように各 variable に説明を付けたいときは、`Parameters` の各エントリの `Description` を使う。

**順序に意味はない。** CloudFormation は依存関係を `!Ref` / `!GetAtt` から自動で組み立てるので、`Resources` の中でどのリソースを先に書いてもよい(→ §4-5)。読みやすさのために `app.yml` はネットワーク → SG → RDS → ALB → ECS の順に並べている。

**`Mappings` は「環境差分の置き場」ではなく「定数表」として使うと便利。** `app.yml:182` の実例:

```yaml
Mappings:
  # CloudFront のマネージドポリシーは ID を直書きするしかない。
  # Terraform の data "aws_cloudfront_cache_policy" に相当するデータソースが CloudFormation に無い。
  CloudFrontManagedPolicy:
    CachePolicy:
      Optimized: 658327ea-f89d-4fab-a63d-7e88639e58f6
```

引くときは `!FindInMap [CloudFrontManagedPolicy, CachePolicy, Optimized]`。**これが `locals` の代用**になる。なお環境差分(stg / prod)を `Mappings` に入れる書き方もできるが、このリポジトリは採っていない(共通テンプレートに prod の値が直書きされるのを避けるため → 設計書 決定2)。

---

## 4. タスク別対訳

### 4-1. 変数を外から渡す

```hcl
# terraform/modules/app-infrastructure/variables.tf(抜粋のイメージ)
variable "ecs_web_service_config" {
  type = object({
    desired_count        = number
    bake_time_in_minutes = number
    capacity_provider_strategy = list(object({ ... }))
  })
}
```

```yaml
# cloudformation/app.yml
Parameters:
  WebDesiredCount:
    Type: Number
  BakeTimeInMinutes:
    Type: Number
  WebCapacityProvider:
    Type: String
    AllowedValues: [FARGATE, FARGATE_SPOT]
```

**仕様: `Parameters` の型は平坦なものしかない。** `String` / `Number` / `List<Number>` / `CommaDelimitedList` と、AWS 固有型(`AWS::EC2::VPC::Id` など)、SSM から引く型。**オブジェクトやマップは渡せない。** そのため Terraform で `object({...})` にまとめていた設定は、フィールドごとに個別のパラメータへ開くことになる(`app.yml` はこれで 40 個超のパラメータを持っている)。

代わりに Terraform の `variable` には無い機能がある。

| 機能 | 書き方 | Terraform 側 |
|---|---|---|
| 選択肢の制限 | `AllowedValues: [stg, prod]` | `validation` ブロックで自作 |
| 正規表現 | `AllowedPattern` | 同上 |
| 数値の範囲 | `MinValue` / `MaxValue` | 同上 |
| 入力を隠す | `NoEcho: true` | `sensitive = true`(用途が近い) |
| SSM から自動で引く | `Type: AWS::SSM::Parameter::Value<String>` | `data "aws_ssm_parameter"` |

値の渡し方は `tfvars` とほぼ同じ発想で、このリポジトリは `cloudformation/params/stg.json` に置いている(`cloudformation/README.md` に「Terraform の tfvars 相当」と書いてある)。

**1 つ落とし穴がある。** `aws cloudformation deploy` は `--parameter-overrides` に無いパラメータを自動で `UsePreviousValue: true` にしてくれるが、`create-change-set` を直接叩くときは**自分で組む必要がある**。値を渡さずデフォルトも無いパラメータが 1 つでもあると「must have values」で落ちる(設計書 §8-14)。`--parameters` の 3 つの書き方と、`deploy` がこれを埋める仕組み → [別ノート §5-3, §10-3](./cli-commands-and-change-sets.md)。

### 4-2. 参照 — `!Ref` / `!GetAtt` / `!Sub`

Terraform は `aws_db_instance.main.address` のように属性を素直に辿れる。CloudFormation は 3 つを使い分ける。

| やりたいこと | CloudFormation | 例 |
|---|---|---|
| リソースの主要な識別子 | `!Ref` | `!Ref Cluster` → クラスタ名 |
| パラメータの値 | `!Ref` | `!Ref DbName` |
| それ以外の属性 | `!GetAtt` | `!GetAtt Database.Endpoint.Address` |
| 文字列の組み立て | `!Sub` | `!Sub ${ProjectName}-${EnvName}-app` |

**`!Ref` が何を返すかはリソース型ごとに決まっている。** 型のリファレンスの「Return values」に書かれていて、ARN のこともあれば名前のこともある。ここは Terraform より覚えることが多い。

`!Sub` が `locals` の代わりになる。`app.yml` は FQDN やリソース名の組み立てを毎回 `!Sub` で書いている(`locals` が無いので使い回せない)。

```yaml
# app.yml:1035 付近
Family: !Sub ${ProjectName}-${EnvName}-app
Image: !Sub ${AWS::AccountId}.dkr.ecr.${AWS::Region}.amazonaws.com/${EcrRepositoryName}:${ImageTag}
```

`${AWS::AccountId}` / `${AWS::Region}` は**疑似パラメータ**で、Terraform の `data "aws_caller_identity"` / `data "aws_region"` に相当する。**データソースが無い CloudFormation で、数少ない「環境から取れる値」がこれ。**

### 4-3. 条件分岐 — `count = x ? 1 : 0` の代わり

Terraform で「この環境だけ作る」をやるときの定石は `count`。

```hcl
resource "aws_foo" "bar" {
  count = var.enabled ? 1 : 0
}
```

CloudFormation は `Conditions` に名前を付けて、リソースに `Condition` を貼る。

```yaml
# app.yml:192
Conditions:
  # DesiredCount が 0 の段(ブートストラップ待ち)ではオートスケーリングを作らない。
  ServiceEnabled: !Not [!Equals [!Ref WebDesiredCount, 0]]
  BasicAuthEnabled: !Equals [!Ref EnableBasicAuth, "true"]
  EnhancedMonitoringEnabled: !Not [!Equals [!Ref DbMonitoringInterval, 0]]

Resources:
  ScalableTarget:
    Type: AWS::ApplicationAutoScaling::ScalableTarget
    Condition: ServiceEnabled
```

`count` との違いが 2 つ。

- **`count` は 0 と 1 だけでなく N も作れる**が、`Condition` は作る/作らないの二値だけ
- **`count` を使うとアドレスが `aws_foo.bar[0]` に変わる**(0 個 ↔ 1 個の切り替えでハマるところ)。`Condition` は論理 ID を変えないので、この問題が無い

条件式に使えるのは `!Equals` / `!Not` / `!And` / `!Or` / `!Condition`。**`Conditions` の中で `!GetAtt` は使えない**(リソースを作る前に評価されるため)。判定材料は `Parameters` と `Mappings` に限られる。

### 4-4. 繰り返し — `for_each` の代わり

**素のテンプレートに繰り返しは無い。** 選択肢は 2 つ。

**(a) 列挙する。** このリポジトリはこれ。SES の DKIM の CNAME 3 本はべた書きしている(設計書 §8-7)。

**(b) `Fn::ForEach` を使う。** `Transform: AWS::LanguageExtensions` を宣言すると使える。

```yaml
Transform: AWS::LanguageExtensions

Resources:
  Fn::ForEach::Buckets:
    - Suffix
    - [logs, images, backup]
    - Bucket${Suffix}:
        Type: AWS::S3::Bucket
        Properties:
          BucketName: !Sub ${ProjectName}-${Suffix}
```

**代償:** Transform はマクロなので、**Change Set 経由でしか展開されない**。`validate-template` では中身が見えず、Change Set の表示も展開後になる。`CAPABILITY_AUTO_EXPAND` の指定も要る。3 本の CNAME のために入れる仕掛けではない。

### 4-5. 依存関係 — 書かなくていい場合が多い

```hcl
depends_on = [aws_iam_role_policy.x]
```

```yaml
DependsOn: TaskExecutionRolePolicy
```

意味はほぼ同じ。**そして両方とも、参照があるなら書かなくていい。** `!Ref` / `!GetAtt` で参照していれば CloudFormation が順序を決める(削除順序も逆順で自動)。

明示が必要になるのは Terraform と同じ「参照が無いのに順序が要る」ケース。設計書 §8-15 の例:

> **RDS のロググループは先に作って `DependsOn` させる。** 先に消えると RDS が削除処理中の最終書き込みで同名のロググループを作り直し、管理外の孤児(保持期間 無期限)が残る

これは Terraform 側のコメントにあった知見で、CloudFormation でも同じだった。

### 4-6. 削除の扱い — ここは既定値が違うので注意

| Terraform | CloudFormation |
|---|---|
| `lifecycle.prevent_destroy = true` | `DeletionPolicy: Retain` |
| (なし) | `DeletionPolicy: Snapshot` — スナップショットを取って消す |
| `force_destroy = true`(S3) | **相当物なし** |
| state から外す `terraform state rm` | `delete-stack --retain-resources` |

**仕様: `DeletionPolicy` の既定は `Delete`。ただし RDS だけ例外で `Snapshot`。** 書き忘れるとスタックを消してもスナップショットが残り、課金が続く。`app.yml:607` はそのためにコメント付きで明示している。

```yaml
    # 【重要】RDS だけは DeletionPolicy の既定が Delete ではなく Snapshot。
    DeletionPolicy: Delete
    UpdateReplacePolicy: Delete
```

**`UpdateReplacePolicy` も一緒に書く。** これは「更新で置換が起きたとき、古い方をどうするか」の指定(→ §6)。`DeletionPolicy` だけ書いて置換が起きると、古いリソースが残る。

**S3 に `force_destroy` 相当は無い。** 中身が残っているバケットは削除に失敗する。このリポジトリは撤収ワークフローが `aws s3 rm --recursive` してから `delete-stack` することで解決している(CDK の `autoDeleteObjects` は中身がカスタムリソース、つまり Lambda。素の YAML でそれを書くのは大掛かりなので採らなかった)。

### 4-7. データソース — 相当する仕組みが無い

Terraform で当たり前に使う `data` ブロックに、CloudFormation は相当物を持たない。**「今の AWS に何があるかをテンプレートから問い合わせる」ことができない。**

実際に困った箇所が `app.yml` に 2 つある(設計書 §8-5, §8-6)。

| 引きたかったもの | Terraform | CloudFormation での回避 |
|---|---|---|
| CloudFront のマネージドポリシー ID | `data "aws_cloudfront_cache_policy"` | `Mappings` に ID を直書き(`app.yml:182`) |
| Route53 のホストゾーン | `data "aws_route53_zone"` | `HostedZoneId` をパラメータで手渡し |

数少ない例外が**疑似パラメータ**(`AWS::AccountId` / `AWS::Region` / `AWS::Partition` / `AWS::StackName`)と、**SSM から引くパラメータ型**(`Type: AWS::SSM::Parameter::Value<String>`)。後者は「事前に SSM に入れておけば引ける」ので、データソースの代用として使える。

---

## 5. 差分の見方 — `plan` と Change Set は同じものではない

ここがこのノートで一番重要な節。**比較しているものが違う。**

| | 何と何を比べるか | 実物(実リソース)を読むか |
|---|---|---|
| `terraform plan` | refresh した state と、設定 | **○ 既定で読む** |
| Change Set(既定) | **前回のテンプレート**と、今回のテンプレート | **✕ 読まない** |
| drift 検出 | テンプレートの定義と、実物 | ○(明示的に実行) |
| drift-aware change set | 三方(前回・今回・実物) | ○(`--deployment-mode REVERT_DRIFT`)。**`deploy` からは使えない** → [別ノート §5-8](./cli-commands-and-change-sets.md) |

**仕様:** Terraform 側は [`terraform plan` のドキュメント](https://developer.hashicorp.com/terraform/cli/commands/plan)にこうある。

> Reads the current state of any already-existing remote objects to make sure that the Terraform state is up-to-date.

`-refresh=false` の説明が裏付けになる。

> setting `refresh=false` causes Terraform to **ignore external changes**, which could result in an incomplete or incorrect plan.

**仕様:** CloudFormation 側は [Using drift-aware change sets](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/drift-aware-change-sets.html) にこうある。

> **Traditional change sets provide a comparison of your new template with the previous template for a stack, but do not account for stack drift.** Drift-aware change sets solve this problem by providing a three-way comparison between: **Actual state** – The live configuration of your resources...

つまり「CloudFormation は Terraform と違って実リソースと見比べてくれる」という理解は**正確に裏返し**。既定では実物を見ないのが CloudFormation で、毎回見るのが Terraform。

### 帰結 1: `ignore_changes` が要らない

外部で変更されても差分に出ないので、「Terraform が毎回巻き戻そうとするから無視させる」という指定が不要になる。実物で比べると差が分かりやすい。

| | `ignore_changes` 相当の指定 |
|---|---|
| `terraform/modules/app-infrastructure/` | **3 箇所**(`ecs_web.tf:72` タスク定義と desired_count / `ecs_queue.tf:32` タスク定義 / `alb.tf:109`・`160` Blue/Green の weight) |
| `cloudformation/app.yml` | **0 箇所** |

### 帰結 2: そのぶん事故が遅れて出る

これは利点ではない。差分に出ないだけで、**外部の変更が保護されているわけではない**。テンプレート側でそのリソースに差分が出た次の更新のときに、まとめて巻き戻る。Terraform なら次の `plan` で即バレるものが、CloudFormation では数週間後に「無関係なはずの変更をしたらアプリのイメージが戻った」という形で出る。

詳しくは [ECS のタスク定義は誰が持つか](./ecs-deploy-ownership.md)。

### 帰結 3: drift は自分で見に行く

`terraform plan` が毎回やってくれていた「実物との照合」は、CloudFormation では明示的な操作になる。

```bash
# drift 検出を開始して結果を見る
aws cloudformation detect-stack-drift --stack-name mylabinfra-stg
aws cloudformation describe-stack-resource-drifts --stack-name mylabinfra-stg
```

**非対応のリソース型がある**点も Terraform と違う(Terraform は provider が読めるものは全部読む)。

### 差分を出すコマンドの話は別ノートへ

`terraform plan` → `apply` の 2 手が、CloudFormation では **`create-change-set` → `describe-change-set` → `execute-change-set` の 3 手**になる。この 3 手の詳しい説明、`--change-set-type` と `--deployment-mode` の違い、`--deployment-mode REVERT_DRIFT` で実物を読ませる方法とその副作用、`aws cloudformation deploy` からはそれが使えないこと —— これらはコマンドの細かい話なので [CloudFormation の CLI コマンドを読み解く](./cli-commands-and-change-sets.md) に移した。

- 3 手の流れと Change Set の性質 → [§2](./cli-commands-and-change-sets.md)
- `--change-set-type` / `--deployment-mode` の軸の違い → [§5-7](./cli-commands-and-change-sets.md)
- `REVERT_DRIFT` の副作用・必要な IAM 権限・出力の変化 → [§5-8](./cli-commands-and-change-sets.md)
- `deploy` に `--deployment-mode` が無いこと → [§5-8, §10-1](./cli-commands-and-change-sets.md)

---

## 6. 更新の意味論 — 何が置換されるか

Terraform は `plan` の出力で `-/+ destroy and then create replacement` と教えてくれる。CloudFormation にも同じ概念があるが、**どこに書かれているかが違う。**

**仕様:** リソース型のリファレンスで、プロパティごとに `Update requires` が定義されている。

| 値 | 意味 | Terraform の対応 |
|---|---|---|
| `No interruption` | 無停止で更新される | `~ update in-place` |
| `Some interruption` | 更新はされるが中断がある(EC2 の再起動など) | (区別が無い) |
| `Replacement` | **作り直し。** 新しいものを作って古いものを消す | `-/+ forces replacement` |

### 6-1. 分かるのは「変えたら置換か」であって「今回置換されるか」ではない

ここを混同しやすい。問いは 2 つに分かれる。

| 問い | いつ分かるか | 情報源 |
|---|---|---|
| A. このプロパティを**変えたら**どうなるか | テンプレートを書いている時点 | リソース型リファレンスの `Update requires` |
| B. 今回の適用で**実際にそのプロパティが変わるのか** | Change Set を作るまで分からない | `describe-change-set` の `Replacement` |

**テンプレートを書いている時点で分かるのは A、つまり if-then の写像だけ。** `app.yml` の `DbName` を書き換えようとしたとき、リファレンスに `Update requires: Replacement` と書いてあるので「これを変えるなら DB が作り直しになる」と AWS に一切触らずに気づける。ここは CloudFormation の方が扱いやすい。

**B はエディタには分からない。** 「今 `mylabinfra-stg` にデプロイされている `DbName` が何か」も「`params/stg.json` の値が前回と変わっているか」も、テンプレートを見ているだけでは知りようがない。だから「今回の `cfn-apply` で実際に置換が起きるか」は `create-change-set` → `describe-change-set` を叩くまで確定しない。

**仕様:** しかも Change Set でも確定しないことがある。`Replacement` は二値ではない([ResourceChange](https://docs.aws.amazon.com/AWSCloudFormation/latest/APIReference/API_ResourceChange.html))。

> if the `RequiresRecreation` field is `Always` and the `Evaluation` field is **`Static`**, `Replacement` is `True`. If the `RequiresRecreation` field is `Always` and the `Evaluation` field is **`Dynamic`**, `Replacement` is **`Conditional`**.

`Dynamic` は値が実行時にしか決まらない(`!GetAtt` や他リソースの出力に依存する)ケース。この場合は Change Set を見ても「置換されるかもしれない」までしか分からない。

### 6-2. エディタが教えてくれるのか — 教えてくれない。手で引く

**結論から言うと、CloudFormation・Terraform とも「置換になるプロパティを書いた瞬間に VS Code が警告する」拡張機能は、調べた範囲では無い。** どちらも**公式リファレンスを手で引く**か、差分コマンド(Change Set / `plan`)を流すことになる。

**CloudFormation 側。** 情報自体は機械可読になっている。2 つの形式がある。

- 旧来の [Resource Specification](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/cfn-resource-specification-format.html)(JSON)はプロパティごとに `UpdateType` を持つ。値は 3 つ

  > CloudFormation replaces the resource when you change **immutable** properties. CloudFormation doesn't replace the resource when you change **mutable** properties. **Conditional** updates can be mutable or immutable, depending on, for example, which other properties you updated.

- 新しいレジストリのリソーススキーマは `createOnlyProperties` で同じことを表す。実際に `AWS::RDS::DBInstance` のスキーマ(`https://schema.cloudformation.us-east-1.amazonaws.com/aws-rds-dbinstance.json`)を落とすと 14 個並んでいて、`/properties/DBName` と `/properties/DBInstanceIdentifier` が入っている

つまり理屈の上ではエディタが警告を出せる情報が揃っている。**にもかかわらず、それを使う lint ルールが無い。** cfn-lint の[ルール一覧](https://github.com/aws-cloudformation/cfn-lint/blob/main/docs/rules.md)を見ると、`readOnlyProperties` を使う E3040(読み取り専用プロパティを書くな)はあるが、`createOnlyProperties` を使うルールは無い。VS Code の CloudFormation Linter 拡張は cfn-lint を走らせるだけなので、当然そこからも出ない。

`UpdateReplacePolicy` 系のルール(I3011 / W3011 → §4-6)はあるので混同しやすいが、あれは「置換されたとき古い方をどうするか」を書けという指摘で、「置換されるかどうか」は教えてくれない。

**Terraform 側。** HashiCorp 公式の VS Code 拡張は補完・ホバー・定義ジャンプを出すが、**そのもとになる provider schema に置換の情報が入っていない。** [`terraform providers schema -json`](https://developer.hashicorp.com/terraform/cli/commands/providers/schema) の属性フィールドは `type` / `description` / `required` / `optional` / `computed` / `sensitive` で、ForceNew に相当するものが無い。ForceNew はプロバイダ内部の概念で、`plan` のときに「置換が必要な属性パス」として初めて外に出てくる。**だから拡張が静的に判定するのは原理的に無理。**

拡張のコマンドパレットから `Terraform: plan` は打てるが、それは plan を流しているのであって、エディタが書いている最中に判定しているわけではない。

| | エディタで分かるか | 実際にやること |
|---|---|---|
| CloudFormation | ✕ | リファレンスの `Update requires` を手で読む / Change Set の `Replacement` を見る |
| Terraform | ✕(スキーマに情報が無い) | provider docs の `Forces new resource` 注釈を読む / `terraform plan` を流す |

### 6-3. Terraform 側の注釈は網羅されていない

「Terraform は `plan` を流すまで分からない」は言い過ぎで、AWS プロバイダのドキュメントにも注釈は付いている。

```
* `backup_target` - (Optional, Forces new resource) Specifies where automated backups ...
* `nchar_character_set_name` - (Optional, Forces new resource) The national character set ...
```

ただし**網羅されていない。** `aws_db_instance` の `db_name` はスキーマ上 `ForceNew: true` なのに、

```go
// internal/service/rds/instance.go
"db_name": {
    Type:     schema.TypeString,
    Optional: true,
    Computed: true,
    ForceNew: true,
```

ドキュメント側には注釈が無い。

```
* `db_name` - (Optional) The name of the database to create when the DB instance is created. ...
```

**つまり本当の差は「事前に分かるか」ではなく、「仕様として全リソース型・全プロパティに定義され機械可読か」。** CloudFormation は型仕様の一部なので抜けが無い。Terraform はプロバイダ作者が書いた説明文の慣習なので抜ける。

### 6-4. 置換されたとき、古い方はどうなるか

置換されること自体より危ないのは、**置換されたときに古い方がどうなるか**。ここで `UpdateReplacePolicy` が効く(→ §4-6)。RDS で `DbName` のような `Replacement` のプロパティを触ると DB が作り直しになり、`UpdateReplacePolicy` が既定(`Snapshot`)だとスナップショットが残って課金が続く。

**傾向:** 論理 ID を変えないまま `Replacement` のプロパティを変えるのが、CloudFormation でいちばん高くつく事故。Change Set の出力に `Replacement: True` が出るので、**実行前に必ずそこを見る。**

---

## 7. 論理 ID を変えると何が起きるか

**論理 ID のリネームは「削除して新規作成」になる。** Terraform でモジュール化などによりアドレスが変わるのと同じ問題だが、**CloudFormation には `moved` ブロックが無い。**

Terraform 側の実物(`terraform/stg/moved.tf`)のコメントがこの問題をよく説明している。

```hcl
# モジュール化により、Terraform state 内のリソースアドレスが変わる。
#   例: aws_db_instance.main → module.app.aws_db_instance.main
#
# moved ブロックがないと、Terraform は
#   「旧アドレスのリソースを destroy して、新アドレスで create する」
# と判断してしまう。moved ブロックがあれば、state 内のアドレスだけ
# 書き換わり、AWS 上の実リソースには何も起きない。
```

CloudFormation でこれに相当する救済は無い。`terraform state mv` も無い。**だからテンプレートを整理するときに論理 ID を変えてはいけない。** 変えたければ、リソースをスタックから外して(`--retain-resources` で削除、または別スタックへ移動)、新しい論理 ID でインポートし直す(→ §8)。手数が多く、事故りやすい。

**実務上の指針:** 論理 ID は最初に決めたら変えない。読みにくい名前でも、リネームのコストの方が高い。

---

## 8. 既存リソースの取り込み

| Terraform | CloudFormation |
|---|---|
| `terraform import` / `import` ブロック | リソースのインポート(`create-change-set --change-set-type IMPORT`) |
| (相当なし) | **IaC generator** — 既存リソースを走査してテンプレートを生成する |

CloudFormation のインポートは Terraform より手間が多い。

1. インポートしたいリソースの定義をテンプレートに書き足す(`DeletionPolicy: Retain` が必須)
2. リソース識別子(バケット名など)を書いた JSON を用意する
3. `--change-set-type IMPORT` で Change Set を作って実行する

**IaC generator は Terraform に無い機能。** アカウント内のリソースを走査して、既存のものからテンプレート(と CDK コード)を起こせる。手で作ってしまったリソースを IaC に取り込むときの入口になる。

このリポジトリでは使っていない(全部を新規に作る構成なので)。**傾向:** 実務では「手で作られた既存環境を IaC に載せる」局面で避けられない機能。

---

## 9. ないもの、まとめ

Terraform から来ると「無い」ことに驚くもの。回避方法とセットで。

| 無いもの | 回避 |
|---|---|
| `modules` | ネストスタック / スタック分割 / `AWS::Include` → [別ノート](./templates-and-prerequisites.md) |
| `locals` | `Mappings` + `!FindInMap`、`!Sub` を都度書く |
| `data` | パラメータで外から渡す。疑似パラメータと SSM パラメータ型は使える |
| `count` / `for_each` / `dynamic` | 列挙するか `Fn::ForEach`(要 Transform) |
| `moved` / `state mv` | **回避なし。** 論理 ID を変えない |
| `force_destroy`(S3) | ワークフローで空にしてから削除する |
| `ignore_changes` | **要らない**(実物を読まないため)→ §5 |
| 構造化した変数(`object`) | フィールドごとに個別のパラメータへ開く |
| 複数リージョンを 1 つで扱う | スタックは 1 リージョンに閉じる。StackSets を使うか、リージョンごとにスタックを作る |
| `terraform fmt` / `validate` のローカル実行 | `cfn-lint`(API を呼ばないので資格情報も不要)。`validate-template` は API 呼び出しで 51,200 バイト制限も掛かる |

逆に**Terraform に無くて CloudFormation にあるもの**も挙げておく。

| あるもの | 内容 |
|---|---|
| スタックレベルのタグ伝播 | スタックに付けたタグが全リソースに自動で入る(設計書 §8-10) |
| `Update requires` の事前明示 | 全リソース型・全プロパティに置換の有無が定義されている(機械可読) → §6 |
| サービスロール方式 | 「CloudFormation にリソースを作らせる権限」を分離できる。実行者の資格情報が漏れても、テンプレートに書かれていないことはできない |
| ロールバック | 更新が失敗すると自動で前の状態に戻る(Terraform は途中で止まる) |
| IaC generator | 既存リソースからテンプレートを生成 → §8 |
| 排他制御が組み込み | ロック用の DynamoDB を用意しなくてよい |

---

## 10. このリポジトリでの実例の場所

| 見たいもの | 場所 |
|---|---|
| `Parameters` の実例(40 個超) | `cloudformation/app.yml:29-181` |
| `Mappings` を定数表に使う | `cloudformation/app.yml:182-190` |
| `Conditions` の 3 例 | `cloudformation/app.yml:192-201` |
| `DeletionPolicy` を明示する理由 | `cloudformation/app.yml:607` 付近のコメント |
| `!Sub` でのリソース名組み立て | `cloudformation/app.yml:1035` 付近 |
| 環境差分の渡し方 | `cloudformation/params/stg.json` |
| Terraform 側の `ignore_changes` | `terraform/modules/app-infrastructure/ecs_web.tf:70-76` |
| 論理 ID のリネーム問題(Terraform 版) | `terraform/stg/moved.tf` |

運用手順は [docs/infrastructure/cloudformation-operations.md](../../infrastructure/cloudformation-operations.md)、決定の理由は [ADR-0001](../../adr/0001-cloudformation-yaml-over-terraform.md) と設計書 [2026-08-19-phase13-cloudformation-design.md](../../superpowers/specs/2026-08-19-phase13-cloudformation-design.md)。
