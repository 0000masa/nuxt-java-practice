# デプロイ方式をブランチで分ける

日付: 2026-09-05
ステータス: accepted

## 決定

**デプロイ方式の異なる 2 つの構成を、ブランチで分けて持つ。**

| ブランチ | デプロイ方式 | 状態 |
| --- | --- | --- |
| `main` | CodePipeline + CodeBuild + CodeDeploy | これから育てる |
| `github-actions-deploy` | GitHub Actions + CloudFormation スタック更新(ネイティブ Blue/Green) | **凍結** |

- **`github-actions-deploy` は今のコミットで止める。** アプリ実装(フェーズ5 以降)もドキュメントも
  以後は `main` だけに積む。`main` からのマージはしない
- `main` の ADR とドキュメントだけが育つ。凍結ブランチには当時のものがそのまま残る
- `main` で建てた環境と `github-actions-deploy` で建てた環境は**同時に建てない**
  (スタック名も ECR リポジトリも共用のため)

設計 → [フェーズ16 の設計書](../superpowers/specs/2026-09-05-phase16-codepipeline-design.md)

## 背景と理由

会社で使われていた Code 系のデプロイを学ぶために、このリポジトリでも同じ形を作りたい。
しかし**ネイティブ Blue/Green と CodeDeploy は同じ ECS サービスの上で両立しない。**
`AWS::ECS::Service` の `DeploymentController` は 1 サービスに 1 つで、しかも
**既存サービスに後から `CODE_DEPLOY` を付ける更新は ECS の API が受け付けない。**

したがって「両方いつでも使える」形は最初から存在しない。選べるのは次の 3 つだった。

1. **`app.yml` にパラメータを持たせ、`Fn::If` で作成時に切り替える**
2. **ブランチで分ける**(この決定)
3. **Code 系専用の 2 つ目の ECS サービスを同じスタックに建てる**

1 は「作り捨て運用だから作成時に選べば `DeploymentController` の不変性は問題にならない」という点で正しかったが、
**分岐が 1 か所では済まない。** CODE_DEPLOY にすると `DeploymentConfiguration`(`Strategy` /
`BakeTimeInMinutes` / `DeploymentCircuitBreaker`)も `LoadBalancers[].AdvancedConfiguration`
(`AlternateTargetGroupArn` / `ProductionListenerRule` / `RoleArn`)もまるごと使えなくなり、
さらに ALB のリスナー構成そのものが変わる(→ [ADR-0013](./0013-app-deploy-with-code-services.md))。
`Fn::If` + `AWS::NoValue` で書けはするが、**テンプレートの読みやすさを大きく損なう。**

3 はリソースを二重に持つ割に、学びの中心である CodeDeploy の挙動は 2 でも同じだけ得られる。

**ブランチで分ければテンプレートに分岐が 1 つも要らない。** これが決め手。

## 結果として生じること

### 1. `github-actions-deploy` で建てられるアプリはフェーズ4 相当で固定される

凍結する以上、いいね・画像・プロフィール・検索ラボは向こうには入らない。
向こうの価値は「GitHub Actions + CloudFormation スタック更新でデプロイする構成の、動く記録」であって、
アプリの新しさではない。

### 2. `main` が壊れたときの退避先として機能する

Q1 の時点で「並走」を選んだ動機は「壊れても既存の運用に戻れる」ことだった。
凍結ブランチをチェックアウトすれば今でも建てられるので、そこは満たされている(アプリは古いが動く)。

### 3. ドキュメントの正が `main` にしかなくなる

`github-actions-deploy` の `docs/` は凍結時点のまま古びていく。とくに ADR-0007 は
`main` では superseded になるが、向こうでは accepted のまま残る。**これは意図した状態である**
(その ADR は向こうの構成を正しく説明している)。

### 4. CLAUDE.md にブランチの役割を書く必要がある

セッションを始めた Claude が「今どちらのブランチにいるか」でデプロイ方式の前提が変わるため。

## 検討したが採らなかった選択肢

- **`main` を GitHub Actions のままにし、`codepipeline-deploy` ブランチで Code 系を作る** —
  実績のある方を幹に残す形。採らなかったのは、**この先アプリを実装していくのは `main`** であり、
  幹に置いた側が育つから。学びたい方を幹にしないと、Code 系ブランチが常に遅れていく

- **既存の 5 ワークフローをすべて Code 系に置き換える** — 会社の構成に一番近い。
  採らなかったのは、構築と撤収まで Code 系に寄せるとパイプラインをアプリスタックの外に出すしかなくなり、
  かつ「作り捨て」運用と噛み合わないため。**Code 系が担うのはアプリのデプロイだけ**とした
  (→ [ADR-0013](./0013-app-deploy-with-code-services.md))

- **学習専用の最小スタックを別に作って壊す** — 本体の `app.yml` に一切触らずに済む。
  採らなかったのは、実際のアプリで動かないと「タスク定義の所有権」「migrate の順序」「ALB の構成」
  といった**本当に難しい部分に一つも当たらない**ため。実際、設計中に出てきた論点はすべてそこだった
