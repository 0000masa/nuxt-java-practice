# 実装フェーズ計画と進捗

投稿アプリ([設計概要](../superpowers/specs/2026-07-19-app-design-overview.md)、用語は [CONTEXT.md](../../CONTEXT.md))の実装計画と現在地。

**このファイルの運用**: Claude Code のセッション(誰でも・どのセッションでも)が実装に着手するときは、まずこのファイルを読んで現在地を把握し、フェーズの開始・完了時にステータスと「完了メモ」を更新すること。フェーズ内の細かい進捗も「完了メモ」に残してよい。

ステータス: `未着手` / `作業中` / `完了`

| # | フェーズ | 内容 | ステータス |
|---|---|---|---|
| 0 | 設計 | アプリ設計・テーブル設計・各種ドキュメント整備 | 完了 |
| 1 | DB 基盤 | Flyway 導入、全テーブルのマイグレーション(V1)、categories マスタ投入(V2)、パッケージを `com.example.app` に整理 | 完了 |
| 2 | 投稿・タイムライン | posts/categories の API(作成・削除・詳細・タイムライン=カーソルページネーション)+ フロント(タイムライン・投稿詳細・無限スクロール)。認証は未導入のため開発用ユーザーで代用 | 完了 |
| 3 | 認証(パスワード) | Spring Security + Spring Session JDBC(セッションテーブルは V3)。会員登録 → 確認メール(Mailpit)→ メール確認、ログイン/ログアウト、パスワードリセット、**パスワード変更**。フェーズ2の開発用ユーザーを実認証に置き換え。**設計 → [2026-08-05-phase3-auth-design.md](../superpowers/specs/2026-08-05-phase3-auth-design.md)** | 完了 |
| 4 | 認証(Google) | `oauth2Login()` による Google ログイン、同一メールのアカウントリンク(`google_sub` 紐づけ) | 未着手 |
| 5 | いいね | トグル API、タイムライン/詳細でのいいね数・自分のいいね状態表示(N+1 を解決する形で) | 未着手 |
| 6 | 画像 | 投稿画像(最大4枚)・プロフィール画像のアップロード(MinIO/S3)と配信、投稿削除時のオブジェクト削除 | 未着手 |
| 7 | プロフィール | プロフィールページ(ユーザー情報 + 投稿一覧)、本人による編集(表示名・bio・画像) | 未着手 |
| 8 | 検索ラボ | 検索 API(対象・一致方法・カテゴリー・方式/件数切り替え、安全上限)、実行時間計測、EXPLAIN 返却、フロント(条件フォーム・プリセット・計測表示) | 未着手 |
| 9 | シードタスク | タスクモード(`--app.task=seed`)実装。users 1万 / posts 100万 / likes 300万 をセットベース SQL で投入 | 未着手 |
| 10 | index 実験 | 検索ラボで実験用 index(複合・FULLTEXT)の before/after を検証し、結果を `docs/notes/` に記録 | 未着手 |
| 11 | SSG 統合 | `nuxt generate` → Spring Boot `static/` 配置の本番形を確認。SPA フォールバック設定 | 未着手 |
| 12 | AWS 運用 | `db-task.yml`(ECS Run Task で migrate/seed)、SES/S3 の本番設定。CloudFormation テンプレート側の作業と合わせて別途設計 | 未着手 |
| 13 | インフラコード | CloudFormation テンプレート(素の YAML)の作成。**ファイル分割・環境差分の共通化方式は未確定で、着手前に別セッションで設計を議論する**。方針 → [ADR-0001](../adr/0001-cloudformation-yaml-over-terraform.md) と [infrastructure/README.md](../infrastructure/README.md) | 未着手 |

## 実装方針(全フェーズ共通)

- バックエンドは**機能別パッケージ**(`com.example.app` 直下に `auth` / `user` / `post` / `like` / `category` / `searchlab` / `seed` + `config` / `common`)。詳細 → [backend-structure-best-practices.md](./backend-structure-best-practices.md)
- フロントは Nuxt 4 の `app/` 配下に配置。API 通信は composables に集約。詳細 → [frontend-structure-best-practices.md](./frontend-structure-best-practices.md)
- テストは**要所に絞る**(案A): ページネーションのクエリ、認証の境界(未確認メール・期限切れトークン)、いいねの重複防止、代表的な `@WebMvcTest` を数本
- スキーマ変更はすべて Flyway(`backend/src/main/resources/db/migration/`)。`ddl-auto` は `validate`
- 実験用 index(複合 index・FULLTEXT)は**マイグレーションに入れない**(フェーズ10で手動 ALTER して before/after を比較するため)
- backend の Java を編集したら `docker compose exec backend sh ./gradlew classes` で反映(CLAUDE.md 参照)

