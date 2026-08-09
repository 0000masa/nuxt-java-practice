# セッションはどこに置かれているのか — 保存先の 5 段階でフレームワークを並べる

`AuthController.changePassword` の `httpRequest.getSession(false)` を見て湧く疑問 — 「これは Cookie に入っているセッション ID をもとに DB から取り出すメソッドなのか?」「セッションを DB に置くと何が嬉しいのか、代償は何か?」「Laravel や Better Auth はどこに置いているのか?」「置き場所を変えるとインフラは増えるのか?」 — に答える学習メモ。

**「セッションの実体をどこに置くか」**という 1 本の軸で 5 段階に並べる。Spring / Laravel / Better Auth の違いも、この段階表のどこに何を置いているか、として位置づける。

対象ファイル: [AuthController.java](../../../../backend/src/main/java/com/example/app/auth/AuthController.java) / [UserSessionManager.java](../../../../backend/src/main/java/com/example/app/auth/UserSessionManager.java) / [V3__create_spring_session_tables.sql](../../../../backend/src/main/resources/db/migration/V3__create_spring_session_tables.sql) / [application.yml](../../../../backend/src/main/resources/application.yml)

> **このメモの検証状況**
> Spring 側の既定値(Cookie 名・Base64 エンコード・cleanup cron・発行される SQL)は、**このプロジェクトが実際に使っている jar のソースを読んで確認した**(`spring-session-core` / `spring-session-jdbc` 4.1.0、`~/.gradle/caches` 内の `-sources.jar`)。該当箇所には「ソース確認」と記した。
> Laravel / Better Auth の記述は**公式ドキュメントを参照して書いたが、コードは実行していない**。対象は Laravel 13.x / Better Auth の現行ドキュメント。この 2 つはバージョンで既定値が変わる領域なので、使う前に公式で裏を取ること(リンクは各章の末尾)。
> **なぜ Cookie セッション方式を選んだか**という判断はこのメモの担当ではない → [ADR-0002](../../../adr/0002-session-cookie-over-jwt.md)。ここは「どういう選択肢があり、それぞれ何が起きるか」の地図。

## まず結論(3 行)

1. **`getSession()` 自体は保存先を知らない。** サーブレット仕様は「このリクエストのセッションを返せ」としか決めていない。MySQL から取り出しているのは、**リクエストを横取りして包み直しているフィルタ**のほう。
2. **保存先は 5 段階あり、このプロジェクトは③共有 RDB にいる。** そして「番号が大きいほど高級」ではない。**①②は複数インスタンスで破綻し、⑤は失効できない。**③と④の差は性能とインフラ費用だけ。
3. **「認証機能」と「セッションの保管」は別の層。** Better Auth は両方をまとめて提供し、Spring は Spring Security(認証)と Spring Session(保管)の 2 部品に分かれている。**「Better Auth と Spring Session を比べる」は層がずれた比較**になる。

## 主軸: セッションの実体をどこに置くか — 5 段階

| | 段階 | 置き場所 | 再起動で | 複数インスタンスで共有 | 全端末ログアウト | 追加インフラ | 代表例 |
|---|---|---|---|---|---|---|---|
| **①** | **プロセス内メモリ** | JVM / PHP プロセスのヒープ | **消える** | **不可** | 不可(自分のみ) | 不要 | Spring Session 抜きの Tomcat |
| **②** | **ローカルファイル** | コンテナのディスク | 残る | **不可** | 不可(自分のみ) | 不要 | Laravel の `file` ドライバ、PHP の既定 |
| **③** | **共有 RDB** | MySQL / RDS | 残る | 可 | **可** | **不要**(既存 DB に相乗り) | **このプロジェクト**、Laravel の現在の既定 |
| **④** | **インメモリストア** | Redis / Memcached | 設定次第 | 可 | **可** | **要る**(ElastiCache 等) | 実務の定番 |
| **⑤** | **クライアント自身** | Cookie の中身そのもの | 残る | 可 | **不可**(要ブラックリスト) | 不要 | JWT、Laravel の `cookie` ドライバ |

この表の読み方で大事な点が 3 つある。

- **①→②→③ は「共有できるか」で切れている。** ①と②の違いは「再起動で消えるか」だけで、**どちらも隣のコンテナからは見えない**。ECS でタスクを 2 つにした瞬間、①②は「リクエストのたびにログイン状態が飛ぶ」状態になる。
- **③と④の違いは性能とインフラ費用だけ。** できること(共有・失効・検索)は同じ。だから ADR-0002 は「性能が問題になったら差し替える」と書ける。
- **⑤だけが質的に違う。** サーバーが何も覚えないので**失効させる相手がいない**。ログアウト・パスワード変更・アカウント停止のたびに困る。対策としてブラックリストを DB に持つと、結局③を再発明することになる(→ [ADR-0002](../../../adr/0002-session-cookie-over-jwt.md))。

