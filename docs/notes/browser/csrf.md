# CSRF とは何か、CSRF トークンはなぜ効くのか

`SecurityConfig` の `.csrf(csrf -> csrf.spa())` と、フロントの `X-XSRF-TOKEN` ヘッダ。この 2 行が何を守っているのかを、**実際にこのアプリを攻撃してみるところから**組み立て直す学習メモ。

**「攻撃を 1 本通し、防ぎ方を順に試して落とし、最後に残ったものがトークンだった」**という順序で書く。仕組みの説明を先に読んでも「なぜそんな面倒なことを」が残るので、先に攻撃側に立つ。

CSRF は Spring の概念ではない。**ブラウザが Cookie を「他サイトからのリクエストにも自動で付ける」という性質から生まれる攻撃**で、Rails でも Laravel でも Express でも同じ話になる。だから `java/spring/` ではなく `browser/` に置いている。

> **このメモの検証状況**
> このプロジェクトに対する挙動は、**起動中の backend に実際にリクエストを送って確かめた**。本文中で `実測` と記した箇所がそれで、貼ってあるレスポンスは実物。
> `csrf.spa()` の内部動作は、このプロジェクトが実際に使っている jar のソース(`spring-security-config` 7.1.0 / `spring-session-core` 4.1.0 の `-sources.jar`)を読んで確認した。該当箇所には `ソース確認` と記した。
> 一方、**ブラウザ側の挙動(SameSite の判定、CORS のプリフライト、`<form>` が送れる Content-Type)は仕様に基づく一般論で、実際のブラウザで再現してはいない**。Laravel の記述も一般論。
> **フィルタの列のどこに `CsrfFilter` がいるか**はこのメモの担当ではない → [security-filter-chain.md](../java/spring/security-filter-chain.md)。あちらが「列全体の地図」、こちらが「トークン 1 個の一生」。

## まず結論(3 行)

1. **CSRF は「攻撃者がリクエストを送らせる」攻撃であって、「攻撃者がレスポンスを読む」攻撃ではない。** ブラウザは他サイトからのリクエストにも Cookie を自動で付けるが、返ってきたレスポンスは読ませてくれない。この **送れるが読めない** という隙間が CSRF。
2. **CSRF トークンは、その隙間をちょうど塞ぐ形をしている。** 「Cookie の値を**読んで**ヘッダに載せ直す」ことを要求する。Cookie は自動で付くが、**読むには同じサイトの JavaScript でなければならない**。攻撃者にはできない。
3. **このアプリの正解は Cookie 側にあり、サーバーは覚えていない**(実測で確認)。この方式(Double Submit Cookie)は「Cookie を書ける攻撃者」には破られるので、`SameSite` と組み合わせて初めて成立する。片方だけでは足りない。

---

## 1. 攻撃してみる

CSRF(Cross-Site Request Forgery、クロスサイトリクエストフォージェリ)は、**ログイン中の利用者のブラウザを使って、本人の意図しないリクエストをサーバーに送らせる攻撃**。

古典的な例で原理を見る。銀行サイト `bank.example.com` に、送金のフォームがあるとする。

```html
<!-- bank.example.com の正規の画面 -->
<form action="https://bank.example.com/transfer" method="post">
  <input name="to" value="">
  <input name="amount" value="">
  <button>送金する</button>
</form>
```

利用者はログイン済みで、ブラウザには `SESSION` Cookie が入っている。ここで攻撃者が、まったく無関係な自分のサイト `evil.example` にこう置く。

```html
<!-- evil.example の罠ページ。「猫の画像まとめ」とでも書いてある -->
<form id="f" action="https://bank.example.com/transfer" method="post">
  <input type="hidden" name="to" value="attacker">
  <input type="hidden" name="amount" value="1000000">
</form>
<script>document.getElementById('f').submit()</script>   <!-- 開いた瞬間に送信 -->
```

被害者が罠ページを開くと、**ブラウザは `bank.example.com` へ POST を送る。そのとき `SESSION` Cookie も一緒に付ける**。銀行のサーバーから見ると、ログイン済みの本人が送金ボタンを押したのと**まったく同じリクエスト**が届く。

図にするとこうなる。

```
     被害者のブラウザ
          │
          │ ① evil.example を開く(攻撃者のサイト)
          ▼
     ┌──────────────┐
     │ evil.example │  「猫の画像まとめ」…に見えるが
     │  の罠ページ  │   裏で form.submit() が走る
     └──────────────┘
          │
          │ ② POST https://bank.example.com/transfer
          │    Cookie: SESSION=...     ← ブラウザが勝手に付ける
          │    to=attacker&amount=1000000
          ▼
     ┌──────────────────┐
     │ bank.example.com │  本人のセッションなので通ってしまう
     │   のサーバー     │
     └──────────────────┘
          │
          │ ③ レスポンス(送金完了)
          ▼
     evil.example の JavaScript は
     このレスポンスを読めない ← ここが効いてくる
```

