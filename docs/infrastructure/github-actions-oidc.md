# GitHub Actions から AWS を触るための OIDC 設定(ECR push 用)

`.github/workflows/ecr-push.yml` が ECR にイメージを push できるようにするための、**AWS 側と GitHub 側の 1 回きりのセットアップ手順**。信頼ポリシーと権限ポリシーの中身も逐条で説明する。

コンソール操作と AWS CLI の両方を載せる。**どちらか片方をやればよい。**

> **2026-08-18 に一連の手順を実際に通して確認済み**(push 成功、および 2 回目の実行が事前チェックでスキップされることまで)。 その過程で 4 か所直している
> (IAM の `--description` に日本語が使えない / `--query` のキー名に日本語が使えない /
> `sub` に不変 ID が入る / push に `ecr:BatchGetImage` が要る)。いずれも §8 のトラブルシューティングに
> エラーメッセージから引けるよう載せてある。

関連: [インフラ構成(AWS)](./README.md) / [GitHub Actions で自動テスト](../notes/ci-with-github-actions.md)

---

## 1. 全体像

### なぜアクセスキーではなく OIDC か

GitHub Actions から AWS を操作する方法は 2 つある。

| | アクセスキー方式 | **OIDC 方式(採用)** |
|---|---|---|
| GitHub に置くもの | IAM ユーザーのアクセスキー ID とシークレット | **ロールの ARN だけ**(資格情報ではない) |
| 有効期限 | 手で回さない限り無期限 | **実行ごとに発行され、1 時間ほどで失効** |
| 漏れたときの被害 | 気づくまでずっと使える | そのトークンは既に失効している |
| 誰が使えるか | キーを持っている全員 | **信頼ポリシーで許可した GitHub リポジトリだけ** |

仕組みはこうなる。

```
GitHub Actions のジョブ
  ↓ ① GitHub に「このジョブの身分証(JWT)をくれ」と頼む
     └ permissions: id-token: write が無いとここで断られる
  ↓ ② 身分証には repo / branch / workflow などが署名付きで入っている
AWS STS に AssumeRoleWithWebIdentity
  ↓ ③ STS が GitHub の公開鍵で署名を検証(= OIDC ID プロバイダの登録が要る理由)
  ↓ ④ ロールの信頼ポリシーの条件に合致するか判定(= sub / aud の条件)
  ↓ ⑤ 一時的な資格情報を発行
ECR に push
     └ ロールの権限ポリシーで許可された操作だけができる
```

**登場する設定が 3 つに分かれている**のがポイント。

1. **OIDC ID プロバイダ** — 「GitHub の発行するトークンを信じる」という宣言。アカウントに 1 つ
2. **信頼ポリシー** — 「**誰が**このロールになれるか」。今回のリポジトリを指定する
3. **権限ポリシー** — 「ロールになった人が**何をできる**か」。ECR への push だけ

### なぜ CloudFormation で作らず手動なのか

理由は **循環依存**。

```
ECR に push したい → push 用の IAM ロールが要る
          ↓ ロールを作り捨てスタックが作るとすると
スタックを作る → ECS タスク定義がイメージを参照する → イメージがまだ無い
          ↓ ECS サービスが安定しない(CannotPullContainerError)
スタックが ROLLBACK → 作られかけた IAM ロールごと消える
          ↓
永久に push できない
```

