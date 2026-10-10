# フェーズ19: DB を Aurora MySQL(プロビジョンド)に移し、Reader と Writer を使い分ける

日付: 2026-09-13
ステータス: 実装済み(ローカルで確認 2026-09-13)・AWS 未検証

方針 → [ADR-0016](../../adr/0016-aurora-provisioned-with-read-write-split.md)。
[ADR-0005](../../adr/0005-separate-db-users-for-app-and-migration.md)(DB ユーザーの分離)は **変更しない**。

## 1. 目的

会社で使われている **Aurora MySQL のプロビジョンドクラスタ**を、このリポジトリで建てて理解する。

「理解する」を具体的な達成条件にすると、次の 3 つになる。

1. **アプリが読み取り専用トランザクションを Reader に、それ以外を Writer に送っていることを、ローカルのテストと stg のメトリクスで確かめられること**
2. **書いた直後に読むとき何が起きるかを説明でき、このアプリでどこを Writer に固定したかを言えること**
3. **stg で実際にフェイルオーバーさせ、何秒エラーが続いたか、Reader への接続がどう偏ったかを数字で持っていること**

性能改善が目的ではない。この規模では RDS for MySQL 1 台で困っていない(→ ADR-0016)。

## 2. いま何が動いているか

| 要素 | 現状 |
| --- | --- |
| DB リソース | `AWS::RDS::DBInstance`(論理 ID `Database`)1 台、MySQL 8.4、`gp3` |
| 環境差 | stg: `db.t4g.micro` / Single-AZ / バックアップ 0 日。prod: `db.t4g.medium` / Multi-AZ / 35 日 |
| アプリの接続 | `spring.datasource.*` の 1 本(Hikari の既定。`application.yml` にプールの設定は無い) |
| 読み取りの目印 | `@Transactional(readOnly = true)` が `PostService`(タイムライン・詳細)、`CategoryService`、`AppUserDetailsService`、`AuthService.getCurrentUser` に付いている |
| アラーム | `DBInstanceIdentifier` 次元のメトリクス 4 本(CPU / FreeStorageSpace / FreeableMemory / DatabaseConnections)、ログ由来 2 本、イベント購読 `db-instance` 1 本 |
| ローカル | `mysql:8`(8.4)1 台 |
| ガード | `cfn-apply.yml` は Change Set の `Replacement` が `True` / `Conditional` のときだけ止まる(`AWS::ECS::TaskDefinition` は除外) |

AWS 環境は現在建てていない。**移行作業は無く、最初の構築から Aurora で作る。**

## 3. 制約として確定した事実

設計中に確かめたもの。すべて設計を左右した。
**(要検証)** が付いたものは、実装時に一次情報か実機で裏を取る。

### 3-1. Aurora MySQL 8.4 は GA 済み(2026-05)

MySQL 8.4 互換で、バージョン番号がコミュニティ版と揃った(例: `8.4.7`)。
**ローカルの `mysql:8` と今の RDS がどちらも 8.4 なので、開発と本番のバージョンが揃ったまま移せる。**
3.x に対する差分は Fast insert が使えないことだけ。新しいユーザーの既定認証は `caching_sha2_password`、
**新規クラスタは既定で TLS を強制する**(TLS 1.2 / 1.3 のみ)。

- `EngineVersion` は `8.4.mysql_aurora.8.4.7` の形(AWS の「Checking Aurora MySQL version numbers」で確認)。
  cfn-lint も実在するバージョンの一覧で照合しており、存在しない `8.4.mysql_aurora.8.4.99` はエラーになった
- パラメータグループのファミリー名は `aurora-mysql8.4` の見込み **(要検証)**。AWS のドキュメントに 8.4 の例が見当たらず、
  cfn-lint もファミリー名は検査しない(わざと `aurora-mysql9.9` にしても通った)。stg の構築で初めて分かる

### 3-2. Aurora は SQL を見て振り分けない

