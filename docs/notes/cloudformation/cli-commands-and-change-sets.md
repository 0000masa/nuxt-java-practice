# CloudFormation の CLI コマンドを読み解く

`cfn-apply.yml`(反映)と `cfn-destroy.yml`(撤収)が叩いている `aws cloudformation` の各コマンドを、API リファレンスと aws-cli / botocore のソースまで降りて読むノート。「スタックと Change Set は何が違うのか」「オプションで何を設定できるか」「`Status` / `StatusReason` に何が返るか」に答えることが目的。

`aws cloudformation deploy` はこのリポジトリでは使っていないが、**3 手を手組みするコードは `deploy` が肩代わりしていたものの写し**なので、対等に解説する(→ §3、§10)。

このノートは記述の確からしさを 3 段階で書き分ける。

- **仕様** — 公式ドキュメントまたは aws-cli / botocore のソースに書かれていること。リンクと引用を付ける
- **傾向** — 実務でよく見る形。根拠が弱いので断定しない
- **未検証** — まだ実物で確かめていない(→ §12 に一覧)

要点は 3 つ。

1. **Change Set は「差分」ではなく「実行予約」。** 作った時点で名前が付いた実行可能な単位として AWS 側に保存され、`execute-change-set` を撃つまで何も起きない。`terraform plan` の出力(読んで捨てるテキスト)とはそこが違う。だから**作ったら実行するか消すかのどちらかが必要**で、`cfn-apply.yml` が 3 箇所で `delete-change-set` を呼んでいるのはそのため
2. **`Status` は 4 つある。** `Status`(Change Set)/ `ExecutionStatus` / `StackStatus` / `ResourceStatus` は軸が違い、混ぜると読めない。とくに**「差分ゼロ」が `Status: FAILED` として返る**のが最大の罠で、`StatusReason` の文字列を読まないと本物の異常と区別できない
3. **`aws cloudformation deploy` は 3 手をまとめる代わりに、4 つのことを暗黙にやっている。** テンプレートの S3 アップロード / 渡さなかったパラメータの `UsePreviousValue` 化 / CREATE と UPDATE の判定 / 差分ゼロを成功として黙認(AWS CLI v2 の既定)。手組みに移ると**この 4 つが全部自分の責任になる**。`cfn-apply.yml` の一見冗長なコードは、ほぼこれの肩代わり

関連ノート: [Terraform 経験者のための CloudFormation](./terraform-to-cloudformation.md) / [テンプレートの分割と置き場](./templates-and-prerequisites.md) / [ECS のタスク定義は誰が持つか](./ecs-deploy-ownership.md)

---

## 1. 全体像 — 2 つのワークフローが叩くコマンド

### `cfn-apply.yml`(反映)

| ステップ | コマンド | 呼ばれる API | 本ノート |
|---|---|---|---|
| 前提を確かめる(`:141`) | `describe-stacks` | `DescribeStacks` | §4-3, §9-3 |
| テンプレートを S3 に置く(`:194-198`) | `sts get-caller-identity` / `s3 cp` | (CloudFormation ではない) | §5-2 |
| Change Set を作る(`:241`) | `create-change-set` | `CreateChangeSet` | §5 |
| 同(`:254`) | `wait change-set-create-complete` | `DescribeChangeSet` をポーリング | §4-1, §4-2 |
| 同(`:257`) | `describe-change-set` | `DescribeChangeSet` | §6, §7 |
| 同(`:265`) | `delete-change-set` | `DeleteChangeSet` | §8-2 |
| 差分を確認する(`:290`, `:315`) | `describe-change-set` | `DescribeChangeSet` | §7 |
| 同(`:327`) | `delete-change-set` | `DeleteChangeSet` | §8-2 |
| dry run(`:346`) | `delete-change-set` | `DeleteChangeSet` | §8-2 |
| Change Set を実行する(`:362`) | `execute-change-set` | `ExecuteChangeSet` | §8-1 |
| 同(`:373`) | `wait stack-create-complete` / `stack-update-complete` | `DescribeStacks` をポーリング | §4-2 |
| 同(`:375`) | `describe-stack-events` | `DescribeStackEvents` | §9-4 |
| 結果をサマリに出す(`:389`) | `describe-stacks` | `DescribeStacks` | §9-3 |

### `cfn-destroy.yml`(撤収)

| ステップ | コマンド | 呼ばれる API | 本ノート |
|---|---|---|---|
| スタックの存在を確かめる(`:56`) | `describe-stacks`(終了コードだけ見る) | `DescribeStacks` | §4-3, §4-4 |
| 画像バケットを空にする(`:69`) | `describe-stacks --query Outputs` | `DescribeStacks` | §4-5, §9-3 |
| スタックを削除する(`:83`) | `delete-stack --role-arn` | `DeleteStack` | §9-1, §9-2 |
| 同(`:86`) | `wait stack-delete-complete` | `DescribeStacks` をポーリング | §4-2 |

**CloudFormation を叩くのはこの 2 本だけ。** `cfn-deploy.yml`(構築)は順序を表現するだけで、実際の作成と更新は `workflow_call` で `cfn-apply.yml` に委譲する(→ [ADR-0009](../../adr/0009-cfn-apply-as-the-single-cloudformation-caller.md))。

---

## 2. スタックと Change Set

### 2-1. まずスタックとは何か

**仕様:** [Managing AWS resources as a single unit with CloudFormation stacks](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/stacks.html) より。

> A stack is a collection of AWS resources that you can manage as a single unit. In other words, you can create, update, and delete a collection of resources by creating, updating, and deleting stacks.

**スタックが持っているものは 2 つ。**

1. **テンプレートから実際に作られた AWS リソース**(このリポジトリなら VPC・NAT GW・SG・ALB・WAF・ACM・ECS・RDS・S3・CloudFront・Route53・IAM ロール)
2. **その管理情報** —— 「どの論理 ID がどの実リソースに対応するか」の対応表、パラメータ、Outputs、タグ、イベント履歴、状態

**つまり Terraform の `tfstate` + そこに載っているリソース群を、1 つのオブジェクトにまとめたものがスタック。** 違いは、`tfstate` が S3 に置いたファイルなのに対し、**スタックは AWS の中にあってファイルとして触れない**こと(→ [Terraform 経験者のための CloudFormation §1](./terraform-to-cloudformation.md))。

`describe-stacks` が返すのはこの管理情報のほうで、実リソースそのものではない。

```
StackName:   nuxt-java-practice-stg
StackId:     arn:aws:cloudformation:ap-northeast-1:...:stack/nuxt-java-practice-stg/<uuid>
StackStatus: UPDATE_COMPLETE
Parameters:  [{ImageTag: abc123}, {WebDesiredCount: 1}, ...]
Outputs:     [{AppUrl: https://...}, {LoadBalancerDnsName: ...}]
```

**そして Change Set はスタックに従属する。** 単独では存在できないので、`describe-change-set` は `--stack-name` と `--change-set-name` の 2 つを要求する(ARN を渡すときは 1 つで足りる。ARN にスタック名が含まれているため)。

