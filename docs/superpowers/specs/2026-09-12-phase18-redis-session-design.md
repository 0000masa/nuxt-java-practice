# フェーズ18: セッションストアを Redis(ElastiCache)に移す

日付: 2026-09-12
ステータス: 実装済み(ローカルは実機確認済み / AWS 未検証)

方針 → [ADR-0015](../../adr/0015-session-store-on-redis.md)(保存先を Redis に移す)。
[ADR-0002](../../adr/0002-session-cookie-over-jwt.md)(セッション Cookie 方式)は **supersede しない**。
解説 → [docs/notes/redis/](../../notes/redis/)

## 1. 目的

会社のログイン基盤で使われていた **Redis によるセッション管理**を、このリポジトリで再現して理解する。

「理解する」を具体的な達成条件にすると、次の 2 つになる。

1. **ローカルで GUI を開き、ログイン → リロード → ログアウトでキーがどう増減するかを目で追えること**
2. **本番(ElastiCache)で何を用意しなければならないかを、自分で建てて把握していること**

性能改善が目的ではない。**この規模では MySQL でも Redis でも体感差は無い**(→ ADR-0015)。

## 2. いま何が動いているか

| 要素 | 現状 |
| --- | --- |
| 依存 | `spring-boot-starter-session-jdbc` |
| 保存先 | MySQL の `SPRING_SESSION` / `SPRING_SESSION_ATTRIBUTES`(`V3__create_spring_session_tables.sql`) |
| 期限切れの掃除 | Spring Session JDBC の `cleanup-cron`(毎分 `DELETE FROM SPRING_SESSION WHERE EXPIRY_TIME < ?`) |
| 全端末ログアウト | `UserSessionManager` が `FindByIndexNameSessionRepository.findByPrincipalName(email)` で引く |
| ローカル | コンテナ 6 つ(nuxt / backend / mysql / minio / minio-init / mailpit) |
| テストの分離 | `build.gradle` の `test` タスクが `DB_NAME` を `app_test` に差し替える |

保存先を 5 段階に並べた
[session-store-and-other-frameworks.md](../../notes/java/spring/session-store-and-other-frameworks.md) では、
このプロジェクトは **③(共有 RDB)** に位置づけられている。本フェーズで **④(インメモリストア)** に移る。

## 3. 制約として確定した事実

設計中に確かめたもの。すべて設計を左右した。
**(要検証)** が付いたものは、実装時に一次情報に当てて裏を取る。

### 3-0. Spring Boot 4 で設定キーが `spring.session.data.redis.*` に変わっている(実測で判明)

設計時に見落とし、**実装中に踏んだ**。記事でよく見る `spring.session.redis.repository-type` は
Boot 4.0.0 で `deprecated`、しかも **`level: error`**(= バインドされない)になっている。

```json
{ "name": "spring.session.redis.repository-type", "deprecated": true,
  "deprecation": { "level": "error",
                   "replacement": "spring.session.data.redis.repository-type",
                   "since": "4.0.0" } }
```

書いても黙って無視され、3-1 の起動失敗がそのまま起きる。
**接続側の `spring.data.redis.*` は Boot 3 から変わっていない**ので、片方だけ直して詰まりやすい。

### 3-1. Spring Session Redis の既定実装は `FindByIndexNameSessionRepository` を実装しない

Spring Boot 3.0 以降、既定は **`RedisSessionRepository`**(それ以前は indexed が既定だった)。
これは principal 名の索引を持たないため `FindByIndexNameSessionRepository` を実装していない。

→ 既定のまま依存を差し替えると、`UserSessionManager` の DI が解決できず **アプリが起動しない**。
`TaskRunner` の javadoc が記録している「実測で確認した起動失敗」とまったく同じ現象になる。
**`spring.session.data.redis.repository-type: indexed` の明示が必須**(キー名は 3-0 のとおり Boot 4 で変わっている)。

