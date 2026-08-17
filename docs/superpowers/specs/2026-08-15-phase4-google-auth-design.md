# フェーズ4 設計: Google ログイン(OIDC + アカウントリンク)

日付: 2026-08-15
ステータス: 実装済み(2026-08-17 完了)

[実装フェーズ計画](../../development/implementation-progress.md) のフェーズ4 の設計。全体像は [設計概要](./2026-07-19-app-design-overview.md)、用語は [CONTEXT.md](../../../CONTEXT.md)、前提となる認証基盤は [フェーズ3 設計](./2026-08-05-phase3-auth-design.md) を正とする。

## 1. スコープ

### 作るもの

| 機能 | 内容 |
|---|---|
| Google で新規登録 | そのメールアドレスの users 行が無い場合、ログインと同時にユーザーを作る |
| Google でログイン | `google_sub` が紐づいている場合、セッションを張るだけ |
| アカウントリンク | メールアドレスが一致する既存ユーザーに `google_sub` を紐づける(暗黙に起きる) |
| パスワード未設定の可視化 | `GET /api/auth/me` に `hasPassword` を足し、`/settings/password` の案内を分岐させる |

この 3 つはどれも「Google ボタンを押した」という 1 本の経路の中の分岐であり、実装としては 1 つのフローになる。

### 作らないもの

- **設定画面からの明示的な連携 / 連携解除**。「ログイン中に別の認証手段を追加する」は別のフローで、解除時の安全確認(パスワード未設定のユーザーが解除するとログイン手段が消える)という別の設計判断が要る。落としても、リンクは暗黙に起きるので設計概要 §3 の目的(統合型アカウント)は達成される。Google 専用ユーザーがパスワードを持ちたくなればパスワードリセット経路で設定できる
- Google 以外の OIDC プロバイダ
- Google 側のトークンを保存して API を叩くこと(ログインの手段としてしか使わないので、アクセストークンは保持しない)
- `google_sub` に対応する Flyway マイグレーション(**V1 で作成済み**。このフェーズでスキーマ変更は無い)

## 2. 決定一覧

