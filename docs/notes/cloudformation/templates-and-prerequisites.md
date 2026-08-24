# テンプレートの分割と置き場、そして事前リソース

「Terraform みたいにサービスごとにファイルを分けたい」から出発して、素の CloudFormation で分ける手段を全部並べ、そこから S3 バケットの話と `cdk bootstrap` の話に降りるノート。末尾の §7 は「SAM とは何か」の補足。

このノートは記述の確からしさを 3 段階で書き分ける(**仕様** / **傾向** / **未検証**)。

要点は 3 つ。

1. **分ける手段は 4 つあり、うち 1 つ(スタック自体を分割)だけ仕組みとして S3 を要求しない。** 「分割には S3 が必要」も「分けたいなら CDK しかない」も、どちらも正確ではない
2. **S3 が必要になる理由は 2 つあり、混ぜてはいけない。** ①分割の仕組みが要求する(`AWS::Include` / ネストスタックは**サイズが小さくても**必須)、②テンプレートが 51,200 バイトを超える(**どの方式にも掛かる**。スタック分割も例外ではない)。このリポジトリは分割していないが、②の理由でバケットを持っている
3. **そのバケットは tfstate の S3 とは別物。** 中身は「Git から再生できるビルド成果物」で、状態ではない。状態は CloudFormation が AWS 側で持っている

関連: [Terraform 経験者のための CloudFormation](./terraform-to-cloudformation.md) / [CloudFormation の CLI コマンドを読み解く](./cli-commands-and-change-sets.md) / [ADR-0008](../../adr/0008-template-bucket-as-resident-resource.md)

---

## 1. なぜ 1 ファイルになるのか

**CloudFormation には `module` に相当する仕組みがない。** さらに、同じディレクトリの YAML を自動で連結する機能もない(Terraform は `*.tf` を全部読んで 1 つの設定として扱うが、CloudFormation は指定した 1 ファイルだけを読む)。

だから素直に書くと、全リソースが 1 つの YAML に集まる。このリポジトリの `cloudformation/app.yml` は VPC・サブネット・NAT GW・SG・ALB・WAF・ACM・ECS・RDS・S3・CloudFront・Route53・IAM ロールを 1 本に入れている。

Terraform 側(`terraform/modules/app-infrastructure/`)は同じ範囲を 28 ファイルに分けている。`alb.tf` / `rds.tf` / `ecs_web.tf` / `waf.tf` / `iam.tf` のようにサービス単位。**この分け方が素の CloudFormation ではできない**、というのがこのノートの出発点。

**傾向:** リソース数の上限(1 テンプレート 500)には遠く届かないので、分割の動機は「上限」ではなく「読みやすさ」と「ライフサイクルの違い」になる。

---

## 2. 分ける手段は 4 つある

### 2-1. `AWS::Include` transform

**仕様:** [`AWS::Include` transform](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/transform-aws-include.html) より。

> The `AWS::Include` is a CloudFormation macro that, when referenced in your stack template, inserts the contents of the specified file at the location of the transform in the template when you create or update a stack using a change set. The `AWS::Include` function behaves similarly to an `include`, `copy`, or `import` directive in programming languages.

**プログラミング言語の `include` に近い、素の CloudFormation で唯一の「単純なファイル連結」。** 使い方はこう。

```yaml
Resources:
  Fn::Transform:
    Name: AWS::Include
    Parameters:
      Location: s3://my-bucket/network.yml
```

**代償が多い。以下はすべて公式ドキュメントに根拠がある**(引用のあるものは上と同じ `AWS::Include` ページの Considerations、`Parameters` の制約は同ページの Usage、`CAPABILITY_AUTO_EXPAND` はマクロ側のページ)。

