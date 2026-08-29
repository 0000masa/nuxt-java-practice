# GitHub に登録する Secrets(5 つ)

IAM ロールや Slack の設定を作り終えたあと、**最終的にどの値を GitHub のどこに入れるか**だけをまとめた手順書。ロールそのものの作り方は [cloudformation-operations.md](./cloudformation-operations.md) §2 と [github-actions-oidc.md](./github-actions-oidc.md) §4 にある。

**登録するのは 5 つ。1 つが Repository secret、4 つが Environment `stg` の secret。** 置き場が分かれるのには理由があり、間違えると片方のワークフローだけが動かない(→ §1)。

**AWS の長期クレデンシャル(アクセスキー)は 1 つも登録しない。** 4 つはロールの ARN で、それ自体は資格情報ではない(→ [github-actions-oidc.md](./github-actions-oidc.md) §1)。本物の資格情報は `BASIC_AUTH_CREDENTIAL` だけ。

---

## 0. 一覧

| Secret 名 | 置き場 | 値 | 使うワークフロー | 値の作り方 |
|---|---|---|---|---|
| `AWS_ECR_PUSH_ROLE_ARN` | **Repository** | `nuxt-java-practice-gha-ecr-push` の ARN | `ecr-push.yml` | [github-actions-oidc.md](./github-actions-oidc.md) §4 |
| `AWS_CFN_DEPLOY_ROLE_ARN` | Environment `stg` | `nuxt-java-practice-gha-cfn-stg` の ARN | `cfn-apply.yml` / `cfn-destroy.yml` | [cloudformation-operations.md](./cloudformation-operations.md) §2-2 |
| `AWS_CFN_SERVICE_ROLE_ARN` | Environment `stg` | `nuxt-java-practice-cfn-service-stg` の ARN | 同上(`--role-arn` に渡す) | [cloudformation-operations.md](./cloudformation-operations.md) §2-1 |
| `AWS_DB_TASK_ROLE_ARN` | Environment `stg` | `nuxt-java-practice-gha-dbtask-stg` の ARN | `db-task.yml` | [cloudformation-operations.md](./cloudformation-operations.md) §2-3 |
| `BASIC_AUTH_CREDENTIAL` | Environment `stg` | 生の `user:password` | `cfn-apply.yml` | §2-2 |

**`cfn-deploy.yml` はこの表に出てこない。** 自分では AWS を叩かず `secrets: inherit` で `cfn-apply.yml` と `db-task.yml` に渡すだけなので、専用の Secret を持たない(→ [ADR-0009](../adr/0009-cfn-apply-as-the-single-cloudformation-caller.md))。

**アラートの通知先はここには無い。** Slack のワークスペース ID とチャンネル ID は秘密ではないので `cloudformation/params/*.json` に平文で置く(→ [ADR-0011](../adr/0011-slack-notification-with-chatbot.md))。

---

## 1. なぜ置き場が 2 種類あるのか

**判定基準は「そのワークフローのジョブが `environment:` を宣言しているか」。**

| ワークフロー | `environment:` の宣言 | 読める Secret |
|---|---|---|
| `ecr-push.yml` | **無し** | Repository secret **だけ** |
| `cfn-apply.yml` / `db-task.yml` | `environment: ${{ inputs.env }}` | Repository + Environment `stg` |
| `cfn-destroy.yml` | `environment: stg` | 同上 |

**Environment secret は、`environment:` を宣言したジョブからしか読めない。** `ecr-push.yml` は宣言していないので、`AWS_ECR_PUSH_ROLE_ARN` は Repository secret でなければならない。

### これは IAM の信頼ポリシーと表裏一体になっている

置き場の違いは GitHub 側の都合だけではなく、**AWS 側の信頼ポリシーと対応している。**

| ロール | 信頼ポリシーの `sub` | 意味 |
|---|---|---|
| `gha-cfn-stg` / `gha-dbtask-stg` | `...:environment:stg`(`StringEquals`) | **Environment を宣言したジョブからしか AssumeRole できない** |
| `gha-ecr-push` | `repo:R:*`(`StringLike`) | 宣言の有無を問わない |

つまり `cfn-*` 系は「Environment を宣言する」ことが**ワークフローの都合ではなく AWS 側の要件**になっている。`environment:` を外すと `sub` の末尾が `ref:refs/heads/main` に変わり、信頼ポリシーに一致せず AssumeRole が落ちる(→ [github-actions-oidc.md](./github-actions-oidc.md) §4-1 の `sub` の形の表)。

### 間違えたときに何が起きるか

