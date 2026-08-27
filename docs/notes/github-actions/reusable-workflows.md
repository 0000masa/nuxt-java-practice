# 再利用可能ワークフロー(`workflow_call`)— 呼び方と 5 つの落とし穴

GitHub Actions で「ワークフローから別のワークフローを呼ぶ」仕組み。このリポジトリでは
`cfn-deploy.yml` が `cfn-apply.yml` と `db-task.yml` を呼んでいる(→ [ADR-0009](../../adr/0009-cfn-apply-as-the-single-cloudformation-caller.md))。

**呼ぶ単位はジョブ。** ステップではない。ここが後述の落とし穴のほとんどの原因になる。

---

## 1. 書き方

呼ばれる側に `on: workflow_call` を足す。`workflow_dispatch` と**両方持てる**。

```yaml
# .github/workflows/db-task.yml
on:
  workflow_dispatch:
    inputs:
      action:
        type: choice
        options: [create-db-users, migrate, sql]
  workflow_call:
    inputs:
      action:
        type: string
        required: true
```

呼ぶ側は `steps` ではなく `jobs.<id>.uses` に書く。

```yaml
jobs:
  create-db-users:
    uses: ./.github/workflows/db-task.yml   # 同一リポジトリなら相対パス
    with:
      action: create-db-users
    secrets: inherit                        # Secret を個別に列挙せず丸ごと渡す
```

`uses` を持つジョブには `runs-on` も `steps` も書かない(書くとエラー)。**呼ばれた側のジョブが、呼び出し側の実行の中の 1 ジョブとして現れる。**

## 2. `workflow_dispatch` と `workflow_call` で入力を別に宣言できる

同じ入力名を両方に書くこともできるが、**片方にだけ宣言することもできる。** これは単なる重複回避ではなく、**権限設計の道具**になる。

このリポジトリの `cfn-apply.yml` は、既存環境を守る guard を外す 3 つの入力(`web_desired_count` / `allow_missing_stack` / `allow_zero_desired_count`)を **`workflow_call` にしか宣言していない。** 結果として:

- Actions の UI から叩いた人は、その 3 つを**設定する手段が存在しない**。UI に出てこない
- 構築ワークフロー(`cfn-deploy.yml`)だけが、コードとして書かれた経路で guard を開けられる

「危険な操作を、特定の呼び出し元にだけ開ける」を**設定ではなくワークフローの形で**表現できる。GitHub Free のプライベートリポジトリでは Environment の required reviewers が使えないので、承認の代わりにこういう構造で埋めている。

## 3. 落とし穴 ① `concurrency` は呼ばれる側でも効く

**呼ばれる側のワークフローレベル `concurrency` は無効化されない。** そして `${{ github.workflow }}` は**呼び出し側のワークフロー名**になるので、両方が同じ式を使うと同じグループを取り合う。

- `cancel-in-progress: true` → 実行中の親がキャンセルされる
- `cancel-in-progress: false` → 子が親の終了を待ち、親は子の終了を待つ。**進まない**

```yaml
# 呼び出し側                        # 呼ばれる側
concurrency:                        concurrency:
  group: cfn-deploy-${{ inputs.env }}  group: cfn-deploy-${{ inputs.env }}   # ← 同じ
  cancel-in-progress: false            cancel-in-progress: false
```

**対処は「呼ばれる側は `concurrency` を持たない」**(直列化は入口が持つ)か、**リテラルで別のグループ名にする。** このリポジトリは前者を採り、`cfn-apply.yml` から `concurrency` を削除した。外しても保護が抜けないことを、スタックの状態の全期間を並べて確認してから決めている(→ ADR-0009)。

`db-task.yml` に最初から `concurrency` が無かったのは、結果的にこの落とし穴を避けていた。

## 4. 落とし穴 ② ワークフローレベルの `concurrency` で `inputs` が評価されない(疑い)

