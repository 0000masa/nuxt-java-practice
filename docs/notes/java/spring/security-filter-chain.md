# Spring Security はどう動いているのか — フィルタチェーンと、関係するファイル

`SecurityConfig` と `auth` パッケージを眺めていて湧く疑問 — 「`SecurityConfig` に `AppUserDetailsService` の名前が一度も出てこないのに、なぜ呼ばれるのか?」「`POST /api/auth/login` を受け取るメソッドがどこにも無いのはなぜか?」「`@AuthenticationPrincipal` はどこから principal を持ってくるのか?」「401 と 403 は誰が返しているのか?」 — に答える学習メモ。

**「1 本のリクエストが Controller に届くまでに、どのフィルタを順に通るか」**という 1 本の軸で全体を貫く。ファイルの関係も、この列のどこに差し込まれているか、として位置づける。

対象ファイル: [SecurityConfig.java](../../../../backend/src/main/java/com/example/app/config/SecurityConfig.java) / [AppUserDetailsService.java](../../../../backend/src/main/java/com/example/app/auth/AppUserDetailsService.java) / [AppUserDetails.java](../../../../backend/src/main/java/com/example/app/auth/AppUserDetails.java) / [AuthResponseWriter.java](../../../../backend/src/main/java/com/example/app/auth/AuthResponseWriter.java) / [UserSessionManager.java](../../../../backend/src/main/java/com/example/app/auth/UserSessionManager.java) / [AuthController.java](../../../../backend/src/main/java/com/example/app/auth/AuthController.java)

> **このメモの検証状況**
> このプロジェクトのコード(`SecurityConfig` と `auth` パッケージ)は**実際に読んで書いた**。行番号もその時点の実物。
> 一方、**フィルタの並び順・各フィルタの内部動作は Spring Security の公式ドキュメントに基づく一般論で、このプロジェクトで実行して確かめてはいない**。ただし 1 か所、`@AuthenticationPrincipal` が `null` になる仕組みだけは、このプロジェクトが実際に使っている jar のソース(`spring-security-web` 7.1.0 の `-sources.jar`)を読んで確認した。該当箇所には「**ソース確認**」と記した。このプロジェクトは Spring Boot 4.x / Spring Security 7 系(`csrf.spa()` は 7 で入った API)で、フィルタの顔ぶれや順序は**バージョンで変わり得る**。だからこのメモは並び順そのものより「**どの段階で何が起きるか**」を主役にしている。
> 実物を見たくなったら [application.yml](../../../../backend/src/main/resources/application.yml) に次を足して `docker compose restart backend` すると、起動ログに実際の列が出る(確認したら戻すこと)。
> ```yaml
> logging:
>   level:
>     org.springframework.security: DEBUG
> ```
> **セッションを「どこに置くか」**はこのメモの担当ではない → [session-store-and-other-frameworks.md](./session-store-and-other-frameworks.md)。あちらが保管層(Spring Session)、こちらが認証層(Spring Security)で、**2 本で 1 対**になっている。

## まず結論(3 行)

1. **Spring Security の実体は「Controller の手前に一列に並んだフィルタ」**。認証も認可も CSRF もログインもログアウトも、Controller に届く前のこの列の中で終わっている。だから「ログインの Controller メソッド」は存在しない。
2. **`SecurityConfig` は動作ではなく「列の組み立て指示書」**。あのメソッドは**起動時に 1 回だけ**実行され、リクエストのたびに動くわけではない。読むときは「処理」ではなく「設定の宣言」として読む。
3. **自前で書いたコードは、列の 4 か所に差し込まれているだけ**。`AppUserDetailsService`(ユーザーを引く)・`AppUserDetails`(principal の中身)・`AuthResponseWriter`(レスポンスの形)・`PasswordEncoder`(ハッシュの道具)の 4 つ。それ以外の `AuthService` や `AuthTokenService` は Spring Security の管轄外で、**普通の Controller / Service として動いている**。

## 実体はフィルタの列

### サーブレットフィルタとは

**サーブレットフィルタ**は Java の Web の土台(サーブレット仕様)が定めている仕組みで、「リクエストが目的地に届く前と、レスポンスが返る途中に、横から割り込める関門」のこと。空港の保安検査に似ていて、搭乗口(Controller)に着く前に全員が必ず通る。

契約は極めて単純で、次の 1 メソッドだけ。

```java
void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
    // 前処理(通すか、書き換えるか、ここで打ち切るか を決める)
    chain.doFilter(request, response);   // ← 次のフィルタへ進める
    // 後処理
}
```

重要なのは **`chain.doFilter()` を呼ばなければ、そこで打ち切りになる**こと。Spring Security がログインリクエストを「横取り」できるのはこの性質による。呼ばずに自分でレスポンスを書けば、後ろのフィルタにも Controller にも到達しない。

### 三段構えになっている

Tomcat に登録されているフィルタは、実は **`springSecurityFilterChain` という名前の 1 個だけ**。その中で入れ子になっている。

```
Tomcat のフィルタ登録
  └ DelegatingFilterProxy ("springSecurityFilterChain")
       └ FilterChainProxy                     ← Spring 側の司令塔
            ├ SecurityFilterChain #1          ← SecurityConfig.filterChain() が返した 1 本
            │    └ [ フィルタ, フィルタ, フィルタ, ... ]  ← 実際に働く列
            └ SecurityFilterChain #2 …        ← 複数定義することもできる(このプロジェクトは 1 本)
```

