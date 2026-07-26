# GitHub Actions で自動テスト — ホスト / services / docker compose の境界線

GitHub Actions で自動テストを回すとき、**Java や MySQL をどこで動かすか**には 4 つの選択肢があります。それぞれの YAML を並べ、どれが一番よく使われ、どれが一番シンプルかを整理した学習メモ。あわせて「この境界線は Laravel や Node.js(Hono / Express)でも同じか」にも答えます。

要点は 3 つ。

1. **一番よく使われ、一番シンプルで、おすすめも同じ「方式 B（`services` で DB を立て、Java はホスト）」**
2. **境界線を決めているのは言語ではなく「ビルドが必要か、イメージを引くだけか」**。ビルドが要るもの（アプリ・ランタイム）はホスト、引くだけのもの（MySQL などのミドルウェア）は `services`
3. **したがって Laravel / Node.js とまったく同じ境界線でよい**。違うのは `setup-*` アクションの名前とキャッシュ対象のディレクトリだけ

なお現時点でこのリポジトリに `.github/` はまだありません。**これから作るときの指針**としてのメモです。

## 1. 前提 — runner とは何か

GitHub Actions のジョブは **runner**（GitHub が用意する使い捨ての仮想マシン）の上で動きます。`runs-on: ubuntu-latest` と書くと、Ubuntu の仮想マシンが 1 台立ち上がり、ジョブが終われば破棄されます。

ここで「**ホスト**」と言っているのは、この runner の OS そのものを指します。使い捨てなので、**開発環境と違って「データを汚さない配慮」は不要**です。この点が、ローカルのテスト用 DB の話（→ [testing-and-test-database.md](./java/spring/testing-and-test-database.md)）と大きく違います。

ただし**「守る必要が無い」ことと「database 名を変えてよい」ことは別**です。テストの接続先は `build.gradle` が `app_test` に固定しているので、**CI でも `app_test` という名前で作る必要があります**（理由と実測 → 第 10 節）。ここは間違えやすいので先に注意しておきます。

runner から使える「外部のもの」の置き方が、以下の 4 方式です。

## 2. 方式 A — 全部ホストに置く

runner の OS に MySQL を直接インストール（または同梱のものを起動）して使う方式です。

```yaml
name: test
on: [push, pull_request]

jobs:
  backend-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: MySQL を起動して DB を作る
        run: |
          sudo systemctl start mysql.service
          mysql -uroot -proot -e "
            CREATE DATABASE app_test CHARACTER SET utf8mb4;
            CREATE USER 'app'@'%' IDENTIFIED BY 'password';
            GRANT ALL PRIVILEGES ON app_test.* TO 'app'@'%';"

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: gradle

      - name: テスト実行
        working-directory: backend
        env:
          # DB_NAME は書かない。build.gradle の test タスクが app_test に固定するため(→ 第 10 節)
          DB_HOST: 127.0.0.1
          DB_USER: app
          DB_PASSWORD: password
        run: sh ./gradlew test
```

**database 名が `app` ではなく `app_test` なのは誤記ではありません。** `build.gradle` の `test` タスクが接続先を `app_test` に固定しているためです。理由と実測は第 10 節にまとめています。

**利点** — 追加のコンテナが無く、起動が速い。

**欠点** — **runner イメージに MySQL が同梱されていることに依存します**。同梱されるソフトとバージョンは GitHub 側のイメージ更新で変わるため、ある日突然壊れることがあります（公式の `actions/runner-images` リポジトリで都度確認が必要）。また**バージョンを自分で選べません**。開発環境が MySQL 8 なのに runner が別バージョンになる、という食い違いが起きます。

`sudo apt-get install mysql-server` で入れる書き方もありますが、毎回ダウンロードとセットアップの時間がかかります。

**結論: 積極的に選ぶ理由が薄い方式です。**

## 3. 方式 B — `services` で DB を立てる（推奨）

GitHub Actions の `services` は「**このジョブの間だけ、指定したイメージのコンテナを一緒に立ち上げてください**」という機能です。docker compose の簡易版と考えると分かりやすいです。