エンドポイントは 3 種類で、どれに繋ぐかはアプリが決める。

| エンドポイント | つながる先 |
|---|---|
| クラスタエンドポイント(Writer) | 常に Writer。フェイルオーバーすると新しい Writer に向き直る(DNS) |
| Reader エンドポイント | Reader のどれか(DNS で振り分け)。書き込みはエラーになる |
| インスタンスエンドポイント | その 1 台に固定 |

### 3-3. 読み取り専用の接続での書き込みは、ローカルでも止まる(実測で確定)

Spring の `@Transactional(readOnly = true)` はトランザクション開始時に JDBC 接続へ `setReadOnly(true)` を呼ぶ。
読み取り専用になった接続でデータを変える文を実行すると、**Connector/J がサーバに送る前に**
`Connection is read-only. Queries leading to data modification are not allowed.` で止める
(設計時は MySQL サーバの `Cannot execute statement in a READ ONLY transaction` で止まると見ていたが、実測では手前のドライバだった)。
そのため **MySQL 1 台のローカルでも、読み取り専用トランザクション内の書き込みはエラーになる。**
振り分けの目印も `readOnly` なので、「Reader に書き込みが行く」と「読み取り専用トランザクションで書く」は同じ状況になる。

**ただし `setReadOnlyDataSource` を使う構成では、Reader のプールに `read-only: true` が必須。**
`LazyConnectionDataSourceProxy` は Reader から借りた接続に `setReadOnly(true)` を呼ばない
(プールの既定として設定済みという前提。Spring のソースで確認)。付けないとローカルでは書き込みが素通りする。
→ `ReadWriteRoutingTest` で確認済み

### 3-4. 接続先はトランザクションの開始時に決まり、途中で変わらない

`@Transactional`(`readOnly` なし)の中で INSERT してから SELECT しても、全部 Writer に流れる。
遅延が問題になるのは **書き込みのリクエストが終わった後、別のリクエストで読むとき**だけ。

### 3-5. Aurora のレプリカ遅延はゼロではない

Reader は Writer とストレージを共有するが、AWS の説明では遅延は通常 100 ミリ秒未満で、ゼロではない。

### 3-6. 認証の読み取りはリクエストごとには走らない

コードで数えた結果。

| 読み取り | 実行されるタイミング |
|---|---|
| `AppUserDetailsService.loadUserByUsername` | パスワードでログインしたとき 1 回 |
| `AuthService.getCurrentUser`(`/api/auth/me`) | `plugins/auth.client.ts` のアプリ起動時 1 回、`auth/callback.vue` の Google ログイン後 1 回、ログイン成功時のレスポンス |
| リクエストごとのユーザー確認 | なし(ログイン状態は Redis のセッションにあり、DB を見るフィルタは無い) |

### 3-7. 書いた直後に読む流れは 3 つある

| 流れ | 書き込み(Writer) | 直後の読み取り | Reader に送ると起きること |
|---|---|---|---|
| 初回の Google ログイン | `GoogleAccountService` がユーザーを INSERT | `/auth/callback` → `/api/auth/me` | **未ログイン扱いになる** |
| メール確認 → ログイン | 確認済みフラグを UPDATE | `loadUserByUsername` | 「メール未確認」で弾かれる |
| パスワードリセット → ログイン | パスワードハッシュを UPDATE | `loadUserByUsername` | 新しいパスワードで入れない |
| 投稿の作成・削除 | INSERT / DELETE | なし(`Form.vue` / `Card.vue` はレスポンスの値を画面に反映し、読み直さない) | なし |

### 3-8. `db.t4g.medium` の `max_connections` の既定は 90

RDS for MySQL の同クラスより大幅に低い(今の prod のアラームしきい値は 307)。T 系は Aurora では開発・テスト向けとされている。

### 3-9. Blue/Green デプロイ中は接続数が 2 倍になる