| # | 決定 | 理由の要約 |
|---|---|---|
| 1 | **OAuth2 のエンドポイントを `/api/**` 配下に移す** | 既定の `/oauth2/authorization/*` と `/login/oauth2/code/*` は devProxy(`/api` のみ転送)に乗らない。さらに **`/login` は Nuxt の実在ページ**で、フェーズ11 で SSG 出力を `static/` に置くとルーティングが衝突する。「REST API はすべて `/api/**` 配下」という CLAUDE.md の決定にも沿う |
| 2 | **ユーザーの特定は `google_sub` → `email` → 新規作成の順** | `sub` を先に引かないと、利用者が Google 側でメールアドレスを変えた瞬間に別人扱いになり `uk_users_google_sub` に当たって落ちる。設計概要 §3 の「メールをキーにしない」の実装 |
| 3 | **`sub` が一致したとき `users.email` は更新しない** | `users.email` は「メール確認を済ませた、パスワードリセットの送り先」という独立した意味を持つ。Google 側の変更を取り込むと未確認のアドレスが確認済みの座に座り、他人が使用中なら UNIQUE 制約で落ちる。Google は**ログインの手段**であって、メールアドレスの現在の持ち主の証明ではない |
| 4 | **メールが一致した既存アカウントが未確認なら、行を削除して作り直す** | 会員登録と同じ pre-hijacking 対策。リンクして確認済みにすると、攻撃者が先に登録したパスワードごとアカウントが有効化される → [ADR-0004](../../adr/0004-google-account-linking.md) / [ADR-0003](../../adr/0003-account-enumeration-and-unverified-signup.md) |
| 5 | **`email_verified` が false ならログインを拒否する** | 決定4 の逆向きの乗っ取り(未確認のメールを名乗る Google アカウントで、確認済みアカウントにリンクする)を塞ぐ。ただし**既に `sub` が紐づいている場合は見ない**(過去に一度所有権を確認済みのため)→ [ADR-0004](../../adr/0004-google-account-linking.md) |
| 6 | **username はサーバーが自動生成する** | 入力欄が無いため。「登録の途中で username 入力画面へ飛ばす」案は**半分だけ登録されたユーザー**という中間状態(離脱・リロード・別タブ)を扱うことになり、認証の本筋から外れる割に厄介 |
| 7 | **principal は `AppUserDetails` を継承し `OidcUser` を実装する `AppOidcUser`** | 素の `oauth2Login()` は `OidcUser` を principal にするため、既存 4 箇所の `@AuthenticationPrincipal AppUserDetails` が **null になり 500 で落ちる**(認可は通っているので 401 にすらならない)。両方を満たす型にすれば既存コードに手を入れずに済む |
| 8 | **`AppOidcUser#getName()` はメールアドレスを返す** | `SPRING_SESSION.PRINCIPAL_NAME` の値は `Authentication#getName()` で決まり、OIDC の既定は **`sub`**。放置すると `UserSessionManager.findByPrincipalName(email)` が Google 由来のセッションを見つけられず、**パスワードリセットしても Google ログインのセッションだけ生き残る** |
| 9 | **`redirect-uri` は `APP_BASE_URL` から絶対 URL で組み立てる** | 既定の `{baseUrl}` はリクエストの Host から作られるが、devProxy は `changeOrigin: true` なので Host が `backend:8080` になり `redirect_uri_mismatch` で弾かれる。本番でも ALB が TLS を終端するため `https` にならない。`APP_BASE_URL` から作れば **Google Console に登録する文字列と設定値が同一**になる |
| 10 | **`RequestCache` を無効化する(`NullRequestCache`)** | 既定の成功ハンドラは「401 になる直前のリクエスト」へ戻すが、このアプリで 401 になるのは `POST /api/posts` のような XHR。放置するとログイン成功後にブラウザが API へ飛ばされ、画面が JSON になる |
| 11 | **ログイン後の戻り先はブラウザ(`sessionStorage`)が覚える** | SSG + SPA では利用者が行きたかった**クライアント側ルート**をサーバーが一度も見ていない(保護ページも静的 HTML として 200 で返るため)。行き先を知っているのはクライアントだけなので、サーバーに預けて返してもらう往復は無駄 |
| 12 | **成功時の着地は専用ページ `/auth/callback`** | 「Google から戻った直後」という条件が来訪そのもので保証される。`plugins/auth.client.ts` に書くと全ページで走るため、鍵の有無だけで判定することになり誤爆する。`replace` で遷移すれば履歴に中継地点が残らない |
| 13 | **資格情報が未設定でもアプリは起動する(ダミー既定値)** | `client-id` が空だと `ClientRegistrationRepository` を作れず起動に失敗し、クローンしただけの人・`@SpringBootTest`・CI が全部止まる。Google ログインを使わない作業まで止めるのは割に合わない |
| 14 | **`GET /api/auth/me` に `hasPassword` を足す** | このフェーズはパスワードを持たないユーザーを量産する。その人たちが `/settings/password` を開くと、何を入れても失敗する変更フォームが出て理由が分からない |

### 固定した細部

- **スコープ(OAuth の scope)**: `openid` / `profile` / `email`。`CommonOAuth2Provider.GOOGLE` の既定値をそのまま使うため、`application.yml` に書くのは資格情報と `redirect-uri` だけ
- **username の生成規則**: メールのローカル部 → 小文字化 → 英数字と `_` 以外を `_` に置換 → 20 文字に切り詰め → 空になったら `user` → 重複していたら末尾に `_2`, `_3` … を付けて空きを探す。`masanori.adachi@gmail.com` → `masanori_adachi`
  - **受容しているもの**: username は公開情報(X の @xxx 相当)なので、メールアドレスのローカル部が公になる。ランダム生成にすれば漏れないが、意味のある初期値のほうが親切であり、フェーズ7 のプロフィール編集で変更できる前提で許容する
