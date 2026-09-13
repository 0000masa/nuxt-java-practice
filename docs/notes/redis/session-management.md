# Redis のセッション管理 — キーを目で見ながら追う

「Redis でセッションを管理する」と言われたときに、**Redis の中に何がどう置かれているのか**を、
実際に動かして目で確認する。

前提 → [redis-basics.md](redis-basics.md)(TTL がキー単位であること、キースペース通知)
決定 → [ADR-0015](../../adr/0015-session-store-on-redis.md)
設計 → [フェーズ18 設計書](../../superpowers/specs/2026-09-12-phase18-redis-session-design.md)

**このノートの出力はすべて実機の実行結果**である。推測で書いた値は無い。

## まず結論(4 行)

- セッション 1 件につき **Hash と String の 2 つのキー**が作られる。**TTL が 300 秒ずれている**のが肝
- 「このユーザーの全セッション」は **Set の索引**として別に持つ。パスワード変更時の強制ログアウトはこれを引く
- **索引の掃除にはキースペース通知が要る。** TTL はキー単位で、Set の要素には張れないから
- アプリのコードは **1 行も変わらない**。保存先は Spring Session の抽象の下にあるから

## 1. 層が分かれている

まず押さえるべきは、**「ログインの手続き」と「セッションの置き場所」は別の層**だということ。

```
┌─────────────────────────────────────────┐
│ 認証機能層   SecurityConfig の formLogin() │  ← 誰がログインしているかを決める
├─────────────────────────────────────────┤
│ 保管層       Spring Session              │  ← それをどこに置くかを決める
│              └ JDBC / Redis / ...        │
└─────────────────────────────────────────┘
```

MySQL から Redis に移しても、**`SecurityConfig` も `AuthController` も `UserSessionManager` も変わらない**。
変わったのは依存 1 行と設定だけである。

```groovy
// backend/build.gradle
- implementation 'org.springframework.boot:spring-boot-starter-session-jdbc'
+ implementation 'org.springframework.boot:spring-boot-starter-session-data-redis'
```

`UserSessionManager` が `JdbcIndexedSessionRepository` ではなく
`FindByIndexNameSessionRepository`(インターフェース)に依存しているから、これが成立する。
**保管層を差し替える予定があるなら、具体型に触らない**というのがここでの教訓になる。

## 2. Spring Boot 4 でプロパティ名が変わっている(最初の罠)

ネット上の記事はほぼ全部が `spring.session.redis.repository-type` と書いているが、
**Spring Boot 4 では効かない。**

```json
{
  "name": "spring.session.redis.repository-type",
  "deprecated": true,
  "deprecation": {
    "level": "error",
    "replacement": "spring.session.data.redis.repository-type",
    "since": "4.0.0"
  }
}
```

`level: error` は「もうバインドしない」という意味で、**書いても黙って無視される**。
正しいのは **`spring.session.data.redis.*`**。

> 接続側の `spring.data.redis.*`(host / port / password / ssl)は Boot 3 のときから同じ。
> 変わったのは Spring Session 側のキーだけなので、**片方だけ直して詰まりやすい**。

無視されるとどうなるか。既定の `RedisSessionRepository` が選ばれ、
これは `FindByIndexNameSessionRepository` を実装していないので **起動が失敗する**(実測)。

```
APPLICATION FAILED TO START

Parameter 0 of constructor in com.example.app.auth.UserSessionManager
required a bean of type 'org.springframework.session.FindByIndexNameSessionRepository'
that could not be found.
```

**この失敗自体は親切**である。黙ってログアウトされ続けるより、起動時に止まるほうがずっといい。

```yaml
# backend/src/main/resources/application.yml
spring:
  session:
    timeout: 1d
    data:
      redis:
        repository-type: indexed
```

## 3. ログイン前 — Redis は空

```bash
$ docker compose exec redis redis-cli --scan
$
```

何も無い。セッションは**ログインした瞬間に生まれる**わけではなく、
**セッションに何か書き込まれた瞬間**に生まれる(このアプリでは認証情報がそれにあたる)。

## 4. ログインする

