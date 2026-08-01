# Spring Boot のテストの仕組みと、開発 DB を汚さないテスト用 DB

`ApplicationTests` は何をテストしているのか、`@SpringBootTest` / `@WebMvcTest` / `@DataJpaTest` は何が違うのか、そして**インメモリ DB を使わずに、docker compose の MySQL コンテナに専用のテスト用 database を作って使えるのか**をまとめた学習メモ。

要点は 3 つ。

1. **テストは「どこまで立ち上げるか」を選ぶ**。全部立ち上げる `@SpringBootTest` は 1 つあれば足り、あとは必要な層だけ切り出す
2. **かつてこのプロジェクトのテストは開発 DB(`app`)を共用していた**。ロールバックで守られていたが、綱渡りではあった
3. **現在は専用 database `app_test` を使う方式を採用済み**。変更は `build.gradle` に 1 行で、Flyway がスキーマもシード(カテゴリー 10 件)も自動で作る — このメモの内容は実際に動かして確認済み

> このメモは**仕組みの解説**。日々の手順（`app_test` の作り方・実行コマンド・テスト一覧）は [docs/test/README.md](../../../test/README.md) にまとまっている。

## 1. テストの 3 段階 — どこまで立ち上げるかを選ぶ

Spring Boot のテストは「アプリのどこまでを起動するか」で種類が分かれます。全部起動すれば本番に近い代わりに遅く、一部だけなら速い代わりに検証範囲が狭くなります。

| アノテーション | 立ち上がるもの | DB | 速さ | このプロジェクトでの例 |
|---|---|---|---|---|
| `@SpringBootTest` | **全部**(Controller / Service / Repository / DB / Flyway) | 必要 | 遅い | `ApplicationTests` |
| `@WebMvcTest` | Controller 層と JSON 変換だけ | **不要** | 速い | `PostControllerTest` / `CategoryControllerTest` |
| `@DataJpaTest` | JPA と Repository だけ | 必要 | 中間 | `PostRepositoryTest` |

工場に例えると、`@SpringBootTest` は「ライン全体を動かす通し試験」、`@WebMvcTest` と `@DataJpaTest` は「特定の工程だけを台に載せて動かす部分試験」です。

`@WebMvcTest` と `@DataJpaTest` を**スライステスト**（アプリを薄く切り出すテスト）と呼びます。`@SpringBootTest` は起動が重いので、**アプリ全体を立ち上げるテストは 1 つだけにして、あとはスライステストで書く**のが Spring Boot の推奨です。このプロジェクトはそのとおりになっています。

`@WebMvcTest` が DB 不要なのは、Service を本物ではなく**偽物（モック）**に差し替えるからです。

```java
// PostControllerTest.java:26-27 付近
@WebMvcTest(PostController.class)
class PostControllerTest {

	@MockitoBean          // ← 本物の PostService ではなく、指示どおりに答える偽物を入れる
	PostService postService;
```

DB に触る `PostService` が偽物なので、DB そのものが不要になります。だから `@WebMvcTest` は速く、「URL・ステータスコード・JSON の形」の検証に集中できます。

### Spring Boot 4 での注意

Spring Boot 4 でテスト系アノテーションの**パッケージが移動**しています。ネットの記事をそのまま写すと import が見つかりません。

| アノテーション | Boot 4 でのパッケージ |
|---|---|
| `@WebMvcTest` | `org.springframework.boot.webmvc.test.autoconfigure` |
| `@DataJpaTest` | `org.springframework.boot.data.jpa.test.autoconfigure` |
| `@AutoConfigureTestDatabase` | `org.springframework.boot.jdbc.test.autoconfigure` |
| `@MockitoBean` | `org.springframework.test.context.bean.override.mockito`（旧 `@MockBean` は廃止） |

## 2. ApplicationTests は何をテストしているのか

```java
@SpringBootTest
class ApplicationTests {

	@Test
	void contextLoads() {
	}

}
```

