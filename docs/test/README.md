# テストの実行方法と方針

バックエンドのテストは **backend コンテナの中で実行**し、DB は開発 DB(`app`)ではなく**テスト専用の database `app_test`** を使う。

- ホストに JDK を置かない方針なので、実行は常に `docker compose exec backend` 経由
- **本物の MySQL 8 を使う**(インメモリ DB には差し替えない)
- ただし開発 DB とは database を分けているので、**テストが開発中のデータを壊すことはない**

テストの仕組みそのもの(`@SpringBootTest` / `@WebMvcTest` / `@DataJpaTest` の違い、Flyway との関係)は学習メモ → [docs/notes/java/spring/testing-and-test-database.md](../notes/java/spring/testing-and-test-database.md)

## 初回セットアップ — `app_test` を作る

**クローン直後に 1 回だけ**必要な作業。これをやらないとテストが全部落ちる。

```bash
docker compose up -d   # mysql が起動していること

docker compose exec mysql mysql -uroot -proot -e "
  CREATE DATABASE IF NOT EXISTS app_test CHARACTER SET utf8mb4;
  GRANT ALL PRIVILEGES ON app_test.* TO 'app'@'%';
  FLUSH PRIVILEGES;"
```

作成できたことの確認:

```bash
docker compose exec mysql mysql -uroot -proot -e "SHOW DATABASES;"
# app / app_test の両方が並んでいれば OK
```

**空の database を作るだけでよい。テーブルは作らなくてよい。** テスト実行時に Flyway が `db/migration` を流してテーブル 6 つとカテゴリー 10 件(V2 のシード)を自動で用意する。

### なぜ手動なのか

自動化しない理由が 2 つある。

1. **Flyway は database(MySQL では schema)自体を作れない。** 作るのはテーブルだけ。database が無い状態でテストを走らせると、接続段階で `FlywaySqlUnableToConnectToDbException`(Unknown database)になる
2. **MySQL 公式イメージの `/docker-entrypoint-initdb.d/` はデータディレクトリが空のときだけ実行される。** すでに `mysql-data` ボリュームがある環境では無反応で、効かせるには `docker compose down -v`(**開発データが全部消える**)が必要になる。「新規は自動・既存は手動」の二重手順を避けるため、**全員が同じ手順を 1 回打つ**方式にしている

## テストの実行

```bash
# 全テスト
docker compose exec backend sh ./gradlew test

# クラスを絞る
docker compose exec backend sh ./gradlew test --tests '*PostRepositoryTest*'

# メソッド単位で絞る
docker compose exec backend sh ./gradlew test --tests '*PostRepositoryTest$FindTimeline.returnsNewestFirst'

# @Nested の中のメソッドを絞る(内部クラスは $ でつなぐ。$ を展開させないためシングルクォート必須)
docker compose exec backend sh ./gradlew test --tests '*PostControllerTest$CreatePost.returnsBadRequestWhenBodyIsBlank'

# 前回から変更が無くても必ず走らせ直す
docker compose exec backend sh ./gradlew test --rerun-tasks
```

**VS Code の Dev Container で backend コンテナに入っている場合**は `docker compose exec backend` が不要:

```bash
sh ./gradlew test
```

`sh` を付けているのは、`gradlew` に実行権限が無いため(git 上のファイルモードが `100644`)。なぜ 644 になっているのか、権限がどこに保存されているのかの仕組み → [docs/notes/file-permissions-and-exec-bit.md](../notes/file-permissions-and-exec-bit.md)

## 命名規約

**メソッド名は英語の camelCase、テストの内容は `@DisplayName` に日本語の 1 文で書く。**

```java
@Test
@DisplayName("本文が 280 文字を超えると 400 を返す")
void returnsBadRequestWhenBodyExceedsMaxLength() throws Exception {
```

- **メソッド名に日本語を使わない。** Java の識別子に日本語を使うのは文法上は合法で、日本の現場では実際によく見かける。ただしこれは日本ローカルの慣習で、Spring Boot / JUnit のどちらの公式作法でもない。JUnit 5 が「テスト名を自然言語で書きたい」に対して用意している公式の機能が `@DisplayName` なので、そちらを使う
- **メソッド名は動詞から始める。`should` は付けない。** Spring Framework / JUnit 5 本体のテストコードに合わせている。`@DisplayName` がすでに「〜を返す」と主張しているので、名前で二度主張しない
- **`@DisplayName` は日本語で書く。** このリポジトリのドキュメントは日本語なので、テストが何を保証しているかも日本語で読めるようにする。メソッド名と 2 箇所を同期させる責任が生まれる点には注意する(片方だけ直さない)