| やってしまったこと | 症状 |
|---|---|
| `AWS_ECR_PUSH_ROLE_ARN` を Environment `stg` に置いた | `ecr-push.yml` から値が**空に見える**。`Credentials could not be loaded` / `role-to-assume` が空でエラー |
| 4 つを Repository secret に置いた | **動いてしまう。** Repository secret は Environment を宣言したジョブからも読めるため。ただし prod を足した瞬間に stg / prod で値を分けられなくなるので、そのときに全部やり直しになる |
| Environment `stg` を作らずに Environment secret を登録しようとした | 画面にたどり着けない。**先に Environment を作る**(→ §3-2) |

---

## 2. 値を集める

### 2-1. ロールの ARN 4 本

一度に出す。**ロールを 4 本とも作り終えてから**実行する。

```bash
for r in nuxt-java-practice-gha-ecr-push \
         nuxt-java-practice-gha-cfn-stg \
         nuxt-java-practice-cfn-service-stg \
         nuxt-java-practice-gha-dbtask-stg; do
  printf '%-42s %s\n' "$r" \
    "$(aws iam get-role --role-name "$r" --query 'Role.Arn' --output text 2>&1)"
done
```

`NoSuchEntity` が出たロールはまだ作っていない。§0 の表の「値の作り方」に戻る。

ARN は `arn:aws:iam::<アカウントID>:role/<ロール名>` の形。**資格情報ではない**が、アカウント ID が含まれるので Secret に置く(ログで `***` にマスクされる副次的な効果もある)。

### 2-2. Basic 認証の値

WAF が検証環境全体に掛ける Basic 認証(→ [ADR-0006](../adr/0006-basic-auth-with-waf.md))のユーザー名とパスワード。

```
user:password        ← コロン区切りの生の文字列をそのまま入れる
```

**base64 化はしない。** テンプレート側の `Fn::Base64` が行うので、自分で変換した値を入れると認証が通らなくなる。

パスワードを新しく作るなら:

```bash
echo "admin:$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 20)"
```

**ユーザー名にコロンを含めないこと。** 区切りが曖昧になる。

---

## 3. GitHub に登録する

### 3-1. Repository secret(1 つ)

**Settings → Secrets and variables → Actions → Secrets タブ → New repository secret**

| 入力欄 | 値 |
|---|---|
| Name | `AWS_ECR_PUSH_ROLE_ARN` |
| Secret | `arn:aws:iam::<アカウントID>:role/nuxt-java-practice-gha-ecr-push` |

`gh` CLI なら:

```bash
gh secret set AWS_ECR_PUSH_ROLE_ARN \
  --body "$(aws iam get-role --role-name nuxt-java-practice-gha-ecr-push --query 'Role.Arn' --output text)"
```

### 3-2. Environment `stg` を作る

**Settings → Environments → New environment** で名前を `stg` にして作る。

**protection rules は設定しない**(というより設定できない)。GitHub Free のプライベートリポジトリでは required reviewers もブランチ制限も使えないので、**ブランチ制限は IAM の信頼ポリシー側**(`token.actions.githubusercontent.com:ref` の条件)で掛けている。

`gh` CLI なら:

```bash
gh api --method PUT repos/0000masa/nuxt-java-practice/environments/stg
```

### 3-3. Environment secrets(4 つ)

作った `stg` の画面の **Environment secrets → Add environment secret** で 4 つ登録する。

| Name | Secret |
|---|---|
| `AWS_CFN_DEPLOY_ROLE_ARN` | `nuxt-java-practice-gha-cfn-stg` の ARN |
| `AWS_CFN_SERVICE_ROLE_ARN` | `nuxt-java-practice-cfn-service-stg` の ARN |
| `AWS_DB_TASK_ROLE_ARN` | `nuxt-java-practice-gha-dbtask-stg` の ARN |
| `BASIC_AUTH_CREDENTIAL` | `user:password`(§2-2) |

`gh` CLI なら:

```bash
for pair in \
  "AWS_CFN_DEPLOY_ROLE_ARN:nuxt-java-practice-gha-cfn-stg" \
  "AWS_CFN_SERVICE_ROLE_ARN:nuxt-java-practice-cfn-service-stg" \
  "AWS_DB_TASK_ROLE_ARN:nuxt-java-practice-gha-dbtask-stg"; do
  name=${pair%%:*}; role=${pair#*:}
  gh secret set "$name" --env stg \
    --body "$(aws iam get-role --role-name "$role" --query 'Role.Arn' --output text)"
done

# Basic 認証はシェル履歴に残るので、対話で入力する
gh secret set BASIC_AUTH_CREDENTIAL --env stg
```

