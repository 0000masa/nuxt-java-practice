# Redis の基礎 — セッションを置く前に知っておくこと

セッション管理([session-management.md](session-management.md))と
ElastiCache([elasticache.md](elasticache.md))を読む前提になる、Redis そのものの性質をまとめる。

このノートの数値・出力は、**このリポジトリの `redis` コンテナ(Redis 7.4.11)で実際に実行した結果**である。

```bash
docker compose exec redis redis-cli
```

## まず結論(3 行)

- Redis は **メモリ上のデータ構造サーバー**。「キー → 値」だが、**値に型がある**のが KVS と違う
- **TTL はキー単位**で設定する。要素単位には設定できない。これがセッション管理の設計を決めている
- 落ちると消える。永続化の仕組みはあるが、**「消えても困らない設計」が前提**の道具である

## 1. 「値に型がある」ということ

素朴な KVS は「キー → 文字列」だが、Redis は値が **データ構造**になっている。

| 型 | 中身 | 主なコマンド | Spring Session での用途 |
| --- | --- | --- | --- |
| String | 単なる値 | `SET` / `GET` | 期限管理用の影のキー |
| **Hash** | フィールドと値の表 | `HSET` / `HGET` / `HKEYS` | **セッション本体** |
| List | 順序付きの列(両端キュー) | `LPUSH` / `RPOP` | 使わない |
| **Set** | 重複なしの集合 | `SADD` / `SREM` / `SMEMBERS` / `SCARD` | **principal 索引・期限グループ** |
| Sorted Set | スコア付きの集合 | `ZADD` / `ZRANGEBYSCORE` | 使わない(バージョンによる) |

型があることの意味は、**「一部だけ読み書きできる」**という点にある。
セッションを JSON 文字列として String に入れると、1 つの属性を更新するにも全体を読んで書き戻す必要がある。
Hash なら `HSET <key> lastAccessedTime <値>` で **その 1 フィールドだけ**触れる。

型は後から変えられない。既に Hash があるキーに `SET` すると `WRONGTYPE` エラーになる。

```
127.0.0.1:6379> TYPE spring:session:sessions:a6471814-a531-4f0f-8285-bc5d654e43b3
hash
```

### 内部エンコーディングは要素数で変わる

同じ Set でも、要素が少ないうちは配列的な `listpack`、増えると `hashtable` になる。
メモリ効率と速度のトレードオフを Redis が自動で切り替えている。

```
索引(セッション 1 件)  → 256 bytes  / encoding=listpack
セッション本体(Hash)   → 2024 bytes / encoding=hashtable
```

`OBJECT ENCODING <key>` / `MEMORY USAGE <key>` で確認できる。
**セッション 1 件がおよそ 2KB** なので、0.5GB のノードなら単純計算で 25 万件入る
(実際は後述の予約メモリがあるのでもっと少ない)。

## 2. 単一スレッドで動く

Redis はコマンドの実行を **1 本のスレッドで順番に処理する**。
排他制御が要らず、**すべてのコマンドがアトミック**になるのはこのため。

```
multiplexing_api:epoll
io_threads_active:0
```

ネットワーク I/O は epoll で多重化しているので、
「1 スレッド = 1 接続しか捌けない」という意味ではない。捌く順番が 1 本なだけである。

**ここから出てくる実務上の注意が 1 つある。** `KEYS *` のように全キーを走査するコマンドは、
その間ずっと他のコマンドを止める。**本番で `KEYS` は打たない。**
代わりに少しずつ返す `SCAN` を使う。

```bash
redis-cli --scan              # 内部で SCAN を繰り返してくれる
redis-cli --scan --pattern 'spring:session:sessions:*'
```

## 3. TTL — キー単位でしか張れない

`EXPIRE <key> <秒>` で有効期限を設定し、`TTL <key>` で残りを見る。`-1` は「期限なし」。

```
127.0.0.1:6379> TTL spring:session:sessions:a6471814-a531-4f0f-8285-bc5d654e43b3
(integer) 86699
```

**重要なのは「キー単位」という点。** Set の要素ごとや Hash のフィールドごとには張れない。

> セッション本体は TTL で勝手に消えるのに、
> 「このユーザーのセッション ID 一覧」を持つ Set の**要素**は自動では消えない。
> だから誰かが `SREM` しに行く必要がある — これが
> [session-management.md](session-management.md) の中心的な話になる。