**メソッドの中身が空**です。アサーション（`assertEquals` などの検証文）が 1 つもありません。それでもこれは立派なテストで、**検証はメソッドの中ではなく外で行われています**。

`@SpringBootTest` を見た Spring は、テストメソッドを呼ぶ前に本番と同じ手順でアプリを組み立てます。

1. `Application.java` の `@SpringBootApplication` を起点にクラスを走査する（コンポーネントスキャン）
2. `@RestController` / `@Service` / `@Repository` などを Bean にし、互いに注入する（DI）
3. `application.yml` に従って DB につなぐ
4. **Flyway が `db/migration` の未適用マイグレーションを実行する**
5. **JPA が `ddl-auto: validate` に従い、エンティティと DB スキーマの一致を検証する**

この 1〜5 のどこかで失敗すれば、メソッドに到達せずテストが落ちます。逆に空のメソッドが実行されたという事実が「アプリは起動できる」の証明になります。

実際に DB を見つからない設定にして走らせると、空のメソッドが落ちます。

```
$ docker compose exec -e DB_HOST=nosuchhost backend sh ./gradlew test --tests '*ApplicationTests*'

ApplicationTests > contextLoads() FAILED
    Caused by: FlywaySqlUnableToConnectToDbException
        Caused by: java.net.UnknownHostException
```

**この 1 行のテストが守っているもの**は次のとおりです。どれも「起動しようとした瞬間に判明するが、それまで気づけない」種類の事故です。

- Bean の配線ミス（必要な `@Service` が無い、循環参照している）
- **エンティティと DB スキーマの不一致** — `Post` に `@Column` を足してマイグレーションを書き忘れると、`ddl-auto: validate` が起動を止めるので、**このテストだけが落ちます**
- Flyway のマイグレーションが壊れている（SQL の文法ミス、チェックサム不一致）
- `application.yml` の設定ミス

## 3. かつては開発 DB を共用していた — なぜやめたか

**この節は、専用テスト DB に切り替える前の状態の記録です**（現在は §4 の方式を採用済み）。何が問題だったかを残しておきます。

以前は `backend/src/test/resources` も `build.gradle` の上書きも無かったため、テストは**本体の `application.yml` をそのまま使っていました**。つまり `DB_NAME=app`、開発中の DB そのものにつないでいたことになります。

`PostRepositoryTest` はそれを前提に、かなり大胆なことをしていました（`deleteAll()` を呼ぶ構造は今も同じで、接続先とコメントだけが変わっています）。

```java
// PostRepositoryTest.java:47-49 付近
@BeforeEach
void setUp() {
	// ↓ 当時のコメント（現在は app_test 前提の内容に修正済み）
	// 開発 DB を共用するため、既存の投稿はトランザクション内で消して前提を固定する(ロールバックで元に戻る)
	postRepository.deleteAll();
```

**開発 DB の投稿を全件削除しています。** これで事故が起きなかったのは、`@DataJpaTest` が**各テストを自動でトランザクションに包み、終了時にロールバックする**ためです。削除は取り消され、開発データは戻ります。実際に確認しても、テスト前後で `app.posts` は 2 件のまま変わりませんでした。

ただし綱渡りではありました。

- `@Transactional` の効かない書き方（別スレッド、`@Commit`、`REQUIRES_NEW` など）を 1 箇所足した瞬間に、**開発データが本当に消えます**
- `categoryRepository.findById(1L).orElseThrow()` があるので、**開発 DB のシードデータに依存**します。誰かがカテゴリーを消すとテストが落ちます
- テストと `docker compose up` のアプリが同じ DB を触るので、テスト中に画面を触ると結果が揺れる可能性があります

この 3 点を理由に、「本物の MySQL でテストしたいが、開発 DB は使いたくない」という方針に切り替えました。

## 4. 採用した方式 — 専用 database `app_test`

インメモリ DB に差し替える必要はなく、**同じ MySQL コンテナの中に `app_test` という database を追加するだけ**で済みました。以下は実際に動かして確認した内容です。