### `@Nested` を使う基準

**1 つのテストクラスが検証対象を 2 つ以上持つときだけ `@Nested` で分ける。** 1 つしかないならクラス自体がグループなので、フラットに書く。

```java
@WebMvcTest(PostController.class)
class PostControllerTest {

	@Nested
	@DisplayName("POST /api/posts")
	class CreatePost { ... }

	@Nested
	@DisplayName("GET /api/posts")
	class GetTimeline { ... }
}
```

現状では `PostControllerTest`(投稿の作成・削除・タイムライン)、`AuthControllerTest`(登録・メール確認・現在ユーザー・パスワード変更)、`PostRepositoryTest`(`findTimeline` と投稿の保存)が該当する。`CategoryControllerTest` は `GET /api/categories` のみ、`AuthTokenServiceTest` はトークンの発行と検証のみなのでフラットに書いている。

トップレベルのクラスを分ける手もあるが、`@WebMvcTest` と `@MockitoBean` の定型宣言がクラスごとに複製される。`@Nested` なら外側の `@BeforeEach` とフィールドをそのまま共有でき、グループ固有の前提だけ内側の `@BeforeEach` に足せる。

### 結果の見方

- **`build/reports/tests/test/index.html`** — 人間向けの HTML レポート。ブラウザで開くと落ちたテストと例外が読める
- **`build/test-results/test/*.xml`** — 機械向けの JUnit XML。将来 CI がこれを読む

**`UP-TO-DATE` と出て一瞬で終わったときは、テストが実行されていない。** Gradle は前回から変更が無いタスクを省略する。強制したいときは `--rerun-tasks`。

## テスト実行時に自動で起きること

`app_test` を空のまま放置していても、毎回この順で整う。

1. Flyway が未適用のマイグレーション(`V1__create_base_tables.sql` / `V2__insert_categories.sql`)を `app_test` に適用する
2. JPA が `ddl-auto: validate` でエンティティとスキーマの一致を検証する
3. 各テストが走る。`@DataJpaTest` のテストは**自動でトランザクションに包まれ、終了時にロールバックされる**

このため **`app_test` の中身は普段ほぼ空**(カテゴリー 10 件だけ)で、テストのたびにデータが積み上がることはない。

```bash
# テスト後の状態を確認したいとき
docker compose exec mysql mysql -uroot -proot -e "
  SELECT COUNT(*) AS posts FROM app_test.posts;
  SELECT COUNT(*) AS categories FROM app_test.categories;"
# posts = 0 / categories = 10
```

## 設定の場所

DB の切り替えは `backend/build.gradle` の 1 箇所だけで行っている。

```groovy
tasks.named('test') {
	useJUnitPlatform()
	// テストは開発 DB(app)ではなく専用の app_test を使う。application.yml の ${DB_NAME:app} を上書きする。
	environment 'DB_NAME', 'app_test'
}
```

`application.yml` の接続先が `.../${DB_NAME:app}` と環境変数を読む形になっているので、**テストタスクの環境変数を差し替えるだけ**で接続先が変わる。`bootRun`(アプリ本体の起動)には影響しない。

> **`backend/src/test/resources/application.yml` を作る方式は採らない。** 同名ファイルはクラスパス上でテスト側が先に見つかり、本体の `application.yml` が**丸ごと読まれなくなる**。`username` / `password` が失われるだけでなく、`ddl-auto: validate` と `open-in-view: false` も消える。接続だけ通る設定を書いた場合、**スキーマ検証をしないテストに静かに成り下がる**ため危険。

## 現在のテスト一覧