`formLogin()` に乗っているので、ログインだけ JSON ではなく **form-urlencoded** で送る(→ ADR-0002)。
パラメータ名は `username` ではなく **`email`**(`SecurityConfig` の `usernameParameter("email")`)。

```bash
J=/tmp/cookies.txt && rm -f $J

# 1) CSRF トークンを受け取る(Spring Security 6 以降は遅延発行なので、まず GET が要る)
curl -s -c $J -b $J http://localhost:8080/api/auth/me > /dev/null
TOKEN=$(grep XSRF-TOKEN $J | awk '{print $7}')

# 2) ログイン
curl -s -c $J -b $J -X POST http://localhost:8080/api/auth/login \
  -H "X-XSRF-TOKEN: $TOKEN" \
  --data-urlencode "email=redis-demo@example.com" \
  --data-urlencode "password=password123"
```

```json
{"user":{"id":4,"username":"redisdemo","displayName":"Redis デモ","email":"redis-demo@example.com","hasPassword":true}}
```

## 5. 4 種類のキーができる

```bash
$ docker compose exec redis redis-cli --scan | sort
spring:session:expirations:1789281900000
spring:session:index:org.springframework.session.FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME:redis-demo@example.com
spring:session:sessions:dc79eb93-0902-4876-9a0c-2185bd5ebf3a
spring:session:sessions:expires:dc79eb93-0902-4876-9a0c-2185bd5ebf3a
```

**セッション 1 件に対してキーは 4 つ**(うち 2 つは共有)。役割はこう分かれている。

| キー | 型 | 役割 |
| --- | --- | --- |
| `spring:session:sessions:<id>` | Hash | **セッション本体。**属性が入る |
| `spring:session:sessions:expires:<id>` | String | **影のキー。**期限切れを知らせるためだけに存在する |
| `spring:session:index:...PRINCIPAL_NAME_INDEX_NAME:<email>` | Set | **principal 索引。**そのユーザーのセッション ID 一覧 |
| `spring:session:expirations:<分単位のepoch>` | Set | **期限グループ。**その分に切れるセッションの一覧 |

**索引のキー名にメールアドレスがそのまま入っている**のが目を引く。
GUI で眺めると「誰がログインしているか」が一覧でわかる。
裏返すと、**Redis が読めれば誰がログイン中か分かる**ということでもある
(だから本番では認証と暗号化を掛ける → [elasticache.md](elasticache.md))。

### Cookie に入っているもの

```bash
$ grep SESSION /tmp/cookies.txt
localhost  FALSE  /  FALSE  0  SESSION  ZGM3OWViOTMtMDkwMi00ODc2LTlhMGMtMjE4NWJkNWViZjNh
```

Base64 デコードするとキー名の `<id>` がそのまま出てくる。
**ブラウザが持っているのはこの ID だけ**で、中身は全部サーバー側にある。これがセッション方式の要点。

## 6. セッション本体(Hash)の中身

```bash
$ docker compose exec redis redis-cli HKEYS spring:session:sessions:dc79eb93-...
creationTime
maxInactiveInterval
lastAccessedTime
sessionAttr:SPRING_SECURITY_CONTEXT
```

MySQL 時代の `SPRING_SESSION` の列とほぼ同じ顔ぶれである。
**属性は `sessionAttr:` という接頭辞付きのフィールド**として入る。
CSRF トークンや、Google ログインの途中経過もここに増える。

値を見るとバイナリになっている。

```bash
$ docker compose exec redis redis-cli --no-raw HGET spring:session:sessions:dc79eb93-... creationTime
"\xac\xed\x00\x05sr\x00\x0ejava.lang.Long;\x8b\xe4\x90\xcc\x8f#\xdf\x02\x00\x01J\x00\x05value..."

$ ... HGET ... "sessionAttr:SPRING_SECURITY_CONTEXT"
"\xac\xed\x00\x05sr\x00=org.springframework.security.core.context.SecurityContextImpl..."
```

先頭の **`\xac\xed\x00\x05` は Java のシリアライズ形式のマジックナンバー**で、
「これは JDK シリアライズだ」という印。続いてクラス名が平文で見える。