## 完了メモ

- **フェーズ3 完了**(2026-08-06): [設計](../superpowers/specs/2026-08-05-phase3-auth-design.md) §9 の 7 ステップすべて完了。テスト 29 本すべて成功。
  - **ステップ4**: パスワードリセット(申請 → メール → 実行)、ログイン中のパスワード変更、セッション無効化。`UserSessionManager` が `FindByIndexNameSessionRepository#findByPrincipalName` でそのユーザーのセッションを引いて削除する(`SPRING_SESSION.PRINCIPAL_NAME` の index を使う)。リセットは全件削除、パスワード変更は操作中のセッション以外を削除。あわせて未使用のリセットトークンも失効させる
  - **ステップ5**: 認可を確定(公開: 閲覧系 GET と認証系 / 認証必須: `POST /api/posts`、`DELETE /api/posts/{id}`、`PUT /api/auth/password`)。**`CurrentUserProvider` / `DevCurrentUserProvider` を削除**し `@AuthenticationPrincipal` に置き換え。`PostService` は `create(request, userId)` / `delete(id, userId)` に変更
  - **ステップ6**: Pinia 導入。`stores/auth.ts` / `plugins/api.ts`(CSRF ヘッダ + 401 共通処理)/ `plugins/auth.client.ts`(起動時に `/api/auth/me`)/ `composables/useAuth.ts` / `middleware/auth.ts` / 6 ページ / ヘッダの導線。`PostCard` の削除ボタンは自分の投稿にだけ出す。`usePosts` は `$fetch` → `$api` に変更(CSRF ヘッダが必要なため)
  - **ステップ7**: テスト 3 クラス追加(`AuthTokenServiceTest` 7 本 / `AuthControllerTest` 7 本 / `AuthFlowTest` 2 本)、`docs/api/` に認証系 9 ファイル追加 + 既存 3 ファイル更新、`docs/test/README.md` のテスト一覧更新。**合計 29 本すべて成功**
  - **動作確認済み(ステップ4〜6)**:
    - パスワード変更: 2 端末でログイン → 変更 → セッション 2 → 1、操作端末は維持、別端末は `user: null`、旧パスワードは 401
    - パスワードリセット: 未登録 400 / 未確認 400 / 確認済み 204 + メール到着 → 実行で**セッション 0 件**(全端末追い出し)→ 2 回目のリンクは 400
    - 認可: 公開 GET 4 本すべて 200、未ログイン POST は CSRF なしで 403 / CSRF ありで 401、ログイン後の投稿は 201 で投稿者が正しい、他人の投稿の削除は 403
    - `eraseCredentials` の効果: `SPRING_SESSION_ATTRIBUTES` にパスワードハッシュが残らないことを実データで確認(実装前に作られたセッションには残っていたので、対比で確かめられた)
    - フロント: 8 ページすべて 200、devProxy 経由で Cookie と `X-XSRF-TOKEN` が正しく通る(`/api/auth/me` → ログイン → 投稿 → ログアウト)
    - **SSG ビルド**(`npm run generate`): 16 ルートのプリレンダ成功。`/settings/password` が「`/login` へのリダイレクト」ではなく本来の内容として静的化されており、middleware の `import.meta.server` ガードが効いていることを生成 HTML で確認
  - **未確認**: ブラウザでの実操作(フォーム送信、Pinia の状態遷移、ヘッダの表示切り替え)。curl と SSG ビルドまでは通っているが、画面上のクリック操作は試していない
  - **ステップ1**: 依存追加(`spring-boot-starter-security` / `spring-boot-starter-session-jdbc` / `spring-boot-starter-mail` / test に `spring-boot-starter-security-test`)、V3(Spring Session の公式 MySQL DDL を jar から取り出して取り込み)、`SecurityConfig` の骨格。`application.yml` に `spring.session.timeout: 1d` と `spring.session.jdbc.initialize-schema: never`
  - **ステップ2**: `AppUserDetails` / `AppUserDetailsService` / `formLogin` / `logout` / `GET /api/auth/me` / CSRF / `AuthResponseWriter`(Spring Security のリダイレクトを JSON に差し替える役)。curl で全経路を確認済み(CSRF なし → 403、パスワード誤り・未登録・パスワード未設定 → すべて同一の 401、未確認メール → 区別した 401、成功 → 200 + `SESSION` Cookie + `SPRING_SESSION` に 1 行、ログアウト → 204 + セッション行削除)
  - **ステップ3**: `AuthToken` / `AuthTokenPurpose` / `AuthTokenRepository` / `AuthTokenService`(SHA-256 ハッシュ保存)/ `AuthMailSender`(`@TransactionalEventListener(AFTER_COMMIT)`)/ `AuthService.signup` `verifyEmail` `resendVerification` / `AuthController` / `config/AppProperties`。`.env` と `.env.example` に `APP_BASE_URL` と `MAIL_FROM` を追加。共通例外を 2 つ追加(`InvalidRequestException` → 400、`FieldValidationException` → 400 + `fieldErrors`)
  - **ステップ3 の動作確認済み**: 登録 → Mailpit にメール到着 → 未確認ではログイン 401 → メール確認 204 → 同じリンク 2 回目は 400 → ログイン成功 200。確認済みメールの再登録 → 400 `fieldErrors.email`、既存 username → 400 `fieldErrors.username`、**未確認メールの再登録 → users の id が変わる(削除して作り直し = ADR-0003 の pre-hijacking 対策が効いている)**、再送は確認済み 400 / 未確認 204 / 未登録 400
  - **Spring Boot 4 / Spring Security 7 で判明した差異**(設計時の想定と違った点):
    - **Jackson が 3 系**。`com.fasterxml.jackson.databind` は存在せず `tools.jackson.databind` に移動している(アノテーションだけ `com.fasterxml.jackson.annotation` のまま)
    - **Spring Security は 7.1.0**。CSRF は `csrf(csrf -> csrf.spa())` の 1 行で済む。設計時に想定していた「XOR ハンドラの差し替え + 初回 Cookie 発行の自作フィルタ」は不要になった(`spa()` が Cookie リポジトリ・SPA 向けトークン解決・認証成功/ログアウト後の再発行をまとめて面倒を見る)
    - セッション用の starter は素の `spring-session-jdbc` ではなく **`spring-boot-starter-session-jdbc`**(Flyway と同じく、自動設定はこちら側に入っている)
    - **`@WebMvcTest` は Boot の既定のセキュリティ設定(全リクエスト認証必須)を使う。** アプリの認可ルールを効かせるには `@Import(SecurityConfig.class)` が必要で、これを入れないと公開しているはずの `GET /api/posts` や `GET /api/categories` が 401 になる。入れる場合は `SecurityConfig` が要求する `AuthResponseWriter` を `@MockitoBean` で用意することになり、その副作用で **401 / 403 のステータスコード自体はこのスライスで検証できなくなる**(ステータスを書くのがモックにした側なので)
    - `@AutoConfigureMockMvc` の import は `org.springframework.boot.webmvc.test.autoconfigure`
  - **残っている開発データ**: `masa@example.com` / パスワード `resetpass123`(確認済み。動作確認にそのまま使える)、`pending@example.com`(未確認のまま)、`dev_user`(パスワード未設定なのでログイン不可 = フェーズ4 の Google 専用ユーザーと同じ状態)
  - **フェーズ4 への申し送り**: `SecurityConfig` に `oauth2Login()` を足す形になる。`AppUserDetails` は `users.id` とメールしか持たないので Google 由来のユーザーにもそのまま使える。`dev_user` が「メール確認済み・パスワード未設定」の状態で残っているので、アカウントリンクの検証データとして使える
  - **別途相談したい点**: `npm audit` が 6 件(critical 1 件)を報告している。すべて Nuxt のツールチェーン側(`@nuxt/devtools` / `@nuxt/vite-builder` / `brace-expansion` / `postcss` / `tar`)で SSG 出力には含まれないが、critical の `@nuxt/devtools`(未認証 RPC による任意コマンド実行)は `devtools: { enabled: true }` のまま dev サーバーを `0.0.0.0:3000` で公開しているため、共有ネットワークでは注意が必要
