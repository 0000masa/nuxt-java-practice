# Google ログインの設定(Google Cloud Console)

[Google ログイン](../api/google-login.md)を実際に動かすには、Google Cloud Console で OAuth クライアントを作り、その資格情報を `.env` に入れる必要がある。**この作業だけはリポジトリの中で完結しない。**

設定しなくてもアプリは起動し、Google ログイン以外の機能はすべて動く(`application.yml` にダミー値が入っているため)。Google ボタンを押したときだけ Google の画面でエラーになる。

## 手順

### 1. プロジェクトを用意する

[Google Cloud Console](https://console.cloud.google.com/) にログインし、プロジェクトを選ぶ(無ければ作る)。学習用なので既存のどれでもよい。

### 2. OAuth 同意画面を設定する

「API とサービス」→「OAuth 同意画面」。

- User Type は **外部**
- アプリ名・サポートメール・デベロッパー連絡先を埋める
- スコープは追加不要(`openid` / `email` / `profile` は既定で付く)
- **公開ステータスが「テスト」のままなら、ログインに使う Google アカウントを「テストユーザー」に追加する。** これを忘れると同意画面で「アクセスをブロックしました」と言われる

### 3. OAuth クライアント ID を作る

「API とサービス」→「認証情報」→「認証情報を作成」→「OAuth クライアント ID」。

- アプリケーションの種類: **ウェブアプリケーション**
- **承認済みのリダイレクト URI** に次を追加する

```
http://localhost:3000/api/login/oauth2/code/google
```

ここを間違えると Google が `redirect_uri_mismatch` で弾く。よくある間違い:

| 間違い | なぜ駄目か |
|---|---|
| ポートを `8080` にする | 登録するのは**ブラウザから見た URL**。ブラウザが開いているのは Nuxt の 3000 番で、`/api` は devProxy が backend へ転送している |
| `/login/oauth2/code/google`(`/api` 無し) | このアプリは OAuth のエンドポイントを `/api` 配下に移してある(理由 → [設計 決定1](../superpowers/specs/2026-08-15-phase4-google-auth-design.md)) |
| 末尾にスラッシュを足す | Google の照合は完全一致 |

「承認済みの JavaScript 生成元」は空でよい(このアプリはブラウザから Google の API を直接呼ばない)。

### 4. `.env` に書く

作成後に表示されるクライアント ID とシークレットを `.env` に入れる。

```
GOOGLE_CLIENT_ID=1234567890-xxxxxxxxxxxx.apps.googleusercontent.com
GOOGLE_CLIENT_SECRET=GOCSPX-xxxxxxxxxxxxxxxx
```

`.env` は `.gitignore` に入っているのでコミットされない。テンプレートは `.env.example`。

**空文字にはしないこと。** 空文字は「値が設定されている」扱いになり `application.yml` の既定値が効かず、クライアントを組み立てられずに**起動が失敗する**。使わないなら `dummy-client-id` のようなダミーを入れておく。

### 5. 反映して確認する

```bash
docker compose restart backend
```

送り出しが Google に向いているかは、ブラウザを開かなくても確認できる。

```bash
curl -s -i http://localhost:3000/api/oauth2/authorization/google | grep -i '^location:'
```

`https://accounts.google.com/o/oauth2/v2/auth?...` が返り、その中の `client_id` が自分の値、`redirect_uri` が `http://localhost:3000/api/login/oauth2/code/google` になっていればよい。

あとはブラウザで `http://localhost:3000/login` を開き、「Google でログイン」を押す。

## 本番(AWS)で使うとき

- リダイレクト URI に本番ドメインのものを**追加**する(`https://<ドメイン>/api/login/oauth2/code/google`)。開発用と両方登録しておける
- `APP_BASE_URL` を本番の URL にする。`redirect-uri` はこれを元に組み立てられるので、ここが合っていないと `redirect_uri_mismatch` になる
- クライアントシークレットは ECS のタスク定義から Secrets Manager 経由で注入する(`.env` を本番に持ち込まない)

## 関連

- [Google ログインの API ドキュメント](../api/google-login.md)
- [フェーズ4 設計](../superpowers/specs/2026-08-15-phase4-google-auth-design.md)
- [新しい PC への開発環境構築](./new-machine.md)