つまり **「ロールが作り捨てスタックの中にある」ことが問題**であって、IaC そのものが悪いわけではない。ECS を含まない常駐の「ブートストラップスタック」を別に作れば CloudFormation で管理することもできる。それを採らなかったのは、**そのスタックを作る作業自体は結局手作業になり、手動の工程がゼロにはならない**ため。それなら [README の手動管理リソースの表](./README.md#ドメインと管理範囲)に Route53 ホストゾーン・ECR と並べるほうが、概念が増えなくて済む。

将来 AWS アカウントを作り直すなら、この手順書の CLI コマンドはそのままブートストラップスタックのテンプレートに翻訳できる。

---

## 2. 前提

- **ECR リポジトリ `nuxt-java-practice-ecs` が作成済み**(タグの上書き禁止 = IMMUTABLE)
- リージョンは **`ap-northeast-1`**
- 作業する人が IAM と ECR を操作できる権限を持っていること

以降のコマンドは、この変数を設定してからそのまま貼れる。

```bash
export AWS_REGION=ap-northeast-1
export AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
export GITHUB_REPO=0000masa/nuxt-java-practice
# 信頼ポリシーに使う不変 ID(取り方は 4-1)。名前ではなくこの数値が本体になる
export GITHUB_OWNER_ID=134136756
export GITHUB_REPO_ID=1303585339
export ROLE_NAME=nuxt-java-practice-gha-ecr-push
export ECR_REPOSITORY=nuxt-java-practice-ecs

echo "アカウント: $AWS_ACCOUNT_ID"
```

---

## 3. 手順1 — OIDC ID プロバイダを作る

「`token.actions.githubusercontent.com` が発行したトークンの署名を信用する」という登録。**AWS アカウントに 1 つだけ**作る。将来 CloudFormation デプロイ用のロールを足すときも、これは共有する。

### まず既にあるか確認する

```bash
aws iam list-open-id-connect-providers
```

`token.actions.githubusercontent.com` を含む ARN が出たら**この手順は飛ばす**(2 つ目は作れず `EntityAlreadyExists` になる)。

### CLI で作る

```bash
aws iam create-open-id-connect-provider \
  --url https://token.actions.githubusercontent.com \
  --client-id-list sts.amazonaws.com \
  --thumbprint-list 6938fd4d98bab03faadb97b34396831e3780aea1
```

### コンソールで作る

IAM → **ID プロバイダ** → **プロバイダを追加**

| 入力欄 | 値 |
|---|---|
| プロバイダのタイプ | OpenID Connect |
| プロバイダの URL | `https://token.actions.githubusercontent.com` |
| 対象者(Audience) | `sts.amazonaws.com` |

### 各項目の意味

- **プロバイダの URL** — トークンの発行元(`iss` クレーム)。ここと一致しないトークンは受け付けない
- **対象者(client-id-list / Audience)** — トークンの宛先(`aud` クレーム)。`sts.amazonaws.com` は「AWS の STS 宛て」という意味で、`aws-actions/configure-aws-credentials` がこの値でトークンを要求する
- **サムプリント(thumbprint)** — **現在は実質的に使われていない。** AWS は 2023 年 7 月以降、この URL のトークンを信頼された認証局の証明書で検証するようになり、サムプリントの一致を要求しない。ただし API のパラメータとしては受け付けるため、公式に案内されてきた値を入れてある。「サムプリントが変わったので更新が必要」という古い記事に従う必要はない

---

## 4. 手順2 — IAM ロールを作る

### 4-1. 信頼ポリシー(誰がこのロールになれるか)

**ここが一番重要。条件を書き忘れると、世界中のどの GitHub リポジトリからでもこのロールを AssumeRole できてしまう**(OIDC の設定ミスとして最も有名な事故)。

```bash
cat > /tmp/trust-policy.json <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Federated": "arn:aws:iam::${AWS_ACCOUNT_ID}:oidc-provider/token.actions.githubusercontent.com"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "token.actions.githubusercontent.com:aud": "sts.amazonaws.com"
        },
        "StringLike": {
          "token.actions.githubusercontent.com:sub": "repo:0000masa@${GITHUB_OWNER_ID}/nuxt-java-practice@${GITHUB_REPO_ID}:*"
        }
      }
    }
  ]
}
EOF
```

逐条で読む。

| 要素 | 意味 |
|---|---|
| `Principal.Federated` | 手順1 で作った ID プロバイダ。**「GitHub が署名したトークンを持っている人」**という principal の指定 |
| `Action: sts:AssumeRoleWithWebIdentity` | OIDC 用の AssumeRole。通常の `sts:AssumeRole`(IAM ユーザー/ロールが対象)とは別物 |
| `aud` の条件 | トークンの宛先が STS であること。**省略してはいけない。** 他サービス向けに発行されたトークンの流用を防ぐ |
| `sub` の条件 | **どのリポジトリの、どの実行か。** これが実質的な「鍵」 |

#### `sub` に不変 ID が入る(ここを間違えると必ず AssumeRole に失敗する)

**リポジトリ名だけを書いた `repo:<owner>/<repo>:*` では一致しない。** GitHub は 2026-07-15 以降に作られたリポジトリ(およびそれ以降に改名・移管したリポジトリ)で、`sub` に**オーナー ID とリポジトリ ID を `@` 区切りで付ける**ようになった。

```
repo:0000masa@134136756/nuxt-java-practice@1303585339:ref:refs/heads/main
     ~~~~~~~~ ~~~~~~~~~ ~~~~~~~~~~~~~~~~~~ ~~~~~~~~~~
     オーナー名 オーナーID  リポジトリ名        リポジトリID
```

区切りが `@` なのは、GitHub のユーザー名とリポジトリ名に `@` を使えないため。OIDC の仕様が「`sub` は再利用してはならない」と定めているのに対し、名前は改名・移管・削除後の再取得で使い回せてしまうので、**一度採番したら二度と再利用されない数値**を足して同一性を保証する仕組み。

これは制約ではなく利点でもある。名前ではなく ID で縛るので、**リポジトリを改名しても信頼ポリシーを直す必要がなく**、逆に「リポジトリを消した後に他人が同じ名前を取って、このロールを使う」という乗っ取りも成立しない。

**ID の取り方**。**正式な手段は API のみで、GitHub の画面に ID を表示する欄は無い**(リポジトリの Settings にも無い)。オーナー ID は公開 API で引ける。

```bash
curl -s https://api.github.com/users/0000masa | jq '.id'
```

リポジトリ ID は private リポジトリだと未認証では引けないので、認証して引く。

```bash
gh api repos/0000masa/nuxt-java-practice --jq '.id'
```

いずれにせよ**最終的に信頼ポリシーと突き合わせるべきなのは、実際に発行されたトークンの `sub`**(手順は「§8 トラブルシューティング」の `sub` 確認方法)。API で引いた ID を書いたのに AssumeRole が通らないときは、推測せずトークンの実物を見る。

> **画面から読み取る裏技もあるが、当てにしないこと。** リポジトリのトップページの HTML には
> `<meta name="octolytics-dimension-repository_id" content="...">`、プロフィール画像の URL には
> `https://avatars.githubusercontent.com/u/<オーナーID>` という形で ID が埋まっているので、
> ソース表示や DevTools で読める(ログイン中なら private リポジトリでも見える)。
> ただしこれらは **GitHub 社内のアナリティクス用の非公開実装**で、ドキュメント化も互換性の保証もされておらず、
> 予告なく消えたり名前が変わったりする。**手順として採用せず、API か `sub` の実物を使う。**

**`sub` クレームの形**は実行のされ方でも変わる。以下は `R` を `0000masa@134136756/nuxt-java-practice@1303585339` の略とする。

| 実行のされ方 | `sub` の値 |
|---|---|
| ブランチで実行(`workflow_dispatch` / `push`) | `repo:R:ref:refs/heads/main` |
| タグで実行 | `repo:R:ref:refs/tags/v1.0` |
| Environment 経由 | `repo:R:environment:production` |
| **fork からの pull_request** | `repo:R:pull_request` |

このリポジトリでは **`repo:R:*`(`StringLike`)** を採った。**別ブランチからもイメージを push したい**ため。

絞り方の選択肢と、採らなかった理由:

| 書き方 | 効果 | 採否 |
|---|---|---|
| `StringEquals` で `repo:R:ref:refs/heads/main` | main からのみ。ワイルドカードが 1 つも無く最も厳格 | **別ブランチで作業できないため不採用** |
| **`StringLike` で `repo:R:*`** | このリポジトリのあらゆる実行 | **採用** |
| `StringLike` で `repo:0000masa@134136756/*` | オーナー配下の**全リポジトリ**。危険 | 不採用 |
| `StringLike` で `repo:*/nuxt-java-practice@*:*` | ID を効かせていないので改名・再取得に耐えない。書く意味がない | 不採用 |
| 条件を書かない | **世界中の GitHub リポジトリ**が対象。事故 | 論外 |

> **この選択に伴う制約:** このロールを AssumeRole するワークフローに **`pull_request` トリガーを足してはいけない。** 上の表のとおり fork からの PR でも `sub` が `repo:R:*` に一致するため、外部の人が PR を出すだけでロールを使えてしまう。`.github/workflows/ecr-push.yml` の先頭にも同じ注意を書いてある。

> **古い記事との食い違いに注意。** ウェブ上の OIDC 設定例はほぼすべて `repo:<owner>/<repo>:*` の形で書かれている。2026-07-15 より前に作られたリポジトリはいまもその形式なので記事が間違っているわけではないが、**このリポジトリを含む新しいリポジトリではそのまま使うと必ず失敗する**。組織またはリポジトリの設定で従来形式に戻すこともできるが、その場合 ID による同一性の保証を捨てることになる。

### 4-2. 権限ポリシー(何ができるか)

```bash
cat > /tmp/permission-policy.json <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "GetAuthToken",
      "Effect": "Allow",
      "Action": "ecr:GetAuthorizationToken",
      "Resource": "*"
    },
    {
      "Sid": "PushToRepository",
      "Effect": "Allow",
      "Action": [
        "ecr:DescribeImages",
        "ecr:BatchGetImage",
        "ecr:BatchCheckLayerAvailability",
        "ecr:InitiateLayerUpload",
        "ecr:UploadLayerPart",
        "ecr:CompleteLayerUpload",
        "ecr:PutImage"
      ],
      "Resource": "arn:aws:ecr:${AWS_REGION}:${AWS_ACCOUNT_ID}:repository/${ECR_REPOSITORY}"
    }
  ]
}
EOF
```

**各アクションが push のどの段階で使われるか**を並べると、この一覧が最小限であることが分かる。

| アクション | 使われる場面 |
|---|---|
| `ecr:DescribeImages` | ワークフロー冒頭の「同じタグが既にあるか」チェック |
| `ecr:GetAuthorizationToken` | `docker login` 用の一時パスワードを取得(`amazon-ecr-login` が呼ぶ) |
| `ecr:BatchGetImage` | **push の途中で** buildx がマニフェストを読みに行く(`GET /v2/<repo>/manifests/<ref>` に相当)。「push なのに読み取り権限が要る」ので見落としやすい |
| `ecr:BatchCheckLayerAvailability` | 各レイヤーが既にレジストリにあるか問い合わせ、**無いものだけ**アップロードする |
| `ecr:InitiateLayerUpload` | レイヤー 1 つのアップロード開始 |
| `ecr:UploadLayerPart` | レイヤーの中身を分割送信 |
| `ecr:CompleteLayerUpload` | レイヤー 1 つのアップロード完了 |
| `ecr:PutImage` | 最後にマニフェスト(レイヤーの組み合わせ + タグ)を登録。**タグ不変の違反はここで弾かれる** |

**`GetAuthorizationToken` だけ `Resource: "*"` になっている理由**: この操作はリポジトリ単位ではなく**レジストリ(アカウント)単位**で、対象となるリソース ARN が存在しない。`*` にせざるを得ない仕様で、緩めているわけではない。

**`ecr:BatchGetImage` が要る理由**: 「push しかしないのだから読み取り権限は不要」と考えると足りなくなる。`docker buildx build --push` はマニフェストを PUT する前に**同じ参照のマニフェストを GET して確認する**ため、レジストリ API の読み取り = `ecr:BatchGetImage` が必要になる。無いと push の最終段でこう落ちる。

```
denied: User: arn:aws:sts::...:assumed-role/... is not authorized to perform:
ecr:BatchGetImage on resource: arn:aws:ecr:...:repository/nuxt-java-practice-ecs
```

AWS のドキュメントが「push に必要なアクション」として挙げているのは `BatchCheckLayerAvailability` / `InitiateLayerUpload` / `UploadLayerPart` / `CompleteLayerUpload` / `PutImage` の 5 つで、素の `docker push` ならそれで足りる。**buildx は素の push より 1 手多い**、というのがここのずれ。

**含めていないもの**: `ecr:GetDownloadUrlForLayer`(レイヤーの実体をダウンロードする権限)は入れていない。ベースイメージは Docker Hub から取り、ビルドキャッシュは ECR ではなく GitHub Actions のキャッシュ(`type=gha`)に置いているので、このロールが ECR から**レイヤーの中身**を読む場面が無いため。`cache-to: type=registry` に切り替えるならこれが必要になる。**ECS がイメージを pull する権限は別のロール**(タスク実行ロール。CloudFormation 側で作る)が持つ。

### 4-3. ロールを作る

#### CLI で作る

```bash
aws iam create-role \
  --role-name "$ROLE_NAME" \
  --description "GitHub Actions role for pushing app images to ECR" \
  --assume-role-policy-document file:///tmp/trust-policy.json

aws iam put-role-policy \
  --role-name "$ROLE_NAME" \
  --policy-name ecr-push \
  --policy-document file:///tmp/permission-policy.json

# 確認
aws iam get-role --role-name "$ROLE_NAME" --query 'Role.Arn' --output text
aws iam get-role-policy --role-name "$ROLE_NAME" --policy-name ecr-push
```

> **`--description` は英語で書く(日本語は通らない)。** IAM のロールの説明は
> `[\u0009\u000A\u000D\u0020-\u007E\u00A1-\u00FF]*` という文字種の制約があり、
> 許されるのは ASCII の印字可能文字と Latin-1 補助(À, é など)だけ。日本語を入れると
> `ValidationError ... Value at 'description' failed to satisfy constraint` になる。
> コンソールの「説明」欄も同じ制約なので、そちらで作る場合も英語にする。
> 説明はロールの動作に影響しないので、`--description` ごと省いてもよい。

管理ポリシー(`create-policy` + `attach-role-policy`)ではなく**インラインポリシー**(`put-role-policy`)にしてある。このポリシーを使うのはこのロールだけで、ロールを消せばポリシーも一緒に消えるため、後片付けが 1 手で済む。

#### コンソールで作る

IAM → **ロール** → **ロールを作成**

1. 信頼されたエンティティタイプ → **ウェブアイデンティティ**
2. アイデンティティプロバイダー → `token.actions.githubusercontent.com`
3. Audience → `sts.amazonaws.com`
4. GitHub organization / repository / branch の入力欄が出るが、**ここで作られる条件は `StringEquals` になる**。organization に `0000masa`、repository に `nuxt-java-practice` だけ入れて branch は空にしておき、**作成後に「信頼関係を編集」で 4-1 の JSON に貼り替える**のが確実
5. 許可ポリシー → 一旦何も選ばずに進む
6. ロール名 → `nuxt-java-practice-gha-ecr-push`
7. 作成後、そのロールの **許可 → 許可を追加 → インラインポリシーを作成 → JSON** に 4-2 の内容を貼る
8. **信頼関係** タブ → **信頼ポリシーを編集** で 4-1 の内容に合わせる

> コンソールのウィザードは branch を空にすると `sub` を `repo:owner/repo:*` にしてくれることが多いが、UI の世代によって挙動が違う。**作成後に信頼関係タブで実際の JSON を必ず確認する。**

---

## 5. 手順3 — ECR リポジトリの設定を確認・ライフサイクルポリシーを付ける

### 現在の設定を確認する

```bash
aws ecr describe-repositories --repository-names "$ECR_REPOSITORY" \
  --query 'repositories[0].{URI:repositoryUri,TagMutability:imageTagMutability,ScanOnPush:imageScanningConfiguration.scanOnPush}'
```

> **`--query` のキー名も英数字にする。** `--query` は JMESPath 式で、引用符で囲まない識別子は
> `[A-Za-z_][A-Za-z0-9_]*` しか使えない。日本語のキーを書くと AWS に送る前に
> `Bad jmespath expression: Unknown token` で止まる(囲めば通るが、素直に英数字にするほうがよい)。

`imageTagMutability` が **`IMMUTABLE`** であること。ワークフローが「同じタグは push せずスキップする」前提で書かれているのは、この設定が理由。

コンソールなら ECR → リポジトリ → 対象を選択 → **イメージタグの変更可能性** で確認できる。

### ライフサイクルポリシー(直近 10 個だけ残す)

タグが不変なので、ワークフローを回すたびにイメージが 1 個ずつ増え、**減ることはない**。1 個あたり約 400 MB あるので放置すると増え続ける(ECR のストレージは $0.10 / GB / 月)。

```bash
cat > /tmp/lifecycle.json <<'EOF'
{
  "rules": [
    {
      "rulePriority": 1,
      "description": "新しい順に 10 個だけ残す",
      "selection": {
        "tagStatus": "any",
        "countType": "imageCountMoreThan",
        "countNumber": 10
      },
      "action": { "type": "expire" }
    }
  ]
}
EOF

aws ecr put-lifecycle-policy \
  --repository-name "$ECR_REPOSITORY" \
  --lifecycle-policy-text file:///tmp/lifecycle.json

# どのイメージが消える判定になるか、実際には消さずに試せる
aws ecr start-lifecycle-policy-preview \
  --repository-name "$ECR_REPOSITORY" \
  --lifecycle-policy-text file:///tmp/lifecycle.json
aws ecr get-lifecycle-policy-preview --repository-name "$ECR_REPOSITORY"
```

> ライフサイクルポリシーの `description` は**日本語で通る**(実機で確認済み)。IAM のロールの説明とは
> 違って文字種の制約が無い。AWS の API はフィールドごとに制約が違うので、「AWS だから英数字だけ」と
> 一般化しないこと。

コンソールなら ECR → リポジトリ → **ライフサイクルポリシー** → ルールを作成。

**`tagStatus` は `any` にする。** 理由:

- `untagged` — このワークフローはタグ無しイメージを作らないので、何も消えない
- `tagPrefixList` — タグはコミットの SHA で毎回違う文字列なので、共通の接頭辞で拾えない

**日数ベース(`sinceImagePushed`)にしない理由**: このリポジトリは「検証したいときだけ環境を建てて、それ以外は放置」という運用なので、**数か月空けるとイメージが 1 個も無くなる**。その状態でスタックを作ると `CannotPullContainerError` で失敗する。個数ベースなら常に直近 N 個が残る。

---

## 6. 手順4 — GitHub に Secrets を登録する

ロールの ARN を GitHub に登録する。**ARN は資格情報ではない**(これだけでは何もできない)が、AWS アカウント ID が含まれるので Secrets に置く。Secrets に置くとログ出力時に `***` にマスクされる副次的な効果もある。

```bash
aws iam get-role --role-name "$ROLE_NAME" --query 'Role.Arn' --output text
```

GitHub のリポジトリ → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**

| 入力欄 | 値 |
|---|---|
| Name | `AWS_ECR_PUSH_ROLE_ARN` |
| Secret | `arn:aws:iam::<アカウントID>:role/nuxt-java-practice-gha-ecr-push` |

**これは Repository secret にする。Environment secret ではない。** `ecr-push.yml` のジョブは `environment:` を宣言していないので、Environment secret を置いても値が空に見えて `Credentials could not be loaded` になる。CloudFormation 系の 4 つが Environment `stg` に入るのとは逆なので、**このリポジトリで唯一の Repository secret**。理由と置き場の一覧 → **[GitHub に登録する Secrets(5 つ)](./github-secrets.md)**。

命名は **`AWS_<用途>_ROLE_ARN`** の規則にする。Secrets 一覧はアルファベット順に並ぶので、`AWS_` 接頭辞で AWS 関連がひとかたまりになり、その中で用途順に読める。

**リージョンと ECR リポジトリ名は Secrets に入れない。** ワークフローの `env:` に直書きしてある。隠す意味が無い値を隠すと、ワークフローを読んでも何をしているのか分からなくなるため。

---

## 7. 動作確認

GitHub のリポジトリ → **Actions** → **ECR へイメージを push** → **Run workflow**(ブランチを選んで実行)

確認する点:

1. **「AWS に AssumeRole する(OIDC)」が緑になる** — 信頼ポリシーと `permissions: id-token: write` が効いている
2. **「同じタグが既に存在するか調べる」で `exists=false`** — 初回はイメージが無いのでこうなる
3. **「ビルドして push」が通る** — 権限ポリシーが足りている
4. **ジョブサマリにイメージタグが出る** — この値を CloudFormation スタックのパラメータに渡す

AWS 側からも確認する。

```bash
aws ecr list-images --repository-name "$ECR_REPOSITORY"
```

**もう一度同じコミットで実行**すると、`exists=true` になってビルドを飛ばし、サマリに「既に push 済みのためスキップしました」と出れば期待どおり。

---

## 8. トラブルシューティング

| エラー | 原因 | 対処 |
|---|---|---|
| `Could not assume role with OIDC: Not authorized to perform sts:AssumeRoleWithWebIdentity` | ① 信頼ポリシーの `sub` が実行時の値と合っていない(**`sub` に不変 ID が入っているのを見落としているのが最も多い** → 4-1)② `role-to-assume` に渡した ARN が別のロールを指している ③ 信頼ポリシーが参照する OIDC プロバイダが存在しない | 下の「`sub` を実際に確認する」で実物と突き合わせる。②は Secret の値、③は `aws iam list-open-id-connect-providers` |
| `Credentials could not be loaded` / `Unable to get ACTIONS_ID_TOKEN_REQUEST_URL` | ワークフローに `permissions: id-token: write` が無い | ワークフローの `permissions` を確認 |
| `InvalidIdentityToken: Incorrect token audience` | ID プロバイダの Audience か、信頼ポリシーの `aud` 条件が `sts.amazonaws.com` になっていない | 手順1 と 4-1 を確認 |
| `EntityAlreadyExists`(プロバイダ作成時) | OIDC プロバイダは既に存在する | 手順1 を飛ばす |
| `ValidationError ... Value at 'description' failed to satisfy constraint`(ロール作成時) | `--description` に日本語を入れた。IAM の説明は ASCII + Latin-1 補助しか受け付けない | 英語にするか `--description` を省く(4-3 の注記) |
| `Bad jmespath expression: Unknown token`(AWS に届く前に失敗する) | `--query` のキー名に日本語を使った | キー名を英数字にする(手順3 の注記) |
| `AccessDeniedException ... ecr:DescribeImages` | 権限ポリシーの ARN・リージョンが実物と違う | `aws ecr describe-repositories` の `repositoryUri` と突き合わせる |
| `denied: ... not authorized to perform: ecr:InitiateLayerUpload` | 同上(push 系の権限) | 4-2 のアクション一覧が揃っているか確認 |
| `denied: ... not authorized to perform: ecr:BatchGetImage`(push の最終段) | buildx はマニフェストを PUT する前に GET する。「push だけなら読み取りは不要」という思い込みで抜けやすい | 権限ポリシーに `ecr:BatchGetImage` を足す(4-2) |
| `ImageTagAlreadyExists` | 同じタグが既にある(タグ不変) | ワークフロー経由なら事前チェックで起きない。手で push したときに出る |
| ECR の一覧に `unknown/unknown` の成果物が並ぶ | buildx の provenance 添付 | ワークフローで `provenance: false` にしてある。手で `docker buildx build` するときは `--provenance=false` |

### `sub` を実際に確認する

信頼ポリシーの条件が合っているかは、推測せず**発行されたトークンの中身**を見るのが早い。ワークフローに一時的にこのステップを足す。

```yaml
      - name: OIDC トークンのクレームを見る
        run: |
          token=$(curl -sS -H "Authorization: bearer $ACTIONS_ID_TOKEN_REQUEST_TOKEN" \
            "$ACTIONS_ID_TOKEN_REQUEST_URL&audience=sts.amazonaws.com" | jq -r '.value')
          payload=$(echo "$token" | cut -d. -f2)
          padded="${payload//-/+}"; padded="${padded//_//}"
          while [ $(( ${#padded} % 4 )) -ne 0 ]; do padded="${padded}="; done
          echo "$padded" | base64 -d | jq '{iss, aud, sub, repository, ref}'
```

JWT は `ヘッダ.ペイロード.署名` の 3 つを `.` で繋いだもので、2 番目だけを取り出して base64url をデコードしている。**クレームをログに出すのは問題ない**(秘密なのは署名で、クレーム自体は改ざんできないことが保証されているだけの公開情報)。ただしトークン全体は出さないこと。

**`sub` が `repo:<owner>@<数字>/<repo>@<数字>:...` の形なら、信頼ポリシー側も同じ形にする必要がある**(→ 4-1)。

Secret に入れた ARN が正しいかも同じ要領で確かめられる。値そのものは `***` にマスクされるが、長さや分解した部分は出せる。

```yaml
        env:
          ARN: ${{ secrets.AWS_ECR_PUSH_ROLE_ARN }}
        run: |
          echo "長さ: ${#ARN}"
          echo "アカウント: $(echo "$ARN" | cut -d: -f5)"
          echo "リソース部: $(echo "$ARN" | cut -d: -f6)"
          case "$ARN" in *[[:space:]]*) echo "::error::空白または改行が含まれている" ;; esac
```

なお **ログに `role-to-assume: ***` と出ていれば Secret は存在し空でもない**(空の Secret はマスクされず何も表示されない)。「登録し忘れ」「名前の間違い」はこれで切り分けられる。

### 同じコミットで作り直したいとき

「ベースイメージのセキュリティ更新を取り込みたい」など、**同じコミットで違うイメージを作りたい**場合、タグが不変なので上書きできない。該当イメージを消してからワークフローを回す。

```bash
aws ecr batch-delete-image \
  --repository-name "$ECR_REPOSITORY" \
  --image-ids imageTag=<短縮SHA>
```

**その ECS タスク定義がまだ生きている環境がある場合は消さないこと。** 稼働中のタスクが再起動したときに pull できなくなる。

---

## 9. 将来ロールを増やすとき

CloudFormation でスタックを作成・削除するワークフローを足すときは、**このロールを使い回さず新しいロールを作る**。

理由: この構成を作るロールには VPC・NAT GW・RDS・ECS・ACM・Route53 に加えて `iam:CreateRole` と `iam:PassRole` が必要で、**実質的に管理者に近い権限**になる。「イメージを push するだけ」のワークフローにそれを持たせると、ワークフローが乗っ取られたときの被害範囲がアカウント全体に広がる。

増やすときの作業:

1. **OIDC ID プロバイダは作り直さない**(アカウントに 1 つ。手順1 を飛ばす)
2. 信頼ポリシーは 4-1 と同じものを使い回せる
3. 権限ポリシーだけ用途に合わせて書く
4. Secrets 名は `AWS_<用途>_ROLE_ARN` の規則に従う

---

## 用語集

- **OIDC(OpenID Connect)** — 「この人は確かにうちのユーザーです」を第三者に証明するための仕組み。ここでは GitHub が発行者、AWS が検証者
- **ID プロバイダ(IdP)** — トークンを発行する側。ここでは `token.actions.githubusercontent.com`
- **AssumeRole** — IAM ロールになりきって一時的な資格情報を得る操作。OIDC 経由のものは `sts:AssumeRoleWithWebIdentity`
- **信頼ポリシー(trust policy / assume role policy)** — **誰がそのロールになれるか**を定義する。ロールに 1 つだけ付く
- **権限ポリシー(permission policy)** — **ロールになった人が何をできるか**を定義する。複数付けられる
- **`sub` クレーム** — トークンに入っている「主体」の識別子。GitHub Actions では `repo:オーナー/リポジトリ:ref:refs/heads/ブランチ` の形
- **`aud` クレーム** — トークンの宛先。AWS 向けは `sts.amazonaws.com`
- **インラインポリシー** — 特定のロールに直接埋め込むポリシー。ロールを消すと一緒に消える
- **タグ不変(IMMUTABLE)** — 一度使ったイメージタグを上書きできない ECR の設定
- **ライフサイクルポリシー** — 古いイメージを自動で削除する ECR のルール