## Spring Boot での仕組み — `getSession()` が MySQL に届くまで

### 素の Tomcat なら①にいる

`HttpServletRequest#getSession()` はサーブレット仕様が定めたメソッドで、契約は「このリクエストに紐づくセッションを返す(無ければ作る)」だけ。**保存先は仕様に書かれていない。**

Spring Session を入れていない Spring Boot では、この呼び出しに答えるのは **Tomcat 自身**で、`JSESSIONID` という Cookie を見て**自分の JVM のメモリ上の Map** を引く。つまり素の状態は段階表の①。

### `SessionRepositoryFilter` がリクエストを包み直す

`spring-boot-starter-session-jdbc` を入れると([build.gradle](../../../../backend/build.gradle))、`SessionRepositoryFilter` というサーブレットフィルタがチェーンのかなり手前に入る。このフィルタがやることは 1 つだけ — **`HttpServletRequest` を自前のラッパーで包んで後続に渡す**。

公式ドキュメントに載っている、そのラッパーの骨格。

```java
// Spring Session 公式ドキュメントの説明用コード
public class SessionRepositoryRequestWrapper extends HttpServletRequestWrapper {

    public HttpSession getSession() {
        return getSession(true);
    }

    public HttpSession getSession(boolean createNew) {
        // ここで Spring Session 側の HttpSession 実装を組み立てて返す
    }

    // それ以外のメソッドは元の HttpServletRequest にそのまま委譲する
}
```

結果、フィルタより後ろにいる全員が「包まれた方」を受け取る。

```
ブラウザ  Cookie: SESSION=M2YyYi0uLi4=
   │
   ▼
SessionRepositoryFilter        ← ここで request を包む
   │  (以降 getSession() の宛先が Tomcat から MySQL に変わる)
   ▼
SecurityContextHolderFilter    ← getSession(false) を呼んで SecurityContext を復元
   │                             ★ SELECT が走るのは実質ここ
   ▼
CsrfFilter / 認可フィルタ …
   ▼
DispatcherServlet
   ▼
AuthController.changePassword(..., HttpServletRequest httpRequest)
   │                                 ↑ 包まれた方が届く
   └─ httpRequest.getSession(false)  ← 既に読み込み済み。キャッシュから返るだけ
```

**Controller に届く `httpRequest` はもう Tomcat の素の実装ではない。** ここが「`getSession()` が DB を引いている」と感じる仕掛けの正体で、`getSession()` というメソッド自体は何も変わっていない。

> **Controller の `getSession(false)` で SQL は走っていない。** セッションはリクエスト内で 1 回だけ読んでキャッシュされる。Spring Security が Controller のずっと手前で読み込み済みなので、[AuthController.java](../../../../backend/src/main/java/com/example/app/auth/AuthController.java) の `getSession(false)` はキャッシュを返すだけ。SELECT が 2 回走ることはない。

### Cookie に入っているもの(ソース確認)

`DefaultCookieSerializer` の既定値を jar のソースで確認した。

| 項目 | 既定値 | 確認元 |
|---|---|---|
| Cookie 名 | **`SESSION`**(`JSESSIONID` ではない) | `private String cookieName = "SESSION";` |
| 値 | **セッション ID を Base64 エンコードしたもの** | `private boolean useBase64Encoding = true;` |
| `HttpOnly` | **true**(JavaScript から読めない) | `private boolean useHttpOnlyCookie = true;` |
| `SameSite` | `Lax` | 公式ドキュメント |
| `Secure` | リクエストが HTTPS なら付く | 公式ドキュメント |

**Base64 されている点は実際に Cookie を目で見るときに引っかかる。** ブラウザの開発者ツールで `SESSION` の値をコピーして `SELECT ... WHERE SESSION_ID = '<その値>'` としても一致しない。デコードしてから比べる必要がある。

**中身はセッション ID だけ**で、ログイン状態そのものは入っていない。ここが段階表⑤との決定的な違い。

### DB 側の 2 テーブル

[V3__create_spring_session_tables.sql](../../../../backend/src/main/resources/db/migration/V3__create_spring_session_tables.sql) で作っている。中身は公式 DDL のまま。

**`SPRING_SESSION`** — セッション 1 件 = 1 行。

| カラム | 意味 |
|---|---|
| `PRIMARY_ID` | 主キー。**行の識別子**で、Cookie には出てこない |
| `SESSION_ID` | **Cookie に載る方の ID**。セッション固定攻撃対策で再発行されるとここだけ変わる |
| `CREATION_TIME` / `LAST_ACCESS_TIME` | 作成時刻と最終アクセス時刻(エポックミリ秒) |
| `MAX_INACTIVE_INTERVAL` | 無操作で切れるまでの秒数。`spring.session.timeout: 1d` がここに入る |
| `EXPIRY_TIME` | 期限切れ時刻。掃除用に index が張られている |
| `PRINCIPAL_NAME` | **ログイン識別子 = このアプリではメールアドレス**。index あり |