```yaml
name: test
on:
  push:
    branches: [main]
  pull_request:

jobs:
  backend-test:
    runs-on: ubuntu-latest

    services:
      mysql:
        image: mysql:8                      # 開発環境と同じイメージを指定できる
        env:
          MYSQL_ROOT_PASSWORD: root
          MYSQL_DATABASE: app_test          # ← build.gradle が接続先を app_test に固定している(第 10 節)
          MYSQL_USER: app
          MYSQL_PASSWORD: password
        ports:
          - 3306:3306                       # runner の localhost:3306 に繋ぐ
        options: >-
          --health-cmd="mysqladmin ping -h 127.0.0.1 -uroot -proot"
          --health-interval=5s
          --health-timeout=5s
          --health-retries=10

    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: gradle                     # ~/.gradle をキャッシュ(依存の再ダウンロードを防ぐ)

      - name: テスト実行
        working-directory: backend
        env:
          # DB_NAME は書かない。build.gradle が app_test に固定するので、ここで指定しても無視される
          DB_HOST: 127.0.0.1
          DB_PORT: '3306'
          DB_USER: app
          DB_PASSWORD: password
        run: sh ./gradlew test               # gradlew に実行権限が無いので sh 経由

      - name: テストレポートを保存
        if: always()                         # 失敗時こそ欲しいので always
        uses: actions/upload-artifact@v4
        with:
          name: test-report
          path: backend/build/reports/tests/test
```

**`options` の health-cmd が重要です。** これが無いと、MySQL が起動しきる前にテストが走って「接続できない」で落ちます。docker-compose.yml の `healthcheck` + `depends_on: condition: service_healthy` と同じ役割を、`services` では `options` で書きます。

**利点**

- **バージョンを開発環境と揃えられる**（`mysql:8`）
- イメージを引くだけなのでビルド時間ゼロ
- Java 側は `setup-java` の `cache: gradle` で依存キャッシュが効き、2 回目以降が速い
- **Actions の標準機能なので、書き方の例が世の中に一番多い**

**欠点** — MinIO や Mailpit など「compose にあるが `services` に書き足していないもの」は使えません。必要になったら `services` に足すことになり、compose と二重管理になります。

**結論: 最もよく使われ、最もシンプルで、この方式がおすすめです。**

## 4. 方式 C — docker compose をそのまま使う

開発環境の `docker-compose.yml` を CI でも動かす方式です。

```yaml
name: test
on: [push, pull_request]

jobs:
  backend-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: .env を用意
        run: cp .env.example .env

      - name: MySQL だけ起動して healthy を待つ
        run: docker compose up -d --wait mysql

      - name: テスト用 database を作る
        # compose の mysql は .env の DB_NAME(= app)しか作らないため、app_test は自分で作る必要がある
        run: |
          docker compose exec -T mysql mysql -uroot -proot -e "
            CREATE DATABASE IF NOT EXISTS app_test CHARACTER SET utf8mb4;
            GRANT ALL PRIVILEGES ON app_test.* TO 'app'@'%';
            FLUSH PRIVILEGES;"

      - name: backend コンテナをビルドしてテスト
        run: docker compose run --rm backend sh ./gradlew test
```

**利点** — 「開発環境とまったく同じ構成でテストする」という一貫性。compose を直したら CI も自動的に追従します。

**欠点は 3 つあり、どれも重いです。**

1. **backend イメージのビルドが毎回走る**（`docker/backend/Dockerfile` の `eclipse-temurin:21-jdk` を pull する時間も含む）
2. **Gradle の依存キャッシュが効きにくい**。compose では依存を `gradle-cache` という named volume に置いていますが、**runner は毎回まっさらなのでボリュームも空**です。つまり毎回すべての依存をダウンロードし直します。`actions/cache` でボリュームを保存・復元する設定を自分で書くことになり、方式 B の `cache: gradle` の 1 行と比べて手間が大きく増えます
3. **compose は開発向けに書かれている**。`docker compose up -d` をサービス名なしで実行すると nuxt / minio / minio-init / mailpit まで起動してしまい、テストに不要なものを待つことになります（上の例で `mysql` を明示しているのはこのため）
4. **テスト用 database を作るステップが余分に必要**。compose の mysql が作るのは `.env` の `DB_NAME`（= `app`）だけなので、`app_test` は自分で `CREATE DATABASE` する必要があります。方式 B なら `MYSQL_DATABASE: app_test` の 1 語で済むところが、6 行のステップになります

**結論: 一貫性は魅力だが、遅さと設定量で不利。テストだけを回す目的なら選ばないほうが無難です。**