**③ が肝**。攻撃者はレスポンスを読めない。読めないのに攻撃が成立するのは、**送金は「送った時点で終わり」だから**。結果を知る必要がない。

だから CSRF で狙われるのは「実行したら終わり」の操作 — 送金・退会・パスワード変更・投稿削除・設定変更。**情報を盗む攻撃ではない**(それは XSS の担当)。

## 2. なぜサーバーは見分けられないのか

「本人のリクエストと、罠ページ経由のリクエストを区別すればいい」と思うところだが、**サーバーに届いた HTTP リクエストだけを見ても、この 2 つはほぼ同じ**。

| | 本人が押した | 罠ページ経由 |
|---|---|---|
| メソッド・URL | `POST /transfer` | `POST /transfer` |
| Cookie | 付く | **付く** |
| ボディ | `to=...&amount=...` | `to=...&amount=...` |
| 送信元の IP | 被害者 | 被害者 |
| User-Agent | 被害者のブラウザ | 被害者のブラウザ |

同じになる原因はただ 1 つ、**Cookie が「宛先ドメイン」だけで送るかどうかを決めている**こと。

```
ブラウザの Cookie の規則(素の状態)

  「このリクエストの宛先は bank.example.com か?」
      → はい なら bank.example.com の Cookie を付ける

  「そのリクエストは、どのページから発生したのか?」
      → 見ていない
```

Cookie が発明されたとき、「どのページから送られたリクエストか」は考慮されていなかった。**この設計をあとから直すことができないので、上に対策を積むしかない**。CSRF 対策とは要するに、この足りない判断材料をアプリ側で補う作業のこと。

なお、この「ブラウザが持っている資格情報を自動で付けてしまう」性質を **ambient authority(環境的な権限)** と呼ぶ。Cookie だけでなく Basic 認証やクライアント証明書も同じ問題を持つ。**逆に `Authorization: Bearer ...` ヘッダは自動では付かない**ので、Bearer トークン方式なら CSRF は成立しない。「REST API に CSRF 対策は不要」という言説はこの前提の話で、Cookie にセッション ID を載せるこのアプリには**当てはまらない** — [ADR-0002](../../adr/0002-session-cookie-over-jwt.md) がそう書いている。

## 3. 防ぎ方を順に試す

思いつく対策を順に試して、どこで落ちるかを見る。

| 対策案 | 判定 | 落ちる理由 |
|---|---|---|
| `Referer` を見る | ✗ | 消えることがある。消えたら通すしかない |
| `Origin` を見る | △ | 有効だが単独では穴がある |
| 更新は POST 限定にする | ✗ | 罠フォームも POST を送れる |
| JSON しか受け付けない | △ | 効くが「防御」として設計されていない |
| `SameSite` Cookie | ○ | かなり防げる。ただし穴が 3 つ(→ §4) |
| **CSRF トークン** | ◎ | **攻撃者に原理的にできないことを要求する** |

### `Referer` を見る → ✗

「リクエスト元が自サイトでなければ弾く」。方向は正しいが、`Referer` は**送られてこないことがある**。プライバシー保護のため送信を抑える設定・拡張・`Referrer-Policy` の指定があり、HTTPS → HTTP の遷移でも落ちる。

すると「`Referer` が無いリクエストをどうするか」を決めることになる。弾けば正規の利用者が使えなくなり、通せば**攻撃者は `Referer` を消せばいい**。どちらに倒しても破綻する。

### `Origin` を見る → △

`Origin` ヘッダは `Referer` より状況がよく、**書き込み系のリクエストにはブラウザが必ず付ける**。攻撃者の JavaScript から偽装することもできない(ブラウザが付けるヘッダなので上書きできない)。実際 OWASP は**トークンと併用する補助策**として推奨している。

単独で頼らない理由は 2 つ。**`Origin` が `null` になる場合がある**(リダイレクト経由、`sandbox` 属性付き iframe など)ため「`null` をどう扱うか」問題が `Referer` と同じ形で残ること。そして**許可リストの管理が要る**こと — 環境ごとにドメインが違うので、設定を 1 か所間違えると全部通るか全部落ちるかになる。

### 更新は POST 限定にする → ✗

**罠ページの `<form method="post">` も普通に POST を送れる**ので、何も防げていない。

ただし**逆向きには意味がある**。「GET では状態を変えない」というのは、`<img src="...">` や `<link>` を踏ませるだけで攻撃が成立してしまうのを避けるために必要。`security-filter-chain.md` が書いている「GET でログアウトできると `<img src="/api/auth/logout">` で他人をログアウトさせられる」がその例。

**「POST なら安全」ではなく「GET を危険にしない」**。この 2 つは別の話。

### JSON しか受け付けない → △

ここは実際に効く。理由は **`<form>` が送れる `Content-Type` が 3 種類に限られている**こと。