### 3-2. `RedisIndexedSessionRepository` は起動時に Redis へ接続する

keyspace notifications を購読するために `RedisMessageListenerContainer` を起こす。
購読は「繋ぎっぱなしにして通知を待つ」動作なので、**Lettuce の遅延接続では成立しない**。

→ **`migrate` のタスクモードも Redis を要求する。**
タスクモードは `spring.main.web-application-type=none` を使えない(→ `TaskRunner` の javadoc)ため、
Spring Session は必ず初期化される。

> **【実装時の訂正】** 当初この項に「タスク定義 3 つすべて」と書いたが、**2 つが正しい**。
> `DbOpsTaskDefinition` は Spring Boot ではなく `public.ecr.aws/docker/library/mysql:8` を動かして
> SQL を流すだけなので、Redis は要らない。決定9 を訂正済み。

### 3-3. Redis の TTL はキー単位で、SET の要素ごとには張れない

セッション本体 `spring:session:sessions:<id>` は TTL で自動的に消えるが、
principal 索引の SET に入っている `<id>` という**文字列**は、誰かが `SREM` しない限り残る。

→ 索引の掃除に **期限切れ通知(`notify-keyspace-events`)が要る**。
無効でも「引く」動作(= 全端末ログアウト)は成立するが、
**タイムアウトしたセッションの ID が索引に溜まり続ける**。
明示ログアウトやパスワード変更による削除は `deleteById` が索引も直接消すので、通知が無くても綺麗に消える。

### 3-4. Redis は期限切れキーを即座には消さない

消えるのは ①誰かがそのキーに触ったとき ②Redis の定期サンプリングに当たったとき、のどちらか。
**誰もアクセスしないセッション(= まさにタイムアウトしたもの)は、いつ消えるか分からない。**

→ `RedisIndexedSessionRepository` は 3 種類目のキー `spring:session:expirations:<分単位のepoch>` を持ち、
1 分ごとのスケジュールタスクで「この分に切れるはずのキー」に `EXISTS` を打つ。
**わざと触ることで Redis に期限切れを気づかせ、`expired` イベントを発火させている。**

### 3-5. ElastiCache Serverless はパラメータグループを持てない

→ `notify-keyspace-events` を有効化できないため、3-3 の索引掃除が成立しない。
加えて最小構成でも月 $90 前後で、**撤収運用と噛み合わない**(`cache.t4g.micro` は約 $0.016/時)。

### 3-6. `AWS::ElastiCache::CacheCluster` は暗号化と AUTH に対応しない

`AtRestEncryptionEnabled` / `TransitEncryptionEnabled` / `AuthToken` は
**`AWS::ElastiCache::ReplicationGroup` 専用**のプロパティ。

→ ノード 1 台でも `ReplicationGroup`(`NumCacheClusters: 1`、クラスターモード無効)で書く。
後からレプリカを足すのもパラメータ 1 つで済む。

### 3-7. ElastiCache の Redis OSS は 7.1 で止まっている

2024 年の Redis 社のライセンス変更(RSALv2 / SSPL)を受けて AWS は Valkey に舵を切った。
Valkey は 8.x が使え、料金も約 20% 安い。

→ それでも Redis OSS 7.1 を採る(用語を割らないため → ADR-0015)。
ローカルは `redis:7` に揃え、**メジャーバージョンを一致させる**。

### 3-8. ElastiCache の既定 `maxmemory-policy` は `volatile-lru`

TTL 付きキーを古い順に捨てる設定。**Spring Session のキーは全部 TTL 付き。**

→ 既定のままだと、メモリ逼迫時にセッションが静かに消える。**`noeviction` を明示する。**

### 3-9. `notify-keyspace-events` は ElastiCache のパラメータグループで変更できる(要検証)

