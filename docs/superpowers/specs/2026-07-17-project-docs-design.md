# プロジェクト初期ドキュメント整備 設計書

日付: 2026-07-17
ステータス: 承認済み

## 目的

学習用リポジトリ(Nuxt.js + Spring Boot + AWS)の初期セットアップとして、フォルダ構成の作成と、技術スタック・インフラ構成・開発環境・環境構築手順のドキュメント、および Claude Code 用の CLAUDE.md を整備する。

## 確定したアーキテクチャ方針

| 項目 | 決定内容 |
|---|---|
| フロントエンド | Nuxt 3(SSGモード = `nuxt generate`)、Node 22 LTS、npm |
| フロント配信方式 | SSGビルド成果物を Spring Boot の `static/` に配置。本番に Node.js コンテナは置かない |
| バックエンド | Java 21 + Spring Boot 3.x + Gradle。組み込み Tomcat が直接 HTTP を受ける |
| Nginx | **使わない**(ALB が TLS 終端・負荷分散を担い役割が重複するため) |
| 開発環境 | docker-compose で 5 コンテナ: Nuxt(dev)/ Spring Boot / MySQL 8 / MinIO / Mailpit |
| AWS 構成 | 独自ドメイン(Route53 + ACM)、ALB → ECS Fargate → RDS(MySQL)、SES(ドメイン認証)、画像用 S3 + CloudFront、ECR |
| IaC / CD | Terraform(state は S3 バックエンド)を GitHub Actions の `workflow_dispatch` で実行。apply で構築 / destroy で撤収。AWS 認証は OIDC |

## 主要な設計判断と理由

1. **SSG を選択** — 「ビルド時に HTML を事前生成し、ブラウザで JS がハイドレートする」挙動を求めており、本番で Node.js コンテナを持ちたくないため。動的データはブラウザから `/api` を呼んで描画する。
2. **Nginx を使わない** — Spring Boot は組み込み Tomcat により単体で HTTP サーバーとして動作する。TLS 終端・ヘルスチェック・負荷分散は ALB が担うため、Nginx を挟むと役割が重複しコンテナと設定が増えるだけになる。
3. **フロント配信用の S3 + CloudFront / Amplify は使わない** — ページ配信は Spring Boot の静的配信で行う。S3 + CloudFront はユーザーアップロード画像の保存・配信専用。
4. **必要なときだけ建てる運用** — 常時公開ではなく、検証したいときに GitHub Actions 経由で Terraform apply、終わったら destroy する。

## 作成する成果物

```
├── CLAUDE.md
├── frontend/   (.gitkeep のみ)
├── backend/    (.gitkeep のみ)
├── terraform/  (.gitkeep のみ)
├── docker/     (.gitkeep のみ、Dockerfile 置き場)
└── docs/
    ├── tech-stack/README.md       技術スタックと選定理由
    ├── infrastructure/README.md   AWS 構成・Terraform 運用
    ├── development/README.md      docker-compose 開発環境構成
    └── setup/
        ├── frontend.md            Nuxt 環境構築手順
        └── backend.md             Spring Boot 環境構築手順
```

- frontend / backend / terraform / docker の中身は今回作成しない(フォルダのみ)。
- CLAUDE.md にはプロジェクト概要・技術スタック・フォルダ構成・アーキテクチャ決定事項・ドキュメントの場所を記載し、コードが増えた段階でビルド・テストコマンドを追記する。