**ID が 2 本ある理由**がここで効いてくる。ログイン成功時に `SESSION_ID` を作り直しても `PRIMARY_ID` は変わらないので、属性テーブルの外部キーが壊れない。

**`SPRING_SESSION_ATTRIBUTES`** — セッションに入れた値。カラムは 3 つだけで、**「属性名」と「値」を 2 本のカラムで持つキー・バリュー型**になっている。

| カラム | 意味 |
|---|---|
| `SESSION_PRIMARY_ID` | どのセッションの属性か(`SPRING_SESSION.PRIMARY_ID` への外部キー) |
| `ATTRIBUTE_NAME` | **属性の名前**。`session.setAttribute("名前", 値)` の「名前」 |
| `ATTRIBUTE_BYTES` | **属性の値**。Java オブジェクトをシリアライズしたバイト列(BLOB) |

主キーが `(SESSION_PRIMARY_ID, ATTRIBUTE_NAME)` の複合になっているとおり、**1 セッション × 1 属性名 = 1 行**。属性を増やすとカラムではなく**行が増える**(属性名は任意なのでカラムでは表現できない)。

ログイン中のセッションには、こういう行が入っている。

| SESSION_PRIMARY_ID | ATTRIBUTE_NAME | ATTRIBUTE_BYTES |
|---|---|---|
| `a1b2…` | `SPRING_SECURITY_CONTEXT` | `<SecurityContext をシリアライズしたバイト列>` |

`SPRING_SECURITY_CONTEXT` は**カラム名ではなく `ATTRIBUTE_NAME` に入る値**で、Spring Security が `SecurityContext` を保存するときに使う決め打ちのキー。その `ATTRIBUTE_BYTES` を復元すると `SecurityContext` → `Authentication` → `AppUserDetails` と辿れる。

[AppUserDetails](../../../../backend/src/main/java/com/example/app/auth/AppUserDetails.java) が `userId` / `email` / `emailVerified` しか持たず、`eraseCredentials()` でパスワードハッシュを消しているのは、**この BLOB に何が焼き付くかを意識しているから**。表示名まで持たせるとプロフィール編集後も古い値が BLOB に残り続ける。

### 実際に発行される SQL(ソース確認)

`JdbcIndexedSessionRepository` が持っている SQL。

```sql
-- 読み込み(リクエストのたび)
SELECT ... FROM SPRING_SESSION S
  LEFT JOIN SPRING_SESSION_ATTRIBUTES SA ON S.PRIMARY_ID = SA.SESSION_PRIMARY_ID
  WHERE S.SESSION_ID = ?

-- 書き戻し(リクエストのたび。後述)
UPDATE SPRING_SESSION
   SET SESSION_ID = ?, LAST_ACCESS_TIME = ?, MAX_INACTIVE_INTERVAL = ?, EXPIRY_TIME = ?, PRINCIPAL_NAME = ?
 WHERE PRIMARY_ID = ?

-- 特定ユーザーの全セッション(UserSessionManager が使う)
SELECT ... WHERE S.PRINCIPAL_NAME = ?

-- 削除
DELETE FROM SPRING_SESSION WHERE SESSION_ID = ?
```

3 番目が[全端末強制ログアウト](../../../../backend/src/main/java/com/example/app/auth/UserSessionManager.java)の実体。`PRINCIPAL_NAME` に index が張ってあるのはこの 1 クエリのため。

### `session.getId()` と `SESSION_ID` が噛み合う理由

[UserSessionManager](../../../../backend/src/main/java/com/example/app/auth/UserSessionManager.java) の `findByPrincipalName` が返す Map のキーも、`deleteById` が受け取る値も、上の `SESSION_ID`。だから Controller で取った `session.getId()` をそのまま「残すセッション」として渡せば正しく一致する。

```java
// AuthController.changePassword
HttpSession session = httpRequest.getSession(false);
authService.changePassword(..., session == null ? null : session.getId());
```

**これは①では原理的に書けないコード。** Tomcat のメモリセッションには「特定ユーザーのセッションを横断検索する」API が存在しない。**「セッションを共有ストアに置いた」という選択が、この機能を可能にしている。**

## ③ 共有 RDB に置くメリット

1. **失効させられる。** ログアウト・パスワードリセット・アカウント停止のたびに `DELETE` できる。⑤(JWT)には無い能力で、これがこのアプリの[パスワードリセット時の全端末強制ログアウト](../../../../backend/src/main/java/com/example/app/auth/AuthService.java)を成立させている。
2. **スケールアウトしてもスティッキーセッションが要らない。** どのタスクが受けても同じ MySQL を見るので、ALB は素直にラウンドロビンでよい。
3. **デプロイでログアウトされない。** ECS のタスクが入れ替わってもセッションは DB に残る。①②だと**デプロイのたびに全ユーザーがログアウトする**。
4. **追加インフラが要らない。** アプリが既に使っている RDS に相乗りするだけ。段階表で③だけが「共有できる」と「追加インフラ不要」を同時に満たす。
5. **目で見える。** `SELECT * FROM SPRING_SESSION` でログイン状態を確認できる。学習リポジトリとしてはこれが大きい(→ [ADR-0002](../../../adr/0002-session-cookie-over-jwt.md))。

