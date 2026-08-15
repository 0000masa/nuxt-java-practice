# GET /api/auth/me — 現在ユーザーの取得

## 概要

いまログインしているユーザーを返す。**認証不要で、未ログインでも 200 を返す**(`user` が `null` になる)。

フロントエンドはアプリ起動時にこれを 1 回呼び、ログイン状態を復元する。

## リクエスト

パラメータなし。

## レスポンス

**200 OK**

ログイン中:

```json
{
  "user": {
    "id": 20,
    "username": "taro",
    "displayName": "太郎",
    "email": "taro@example.com",
    "hasPassword": true
  }
}
```

`hasPassword` は**パスワードが設定されているか**。[Google ログイン](./google-login.md)だけで作られたユーザーは `false` になる。画面(`/settings/password`)はこれを見て、パスワード変更フォームの代わりに「[パスワードリセット](./request-password-reset.md)から設定してください」の案内を出す。フォームを出しても、照合する現在のパスワードが存在しないので必ず失敗するため。自分自身の情報なので、他人に何かが漏れる値ではない。

未ログイン:

```json
{ "user": null }
```

**レスポンスと一緒に `XSRF-TOKEN` Cookie が発行される。** これが重要で、CSRF トークンの初回発行をこのエンドポイントが兼ねている。

## なぜ未ログインを 401 にしないのか

「現在の認証状態を問い合わせる」エンドポイントとして、未ログインは**エラーではなく正常な答え**として扱っている。401 にすると次の面倒が生じる。

- タイムラインは公開なので、未ログインの利用者が普通にトップページを開く。そのたびに起動時のリクエストが 401 になる
- フロントには「401 ならセッション切れなのでログイン画面へ送る」という共通処理を置きたいが、起動時の 401 は「そもそもログインしていない」であって送ってはいけない。共通処理に例外を書くことになる
- 未ログインで開くたびに DevTools に赤い 401 が記録され、後からログを見る人にとってノイズになる
- CSRF トークンの初回発行を、401 を返すエンドポイントに担わせるのは筋が悪く、専用のエンドポイントを別に作ることになる

実務ではどちらの設計も見られるが、公開画面を持つ SPA では 200 + `null` が多数派。

## エラー

通常は発生しない。

## セッションが生きているが users の行が消えている場合

削除されたアカウントのセッションが残っているケースでは、`user: null`(未ログインと同じ)を返す。

## 処理の流れ

1. `AuthController.me()` — `com/example/app/auth/AuthController.java`
   `@AuthenticationPrincipal AppUserDetails principal` で現在ユーザーを受け取る。**未ログインのときは `null`** が入る
   (Spring Security は未ログインでも `AnonymousAuthenticationToken` を割り当てるが、その principal は `"anonymousUser"` という文字列なので、型を指定した引数には入らない)
2. `AuthService.getCurrentUser()` — `com/example/app/auth/AuthService.java`
   `userId` が null なら `MeResponse.anonymous()`、そうでなければ users から引いて `MeResponse.of()`

### 表示名を毎回 DB から読んでいる理由

`AppUserDetails`(principal)はシリアライズされて `SPRING_SESSION_ATTRIBUTES` に保存される。表示名まで持たせると、プロフィール編集(フェーズ7)後もセッションの中身が古い値のまま残る。そのため principal には `users.id` とメールアドレスだけを持たせ、あとから変わる値は都度 DB から読む。

登場するその他のファイル:

- レスポンス定義: `com/example/app/auth/dto/MeResponse.java`
- principal: `com/example/app/auth/AppUserDetails.java`(Google ログイン時は `AppOidcUser.java`)