| テストクラス | 種類 | DB | 本数 | 何を守っているか |
|---|---|---|---|---|
| `ApplicationTests` | `@SpringBootTest` | 使う | 1 | アプリ全体が起動できること(Bean 配線・Flyway・`ddl-auto: validate`) |
| `PostControllerTest` | `@WebMvcTest` | **不要** | 6 | 投稿 API のステータスコードとバリデーションエラーの形、principal の id が Service に渡ること |
| `CategoryControllerTest` | `@WebMvcTest` | **不要** | 2 | カテゴリー一覧 API の順序と 0 件時の挙動 |
| `PostRepositoryTest` | `@DataJpaTest` | 使う | 6 | カーソルページネーションの境界条件、新規保存で id / created_at が埋まり DB に行が入ること |
| `AuthTokenServiceTest` | `@DataJpaTest` | 使う | 7 | 使い捨てトークンの境界(期限切れ・使用済み・用途違い・ハッシュ保存・再発行での無効化) |
| `AuthControllerTest` | `@WebMvcTest` | **不要** | 7 | 認証 API の入力チェックと `fieldErrors`、`/api/auth/me` が未ログインでも 200、パスワード変更が認可で弾かれること |
| `AuthFlowTest` | `@SpringBootTest` | 使う | 3 | 登録 → 未確認ではログイン不可 → メール確認 → ログイン成功の一連、未ログインでは投稿できないこと、Google ログインの入口が Google へ 302 すること |
| `GoogleAccountServiceTest` | `@DataJpaTest` | 使う | 6 | Google ログインの分岐(既存ユーザーの特定・アカウントリンク・未確認アカウントの作り直し・新規作成の初期値・未確認メールの拒否・メール変更を取り込まないこと) |
| `UsernameGeneratorTest` | `@DataJpaTest` | 使う | 3 | Google 由来ユーザーの username 生成(文字種の変換・衝突時の連番・使える文字が無いときの代替) |
| `AppOidcUserTest` | 素の JUnit | **不要** | 1 | `getName()` がメールアドレスを返すこと(セッション無効化がメールを鍵に引くため) |
| `AppOidcUserServiceTest` | 素の JUnit + Mockito | **不要** | 4 | Google のクレームをアプリの言葉に翻訳する部分(`email_verified` の 3 値変換・拒否の `OAuth2AuthenticationException` への翻訳・principal の組み立て) |

合計 46 本。`@WebMvcTest` の 15 本と `AppOidcUserTest` / `AppOidcUserServiceTest` は DB を使わない。

`AppOidcUserTest` の 1 本は他より重要度が高い。ここが OIDC の既定(`sub`)のままでも画面上は何も壊れず、「パスワードをリセットしたのに Google ログインのセッションだけ生き残る」という形でしか露見しないため、手で気づくのがほぼ不可能。

### `AppOidcUserService` の delegate を差し替える方法

`AppOidcUserService` は `private final OidcUserService delegate = new OidcUserService()` を握っており、そのまま呼ぶと Google と実通信してしまう。`@InjectMocks` は効かない(引数付きコンストラクタでの生成が先に成功するため、フィールド注入まで進まない)ので、`ReflectionTestUtils.setField(service, "delegate", mock)` で差し込む。**テストの都合で本番コードに差し替え用のコンストラクタを足さない**方針。代償として、`delegate` をリネームするとコンパイルは通ったままこのテストだけが実行時に落ちる。

Google が返す `OidcUser` も `mock(OidcUser.class)` で足りる。`ClientRegistration` や署名済み ID トークンを組み立てる必要はない。ただし `thenReturn(googleUser(...))` のようにヘルパーを `when(...)` の内側で呼ぶと `UnfinishedStubbingException` になるため、戻り値は必ず変数に受けてから渡す。

テストの方針は「**要所に絞る**」(→ [implementation-progress.md](../development/implementation-progress.md))。網羅率を追わず、ページネーションのクエリ・認証の境界・いいねの重複防止のような**バグの温床**を優先する。

### `@WebMvcTest` とセキュリティの注意点

**`@WebMvcTest` は Spring Boot の「既定のセキュリティ設定」(全リクエスト認証必須)を使う。** アプリの認可ルールを効かせるには `@Import(SecurityConfig.class)` を付ける必要があり、付けないと公開しているはずの `GET /api/posts` や `GET /api/categories` が 401 になってテストが落ちる。

