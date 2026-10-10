# DB を Aurora MySQL(プロビジョンド)に移し、読み取り専用トランザクションを Reader に送る

日付: 2026-09-13
ステータス: accepted

## 決定

AWS 上の DB を **RDS for MySQL(`AWS::RDS::DBInstance` 1 台)から Aurora MySQL 8.4 のプロビジョンドクラスタに置き換える。**
切り替え式にはせず、stg も prod も Aurora にする。

| | stg | prod |
|---|---|---|
| インスタンスクラス | `db.t4g.medium` | `db.t4g.medium` |
| Writer | 1 台 | 1 台 |
| Reader | 1 台 | 2 台 |

**Reader は「フェイルオーバー待ち」ではなく、アプリが実際に使う。**
Spring の `@Transactional(readOnly = true)` を目印に、読み取り専用トランザクションを Reader エンドポイントへ、
それ以外を Writer(クラスタエンドポイント)へ送る。
振り分けは Spring 側(`LazyConnectionDataSourceProxy#setReadOnlyDataSource`。Spring Framework 6.1.2 から)で行い、
ドライバは `mysql-connector-j` のまま変えない。
(設計時は `AbstractRoutingDataSource` + `LazyConnectionDataSourceProxy` の予定だったが、
同じことをルーティング用のクラスを自作せずにできる公式の機能があったので実装時に切り替えた)

ただし **認証の読み取り(`AppUserDetailsService.loadUserByUsername` / `AuthService.getCurrentUser`)は Writer に固定する。**

## 背景と理由

実務(会社)の DB が Aurora MySQL のプロビジョンドであり、**その構成を手元で建てて理解すること**が目的。
性能上の必要から移すのではない。この規模では RDS for MySQL 1 台で困っていない。

Reader を実際に使うことにしたのは、Aurora を「高い RDS」として置くだけでは、
実務で効いてくる論点 — **どの読み取りを Reader に逃がすか、書いた直後に読むとどうなるか、接続数の上限** —
に触れられないため。

### なぜ SQL の文面ではなくトランザクション単位で振り分けるのか

Aurora もリードレプリカも、SQL を見て自動で振り分けてはくれない。用意されるのは行き先の違うエンドポイントだけで、
どちらに接続するかはアプリが決める。SQL を解析して振り分けるのはプロキシ(ProxySQL など)の仕事だが、
`SELECT ... FOR UPDATE` の誤送信やトランザクション途中での行き先の変化といった落とし穴がある。
このアプリの読み取り系メソッドにはすでに `readOnly = true` が付いていたので、それをそのまま目印にした。

### なぜ認証の読み取りだけ Writer に固定するのか

Aurora の Reader はストレージを共有するが、遅延はゼロではない(AWS の説明では通常 100 ミリ秒未満)。
**初回の Google ログインは「ユーザーを INSERT → リダイレクト → `/api/auth/me`」がほぼ連続して起き**、
Reader が追いついていなければ未ログイン扱いになる。パスワードリセット直後のログインも同じ形をしている。

一方、この 2 つは主キーかユニークキーで 1 行引くだけで、しかも**リクエストごとには走らない**
(ログイン状態は Redis のセッションにあり、DB を見るフィルタは無い)。
Reader に逃がす価値があるのは大量の行を走査する読み取り(タイムライン、検索ラボ、index 実験)であり、
「回数は少なく、1 回は軽く、遅れは許されない」認証の読み取りは Writer に置いても失うものがほとんど無い。

## 検討したが採らなかった選択肢

- **RDS for MySQL と Aurora をパラメータで切り替えられるようにする** — ロググループ名、アラームの次元、
  イベント購読の種類、Outputs の `GetAtt` まで `!If` が入る。RDS for MySQL の構成は
  凍結ブランチ `github-actions-deploy` に動く記録として残っている(→ [ADR-0012](0012-deploy-method-per-branch.md))