- **`Location` は `s3://` のみ。** ローカルパスも GitHub の URL も渡せない(「It must be an Amazon S3 bucket, as opposed to something like a GitHub repository」)。つまり**S3 が必須**。ただし**同一リージョン制約は無い**(クロスリージョンレプリケーションの URI も使えると明記されている)
- **YAML のショートハンドが使えない**(「We don't currently support using shorthand notations for YAML snippets」)。スニペット内では `!Ref` / `!Sub` ではなく `Fn::Ref` / `Fn::Sub` の長形式になる。**切り出した側だけ書き方が変わる**ので、実用上いちばん効くのはこれ
- **`AWS::Include` の入れ子ができない**(「You can't use `AWS::Include` to reference a template snippet that also uses `AWS::Include`」)。親 1 段 + 子で終わりで、階層は作れない
- **スニペットは有効な key-value オブジェクトでなければならない**(`"KeyName": "keyValue"`)。リソース定義の断片は書けるが、任意の行を切り貼りする用途には使えない
- **マクロなので Change Set 経由でしか展開されない。** `validate-template` では中身が見えず、`CAPABILITY_AUTO_EXPAND` の指定も要る
- **`Parameters` セクションとテンプレートバージョンには使えない**(公式に明記されている)
- **スニペットを差し替えてもスタックは自動追従しない。** 反映にはスタック更新が要る。逆に「知らないうちにスニペットが変わっていた」も起きうるので、公式も Change Set で確認せよと書いている

**傾向:** 「同じスニペットを複数のテンプレートで使い回す」用途で見かける。「1 つのスタックを読みやすく分割する」用途にはあまり使われない(展開後しか検証できないため)。

### 2-2. ネストスタック

親テンプレートに `AWS::CloudFormation::Stack` を書き、子テンプレートを指す。

```yaml
Resources:
  Network:
    Type: AWS::CloudFormation::Stack
    Properties:
      TemplateURL: https://s3.amazonaws.com/my-bucket/network.yml
      Parameters:
        VpcCidr: !Ref VpcCidr
```

**仕様:** 子テンプレートの指定は `TemplateURL`(S3)しかない。`AWS::CloudFormation::Stack` には `TemplateBody` プロパティも定義されているが、[リファレンス](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-cloudformation-stack.html)にこう書かれている。

> These properties can be configured only when using AWS Cloud Control API. This is because the below properties are set by the parent stack, and thus cannot be configured using CloudFormation or AWS CDK but only AWS Cloud Control API.
>
> ... `TemplateBody`

つまり**テンプレートに書ける子スタックの指定は `TemplateURL` だけ**で、**S3 が必須**。

**手間を減らす道具がある。** `aws cloudformation package` はローカルパスで書いた `TemplateURL` を見つけて S3 にアップロードし、URL に書き換えた新しいテンプレートを出力する。ローカルで `TemplateURL: ./network.yml` と書けるようになるので、ネストスタックを現実的にするのはこのコマンド。

**代償:**

- 親子間の値の受け渡し(子の `Outputs` → 親 → 別の子の `Parameters`)を全部手書きする
- Change Set が親子で入れ子になり読みにくい
- デプロイ手順に `package` が挟まる

**傾向:** AWS 公式が示す分割基準は「ライフサイクルと所有者で分ける」。**このリポジトリはライフサイクルが 1 つしかない**(全部まとめて建てて全部まとめて消す)ので、この基準では分ける理由が弱い。設計書 決定1 がネストスタックを採らなかったのはこの理由。

### 2-3. スタック自体を分割する + `Export` / `ImportValue`

ファイルもスタックも分ける。基盤スタックが値をエクスポートし、サービススタックがインポートする。

```yaml
# 基盤スタック側
Outputs:
  DbEndpoint:
    Value: !GetAtt Database.Endpoint.Address
    Export:
      Name: !Sub ${ProjectName}-${EnvName}-db-endpoint

# サービススタック側
Environment:
  - Name: DB_HOST
    Value: !ImportValue mylabinfra-stg-db-endpoint
```

**仕組みとして S3 を要求しないのはこの方法だけ。** ただし**サイズ上限は別に掛かる**。それぞれのテンプレートが 51,200 バイト以下なら `--template-file` で直接投げられるが、分割後の 1 本が超えればその 1 本は S3 が必要になる(→ §4)。分割の効果は「S3 が不要になる」ではなく「**各テンプレートが小さくなるので、サイズ由来の S3 を回避しやすくなる**」。

**代償(そしてこれが決定的):**

- **仕様: エクスポート元のスタックは、参照している側が消えるまで削除できない。** 「全部まとめて消す」運用と正面衝突する。撤収のたびに削除順序を守る手順が要る
- **エクスポートした値は変更できない**(参照されている間は)。DB を作り直すような変更が難しくなる
- 環境変数のように「1 つの文字列」しか渡せない。構造化した値は渡せない

**傾向:** 頻繁にデプロイする本番構成ではこれが定石(アプリだけ 2 分で更新できる)。作り捨ての検証環境では割に合わない。

### 2-4. CDK でコードから生成する

CDK なら TypeScript のファイルをいくらでも分けられる。`cdk synth` が CloudFormation テンプレートを生成する。

```
lib/
├── network-stack.ts
├── database-stack.ts
└── service-stack.ts     ← Terraform のファイル分割に近い感覚
```

**「Terraform のように分けたいなら CDK しかない」は誤り**(2-1 から 2-3 がある)。ただし**「Terraform と同じ感覚で分けたいなら CDK が一番近い」は正しい。** CDK なら:

- ファイル分割はプログラミング言語の機能なので自由
- 値の受け渡しは変数の受け渡しになる(手書きの `Outputs` / `Parameters` が消える)
- 複数スタックにするか 1 スタックにするかはコード側で決められる
- 子テンプレートやアセットは `cdk bootstrap` が作ったバケットに自動で入る(→ §6)

**SAM はここに並ばない。** SAM は CDK と並べて語られることが多いが、書くものは YAML で「コードから生成する」ものではなく、ファイル分割の手段も 2-2 のネストスタックそのもの(→ §7)。

**このリポジトリは CDK を採らない。** 理由は 3 点あり [ADR-0001](../../adr/0001-cloudformation-yaml-over-terraform.md) に書いてある(要するに「CloudFormation を学ぶのが目的なので抽象化で隠すのは順序が逆」)。将来の学習題材として、同じ構成を CDK で書き直して `cdk synth` の出力を比較する案は残している。

### まとめ

**S3 が必要になる理由は独立に 2 つあるので、列を分けて書く。**

| 手段 | ファイルを分けられるか | 仕組み上 S3 が必要か | サイズ由来で S3 が必要か | 主な代償 |
|---|---|---|---|---|
| 1 ファイルのまま | ✕ | 不要 | 51,200 バイト超なら必要 | 長くなる |
| `AWS::Include` | ○ | **必須**(子ファイルの置き場) | 別途、親テンプレートに掛かる | マクロなので検証しにくい、YAML ショートハンド不可、入れ子不可 |
| ネストスタック | ○ | **必須**(子ファイルの置き場) | 別途、親テンプレートに掛かる | 値の受け渡しを手書き、Change Set が入れ子 |
| スタック分割 + `ImportValue` | ○ | **不要** | 別途、各テンプレートに独立に掛かる | **削除順序に縛られる**、値を変更できない |
| CDK | ○ | 必須(bootstrap が作る) | 別途、生成後のテンプレートに掛かる | CloudFormation を隠す。Node.js 依存が増える |

**「サイズ由来」の列は全方式に共通で掛かる**(→ §4)。だから「スタック分割なら S3 が要らない」は無条件には成り立たない。逆に、`AWS::Include` とネストスタックは**サイズが小さくても S3 が要る**。

**SAM をこの表に入れていないのは、SAM が「分ける手段」ではないから。** 実体は CloudFormation の拡張構文で、ファイル分割は `AWS::Include` かネストスタックという上と同じ枠に収まる(→ §7)。

---

## 3. どの道も S3 に行き着く(1 つを除いて)

上の表を見ると、**「読みやすく分割する」現実的な手段はどれも S3 を必要とする。** 仕組み上 S3 を要求しないのはスタック分割だけで、それは削除順序という別のコストを払う(そしてサイズ上限からは逃げられない)。

**これは偶然ではない。** CloudFormation がテンプレートを読める場所は限られている。

**仕様:** リクエストに直接載せる(`TemplateBody`)か、**S3 か Systems Manager ドキュメントに置いて URL で渡す**(`TemplateURL`)かの 2 つだけ。GitHub の raw URL は渡せない(`CreateChangeSet` の API リファレンスに「S3 バケットまたは Systems Manager ドキュメント」「S3 の静的ウェブサイト URL は非対応」と明記されている)。

だから「テンプレートが 1 つのリクエストに収まらない」状況になった瞬間、行き先は S3 になる。分割してもしなくても同じ。

### バケットは 1 つで足りる

**「分割すると複数の S3 が必要になる」わけではない。** `AWS::Include` の `Location` もネストスタックの `TemplateURL` も指すのは**オブジェクト(キー)**なので、**1 つのバケットにキーを並べれば済む**。`aws cloudformation package` が受け取る `--s3-bucket` も 1 つだけで、見つけたローカル参照を全部そのバケットにアップロードして URL に書き換える。このリポジトリの置き場バケット(§6)をそのまま使える。

**そして S3 が必須なのは「子」だけ。** 親テンプレートが 51,200 バイト以下なら、親は `--template-body` で直接投げつつ子だけ S3 にある、という状態が成立する。

**参照される回数も違う。**

| | S3 の役割 | 読まれるタイミング |
|---|---|---|
| 1 ファイル + サイズ回避 | そのリクエスト 1 回のための運び屋 | スタック操作の直前に置いて、それきり |
| `AWS::Include` / ネストスタック | 構成の一部の保管場所 | create / update のたびに読まれる(ネストスタックは「子だけ差し替えて親を更新」が成り立つ) |

**この違いが「置きっぱなしにする必要があるか」を分ける。** 前者はライフサイクルルールで消えても困らない(→ §6)が、後者で現行スタックが参照しているキーを消すと、次の更新が失敗する。

---

## 4. サイズ上限 — 分割とは別の理由でも S3 になる

**仕様:** [CloudFormation のクォータ](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/cloudformation-limits.html)より。

| 項目 | 上限 |
|---|---|
| Template body size in a request(`CreateStack` / `UpdateStack` / **`ValidateTemplate`**) | **51,200 バイト** |
| Template body size in an Amazon S3 object(`TemplateURL` 経由) | **1 MB** |
| 1 テンプレートのリソース数 | 500 |

公式が挙げている回避策も「ネストスタックに分ける」か「S3 にアップロードする」の 2 つ。

**上限はテンプレート 1 本ごとに独立に掛かる。** スタックを 4 本に分けても、そのうち 1 本が 51,200 バイトを超えればその 1 本だけ S3 経由になる。ネストスタックの子は最初から S3 にあるので、掛かるのは 1 MB 側(`TemplateURL` は「max size: 1 MB」と明記されている)。

**このリポジトリの `app.yml` は 51,200 バイトを超えている**(現在のバイト数と行数は `cloudformation/README.md` を参照。書き足すたびに変わるのでここには書かない)。だから**分割していないのに S3 バケットが必要になった。**

派生する事実が 3 つ。

- **`aws cloudformation validate-template --template-body file://...` が使えない。** 51,200 バイトの制限は `ValidateTemplate` にも等しく掛かる。ローカルで構文を見るなら `cfn-lint`(API を呼ばないので資格情報も不要)
- **`aws cloudformation deploy` は `--s3-bucket` が無いと AWS を呼ぶ前に `DeployBucketRequiredError` で落ちる**(設計書 §8-12)。`deploy` の全オプションと内部でやっていること → [CLI コマンドのノート §10](./cli-commands-and-change-sets.md)
- **日本語コメントは 1 文字 3 バイト。** 行数より先にバイト数が上限に当たる

**必要な IAM 権限は `s3:PutObject` だけではない。** テンプレート URL は呼び出し側の権限で読まれる。コンソールの手順に「stored in an S3 bucket **that you have read permissions to**」と明記されており、`deploy` はアップロード前に既存オブジェクトとハッシュを比較するのでそこでも読み取りが要る。**`s3:PutObject` + `s3:GetObject` の両方**が必要。

---

## 5. これは tfstate の S3 とは別物

**「テンプレートを S3 で管理するのは、tfstate を S3 で管理するのと同じようなものか」→ 違う。** 置かれているものの性質がまったく違う。

```
Terraform
─────────────────────────────────────────────────────
  *.tf ─────(ローカル / CI から直接 API を叩く)─────> AWS
                                                        │
  tfstate ────> S3            状態。失うと管理不能になる。
              + ロック        自分で守る対象


CloudFormation
─────────────────────────────────────────────────────
  app.yml ───> S3 ───(TemplateURL)───> CloudFormation ───> AWS
              │                              │
              成果物。失っても Git から      状態はスタックの中
              再生できる。守る対象ではない    (AWS 側が保持。ファイルは無い)
```

| | tfstate の S3 | テンプレート置き場の S3 |
|---|---|---|
| 置いてあるもの | Terraform が管理するリソースの一覧(JSON) | 自分が書いた YAML そのもの |
| Git にあるか | **無い**(生成物であり、秘密も含む) | **ある**(Git が正本) |
| 失ったら | **管理不能。** 実物と設定の対応が失われる | 何も起きない。もう一度アップロードするだけ |
| 誰が書くか | Terraform | CI(`aws s3 cp` か `deploy --s3-bucket`) |
| 役割の例え | データベース | ビルド成果物置き場(jar や Docker イメージと同じ) |
| ロックが必要か | 必要(同時実行で壊れる) | 不要 |

**CloudFormation 側で tfstate に相当するのは「スタック」で、それは AWS の中にあってファイルとして触れない。** だから S3 に置く必要が最初から無い。逆に言えば `terraform state rm` のような操作もできない(→ [対訳ノート §1](./terraform-to-cloudformation.md))。

**では Terraform 側でテンプレート置き場に相当するものは?** 無い。Terraform は設定ファイルをローカル / CI から直接 API に送るので、中間の置き場を必要としない。**この非対称は「状態を誰が持つか」の裏返し**で、Terraform は状態を外に置く代わりに設定は手元から送り、CloudFormation は状態を預ける代わりに設定を預ける場所が要る。

---

## 6. 事前リソース — `cdk bootstrap` 相当は何か

### CDK の場合

**仕様:** [AWS CDK bootstrapping](https://docs.aws.amazon.com/cdk/v2/guide/bootstrapping.html) より、`cdk bootstrap` が作るもの。

> + **Amazon Simple Storage Service (Amazon S3) bucket** – Used to store your CDK project files, such as AWS Lambda function code and assets.
> + **Amazon Elastic Container Registry (Amazon ECR) repository** – Used primarily to store Docker images.
> + **AWS Identity and Access Management (IAM) roles** – Configured to grant permissions needed by the AWS CDK to perform deployments.

そして重要な点。

> Resources and their configuration that are used by the CDK are defined in an AWS CloudFormation template. This template is created and managed by the CDK team. ... To bootstrap an environment, you use the `cdk bootstrap` command. The CDK CLI retrieves the template and deploys it to AWS CloudFormation as a stack, known as the *bootstrap stack*.

ここから、よく聞く話を 3 つに分けて確認できる。

| よく聞く話 | 実際 |
|---|---|
| 「テンプレート管理外のリソースが作られる」 | **不正確。** `CDKToolkit` という**普通の CloudFormation スタック**として作られる。管理外なのは「あなたのアプリのスタックの外」という意味だけ |
| 「削除には手動操作が必要」 | **正しい。** `cdk destroy` はアプリのスタックを消すだけで `CDKToolkit` は残る。消すなら `CDKToolkit` スタックを自分で削除する(バケットを空にする必要もある) |
| 「あなたのテンプレートを見て必要なものを作るのではなく、設定に基づいて作る」 | **正しい。** CDK チームが管理する固定のブートストラップテンプレートと、CLI のオプション(`--cloudformation-execution-policies` / `--trust` / `--qualifier` など)から決まる。アプリのコードは見ていない |

既定のバケット名は `cdk-hnb659fds-assets-<ACCOUNT>-<REGION>`。アカウント × リージョンごとに 1 回必要。

### SAM の場合

(SAM そのものが何かは → §7)

**仕様:** `sam deploy` / `sam package` の `--resolve-s3` は「Automatically create an Amazon S3 bucket to use for packaging」。`--s3-bucket` との併用はエラーになる。

**未検証:** 自動作成されるバケットの名前は公式ドキュメントに規定がない(「バケットを自動作成する」とだけ書かれている)。`aws-sam-cli-managed-default` という名前を目にすることがあるが、それは CloudFormation スタック側の名前で、バケット名にはサフィックスが付く。

### 素の CloudFormation の場合 — bootstrap コマンドは無い

**`cdk bootstrap` に相当するコマンドが無い。** 必要な事前リソースは自分で作る。このリポジトリでそれに当たるのは 3 つで、[運用手順](../../infrastructure/cloudformation-operations.md) の §2 と §3 が作成手順になっている。

| 事前リソース | 役割 | 手順 |
|---|---|---|
| テンプレート置き場の S3 バケット | 51,200 バイト超のテンプレートを渡すため | §3 |
| CloudFormation サービスロール | CloudFormation がリソースを作るときに引き受けるロール | §2-1 |
| GitHub Actions が引き受けるロール | OIDC で AssumeRole する側 | §2-2 |

`aws cloudformation deploy` に `--resolve-s3` はない(SAM だけの機能)。だからバケットは手で作ることになる(`deploy` のオプション一覧 → [CLI コマンドのノート §10-1](./cli-commands-and-change-sets.md))。

**これは Terraform でも同じ形の問題がある。** state 用の S3 バケットとロックのテーブルは、Terraform で管理しようとすると「Terraform を動かすために Terraform が要る」鶏卵問題になるので、手で作るか別の設定で作る。**「IaC を動かすための事前リソースは IaC の外で作る」という構図は両方に共通する。**

| | 事前に必要なもの | 誰が作るか |
|---|---|---|
| Terraform | state バケット + ロック | 手動、または bootstrap 用の別設定 |
| CDK | S3 + ECR + IAM ロール群(`CDKToolkit`) | `cdk bootstrap`(専用コマンドがある) |
| SAM | S3 バケット | `--resolve-s3` が自動で作る |
| 素の CloudFormation | テンプレート置き場 + IAM ロール | **手動**(コマンドが無い) |

**ここは素の CloudFormation が一番手間が掛かる。** 引き換えに、常駐するものが少なく中身が全部見える(ADR-0001 が `cdk bootstrap` を避けた理由もこれ)。

### このリポジトリでの扱い

テンプレート置き場のバケットは**手動管理の常駐リソース**として、ECR と Route53 ホストゾーンと同じ扱いになる。撤収(`delete-stack`)では消えない。

- コストは数十 KB の YAML なので実質ゼロ
- ライフサイクルルールで古いテンプレートを 30 日で削除する
- 公開しない(テンプレートにはリソース構成が全部書かれている)
- スタックと同じリージョンに置く(コンソールの手順に「Some resources may require that the bucket be in the same Region as the stack」とある)

決定の理由と却下案 → [ADR-0008](../../adr/0008-template-bucket-as-resident-resource.md)。

---

## 7. 補足 — SAM は「3 つめの IaC」ではない

SAM(AWS Serverless Application Model)は CDK と並べて語られることが多く、CloudFormation / CDK に続く 3 つめの選択肢のように見える。**実際は CloudFormation の拡張構文と専用 CLI で、リソースを作るのは CloudFormation 自身。**

**仕様:** [What is the AWS Serverless Application Model (AWS SAM)?](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/what-is-sam.html) より、SAM は 2 つの部品からなる。

> 1. **AWS SAM CLI** - A command-line tool that helps you develop, locally test, and deploy your serverless applications.
> 2. **AWS SAM Template** - An **extension of CloudFormation** that provides simplified syntax for defining serverless resources.

### 7-1. SAM テンプレートは `Transform` 付きの CloudFormation テンプレート

書くのはこういう YAML。

```yaml
Transform: AWS::Serverless-2016-10-31   # これが SAM の正体
Resources:
  MyFunction:
    Type: AWS::Serverless::Function     # SAM 独自のリソース型
    Properties:
      Handler: index.handler
      Runtime: nodejs20.x
      CodeUri: s3://amzn-s3-demo-bucket/MySourceCode.zip
```

**仕様:** [`AWS::Serverless` transform](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/transform-aws-serverless.html) より。

> When creating a change set from the template, CloudFormation expands the AWS SAM syntax, as defined by the transform. The processed template expands the `AWS::Serverless::Function` resource, declaring a Lambda function and an execution role.

`AWS::Serverless::Function` 1 個が `AWS::Lambda::Function` + `AWS::IAM::Role` に展開される(`Events` に API を書けば API Gateway と `AWS::Lambda::Permission` も付く)。**§2-1 の `AWS::Include` と同じマクロの仕組み**で、SAM は AWS 自身が提供する公式マクロにあたる。

派生する事実が 2 つ。

- **`Transform` はテンプレートの最上位にしか書けない**(公式に「You can't use `AWS::Serverless` as a transform embedded in any other template section」と明記)。値も `AWS::Serverless-2016-10-31` というリテラル固定で、パラメータや組み込み関数で指定できない
- **`AWS::LanguageExtensions` と併用するときは `AWS::Serverless` より前に書く**([SAM template anatomy](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/sam-specification-template-anatomy.html) に「you should add `AWS::LanguageExtensions` *before* the serverless transform」とある)

### 7-2. テンプレートがどこで生成されるか

3 つとも最終的に CloudFormation が実物を作る。違うのは**テンプレートになるタイミングと場所**。

```
素の CloudFormation
  app.yml ──────────────────────────────────────> CloudFormation ──> AWS

SAM
  template.yaml ────────────────────────────────> CloudFormation ──> AWS
  (Transform 付き YAML)                            └─ ここで展開(サーバー側)

CDK
  *.ts ──[cdk synth]──> cdk.out/*.template.json ─> CloudFormation ──> AWS
                        └─ ここで生成(手元/CI 側)
```

| | 素の CFn | SAM | CDK |
|---|---|---|---|
| 書くもの | YAML | YAML(略記あり) | TypeScript 等のコード |
| 宣言的 / 手続き的 | 宣言的 | 宣言的 | 手続き的 |
| テンプレート生成 | しない | AWS 側が Transform で展開 | 手元で `cdk synth` |
| 得意分野 | 全サービス | サーバーレス中心(Lambda / API GW / DynamoDB / Step Functions) | 全サービス |
| ファイル分割 | 苦手 | 素の CFn と同じ(苦手) | 自由 |

**「展開後しか最終形が見えない」という §2-1 の代償が SAM にもそのまま乗る**、というのがこの表の実務上の意味。逆に CDK は手元に `cdk.out/*.template.json` が出るので、投げる前に最終形を読める。

### 7-3. ファイル分割の手段はネストスタックそのもの

**仕様:** [`AWS::Serverless::Application`](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/sam-resource-application.html) より。

> Embeds a serverless application from the AWS Serverless Application Repository or from an Amazon S3 bucket as a nested application. **Nested applications are deployed as nested `AWS::CloudFormation::Stack` resources** ...
>
> `Location` ... If a local file path is provided, the template must go through the workflow that includes the `sam deploy` or `sam package` command, in order for the application to be transformed properly.

つまり SAM の分割は **§2-2 のネストスタックと同じ仕組みで、S3 も同じく必須。** 違うのは、§2-2 で「手間を減らす道具」として挙げた `aws cloudformation package` 相当を SAM CLI が自動でやる点(公式に [How AWS SAM uploads local files at deployment](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/deploy-upload-local-files.html) として「ローカルファイルを自動アップロードし、テンプレートの参照先を自動で書き換える」と書かれている)。

### 7-4. 公式が示す使い分け

**仕様:** §7 冒頭と同じ公式ページより。

> - Use SAM instead of CloudFormation to simplify serverless resource definitions while maintaining template compatibility.
> - **Use SAM instead of AWS CDK if you prefer a declarative approach** to describing your infrastructure rather than a programmatic one.
> - **Combine SAM with AWS CDK** by using SAM CLI's local testing features to enhance your CDK applications.

3 つめが示すように、**SAM CLI はテンプレートの書き方と切り離して使える。** 同じページに Terraform サポートも明記されている。

> **Manage your Terraform serverless applications** — Use the AWS SAM CLI to perform local debugging and testing of your Lambda functions and layers.

**SAM の価値は「YAML の略記」と「CLI」の 2 本立てで、後者だけ借りることができる**、というのがここの読みどころ。

### 7-5. このリポジトリでの扱い

**SAM は使わない。Lambda が 1 つもない**(ECS Fargate + RDS + ALB)。SAM 独自のリソース型はすべてサーバーレス向けなので、`app.yml` に置き換える対象が存在しない。§6 に SAM が出てくるのは、`--resolve-s3` が「IaC を動かすための事前リソースを誰が作るか」の対比になるからだけ。