今の設定(`CodeDeployDefault.ECSAllAtOnce`、`TerminationWaitTimeInMinutes: 0`)では、
Green のタスクが起動した時点で Hikari がプールを張り、Blue は停止されるまでプールを持ち続ける。
**Hikari は既定で `minimumIdle` = `maximumPoolSize` の固定サイズなので、使っていなくても接続は減らない。**

prod の最悪ケース(既定のまま):

| 条件 | 本数 |
|---|---|
| `WebMaxCapacity` 4 × Blue/Green で 2 倍 = 8 タスク × Writer プール 10 | 80 |
| マイグレーションのタスク(Hikari 10 + Flyway 1) | 11 |
| **Writer 合計** | **約 91(上限 90 を超える)** |
| Reader: 8 タスク × 10 が、DNS の振り分け次第で 2 台のうち 1 台に集中 | 最悪 80 |

### 3-10. Aurora のメトリクスとイベントは RDS for MySQL と形が違う

- **`FreeStorageSpace` は意味を持たない。** データはクラスタボリュームにあり自動で伸びる。
  インスタンスごとの一時領域は `FreeLocalStorage`(`db.t4g.medium` は最大 32 GiB)
- 次元に **`DBClusterIdentifier` + `Role`(WRITER / READER)** があり、役割単位で集計できる
- **`AuroraReplicaLagMaximum`**(Writer が発行、全 Reader の中で最大の遅延、ミリ秒)
- **フェイルオーバーのイベント(RDS-EVENT-0071 など)は `db-cluster` のソースにしか出ない**
- 役割単位の集計値に対して `Maximum` を取ると「一番高い 1 台」になるか **(要検証・stg で確認)**

### 3-11. `AWS::RDS::DBCluster` の制約

- `BackupRetentionPeriod` は **1〜35**(0 にできない)
- `DeleteAutomatedBackups` / `DeletionProtection` / `ManageMasterUserPassword` はクラスタにある
- **`DeletionPolicy` の既定は `Snapshot`**(DBInstance と同じ)
- 属性は `Endpoint.Address` / `Endpoint.Port` / `ReadEndpoint.Address` / `MasterUserSecret.SecretArn`(CloudFormation のリファレンスで確認)

### 3-12. リソースの種類は同じ論理 ID のまま変えられない

`Database`(DBInstance)を DBCluster にするには論理 ID を変えるしかなく、Change Set では
**旧リソースが `Action: Remove`、新リソースが `Action: Add`** になる。
**今のガードは `Replacement` しか見ていないので、DB が中身ごと消える更新を止めない。**

### 3-13. Performance Insights は 2026-07-31 に画面が CloudWatch Database Insights に移った

API と CloudFormation のパラメータはそのまま動き、何もしなければ Database Insights の Standard モードになる。
- Aurora MySQL で非対応なのは `db.t2` / `db.t3` / `db.t4g.micro` / `db.t4g.small` で、**`db.t4g.medium` は対応**(Database Insights の対応表で確認)

### 3-14. スロークエリの 3 設定はインスタンス単位のパラメータ(実装時に判明)

Aurora MySQL では `slow_query_log` / `long_query_time` / `log_output` は**インスタンス単位**のパラメータで、
クラスタパラメータグループには出てこない(AWS の Aurora MySQL パラメータ一覧で確認)。
設計時は決定13 で「クラスタパラメータグループへ移す」としていたが、**インスタンスの DB パラメータグループに置く**形に直した。

### 3-15. クラスタに属するインスタンスはスナップショットを取らずに削除される

CloudFormation のリファレンスどおり、`DBClusterIdentifier` を持つ `AWS::RDS::DBInstance` はスタック削除時にそのまま削除される
(スナップショットはクラスタ単位のもの)。`DeletionPolicy: Snapshot` の既定を気にする必要があるのはクラスタだけ。

## 4. 決定

### 決定1 RDS for MySQL は完全に置き換える

