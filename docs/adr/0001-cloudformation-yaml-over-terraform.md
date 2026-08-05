# IaC は Terraform をやめ、素の CloudFormation YAML を使う

日付: 2026-08-05
ステータス: accepted

## 決定

AWS のインフラコードを **Terraform から AWS CloudFormation(素の YAML テンプレート)に変更する**。AWS CDK は使わない。

## 背景と理由

このリポジトリは学習が目的で、著者は Terraform の実務経験がある一方 **CloudFormation は未経験**。学習価値が高いのは未経験の CloudFormation のほうであり、Terraform を続けても新しく得るものが少ない。着手時点で `terraform/` は `.gitkeep` のみ(実装ゼロ)だったため、乗り換えコストはドキュメントの書き換えのみだった。

CDK を採用しなかった理由は 3 つある。

1. **CDK は CloudFormation の学習を免除しない。** 障害調査では結局、自動生成された論理 ID が並ぶ CloudFormation テンプレートを読むことになる。学びたい対象を抽象化で隠すのは順序が逆
2. **`cdk bootstrap` がこのリポジトリの運用と噛み合わない。** CDK は初回にアカウント×リージョンごとに CDKToolkit スタック(S3 + ECR + IAM ロール群)を作るが、これは `cdk destroy` で消えず常駐する。「使うときだけ建てて終わったら全部消す」方針に、消えない残留物が生まれる。bootstrap は強い IAM 権限も要求するため OIDC ロール設計も複雑になる
3. **Terraform の経験が素の YAML に直接活きる。** 宣言的に書く感覚はすでにあるので、あとは CloudFormation 固有の部分(Change Set、`DeletionPolicy`、スタックの状態遷移、`Ref` / `Fn::GetAtt`)を覚えるだけで済む

なお AWS 公式(Prescriptive Guidance)は「組織に開発力があるなら CDK を推奨」としており、**この決定は公式推奨からの意図的な逸脱**である。学習目的という前提が変われば、再検討の余地がある。

## 検討したが採らなかった選択肢

- **AWS CDK (TypeScript)** — 記述量は 1/10 以下になり、`autoDeleteObjects` など削除まわりの利便性もある。上記 3 点の理由で見送り。**将来の学習題材として、同じ構成を CDK で書き直し `cdk synth` の出力を比較する案は残している**
- **AWS CDK (Java)** — バックエンドが Java だが、CDK 利用者の主流は TypeScript(Python が 2 位、Java は少数)。Java でも CDK CLI は npm 配布なので Node.js が必須で依存が増えるだけになる。加えて `cdk init --language java` が生成するのは Maven プロジェクトで、このリポジトリの Gradle と混在する
- **CDK for Terraform (CDKTF)** — 「CDK の書き味 + Terraform のエンジン」が狙えたが、**2025-12-10 に HashiCorp が sunset しリポジトリはアーカイブ済み**。新規採用の選択肢にならない
- **Terraform の継続** — 最も慣れているが、学習リポジトリで既知の技術を続ける意味が薄い

## 結果として生じること

- **state の自前管理が不要になる。** Terraform で必要だった state 用 S3 バケットとロックの設計が丸ごと消え、状態は CloudFormation スタックが AWS 側で保持する
- **`count` / `for_each` に相当する機能が素の YAML にない。** 繰り返しが必要なら `Transform: AWS::LanguageExtensions` の `Fn::ForEach` を使うか、素直に列挙する
- **`terraform plan` に相当するのは Change Set** で、明示的に作成・確認・実行する手順になる。drift 検出も自動ではなく明示的に実行する必要があり、非対応リソース型もある
- **削除時の既定値に注意が必要。** `DeletionPolicy` を書かないときの既定は `Delete` だが、**RDS は例外で `Snapshot`**。放置するとスナップショット課金が残る。「終わったら全部消す」運用では明示指定が必須
- **Terraform の modules に直接相当する仕組みがない。** 環境差分は `Parameters` / `Mappings` / `Conditions` で吸収するのが基本で、共通化にはネストスタックなどの選択肢がある(→ 具体的な構成は別途設計)
