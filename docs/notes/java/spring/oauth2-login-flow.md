# Google ログイン(OIDC)の遷移はどう進むのか

`oauth2Login()` を足すと何が起きるのか、なぜリダイレクト先が固定なのか、なぜセッションを DB に置いていないと成立しないのか。フェーズ4 の実装中に整理した内容。

実装 → [フェーズ4 設計](../../../superpowers/specs/2026-08-15-phase4-google-auth-design.md) / フィルタの列そのもの → [security-filter-chain.md](./security-filter-chain.md)

## 遷移は 6 段ある

パスワードログインは「POST を 1 回投げて JSON が返る」で終わるが、Google ログインは**ブラウザを 3 者の間で往復させる**。

```
① ブラウザ → GET localhost:3000/api/oauth2/authorization/google
             ( /login の <a href>。devProxy 経由で backend へ )

② backend → 302 → accounts.google.com/o/oauth2/v2/auth?...
   OAuth2AuthorizationRequestRedirectFilter が state / nonce / PKCE を作り、
   セッションに退避してから送り出す
   redirect_uri=localhost:3000/api/login/oauth2/code/google をパラメータに載せる

③ ブラウザが Google 上でログイン・同意

④ Google → 302 → localhost:3000/api/login/oauth2/code/google?code=...&state=...
   ★ここが Google 側の遷移

⑤ backend が受け取る。state を照合し、code をトークンに交換し
   ( ここはブラウザを通らないサーバー間通信 )、
   OidcUserService が「誰としてログインするか」を決めてセッションを張る
   → 302 → 好きな URL

⑥ ブラウザが ⑤ の指示した URL に着地
```

## リダイレクト先が固定なのは ④ だけ

「Google ログインは戻り先が固定の URL にしかできない」と誤解しやすいが、固定なのは ④ の 1 段だけ。

④ の行き先は ② で渡した `redirect_uri` で、これは Google Cloud Console に事前登録したものと完全一致でなければ Google が拒否する。任意の URL に飛ばせてしまうと、認可コードを攻撃者のサイトへ渡す踏み台になるため、OAuth の仕様が要求している制約。

一方 **⑤ の遷移先は完全に自由**で、`/posts/3` でも何でも指定できる。

では戻り先の指定がなぜ難しいかというと、⑤ の時点で**サーバーが「利用者がどこへ行きたかったか」を知らない**から。その情報は ① にしか無く、②→④ の往復で失われる。往復を生き延びるのは `state` だけだが、これは Spring Security が偽造検知のために生成・照合する不透明な値なので、戻り先を相乗りさせるものではない。

だから選択肢は「誰が往復の間その情報を覚えているか」になる。

- サーバーが覚える … ① でセッションに書き、⑤ で読む。⑤ が直接目的地へ飛ばす
- ブラウザが覚える … `sessionStorage` に入れる。⑤ は固定の受け皿ページへ飛ばし、⑥ でフロントが自分で移動する

このアプリは後者を採った。SSG + SPA では保護ページも静的 HTML として 200 で返り、ログイン判定はクライアント側の middleware がやっている。つまり**行きたかった場所はクライアント側ルート**で、サーバーは一度も見ていない。知っているのはブラウザだけなので、ブラウザに覚えさせるのが素直だという理由。

なお `sessionStorage` は **オリジン × タブ**単位なので、②〜④ でタブが `accounts.google.com` に移っても消えない。消えるのはタブを閉じたとき。

### 実務ではどちらが多いか

アーキテクチャで割れる。フロントが直接 IdP と話す SPA(トークンがブラウザに来る形)はブラウザ側ストレージが定石で、Auth0 の SPA SDK は `loginWithRedirect({ appState: { returnTo } })` の `appState` を sessionStorage に置く。一方バックエンドがセッションを持つ BFF 型はサーバー側が定石で、Spring Security の `RequestCache`(「401 になる直前の行き先を覚えてログイン後に戻す」)がまさにそれ。

