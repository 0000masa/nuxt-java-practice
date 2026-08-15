# Google ログイン

`GET /api/oauth2/authorization/google` / `GET /api/login/oauth2/code/google`

Google アカウントでログインする(→ CONTEXT.md「Google ログイン」)。初めて使うユーザーは同時に作られ、同じメールアドレスの既存ユーザーがあれば紐づけられる(→ CONTEXT.md「アカウントリンク」)。

> **このファイルだけ「エンドポイントごとに1ファイル」の原則から外れている。** 関わる 2 つのエンドポイントはフロントから見ると 1 本の遷移であり、コールバックは誰も直接叩かない・叩けない(`state` が無いと通らない)。別々に書くと、片方だけ読んでも意味が取れない文書が 2 つできるため 1 ファイルにまとめている。

設計 → [2026-08-15-phase4-google-auth-design.md](../superpowers/specs/2026-08-15-phase4-google-auth-design.md) / 方針 → [ADR-0004](../adr/0004-google-account-linking.md)

## 呼び出し方

**fetch ではなく、ブラウザのページ遷移で叩く。**

```html
<a href="/api/oauth2/authorization/google">Google でログイン</a>
```

`fetch` / `$fetch` では Google の同意画面を取りに行くことになり CORS で失敗する。Nuxt の `<NuxtLink>` もクライアント側ルーティングになってサーバーにリクエストが飛ばないため使えない。

CSRF トークンは不要(GET のため)。

## 遷移の流れ

```
① ブラウザ → GET /api/oauth2/authorization/google
② backend → 302 → https://accounts.google.com/o/oauth2/v2/auth?...
   ( state / nonce / PKCE をセッション = MySQL に退避してから送り出す )
③ 利用者が Google 上でログイン・同意
④ Google → 302 → {APP_BASE_URL}/api/login/oauth2/code/google?code=...&state=...
⑤ backend: state を照合 → code をトークンに交換 → users を引く/作る/リンクする
   → セッションを張る → 302
⑥ 成功: {APP_BASE_URL}/auth/callback   失敗: {APP_BASE_URL}/login?error=<コード>
```

④ の行き先は Google Cloud Console に登録済みの URI と**完全一致**でなければ Google が拒否する。開発では `http://localhost:3000/api/login/oauth2/code/google`(ブラウザから見た URL なので 3000 番。8080 ではない)。

⑤ を終えた時点で `SESSION` Cookie が発行され、以後はパスワードログインと同じ扱いになる。`GET /api/auth/me` も `POST /api/posts` もそのまま使える。

## ユーザーの特定と作成

`google_sub` → `email` の順に引く。

| 状況 | 結果 |
|---|---|
| `google_sub` が一致 | そのユーザーでログイン。`users.email` は Google 側の値で**更新しない** |
| メールが一致・**確認済み** | `google_sub` を紐づけてログイン(アカウントリンク)。`users.id` は変わらないので投稿もいいねも引き継がれる |
| メールが一致・**未確認** | その行を削除して作り直す。攻撃者が先に登録したパスワードごと有効化されるのを防ぐため(→ [ADR-0003](../adr/0003-account-enumeration-and-unverified-signup.md)) |
| どちらも無し | 新規作成 |

新規作成されるユーザーの初期値:

| 列 | 値 |
|---|---|
| `username` | メールのローカル部から自動生成(小文字化・英数字と `_` 以外は `_`・20 文字・衝突時は `_2`)。例: `masanori.adachi@gmail.com` → `masanori_adachi` |
| `display_name` | Google の `name` クレーム(無ければ username) |
| `email_verified_at` | 現在時刻。**確認メールは送らない** |
| `password_hash` | NULL。パスワードログインはできない |

`password_hash` が NULL のユーザーがパスワードを設定するには[パスワードリセット](./request-password-reset.md)の経路を使う。この状態はフロントで判別できるよう [`GET /api/auth/me`](./get-me.md) の `hasPassword` が `false` になる。

## 失敗

失敗しても JSON は返らない。`{APP_BASE_URL}/login?error=<コード>` へリダイレクトされ、文言はフロント側の対応表(`pages/login.vue`)が持つ。**URL にメッセージ本文を載せない**のは、細工したリンクで任意の文言を画面に表示させられるため。

| コード | 意味 |
|---|---|
| `email_unverified` | Google 側でメールアドレスの所有権が確認されていない。メールを鍵に人物を特定できないので断る(→ [ADR-0004](../adr/0004-google-account-linking.md)) |
| `login_failed` | それ以外の失敗(state 不一致、トークン交換の失敗、Google 側での拒否など) |

## 設定

`.env` の 2 つ。取得手順 → [docs/setup/google-oauth.md](../setup/google-oauth.md)

```
GOOGLE_CLIENT_ID=...
GOOGLE_CLIENT_SECRET=...
```

**未設定でもアプリは起動する**(ダミー値が入る)。その場合 Google ボタンを押すと Google の画面でエラーになるが、他の機能には影響しない。

## 関連

- [ログイン](./login.md) — メールアドレス + パスワード
- [現在ユーザーの取得](./get-me.md) — `hasPassword`
- [パスワードリセットの申請](./request-password-reset.md) — Google 専用ユーザーがパスワードを設定する経路
- [ログアウト](./logout.md) — Google 側のセッションには触れない。このアプリのセッションを消すだけ