- **`DelegatingFilterProxy`** … Tomcat と Spring の橋渡し。Tomcat は Spring の Bean を知らないので、間に立って「Spring が管理しているフィルタ」へ処理を委ねる。
- **`FilterChainProxy`** … 複数の `SecurityFilterChain` を持ち、リクエストの URL を見て**どの列に流すか**を選ぶ。
- **`SecurityFilterChain`** … 実際のフィルタの並び。[SecurityConfig.java:87](../../../../backend/src/main/java/com/example/app/config/SecurityConfig.java) の `return http.build();` が返しているのがこれ。

この三段構えを知らないと、`SecurityConfig` の戻り値の型 `SecurityFilterChain` が何なのか分からないまま読むことになる。**あのメソッドは「列を 1 本組み立てて Spring に渡す」ためのもの**、と分かれば読み方が変わる。

### なぜ認可を Controller でやらないのか

「このエンドポイントはログイン必須」という判定を各 Controller メソッドの先頭に書くこともできる。それをせず手前の関門に集約している理由は、**書き忘れが即・脆弱性になる**から。

[SecurityConfig.java:47-50](../../../../backend/src/main/java/com/example/app/config/SecurityConfig.java) のコメントがそれを言っている。

```java
// 上で公開扱いにしなかった API は認証必須。ここが既定拒否になっているので、
// 新しいエンドポイントを足したときに「うっかり公開」にはならない。
.requestMatchers("/api/**").authenticated()
```

Controller に書く方式だと「書き忘れ = 公開」になる。関門方式だと「書き忘れ = 拒否」になる。**間違えたときにどちら側に転ぶか**が決定的に違う。

### このプロジェクトで効いている 7 つ

15 個前後が並ぶが、`SecurityConfig` の設定や自前ファイルと 1 対 1 で結びつくのは次の 7 つ(+ Spring Session の 1 つ)。以降はこの 7 つだけを追う。

| # | フィルタ | 何をする | 繋がっているもの |
|---|---|---|---|
| **0** | `SessionRepositoryFilter` | リクエストを包み直し、セッションを MySQL から引く | Spring **Session** 側 → [別ノート](./session-store-and-other-frameworks.md) |
| **1** | `SecurityContextHolderFilter` | セッションから principal を復元して置く | **`@AuthenticationPrincipal` の供給源** |
| **2** | `CsrfFilter` | トークンを照合し、XSRF-TOKEN Cookie を発行 | `SecurityConfig.java:64` `csrf.spa()` |
| **3** | `LogoutFilter` | `/api/auth/logout` を横取り | `SecurityConfig.java:78-80` / `AuthResponseWriter` |
| **4** | `UsernamePasswordAuthenticationFilter` | `/api/auth/login` を横取り | `SecurityConfig.java:69-74` → `AppUserDetailsService` → `AppUserDetails` |
| **5** | `AnonymousAuthenticationFilter` | 未ログインなら principal を `"anonymousUser"` にする | **`AppUserDetails.java:16-17` の「null が入る」理由** |
| **6** | `ExceptionTranslationFilter` | 認証/認可の例外を捕まえてハンドラへ回す | `SecurityConfig.java:83-85` / `AuthResponseWriter` |
| **7** | `AuthorizationFilter` | URL とルールを突き合わせて通す/弾く | `SecurityConfig.java:34-52` |

残りは名前だけ挙げておく。今回の話には絡まない。

| フィルタ | ざっくり何のため |
|---|---|
| `DisableEncodeUrlFilter` | セッション ID を URL に埋め込む古い方式を無効化する |
| `WebAsyncManagerIntegrationFilter` | 非同期処理へ principal を引き継ぐ |
| `HeaderWriterFilter` | `X-Frame-Options` などのセキュリティヘッダを付ける |
| `RequestCacheAwareFilter` | ログイン前に弾いたリクエストを覚えておく(HTML アプリ向け) |
| `SecurityContextHolderAwareRequestFilter` | `request.isUserInRole()` など古い API を使えるようにする |
| `SessionManagementFilter` 系 | セッション固定化対策・同時ログイン数制御 |

## 【図①】ログイン時に通る道

`AppUserDetailsService` が動くのは**この場面だけ**。★印が自前のコード。

