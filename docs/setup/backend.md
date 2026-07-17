# Spring Boot 環境構築手順

バックエンド(Spring Boot 4.x)の初期構築手順。プロジェクトは `backend/` に作成する。

## 前提ツール

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
```

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

## 本番イメージのビルドの流れ(CI)

1. `frontend/` で `npm run generate` → `.output/public/` を `backend/src/main/resources/static/` にコピー
2. `./gradlew build` で Jar を作成
3. `docker/` の Dockerfile でイメージ化し、ECR に push