- **Google 由来ユーザーの初期値**: `display_name` は `name` クレーム(無ければ生成した username を流用)を 50 文字に切り詰め / `email_verified_at` は現在時刻 / `password_hash` は NULL / 確認メールは**送らない**(設計概要 §3「Google 登録は確認済み扱いでスキップ」)
- **エラーの伝え方**: 失敗時は `/login?error=<コード>` に遷移し、`/login` 側でコードから日本語メッセージを引く。**メッセージ本文を URL に載せない**(任意の文言を画面に差し込まれるため)
- **戻り先の検証**: `sessionStorage` から読んだ値は「`/` で始まり `//` で始まらない」ものだけ許可する。怠るとオープンリダイレクトの踏み台になる
- **ログアウト**: Google 側のセッションやトークンには触らない。このアプリのセッションを消すだけ(既存の `logout` がそのまま使える)
- **アクセストークン / リフレッシュトークン**: 保存しない。ログインの手段としてしか使わないため

## 3. データモデル

**スキーマ変更なし。** `users.google_sub VARCHAR(255) NULL UNIQUE` は V1 で作成済み。

| 列 | このフェーズでの使い方 |
|---|---|
| `users.google_sub` | Google OIDC の `sub`。NULL = Google 未連携。UNIQUE なので 1 つの Google アカウントは 1 ユーザーにしか紐づかない |
| `users.password_hash` | Google のみのユーザーは NULL のまま。パスワードログイン不可 |
| `users.email_verified_at` | Google 経由で作られたユーザーは作成時点で埋まる |

`AppOidcUser` は `SPRING_SESSION_ATTRIBUTES` にシリアライズされるため、ID トークンとクレームの分だけ行が大きくなる。**受容している**: ID トークンは不変の値なので、`AppUserDetails` が避けている「セッション内の値が古くなる」問題は起きない。増えるのは容量だけ。

## 4. エンドポイント

| メソッド | パス | 認証 | 説明 |
|---|---|---|---|
| GET | `/api/oauth2/authorization/google` | 公開 | Google へ送り出す入口。**ブラウザのページ遷移で叩く**(fetch 不可) |
| GET | `/api/login/oauth2/code/google` | 公開 | Google からの戻り先。フロントは直接叩かない・叩けない(`state` が無いと通らない) |

どちらも `SecurityConfig` の認可ルールに `permitAll` として列挙する。ただしこれは**公開される URL の一覧として読めるようにするため**で、動作上は必須ではない。ログイン/ログアウトと同じく、認可を行う `AuthorizationFilter` より手前の `OAuth2AuthorizationRequestRedirectFilter` / `OAuth2LoginAuthenticationFilter` が処理して後続へ進まないので、`permitAll` を外しても入口・戻り先とも同じレスポンスを返す(実機で確認済み)。

既存エンドポイントの変更は `GET /api/auth/me` のレスポンスに `hasPassword` が増えるのみ。

## 5. フロー

### 遷移の連鎖

```
① ブラウザ → GET {base-url}/api/oauth2/authorization/google
   ( /login の <a href>。devProxy 経由で backend へ )

② backend → 302 → https://accounts.google.com/o/oauth2/v2/auth?...
   OAuth2AuthorizationRequestRedirectFilter が state / nonce / PKCE を作り、
   セッション(= MySQL の SPRING_SESSION_ATTRIBUTES)に退避してから送り出す
   redirect_uri={APP_BASE_URL}/api/login/oauth2/code/google をパラメータに載せる

③ 利用者が Google 上でログイン・同意

④ Google → 302 → {base-url}/api/login/oauth2/code/google?code=...&state=...
   ★ここは Google 側の遷移。行き先は ② で渡した redirect_uri で、
     Google Cloud Console に登録済みのものと完全一致でなければ Google が拒否する

⑤ backend: state を照合 → code をトークンに交換(ブラウザを通らないサーバー間通信)
   → AppOidcUserService → GoogleAccountService が users を引く/作る/リンクする
   → セッションを張る → 302 → {base-url}/auth/callback (失敗時は /login?error=...)

⑥ /auth/callback: /api/auth/me でストアを埋める
   → sessionStorage の戻り先を読み(無ければ "/")、検証して replace で遷移
```

**セッションが MySQL にあることがこのフローの前提**になっている。② で退避した `state` を ⑤ で読むので、ECS でタスクが複数あって別コンテナに当たっても成立する必要がある。メモリセッションなら `state` の照合で毎回失敗する(→ [ADR-0002](../../adr/0002-session-cookie-over-jwt.md))。