```
POST /api/auth/login   (Content-Type: application/x-www-form-urlencoded)
                       email=a@example.com&password=xxxx
  │
  ▼
┌──────────────────────────────────────────────────────────────┐
│ SessionRepositoryFilter            Spring Session の担当       │
│   Cookie の SESSION を見て MySQL からセッションを引く            │
│   → ログイン前なので中身は空(または Cookie 自体まだ無い)         │
└──────────────────────────────────────────────────────────────┘
  ▼
┌──────────────────────────────────────────────────────────────┐
│ SecurityContextHolderFilter                                   │
│   セッションから SecurityContext を取り出して置く → 空っぽ       │
└──────────────────────────────────────────────────────────────┘
  ▼
┌──────────────────────────────────────────────────────────────┐
│ CsrfFilter                    ← SecurityConfig.java:64        │
│   X-XSRF-TOKEN ヘッダと XSRF-TOKEN Cookie を照合                │
│   合わなければ ここで 403(→ ExceptionTranslationFilter へ)      │
└──────────────────────────────────────────────────────────────┘
  ▼
┌──────────────────────────────────────────────────────────────┐
│ UsernamePasswordAuthenticationFilter                          │
│   ← SecurityConfig.java:70 loginProcessingUrl と URL が一致!   │
│   ここで横取りする。以降のフィルタにも Controller にも進まない    │
│      │                                                        │
│      ├─ email / password をフォームから取り出す                 │
│      │    ← :71-72 usernameParameter / passwordParameter       │
│      │                                                        │
│      ├─ ProviderManager                                       │
│      │   └─ DaoAuthenticationProvider                         │
│      │        ①ユーザーを引く                                  │
│      │          AppUserDetailsService                      ★  │
│      │            .loadUserByUsername("a@example.com")         │
│      │              └─ UserRepository.findByEmail()            │
│      │                   → AppUserDetails を返す            ★  │
│      │        ②有効性の検査  isEnabled() が false             │
│      │              → DisabledException(メール未確認)          │
│      │        ③パスワード照合                                  │
│      │          PasswordEncoder.matches(入力, getPassword())   │
│      │              → 不一致なら BadCredentialsException       │
│      │                                                        │
│      ├─ 成功: セッション ID を再発行(セッション固定化攻撃対策)    │
│      ├─ 成功: eraseCredentials() でハッシュを消す           ★  │
│      ├─ 成功: SecurityContext に格納 → セッションへ保存         │
│      │        (= MySQL の SPRING_SESSION_ATTRIBUTES に書かれる) │
│      │                                                        │
│      ├─ 成功 → AuthResponseWriter.onLoginSuccess           ★  │
│      │           200 + 現在のユーザー JSON                     │
│      └─ 失敗 → AuthResponseWriter.onLoginFailure           ★  │
│                  401 + エラーメッセージ                        │
└──────────────────────────────────────────────────────────────┘
  ▼
Controller には到達しない(対応するメソッドがそもそも存在しない)
```

### ここで読み取ってほしい 3 点

**① 検査の順番が「ユーザーを引く → 有効性 → パスワード」である**

パスワード照合は最後。だから**メール未確認のユーザーは、パスワードを間違えていても「メール未確認」のエラーになる**。[AuthResponseWriter.java:58-60](../../../../backend/src/main/java/com/example/app/auth/AuthResponseWriter.java) がこれを明記している。

```java
 * <p>なお DisabledException はパスワードの照合より前に投げられる(AbstractUserDetails
 * AuthenticationProvider が先に有効性を検査する)ため、パスワードが間違っていても
 * このメッセージになる。メールアドレスの登録有無が分かる形だが、これは決定8 で許容している。
```

「メールアドレスの登録有無が分かる形」= アカウント列挙の余地があるということ。それを承知で受け入れた判断は [ADR-0003](../../../adr/0003-account-enumeration-and-unverified-signup.md) にある。**仕組みを知っていないと、この判断の意味が読み取れない**のが分かりやすい例。

**② パスワードの照合は `AppUserDetailsService` の仕事ではない**

自前コードは「ユーザーを 1 件返す」だけ。合っている/間違っているの判定はフレームワーク側。[AppUserDetailsService.java:15-17](../../../../backend/src/main/java/com/example/app/auth/AppUserDetailsService.java) の Javadoc がそう宣言している。ここで自分で `matches()` を書いてしまうのがよくある誤りで、タイミング攻撃対策などフレームワークが用意した仕掛けを取りこぼすことになる。

**③ ここで作られた `AppUserDetails` が、そのままセッションに保存される**

この一点が、次の図②の全ての前提になる。

## 【図②】2 回目以降のリクエストが通る道

図①との**差分**が主役。`AppUserDetailsService` の箱が消えているのが最大の違い。

```
PUT /api/auth/password   Cookie: SESSION=...; XSRF-TOKEN=...
  │
  ▼
┌──────────────────────────────────────────────────────────────┐
│ SessionRepositoryFilter                                       │
│   Cookie の SESSION → MySQL からセッションを 1 回だけ SELECT    │
│   (以降このリクエスト内ではキャッシュ                            │
│    → AuthController.java:110-113 のコメントの根拠)             │
└──────────────────────────────────────────────────────────────┘
  ▼
┌──────────────────────────────────────────────────────────────┐
│ SecurityContextHolderFilter          ★ここが principal の源泉  │
│   セッションから SecurityContext を復元                         │
│   → 中の principal は、ログイン時に作られた AppUserDetails       │
│   ※ DB の users は読まない。AppUserDetailsService も動かない    │
└──────────────────────────────────────────────────────────────┘
  ▼
┌──────────────────────────────────────────────────────────────┐
│ CsrfFilter                                                    │
│   GET/HEAD 以外なので照合する。合わなければ 403                  │
└──────────────────────────────────────────────────────────────┘
  ▼
┌──────────────────────────────────────────────────────────────┐
│ LogoutFilter / UsernamePasswordAuthenticationFilter           │
│   URL が一致しないので、何もせず素通り                           │
└──────────────────────────────────────────────────────────────┘
  ▼
┌──────────────────────────────────────────────────────────────┐
│ AnonymousAuthenticationFilter                                 │
│   SecurityContext が空のときだけ、匿名の Authentication を入れる │
│   (principal = "anonymousUser" という ただの String)           │
│   → 今回は復元済みなので何もしない                              │
└──────────────────────────────────────────────────────────────┘
  ▼
┌──────────────────────────────────────────────────────────────┐
│ ExceptionTranslationFilter    ← SecurityConfig.java:83-85     │
│   ここから先で投げられた認証/認可の例外を待ち受ける              │
│     未認証        → onUnauthenticated  401                 ★ │
│     認可されない  → onAccessDenied     403                 ★ │
└──────────────────────────────────────────────────────────────┘
  ▼
┌──────────────────────────────────────────────────────────────┐
│ AuthorizationFilter           ← SecurityConfig.java:34-52     │
│   ルールを上から評価。PUT /api/auth/password は公開列挙に無い    │
│   → :50 の .requestMatchers("/api/**").authenticated() に一致  │
│   → 認証済みなので通す                                         │
└──────────────────────────────────────────────────────────────┘
  ▼   ここから先が「Spring MVC の世界」
┌──────────────────────────────────────────────────────────────┐
│ DispatcherServlet → AuthController.changePassword         ★  │
│   @AuthenticationPrincipal AppUserDetails principal            │
│     ← SecurityContext から principal を取り出して引数に入れる   │
│   ここで落ちた例外は GlobalExceptionHandler が受ける         ★  │
└──────────────────────────────────────────────────────────────┘
```