```
<form enctype="..."> で指定できるのはこの 3 つだけ
  application/x-www-form-urlencoded   (既定)
  multipart/form-data
  text/plain

  application/json は指定できない
```

だから JSON ボディを要求する API は、罠ページの `<form>` からは叩けない。「では `fetch` で送れば?」となるが、`Content-Type: application/json` を付けた時点で **CORS のプリフライト(事前確認の `OPTIONS` リクエスト)**が発生し、サーバーが明示的に許可していなければブラウザがリクエスト本体を送らない。

△ にしているのは、これが**防御として設計されたものではない**から。API の仕様が変わった瞬間に消える。実際このアプリでも、ログインエンドポイントだけ `form-urlencoded` を受けるので穴が空いている(→ §9)。**副産物としての安全性に依存すると、仕様変更で静かに壊れる。**

### `SameSite` Cookie → ○

Cookie 側に「他サイトから発生したリクエストには付けるな」と書く方式。§2 で見た「Cookie が宛先ドメインしか見ていない」という根本原因を、Cookie の仕組み自体を拡張して直しにいったもの。

```
Set-Cookie: SESSION=abc123; SameSite=Lax
```

| 値 | 他サイトからのリクエストで送るか |
|---|---|
| `Strict` | 一切送らない。他サイトのリンクから来た初回も送らないので、ログイン済みなのにログアウト状態に見える |
| `Lax` | **トップレベルの GET だけ送る**(リンクをクリックした遷移)。form POST や `fetch`、`<img>` では送らない |
| `None` | 常に送る。`Secure` 必須 |

2020 年前後に主要ブラウザが「**`SameSite` を指定していない Cookie は `Lax` として扱う**」という既定に切り替えたので、現在は何も書かなくても大半の CSRF は成立しない。

**実測** — このアプリの `SESSION` Cookie には `SameSite=Lax` が付いている。`application.yml` に何も書いていないが、Spring Session の `DefaultCookieSerializer` の既定がそうなっているため(`ソース確認`: `spring-session-core` 4.1.0)。

```java
// org.springframework.session.web.http.DefaultCookieSerializer
private boolean useHttpOnlyCookie = true;
private @Nullable String sameSite = "Lax";
```

つまり **§1 の攻撃はこのアプリには最初から通らない**。`SameSite=Lax` によって、罠ページの form POST には `SESSION` Cookie が付かないから。

では、なぜトークンも入れているのか。

## 4. `SameSite` だけではなぜ足りないのか

理由は 3 つある。どれも「今このアプリで踏んでいる」わけではないが、**将来踏む可能性がある**ものばかり。

### ① `Lax` はトップレベルの GET を通す

`Lax` の定義は「他サイトから来た**トップレベルの GET** には Cookie を付ける」。リンクをクリックして遷移したときにログアウト状態に見えないよう、実用性のために空けてある穴。

だから **GET で状態を変える API が 1 本でもあると、そこは `SameSite` で守られない**。

```html
<!-- 罠ページ。Lax でも Cookie が付く -->
<a href="https://example.com/api/posts/1/delete">かわいい猫の写真はこちら</a>
```

**実測** — このアプリの `@GetMapping` は現在 4 本(`/api/categories` `/api/posts` `/api/posts/{id}` `/api/auth/me`)で、いずれも読み取り専用。**今は該当しない**。だが「今後 GET で状態を変えるものを 1 本足したら、そこだけ無防備になる」という条件付きの安全であって、**設定ファイルを見ても気付けない**。

### ② ブラウザの実装に依存する

`SameSite` を判断するのはブラウザ。**サーバーは「相手が判断してくれた」ことを確認できない**。古い環境・独自ブラウザ・`SameSite` を解釈しない HTTP クライアントでは、Cookie はそのまま送られる。

CSRF トークンは逆に、**サーバー側で照合する**。判定を相手に委ねない。

### ③ `SameSite` は「サイト」単位で、ドメイン単位ではない

`SameSite` の「same site」は登録可能ドメイン(eTLD+1)で判定される。つまり **`evil.example.com` と `app.example.com` は「同じサイト」**。

```
example.com
 ├── app.example.com    ← 本体
 └── blog.example.com   ← 別チームが運用、脆弱性あり

blog から app へのリクエストは「same-site」
  → SameSite=Lax でも Cookie が付く
  → SameSite による防御が丸ごと無効
```

サブドメインを 1 つでも取られる、あるいは共有ホスティングで隣に置かれると、`SameSite` は効かなくなる。

### だから両方入れる

これが **多層防御(defense in depth)** と呼ばれる考え方。ひとつの対策が破られても、別の層が残っている状態にする。

- `SameSite` の穴 → トークンが埋める
- トークンの穴(→ §6 で見る) → `SameSite` が埋める

**互いの弱点が違うので重ねる意味がある**。同じ弱点を持つ対策を 2 つ並べても厚みは増えない。

