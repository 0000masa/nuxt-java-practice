# アプリのデプロイは CloudFormation の内側で行う

日付: 2026-08-21
ステータス: accepted

## 決定

アプリのイメージ更新(通常のリリース)を **CloudFormation のスタック更新として行う。**

- イメージタグは `ImageTag` スタックパラメータ。**リリース = スタック更新**
- ECS サービスはタスク定義を `TaskDefinition: !Ref AppTaskDefinition` で参照する(リビジョン付き ARN)。タスク定義の中身の唯一の正はテンプレート
- `aws ecs register-task-definition` / `update-service` を CloudFormation の外から叩く経路は**作らない**
- ワークフローは目的で分ける。**構築**は `cfn-deploy.yml`、**反映**は `cfn-apply.yml`、**撤収**は `cfn-destroy.yml`。**CloudFormation を実際に叩くのは `cfn-apply.yml` だけで、構築はそこへ委譲する**(→ [ADR-0009](./0009-cfn-apply-as-the-single-cloudformation-caller.md))

## 背景と理由

参考にした Terraform リポジトリは逆の分担だった。AWS 環境の作成は Terraform、普段のタスク更新は GitHub Actions から ECS を直接叩く。境界を守っていたのが `ignore_changes = [task_definition, desired_count]` で、**Terraform は `refresh` で実物を読んでから差分を出すため、この指定が無いと外での更新を毎回巻き戻してしまう。**

CloudFormation には属性単位の `ignore_changes` に相当する機能がない。ただし**同じ問題が同じ形では起きない。** CloudFormation は実リソースの状態を読み直さず、比較するのは「前回のテンプレート」と「今回のテンプレート」だけである(→ [設計書の決定19](../superpowers/specs/2026-08-19-phase13-cloudformation-design.md#決定19-ecs-はネイティブ-bluegreen--オートスケーリング))。そのため外部で `update-service` しても即座には戻らない。**戻るのは、テンプレート側で ECS サービスかタスク定義に差分が出た次のスタック更新のとき**で、「インフラを直したら無関係なはずのアプリのイメージが数週間前に巻き戻る」という形で現れる。

つまり選択肢は「アプリのデプロイを IaC の内側に置くか、外に出すか」という設計判断であり、IaC が何であるかで自動的に決まるものではない。実務は二派に分かれるが、**CloudFormation を使うチームは内側(この決定)、Terraform を使うチームは外側が多い。** CloudFormation 側の公式ツールチェーンはすべて内側を前提に作られている。

- `sam deploy` / `cdk deploy` — イメージやアセットを含めてスタック更新として扱う
- AWS Copilot / App Runner も同様

このリポジトリで内側を選ぶ理由は 3 つ。

1. **デプロイ頻度が低い。** 常時公開せず、検証したいときだけ建てて使い終わったら撤収する運用(→ [ADR-0001](./0001-cloudformation-yaml-over-terraform.md))。内側の欠点である「1 リリースあたりスタック更新 2〜5 分」がほとんど効かない
2. **タスク定義の中身がスタックの出力に強く依存している。** `DB_HOST` は `!GetAtt Database.Endpoint.Address`、他に画像バケット名・CloudFront のドメイン・Secrets Manager と SSM の ARN。外に出すと、この受け渡しをデプロイツール側で自作することになる
3. **外側にしたときの利点が小さい。** 速さと「インフラ差分が混ざらない」ことだが、どちらも検証環境では価値が低い

## 検討したが採らなかった選択肢

- **ECS サービスから family だけを参照する** — CloudFormation の [`AWS::ECS::Service`](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-ecs-service.html) の `TaskDefinition` は「`family` と `revision`(`family:revision`)または完全な ARN。**リビジョンを指定しない場合は最新の `ACTIVE` リビジョンが使われる**」と定義されている。`!Sub ${ProjectName}-${EnvName}-app` と書けばテンプレート上の値が不変になり、外で登録した新リビジョンが巻き戻らない。これが `ignore_changes = [task_definition]` の CloudFormation 版である。

  採らなかったのは、**タスク定義の所有者が CloudFormation とデプロイツールの 2 つになる**ため。テンプレート側でタスク定義を直しても ECS サービスのプロパティが変わらないので `UpdateService` が呼ばれず、**その修正はロールアウトされない**(逆向きの罠)。破綻を避けるには「デプロイツールは family の最新 ACTIVE リビジョンを土台にしてイメージタグだけ差し替える」という規律を人が守り続ける必要がある。加えて `DependsOn: AppTaskDefinition` の明示も要る(`!Ref` をやめると CloudFormation が依存関係を推論できない)。

- **タスク定義を CloudFormation の外に出す** — ecspresso や `aws-actions/amazon-ecs-deploy-task-definition` にタスク定義そのものを持たせる。属性単位で無視できない CloudFormation で外側の分担をやるなら、これが本来の形。採らなかったのは上記の理由 2(スタック出力への依存)による

- **基盤スタックとサービススタックに分割する** — 内側を選んだうえで「アプリだけ 2 分で更新」を成立させる、頻繁にデプロイする現場の定石。採れなかったのは、サービススタックが基盤側の値(DB エンドポイント・サブネット・Secrets の ARN)を必要とし、`Export` / `ImportValue` を使うことになるため。**エクスポート元のスタックは、参照している側が消えるまで削除できない。** 「全部まとめて建てて全部まとめて消す」という撤収運用と正面衝突する(`cloudformation/app.yml` の Outputs のコメントに同じ理由が書かれている)

- **`AWS::CodeDeploy::BlueGreen` フック**(`Transform: AWS::CodeDeployBlueGreen`)— 「タスク定義の更新と他リソースの更新を同一スタック更新に混ぜられない」という制約がある。このリポジトリが採ったのは CodeDeploy ではなく **ECS ネイティブ Blue/Green**(`DeploymentConfiguration.Strategy: BLUE_GREEN`)なので、この制約とは無関係

## 結果として生じること

- **アプリだけ直したいときもインフラの差分が同時に適用される。** これは「分割しない内側」を選んだ代償。頻繁にデプロイする本番でこの形を採るなら、スタック分割を検討する段階に来ている
- **リリースのたびに Change Set の作成とスタック更新を待つ。** ECS サービスの入れ替えを含むと 10〜20 分
- **`WebDesiredCount` を毎回明示的に渡すので、オートスケーリングが増やしている最中に反映すると `params` の値に戻る可能性がある。** CloudFormation が `UpdateService` に `desiredCount` を常に含めるかは公式ドキュメントに明記がなく、未検証(→ 設計書 §10)。stg は `MinCapacity=1 / MaxCapacity=2` なので実害は小さい。必要になれば [drift-aware change set](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/drift-aware-change-sets.html)(`--deployment-mode REVERT_DRIFT`)に切り替える道がある。ECS の desired count は「AWS 管理プロパティ」として drift を保持すると明記されている
- **CloudFormation の外から `update-service` してはいけない**、という規律が要る。やっても即座には戻らないが、次にテンプレート側でタスク定義に差分が出た瞬間に巻き戻る。緊急時に手で叩いたら、同じイメージタグで `cfn-apply.yml` を流して記憶を合わせる