**仕様:** [`Welcome`](https://docs.aws.amazon.com/AWSCloudFormation/latest/APIReference/Welcome.html) は API を「Stack actions」と「Change set actions」に分けていて、後者をこう説明している。

> If you need to make changes to the running resources in a stack, you update the stack. Before making changes to your resources, you can generate a change set, which is **summary of your proposed changes**.

そしてユーザーガイド側は Change Set の実体をこう書いている。

> Change sets are **JSON-formatted documents** that summarize the changes CloudFormation will make to a stack.

**つまり Change Set が持っているのは変更の予定リストだけで、実リソースは 1 つも持たない。**

| | スタック | Change Set |
|---|---|---|
| 何を持つか | 実リソース + 管理情報 | **変更の予定リストだけ** |
| 寿命 | 永続(`delete-stack` するまで) | 一時的(実行するか消すかまで) |
| 従属関係 | 独立 | **スタックに属する** |
| 個数の関係 | 1 | **N**(1 スタックに複数作れる) |
| 状態フィールド | `StackStatus`(23 値 → §6-5) | `Status`(8 値 → §6-2)+ `ExecutionStatus`(6 値 → §6-4) |
| 引くコマンド | `describe-stacks` | `describe-change-set` |
| 消すコマンド | `delete-stack` | `delete-change-set` |
| 消したときに起きること | **実リソースが削除される** | **何も削除されない** |

**`delete-stack` と `delete-change-set` の差がいちばん分かりやすい対比。** 後者は予定リストを捨てるだけで AWS 上のものは何も減らない。`cfn-apply.yml:337` が Replacement を検出したときに `delete-change-set` を呼んでいるのは「危険な予定リストを実行可能なまま残さない」という意味で、リソースには一切触っていない(→ §8-3)。

#### スタックがあることは、リソースがあることを意味しない

ここが一番ややこしい。**`--change-set-type CREATE` で Change Set を作ると、リソースが 1 つも無いスタックが先にできる。**

```
create-change-set --change-set-type CREATE
  → スタック nuxt-java-practice-stg が誕生(StackStatus: REVIEW_IN_PROGRESS)
     スタック ID だけあって、中身は空
  → その空のスタックに紐づく Change Set ができる

execute-change-set
  → ここで初めて VPC も RDS も作られる(CREATE_IN_PROGRESS → CREATE_COMPLETE)
```

**仕様:** [`CreateChangeSet`](https://docs.aws.amazon.com/AWSCloudFormation/latest/APIReference/API_CreateChangeSet.html) の `ChangeSetType` より。

> If you create a change set for a new stack, CloudFormation creates a stack with a unique stack ID, but **no template or resources**. The stack will be in the `REVIEW_IN_PROGRESS` state until you execute the change set.

**だから `cfn-apply.yml:152` は `REVIEW_IN_PROGRESS` を `NOT_FOUND` と同じ扱いにしている。** スタックはあるがリソースが無いので、「スタックが無い」と見なした方が正しい。この扱いと `dry_run` がリソースを持たないスタックを残すことの依存関係 → §6-5、§8-2。

### 2-2. Change Set は作っても何も起きない — けれど「読んで捨てるテキスト」でもない

**仕様:** [`CreateChangeSet`](https://docs.aws.amazon.com/AWSCloudFormation/latest/APIReference/API_CreateChangeSet.html) より。

> Creates a list of changes that will be applied to a stack so that you can review the changes before executing them. ... **CloudFormation doesn't make changes until you execute the change set.**

ここで押さえたいのは「作っても何も起きない」だけではない。**Change Set は AWS 側に名前を持って保存される、実行可能なオブジェクト**である。ARN を持つ(`arn:aws:cloudformation:...:changeSet/<name>/<uuid>`)。

つまり性質はこうなる。

| | `terraform plan` の出力 | Change Set |
|---|---|---|
| 保存先 | 標準出力(`-out` を付ければローカルのファイル) | **AWS 側**(スタックに紐づく) |
| 名前 | なし | **ある**(スタック内で一意。128 文字まで) |
| 放置したとき | 消えるだけ | **残る。** 実行可能なまま溜まる |
| 実行 | `terraform apply` に食わせる | `execute-change-set` に名前を渡す |

**「残る」が実務上の帰結を 2 つ生む。**

1. **作ったら、実行するか `delete-change-set` するかのどちらかが必要。** `cfn-apply.yml` は差分ゼロ・Replacement 検出・dry run の 3 箇所で明示的に消している(→ §8-3)
2. **1 つ実行すると、他は全部消える。仕様:** [`ExecuteChangeSet`](https://docs.aws.amazon.com/AWSCloudFormation/latest/APIReference/API_ExecuteChangeSet.html) より

   > When you execute a change set, CloudFormation deletes all other change sets associated with the stack because they aren't valid for the updated stack.

### 2-3. だから 3 手に分かれる

`terraform plan` → `apply` の 2 手が、CloudFormation では 3 手になる。

```bash
# 1. 作る(この時点では何も起きない)
aws cloudformation create-change-set --stack-name X --change-set-name Y --template-url ... --parameters ...
# 2. 見る
aws cloudformation describe-change-set --stack-name X --change-set-name Y
# 3. 実行する
aws cloudformation execute-change-set --stack-name X --change-set-name Y
```

`cfn-apply.yml` はこの 3 手をそれぞれ別のステップに割り当てていて、**2 手目と 3 手目の間に判定を 2 つ挟む**のがこのワークフローの設計上の勘所になっている。

```
create-change-set
  → wait change-set-create-complete
  → describe-change-set ─┬→ Status=FAILED かつ「差分なし」    → delete して正常終了
                         └→ Status=CREATE_COMPLETE
                              → describe-change-set(差分をサマリに出す)
                                 ├→ Replacement=True が含まれる → delete して失敗
                                 ├→ dry_run                     → delete して正常終了
                                 └→ それ以外                     → execute-change-set
```

**`aws cloudformation deploy` はこの 3 手をまとめてやる。** `--no-execute-changeset` を付ければ 2 手目で止まる(→ §10-2)。

### 2-4. `terraform plan` とは比較対象が違う

Change Set が比べているのは **前回のテンプレートと今回のテンプレート**で、実リソースは読まない。`terraform plan` は実リソースを読む。この対比と、そこから派生する `ignore_changes` の話は [Terraform 経験者のための CloudFormation §5](./terraform-to-cloudformation.md) に置いてある。実リソースを読ませるモード(`--deployment-mode REVERT_DRIFT`)は §5-8 で扱う。

---

## 3. スタックを更新する 3 つの道

`aws cloudformation` でスタックの中身を変える手段は 3 つある。**上から下へ「抽象度が下がり、制御できることが増える」**並びになっている。

| | `deploy` | `create-stack` / `update-stack` | Change Set の 3 手 |
|---|---|---|---|
| コマンド数 | 1 | 1 | 3(+ `wait` と `describe`) |
| 差分を実行前に見られるか | **`--no-execute-changeset` を付けたときだけ** | ✕ | ○ |
| 差分を見たうえで実行できるか | ✕(見たら止まる。実行は別途手で) | — | **○** |
| 51,200 バイト超のテンプレート | `--s3-bucket` を渡せば自動でアップロード | ✕(`--template-url` を自分で用意) | ✕(自分で用意) |
| 渡さなかったパラメータ | 自動で `UsePreviousValue` | 自動で前回値(`update-stack`) | **自分で組む** |
| CREATE / UPDATE の判定 | 自動 | コマンドが別 | **自分で判定** |
| `--deployment-mode REVERT_DRIFT` | ✕(オプションが無い) | ✕ | ○ |
| `--on-stack-failure` | ✕ | `create-stack --on-failure` のみ | ○ |
| 差分ゼロのとき | v2 は成功、v1 は失敗(→ §10-5) | `update-stack` は API エラー | `Status: FAILED` で返る(→ §6-3) |

**このリポジトリが 3 手を選んだ理由は「差分を見たうえでそのまま実行したい」の 1 点。** `deploy --no-execute-changeset` は差分を見せて**止まる**ので、そこから実行するには結局 `execute-change-set` を自分で撃つことになり、`deploy` の利点が消える。既存環境を触るワークフローなので「何が変わるか」をジョブサマリに必ず残したい、という要件がここに効いている(→ [ADR-0009](../../adr/0009-cfn-apply-as-the-single-cloudformation-caller.md))。

**代償が §5 以降の長さそのもの。** `deploy` が暗黙にやっていた 4 つを自分で書くことになる。誰が肩代わりしているかの対応表は §10-4 にまとめた。

**`create-stack` / `update-stack` をこのリポジトリで使わない理由**は 2 つ。差分を出す隙が無いこと、そしてテンプレートに `Transform`(マクロ)を書いた場合に Change Set 経由でないと展開されないこと。**仕様:** [公式のコマンド例](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/service_code_examples.html)も「When you create a stack from a template that includes transforms, you must use a change set」と書いている。

---

## 4. CLI 層の話 — `wait` / `--query` / 終了コード

ここは API ではなく **aws-cli 側**の機能。4 つのコマンドで同じ仕組みを使うので、個別のコマンドより先に置く。

### 4-1. `wait` は 30 秒 × 120 回 = 60 分で諦める

`aws cloudformation wait <name>` は、**botocore に定義された waiter を実行して終了コードを返すだけ**のサブコマンド。ポーリング間隔と回数は botocore の [waiter 定義 JSON](https://github.com/boto/botocore/blob/develop/botocore/data/cloudformation/2010-05-15/waiters-2.json) に書かれている。

| waiter | `delay` | `maxAttempts` | 最大待ち時間 |
|---|---|---|---|
| `stack-create-complete` | 30 秒 | 120 | 60 分 |
| `stack-update-complete` | 30 秒 | 120 | 60 分 |
| `stack-delete-complete` | 30 秒 | 120 | 60 分 |
| `change-set-create-complete` | 30 秒 | 120 | 60 分 |
| `stack-exists` | 5 秒 | 20 | 100 秒 |

**これが `cfn-apply.yml:116` の `timeout-minutes: 75` の根拠。** waiter が 60 分粘るので、ジョブの制限時間はそれを待ちきれる値にしておかないと、待っている途中で GitHub 側に切られる。

**待ちきれなかったときは失敗として返る。** 60 分でスタックが終わらなければ waiter は非ゼロで終わり、`cfn-apply.yml:373` の `if !` がそれを拾ってイベントの表を出す。**スタック側の操作は止まらない**(AWS 側で走り続ける)点に注意。CI が失敗しても反映は進んでいることがある。

### 4-2. 4 つの waiter が何を成功とみなすか

waiter 定義の `acceptors` が「この状態になったら成功 / 失敗」を列挙している。ここを読むと、**同じ「完了待ち」でも中身がかなり違う**ことが分かる。

**`change-set-create-complete`**(`DescribeChangeSet` をポーリング)

| 条件 | 判定 |
|---|---|
| `Status == CREATE_COMPLETE` | 成功 |
| `Status == FAILED` | **失敗** |
| `ValidationError` が返る | 失敗 |

**差分ゼロは `Status: FAILED` なので、この waiter は失敗として返る**(→ §6-3)。`cfn-apply.yml:254-255` が `|| true` を付けているのはこのため。異常か正常かの判定を waiter に任せず、後段の `describe-change-set` で `StatusReason` を読んで分けている。

**`stack-update-complete`**(`DescribeStacks` をポーリング)

| 条件 | 判定 |
|---|---|
| `UPDATE_COMPLETE` | 成功 |
| `UPDATE_FAILED` / `UPDATE_ROLLBACK_FAILED` / `UPDATE_ROLLBACK_COMPLETE` | **失敗** |
| `ValidationError` | 失敗 |

**`UPDATE_ROLLBACK_COMPLETE` を失敗として扱う**のが重要。ロールバックが完全に成功しても「更新は通らなかった」ので失敗になる。`cfn-apply.yml:374` のエラーメッセージが「失敗、またはロールバックしました」と 2 つ並べているのはこの挙動に対応している。

なお `UPDATE_COMPLETE_CLEANUP_IN_PROGRESS`(置換された古いリソースの掃除中)はどの acceptor にも当たらないので、waiter はポーリングを続ける。

**`stack-create-complete`**(`DescribeStacks` をポーリング)

| 条件 | 判定 |
|---|---|
| `CREATE_COMPLETE` | 成功 |
| `UPDATE_COMPLETE` / `UPDATE_IN_PROGRESS` / `UPDATE_COMPLETE_CLEANUP_IN_PROGRESS` / `UPDATE_FAILED` / `UPDATE_ROLLBACK_*`(5 種) | **成功** |
| `CREATE_FAILED` / `DELETE_COMPLETE` / `DELETE_FAILED` / `ROLLBACK_FAILED` / `ROLLBACK_COMPLETE` | 失敗 |
| `ValidationError` | 失敗 |

**`UPDATE_*` を全部成功にしているのが奇妙に見えるが、「作成は既に済んでいる」という意味では筋が通る。** ただし `stack-create-complete` を UPDATE の完了待ちに流用すると `UPDATE_ROLLBACK_COMPLETE` を成功と誤判定するので、**CREATE と UPDATE で waiter を出し分ける必要がある**。`cfn-apply.yml:365-371` がそれをやっている。`aws cloudformation deploy` も内部で同じ出し分けをしている(→ §10-3)。

**`stack-delete-complete`**(`DescribeStacks` をポーリング)

| 条件 | 判定 |
|---|---|
| `DELETE_COMPLETE` | 成功 |
| **`ValidationError` が返る** | **成功** |
| `DELETE_FAILED` / `CREATE_FAILED` / `ROLLBACK_FAILED` / `UPDATE_ROLLBACK_IN_PROGRESS` / `UPDATE_ROLLBACK_FAILED` / `UPDATE_ROLLBACK_COMPLETE` / `UPDATE_COMPLETE` | 失敗 |

**この waiter だけ `ValidationError` を成功として扱う。** 他の 3 つは失敗にしている。理由は §4-4 の仕様と繋がっている ——「削除が終わるとスタック名では引けなくなる」ので、`ValidationError`(= もう無い)は削除完了の証拠になる。

### 4-3. `describe-stacks` は存在しないスタックで**エラー**になる(空ではない)

**仕様:** [`DescribeStacks`](https://docs.aws.amazon.com/AWSCloudFormation/latest/APIReference/API_DescribeStacks.html) より。

> If the stack doesn't exist, a `ValidationError` is returned.

つまり「`Stacks` が空配列で返る」のではなく**リクエストが失敗する**。CLI の終了コードは 0 にならない。

**仕様:** [Command line return codes](https://docs.aws.amazon.com/cli/latest/userguide/cli-usage-returncodes.html) より、AWS CLI v2 の該当コードは **254**。

> 254 — The command successfully parsed and a request made to the specified service but the service returned an error.

(AWS CLI v1 では 255 だった。v2 で 252 / 253 / 254 が追加された。)

**だから両方のワークフローで「存在確認」が `if` の形になる。**

```bash
# cfn-apply.yml:141 — JSON を取りつつ、失敗なら NOT_FOUND とみなす
if json=$(aws cloudformation describe-stacks --stack-name "$stack" --output json 2>/dev/null); then
  status=$(echo "$json" | jq -r '.Stacks[0].StackStatus')
else
  status=NOT_FOUND
fi
```

```bash
# cfn-destroy.yml:56 — 出力は捨てて終了コードだけ見る
if aws cloudformation describe-stacks --stack-name "nuxt-java-practice-$ENV_NAME" >/dev/null 2>&1; then
```

`2>/dev/null` が付いているのは、**存在しないのは想定内なのでエラーメッセージをログに出したくない**から。`set -euo pipefail` が効いていても `if` の条件部は `-e` の対象外なので、ここで落ちることはない。

`aws cloudformation deploy` も内部で同じことをしている。**仕様:** aws-cli の [`deployer.py`](https://github.com/aws/aws-cli/blob/develop/awscli/customizations/cloudformation/deployer.py) の `has_stack` より。

```python
except botocore.exceptions.ClientError as e:
    # If a stack does not exist, describe_stacks will throw an
    # exception. Unfortunately we don't have a better way than parsing
    # the exception msg to understand the nature of this exception.
    msg = str(e)
    if "Stack with id {0} does not exist".format(stack_name) in msg:
```

エラーメッセージの文字列一致で判定している。コメントに「文字列をパースするしか手が無い」と書いてあるあたりが、この API の作りをよく表している。

### 4-4. 消えたスタックは名前で引けない

**仕様:** [`DescribeStacks`](https://docs.aws.amazon.com/AWSCloudFormation/latest/APIReference/API_DescribeStacks.html) の `StackName` より。

> The name or the unique stack ID that's associated with the stack, which aren't always interchangeable:
> * Running stacks: You can specify either the stack's name or its unique stack ID.
> * **Deleted stacks: You must specify the unique stack ID.**

[`DeleteStack`](https://docs.aws.amazon.com/AWSCloudFormation/latest/APIReference/API_DeleteStack.html) 側にも同じことが書いてある。

> Deleted stacks don't show up in the `DescribeStacks` operation if the deletion has been completed successfully.

**これが 2 つのことを成立させている。**

1. **`cfn-destroy.yml` を 2 回流しても壊れない。** 1 回目の削除が終わると名前では引けなくなるので、2 回目の存在確認が `false` になって「何もしません」で終わる
2. **`stack-delete-complete` waiter が `ValidationError` を成功とみなせる**(→ §4-2)

逆に言うと、**削除済みスタックの履歴を CLI で見たいときはスタック ID(ARN)が要る**。名前しか手元に無いと辿れない。`list-stacks --stack-status-filter DELETE_COMPLETE` で ID を拾うのが入口になる。

### 4-5. `--query` と `--output` — 空のとき `None` になる

`cfn-destroy.yml:69-71` はこう書いている。

```bash
bucket=$(aws cloudformation describe-stacks --stack-name "nuxt-java-practice-$ENV_NAME" \
  --query "Stacks[0].Outputs[?OutputKey=='ImageBucketName'].OutputValue | [0]" --output text)
if [ -n "$bucket" ] && [ "$bucket" != "None" ]; then
```

読み方は 3 段。

- **`Outputs[?OutputKey=='ImageBucketName']`** — JMESPath のフィルタ式。条件に合う要素だけの**配列**が返る
- **`.OutputValue`** — 配列の各要素からその属性を取る。結果も**配列**(要素 1 つの配列)
- **`| [0]`** — パイプで配列の 0 番目を取り出してスカラーにする。**これが無いと `--output text` はタブ区切りで並べて出す**ので、値が 1 つのときも配列として扱われて扱いにくい

`--output` の使い分けも 2 つのワークフローで分かれている。

| 値 | 使用箇所 | 用途 |
|---|---|---|
| `json` | `cfn-apply.yml:141`, `:258`, `:291`, `:389` | `jq` に渡して複数の値を取り出す |
| `text` | `cfn-destroy.yml:70`, `cfn-apply.yml:194` | シェル変数に 1 つの値を入れる |
| `table` | `cfn-apply.yml:317`, `:377` | ジョブサマリに罫線付きの表として貼る |

**未検証(公式に明記が見つからない):** `--output text` は JMESPath の結果が null のとき文字列 `None` を出力する(Python の `None` の repr)。`cfn-destroy.yml:71` が `[ "$bucket" != "None" ]` を持っているのは、実地で踏んだ証拠と読める。**空文字判定(`-n`)だけでは足りない**のがポイント。

JMESPath の構文そのものは CloudFormation の話ではないので、ここでは使っている式を読み解くのに必要な分だけにする。

---

## 5. `create-change-set`

### 5-1. 全オプション

**仕様:** [`CreateChangeSet` API リファレンス](https://docs.aws.amazon.com/AWSCloudFormation/latest/APIReference/API_CreateChangeSet.html)。CLI のオプション名は API のパラメータ名をケバブケースにしたもの。

| オプション | 必須 | このリポジトリ | 内容 |
|---|---|---|---|
| `--stack-name` | ○ | ○ | 名前または ID(ARN) |
| `--change-set-name` | ○ | ○ | スタック内で一意。英数字とハイフンのみ、英字始まり、128 文字まで |
| `--template-body` | △ | ✕ | テンプレート本文。**51,200 バイトまで** → §5-2 |
| `--template-url` | △ | **○** | S3 または SSM ドキュメントの URL。**1 MB まで** → §5-2 |
| `--use-previous-template` | △ | ✕ | 前回のテンプレートを再利用 → §5-2 |
| `--parameters` | | ○ | パラメータの配列 → §5-3 |
| `--capabilities` | | ○ | `CAPABILITY_IAM` / `CAPABILITY_NAMED_IAM` / `CAPABILITY_AUTO_EXPAND` → §5-4 |
| `--role-arn` | | ○ | CloudFormation が引き受けるサービスロール → §5-5 |
| `--tags` | | ○ | スタックのタグ。最大 50 個 → §5-6 |
| `--change-set-type` | | ○ | `CREATE` / `UPDATE` / `IMPORT`。既定は `UPDATE` → §5-7 |
| `--deployment-mode` | | ✕ | `REVERT_DRIFT` のみ → §5-8 |
| `--on-stack-failure` | | ✕ | `ROLLBACK` / `DELETE` / `DO_NOTHING` → §5-9 |
| `--include-nested-stacks` | | ✕ | ネストスタックも差分に含める。既定 `false` → §5-10 |
| `--import-existing-resources` | | ✕ | 既存リソースを自動でインポートする |
| `--resources-to-import` | | ✕ | インポートするリソースの明示リスト。最大 200 個 |
| `--resource-types` | | ✕ | 触れるリソース型を絞る。**`--capabilities` と同時指定できない** |
| `--rollback-configuration` | | ✕ | ロールバックのトリガ(CloudWatch アラーム)と監視時間 |
| `--notification-arns` | | ✕ | イベントを流す SNS トピック。最大 5 個 |
| `--description` | | ✕ | 説明文。1024 文字まで |
| `--client-token` | | ✕ | 再送時の冪等キー。128 文字まで |

**`--capabilities` と `--resource-types` が排他**なのは API リファレンスに明記されている(「Only one of the `Capabilities` and `ResourceType` parameters can be specified.」)。IAM リソースを含むテンプレートでリソース型を絞りたい、はできない。

以下、掘るものを順に。

### 5-2. テンプレートの渡し方は 3 択

**仕様:** 3 つのうち**ちょうど 1 つ**を指定する(「You must specify only one of the following parameters」)。

| 指定 | サイズ上限 | 置き場所 |
|---|---|---|
| `--template-body` | **51,200 バイト** | リクエストに直接載せる |
| `--template-url` | **1 MB** | S3 バケット または Systems Manager ドキュメント |
| `--use-previous-template` | — | スタックが持っている前回のテンプレート |

`--template-url` の制約は API リファレンスにこう書かれている。

> The URL must point to a template (max size: 1 MB) that's located in an Amazon S3 bucket or a Systems Manager document. ... The location for an Amazon S3 bucket must start with `https://`. **URLs from S3 static websites are not supported.**

**このリポジトリのテンプレートは 51,200 バイトを超えているので、`--template-url` しか選べない。** だから `cfn-apply.yml` には「テンプレートを S3 に置く」ステップがある。バケットは手動管理の常駐リソース(→ [ADR-0008](../../adr/0008-template-bucket-as-resident-resource.md))。

**GitHub の raw URL は渡せない。** S3 か SSM ドキュメントに限られると明記されている。SSM ドキュメントを使う場合の書式は `--template-url "ssm-doc://arn:aws:ssm:<region>:<account>:document/<name>"`。

`--use-previous-template` を使う場面は「テンプレートは変えずにパラメータだけ変える」更新。このリポジトリは params もテンプレートも Git から毎回渡すので使っていない。なお API リファレンスは `AWS::LanguageExtensions` transform を使うテンプレートでは**これを避けろ**と書いている(パラメータの新しい値や SSM パラメータの更新が正しく適用されないため)。

サイズ上限やバケットを持つ理由の詳細 → [テンプレートの分割と置き場](./templates-and-prerequisites.md)。

### 5-3. `--parameters` — 3 つの書き方

**仕様:** [`Parameter` データ型](https://docs.aws.amazon.com/AWSCloudFormation/latest/APIReference/API_Parameter.html)。1 要素に書けるのは次の 4 つ。

| キー | 意味 |
|---|---|
| `ParameterKey` | テンプレートの `Parameters` のキー |
| `ParameterValue` | 渡す値 |
| `UsePreviousValue` | `true` にすると**今スタックに入っている値をそのまま使う** |
| `ResolvedValue` | (読み取り専用)SSM 型のパラメータが実際に解決された値。`describe-*` の出力に出る |

**`ParameterValue` と `UsePreviousValue` は排他。** そして `CREATE` では `UsePreviousValue` が使えない(前の値が存在しないため)。`cfn-apply.yml:218-225` の分岐がそれを表している。

```bash
if [ -n "$IMAGE_TAG" ]; then
  image=$(jq -n --arg v "$IMAGE_TAG" '{ParameterKey: "ImageTag", ParameterValue: $v}')
elif [ "$CS_TYPE" = "CREATE" ]; then
  echo "::error::新規作成では image_tag を省略できません(UsePreviousValue にできる前の値が無い)"
  exit 1
else
  image='{"ParameterKey": "ImageTag", "UsePreviousValue": true}'
fi
```

**落とし穴が 2 つ。**

1. **テンプレートの全パラメータを埋めなければならない。** 値を渡さず `Default` も無いパラメータが 1 つでもあると「must have values」で落ちる。`deploy` はこれを自動で `UsePreviousValue` に埋めてくれる(→ §10-3)
2. **同じ `ParameterKey` を 2 つ持つ配列は弾かれる。** `cfn-apply.yml:229-238` の jq が `web_desired_count` を「追記」ではなく「既存要素の置換」として書いているのはこのため

```bash
# WebDesiredCount は params の中に既にあるので、値を書き換える
'($p[0]
   | if $desired == "" then .
     else map(if .ParameterKey == "WebDesiredCount" then .ParameterValue = $desired else . end)
     end)
 + [$image, {ParameterKey: "BasicAuthCredential", ParameterValue: $cred}]'
```

### 5-4. `--capabilities` の 3 値

**仕様:** 有効値は `CAPABILITY_IAM | CAPABILITY_NAMED_IAM | CAPABILITY_AUTO_EXPAND`。

| 値 | いつ必要か |
|---|---|
| `CAPABILITY_IAM` | IAM リソース(`AWS::IAM::Role` など 8 型)を含むとき |
| `CAPABILITY_NAMED_IAM` | 上記のうち**カスタム名を付けている**とき。この場合は `CAPABILITY_IAM` では通らない |
| `CAPABILITY_AUTO_EXPAND` | マクロ(`AWS::Include` / `AWS::Serverless` など)を含むテンプレートを**Change Set を経由せず**直接作成・更新するとき |

指定が足りないと `InsufficientCapabilities` エラーになる。

**`cfn-apply.yml:247` が `CAPABILITY_NAMED_IAM` を渡しているのは、テンプレートが名前付きの IAM ロールを作るから。** IAM リソースを含む対象は API リファレンスに 8 型が列挙されている(`AWS::IAM::AccessKey` / `Group` / `InstanceProfile` / `ManagedPolicy` / `Policy` / `Role` / `User` / `UserToGroupAddition`)。

**`CAPABILITY_AUTO_EXPAND` について、公式は Change Set では効かないと書いている。**

> **Note:** This capacity doesn't apply to creating change sets, and specifying it when creating change sets has no effect.
>
> If you want to create a stack from a stack template that contains macros *and* nested stacks, you must create or update the stack directly from the template using the `CreateStack` or `UpdateStack` action, and specifying this capability.

つまり **`create-change-set` に `CAPABILITY_AUTO_EXPAND` を渡しても無視される。** マクロは Change Set の作成時点で展開されるので、そもそも「未展開のまま作ってしまう危険」が無い、というのが理屈。この capability は `create-stack` / `update-stack` を直接叩くときのためのもの。

**未検証:** [テンプレートの分割と置き場](./templates-and-prerequisites.md) は `AWS::Include` の代償として「`CAPABILITY_AUTO_EXPAND` の指定も要る」と書いているが、上の引用に照らすと **Change Set 経由なら不要**の可能性が高い。`create-stack` を使う場合には要る。実際に `AWS::Include` を入れて `create-change-set` を流すまで確定しない。

### 5-5. `--role-arn` — ロールが 2 段になっている理由

**仕様:** `RoleARN` の説明より。

> The Amazon Resource Name (ARN) of an IAM role that CloudFormation assumes when executing the change set. CloudFormation uses the role's credentials to make calls on your behalf. **CloudFormation uses this role for all future operations on the stack.** ... If you don't specify a value, CloudFormation uses the role that was previously associated with the stack. If no role is available, CloudFormation uses a temporary session that is generated from your user credentials.

このリポジトリはロールを 2 つ使う。

| ロール | 誰が引き受けるか | 何ができるか |
|---|---|---|
| `AWS_CFN_DEPLOY_ROLE_ARN` | **GitHub Actions**(OIDC で AssumeRole) | `cloudformation:*` と S3・ECS の限定的な権限 |
| `AWS_CFN_SERVICE_ROLE_ARN` | **CloudFormation**(`--role-arn` で渡す) | `AdministratorAccess`(実リソースを作る) |

**これが効くのは「実行者の資格情報が漏れても、テンプレートに書かれていないことはできない」という性質。** Actions のロールは VPC も RDS も直接作れない。作れるのは CloudFormation に頼むことだけで、CloudFormation はテンプレートに書かれた通りにしか動かない。権限の詳細 → [手順書 §2](../../infrastructure/cloudformation-operations.md)。

**「CloudFormation uses this role for all future operations on the stack」が重要。** 一度渡すとスタックに紐づくので、以降の操作で省略しても同じロールが使われる(→ §9-2 で `delete-stack` の話に繋がる)。

### 5-6. `--tags` — 省略するとスタックのタグが消える

**仕様:** `Tags` の説明より。

> Key-value pairs to associate with this stack. CloudFormation also propagates these tags to resources in the stack. You can specify a maximum of 50 tags.

**「今あるタグに追加する」ではなく「これがタグの全体である」という意味。** 渡さなければ空のリストとして扱われ、既存のタグが消える。`UpdateStack` と同じ挙動。

だから `cfn-apply.yml:248` は毎回 3 つを渡している。

```bash
--tags Key=Project,Value=nuxt-java-practice Key=Env,Value=${{ inputs.env }} Key=ManagedBy,Value=cloudformation
```

**スタックのタグは全リソースに自動で伝播する**(Terraform の provider `default_tags` に相当)ので、消えると影響範囲が広い。`aws cloudformation deploy` も同じ性質を持っていて、ソースを見ると `Tags` を無条件に `kwargs` に入れている(`--tags` を省略すると空配列が渡る)。

### 5-7. `--change-set-type` — `CREATE` / `UPDATE` / `IMPORT`

**仕様:** 有効値は `CREATE | UPDATE | IMPORT`、**既定は `UPDATE`**。`CREATE` が空のスタックを先に作ることは §2-1 で扱った。

> If you create a change set for a new stack, CloudFormation creates a stack with a unique stack ID, but no template or resources. The stack will be in the `REVIEW_IN_PROGRESS` state until you execute the change set.
>
> By default, CloudFormation specifies `UPDATE`. **You can't use the `UPDATE` type to create a change set for a new stack or the `CREATE` type to create a change set for an existing stack.**

| 値 | 用途 |
|---|---|
| `CREATE` | まだ存在しないスタック。作った時点で `REVIEW_IN_PROGRESS` のスタックが先にでき(リソースは 1 つも無い)、`execute-change-set` して初めてリソースができる |
| `UPDATE` | 既存スタックの更新 |
| `IMPORT` | 既存リソースの取り込み(→ [Terraform 経験者のための CloudFormation §8](./terraform-to-cloudformation.md)) |

**この 3 択が排他なので、「スタックがあるか」を先に判定しないと `create-change-set` を撃てない。** `cfn-apply.yml:151-171` の `case` 文がその判定で、`deploy` が内部でやっていることの写しになっている(→ §10-3)。

**`--change-set-type` と `--deployment-mode` は名前が似ているが軸が違う。**

| オプション | 何を指定するか | 値 | 既定 |
|---|---|---|---|
| `--change-set-type` | **何のための変更セットか** | `CREATE` / `UPDATE` / `IMPORT` | `UPDATE` |
| `--deployment-mode` | **差分をどう出すか** | `REVERT_DRIFT` | 指定なし(前回テンプレートとの 2 者比較) |

前者は「作る / 更新する / 取り込む」という**用途**、後者は「実物を読むか読まないか」という**比較の仕方**で、直交している。

### 5-8. `--deployment-mode REVERT_DRIFT` — 実物を読ませる唯一の手段

**仕様:** `DeploymentMode` の説明より。

> `REVERT_DRIFT` – Creates a drift-aware change set that brings actual resource states in line with template definitions. **Provides a three-way comparison between actual state, previous deployment state, and desired state.**

ドリフトは既存スタックにしか存在しないので `UPDATE` と組む。**未検証:** `CREATE` と併用したときエラーになるか黙って無視されるかは公式に記載がない。

#### `deploy` では実物を読ませられない

**仕様: `aws cloudformation deploy` に実リソースを読ませる設定は無い。** オプション自体が存在しないので、設定では切り替えられない。根拠は [`deploy` のリファレンス](https://docs.aws.amazon.com/cli/latest/reference/cloudformation/deploy.html)の Synopsis が全オプションを列挙していて、そこに無いこと。

```
deploy --template-file --stack-name [--s3-bucket] [--force-upload] [--s3-prefix]
[--kms-key-id] [--parameter-overrides] [--capabilities] [--no-execute-changeset]
[--disable-rollback | --no-disable-rollback] [--role-arn] [--notification-arns]
[--fail-on-empty-changeset | --no-fail-on-empty-changeset] [--tags]     ← ここまでが全部
```

`update-stack` にも無い。**`deploy` は内部で Change Set を作るが、既定モードで作るしか選べない。**

取れる道は 3 つ。

| 道 | `deploy` を保てるか | 備考 |
|---|---|---|
| `create-change-set` を手組みして `--deployment-mode REVERT_DRIFT` を足す | ✕ 置き換えになる | このリポジトリの `cfn-apply.yml` は既に create → describe → execute を自分で書いているので、**1 行足すだけで済む** |
| `deploy` の前に `detect-stack-drift` を別ステップで走らせる | ○ | 差分表示は変わらないので、人が 2 つを見比べることになる |
| コンソールで「Drift aware change set」を選ぶ | — | CI では選択肢にならない |

#### 必要になる IAM 権限

**仕様:** [drift 検出のドキュメント](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/using-cfn-stack-drift.html)より。

> In order to successfully perform drift detection on a stack, a user must have the following permissions:
> + Read permission for each resource that supports drift detection included in the stack.

このリポジトリの 2 つのロールで見ると:

- **CloudFormation サービスロール** — `AdministratorAccess` なので満たす(→ [手順書 §2-1](../../infrastructure/cloudformation-operations.md))
- **GitHub Actions が引き受けるロール** — `cloudformation:*` と S3・ECS の限定的な権限だけで、**リソースの `Describe*` 系を持たない**(同 §2-2 の `--policy-name DeployStack`)

**未検証:** 変更セット作成時の実状態の読み取りが、スタックに紐づくサービスロールで行われるのか、呼び出し側の資格情報で行われるのか、公式ドキュメントに明記がない。前者なら追加不要、後者なら Actions のロールに `ec2:Describe*` / `rds:Describe*` / `ecs:Describe*` / `elasticloadbalancing:Describe*` などが要る。**判別するには、権限を足さずに一度流して `ResourceDriftStatus` が全部 `NOT_CHECKED` で返るかを見るのが早い。**

#### 副作用 — 「見るだけ」のモードではない

`REVERT_DRIFT` は実行すると外部変更を積極的に巻き戻す。**仕様:** [drift-aware change sets](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/drift-aware-change-sets.html) より。

> Drift-aware change sets will **update the actual state of all stack resources to match the desired state, even if a resource was not explicitly changed in the template.**

ただし ECS のタスク数は保護される。ここは [ADR-0007](../../adr/0007-app-deploy-inside-cloudformation.md) の未検証項目に直結する。

> Drift-aware change sets recognize that drift is expected for **AWS-managed properties** and leave their actual value untouched if you have not modified the property in their template. Top examples ... Using the `AWS::ApplicationAutoScaling::ScalableTarget` resource to enable auto-scaling for properties such as ... **the desired count of an Amazon ECS cluster**

他に効く制約が 4 つ。

- **書き込み専用プロパティ**(パスワード・シークレット)は実物ではなく前回デプロイ値と比較される
- **不変(immutable)プロパティのドリフトは戻せない**(→ §7-2 の `Replacement`)
- **非対応のリソース型は従来の 2 者比較にフォールバックする**(除外リストが 28 型ある。この構成で使っている型は含まれていない)
- **テンプレートに書いていないタグキーは触らない**(ABAC との衝突を避ける仕様)

#### `describe-change-set` の出力が変わる

三方比較になるので、返ってくる項目が増える。**仕様:** [`DescribeChangeSet`](https://docs.aws.amazon.com/AWSCloudFormation/latest/APIReference/API_DescribeChangeSet.html) と [`ResourceChange`](https://docs.aws.amazon.com/AWSCloudFormation/latest/APIReference/API_ResourceChange.html) で、いずれも「Only present for drift-aware change sets」と注記されている。

| 項目 | 場所 | 内容 |
|---|---|---|
| `DeploymentMode` | トップレベル | `REVERT_DRIFT`(付けて作ったときだけ present) |
| `StackDriftStatus` | トップレベル | `DRIFTED` / `IN_SYNC` / `UNKNOWN` / `NOT_CHECKED` |
| `ResourceDriftStatus` | `ResourceChange` | `IN_SYNC` / `MODIFIED` / `DELETED` / `NOT_CHECKED` / `UNKNOWN` / **`UNSUPPORTED`**(型が実状態比較に非対応) |
| `ResourceDriftIgnoredAttributes` | `ResourceChange` | ドリフトを戻さなかった属性と、その理由 |
| `BeforeContext` / `PreviousDeploymentContext` | `ResourceChange` | before の値がどこから来たか |

`cfn-apply.yml` の差分サマリは `.Changes[].ResourceChange` の 4 項目しか見ていないので、これらを表に出したいなら jq の追記が要る。

### 5-9. `--on-stack-failure` と `ROLLBACK_COMPLETE` のスタック

**仕様:** `OnStackFailure` の説明より。

| 値 | 挙動 |
|---|---|
| `ROLLBACK` | 作成が失敗したらロールバックする。`ExecuteChangeSet` の `DisableRollback=false` と等価 |
| `DO_NOTHING` | 何もしない。`DisableRollback=true` と等価 |
| `DELETE` | **失敗したらスタックを削除する。`--change-set-type CREATE` のときだけ有効。** 削除も失敗したらスタックは `DELETE_FAILED` になる |

> Determines what action will be taken if stack creation fails. If this parameter is specified, the `DisableRollback` parameter to the `ExecuteChangeSet` API operation **must not be specified**.

**`cfn-apply.yml` はこれを指定していない。** その帰結が precheck の 1 分岐になっている。

```bash
# cfn-apply.yml:162-164
ROLLBACK_COMPLETE)
  echo "::error::状態が $status です。作成に失敗したスタックは更新できません。cfn-destroy.yml で削除してから建て直してください"
  exit 1 ;;
```

初回構築(`CREATE`)が途中で失敗すると、ロールバックが走って `ROLLBACK_COMPLETE` のスタックが残る。

**これは `REVIEW_IN_PROGRESS` のスタックとは中身が違う。** `ROLLBACK_COMPLETE` は一度リソースが作られてから削除された跡で、`REVIEW_IN_PROGRESS` は一度も作られていない。そして**ロールバックで消えるのは CloudFormation が消せるものだけ**なので、`DeletionPolicy: Retain` を付けたリソースと削除に失敗したリソースは AWS 上に残る(管理外の孤児になる)。「リソースが無いスタック」と言い切れるのは `REVIEW_IN_PROGRESS` のほうだけ。

**そして `ROLLBACK_COMPLETE` のスタックは更新できない**(CloudFormation が `ValidationError` を返す。CDK のドキュメントも「The stack failed its previous deployment, and is in a non-retryable state. Go into the CloudFormation console, delete the stack, and retry」と案内している)。だから手で消してから建て直すことになる。

**`--on-stack-failure DELETE` を渡していれば、この分岐は要らなかった。** 失敗したスタックは自動で消えるので、次の構築がそのまま通る。

指定していない理由は 2 つ考えられる。**傾向:** ①失敗したスタックが残っていると、`describe-stack-events` で「なぜ失敗したか」を後から読める。消えると調査の手がかりも消える。②`DELETE` は `CREATE` のときだけ有効なので、CREATE と UPDATE を同じコードで扱っている `cfn-apply.yml` では条件付きで足すことになり、コードが増える。

**未検証:** 省略したときの既定値が `ROLLBACK` である、と API リファレンスは明記していない(有効値の列挙だけ)。`ROLLBACK_COMPLETE` になる観察はこのリポジトリの precheck が前提にしているが、実測はしていない。

### 5-10. 掘らないオプション

- **`--include-nested-stacks`** — 既定 `false`。ネストスタックの差分も含めるかどうか。このリポジトリはテンプレートを分割していないので使わない(→ [テンプレートの分割と置き場](./templates-and-prerequisites.md))。ただし `delete-change-set` の挙動に関係する(→ §8-2)
- **`--resource-types`** — 「このリソース型だけ触ってよい」という絞り込み。IAM のポリシー条件キーとしても使える。`--capabilities` と排他
- **`--rollback-configuration`** — CloudWatch アラームをトリガに、更新後の監視期間中にアラームが鳴ったらロールバックする仕組み。監視期間は最大 180 分
- **`--notification-arns`** — スタックイベントを SNS に流す。最大 5 個。このリポジトリはジョブサマリで足りている
- **`--client-token`** / **`--description`** — 冪等キーと説明文。`deploy` は `--description` に `"Created by AWS CLI at <ISO8601> UTC"` を自動で入れている
- **`--import-existing-resources`** / **`--resources-to-import`** — 既存リソースの取り込み。前者はカスタム名を持つリソースだけが対象

---

## 6. `Status` と `StatusReason` — 何を表し、何が返るか

### 6-1. `Status` という名前は 4 つある

**軸が違うので混ぜると読めない。**

| フィールド | どのコマンドの出力 | 何の状態か | ワークフロー内 |
|---|---|---|---|
| `Status` | `describe-change-set` | **Change Set の作成が進んだか** | `cfn-apply.yml:259` |
| `ExecutionStatus` | `describe-change-set` | **その Change Set を実行できるか** | 使っていない(waiter が内部で見る) |
| `StackStatus` | `describe-stacks` | **スタックが今どういう状態か** | `cfn-apply.yml:142` → `:151-171` |
| `ResourceStatus` | `describe-stack-events` | **1 リソース 1 イベントの結果** | `cfn-apply.yml:376` |

`StatusReason` も同様に 3 つある。`StatusReason`(Change Set)/ `StackStatusReason`(スタック)/ `ResourceStatusReason`(イベント)。

**Change Set の `Status` と `ExecutionStatus` は独立に動く。** 「作成は完了したが実行できない」も「作成中だから実行できない」も表せる。

```
create-change-set 直後   Status=CREATE_IN_PROGRESS  ExecutionStatus=UNAVAILABLE
作成完了                 Status=CREATE_COMPLETE     ExecutionStatus=AVAILABLE
他の Change Set を実行後  Status=CREATE_COMPLETE     ExecutionStatus=OBSOLETE
```

### 6-2. Change Set の `Status` — 全値

**仕様:** [`DescribeChangeSet`](https://docs.aws.amazon.com/AWSCloudFormation/latest/APIReference/API_DescribeChangeSet.html) の `Status` より。有効値は 8 つ。

| 値 | 意味 | `cfn-apply.yml` の扱い |
|---|---|---|
| `CREATE_PENDING` | 作成待ち。まだ計算を始めていない | waiter が待つ |
| `CREATE_IN_PROGRESS` | 差分を計算中 | waiter が待つ |
| `CREATE_COMPLETE` | **作成完了。差分が確定した** | waiter が成功で返す → 後段へ |
| `FAILED` | **作成に失敗した。または差分がゼロだった** | waiter が失敗で返す → `StatusReason` を読んで分岐 |
| `DELETE_PENDING` | 削除待ち | — |
| `DELETE_IN_PROGRESS` | 削除中 | — |
| `DELETE_COMPLETE` | 削除完了 | — |
| `DELETE_FAILED` | 削除に失敗 | — |

**`DELETE_*` は `delete-change-set` を撃ったあとの状態。** 通常は同期的に消えるので、CLI から観測することはあまりない。

**`CREATE_FAILED` という値は無い。** 作成の失敗も `FAILED` に入る。

**ただしユーザーガイドは `CREATE_FAILED` と書いている。仕様:** [Create a stack from the CloudFormation console](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/cfn-console-create-stack.html) より。

> If CloudFormation fails to create the change set, it sets the changes set status to `CREATE_FAILED`. Fix the error displayed in the **Status reason** field, and then create a new change set.

**API リファレンスの有効値の列挙と食い違っている。** `cfn-apply.yml:262` が `FAILED` だけを見ているのは API リファレンス側に従った形で、コンソールの表示がどちらであれ API が `CREATE_FAILED` を返さないなら問題にならない。**未検証:** 実際に返る値。

### 6-3. `StatusReason` — 差分ゼロのときの実文字列

**仕様:** `StatusReason` の説明は素っ気ない。

> A description of the change set's status. For example, if your attempt to create a change set failed, CloudFormation shows the error message.

**つまり enum ではなく自由なメッセージ。** 型は String だけで、取りうる値の列挙はどこにも無い。だから**文字列一致で判定するしかない**。

**ここが最大の罠。** 差分がゼロのとき、Change Set は正常に作られず `Status: FAILED` で返る。異常ではないのに失敗と区別が付かない。分けるには `StatusReason` を読む必要がある。

**仕様:** aws-cli の [`deployer.py`](https://github.com/aws/aws-cli/blob/develop/awscli/customizations/cloudformation/deployer.py) の `wait_for_changeset` が、まさに同じことをやっている。

```python
resp = ex.last_response
status = resp["Status"]
reason = resp["StatusReason"]

if status == "FAILED" and \
   "The submitted information didn't contain changes." in reason or \
                "No updates are to be performed" in reason:
        raise exceptions.ChangeEmptyError(stack_name=stack_name)

raise RuntimeError("Failed to create the changeset: {0} "
                   "Status: {1}. Reason: {2}"
                   .format(ex, status, reason))
```

**見ている文字列は 2 つ。**

| 文字列 | 出どころ(推定) |
|---|---|
| `The submitted information didn't contain changes.` | `CreateChangeSet` が差分ゼロを返すとき |
| `No updates are to be performed` | `UpdateStack` 由来のメッセージ。Change Set 経由でも返ることがある |

`cfn-apply.yml:263` の `grep` が同じ 2 パターンを見ているのは、この実装を写したもの。

```bash
if echo "$reason" | grep -qi "didn't contain changes\|No updates are to be performed"; then
```

**`-i`(大文字小文字を無視)を付けているのと、前半を短く切っているのは、文字列が変わることへの保険。** AWS がメッセージを変えても壊れにくい。

**差分ゼロは `CREATE` では起きない**(新規作成なら全リソースが `Add` になるので、必ず差分がある)。`cfn-apply.yml:252-253` のコメントがそう書いている。

**未検証:** 実際に AWS が返す文字列。上の 2 つは aws-cli のソースに現れるものなので「aws-cli の作者が観測した文字列」までは確かだが、末尾のピリオドの有無や現在の文言は実測しないと確定しない。aws-cli 自身も `or` の優先順位が怪しい書き方をしている(`status == "FAILED" and A or B` は `(status == "FAILED" and A) or B` と解釈される)ので、この判定は厳密ではない。

### 6-4. `ExecutionStatus` — 全値。なぜ見なくて済むのか

**仕様:** 有効値は 6 つ。

> If the change set execution status is `AVAILABLE`, you can execute the change set. If you can't execute the change set, the status indicates why. For example, a change set might be in an `UNAVAILABLE` state because CloudFormation is still creating it or in an `OBSOLETE` state because the stack was already updated.

| 値 | 意味 |
|---|---|
| `UNAVAILABLE` | まだ実行できない。作成中、または作成に失敗した |
| `AVAILABLE` | **実行できる** |
| `EXECUTE_IN_PROGRESS` | 実行中 |
| `EXECUTE_COMPLETE` | 実行完了 |
| `EXECUTE_FAILED` | 実行に失敗 |
| `OBSOLETE` | **もう実行できない。** 同じスタックの別の Change Set が実行されて、前提が変わった |

**`cfn-apply.yml` はこれを見ていない。** 見なくて済む理由が 2 つある。

1. **`Status=CREATE_COMPLETE` を確認した直後に実行しているので、`AVAILABLE` 以外になる隙が小さい。** Change Set の名前に `${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}` を入れて毎回新しく作っているので、古いものを掴んで `OBSOLETE` を踏むこともない
2. **`OBSOLETE` を踏むには「他の誰かが同じスタックを更新した」必要があるが、直列化(`concurrency`)と precheck がそれを防いでいる**(→ [ADR-0009](../../adr/0009-cfn-apply-as-the-single-cloudformation-caller.md))

**それでも踏んだときは `execute-change-set` が `InvalidChangeSetStatus` で落ちる。仕様:** [`ExecuteChangeSet` の Errors](https://docs.aws.amazon.com/AWSCloudFormation/latest/APIReference/API_ExecuteChangeSet.html) より。

> **InvalidChangeSetStatus** — The specified change set can't be used to update the stack. For example, the change set status might be `CREATE_IN_PROGRESS`, or the stack status might be `UPDATE_IN_PROGRESS`.

`set -euo pipefail` が効いているので、そのまま非ゼロで落ちる。**「見ていない」ことで失われるのは、エラーメッセージの分かりやすさだけ。**

### 6-5. `StackStatus` — 全値と precheck の分岐

**仕様:** [`Stack` データ型](https://docs.aws.amazon.com/AWSCloudFormation/latest/APIReference/API_Stack.html) の `StackStatus` より。有効値は **23 個**。

| 値 | 意味 | `cfn-apply.yml` の precheck |
|---|---|---|
| `CREATE_IN_PROGRESS` | 作成中 | `*)` → 「進行中」で拒否 |
| `CREATE_FAILED` | 作成失敗(ロールバックしなかった) | `*)` → 拒否 |
| `CREATE_COMPLETE` | **作成完了** | **UPDATE で続行** |
| `ROLLBACK_IN_PROGRESS` | 作成失敗のロールバック中 | `*)` → 拒否 |
| `ROLLBACK_FAILED` | そのロールバックが失敗 | `*)` → 拒否 |
| `ROLLBACK_COMPLETE` | **作成失敗のロールバック完了。更新できない** | 専用の分岐 → 「削除してから建て直せ」 |
| `DELETE_IN_PROGRESS` | 削除中 | `*)` → 拒否 |
| `DELETE_FAILED` | 削除失敗 | `*)` → 拒否 |
| `DELETE_COMPLETE` | 削除完了 | **観測できない**(名前で引けない → §4-4) |
| `UPDATE_IN_PROGRESS` | 更新中 | `*)` → 拒否 |
| `UPDATE_COMPLETE_CLEANUP_IN_PROGRESS` | 更新は済み、古いリソースの掃除中 | `*)` → 拒否 |
| `UPDATE_COMPLETE` | **更新完了** | **UPDATE で続行** |
| `UPDATE_FAILED` | 更新失敗(ロールバックしなかった) | `*)` → 拒否 |
| `UPDATE_ROLLBACK_IN_PROGRESS` | 更新失敗のロールバック中 | `*)` → 拒否 |
| `UPDATE_ROLLBACK_FAILED` | **そのロールバックが失敗。手当てが必要** | 専用の分岐 → `continue-update-rollback` を案内 |
| `UPDATE_ROLLBACK_COMPLETE_CLEANUP_IN_PROGRESS` | ロールバック後の掃除中 | `*)` → 拒否 |
| `UPDATE_ROLLBACK_COMPLETE` | **ロールバック完了。ここからは更新できる** | **UPDATE で続行** |
| `REVIEW_IN_PROGRESS` | **`CREATE` の Change Set を作ったが実行していない**。リソースは 1 つも無い | 「スタックが無い」と同じ扱い → CREATE |
| `IMPORT_IN_PROGRESS` | インポート中 | `*)` → 拒否 |
| `IMPORT_COMPLETE` | インポート完了 | `*)` → 拒否 |
| `IMPORT_ROLLBACK_IN_PROGRESS` | インポートのロールバック中 | `*)` → 拒否 |
| `IMPORT_ROLLBACK_FAILED` | そのロールバックが失敗 | `*)` → 拒否 |
| `IMPORT_ROLLBACK_COMPLETE` | ロールバック完了 | `*)` → 拒否 |

precheck が明示的に扱っているのは 6 つだけで、残りは `*)` の「進行中か、削除待ちの可能性があります」に落ちる。**AWS の生のエラーではなく「代わりに何をすべきか」を出すのが目的**なので、頻度の高いものだけ名指ししている設計。

#### `ROLLBACK_COMPLETE` と `UPDATE_ROLLBACK_COMPLETE` は別の状態

**表の中で 5 行離れていて名前も似ているが、扱いが正反対になる。** ここを混同すると「ロールバックの完了を待てば更新できるはず」と読み違える。

| | `ROLLBACK_COMPLETE` | `UPDATE_ROLLBACK_COMPLETE` |
|---|---|---|
| いつなるか | **初回作成(CREATE)**が失敗してロールバックが完走 | **更新(UPDATE)**が失敗してロールバックが完走 |
| 直前に成功した状態 | **無い**(一度も `CREATE_COMPLETE` になっていない) | **ある**(前回の `CREATE_COMPLETE` / `UPDATE_COMPLETE` に戻っている) |
| 中のリソース | ロールバックで削除済み | **前のバージョンで動いている** |
| 更新できるか | ✕ | ○ |
| precheck | `:162` で拒否 | `:161` で続行 |

**判定軸は「ロールバックが完了したか」ではなく「戻った先が動く状態か」。** 初回作成の失敗は戻る先が存在しないので、ロールバックは作りかけを片付けただけで終わり、更新の起点にならない。

**仕様:** [`RollbackStack`](https://docs.aws.amazon.com/AWSCloudFormation/latest/APIReference/API_RollbackStack.html) がこの概念を **last known stable state** として明文化している。

> This operation will delete a stack if it doesn't contain a last known stable state. A last known stable state includes any status in a `*_COMPLETE`. This includes the following stack statuses.
> * `CREATE_COMPLETE`
> * `UPDATE_COMPLETE`
> * `UPDATE_ROLLBACK_COMPLETE`
> * `IMPORT_COMPLETE`
> * `IMPORT_ROLLBACK_COMPLETE`

**`ROLLBACK_COMPLETE` はこのリストに入っていない。** `*_COMPLETE` で終わる名前なのに除外されているのが、両者の差そのもの。

**運用上の帰結。**

| 失敗した操作 | 結果の状態 | `cfn-destroy.yml` は必要か |
|---|---|---|
| 初回構築(`cfn-deploy.yml`)が失敗 | `ROLLBACK_COMPLETE` | **必要。** 消して建て直す |
| 反映(`cfn-apply.yml`)が失敗 | `UPDATE_ROLLBACK_COMPLETE` | **不要。** 原因を直して `cfn-apply.yml` を再実行する |

つまり**撤収が要るのは初回構築が失敗したときだけ。** すでに一度立ち上がっているスタックの更新が失敗しても、ロールバックの完了を待って(`UPDATE_ROLLBACK_IN_PROGRESS` の間は precheck が `*)` で弾く)テンプレートや params を直して再実行すればよい。

**`REVIEW_IN_PROGRESS` の扱いがいちばん独特。** 「スタックが無い」と同じ扱いにしている。

**仕様:** aws-cli の `has_stack` が同じことをしている。コメントが理由を説明している。

```python
# When you run CreateChangeSet on a a stack that does not exist,
# CloudFormation will create a stack and set it's status
# REVIEW_IN_PROGRESS. However this stack is cannot be manipulated
# by "update" commands. Under this circumstances, we treat like
# this stack does not exist and call CreateChangeSet will
# ChangeSetType set to CREATE and not UPDATE.
stack = resp["Stacks"][0]
return stack["StackStatus"] != "REVIEW_IN_PROGRESS"
```

**仕様:** AWS 側の説明は [Why is my CloudFormation stack stuck in the REVIEW_IN_PROGRESS state?](https://repost.aws/knowledge-center/cloudformation-stack-review-in-progress) にある。

> When you create a change set for a new stack, CloudFormation creates a unique stack ID, but no resources. If you don't execute the change set, then the stack remains in the REVIEW_IN_PROGRESS state.

**`dry_run=true` で初回構築を流すと、必ずこのリソースを持たないスタックが残る。** `delete-change-set` は Change Set を消すだけで、スタックには触らない(→ §8-2)。precheck がそのスタックを「無い」とみなして CREATE を作り直すことで次の構築が通る、という依存関係になっている(→ [ADR-0009](../../adr/0009-cfn-apply-as-the-single-cloudformation-caller.md))。

**注意: precheck が拒否している状態のうち、実は Change Set が作れるものがある。仕様:** [Choose how to handle failures](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/stack-failure-options.html) より。

> You can initiate a change set for a stack with a status of `CREATE_FAILED` or `UPDATE_FAILED`, but not for a status of `UPDATE_ROLLBACK_FAILED`.

つまり `CREATE_FAILED` / `UPDATE_FAILED` からは「成功したリソースを保持したまま」続行できる。**precheck はこれを許していない**(`*)` で拒否する)。学習用リポジトリでは「失敗したら消して建て直す」の方が単純で安全という判断だが、実務では使う道になる。

### 6-6. `ResourceStatus` / `ResourceStatusReason` — 失敗調査で見る

`describe-stack-events` が返すのは **1 リソース 1 イベント**の履歴。`cfn-apply.yml:375-377` は失敗時にここから最初の 10 件を引いている。

```bash
aws cloudformation describe-stack-events --stack-name "$stack" \
  --query 'StackEvents[?contains(ResourceStatus, `FAILED`)] | [0:10].{論理ID:LogicalResourceId,状態:ResourceStatus,理由:ResourceStatusReason}' \
  --output table || true
```

読み方のポイントが 3 つ。

- **`contains(ResourceStatus, 'FAILED')`** — `CREATE_FAILED` / `UPDATE_FAILED` / `DELETE_FAILED` を部分一致でまとめて拾う。状態名を列挙しなくて済む
- **`[0:10]`** — `describe-stack-events` は**新しい順**に返る。だから `[0:10]` は「最新の 10 件」。ロールバックの過程で大量のイベントが出るので絞っている
- **`ResourceStatusReason` に本当の原因が入る。** 「なぜ失敗したか」はここしか書いていない(例: `The following resource(s) failed to create: [Database]` や、サービス側の生のエラーメッセージ)

**`|| true` が付いているのは、調査そのものが失敗しても本体のエラーを消さないため。** すでに `::error::` を出したあとなので、表が出なくても失敗としては伝わる。

**傾向:** ロールバックしたスタックの原因を探すときは、最新のイベントよりも**「最初に FAILED になったイベント」**が知りたいことが多い。`[0:10]` では届かないことがあるので、コンソールのイベントタブか `--max-items` を上げて全件を見るのが確実。

---

## 7. `describe-change-set` の出力を読む

### 7-1. コマンドとオプション

**仕様:** [`DescribeChangeSet`](https://docs.aws.amazon.com/AWSCloudFormation/latest/APIReference/API_DescribeChangeSet.html)。

| オプション | 必須 | このリポジトリ | 内容 |
|---|---|---|---|
| `--change-set-name` | ○ | ○ | 名前または ARN。**ARN を渡すなら `--stack-name` は不要** |
| `--stack-name` | △ | ○ | 名前で指定するときは必須 |
| `--include-property-values` | | ✕ | **プロパティの実際の値を出力に含める** |
| `--next-token` | | ✕ | ページング → §7-3 |

**`--include-property-values` を付けると `ResourceChange` の `BeforeContext` / `AfterContext`(JSON 文字列)に変更前後のプロパティ値が入る。** 「何が変わるか」ではなく「どの値からどの値へ変わるか」が見える。

**それでもジョブサマリには出さない。** 理由は秘密の露出。このスタックのパラメータには `BasicAuthCredential` があり、テンプレートは SSM の SecureString も参照している。プロパティ値を出力に含めると、それらが `BeforeContext` / `AfterContext` に載る可能性がある。ジョブサマリは**リポジトリの読み取り権限を持つ全員が見られて、永続する**ので、載ったら取り返しがつかない。

**未検証(公式に記載が見つからない):** `NoEcho: true` のパラメータや書き込み専用プロパティが `BeforeContext` / `AfterContext` でマスクされるかどうか。[`DescribeChangeSet`](https://docs.aws.amazon.com/AWSCloudFormation/latest/APIReference/API_DescribeChangeSet.html) の `IncludePropertyValues` の説明は「If `true`, the returned changes include detailed changes in the property values.」の 1 文だけで、マスクには触れていない。**マスクされる保証が無いなら、CI のログに出す選択はしない。**

**使いどころは手元の調査。** 「なぜこのリソースが Modify になったのか」を追うときに、ターミナルから 1 回叩く。

```bash
aws cloudformation describe-change-set --stack-name nuxt-java-practice-stg \
  --change-set-name <name> --include-property-values \
  --query 'Changes[].ResourceChange.{論理ID:LogicalResourceId,前:BeforeContext,後:AfterContext}'
```

**未検証:** 付けると出力サイズが増えるので、`app.yml` の規模(リソース 50 個超)でページングに当たるかどうか(→ §7-3)。

### 7-2. `Changes[].ResourceChange` の構造

`describe-change-set` の `Changes` は `Change` の配列で、各要素が `Type`(常に `Resource`)と `ResourceChange` を持つ。**差分の中身は全部 `ResourceChange` の中。**

**仕様:** [`ResourceChange`](https://docs.aws.amazon.com/AWSCloudFormation/latest/APIReference/API_ResourceChange.html)。

| フィールド | 内容 | `cfn-apply.yml` |
|---|---|---|
| `Action` | **何をするか。** `Add` / `Modify` / `Remove` / `Import` / `Dynamic` / `SyncWithActual` | サマリの表に出す |
| `LogicalResourceId` | 論理 ID | サマリの表に出す |
| `PhysicalResourceId` | 実リソースの ID。**`Add` では無い**(まだ存在しないため) | — |
| `ResourceType` | `AWS::RDS::DBInstance` など | サマリの表に出す |
| `Replacement` | **作り直しになるか。** `True` / `False` / `Conditional` | **表に出し、`True` か `Conditional` があれば停止** |
| `Scope` | 何が引き金か。`Properties` / `Metadata` / `CreationPolicy` / `UpdatePolicy` / `DeletionPolicy` / `UpdateReplacePolicy` / `Tags` | — |
| `Details` | `Modify` のときの詳細(`ResourceChangeDetail` の配列) | — |
| `PolicyAction` | **実リソースがどうなるか。** `Delete` / `Retain` / `Snapshot` / `ReplaceAndDelete` / `ReplaceAndRetain` / `ReplaceAndSnapshot` | — |
| `ModuleInfo` | モジュール由来のリソースの情報 | — |
| `ChangeSetId` | ネストスタックの Change Set ID | — |

**`Action` の 6 値のうち 2 つは注意が要る。**

- **`Dynamic`** — 「このリソースに何をするか実行時まで決まらない」。カスタムリソースや、他リソースの出力に依存する定義で出る
- **`SyncWithActual`** — 「リソースは変わらず、CloudFormation 側のメタデータだけ変わる」。drift-aware change set で出る

**`Replacement` は二値ではない。**

> if the `RequiresRecreation` field is `Always` and the `Evaluation` field is **`Static`**, `Replacement` is `True`. If the `RequiresRecreation` field is `Always` and the `Evaluation` field is **`Dynamic`**, `Replacement` is **`Conditional`**.
>
> If you have multiple changes with different `RequiresRecreation` values, the `Replacement` value depends on the change with the most impact.

**`cfn-apply.yml:315-320` は `True` と `Conditional` の両方を拾う。**

```bash
ids() {
  echo "$detail" | jq -r --arg v "$1" \
    '[.Changes[].ResourceChange | select(.Replacement == $v) | .LogicalResourceId] | join(" ")'
}
replaced=$(ids True)
maybe=$(ids Conditional)
```

**`Conditional` も止める理由は、危険度が `True` と変わらないこと。** 「実行時の値によって置き換わるかもしれない」であって「置き換わらない」ではないので、素通りさせると「止めるはずが止まらなかった」が起きる。RDS の `Replacement` を止めるための安全弁なのだから、確定しないケースを通してしまうと弁の意味が薄れる。**傾向:** 実務でもここは止める(または明示的に警告として出す)側に寄せる。

サマリでは 2 つを分けて出す。どちらも `allow_replacement=true` で開く。

```
- 作り直しになる(`True`): `Database`
- 作り直しになるかもしれない(`Conditional`): `WebService`
```

**未検証で、運用してみないと分からないことが 1 つある。** `Conditional` がこのテンプレートで日常的に出るなら、`allow_replacement=true` を毎回付ける習慣がついて `True` の安全弁も一緒に無効化される。**そうなったら `allow_replacement` を `True` 用と `Conditional` 用の 2 つの入力に分けるのが筋。** 今は 1 つで足りると見込んでいる(→ §12 の #10)。

`Replacement` と `Update requires` の関係、置換事故の話 → [Terraform 経験者のための CloudFormation §6](./terraform-to-cloudformation.md)。

**`PolicyAction` は見る価値がある。** `ReplaceAndSnapshot` が出ていれば「置き換えて古い方はスナップショットを取る」と分かる。`cfn-apply.yml` のサマリは「`Database` が含まれている場合、新しい空の RDS に置き換わります(DeletionPolicy が Snapshot なのでスナップショットは残る)」と文章で説明しているが、**`PolicyAction` を表に出せばテンプレートの設定を読まずに確認できる。**

### 7-3. ページング

**仕様:** `NextToken` の説明より。

> If the output exceeds 1 MB, a string that identifies the next page of changes. If there is no additional page, this value is null.

**`cfn-apply.yml` は `NextToken` を見ていない。** 1 MB を超えると差分が途中で切れる。

CLI は多くのコマンドで自動ページングするが、**`describe-change-set` はページング対応コマンドとして扱われていない**(`--starting-token` / `--max-items` が Synopsis に無い)。手で `--next-token` を回すことになる。

**未検証:** `app.yml` の差分が 1 MB を超えるか。初回作成(全リソースが `Add`)がいちばん大きくなるが、`Add` は `Details` を持たないので軽い。`--include-property-values` を付けると一気に増える見込み。**超えていても静かに切れるだけなので、気づく手段が無いのが怖いところ。**

---

## 8. `execute-change-set` / `delete-change-set`

### 8-1. `execute-change-set` — 投げたら返ってくる

**仕様:** [`ExecuteChangeSet`](https://docs.aws.amazon.com/AWSCloudFormation/latest/APIReference/API_ExecuteChangeSet.html)。

> After the call successfully completes, CloudFormation **starts** updating the stack. Use the `DescribeStacks` action to view the status of the update.

**非同期。** 呼ぶと即座に返り、スタックの更新はそのあと AWS 側で走る。だから完了を待つのは別の仕事(`wait`)になる。

| オプション | 必須 | このリポジトリ | 内容 |
|---|---|---|---|
| `--change-set-name` | ○ | ○ | 名前または ARN |
| `--stack-name` | △ | ○ | 名前で指定するときは必須 |
| `--disable-rollback` / `--no-disable-rollback` | | ✕ | 失敗したときロールバックしないか |
| `--retain-except-on-create` | | ✕ | ロールバック時に、新規作成されたリソースを `DeletionPolicy: Retain` でも削除する。既定 `false` |
| `--client-request-token` | | ✕ | 冪等キー。**このトークンが全イベントに付くので、操作の追跡に使える** |

**`--disable-rollback` の既定値が公式ドキュメントと噛み合わない。仕様:** API リファレンスと [CLI リファレンス](https://docs.aws.amazon.com/cli/latest/reference/cloudformation/execute-change-set.html)は両方こう書いている。

> * `True` - if the stack creation fails, do nothing. This is equivalent to specifying `DO_NOTHING` for the `OnStackFailure` parameter to the `CreateChangeSet` API operation.
> * `False` - if the stack creation fails, roll back the stack. ...
>
> **Default: `True`**

**未検証:** これを額面通りに読むと「既定ではロールバックしない」になるが、コンソールの既定はロールバックする側であり、`cfn-apply.yml:374` のエラーメッセージも「失敗、またはロールバックしました」とロールバックを前提にしている。**ドキュメントの誤りである可能性が高い**が、実測しないと確定しない。

なお `aws cloudformation deploy` は**この曖昧さを踏まない**。ソースを見ると常に明示的に渡している。

```python
def execute_changeset(self, changeset_id, stack_name,
                      disable_rollback=False):
    return self._client.execute_change_set(
            ChangeSetName=changeset_id,
            StackName=stack_name,
            DisableRollback=disable_rollback)
```

**`--disable-rollback` と `--on-stack-failure` は同時に指定できない**(→ §5-9)。両方で同じことを指定できてしまうため。

### 8-2. `delete-change-set` — スタックには触らない

**仕様:** [`DeleteChangeSet`](https://docs.aws.amazon.com/AWSCloudFormation/latest/APIReference/API_DeleteChangeSet.html) の説明は全部で 3 文しかない。

> Deletes the specified change set. Deleting change sets ensures that no one executes the wrong change set.
>
> If the call successfully completes, CloudFormation successfully deleted the change set.
>
> If `IncludeNestedStacks` specifies `True` during the creation of the nested change set, then `DeleteChangeSet` will delete all change sets that belong to the stacks hierarchy and will also delete all change sets for nested stacks with the status of `REVIEW_IN_PROGRESS`.

**読み方に注意。3 文目は「ネストスタックしか消さない」ではない。**

- 消すのは**指定した Change Set**(1 文目)
- `--include-nested-stacks` で作った Change Set なら、**階層に属する Change Set も全部**消す(3 文目)
- そのとき、**ネストスタックの `REVIEW_IN_PROGRESS` の Change Set も**消す

**どのケースでも、親スタック自身(`REVIEW_IN_PROGRESS` のもの)は消さない。** `delete-change-set` はスタックを触るコマンドではないため。

**これが `dry_run` の帰結を決めている。** 初回構築を `dry_run=true` で流すと、`--change-set-type CREATE` が `REVIEW_IN_PROGRESS` のスタックを作り、`delete-change-set` は Change Set だけ消してスタックを残す。そのスタックを消したければ `delete-stack` を撃つ(リソースが 1 つも無いので即座に終わる)。

```bash
aws cloudformation delete-stack --stack-name nuxt-java-practice-stg
```

**[ADR-0009](../../adr/0009-cfn-apply-as-the-single-cloudformation-caller.md) は「`delete-change-set` が消すのはネストスタックだけ」と書いているが、これは上の 3 文目の読み違い。** 同 ADR に訂正の追記を入れた。**結論(リソースを持たないスタックが残る)は変わらないので、依存関係の記述は正しい。**

### 8-3. ワークフローが `delete-change-set` を呼ぶ 3 箇所

§2-2 の「作ったら実行するか消すかのどちらかが必要」の具体化。

| 箇所 | 状況 | 消す理由 |
|---|---|---|
| `cfn-apply.yml:265` | 差分ゼロ(`Status: FAILED`) | **`FAILED` の Change Set も残る。** 実行はできないが、スタックの Change Set 一覧に溜まる |
| `cfn-apply.yml:337` | `Replacement: True` を検出して停止 | **実行可能なまま残すと危険。** 名前を知っていれば誰でも `execute-change-set` できる |
| `cfn-apply.yml:346` | `dry_run=true` | 「見るだけ」なので溜めない |

**3 箇所とも「実行しないと決めた」ときに呼んでいる。** 逆に `execute-change-set` に進む経路では消さない —— 実行すると CloudFormation が自動で消すため(→ §2-2)。

**溜めるとどうなるか。** 1 スタックあたりの Change Set 数には上限がある(CloudFormation クォータ)。それ以上に、`Replacement: True` を含む Change Set が残っていると「止めたはずの危険な操作が実行可能な状態で置かれている」ことになる。**`:327` の削除は安全弁として効いている。**

---

## 9. `delete-stack` と `describe-stacks` / `describe-stack-events`

### 9-1. `delete-stack` の全オプション

**仕様:** [`DeleteStack`](https://docs.aws.amazon.com/AWSCloudFormation/latest/APIReference/API_DeleteStack.html)。

| オプション | 必須 | このリポジトリ | 内容 |
|---|---|---|---|
| `--stack-name` | ○ | ○ | 名前または ID |
| `--role-arn` | | **○** | CloudFormation が削除時に引き受けるロール → §9-2 |
| `--retain-resources` | | ✕ | **`DELETE_FAILED` のスタック限定。** 消せないリソースの論理 ID を挙げて、スタックだけ消す |
| `--deletion-mode` | | ✕ | `STANDARD`(既定)/ `FORCE_DELETE_STACK` |
| `--client-request-token` | | ✕ | 冪等キー |

**`--retain-resources` は `DELETE_FAILED` のときしか使えない。**

> For stacks in the `DELETE_FAILED` state, a list of resource logical IDs that are associated with the resources you want to retain. During deletion, CloudFormation deletes the stack but doesn't delete the retained resources.
>
> Retaining resources is useful when you can't delete a resource, such as a **non-empty S3 bucket**, but you want to delete the stack.

**公式が挙げている例が、まさにこのリポジトリが踏む問題。** 素の CloudFormation に Terraform の `force_destroy` 相当が無いので、中身が残っているバケットは削除に失敗する。`cfn-destroy.yml:65-74` が先に `aws s3 rm --recursive` して空にしているのは**そもそも `DELETE_FAILED` にしないため**。

**`--deletion-mode FORCE_DELETE_STACK` は事後の救済。仕様:** [公式のコマンド例](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/service_code_examples.html)より。

> * `STANDARD`: Deletes the stack normally. This is the default deletion mode.
> * `FORCE_DELETE_STACK`: Deletes the stack and skips all resources that are failing to delete.
>
> After using `FORCE_DELETE_STACK`, you can use the `list-stack-resources` command to list the resources that were skipped during the stack deletion process. The retained resources will show a **DELETE_SKIPPED** status.

**`--retain-resources` との違いは「どのリソースを残すかを自分で挙げるか、CloudFormation に任せるか」。** 前者は論理 ID を明示、後者は失敗したもの全部。どちらも**リソースは AWS 上に残り、管理外の孤児になる**ので、あとで手で消すことになる。

### 9-2. なぜ `cfn-destroy.yml` が `--role-arn` を渡すのか

```bash
# cfn-destroy.yml:81-84
# サービスロールを明示する。作成時と同じロールで消さないと、
# Actions のロール(cloudformation:* しか持たない)ではリソースを削除できない。
aws cloudformation delete-stack --stack-name "$stack" \
  --role-arn "${{ secrets.AWS_CFN_SERVICE_ROLE_ARN }}"
```

**仕様:** `RoleARN` の説明より。

> If you don't specify a value, CloudFormation uses the role that was previously associated with the stack. If no role is available, CloudFormation uses a temporary session that's generated from your user credentials.

**つまり厳密には省略しても動く。** `create-change-set` が `--role-arn` を渡しているので、そのロールがスタックに紐づいている(→ §5-5)。省略しても「previously associated」でそれが使われる。

**それでも明示するのは 3 つの理由で筋が通る。**

1. **スタックが手で作られた場合に備える。** ロールが紐づいていないスタックだと、Actions のロール(`cloudformation:*` だけ)の一時セッションで削除が試みられ、リソースを消せずに `DELETE_FAILED` になる
2. **`describe-stacks` の `RoleARN` を読まなくても、どのロールで消えるかがコードから分かる。** 暗黙の状態依存を減らす
3. **撤収は失敗すると課金が続く操作**なので、暗黙の既定に頼らない方が安全側

**傾向:** 実務でも「削除時にサービスロールを明示する」は定石として見る。

**なお `cfn-destroy.yml:81-85` のコメントは以前「明示しないと削除できない」と書いていたが、仕様としては強すぎるので直した。** 正確には「明示しないと、スタックに紐づいたロールに依存する」。ロールがスタックに紐づいているのは `Stack` データ型が `RoleARN` を持っていることから確認できる。

> **RoleARN** — The Amazon Resource Name (ARN) of an IAM role that's **associated with the stack**. During a stack operation, CloudFormation uses this role's credentials to make calls on your behalf.

つまり `create-change-set --role-arn` で渡した時点でスタック側に保存され、`describe-stacks` で読み出せる。`delete-stack` で省略したときに使われるのはこの値。

### 9-3. `describe-stacks` の 3 つの使い方

同じコマンドが 3 つの目的で使われている。

| 目的 | 使用箇所 | 見るもの |
|---|---|---|
| **存在確認** | `cfn-destroy.yml:56` | 終了コードだけ。出力は捨てる(→ §4-3) |
| **状態とパラメータの取得** | `cfn-apply.yml:141` | `.Stacks[0].StackStatus` と `.Stacks[0].Parameters[]` |
| **Outputs の取得** | `cfn-apply.yml:389`, `cfn-destroy.yml:69` | `.Stacks[0].Outputs[]` |

**`Parameters` と `Outputs` の両方が同じ 1 回の呼び出しで取れる**のがポイント。`cfn-apply.yml:389-396` は JSON を 1 回取って、シェル関数で 2 種類を引いている。

```bash
json=$(aws cloudformation describe-stacks --stack-name "$stack" --output json)
out()   { echo "$json" | jq -r --arg k "$1" '.Stacks[0].Outputs[]    | select(.OutputKey    == $k) | .OutputValue';   }
param() { echo "$json" | jq -r --arg k "$1" '.Stacks[0].Parameters[] | select(.ParameterKey == $k) | .ParameterValue'; }
```

**`--stack-name` を省略するとアカウント内の全スタックが返る。** 公式が性能への注意を書いている。

> If you don't pass a parameter to `StackName`, the API returns a response that describes all resources in the account, which can impact performance. This requires `ListStacks` and `DescribeStacks` permissions.

`Stack` データ型が返す主なフィールドは他にもある。**傾向:** 運用で見る価値があるのはこのあたり。

| フィールド | 内容 |
|---|---|
| `StackStatusReason` | 状態の理由。失敗の第一報がここに出る |
| `RoleARN` | スタックに紐づいているサービスロール(→ §9-2) |
| `EnableTerminationProtection` | 削除保護。有効だと `delete-stack` が拒否される |
| `DriftInformation` | 最後に検出したドリフト状態と検出時刻 |
| `LastUpdatedTime` | 最後に更新した時刻(更新が 1 回でもあれば present) |
| `DetailedStatus` | `CONFIGURATION_COMPLETE` / `VALIDATION_FAILED` |

### 9-4. `describe-stack-events`

`ResourceStatus` / `ResourceStatusReason` の読み方は §6-6 で扱った。ここではコマンド側だけ。

**ページング対応コマンド**なので `--max-items` / `--starting-token` が使える。既定では CLI が自動で全ページを取ってくる(`--no-paginate` で 1 ページに止める)。イベントは**新しい順**。

**削除済みスタックのイベントを見たいときはスタック ID が必要**(→ §4-4)。名前では引けない。

---

## 10. `aws cloudformation deploy`

このリポジトリでは使っていないが、3 手を手組みするコードの半分は `deploy` の写しなので、中身を読むと `cfn-apply.yml` の設計理由が分かる。

**`deploy` は API ではなく aws-cli の「カスタマイゼーション」**(Python で書かれた複合コマンド)。実装は [`awscli/customizations/cloudformation/`](https://github.com/aws/aws-cli/tree/develop/awscli/customizations/cloudformation) の `deploy.py` と `deployer.py`。

### 10-1. 全オプション

**仕様:** [`deploy` の CLI リファレンス](https://docs.aws.amazon.com/cli/latest/reference/cloudformation/deploy.html)。

| オプション | 必須 | 内容 |
|---|---|---|
| `--template-file` | ○ | **ローカルパス。** URL は渡せない |
| `--stack-name` | ○ | 既存なら更新、新規なら作成 |
| `--s3-bucket` | | 51,200 バイト超のテンプレートで必須 |
| `--s3-prefix` | | S3 のキーに付ける接頭辞 |
| `--force-upload` | | 同名のオブジェクトがあっても上書きアップロードする |
| `--kms-key-id` | | S3 上のテンプレートを暗号化する KMS キー |
| `--parameter-overrides` | | `Key=Value` の並び、または JSON ファイル |
| `--capabilities` | | **`CAPABILITY_IAM` と `CAPABILITY_NAMED_IAM` だけ** |
| `--no-execute-changeset` | | 作って止まる → §10-2 |
| `--disable-rollback` / `--no-disable-rollback` | | 失敗時のロールバック |
| `--role-arn` | | サービスロール |
| `--notification-arns` | | SNS トピック |
| `--fail-on-empty-changeset` / `--no-fail-on-empty-changeset` | | 差分ゼロの扱い → §10-5 |
| `--tags` | | `Key=Value` の並び |

**`create-change-set` に比べて無いものが多い。** `--change-set-name`(自動生成)、`--change-set-type`(自動判定)、`--template-url`、`--use-previous-template`、`--deployment-mode`、`--on-stack-failure`、`--include-nested-stacks`、`--resource-types`、`--rollback-configuration`、`--client-token`、`--description`(自動生成)。

**`--capabilities` に `CAPABILITY_AUTO_EXPAND` が渡せない。** ソースの `ARG_TABLE` の schema が `enum: ['CAPABILITY_IAM', 'CAPABILITY_NAMED_IAM']` で閉じている。§5-4 の通り Change Set 経由では効かない値なので、渡せなくても実害はない。

**`--parameter-overrides` の JSON には `UsePreviousValue` を書けない。仕様:** CLI リファレンスの注記より。

> **Note:** Only `ParameterKey` and `ParameterValue` are expected keys, command will throw an exception if receives unexpected keys (e.g. `UsePreviousValue` or `ResolvedValue`).

このリポジトリの `cloudformation/params/stg.json` は `[{"ParameterKey": ..., "ParameterValue": ...}]` の形なので `deploy` にもそのまま渡せる。ただし **`ImageTag` を `UsePreviousValue` にする、という `cfn-apply.yml` の挙動は `deploy` では表現できない**(`deploy` は「渡さなければ自動で `UsePreviousValue`」なので、結果は同じになる)。

### 10-2. `--no-execute-changeset` で何が変わるか

**仕様:** ソースを読むと、`--no-execute-changeset` は `execute_changeset` という真偽値を `False` にするだけ。

```python
{
    'name': 'no-execute-changeset',
    'action': 'store_false',
    'dest': 'execute_changeset',
    ...
}
```

そして本体はこう分岐する。

```python
if execute_changeset:
    deployer.execute_changeset(result.changeset_id, stack_name, disable_rollback)
    deployer.wait_for_execute(stack_name, result.changeset_type)
    sys.stdout.write(self.MSG_EXECUTE_SUCCESS.format(stack_name=stack_name))
else:
    sys.stdout.write(self.MSG_NO_EXECUTE_CHANGESET.format(changeset_id=result.changeset_id))
```

**つまり `--no-execute-changeset` は「Change Set の ID を表示して終わる」だけ。**

- **差分は表示しない。** `describe-change-set` を自分で叩く必要がある
- **Change Set は消さない。** 実行可能なまま残る
- **`CREATE` なら `REVIEW_IN_PROGRESS` のスタックも残る**(→ §6-5)

**`terraform plan` の代わりとしては物足りない。** 差分を見るには 2 コマンド目が要るので、結局 `describe-change-set` を書くことになる。`cfn-apply.yml` が `deploy` を捨てた理由の一つはここ ——「差分を見て、そのまま実行する」を `deploy` では 1 本で書けない。

### 10-3. 内部で何をしているか

**仕様:** `deployer.py` の `create_and_wait_for_changeset` → `create_changeset` → `wait_for_changeset` の流れ。順に追う。

**① Change Set の名前と説明を自動生成する。**

```python
def __init__(self, cloudformation_client,
             changeset_prefix="awscli-cloudformation-package-deploy-"):
```

```python
now = get_current_datetime().isoformat()
description = "Created by AWS CLI at {0} UTC".format(now)
# Each changeset will get a unique name based on time
changeset_name = self.changeset_prefix + str(int(time.time()))
```

名前は `awscli-cloudformation-package-deploy-<unixtime>`。`cfn-apply.yml:212` が `apply-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}` にしているのは、**時刻より run と紐づける方が追跡しやすいから**(同じ run の再実行も区別できる)。

**② スタックの有無で `CREATE` / `UPDATE` を決める。**

```python
if not self.has_stack(stack_name):
    changeset_type = "CREATE"
    # When creating a new stack, UsePreviousValue=True is invalid.
    parameter_values = [x for x in parameter_values
                        if not x.get("UsePreviousValue", False)]
else:
    changeset_type = "UPDATE"
    # UsePreviousValue not valid if parameter is new
    summary = self._client.get_template_summary(StackName=stack_name)
    existing_parameters = [parameter['ParameterKey'] for parameter in summary['Parameters']]
    parameter_values = [x for x in parameter_values
                        if not (x.get("UsePreviousValue", False) and
                        x["ParameterKey"] not in existing_parameters)]
```

`has_stack` は `REVIEW_IN_PROGRESS` を「無い」とみなす(→ §6-5)。

**ここに `cfn-apply.yml` が写していない処理がある。** `UPDATE` のとき `get_template_summary` を追加で呼んで、**「テンプレートに新しく追加されたパラメータ」から `UsePreviousValue` を外している。** 前の値が存在しないパラメータに `UsePreviousValue` を付けると落ちるため。

`cfn-apply.yml` が写していないのは、`UsePreviousValue` を使うのが `ImageTag` の 1 つだけで、それは新しいパラメータではないから。**ただし「params に無いパラメータを新しく増やして `UsePreviousValue` にする」ような変更をしたら、ここで踏む。**

**③ テンプレートを `TemplateBody` か `TemplateURL` で渡す。**

```python
kwargs = {
    'ChangeSetName': changeset_name,
    'StackName': stack_name,
    'TemplateBody': cfn_template,
    'ChangeSetType': changeset_type,
    'Parameters': parameter_values,
    'Capabilities': capabilities,
    'Description': description,
    'Tags': tags,
}

# If an S3 uploader is available, use TemplateURL to deploy rather than
# TemplateBody. This is required for large templates.
if s3_uploader:
    with mktempfile() as temporary_file:
        temporary_file.write(kwargs.pop('TemplateBody'))
        ...
        kwargs['TemplateURL'] = s3_uploader.to_path_style_s3_url(...)
```

`--s3-bucket` があれば S3 に上げて `TemplateURL` に差し替える。**`to_path_style_s3_url`** なので `https://s3.<region>.amazonaws.com/<bucket>/<key>` の形。`cfn-apply.yml:199` は virtual-hosted 形式(`https://<bucket>.s3.<region>.amazonaws.com/<key>`)を組んでいる。**どちらも受け付けられる。**

**`Tags` と `Capabilities` は無条件に `kwargs` に入る。** 渡さなければ空配列になり、既存のタグが消える(→ §5-6)。**`--role-arn` と `--notification-arns` だけは `None` のとき入れない**(既存値を保つため)。

```python
# don't set these arguments if not specified to use existing values
if role_arn is not None:
    kwargs['RoleARN'] = role_arn
if notification_arns is not None:
    kwargs['NotificationARNs'] = notification_arns
```

**④ Change Set の完成を待つ。ポーリング間隔が 5 秒。**

```python
waiter = self._client.get_waiter("change_set_create_complete")
# Poll every 5 seconds. Changeset creation should be fast
waiter_config = {'Delay': 5}
```

**`MaxAttempts` は上書きしていないので 120 のまま。** つまり `deploy` の Change Set 待ちは **5 秒 × 120 = 10 分**。一方 `aws cloudformation wait change-set-create-complete` を素で叩くと botocore の既定が効いて **30 秒 × 120 = 60 分**(→ §4-1)。**`cfn-apply.yml` の方が 6 倍気長で、そのぶん反応が 30 秒単位になる。**

**⑤ 差分ゼロを `StatusReason` の文字列一致で判定する。** → §6-3

**⑥ 実行して、CREATE / UPDATE で waiter を出し分ける。**

```python
if changeset_type == "CREATE":
    waiter = self._client.get_waiter("stack_create_complete")
elif changeset_type == "UPDATE":
    waiter = self._client.get_waiter("stack_update_complete")
...
# Poll every 30 seconds. Polling too frequently risks hitting rate limits
# on CloudFormation's DescribeStacks API
waiter_config = {'Delay': 30, 'MaxAttempts': 120}
```

`cfn-apply.yml:365-371` がやっているのと同じ。**コメントに「頻繁にポーリングすると `DescribeStacks` のレート制限に当たる」と書いてある**のが、30 秒という値の根拠。

### 10-4. `deploy` が暗黙にやっていた 4 つ

**このリポジトリでは誰が肩代わりしているか。**

| `deploy` がやっていたこと | 肩代わりしている場所 |
|---|---|
| **1. テンプレートを S3 に上げて `TemplateURL` に差し替える** | 「テンプレートを S3 に置く」ステップ(`cfn-apply.yml:190-200`)。バケットは手動管理の常駐リソース(→ [ADR-0008](../../adr/0008-template-bucket-as-resident-resource.md)) |
| **2. 渡さなかったパラメータを `UsePreviousValue` にする** | `cfn-apply.yml:218-238` の jq。ただし方針が違う —— `deploy` は「渡さなかった全部」を自動で埋めるが、こちらは **params ファイルを唯一の正として全パラメータを明示**し、`ImageTag` だけを `UsePreviousValue` にする |
| **3. スタックの有無から `CREATE` / `UPDATE` を判定する**(`REVIEW_IN_PROGRESS` は「無い」扱い) | precheck の `case` 文(`cfn-apply.yml:151-171`)。加えて `wait` の出し分け(`:365-371`) |
| **4. 差分ゼロを判定する** | `cfn-apply.yml:262-275`。`StatusReason` の 2 パターンを `grep -qi` |

**ワークフローのファイル冒頭のコメント(`cfn-apply.yml:33-37`)は 3 つと書いているが、4 つある。** 4 番目の「差分ゼロの判定」も `deploy` が持っていた処理(`ChangeEmptyError` を投げる仕組み)で、`deploy` を捨てたぶん自分で書くことになったもの。§10-5 の通り**扱いは v1 と v2 で違う**ので、単に「`deploy` に任せればよかった」ではないのが厄介なところ。

**逆に、`cfn-apply.yml` が `deploy` より多く持っているもの。**

| 追加分 | 場所 |
|---|---|
| 差分をジョブサマリに表として出す | `:293-310` |
| `Replacement` が `True` / `Conditional` のものを検出して停止する | `:312-340` |
| dry run で Change Set を消す | `:342-352` |
| 失敗時にイベントを表として出す | `:375-377` |
| 前提が崩れている状態を「代わりに何をすべきか」で弾く | `:151-182` |
| 結果を outputs として返す | `:383-403` |

**「`deploy` を捨てた」の実質は、この 6 つを足すために 4 つを引き受けた、という交換。**

### 10-5. `--fail-on-empty-changeset` — v1 と v2 で既定が逆

**ここは AWS CLI のバージョンで挙動が変わるので、単に「`deploy` は差分ゼロを黙認する」とは言えない。**

**仕様:** [AWS CLI v2 の破壊的変更](https://docs.aws.amazon.com/cli/latest/userguide/cliv2-migration-changes.html)の「Improved handling of CloudFormation deployments that result in no changes」より。

> By default in the AWS CLI version 1, if you deploy a CloudFormation template that results in no changes, the AWS CLI returns a **failed** error code. This causes problems if you don't consider that to be an error and you want your script to continue. You can work around this in the AWS CLI version 1 by adding the flag `--no-fail-on-empty-changeset`, which returns `0`.
>
> Since this is a common use case, the AWS CLI version 2 defaults to returning a **successful exit code of `0`** when there is no change caused by a deployment and the operation returns an empty changeset.

| | 差分ゼロのときの `deploy` の終了コード |
|---|---|
| AWS CLI **v1** | **非ゼロ**(失敗) |
| AWS CLI **v2** | **0**(成功) |

v1 のソースでは `'default': True` になっていて、実際に警告メッセージまで出す。

```python
if v2_debug and fail_on_empty_changeset:
    uni_print(
        '\nAWS CLI v2 UPGRADE WARNING: In AWS CLI v2, deploying '
        'an AWS CloudFormation Template that results in an empty '
        'changeset will NOT result in an error by default. This '
        'is different from v1 behavior, where empty changesets '
        'result in an error by default. ...'
    )
```

**GitHub Actions の `ubuntu-latest` には AWS CLI v2 が入っている**ので、`deploy` を使っていたら差分ゼロは静かに成功していたことになる。

**`cfn-apply.yml` は「差分ゼロは成功だが、それが分かるように出す」を選んでいる。**

```bash
{
  echo "### 差分がありませんでした"
  echo ""
  echo "テンプレートと params は既にこのスタックへ反映済みです。"
} >> "$GITHUB_STEP_SUMMARY"
exit 0
```

`deploy` の v2 の挙動と結果は同じ(終了コード 0)だが、**ジョブサマリに理由が残る**点が違う。それと、そのあとの `Change Set を実行する` 以降のステップを `steps.cs.outputs.empty == 'false'` でまとめてスキップしているので、**outputs も空文字で返る**(呼び出し側の `cfn-deploy.yml` がそれを見てサマリを組む)。

---

## 11. ワークフローのステップと節の対応表

| ワークフロー | ステップ | 主なコマンド | 読む節 |
|---|---|---|---|
| apply | 前提を確かめる | `describe-stacks` | §4-3, §6-5 |
| apply | テンプレートを S3 に置く | `s3 cp` | §5-2 |
| apply | Change Set を作る | `create-change-set` | §5 全体 |
| apply | 同(完了待ち) | `wait change-set-create-complete` | §4-1, §4-2 |
| apply | 同(差分ゼロ判定) | `describe-change-set` | §6-2, §6-3 |
| apply | 差分を確認する | `describe-change-set` | §7 |
| apply | dry run なので実行しない | `delete-change-set` | §8-2, §8-3 |
| apply | Change Set を実行する | `execute-change-set` + `wait` | §8-1, §4-2 |
| apply | 同(失敗時) | `describe-stack-events` | §6-6, §9-4 |
| apply | 結果をサマリに出す | `describe-stacks` | §9-3 |
| destroy | スタックの存在を確かめる | `describe-stacks` | §4-3, §4-4 |
| destroy | 画像バケットを空にする | `describe-stacks --query` | §4-5, §9-1 |
| destroy | スタックを削除する | `delete-stack` + `wait` | §9-1, §9-2, §4-2 |
| — | `deploy` を使うとしたら | `deploy` | §3, §10 |

運用手順は [docs/infrastructure/cloudformation-operations.md](../../infrastructure/cloudformation-operations.md)、決定の理由は [ADR-0009](../../adr/0009-cfn-apply-as-the-single-cloudformation-caller.md) と設計書 [2026-08-19-phase13-cloudformation-design.md](../../superpowers/specs/2026-08-19-phase13-cloudformation-design.md)。

---

## 12. 実測して確定させたいこと

このノートは公式ドキュメントと aws-cli / botocore のソースだけで書いていて、AWS を叩いていない。**実物を一度動かせば確定するものを一覧にしておく。**

| # | 確定させたいこと | 確かめ方 | 関連 |
|---|---|---|---|
| 1 | 差分ゼロのときの `StatusReason` の実文字列(2 パターンのどちらが返るか、末尾のピリオドの有無) | 差分の無いテンプレートで `create-change-set` → `describe-change-set` | §6-3 |
| 2 | `--on-stack-failure` を省略したときの既定の挙動 | `CREATE` を意図的に失敗させて `StackStatus` を見る | §5-9 |
| 3 | `execute-change-set` の `--disable-rollback` の実際の既定値(ドキュメントは `True` と書いている) | `UPDATE` を意図的に失敗させて、ロールバックが走るか見る | §8-1 |
| 4 | `create-change-set` に `CAPABILITY_AUTO_EXPAND` を渡しても無視されるか(`AWS::Include` を入れた場合) | マクロを含むテンプレートで capability 無しの `create-change-set` を流す | §5-4 |
| 5 | drift-aware change set の実状態読み取りが、サービスロールと呼び出し側資格情報のどちらで行われるか | 権限を足さずに `--deployment-mode REVERT_DRIFT` を流し、`ResourceDriftStatus` が全部 `NOT_CHECKED` になるか見る | §5-8 |
| 6 | `--deployment-mode REVERT_DRIFT` を `--change-set-type CREATE` と併用したときの挙動 | 新規スタックで両方を指定する | §5-8 |
| 7 | `describe-change-set` の出力が 1 MB を超えるか(とくに `--include-property-values` 付き) | 初回作成の Change Set で `NextToken` が返るか見る | §7-1, §7-3 |
| 8 | `--output text` が null を `None` と出すこと | `--query` で存在しないキーを引く | §4-5 |
| 9 | Change Set の作成が失敗したときに API が返す `Status`(`FAILED` か `CREATE_FAILED` か。API リファレンスとユーザーガイドが食い違っている) | 通らないテンプレートで `create-change-set` → `describe-change-set` | §6-2 |
| 10 | `Conditional` がこのテンプレートで日常的に出るか(出るなら `allow_replacement` を 2 つの入力に分ける) | 何度か `cfn-apply` を流してサマリを見る | §7-2 |

**1 と 3 は `describe-change-set` を数回叩くだけで済む**ので、AWS 側の準備(テンプレート用 S3 バケットの手動作成 → [ADR-0008](../../adr/0008-template-bucket-as-resident-resource.md))が終わったら最初に潰したい。