過去に ElastiCache 側で制限されていた時期があったパラメータのため、
**実装時に AWS の「Redis のパラメータ」一覧に当てて裏を取る。**
もし変更不可だった場合、3-5 の判断(Serverless を却下する根拠のひとつ)が揺らぐ。
ただし料金の根拠は残るので、**ノード指定型という結論自体は変わらない。**

## 4. 決定

### 決定1 セッションストアは Redis に一本化する

JDBC と Redis を切り替え可能にはしない。設定・ドキュメント・テストが二重になり、
**どちらの構成も"本物"にならない**ため(→ ADR-0015)。

### 決定2 `spring.session.data.redis.repository-type: indexed` を明示する

3-1 の帰結。これにより `UserSessionManager` は **1 行も変えずに済む**。

### 決定3 ローカルは `redis` 公式イメージ + `redis/redisinsight` を別コンテナで置く

`redis` 公式イメージに GUI は入っていない。GUI 付きなのは `redis/redis-stack` だが、
それは **素の Redis + モジュール + GUI** であり、本番(ElastiCache)に無いモジュールがローカルにだけ入る。
さらに `redis-stack` は Redis 8 でモジュールが本体に統合されたことで 7.4 世代で止まっており、
**「GUI 付きの Redis イメージ」という選択肢はこの先そもそも無くなる**。
RedisInsight は本体に統合されず今後も別イメージのままなので、**最初から分けておく。**

### 決定4 ローカルの Redis は AOF で永続化する

`redis-data` を named volume で用意し、`--appendonly yes` を付ける。
`mysql-data` / `minio-data` と方針が揃い、`docker compose down` のたびにログインし直さずに済む。
GUI でキーを観察する作業も再起動をまたいで続けられる。
「落ちたら消える」ことは `docker compose exec redis redis-cli FLUSHALL` でいつでも体験できる。

### 決定5 テストは専用の `redis-test` コンテナに繋ぐ

`build.gradle` の `test` タスクで `REDIS_HOST` を `redis-test` に差し替える。
テスト用コンテナは **永続化しない**(volume なし)が、`--notify-keyspace-events Egx` は本番と揃える。

> **MySQL とは分離の方式が違う。** MySQL は「同じコンテナで database を分ける」(`app` / `app_test`)、
> Redis は「コンテナごと分ける」。Redis にも論理データベース番号(0〜15)があるので同じ形にはできるが、
> **分離の強さを優先した。** この非対称は `docs/test/README.md` に明記する。

### 決定6 ElastiCache は `ReplicationGroup` ノード 1 台で建てる

クラスターモード無効、`NumCacheClusters: 1`、`cache.t4g.micro`。
専用のサブネットグループとパラメータグループと SG を作り、**プライベートサブネット**に置く。
ノード数・ノードタイプ・しきい値は `Parameters` 化して `params/` で環境ごとに変える。

### 決定7 エンジンは Redis OSS 7.1、ローカルは `redis:7`

3-7 の帰結。`mysql:8` と同じ「メジャーだけ指定」の粒度に揃える。

### 決定8 保管時・転送時暗号化を有効にし、AUTH トークンを SSM SecureString に置く

`AtRestEncryptionEnabled: true` / `TransitEncryptionEnabled: true` / `AuthToken`。

ElastiCache には RDS の `ManageMasterUserPassword` に相当する自動生成が無く、
**既定では認証が一切ない**(SG さえ通れば誰でも `KEYS *` が打てる)。
入っているのはセッション ID そのものなので、**読めた人はそのまま成りすませる**。

→ トークンは自分で作って SSM SecureString に置き、taskdef の `secrets` で注入する。
既存の `google_client_secret` / `app_db_password` と完全に同じ形。
**値そのものはリポジトリに書かない**(CLAUDE.md の方針)。作り方だけ手順書に書く。

アプリ側は `REDIS_SSL` / `REDIS_PASSWORD` の 2 変数で、ローカル(平文・認証なし)と切り分ける。

