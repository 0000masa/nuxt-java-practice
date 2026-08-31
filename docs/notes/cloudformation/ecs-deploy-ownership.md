# ECS のタスク定義は誰が持つか — `ignore_changes` と CloudFormation

「AWS 環境の構築は IaC、日々のアプリのデプロイは CI/CD」という分担は、Terraform では `lifecycle.ignore_changes` が支えている。CloudFormation に同じ指定は無い。ではどうなるのか、という話。

このノートは記述の確からしさを 3 段階で書き分ける(**仕様** / **傾向** / **未検証**)。

要点は 3 つ。

1. **`ignore_changes` が必要なのは、Terraform が差分を出すときに実物を読むから。** 読まない CloudFormation では、そもそもこの指定が要らない
2. **「要らない」は「安全」ではない。** 外部の変更は保護されておらず、次にテンプレート側で差分が出たときにまとめて巻き戻る。**Terraform なら即バレする事故が、CloudFormation では遅れて出る**
3. **CloudFormation にも `ignore_changes` 相当はある**(タスク定義を family だけで参照する)。そして**「直したのにロールアウトされない」問題は Terraform 側にもある** — `ignore_changes` は設定側の変更も反映しないので、同じ罠が起きる。CloudFormation 固有の悪さは別のところで、**テンプレートに残した古いイメージのタスク定義が「最新 ACTIVE」になり、テンプレートがそれを指し続けること**

関連: [Terraform 経験者のための CloudFormation](./terraform-to-cloudformation.md) §5 / [CloudFormation の CLI コマンドを読み解く](./cli-commands-and-change-sets.md) §5-8 / [ADR-0007](../../adr/0007-app-deploy-inside-cloudformation.md) / [テンプレートの書き方(YAML の文法と組み込み関数)](./template-syntax-and-functions.md)

---

## 1. なぜ `ignore_changes` が必要なのか

`terraform/modules/app-infrastructure/ecs_web.tf:70-76` の実物。

```hcl
resource "aws_ecs_service" "main" {
  name            = "${var.project_name}-main-service"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.main.arn
  desired_count   = var.ecs_web_service_config.desired_count

  # ...

  lifecycle {
    # ecspresso 側でタスク定義を更新しているため、その変更を Terraform の管理外にする
    ignore_changes = [
      task_definition,
      desired_count # オートスケーリングを使うなら追加
    ]
  }
}
```

この指定が要るのは、**Terraform が差分を出すときに実物を読むから。**