### 期限切れキーは「即座には」消えない

TTL が 0 になった瞬間にメモリから消えるわけではない。消えるのは次のどちらか。

1. **遅延削除(lazy)** — 誰かがそのキーにアクセスしたとき。Redis は期限を見て「無い」と答え、そこで削除する
2. **能動削除(active)** — Redis が定期的に、期限付きキーからランダムにサンプリングして期限切れを探す

つまり **誰もアクセスしないキーは、いつ消えるか分からない**。
サンプリングに当たるまで残り、メモリも占有し続ける。

これは「期限切れを検知して何かしたい」仕組みにとって厄介で、
Spring Session は **わざとキーに触りに行く**という回避策を持っている(→ session-management.md)。

### 現在の状況を見る

```
127.0.0.1:6379> INFO keyspace
db0:keys=6,expires=5,avg_ttl=52031419
```

`keys=6` のうち `expires=5` — **1 つだけ TTL が付いていない**。
どのキーがそれなのかは session-management.md で扱う。

## 4. 論理データベース(0〜15)

1 つの Redis サーバーの中に、**番号で分かれた名前空間**が既定で 16 個ある。

```
127.0.0.1:6379> CONFIG GET databases
1) "databases"
2) "16"
```

`SELECT 1` で切り替わり、`FLUSHDB` はそのデータベースだけ、`FLUSHALL` は全部を消す。
MySQL の database(`app` / `app_test`)に少し似ているが、**似ているだけ**で違いが 2 つある。

- **アクセス制御が分かれない。** Redis の論理 DB は「番号で仕切っただけ」で、権限は分けられない
- **クラスターモードでは 0 番しか使えない。** ElastiCache でクラスターモードを有効にすると番号は使えなくなる

このリポジトリは **論理 DB では分けず、テスト用に別コンテナ(`redis-test`)を立てている**。
理由は分離の強さを優先したため(→ [docs/test/README.md](../../test/README.md))。

## 5. 永続化 — RDB と AOF

Redis は「メモリ上」と言いつつ、ディスクに書く方法を 2 つ持っている。

| 方式 | 中身 | 復旧単位 | 既定 |
| --- | --- | --- | --- |
| **RDB** | ある瞬間のスナップショットをバイナリで保存 | 最後のスナップショット時点 | 有効(`save 3600 1` など) |
| **AOF** | 実行した書き込みコマンドを追記していく | ほぼ最後の 1 秒まで | 無効 |

このリポジトリのローカルは **AOF を有効にしている**。

```yaml
# docker-compose.yml
command: >
  redis-server
  --appendonly yes
  --notify-keyspace-events Egx
```

```
127.0.0.1:6379> CONFIG GET appendfsync
1) "appendfsync"
2) "everysec"          # 1 秒ごとに fsync。最悪 1 秒ぶん失う
```

`docker compose down` してもログイン状態とキーが残るので、
GUI での観察が再起動をまたいで続けられる。

**「落ちたら全部消える」を体験したいときは自分で消す。**

```bash
docker compose exec redis redis-cli FLUSHALL   # 全部消える = 全員ログアウト
```

なお本番(ElastiCache)は既定でバックアップ無効なので、**ローカルのほうが手厚い**。
この差は意図的なもので、理由は [elasticache.md](elasticache.md) に書いた。

## 6. メモリが埋まったらどうなるか — `maxmemory-policy`

Redis には「メモリ上限」と「上限に達したときの振る舞い」がある。

```
127.0.0.1:6379> CONFIG GET maxmemory
2) "0"                 # 0 = 無制限(コンテナのメモリまで使う)
127.0.0.1:6379> CONFIG GET maxmemory-policy
2) "noeviction"
```

| ポリシー | 振る舞い |
| --- | --- |
| `noeviction` | **書き込みを拒否してエラーを返す。**読み取りは通る |
| `volatile-lru` | TTL 付きのキーを、最近使われていない順に捨てる |
| `allkeys-lru` | TTL の有無に関係なく、最近使われていない順に捨てる |
| `volatile-ttl` | TTL 付きのキーを、期限が近い順に捨てる |

**Docker 公式イメージの既定は `noeviction`。**
一方 **ElastiCache の既定は `volatile-lru`** で、ここが食い違っている。