読み込む場合は `SecurityConfig` が要求する `AuthResponseWriter` を `@MockitoBean` で用意する。その副作用として **401 / 403 のステータスコード自体はこのスライスで検証できない**(ステータスを書くのがモックにした `AuthResponseWriter` のため)。書き込み系のリクエストには `with(user(...))` と `with(csrf())` を添える。本物のステータスは `AuthFlowTest` で確認している。

### `AuthFlowTest` に `@Transactional` を付けてはいけない

確認メールの送信は「登録トランザクションのコミット後」に走る(`@TransactionalEventListener(AFTER_COMMIT)`)。テストをトランザクションで囲むとコミットされないため送信が発火せず、メール本文からトークンを取れなくなる。そのため作ったデータは `@BeforeEach` / `@AfterEach` で自分で消している。

## なぜ専用 database なのか

4 つの選択肢を比較して `app_test` を選んだ。

| 方式 | 本物の MySQL | 開発 DB を守れる | 速さ | 判断 |
|---|---|---|---|---|
| 開発 DB(`app`)を共用 | ○ | **△ ロールバック頼み** | ◎ | 以前の方式。下記の理由でやめた |
| **専用 database(`app_test`)** | ○ | ○ | ◎ | **採用** |
| Testcontainers | ○ 毎回使い捨て | ○ | △ 毎回コンテナ起動 | CI を作るときに再検討 |
| インメモリ DB(H2) | **×** | ○ | ◎ | 却下 |

**開発 DB 共用をやめた理由** — `PostRepositoryTest.setUp()` は前提を固定するために `postRepository.deleteAll()` を呼ぶ。`@DataJpaTest` のロールバックで取り消されるので実害は出ていなかったが、次の 3 点が危うい。

- `@Transactional` の効かない書き方(別スレッド、`@Commit`、`REQUIRES_NEW`)を 1 箇所足した瞬間に、**開発中の投稿が本当に消える**
- 開発 DB のシードデータに依存する(`categoryRepository.findById(1L)`)ため、開発中にカテゴリーを触るとテストが落ちる
- テストと `docker compose up` のアプリが同じ database を触るので、テスト中に画面を操作すると結果が揺れうる

**インメモリ DB(H2)を却下した理由** — H2 は MySQL と SQL 方言が違うため、`ddl-auto: validate` によるスキーマ検証や MySQL 固有の挙動(照合順序、日付の精度)の検証が成立しない。「本物の MySQL で確かめる」という前提を捨てることになる。

**Testcontainers を見送った理由** — テスト実行のたびにコンテナ起動を待つぶん遅く、ローカル開発では手数が増える。**CI で真価を発揮する道具**なので、GitHub Actions を作るときに改めて検討する(比較 → [ci-with-github-actions.md](../notes/ci-with-github-actions.md))。

## つまずきポイント

- **`app_test` を作らずにテストを実行すると全部落ちる。** `Unknown database 'app_test'`。初回セットアップを 1 回打つ
- **`UP-TO-DATE` はテストが走っていない印。** `--rerun-tasks` を付ける
- **`sh ./gradlew` の `sh` を省略すると `Permission denied`。** `gradlew` に実行権限が無い(→ [file-permissions-and-exec-bit.md](../notes/file-permissions-and-exec-bit.md))
- **`src/test/resources/application.yml` を作ってはいけない。** 本体の設定を丸ごと置き換えてしまう(上記「設定の場所」参照)
- **`docker compose down -v` は開発 DB も `app_test` も消す。** 消した後は初回セットアップをやり直す
- **`app_test` にデータが残っていても気にしなくてよい。** ロールバックされるので普段は空。おかしくなったら `DROP DATABASE app_test` して作り直せば、Flyway が全部作り直す

## 関連

- テストの仕組みの解説(`@SpringBootTest` とは何か、Flyway との関係、実測ログ) → [docs/notes/java/spring/testing-and-test-database.md](../notes/java/spring/testing-and-test-database.md)
- GitHub Actions で自動テストを回すときの構成(まだ未作成) → [docs/notes/ci-with-github-actions.md](../notes/ci-with-github-actions.md)
- 開発環境の 5 コンテナ構成と起動方法 → [docs/development/README.md](../development/README.md)
- テスト方針(要所に絞る)と実装フェーズの進捗 → [docs/development/implementation-progress.md](../development/implementation-progress.md)