公式の contexts 一覧表は「`concurrency` で使えるのは `github` / `inputs` / `vars`」と書いているが、**`${{ inputs.X }}` が無視されるという報告がある**([community #45734](https://github.com/orgs/community/discussions/45734)、2024 年 7 月)。もしそれが今も正しいなら、`group: cfn-deploy-${{ inputs.env }}` は実際には `cfn-deploy-` という 1 つのグループになる。

**回避策として挙がっているのは 2 つ。** `workflow_dispatch` なら `${{ github.event.inputs.X }}` を使う(こちらは効くという報告)、または `concurrency` をジョブレベルに移す(ジョブレベルでは `inputs` が効く)。

**このリポジトリでは直していない。** 環境が stg 1 つしかないので、群が 1 つになっても直列化はむしろ広く効く方向で実害が無い。prod を足すときに見直す。

## 5. 落とし穴 ③ 呼び出し側のワークフローレベル `env` は伝播しない

公式ドキュメントに明記されている(「caller workflow のワークフローレベル `env` で設定した環境変数は called workflow へ伝播しない」)。

```yaml
# 呼び出し側
env:
  AWS_REGION: ap-northeast-1   # ← 呼ばれる側では未定義
```

呼ばれる側は自分で `env` を持つか、`inputs` で受け取る。逆方向も同じで、**呼ばれる側から `GITHUB_ENV` で呼び出し側のステップに値を渡すことはできない**(呼ばれるのがジョブなので、そもそも同じジョブにステップが無い)。値を返すには `outputs` を使う(§7)。

## 6. 落とし穴 ④ `permissions` は下げられるだけ

`GITHUB_TOKEN` の権限は**呼び出し側の値が上限**で、呼ばれる側はそれを下げられるだけ。上げられない。

つまり呼び出し側が `permissions` を書き忘れると、呼ばれる側でいくら `id-token: write` と書いても OIDC の AssumeRole が落ちる。**呼び出し側が `uses` だけのワークフローでも `permissions` は残す。**

```yaml
# cfn-deploy.yml は aws コマンドを 1 つも持たないが、これは要る
permissions:
  id-token: write
  contents: read
```

## 7. 値を返す — `outputs`

呼ばれる側は「ジョブの `outputs`」を「ワークフローの `outputs`」に持ち上げる。2 段構えになる。

```yaml
# 呼ばれる側
on:
  workflow_call:
    outputs:
      app_url:
        value: ${{ jobs.apply.outputs.app_url }}   # ← ジョブの outputs を指す

jobs:
  apply:
    outputs:
      app_url: ${{ steps.result.outputs.app_url }} # ← ステップの outputs を指す
    steps:
      - id: result
        run: echo "app_url=https://..." >> "$GITHUB_OUTPUT"
```

```yaml
# 呼び出し側
  summary:
    needs: deploy-service
    steps:
      - run: echo "${{ needs.deploy-service.outputs.app_url }}"
```

**値を出すステップが `if:` で実行されなかった場合、`outputs` は空文字で返る**(エラーにはならない)。受け取る側で `${VAR:-(取得できませんでした)}` のような既定値を用意しておく。

これは「AWS を叩くジョブ」と「サマリを組み立てるジョブ」を分けるのに便利で、後者は `outputs` を受け取るだけなので **AssumeRole が要らない。**

## 8. 落とし穴 ⑤ `github` コンテキストは呼び出し側のもの

`github.workflow` は**呼び出し側のワークフロー名**になる(§3 の原因)。`github.event_name` も呼び出し側を起動したイベントで、`workflow_call` にはならない。**「自分は呼ばれているのか」を `github` コンテキストから判定するのは当てにできない。**

判定が必要なら **`workflow_call` にだけ宣言した入力を見る**のが確実(§2 と同じ道具)。

## 9. 上限

- **10 階層**まで(最上位の呼び出し元 + 再利用可能ワークフロー 9 段)。ループは不可
- **1 つのワークフローファイルから呼べるのは 50 本**まで(ユニーク数)
- Secret は**直接呼んだ相手にしか渡らない。** A → B → C の連鎖では、B が C に明示的に渡す(または `secrets: inherit`)必要がある

---

## composite action との使い分け

「共通処理をまとめる」手段はもう 1 つある。`action.yml` に書く **composite action**。境界はここ。

| | composite action | reusable workflow |
|---|---|---|
| まとめる単位 | **ステップ** | **ジョブ** |
| 走る場所 | 呼び出し側のジョブの中 | 別のジョブ(runner も別) |
| `secrets` コンテキスト | 使えない(`inputs` で渡す) | `secrets: inherit` が使える |
| `environment` | 持てない | **持てる**(= Environment secrets が引ける) |
| `permissions` | 呼び出し側のまま | 自分で下げられる |
| 並列 / `needs` | 不可(ステップなので直列) | 可 |

**このリポジトリが reusable workflow を選んだ理由は `environment` にある。** ロール ARN を Environment secrets に置いて `secrets.AWS_CFN_DEPLOY_ROLE_ARN` という同じ名前で stg / prod を切り替える設計(→ 設計書の決定17)なので、呼ばれる側が自分で `environment: ${{ inputs.env }}` を宣言できる必要がある。composite action ではこれができない。

逆に「同じジョブの中で使い回したいステップの塊」(例: JDK のセットアップ + Gradle キャッシュ)なら composite action のほうが軽い。

---

## つまずきポイント

- **`uses` のジョブに `runs-on` を書いてエラー。** 呼ばれる側が持つもの
- **`secrets: inherit` を忘れて `secrets.X` が空。** エラーではなく空文字になるので、AssumeRole の失敗として遅れて現れる
- **呼び出し側の `permissions` を消してしまう。** 「このワークフローは何も叩かないから要らない」は逆(§6)
- **`concurrency` を素直にコピーして実行が進まなくなる**(§3)。ログに出るのは「Queued」だけなので原因が見えにくい

## 用語集

| 用語 | 意味 |
|---|---|
| caller / 呼び出し側 | `uses:` で他のワークフローを呼ぶ側 |
| called / reusable workflow | `on: workflow_call` を持ち、呼ばれる側 |
| `secrets: inherit` | 呼び出し側が持つ Secret を丸ごと渡す指定。個別に列挙する `secrets: {NAME: ${{ secrets.NAME }}}` の代わり |
| composite action | `action.yml` にステップの塊を書く仕組み。呼び出し側のジョブの中で走る |

## 関連

- [ADR-0009](../../adr/0009-cfn-apply-as-the-single-cloudformation-caller.md) — このリポジトリで `workflow_call` を採った経緯と、却下した代替案
- [設計書 決定13 / 決定17 / 決定20](../../superpowers/specs/2026-08-19-phase13-cloudformation-design.md)
- [ci-with-github-actions.md](../ci-with-github-actions.md) — 自動テストを Actions で回すときのホスト / コンテナの境界線
- [公式: Reuse workflows](https://docs.github.com/en/actions/how-tos/reuse-automations/reuse-workflows) / [Reusing workflow configurations](https://docs.github.com/en/actions/reference/workflows-and-actions/reusing-workflow-configurations)