このアプリは BFF 型だが、上に書いた理由で `RequestCache` が構造的に空振りする(サーバーが 401 を返すのは XHR だけで、それは利用者が「行きたかったページ」ではない)。むしろ有効なままだとログイン後に `POST /api/posts` へ飛ばされて画面が JSON になるので、`NullRequestCache` で止めてある。

## セッションが DB にあることが前提になっている

② で退避した `state` / `nonce` / PKCE の `code_verifier` を ⑤ で読み直す。この間にブラウザは Google を往復しているので、**② と ⑤ が別のコンテナに当たっても成立しなければならない**。

このアプリは Spring Session JDBC を入れているので、`request.getSession().setAttribute(...)` は Tomcat のメモリではなく MySQL の `SPRING_SESSION_ATTRIBUTES` に書かれる(`SessionRepositoryFilter` がリクエストを包んで `getSession()` を差し替えている)。どのコンテナに当たっても `SESSION` Cookie から同じ行を読めるので、ECS でタスクを複数並べても壊れない。

逆に言えば、セッションがメモリだったら ECS 上で Google ログインは `state` の照合に失敗し続ける。→ [ADR-0002](../../../adr/0002-session-cookie-over-jwt.md)

## `{baseUrl}` は devProxy 越しだと壊れる

`redirect-uri` の既定値は `{baseUrl}/login/oauth2/code/{registrationId}` で、`{baseUrl}` はリクエストの Host ヘッダから組み立てられる。

ところが Nuxt の devProxy は `changeOrigin: true` を指定しているので、backend が受け取る Host は **`backend:8080`** になる。既定のままだと Google に `http://backend:8080/api/login/oauth2/code/google` を渡してしまい、登録済みの URI と一致せず `redirect_uri_mismatch` で弾かれる。エラーは Google の画面に出るので、原因がアプリ側の設定だと気づきにくい。

本番でも同じ罠がある。ALB が TLS を終端するので、Host から組み立てると `https` ではなく `http` になる。

そのため `APP_BASE_URL` から絶対 URL で組み立てている。副産物として「Google Console に登録する文字列」と「アプリの設定値」が同一になるので、照合が目視でできる。

## principal の型が既存コードを壊しかける

素の `oauth2Login()` は SecurityContext に `OidcUser` を入れる。このアプリの Controller は `@AuthenticationPrincipal AppUserDetails` で受け取っているので、型が合わないと**黙って `null` が渡る**。認可自体は通っているため 401 にもならず、Controller の中で NullPointerException になって 500 で落ちる。

対処は、両方を満たす型を principal にすること。

```java
public class AppOidcUser extends AppUserDetails implements OidcUser { ... }
```

さらに `getName()` の上書きが要る。`SPRING_SESSION.PRINCIPAL_NAME` に入る値は `Authentication#getName()` で決まり、**OIDC の既定はこれが `sub`**。放置すると `UserSessionManager.findByPrincipalName(email)` が Google 由来のセッションを見つけられず、「パスワードをリセットしたのに Google ログインのセッションだけ生き残る」という壊れ方をする。画面上は何も起きないので、テストが無いと気づけない。

## SSG のビルドが `<a href>` を追いかける

Google ボタンは `<a href="/api/oauth2/authorization/google">` でなければならない(`NuxtLink` だとサーバーにリクエストが飛ばず、`$fetch` だと Google の同意画面を fetch することになって CORS で失敗する)。

すると `nuxt generate` のクローラが生成済み HTML の `<a href>` を辿り、**`/api/oauth2/authorization/google` を Nuxt のページとして静的化しようとして 404 で落ちる**。ビルド時にバックエンドは居ないので当然。

`/api` 配下は常に Spring Boot が受けるもので Nuxt のルートではないため、まとめて対象外にする。

```ts
nitro: { prerender: { ignore: ['/api'] } }
```