**これは MySQL 時代と同じ状況**である。`SPRING_SESSION_ATTRIBUTES.ATTRIBUTE_BYTES` も
BLOB で、`SELECT` しても読めなかった。**Redis にしたから読めなくなったわけではない。**

### JSON にすれば読めるが、安くはない

`GenericJackson2JsonRedisSerializer` に差し替えれば中身まで読める。ただし必要な作業が多い。

1. `SecurityJackson2Modules.getModules()` を登録する(Spring Security 標準クラスはこれで足りる)
2. **自作の `AppUserDetails` / `AppOidcUser` は別途 mixin を書く。**
   Spring Security は既知でないクラスの復元を許可リストで弾くので、`@JsonTypeInfo` の登録も要る
3. `AppOidcUser` のコンストラクタは `User` エンティティを受け取る形なので、
   **JSON 復元用のコンストラクタを認証クラスに足す**ことになる

失敗したときの出方が「Google ログインのときだけセッション復元に失敗する」という分かりにくい形なので、
このリポジトリでは **JDK シリアライズのまま**にしている(→ ADR-0015 の「検討したが採らなかった選択肢」)。

**読めなくて困るかというと、実はあまり困らない。**
「誰が・いくつ・あと何秒」はキー名と TTL だけで全部わかるからである。

## 7. なぜキーが 2 つに分かれているのか — 300 秒の意味

ここがこの仕組みで一番面白いところ。

```bash
$ R="docker compose exec redis redis-cli"
$ $R TTL spring:session:sessions:0ef684d1-...
86699
$ $R TTL spring:session:sessions:expires:0ef684d1-...
86399
```

**差はちょうど 300 秒(5 分)。** 影のキーが**先に**切れるようになっている。

なぜこの順序が要るのか。[redis-basics.md](redis-basics.md) の 3 節のとおり、
**TTL は Set の要素には張れない**。だから索引 Set に入ったセッション ID は、
誰かが `SREM` しないと残り続ける。その「誰か」は期限切れ通知を受け取った Spring Session である。

そして **索引からセッション ID を消すには、そのセッションが誰のものか(= principal 名)を知る必要がある。**
principal 名はセッション本体の中にある。つまり:

> **影のキーが切れて通知が飛んだとき、本体がまだ生きていないと掃除ができない。**
> そのための 5 分の猶予が、この TTL の差である。

### 実験で確かめる

**まず間違ったやり方から。** 本体を先に消すと掃除できない。

```bash
$ $R SCARD <索引>          # 2
$ $R DEL spring:session:sessions:<id>   # 本体を先に消す
$ sleep 3; $R SCARD <索引> # 2 ← 減らない
```

principal 名を読む先が無くなったので、索引に ID が残る。

**正しい順序 — 影のキーだけ先に切らす。**

```bash
$ $R SCARD <索引>          # 1
$ $R EXPIRE spring:session:sessions:expires:<id> 1   # 影だけ 1 秒に縮める
$ sleep 5
$ $R SCARD <索引>          # 0 ← 掃除された
$ $R --scan
spring:session:sessions:0ef684d1-e577-47cb-98f1-cd0bb2f0be42   ← 本体はまだ残っている
```

索引が 0 になり、**要素が空になった Set は Redis が自動的にキーごと削除する**。
本体は TTL(残り 5 分)が来たら消える。

### 期限グループ(`expirations:`)の役割

4 つ目のキーがまだ説明されていない。

```bash
$ $R SMEMBERS spring:session:expirations:1789281900000
expires:dc79eb93-0902-4876-9a0c-2185bd5ebf3a
$ $R TTL spring:session:expirations:1789281900000
-1                     ← TTL が付いていない
```

`1789281900000` は **分単位に丸めた epoch ミリ秒**で、「この分に期限が来るセッション」の一覧である。
`INFO keyspace` で `keys=6, expires=5` と「1 つだけ TTL 無し」に見えたのは、このキーのこと。

