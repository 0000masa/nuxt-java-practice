# POST /api/auth/verify-email — メール確認の完了

## 概要

確認メールのリンクに載っていたトークンを検証し、`users.email_verified_at` を埋めてログイン可能にする。

認証不要。CSRF トークンは必要。

## リクエスト

### ボディ(JSON)

| フィールド | 型 | 必須 | バリデーション |
|-----------|-----|------|--------------|
| `token` | string | 必須 | 空白のみは不可 |

```json
{ "token": "xvGGoG373LjU..." }
```

## レスポンス

**204 No Content** — ボディなし。

## エラー

| ステータス | 発生条件 | メッセージ |
|-----------|---------|-----------|
| 400 Bad Request | `token` が空 | `fieldErrors.token` |
| 400 Bad Request | トークンが存在しない、または**用途が違う** | リンクが不正です。もう一度やり直してください |
| 400 Bad Request | 既に使用済み | このリンクは既に使用されています |
| 400 Bad Request | 有効期限(24時間)切れ | リンクの有効期限が切れています。もう一度やり直してください |
| 403 Forbidden | CSRF トークンが無い / 合わない |  |

**用途が違うトークン**(パスワードリセット用のトークンをここに渡した場合)は、「存在しない」場合と同じメッセージを返す。トークンの存在自体を教えないため。

期限切れ・使用済みからの復帰は [確認メールの再送](./resend-verification.md)。

## なぜ GET ではなく POST なのか

メールに載っているリンクは**バックエンドではなくフロントのページ**(`{APP_BASE_URL}/verify-email?token=...`)を指している。そのページが読み込み後にこの API を POST で叩く。

- 成功 / 期限切れ / 使用済みの表示をフロント側で統一できる
- 「画面は全て Nuxt、Spring は `/api/**` のみ」というアーキテクチャ決定に沿う
- **副作用のある処理を GET にしない。** メールソフトやセキュリティ製品のリンク先読みで、利用者がクリックする前にトークンが消費される事故を避けられる

## 処理の流れ

1. `AuthController.verifyEmail()` — `com/example/app/auth/AuthController.java`
2. `AuthService.verifyEmail()` — `com/example/app/auth/AuthService.java`
3. `AuthTokenService.consume()` — `com/example/app/auth/AuthTokenService.java`
   受け取った生の値を SHA-256 でハッシュ化し、`auth_tokens.token` の UNIQUE index で 1 行引く → 用途・使用済み・期限を検証 → `used_at` を埋める
4. `AuthService` が `users.email_verified_at` に現在時刻を入れる(取得したエンティティは JPA の管理下にあるので、値を変えるだけで UPDATE が流れる)

登場するその他のファイル:

- リクエストボディ定義: `com/example/app/auth/dto/VerifyEmailRequest.java`
- トークンのエンティティ: `com/example/app/auth/AuthToken.java`
- 400 変換: `com/example/app/common/exception/InvalidRequestException.java`