### 決定9 Spring Boot を動かす ECS タスク定義 2 つに Redis の接続情報を渡す

3-2 の帰結。`AppTaskDefinition` と `MigrateTaskDefinition` に
`REDIS_HOST` / `REDIS_PORT` / `REDIS_SSL`(environment)と `REDIS_PASSWORD`(secrets)を書く。

> **当初「3 つすべて」としていたが実装時に訂正した。**
> `DbOpsTaskDefinition` は MySQL クライアントのイメージで Spring Boot を起動しないため不要。

**渡し忘れるとマイグレーションタスクが起動できず、デプロイが止まる。**
ECS の SG は 3 タスク共通なので、**Redis SG の ingress ルールは 1 本で足りる。**

タスク実行ロール(`TaskExecutionRole`)は SSM パスにワイルドカードで権限を持っているので、
`redis_auth_token` を足しても IAM の変更は要らない。

### 決定10 セッションのシリアライズは既定の JDK のままにする

JSON 化すると GUI で中身まで読めるが、自作の `AppUserDetails` / `AppOidcUser` に
Jackson の mixin と JSON 復元用コンストラクタが要る(→ ADR-0015)。

**JDK のままでも GUI で見えるものは多い。**

| キー | 型 | 読めるもの |
| --- | --- | --- |
| `spring:session:sessions:<uuid>` | HASH | キー名・フィールド名(`creationTime` / `lastAccessedTime` / `maxInactiveInterval` / `sessionAttr:SPRING_SECURITY_CONTEXT`)。**値はバイナリ** |
| `spring:session:sessions:expires:<uuid>` | STRING | TTL |
| `spring:session:expirations:<epoch>` | SET | その分に期限が来るセッション |
| `spring:session:index:...PRINCIPAL_NAME_INDEX_NAME:<email>` | SET | **キー名にメールアドレスが入る**。誰が何セッション持っているか |

つまり「誰が・いくつ・あと何秒」は全部見え、読めないのは
MySQL 時代も BLOB で読めなかった部分だけ。**JSON 化の手順と落とし穴はノート側に書く。**

### 決定11 `notify-keyspace-events: Egx` を有効化する

3-3 / 3-4 の帰結。ローカルの `redis` コンテナも
`command: redis-server --appendonly yes --notify-keyspace-events Egx` で揃える。

### 決定12 `maxmemory-policy: noeviction` を明示する

3-8 の帰結。埋まったら書き込みを拒否してエラーになる — 派手に壊れるが、**壊れたことが分かる**。
`volatile-lru` は「消えても再計算すればいい」キャッシュ向けの既定値で、
**セッションストアは再計算できない**ので前提が違う。パラメータグループにこの理由をコメントで残す。

### 決定13 監視は Redis 専用の SNS トピックと Slack チャンネルを新設する

アラーム 2 本。

| アラーム | メトリクス | 意味 |
| --- | --- | --- |
| メモリ逼迫 | `DatabaseMemoryUsagePercentage` | `noeviction` で書き込み拒否が始まる手前で気づく |
| 追い出し検知 | `Evictions` | **`noeviction` なら常に 0 のはず。**0 でなければパラメータグループが効いていない |

`RedisAlertsTopic` + `AWS::Chatbot::SlackChannelConfiguration` + `SlackChannelIdRedis` パラメータ。
Slack 側のチャンネル作成手順は `docs/slack/README.md` に追記する。

### 決定14 `docs/notes/redis/` を 3 ファイルに分ける

| ファイル | 内容 |
| --- | --- |
| `redis-basics.md` | データ型、TTL と遅延削除、論理データベース、永続化(RDB / AOF)、単一スレッド |
| `session-management.md` | GUI でキーの増減を追う実演、索引と全端末ログアウト、シリアライズ、MySQL 時代との対比 |
| `elasticache.md` | ノード型と Serverless、パラメータグループ、暗号化と AUTH、監視、料金 |

