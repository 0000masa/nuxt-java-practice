# REST API ドキュメント

バックエンド(Spring Boot)が提供する REST API のドキュメント。**エンドポイントごとに1ファイル**で管理する。API を変更したら該当ファイルを必ず更新すること。

## エンドポイント一覧

「認証」列が **必要** のものは、ログインしていないと 401 を返す。

| メソッド | パス | 認証 | 内容 | ドキュメント |
|---------|------|------|------|------------|
| GET | `/api/posts` | 不要 | タイムライン取得(カーソルページネーション・カテゴリー絞り込み) | [get-posts.md](./get-posts.md) |
| GET | `/api/posts/{id}` | 不要 | 投稿の単体取得 | [get-post-by-id.md](./get-post-by-id.md) |
| POST | `/api/posts` | **必要** | 投稿の作成 | [create-post.md](./create-post.md) |
| DELETE | `/api/posts/{id}` | **必要** | 投稿の削除(自分の投稿のみ) | [delete-post.md](./delete-post.md) |
| GET | `/api/categories` | 不要 | カテゴリー一覧取得 | [get-categories.md](./get-categories.md) |
| POST | `/api/auth/signup` | 不要 | 会員登録(確認メールを送る) | [signup.md](./signup.md) |
| POST | `/api/auth/verify-email` | 不要 | メール確認の完了 | [verify-email.md](./verify-email.md) |
| POST | `/api/auth/verification/resend` | 不要 | 確認メールの再送 | [resend-verification.md](./resend-verification.md) |
| POST | `/api/auth/login` | 不要 | ログイン(**ボディは form-urlencoded**) | [login.md](./login.md) |
| POST | `/api/auth/logout` | 不要 | ログアウト | [logout.md](./logout.md) |
| GET | `/api/auth/me` | 不要 | 現在ユーザーの取得(未ログインなら `user: null`) | [get-me.md](./get-me.md) |
| POST | `/api/auth/password-reset/request` | 不要 | パスワードリセットの申請 | [request-password-reset.md](./request-password-reset.md) |
| POST | `/api/auth/password-reset/confirm` | 不要 | パスワードリセットの実行 | [confirm-password-reset.md](./confirm-password-reset.md) |
| PUT | `/api/auth/password` | **必要** | ログイン中のパスワード変更 | [change-password.md](./change-password.md) |
| GET | `/api/oauth2/authorization/google` | 不要 | Google ログインの入口(**ブラウザのページ遷移で叩く**) | [google-login.md](./google-login.md) |
| GET | `/api/login/oauth2/code/google` | 不要 | Google からの戻り先(フロントは直接叩かない) | [google-login.md](./google-login.md) |

ベース URL について:フロントエンドは相対パス `/api` を呼び、開発時は Nuxt の devProxy が backend コンテナへ転送する。詳細は `docs/development/` を参照。

## 認証とセッション

方式の選定理由 → [ADR-0002](../adr/0002-session-cookie-over-jwt.md)、実装の全体像 → [フェーズ3 設計](../superpowers/specs/2026-08-05-phase3-auth-design.md)

- **セッション Cookie 方式。** ログインすると `SESSION` Cookie(中身はセッション ID のみ)が発行され、セッションの実体は MySQL の `SPRING_SESSION` / `SPRING_SESSION_ATTRIBUTES` に保存される。**JWT は使わない**
- **ログインの識別子はメールアドレス。** `users.username` は表示・検索用で、ログインには使わない
- **ログイン手段は 2 つ。** メールアドレス + パスワードと、[Google ログイン](./google-login.md)。同じメールアドレスなら同一ユーザーに統合される(アカウントリンク → [ADR-0004](../adr/0004-google-account-linking.md))
- **メール確認が済むまでログインできない。** 未確認のアカウントでログインを試みると 401 になり、他の失敗とは区別されたメッセージが返る
- セッションのタイムアウトは 1 日

### CSRF トークン(書き込み系すべてに必要)

**GET / HEAD 以外のリクエストには CSRF トークンが必要**で、無い / 合わない場合は **403** になる。

1. 何らかの GET(実際にはアプリ起動時の `GET /api/auth/me`)のレスポンスで `XSRF-TOKEN` Cookie が発行される
2. 書き込み系のリクエストで、その値を `X-XSRF-TOKEN` ヘッダに載せて送る
3. **ログイン成功時とログアウト成功時にトークンは作り直される**ため、リクエストごとに Cookie を読み直す

フロントエンドではこの処理を `frontend/app/plugins/api.ts` の `$api` が受け持っており、各画面は意識しなくてよい。curl で試すときは自分で載せる必要がある。

## エラーレスポンス

エラーはすべて共通形 `ErrorResponse` で返る。各 Controller に try-catch は書かない。

```json
{
  "message": "人間向けのエラーメッセージ",
  "fieldErrors": null
}
```

`fieldErrors` は項目単位のエラーのときだけ「フィールド名 → メッセージ」のマップが入り、それ以外は `null`。