## ③ 共有 RDB に置くデメリット

メリットだけ見ると万能に見えるので、代償も同じ重みで並べる。

1. **リクエストのたびに SELECT と UPDATE が走る。** セッションを読むと `setLastAccessedTime()` が呼ばれ、内部の `changed` フラグが立ち、リクエスト終了時に `UPDATE` が発行される(ソース確認: `setLastAccessedTime` の中で `this.changed = true`)。**ログイン中のユーザーの全リクエストに DB 往復が 2 回追加される**。何もしない `GET /api/posts` でもこれは避けられない。
2. **アプリ DB と負荷点が同じになる。** 投稿の検索が重くて RDS が詰まると、**ログイン状態の読み書きまで一緒に詰まる**。Redis に分けていれば別々に倒れる。
3. **期限切れの掃除を自前で回している。** `DEFAULT_CLEANUP_CRON = "0 * * * * *"`(ソース確認)で、**毎分** `DELETE FROM SPRING_SESSION WHERE EXPIRY_TIME < ?` が走る。Redis なら TTL でストア側が勝手に消してくれる仕事を、③では DELETE クエリとして払っている。
4. **セッションに入れた物が毎回シリアライズされる。** `ATTRIBUTE_BYTES` は BLOB。大きなオブジェクトを入れると、その分がリクエストのたびに DB と往復する。`AppUserDetails` を最小限にしているのはこの事情もある。
5. **書き込みの競合が起きうる。** 同じセッションで同時に 2 本のリクエストが走ると、後勝ちでセッション属性が失われることがある。Spring Session にはこれを止める標準機能が無い(Laravel には後述の Session Blocking がある)。
6. **セッション行がユーザー数 × 端末数だけ増える。** 期限 1 日で放置すると、`SPRING_SESSION` が地味に大きくなる。

**この規模だから③が成立している**、というのが正しい理解。同時接続が増えれば④に動かす判断になる。

## 保存先を変えると、このプロジェクトのインフラは何が増えるか

現在の本番想定は **Route53 → ALB → ECS Fargate → RDS**(→ [docs/infrastructure/README.md](../../../infrastructure/README.md))。`cloudformation/` はまだ空なので、以下は「これから書くテンプレートに何が増えるか」の話。

| 段階 | CloudFormation に増えるもの | ALB の設定 | 課金 | デプロイ時 |
|---|---|---|---|---|
| ① プロセス内メモリ | なし | **スティッキーセッション必須** | 増えない | **全員ログアウト** |
| ② ローカルファイル | なし(Fargate なら実質①と同じ) | **スティッキーセッション必須** | 増えない | **全員ログアウト** |
| **③ 共有 RDB(現在)** | **なし** | 不要 | 増えない | 維持される |
| ④ Redis | ElastiCache クラスタ、サブネットグループ、専用セキュリティグループ、パラメータグループ、接続情報の受け渡し | 不要 | **常時課金が増える** | 維持される |
| ⑤ クライアント自身 | なし | 不要 | 増えない | 維持される |

**①②を選ぶと「インフラは増えないが ALB の設定が増える」**のがポイント。スティッキーセッション(同じブラウザを常に同じタスクへ送る)を有効にすれば動くには動くが、代償が大きい。

- タスクが 1 台落ちると、そこに貼り付いていたユーザーだけログアウトする
- 負荷が均等に散らない(重いユーザーが同じタスクに固まる)
- **デプロイのたびに全員ログアウトする**

**④を選ぶと逆に「ALB は素直なままだがインフラが増える」。** ElastiCache は使っていなくても起動している限り課金されるので、[このリポジトリの「使うときだけスタックを作って消す」運用](../../../infrastructure/README.md)とは相性が悪い。

**③は「追加リソース 0 個」で「ALB の設定も素直なまま」という、この規模では実に都合の良い位置にいる。** アプリ側の差し替えコストも小さく、`spring-session-jdbc` を `spring-session-data-redis` に入れ替えるだけで④に移れる — アプリコードが Spring Session の抽象より下に依存していない限りは。

## 層が違う — 認証機能層と保管層

Better Auth と Spring Session を直接比べると噛み合わない。提供している層が違うため。

```
                Java/Spring          PHP/Laravel           TypeScript
             ┌─────────────────┬──────────────────┬──────────────────┐
認証機能層    │  Spring Security │  Laravel Auth    │                  │
 登録/ログイン │                  │  + Fortify       │   Better Auth    │
 OAuth/リセット│                  │  (Breeze 等)     │   (ここを 1 つで  │
             ├─────────────────┼──────────────────┤    まかなう)      │
保管層        │  Spring Session  │  Session         │                  │
 どこに置くか  │  (jdbc/redis)    │  (driver)        │                  │
             └─────────────────┴──────────────────┴──────────────────┘
```