## 5. 方式 D — ハイブリッド（ミドルウェアは compose、ランタイムはホスト）

「アプリのコンテナは使わず、compose の**ミドルウェアだけ**借りる」方式です。

```yaml
jobs:
  backend-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: cp .env.example .env

      - name: MySQL と MinIO だけ compose で起動
        run: docker compose up -d --wait mysql minio minio-init

      - name: テスト用 database を作る
        run: |
          docker compose exec -T mysql mysql -uroot -proot -e "
            CREATE DATABASE IF NOT EXISTS app_test CHARACTER SET utf8mb4;
            GRANT ALL PRIVILEGES ON app_test.* TO 'app'@'%';
            FLUSH PRIVILEGES;"

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: gradle

      - name: テスト実行(Java はホストで動かす)
        working-directory: backend
        env:
          # DB_NAME は書かない。build.gradle が app_test に固定する
          DB_HOST: 127.0.0.1               # compose が ports で公開しているので localhost で届く
          DB_USER: app
          DB_PASSWORD: password
          S3_ENDPOINT: http://127.0.0.1:9000
        run: sh ./gradlew test
```

方式 C の欠点 1・2（backend イメージのビルドとキャッシュ問題）を回避しつつ、**MinIO や Mailpit のような「`services` に書き写すのが面倒な複数コンテナ構成」を compose の定義ごと再利用**できます。`minio-init` のような初期化ジョブを含む構成では、これが効いてきます。

**結論: 画像アップロードやメール送信のテストを書き始めたら、この方式が最適解になります。今の（DB だけで足りる）段階では方式 B で十分です。**

## 6. 4 方式の比較と結論

| | よく使われるか | 速さ | 設定の量 | 開発環境との一致 | 壊れやすさ |
|---|---|---|---|---|---|
| **A. 全部ホスト** | △ | ◎ | 少 | **×** バージョンを選べない | **×** runner イメージ依存 |
| **B. `services`** ← **推奨** | **◎ 最多** | ◎ | **少** | ○ イメージを揃えられる | ○ |
| **C. docker compose** | △ | **×** ビルド毎回 | 多 | **◎** 完全一致 | ○ |
| **D. ハイブリッド** | ○ | ○ | 中 | ◎ ミドルウェアは一致 | ○ |

**3 つの問いへの答え**

- **一番よく使われる** → **B**。GitHub 公式ドキュメントのサンプルもこの形で、記事もこれが圧倒的に多い
- **一番シンプル** → **B**。DB を足すのに YAML 10 行程度、キャッシュは `cache: gradle` の 1 行
- **おすすめ** → **B**。将来 MinIO / Mailpit が絡むテストを書くときに **D** へ移る

## 7. 境界線の原則 — 何をホストに置き、何をコンテナにするか

方式 B / D に共通する考え方を言葉にすると、こうなります。

> **ビルドが必要なものはホスト、イメージを引いてくるだけのものはコンテナ。**

| 対象 | どこで動かすか | 理由 |
|---|---|---|
| **ランタイム**（JDK / PHP / Node） | **ホスト**（`setup-*` アクション） | 依存キャッシュが効く。コンテナに入れると、ホスト↔コンテナ間でキャッシュを受け渡す設定が別途必要になる |
| **ミドルウェア**（MySQL / Redis / MinIO） | **コンテナ**（`services` か compose） | 引くだけで動く。バージョンをイメージタグで固定できる |
| **アプリ自身のコンテナ** | **CI のテストでは使わない** | ビルド時間がまるごと無駄になる。本番イメージのビルドはデプロイ用の別ジョブでやる |

3 行目が一番の勘所です。「開発を docker compose でやっているから CI もコンテナで」と考えたくなりますが、**テストの目的は「コードが正しいか」であって「コンテナが正しく組めるか」ではありません**。イメージのビルド検証が必要なら、テストとは別のジョブに分けます。

## 8. Laravel / Node.js と同じ境界線でよいか

**結論: まったく同じでよいです。** 上の原則は言語に依存していません。

| | ランタイムの入れ方 | DB | キャッシュ対象 | テストコマンド |
|---|---|---|---|---|
| **Java (Spring Boot)** | `actions/setup-java`（temurin 21） | `services: mysql:8` | `~/.gradle`（`cache: gradle`） | `./gradlew test` |
| **PHP (Laravel)** | `shivammathur/setup-php` | `services: mysql:8` | Composer のキャッシュ | `php artisan test` |
| **Node.js (Hono / Express)** | `actions/setup-node` | `services: mysql:8` / `postgres` | `~/.npm`（`cache: npm`） | `npm test` |

