# テンプレート置き場の S3 バケットを手動管理の常駐リソースとする

日付: 2026-08-22
ステータス: accepted

## 決定

CloudFormation テンプレートを渡すための **S3 バケットを、手動管理の常駐リソースとして 1 つ持つ。** ECR と Route53 ホストゾーンと同じ扱いで、撤収(`delete-stack`)では消えない。

- バケット名はアカウント ID を含む規則で組み立てる(ワークフローも同じ規則で組み立てるので、設定として渡さない)
- **公開しない。** テンプレートにはリソース構成が全部書かれている
- **ライフサイクルルールで 30 日で削除する。** 反映のたびに数十 KB 増えるだけなので溜め続ける必要がない
- スタックと同じリージョンに置く
- GitHub Actions が引き受けるロールに **`s3:PutObject` と `s3:GetObject` の両方**を与える(テンプレート URL は呼び出し側の権限で読まれるため)
- 作成手順 → [docs/infrastructure/cloudformation-operations.md](../infrastructure/cloudformation-operations.md) §3

## 背景と理由

「使いたいときだけ建てて、終わったら全部消す」のがこのリポジトリの方針(→ [ADR-0001](./0001-cloudformation-yaml-over-terraform.md))。そこに**消えないリソースを増やすのは方針に逆行する**ので、記録が必要な決定になる。

きっかけは `cloudformation/app.yml` が **51,200 バイトを超えたこと。** これは「リクエストに直接載せられるテンプレートの上限」で、`CreateStack` / `UpdateStack` と **`ValidateTemplate`** に等しく掛かる。日本語コメントは 1 文字 3 バイトなので、行数より先にバイト数が上限に当たった。

**CloudFormation がテンプレートを読める場所は S3 か Systems Manager ドキュメントの 2 つだけ。** GitHub の raw URL は渡せない(`CreateChangeSet` の API リファレンスに「S3 バケットまたは Systems Manager ドキュメント」「S3 の静的ウェブサイト URL は非対応」と明記されている)。したがって上限を超えた時点で、置き場を持つこと自体は避けられない。

**AWS 公式のツールチェーンはすべて置き場を持っている**ことも判断材料になった。`cdk bootstrap` が作る `CDKToolkit` スタックには S3 バケットが含まれ、`sam deploy --resolve-s3` はバケットを自動作成し、`aws cloudformation package` / `deploy --s3-bucket` も同じ前提。**「1 スタックに収まる小さなテンプレートを直接投げる」のが小規模時の形で、育ったら置き場を持つのが既定路線**である。

## 検討したが採らなかった選択肢

- **テンプレートを削って 51,200 バイトに収める** — 削る対象は日本語コメントか構成そのもの。**このリポジトリは学習用でコメントに価値がある**うえ、「書き足すたびに上限を気にして削る」運用は続かない。上限に張り付いた状態を維持するコストの方が高い

- **ネストスタックに分割する** — 分割しても**子テンプレートの指定は `TemplateURL`(S3)しかない**(`AWS::CloudFormation::Stack` の `TemplateBody` は Cloud Control API 専用と公式に明記)。つまり**S3 は結局必要になる。** 加えて親子間の値の受け渡しを全部手書きすることになり、Change Set が入れ子で読みにくくなる。**設計書 決定1 がネストスタックを却下した理由の 1 つ(「子テンプレート置き場の S3 バケットが増える」)は、この決定によって無効になった。** 残る理由は有効なので、分割しない判断そのものは変えていない

- **`AWS::Include` transform で分割する** — 素の CloudFormation でファイルを連結する唯一の手段だが、**`Location` は `s3://` のみ**なので S3 は必要。さらにマクロなので Change Set 経由でしか展開されず、`validate-template` で中身が見えない。`CAPABILITY_AUTO_EXPAND` も要る。分割の利点に対して検証しにくさの代償が大きい

- **スタック自体を分割する** — **これだけは S3 が要らない**(各テンプレートが 51,200 バイト以下なら直接投げられる)。採らなかったのは、基盤側の値を `Export` / `ImportValue` で渡すことになり、**エクスポート元のスタックは参照している側が消えるまで削除できない**ため。「全部まとめて建てて全部まとめて消す」撤収運用と正面衝突する(→ [ADR-0007](./0007-app-deploy-inside-cloudformation.md))

- **Systems Manager ドキュメントに置く** — CloudFormation が読める、もう一方の場所。**傾向として**テンプレート置き場に使う例をほとんど見ず、バージョニングやライフサイクルの扱いも S3 の方が素直なので採らなかった

- **CDK に移行して bootstrap に任せる** — 置き場の管理から解放されるが、[ADR-0001](./0001-cloudformation-yaml-over-terraform.md) の再検討になる。`cdk bootstrap` は S3 だけでなく ECR と IAM ロール群も常駐させるので、**常駐リソースはむしろ増える**

## 結果として生じること

- **手動管理の常駐リソースが 1 つ増える。** ECR・Route53 ホストゾーンと並ぶ 3 つ目。新しいアカウントで環境を建てるときの手動セットアップ手順が 1 つ増える
- **撤収手順は変わらない。** バケットはスタックの外にあるので `delete-stack` に影響しない
- **コストは実質ゼロ。** 数十 KB の YAML と、30 日で消えるその履歴だけ
- **`aws cloudformation validate-template --template-body file://...` が使えない。** 51,200 バイトの制限が `ValidateTemplate` にも掛かるため。ローカルでの構文チェックは `cfn-lint`(API を呼ばないので資格情報も不要)
- **`create-change-set` を直接使うときの手当てが要る。** `--template-url` に切り替わるのに伴い、`deploy` が面倒を見てくれていた `--tags` の引き継ぎと、`--parameter-overrides` に無いパラメータの `UsePreviousValue` を自分で組む必要がある(→ 設計書 §8-13, §8-14)
- **`app.yml` のバイト数を意識する習慣が要る。** 上限は 1 MB に上がったので当面余裕はあるが、`wc -c` を見る癖は残す
- 詳しい解説 → [docs/notes/cloudformation/templates-and-prerequisites.md](../notes/cloudformation/templates-and-prerequisites.md)
