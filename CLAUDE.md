# CLAUDE.md

このリポジトリは **学習用** のフルスタック開発練習リポジトリです。回答・ドキュメントは日本語で書いてください。

## 作業ルール

- **gitの操作(commit / push など)はユーザー自身が行う。Claudeは勝手にコミット・プッシュしないこと。** 変更を加えたら内容を報告し、コミットはユーザーに任せる。

## プロジェクト概要

Nuxt 4 + Spring Boot のアプリケーションを docker-compose で開発し、検証したいときだけ CloudFormation(GitHub Actions 経由)で AWS に環境を構築・撤収する。**常時公開はしない。**

## 技術スタック

- フロントエンド: Nuxt 4(**SSG モード** `nuxt generate`)、Node 22 LTS、npm
- バックエンド: Java 21、Spring Boot 4.x、Gradle
- DB: MySQL 8(本番は RDS)
- 画像保存: MinIO(開発)/ S3 + CloudFront(本番)
- メール送信: Mailpit(開発)/ SES(本番)
- インフラ: **CloudFormation(素の YAML。CDK は使わない)** + GitHub Actions(OIDC 認証、workflow_dispatch でスタック作成/削除)
- AWS: 独自ドメイン(Route53 + ACM)、ALB → ECS Fargate → RDS、ECR

## フォルダ構成

```
├── frontend/    Nuxt 4 プロジェクト
├── backend/     Spring Boot プロジェクト
├── cloudformation/  CloudFormation テンプレート
├── docker/      Dockerfile 置き場(docker-compose.yml はリポジトリ直下)
└── docs/        ドキュメント
```

## アーキテクチャ上の重要な決定事項

1. **Nginx は使わない。** Spring Boot の組み込み Tomcat が直接 HTTP を受ける。本番の TLS 終端・負荷分散は ALB が担当
2. **Nuxt は SSG ビルドし、出力を Spring Boot の `static/` に配置して配信する。** 本番に Node.js コンテナは置かない。フロント配信用の S3 + CloudFront / Amplify は使わない
3. S3 + CloudFront は**ユーザーアップロード画像専用**
4. REST API はすべて `/api/**` 配下。フロントは相対パス `/api` を呼ぶ(開発時は Nuxt の devProxy が backend:8080 へ転送)
5. AWS 環境は使い終わったらスタックを削除して撤収する運用。**Route53 ホストゾーンと ECR は手動管理**で常駐させ、それ以外を 1 スタックで作り捨てる(理由 → `docs/adr/0001-cloudformation-yaml-over-terraform.md` と `docs/infrastructure/README.md`)

## ドキュメント

- `docs/tech-stack/` — 技術スタックと選定理由(Nginx 不採用・SSG 採用の理由もここ)
- `docs/infrastructure/` — AWS 構成図、CloudFormation + GitHub Actions の運用フロー
- `docs/adr/` — アーキテクチャ決定記録(なぜその技術・構成を選んだか)
- `docs/development/` — docker-compose 開発環境の構成(5 コンテナ、ポート、環境変数方針)
- `docs/setup/` — Nuxt / Spring Boot の環境構築手順
- `docs/test/` — テストの実行方法と方針(テスト専用 database `app_test` の作り方、テスト一覧)
- `docs/api/` — REST API ドキュメント(エンドポイントごとに1ファイル。API を変更したら必ず更新すること)
- `docs/notes/` — 学習メモ(セッションで解説した内容の記録。1 トピック 1 ファイル)
- `docs/superpowers/specs/` — 設計書(スペック)置き場(アプリ設計は `2026-07-19-app-design-overview.md`、用語集はリポジトリ直下 `CONTEXT.md`)
- `docs/development/implementation-progress.md` — **実装フェーズ計画と進捗。実装に着手する前に必ず読み、フェーズの開始・完了時に更新すること**

設計に関わる変更をしたら、該当ドキュメントも更新すること。

## ビルド・実行コマンド

(アプリ実装後に追記する)

- 開発環境: `docker compose up -d`
- フロント: `cd frontend && npm run dev` / SSG ビルドは `npm run generate`
- バックエンド: `cd backend && ./gradlew bootRun` / ビルドは `./gradlew build`
- **テスト: リポジトリ直下で `docker compose exec backend sh ./gradlew test`**(クラスを絞るなら `--tests '*PostRepositoryTest*'`、キャッシュを無視するなら `--rerun-tasks`)
  - テストは開発 DB(`app`)ではなく**専用 database `app_test`** を使う(`build.gradle` の `test` タスクが `DB_NAME` を上書きしている)。**`app_test` は初回に手動作成が必要**。手順とテスト方針 → `docs/test/README.md`
- バックエンドの Java 開発は **VS Code の Dev Container**(`.devcontainer/`)で backend コンテナに入って行う(保存で自動コンパイル + devtools 再起動。ホストに JDK 不要)。依存(`build.gradle`)変更時は `docker compose restart backend`。詳細 → `docs/notes/java-dev-env-comparison.md`
- **Claude が backend の Java を編集したら、編集後にリポジトリ直下で `docker compose exec backend sh ./gradlew classes` を実行して反映すること**(ホスト側からの編集は自動コンパイルされない。devtools がコンパイル結果を拾って再起動する。依存変更時は `docker compose restart backend`)