### 手順 1: database を先に作る

```bash
docker compose exec mysql mysql -uroot -proot -e "
  CREATE DATABASE IF NOT EXISTS app_test CHARACTER SET utf8mb4;
  GRANT ALL PRIVILEGES ON app_test.* TO 'app'@'%';
  FLUSH PRIVILEGES;"
```

**database 自体は手で作る必要があります。Flyway は作ってくれません。** これは実測で確認しました。`app_test` を消した状態でテストを走らせると、テーブルを作る前の接続段階で落ちます。

```
Caused by: FlywaySqlUnableToConnectToDbException
    Caused by: java.sql.SQLSyntaxErrorException     ← Unknown database 'app_test'
```

Flyway が作るのは**テーブル**であって、**database（MySQL では schema と同義）ではない**、と覚えておくと混乱しません。

### 手順 2: テスト実行時だけ DB 名を差し替える

`application.yml` は既に環境変数を読む形になっています。

```yaml
url: jdbc:mysql://${DB_HOST:localhost}:${DB_PORT:3306}/${DB_NAME:app}
```

`${DB_NAME:app}` は「環境変数 `DB_NAME` があればその値、無ければ `app`」という意味です。**この仕組みをそのまま使えばよい**ので、`build.gradle` の `test` タスクに 1 行足すだけで済みます。

```groovy
tasks.named('test') {
	useJUnitPlatform()
	environment 'DB_NAME', 'app_test'   // ← テスト実行時だけ app_test を使う
}
```

`environment` は「このタスクが起動する JVM に環境変数を渡す」指定です。アプリ本体の起動（`bootRun`）には影響しません。

### 実測結果

この状態で `./gradlew test` を走らせた結果です。

**① Flyway がスキーマとシードを自動で作った**

```
$ docker compose exec mysql mysql -uroot -proot -e "SHOW TABLES FROM app_test"
auth_tokens / categories / flyway_schema_history / likes / post_images / posts / users

$ ... "SELECT version, description, success FROM app_test.flyway_schema_history"
1  create base tables    1
2  insert categories     1

$ ... "SELECT COUNT(*) FROM app_test.categories"
10
```

**空の database を用意するだけで、テーブル 6 つとカテゴリー 10 件が揃います。** `PostRepositoryTest` が `findById(1L)` でカテゴリーを引いているのも、シードが V2 マイグレーションで入るのでそのまま通ります。テストコードの修正は不要でした。

**② テストは全部成功し、開発 DB は無傷**

```
BUILD SUCCESSFUL

app.posts = 2 / app.categories = 10 / app.users = 1   ← 実行前と同じ
app_test.posts = 0 / app_test.users = 0               ← ロールバック済み
```

### 罠: `src/test/resources/application.yml` は「追加」ではなく「置き換え」

「テスト用の設定ファイルを置けばよい」と考えて、次のようなファイルを作るのが自然に思えます。**これは危険です。**

```yaml
# backend/src/test/resources/application.yml ← この方法は勧めない
spring:
  datasource:
    url: jdbc:mysql://${DB_HOST:localhost}:${DB_PORT:3306}/app_test
```

同名の `application.yml` はクラスパス上で**テスト側が先に見つかり、本体側は読まれません**。差分の上書きではなく、丸ごと置き換えです。実際に上のファイルだけを置いて走らせると、`username` / `password` が失われて接続が失敗します。

```
Caused by: java.sql.SQLException     ← Access denied（ユーザー名が空になっている）
```

さらに恐ろしいのは、`ddl-auto: validate` と `open-in-view: false` も一緒に消えることです。**接続が通ってしまう設定を書いた場合、エラーにならないまま「スキーマ検証をしないテスト」に成り下がります**。`ApplicationTests` の価値の半分が静かに失われます。

どうしてもファイルで管理したいなら、本体の設定を全部書き写すか、`application-test.yml` + `@ActiveProfiles("test")` にします。ただし**環境変数 1 つで済むなら、`build.gradle` の 1 行が最もシンプルで壊れにくい**と考えます。