- **Aurora Serverless v2** — 会社がプロビジョンドを使っているので、目的に合わない
- **Aurora MySQL 3.x(MySQL 8.0 互換)** — ローカルの `mysql:8` と今の RDS が 8.4 なので、揃えたまま移せる 8.4 を選んだ
- **AWS Advanced JDBC Wrapper の読み書き分離プラグイン** — フェイルオーバーの切り替えも速くなるが、
  振り分けの判断がドライバの中に入って見えにくくなる。アプリ側の目印(`readOnly`)は共通なので後から載せ替えられる。
  フェイルオーバーの実測値を見てから判断する
- **読み書きを分離せず、Reader はフェイルオーバー先と人間の調査用にする** — 実務でも多い構成だが、
  上に書いた論点に触れられない
- **Reader 用のプールを別の DB ユーザー(`SELECT` のみ)で接続する** — 実務では少数派。
  Reader インスタンス自体が書き込みを拒否し、ローカルでも Reader のプールを `read-only: true` にしておけば
  Connector/J が `Connection is read-only` で止める(実測)。[ADR-0005](0005-separate-db-users-for-app-and-migration.md) の 3 ユーザー構成は変えない
- **書き込んだユーザーのリクエストだけ数秒間 Writer に固定する(セッションに印を付ける)** — 汎用的だが、
  ルーティングがセッションに依存して仕組みが大きくなる。今のアプリで困る場面は認証の固定だけで塞がる

## 結果として生じること

- **stg の DB 固定費が上がる。** `db.t4g.micro` 1 台から `db.t4g.medium` 2 台になる(Aurora の最小クラスが `db.t4g.medium`)。
  自動バックアップも 0 日にできず、最小の 1 日になる
- **接続数の上限が大きく下がる。** Aurora MySQL の `db.t4g.medium` の `max_connections` の既定は 90。
  Blue/Green デプロイ中は旧タスクと新タスクが同時にプールを持つので、Hikari の既定(10 本 × Writer / Reader の 2 プール)のままだと prod の Writer が上限を超える。
  **`maximumPoolSize` を Writer / Reader 別に明示して環境変数で渡す**
- **ローカルでは遅延を再現できない。** ローカルは会社と同じく MySQL 1 台で、Writer と Reader に同じ接続情報を渡す。
  書いた直後に読む問題は、設計で避ける(認証の固定)か stg で確かめる
- **フェイルオーバーの挙動は既定値のまま実測する。** JVM の DNS キャッシュや `maxLifetime` は先に調整しない。
  秒数や Reader への接続の偏りが問題なら、既定値の調整ではなく JDBC Wrapper の導入を検討する
- **監視を組み直す。** `FreeStorageSpace` は Aurora では意味を持たず、アラームの次元はインスタンス単位から
  役割単位(`DBClusterIdentifier` + `Role`)に変わり、`AuroraReplicaLagMaximum` のアラームが増える。
  フェイルオーバーのイベントは `db-cluster` 側にしか出ないので、イベント購読は 2 本になる(→ [ADR-0010](0010-monitoring-in-ephemeral-stack.md))
- **フェーズ9 のシードでレプリカ遅延のアラームが鳴るはず。** posts 100 万件などを Writer に一気に投入するため。
  フェーズ8・10 のスロークエリと同じく、閾値は緩めず人が無視する
- **凍結ブランチ `github-actions-deploy` は RDS for MySQL のまま残る。** 両ブランチで DB の構成が違う

## 関連

- [ADR-0005](0005-separate-db-users-for-app-and-migration.md) — DB ユーザーの分離(変更なし)
- [ADR-0010](0010-monitoring-in-ephemeral-stack.md) — 監視を使い捨てスタックに入れる方針
- [ADR-0012](0012-deploy-method-per-branch.md) — ブランチごとのデプロイ方式(凍結ブランチの扱い)
- 設計 → [フェーズ19 設計書](../superpowers/specs/2026-09-13-phase19-aurora-design.md)