このプロジェクトで言うと、**ログインの手続きは `SecurityConfig` の `formLogin()`(認証機能層)、セッションの置き場所は `spring-session-jdbc`(保管層)**と、担当が完全に分かれている。だから「Redis に移す」判断をしてもログイン処理のコードは 1 行も変わらない。

**Better Auth に相当するのは「Spring Security + Spring Session」の組み合わせ**、と対応付けると比較できるようになる。

## Laravel との対比

### 既定は `database`。段階表の③にいる

**ここが一番の注意点。** 公式(13.x)は "By default, Laravel is configured to use the `database` session driver" と書いている。**古い記事には「既定は `file`」と書かれていることが多い**ので、そのつもりでいると噛み合わない。

| Laravel のドライバ | 段階表 | 置き場所 |
|---|---|---|
| `array` | ①(揮発) | PHP の配列。永続化されない。テスト用 |
| `file` | **②** | `storage/framework/sessions` にファイルとして |
| **`database`(既定)** | **③** | `sessions` テーブル |
| `redis` / `memcached` | ④ | それぞれのストア |
| `dynamodb` | ④ | DynamoDB |
| `cookie` | **⑤** | **暗号化された Cookie の中身そのもの** |

**`cookie` ドライバが⑤に相当する**のが分かりやすい。JWT を使わなくても「クライアントに全部持たせる」方式は選べる、ということ。

### 切り替えは 1 行

```
SESSION_DRIVER=database   # .env のこの 1 行で段階表を移動できる
```

Spring は依存(`spring-session-jdbc` / `spring-session-data-redis`)の入れ替えで、Laravel は環境変数で切り替わる。**「保管層が差し替え可能な抽象になっている」点は両者共通**で、これは偶然ではなく、③④を行き来する需要が実際にあるから両方が用意している。

### テーブルの用意のしかたは違う

③を選ぶと、どちらも「セッション用のテーブルを作る」必要が出てくる。**行き着く先は同じだが、手段が違う。**

| | Laravel | Spring Session JDBC |
|---|---|---|
| 生成コマンド | **`php artisan make:session-table`** でマイグレーションを生成できる | **無い** |
| フレームワークに任せる道 | (マイグレーションを流すだけ) | `spring.session.jdbc.initialize-schema: always` で起動時に自動実行 |
| 手で取り込む道 | — | jar 同梱の DDL を**手動でコピー**してスキーマ管理ツールに置く |

Spring Session の DDL は **jar の中に入っている**(`spring-session-jdbc-4.1.0.jar` の `org/springframework/session/jdbc/schema-mysql.sql`)。`initialize-schema` に任せた場合も、フレームワークがこのファイルを読んで実行しているだけ。

**このプロジェクトは自動実行を使わず、この DDL を jar から手動でコピーして [V3](../../../../backend/src/main/resources/db/migration/V3__create_spring_session_tables.sql) にした**(コメントと空行を除いて公式ファイルと完全一致)。Laravel の `make:session-table` に相当する生成コマンドが無いので、ここは人の手で運んでいる。

#### DDL を jar から取り出す手順

使うのはこのコマンド。

```bash
jar xf ~/.gradle/caches/.../spring-session-jdbc-4.1.0.jar \
       org/springframework/session/jdbc/schema-mysql.sql
```

**このコマンドが何をするか**

- `jar` は **JDK に付属するツール**で、jar ファイル(実体は zip)の中身を読み書きする
- `xf` は **x = extract(取り出す)、f = file(対象の jar を指定)** の意味
- 第 1 引数が**取り出し元の jar**、第 2 引数が**その中から取り出したいファイル**
- **画面には何も出ない。** 成功すると無言で終わる。結果は「標準出力」ではなく「**ファイルが作られること**」で返ってくる。中身を見るには続けて `cat` する

**実行する場所**

- **backend コンテナの中**で実行する。jar は依存として Gradle のキャッシュに落ちており、ホスト側には JDK も jar も無い(→ [java-dev-env-comparison.md](../../java-dev-env-comparison.md))
- したがって `~` は**コンテナ内の `/root`** を指す
- `...` の部分はハッシュ値のディレクトリで環境ごとに違う。`find /root/.gradle -name "spring-session-jdbc-*.jar"` で探す(本体・`-sources`・`-javadoc` の 3 つが並ぶので、**本体を選ぶこと**)

**作られるもの**

`jar` の中のパスがそのまま再現されるので、ファイル 1 つ取り出すだけでフォルダが 4 階層できる。**jar 自体は変更されない**(読み取るだけ)。

```
org/springframework/session/jdbc/schema-mysql.sql   ← これが欲しかったファイル
```