`app.yml` から `AWS::RDS::DBInstance`(単体)を消し、stg も prod も Aurora にする。パラメータでの切り替えは持たない。
RDS for MySQL の構成は凍結ブランチ `github-actions-deploy` に残っている。

### 決定2 エンジンは Aurora MySQL 8.4

ローカルの `mysql:8` は変えない。

### 決定3 インスタンスは stg が Writer 1 + Reader 1、prod が Writer 1 + Reader 2

- クラスは両環境とも `db.t4g.medium`
- `DbReaderCount: 1 | 2` をパラメータにし、Reader の 1 台目は常に作り、**2 台目にだけ `Condition`** を付ける
- サブネットは A / C の 2 つなので、Writer と Reader1 は別の AZ に置く(prod の Reader2 はどちらかと同じ AZ になる)
- `DbMultiAZ` は廃止(Reader が別 AZ にいることが同じ意味を持つ)

### 決定4 読み書きの振り分けは Spring 側で `readOnly` を目印に行う

`LazyConnectionDataSourceProxy#setReadOnlyDataSource`(Spring Framework 6.1.2 から)で、Writer 用と Reader 用の Hikari プールを 2 つ持つ。
`readOnly = true` のトランザクションは Reader、それ以外(トランザクション外を含む)は Writer。
ドライバは `mysql-connector-j` のまま。コード → `backend/src/main/java/com/example/app/config/DataSourceConfig.java`

- **設計時は `AbstractRoutingDataSource` + `LazyConnectionDataSourceProxy` の予定だった。** 実装前に、プロジェクトの Spring JDBC 7.0.8 に
  `setReadOnlyDataSource` が実在することを確かめ、ユーザーと相談して切り替えた(ルーティング用のクラスを自作しなくて済む)
- `LazyConnectionDataSourceProxy` が要るのは、ルーティングの判定より先に接続を取られると `readOnly` が決まっていないため
- **Reader のプールは `read-only: true` が必須**(3-3)
- Writer / Reader のプールは `@Bean(defaultCandidate = false)` にした。DataSource 型の bean が 3 つになると JPA などがプロキシを選べないため
- **Flyway には `spring.flyway.url` で Writer の URL を明示した。** 書かないと、`spring.flyway.user` があるときアプリの DataSource から
  URL を引き継ごうとするが、プロキシは URL を持たない(`FlywayAutoConfiguration#getMigrationDataSource` のソースで確認)。
  ローカルではテストと起動で Flyway が流れることを確認済み

### 決定5 ローカルは MySQL 1 台のまま、Writer と Reader に同じ接続情報を渡す

- 会社の開発環境と同じ形。ローカル用のレプリケーションや Reader 専用ユーザーは作らない
- 環境変数 `DB_READER_HOST` を足し、**未設定なら `DB_HOST` を使う**(docker compose もテストも無変更で動く)
- 振り分けの誤りは 3-3 の挙動でローカルでも検出できる。`ReadWriteRoutingTest` で確認済み
- テスト(`app_test`)も 1 台のまま

### 決定6 AWS でも Reader 用のプールは `app` ユーザーで接続する

ADR-0005 の 3 ユーザー構成(マスター / `migrate` / `app`)は変えない。Reader インスタンス自体が書き込みを拒否する。
**`DB_READER_HOST` は buildspec が Outputs の `DbReaderEndpoint` から差し込み**、人が値を書く場面を作らない。

### 決定7 認証の読み取りは Writer に固定する

`AppUserDetailsService.loadUserByUsername` と `AuthService.getCurrentUser` から `readOnly = true` を外す。
**後から読んだ人が「付け忘れ」と誤解して付け直さないよう、Writer に固定する理由をコメントに書く**(3-6・3-7 の要約と ADR-0016 へのリンク)。

タイムライン、投稿の詳細、カテゴリー、今後の検索ラボは `readOnly = true` のまま Reader に送る。

