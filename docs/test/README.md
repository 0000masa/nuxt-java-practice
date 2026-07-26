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

# メソッド単位で絞る(日本語メソッド名もそのまま指定できる)
docker compose exec backend sh ./gradlew test --tests '*PostControllerTest.投稿作成は201を返す'

# 前回から変更が無くても必ず走らせ直す
docker compose exec backend sh ./gradlew test --rerun-tasks
```

**VS Code の Dev Container で backend コンテナに入っている場合**は `docker compose exec backend` が不要:

```bash
sh ./gradlew test
```

`sh` を付けているのは、`gradlew` に実行権限が無いため(git 上のファイルモードが `100644`)。

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
| `PostControllerTest` | `@WebMvcTest` | **不要** | 5 | 投稿 API のステータスコードとバリデーションエラーの形 |
| `CategoryControllerTest` | `@WebMvcTest` | **不要** | 2 | カテゴリー一覧 API の順序と 0 件時の挙動 |
| `PostRepositoryTest` | `@DataJpaTest` | 使う | 4 | カーソルページネーションの境界条件 |

合計 12 本。`@WebMvcTest` の 7 本は Service をモックに差し替えるので DB を使わない。

テストの方針は「**要所に絞る**」(→ [implementation-progress.md](../development/implementation-progress.md))。網羅率を追わず、ページネーションのクエリ・認証の境界・いいねの重複防止のような**バグの温床**を優先する。

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
- **`sh ./gradlew` の `sh` を省略すると `Permission denied`。** `gradlew` に実行権限が無い
- **`src/test/resources/application.yml` を作ってはいけない。** 本体の設定を丸ごと置き換えてしまう(上記「設定の場所」参照)
- **`docker compose down -v` は開発 DB も `app_test` も消す。** 消した後は初回セットアップをやり直す
- **`app_test` にデータが残っていても気にしなくてよい。** ロールバックされるので普段は空。おかしくなったら `DROP DATABASE app_test` して作り直せば、Flyway が全部作り直す

## 関連

- テストの仕組みの解説(`@SpringBootTest` とは何か、Flyway との関係、実測ログ) → [docs/notes/java/spring/testing-and-test-database.md](../notes/java/spring/testing-and-test-database.md)
- GitHub Actions で自動テストを回すときの構成(まだ未作成) → [docs/notes/ci-with-github-actions.md](../notes/ci-with-github-actions.md)
- 開発環境の 5 コンテナ構成と起動方法 → [docs/development/README.md](../development/README.md)
- テスト方針(要所に絞る)と実装フェーズの進捗 → [docs/development/implementation-progress.md](../development/implementation-progress.md)
