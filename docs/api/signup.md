# POST /api/auth/signup — 会員登録

## 概要

未確認のユーザーを作り、確認メールを送る。**この時点ではまだログインできない**(メール確認が済むまでログイン不可)。

認証不要。CSRF トークンは必要(→ [README の「CSRF トークン」](./README.md#csrf-トークン書き込み系すべてに必要))。

## リクエスト

### ボディ(JSON)

| フィールド | 型 | 必須 | バリデーション |
|-----------|-----|------|--------------|
| `username` | string | 必須 | 英数字と `_` のみ、3〜30文字。一意 |
| `displayName` | string | 必須 | 50文字以内 |
| `email` | string | 必須 | メールアドレス形式、255文字以内。一意 |
| `password` | string | 必須 | 8〜72文字 |

`password` の上限 72 は BCrypt の仕様に合わせたもの(72バイトを超えた分は無視されるため、それ以上を受け付けると「後半を書き間違えても通る」状態になる)。

```json
{
  "username": "taro",
  "displayName": "太郎",
  "email": "taro@example.com",
  "password": "password123"
}
```

## レスポンス

**201 Created** — ボディなし。フロントは「確認メールを送りました」の表示に切り替える。

## エラー

| ステータス | 発生条件 |
|-----------|---------|
| 400 Bad Request | ボディのバリデーション違反(`fieldErrors` に詳細) |
| 400 Bad Request | `email` が**確認済みのアカウント**に使われている(`fieldErrors.email`) |
| 400 Bad Request | `username` が他のアカウントに使われている(`fieldErrors.username`) |
| 403 Forbidden | CSRF トークンが無い / 合わない |

```json
{
  "message": "入力内容に誤りがあります",
  "fieldErrors": {
    "email": "このメールアドレスは既に登録されています"
  }
}
```

## メールアドレスが既に使われている場合の分岐

**確認済みか未確認かで振る舞いが変わる。** 理由と背景 → [ADR-0003](../adr/0003-account-enumeration-and-unverified-signup.md)

| 状況 | 振る舞い |
|---|---|
| **確認済み** | 400 + `fieldErrors.email` で弾く |
| **未確認** | 既存の users 行を**削除して、今回の入力で作り直す**。201 を返して確認メールを送る |

未確認のアカウントを作り直すのは、古い資格情報(パスワード)を破棄するため。確認メールを送るだけにすると、攻撃者が先に他人のメールアドレスで登録しておき、本人が後から登録して確認リンクを踏んだ瞬間に**攻撃者のパスワードのアカウントが有効化される**(pre-hijacking)。この削除は意図的な実装なので、「危険に見えるから」と変更しないこと。

未確認ユーザーはログインできず投稿もいいねも持ち得ないため、行を削除しても他のテーブルが壊れない(`auth_tokens` は `ON DELETE CASCADE` で付随して消える)。

なお `username` の重複チェックは、作り直しより**先**に行う。他人が使っている username を指定してきた場合は削除せず 400 で弾く。

## 「登録済みかどうか」を教えている点について

このエンドポイントは「そのメールアドレスは既に登録されている」ことを利用者に伝える。これはユーザー列挙(account enumeration)を許すことになるが、学習用アプリとして**分かりやすさを優先した意図的な判断**である(→ [ADR-0003](../adr/0003-account-enumeration-and-unverified-signup.md))。実運用サービスに転用する場合は列挙対策を入れ直す必要がある。

## 処理の流れ

1. `AuthController.signup()` — `com/example/app/auth/AuthController.java`
   `@Valid` で `SignupRequest` のバリデーションを実行(違反時はここで 400)
2. `AuthService.signup()` — `com/example/app/auth/AuthService.java`
   メールアドレスと username の重複を判定 → 未確認なら既存行を削除 → パスワードをハッシュ化して users に INSERT
3. `AuthTokenService.issue()` — `com/example/app/auth/AuthTokenService.java`
   `SecureRandom` で 32 バイトのトークンを作り、**SHA-256 ハッシュだけを** auth_tokens に保存。生の値を戻り値で返す
4. `AuthService` が `AuthMailRequestedEvent` を発行 → **トランザクションのコミット後**に `AuthMailSender` が送信

メール送信をコミット後に回しているのは、DB トランザクションを SMTP の往復時間だけ開けたままにしないため、かつメールサーバー障害で登録自体が失敗しないようにするため。逆に「登録はできたがメールが届かない」は起こり得るので、[確認メールの再送](./resend-verification.md)で救う。

登場するその他のファイル:

- リクエストボディ定義: `com/example/app/auth/dto/SignupRequest.java`
- パスワードのハッシュ化: `com/example/app/config/SecurityConfig.java`(`PasswordEncoder`。`{bcrypt}` プレフィックス付きで保存される)
- メール本文とリンクの組み立て: `com/example/app/auth/AuthMailSender.java`
- 400 変換: `com/example/app/common/exception/FieldValidationException.java`、`GlobalExceptionHandler.java`