> **`docker compose exec` で実行すると、リポジトリの中にファイルが作られる。消し忘れに注意。**
>
> `jar xf` は出力先を指定できず、**必ずカレントディレクトリに展開する**。backend コンテナの作業ディレクトリは `/app`(`docker/backend/Dockerfile` の `WORKDIR`)で、そこは `./backend` にバインドマウントされている。つまり上のコマンドをそのまま実行すると、ホスト側に `backend/org/springframework/session/jdbc/schema-mysql.sql` ができ、**`git status` に未追跡ファイルとして出てくる**。
>
> 中身を V3 にコピーし終わったら消すこと。
>
> ```bash
> docker compose exec backend rm -rf /app/org
> ```
>
> リポジトリを汚したくなければ、コンテナ内の一時ディレクトリに移ってから実行する(`cd /tmp && jar xf ...`)。そこはバインドマウントの外なので、ホストからは見えず、コンテナを作り直せば消える。

> **`jar xf` は jar の中に無いファイル名を指定しても、エラーを出さず終了コード 0 で終わる。** 何も起きていないのに成功したように見える。jar のパスを間違えた(`-javadoc` の方を掴んだ等)ときにここで詰まりやすい。中身の一覧を確認するには `jar tf <jarファイル>`(t = table of contents)。

**手動なのはこのコピー作業だけで、一度きり。** V3 として置いた後は Flyway が起動時に自動で適用するので、環境を作り直すたびに手で SQL を流す必要はない。

この一手間を払っている理由は「スキーマ変更はすべて Flyway、`ddl-auto` は `validate`」という方針を崩さないため。`initialize-schema: always` に任せるとセッションテーブルだけ Flyway の管理外になり、`db/migration/` を見れば DB の全体像が分かる状態が崩れる。`application.yml` で `initialize-schema: never` を明示しているのはその宣言。

> **既定値の罠**: `spring.session.jdbc.initialize-schema` の既定は `embedded`(H2 などの組み込み DB のときだけ作る)。MySQL では**何も作られない**ので、Flyway にも取り込まずに起動すると最初のセッション書き込みで落ちる。

### Laravel にあって Spring に無いもの: Session Blocking

上のデメリット 5 で挙げた同時書き込みの問題に、Laravel は標準の答えを持っている。

```php
Route::post('/profile', function () {
    // ...
})->block($lockSeconds = 10, $waitSeconds = 10);
```

同じセッション ID のリクエストを直列化する。**`cookie` ドライバでは使えない**(⑤にはロックを置く場所が無い)という制約も、段階表で見ると納得できる。

### セッション ID の再発行

Laravel は `$request->session()->regenerate()` を明示的に呼ぶ API を持ち、スターターキットや Fortify を使っていれば認証時に自動で呼ばれる。Spring Security の `formLogin()` も認証成功時に自動でセッション ID を再発行する(→ [ADR-0002](../../../adr/0002-session-cookie-over-jwt.md))。**どちらもセッション固定攻撃対策として同じことをしている。**

一方で **「特定ユーザーの全セッションを消す」は Laravel の標準機能には無い。** `sessions` テーブルに `user_id` カラムがあるので自分で `DELETE` は書けるが、Spring Session の `findByPrincipalName` のような専用 API は用意されていない。このプロジェクトが[数行で全端末ログアウトを書けている](../../../../backend/src/main/java/com/example/app/auth/UserSessionManager.java)のは Spring Session の持ち分。

公式: <https://laravel.com/docs/13.x/session>

## Better Auth との対比

### 認証機能ごと持ってくるライブラリ

Better Auth は TypeScript の認証ライブラリで、**サインアップ・ログイン・OAuth・メール確認・パスワードリセット・セッション**をまとめて提供する。上の層の図でいうと、認証機能層と保管層を縦に貫いている。

**このプロジェクトで言えば、`AuthService` / `AuthTokenService` / `AuthMailSender` / `SecurityConfig` / `spring-session-jdbc` をまとめて置き換えるもの**、と考えると規模感が合う。

### 保管は既定で③

Better Auth は既定でデータベースにセッションを持つ。作られる `session` テーブルはこの構成。

| カラム | 意味 |
|---|---|
| `id` | 主キー |
| `token` | **セッショントークン。Cookie に載る値** |
| `userId` | ユーザーへの外部キー |
| `expiresAt` | 期限 |
| `ipAddress` / `userAgent` | **接続元 IP と UA** |
| `createdAt` / `updatedAt` | 記録 |

`SPRING_SESSION` と比べると差がはっきりする。

| | Spring Session JDBC | Better Auth |
|---|---|---|
| ユーザーとの関連 | `PRINCIPAL_NAME`(**文字列。外部キーではない**) | `userId`(**外部キー**) |
| 任意のデータを入れられるか | **入れられる**(`SPRING_SESSION_ATTRIBUTES` に何でも) | 入れる場所が無い(認証情報専用) |
| IP / UA の記録 | 無い | **標準で持つ** |
| 汎用性 | HttpSession の置き換えなので**カートでも何でも入る** | **認証セッション専用** |

