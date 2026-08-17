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
| 4 | 認証(Google) | `oauth2Login()` による Google ログイン、同一メールのアカウントリンク(`google_sub` 紐づけ)。**設計 → [2026-08-15-phase4-google-auth-design.md](../superpowers/specs/2026-08-15-phase4-google-auth-design.md)** | 完了 |
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

- **フェーズ4 完了**(2026-08-17): [設計](../superpowers/specs/2026-08-15-phase4-google-auth-design.md) §9 の 7 ステップすべて完了。テスト 46 本すべて成功。**実際の Google アカウントで 4 経路すべて確認済み**(下記)。
  - **実機での確認結果(ステップ6)**:
    - **新規作成**: Google 初回ログインで users に 1 行できる。`masanori.basketball@gmail.com` → username `masanori_basketball` が自動生成され、`display_name` は Google の名前、`password_hash` は NULL、`email_verified_at` は作成時刻(確認メールは飛ばない)
    - **`SPRING_SESSION.PRINCIPAL_NAME` がメールアドレスになっている**ことを実データで確認。決定8(`AppOidcUser#getName()` の上書き)が効いている証拠。OIDC の既定のままなら `sub` が入る
    - **アカウントリンク**: `UPDATE users SET google_sub = NULL WHERE id = 28` で「確認済み・パスワード未設定・Google 未連携」の状態を作り、同じ Google アカウントで再ログイン → **users の件数と最大 id が変わらず、`google_sub` が元の値に復活**。`created_at` は据え置きで `updated_at` だけ動いたので、新規作成ではなく **UPDATE が走った = 既存行に紐づいた**と確定できた(この日時の差が一番わかりやすい判定材料)
    - **2 回目以降のログイン**: `sub` ヒットの経路。users に行が増えない
    - **`hasPassword: false` の画面分岐**: `/settings/password` で変更フォームではなくパスワード再設定への案内が出る
    - **戻り先の復元**: 未ログインで `/settings/password` → `/login?redirect=/settings/password` → Google ボタン → **`/settings/password` に着地**。`sessionStorage` + `/auth/callback` の経路(決定11・12)が効いている
  - **ステップ0**: 設計書と [ADR-0004](../adr/0004-google-account-linking.md)(自動アカウントリンク)、`CONTEXT.md` に「Google ログイン」「アカウントリンク」を追加
  - **ステップ1**: `spring-boot-starter-oauth2-client` 追加、`application.yml` に Google の登録、`SecurityConfig` に `oauth2Login()`(`baseUri` を 2 つとも `/api` 配下へ)+ `permitAll` 2 行 + `NullRequestCache`。`.env.example` に `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET`。**スキーマ変更なし**(`users.google_sub` は V1 で作成済み)
  - **ステップ2〜3**: `UsernameGenerator` / `GoogleAccountService` / `GoogleLoginNotAllowedException` / `AppOidcUser` / `AppOidcUserService`、`AuthResponseWriter` に `onOAuth2LoginSuccess` `onOAuth2LoginFailure` を追加
  - **ステップ4**: `MeResponse.CurrentUser` に `hasPassword`。`/settings/password` は false ならフォームを出さずパスワードリセットへ案内
  - **ステップ5**: `components/auth/GoogleButton.vue`(素の `<a href>`)、`utils/postLoginRedirect.ts`、`pages/auth/callback.vue`、`/login` の `?error=` 対応表、`/signup` にもボタン
  - **動作確認済み(curl / ビルド)**:
    - `GET /api/oauth2/authorization/google` が Google へ 302。`redirect_uri` は Host ではなく `APP_BASE_URL` 由来の `http://localhost:3000/api/login/oauth2/code/google`、`scope=openid profile email`、PKCE 付き。**devProxy 経由(3000 番)でも同じ**
    - 資格情報がダミーのままでもアプリが起動し、既存の公開 GET・登録・メール確認・ログインがすべて従来どおり動く。`/api/auth/me` に `hasPassword` が乗る
    - 8 ページすべて 200(`/auth/callback` を含む)、**SSG ビルドは 18 ルートのプリレンダ成功**
  - **SSG ビルドで見つかった問題と対処**: Nitro のクローラが生成 HTML の `<a href>` を辿るため、Google ボタンの `/api/oauth2/authorization/google` を Nuxt のページとして静的化しようとして**ビルドが 404 で落ちた**。`nuxt.config.ts` に `nitro.prerender.ignore: ['/api']` を追加して解決(フェーズ11 でも効いてくる設定)
  - **既存テストへの波及**: `SecurityConfig` が `AppOidcUserService` を要求するようになったため、`@Import(SecurityConfig.class)` を使う `@WebMvcTest` 3 クラス(`PostControllerTest` / `CategoryControllerTest` / `AuthControllerTest`)に `@MockitoBean AppOidcUserService` の追加が必要だった。フェーズ3 の `AuthResponseWriter` と同じ事情
  - **残っている開発データの訂正**: フェーズ3 のメモにある `masa@example.com` / `resetpass123` は**もう通らない**(401)。後のセッションでパスワードが変わったとみられる。新規登録 → メール確認 → ログインの経路は curl で通ることを確認済みなので、動作確認には新しいユーザーを作るのが早い。`dev_user` は「メール確認済み・パスワード未設定」のまま残っており、アカウントリンクの検証データとして使える
  - **`permitAll` は動作上は不要だった**(設計時の想定と違った点): `SecurityConfig` に足した `/api/oauth2/authorization/*` と `/api/login/oauth2/code/*` の `permitAll` は、外しても入口・戻り先とも同じレスポンスを返す(実機で確認)。`OAuth2AuthorizationRequestRedirectFilter` / `OAuth2LoginAuthenticationFilter` がどちらも `AuthorizationFilter` より手前でレスポンスを書いて後続に進まないため、`formLogin` / `logout` とまったく同じ理屈。**公開される URL の一覧として読めるように残してある**
  - **Google Cloud Console 側の設定**(新しい PC では再度必要): OAuth クライアント ID(ウェブアプリケーション)、リダイレクト URI に `http://localhost:3000/api/login/oauth2/code/google`(完全一致・**8080 ではなく 3000**)、同意画面が「テスト」ならログインに使うアカウントをテストユーザーに追加、`.env` に 2 つの値 → 手順は [docs/setup/google-oauth.md](../setup/google-oauth.md)
  - **残っている開発データ**: id 28 `masanori_basketball` / `masanori.basketball@gmail.com` が **Google 連携済み・パスワード未設定**の状態で残っている。`hasPassword: false` の画面や「パスワードログインできないアカウント」の検証にそのまま使える。`dev_user` も同じくパスワード未設定(Google 未連携)
  - **フェーズ5 への申し送り**: いいねは**公開エンドポイントで principal を受ける**ことになるが、`AppOidcUser` が `AppUserDetails` を継承しているので **`@AuthenticationPrincipal AppUserDetails` の 1 種類で両方のログイン手段を受けられる**(ログイン方法による分岐は不要)。未ログイン時に `null` が入る点だけフェーズ3 と同じ扱いにすればよい
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
