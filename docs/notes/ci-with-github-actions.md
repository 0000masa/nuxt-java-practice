# GitHub Actions で自動テスト — ホスト / services / docker compose の境界線

GitHub Actions で自動テストを回すとき、**Java や MySQL をどこで動かすか**には 4 つの選択肢があります。それぞれの YAML を並べ、どれが一番よく使われ、どれが一番シンプルかを整理した学習メモ。あわせて「この境界線は Laravel や Node.js(Hono / Express)でも同じか」にも答えます。

要点は 3 つ。

1. **一番よく使われ、一番シンプルで、おすすめも同じ「方式 B（`services` で DB を立て、Java はホスト）」**
2. **境界線を決めているのは言語ではなく「ビルドが必要か、イメージを引くだけか」**。ビルドが要るもの（アプリ・ランタイム）はホスト、引くだけのもの（MySQL などのミドルウェア）は `services`
3. **したがって Laravel / Node.js とまったく同じ境界線でよい**。違うのは `setup-*` アクションの名前とキャッシュ対象のディレクトリだけ

なお現時点でこのリポジトリに `.github/` はまだありません。**これから作るときの指針**としてのメモです。

## 1. 前提 — runner とは何か

GitHub Actions のジョブは **runner**（GitHub が用意する使い捨ての仮想マシン）の上で動きます。`runs-on: ubuntu-latest` と書くと、Ubuntu の仮想マシンが 1 台立ち上がり、ジョブが終われば破棄されます。

ここで「**ホスト**」と言っているのは、この runner の OS そのものを指します。使い捨てなので、**開発環境と違って「汚さない配慮」は不要**です。この点が、ローカルのテスト用 DB の話（→ [testing-and-test-database.md](./java/spring/testing-and-test-database.md)）と大きく違います。

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
            CREATE DATABASE app CHARACTER SET utf8mb4;
            CREATE USER 'app'@'%' IDENTIFIED BY 'password';
            GRANT ALL PRIVILEGES ON app.* TO 'app'@'%';"

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: gradle

      - name: テスト実行
        working-directory: backend
        env:
          DB_HOST: 127.0.0.1
          DB_NAME: app
          DB_USER: app
          DB_PASSWORD: password
        run: sh ./gradlew test
```

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
          MYSQL_DATABASE: app
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
          DB_HOST: 127.0.0.1
          DB_PORT: '3306'
          DB_NAME: app
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

      - name: backend コンテナをビルドしてテスト
        run: docker compose run --rm backend sh ./gradlew test
```

**利点** — 「開発環境とまったく同じ構成でテストする」という一貫性。compose を直したら CI も自動的に追従します。

**欠点は 3 つあり、どれも重いです。**

1. **backend イメージのビルドが毎回走る**（`docker/backend/Dockerfile` の `eclipse-temurin:21-jdk` を pull する時間も含む）
2. **Gradle の依存キャッシュが効きにくい**。compose では依存を `gradle-cache` という named volume に置いていますが、**runner は毎回まっさらなのでボリュームも空**です。つまり毎回すべての依存をダウンロードし直します。`actions/cache` でボリュームを保存・復元する設定を自分で書くことになり、方式 B の `cache: gradle` の 1 行と比べて手間が大きく増えます
3. **compose は開発向けに書かれている**。`docker compose up -d` をサービス名なしで実行すると nuxt / minio / minio-init / mailpit まで起動してしまい、テストに不要なものを待つことになります（上の例で `mysql` を明示しているのはこのため）

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

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: gradle

      - name: テスト実行(Java はホストで動かす)
        working-directory: backend
        env:
          DB_HOST: 127.0.0.1               # compose が ports で公開しているので localhost で届く
          DB_NAME: app
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

**③ CI では DB 名を `app_test` にしなくてよい** — ローカルで `app_test` を分けるのは「開発データを守る」ためです。**runner の DB は毎回捨てられるので守る対象がありません**。`MYSQL_DATABASE: app` のまま使うのが素直で、設定も 1 つ減ります。

## つまずきポイント

- **`services` に health-cmd を書かないと、MySQL の起動前にテストが走って落ちる。** compose の `healthcheck` + `depends_on` に相当するものを `options` に書く
- **`services` への接続先は `127.0.0.1`。** compose のようにサービス名（`mysql`）では引けません（ジョブ自体をコンテナで動かす場合は逆にサービス名になります）
- **`gradlew` の実行権限。** `sh ./gradlew` で回避する
- **compose 方式では Gradle の依存キャッシュが毎回空。** named volume は runner に残らないため、依存を毎回ダウンロードし直す
- **`docker compose up -d` をサービス名なしで打つと、nuxt / minio / mailpit まで起動する。** CI では必要なサービス名を明示する
- **`upload-artifact` に `if: always()` を付け忘れる。** 失敗時にこそレポートが欲しいのに、既定ではテストが落ちるとこのステップが飛ばされる
- **CI のためにテスト DB を分けようとしてしまう。** runner は使い捨てなので不要。分ける必要があるのはローカルだけ

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

## 関連

- ローカルでのテストの仕組み、テスト専用 DB、実行コマンド → [java/spring/testing-and-test-database.md](./java/spring/testing-and-test-database.md)
- 開発環境の 5 コンテナ構成と環境変数の方針 → [docs/development/README.md](../development/README.md)
- Terraform + GitHub Actions（OIDC 認証・`workflow_dispatch` での apply / destroy） → [docs/infrastructure/README.md](../infrastructure/README.md)
- 言語ごとのビルドツールとパッケージ管理の対応（Gradle / Composer / npm） → [build-and-tooling-by-language.md](./build-and-tooling-by-language.md)
- 環境変数の基本と `${VAR:default}` の読み方 → [env-vars-basics.md](./env-vars-basics.md)