**仕様:** [`terraform plan` のドキュメント](https://developer.hashicorp.com/terraform/cli/commands/plan)より。

> Reads the current state of any already-existing remote objects to make sure that the Terraform state is up-to-date.

`-refresh=false` の説明が裏付けになる。

> setting `refresh=false` causes Terraform to **ignore external changes**, which could result in an incomplete or incorrect plan.

つまり毎回こうなる。

```
1. ecspresso が新しいタスク定義 rev.42 を登録し、サービスをそれに向ける
2. 誰かがインフラを直して terraform plan
3. Terraform は実物を読む → サービスは rev.42 を指している
4. 設定(state 経由)は rev.31 を指している
5. 「rev.42 → rev.31 に戻す」という差分が出る  ← これを毎回止める必要がある
```

**`ignore_changes` は「属性単位で見ない」という指定。** 「外部の変更を尊重する」ではなく「その属性の差分を計算しない」。だから `desired_count` も一緒に入っている(オートスケーリングが増やした数を毎回戻そうとするため)。

**仕様:** [`ignore_changes` のドキュメント](https://developer.hashicorp.com/terraform/language/block/resource#ignore_changes)より。

> The `ignore_changes` argument specifies a list of resource attributes for Terraform to ignore when planning updates to the resource.

---

## 2. CloudFormation には要らない。ただし代償がある

CloudFormation は差分を出すときに実物を読まない。

**仕様:** [Using drift-aware change sets](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/drift-aware-change-sets.html) より。

> **Traditional change sets provide a comparison of your new template with the previous template for a stack, but do not account for stack drift.**

だから上の 5 ステップのうち **3 と 4 が起きない**。比較するのは「前回のテンプレート」と「今回のテンプレート」だけなので、外で `update-service` されていても差分に出ない。

実物で数を比べるとこうなる。

| | `ignore_changes` 相当の指定 |
|---|---|
| `terraform/modules/app-infrastructure/` | **3 箇所** — `ecs_web.tf:72`(タスク定義 + desired_count)、`ecs_queue.tf:32`(タスク定義)、`alb.tf:109` と `:160`(Blue/Green の weight) |
| `cloudformation/app.yml` | **0 箇所** |

### 代償 — 差分が遅れて爆発する

**これは利点ではない。** 「差分に出ない」は「保護されている」ではない。テンプレート側でそのリソースに差分が出た瞬間に、CloudFormation は「前回のテンプレートの値」を正として書き戻す。

```
1 月 10 日  ecspresso 相当のツールが外から rev.42 にした(CloudFormation は知らない)
1 月 20 日  何も起きない。Change Set にも出ない
2 月  3 日  無関係なインフラ変更(ログ保持期間を変えた、など)を反映
            → その更新でタスク定義に差分が出た
            → サービスが rev.31(1 月 10 日以前のイメージ)に戻る
```

Terraform なら 1 月 20 日の `plan` で気付く。CloudFormation は**2 月 3 日に、無関係な変更のついでに起きる。** 原因と結果が離れているので追いにくい。

### 見えるようにはできる。ただし選択的にはできない

「CloudFormation では外部の変更が見えない」は言い過ぎで、読ませる手段はある。ただし**既定の Change Set は手段にならない。**

| 手段 | 読み取り専用か | 制約 |
|---|---|---|
| 既定の Change Set | — | **実物を読まない**(§2 冒頭の引用「do not account for stack drift」)。`cfn-apply.yml` の `dry_run` も既定モードなので同じ |
| `detect-stack-drift` | ○ | 別操作。Change Set の出力とは別に見ることになる。非対応のリソース型がある |
| drift-aware change set(`--deployment-mode REVERT_DRIFT`) | 作るだけなら ○ | **`aws cloudformation deploy` からは使えない。** 実行すると全部戻る(→ [CLI コマンドのノート §5-8](./cli-commands-and-change-sets.md)) |

**決定的なのは、`REVERT_DRIFT` に「このプロパティだけは見るが戻さない」という指定が無いこと。** 戻さないでくれるのは AWS が決めた 3 種類(AWS 管理プロパティ / 書き込み専用プロパティ / 不変プロパティ)だけで、**タスク定義は AWS 管理プロパティの一覧に入っていない**(公式が挙げる例は RDS の `AutoMinorVersionUpgrade`、`AWS::ApplicationAutoScaling::ScalableTarget`、`AWS::AutoScaling::ScalingPolicy`)。つまり外部ツールが登録したリビジョンは「ドリフト」として戻される。

```
Terraform : 既定で全部読む  +  ignore_changes で 1 属性だけ明示的に外す
CFN       : 読まない  か  読んで全部戻す(タスク定義も戻る)の二択
```

**差は「見えるかどうか」ではなく「選択的に扱えるかどうか」。** CloudFormation で外側の分担をやると、盲目で運用するか、見るたびに戻す危険を負うかになる。

そして family 参照を採ると、これが効く場面がもう 1 つ増える。**テンプレートが指す先が現実からずれても、その事実は Change Set にも drift 検出以外の経路にも出てこない**(→ §3-3)。

設定・必要な IAM 権限・副作用の詳細 → [CLI コマンドのノート §5-8](./cli-commands-and-change-sets.md#deploy-では実物を読ませられない)。

---

## 3. 4 つの構成

「タスク定義の中身を書いた正本は誰が持つか」を軸に並べる。

### 3-1. Terraform + 外側(ecspresso など)← 前に書いた構成

```
所有者: ecspresso(タスク定義の JSON を持つ)
Terraform: サービスは作るが task_definition は見ない(ignore_changes)
リリース: GitHub Actions が register-task-definition → update-service
```

**利点:** リリースが速い(数十秒〜数分)。インフラの差分がリリースに混ざらない。IaC を触らずにデプロイできるので、アプリ開発者が IaC を知らなくても回る。

**必要な規律が 2 つある。**

- **`ignore_changes` を消さないこと。** 消すと次の `plan` でタスク定義が巻き戻る差分が出る
- **Terraform 側のタスク定義を直さないこと。** `ecs_web.tf:80` には `aws_ecs_task_definition.main` が container_definitions ごと存在し、イメージも `var.image_tag_laravel` / `var.image_tag_nginx` から来ている。だが `ignore_changes = [task_definition]` があるので、**ここを直しても新リビジョンが登録されるだけでロールアウトされない**(理由 → §3-3)。**この構成が破綻しないのは `ignore_changes` が罠を消しているからではなく、正本が ecspresso 側にあって「Terraform 側のタスク定義を直す」場面が運用上発生しないから**

**この構成の実物:** `terraform/modules/app-infrastructure/ecs_web.tf`(ALB の weight も含めて `ignore_changes` が 3 箇所ある)。

### 3-2. CloudFormation + 内側 ← このリポジトリ

```
所有者: cloudformation/app.yml(タスク定義の中身の唯一の正)
リリース: ImageTag パラメータを変えてスタック更新
```

`app.yml:1203` 付近(`AppTaskDefinition`)と `:1406` 付近(`Service`)。

```yaml
  AppTaskDefinition:
    Type: AWS::ECS::TaskDefinition
    Properties:
      Family: !Sub ${ProjectName}-${EnvName}-app
      ContainerDefinitions:
        - Name: app
          Image: !Sub ${AWS::AccountId}.dkr.ecr.${AWS::Region}.amazonaws.com/${EcrRepositoryName}:${ImageTag}
          Environment:
            - Name: DB_HOST
              Value: !GetAtt Database.Endpoint.Address

  Service:
    Type: AWS::ECS::Service
    Properties:
      TaskDefinition: !Ref AppTaskDefinition   # ← リビジョン付き ARN が入る
      DesiredCount: !Ref WebDesiredCount
```

**`!Ref AppTaskDefinition` はリビジョン付きの ARN を返す。** だからテンプレートを更新すると新しいリビジョンが登録され、サービスのプロパティも変わり、`UpdateService` が呼ばれてロールアウトする。**リリース = スタック更新**になる。

**利点:** 正本が 1 つ。`DB_HOST` を `!GetAtt Database.Endpoint.Address` から取れる(スタックの中なので参照できる)。外部ツールが要らない。

**代償:** リリースのたびに Change Set の作成とスタック更新を待つ。ECS サービスの入れ替えを含むと 10〜20 分。**アプリだけ直したいときもインフラの差分が同時に適用される。**

このリポジトリがこれを選んだ理由は 3 つ(→ [ADR-0007](../../adr/0007-app-deploy-inside-cloudformation.md))。デプロイ頻度が低い(常時公開しない検証環境)、タスク定義がスタックの出力に強く依存している、外側にしたときの利点が検証環境では小さい。

### 3-3. CloudFormation + family 参照 = `ignore_changes` 相当

**仕様:** [`AWS::ECS::Service` の `TaskDefinition`](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-ecs-service.html) より。

> The `family` and `revision` (`family:revision`) or full ARN of the task definition to run in your service. **If a `revision` isn't specified, the latest `ACTIVE` revision is used.**

つまりこう書ける。

```yaml
  Service:
    Type: AWS::ECS::Service
    DependsOn: AppTaskDefinition        # !Ref をやめると依存を推論できないので明示が必要
    Properties:
      TaskDefinition: !Sub ${ProjectName}-${EnvName}-app   # family だけ。リビジョンを書かない
```

**テンプレート上の値が不変になるので、外で登録した新リビジョンが巻き戻らない。** これが `ignore_changes = [task_definition]` の CloudFormation 版。**「相当物が無い」わけではない。**

**では何が問題か。「無視」ではなく「所有者が 2 人になる」こと。**

```
テンプレート側でタスク定義の中身を直す(環境変数を 1 つ足す、など)
  → 新しいリビジョンは登録される
  → しかしサービスのプロパティ(family 文字列)は変わらない
  → UpdateService が呼ばれない
  → その修正はロールアウトされない  ← 逆向きの罠
```

**そして、この罠は Terraform + `ignore_changes` にもある。** `ignore_changes` は「外部の変更を無視する」だけの指定ではなく、**その属性については設定側の変更も実物に反映しない。**

**仕様:** [lifecycle のドキュメント](https://developer.hashicorp.com/terraform/language/meta-arguments/lifecycle)より。

> By default, Terraform detects any difference in the current settings of a real infrastructure object and **plans to update the remote object to match configuration.** Use the `ignore_changes` argument when a resource is created with references to data that may change in the future, but should not affect the resource after its creation.

つまり `ignore_changes` に入れた属性は更新の計画から外れるので、**設定を直しても実物に届かない。** §3-1 の構成がまさにそれで、`ecs_web.tf:80` の `aws_ecs_task_definition.main` を編集しても(`var.image_tag_laravel` を上げても)ロールアウトされない。

| | Terraform + `ignore_changes` | CFN + family 参照 |
|---|---|---|
| 設定 / テンプレートを直したときの新リビジョン登録 | される | される |
| サービスへのロールアウト | **されない** | **されない** |
| **無視している値の中身** | refresh で読んだ実物の値(`...:42`)。**「いま現実に走っているもの」** | family 文字列。**「いちばん新しいもの」**(ECS が解決する) |
| 回復手段 | `ignore_changes` から属性を外す | `!Ref` に戻す |

CFN 側でも新リビジョンは普通に登録される。`AWS::ECS::TaskDefinition` の [`ContainerDefinition`](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-properties-ecs-taskdefinition-containerdefinition.html) は各プロパティが `Update requires: Replacement` なので、中身を直せば置換 = `RegisterTaskDefinition` が走る。**「タスク定義の更新すら入らない」わけではない。**

#### 差が出るのは 2 手目。1 手目は完全に同じ

表の 3 行目が効いてくる場面を、2 段階で追う。前提は外側の分担で、**IaC 側のタスク定義のイメージは構築時のまま `:v1`。実際に走っているのは外部ツールが入れた rev.42 / `:v9`。**

**Step A — IaC の環境変数だけ直して適用する。両方まったく同じ。**

| | 起きること |
|---|---|
| Terraform | rev.43(`:v1` + 新しい環境変数)を登録。サービスは更新しない → **rev.42 / `:v9` のまま** |
| CFN + family | rev.43 を登録。サービスのプロパティ(family 文字列)は不変なので `UpdateService` が呼ばれない → **rev.42 / `:v9` のまま** |

**ここに差は無い。** どちらも「直したのにロールアウトされない」で、上に書いた共通の罠そのもの。

**Step B — 後日、サービスの別のプロパティ(`BakeTimeInMinutes` など)を直して適用する。ここだけ違う。**

| | 起きること |
|---|---|
| Terraform | サービスに差分 → `UpdateService`。無視対象の値は refresh で読んだ `...:42` なので、送らないか `...:42` を送るかのどちらか。**どちらでも rev.42 / `:v9` のまま** |
| CFN + family | サービスに差分 → `UpdateService`。渡す値は family 文字列。**含めるなら ECS が最新 ACTIVE = rev.43 を解決 → イメージが `:v1` に戻る** |

Step A の前は「現実 = 最新 = rev.42」で一致していた。**Step A が rev.43 を作った瞬間に「最新」が「現実」から離れ、テンプレートは離れた方を指し続ける。**

**確実なのはここまで:** Step A の後、テンプレートが指す先(最新 ACTIVE)と現実がずれる。Terraform 側はずれない。

**未検証:** そのずれが実害になるかは、CloudFormation が値の変わっていない `TaskDefinition` を `UpdateService` に含めるかで決まる。公式ドキュメントに記載がない(→ §6 と同じ論点)。**含めないなら Step B も同じ結果になり、差は消える。**

**ずれは自然に解除される。** 次の日々のデプロイが走れば、外部ツールは rev.44 を登録して**リビジョン付きの ARN を明示して** `update-service` を呼ぶので、「現実 = 最新 = rev.44」に戻る。**危険な窓は「IaC を適用してから次の日々のデプロイまで」。** 毎日デプロイする現場では短く、**普段デプロイしない検証環境では長く開き続ける。**

**そしてこのずれは Change Set に出ない。** 解決するのは ECS で、しかも API を呼ぶ瞬間に起きるので、Change Set の表示は

```
TaskDefinition: myproj-stg-app  →  myproj-stg-app   (変更なし)
```

にしかならない。`dry_run` でも見えない(→ §2「見えるようにはできる。ただし選択的にはできない」)。

#### もう 1 つの差 — `DependsOn` が必要になる

**`!Ref` をやめると依存関係の推論も失われる。** CloudFormation はサービスとタスク定義の順序を参照から組み立てているので、`DependsOn` を明示しないと壊れる(上のコードの注記)。Terraform 側は属性を無視するだけで、`aws_ecs_task_definition.main.arn` という参照は設定に残るので**依存グラフは無傷**。

#### 外側の分担でこれが何を意味するか

日々のデプロイを [`aws-actions/amazon-ecs-render-task-definition`](https://github.com/aws-actions/amazon-ecs-render-task-definition) + `amazon-ecs-deploy-task-definition` でやる場合、**この Action の `task-definition` 入力はリポジトリにコミットした JSON ファイルが前提**(README の例はすべて `task-definition: task-definition.json`)。つまりタスク定義の正本はそのファイルで、**テンプレート側のタスク定義は 2 つ目の写しになる。**

すると環境変数を 1 つ変えるだけでも、テンプレート側のイメージ URI を**現在走っているものに書き換えておく**必要が出る。書かなければ上のずれが生まれるからだ。**これは「イメージタグを IaC に知らせなくて済む」という外側の分担の利点そのものを打ち消す。**

**未検証:** 古いリビジョンの後始末は、おそらく対称。Terraform の [`aws_ecs_task_definition`](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/ecs_task_definition) の `skip_destroy` は「Whether to retain the old revision when the resource is destroyed or replacement is necessary. **Default is `false`**」なので既定では古いリビジョンを deregister する。CloudFormation も[スタック更新のドキュメント](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/using-cfn-updating-stacks-monitor-stack.html)に「置換後は古いリソースの削除を 3 回試みる」とあるので同じと読めるが、**ECS のタスク定義に限った明記がない。**

破綻を避けるには「デプロイツールは family の最新 ACTIVE リビジョンを土台にして、イメージタグだけ差し替える」という規律を人が守り続ける必要がある。ADR-0007 がこれを採らなかったのはその理由。

### 3-4. CloudFormation + 外部ツールにタスク定義を持たせる

3-3 の「所有者が 2 人」を解消する形。**タスク定義そのものを CloudFormation の外に出し、ecspresso や `aws-actions/amazon-ecs-deploy-task-definition` に持たせる。** CloudFormation はサービスと周辺だけを作る。

**CloudFormation で外側の分担をやるなら、事実上これしかない。** 理由は 3-3 の裏返しで、**3-3 はテンプレートに古いイメージのタスク定義を抱え続け、それが「最新 ACTIVE」になってしまう**から。タスク定義をテンプレートから消せば、ずれる対象そのものが無くなる。

**代償 1: タスク定義に入れたい値の受け渡しを自作する。** DB エンドポイント、S3 バケット名、CloudFront のドメイン、Secrets Manager と SSM の ARN。CloudFormation の `Outputs` を読んでツールの設定に流し込むコードが増える。ADR-0007 がこれを採らなかったのはこの理由。

**代償 2: 初回のリビジョンを誰が登録するか。** タスク定義がテンプレートに無いので、**`DependsOn` では解決できない**(テンプレート内のリソース間の順序付けの仕組みなので、外にあるものは待たせられない)。手は 3 つ。

- スタックを作る前に、ワークフローが `register-task-definition` でリビジョン 1 を登録しておく
- **タスク定義の ARN をスタックパラメータで受ける。** family 参照をやめるので「最新が勝つ」も消え、どのリビジョンかがスタックに記録される。ただしデプロイのたびにパラメータを更新することになり、外側に出した意味が薄れる
- ecspresso にサービス作成まで任せ、CloudFormation はサービスを持たない

**未検証:** ACTIVE なリビジョンが 1 つも無い状態で `CreateService` が family を解決できず失敗するかは、公式ドキュメントに明記がない。

---

## 4. どちらを選ぶか

| 判断材料 | 内側(スタック更新) | 外側(外部ツール) |
|---|---|---|
| デプロイ頻度 | 低い(日に数回未満) | 高い |
| 1 リリースの所要時間 | 数分〜20 分 | 数十秒〜数分 |
| タスク定義がスタックの出力に依存するか | 依存が多いほど内側が楽 | 依存が少ないなら外側でよい |
| 正本の数 | 1 つ | 2 つ(規律が必要) |
| インフラ差分の混入 | 混ざる(スタック分割で解消可) | 混ざらない |
| アプリ開発者が IaC を知る必要 | ある | 無い |
| 事故の見つけやすさ(CFN の場合) | 問題にならない | **悪い**(遅れて出る → §2) |

**傾向:** 実務は二派に分かれる。**CloudFormation を使うチームは内側、Terraform を使うチームは外側が多い。** 直接の統計は持っていないので断定はしない。根拠は 2 本ある。

1. **CloudFormation 側の公式ツールチェーンがすべて内側を前提に作られている**(間接証拠)
2. **CloudFormation でタスク定義をテンプレートに残したまま外側をやると、テンプレートが指す先が現実からずれる。** 古いイメージのタスク定義が「最新 ACTIVE」になり、後日の無関係な更新でそれが出てくる可能性がある(実害になるかは**未検証** → §3-3)。避けるにはタスク定義を完全に外へ出す(→ §3-4)以外になく、そちらには受け渡しと初回リビジョンの代償がある。**Terraform は同じ構成でもずれないので、外側が成立しやすい**

- `sam deploy` — Lambda のコードやアセットを含めてスタック更新として扱う
- `cdk deploy` — 同じ
- AWS Copilot / App Runner — 同じ

**内側を選んだうえで速さも欲しいなら、スタックを分割する**(基盤スタック + サービススタック)のが頻繁にデプロイする現場の定石。このリポジトリが採れなかったのは、サービススタックが基盤側の値を `Export` / `ImportValue` で参照することになり、**エクスポート元のスタックは参照している側が消えるまで削除できない**という仕様が「全部まとめて建てて全部まとめて消す」運用と衝突するため(→ [テンプレートの分割と置き場](./templates-and-prerequisites.md))。

---

## 5. 同じ話が ALB の weight でも起きる

タスク定義以外にも「ECS が実物を書き換えるプロパティ」がある。Blue/Green デプロイ中のリスナールールの weight。

`terraform/modules/app-infrastructure/alb.tf:107-110`:

```hcl
  # ECS がデプロイ中に weight を書き換えるので Terraform は追従しない
  lifecycle {
    ignore_changes = [action]
  }
```

`app.yml` に対応する指定は無い。**実物を読まないので要らない。**

ただし §2 の代償はここにも掛かる。**そのリソースの定義を後で編集すると、そのとき weight もテンプレートの値に戻る。** ECS ネイティブ Blue/Green を使っている最中にリスナールールを編集すると、切り替え途中の状態を壊しうる。

---

## 6. まだ確かめていないこと

**未検証:** オートスケーリングが `WebDesiredCount` を超えてタスクを増やしている最中にスタックを更新したとき、タスク数が `params` の値に戻るか。

`app.yml:1416` は `DesiredCount: !Ref WebDesiredCount` と書いており、ワークフローは毎回この値を渡す。**CloudFormation が ECS の `UpdateService` に `desiredCount` を常に含めるのか、変更のないプロパティは送らないのかが公式ドキュメントに明記されていない。**

stg は `MinCapacity=1 / MaxCapacity=2` なので実害は小さい。必要になれば [drift-aware change set](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/drift-aware-change-sets.html)(`--deployment-mode REVERT_DRIFT`)に切り替える道がある。ECS の desired count は「AWS 管理プロパティ」として drift を保持すると明記されている。**切り替えに必要な設定・IAM 権限・副作用** → [CLI コマンドのノート §5-8](./cli-commands-and-change-sets.md#deploy-では実物を読ませられない)。

検証結果は設計書 [§10 実測で確かめること](../../superpowers/specs/2026-08-19-phase13-cloudformation-design.md)に書く。

---

## 7. 運用上の規律(このリポジトリ)

- **CloudFormation の外から `aws ecs update-service` / `register-task-definition` を叩かない。** 叩いても即座には戻らないが、次にテンプレート側でタスク定義に差分が出た瞬間に巻き戻る
- 緊急時に手で叩いたら、**同じイメージタグで `cfn-apply.yml` を流してテンプレート側の記憶を合わせる**
- イメージタグは `ecr-push.yml` のサマリに出る短縮 SHA を使う(→ [運用手順](../../infrastructure/cloudformation-operations.md))