### 決定8 接続まわりは既定値のまま、stg でフェイルオーバーを実測する

JVM の DNS キャッシュ(`networkaddress.cache.ttl`)や Hikari の `maxLifetime` は先に調整しない。
**先に設定を変えると、効いたのかを比べる基準が無くなる。**
stg で `aws rds failover-db-cluster` を実行し、次を記録する。

- Writer への書き込みエラーが何秒続いたか
- Reader ごとの接続数の偏り(`DatabaseConnections` をインスタンス単位で見る)

秒数や偏りが問題なら、既定値の調整ではなく **AWS Advanced JDBC Wrapper の導入**を別途検討する
(フェイルオーバープラグインがトポロジーを見てつなぎ替え、読み書き分離プラグインは Reader の選び方も持つ)。

### 決定9 Hikari のプールは固定サイズのまま、大きさを Writer / Reader 別に明示する

- `minimumIdle` は設定しない(= `maximumPoolSize` と同じ固定サイズ。Hikari の推奨)
- `maximumPoolSize` を Writer / Reader 別に環境変数で渡す(名前は実装時に決める。例: `DB_WRITER_POOL_SIZE` / `DB_READER_POOL_SIZE`)
- **初期値は各 5**。prod の最悪ケースは Writer 8 × 5 + マイグレーション ≒ 46、Reader は 1 台に偏っても 40 で、上限 90 に対して人間の調査用と `db-ops` の分が残る
- stg で接続数を見て調整できるよう、taskdef に値を持たせる

### 決定10 アラームは役割単位(`DBClusterIdentifier` + `Role`)で張る

フェイルオーバーで役割が入れ替わっても「Writer の CPU」は新しい Writer を見続け、Reader の台数でアラーム数が変わらない。
READER は複数台の集計値なので統計は `Maximum` にする(3-10 の要検証)。

| 系統 | アラーム | 次元 | しきい値 |
|---|---|---|---|
| メトリクス | CPUUtilization | WRITER / READER | 今と同じ 90% |
| | FreeableMemory | WRITER / READER | RAM の 25% が目安(`db.t4g.medium` は 4 GiB) |
| | DatabaseConnections | WRITER / READER | **72(90 の 8 割)** |
| | FreeLocalStorage(`FreeStorageSpace` の置き換え) | WRITER / READER | 実装時に決める(一時領域の 10% が目安) |
| | **AuroraReplicaLagMaximum(新規)** | WRITER | 実装時に決める(決定7 の前提「遅延は普段小さい」が崩れたことに気付くため) |
| ログ | `[ERROR]` / スロークエリ急増 | — | 変更なし |

しきい値はパラメータにする(今の `Rds*Threshold*` と同じ扱い)。

### 決定11 イベント購読は `db-cluster` と `db-instance` の 2 本にする

| ソース | カテゴリ |
|---|---|
| `db-cluster` | failover / failure / maintenance |
| `db-instance` | availability / failure / low storage / maintenance / configuration change |

creation / deletion は今と同じく購読しない(建て直しのたびに飛ばないように)。

### 決定12 ロググループはクラスタ単位の名前で先に作る

`/aws/rds/cluster/<クラスタ識別子>/error` と `/slowquery`。Writer と Reader のログが同じグループに流れるので、
メトリクスフィルタとアラームはそのまま使える。**先に作って `DependsOn` させる理由は今と同じ**(削除時の孤児防止)。

### 決定13 既定で決めた項目