なぜこれが要るのか。[redis-basics.md](redis-basics.md) の 3 節のとおり、
**Redis は誰もアクセスしないキーをいつ消すか保証しない**。
タイムアウトしたセッションは、まさに誰もアクセスしないキーである。
放っておくと `expired` 通知がいつ飛ぶか分からない。

そこで `RedisIndexedSessionRepository` は **1 分ごとのスケジュールタスク**で、
この分のグループに入っているキーに `EXISTS` を打つ。
**わざと触ることで Redis に遅延削除を発動させ、`expired` 通知を出させている。**

```
Spring の 1 分タスク ──EXISTS──> Redis(期限切れに気づく)
                                    │
                                    └─ expired イベント ──> Spring が索引を SREM
```

MySQL 時代は、同じ仕事を毎分の `DELETE FROM SPRING_SESSION WHERE EXPIRY_TIME < ?` でやっていた。
**「期限切れの掃除」という仕事自体は消えていない。形が変わっただけ**である。

## 8. 全端末強制ログアウト

`UserSessionManager` はこの索引を引いて、そのユーザーのセッションをまとめて消す。
パスワードリセット完了時は全部、ログイン中のパスワード変更時は**操作中の 1 つを残して**消す。

3 端末からログインした状態で、端末1 がパスワードを変える。

```bash
$ $R SCARD <索引>
3

$ curl -s -b /tmp/pc1.txt -X PUT http://localhost:8080/api/auth/password \
    -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $T" \
    -d '{"currentPassword":"password123","newPassword":"newpassword456"}'
HTTP:204

$ $R SCARD <索引>
1
```

```
端末1 の /me : 200                 ← 操作した本人は残る
端末2 の /me : {"user":null}       ← 追い出された
端末3 の /me : {"user":null}       ← 追い出された
```

**これが JWT では素直にできない**ことが、ADR-0002 が JWT を却下した理由の 1 つ目だった。
発行済みトークンを失効させるにはブラックリストを持つことになり、結局セッションを再発明する。

> **なおこの削除は通知を使っていない。** `deleteById` が索引も直接 `SREM` するので、
> 明示的なログアウトやパスワード変更では通知なしで綺麗に消える。
> 通知が要るのは **タイムアウトで消えたとき**だけである。

## 9. GUI(RedisInsight)で見る

`docker compose up -d` すると `http://localhost:5540` で開く。

初回だけ接続先の登録が要る。**公式の `redis` イメージに GUI は入っていない**ので、
GUI は別コンテナで、Redis のことをまだ知らない状態で立ち上がるためである。

| 項目 | 値 |
| --- | --- |
| Host | `redis`(compose のサービス名。`localhost` ではない) |
| Port | `6379` |
| Username / Password | 空のまま(ローカルは認証なし) |

`redis-test` を見たいときは Host に `redis-test` を入れてもう 1 つ登録する。

GUI で見ると、`spring:session:` の階層がツリー状に畳まれて表示され、
TTL が秒数で出て、Hash のフィールドが表で並ぶ。
**`--scan` を打たなくても「誰が何セッション持っているか」が一望できる**のがありがたい。

値がバイナリのフィールドは GUI でも読めない(6 節のとおり)。

## 10. MySQL 時代との対比

| | MySQL(Spring Session JDBC) | Redis(Spring Session Data Redis) |
| --- | --- | --- |
| 置き場所 | `SPRING_SESSION` / `SPRING_SESSION_ATTRIBUTES` の 2 テーブル | 4 種類のキー |
| 期限切れの掃除 | 毎分の `DELETE ... WHERE EXPIRY_TIME < ?` | TTL + キースペース通知 + 毎分の `EXISTS` 突き |
| 全セッション検索 | `PRINCIPAL_NAME` の index で 1 クエリ | 索引 Set を `SMEMBERS` |
| 属性の中身 | BLOB(読めない) | バイナリ(読めない) |
| 目視 | `SELECT * FROM SPRING_SESSION` | `redis-cli --scan` / RedisInsight |
| スキーマ管理 | Flyway(V3 で作成、**V4 で削除**) | 不要(スキーマという概念が無い) |
| 負荷点 | アプリ DB と同じ RDS | **別の場所に分かれる** |

