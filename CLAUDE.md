# CLAUDE.md

このリポジトリは **学習用** のフルスタック開発練習リポジトリです。回答・ドキュメントは日本語で書いてください。

## 作業ルール

- **gitの操作(commit / push など)はユーザー自身が行う。Claudeは勝手にコミット・プッシュしないこと。** 変更を加えたら内容を報告し、コミットはユーザーに任せる。

## プロジェクト概要

Nuxt 3 + Spring Boot のアプリケーションを docker-compose で開発し、検証したいときだけ Terraform(GitHub Actions 経由)で AWS に環境を構築・撤収する。**常時公開はしない。**

## 技術スタック

- フロントエンド: Nuxt 3(**SSG モード** `nuxt generate`)、Node 22 LTS、npm
- バックエンド: Java 21、Spring Boot 4.x、Gradle
- DB: MySQL 8(本番は RDS)
- 画像保存: MinIO(開発)/ S3 + CloudFront(本番)
- メール送信: Mailpit(開発)/ SES(本番)
- インフラ: Terraform + GitHub Actions(OIDC 認証、workflow_dispatch で apply/destroy)
- AWS: 独自ドメイン(Route53 + ACM)、ALB → ECS Fargate → RDS、ECR

## フォルダ構成

```
├── frontend/    Nuxt 3 プロジェクト
├── backend/     Spring Boot プロジェクト
├── terraform/   Terraform コード
├── docker/      Dockerfile 置き場(docker-compose.yml はリポジトリ直下)
└── docs/        ドキュメント
```

## アーキテクチャ上の重要な決定事項

1. **Nginx は使わない。** Spring Boot の組み込み Tomcat が直接 HTTP を受ける。本番の TLS 終端・負荷分散は ALB が担当
2. **Nuxt は SSG ビルドし、出力を Spring Boot の `static/` に配置して配信する。** 本番に Node.js コンテナは置かない。フロント配信用の S3 + CloudFront / Amplify は使わない
3. S3 + CloudFront は**ユーザーアップロード画像専用**
4. REST API はすべて `/api/**` 配下。フロントは相対パス `/api` を呼ぶ(開発時は Nuxt の devProxy が backend:8080 へ転送)
5. AWS 環境は使い終わったら `terraform destroy` で撤収する運用

## ドキュメント

- `docs/tech-stack/` — 技術スタックと選定理由(Nginx 不採用・SSG 採用の理由もここ)
- `docs/infrastructure/` — AWS 構成図、Terraform + GitHub Actions の運用フロー
- `docs/development/` — docker-compose 開発環境の構成(5 コンテナ、ポート、環境変数方針)
- `docs/setup/` — Nuxt / Spring Boot の環境構築手順
- `docs/notes/` — 学習メモ(セッションで解説した内容の記録。1 トピック 1 ファイル)
- `docs/superpowers/specs/` — 設計書(スペック)置き場

設計に関わる変更をしたら、該当ドキュメントも更新すること。

## ビルド・実行コマンド

(アプリ実装後に追記する)

- 開発環境: `docker compose up -d`
- フロント: `cd frontend && npm run dev` / SSG ビルドは `npm run generate`
- バックエンド: `cd backend && ./gradlew bootRun` / ビルドは `./gradlew build`
