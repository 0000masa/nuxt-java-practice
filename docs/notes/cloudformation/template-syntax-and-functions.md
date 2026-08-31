# CloudFormation テンプレートの書き方 — YAML の文法と組み込み関数

`cloudformation/app.yml` のような素の CloudFormation テンプレートを、**自分でゼロから書けるようになる**ための文法ノート。セクション → パラメータ → リソース → 関数 → 属性 → 出力、の順に並べてあるので、辞書として引ける。

このディレクトリの他のノートとは役割を分けている。**あちらは「なぜそうするか」(運用・設計・Terraform との対比)、こちらは「どう書くか」(記法そのもの)。** 同じ話題が出てきたときは、こちらで書き方を示したうえで、判断の理由は向こうへリンクで送る。

コード例は全部このリポジトリの `cloudformation/app.yml`(2154 行)から行番号つきで引く。**このリポジトリで使っていない構文も扱うが、そのときは必ず「未使用」と断る。** 他所のテンプレートを読むときに知らない記法で止まらないようにするため。

このノートは記述の確からしさを 3 段階で書き分ける。

- **仕様** — 公式ドキュメントに書かれていること。リンクと引用を付ける
- **傾向** — 実務でよく見る形。根拠が弱いので断定しない
- **未検証** — まだ実物で確かめていない

要点は 3 つ。

1. **CloudFormation の YAML は「YAML の文法」と「CloudFormation の文法」の 2 層でできている。** クォートや複数行文字列の罠は YAML 由来で、CloudFormation は何も悪くない。切り分けられると詰まったときに調べ先が決まる
2. **`!Ref` / `!Sub` / `!GetAtt` の 3 つで 9 割書ける。** `app.yml` の関数使用 344 回のうち 321 回がこの 3 つ(コメント行を除いた実測)。残りは必要になったときに引けばいい
3. **リソースの中身は `Properties` の内と外で意味が違う。** 外側(`DependsOn` / `DeletionPolicy` など)は**属性**で、組み込み関数が書けない。ここを混ぜると「なぜか `!If` が効かない」で止まる

関連ノート: [Terraform 経験者のための CloudFormation](./terraform-to-cloudformation.md) / [RDS / ECS の環境差分と IaC 2 ツールでの表現力](./environment-differences.md) / [CLI コマンドを読み解く](./cli-commands-and-change-sets.md) / [コマンドと IAM 権限](./iam-roles-and-command-permissions.md) / [ECS のタスク定義は誰が持つか](./ecs-deploy-ownership.md) / [テンプレートの分割と置き場](./templates-and-prerequisites.md)

---

## 1. YAML そのものの前提

CloudFormation テンプレートは JSON でも YAML でも書ける。**このリポジトリは YAML。** コメントが書けること(JSON には無い)と、`!Ref` などの短縮形が使えることが理由。

ここで詰まるものの多くは CloudFormation ではなく **YAML の仕様**なので、先に片付けておく。

### 1-1. インデントとリストの 2 記法

インデントは**スペース 2 つ**。タブは YAML の仕様で禁止されている。

`app.yml` の階層はこうなっている。

```yaml
# app.yml:342-350
Resources:                        # 0
  Vpc:                            # 2  論理 ID
    Type: AWS::EC2::VPC           # 4  型
    Properties:                   # 4
      CidrBlock: !Ref VpcCidr     # 6  プロパティ
      Tags:                       # 6
        - Key: Name               # 8  リストの要素
          Value: !Sub ${ProjectName}-${EnvName}-vpc
```

リストには**ブロック形式**と**フロー形式**の 2 つがある。同じ意味で、見た目だけが違う。

```yaml
# ブロック形式 — 1 要素 1 行
AllowedValues:
  - stg
  - prod

# フロー形式 — 1 行に収める
AllowedValues: [stg, prod]
```

**傾向: 短い列挙はフロー、要素が構造を持つならブロック。** `app.yml` はこの使い分けで統一している。

```yaml
# app.yml:35    フロー(値が短い)
    AllowedValues: [stg, prod]
# app.yml:1208  フロー
      RequiresCompatibilities: [FARGATE]
# app.yml:372   フロー(関数の引数もリストなので同じ)
      AvailabilityZone: !Select [0, !GetAZs ""]
```

`Tags` のように要素が `Key` / `Value` を持つものはブロックにする。フローでも書けるが読めなくなる。

### 1-2. クォートが要る値、要らない値

**YAML は裸の文字列を勝手に型解釈する。** ここが一番よく踏む。

| 書いた値 | YAML の解釈 | CloudFormation が欲しい型 |
|---|---|---|
| `8.4` | 数値 8.4 | 文字列 `"8.4"` |
| `true` | 真偽値 | 型による |
| `512` | 数値 | 文字列(ECS の `Cpu` は文字列) |
| `2010-09-09` | 日付 | 文字列 |
| `17:00-17:30` | 文字列(コロンの後にスペースが無いのでマップにならない) | 文字列 |
| `sun:15:00-sun:15:30` | 文字列(同上) | 文字列 |

**規則は「YAML が別の型に読んでしまう値だけクォートする」。** `app.yml` の実例。

```yaml
# app.yml:1     クォート要る。裸だと日付になる
AWSTemplateFormatVersion: "2010-09-09"
# app.yml:761   クォート要る。裸だと数値 8.4 になり、MySQL 8.40 と区別が付かなくなる
      EngineVersion: "8.4"
# app.yml:1269  クォート要る。ECS の Cpu / Memory は文字列型
      Cpu: "512"
# app.yml:1832  クォート要る。MetricFilter の MetricValue は文字列型
          MetricValue: "1"
# app.yml:796   クォート要る……ように見えるが、実は無くても通る。揃えるために付けている
      PreferredBackupWindow: "17:00-17:30"
# app.yml:794   クォート無し。同じ形式なのに付いていない
      PreferredMaintenanceWindow: sun:15:00-sun:15:30
```

最後の 2 行は**このリポジトリの中で揺れている実例**。どちらも動くが、意図が読めないので新しく書くときは付ける側に寄せるとよい。

真偽値には別の罠がある。

```yaml
# app.yml:125  パラメータの AllowedValues。文字列の "true" / "false"
    AllowedValues: ["true", "false"]

# app.yml:346  リソースのプロパティ。こちらは裸の true(YAML の真偽値)
      EnableDnsHostnames: true
```

**`Parameters` の値は必ず文字列として渡ってくる**(`params/*.json` も CLI の `--parameters` も文字列)。だから `AllowedValues` は `"true"` と書く。一方でリソースのプロパティが `Boolean` 型なら裸の `true` でよい。

**仕様:** [Parameters](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/parameters-section-structure.html) の型は `String` / `Number` / `List<Number>` / `CommaDelimitedList` と AWS 固有型だけで、`Boolean` は無い。真偽を渡したいときは `String` + `AllowedValues: ["true", "false"]` にするのが定石。

### 1-3. 複数行文字列 — `>-` と `|`

長い文字列は 2 通りで折り返せる。読み解くコツは、**記号を 2 つの部品に分けて考える**こと。

- **本体(`>` か `|` か)** — **途中の**改行をどうするか
- **後ろの `-` の有無** — **末尾の**改行をどうするか

この 2 つは独立していて、組み合わせで 4 通りになる。

| 記法 | 途中の改行 | 末尾の改行 | 用途 |
|---|---|---|---|
| `>` | スペースに畳む | 残す | |
| `>-` | スペースに畳む | 落とす | 長い説明文 |
| `\|` | そのまま残す | 残す | スクリプト |
| `\|-` | そのまま残す | 落とす | |

#### 本体 — 「スペースに畳む」と「そのまま残す」の違い

**YAML に書いた改行は「ソースの見た目」であって、値そのものとは限らない。** 同じ 2 行を書いても、記号によって出来上がる文字列が変わる。

```yaml
folded: >
  set -eu
  echo hi

literal: |
  set -eu
  echo hi
```

```
folded  = "set -eu echo hi\n"    # 改行が半角スペース 1 個に置き換わり、1 行に畳まれる
literal = "set -eu\necho hi\n"   # 改行が \n としてそのまま値に入る
```

`>`(folded)は**ワープロの自動折り返しと同じ**で、どこで折り返したかに意味がない。「ファイル上では読みやすく折り返しておきたいが、値としては 1 行の文でいい」ときに使う。

`|`(literal)は書いたとおりの形が値になる。改行そのものが意味を持つとき(シェルスクリプトのコマンド区切りなど)はこちら。

#### 後ろの `-` — 「末尾の改行を残す」と「落とす」の違い

ブロックの**最後の行の後ろ**に `\n` を付けるかどうか、それだけの違い。`-` は strip(削る)の意味。途中の改行の扱いには一切影響しない。

```yaml
keep: |
  hi
strip: |-
  hi
```

```
keep  = "hi\n"
strip = "hi"
```

説明文は末尾に `\n` があっても意味がないので `-` を付けて落とす。スクリプトは最終行の終端として `\n` があるのが自然なので付けない。

#### `app.yml` での使い分け

`app.yml` は説明文に `>-`、シェルスクリプトに `|` を使っている。

```yaml
# app.yml:51-55  >- は改行がスペースになる。1 行の長い文になる
  HostedZoneId:
    Type: AWS::Route53::HostedZone::Id
    Description: >-
      手動管理のホストゾーンの ID。CloudFormation には Terraform の
      data "aws_route53_zone" に相当する仕組みが無いので外から渡す
```

```yaml
# app.yml:1359-1362  | は改行がそのまま残る。シェルスクリプトなので必須
          Command:
            - !Sub |
              set -eu
              if [ "${!SQL_USER:-master}" = "app" ]; then
```

**スクリプトを `>-` で書くと全部 1 行に潰れて壊れる。** 上のスクリプトなら `set -eu if [ ... ]; then ...` という 1 個のコマンドになってしまう。ここは間違えると気付きにくい(YAML としては正しいのでリンタも黙る)。

**日本語で `>-` を使うときの注意:** 「改行 → 半角スペース」なので、日本語の文中で折り返すとそこに半角スペースが入る。`Description: >-` で `手動管理のホストゾーンの` / `ID を渡す` と折り返せば値は `"手動管理のホストゾーンの ID を渡す"` になる。**折り返す位置は、もともとスペースがある場所(英単語や句読点の切れ目)を選ぶ**のが安全。上の `app.yml:51-55` も `Terraform の` / `data "..."` と、英単語の前で折り返している。

`|` の中の `${!SQL_USER}` については §5-5 で扱う。

### 1-4. コメント

`#` から行末まで。**JSON には無いので、これが YAML を選ぶ最大の理由**になっている。

`app.yml` は 2112 行中 456 行(約 22%)がコメントで、**「なぜそう書いたか」を必ず残す**方針を採っている。

```yaml
# app.yml:365-372
  # AZ は Fn::GetAZs で引く。ap-northeast-1 の a / c を直書きしないのは、
  # リージョンを変えたときにテンプレートを直さなくて済むようにするため。
  PublicSubnetA:
    Type: AWS::EC2::Subnet
    Properties:
      AvailabilityZone: !Select [0, !GetAZs ""]
```

**注意: コメントもテンプレートのバイト数に数えられる。** 日本語は 1 文字 3 バイトなので、行数より先にサイズ上限へ当たる(→ [テンプレートの分割と置き場 §4](./templates-and-prerequisites.md))。

---

## 2. トップレベルのセクション

### 2-1. キーは 9 つ。必須は `Resources` だけ

**仕様:** [Template anatomy](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/template-anatomy.html)

| キー | 必須 | 何を書くか | `app.yml` |
|---|---|---|---|
| `AWSTemplateFormatVersion` | — | 固定文字列 `"2010-09-09"` | 1 行目 |
| `Description` | — | テンプレートの説明(0〜1024 バイト) | 22 行目 |
| `Metadata` | — | 任意のデータ。コンソールの入力画面の整形など | **未使用**(→ §2-4) |
| `Parameters` | — | 外から受ける値 | 32 行目 |
| `Rules` | — | パラメータ間の検証 | **未使用**(→ §2-5) |
| `Mappings` | — | 定数表 | 286 行目 |
| `Conditions` | — | 名前付きの真偽値 | 297 行目 |
| `Transform` | — | マクロ / SAM / 言語拡張の宣言 | **未使用** |
| `Resources` | **必須** | 作るもの | 307 行目 |
| `Outputs` | — | スタックから読み出す値 | 2042 行目 |

`Resources` 以外は全部省略できる。**最小のテンプレートはこれだけで通る。**

```yaml
Resources:
  MyBucket:
    Type: AWS::S3::Bucket
```

`AWSTemplateFormatVersion` は 2010 年から一度も改訂されていない。**別の値は無いので、書くなら `"2010-09-09"` 一択。** 省略しても動く。

### 2-2. 順序に意味は無い

セクションの並び順も、`Resources` の中のリソースの並び順も、**CloudFormation は見ていない。** 作成順は `!Ref` / `!GetAtt` / `!Sub` の参照から自動で組み立てられる(→ §7-2)。

だから並び順は**人間のための都合**でしかない。`app.yml` はレイヤ順に並べている。

```
ネットワーク → SG → 証明書/DNS/SES → ロググループ → RDS
  → S3+CloudFront → ALB → WAF → IAM → ECS → 監視 → ログアーカイブ
```

グループの見出しはコメントで作る。

```yaml
# app.yml:339-341
  # ===========================================================================
  # ネットワーク
  # ===========================================================================
```

### 2-3. `Description` はマルチバイトが化ける

`app.yml` の `Description` だけ英語になっている。理由がコメントに書いてある。