**同じ**なのは、境界を決めている理由が言語と無関係だからです。どの言語でも、ランタイムはビルド・キャッシュの対象で、DB は引いてくるだけのミドルウェアです。

**違うのは 3 点だけ**で、いずれも境界線の話ではありません。

1. **`setup-*` アクションの名前**（`setup-java` / `setup-php` / `setup-node`）
2. **キャッシュするディレクトリ**（`~/.gradle` / Composer / `~/.npm`）
3. **マイグレーションの流し方** — ここだけは Java が少し楽です

3 について補足すると、**Spring Boot は Flyway をアプリ起動時に自動で実行する**ため、CI に「マイグレーションを流すステップ」が要りません。テストが起動すればスキーマができています（実測 → [testing-and-test-database.md](./java/spring/testing-and-test-database.md)）。

一方、Laravel や Node.js では明示的なステップが必要です。

```yaml
      # Laravel
      - run: php artisan migrate --force
      # Prisma を使う Node.js
      - run: npx prisma migrate deploy
```

つまり **Java の CI は他言語より 1 ステップ少なくて済む**、という違いです。逆に言えば、他言語の CI 記事を読むときに出てくる migrate ステップを「Java にも要るのか」と探す必要はありません。

## 9. このプロジェクトに当てはめた最終形

方式 B の YAML（第 3 節）がそのまま答えになりますが、このプロジェクト固有の注意点を 3 つ挙げます。

**① `sh ./gradlew` にする** — `backend/gradlew` は git 上のファイルモードが `100644`（実行権限なし）です。`./gradlew test` と書くと `Permission denied` で落ちます。`sh ./gradlew test` にするか、事前に `chmod +x gradlew` を挟みます。

**② `working-directory: backend` を忘れない** — `gradlew` はリポジトリ直下ではなく `backend/` にあります。

**③ CI でも DB 名は `app_test` にする** — 「runner の DB は毎回捨てられるのだから `app` でよいのでは」と考えたくなりますが、**それでは CI が落ちます**。`build.gradle` の `test` タスクが接続先を `app_test` に固定しており、ワークフロー側で `DB_NAME: app` と書いても**無視される**ためです。理由は次の節。

## 10. 落とし穴 — Gradle の `environment` はワークフローの `env:` に勝つ

このプロジェクトの `build.gradle` には次の指定があります。

```groovy
tasks.named('test') {
	useJUnitPlatform()
	environment 'DB_NAME', 'app_test'
}
```

`environment` は「**このタスクが起動するテスト JVM の環境変数を設定する**」指定です。ここが重要で、**外から渡された同名の環境変数を上書きします**。優先順位はこうなります。

```
ワークフローの env:  DB_NAME: app
        ↓ 上書きされる（負ける）
build.gradle の environment 'DB_NAME', 'app_test'
        ↓
テスト JVM が実際に見る値: app_test
```

実測で確認できます。存在しない database 名を外から渡しても、テストは平然と成功します。

```
$ docker compose exec -e DB_NAME=nosuchdb backend sh ./gradlew test --tests '*ApplicationTests*'
BUILD SUCCESSFUL
```

`nosuchdb` という database は存在しないのに成功しています。つまり**その値は使われておらず、`app_test` に接続している**ということです。

したがって、CI で `MYSQL_DATABASE: app` としてしまうと次の食い違いが起きます。

| | 作られる database | テストが探す database |
|---|---|---|
| ❌ `MYSQL_DATABASE: app` | `app` | `app_test` → **`Unknown database 'app_test'` で全滅** |
| ✅ `MYSQL_DATABASE: app_test` | `app_test` | `app_test` → 成功 |

**「ローカルと CI で接続先が揃っている」ことは欠点ではなく利点です。** `build.gradle` が単一の真実の源になっているので、「テストは必ず `app_test` を使う」という不変条件が環境によらず保たれます。

なお「CI からも上書きできるように」と次のような形にするのは**避けてください**。

```groovy
// ✗ これはローカルの安全装置を壊す
environment 'DB_NAME', System.getenv('DB_NAME') ?: 'app_test'
```