## 5. CSRF トークンの仕組み

ここまでの対策が全部「リクエストの**外側**(ヘッダ、メソッド、Cookie の属性)を見る」ものだったのに対し、トークンは発想が違う。

> **攻撃者に原理的にできないことを 1 つ選び、それをリクエストに要求する。**

その「できないこと」が、**他サイトの Cookie を読むこと**。

§1 の図の ③ を思い出す。ブラウザは Cookie を**送る**ことは他サイトからでも許すが、**読む**ことは同じオリジンの JavaScript にしか許さない(同一オリジンポリシー)。`evil.example` の JavaScript から `document.cookie` を読んでも、`evil.example` 自身の Cookie しか見えない。

```
                       送れる   読める
本人のページ (同一オリジン)   ○      ○
罠ページ    (別オリジン)     ○      ✗   ← ここの差を利用する
```

**この非対称性がそのまま防御になる**。「Cookie の値を読んで、ヘッダに書き写して送れ」と要求すれば、本人のページにはできて罠ページにはできない。

### トークンの一生

```
① 発行   サーバーがランダムな値を作り、Cookie に入れて返す
             Set-Cookie: XSRF-TOKEN=59c3b17b-...; Path=/
                    │
                    ▼
② 保管   ブラウザの Cookie ストアに入る
             HttpOnly は付けない ← JavaScript から読ませる必要があるので
                    │
                    ▼
③ 読む   同一オリジンの JavaScript が document.cookie から読み出す
             ★ 罠ページにはこれができない
                    │
                    ▼
④ 送信   ヘッダに書き写して送る
             Cookie:       XSRF-TOKEN=59c3b17b-...   ← ブラウザが自動で付ける
             X-XSRF-TOKEN: 59c3b17b-...              ← JavaScript が明示的に付ける
                    │
                    ▼
⑤ 照合   サーバーが 2 つを突き合わせる
             一致 → 通す / 不一致・欠落 → 403
                    │
                    ▼
⑥ 再発行 ログイン成功時・ログアウト時に作り直す
             → 古い値を握られていても無効になる
```

**④ で同じ値が 2 か所に入るのが要点**。片方(Cookie)はブラウザが勝手に付けるので攻撃者も同じ状況を作れるが、もう片方(ヘッダ)は**読めた者にしか書けない**。

### 罠ページから見るとどうなるか

```
罠ページの form submit
  │
  ├─ Cookie: XSRF-TOKEN=59c3b17b-...   ← 付いてしまう(ブラウザが付ける)
  └─ X-XSRF-TOKEN:                     ← 付けられない
                                          値を知らないので書きようがない
  ▼
サーバー: Cookie にはあるがヘッダに無い → 不一致 → 403
```

そもそも罠ページの `<form>` は**任意のヘッダを付けられない**(HTML の form にヘッダを指定する手段が無い)。`fetch` なら付けられるが、カスタムヘッダを付けた時点で CORS のプリフライトが走って止まる。

**「Cookie を読む」と「カスタムヘッダを付ける」の 2 つが同時に必要で、罠ページはどちらもできない。**

### ⑥ の再発行を忘れると事故る

ログイン前後でトークンを作り直すのは、**セッション固定化攻撃の CSRF 版**を防ぐため。攻撃者が自分のトークンを被害者のブラウザに仕込んでおくと、それを知っている攻撃者は正しいヘッダを作れてしまう。ログイン時に作り直せば、仕込まれた値は無効になる。

フロント側にこれが効いている — [plugins/api.ts:47](../../../frontend/app/plugins/api.ts) が**リクエストのたびに Cookie を読み直している**のはこのため。起動時に 1 回読んで変数に持つと、ログイン後に古い値を送り続けて 403 になる。

```ts
/**
 * XSRF-TOKEN Cookie を読む。
 *
 * リクエストごとに読み直しているのが重要。Spring Security はログイン成功時と
 * ログアウト成功時にトークンを作り直すので、起動時に 1 回読んで保持すると古い値を送ってしまう。
 */
function readCsrfToken(): string | undefined {
```

## 6. 正解はどこにあるのか — 2 つの方式

「サーバーは何と照合しているのか」で方式が 2 つに分かれる。ここを取り違えると、弱点の見積もりを間違える。

**実測** — Cookie を送らずヘッダだけ送ると 403 になる。

```
$ curl -X POST http://localhost:8080/api/auth/login \
    -H "X-XSRF-TOKEN: 59c3b17b-35d8-4217-a447-0991b001fc01" \
    -d 'email=a@example.com&password=xxx'
HTTP/1.1 403
```

正しい値をヘッダで送っているのに落ちる。つまり**サーバーは正解を持っておらず、Cookie 側が正解**。「Cookie とヘッダが一致しているか」だけを見ている。