**ノートは実装して実際に動かしてから書く。** 推測で書いたキー名や TTL は必ずどこかズレる。

### 決定15 ADR-0002 は supersede せず、注記だけ足す

JWT を却下した理由は今も有効で、`superseded` にすると
**「JWT を発行しないという判断も無効になった」と読まれる**。
タイトルに `(Spring Session JDBC)` が残るのは、ADR が
**決定した時点の記録であって現状の説明書ではない**ため。

### 決定16 MySQL のセッションテーブルは V4 で DROP する

Flyway の鉄則どおり `V3__create_spring_session_tables.sql` は消さず変えず、
`V4__drop_spring_session_tables.sql` で打ち消す。
V3 のファイルにコメントを足すこともしない(**チェックサムが変わる**)。記録は V4 側に書く。

### 決定17 `CONTEXT.md` には何も足さない

`CONTEXT.md` は**このアプリ固有のドメイン語彙**だけを置く場所で、実装の詳細を入れない。
実際いま載っているのは投稿・いいね・検索ラボといったアプリの言葉だけで、「セッション」すら無い。
Redis / ElastiCache / セッションストアは一般的な技術用語なので、ここに入れると基準が崩れる。

## 5. 変更するファイル

### バックエンド

| ファイル | 変更 |
| --- | --- |
| `backend/build.gradle` | `session-jdbc` → `data-redis` + `spring-session-data-redis`。`test` タスクに `REDIS_HOST=redis-test` |
| `backend/src/main/resources/application.yml` | `spring.data.redis.*`(host / port / password / ssl)、**`spring.session.data.redis.repository-type: indexed`**(3-0)。`spring.session.jdbc.*` を削除。`timeout` のコメントを Redis 版に修正 |
| `backend/src/main/resources/db/migration/V4__drop_spring_session_tables.sql` | **新規**。2 テーブルを DROP |
| `backend/src/main/java/.../UserSessionManager.java` | **コードは変更なし。** javadoc の「(MySQL)」「`SPRING_SESSION.PRINCIPAL_NAME`」の記述を Redis 版に修正 |
| `backend/src/main/java/.../config/TaskRunner.java` | javadoc の「Spring Session JDBC」を Redis 版に修正 |

### ローカル環境

| ファイル | 変更 |
| --- | --- |
| `docker-compose.yml` | `redis` / `redis-test` / `redisinsight` の 3 サービス追加、`redis-data` volume 追加、backend の `depends_on` に `redis`(`service_healthy`) |
| `.env.example` | `REDIS_HOST` / `REDIS_PORT` / `REDIS_SSL` / `REDIS_PASSWORD` |

### インフラ

| ファイル | 変更 |
| --- | --- |
| `cloudformation/app.yml` | ElastiCache 一式(ReplicationGroup / SubnetGroup / ParameterGroup / SG)、タスク定義 2 つに env+secrets、アラーム 2 本、SNS トピック + Chatbot 設定、`Parameters` 5 つ、`Conditions` 1 つ(`RedisHasReplica`)、`Outputs` に `RedisEndpoint` / `RedisPort`。**リソース 85 → 93 / パラメータ 51 → 56 / 出力 19 → 21** |
| `cloudformation/params/stg.json` / `prod.json` | ノードタイプ・ノード数・しきい値・`SlackChannelIdRedis` |
| `deploy/taskdef.json` / `deploy/taskdef-migrate.json` | `__REDIS_HOST__` などのプレースホルダ追加 |
| `deploy/buildspec.yml` | `sed` の置換行を追加(未置換検出の `grep` が守ってくれる) |

> **`deploy/` 配下はフェーズ16 が実装未着手なのでまだ動いていない。**
> 実際に検証できるのは `app.yml` 側のタスク定義 3 つだけで、`deploy/` 側は
> **フェーズ16 の実装時にまとめて検証される**。
> それでも今揃えて書くのは、フェーズ16 実装時に「Redis を足し忘れた」で詰まらないため。

