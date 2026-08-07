# POST /api/auth/verification/resend — 確認メールの再送

## 概要

確認メールをもう一度送る。「メールが届かなかった」「有効期限(24時間)が切れた」からの**唯一の復帰経路**。

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

**204 No Content** — ボディなし。

## エラー

| ステータス | 発生条件 | メッセージ |
|-----------|---------|-----------|
| 400 Bad Request | `email` の形式が不正 | `fieldErrors.email` |
| 400 Bad Request | そのメールアドレスが未登録 | `fieldErrors.email`: このメールアドレスは登録されていません |
| 400 Bad Request | 既に確認済み | メールアドレスの確認は既に完了しています。そのままログインしてください |
| 403 Forbidden | CSRF トークンが無い / 合わない |  |

未登録のメールアドレスであることを伝えているのは、ユーザー列挙を許容する意図的な判断による(→ [ADR-0003](../adr/0003-account-enumeration-and-unverified-signup.md))。

## 古いリンクは無効になる

新しいトークンを発行するとき、**同じ用途の未使用トークンはすべて使用済みにする**。有効なリンクが同時に複数存在しないようにするため、再送すると前回のメールのリンクは使えなくなる。

## 呼び出される画面

- ログイン画面 — メール未確認で 401 になったとき、エラーメッセージの中に再送ボタンを出す
- `/verify-email` — 期限切れ・使用済みでトークンの検証に失敗したとき、メールアドレスを入力して再送させる

## 処理の流れ

1. `AuthController.resendVerification()` — `com/example/app/auth/AuthController.java`
2. `AuthService.resendVerification()` — `com/example/app/auth/AuthService.java`
   メールアドレスで users を引く(無ければ 400)→ 確認済みなら 400
3. `AuthTokenService.issue()` — `com/example/app/auth/AuthTokenService.java`
   古い未使用トークンを無効化してから新しいトークンを発行
4. `AuthMailRequestedEvent` の発行 → コミット後に `AuthMailSender` が送信