| | Synchronizer Token Pattern | **Double Submit Cookie** |
|---|---|---|
| 正解の保管場所 | サーバー(セッション) | **Cookie** |
| サーバー側の状態 | 要る | **不要** |
| 照合の内容 | セッション内の値 vs 送られた値 | **Cookie vs ヘッダ** |
| 破るのに必要なこと | 正解を**読む** | Cookie を**書く** |
| 代表例 | Laravel、Rails の既定 | **このアプリ**、SPA 向け構成一般 |

### なぜ SPA では Cookie 保管なのか

理由が 2 つある。

**① サーバーが HTML を作らないから。** Synchronizer 方式は「サーバーが HTML を生成するときに hidden フィールドへトークンを埋め込む」ことを前提にしている。このアプリは Nuxt を SSG でビルドして静的ファイルとして配る構成なので([CLAUDE.md](../../../CLAUDE.md) の決定 2)、**トークンを埋め込む相手の HTML がそもそも存在しない**。Cookie で配るしかない。

**② セッションが無い状態でも配れるから。** ログイン前 — つまりセッションがまだ無い状態 — でも、ログインリクエスト自体に CSRF トークンが要る。Cookie 保管ならセッションと無関係に発行できる。

### この方式の弱点

「破るのに必要なこと」の行が全て。**攻撃者が被害者のブラウザに Cookie を書き込めれば、Cookie とヘッダの両方を自分の知っている値に揃えられる**ので、照合を通せてしまう。

Cookie を書き込む経路として現実的なのが**サブドメイン**。Cookie は `Domain=example.com` を指定すると全サブドメインへ書き込めるので、`blog.example.com` を取られると `app.example.com` の `XSRF-TOKEN` を上書きできる(cookie tossing と呼ばれる)。

ここで §4 ③ と繋がる。

```
サブドメインを取られたとき

  SameSite=Lax    → 効かない(same-site 扱いになる)
  Double Submit   → 効かない(Cookie を上書きできる)

  → どちらも同じ穴に落ちる。ここは重ねても厚くならない
```

**つまり「サブドメインを他人に渡さない」ことが、この 2 つの対策の共通の前提**になっている。この構成では独自ドメインを Route53 で自前管理しているので今は問題にならないが、**前提であることを知らずに運用すると崩せる**。

Synchronizer 方式ならこの穴は無い(正解がサーバーにあるので Cookie をいくら書いても無関係)。**サーバーの状態を持たない代わりに、この弱点を受け入れている**というトレードオフ。

## 7. このプロジェクトでの実測

設定は 1 行だけ。

```java
// SecurityConfig.java:66
.csrf(csrf -> csrf.spa())
```

`spa()` の中身は 2 行(`ソース確認`: `spring-security-config` 7.1.0 `CsrfConfigurer`)。

```java
public CsrfConfigurer<H> spa() {
    this.csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
    this.requestHandler = new SpaCsrfTokenRequestHandler();
    return this;
}
```

- `CookieCsrfTokenRepository.withHttpOnlyFalse()` … 正解を Cookie に置く(= Double Submit)。`HttpOnly` を外すのは **JavaScript に読ませるため**。「`HttpOnly` を外すのは危険では?」と思うところだが、**このトークンは秘密ではない**。秘密なのはセッション ID の方で、そちらは `HttpOnly` が付いている。
- `SpaCsrfTokenRequestHandler` … 送られてきた値の解釈方法(→ §8)

### トークンはいつ発行されるか

**実測** — 未ログインで `GET /api/auth/me` を叩くと Cookie が返る。

```
$ curl -i http://localhost:8080/api/auth/me
HTTP/1.1 200
Set-Cookie: XSRF-TOKEN=59c3b17b-35d8-4217-a447-0991b001fc01; Path=/
Content-Type: application/json

{"user":null}
```

`Path=/` だけで、`HttpOnly` も `SameSite` も付いていない。

この GET は [plugins/auth.client.ts](../../../frontend/app/plugins/auth.client.ts) がアプリ起動時に必ず 1 回叩くもので、**ログイン状態の復元が主目的、トークンの受け取りが副産物**。この副産物が無いと「ログイン前なのでトークンが無く、ログインできない」という詰みが発生する。[AuthController.java:47](../../../backend/src/main/java/com/example/app/auth/AuthController.java) がそう断り書きしている。

「認可の設定で `permitAll()` にした URL でも `CsrfFilter` は通る」という性質を利用した設計 → [security-filter-chain.md](../java/spring/security-filter-chain.md) の「`permitAll()` は『フィルタを通らない』ではない」。

### 照合されるところ

**実測** — トークン無しの POST は 403。

```
$ curl -i -b cookies.txt -X POST http://localhost:8080/api/auth/login \
    -d 'email=a@example.com&password=xxx'
HTTP/1.1 403
Content-Type: application/json;charset=UTF-8

{"message":"この操作は許可されていません","fieldErrors":null}
```