### ⑤ の分岐(`GoogleAccountService.resolve`)

```
emailVerified が false かつ google_sub が未紐づけ
  → OAuth2AuthenticationException(/login?error=email_unverified)

findByGoogleSub(sub) がヒット
  → そのユーザーを返す(users.email は更新しない)

findByEmail(email) がヒット
  ├ 確認済み  → google_sub を書き込んで返す(アカウントリンク。id は変わらない)
  └ 未確認    → その行を削除して flush し、新規作成して返す
                 (password_hash は NULL に、email_verified_at は現在時刻に)

どちらも無し
  → 新規作成して返す
```

## 6. バックエンドの構成

| クラス | 役割 |
|---|---|
| `auth/AppOidcUserService` | `OAuth2UserService<OidcUserRequest, OidcUser>` の実装。`OidcUserService` に委譲してクレームを取り出し、`GoogleAccountService` を呼び、`AppOidcUser` を組み立てて返す。**Spring Security の型はここまで** |
| `auth/GoogleAccountService` | `resolve(sub, email, emailVerified, name)` → `User`。§5 の分岐が全部ここ |
| `auth/UsernameGenerator` | メールのローカル部から username を作り、衝突していたら連番を振る |
| `auth/AppOidcUser` | principal。`AppUserDetails` を継承し `OidcUser` を実装。`getName()` はメールアドレス |
| `auth/AuthResponseWriter` | **追記**: `onOAuth2LoginSuccess`(→ `/auth/callback`)と `onOAuth2LoginFailure`(→ `/login?error=...`)を足す |
| `config/SecurityConfig` | **追記**: `oauth2Login()`、`baseUri` 2 つ、`permitAll` 2 行、`NullRequestCache` |

拡張点(`AppOidcUserService`)と業務ロジック(`GoogleAccountService`)を分けるのは、[backend-structure-best-practices.md](../../development/backend-structure-best-practices.md) の「Controller は薄く、ロジックは Service に寄せる」と同じ考え方。`AppOidcUserService` はフレームワークとの接続部という意味で Controller と同じ位置にある。あわせて、`OidcUserRequest` を手で組み立てずにテストできるようになる。

