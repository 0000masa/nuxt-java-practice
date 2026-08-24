# CloudFormation を叩くのは cfn-apply.yml だけにする

日付: 2026-08-24
ステータス: accepted

## 決定

**`aws cloudformation` の呼び出しを `.github/workflows/cfn-apply.yml` の 1 本に集約する。** 構築ワークフロー `cfn-deploy.yml` は自分では CloudFormation を叩かず、`workflow_call` で `cfn-apply.yml` に委譲する。

- `cfn-deploy.yml` は **順序を表現するだけ**の 5 ジョブになる。`runs-on` を持つのは最後の `summary` だけで、aws コマンドは 1 つも無い
- `cfn-apply.yml` は `workflow_call` を持ち、**新規作成(`--change-set-type CREATE`)も担う**
- **`workflow_dispatch` から CREATE には到達できない。** 既存環境向けの guard を外す 3 つの入力(`web_desired_count` / `allow_missing_stack` / `allow_zero_desired_count`)は `workflow_call` にしか宣言しない。人間が Actions の UI から解除する手段が構造的に無いことが安全弁
- **判断は呼び出し側が入力で渡す。** `cfn-apply.yml` は「なぜ `DesiredCount` を 0 にするのか」を知らない
- **`cfn-apply.yml` は `concurrency` を持たない。** 直列化は入口(`cfn-deploy.yml` / `cfn-destroy.yml`)が持つ
- `cfn-apply.yml` は `workflow_call` の `outputs` で URL / ALB の DNS 名 / イメージタグ / タスク数を返し、`cfn-deploy.yml` の `summary` ジョブがそれを整形する(**AWS を叩かないので AssumeRole が要らない**)

`workflow_dispatch` から見た `cfn-apply.yml` の挙動は**変わらない**。だから `name:`(「反映(更新のみ)」)もそのまま。

## 背景と理由

`cfn-deploy.yml` と `cfn-apply.yml` は、どちらも同じスタックに同じ params を流し込むワークフローだったため、次の知識が 2 ファイルに重複していた。

- テンプレート置き場のバケット名をアカウント ID から組み立てる規則
- **`app.yml` が 51,200 バイトを超えているのでテンプレートは S3 経由でしか渡せない**([ADR-0008](./0008-template-bucket-as-resident-resource.md))
- `params/<env>.json` を jq で `--parameter-overrides` の形に組み立てる
- `--tags` に `Project` / `Env` / `ManagedBy` の 3 つを毎回渡す(省略するとスタックのタグが失われる)
- Change Set を作って差分をジョブサマリに出す一式(`cfn-deploy.yml` の `dry_run` 側)

**先例が同じリポジトリにあった。** `db-task.yml` は `workflow_dispatch` と `workflow_call` の両方を持ち、`cfn-deploy.yml` が bootstrap と migrate をそこへ委譲している([決定13](../superpowers/specs/2026-08-19-phase13-cloudformation-design.md))。`run-task` の待ち合わせが 1 か所で済んでいるのと同じ形を、CloudFormation の叩き方にも適用する。

**委譲したことで消えた配線が 1 つある。** これまで `deploy-zero` が params から `WebDesiredCount` を読んで `outputs` で `deploy-service` に渡していたが、`cfn-apply.yml` は元から params を丸ごと読むので、**`web_desired_count` を渡さなければ params の値に収束する。** params を読む jq が 1 か所になった。

### `concurrency` を外した理由

調べた結果、**呼ばれる側(reusable workflow)のワークフローレベル `concurrency` も適用される。** 呼び出し側と同じグループ名を持つと親子で枠を取り合う(`cancel-in-progress: true` ならキャンセル、`false` なら待ち続ける)。`cfn-deploy.yml` と `cfn-apply.yml` は両方が `cfn-deploy-${env}` / `cancel-in-progress: false` だったので、素直に呼ぶと自分自身の待ちで止まる。**`db-task.yml` に `concurrency` が無いのは偶然ではない。**

外しても保護が抜けないことを確認してから決めた。`cfn-apply.yml` を単体で dispatch したとき、`cfn-deploy` / `cfn-destroy` が走っている**全期間**が precheck に引っかかる。

| そのとき走っている処理 | スタックの状態 | dispatch された cfn-apply |
|---|---|---|
| cfn-deploy 1 段目 | `CREATE_IN_PROGRESS` / `UPDATE_IN_PROGRESS` | 状態チェックで落ちる |
| cfn-deploy 2〜3 段目(bootstrap / migrate) | `CREATE_COMPLETE` だが `WebDesiredCount` が 0 | DesiredCount チェックで落ちる |
| cfn-deploy 4 段目 | `UPDATE_IN_PROGRESS` | 状態チェックで落ちる |
| cfn-destroy | `DELETE_IN_PROGRESS` | 状態チェックで落ちる |

