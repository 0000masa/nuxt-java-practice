# PUT /api/auth/password — パスワードの変更

## 概要

ログイン中のユーザーが、現在のパスワードを確認したうえで新しいパスワードに差し替える。

パスワードを忘れた場合の [パスワードリセット](./request-password-reset.md)とは別の操作(→ [CONTEXT.md](../../CONTEXT.md))。

**認証必須。** CSRF トークンも必要。

## リクエスト

### ボディ(JSON)

| フィールド | 型 | 必須 | バリデーション |
|-----------|-----|------|--------------|
| `currentPassword` | string | 必須 | 空白のみは不可 |
| `newPassword` | string | 必須 | 8〜72文字 |

```json
{
  "currentPassword": "password123",
  "newPassword": "newpassword123"
}
```

## レスポンス

**204 No Content** — ボディなし。操作した端末のログインは維持される。

## エラー

| ステータス | 発生条件 | メッセージ |
|-----------|---------|-----------|
| 400 Bad Request | ボディのバリデーション違反 | `fieldErrors` |
| 400 Bad Request | 現在のパスワードが違う、またはパスワード未設定のアカウント | `fieldErrors.currentPassword`: 現在のパスワードが違います |
| 401 Unauthorized | 未ログイン | ログインが必要です |
| 403 Forbidden | CSRF トークンが無い / 合わない | この操作は許可されていません |

### なぜ現在のパスワードを要求するのか

セッションを盗まれた相手に、パスワードごと奪われないようにするため。セッションだけではパスワードを変更できない。

### パスワード未設定のアカウント

`users.password_hash` が NULL のアカウント(Google 専用ユーザー)は照合する現在のパスワードが無いので、この経路では変更できない。その場合は [パスワードリセット](./request-password-reset.md)の経路で設定する。

## 自分以外のセッションを消す

リセットと違い、**操作中のセッションだけは残す**。変更した本人が追い出されるのは不自然なため。他の端末は追い出される。

残すセッションの判定には現在のセッション ID を使う。Controller が `HttpServletRequest` から取り出して Service に渡す(Service は Web 層の型を受け取らない)。

あわせて、**発行済みの未使用パスワードリセットトークンも失効させる**。パスワードを変えた後に古いリセットメールが使えると、変更前の状態に戻せてしまうため。

## 処理の流れ

1. `AuthController.changePassword()` — `com/example/app/auth/AuthController.java`
   `@AuthenticationPrincipal` で現在ユーザーを受け取る。認可ルールで認証必須にしてあるので、ここに来た時点で principal は必ず存在する
2. `AuthService.changePassword()` — `com/example/app/auth/AuthService.java`
   `PasswordEncoder.matches()` で現在のパスワードを照合(不一致なら 400)→ 新しいハッシュを設定
3. `AuthTokenService.invalidateUnused()` — 未使用のリセットトークンを使用済みにする
4. `UserSessionManager.deleteAllExcept()` — `com/example/app/auth/UserSessionManager.java`
   現在のセッション ID 以外を削除

登場するその他のファイル:

- リクエストボディ定義: `com/example/app/auth/dto/ChangePasswordRequest.java`
- 認可ルール: `com/example/app/config/SecurityConfig.java`