```yaml
# app.yml:19-22
# Description はマルチバイト文字を保持せず、コンソール上で ? に化ける
# (AWS のドキュメントには 0〜1024 バイトとしか書かれていない、未文書の挙動)。
# 日本語の説明は上のコメントブロックに置き、ここは英語で書く。
Description: Verification environment for the posting app (ALB + ECS Fargate + RDS MySQL)
```

**これは実際に踏んだ**(コミット `51e30b7`「日本語だと弾かれる Description を英語に直す」)。**未検証:** 化けるのか弾かれるのかはコミットメッセージと現行コメントで表現が違う。いずれにせよ日本語を入れない、が結論。

**`Description` はトップレベルに 1 つだけ。** リソースごとの説明はコメントで書き、パラメータの説明は各パラメータの `Description` を使う。

### 2-4. `Metadata` — 解釈されない任意データの置き場

**このリポジトリでは未使用。** ただし他所のテンプレートでは頻出するので読めるようにしておく。

まず、`Metadata` という名前の入れ物は**テンプレートに 2 種類ある**。ここを分けないと話が混ざる。

```yaml
Metadata:                       # ← ① トップレベルのセクション
  AWS::CloudFormation::Interface: ...

Resources:
  Vpc:
    Type: AWS::EC2::VPC
    Metadata: ...               # ← ② リソースの属性(Properties の外側 → §7-1)
    Properties: ...
```

どちらも「CloudFormation が解釈しない任意のデータ」の置き場で、**保存して、そのまま返すだけ**(`aws cloudformation get-template` で読める)。デプロイの挙動は変わらない。ただしいくつかの予約キーには、**CloudFormation 以外**が意味を持たせている。

#### ① トップレベルの `Metadata` — 用途は 3 つ

**1. `AWS::CloudFormation::Interface`** — **マネジメントコンソールでスタックを作るときのパラメータ入力画面の並び順とラベル**を決める。AWS が意味を持たせている唯一のキー。

```yaml
Metadata:
  AWS::CloudFormation::Interface:
    ParameterGroups:
      - Label:
          default: ネットワーク
        Parameters: [VpcCidr, PublicSubnetACidr, PrivateSubnetACidr]
      - Label:
          default: データベース
        Parameters: [DbInstanceClass, DbAllocatedStorage]
    ParameterLabels:
      DbInstanceClass:
        default: RDS のインスタンスクラス
```

これを書かないと、コンソールの入力画面は `Parameters` に書いた順に縦一列で並ぶ(`app.yml` なら約 50 個)。書くと見出しつきのセクションに分かれる。

**あくまで表示の指定で、パラメータを増やすことも値を入れることもできない。** 渡せる値やバリデーションは変わらず、入力欄そのものの制約は `Parameters` 側の `AllowedValues` / `MinValue` が担当する。

**2. `AWS::CloudFormation::Designer`** — 廃止された GUI エディタ「CloudFormation Designer」が書き込んでいた、**図の中でのアイコンの座標**。

```yaml
Metadata:
  AWS::CloudFormation::Designer:
    d3a1b2c3-4d5e-6f70-8901-23456789abcd:
      size: { width: 60, height: 60 }
      position: { x: 240, y: 90 }
```

手で書くものではない。古いテンプレートを拾ってくるとこれが大量に入っていて読みにくい、という形でしか出会わない。消してよい。

**3. 完全に自由なメモ欄** — AWS が何も解釈しないという性質を逆に利用して、**サードパーティのツールが自分の設定置き場にしている。**

```yaml
Metadata:
  cfn-lint:                      # cfn-lint がこれを読む(CloudFormation は読まない)
    config:
      ignore_checks: [W2001]
  TemplateVersion: "2.1.0"       # ただのメモ(誰も読まない)
  Owner: platform-team
```

#### ② リソースの `Metadata` 属性 — `cfn-init`

**「実際に何かが起きる」`Metadata` はこちら。** `AWS::CloudFormation::Init` は EC2 インスタンス用で、「起動後にこのパッケージを入れ、この設定ファイルを置き、このサービスを起動しろ」という**手順書**を宣言的に書いておく場所。

```yaml
  WebServer:
    Type: AWS::EC2::Instance
    Metadata:
      AWS::CloudFormation::Init:
        config:
          packages:
            yum: { httpd: [] }
          files:
            /var/www/html/index.html:
              content: !Sub "Hello from ${EnvName}"
          services:
            sysvinit:
              httpd: { enabled: true, ensureRunning: true }
    Properties:
      UserData: ...        # ここから cfn-init コマンドを叩く
```

**ただし解釈しているのは CloudFormation ではない。** EC2 の中で動く `cfn-init` エージェントが `DescribeStackResource` API で**自分の `Metadata` を取りに来て**実行している。CloudFormation 側は相変わらず保存して返すだけなので、「解釈しない任意のデータ」という原則は崩れていない。

`AWS::CloudFormation::Authentication` は、その `cfn-init` が S3 の非公開ファイルを取りに行くときの認証情報の指定。`Init` とセットで使う。

**① と違い、② には組み込み関数が書ける**(→ §5-11 の表)。上の `!Sub` が通るのはそのため。パラメータの値を EC2 の中に流し込めるので `Init` が実用になっている。

#### このリポジトリが使わない理由

**`Interface`(推測):** スタックの作成はすべて GitHub Actions から `params/*.json` を渡して行い、コンソールの入力画面を通らないため。同じグルーピングをコメントの区切りで表現している。

```yaml
# app.yml:89, 106 など
  # --- ネットワーク ---
  # --- RDS ---
```

**`AWS::CloudFormation::Init`:** これは **EC2 専用の仕組み**で、ECS Fargate のこのリポジトリには設定を流し込む対象の OS が無い。同じ役割は Docker イメージと ECS のタスク定義(環境変数・SSM パラメータ)が果たしている。

#### 罠 2 つ

**秘密情報を書かない。** `Metadata` は `get-template` / `describe-stack-resource` で読めて、`NoEcho` のようなマスクは効かない。AWS のドキュメントも "doesn't transform, modify, or redact any information in the Metadata section" と明記している。

**`Metadata` だけを変えてもリソースは更新されない。** `Init` を書き換えてスタックを更新しても、起動済みの EC2 には何も起きない(`cfn-hup` という別の常駐エージェントを入れて初めて追従する)。`cfn-init` を使う人が必ず一度は踏む。

### 2-5. `Rules` — パラメータ間の制約はここでだけ書ける

**このリポジトリでは未使用。** だが**知っておく価値がある。**

`Parameters` の制約(`AllowedValues` / `MinValue` など)は**1 つのパラメータの中で完結するものしか書けない**。「A は B より大きいこと」のような**パラメータをまたぐ制約**は書けない。`app.yml` にもその困りごとがコメントで残っている。

```yaml
# app.yml:115-119
  # DbAllocatedStorage より大きい必要があるが、パラメータをまたぐ制約は AllowedValues では
  # 書けないので、値を入れるときに人が気を付ける
  DbMaxAllocatedStorage:
    Type: Number
    MinValue: 20
```

**仕様: それを書く場所が `Rules` セクション。** [Rules section](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/rules-section-structure.html) より。

> The optional `Rules` section validates a parameter or a combination of parameters passed to a template during a stack creation or stack update.

上のケースはこう書ける。

```yaml
Rules:
  MaxStorageMustExceedAllocated:
    Assertions:
      - Assert: !Not [!Equals [!Ref DbMaxAllocatedStorage, !Ref DbAllocatedStorage]]
        AssertDescription: DbMaxAllocatedStorage は DbAllocatedStorage より大きくすること
```

`Rules` 専用の関数として `Fn::Contains` / `Fn::EachMemberEquals` / `Fn::EachMemberIn` / `Fn::RefAll` / `Fn::ValueOf` / `Fn::ValueOfAll` があり、`Conditions` で使う `Fn::And` / `Fn::Or` / `Fn::Not` / `Fn::Equals` も使える。

**未検証: 数値の大小比較そのものは `Rules` にも無い。** 使えるのは等値・包含の判定までなので、上の例も「等しくない」しか言えていない。`AllowedValues` を両方に置いて組み合わせを列挙する、という遠回りは可能だが現実的ではない。

**注意:** 既存ノート [RDS / ECS の環境差分 §9-4](./environment-differences.md) は「項目をまたぐ整合の検証は**書けない**」としているが、正確には**「`Rules` で等値・包含までは書ける。大小比較は書けない」**。

---

## 3. `Parameters` — 型・制約・渡し方

Terraform の `variable` に相当する。**テンプレート本体を共通にして、環境ごとの値だけ外から渡す**ための仕組み。`app.yml` は 52 個持っている。

書き方の骨格。

```yaml
Parameters:
  <パラメータ名>:
    Type: <型>              # 必須。これだけが必須
    Description: <説明>
    Default: <既定値>
    AllowedValues: [...]
    NoEcho: true
```

### 3-1. 型は平坦なものしかない

**仕様:** 使える `Type` は次の 3 系統だけ。

**(a) 基本型 4 つ**

| 型 | 中身 | 例 |
|---|---|---|
| `String` | 文字列 | `stg` |
| `Number` | 整数 or 浮動小数(**内部では文字列として扱われる**) | `20` |
| `List<Number>` | 数値のカンマ区切り | `1,2,3` |
| `CommaDelimitedList` | 文字列のカンマ区切り | `a,b,c` |

`app.yml` が使っているのは `String` と `Number` だけ。**`List<Number>` / `CommaDelimitedList` は未使用。**