| ステータス | 変換元の例外 | 発生条件 |
|-----------|------------|---------|
| 400 Bad Request | `MethodArgumentNotValidException` | リクエストボディのバリデーション違反(`fieldErrors` あり) |
| 400 Bad Request | `ConstraintViolationException` | クエリパラメータのバリデーション違反(`@Min` / `@Max` など) |
| 400 Bad Request | `FieldValidationException` | 項目単位の業務ルール違反(登録済みのメールアドレスなど。`fieldErrors` あり) |
| 400 Bad Request | `InvalidRequestException` | 業務ルール違反(トークンの期限切れなど) |
| 401 Unauthorized | — (`AuthenticationEntryPoint`) | 認証必須のエンドポイントに未ログインでアクセス |
| 401 Unauthorized | — (ログイン失敗ハンドラ) | ログインの失敗(資格情報が不正 / メール未確認) |
| 403 Forbidden | — (`AccessDeniedHandler`) | CSRF トークンが無い / 合わない |
| 403 Forbidden | `ForbiddenOperationException` | 認可されていない操作(例:他人の投稿の削除) |
| 404 Not Found | `ResourceNotFoundException` | 対象リソースが存在しない |

400 / 403 / 404 は `GlobalExceptionHandler`(`@RestControllerAdvice`)が変換する。401 と CSRF の 403 は Controller に到達する前のフィルタ段階で起きるため、`AuthResponseWriter` が変換する。

## ファイル関係ツリー

```
backend/src/main/java/com/example/app/
├── auth/                          … 認証ドメイン
│   ├── AuthController.java        … /api/auth/** の HTTP 入口(login / logout を除く)
│   ├── AuthService.java           … 会員登録・メール確認・リセット・パスワード変更の業務ロジック
│   ├── AuthTokenService.java      … 使い捨てトークンの発行と検証(SHA-256 ハッシュで保存)
│   ├── AuthToken.java             … エンティティ(auth_tokens)
│   ├── AuthTokenPurpose.java      … トークンの用途(EMAIL_VERIFICATION / PASSWORD_RESET)
│   ├── AuthTokenRepository.java   … DB アクセス(JPA)
│   ├── AppUserDetails.java        … ログイン中のユーザー(principal)。@AuthenticationPrincipal で受け取る型
│   ├── AppUserDetailsService.java … メールアドレスで users を引き、Spring Security へ渡す
│   ├── AuthResponseWriter.java    … Spring Security のリダイレクトを JSON(401/403)に差し替える
│   ├── UserSessionManager.java    … 特定ユーザーのセッションを削除する(パスワード変更時の強制ログアウト)
│   ├── AuthMailRequestedEvent.java… メール送信をコミット後に回すためのイベント
│   ├── AuthMailSender.java        … メール本文の組み立てと送信(@TransactionalEventListener)
│   └── dto/                       … SignupRequest / MeResponse など
├── post/                          … 投稿ドメイン
│   ├── PostController.java        … GET/POST /api/posts、GET/DELETE /api/posts/{id} の HTTP 入口
│   ├── PostService.java           … 業務ロジック(ページネーション判定、カテゴリー存在チェック、所有者チェック)
│   ├── PostRepository.java        … DB アクセス(JPA。fetch join で N+1 を回避)
│   ├── Post.java                  … エンティティ(posts テーブル)
│   └── dto/
│       ├── CreatePostRequest.java … 投稿作成のリクエストボディ(バリデーション定義)
│       ├── PostResponse.java      … 投稿1件のレスポンス(ユーザー・カテゴリーの要約を内包)
│       └── TimelineResponse.java  … タイムラインのレスポンス(posts + nextCursor)
├── category/                      … カテゴリードメイン
│   ├── CategoryController.java    … GET /api/categories の HTTP 入口
│   ├── CategoryService.java       … 参照のみだが Controller → Service → Repository を統一するため配置
│   ├── CategoryRepository.java    … DB アクセス(JPA)
│   ├── Category.java              … エンティティ(categories テーブル。Flyway で投入するマスタデータ)
│   └── dto/
│       └── CategoryResponse.java  … カテゴリー1件のレスポンス
├── user/                          … ユーザードメイン(ユーザー用の API はまだない)
│   ├── User.java                  … エンティティ(users テーブル)
│   └── UserRepository.java        … DB アクセス(JPA)
├── config/                        … 横断的な設定
│   ├── SecurityConfig.java        … 認可ルール・formLogin・logout・CSRF・PasswordEncoder
│   └── AppProperties.java         … app.base-url / app.mail.from
└── common/                        … 横断的関心事
    ├── dto/
    │   └── ErrorResponse.java     … エラーレスポンスの共通形
    └── exception/
        ├── GlobalExceptionHandler.java      … 例外 → HTTP レスポンスへの一元変換
        ├── ResourceNotFoundException.java   … リソースが存在しない → 404
        ├── ForbiddenOperationException.java … 認可されていない操作 → 403
        ├── InvalidRequestException.java     … 業務ルール違反 → 400
        └── FieldValidationException.java    … 項目単位の業務ルール違反 → 400 + fieldErrors
```

依存の流れは `Controller → Service → Repository` が基本。`PostService` は投稿作成時のカテゴリー存在チェックのために `CategoryRepository` に、投稿者の取得のために `UserRepository` に依存する。

**ログインとログアウトに対応する Controller メソッドは存在しない。** Spring Security の `formLogin` / `logout` がフィルタとして直接処理するため、設定は `config/SecurityConfig.java` にある。

## 日時のフォーマット

レスポンス中の日時(`createdAt` など)は `LocalDateTime` を ISO-8601 形式(タイムゾーンなし)でシリアライズしたもの。例: `"2026-07-20T10:15:30"`
