# 接続プールの大きさと Aurora のフェイルオーバー

Aurora の `db.t4g.medium` は同時接続の上限(`max_connections`)が **90** しかない。
Hikari を既定のまま使うと、このリポジトリの prod はデプロイのたびに上限を超える計算になる。
このノートは、**プールの大きさをどう見積もったか**と、**DNS で切り替わるエンドポイントとプールの相性**をまとめる。

前提 → [read-write-split.md](read-write-split.md)
決定 → [ADR-0016](../../adr/0016-aurora-provisioned-with-read-write-split.md)
設計 → [フェーズ19 設計書](../../superpowers/specs/2026-09-13-phase19-aurora-design.md) の決定8・9

## まず結論(4 行)

- Hikari は**起動した時点で決まった本数の接続を張り、使っていなくても持ち続ける**(既定は 10 本の固定サイズ)
- **Blue/Green デプロイ中は旧タスクと新タスクが同時にプールを持つ**ので、接続数は 2 倍になる
- prod の最悪ケースから逆算して、Writer / Reader のプールを**各 5 本**にした(環境変数で変えられる)
- フェイルオーバーへの備えは**既定値のまま実測する**。困ったら既定値の調整ではなく AWS Advanced JDBC Wrapper を入れる

## 1. 接続プールとは

DB の接続を 1 本張るのは高くつく(TCP の接続、TLS のハンドシェイク、ユーザー認証)。
リクエストのたびに張って閉じると遅いので、**張った接続を取っておいて使い回す。** これが接続プールで、
Spring Boot は標準で HikariCP を使う。

| 設定 | 意味 | 既定 |
|---|---|---|
| `maximumPoolSize` | 1 つのプールが持てる接続の最大本数(使用中 + 待機中)。**1 タスクが DB に同時に投げられるクエリ数の上限** | 10 |
| `minimumIdle` | 最低限確保しておく待機中の接続の本数。最大値より小さくすると、使われない接続は `idleTimeout` 後に閉じられる | `maximumPoolSize` と同じ |
| `connectionTimeout` | 全部使用中のとき、空きを待つ時間。過ぎると例外 | 30 秒 |
| `idleTimeout` | 待機中の接続を閉じるまでの時間(`minimumIdle` < 最大のときだけ効く) | 10 分 |
| `maxLifetime` | 1 本の接続を使い続ける最長時間。過ぎると張り直す | 30 分 |

**HikariCP の公式は `minimumIdle` を設定せず、固定サイズで使うことを推奨している**
(負荷が急に来たときに、接続を新しく張る遅れを出さないため)。このリポジトリもそれに従っている。
その結果、**「タスク数 × `maximumPoolSize`」の接続が常に張られている**。

## 2. Blue/Green デプロイ中に接続が 2 倍になる理由

今の設定(`CodeDeployDefault.ECSAllAtOnce`、`TerminationWaitTimeInMinutes: 0`)での流れ:

```
① 旧タスク(Blue)がトラフィックを受けている
② CodeDeploy が新タスク(Green)を Blue と同じ数だけ起動する
     ← Green の Spring Boot が起動した瞬間に Hikari が接続を張る
     ← この時点で Blue と Green の両方が接続を持っている(2 倍)
③ Green が ALB のヘルスチェックに通るのを待つ(数分)
④ ALB のリスナーを Green に一度に切り替える
⑤ 待たずに Blue を停止する
     ← ここで Blue の接続が消える
```

2 倍になるのは ②〜⑤ で、**長いのは「Green がトラフィックを受ける前」の ②〜③**。
Green は使う前から接続を張り、Blue は使わなくなっても停止されるまで接続を持っている。

## 3. prod の見積もり

| 条件 | 既定(10 本)のまま | 各 5 本にした後 |
|---|---|---|
| `WebMaxCapacity` 4 × Blue/Green で 2 倍 = 8 タスク × Writer プール | 80 | 40 |
| マイグレーションのタスク(Writer プール + Flyway の非プール接続 1) | 11 | 6 |
| **Writer の合計**(上限 90) | **約 91 — 超える** | 約 46 |
| Reader: 8 タスク × Reader プール(DNS の振り分け次第で 2 台のうち 1 台に集中しうる) | 最悪 80 | 最悪 40 |

残りの枠は、人間の調査や `db-ops` タスク(ユーザー作成・任意 SQL)に使う。
アラーム(`rds-writer-connections-high` / `rds-reader-connections-high`)は 90 の 8 割の 72 で鳴る。

stg は最大 2 タスク、Blue/Green 中でも 4 タスクなので、既定のままでも収まっていた。

値はパラメータ `DbWriterPoolSize` / `DbReaderPoolSize` → taskdef の `DB_WRITER_POOL_SIZE` / `DB_READER_POOL_SIZE`
→ `application.yml` の `maximum-pool-size` と流れる。

**接続数そのものが問題になるほど規模が大きい場合**は、RDS Proxy を挟んで接続を束ねる手もある
(Aurora のドキュメントも、接続の開閉が多い・長寿命の接続が多い場合に勧めている)。今の規模では要らない。

## 4. フェイルオーバーと DNS

Aurora の Writer が落ちると、Reader のどれかが Writer に昇格し、**クラスタエンドポイントの DNS が新しい Writer に向き直る。**
長く生きるプールと組み合わせると、次の 2 つが起きうる。

| 起きうること | 仕組み |
|---|---|
| **フェイルオーバー後、しばらく書き込みが失敗する** | JVM が古い IP をキャッシュしていると、元 Writer(今は Reader)に繋ぎ直して `--read-only` のエラーが続く |
| **prod の Reader 2 台に負荷が偏る** | Reader エンドポイントは DNS を引いたときに 1 台を返すだけ。プールは接続を最長 30 分使い回すので、起動時に引いた Reader に張り付く |

対策として思いつくのは、JVM の DNS キャッシュ(`networkaddress.cache.ttl`)や `maxLifetime` を短くすること。
**このリポジトリはあえて何もしていない。** 理由は 2 つ。

1. **先に設定を変えると、その設定が効いたのかを比べる基準が無くなる。** まず既定のまま測る
2. **困ったときの本命は既定値の調整ではない。** TTL を短くしても「DNS が切り替わるのを待つ」構造は変わらない。
   AWS Advanced JDBC Wrapper のフェイルオーバープラグインはクラスタの構成を直接見てつなぎ替えるので構造ごと変わり、
   読み書き分離プラグインは Reader の選び方も持っているので偏りも解ける

### stg で測ること(設計書 §7)

```bash
aws rds failover-db-cluster --db-cluster-identifier nuxt-java-practice-stg-db
```

- 書き込みのエラーが何秒続いたか(アプリのログ)
- Reader ごとの接続数の偏り(`DatabaseConnections` をインスタンス単位で見る。prod 相当の偏りは Reader 2 台の構成でないと出ない)
- イベント購読(`db-cluster` の failover カテゴリ)が Slack に届くか