### compose で自動化する場合と、その落とし穴

`app_test` の作成をコンテナ起動時に自動化することもできます。MySQL 公式イメージは、初回起動時に `/docker-entrypoint-initdb.d/` の `.sql` を実行します。

```yaml
  mysql:
    image: mysql:8
    volumes:
      - mysql-data:/var/lib/mysql
      - ./docker/mysql/init:/docker-entrypoint-initdb.d:ro   # 追加
```

```sql
-- docker/mysql/init/01-create-test-db.sql
CREATE DATABASE IF NOT EXISTS app_test CHARACTER SET utf8mb4;
GRANT ALL PRIVILEGES ON app_test.* TO 'app'@'%';
```

**落とし穴: この初期化スクリプトは「データディレクトリが空のときだけ」実行されます。** すでに `mysql-data` ボリュームができている環境では走りません。既存環境に後から入れる場合は、次のどちらかが必要です。

- 手順 1 の `CREATE DATABASE` を 1 回だけ手で実行する（既存データを消さずに済む。**こちらを推奨**）
- `docker compose down -v` でボリュームごと作り直す（**開発データが全部消える**）

新しく環境を作る人のために init スクリプトを置き、既存環境は手で 1 回作る、という併用が現実的です。

### 4 つの選択肢の比較

| 方式 | 本物の MySQL か | 開発 DB を汚さないか | 追加の手間 |
|---|---|---|---|
| **開発 DB 共用（以前の方式）** | ○ | △ ロールバック頼み | なし |
| **専用テスト DB（`app_test`）← 採用** | ○ | ○ | database を 1 回作る + `build.gradle` 1 行 |
| **Testcontainers** | ○ 毎回使い捨て | ○ | 依存追加 + テストクラスの修正 + 起動が毎回遅い |
| **インメモリ DB（H2）** | **×** | ○ | 依存追加。ただし後述の問題 |

**インメモリ DB は今回の目的に合いません。** H2 は MySQL と方言が違うため、`ddl-auto: validate` や MySQL 向けの SQL、照合順序の検証がそもそも成立しません。「本物の MySQL で試したい」という前提を捨てることになります。

**Testcontainers**（テスト実行時に Docker で使い捨ての DB を立てるライブラリ）は理想的ですが、テストごとにコンテナ起動を待つぶん遅く、ローカル開発では手数が増えます。**CI で真価を発揮する道具**なので、CI を作るときに検討するのが順番として自然です。

したがって**当面は「専用テスト DB」が最も費用対効果が高い**と判断し、この方式を採用しました（運用手順 → [docs/test/README.md](../../../test/README.md)）。

## 5. テストの実行コマンド

このプロジェクトはホストに JDK を置かない方針なので、**backend コンテナの中で実行**します。

```bash
# 全テスト実行(リポジトリ直下から)
docker compose exec backend sh ./gradlew test

# クラスを絞る
docker compose exec backend sh ./gradlew test --tests '*ApplicationTests*'
docker compose exec backend sh ./gradlew test --tests '*PostRepositoryTest*'

# キャッシュを無視して必ず走らせ直す(前回と同じなら Gradle は実行を省略するため)
docker compose exec backend sh ./gradlew test --rerun-tasks

# コンパイルだけ(Java を編集したあとの反映)
docker compose exec backend sh ./gradlew classes

# テストまで含めた通しビルド
docker compose exec backend sh ./gradlew build
```

**`sh ./gradlew` と `sh` を付けている理由**は、`gradlew` に実行権限が無いためです（git 上のファイルモードが `100644`）。`docker/backend/Dockerfile` の `CMD ["sh", "./gradlew", "bootRun"]` も同じ事情によるものです。

**VS Code の Dev Container で backend コンテナに入っている場合**は、`docker compose exec backend` の部分が不要になります。

```bash
sh ./gradlew test
```