**実測** — ヘッダを付けると CSRF は通過し、認証の段階(401)まで進む。

```
$ curl -i -b cookies.txt -X POST http://localhost:8080/api/auth/login \
    -H "X-XSRF-TOKEN: 59c3b17b-35d8-4217-a447-0991b001fc01" \
    -d 'email=a@example.com&password=xxx'
HTTP/1.1 401
{"message":"メールアドレスまたはパスワードが違います","fieldErrors":null}
```

**403 → 401 に変わったことが「CSRF を通過した」証拠**。403 は `CsrfFilter` が、401 は認証プロバイダが出している。この 2 つの出どころの違いは [security-filter-chain.md](../java/spring/security-filter-chain.md) の表にある。

### フロント側

[plugins/api.ts:12-22](../../../frontend/app/plugins/api.ts) が全リクエストの前に差し込んでいる。

```ts
onRequest({ options }) {
  const method = String(options.method ?? 'GET').toUpperCase()
  // GET / HEAD は状態を変えないので CSRF トークンは不要(サーバー側も要求しない)
  if (method === 'GET' || method === 'HEAD') return

  const token = readCsrfToken()
  if (!token) return
  const headers = new Headers(options.headers)
  headers.set('X-XSRF-TOKEN', token)
  options.headers = headers
}
```

GET / HEAD を除外しているのは、サーバー側の `CsrfFilter` も同じ判断をしているから(`ソース確認`: `spring-security-web` 7.1.0)。

```java
// org.springframework.security.web.csrf.CsrfFilter.DefaultRequiresCsrfMatcher
private final HashSet<String> allowedMethods = new HashSet<>(Arrays.asList("GET", "HEAD", "TRACE", "OPTIONS"));

@Override
public boolean matches(HttpServletRequest request) {
    return !this.allowedMethods.contains(request.getMethod());
}
```

**この「安全なメソッド」の定義が両側で一致していることが前提**になっていて、GET で状態を変える API を足すと前提が崩れる(→ §4 ①)。

## 8. なぜ `_csrf` パラメータだと通らないのか(ソース確認)

`CsrfFilter` は本来、ヘッダだけでなく **`_csrf` というフォームパラメータ**でもトークンを受け取れる。ところがこのアプリでは通らない。

**実測** — 同じ値をパラメータで送ると 403。

```
$ curl -X POST http://localhost:8080/api/auth/login -b cookies.txt \
    -d 'email=a@example.com&password=xxx&_csrf=59c3b17b-35d8-4217-a447-0991b001fc01'
HTTP/1.1 403
```

ヘッダで送れば通る同じ値が、パラメータでは落ちる。理由は `spa()` が入れた `SpaCsrfTokenRequestHandler` にある(`ソース確認`: `spring-security-config` 7.1.0 `CsrfConfigurer` の入れ子クラス)。

```java
private static final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

    private final CsrfTokenRequestAttributeHandler plain = new CsrfTokenRequestAttributeHandler();
    private final CsrfTokenRequestAttributeHandler xor = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        String headerValue = request.getHeader(csrfToken.getHeaderName());
        return (StringUtils.hasText(headerValue) ? this.plain : this.xor).resolveCsrfTokenValue(request, csrfToken);
    }
}
```

**ヘッダがあれば `plain`(生の値として比較)、無ければ `xor`(XOR でマスクされた値とみなしてデコードしてから比較)** という切り替えになっている。Cookie に入っているのは生の値なので、それをそのまま `_csrf` に入れると XOR デコードを通されて別物になり、一致しない。

### XOR は何のためにあるのか

Spring Security の既定(`spa()` を使わない場合)は `XorCsrfTokenRequestAttributeHandler` で、**画面に埋め込むトークンを毎回違うランダム値でマスクする**。同じセッションでも、リクエストのたびに見た目の異なる文字列になる。

これは **BREACH** という攻撃への対策。HTTPS のレスポンスを圧縮していると、圧縮後のサイズが「同じ文字列が繰り返し出現したか」で変わる。攻撃者がレスポンスに自分の推測文字列を混ぜ込める状況では、**サイズの変化から秘密の値を 1 文字ずつ絞り込める**。マスクを毎回変えれば、同じトークンでもレスポンス上の見た目が毎回変わるので、この手が使えなくなる。

### なぜ SPA だけ生の値でよいのか

**BREACH はレスポンス本文に秘密が現れることを前提にした攻撃**だから。SPA 構成ではトークンは `Set-Cookie` ヘッダで配られ、レスポンス本文には現れない。Cookie を JavaScript が読んでヘッダに載せる経路なので、**マスクを噛ませる場所が無い**(JavaScript 側で XOR エンコードを再実装させることになり、現実的でない)。

だから `spa()` は「ヘッダで来たものは生の値として扱う」という妥協を入れている。`SecurityConfig.java:58-63` のコメント②が指しているのがこれ。