**`List<String>` という型は無い。** 文字列のリストを表す型は `CommaDelimitedList` の 1 つきり。対称に見えないのには理由があるので、このセクションの末尾に補足を置いた(→ [補足](#補足--なぜ文字列のリストは-liststring-ではないのか))。

```yaml
# app.yml:33-40
  EnvName:
    Type: String
    AllowedValues: [stg, prod]
    Description: 環境名。リソース名とタグに入る

  ProjectName:
    Type: String
    Default: nuxt-java-practice
    Description: リソース名の接頭辞
```

**オブジェクトやマップは渡せない。** Terraform で `object({...})` にまとめていた設定は、フィールドごとに個別のパラメータへ開くことになる。`app.yml` が 52 個も持っているのはこれが理由で、冒頭にそう書いてある。

```yaml
# app.yml:27-28
# CloudFormation の Parameters は String / Number / List しか持てないため、
# Terraform の rds_config のような構造化した型は使えず、各フィールドを平坦に開く。
```

**(b) AWS 固有型**

`AWS::EC2::VPC::Id` / `AWS::EC2::Subnet::Id` / `AWS::Route53::HostedZone::Id` / `AWS::EC2::KeyPair::KeyName` など。**渡された値が実在するかをスタック操作の前に検証してくれる**のと、コンソールでドロップダウンになるのが利点。

`app.yml` で使っているのは 1 つだけ。

```yaml
# app.yml:51-55
  HostedZoneId:
    Type: AWS::Route53::HostedZone::Id
    Description: >-
      手動管理のホストゾーンの ID。CloudFormation には Terraform の
      data "aws_route53_zone" に相当する仕組みが無いので外から渡す
```

`List<AWS::EC2::Subnet::Id>` のようにリスト版もある(未使用)。

**(c) SSM パラメータストアから引く型**

`Type: AWS::SSM::Parameter::Value<String>` と書くと、**渡すのはパラメータ名で、CloudFormation が値を取ってきて展開する。**

```yaml
# このリポジトリでは未使用
  DbHost:
    Type: AWS::SSM::Parameter::Value<String>
    Default: /nuxt-java-practice/stg/db_host
```

**このリポジトリでは未使用。** 秘密は SSM の SecureString に置き、ECS タスク定義の `Secrets` / `ValueFrom` で**コンテナに直接注入**している(CloudFormation は値を読まない)。

```yaml
# app.yml:1248-1249
            - Name: DB_PASSWORD
              ValueFrom: !Sub arn:aws:ssm:${AWS::Region}:${AWS::AccountId}:parameter${SsmParameterPath}app_db_password
```

**なお `AWS::SSM::Parameter::Value<...>` は SecureString に対応していない。** 平文の `String` / `StringList` だけ。だからこの型を使っても秘密は運べない。

**仕様:** [CloudFormation-supplied parameter types](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/cloudformation-supplied-parameter-types.html) の「Unsupported Systems Manager parameter types」に明記されている。

> In addition, CloudFormation doesn't support defining template parameters as `SecureString` Systems Manager parameter types.

**この制限が `BasicAuthCredential` を GitHub Actions の secret 経由にしている理由。** `app.yml` で唯一「秘密をパラメータで受けている」値がこれで(→ [3-3](#3-3-noecho-は隠すであって暗号化ではない))、CloudFormation の中に値を持ち込む経路が 3 つとも塞がっている。

| 経路 | 使えない理由 |
|---|---|
| `AWS::SSM::Parameter::Value<String>`(この (c) の型) | **SecureString に対応していない**(上の仕様) |
| `{{resolve:ssm-secure:...}}`(動的参照。SecureString を読める) | 対応プロパティが 11 個に限定されていて、`AWS::WAFv2::WebACL` の `ByteMatchStatement.SearchString` が入っていない(→ [3-3](#3-3-noecho-は隠すであって暗号化ではない)、`app.yml:69-78`) |
| `{{resolve:ssm:...}}`(平文の動的参照。プロパティ制限は無い) | 読めるが、**SSM 側を平文の `String` 型で持つことになる**ので採らない |

DB パスワードのように ECS のタスク定義へ注入するだけの秘密は、`Secrets` / `ValueFrom` で**コンテナが直接 SSM から読む**ので CloudFormation は値に触らない。しかし Basic 認証の資格情報は **WAF のルールの中に埋め込む必要がある**ため、テンプレートが値そのものを持たざるを得ない。

**結果、値の出どころはスタックの外側になる。** それを埋めているのが GitHub の Environment secret で、`cfn-apply.yml` が `create-change-set` の `--parameters` に差し込んでいる。

```yaml
# .github/workflows/cfn-apply.yml:210
          BASIC_AUTH_CREDENTIAL: ${{ secrets.BASIC_AUTH_CREDENTIAL }}

# .github/workflows/cfn-apply.yml:229-234  params/*.json に積んで unique_by で上書きする
          parameters=$(jq --arg tag "$IMAGE_TAG" --arg cred "$BASIC_AUTH_CREDENTIAL" --arg desired "$WEB_DESIRED_COUNT" '
            . as $params
            | [ {ParameterKey: "ImageTag",            ParameterValue: $tag},
                {ParameterKey: "WebDesiredCount",     ParameterValue: $desired},
                {ParameterKey: "BasicAuthCredential", ParameterValue: $cred} ]
            | map(select(.ParameterValue != "")) + $params
```

**`params/*.json` には書かない。** リポジトリに平文で残ってしまうため。`map(select(.ParameterValue != ""))` は secret が空のときにこのキーごと落とすので、そのときは `Default: ""` が効く。Basic 認証を使わない環境(prod は `EnableBasicAuth: "false"`)では WAF のリソース自体が `Condition: BasicAuthEnabled` で作られないため、空のままで通る。

秘密の置き場の使い分けそのものは → [ADR-0006](../../adr/0006-basic-auth-with-waf.md)。

#### 補足 — なぜ文字列のリストは `List<String>` ではないのか

**仕様: `List<String>` という型は存在しない。** リスト系で書けるのは次の 3 つだけで、`List<String>` はテンプレートの検証で弾かれる。

| 書き方 | 有効か |
|---|---|
| `CommaDelimitedList` | ○ |
| `List<Number>` | ○ |
| `List<AWS::EC2::Subnet::Id>` など AWS 固有型のリスト((b)) | ○ |
| `List<String>` | **×(存在しない)** |

理由は **`List<T>` の `<T>` が「区切り方」ではなく「各要素の検証の仕方」を指しているから。**

- `List<Number>` — カンマで割ったうえで、**各要素が数値か**を検証する
- `List<AWS::EC2::Subnet::Id>` — **各要素のサブネットが実在するか**をスタック操作の前に検証する((b) に書いた利点そのもの)
- `List<String>` — 文字列には検証することが無い。「カンマで割るだけ」になり、`CommaDelimitedList` と完全に同義になってしまう

**つまり `List<String>` を足しても既存の型の別名にしかならないので、存在しない。**

そして **`CommaDelimitedList` という名前は、出来上がる型ではなく入力の書式を指している。** [1-2](#1-2-クォートが要る値要らない値) に書いたとおり `Parameters` に渡ってくる値は必ず文字列なので、この型は「渡ってきた文字列をカンマで区切って配列として扱え」という指示に近い。その証拠に、`List<Number>` も `Ref` すると**文字列の配列**になる。

> For example, users could specify `"80,20"`, and a `Ref` would result in `["80","20"]`.
> — [Parameters](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/parameters-section-structure.html)

**`List<Number>` と `CommaDelimitedList` の違いは、受け取る値ではなく、投入時に数値チェックが入るかどうかだけ。**

`List<String>` という綴り自体は (c) の SSM 型の中にだけ出てくる。しかも**公式が `CommaDelimitedList` と同義だと明記している**ので、2 つが同じものだという裏付けになる。

> `AWS::SSM::Parameter::Value<List<String>>` or `AWS::SSM::Parameter::Value<CommaDelimitedList>`
> A Systems Manager parameter whose value is a list of strings. This corresponds to the `StringList` parameter type in Parameter Store.
> — [CloudFormation-supplied parameter types](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/cloudformation-supplied-parameter-types.html)

**傾向(公式に理由としては書かれていないので断定しない):** 初期の CloudFormation の型は `String` / `Number` / `CommaDelimitedList` で、`List<...>` という記法は AWS 固有型のリスト版を表すために後から入った。その時点で文字列のリストは `CommaDelimitedList` で埋まっていたので、トップレベルには `List<String>` を作らなかった、と読める。SSM 型の側だけ両方の綴りが認められているのは、あとから来た記法で書きたい人への配慮だろう。

使うときの注意。**未検証 — このリポジトリでは未使用なので実物で確かめていない。**

- **各要素は前後の空白がトリムされる**(仕様)。`"a, b, c"` は `["a","b","c"]` になるので、読みやすさのためにカンマの後ろへスペースを入れてよい
- 取り出しは `!Select [n, !Ref <パラメータ名>]`(→ [5-6](#5-6-select-と-getazs))
- **CLI の `--parameters` で渡すときは値の中のカンマを `\\` でエスケープする**(仕様)。`ParameterKey="SubnetIds",ParameterValue="subnet-a\\,subnet-b"`。`params/*.json` の形式(→ 3-4)なら文字列にカンマを含めるだけでよい

### 3-2. 制約は 6 種類

| キー | 対象 | `app.yml` の使用 |
|---|---|---|
| `Default` | 全型 | 12 箇所 |
| `AllowedValues` | 全型 | 11 箇所 |
| `AllowedPattern` | `String` / `CommaDelimitedList` | 4 箇所 |
| `MinValue` / `MaxValue` | `Number` のみ | 使用 |
| `MinLength` / `MaxLength` | `String` のみ | **未使用** |
| `ConstraintDescription` | 全型 | **未使用** |

```yaml
# app.yml:126-129  数値の範囲
  DbBackupRetentionDays:
    Type: Number
    MinValue: 0
    MaxValue: 35

# app.yml:148-149  数値でも AllowedValues で列挙できる(RDS が受け付ける値がとびとびのため)
    Type: Number
    AllowedValues: [0, 1, 5, 10, 15, 30, 60]

# app.yml:65-68  正規表現。末尾スラッシュを強制している
  SsmParameterPath:
    Type: String
    AllowedPattern: ^/.*/$
```

`AllowedPattern` は**プレースホルダのまま反映するのを防ぐ**のにも使える。`app.yml` はそう使っている。

```yaml
# app.yml:263-265
  SlackWorkspaceId:
    Type: String
    AllowedPattern: ^[0-9A-Z]{1,255}$
```

`prod.json` の `HostedZoneId` は `REPLACE_WITH_HOSTED_ZONE_ID` というプレースホルダのままだが、`AWS::Route53::HostedZone::Id` 型なので**そのまま反映しようとすると型検証で落ちる**。同じ発想。

**`ConstraintDescription` は制約に引っかかったときのエラー文言を差し替えるもの。** 未使用だが、書くと親切になる。

```yaml
  SsmParameterPath:
    Type: String
    AllowedPattern: ^/.*/$
    ConstraintDescription: スラッシュで始まり、スラッシュで終わること(例 /project/stg/)
```

**`CommaDelimitedList` に `AllowedPattern` / `AllowedValues` を付けると、リストの文字列全体ではなく各要素に対して効く**(仕様)。`AllowedPattern: ^subnet-` なら「全要素が `subnet-` で始まること」の意味になる。→ [3-1 の補足](#補足--なぜ文字列のリストは-liststring-ではないのか)

**制約はスタック操作の前に検証される。** つまり `AllowedValues` を外れた値を渡すと、リソースは 1 つも作られずに落ちる。ここは安全側。

### 3-3. `NoEcho` は「隠す」であって「暗号化」ではない

```yaml
# app.yml:82-87
  BasicAuthCredential:
    Type: String
    NoEcho: true
    Default: ""
    Description: >-
      Basic 認証の生の資格情報 "user:pass"。base64 化はテンプレート側で行う。
      params ファイルには置かず、GitHub の Environment secret からワークフローが渡す
```

**仕様:** [NoEcho](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/parameters-section-structure.html) を `true` にすると、`describe-stacks` / `describe-change-set` / コンソール / イベントで値が `****` にマスクされる。

**マスクされるのは「CloudFormation が値を返す場所」だけ。** 次のところには平文で出る。

- **テンプレート本体**(`get-template` で読める)。だから値そのものはテンプレートに書かない
- **リソースの実物**。`app.yml` の場合は WAF のルールに base64 が入るので、WAF を見れば読める
- **`Outputs` に出したら丸見え**(`NoEcho` は出力側には効かない)

`app.yml` がこの 1 つだけパラメータで受けている理由が 70-80 行に長々と書いてある。要約すると「SecureString をテンプレートから読む `{{resolve:ssm-secure:}}` が、WAF の `ByteMatchStatement.SearchString` に対応していないから」。

### 3-4. 値の渡し方 — `params/*.json`

Terraform の `tfvars` に相当するのが、このリポジトリでは `cloudformation/params/stg.json`。

**形式は JSON。** CLI の `--parameters file://` がそのまま食える配列。

```json
[
  { "ParameterKey": "EnvName",          "ParameterValue": "stg" },
  { "ParameterKey": "DbAllocatedStorage", "ParameterValue": "20" },
  { "ParameterKey": "DbMultiAZ",        "ParameterValue": "false" }
]
```

**`ParameterValue` は必ず文字列。** 数値も `"20"`、真偽も `"false"` と書く。ここが `AllowedValues: ["true", "false"]` と対応する(→ §1-2)。

`stg.json` と `prod.json` は**キーの順番まで完全に同じ 58 行(パラメータ 50 個)**にしてあり、行単位で diff が取れる。空行のブロック分けも `app.yml` の `# --- RDS ---` のような区切りと 1 対 1 で対応している。

**params に載せないもの**が 2 つある。デプロイのたびに変わる値と秘密は、ワークフローが `--parameter-overrides` で渡す。

| パラメータ | 渡し元 |
|---|---|
| `ImageTag` | ワークフローの input |
| `BasicAuthCredential` | GitHub の Environment secret |

コマンド側の話(`--parameters` の 3 つの書き方、`deploy` が `UsePreviousValue` を埋める仕組み)は → [CLI コマンドを読み解く §5-3, §10-3](./cli-commands-and-change-sets.md)。

### 3-5. 動的参照 `{{resolve:...}}` — パラメータを経由しない渡し方

**`{{resolve:}}` はこのリポジトリでは未使用**(コメントで検討の記録だけ残っている)。代わりに採った ECS の `Secrets` / `ValueFrom` は、このセクションの後半で対比する。

`Parameters` を通さず、**テンプレートの中に直接「SSM / Secrets Manager から取ってこい」と書く**記法。

```yaml
# 平文の SSM パラメータ
      DBName: '{{resolve:ssm:/nuxt-java-practice/stg/db_name}}'

# SecureString
      MasterUserPassword: '{{resolve:ssm-secure:/nuxt-java-practice/stg/db_password}}'

# Secrets Manager
      MasterUserPassword: '{{resolve:secretsmanager:MySecret:SecretString:password}}'
```

**仕様: `ssm-secure` は使えるプロパティが限られている。** [SSM secure string parameters](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/dynamic-references-ssm-secure-strings.html) に対応プロパティの一覧表があり、`AWS::RDS::DBInstance` の `MasterUserPassword` や `AWS::IAM::User` の `LoginProfile.Password` など**11 個だけ**。

`app.yml` が Basic 認証の資格情報でこれを使えなかった理由がそれ。

```yaml
# app.yml:74-79
  # SecureString をテンプレートから読む手段は {{resolve:ssm-secure:...}} だが、
  # これは対応プロパティが 11 個(RDS の MasterUserPassword、IAM ユーザーの LoginProfile.Password など)
  # に限定されていて、AWS::WAFv2::WebACL の ByteMatchStatement.SearchString は入っていない。
  # 平文の {{resolve:ssm:...}} にはプロパティの制限が無いが、SSM 側を平文の String 型で
  # 持つことになるので採らない。
```

**平文の `ssm` にはプロパティ制限が無い**、という非対称がポイント。安全な方が制限されている。

#### 採ったのは ECS の `Secrets` / `ValueFrom`

秘密をコンテナへ渡すのに `app.yml` が実際に使っているのはこちら。**名前が似ていて紛らわしいが、`{{resolve:}}` とはまったく別の仕組み。**

```yaml
# app.yml:1245-1253
          Secrets:
            # DB_USER は app なので、パスワードも app ユーザーのもの(SSM の SecureString)。
            # RDS のマスターパスワードはこのタスクには渡らない(→ docs/adr/0005)
            - Name: DB_PASSWORD
              ValueFrom: !Sub arn:aws:ssm:${AWS::Region}:${AWS::AccountId}:parameter${SsmParameterPath}app_db_password
            - Name: GOOGLE_CLIENT_ID
              ValueFrom: !Sub arn:aws:ssm:${AWS::Region}:${AWS::AccountId}:parameter${SsmParameterPath}google_client_id
            - Name: GOOGLE_CLIENT_SECRET
              ValueFrom: !Sub arn:aws:ssm:${AWS::Region}:${AWS::AccountId}:parameter${SsmParameterPath}google_client_secret
```

**`ValueFrom` に書くのは値ではなく ARN(値の置き場)。** CloudFormation は `!Sub` で ARN の文字列を組み立てるだけで、値には触らない。**実際に SSM を叩くのは ECS エージェントで、タイミングはタスクの起動時。** 取得した値を環境変数としてコンテナに注入する。

その取得に使う権限を持っているのは**タスク実行ロール**。

```yaml
# app.yml:1080-1086
              # SSM の SecureString(手動作成の 4 つ)。既定の aws/ssm キーで暗号化されているので
              # kms:Decrypt を明示する必要はない
              - Sid: SsmSecureStrings
                Effect: Allow
                Action:
                  - ssm:GetParameters
                Resource: !Sub arn:aws:ssm:${AWS::Region}:${AWS::AccountId}:parameter${SsmParameterPath}*
```

**SecureString を復号する権限を持つのは ECS のタスク実行ロールであって、CloudFormation のサービスロールではない。** ここが `{{resolve:ssm-secure:}}` との決定的な違いで、対応プロパティ 11 個の表を気にしなくていい理由でもある。

| | 解決するのは誰か | いつ | SecureString | 値がどこに残るか |
|---|---|---|---|---|
| `Secrets` / `ValueFrom` | **ECS エージェント** | **タスク起動のたび** | ○ | どこにも残らない(タスク定義には ARN だけ) |
| `{{resolve:ssm-secure:}}` | CloudFormation | スタック操作時 | ○ だが**対応プロパティ 11 個だけ** | リソースのプロパティに焼き付く |
| `{{resolve:ssm:}}` | CloudFormation | スタック操作時 | ×(平文の `String` のみ) | 同上 |

「タスク起動のたび」なのが効いていて、**SSM の値を更新したらタスクを入れ替えるだけで反映される。スタックの更新は要らない。** 逆に言うと実行中のタスクには反映されないので、ローテーションしたらデプロイが必要になる。

`describe-task-definition` を叩いても出てくるのは ARN だけで、値は見えない。この点も、値がリソースのプロパティに焼き付く `{{resolve:}}` 系と違う。

**ただしこれは ECS の機能であって、CloudFormation の機能ではない。** 同じ仕組みを `AWS::WAFv2::WebACL` は持っていない。だから `BasicAuthCredential` だけはテンプレート側で値を解決するしかなく、`{{resolve:ssm-secure:}}` も使えず、GitHub の Environment secret から `--parameters` で注入する形になっている(→ [3-1 の (c)](#3-1-型は平坦なものしかない))。

---

## 4. `Resources` — 1 リソースの書き方

唯一の必須セクション。**ここに書いたものが実際に AWS に作られる。**

### 4-1. 論理 ID / `Type` / `Properties` の 3 点セット

```yaml
Resources:
  <論理 ID>:                 # テンプレート内で一意な名前。英数字のみ
    Type: <リソース型>        # 必須
    Properties:              # 型ごとに違う。必須プロパティが無い型では省略可
      <プロパティ名>: <値>
```

最小の実例。

```yaml
# app.yml:342-349
  Vpc:
    Type: AWS::EC2::VPC
    Properties:
      CidrBlock: !Ref VpcCidr
      EnableDnsHostnames: true
      EnableDnsSupport: true
      Tags:
        - Key: Name
          Value: !Sub ${ProjectName}-${EnvName}-vpc
```

**`Type` の形は `AWS::<サービス>::<リソース>` の 3 段。** `app.yml` は 45 種類・78 リソースを使っている。サードパーティ製や自作の型は `<組織>::<サービス>::<リソース>` の形になる(未使用)。

**`Properties` を丸ごと省略できる型もある。** 必須プロパティが 1 つも無い型がそれ。

```yaml
  MyBucket:
    Type: AWS::S3::Bucket      # Properties 無しで通る。名前は CloudFormation が付ける
```

### 4-2. 論理 ID の付け方と、変えたときに起きること

**論理 ID は「テンプレート内でのそのリソースの名前」で、AWS 上のリソース名(物理 ID)とは別物。**

| | 論理 ID | 物理 ID |
|---|---|---|
| 誰が決めるか | 自分 | AWS or 自分(`RoleName` などを指定すれば) |
| 例 | `Vpc` | `vpc-0a1b2c3d4e5f` |
| どこで使うか | `!Ref` / `!GetAtt` / `DependsOn` | AWS のコンソール・CLI |

**使える文字は英数字だけ**(`A-Za-z0-9`)。ハイフンもアンダースコアも使えない。

`app.yml` の命名規則。

- **論理 ID は UpperCamelCase + 種別サフィックス** — `PublicSubnetA` / `AlbSecurityGroup` / `TargetGroupBlue` / `RdsFreeStorageLowAlarm`
- **物理名は例外なく `!Sub ${ProjectName}-${EnvName}-<用途>` のケバブケース** — `nuxt-java-practice-stg-vpc`

```yaml
# app.yml:367-374
  PublicSubnetA:                                       # 論理 ID
    Type: AWS::EC2::Subnet
    Properties:
      Tags:
        - Key: Name
          Value: !Sub ${ProjectName}-${EnvName}-public-a   # 物理名
```

**【重要】論理 ID を変えると、CloudFormation は「古いリソースを削除して新しいリソースを作る」と解釈する。** 名前を変えただけのつもりでも作り直される。Terraform の `moved` ブロックや `terraform state mv` に相当する「リネーム」の手段が無い(→ [対訳ノート §7](./terraform-to-cloudformation.md))。

**だから論理 ID は最初に決めて動かさない。** これはコメントの整理よりも優先する規律になる。

#### どこまでが慣習で、どこからが仕様か

上に挙げた 2 つの規則は**大半が自分ルール**で、破っても動く。ただし性質の違う 3 つの層が混ざっているので分けておく。

| | 破ると | 種別 |
|---|---|---|
| 論理 ID が UpperCamelCase + 種別サフィックス | 何も起きない | 慣習 |
| 論理 ID にハイフン / アンダースコアを使う | **落ちる** | 仕様 |
| 物理名がケバブケース・接頭辞つき | 基本は何も起きない | 慣習 |
| 物理名がリソース固有の長さ・文字種の制約を外れる | **落ちる** | 仕様 |
| 物理名に `${EnvName}` が入る | stg / prod が同居できなくなる | 機能的な理由 |
| 物理名を後から変える | 作り直しになる | 機能的な結果 |

**層 1 — 論理 ID。** UpperCamelCase も種別サフィックスも慣習で、`publicSubnetA` でも `Subnet1` でも通る。仕様なのは「英数字のみ」の 1 点だけなので、**ケバブケースにしようと思った瞬間にだけ**当たる。

**層 2 — 物理名。** `${ProjectName}-${EnvName}-<用途>` という形そのものは慣習だが、物理名にはリソース種別ごとの制約があり、そちらは仕様。**そして `app.yml` は実際に上限へ張り付いている。**

**仕様:** [AWS::ElasticLoadBalancingV2::TargetGroup](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-elasticloadbalancingv2-targetgroup.html) の `Name`。

> This name must be unique per region per account, **can have a maximum of 32 characters**, must contain only alphanumeric characters or hyphens, and must not begin or end with a hyphen.

```
nuxt-java-practice-stg-tg-green    31 文字
nuxt-java-practice-prod-tg-green   32 文字  ← 上限ぴったり
```

**`prod` の Green ターゲットグループ(`app.yml:918`)が 32/32 で、余白がゼロ。** `ProjectName` を 1 文字でも伸ばす、`EnvName` に `staging` のような長い名前を使う、`-tg-green` より長いサフィックスを足す——このどれをやっても、命名規則をそのままにしていると通らなくなる(**未検証**: 実際に落ちるところまでは試していない)。**`ProjectName` を変えるときは、まずここを数えること。**

他の型も制約は別々。`AWS::S3::Bucket` の `BucketName` は小文字のみ(ケバブケースがたまたま条件を満たしている)、`AWS::IAM::Role` の `RoleName` は 64 文字まで。引き方は [4-3](#4-3-プロパティ名は型ごとに違う推測できない) と同じで、毎回リファレンスを見る。

**層 3 — 慣習に見えて機能的な理由があるもの。**

- **`${EnvName}` を挟むのは見た目のためではない。** 物理名を明示したリソースは名前が衝突するので、これが無いと `stg` と `prod` を同じアカウント・同じリージョンに同居させられない
- **`RoleName` を明示していること自体が `--capabilities CAPABILITY_NAMED_IAM` を要求している**(`.github/workflows/cfn-apply.yml:246`)。名前を付けなければ `CAPABILITY_IAM` で済む
- **物理名は多くのリソースで `createOnlyProperty`。** 命名規則を後から変えると作り直しになる。上の【重要】と同じ話が物理名の側にもある、と考えるとよい(`DBInstanceIdentifier` を変えれば RDS は作り直され、中のデータは消える)

**慣習の部分の実益は、揃っていることそのものではなく「grep で引ける」「コンソールの一覧で自分のスタックのリソースだと分かる」「撤収漏れを見つけやすい」。** 作り捨て運用なので最後のが一番効いている。

### 4-3. プロパティ名は型ごとに違う。推測できない

同じ意味のものでも型ごとに綴りが違う。**ここは覚えるものではなく、毎回リファレンスを引くもの。**

- `AWS::EC2::VPC` の CIDR → `CidrBlock`
- `AWS::EC2::Subnet` の CIDR → `CidrBlock`
- `AWS::RDS::DBInstance` のストレージ → `AllocatedStorage`
- `AWS::CloudFront::Distribution` の IPv6 → **`IPV6Enabled`**(`IPv6Enabled` ではない)

最後のものは**このリポジトリで実際に踏んでいる**(コミット `f9f3fed`「CloudFront のプロパティ名を IPV6Enabled に直す」)。

**綴り間違いは `cfn-lint` が拾える。** リファレンスの引き方と合わせて → §9。

### 4-4. `Tags` の書き方は型によって違う

**大半の型は `Key` / `Value` のリスト。**

```yaml
# app.yml:348-350
      Tags:
        - Key: Name
          Value: !Sub ${ProjectName}-${EnvName}-vpc
```

だが**型によって形が違う**ものがある。

- `AWS::S3::Bucket` → `Tags`(同じ形)
- `AWS::ECS::Service` → `Tags` + `PropagateTags`(タスクへ伝播させるか)
- `AWS::Logs::LogGroup` → `Tags`(同じ形)
- `AWS::AutoScaling::AutoScalingGroup` → **`Tags` の要素に `PropagateAtLaunch` が必要**(未使用)

`app.yml` は `Key: Name` だけを 18 箇所に付けている。**`Env` / `Project` を全リソースに付ける方式は採っていない。**

理由は**スタックレベルのタグで足りるから**。`create-change-set --tags` / `deploy --tags` で渡したタグは、**スタックが作る全リソース(対応する型)に自動で伝播する**。Terraform の `default_tags` に相当する。

**注意: `--tags` を省略して更新すると、スタックのタグが消える。** → [CLI コマンドを読み解く §5-6](./cli-commands-and-change-sets.md)

---

## 5. 参照と組み込み関数

**CloudFormation には変数も式も無い。** できるのは「組み込み関数を呼ぶ」ことだけで、その一覧が有限。ここを押さえると書けることの範囲が見える。

### 5-1. 短縮形 `!Xxx` と長形式 `Fn::Xxx` は同じもの

**すべての組み込み関数に 2 つの書き方がある。**

```yaml
# 短縮形 — YAML のタグ記法
CidrBlock: !Ref VpcCidr

# 長形式 — ただのマップ
CidrBlock:
  Fn::Ref: VpcCidr      # ※ Ref だけは Fn:: が付かない。正しくは下
CidrBlock:
  Ref: VpcCidr
```

**`Ref` だけ例外で `Fn::` が付かない。** 残りは全部 `Fn::GetAtt` / `Fn::Sub` / `Fn::If` のように付く。短縮形は `!Ref` / `!GetAtt` / `!Sub` / `!If` と統一されているので、この非対称は長形式のときだけ気にすればいい。

対応表。

| 短縮形 | 長形式 | 短縮形 | 長形式 |
|---|---|---|---|
| `!Ref` | `Ref` | `!Join` | `Fn::Join` |
| `!GetAtt` | `Fn::GetAtt` | `!Select` | `Fn::Select` |
| `!Sub` | `Fn::Sub` | `!Split` | `Fn::Split` |
| `!If` | `Fn::If` | `!Base64` | `Fn::Base64` |
| `!Equals` | `Fn::Equals` | `!Cidr` | `Fn::Cidr` |
| `!Not` | `Fn::Not` | `!GetAZs` | `Fn::GetAZs` |
| `!And` / `!Or` | `Fn::And` / `Fn::Or` | `!ImportValue` | `Fn::ImportValue` |
| `!FindInMap` | `Fn::FindInMap` | `!Condition` | `Condition` |

**`app.yml` は短縮形に統一している。** 長形式は 1 箇所だけで、それには理由がある(→ §5-2)。

**注意: 短縮形は JSON では使えない。** YAML のタグ記法なので、JSON でテンプレートを書くなら全部長形式になる。また `AWS::Include` で取り込むスニペットの中でも短縮形が使えない(→ [テンプレートの分割と置き場 §2-1](./templates-and-prerequisites.md))。

### 5-2. 短縮形が使えない唯一のケース — タグは 2 つ重ねられない

**関数を入れ子にするとき、短縮形を 2 つ直に重ねることはできない。**

```yaml
# ↓ これは通らない。YAML のパースエラーになる
SearchString: !Base64 !Ref BasicAuthCredential
```

**理由は CloudFormation ではなく YAML にある。** `!Base64` も `!Ref` も YAML の「タグ」で、**1 つのノードに付けられるタグは 1 つだけ**。だから並べられない。

`app.yml` はここで長形式に落としている。**リポジトリで唯一の長形式。**

```yaml
# app.yml:1022-1030
                  # Terraform の base64encode(var.basic_auth_credential) に相当。
                  # !Base64 !Ref と短縮形を 2 つ重ねられない(YAML が 1 ノードに
                  # タグを 2 つ許さない)ので Fn::Base64 は長い形式で書く。
                  SearchString: !Sub
                    - Basic ${b64}
                    - b64:
                        Fn::Base64: !Ref BasicAuthCredential
```

回避の型は 2 つ。

```yaml
# (a) 外側を長形式にする
SearchString:
  Fn::Base64: !Ref BasicAuthCredential

# (b) 引数がリストになる関数なら、要素として書けば重ねられる
AvailabilityZone: !Select [0, !GetAZs ""]        # → app.yml:372 と同じ形。これは通る
```

**(b) が通るのは、`!Select` の引数がリストで、その要素は別のノードだから。** 直に重ねているわけではない。

### 5-3. `!Ref` — 何が返るかは型ごとに決まっている

**最もよく使う関数。** 用途は 2 つ。

```yaml
# (a) パラメータの値を取る
CidrBlock: !Ref VpcCidr

# (b) リソースの「主要な識別子」を取る
VpcId: !Ref Vpc                  # → vpc-0a1b2c...
```

**【重要】`!Ref` が何を返すかはリソース型ごとに違う。** 名前のこともあれば ID のことも ARN のこともある。

| 型 | `!Ref` が返すもの |
|---|---|
| `AWS::EC2::VPC` | VPC ID(`vpc-...`) |
| `AWS::S3::Bucket` | バケット名 |
| `AWS::ECS::Cluster` | クラスタ名 |
| `AWS::ECS::TaskDefinition` | **リビジョン付き ARN** |
| `AWS::SNS::Topic` | トピックの ARN |
| `AWS::IAM::Role` | ロール名(ARN ではない) |

**推測してはいけない。** 型のリファレンスの「Return values」に必ず書いてある(→ §9-1)。ここは Terraform より覚えることが多い(あちらは `aws_vpc.main.id` と属性名を明示するので迷わない)。

`app.yml` の実例。

```yaml
# app.yml:2093-2094  クラスタ名が返る
  ClusterName:
    Value: !Ref Cluster

# app.yml:2125-2127  バケット名が返る
  ImageBucketName:
    Value: !Ref ImageBucket
```

**擬似パラメータも `!Ref` で取る。**

```yaml
# app.yml:1258
              awslogs-region: !Ref AWS::Region
```

### 5-4. `!GetAtt` — ドット記法とリスト記法

`!Ref` で取れない属性はこちら。**書き方が 2 つある。**

```yaml
# ドット記法(短縮形で使える)
Value: !GetAtt LoadBalancer.DNSName

# リスト記法(長形式。JSON ではこちらしか書けない)
Value:
  Fn::GetAtt: [LoadBalancer, DNSName]
```

**属性が入れ子のときはドットで繋いでいく。**

```yaml
# app.yml:2118-2123
  DbEndpoint:
    Value: !GetAtt Database.Endpoint.Address

  DbMasterSecretArn:
    Value: !GetAtt Database.MasterUserSecret.SecretArn
```

`Database.Endpoint.Address` は「`Database` というリソースの `Endpoint` 属性の `Address`」。リスト記法だと `[Database, Endpoint.Address]` になり、**論理 ID だけが最初の要素で、残りは 1 つの文字列**になる点に注意。

**取れる属性は型のリファレンスの「Return values」→「Fn::GetAtt」の表にある。** `!Ref` と同じページ。

**`!GetAtt` は `Conditions` の中では使えない**(→ §6-6)。

### 5-5. `!Sub` — 文字列の組み立て

**CloudFormation には文字列連結の演算子が無い。** その代わりがこれ。`app.yml` で 109 回使われていて、`!Ref` に次いで多い。

```yaml
# app.yml:350
          Value: !Sub ${ProjectName}-${EnvName}-vpc
```

**`${}` の中に書けるものは 3 つ。**

| 書けるもの | 例 |
|---|---|
| パラメータ名 | `${ProjectName}` |
| リソースの論理 ID(`!Ref` 相当が入る) | `${Cluster}` |
| **リソースの属性(`!GetAtt` 相当)** | `${LogArchiveBucket.Arn}` |
| 擬似パラメータ | `${AWS::Region}` |

3 つめが見落とされやすい。**`!Sub` の中ではドット記法で属性が取れる。**

```yaml
# app.yml:1998  !GetAtt を別に書かなくていい
                  - !Sub ${LogArchiveBucket.Arn}/*

# app.yml:1215  擬似パラメータとパラメータを混ぜる
          Image: !Sub ${AWS::AccountId}.dkr.ecr.${AWS::Region}.amazonaws.com/${EcrRepositoryName}:${ImageTag}
```

**`${}` を文字として残したいときは `${!}` でエスケープする。** シェルスクリプトを埋め込むときに必須。

```yaml
# app.yml:1360-1367
            - !Sub |
              set -eu
              if [ "${!SQL_USER:-master}" = "app" ]; then     # ${!...} → シェルに ${SQL_USER:-master} として渡る
                RUN_USER="${DbAppUsername}"                   # ${...}  → CloudFormation が展開する
                MYSQL_PWD="$APP_DB_PASSWORD"                  # $... は ! 不要($ 1 つは展開対象外)
```

**同じブロックの中で 2 種類が混ざっている**のがこの実例の要点。`${!SQL_USER:-master}` はシェル変数として残し、`${DbAppUsername}` は CloudFormation が展開する。`$APP_DB_PASSWORD`(波括弧なし)はそもそも `!Sub` の対象外なのでエスケープ不要。

**第 2 引数(変数マップ)を渡すと、その場限りの変数が定義できる。**

```yaml
# app.yml:1027-1030
                  SearchString: !Sub
                    - Basic ${b64}                     # ← テンプレート文字列
                    - b64:                             # ← 変数マップ
                        Fn::Base64: !Ref BasicAuthCredential
```

`!Sub` にリストを渡すと、**1 つめがテンプレート、2 つめが変数の定義**になる。ここでしか使えない `${b64}` が生える。

**これが `locals` の代用になる。** ただし**定義できるのはその `!Sub` の中だけ**で、他のリソースからは参照できない。だから `app.yml` はリソース名の組み立てを毎回 `!Sub` で書き直している(Terraform との対比は → [対訳ノート §4-2](./terraform-to-cloudformation.md))。

### 5-6. `!Select` と `!GetAZs`

`!Select` は**リストから n 番目(0 始まり)を取る**。

```yaml
!Select [<インデックス>, <リスト>]
```

`!GetAZs` は**そのリージョンの AZ 名のリストを返す**。引数はリージョン名で、`""` を渡すとスタックのリージョンになる。

この 2 つは組で使う。

```yaml
# app.yml:365-372
  # AZ は Fn::GetAZs で引く。ap-northeast-1 の a / c を直書きしないのは、
  # リージョンを変えたときにテンプレートを直さなくて済むようにするため。
  PublicSubnetA:
    Type: AWS::EC2::Subnet
    Properties:
      AvailabilityZone: !Select [0, !GetAZs ""]
```

#### 引数の `""` は「スタックのリージョン」

**`""` は「引数を省略した」ではなく「`AWS::Region` を渡した」と同じ意味。** 返るものは変わらず AZ 名のリストで、変わるのは**どのリージョンの AZ を引くか**だけ。

**仕様:** [Fn::GetAZs](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/intrinsic-function-reference-getavailabilityzones.html) の Parameters。

> **region** — The name of the Region for which you want to get the Availability Zones.
> You can use the `AWS::Region` pseudo parameter to specify the Region in which the stack is created. **Specifying an empty string is equivalent to specifying `AWS::Region`.**

**`!GetAZs` と `!Ref AWS::Region` は別物なので混同しないこと。** 前者は AZ 名の**リスト**、後者はリージョン名の**文字列 1 個**を返す。同じなのは「`!GetAZs` の引数として書いたとき」だけ。

**そして `!GetAZs !Ref AWS::Region` とは書けない。** 短縮形のタグを 1 ノードに 2 つ載せられないという [5-2](#5-2-短縮形が使えない唯一のケース--タグは-2-つ重ねられない) の規則がここにも効いていて、AWS がこの組み合わせを名指しで挙げている。

> You can't nest short form functions consecutively, so a pattern like **`!GetAZs !Ref`** isn't valid.

明示して書くなら公式が挙げているのはこの 2 つ。

```yaml
# (1) !GetAZs は短縮形のまま、Ref を長形式にする
AvailabilityZone: !Select
  - 0
  - !GetAZs
    Ref: 'AWS::Region'

# (2) GetAZs のほうを長形式にする
AvailabilityZone: !Select
  - 0
  - Fn::GetAZs: !Ref 'AWS::Region'
```

**`app.yml` が `""` を使っているのは、意味が同じうえに 1 行で書けてタグの重なりを避けられるから。** 3 行に開く (1) や (2) を選ぶ理由が無い。

#### 注意: 返る順序も、返る個数も当てにしない

**AZ 名(`ap-northeast-1a`)とアカウント内の物理 AZ の対応は、AWS がアカウントごとにシャッフルしている。** だから「`!Select [0, ...]` が必ず 1a」ではない。

さらに**順序そのものも保証されていない**(仕様)。

> Similarly to the response from the `describe-availability-zones` AWS CLI command, **the order of the results from the `Fn::GetAZs` function isn't guaranteed and can change when new Availability Zones are added.**

返る個数も環境依存になりうる。

> The `Fn::GetAZs` function returns only Availability Zones that **have a default subnet** unless none of the Availability Zones has a default subnet; in that case, all Availability Zones are returned.

**それでもこの用途には十分。** `app.yml` が必要としているのは「`!Select [0]` と `!Select [1]` が違う AZ を指すこと」だけで、どの AZ が来るかにも順序の安定にも依存していないため。逆に言うと、**特定の AZ を狙う目的で `!GetAZs` を使ってはいけない。** そのときはリージョンごとの `Mappings` に AZ 名を書くか、`AWS::EC2::AvailabilityZone::Name` 型のパラメータで外から渡す。

### 5-7. `!FindInMap` と `Mappings`

`Mappings` は**2 段のキーで引ける定数表**。CloudFormation には `locals` も `data` も無いので、**定数の置き場はここしかない。**

```yaml
Mappings:
  <マップ名>:
    <第1キー>:
      <第2キー>: <値>
```

```yaml
# app.yml:311-320
Mappings:
  # CloudFront のマネージドポリシーは ID を直書きするしかない。
  # Terraform の data "aws_cloudfront_cache_policy" に相当するデータソースが CloudFormation に無い。
  CloudFrontManagedPolicy:
    CachePolicy:
      # Managed-CachingOptimized
      Optimized: 658327ea-f89d-4fab-a63d-7e88639e58f6
    OriginRequestPolicy:
      # Managed-CORS-S3Origin
      CorsS3: 88a5eaf4-2fd4-4709-b370-b4c650ea3fcf
```

引くのは `!FindInMap [マップ名, 第1キー, 第2キー]`。**必ず 3 引数**で、2 段より深くも浅くもできない。

```yaml
# app.yml:853-854
          CachePolicyId: !FindInMap [CloudFrontManagedPolicy, CachePolicy, Optimized]
          OriginRequestPolicyId: !FindInMap [CloudFrontManagedPolicy, OriginRequestPolicy, CorsS3]
```

**制約が 2 つある。**

- **値に組み込み関数を書けない。** `Mappings` の中はリテラルだけ
- **キーには `!Ref` が使える。** `!FindInMap [EnvConfig, !Ref EnvName, DbInstanceClass]` のように環境で引き分ける書き方ができる

2 つめが「`Mappings` を環境差分の置き場にする」定石だが、**このリポジトリは意図的に採らない。** 共通テンプレートに prod の値が直書きされるのを避けるため、環境差分は `params/*.json` にしか置かない(→ [RDS / ECS の環境差分 §7](./environment-differences.md))。

### 5-8. `!Join` / `!Split` / `!Cidr` / `!Base64`

**このリポジトリでは `Fn::Base64` 以外すべて未使用。** 他所のテンプレートでは頻出する。

**`!Join`** — 区切り文字でリストを繋ぐ。

```yaml
!Join [<区切り文字>, [<要素>, ...]]

# 例
!Join ["-", [!Ref ProjectName, !Ref EnvName, "vpc"]]   # → nuxt-java-practice-stg-vpc
```

**`!Sub` があれば大抵不要。** 上の例は `!Sub ${ProjectName}-${EnvName}-vpc` と同じで、後者のほうが読みやすい。`app.yml` が `!Join` を 1 度も使っていないのはそのため。

**`!Join` が要るのは「要素数が動的なとき」。** `!Split` の結果や `!GetAZs` の返り値をそのまま繋ぐようなケース。

```yaml
# 全 AZ をカンマ区切りにする。!Sub では書けない
!Join [",", !GetAZs ""]
```

**`!Split`** — 文字列を区切って リストにする。`!Join` の逆。

```yaml
!Split [",", "a,b,c"]        # → [a, b, c]
```

`CommaDelimitedList` 型のパラメータを使えば同じことができるので、出番は「他の関数の返り値を割る」とき。

**`!Cidr`** — CIDR ブロックを等分割する。

```yaml
!Cidr [<CIDR>, <個数>, <ホスト部のビット数>]

# 例: /20 を /24 が 4 つに割る(32-24=8 → 第3引数は 8)
!Select [0, !Cidr [!Ref VpcCidr, 4, 8]]
```

**このリポジトリは使わず、サブネットの CIDR を `params/*.json` に 4 本べた書きしている。** サブネットが 4 つで固定なので、計算するより読める値を置くほうが分かりやすい、という判断(と読める)。

**`!Base64`** — 文字列を base64 にする。EC2 の `UserData` で使うのが定番。`app.yml` は WAF の Basic 認証で使っている(→ §5-2)。

**注意: `!Base64` に逆(デコード)は無い。**

### 5-9. `!ImportValue` — 未使用。しかも意図的

他のスタックが `Outputs` で `Export` した値を読む関数。

```yaml
VpcId: !ImportValue nuxt-java-practice-network-VpcId
```

**このリポジトリでは未使用で、`Export` 側も付けていない。** 理由は `app.yml` にコメントで書いてある。

```yaml
# app.yml:2081-2082
# Export は付けない。他のスタックから ImportValue されると、
# エクスポート元のスタックを削除できなくなり撤収運用が壊れる。
```

詳しくは §8-2。

### 5-10. 擬似パラメータ

**宣言しなくても最初からある `!Ref` できる名前。** CloudFormation にデータソースが無いなか、**環境から取れる数少ない値**がこれ。

| 擬似パラメータ | 中身 | `app.yml` |
|---|---|---|
| `AWS::Region` | `ap-northeast-1` | 18 箇所 |
| `AWS::AccountId` | 12 桁のアカウント ID | 14 箇所 |
| `AWS::NoValue` | 「値なし」。プロパティ / リスト要素ごと消える(→ §6-5) | 2 箇所 |
| `AWS::StackName` | スタック名 | **未使用** |
| `AWS::StackId` | スタックの ARN | **未使用** |
| `AWS::Partition` | `aws` / `aws-cn` / `aws-us-gov` | **未使用** |
| `AWS::URLSuffix` | `amazonaws.com` | **未使用** |
| `AWS::NotificationARNs` | スタックの通知先 SNS のリスト | **未使用** |

```yaml
# app.yml:512   リージョンをサービス名に埋める
      ServiceName: !Sub com.amazonaws.${AWS::Region}.s3

# app.yml:871   ARN を組み立てる
                AWS:SourceArn: !Sub arn:aws:cloudfront::${AWS::AccountId}:distribution/${ImageDistribution}
```

**`AWS::Partition` を使わず `arn:aws:` を直書きしている**箇所が 18 ある。中国リージョン(`aws-cn`)や GovCloud(`aws-us-gov`)では動かない書き方だが、**このリポジトリは `ap-northeast-1` 固定なので実害が無い。** 汎用のテンプレートを書くなら `!Sub arn:${AWS::Partition}:...` にする。

#### `AWS::StackName` を物理名に使わないのは意識的な選択

**傾向: 物理名を明示するとき、スタック名を前置するのが CloudFormation の定番のイディオム。**

```yaml
# よく見る書き方。app.yml は採っていない
  Alb:
    Type: AWS::ElasticLoadBalancingV2::LoadBalancer
    Properties:
      Name: !Sub ${AWS::StackName}-alb
```

**スタック名はアカウント・リージョン内で一意なので、前置しておけば物理名が絶対に衝突しない。** 同じテンプレートから何個スタックを建てても勝手に別名になる。他所のテンプレートで `${AWS::StackName}-` で始まる名前を見たら、この意図だと思ってよい。

`app.yml` はこれを使わず、`Parameters` から組んでいる。

```yaml
# app.yml:879
      Name: !Sub ${ProjectName}-${EnvName}-alb
```

**紛らわしいことに、現状この 2 つは同じ文字列になる。**

```
スタック名(cfn-apply.yml:138)   nuxt-java-practice-stg
${ProjectName}-${EnvName}        nuxt-java-practice-stg   ← params/stg.json:2-3
```

**つまりこれは見た目の選択ではなく、「名前が何に依存するか」の選択。**

| | 名前の出どころ |
|---|---|
| `AWS::StackName` 方式 | `create-stack --stack-name` に渡した値(**CLI の引数**) |
| `app.yml` の方式 | `params/*.json` の 2 行(**リポジトリの中**) |

**効いてくるのは建て直しのとき。** このリポジトリは作り捨て運用なので、削除して建て直すのが通常の操作になる。そこで別名(`njp-test` など)で建てると、`AWS::StackName` 方式なら ALB も RDS も IAM ロールも物理名が全部変わる。`app.yml` の方式なら、スタック名が何であれ `nuxt-java-practice-stg-*` のまま。

**物理名をテンプレートの外から参照しているので、後者が要る。** `docs/` の手順書に書いた名前、手動管理の常駐リソース(Route53 のレコード、ECR、SSM の SecureString のパス)との対応、コンソールで探すときの当たり——これらを「スタックをどう建てたか」に左右させたくない。

なお **CloudFormation にスタックのリネームは無い**(削除して建て直すしかない)ので、「スタック名が変わる」は正確には「別名で建て直す」の意味になる。

**代償も受け入れている。** 固定名なので**同じ環境のスタックを 2 つ同時に建てられない**(物理名が衝突して 2 つめの作成が失敗する)。`AWS::StackName` 方式ならこれが自然にできるので、PR ごとの preview 環境のような使い方に向く。`terraform/`(参考コード)は `stg/preview_shared.tf` で preview 環境を持っているが、**このリポジトリは preview 環境を採用しないと決めている**(→ CLAUDE.md の対比表)ので、この代償を払っても困らない。

### 5-11. 関数が書ける場所は限られている

**【重要】どこでも関数が書けるわけではない。**

**仕様:** [Intrinsic functions](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/intrinsic-function-reference.html) より。

> You can use intrinsic functions only in specific parts of a template. Currently, you can use intrinsic functions in resource properties, outputs, metadata attributes, and update policy attributes.

つまり**書けるのは 4 か所**。

| 場所 | 関数 |
|---|---|
| `Resources` の `Properties` の中 | 書ける |
| `Outputs` の `Value` / `Description` | 書ける |
| リソースの `Metadata` 属性 | 書ける |
| リソースの `UpdatePolicy` 属性 | 書ける |
| **`DeletionPolicy` / `UpdateReplacePolicy` / `DependsOn` / `Condition`** | **書けない**(→ §7-6) |
| **`Parameters` の `Default` / `AllowedValues`** | **書けない** |
| **`Mappings` の値** | **書けない** |
| **`Conditions` の中** | `!GetAtt` 以外は書ける(→ §6-6) |

これを知らないと「`DeletionPolicy: !If [IsProd, Retain, Delete]` が通らない」で止まる。**属性は定数しか受け付けない。** 対処は → [RDS / ECS の環境差分 §8-2](./environment-differences.md)。

### 5-12. 早見表 — 全関数とこのリポジトリでの使用状況

| 関数 | 用途 | `app.yml` |
|---|---|---|
| `!Ref` | パラメータ / リソースの主識別子 / 擬似パラメータ | 171 |
| `!Sub` | 文字列の組み立て | 109 |
| `!GetAtt` | リソースの属性 | 41 |
| `!Select` | リストの n 番目 | 4 |
| `!GetAZs` | AZ の一覧 | 4 |
| `!Equals` | 等値判定(`Conditions` 用) | 5 |
| `!Not` | 否定(`Conditions` 用) | 4 |
| `!FindInMap` | `Mappings` から引く | 2 |
| `!If` | 三項演算 | 2 |
| `Fn::Base64` | base64 化 | 1(長形式) |
| `!Or` | 論理和(`Conditions` 用) | 1 |
| `!And` | 論理積(`Conditions` 用) | **0** |
| `!Condition` | 他の `Conditions` を参照 | **0** |
| `!Join` | リストを繋ぐ | **0** |
| `!Split` | 文字列を割る | **0** |
| `!Cidr` | CIDR の分割 | **0** |
| `!ImportValue` | 他スタックの `Export` を読む | **0**(意図的) |
| `Fn::Transform` | マクロの呼び出し | **0** |
| `Fn::ForEach` | 繰り返し(要 `Transform`) | **0** |
| `Fn::ToJsonString` / `Fn::Length` | 要 `Transform: AWS::LanguageExtensions` | **0** |
| `Fn::Contains` / `Fn::RefAll` など 6 つ | `Rules` 専用 | **0** |

**繰り返しの `Fn::ForEach` を採らなかった理由**(マクロなので Change Set 経由でしか展開されず、`CAPABILITY_AUTO_EXPAND` も要る)は → [対訳ノート §4-4](./terraform-to-cloudformation.md)。

---

## 6. `Conditions` と分岐

CloudFormation に `if` 文は無い。**あるのは「名前付きの真偽値」を先に定義しておいて、それをリソースや値に貼る**という仕組み。

### 6-1. `Conditions` セクションの書き方

```yaml
Conditions:
  <条件名>: <真偽を返す関数>
```

```yaml
# app.yml:322-336
Conditions:
  # DesiredCount が 0 の段(DB ユーザー作成待ち)ではオートスケーリングを作らない。
  # Application Auto Scaling は MinCapacity を下回る状態を見つけると勝手に増やすため、
  # 0 のはずのサービスにタスクが立ち上がってクラッシュループする。
  ServiceEnabled: !Not [!Equals [!Ref WebDesiredCount, 0]]

  BasicAuthEnabled: !Equals [!Ref EnableBasicAuth, "true"]

  EnhancedMonitoringEnabled: !Not [!Equals [!Ref DbMonitoringInterval, 0]]

  # base も weight も 0 なら FARGATE の要素を出さない(stg = Spot 100%)。
  # Fn::If はリストの要素としても使えて、AWS::NoValue を返すと要素ごと消える(→ 学習メモ §8-3)
  UseOnDemand: !Or
    - !Not [!Equals [!Ref WebOnDemandBase, 0]]
    - !Not [!Equals [!Ref WebOnDemandWeight, 0]]
```

**`UseOnDemand` は `!Or` を複数行で書いた形。** `!Or [a, b]` とフロー形式でも同じだが、条件が長いときはこちらが読みやすい。

**`Conditions` は宣言するだけでは何も起きない。** 使い道が 2 つあり、そのどちらかで参照して初めて効く。

- **リソースの `Condition:` 属性** → そのリソースを作る / 作らない(§6-3)
- **`!If`** → 値を出し分ける(§6-4)

#### 条件名は自分で付ける名前。実行時の変数ではない

**`Conditions:` の直下のキーは AWS が定めた設定項目ではなく、テンプレートの作者が付けるラベル。** `ServiceEnabled` を公式ドキュメントで検索しても出てこない。位置づけは論理 ID と同じで、**英数字のみ・テンプレート内で一意**という制約も同じ。

「真偽値の変数」と捉えるとだいたい合っているが、普通の変数と違う点が 3 つある。

- **真偽値しか持てない。** 文字列や数値は入らない(値の出し分けは `!If` 側の仕事)
- **評価はスタック操作の開始時に 1 回だけ。** 途中で変わらない
- **参照できるのは `Parameters` / `Mappings` / 擬似パラメータ / 他の `Conditions` だけ。** `!GetAtt` でリソースの属性は参照できない(→ §6-6)

3 つめが本質で、**理由は「どのリソースを作るかを、リソースを 1 つも作る前に決めておく必要がある」から。** 作ってみて結果を見てから条件を決める、ということが原理的にできない。

**だから「実行時の変数」ではなく「テンプレートを展開するときの定数」と捉えるほうが正確。** C の `#ifdef` やビルドフラグに近い。`ServiceEnabled` は `WebDesiredCount` という入力から機械的に決まる値で、スタック操作が始まった時点で確定している。

名前の付け方は **`<何か>Enabled` にして「真のとき作る」と読めるようにするのが慣習**(**傾向**)。`app.yml` の 4 つのうち 3 つがこの形なのは、`Condition: ServiceEnabled` が「サービスが有効なら作る」と読めるため。否定形(`ServiceDisabled`)にすると読むたびに頭の中で反転させることになる。

#### 【重要】`Conditions` という語は 3 つの意味で出てくる

| 書かれ方 | 意味 |
|---|---|
| `Conditions:`(複数形・**テンプレート直下**) | CloudFormation の条件を**定義する**セクション |
| `Condition:`(単数形・**`Properties` の外**) | 条件を**参照する**リソース属性(→ §6-3) |
| `Conditions:`(複数形・**`Properties` の中**) | **CloudFormation とは無関係。** そのリソース型が持つ同名のプロパティ |

3 つめが一番危ない。`app.yml` には 20 行ほどの間に 2 種類が並んでいる。

```yaml
# app.yml:969-982  ALB のリスナールール。これは ELB の語彙で、CloudFormation の条件ではない
  ProductionListenerRule:
    Type: AWS::ElasticLoadBalancingV2::ListenerRule
    Properties:
      Priority: 100
      Conditions:                      # ← Properties の中。どのリクエストに一致させるか
        - Field: host-header
          HostHeaderConfig:
            Values:
              - !Sub ${EnvName}.${AppSubdomain}.${DomainName}

# app.yml:998-1001  こちらが本物
  WebAcl:
    Type: AWS::WAFv2::WebACL
    Condition: BasicAuthEnabled        # ← Properties の外。CloudFormation の条件参照
    Properties:
      ...
```

| | CloudFormation の `Conditions` | ListenerRule の `Conditions` |
|---|---|---|
| 場所 | **テンプレート直下**(`Resources` と同階層) | **`Properties` の中** |
| 誰の語彙か | CloudFormation | Elastic Load Balancing |
| 中身 | `名前: 真偽式` のマップ | `Field` / `Values` のリスト |
| いつ効くか | **スタック操作時**(1 回) | **リクエストごと**(ALB の実行時、ずっと) |
| 何を決めるか | リソースを作る / 作らない | リクエストをこのルールに一致させるか |

**見分け方は「場所」だけで足りる。** テンプレート直下なら CloudFormation のもの、`Properties:` の中ならそのリソース型のプロパティ。§1 の要点 3(`Properties` の内と外で意味が違う)がここにも効いている。

### 6-2. 条件式に使える関数は 5 つ

| 関数 | 書き方 | 意味 |
|---|---|---|
| `!Equals` | `!Equals [a, b]` | 等しいか |
| `!Not` | `!Not [<条件>]` | 否定。**引数は要素 1 つのリスト** |
| `!And` | `!And [<条件>, <条件>]` | 全部真か(2〜10 個) |
| `!Or` | `!Or [<条件>, <条件>]` | どれか真か(2〜10 個) |
| `!Condition` | `!Condition <条件名>` | 他の条件を参照する |

**`!Equals` しか比較が無い。** 大小比較は書けないので、`!Not [!Equals [!Ref X, 0]]` のように「0 でないか」で代用する。`app.yml` の `!Not` 4 つはすべてこの形。

**`!Or` は `UseOnDemand` で使っている**(→ §6-1)。「base か weight のどちらかが 0 でなければ真」を、`!Not [!Equals [..., 0]]` を 2 つ並べて表している。

`!And` と `!Condition` は**未使用**だが、組み合わせるとこう書ける。

```yaml
Conditions:
  IsProd: !Equals [!Ref EnvName, prod]
  IsMultiAZ: !Equals [!Ref DbMultiAZ, "true"]
  # 他の条件を !Condition で参照して組み立てる
  NeedsFullBackup: !And [!Condition IsProd, !Condition IsMultiAZ]
```

**`!Condition` は `Conditions` セクションの中でだけ使う。** リソースに貼るときは属性の `Condition:`(関数ではない)を使うので、名前が似ていて紛らわしい。

### 6-3. リソース属性の `Condition:` — 作る / 作らない

```yaml
  <論理 ID>:
    Type: ...
    Condition: <条件名>       # 偽ならこのリソースは作られない
```

`app.yml` は 6 箇所で使っている。

```yaml
# app.yml:732-734  拡張モニタリングが無効ならロールごと作らない
  RdsMonitoringRole:
    Type: AWS::IAM::Role
    Condition: EnhancedMonitoringEnabled

# app.yml:1500-1503  タスク数 0 の段ではオートスケーリングを作らない
  ScalableTarget:
    Type: AWS::ApplicationAutoScaling::ScalableTarget
    Condition: ServiceEnabled
    DependsOn: Service
```

**Terraform の `count = x ? 1 : 0` に相当するが、違いが 2 つある。**

- **`count` は N 個作れるが、`Condition` は作る / 作らないの二値だけ**
- **`count` はアドレスが `aws_foo.bar[0]` に変わるが、`Condition` は論理 ID を変えない**(0 個 ↔ 1 個の切り替えでハマる問題が無い)

**注意: `Condition` が偽になったリソースを参照しているものがあると、スタック操作が失敗する。** 参照側にも同じ `Condition` を貼るか、`!If` で `AWS::NoValue` に落とす必要がある。`app.yml` の `ServiceEnabled` はオートスケーリング 3 リソースすべてに貼ってある(1460 / 1471 / 1485)のがその形。

### 6-4. `!If` — 値の出し分け

```yaml
!If [<条件名>, <真のときの値>, <偽のときの値>]
```

**リストで 3 要素。三項演算子そのもの。** 複数行で書くこともできる。

```yaml
# app.yml:785-789
      # 拡張モニタリングが無効(0)のときにロールを渡すと組み合わせエラーになるので外す
      MonitoringRoleArn: !If
        - EnhancedMonitoringEnabled
        - !GetAtt RdsMonitoringRole.Arn
        - !Ref AWS::NoValue
```

**`!If` はプロパティの値だけでなく、リストの要素としても書ける。** `app.yml` は ECS の capacity provider の出し分けでこれを使っている。

```yaml
# app.yml:1418-1427
      # stg: [FARGATE_SPOT(1)] / prod: [FARGATE(base 2, weight 0), FARGATE_SPOT(1)]
      CapacityProviderStrategy:
        - !If
          - UseOnDemand
          - CapacityProvider: FARGATE
            Base: !Ref WebOnDemandBase
            Weight: !Ref WebOnDemandWeight
          - !Ref AWS::NoValue
        - CapacityProvider: FARGATE_SPOT
          Weight: !Ref WebSpotWeight
```

要素の位置に `AWS::NoValue` が来ると、**その要素ごとリストから消える**(空文字が入るのではない)。上の例は stg では要素 1 つ(Spot のみ)、prod では要素 2 つ(オンデマンド + Spot)のリストになる。**`!If` が返す「真のときの値」がマップ 1 個ぶんまるごと**である点にも注目。関数は値ならなんでも返せる。

**入れ子にもできる。**

```yaml
!If [IsProd, "large", !If [IsStg, "medium", "small"]]
```

ただし**読めなくなるので 2 段までにしておくのが無難**(傾向)。

**`!If` で書けないこと:** 要素数が N 個に変わるリスト。Terraform の `dynamic` ブロックに相当するものが無いので、0 個 ↔ 1 個の出し入れが限界(→ [RDS / ECS の環境差分 §8-3](./environment-differences.md))。

### 6-5. `AWS::NoValue` — プロパティごと消す

**「そのプロパティを書かなかったことにする」擬似パラメータ。**

```yaml
      MonitoringRoleArn: !If
        - EnhancedMonitoringEnabled
        - !GetAtt RdsMonitoringRole.Arn
        - !Ref AWS::NoValue      # ← MonitoringRoleArn ごと消える
```

**空文字や `null` とは違う。** 空文字を渡すと「空文字という値を設定した」ことになり、型によってはバリデーションで落ちる。`app.yml` の例は「拡張モニタリングが無効なのにロールを渡すと組み合わせエラーになる」ケースで、**書かないことが必要**だった。

`!Ref AWS::NoValue` は `!If` の中でしか意味がない。単独で書いても何も起きない。

### 6-6. `Conditions` の中で `!GetAtt` は使えない

**仕様:** [Conditions section](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/conditions-section-structure.html) より、条件の中で使えるのは `Fn::And` / `Fn::Equals` / `Fn::If` / `Fn::Not` / `Fn::Or` と、`Ref`(パラメータと擬似パラメータのみ)、`Fn::FindInMap`。

**`!GetAtt` が使えない理由は評価のタイミング。** `Conditions` は「どのリソースを作るか」を決めるために**リソースを作る前に評価される**。まだ存在しないリソースの属性は取れない。

**同じ理由で `!Ref <リソースの論理 ID>` も使えない。** `Conditions` の中の `!Ref` はパラメータと擬似パラメータ専用。

つまり**判定材料は `Parameters` と `Mappings` に限られる。** 実物の状態を見て分岐することはできない。

---

## 7. リソース属性 — `Properties` の外側

`Resources` の各エントリで、`Properties` と**同じ階層**に書くものがある。これを**属性(attribute)**と呼ぶ。

```yaml
  Database:
    Type: AWS::RDS::DBInstance      # ← 型
    DeletionPolicy: Delete          # ← 属性
    UpdateReplacePolicy: Delete     # ← 属性
    DependsOn:                      # ← 属性
      - RdsErrorLogGroup
    Properties:                     # ← ここから中がプロパティ
      Engine: mysql
```

**属性はリソースの型に関係なく共通。** プロパティは型ごとに違う。ここが混ざると調べ先を間違える。

### 7-1. 属性は 7 つ

| 属性 | 何を決めるか | `app.yml` |
|---|---|---|
| `DependsOn` | 作成順・削除順 | 4 箇所 |
| `DeletionPolicy` | **スタック削除時**にリソースをどうするか | 7 箇所 |
| `UpdateReplacePolicy` | **置換が起きたとき**に古い方をどうするか | 7 箇所 |
| `Condition` | 作る / 作らない | 6 箇所 |
| `Metadata` | 任意のデータ(`cfn-init` など) | **未使用**(→ §2-4) |
| `CreationPolicy` | 「作成完了」を待つ条件 | **未使用** |
| `UpdatePolicy` | ローリング更新の方法 | **未使用** |

### 7-2. `DependsOn` — 参照があれば書かなくていい

```yaml
DependsOn: <論理 ID>              # 1 つ
DependsOn: [<論理 ID>, ...]       # 複数(フロー形式)
DependsOn:                        # 複数(ブロック形式)
  - <論理 ID>
  - <論理 ID>
```

`app.yml` は両方使っている。

```yaml
# app.yml:417-419  単一
  PublicDefaultRoute:
    Type: AWS::EC2::Route
    DependsOn: InternetGatewayAttachment

# app.yml:754-756  リスト
    DependsOn:
      - RdsErrorLogGroup
      - RdsSlowQueryLogGroup
```

**【重要】`!Ref` / `!GetAtt` / `!Sub` で参照していれば `DependsOn` は要らない。** CloudFormation は参照から依存グラフを組み立て、作成順を自動で決める。**削除は逆順**で走る。

**明示が要るのは「参照が無いのに順序が要る」ケース。** `app.yml` に 2 種類ある。

**(a) AWS の仕様上、先に何かが無いと失敗するもの**

```yaml
# app.yml:417-419  IGW がアタッチされる前にルートを作ると失敗する
  PublicDefaultRoute:
    Type: AWS::EC2::Route
    DependsOn: InternetGatewayAttachment
```

**(b) 削除順のために作るもの**

```yaml
# app.yml:673-675
  # RDS のロググループを明示的に作って RDS に DependsOn させる。
  # 先に消えると、RDS が削除処理中の最終書き込みで同名のロググループを作り直し、
  # スタック管理外の孤児(保持期間 無期限)が残る。
```

**(c) 参照をわざと断ったので、依存も一緒に消えたもの** — これが一番示唆的な実例。

```yaml
# app.yml:1495-1503
  # 【DependsOn: Service が要る理由】
  # CloudFormation の作成順は Ref / GetAtt / Sub の参照から自動で組まれる。上の変更で
  # Service への参照が消えた = 暗黙の依存も消えた。明示しないと Service と並列に作られ、
  # まだ存在しないサービスを登録しようとして失敗する(タイミング次第で成否が変わる)。
  ScalableTarget:
    Type: AWS::ApplicationAutoScaling::ScalableTarget
    Condition: ServiceEnabled
    DependsOn: Service
    Properties:
      ResourceId: !Sub service/${Cluster}/${ProjectName}-${EnvName}-app
```

`!GetAtt Service.Name` をやめて同じ文字列を静的に書いた結果、**依存の辺も消えた**。参照が依存を生むという仕組みが裏返しに見える例(なぜ参照を断ったかは → [CLI コマンドを読み解く §7-2](./cli-commands-and-change-sets.md))。

### 7-3. `DeletionPolicy` と `UpdateReplacePolicy`

**この 2 つはよく似ているが、効くタイミングが違う。**

| 属性 | いつ効くか |
|---|---|
| `DeletionPolicy` | **スタックを削除**したとき / テンプレートからリソースを消したとき |
| `UpdateReplacePolicy` | **更新で置換**が起きたとき(古い方の扱い) |

取れる値は `Delete` / `Retain` / `Snapshot`(スナップショットを取れる型のみ)。`DeletionPolicy` にはもう 1 つ `RetainExceptOnCreate` がある。

**`RetainExceptOnCreate` は「`Retain` だが、そのリソースを作った操作がロールバックしたときだけ消す」。** `Retain` の副作用だけを打ち消した改良版と考えてよい。

打ち消している副作用はこれ。**`Retain` を付けたリソースは、初回作成が失敗してロールバックしたときも残ってしまう。** 残るのは作られたばかりで中身が空の、誰も使っていないリソースで、守りたかったデータは 1 バイトも入っていない。それでいて課金は続き、物理名を明示していれば次の建て直しが**同名衝突で失敗する。**

| 場面 | `Retain` | `RetainExceptOnCreate` |
|---|---|---|
| スタックを削除した | 残る | 残る |
| テンプレートからリソースを消して更新した | 残る | 残る |
| **そのリソースを作った操作がロールバックした** | **残る(空の孤児)** | **消える** |

**仕様:** [DeletionPolicy attribute](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/aws-attribute-deletionpolicy.html) より。

> `RetainExceptOnCreate` behaves like `Retain` for stack operations, except for the stack operation that initially created the resource. If the stack operation that created the resource is rolled back, CloudFormation deletes the resource. (略) The result is that **new, empty, and unused resources are deleted, while in-use resources and their data are retained.**

判定の軸は「初回のスタック作成か」ではなく**「そのリソースを作った操作か」**。既存スタックへの更新で足したリソースが、その更新のロールバックで巻き戻る場合も削除される。

**`DeletionPolicy` 専用の値で、`UpdateReplacePolicy` には指定できない**(あちらは `Delete` / `Retain` / `Snapshot` のみ)。

**このリポジトリで `Retain` を使うことがあれば、選ぶべきはこちら。** 作り捨て運用で建てて壊してを繰り返すため作成失敗が起こりうるうえ、物理名が固定(`nuxt-java-practice-stg-db`)なので、孤児が 1 つ残ると次の建て直しが名前衝突で止まる(固定名の選択 → §5-10)。

**【重要】既定値が型によって違う。**

- ほとんどの型 → `Delete`
- **`AWS::RDS::DBInstance` / `DBCluster` / `AWS::Redshift::Cluster` など、スナップショットが取れる型 → `Snapshot`**

`app.yml` はこれを明示している。

```yaml
# app.yml:749-753
    # 【重要】RDS だけは DeletionPolicy の既定が Delete ではなく Snapshot。
    # 明示しないとスタックを消してもスナップショットが残って課金が続く。
    # UpdateReplacePolicy も同じ理由で明示する(作り直しが起きたときの取り残し防止)。
    DeletionPolicy: Delete
    UpdateReplacePolicy: Delete
```

**傾向: この 2 つは常にペアで書く。** 片方だけ書くと `cfn-lint` が W3011 を出す。`app.yml` も 7 箇所すべてペアになっている。

**このリポジトリは全部 `Delete`。** 撤収前提の学習用環境で、消し残りが課金に直結するため。本番なら RDS は `Retain` か `Snapshot` にする。

### 7-4. `Condition`

§6-3 で扱った。**属性の `Condition:` と、`Conditions` セクションの中で使う関数 `!Condition` は別物。**

### 7-5. `CreationPolicy` と `UpdatePolicy` — 未使用

**どちらも EC2 / Auto Scaling が絡むときの属性。** このリポジトリは ECS Fargate なので出番が無い。

**`CreationPolicy`** — 「リソースが作られた」で完了とせず、**中のアプリから合図が来るまで待つ**。

```yaml
  WebServer:
    Type: AWS::EC2::Instance
    CreationPolicy:
      ResourceSignal:
        Count: 1
        Timeout: PT15M          # ISO 8601 の期間表記。15 分
```

インスタンス側で `cfn-signal` を叩くまで `CREATE_IN_PROGRESS` のまま待つ。**タイムアウトすると失敗扱いになる。**

**`UpdatePolicy`** — Auto Scaling グループなどの**更新の仕方**を決める。

```yaml
  Asg:
    Type: AWS::AutoScaling::AutoScalingGroup
    UpdatePolicy:
      AutoScalingRollingUpdate:
        MaxBatchSize: 1
        MinInstancesInService: 1
        PauseTime: PT5M
```

**`UpdatePolicy` は属性なのに組み込み関数が書ける**(→ §5-11 の引用)。属性の中では例外。

ECS で同じことをしたいときは、属性ではなく**プロパティ**の `DeploymentConfiguration` を使う。`app.yml` はそちら。

```yaml
# app.yml:1428-1432
      DeploymentConfiguration:
        MinimumHealthyPercent: 100
        MaximumPercent: 200
        # ECS ネイティブ Blue/Green。切替後に blue を残す時間は環境で変える
        Strategy: BLUE_GREEN
```

### 7-6. 属性には組み込み関数が書けない

**【重要】`DeletionPolicy` / `UpdateReplacePolicy` / `DependsOn` / `Condition` は定数しか受け付けない。**

```yaml
# ↓ どれも通らない
    DeletionPolicy: !If [IsProd, Retain, Delete]
    DependsOn: !If [BasicAuthEnabled, WebAcl, !Ref "AWS::NoValue"]
    Condition: !Sub ${EnvName}Enabled
```

例外は §7-5 の `UpdatePolicy` と `Metadata` の 2 つだけ(→ §5-11)。

**これが「環境ごとに削除の挙動を変える」を難しくしている。** `app.yml` の答えは、属性ではなく**プロパティ側の `DeletionProtection` をパラメータに出す**ことだった。

```yaml
      DeletionProtection: !Ref DbDeletionProtection    # ← プロパティなので !Ref が書ける
```

経緯は → [RDS / ECS の環境差分 §5-4, §8-2](./environment-differences.md)。

---

## 8. `Outputs`

**スタックの外に値を出すセクション。** Terraform の `output` に相当する。

### 8-1. 書き方

```yaml
Outputs:
  <出力名>:
    Description: <説明>        # 任意
    Value: <値>                # 必須
    Condition: <条件名>        # 任意。偽なら出力ごと消える
    Export:                    # 任意
      Name: <エクスポート名>
```

```yaml
# app.yml:2084-2094
Outputs:
  AppUrl:
    Description: アプリの入口
    Value: !Sub https://${EnvName}.${AppSubdomain}.${DomainName}

  LoadBalancerDnsName:
    Description: ALB の DNS 名(DNS 伝播前の切り分けに使う)
    Value: !GetAtt LoadBalancer.DNSName

  ClusterName:
    Value: !Ref Cluster
```

**`Description` は必要なものだけに付ける**のがこのリポジトリの流儀。`ClusterName` のように名前で自明なものは省いている。

**出力の読み方は `aws cloudformation describe-stacks`。** ワークフローはここから `run-task` の引数を組み立てている。

```yaml
# app.yml:2077-2079
# ワークフローが run-task の networkConfiguration をここから組み立てる。
# Terraform 側はこれを SSM パラメータに書き出して受け渡していたが、
# CloudFormation では describe-stacks で読めるので中継が不要。
```

**`NoEcho` は `Outputs` に効かない。** 秘密を出力に書くと `describe-stacks` で丸見えになる(→ §3-3)。

### 8-2. `Export` / `ImportValue` — 意図的に不採用

`Export` を付けると、**同一アカウント・同一リージョンの他のスタックから `!ImportValue` で読める**ようになる。

```yaml
# このリポジトリでは未使用
Outputs:
  VpcId:
    Value: !Ref Vpc
    Export:
      Name: !Sub ${AWS::StackName}-VpcId
```

```yaml
# 別スタック側
      VpcId: !ImportValue nuxt-java-practice-stg-VpcId
```

**このリポジトリは付けない。理由がコメントに書いてある。**

```yaml
# app.yml:2081-2082
# Export は付けない。他のスタックから ImportValue されると、
# エクスポート元のスタックを削除できなくなり撤収運用が壊れる。
```

**仕様: エクスポートには 2 つの強い制約がある。**

- **参照されている間、エクスポート元のスタックは削除できない**
- **参照されている間、エクスポートの値は変更できない**

**「使い終わったらスタックを削除して撤収する」というこのリポジトリの運用と、正面から衝突する。** スタック分割の手段としての `Export` / `ImportValue` の評価は → [テンプレートの分割と置き場 §2-3](./templates-and-prerequisites.md)。

### 8-3. `Outputs` は Change Set に現れない

**差分を確認しても `Outputs` の変更は出てこない。** `describe-change-set` の `Changes` に載るのはリソースだけ。

これは弱点であると同時に**使える性質**でもあり、`app.yml` はそれを利用している箇所がある。

```yaml
# app.yml:2096-2100
  # ここだけ !GetAtt のまま。Outputs は Change Set の Changes に現れないので、
  # ScalableTarget やアラームで避けた「毎回 Dynamic になる」問題が起きない。
  # 実物から読むぶん、名前の式を写し間違える余地も無い。
  ServiceName:
    Value: !GetAtt Service.Name
```

リソース側では「毎回差分が出る」のを避けるために `!GetAtt` を静的文字列に置き換えた(→ §7-2)が、**`Outputs` は差分に出ないのでその心配が無く、`!GetAtt` のほうが安全**という判断。詳細は → [CLI コマンドを読み解く §7-2](./cli-commands-and-change-sets.md)。

---

## 9. 書けない・詰まったときの調べ方

**CloudFormation を書く作業の実態は、リファレンスを引く作業。** プロパティ名も `!Ref` の返り値も型ごとに違うので、覚えるものではない。

### 9-1. 公式リファレンスの引き方

型ごとに 1 ページある。`AWS::RDS::DBInstance` なら [これ](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/aws-resource-rds-dbinstance.html)。**ページの中で見るところは 3 つ。**

| 見出し | 何が分かるか |
|---|---|
| **Properties** | プロパティ名・型・必須かどうか・**`Update requires`** |
| **Return values** | `!Ref` が何を返すか / `!GetAtt` で取れる属性の一覧 |
| **Examples** | 動く YAML |

**`Update requires` が一番重要。** 3 つの値がある。

| 値 | 意味 |
|---|---|
| `No interruption` | 止まらずに更新される |
| `Some interruption` | 一時的に止まる |
| **`Replacement`** | **作り直される**(物理 ID が変わる) |

**`Replacement` のプロパティを変えると、リソースが消えて作り直される。** RDS なら `DBName` や `DBInstanceIdentifier` がこれ。テンプレートを書く前に見ておくと事故が減る。

**注意: 「そのプロパティを変えたら置換」と書いてあるだけで、「今回置換されるか」は分からない。** それを見るのは Change Set(→ [対訳ノート §6-1](./terraform-to-cloudformation.md))。

### 9-2. リソーススキーマ JSON を落とす

同じ情報が機械可読の形でも公開されている。

```bash
curl -s https://schema.cloudformation.us-east-1.amazonaws.com/aws-rds-dbinstance.json | jq .
```

見るキーは 3 つ。

| キー | 意味 |
|---|---|
| `createOnlyProperties` | **変えると置換になるプロパティ**(= `Update requires: Replacement`) |
| `readOnlyProperties` | 書けないプロパティ(`!GetAtt` で読むもの) |
| `required` | 必須プロパティ |

**`Update requires` を機械的に調べたいときはこちら。** ただし `cfn-lint` にこれを使ったルールは無いので、警告としては出てこない(→ [対訳ノート §6-2](./terraform-to-cloudformation.md))。

### 9-3. `cfn-lint`

**手元で構文を見る唯一の現実的な手段。**

```bash
pipx install cfn-lint     # または pip install cfn-lint
cfn-lint cloudformation/app.yml
```

拾えるもの: 存在しないプロパティ名、型の不一致、必須プロパティの欠落、参照できない論理 ID、`DeletionPolicy` と `UpdateReplacePolicy` の片落ち(W3011)。

**`aws cloudformation validate-template` は使えない。** API 呼び出しなので資格情報が要るうえ、**51,200 バイトの上限が掛かる**。`app.yml` は 94,858 バイトなので超えている(→ `cloudformation/README.md`)。

**`cfn-lint` は API を呼ばないので資格情報が不要で、サイズ上限も無い。**

### 9-4. よく出るエラーと原因

| 症状 | 原因 | 見るところ |
|---|---|---|
| YAML のパースエラー | 短縮形のタグを 2 つ重ねた | §5-2 |
| YAML のパースエラー | タブを使った / インデントがずれた | §1-1 |
| `Encountered unsupported property Xxx` | プロパティ名の綴り違い | §4-3 / §9-1 |
| プロパティが文字列として扱われない | クォートの付け忘れ | §1-2 |
| `!If` が効かない / 通らない | 属性に関数を書いた | §5-11 / §7-6 |
| `Template error: ... does not exist` | `Conditions` の中で `!GetAtt` を使った | §6-6 |
| `Parameters: [X] must have values` | `create-change-set` に値を渡していない | §3-4 |
| スクリプトが 1 行に潰れる | `\|` ではなく `>-` を使った | §1-3 |
| シェル変数が消える | `${!VAR}` のエスケープ忘れ | §5-5 |
| `Description` が `?` になる | 日本語を書いた | §2-3 |
| 削除したのにスナップショットが残る | RDS の `DeletionPolicy` 既定が `Snapshot` | §7-3 |
| リソースが並列に作られて失敗する | 参照が無いのに `DependsOn` を書いていない | §7-2 |

コマンドが返すステータスとエラー文言の読み方は → [CLI コマンドを読み解く §6](./cli-commands-and-change-sets.md)、権限不足の現れ方は → [コマンドと IAM 権限 §9, §11](./iam-roles-and-command-permissions.md)。

---

## 10. このリポジトリでの実物の場所

| 見たいもの | 場所 |
|---|---|
| トップレベルのセクション構成 | `cloudformation/app.yml:1`(`AWSTemplateFormatVersion`)/ `22`(`Description`)/ `32` / `311` / `322` / `338` / `2080` |
| `Parameters` 52 個 | `app.yml:32-306`(ネットワーク 89-、RDS 106-、ECS 167-、監視 249-、その他 299-) |
| AWS 固有型のパラメータ | `app.yml:51-55` |
| `NoEcho` と、その値だけパラメータにした理由 | `app.yml:70-87` |
| 環境ごとの値 | `cloudformation/params/stg.json` / `prod.json`(50 個) |
| `Mappings` と `!FindInMap` | `app.yml:311-320` / `853-854` |
| `Conditions` 4 本(`!Or` を含む) | `app.yml:322-336` |
| `!If` + `AWS::NoValue`(プロパティ / リスト要素) | `app.yml:785-789` / `1414-1423` |
| 属性の `Condition:` | `app.yml:734` / `996` / `1046` / `1498` / `1509` / `1523` |
| `DependsOn`(単一 / リスト / 参照を断った例) | `app.yml:419` / `754-756` / `1491-1499` |
| `DeletionPolicy` + `UpdateReplacePolicy` | `app.yml:678-679` / `749-753` / `808-809` |
| 長形式 `Fn::Base64` と `!Sub` の変数マップ | `app.yml:1022-1030` |
| `!Sub` の `${!}` エスケープ(シェル埋め込み) | `app.yml:1359-1367` |
| `!Select` + `!GetAZs` | `app.yml:365-372` |
| 擬似パラメータで ARN を組む | `app.yml:871` / `1245` |
| `Outputs` 18 個と `Export` 不採用の理由 | `app.yml:2074-2154` |
| 環境差分を `params/` にしか置かない規則 | `cloudformation/README.md` |
| `cfn-lint` の使い方とサイズ上限 | `cloudformation/README.md` |