2 行目が塞がるのは、guard を外す入力を `workflow_dispatch` に宣言していないから。**`concurrency` は「AWS を叩く前に速く落とす」二重防御にすぎず、外しても内側(precheck)と最後の砦(CloudFormation 自身が同時更新を弾く)が残る。** 対して残せば確実に壊れる。

## 検討したが採らなかった選択肢

- **共通部分を 4 本目 `cfn-stack.yml`(`workflow_call` 専用)に抽出し、`cfn-deploy` と `cfn-apply` の両方が呼ぶ** — 入口(`concurrency` と guard を持つ)と機構(持たない)が分かれるので、構造としては一番きれい。採らなかったのは、`cfn-apply` の precheck が別ジョブに切り出されて **AssumeRole が 1 回増えてジョブも 1 つ増える**割に、呼び出し側が 2 つしかないため

- **`mode` 入力 1 つ(`apply` / `create` / `start`)で分岐する** — 呼び出し側は 3 入力で済み一番短い。採らなかったのは、**2 段階デプロイの理由(なぜ 0 で作るのか)が `cfn-apply.yml` の中に流れ込む**ため。`db-task.yml` が `action: bootstrap|migrate|sql` という「何をするか」しか受け取らず、**なぜ bootstrap が先なのかを知らない**のと同じ関係を保ちたかった

- **guard を外すフラグを 1 つに束ねる(`initial_build: true` のような)** — 呼び出し側は読みやすくなるが、4 段目でも「スタックが無くてもよい」が同時に開く。`needs` で 1 段目の後に走るので実際には起きないが、**安全弁は外す範囲を最小にする**ほうを採った([決定18](../superpowers/specs/2026-08-19-phase13-cloudformation-design.md) が任意 SQL を stg 限定にし、実行ユーザーの既定を DDL 不可の `app` にしたのと同じ思想)

- **`cfn-apply.yml` の `concurrency` グループ名を変える / 入力で切り替える** — 前者は [決定20](../superpowers/specs/2026-08-19-phase13-cloudformation-design.md) の「3 本のワークフローをまたいで直列化する」が嘘になる。後者は**ワークフローレベルの `concurrency.group` で `${{ inputs.X }}` が評価されないという報告**があり(公式の contexts 一覧表とは食い違っている)、検証に 1 回 10〜20 分かかる環境で挙動が確かでない機能に乗るのは避けた

- **`cfn-deploy.yml` の `dry_run` を廃止する** — 委譲そのものが重複を消すので、廃止しなくても行数は減る。残した理由は、`dry_run` の価値が差分の中身ではなく **pre-flight**(CREATE の Change Set が本当に作れるか = テンプレートが CloudFormation 側の検証を通るか + サービスロールで作れるか)にあるため。初回構築は 15〜25 分かかり、失敗すれば `ROLLBACK_COMPLETE` で残って撤収からやり直しになる。しかも `app.yml` は 51,200 バイト超で `validate-template --template-body file://` が使えず、手元のチェックは `cfn-lint`(ローカルの構文とスキーマだけ)しかない

## 結果として生じること

- **`aws cloudformation deploy` が暗黙にやっていた「CREATE / UPDATE の出し分け」を自分で持つことになった。** スタックが無い場合と `REVIEW_IN_PROGRESS` の場合を CREATE、それ以外を UPDATE とし、完了待ちも `stack-create-complete` / `stack-update-complete` を出し分ける

- **`dry_run` が残す `REVIEW_IN_PROGRESS` のスタックの器と、CREATE 判定が依存関係にある。** `dry_run=true` で流すと器が残り(`delete-change-set` が消すのはネストスタックだけ)、次の構築はその器を「スタックが無い」とみなして CREATE を作り直すことで通る。`aws cloudformation deploy` はこれを内部でやっていた(aws-cli の `deployer.has_stack`)。**片方だけ直すと壊れる**

- **1 回の `cfn-deploy` 実行でジョブサマリが 7 ブロック並ぶ**(1 段目の差分と結果、bootstrap、migrate、4 段目の差分と結果、締め)。「タスク数 0」→「タスク数 1」の並びは 2 段階デプロイが効いた記録として読める。見た目のために「呼ばれたときはサマリを出さない」入力を足すことは**しない**

- **4 段目にも Change Set の差分と Replacement の安全弁が付いた**(これまで `deploy` を直に叩いていたので差分は出ていなかった)

- **`cfn-apply.yml` を直すと構築フローにも即座に効く。** 重複を消した狙いどおりだが、逆に言えば反映のために入れた変更が構築を壊しうる。`cfn-apply.yml` を触るときは dispatch と `workflow_call` の両経路を見る
