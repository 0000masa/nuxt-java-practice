# セッションの保存先を MySQL から Redis(ElastiCache)に移す

日付: 2026-09-12
ステータス: accepted

## 決定

Spring Session の保存先を **MySQL から Redis に移す**。依存を `spring-boot-starter-session-jdbc` から
`spring-boot-starter-session-data-redis`(Boot 4 の単一 starter。これ 1 本で Lettuce と
Spring Session の自動設定が揃う)に差し替え、
**`spring.session.data.redis.repository-type: indexed` を明示する**(Boot 4 でキー名が変わっており、記事でよく見る `spring.session.redis.*` は `level: error` で deprecated のため無視される)。
ローカルは `redis` 公式イメージ、本番は **ElastiCache(Redis OSS 7.1、`ReplicationGroup` ノード 1 台)**。

**[ADR-0002](0002-session-cookie-over-jwt.md) は supersede しない。**
JWT を発行しない・`formLogin()` に乗る・CSRF が必須になる・ログインだけ form-urlencoded になる、
という判断はすべて今も有効で、**変わるのは保存先だけ**である。

## 背景と理由

ADR-0002 は MySQL を選び、Redis を「検討したが採らなかった選択肢」に置いた。
理由は性能ではなく **学習リポジトリとしての利点** — ①開発環境のコンテナを増やさずに済む
②`SELECT * FROM SPRING_SESSION` でログイン状態を目で見られる — の 2 点だった。

今回それを覆すのは、**目的が変わったから**である。
実務(会社)のログイン基盤が Redis でセッションを持っており、**その構成を手元で再現して理解すること**が
今回の目的になった。ADR-0002 が挙げた 2 つの利点は、この目的の前では逆に働く。
コンテナが 1 つ増えるのは「学ぶ対象が増える」ことであり、
目で見る手段は `SELECT` から GUI(RedisInsight)に変わるだけで失われない。

性能を理由にしていない点は明記しておく。**この規模では MySQL でも Redis でも体感差は無い。**
ADR-0002 が「性能が問題になったら Redis を検討する」と書いた条件は、まだ満たしていない。

差し替えが安く済むことは、ADR-0002 自身が用意していた。
「`spring-session-data-redis` に入れ替えるだけで済むよう、アプリコードは Spring Session の抽象より下に
依存させない」という指示どおり、`UserSessionManager` は `FindByIndexNameSessionRepository` 経由でしか
セッションに触っていない。**この決定でアプリのコードは 1 行も変わらない。**

### `repository-type: indexed` を明示する理由

Spring Boot 3.0 以降、Spring Session Redis の既定実装は `RedisSessionRepository` で、
**これは `FindByIndexNameSessionRepository` を実装していない**。
既定のままだと `UserSessionManager` の DI が解決できず、**アプリが起動しない**
(`TaskRunner` の javadoc が記録している「実測で確認した起動失敗」と同じ現象)。

`indexed` にすると `RedisIndexedSessionRepository` になり、
`findByPrincipalName` によるパスワードリセット時の全端末強制ログアウト(ADR-0002 の要件)が
そのまま成立する。

**Spring Boot 4 ではキー名が `spring.session.data.redis.*` に変わっている。**
旧名 `spring.session.redis.*` は 4.0.0 で `level: error` の deprecated になっており、
書いても黙って無視されて上の起動失敗がそのまま起きる(実測)。
接続側の `spring.data.redis.*` は変わっていないので、**片方だけ直して詰まりやすい**。

## 検討したが採らなかった選択肢

- **MySQL のまま(現状維持)** — 性能上は何も困っていない。ただし今回の目的が
  「実務の構成を再現して理解すること」なので、現状維持では目的を果たせない
- **JDBC と Redis を切り替え可能にする** — 両方の実装を読み比べられる利点があったが、
  設定・ドキュメント・テストがすべて二重になり、**どちらの構成も"本物"にならない**と判断した
- **ElastiCache Serverless** — 運用は一番楽だが、**パラメータグループを持てない**ため
  `notify-keyspace-events` を有効化できず、`indexed` の索引掃除が成立しない。
  加えて最小構成でも月 $90 前後かかり、**使い終わったら撤収する運用**と噛み合わない
- **Valkey** — ElastiCache では Redis OSS が 7.1 で止まっている一方 Valkey は 8.x が使え、
  料金も約 20% 安い。それでも採らなかったのは、**リポジトリ全体で Redis と Valkey の用語が割れる**ため。
  `docs/notes/redis/` というフォルダ名から `spring.data.redis.*` まで、学習対象の名前は 1 つに揃えたい。
  料金差は `cache.t4g.micro` では月 $2〜3 で、判断材料にならない