### ドキュメント

| ファイル | 変更 |
| --- | --- |
| `docs/notes/redis/redis-basics.md` | **新規** |
| `docs/notes/redis/session-management.md` | **新規** |
| `docs/notes/redis/elasticache.md` | **新規** |
| `docs/adr/0015-session-store-on-redis.md` | **新規**(作成済み) |
| `docs/adr/0002-session-cookie-over-jwt.md` | 冒頭に注記(作成済み) |
| `docs/notes/java/spring/session-store-and-other-frameworks.md` | 現在地を ③ → ④ に更新 |
| `docs/development/README.md` | 構成図とコンテナ表(6 → 8 コンテナ)、本番との対応 |
| `docs/test/README.md` | `redis-test` の説明、MySQL と分離方式が違う理由 |
| `docs/slack/README.md` | Redis 用チャンネルの作成手順 |
| `docs/development/implementation-progress.md` | フェーズ18 の行を追加(**欠落しているフェーズ17 の行も補う**) |
| `docs/infrastructure/` | 構成図に ElastiCache を追加 |

## 5-3. 実装で確定したこと(設計から動いた点)

1. **Boot 4 のプロパティ名**(3-0)。設計時に見落とし、起動失敗として現れた
2. **タスク定義は 3 つではなく 2 つ**(決定9 の訂正)
3. **Boot 4 の starter 名は `spring-boot-starter-session-data-redis`。**
   `spring-boot-starter-session-jdbc` と対称の単一 starter で、これ 1 本で
   `data-redis`(Lettuce)と Spring Session の自動設定が揃う
4. **ローカルで動かして確認できた事実**(→ [docs/notes/redis/session-management.md](../../notes/redis/session-management.md) に実機ログとして収録)
   - ログイン 1 回で 4 種類のキーができる
   - セッション本体の TTL 86699 秒 / 影のキー 86399 秒 = **差はちょうど 300 秒**
   - 影のキーだけ切らすと principal 索引が自動で掃除される。**本体を先に消すと掃除されない**
     (principal 名の読み先が無くなるため)
   - 3 端末ログイン → パスワード変更で索引が 3 → 1、他端末は `user: null`
   - テスト 46 本すべて成功。`redis-test` 側にだけテスト用ユーザーのキーが入る
5. **`AllowedPattern` によるプレースホルダ検出が効く。** `SlackChannelIdRedis` は
   `__REDIS_CHANNEL__` のままなので、埋めずに構築すると Change Set の作成で止まる(意図的)

## 6. 実測で覆りうる項目

**AWS 側はスタックを建てていないので、以下はすべて未検証。**

- **3-9** — `notify-keyspace-events` が ElastiCache のパラメータグループで変更可能かどうか
- **`{{resolve:ssm-secure}}` が `AuthToken` で解決されるか。**
  対応プロパティの一覧に入っているという理解で書いたが、初回構築で確かめる。
  解決されなければ `NoEcho: true` のパラメータ経由(`BasicAuthCredential` と同じ形)に落とす
- **アラームの次元。** `CacheClusterId` が `<ReplicationGroupId>-001` になるという命名規則前提で書いている
- `TransitEncryptionEnabled: true` にしたときの Lettuce 側の設定(`spring.data.redis.ssl.enabled` だけで足りるか、
  証明書の検証設定が要るか)
- `AuthToken` を後から変更する場合の挙動(`AuthTokenUpdateStrategy` が要るか)
- `cache.t4g.micro` で `DatabaseMemoryUsagePercentage` のしきい値を何 % に置くのが妥当か
  (`reserved-memory-percent` の既定 25% を踏まえる必要がある)
- テスト実行時に `redis-test` が起動していない場合のエラーの出方
  (`app_test` 未作成のときの `FlywaySqlUnableToConnectToDbException` に相当する説明が要る)