backend コンテナには `env_file: .env` 経由で **`DB_NAME=app` が入っています**（`docker compose exec backend printenv DB_NAME` → `app`）。この書き方にすると `System.getenv('DB_NAME')` が `app` を返し、**ローカルのテストが再び開発 DB に接続します**。`PostRepositoryTest.setUp()` の `deleteAll()` が開発中の投稿を狙うことになります。

## つまずきポイント

- **`services` に health-cmd を書かないと、MySQL の起動前にテストが走って落ちる。** compose の `healthcheck` + `depends_on` に相当するものを `options` に書く
- **`services` への接続先は `127.0.0.1`。** compose のようにサービス名（`mysql`）では引けません（ジョブ自体をコンテナで動かす場合は逆にサービス名になります）
- **`gradlew` の実行権限。** `sh ./gradlew` で回避する（なぜ 644 なのか、なぜ CI でも同じ問題が起きるのかの仕組み → [file-permissions-and-exec-bit.md](./file-permissions-and-exec-bit.md)）
- **compose 方式では Gradle の依存キャッシュが毎回空。** named volume は runner に残らないため、依存を毎回ダウンロードし直す
- **`docker compose up -d` をサービス名なしで打つと、nuxt / minio / mailpit まで起動する。** CI では必要なサービス名を明示する
- **`upload-artifact` に `if: always()` を付け忘れる。** 失敗時にこそレポートが欲しいのに、既定ではテストが落ちるとこのステップが飛ばされる
- **`MYSQL_DATABASE: app` にしてしまう。** runner は使い捨てなので「開発 DB を守る」必要は無いが、**`build.gradle` がテストの接続先を `app_test` に固定している**ので、CI でも `app_test` を作らないと `Unknown database 'app_test'` で全滅する（→ 第 10 節）
- **ワークフローの `env:` に `DB_NAME` を書いて安心してしまう。** Gradle の `environment` に負けるので効きません。書いても無害ですが、読む人を誤解させるので書かないほうがよい

## 用語集

- **CI（継続的インテグレーション）** — push や PR のたびに自動でビルド・テストを走らせる仕組み
- **runner** — GitHub Actions のジョブを実行する使い捨ての仮想マシン。`runs-on` で指定する
- **ジョブ / ステップ** — ジョブは 1 台の runner で動く作業単位、ステップはその中の 1 コマンド
- **`services`** — ジョブの間だけ補助コンテナを立ち上げる Actions の機能。docker compose の簡易版
- **health-cmd** — コンテナが「使える状態になったか」を判定するコマンド。準備完了を待つために使う
- **`actions/setup-java`** — runner に JDK を入れ、Gradle / Maven の依存キャッシュも面倒を見る公式アクション
- **`actions/upload-artifact`** — ジョブの成果物（テストレポートなど）を保存し、後からダウンロードできるようにするアクション
- **`workflow_dispatch`** — 手動実行のトリガー。このリポジトリでは Terraform の apply / destroy に使う方針（→ `docs/infrastructure/README.md`）
- **ミドルウェア** — アプリが利用する DB・ストレージ・メールなどの基盤ソフト
- **`environment`（Gradle の `Test` タスク）** — テスト JVM に渡す環境変数の指定。**外から渡された同名の環境変数を上書きする**ため、CI のワークフローで `env:` に書いた値より強い

## 関連

- **ローカルのテスト運用手順**（`app_test` の作り方・実行コマンド・テスト一覧） → [docs/test/README.md](../test/README.md)
- ローカルでのテストの仕組み、テスト専用 DB、実行コマンド → [java/spring/testing-and-test-database.md](./java/spring/testing-and-test-database.md)
- 開発環境の 5 コンテナ構成と環境変数の方針 → [docs/development/README.md](../development/README.md)
- Terraform + GitHub Actions（OIDC 認証・`workflow_dispatch` での apply / destroy） → [docs/infrastructure/README.md](../infrastructure/README.md)
- 言語ごとのビルドツールとパッケージ管理の対応（Gradle / Composer / npm） → [build-and-tooling-by-language.md](./build-and-tooling-by-language.md)
- 環境変数の基本と `${VAR:default}` の読み方 → [env-vars-basics.md](./env-vars-basics.md)
- ファイル権限の保存場所（inode）、umask、git のファイルモード、artifact で実行ビットが失われる話 → [file-permissions-and-exec-bit.md](./file-permissions-and-exec-bit.md)
