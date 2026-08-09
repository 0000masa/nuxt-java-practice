# POST /api/auth/logout — ログアウト

## 概要

セッションを破棄する。`SPRING_SESSION` から該当行が消え、`SESSION` Cookie と CSRF Cookie が無効化される。

認証不要(未ログインで叩いても成功として扱う)。CSRF トークンは必要。

[ログイン](./login.md)と同じく **Spring Security の `logout` が処理するので Controller メソッドは存在しない。** 設定は `config/SecurityConfig.java`。

## リクエスト

ボディなし。GET ではなく POST のみ。CSRF が有効なので、GET リンクでログアウトさせることはできない(他人のサイトに置かれた `<img src="/api/auth/logout">` で勝手にログアウトさせられるのを防ぐ形になっている)。

## レスポンス

**204 No Content** — ボディなし。

既定の挙動はログインページへのリダイレクトなので、`AuthResponseWriter.onLogoutSuccess()` で 204 に差し替えている。

`XSRF-TOKEN` Cookie はここで作り直される。フロントは次の書き込みリクエストの前に Cookie を読み直す必要がある(`plugins/api.ts` はリクエストごとに読んでいるので自動的に追従する)。

## エラー

| ステータス | 発生条件 |
|-----------|---------|
| 403 Forbidden | CSRF トークンが無い / 合わない |

## 処理の流れ

1. `LogoutFilter`(Spring Security)が `/api/auth/logout` の POST を捕まえる
2. `SecurityContext` を破棄し、セッションを無効化(Spring Session JDBC 経由で `SPRING_SESSION` の行が削除される)
3. `CsrfLogoutHandler` が CSRF トークンを破棄し、`csrf().spa()` の設定により新しい Cookie が発行される
4. `AuthResponseWriter.onLogoutSuccess()` が 204 を返す

## 関連: 他の端末のセッションを消したい場合

このエンドポイントは操作中のセッションだけを消す。他の端末を追い出したいときは、

- [パスワードリセット](./confirm-password-reset.md) — そのユーザーの**全**セッションを消す
- [パスワード変更](./change-password.md) — 操作中のセッション**以外**を消す
