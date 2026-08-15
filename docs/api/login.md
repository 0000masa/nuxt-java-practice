# POST /api/auth/login — ログイン

## 概要

メールアドレスとパスワードでログインする。成功するとセッションが作られ、`SESSION` Cookie が発行される。

認証不要。CSRF トークンは必要。

## このエンドポイントだけボディが JSON ではない

**リクエストボディは `application/x-www-form-urlencoded`。** 他の API はすべて JSON だが、ここだけ違う。

Spring Security 標準の `formLogin` に処理を任せており、`formLogin` は JSON ボディを読めないため。これは方式選定時に受け入れた代償なので、**「統一されていないから」と JSON 化しないこと**(→ [ADR-0002](../adr/0002-session-cookie-over-jwt.md))。

そのため**対応する Controller メソッドは存在しない。** `config/SecurityConfig.java` の `formLogin(...)` 設定がこのエンドポイントの定義そのものになっている。

## リクエスト

### ボディ(form-urlencoded)

| パラメータ | 必須 | 内容 |
|-----------|------|------|
| `email` | 必須 | ログインの識別子。`users.username` ではない |
| `password` | 必須 | パスワード |

パラメータ名が `username` ではなく `email` なのは `usernameParameter("email")` を指定しているため(既定は `username`)。

```
email=taro%40example.com&password=password123
```

フロントエンドは `new URLSearchParams({ email, password })` を送る(`frontend/app/composables/useAuth.ts`)。

## レスポンス

**200 OK** — [`GET /api/auth/me`](./get-me.md) と同じ形。フロントはこのレスポンスをそのままストアに入れられる。

```json
{
  "user": {
    "id": 20,
    "username": "taro",
    "displayName": "太郎",
    "email": "taro@example.com"
  }
}
```

Cookie が 2 つ返る。

- `SESSION` — セッション ID。以降のリクエストの認証に使う
- `XSRF-TOKEN` — **作り直された** CSRF トークン。ログイン前の値は使えなくなるので、フロントは Cookie を読み直す

## エラー

| ステータス | 発生条件 | メッセージ |
|-----------|---------|-----------|
| 401 Unauthorized | パスワードが違う / メールアドレスが未登録 / パスワード未設定のアカウント | メールアドレスまたはパスワードが違います |
| 401 Unauthorized | **メール確認が完了していない** | メールアドレスの確認が完了していません。確認メールを再送してください |
| 403 Forbidden | CSRF トークンが無い / 合わない | この操作は許可されていません |

### メール未確認だけメッセージを区別している

未確認を隠すと、利用者は「パスワードが違う」と誤解したまま復帰できなくなる。メールアドレスの登録有無が分かる形になるが、これは列挙を許容する方針と一貫している(→ [ADR-0003](../adr/0003-account-enumeration-and-unverified-signup.md))。

フロントエンドはこのメッセージを見て、確認メールの再送ボタンを出す。

なお未確認の判定はパスワードの照合より**前**に行われる(Spring Security が有効性を先に検査する)ため、パスワードが間違っていてもこのメッセージになる。

### 「未登録」と「パスワード未設定」が同じ扱いになる理由

`users.password_hash` が NULL のアカウント(Google ログインだけで作られたユーザー、および `dev_user`)はパスワードログインできない。そのユーザーは [Google ログイン](./google-login.md)を使う。実装は `UsernameNotFoundException` を投げるだけで、Spring Security の `DaoAuthenticationProvider` が次の 2 つを自動で行う。

1. 既定の `hideUserNotFoundExceptions` により「資格情報が不正」に差し替え、未登録との区別をなくす
2. ユーザーが見つからなかった場合もダミーのハッシュとの照合を走らせ、応答時間の差からアカウントの存在を推測されないようにする(タイミング攻撃対策)

対策を自分で書かないのが正解、という箇所。

## 処理の流れ

Controller は無く、フィルタチェーンの中で完結する。

1. `UsernamePasswordAuthenticationFilter`(Spring Security)が `/api/auth/login` の POST を捕まえる
2. `AppUserDetailsService.loadUserByUsername()` — `com/example/app/auth/AppUserDetailsService.java`
   メールアドレスで users を引き、`AppUserDetails` を返す(パスワード未設定なら `UsernameNotFoundException`)
3. `DaoAuthenticationProvider` がパスワードを照合し、`AppUserDetails.isEnabled()`(= メール確認済みか)を検査
4. 成功 → セッション ID を作り直し(セッション固定攻撃対策)、`SecurityContext` をセッションに保存 → `AuthResponseWriter.onLoginSuccess()` が現在ユーザーを JSON で返す
   失敗 → `AuthResponseWriter.onLoginFailure()` が 401 + `ErrorResponse` を返す

セッション固定攻撃対策と `SecurityContext` の保存を自分で書かなくて済むのが、`formLogin` に乗せている主な理由。

なお認証成功後、`AppUserDetails` が持っていたパスワードハッシュは `eraseCredentials()` で消される。ハッシュが `SPRING_SESSION_ATTRIBUTES` に残り続けないようにするため。

登場するその他のファイル:

- 設定: `com/example/app/config/SecurityConfig.java`
- principal: `com/example/app/auth/AppUserDetails.java`
- 成功 / 失敗のレスポンス: `com/example/app/auth/AuthResponseWriter.java`