| 項目 | stg | prod | メモ |
|---|---|---|---|
| 自動バックアップの保持日数 | **1**(0 から変更) | 35 | 3-11 |
| `DeleteAutomatedBackups` | true | false | クラスタに付ける |
| `DeletionProtection` | false | true | クラスタに付ける |
| `DeletionPolicy` / `UpdateReplacePolicy` | `Delete` | `Delete` | クラスタとインスタンスの両方に明示する |
| ストレージタイプ | `aurora`(標準) | 同左 | I/O 最適化が得になるのは I/O が費用の大部分を占めるときだけ |
| Performance Insights | **true**(false から変更) | true | 3-13(対応を確認済み) |
| 拡張モニタリング | 60 秒 | 60 秒 | インスタンスごと |
| パラメータグループ | スロークエリの 3 設定は**インスタンスの DB パラメータグループ**(ファミリー `aurora-mysql8.4`)に置き、全インスタンスに付ける。クラスタパラメータグループは既定 | 同左 | 設計時はクラスタ側に移す予定だったが、Aurora MySQL ではインスタンス単位のパラメータだった(3-14) |
| TLS | 既定の強制のまま | 同左 | Connector/J と `mysql` クライアントは既定で TLS を使うので変更不要の見込み。stg で確認 |
| 消えるパラメータ | `DbAllocatedStorage` / `DbMaxAllocatedStorage` / `DbMultiAZ` / `RdsFreeStorageThresholdBytes` | | 代わりに `DbReaderCount`、プールの大きさ、新しいしきい値を足す |
| SNS トピックと Slack チャンネル | `rds-alerts` / `#njp-alerts-rds` のまま | | Aurora も RDS の一部で、名前空間も `AWS/RDS` |

### 決定14 `cfn-apply.yml` のガードは `Remove` も止める

**「基本は全リソースを止め、止めると困るものだけ理由を書いて外す」という今のガードの考え方に揃える。**

- 止める条件: `Replacement` が `True` / `Conditional`、**または `Action` が `Remove`**
- 除外: `Replacement` の判定から `AWS::ECS::TaskDefinition` を外す(今と同じ)。**`Remove` の除外は無し**
- 止めたときのメッセージを「新しい空の RDS に置き換わります」からクラスタ前提の文面に直す

`Remove` に除外が要らないのは、日常的に起きる `Remove` がこのリポジトリに無いため(確認した結果)。

| `Remove` が起きうる場面 | 日常的か |
|---|---|
| タスク定義の更新 | `Remove` にはならない(`Modify` + `Replacement: True`) |
| `ServiceEnabled` でオートスケーリング 3 つが消える | デプロイは 0 → N の向き(`Add`)だけ |
| `BasicAuthEnabled` で WAF が消える | `EnableBasicAuth` は環境ごとに固定 |
| `EnhancedMonitoringEnabled` / `RedisHasReplica` / `DbReaderCount` | 環境ごとに固定。変えるのは意図的な構成変更のときだけ |
| 論理 ID の変更 | 意図的なリファクタリング。**データを持つリソースだと 3-12 の穴そのもの** |

**許可リスト(データを持つ種類だけ止める)にしなかったのは、書き忘れた種類が素通りするため。**
フェーズ6 で画像バケットを足しても、何もしなくても守られる。

### 決定15 解除の入力 `allow_replacement` を `allow_destructive_changes` に改名する

止める対象に `Remove` が入るので、名前を中身に合わせる。`Replacement` と `Remove` を 1 つの入力で解除する
(どちらも「データを失うかもしれない操作を意識して許可する」同じ判断なので分けない)。

### 決定16 フェーズは 1 つにし、ADR は Aurora 化の 1 本だけ作る

- ADR-0016 を作る(戻すのが大変 / 背景を知らないと驚く / 本物のトレードオフがあった)
- **ガードの拡張は ADR にしない。** 既存の考え方を広げただけなので、この設計書と `cfn-apply.yml` のコメントに書く
- **`CONTEXT.md` は変更しない。** アプリの用語集で、Writer / Reader はインフラの言葉

### 決定17 シードでレプリカ遅延のアラームが鳴るのは既知の衝突として受け入れる

フェーズ9 のシード(posts 100 万件など)は Writer に一気に投入するので、`AuroraReplicaLagMaximum` が鳴るはず。
フェーズ8・10 のスロークエリと同じく、**閾値は緩めず人が無視する**(→ ADR-0010)。