**実務上の結論**: このアプリでは**トークンはヘッダで送る。フォームパラメータでは送れない**。

## 9. ではこのアプリを本当に攻撃できるのか

§1 の教科書的な攻撃を、このアプリに向けて実際に組み立ててみる。

### `DELETE /api/posts/{id}` を狙う → ✗

`<form>` の `method` に指定できるのは **`get` と `post` だけ**。`DELETE` は送れない。`fetch` なら送れるが、`DELETE` はプリフライトが必須のメソッドなので `OPTIONS` で止まる。

### `POST /api/posts` を狙う → ✗

投稿作成は `@RequestBody` で JSON を受ける。§3 で見たとおり `<form>` から `application/json` は送れない。

**実測** — CSRF トークンを付けた上で、JSON API に `form-urlencoded` を送ると 415 で弾かれる。

```
$ curl -i -X POST http://localhost:8080/api/auth/signup -b cookies.txt \
    -H "X-XSRF-TOKEN: 59c3b17b-..." \
    -d 'email=x@example.com&password=xxxx&username=x&displayName=x'
HTTP/1.1 415
Accept: application/json, application/*+json
```

### `fetch` で JSON を送る → ✗

`Content-Type: application/json` はプリフライトの対象。このアプリは CORS を一切設定していないので、`Access-Control-Allow-Origin` が返らずブラウザが本体の送信を中止する。

### `POST /api/auth/login` を狙う → ▲ ここだけ通る

**ログインだけは `form-urlencoded` を受ける**。`formLogin()` が JSON ボディを読めないためで、[ADR-0002](../../adr/0002-session-cookie-over-jwt.md) が「この決定の代償」として明示的に受け入れているもの。

```html
<!-- 罠ページ。CSRF 対策が無ければ通ってしまう形 -->
<form action="https://example.com/api/auth/login" method="post">
  <input type="hidden" name="email" value="attacker@evil.example">
  <input type="hidden" name="password" value="attackers-password">
</form>
```

これは **ログイン CSRF** と呼ばれる攻撃で、被害者を**攻撃者のアカウントにログインさせる**。「ログインさせて何が嬉しいのか」と思うところだが、被害者はログイン済みの画面を見ているので気付きにくく、**そのあと投稿した内容・入力した情報が全部攻撃者のアカウントに溜まる**。

### 結論

**今このアプリで教科書的な CSRF が通らないのは、CSRF トークンだけでなく `SameSite=Lax` と「API が JSON 前提であること」が重なっている結果**。3 つのうち 2 つは CSRF 対策として設計されたものではない。

- `SameSite=Lax` … Spring Session の既定。**設定していないから効いている**という不安定な状態
- JSON 前提 … API 設計の副産物。ログインだけ既に例外がある

**意図して置いた対策は CSRF トークンだけ**で、残り 2 つはたまたま噛み合っている。だから「JSON だから CSRF は要らない」という判断はしてはいけない。

## 10. Laravel との違い

Laravel から来ると、CSRF は「`@csrf` を書く」でほぼ終わる話に見える。仕組みが違う部分を並べる。

| | Laravel | このアプリ |
|---|---|---|
| 方式 | Synchronizer Token | **Double Submit Cookie** |
| 正解の保管場所 | セッション | **Cookie** |
| 画面への埋め込み | `@csrf` が hidden を吐く | **無し**(SSG なのでサーバーが HTML を作らない) |
| 送り方 | `_token` パラメータ / `X-CSRF-TOKEN` / `X-XSRF-TOKEN` | **`X-XSRF-TOKEN` ヘッダのみ** |
| 検査するもの | `VerifyCsrfToken` ミドルウェア | `CsrfFilter` |
| 除外の書き方 | `$except` 配列に URL を書く | `csrf.ignoringRequestMatchers(...)` |

### なぜ Cookie 名が同じなのか

`XSRF-TOKEN` / `X-XSRF-TOKEN` という名前は、Spring と Laravel で偶然一致したわけではない。**AngularJS の `$http` がこの名前で自動送信する実装を持っていたのが広まり、事実上の標準になった**もの。今も axios が `xsrfCookieName: 'XSRF-TOKEN'` を既定に持っている。

Laravel が `VerifyCsrfToken` で `XSRF-TOKEN` Cookie も併せて発行しているのはこのためで、**保管はセッション(Synchronizer)なのに、SPA 向けの受け取り口として Cookie も配る**というハイブリッドになっている。「Cookie がある = Double Submit」ではないので、そこで方式を判定すると読み違える。

### 埋め込み方式が使えない理由

`@csrf` はサーバーが HTML をレンダリングする前提の仕組み。このアプリは `nuxt generate` で静的な HTML を作り置きしてから配るので、**HTML が作られる時点でリクエストも利用者も存在しない**。トークンを埋め込む余地が無い。

Nuxt を SSR で動かしていれば埋め込みもできるが、この構成では選択肢に入らない。**「SSG を選んだ」という決定が、CSRF の方式まで決めている**。