### `@AuthenticationPrincipal` が null になる仕組み

[AppUserDetails.java:16-17](../../../../backend/src/main/java/com/example/app/auth/AppUserDetails.java) にこう書いてある。

```java
 * <p>未ログインのリクエストでは principal が {@code "anonymousUser"} という文字列になるため、
 * 型を指定した引数には {@code null} が入る(公開エンドポイントではこの null を前提にする)。
```

**なぜそうなるか**が図②の `AnonymousAuthenticationFilter` にある。Spring Security は「未ログイン = Authentication が無い」という状態を作らない。**必ず何かを入れる**。未ログインなら `AnonymousAuthenticationToken` を入れ、その principal は `"anonymousUser"` という**ただの文字列**になる。

だから `GET /api/auth/me` を未ログインで叩くと、こうなる。

```
SecurityContext の principal = "anonymousUser" (String 型)
        ↓
AuthenticationPrincipalArgumentResolver が引数を埋めようとする
        ↓  String は AppUserDetails ではない = 型が合わない
        ↓  → 例外にせず null を返す(既定)
@AuthenticationPrincipal AppUserDetails principal = null
```

#### この null は Java の挙動ではない(ソース確認)

**Java の言語仕様なら `ClassCastException` で落ちる**。型の合わない参照をキャストしたときに黙って `null` になる、という挙動は Java には無い。

`null` を返しているのは **`AuthenticationPrincipalArgumentResolver`** という Spring Security のクラス。Controller メソッドの引数を埋める役(Spring MVC の `HandlerMethodArgumentResolver`。`@RequestBody` や `@PathVariable` を解決しているのと同じ仕組み)で、**代入する前に自分で型を検査して、合わなければ `null` を返す**と明示的に書いてある。

```java
// spring-security-web 7.1.0
// org.springframework.security.web.method.annotation
//   .AuthenticationPrincipalArgumentResolver#resolveArgument (sources jar を読んで確認)
Authentication authentication = this.securityContextHolderStrategy.getContext().getAuthentication();
if (authentication == null) {
    return null;                       // ← そもそも Authentication が無いときも null
}
Object principal = authentication.getPrincipal();
// …(annotation の expression 属性の処理は省略)…
if (principal != null && !ClassUtils.isAssignable(parameter.getParameterType(), principal.getClass())) {
    if (annotation.errorOnInvalidType()) {
        throw new ClassCastException(principal + " is not assignable to " + parameter.getParameterType());
    }
    return null;                       // ← ここ。型が合わないので null を返している
}
return principal;
```

読み取れることが 3 つある。

1. **キャストしていない。** `ClassUtils.isAssignable(...)` で「代入できるか」を**先に問い合わせている**。だから例外は発生しない。Java の `instanceof` を使った安全なキャストと同じ発想。
2. **「黙って null」は既定であって、仕様ではない。** `errorOnInvalidType()` の既定値は `false`(`AuthenticationPrincipal.java` で確認)。`@AuthenticationPrincipal(errorOnInvalidType = true)` と書けば、同じ状況で `ClassCastException` が飛ぶようになる。**選べる**ということ。
3. **null になる経路は 2 つある。** 「`Authentication` 自体が無い」と「型が合わない」。このアプリで実際に通るのは後者(`AnonymousAuthenticationFilter` が必ず何か入れるため)。

[AuthController.java:52-53](../../../../backend/src/main/java/com/example/app/auth/AuthController.java) の `principal == null ? null : principal.getUserId()` は、この「既定では黙って null」に乗った書き方。**公開エンドポイントで意図的に null を受け入れている**ので既定のままでよいが、認証必須のはずの場所で null が来たら設定ミスなので、そこは `errorOnInvalidType = true` にして早く気付く、という使い分けもできる。

**「未ログインなら Authentication が null」と思い込むと読み違える**ところ。`SecurityContextHolder.getContext().getAuthentication()` を直接見ると、未ログインでも null ではなく匿名トークンが返ってくる。

### `AppUserDetails` に値を持たせない理由が繋がる