**結果の見方**は 2 つあります。

- `build/reports/tests/test/index.html` — 人間向けの HTML レポート。どのテストが落ちたかブラウザで見られる
- `build/test-results/test/*.xml` — 機械向けの JUnit XML。CI がこれを読んで結果を表示する

なお `--rerun-tasks` を付けない場合、**前回から何も変わっていなければ Gradle はテストを実行せず `UP-TO-DATE` と表示します**。「テストが一瞬で終わった」ときはこれを疑ってください。

## つまずきポイント

- **`src/test/resources/application.yml` は本体の設定を上書きしない、置き換える。** `username` や `ddl-auto: validate` が消える。環境変数で差し替えるほうが安全
- **Flyway は database（schema）を作らない。** 作るのはテーブルだけ。`app_test` は先に手で作る
- **MySQL の初期化スクリプトはボリュームが空のときだけ走る。** 既存環境に後から init SQL を置いても無反応。`down -v` すると開発データが消える
- **`@DataJpaTest` のロールバックに頼って `deleteAll()` する構造は変わっていない。** 接続先が `app_test` になったので開発データは無事だが、トランザクションを外れる書き方を足せば `app_test` の中身は消える(Flyway が作り直すので実害は小さい)
- **インメモリ DB(H2) は MySQL の代わりにならない。** 方言が違い、`ddl-auto: validate` の検証が意味を失う
- **テストが `UP-TO-DATE` で飛ばされる。** 変更が無いと Gradle は実行を省略する。`--rerun-tasks` を付ける
- **Spring Boot 4 でテスト系アノテーションのパッケージが移動している。** `@MockBean` は廃止で `@MockitoBean`
- **`gradlew` に実行権限が無い。** `sh ./gradlew` で回避する。CI でも同じ配慮が必要

## 用語集

- **`@SpringBootTest`** — アプリ全体を本番同様に立ち上げるテスト。遅いので数を絞る
- **`@WebMvcTest`** — Controller 層だけを立ち上げるスライステスト。DB 不要
- **`@DataJpaTest`** — JPA と Repository だけを立ち上げるスライステスト。各テストは自動でロールバックされる
- **スライステスト** — アプリの一部の層だけを切り出して起動するテスト
- **`@MockitoBean`** — Bean を偽物に差し替えるアノテーション（Boot 3 までの `@MockBean` の後継）
- **モック** — 本物の代わりに、指示どおりの値を返す偽物のオブジェクト
- **`@AutoConfigureTestDatabase(replace = NONE)`** — テスト用にインメモリ DB へ差し替える既定動作を止め、本物の DB を使う指定
- **ロールバック** — トランザクション内の変更を取り消して元に戻すこと
- **Flyway** — SQL ファイルで DB スキーマを管理する仕組み。起動時に未適用分を実行する
- **`ddl-auto: validate`** — 起動時にエンティティと DB スキーマの一致を検証し、ずれていたら起動を止める設定
- **Testcontainers** — テスト実行時に Docker で使い捨ての DB などを立ち上げるライブラリ
- **`/docker-entrypoint-initdb.d/`** — MySQL 公式イメージが初回起動時に実行する SQL の置き場

## 関連

- **日々の運用手順**（`app_test` の作り方・実行コマンド・テスト一覧・方式の選定理由） → [docs/test/README.md](../../../test/README.md)
- CI（GitHub Actions）でどこまでをホスト・services・compose に任せるか → [ci-with-github-actions.md](../../ci-with-github-actions.md)
- 開発環境の 5 コンテナ構成・環境変数の方針 → [docs/development/README.md](../../../development/README.md)
- Flyway の基本とマイグレーションの書き方 → [flyway-basics.md](../../flyway-basics.md)
- 2 層のバリデーションと、どこで何を検証するか → [validation-layers.md](../../validation-layers.md)
- 環境変数 `${DB_NAME:app}` のような記法の読み方 → [env-vars-basics.md](../../env-vars-basics.md)