**Spring Session は「HttpSession の保存先を差し替える部品」で、Better Auth の session テーブルは「ログイン中の端末の台帳」。** 名前は同じ「セッション」でも、設計思想が違う。`ipAddress` / `userAgent` を標準で持つのは「ログイン中のデバイス一覧」を出す機能を想定しているため。

### Cookie

- 名前は **`better-auth.session_token`**(プレフィックス `better-auth` + 名前 `session_token`)
- **署名されている**(`BETTER_AUTH_SECRET` で。改ざんを検出できる)
- `httpOnly` は既定で有効、`secure` は本番モードで有効

Spring Session の Cookie が**署名なしの Base64** なのと対照的。ただしどちらも「中身はセッション ID / トークンだけ」なので、**署名の有無はセキュリティの本質的な差にはならない**(サーバー側で存在確認をするため、偽造しても DB に行が無ければ弾かれる)。

### `cookieCache` — ③と⑤の折衷

Better Auth には「セッションのデータを短命な署名付き Cookie にも持たせ、DB へのクエリを減らす」オプションがある(`session.cookieCache.enabled`、既定は `false`)。

**これは③のデメリット 1(毎リクエストの SELECT)への直球の答え**で、段階表でいうと**③と⑤の間**に位置する。「短い有効期間の間だけ⑤として振る舞い、切れたら③に戻る」という設計。失効の遅れ(最大 `maxAge` 秒)と引き換えに DB 往復を減らす、というトレードオフを明示的に選べる。

Spring Session に相当する機能は無い。**同じ問題に対して、Spring は「④に移す」、Better Auth は「⑤を部分的に混ぜる」という別方向の答えを用意している**、という対比になる。

### `secondaryStorage` — ④への逃がし方

Redis などを差し込むための口も用意されている。実装すべきインターフェースは 3 メソッドだけ。

```typescript
interface SecondaryStorage {
  get: (key: string) => Promise<unknown>;
  set: (key: string, value: string, ttl?: number) => Promise<void>;
  delete: (key: string) => Promise<void>;
}
```

セッション・確認用レコード・レート制限のカウンタといった短命なデータをここに逃がす。**Spring で `spring-session-jdbc` を `spring-session-data-redis` に差し替えるのと同じ意図の機能。** セッション行が DB 側にも残るかどうかは設定によるので、使う前に公式で確認すること。

公式: <https://www.better-auth.com/docs/concepts/session-management> / <https://www.better-auth.com/docs/concepts/database> / <https://www.better-auth.com/docs/concepts/cookies>

## 薄いフレームワークには、そもそもセッション機能が無い

Express や Hono はルーティングとミドルウェアだけの薄い層なので、セッションを標準で持たない。ライブラリで補うことになり、**選ぶライブラリが段階表のどこにいるかを自分で意識する必要がある**。

| ライブラリ | 段階表 | 備考 |
|---|---|---|
| `express-session`(既定の MemoryStore) | ① | **公式が「本番では使うな」と明記している**。まさに①の問題のため |
| `express-session` + `connect-redis` 等 | ④ | ストアを差し替える。Spring Session と同じ発想 |
| `iron-session` | ⑤ | 暗号化した Cookie に全部入れる。Laravel の `cookie` ドライバに近い |

[application-events-vs-queues.md](./application-events-vs-queues.md) で見たイベント機能と同じ分かれ方をしている。**フルスタックフレームワーク(Spring / Laravel / Rails)は保管層の抽象を標準で持ち、薄いフレームワークは持たない。**

## つまずきポイント

- **`getSession()` が DB を引くのは、フィルタが request を包んでいるから。** メソッド自体は保存先を知らない。Spring Session を外せば同じコードが Tomcat のメモリを引く。
- **`getSession()` と `getSession(false)` は別物。** 引数なしは「無ければ作る」。取得したいだけのときに使うと、空のセッションを DB に INSERT する副作用が出る。
- **Cookie は `JSESSIONID` ではなく `SESSION`。** 探すときに間違えやすい。
- **Cookie の値をそのまま `SESSION_ID` と比較しても一致しない。** Base64 されている(ソース確認)。デコードしてから比べる。
- **Cookie に入っているのはセッション ID だけ。** ログイン状態そのものではない。⑤と混同しない。
- **`PRIMARY_ID` と `SESSION_ID` は別のカラム。** Cookie に載るのは後者。前者は外部キーの相手で、セッション ID を再発行しても変わらない。
- **ログイン中は全リクエストで UPDATE が走る。** 「読んだだけ」でも `LAST_ACCESS_TIME` が変わるので書き込みが発生する(ソース確認)。
- **`spring.session.jdbc.initialize-schema` の既定は `embedded`。** MySQL では何も作られず、テーブルが無いまま起動して落ちる。このプロジェクトは Flyway で作り、`never` を明示している。
- **`jar xf` は無いファイルを指定しても終了コード 0 で無言で終わる。** 取り出せていないのに成功したように見える。`find` が javadoc / sources の jar を掴んでいるのが典型的な原因。
- **セッションに大きなオブジェクトを入れない。** BLOB として毎リクエスト往復する。`AppUserDetails` が最小限なのはこのため。
- **①②を選んだまま ECS のタスクを増やすと壊れる。** 動かすにはスティッキーセッションが要り、デプロイのたびに全員ログアウトする。
- **「Laravel の既定は `file`」は古い情報。** 13.x の既定は `database`(段階表の③)。
- **Better Auth と Spring Session は層が違う。** 比べるなら「Better Auth」対「Spring Security + Spring Session」。
- **⑤は失効できない。** ブラックリストで補おうとすると③を再発明することになる(→ [ADR-0002](../../../adr/0002-session-cookie-over-jwt.md))。

