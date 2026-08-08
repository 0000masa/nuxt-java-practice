# Spring Boot 環境構築手順

バックエンド(Spring Boot 4.x)の初期構築手順。プロジェクトは `backend/` に作成する。

## 前提ツール

> PC 自体のセットアップ(WSL2 / Docker Desktop / VS Code / git / GitHub 接続)から始める場合は → [docs/setup/new-machine.md](./new-machine.md)

| ツール | バージョン | 備考 |
|---|---|---|
| Java (JDK) | 21 | ローカルで直接動かす場合。コンテナ内開発なら不要 |
| Gradle | Wrapper 使用 | プロジェクト同梱の `./gradlew` を使うためインストール不要 |

## プロジェクト作成

[Spring Initializr](https://start.spring.io/) で以下を選択して生成し、`backend/` に展開する。

| 項目 | 選択 |
|---|---|
| Project | Gradle - Groovy |
| Language | Java |
| Spring Boot | 4.x(最新安定版) |
| Java | 21 |
| Packaging | Jar |

依存関係(Dependencies):

- **Spring Web** — REST API(組み込み Tomcat 含む)
- **Spring Data JPA** — DB アクセス
- **MySQL Driver** — MySQL 接続
- **Validation** — リクエストバリデーション
- **Spring Boot DevTools**(任意)— 開発時の自動再起動

CLI で生成する場合:

```bash
cd backend
curl https://start.spring.io/starter.zip \
  -d type=gradle-project \
  -d language=java \
  -d javaVersion=21 \
  -d dependencies=web,data-jpa,mysql,validation,devtools \
  -d baseDir=. \
  -o starter.zip
unzip starter.zip && rm starter.zip
chmod +x gradlew    # baseDir=. のとき Initializr が gradlew に実行権限を付けないため(後述)
```

**`chmod +x gradlew` を忘れないこと。** Spring Initializr は本来ラッパースクリプトを 755 で ZIP に入れるが、**`-d baseDir=.` を指定したときだけ 644 になる**（Initializr 側の不具合。`baseDir` を付けない／`backend` のような名前を指定した場合は 755 になる）。付け忘れると `./gradlew` が `Permission denied` で落ちる。

このリポジトリは付け忘れた状態でコミットされているため、全箇所で `sh ./gradlew` と書いて回避している。仕組みと実測 → [docs/notes/file-permissions-and-exec-bit.md](../notes/file-permissions-and-exec-bit.md)

## 基本設定

`src/main/resources/application.yml`(接続情報は環境変数で注入する方針):

```yaml
spring:
  datasource:
    url: jdbc:mysql://${DB_HOST:localhost}:${DB_PORT:3306}/${DB_NAME:app}
    username: ${DB_USER:app}
    password: ${DB_PASSWORD:password}
  jpa:
    hibernate:
      ddl-auto: update   # 学習用。本格運用ではマイグレーションツールに移行
server:
  port: 8080
```

## API と静的配信の役割分担

- REST API はすべて `/api/**` 配下に実装する(例: `@RequestMapping("/api/todos")`)
- `src/main/resources/static/` に置いたファイルは Spring Boot がそのまま配信する。ここに Nuxt の SSG 出力(`.output/public/` の中身)を配置することで、ページ配信と API を 1 コンテナで担う
- 開発中は Nuxt dev サーバー(:3000)が入口になるため、static/ は空でよい

## 起動と動作確認

```bash
./gradlew bootRun     # http://localhost:8080
```

1. `./gradlew bootRun` で起動し、ヘルスチェック用エンドポイント(または適当な `/api` エンドポイント)が応答すること
2. MySQL(docker-compose の mysql コンテナ)に接続できること
3. `./gradlew build` で実行可能 Jar(`build/libs/*.jar`)が生成されること

## 日常の開発 — Dev Container で backend コンテナに入る

Java のコードは、VS Code の Dev Container で backend コンテナの**中に入って**書く。コンパイルも補完もコンテナ内の JDK で行われるため、**ホスト(WSL)に JDK は不要**(採用理由と仕組み → [手法比較メモ](../notes/java-dev-env-comparison.md))。

1. ホスト側の VS Code に拡張機能「**Dev Containers**」(`ms-vscode-remote.remote-containers`)をインストールする(初回のみ)
2. `docker compose up -d` で環境を起動する
3. リポジトリを開いた VS Code でコマンドパレット → 「**Dev Containers: Reopen in Container**」を実行する。`.devcontainer/devcontainer.json` が読まれ、**今のウィンドウが開き直って** backend コンテナに接続される(初回はコンテナ内に VS Code Server と Java 拡張をダウンロードするため数分かかる)。今のウィンドウをホスト側のまま残したい場合は、「WSL: New WSL Window」で開いた別ウィンドウから「Dev Containers: Open Folder in Container...」でリポジトリ直下を選ぶ(詳細 → [手法比較メモ](../notes/java-dev-env-comparison.md)の「既存ウィンドウを開いたまま、別ウィンドウでコンテナに入る方法」)
4. 動作確認: `.java` を編集して保存 → 自動コンパイルが走り、backend のログ(`docker compose logs -f backend`)に devtools の再起動(`Restarting due to ...`)が出て、変更が反映される

補足:

- ワークスペースはマウント先の `/app`(= `backend/`)のみ。**docs や frontend の編集はホスト側の VS Code ウィンドウで行う**(2 ウィンドウ運用)
- Dev Container のウィンドウを閉じても compose は止まらない(`shutdownAction: "none"`)。VS Code を開いていない間の変更反映は `docker compose restart backend`
- `build.gradle` の依存を追加・変更したときは保存では反映されない。`docker compose restart backend` する
- Spring 用の補完・ダッシュボードが欲しければ「Spring Boot Extension Pack」をコンテナ側に追加してもよい(任意)

## 本番イメージのビルドの流れ

本番イメージ(ECR に push するもの)は**全工程をマルチステージ Dockerfile で完結**させる方針。「イメージが何でできているかは Dockerfile 1 枚を見ればわかる」「`docker build` 一発でどこでも同じイメージが再現できる」ことを優先し、GitHub Actions 側は `docker build` → ECR push だけを担う(方式比較 → [build-and-tooling-by-language.md](../notes/build-and-tooling-by-language.md))。

ステージ構成(本番用 Dockerfile は AWS 構築時に `docker/` 配下へ作成する):

1. **frontend ビルド用ステージ(Node)** — `npm ci && npm run generate` で SSG 出力(`.output/public/`)を作る
2. **backend ビルド用ステージ(JDK)** — ステージ 1 の出力を `src/main/resources/static/` に取り込み、`./gradlew build` で実行可能 jar を作る
3. **実行用ステージ(JRE)** — jar 1 個だけを COPY し、`CMD ["java", "-jar", ...]` で起動する。コンパイラが不要になるので軽量な JRE イメージで足りる