- **フェーズ3 の設計確定**(2026-08-05): 実装前に設計を詰めた。成果物 → [2026-08-05-phase3-auth-design.md](../superpowers/specs/2026-08-05-phase3-auth-design.md)(決定 14 件・API 一覧・フロー図・実装順序)、[ADR-0002](../adr/0002-session-cookie-over-jwt.md)(セッション Cookie 方式を採り JWT を発行しない)、[ADR-0003](../adr/0003-account-enumeration-and-unverified-signup.md)(ユーザー列挙を許容し未確認アカウントは再登録で作り直す)、`CONTEXT.md` に「メール確認 / パスワードリセット / パスワード変更」を追加。**スコープ追加**: パスワードリセット時のセッション無効化と同じ仕組みが使えるため、ログイン中のパスワード変更(`PUT /api/auth/password` + `/settings/password`)も含めることにした。**方針転換**: フェーズ2 で用意した `CurrentUserProvider` / `DevCurrentUserProvider` は使わず削除し、Spring Security 標準の `@AuthenticationPrincipal` に寄せる(`PostController` / `PostService` / `PostControllerTest` のシグネチャ変更を伴う)
- **テスト DB の分離**(2026-07-26): テストの接続先を開発 DB(`app`)から専用 database `app_test` に切り替えた。`build.gradle` の `test` タスクで `environment 'DB_NAME', 'app_test'` を指定するだけで、`application.yml` の `${DB_NAME:app}` を上書きできる。`app_test` は**クローン後に手動で 1 回作成が必要**(Flyway は database 自体を作らない。MySQL の init SQL は空ボリュームのみ有効なため自動化していない)。空の database を用意すれば Flyway が V1/V2 を流してテーブル 6 つ + カテゴリー 10 件を用意する。手順とテスト方針 → [docs/test/README.md](../test/README.md)、仕組みの解説 → [testing-and-test-database.md](../notes/java/spring/testing-and-test-database.md)
- **リファクタ**(2026-07-25): `CategoryController` が `CategoryRepository` を直接呼んでいた箇所に `CategoryService` を追加し、`Controller → Service → Repository` の三段構えを全機能で統一。当初は「単純な参照のみなので Service を挟まない」判断だったが、[backend-structure-best-practices.md](./backend-structure-best-practices.md) の「Controller は薄く、ロジックは Service に寄せる」に揃える方針を優先した。`@Transactional(readOnly = true)` の置き場が生まれ、`CategoryControllerTest`(`@WebMvcTest` + Service モック)を追加できるようになった
- **フェーズ2**(2026-07-20): 投稿・タイムライン完成。
  - backend: 機能別パッケージ(`user` / `category` / `post` / `common`)で API 実装。`GET/POST /api/posts`、`GET/DELETE /api/posts/{id}`、`GET /api/categories`。タイムラインは fetch join + `limit+1` 方式のカーソルページネーション。認証までのつなぎとして `CurrentUserProvider` インターフェース(フェーズ3でセッション実装に差し替え)+ `DevCurrentUserProvider`(dev_user を自動作成)
  - frontend: `app/` 配下に pages(タイムライン `/`・詳細 `/posts/[id]`)、components(`PostCard` / `PostForm`)、composables(`usePosts` / `useCategories`)、types。無限スクロールは IntersectionObserver。**API 取得は全て `server: false` / クライアント側**(SSG でビルド時にバックエンドが居ないため)
  - テスト: `PostRepositoryTest`(カーソル境界 4 本・実 MySQL でロールバック実行)+ `PostControllerTest`(バリデーション 5 本・`@WebMvcTest`)全て成功
  - **注意: Spring Boot 4 はテストアノテーションのパッケージも移動している。** `@DataJpaTest` → `org.springframework.boot.data.jpa.test.autoconfigure`、`@WebMvcTest` → `org.springframework.boot.webmvc.test.autoconfigure`、`@AutoConfigureTestDatabase` → `org.springframework.boot.jdbc.test.autoconfigure`。`@MockBean` は廃止で `@MockitoBean`(`org.springframework.test.context.bean.override.mockito`)を使う
  - 動作確認済み: curl で API 一式(カーソル・絞り込み・400/404/403/204)、Nuxt 側はページ描画と devProxy 経由の API 疎通まで(ブラウザでの無限スクロール操作は未確認)
- **フェーズ1**(2026-07-19): Flyway 導入完了。V1(全6テーブル)+ V2(categories 10件)適用済みを MySQL 実機で確認。パッケージは `com.example.demo` → `com.example.app`(メインクラス `Application`)、`rootProject.name = 'app'`、`ddl-auto: validate` + `open-in-view: false` に変更。**注意: Spring Boot 4 は自動設定がモジュール分割されており、`flyway-core` 単体では Flyway が有効にならない。`spring-boot-starter-flyway` が必要**(+ MySQL 用に `flyway-mysql`)
- **フェーズ0**(2026-07-19): 設計確定。成果物 → `docs/superpowers/specs/2026-07-19-app-design-overview.md`、`CONTEXT.md`、backend/frontend の structure-best-practices。Nuxt は実際には 4 系だったためドキュメント側を Nuxt 4 表記に修正済み