### 3-4. 登録できたか一覧で見る

```bash
gh secret list                 # Repository secret
gh secret list --env stg       # Environment secret
```

**値は表示されない**(GitHub は書き込み専用として扱う)。名前と更新日時だけが見える。値を間違えた疑いがあるときは、上書き登録し直すのが早い。

---

## 4. 動くことを確かめる

**`ecr-push.yml` を 1 回流すのが一番早い。** Repository secret と OIDC の疎通をまとめて確認できる。

```
Actions → 「ECR へイメージを push」→ Run workflow
  → 「AWS に AssumeRole する(OIDC)」が緑になれば AWS_ECR_PUSH_ROLE_ARN は正しい
```

Environment secrets 側は **`cfn-apply.yml` を `dry_run=true`** で流すと、スタックを作らずに `AWS_CFN_DEPLOY_ROLE_ARN` と `AWS_CFN_SERVICE_ROLE_ARN` の両方を通せる(→ [cloudformation-operations.md](./cloudformation-operations.md) §8)。`AWS_DB_TASK_ROLE_ARN` はスタックが建っていないと確認できない。

### ログから切り分ける

**ログに `role-to-assume: ***` と出ていれば、その Secret は存在し、空でもない。** 空の Secret はマスクされず何も表示されないので、「登録し忘れ」「名前の間違い」「置き場の間違い」はこれだけで切り分けられる。値そのものを確かめる手順 → [github-actions-oidc.md](./github-actions-oidc.md) §8。

---

## 5. prod を足すとき

**ワークフローのコードは 1 行も変えない。** `cfn-apply.yml` と `db-task.yml` は `environment: ${{ inputs.env }}` なので、Environment `prod` を作って**同じ 4 つの名前**で `-prod` のロールの ARN を入れれば切り替わる。これが Environment secrets にしている理由。

必要になるもの:

- IAM ロール 3 本(`...-gha-cfn-prod` / `...-cfn-service-prod` / `...-gha-dbtask-prod`)。**信頼ポリシーの `sub` は `environment:prod` に変える**
- Environment `prod` と、その Environment secrets 4 つ
- `cloudformation/params/prod.json`

`AWS_ECR_PUSH_ROLE_ARN` は**環境をまたいで共通**(イメージは 1 つの ECR に置く)なので増やさない。

**prod を消すボタンは作らない。** `cfn-destroy.yml` は `environment: stg` が直接書かれていて、`env` の入力を持たない(意図的 → [cloudformation-operations.md](./cloudformation-operations.md) §10)。

---

## 6. 詰まったとき

| 症状 | 見るところ |
|---|---|
| `ecr-push` が `Credentials could not be loaded` | `AWS_ECR_PUSH_ROLE_ARN` を **Environment secret に入れていないか**(→ §1)。Repository secret でないと読めない |
| `ecr-push` だけ動いて `cfn-apply` が AssumeRole に失敗する | Environment `stg` を作ったか。Environment secrets 3 つを登録したか(→ §3-2・§3-3) |
| `db-task` だけ `Not authorized to perform sts:AssumeRoleWithWebIdentity` | `AWS_DB_TASK_ROLE_ARN` の登録漏れ。フェーズの途中で分割したロールなので、**後から足す必要がある**(→ [cloudformation-operations.md](./cloudformation-operations.md) §2-3) |
| 全部が `Not authorized to perform sts:AssumeRoleWithWebIdentity` | Secret ではなく**信頼ポリシーの `sub`** を疑う。`sub` にはオーナー ID とリポジトリ ID が入る → [github-actions-oidc.md](./github-actions-oidc.md) §4-1・§8 |
| `Unable to get ACTIONS_ID_TOKEN_REQUEST_URL` | Secret とは無関係。ワークフローに `permissions: id-token: write` があるか |
| ブラウザで Basic 認証が通らない | `BASIC_AUTH_CREDENTIAL` を**自分で base64 化していないか**(→ §2-2)。生の `user:password` を入れる |
| `gh secret set --env stg` が 404 | Environment `stg` がまだ無い(→ §3-2) |

---

## 関連

- [GitHub Actions から AWS を触るための OIDC 設定](./github-actions-oidc.md) — ロールと信頼ポリシーの中身
- [CloudFormation の運用手順](./cloudformation-operations.md) — ロール 3 本の作成、構築・撤収
- [インフラ構成(AWS)](./README.md) — ロールと Secret の対応表