## 用語集

- **セッション** — サーバー側に置く「この人はログイン済み」などの状態の入れ物。ブラウザには識別子だけを渡す
- **セッション ID** — セッションを指す識別子。Cookie で運ばれる。これを盗まれるとなりすまされる
- **`SessionRepositoryFilter`** — Spring Session が入れるサーブレットフィルタ。`HttpServletRequest` を包み直して `getSession()` の宛先を変える
- **`SessionRepositoryRequestWrapper`** — 上のフィルタが作るラッパー。`getSession()` だけ差し替え、他は元のリクエストに委譲する
- **`DefaultCookieSerializer`** — Cookie 名や Base64 エンコードを決めている Spring Session の部品
- **`FindByIndexNameSessionRepository`** — 「index 付きの属性でセッションを検索できる」リポジトリの型。`findByPrincipalName` を持つ
- **`PRINCIPAL_NAME`** — Spring Security から見たログイン識別子。このアプリではメールアドレス。index 経由で全端末ログアウトに使う
- **principal(プリンシパル)** — 今リクエストを送っている当人を表すオブジェクト。このアプリでは `AppUserDetails`
- **セッション固定攻撃** — 攻撃者が用意したセッション ID を被害者に使わせる攻撃。認証成功時に ID を再発行して防ぐ
- **スティッキーセッション** — ロードバランサが同じクライアントを常に同じサーバーへ送る設定。①②で複数インスタンスを動かすときに必要になる
- **シリアライズ** — オブジェクトをバイト列に変換すること。`SPRING_SESSION_ATTRIBUTES` の BLOB がこれ
- **`flushMode` / `saveMode`** — Spring Session がいつ・何を書き戻すかの設定。既定は `ON_SAVE` / `ON_SET_ATTRIBUTE`
- **cleanup cron** — 期限切れセッションを削除する定期処理。Spring Session JDBC の既定は毎分(`0 * * * * *`)
- **セッションドライバ** — Laravel で保存先を選ぶ設定。`SESSION_DRIVER` 環境変数で切り替える
- **Session Blocking** — Laravel の、同一セッションのリクエストを直列化する機能。同時書き込みによるセッションデータの消失を防ぐ
- **`cookieCache`** — Better Auth の、セッションを短命な署名付き Cookie にも載せて DB クエリを減らすオプション
- **`secondaryStorage`** — Better Auth で Redis などを差し込む口。`get` / `set` / `delete` の 3 メソッドだけのインターフェース

## 関連

- **このメモの相方: 認証層(Spring Security)の仕組み** → [security-filter-chain.md](./security-filter-chain.md)。セッションに入る principal を誰が作り、誰が復元するのか
- **なぜ Cookie セッション方式で、なぜ MySQL なのか(判断)** → [ADR-0002](../../../adr/0002-session-cookie-over-jwt.md)
- **全端末ログアウトの実装** → [UserSessionManager.java](../../../../backend/src/main/java/com/example/app/auth/UserSessionManager.java)
- **セッションに何を載せるかの判断** → [AppUserDetails.java](../../../../backend/src/main/java/com/example/app/auth/AppUserDetails.java) のクラスコメント
- **セッションテーブルを Flyway で作る理由** → [V3__create_spring_session_tables.sql](../../../../backend/src/main/resources/db/migration/V3__create_spring_session_tables.sql) のコメント / [flyway-basics.md](../../flyway-basics.md)
- **本番の AWS 構成(ALB / ECS / RDS)** → [docs/infrastructure/README.md](../../../infrastructure/README.md)
- **フェーズ3 認証の設計(決定 11 = 全端末強制ログアウト)** → [2026-08-05-phase3-auth-design.md](../../../superpowers/specs/2026-08-05-phase3-auth-design.md)
- **同じ「フルスタック FW は持つ / 薄い FW は持たない」構図の別テーマ** → [application-events-vs-queues.md](./application-events-vs-queues.md) / [exception-handling-vs-other-frameworks.md](./exception-handling-vs-other-frameworks.md)