## 5. 変更するファイル

### ワークフロー

- `.github/workflows/cfn-apply.yml` — ガードに `Remove` を足す、`allow_replacement` → `allow_destructive_changes`、メッセージの文面
- `cloudformation/pipeline.yml` / `cloudformation/app.yml` — `allow_replacement` に触れているコメント

### バックエンド

- データソースの設定クラス(新規。`config` パッケージ)— Writer / Reader の 2 プールとルーティング
- `backend/src/main/resources/application.yml` — `DB_READER_HOST`、プールの大きさ
- `AppUserDetailsService` / `AuthService` — `readOnly` を外し、理由をコメント
- テスト — `readOnly` のトランザクションが Reader 側のプールに行くこと / 読み取り専用トランザクション内の書き込みがエラーになること

### インフラ

- `cloudformation/app.yml` — DBCluster / DBInstance × 2〜3 / クラスタパラメータグループ / ロググループ / アラーム / イベント購読 2 本 / `DbOpsTaskDefinition` の `DB_HOST` と `MasterUserSecret` の参照先 / Outputs(`DbEndpoint` / `DbReaderEndpoint` / `DbPort` / `DbMasterSecretArn`)
- `cloudformation/params/stg.json` / `prod.json` — 決定3・9・10・13
- `deploy/buildspec.yml` — `__DB_READER_HOST__` と、プールの大きさの差し込み
- `deploy/taskdef.json` / `deploy/taskdef-migrate.json` — `DB_READER_HOST` とプールの大きさ

### ドキュメント

- `CLAUDE.md` — 技術スタック(「本番は RDS」)、プロジェクト概要の構成
- `docs/infrastructure/README.md` — 構成図、リソースの表、アラームの本数、`allow_replacement` の記述
- `docs/infrastructure/cloudformation-operations.md` — 改名した入力、手順の中の RDS の記述
- `docs/infrastructure/github-actions-oidc.md` — RDS に触れている箇所の確認
- `docs/tech-stack/README.md` — 本番の DB
- `docs/development/README.md` — 環境変数(`DB_READER_HOST`、プールの大きさ)
- `docs/slack/README.md` — `#njp-alerts-rds` に流れるものの説明
- `docs/adr/0010-monitoring-in-ephemeral-stack.md` — アラームの一覧(注記を足す)
- `docs/adr/0009-cfn-apply-as-the-single-cloudformation-caller.md` — ガードに触れている箇所の確認
- `docs/notes/cloudformation/cli-commands-and-change-sets.md` — `allow_replacement` の記述
- `docs/development/implementation-progress.md` — 進捗
- 学習ノート(新規)— [docs/notes/aurora/read-write-split.md](../../notes/aurora/read-write-split.md)(エンドポイントと読み書き分離、書いた直後に読む問題)/ [docs/notes/aurora/connection-pool-and-failover.md](../../notes/aurora/connection-pool-and-failover.md)(プールの見積もりとフェイルオーバー)

過去のフェーズの設計書(フェーズ13 など)は当時の記録なので書き換えない。

## 5-3. 実装で確定したこと(設計から動いた点)

- **振り分けは `setReadOnlyDataSource` にした**(決定4)。あわせて Reader のプールに `read-only: true` が必須と分かった(3-3)
- **読み取り専用の接続での書き込みを止めるのは Connector/J**(サーバではない)。エラー文は `Connection is read-only. Queries leading to data modification are not allowed.`
- **スロークエリの設定はインスタンスの DB パラメータグループのまま**(3-14。決定13 の表を修正)
- **インスタンスの論理 ID と名前は `DbInstance1〜3` / `...-db-1〜3`。** Writer / Reader を名前に入れると、フェイルオーバー後に名前が嘘をつくため。
  最初の Writer を `DbInstance1` にするため、Reader 側に `DependsOn: DbInstance1` を付けた(クラスタで最初に作られたインスタンスが Writer になる)