- **IAM 認証(RBAC + `AuthenticationMode: iam`)** — パスワードを置かずに済む最も新しい方式。
  ただし **Lettuce は IAM トークンの生成に対応しておらず**、15 分で失効するトークンを再発行する
  `RedisCredentialsProvider` を自作する必要がある。学習の本筋から外れるので見送った
- **JSON シリアライザ(`GenericJackson2JsonRedisSerializer`)** — GUI でセッションの中身まで読めるようになる。
  ただし `SecurityJackson2Modules` が面倒を見るのは Spring Security 標準クラスまでで、
  自作の `AppUserDetails` / `AppOidcUser` には Jackson の mixin と型情報の許可リスト登録が要る。
  `AppOidcUser` のコンストラクタは `User` エンティティを受け取る形なので、
  **JSON 復元用のコンストラクタを認証クラスに足す**ことになる。
  ADR-0002 の「認証は標準機構に素直に乗せる」方針を曲げる割に得るものが小さい
- **`repository-type: default`** — キー構造が単純になるが、`findByPrincipalName` が使えず
  全端末強制ログアウトを自前で作り直すことになる(ADR-0002 が JWT を却下した根拠そのものを失う)

## 結果として生じること

- **MySQL のセッションテーブルは V4 で DROP する。** Flyway の鉄則どおり
  `V3__create_spring_session_tables.sql` は消さず変えず、`V4__drop_spring_session_tables.sql` で打ち消す。
  「過去を消さず、前に進んで打ち消す」形になる
- **`indexed` は起動時に Redis へ接続する。** keyspace notifications を購読するため、
  Lettuce の遅延接続では済まない。その結果 **`migrate` のタスクモードも Redis を要求する**。
  Spring Boot を動かす ECS タスク定義 2 つ(`AppTaskDefinition` / `MigrateTaskDefinition`)に
  接続情報を渡す必要がある(渡し忘れるとマイグレーションが起動できずデプロイが止まる)。
  `DbOpsTaskDefinition` は MySQL クライアントのイメージなので不要
- **`notify-keyspace-events: Egx` が必須になる。** Redis の TTL はキー単位で、
  SET の要素ごとには張れない。セッション本体が期限切れで消えても、
  principal 索引の SET に入ったセッション ID は残る。これを `SREM` するのが期限切れ通知の役目。
  無効でも機能は壊れないが、**タイムアウトしたセッションの ID が索引に溜まり続ける**
- **`maxmemory-policy` は `noeviction` を明示する。** ElastiCache の既定 `volatile-lru` は
  TTL 付きキーを古い順に捨てる設定で、Spring Session のキーは全部 TTL 付きである。
  既定のままだと、メモリ逼迫時に**セッションが静かに消えてユーザーが身に覚えのないログアウトをする**。
  セッションストアはキャッシュと違って再計算できないので、**気づける形で止まる**ほうを選ぶ
- **切り替えた瞬間に全員ログアウトする。** MySQL に入っていたセッションは読めなくなる。
  作り捨て運用なので移行処理は作らない
- **フロントエンドは無変更。** Cookie 名は Spring Session 共通の `SESSION` で、JDBC / Redis で変わらない
- **セッションの値は GUI でもバイナリのまま見える。** 既定の JDK シリアライズを使うため。
  ただしキー名・フィールド名・TTL・principal 索引(キー名にメールアドレスが入る)は読めるので、
  「誰が・いくつ・あと何秒」は全部見える。これは MySQL 時代に
  `SPRING_SESSION_ATTRIBUTES.ATTRIBUTE_BYTES` が BLOB で読めなかったのと同じ状況である
- **[session-store-and-other-frameworks.md](../notes/java/spring/session-store-and-other-frameworks.md) の
  現在地が変わる。** あのノートは保存先を 5 段階に並べて「このプロジェクトは③(共有 RDB)にいる」と
  書いており、今回の決定で **④(インメモリストア)に移る**

## 関連

- [ADR-0002](0002-session-cookie-over-jwt.md) — セッション Cookie 方式そのものの決定(保存先以外は有効)
- [ADR-0005](0005-separate-db-users-for-app-and-migration.md) — 資格情報を SSM に置く既存の形
- [ADR-0010](0010-monitoring-in-ephemeral-stack.md) — 監視を使い捨てスタックに入れる方針
- 設計 → [フェーズ18 設計書](../superpowers/specs/2026-09-12-phase18-redis-session-design.md)
- 解説 → [docs/notes/redis/](../notes/redis/)
