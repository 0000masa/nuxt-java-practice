# POST /api/auth/password-reset/confirm — パスワードリセットの実行

## 概要

再設定メールのトークンを検証し、新しいパスワードを設定する。**完了すると、そのユーザーの全セッションが削除される**(全端末が強制ログアウトされる)。

認証不要(未ログインで使う)。CSRF トークンは必要。

## リクエスト

### ボディ(JSON)

| フィールド | 型 | 必須 | バリデーション |
|-----------|-----|------|--------------|
| `token` | string | 必須 | 空白のみは不可 |
| `newPassword` | string | 必須 | 8〜72文字 |

```json
{
  "token": "xvGGoG373LjU...",
  "newPassword": "newpassword123"
}
```

## レスポンス

**204 No Content** — ボディなし。ログインはされないので、フロントはログイン画面へ誘導する。

## エラー

| ステータス | 発生条件 | メッセージ |
|-----------|---------|-----------|
| 400 Bad Request | ボディのバリデーション違反 | `fieldErrors` |
| 400 Bad Request | トークンが存在しない、または用途が違う | リンクが不正です。もう一度やり直してください |
| 400 Bad Request | 既に使用済み | このリンクは既に使用されています |
| 400 Bad Request | 有効期限(1時間)切れ | リンクの有効期限が切れています。もう一度やり直してください |
| 403 Forbidden | CSRF トークンが無い / 合わない |  |

## 全セッションを消すのがこの機能の目的の半分

「パスワードを盗まれたかもしれないからリセットした」のに、攻撃者が持っているセッションが生き残っていては意味がない。そのため新しいパスワードを設定したあと、そのユーザーのセッションを全件削除する。

これができるのは**セッションをサーバー側(MySQL)に持っているから**で、`SPRING_SESSION.PRINCIPAL_NAME`(値はメールアドレス)の index を使って 1 クエリで引ける。JWT 方式なら発行済みトークンを失効させる仕組みを自分で作る必要があった(→ [ADR-0002](../adr/0002-session-cookie-over-jwt.md))。

この操作は未ログイン状態で行うので、消して困る自分のセッションは無い。ログイン中の変更で全部消さない理由は [パスワード変更](./change-password.md) を参照。

動作を目で確認したい場合は、実行の前後で `SELECT * FROM SPRING_SESSION` を見ると行が消えることが分かる。

## 処理の流れ

1. `AuthController.confirmPasswordReset()` — `com/example/app/auth/AuthController.java`
2. `AuthService.confirmPasswordReset()` — `com/example/app/auth/AuthService.java`
3. `AuthTokenService.consume()` — `com/example/app/auth/AuthTokenService.java`
   トークンを検証して使用済みにし、紐づくユーザーを返す
4. `AuthService` が `PasswordEncoder` でハッシュ化した新しいパスワードを `users.password_hash` に設定
5. `UserSessionManager.deleteAll()` — `com/example/app/auth/UserSessionManager.java`
   `FindByIndexNameSessionRepository#findByPrincipalName(email)` で引いた全セッションを削除

登場するその他のファイル:

- リクエストボディ定義: `com/example/app/auth/dto/PasswordResetConfirmRequest.java`