図②に「DB の users は読まない」と書いた。ログイン後のリクエストで principal はセッションから復元されるだけで、**`users` テーブルは見に行かない**。

だから [AppUserDetails.java:19-21](../../../../backend/src/main/java/com/example/app/auth/AppUserDetails.java) の判断になる。

```java
 * <p><b>保持する値を最小限にしている理由</b>: このオブジェクトはシリアライズされて
 * SPRING_SESSION_ATTRIBUTES に保存される。表示名や bio まで持たせると、プロフィール編集後も
 * セッションの中身が古い値のまま残ってしまう。あとから変わる値は都度 DB から読む。
```

principal に表示名を入れると、プロフィールを編集しても**再ログインするまで古い名前が出続ける**。`userId` だけ持たせて毎回 DB から引く方式なら、その事故が起きない。

### 401 と 403 の出どころ、そして 2 つの例外ハンドラの境界

図②で `ExceptionTranslationFilter` を `AuthorizationFilter` の**手前**に描いたのが要点。後ろのフィルタや Controller で投げられた例外が、戻り道でここに捕まる。

| 状況 | 誰が投げる | 誰が受ける | 返るもの |
|---|---|---|---|
| 未ログインで認証必須 URL | `AuthorizationFilter` | `AuthResponseWriter.onUnauthenticated` | 401「ログインが必要です」 |
| CSRF トークン不一致 | `CsrfFilter` | `AuthResponseWriter.onAccessDenied` | 403「この操作は許可されていません」 |
| ログイン失敗 | 認証プロバイダ | `AuthResponseWriter.onLoginFailure` | 401 |
| バリデーションエラー・業務エラー | Controller / Service | `GlobalExceptionHandler` | 400 など |

**`AuthResponseWriter` と `GlobalExceptionHandler` は、担当する「場所」で分かれている**。前者はフィルタ段階、後者は Controller に到達した後。[AuthResponseWriter.java:30-32](../../../../backend/src/main/java/com/example/app/auth/AuthResponseWriter.java) がそう説明している。

```java
 * <p>返す JSON の形はアプリ全体で 1 つ({@link ErrorResponse})に揃える。
 * 例外 → HTTP の変換を集約するという GlobalExceptionHandler と同じ考え方だが、
 * こちらは Controller に到達する前のフィルタ段階で起きる事象を扱うため別の場所になる。
```

`@RestControllerAdvice`(= `GlobalExceptionHandler`)は Spring MVC の仕組みなので、**フィルタ段階の例外には手が届かない**。同じことをやっているのに 2 か所ある理由はこれ。

## ログアウト時

短い。`LogoutFilter` が `/api/auth/logout` を横取りして終わる。

```
POST /api/auth/logout
  │
  ▼  SessionRepositoryFilter → SecurityContextHolderFilter
  ▼  CsrfFilter    ← ここを通るので、ログアウトにも CSRF トークンが要る
  ▼
┌──────────────────────────────────────────────────────────────┐
│ LogoutFilter            ← SecurityConfig.java:78-80           │
│   ・SecurityContext を空にする                                 │
│   ・セッションを無効化(MySQL の SPRING_SESSION から消える)      │
│   ・SESSION Cookie と XSRF-TOKEN Cookie を削除                 │
│   └─ AuthResponseWriter.onLogoutSuccess → 204            ★   │
└──────────────────────────────────────────────────────────────┘
```

[SecurityConfig.java:77](../../../../backend/src/main/java/com/example/app/config/SecurityConfig.java) の「CSRF が有効なので POST のみ受け付ける」はこの図のとおり。GET でログアウトできると、`<img src="/api/auth/logout">` を踏ませるだけで他人をログアウトさせられてしまう。

## `SecurityConfig` の 5 ブロックが、列のどこに化けるか

[SecurityConfig.java](../../../../backend/src/main/java/com/example/app/config/SecurityConfig.java) の `filterChain()` は**起動時に 1 回だけ**実行される。中の `.formLogin(...)` などは「その場で何かする」のではなく、**列に何を入れるか・どう設定するかを登録している**だけ。

| ブロック | 行 | 何に化けるか |
|---|---|---|
| `authorizeHttpRequests` | 34-52 | `AuthorizationFilter` が参照するルール表 |
| `csrf(csrf -> csrf.spa())` | 64 | `CsrfFilter` の設定(トークンの保管方法・レスポンスの返し方) |
| `formLogin` | 69-74 | `UsernamePasswordAuthenticationFilter` を列に**追加**し、URL とハンドラを設定 |
| `logout` | 78-80 | `LogoutFilter` の URL とハンドラを設定 |
| `exceptionHandling` | 83-85 | `ExceptionTranslationFilter` の 2 つのハンドラを差し替え |
| `passwordEncoder()` Bean | 98-101 | フィルタではない。認証プロバイダが**道具として使う** |

**ここが Spring Security 最大の読みにくさ**だと思う。`SecurityConfig` を上から下に読んでも「処理の順番」ではない。書いた順とフィルタの並び順も一致しない(`formLogin` を先に書いても `CsrfFilter` の方が手前に来る)。**順序はフレームワークが決めていて、設定はどこに何を差すかの指定でしかない**。

