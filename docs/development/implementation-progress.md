# 実装フェーズ計画と進捗

投稿アプリ([設計概要](../superpowers/specs/2026-07-19-app-design-overview.md)、用語は [CONTEXT.md](../../CONTEXT.md))の実装計画と現在地。

**このファイルの運用**: Claude Code のセッション(誰でも・どのセッションでも)が実装に着手するときは、まずこのファイルを読んで現在地を把握し、フェーズの開始・完了時にステータスと「完了メモ」を更新すること。フェーズ内の細かい進捗も「完了メモ」に残してよい。

ステータス: `未着手` / `作業中` / `完了`

| # | フェーズ | 内容 | ステータス |
|---|---|---|---|
| 0 | 設計 | アプリ設計・テーブル設計・各種ドキュメント整備 | 完了 |
| 1 | DB 基盤 | Flyway 導入、全テーブルのマイグレーション(V1)、categories マスタ投入(V2)、パッケージを `com.example.app` に整理 | 完了 |
| 2 | 投稿・タイムライン | posts/categories の API(作成・削除・詳細・タイムライン=カーソルページネーション)+ フロント(タイムライン・投稿詳細・無限スクロール)。認証は未導入のため開発用ユーザーで代用 | 未着手 |
| 3 | 認証(パスワード) | Spring Security + Spring Session JDBC(セッションテーブルは V3)。会員登録 → 確認メール(Mailpit)→ 有効化、ログイン/ログアウト、パスワードリセット。フェーズ2の開発用ユーザーを実認証に置き換え | 未着手 |
| 4 | 認証(Google) | `oauth2Login()` による Google ログイン、同一メールのアカウントリンク(`google_sub` 紐づけ) | 未着手 |
| 5 | いいね | トグル API、タイムライン/詳細でのいいね数・自分のいいね状態表示(N+1 を解決する形で) | 未着手 |
| 6 | 画像 | 投稿画像(最大4枚)・プロフィール画像のアップロード(MinIO/S3)と配信、投稿削除時のオブジェクト削除 | 未着手 |
| 7 | プロフィール | プロフィールページ(ユーザー情報 + 投稿一覧)、本人による編集(表示名・bio・画像) | 未着手 |
| 8 | 検索ラボ | 検索 API(対象・一致方法・カテゴリー・方式/件数切り替え、安全上限)、実行時間計測、EXPLAIN 返却、フロント(条件フォーム・プリセット・計測表示) | 未着手 |
| 9 | シードタスク | タスクモード(`--app.task=seed`)実装。users 1万 / posts 100万 / likes 300万 をセットベース SQL で投入 | 未着手 |
| 10 | index 実験 | 検索ラボで実験用 index(複合・FULLTEXT)の before/after を検証し、結果を `docs/notes/` に記録 | 未着手 |
| 11 | SSG 統合 | `nuxt generate` → Spring Boot `static/` 配置の本番形を確認。SPA フォールバック設定 | 未着手 |
| 12 | AWS 運用 | `db-task.yml`(ECS Run Task で migrate/seed)、SES/S3 の本番設定。Terraform 側の作業と合わせて別途設計 | 未着手 |

## 実装方針(全フェーズ共通)

- バックエンドは**機能別パッケージ**(`com.example.app` 直下に `auth` / `user` / `post` / `like` / `category` / `searchlab` / `seed` + `config` / `common`)。詳細 → [backend-structure-best-practices.md](./backend-structure-best-practices.md)
- フロントは Nuxt 4 の `app/` 配下に配置。API 通信は composables に集約。詳細 → [frontend-structure-best-practices.md](./frontend-structure-best-practices.md)
- テストは**要所に絞る**(案A): ページネーションのクエリ、認証の境界(未確認メール・期限切れトークン)、いいねの重複防止、代表的な `@WebMvcTest` を数本
- スキーマ変更はすべて Flyway(`backend/src/main/resources/db/migration/`)。`ddl-auto` は `validate`
- 実験用 index(複合 index・FULLTEXT)は**マイグレーションに入れない**(フェーズ10で手動 ALTER して before/after を比較するため)
- backend の Java を編集したら `docker compose exec backend sh ./gradlew classes` で反映(CLAUDE.md 参照)

## 完了メモ

- **フェーズ1**(2026-07-19): Flyway 導入完了。V1(全6テーブル)+ V2(categories 10件)適用済みを MySQL 実機で確認。パッケージは `com.example.demo` → `com.example.app`(メインクラス `Application`)、`rootProject.name = 'app'`、`ddl-auto: validate` + `open-in-view: false` に変更。**注意: Spring Boot 4 は自動設定がモジュール分割されており、`flyway-core` 単体では Flyway が有効にならない。`spring-boot-starter-flyway` が必要**(+ MySQL 用に `flyway-mysql`)
- **フェーズ0**(2026-07-19): 設計確定。成果物 → `docs/superpowers/specs/2026-07-19-app-design-overview.md`、`CONTEXT.md`、backend/frontend の structure-best-practices。Nuxt は実際には 4 系だったためドキュメント側を Nuxt 4 表記に修正済み