### 設定

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          google:
            client-id: ${GOOGLE_CLIENT_ID:dummy-client-id}
            client-secret: ${GOOGLE_CLIENT_SECRET:dummy-client-secret}
            redirect-uri: ${APP_BASE_URL:http://localhost:3000}/api/login/oauth2/code/google
```

依存に `spring-boot-starter-oauth2-client` を追加する。

### 既存コードへの影響

- `MeResponse.CurrentUser` に `hasPassword` を追加(`AuthService.getCurrentUser` は `User` から埋める)
- `SecurityConfig` と `AuthResponseWriter` に追記
- **`PostController` / `AuthController` / `PostService` は変更なし**(決定7 のおかげで `@AuthenticationPrincipal AppUserDetails` がそのまま効く)

## 7. フロントエンドの構成

| ファイル | 内容 |
|---|---|
| `pages/login.vue` | **追記**: Google ボタン、`?error=` のコード → 日本語メッセージ対応表 |
| `pages/signup.vue` | **追記**: Google ボタン |
| `pages/auth/callback.vue` | **新規**: `/api/auth/me` でストアを埋め、`sessionStorage` の戻り先へ `replace` 遷移 |
| `composables/useAuth.ts` | **追記**: Google へ送り出す前に戻り先を `sessionStorage` に入れるヘルパ |
| `pages/settings/password.vue` | **追記**: `hasPassword` が false ならフォームを出さず、パスワードリセットへの案内に差し替える |
| `types/auth.ts` | **追記**: `CurrentUser.hasPassword` |

**Google ボタンは素の `<a href="/api/oauth2/authorization/google">`**。`<NuxtLink>` だとクライアント側ルーティングでサーバーにリクエストが飛ばず、`$fetch` だと Google の同意画面を fetch することになって CORS で失敗する。ここはブラウザのページ遷移でなければならない。

ログイン状態の反映に追加コードは要らない。Google からの着地はフルページロードなので `plugins/auth.client.ts` が走り、`/api/auth/me` でストアが埋まる。

## 8. テスト

| クラス | 本数 | 検証 |
|---|---|---|
| `GoogleAccountServiceTest` | 6 | ① `sub` ヒットで users が増えない ② メール一致・確認済みで **id が変わらず** `google_sub` が入る ③ メール一致・未確認で **id が変わり** `password_hash` が NULL になる ④ 新規作成の初期値 ⑤ `emailVerified` false で例外・users は無変化 ⑥ `sub` 一致でメールが違っても `users.email` は変わらない |
| `UsernameGeneratorTest` | 3 | 文字種の変換と切り詰め / 衝突時の連番 / ローカル部が記号だけなら `user` |
| `AppOidcUserTest` | 1 | **`getName()` がメールアドレスを返す** |
| `AuthFlowTest`(追加) | 1 | `GET /api/oauth2/authorization/google` が `accounts.google.com` への 302 を返す |

合計 11 本。既存 29 本と合わせて **40 本**。

> 実装時に `AppOidcUserServiceTest` 4 本(`email_verified` の 3 値変換、アプリ例外 → `OAuth2AuthenticationException` の翻訳、principal の組み立て)と `PostRepositoryTest` 2 本を追加したため、実際は **46 本**。最新の一覧は [docs/test/README.md](../../test/README.md) を正とする。

`AppOidcUserTest` の 1 本は他より重要度が高い。決定8 を外しても画面上は何も壊れず、「パスワードリセットしたのに Google のセッションだけ生き残る」という形でしか露見しないため。

`AuthFlowTest` の 1 本は決定1(`baseUri` の変更)を守る。既定に戻すと Google の画面まで到達しないが、原因は画面からは分からない。

**書かないもの**: Google とのトークン交換(外部依存。モックしても Spring Security の内部を写経するだけ)、フロントエンド(テスト基盤が無い)。

## 9. 実装順序

| # | 内容 | Google の資格情報 |
|---|---|---|
| 0 | この設計書 | 不要 |
| 1 | 依存追加・`application.yml`・`SecurityConfig`。起動と 302 を確認 | 不要 |
| 2 | `UsernameGenerator` + `GoogleAccountService` + テスト 9 本 | 不要 |
| 3 | `AppOidcUser` / `AppOidcUserService` / ハンドラ + テスト 2 本 | 不要 |
| 4 | `hasPassword` + `/settings/password` の分岐 | 不要 |
| 5 | フロント(ボタン・`sessionStorage`・`/auth/callback`・エラー文言) | 不要 |
| 6 | **実際の Google アカウントで通し確認** | **必要** |
| 7 | ドキュメント一式と進捗表の更新 | 不要 |

決定13(ダミー既定値)により、ステップ 6 以外は資格情報が無くても進められる。

### 利用者側で必要な準備(ステップ6 の前提)

Google Cloud Console での作業は開発者本人にしかできない。

- OAuth 2.0 クライアント ID(種別: ウェブアプリケーション)を作成
- 承認済みのリダイレクト URI に `http://localhost:3000/api/login/oauth2/code/google` を登録(完全一致)
- 同意画面が「テスト」状態なら、ログインに使う Google アカウントをテストユーザーに追加
- `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` を `.env` に記入

手順 → [docs/setup/google-oauth.md](../../setup/google-oauth.md)

## 10. 関連ドキュメント

- [ADR-0004](../../adr/0004-google-account-linking.md) — 同一メールのアカウントに自動でリンクする判断
- [ADR-0003](../../adr/0003-account-enumeration-and-unverified-signup.md) — 未確認アカウントを作り直す理由(決定4 の土台)
- [ADR-0002](../../adr/0002-session-cookie-over-jwt.md) — セッションを MySQL に置く判断(§5 の前提)
- [フェーズ3 設計](./2026-08-05-phase3-auth-design.md) — 認証基盤
- [設計概要](./2026-07-19-app-design-overview.md) §3 — 認証の全体方針
- [CONTEXT.md](../../../CONTEXT.md) — 「Google ログイン」「アカウントリンク」