例外は `authorizeHttpRequests` の**中身**で、こちらは書いた順がそのまま評価順になる([SecurityConfig.java:31-33](../../../../backend/src/main/java/com/example/app/config/SecurityConfig.java) の注意書き)。**「ブロックの順序は無関係、ブロック内の順序は重大」**という二重構造になっている。

## 【図③】ファイル関係図 — どこに差し込まれているか

```
[ フレームワーク側 ]                          [ このプロジェクトのファイル ]

FilterChainProxy の組み立て  ←─ 起動時に読む ── config/SecurityConfig.java
                                                    │
                                                    │ ここで指定した内容が
                                                    │ 下の 3 つの差し込み口を埋める
                                                    ▼
─────────────────────────────────────────────────────────────────────
差し込み口①  UserDetailsService(型で自動発見)
    DaoAuthenticationProvider が探す ────────> auth/AppUserDetailsService.java
                                                  ・ログイン時に 1 回だけ呼ばれる
                                                  ・UserRepository で users を引く
    戻り値の UserDetails            <─────────  auth/AppUserDetails.java
                                                  ・principal の中身
                                                  ・セッションへシリアライズされる
─────────────────────────────────────────────────────────────────────
差し込み口②  5 つのハンドラ(SecurityConfig からメソッド参照で渡す)
    AuthenticationSuccessHandler   ──────────> AuthResponseWriter.onLoginSuccess
    AuthenticationFailureHandler   ──────────> AuthResponseWriter.onLoginFailure
    LogoutSuccessHandler           ──────────> AuthResponseWriter.onLogoutSuccess
    AuthenticationEntryPoint       ──────────> AuthResponseWriter.onUnauthenticated
    AccessDeniedHandler            ──────────> AuthResponseWriter.onAccessDenied
─────────────────────────────────────────────────────────────────────
差し込み口③  PasswordEncoder(Bean を型で解決)
    認証プロバイダ / AuthService  <───────────  SecurityConfig.passwordEncoder()
─────────────────────────────────────────────────────────────────────
[ Spring Session 側 ]
    FindByIndexNameSessionRepository <─ 自分から呼ぶ ─ auth/UserSessionManager.java
        ※ここだけ向きが逆。フィルタから呼ばれるのではなく、
          AuthService が「他端末を強制ログアウトしたい」ときに自分で呼ぶ
─────────────────────────────────────────────────────────────────────
[ Spring Security の管轄外 = 普通の Controller / Service として動く ]
    auth/AuthController.java     …… ただし @AuthenticationPrincipal で principal を受け取る
    auth/AuthService.java
    auth/AuthToken.java / AuthTokenService.java / AuthTokenPurpose.java
    auth/AuthMailSender.java / AuthMailRequestedEvent.java
    auth/dto/*.java
```

**向きを見るのがコツ**。①②③は「フレームワークが自前コードを呼ぶ」向き(制御の反転)で、`UserSessionManager` だけが「自前コードがフレームワークを呼ぶ」向き。前者は**いつ呼ばれるかを自分で決められない**ので、呼ばれるタイミングを図①②で把握しておく必要がある。

## 設定に名前が無いのに繋がる理由

`SecurityConfig` には `AppUserDetailsService` という文字列が 1 度も出てこない。それでも繋がるのは 3 つの仕組みが重なっているため。

**① `@Service` が付いているので Bean になる**
Spring は起動時にパッケージを走査し、`@Service` / `@Component` / `@RestController` などが付いたクラスの実体を 1 個ずつ作って管理する。この管理された実体を **Bean** と呼ぶ。

**② 探すときは「名前」ではなく「型」で探す**
`DaoAuthenticationProvider` は「`UserDetailsService` という型の Bean はあるか」と尋ねる。`AppUserDetailsService` は `implements UserDetailsService` しているので、この問いに一致する。**Java の DI は型が契約**で、名前は使わない。

```
@Service         → Bean 一覧に入る
implements UserDetailsService → 「UserDetailsService 型」として一覧に載る
                              → 型で探しに来たフレームワークに見つかる
```

**③ Spring Boot の自動設定が「無ければ既定、あれば譲る」**
`spring-boot-starter-security` を入れると([build.gradle:25](../../../../backend/build.gradle))、自動設定が働いて既定のセキュリティ設定が有効になる。このとき「`UserDetailsService` の Bean が**無ければ**ランダムパスワードのインメモリユーザーを 1 人作る、**あれば**そちらを使う」という条件付きの振る舞いをする。起動ログに `Using generated security password: ...` が出るのはこの既定側で、自前の `UserDetailsService` を置くと出なくなる。

**この仕組みの副作用**として、同じ型の Bean を 2 つ作ると起動時に「どちらを使えばいいか分からない」というエラーで落ちる。逆に言えば、**黙って片方が無視されることはない**ので、そこは安全側に倒れている。

## Spring Security がやること / やらないこと

Laravel Breeze や Better Auth のような**全部入りの認証パッケージを想像していると必ずずれる**ところ。このプロジェクトの実装と並べる。