これは事故になりうる。Spring Session のセッション本体(Hash)と影のキーには**TTL が付いている**ので、
`volatile-lru` のままメモリが逼迫すると **セッションが静かに消え、ユーザーは身に覚えのないログアウトをする**。
ログには何も出ない。

**Spring Session のキーが全部 TTL 付きなわけではない。**
principal 索引(Set)だけ TTL が無く、`volatile-lru` の対象外になる。
つまり本体が追い出されても索引は残り、**実体の無いセッション ID が索引に居座る**。
`volatile-lru` は「一部だけ消える」ぶん、かえって始末が悪い。

```
$ docker compose exec redis redis-cli TTL spring:session:sessions:<id>
(integer) 76645        # 本体: TTL あり → 追い出される
$ docker compose exec redis redis-cli TTL 'spring:session:index:...PRINCIPAL_NAME_INDEX_NAME:<email>'
(integer) -1           # 索引: TTL なし → 追い出されない
```

`volatile-lru` が既定なのは、Redis を**キャッシュ**として使う前提だから。
キャッシュなら「消えても再計算すればいい」ので正しい。
**セッションは再計算できない**ので前提が違う。
このリポジトリは ElastiCache 側で `noeviction` を明示している(→ [elasticache.md](elasticache.md))。

## 7. キースペース通知

「キーが消えた」「期限が切れた」といった出来事を、Redis が Pub/Sub で流す仕組み。
**既定は無効**で、`notify-keyspace-events` に文字を並べて有効にする。

```
127.0.0.1:6379> CONFIG GET notify-keyspace-events
1) "notify-keyspace-events"
2) "gxE"
```

| 文字 | 意味 |
| --- | --- |
| `K` | キースペース通知。`__keyspace@0__:<キー名>` に**イベント名**が流れる |
| `E` | キーイベント通知。`__keyevent@0__:<イベント名>` に**キー名**が流れる |
| `g` | 汎用コマンド(`DEL` / `EXPIRE` / `RENAME` など) |
| `x` | 期限切れ(`expired`) |
| `A` | `g$lshzxet` の全部をまとめた略記 |

このリポジトリは **`Egx`** を指定している。
「キーイベント側(`E`)で、汎用(`g`)と期限切れ(`x`)を流す」という意味で、
Spring Session が必要とする最小構成である。`A` にすると不要なイベントまで流れる。

自分で覗いてみると分かりやすい。

```bash
# 端末1: 購読する
docker compose exec redis redis-cli PSUBSCRIBE '__keyevent@0__:*'

# 端末2: キーを作って消す
docker compose exec redis redis-cli SET foo bar EX 3
docker compose exec redis redis-cli DEL foo
```

> **注意:** 3 節のとおり期限切れキーは即座には消えないので、
> `expired` イベントも **TTL が 0 になった瞬間には飛ばない**。
> 誰かが触るか、能動削除に当たるまで遅れる。
> Spring Session がこれにどう対処しているかが session-management.md の見どころになる。

## 8. このリポジトリで使うコマンド早見表

```bash
R="docker compose exec redis redis-cli"

$R ping                       # 生きているか
$R --scan                     # 全キー(KEYS * の代わり。本番でも安全)
$R --scan --pattern 'spring:session:sessions:*'
$R TYPE   <key>               # 型
$R TTL    <key>               # 残り秒数(-1 = 期限なし、-2 = 存在しない)
$R HKEYS  <key>               # Hash のフィールド名一覧
$R HGET   <key> <field>       # Hash の 1 フィールド(--no-raw でバイナリを可視化)
$R SMEMBERS <key>             # Set の要素
$R SCARD  <key>               # Set の要素数
$R OBJECT ENCODING <key>      # 内部表現
$R MEMORY USAGE <key>         # 1 キーのメモリ
$R INFO keyspace              # キー数と TTL 付きの数
$R DBSIZE                     # キー数
$R FLUSHALL                   # 全消し(= 全員ログアウト)
```

GUI で見たい場合は RedisInsight を使う → [session-management.md](session-management.md)。

## 関連

- [session-management.md](session-management.md) — Spring Session が Redis をどう使っているか
- [elasticache.md](elasticache.md) — AWS で Redis を建てる
- [ADR-0015](../../adr/0015-session-store-on-redis.md) — セッションの保存先を Redis にした決定
- [session-store-and-other-frameworks.md](../java/spring/session-store-and-other-frameworks.md) — セッションの保存先を 5 段階に並べた話