## つまずきポイント

**403 が返ったらまず CSRF を疑う**
ロールを使っていないので、このアプリで 403 が出る実質的な原因は CSRF トークン不一致しかない。401 は「誰か分からない」、403 は「誰かは分かるが許可されていない」。

**トークンはリクエストごとに Cookie から読み直す**
起動時に 1 回読んで変数に持つと、ログイン直後から 403 が出続ける。ログイン成功時とログアウト時にトークンが作り直されるため。

**`_csrf` フォームパラメータでは送れない**
`csrf.spa()` を使っている限り、パラメータ経由は XOR マスク済みの値を要求される。**ヘッダで送ること**(→ §8)。

**`XSRF-TOKEN` Cookie の `HttpOnly` が無いのは意図的**
JavaScript が読めなければ成立しない仕組みなので、外して正しい。秘密を守っているのは `SESSION` Cookie の方で、そちらには `HttpOnly` が付いている。**この 2 つの Cookie は役割が違う**。

**「JSON API だから CSRF 不要」は成り立たない**
成り立つのは `Authorization: Bearer` 方式のとき。Cookie でセッションを持つ構成では、ボディの形式に関係なく CSRF は成立しうる(→ §9 のログイン CSRF)。

**GET で状態を変える API を足すと、`SameSite` の防御が効かなくなる**
`Lax` はトップレベル GET を通す。かつ `CsrfFilter` も GET を照合対象外にしているので、**両方の防御を同時にすり抜ける**。

**サブドメインを他人に渡すと 2 つの対策が同時に無効になる**
`SameSite` は「サイト」単位、Double Submit は Cookie を書ければ破れる。どちらもサブドメインが攻撃者の手に渡ると崩れる(→ §6)。

## 用語集

| 用語 | 一言説明 |
|---|---|
| **CSRF** | ログイン中の利用者のブラウザを使って、意図しないリクエストをサーバーに送らせる攻撃 |
| **CSRF トークン** | 「他サイトからは読めない値」をリクエストに含めさせることで、正規のページ由来かを判定する仕掛け |
| **ambient authority** | ブラウザが持っている資格情報を自動で付けてしまう性質。Cookie・Basic 認証がこれ。Bearer トークンは該当しない |
| **同一オリジンポリシー(SOP)** | 別オリジンのレスポンスや Cookie を JavaScript から読ませないブラウザの規則。CSRF 対策の土台 |
| **オリジン** | スキーム + ホスト + ポートの組。`https://a.example.com` と `https://b.example.com` は別オリジン |
| **サイト(same-site)** | 登録可能ドメイン(eTLD+1)の単位。`a.example.com` と `b.example.com` は**同一サイト**。オリジンより粗い |
| **`SameSite`** | 他サイトから発生したリクエストに Cookie を付けるかを制御する Cookie の属性。`Strict` / `Lax` / `None` |
| **Synchronizer Token Pattern** | 正解をサーバーのセッションに保管する方式。Laravel / Rails の既定 |
| **Double Submit Cookie** | 正解を Cookie に置き、Cookie とヘッダの一致だけを見る方式。サーバーの状態が不要。**このアプリ** |
| **cookie tossing** | サブドメインから親ドメインの Cookie を上書きする攻撃。Double Submit の弱点を突く |
| **プリフライト** | ブラウザが本体の送信前に `OPTIONS` で許可を確認する CORS の手順。カスタムヘッダや JSON の送信で発生する |
| **BREACH** | 圧縮されたレスポンスのサイズ変化から秘密の値を推測する攻撃。XOR マスクはこれへの対策 |
| **多層防御(defense in depth)** | 弱点の異なる対策を重ね、1 つ破られても残りが効く状態にする考え方 |
| **`CsrfFilter`** | 照合を行う Spring Security のフィルタ。GET / HEAD / TRACE / OPTIONS は既定で対象外 |
| **`CookieCsrfTokenRepository`** | トークンを Cookie に保管する実装。`csrf.spa()` が選ぶ |

## 関連

- [security-filter-chain.md](../java/spring/security-filter-chain.md) — `CsrfFilter` がフィルタの列のどこにいるか、403 と 401 の出どころ。**あちらが列全体の地図、こちらがトークン 1 個の一生**
- [session-store-and-other-frameworks.md](../java/spring/session-store-and-other-frameworks.md) — `SESSION` Cookie の中身がどこに保存されるか。守られている側の話
- [ADR-0002 セッション Cookie 方式を選んだ理由](../../adr/0002-session-cookie-over-jwt.md) — **CSRF 対策が必要になった根本の決定**。ログインだけ form-urlencoded になる代償もここ
- [intersection-observer.md](./intersection-observer.md) — 同じ `browser/` の並び。「これはフレームワークの機能ではなくブラウザの仕組み」という同じ視点