| | 担当 | このプロジェクトでの実装 |
|---|---|---|
| ログイン | **Spring Security** | `formLogin`(Controller メソッド無し) |
| ログアウト | **Spring Security** | `logout`(Controller メソッド無し) |
| 認可(誰がどの URL を叩けるか) | **Spring Security** | `authorizeHttpRequests` |
| CSRF 対策 | **Spring Security** | `csrf.spa()` |
| セッションへの principal 保存 | **Spring Security** + Spring Session | 自動 |
| パスワードのハッシュ化 | **Spring Security**(道具だけ提供) | `PasswordEncoder` Bean |
| 会員登録 | **自前** | `AuthService.signup` |
| メール確認 | **自前** | `AuthTokenService` + `AuthMailSender` |
| 確認メールの再送 | **自前** | `AuthService.resendVerification` |
| パスワードリセット | **自前** | `AuthService.requestPasswordReset` / `confirmPasswordReset` |
| パスワード変更 | **自前** | `AuthController.changePassword` → `AuthService` |
| 他端末の強制ログアウト | **自前**(Spring Session の API を使う) | `UserSessionManager` |
| ログイン画面の HTML | **自前**(Nuxt 側) | SSG されたフロント |

「Spring Security を入れたのに `signup` が無い」のではなく、**最初からその守備範囲ではない**。Spring Security は「**認証の枠組み**」であって「**認証機能一式**」ではない。

なお Google ログイン(フェーズ4)は `oauth2Login()` として**枠組み側に入る** — [SecurityConfig.java:20](../../../../backend/src/main/java/com/example/app/config/SecurityConfig.java) に予告がある。OAuth はプロトコルが標準化されているので枠組みに含められる。メール確認はアプリごとに文面も期限もフローも違うので含められない、という線引き。

## Laravel 経験者がつまずく 3 点

### ① ログインの Controller が存在しない

Laravel なら `routes/web.php` に `POST /login` を書き、`AuthenticatedSessionController@store` が動く。**ルートとコントローラを目で追える**。

Spring Security では `POST /api/auth/login` に対応するメソッドが**どこにも無い**。あるのは [SecurityConfig.java:70](../../../../backend/src/main/java/com/example/app/config/SecurityConfig.java) の 1 行だけ。

```java
.loginProcessingUrl("/api/auth/login")
```

これは「この URL はフィルタが横取りする」という**宣言**であって、ルート定義ではない。[AuthController.java:28-30](../../../../backend/src/main/java/com/example/app/auth/AuthController.java) がわざわざこれを断り書きしている。

```java
 * <p>ログイン({@code POST /api/auth/login})とログアウト({@code POST /api/auth/logout})は
 * このクラスに無い。Spring Security の formLogin / logout がフィルタとして処理するため、
 * 対応する Controller メソッドが存在しない。
```

**「grep しても見つからない処理がある」**のが Spring Security を読むときの最初の壁。見つからないときは `SecurityConfig` を疑う。

副作用として、ログインだけ **リクエストボディが JSON ではなく form-urlencoded** になる([SecurityConfig.java:68](../../../../backend/src/main/java/com/example/app/config/SecurityConfig.java) が「この方式の代償」と書いている)。他の API は全部 `@RequestBody` で JSON を受けるのに、ログインだけ形式が違う。標準の枠組みに乗った対価。

### ② ミドルウェアとフィルタは、登録の向きが逆

| | Laravel | Spring Security |
|---|---|---|
| 割り込む仕組み | ミドルウェア | サーブレットフィルタ |
| 誰が「適用する」と決めるか | **ルート側**が名指し(`Route::middleware('auth')`) | **`SecurityConfig` 側**が URL パターンを列挙 |
| 適用範囲 | 指定したルートだけ | **全リクエストが必ず列を通る** |
| 書き忘れたら | そのルートは**素通り**(= 公開) | 既定拒否に落ちる(= **拒否**) |
| 判定を書く場所 | ルート定義に分散 | `SecurityConfig` に集中 |

「全リクエストが必ず通る」ので、`permitAll()` は**フィルタを通らない**という意味ではない。**通った上で通過を許す**という意味。だから `GET /api/auth/me` も `CsrfFilter` を通り、そこで XSRF-TOKEN Cookie が発行される — [AuthController.java:46-47](../../../../backend/src/main/java/com/example/app/auth/AuthController.java) の「ログイン前なので CSRF トークンが無くログインできない問題もここで解消される」はこの性質を利用した設計。

### ③ 全部入りパッケージではない

Laravel Breeze / Jetstream / Fortify は、コマンド 1 発で会員登録・メール確認・パスワードリセット・二段階認証の**画面とルートとロジックが生成される**。Spring Security にそれに相当するものは無い。前掲の「やること / やらないこと」表の下半分は、全部自分で書くことになる。

**どちらが良いという話ではない**。生成されたコードを読み解いて直す(Laravel)か、枠組みだけ借りて中身は自分で書く(Spring)か、という違い。学習用としては後者の方が「何が起きているか」は見えやすい。

## つまずきポイント

**`authorizeHttpRequests` は上から順、最初の一致が勝つ**
[SecurityConfig.java:31-33](../../../../backend/src/main/java/com/example/app/config/SecurityConfig.java) が警告しているとおり、`.requestMatchers("/api/**").authenticated()` を先頭に書くと、後ろの `permitAll()` は**一切効かない**。逆に `anyRequest().permitAll()` を先頭に書くと全公開になる。**広いパターンほど下に置く**。

**`permitAll()` は「フィルタを通らない」ではない**
認可の判定を通過させるだけで、CSRF もセッション読み込みも普通に走る。「公開エンドポイントなのに 403 が返る」ときは、たいてい CSRF。