**「セッションの読み書きがアプリ DB と同じところに乗らない」**のが、Redis に移して実際に変わった点。
投稿の検索が重くて RDS が詰まっても、ログイン状態の読み書きは巻き込まれない。

この対比のより広い版(Laravel / Better Auth なども含めた 5 段階の整理)は
[session-store-and-other-frameworks.md](../java/spring/session-store-and-other-frameworks.md) にある。

## 11. テスト用の Redis

`@SpringBootTest` は実際にログインを通すので、**テストもセッションを Redis に書く**。
開発用と同じ Redis を使うと、GUI で観察中のキーにテスト由来のセッションが混ざる。

このリポジトリは **`redis-test` という別コンテナ**に向けている。

```groovy
// backend/build.gradle
tasks.named('test') {
    environment 'DB_NAME', 'app_test'
    environment 'REDIS_HOST', 'redis-test'
}
```

分離できていることは、テスト後に両方を覗けばわかる。

```bash
$ docker compose exec redis-test redis-cli --scan | grep index
spring:session:index:...PRINCIPAL_NAME_INDEX_NAME:flow-test@example.com   ← テスト用ユーザーだけ
```

MySQL は「同じコンテナで database を分ける」、Redis は「コンテナごと分ける」と**方式が違う**。
Redis にも論理データベース番号(0〜15)があるので同じ形にもできたが、分離の強さを優先した
(→ [docs/test/README.md](../../test/README.md))。

## 12. つまずきポイント

- **`spring.session.redis.*` は Boot 4 で効かない。** `spring.session.data.redis.*` が正しい(2 節)
- **`repository-type: indexed` を忘れると起動しない。** 既定は `FindByIndexNameSessionRepository` を実装しない(2 節)
- **`notify-keyspace-events` は Redis 側の設定。** アプリの設定ではない。
  ローカルは `docker-compose.yml` の `command`、本番は ElastiCache のパラメータグループ
- **ただし Spring Session は、既定だとこの設定をアプリ自身が入れにいく。** 起動時に
  `CONFIG GET` / `CONFIG SET notify-keyspace-events` を送る(`configure-action` の既定値 `notify-keyspace-events`)。
  **ElastiCache は `CONFIG` コマンドを受け付けない**ので、AWS でだけ起動に失敗する。
  ```
  ERR unknown command 'CONFIG', with args beginning with: 'GET' 'notify-keyspace-events'
  ```
  ローカルの素の Redis では通るので、**テストが全部通っていても AWS で初めて落ちる**。
  `spring.session.data.redis.configure-action: none` でアプリからの書き換えを止める。
  その代わり、Redis 側の設定が外れていても**起動は成功し、索引の掃除だけが黙って止まる**。
  設定が効いているかは `redis-cli CONFIG GET notify-keyspace-events` で確かめる
  (ElastiCache では打てないので、コンソールのパラメータグループで見る)
- **索引の掃除を試すとき、本体を先に消すと掃除されない。** 影のキーだけ切らすこと(7 節)
- **タスクモード(migrate / db-ops)も Redis を要求する。**
  `indexed` は起動時に通知を購読するため、遅延接続では済まない。
  ECS のタスク定義 3 つ全部に接続情報が要る(→ [elasticache.md](elasticache.md))
- **`KEYS *` を本番で打たない。** 単一スレッドなので全部止まる。`--scan` を使う
- **ログインのパラメータ名は `email`。** `username` ではない(`SecurityConfig` の `usernameParameter`)

## 関連

- [redis-basics.md](redis-basics.md) — TTL とキースペース通知の基礎
- [elasticache.md](elasticache.md) — 本番で同じものを AWS に建てる
- [ADR-0002](../../adr/0002-session-cookie-over-jwt.md) — セッション Cookie 方式を選んだ決定
- [ADR-0015](../../adr/0015-session-store-on-redis.md) — 保存先を Redis にした決定
- [csrf.md](../browser/csrf.md) — Cookie セッションに CSRF 対策が要る理由
