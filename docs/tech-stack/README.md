# 技術スタック

学習用リポジトリで使用する技術スタックの一覧と、その選定理由をまとめる。

## 一覧

| レイヤー | 技術 | バージョン | 用途 |
|---|---|---|---|
| フロントエンド | Nuxt 4 (Vue 3) | Node 22 LTS / npm | SPA + SSG によるフロントエンド |
| バックエンド | Spring Boot 4.x | Java 21 / Gradle | REST API(`/api/**`)+ 静的ファイル配信 |
| データベース | MySQL | 8.x | アプリケーションデータの永続化 |
| 画像保存(開発) | MinIO | latest | S3 互換のオブジェクトストレージ |
| 画像保存(本番) | Amazon S3 + CloudFront | - | アップロード画像の保存・配信 |
| メール送信(開発) | Mailpit | latest | SMTP 受信 + Web UI でメール確認 |
| メール送信(本番) | Amazon SES | - | メール送信(ドメイン認証) |
| コンテナ | Docker / docker-compose | - | 開発環境の構築 |
| IaC | Terraform | - | AWS リソースの構築・撤収 |
| CI/CD | GitHub Actions | - | Terraform 実行、イメージビルド & ECR push |

## アーキテクチャ上の決定事項

### 1. Nuxt は SSG モード(`nuxt generate`)を使う

Nuxt のレンダリングモードは 3 つある。

- **SSR(デフォルト)**: リクエストごとに Node.js サーバーが HTML を生成する。本番でも Node.js コンテナが必要。
- **SSG(`nuxt generate`)**: **ビルド時に**各ページの HTML を事前生成する。中身入りの HTML が静的ファイルとして出力され、ブラウザ側で JS がハイドレート(引き継ぎ)する。Node.js コンテナ不要。
- **SPA(`ssr: false`)**: ほぼ空の index.html + JS で全描画。Node.js コンテナ不要。

本プロジェクトでは **SSG** を採用する。理由:

- 本番環境に Node.js コンテナを置きたくない(ECS のコンテナを Spring Boot 1 種類にしてコストと構成を最小化する)
- 事前生成された HTML を返すため、初期表示が SPA より速い

注意点: SSG はビルド時点のデータで HTML を生成するため、ログイン後のデータや DB 由来の動的データは従来どおりブラウザから `/api` を呼んで描画する(学習用途では問題にならない)。

### 2. フロントは Spring Boot から配信する(フロント配信用の S3 + CloudFront / Amplify は使わない)

`nuxt generate` の出力(`.output/public/`)を Spring Boot の静的配信ディレクトリ(`src/main/resources/static/`)に配置する。

- ページへのリクエスト → Spring Boot が静的ファイルを返す
- `/api/**` へのリクエスト → Spring Boot の REST API が返す

これにより本番のアプリケーションコンテナは Spring Boot の 1 種類で済む。S3 + CloudFront は**ユーザーがアップロードした画像の保存・配信専用**であり、フロントエンドの配信には使わない。

### 3. Nginx は使わない

Spring Boot は組み込み Tomcat を持つため、Node.js と同様に**単体で HTTP サーバーとして動作する**。さらに本番では ALB が以下を担う:

- TLS 終端(HTTPS 化)
- ロードバランシング
- ヘルスチェック

Nginx をリバースプロキシとして挟んでも役割が ALB とほぼ重複し、コンテナと設定ファイルが増えるだけになるため採用しない。ALB → Spring Boot(組み込み Tomcat)の直結構成とする。

### 4. 必要なときだけ AWS 環境を建てる

本番環境は常時公開ではない。検証したいときに GitHub Actions 経由で `terraform apply` を実行して構築し、終わったら `terraform destroy` で撤収する運用とする。詳細は [docs/infrastructure/README.md](../infrastructure/README.md) を参照。

## 開発環境と本番環境の対応

| 役割 | 開発(docker-compose) | 本番(AWS) |
|---|---|---|
| フロントエンド | Nuxt dev サーバー(Node.js コンテナ) | Spring Boot の static/ から配信 |
| バックエンド | Spring Boot コンテナ | ECS Fargate(Spring Boot) |
| データベース | MySQL コンテナ | RDS(MySQL) |
| 画像保存 | MinIO コンテナ | S3 + CloudFront |
| メール送信 | Mailpit コンテナ | SES |
| TLS 終端・LB | なし(HTTP 直アクセス) | ALB + ACM |

## 関連ドキュメント

- [インフラ構成(AWS)](../infrastructure/README.md)
- [開発環境構成(docker-compose)](../development/README.md)
- [Nuxt 環境構築手順](../setup/frontend.md)
- [Spring Boot 環境構築手順](../setup/backend.md)