**401 と 403 の出どころが違う**
401 は「あなたが誰か分からない」(`AuthenticationEntryPoint`)、403 は「誰かは分かるが許可されていない」(`AccessDeniedHandler`)。このアプリでロールを使っていないので、[AuthResponseWriter.java:87](../../../../backend/src/main/java/com/example/app/auth/AuthResponseWriter.java) のコメントどおり **403 の実質的な原因はほぼ CSRF**。403 が出たら CSRF を疑う。

**`SecurityConfig` に書いた順序 ≠ フィルタの順序**
`formLogin` を `csrf` より先に書いても、`CsrfFilter` の方が手前に来る。順序はフレームワークが決めている。**ブロックの並べ替えで挙動は変わらない**(`authorizeHttpRequests` の中身は別)。

**`@AuthenticationPrincipal` は未ログインで null、`getAuthentication()` は未ログインで非 null**
前者は型が合わないので null(Java のキャストの挙動ではなく `AuthenticationPrincipalArgumentResolver` が明示的に返している → 本文)、後者は匿名トークンが入っている。「認証済みか」を判定したいときに `getAuthentication() != null` と書くと**未ログインでも true になる**。

**ログイン中にパスワードを変えても、他端末は自動では切れない**
principal はセッションに保存済みで、`users` テーブルは毎回見に行かないため。だから明示的に消す `UserSessionManager` が要る。[UserSessionManager.java:10-15](../../../../backend/src/main/java/com/example/app/auth/UserSessionManager.java) がその役。

**Java ファイルを直したらコンパイルが要る**
`docker compose exec backend sh ./gradlew classes`。`SecurityConfig` を直したのに反映されないときはこれ(→ CLAUDE.md)。

## 用語集

| 用語 | 一言説明 |
|---|---|
| **サーブレットフィルタ** | リクエストが Controller に届く前に割り込める関門。Java の Web の土台が定めた仕組み |
| **フィルタチェーン** | フィルタを一列に並べたもの。全リクエストが上から順に通る |
| **`DelegatingFilterProxy`** | Tomcat と Spring の橋渡しをするフィルタ。Tomcat に登録されるのはこれ 1 個 |
| **`FilterChainProxy`** | 複数の `SecurityFilterChain` を持ち、URL でどれに流すか選ぶ司令塔 |
| **`SecurityFilterChain`** | 実際のフィルタの並び。`SecurityConfig.filterChain()` の戻り値 |
| **認証(Authentication)** | 「あなたは誰か」を確かめること。失敗は 401 |
| **認可(Authorization)** | 「その人にこの操作を許すか」を決めること。失敗は 403 |
| **principal** | 「今ログインしている人」を表すオブジェクト。このアプリでは `AppUserDetails` |
| **`Authentication`** | principal・資格情報・権限をまとめて持つ、認証結果の入れ物 |
| **`SecurityContext`** | `Authentication` を 1 個入れておく箱。セッションに保存される |
| **`UserDetailsService`** | 「識別子からユーザーを 1 件返す」役割の標準インターフェイス |
| **`AuthenticationProvider`** | 認証方式ごとの実装。パスワード方式は `DaoAuthenticationProvider` |
| **匿名認証(Anonymous)** | 未ログインでも `Authentication` を必ず入れる仕組み。principal は `"anonymousUser"` |
| **`HandlerMethodArgumentResolver`** | Controller メソッドの引数を埋める Spring MVC の役。`@AuthenticationPrincipal` / `@RequestBody` / `@PathVariable` はどれもこの仕組みで解決される |
| **Bean** | Spring が起動時に作って管理する部品の実体 |
| **DI(依存性注入)** | 必要な部品を自分で `new` せず外から渡してもらう仕組み。Spring は**型**で解決する |
| **制御の反転(IoC)** | 自分がフレームワークを呼ぶのではなく、フレームワークが自分のコードを呼ぶ構造 |
| **CSRF** | 利用者のログイン状態を使って、別サイトから勝手に操作を実行させる攻撃 |
| **セッション固定化攻撃** | 攻撃者が用意したセッション ID を使わせ、ログイン後に乗っ取る攻撃。ID の再発行で防ぐ |
| **アカウント列挙** | エラーの違いから「このメールは登録済み」と特定すること(→ ADR-0003) |

## 関連

- [session-store-and-other-frameworks.md](./session-store-and-other-frameworks.md) — **このメモの相方**。principal を保存する側(Spring Session)の話。`getSession()` が MySQL に届くまで、保存先の 5 段階、Laravel / Better Auth との対比
- [exception-handling-vs-other-frameworks.md](./exception-handling-vs-other-frameworks.md) — `GlobalExceptionHandler` 側の話。このメモの `AuthResponseWriter` と対になる
- [ADR-0002 セッション Cookie 方式を選んだ理由](../../../adr/0002-session-cookie-over-jwt.md) — なぜ JWT ではないか
- [ADR-0003 アカウント列挙と未確認ユーザーの扱い](../../../adr/0003-account-enumeration-and-unverified-signup.md) — 「メール未確認」だけメッセージを分けた判断
- [フェーズ3 設計書](../../../superpowers/specs/2026-08-05-phase3-auth-design.md) — 何を作るかの記録。このメモは「フレームワークがどう動くか」担当
- [validation-layers.md](../../validation-layers.md) — Controller 到達後のバリデーションの層