- **アラームは合計 14 本**(DB メトリクス 9 / DB ログ 2 / ECS 1 / Redis 2)。READER 側の統計は CPU・接続数が `Maximum`、空きメモリ・空き領域が `Minimum`
- 初期値: `DbEngineVersion` は `8.4.mysql_aurora.8.4.7`、`RdsFreeLocalStorageThresholdBytes` は 32 GiB の 10%(3435973836)、
  `RdsFreeableMemoryThresholdBytes` は 4 GiB の 25%(1073741824)、`RdsReplicaLagThresholdMilliseconds` は 1000
- **確認したこと**: バックエンドのテスト 49 本がすべて成功(`ReadWriteRoutingTest` 3 本を含む)/ 開発中の backend が devtools の再起動で正常に起動 /
  cfn-lint 1.56.3 で `app.yml` の指摘ゼロ(変更前のテンプレートは RDS の `EngineVersion: "8.4"` が許可リスト外として 1 件出ていた)/
  params 2 つとテンプレートの Parameters が過不足なく対応 / buildspec のプレースホルダと taskdef 2 つが一致 /
  ガードの判定を作り物の Change Set で試し、`Remove` と `Conditional` で止まり、`TaskDefinition` の作り直しは除外されること

## 6. 実装順序

1. **ガード** — `cfn-apply.yml` の `Remove` 判定と改名(AWS 不要)
2. **アプリの読み書き分離** — データソースの設定、認証の Writer 固定、テスト。ローカルで完結させる(AWS 不要)
3. **インフラ** — `app.yml` の Aurora 化、アラームとイベント購読、buildspec と taskdef
4. **stg を建てて実測する** — §7 の項目を 1 件ずつ記録する
5. **ドキュメント** — §5 のドキュメント、学習ノート、進捗

## 7. 実測で覆りうる項目

stg を建てたときに 1 件ずつ潰す。

- 3-1: パラメータグループのファミリー名 `aurora-mysql8.4`(違えばパラメータグループの作成で落ちる)
- 3-10: 役割単位の集計値の `Maximum` / `Minimum` が「一番悪い 1 台」を表すか。`AuroraReplicaLagMaximum` が `Role=WRITER` の次元でデータを持つか
- 決定3: `DependsOn` の順で `DbInstance1` が Writer になるか
- 決定4: db-migrate タスクで Flyway が `migrate` ユーザーで Writer に流れること(ローカルは確認済み)
- 決定8: フェイルオーバーで書き込みエラーが何秒続くか、Reader ごとの接続数の偏り
- 決定9: プールの大きさ 5 で足りるか(stg の `DatabaseConnections` と、アプリの接続待ち)
- 決定13: TLS の強制下で Connector/J と `db-ops` の `mysql` クライアントが追加設定なしで繋がるか
- 決定10: `FreeLocalStorage` と `AuroraReplicaLagMaximum` のしきい値が妥当か
- `AutoMinorVersionUpgrade: true` でマイナーバージョンが上がったとき、テンプレートの `EngineVersion` との差で更新が失敗しないか
  (作り捨ての環境ではメンテナンスウィンドウをまたぐことがほぼ無いので、起きにくい)
- 構築・撤収にかかる時間(RDS 1 台のときは構築 15〜25 分、撤収 10〜15 分だった)

## 8. 申し送り

- **フェーズ6(画像)**: 画像バケットは決定14 の全リソース対象のガードで `Remove` も止まる。追加の作業は無い
- **フェーズ8(検索ラボ)・フェーズ10(index 実験)**: 検索 API は `readOnly = true` で Reader に行く。
  EXPLAIN も Reader で取ることになる。index の追加(`ALTER`)は `db-task.yml` の任意 SQL で Writer に流す
- **フェーズ9(シード)**: 書き込みは Writer。決定17 のアラームが鳴る
