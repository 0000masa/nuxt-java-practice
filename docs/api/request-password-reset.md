# POST /api/auth/password-reset/request — パスワードリセットの申請

## 概要

パスワードを忘れた利用者に、再設定用のリンクをメールで送る。**ログインしていない状態で使う**操作。

ログイン中に変更する場合は [パスワード変更](./change-password.md)。両者は別の操作として扱う(→ [CONTEXT.md](../../CONTEXT.md))。

認証不要。CSRF トークンは必要。

## リクエスト

### ボディ(JSON)

| フィールド | 型 | 必須 | バリデーション |
|-----------|-----|------|--------------|
| `email` | string | 必須 | メールアドレス形式 |

```json
{ "email": "taro@example.com" }
```

## レスポンス

**204 No Content** — ボディなし。フロントは「再設定用のメールを送りました」の表示に切り替える。

## エラー

| ステータス | 発生条件 | メッセージ |
|-----------|---------|-----------|
| 400 Bad Request | `email` の形式が不正 | `fieldErrors.email` |
| 400 Bad Request | そのメールアドレスが未登録 | `fieldErrors.email`: このメールアドレスは登録されていません |
| 400 Bad Request | **メール確認が完了していない** | メールアドレスの確認が完了していません。先に確認メールから有効化してください |
| 403 Forbidden | CSRF トークンが無い / 合わない |  |

### 未確認のアカウントには許可しない

許すと「メール確認を経ずにパスワードを設定してログインできる」抜け道になり、メール確認の意味がなくなる。この場合は [確認メールの再送](./resend-verification.md)へ誘導する。

### パスワード未設定のアカウントには許可する

`users.password_hash` が NULL でも、`email_verified_at` が入っていればリセットできる。フェーズ4 の Google 専用ユーザーが**パスワードを新規に設定する手段**がこの経路になる。

開発時の余談として、`dev_user`(`dev@example.com`)はメール確認済みでパスワード未設定なので、この経路でパスワードを設定すればログインできるようになる(宛先が Mailpit なのでメールは受け取れる)。

## 有効期限

リンクは **1 時間**で無効になる。メール確認(24時間)より短いのは、乗っ取られたときの影響が大きいため。

新しいトークンを発行するとき、同じ用途の未使用トークンはすべて無効化される。

## 処理の流れ

1. `AuthController.requestPasswordReset()` — `com/example/app/auth/AuthController.java`
2. `AuthService.requestPasswordReset()` — `com/example/app/auth/AuthService.java`
   メールアドレスで users を引く(無ければ 400)→ 未確認なら 400
3. `AuthTokenService.issue()` — `com/example/app/auth/AuthTokenService.java`
   `PASSWORD_RESET` 用途のトークンを発行(DB には SHA-256 ハッシュのみ)
4. `AuthMailRequestedEvent` の発行 → コミット後に `AuthMailSender` が `{APP_BASE_URL}/password-reset/confirm?token=...` を載せたメールを送る
