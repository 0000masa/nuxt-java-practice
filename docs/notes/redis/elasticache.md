# ElastiCache — AWS でセッション用の Redis を建てる

ローカルの `redis` コンテナ 1 行に対して、AWS では何を用意することになるのかを、
このリポジトリの `cloudformation/app.yml` に実際に書いたものに沿って追う。

前提 → [redis-basics.md](redis-basics.md) / [session-management.md](session-management.md)
決定 → [ADR-0015](../../adr/0015-session-store-on-redis.md)
設計 → [フェーズ18 設計書](../../superpowers/specs/2026-09-12-phase18-redis-session-design.md)

## まず結論(4 行)

- コンテナ 1 つに対して、AWS では **リソース 8 つ**(本体・サブネットグループ・パラメータグループ・SG・SNS・Chatbot・アラーム 2 本)
- **Serverless は選べなかった。** パラメータグループを持てず `notify-keyspace-events` を設定できないため
- **ElastiCache は既定で認証が無い。** SG を通れば誰でも `KEYS *` が打てるので、暗号化と AUTH を自分で掛ける
- **Web 以外のタスク(マイグレーション)にも接続情報が要る。** 忘れるとデプロイが止まる

## 1. ローカルとの対応

| | ローカル | AWS |
| --- | --- | --- |
| 本体 | `redis:7` コンテナ 1 つ | `AWS::ElastiCache::ReplicationGroup` |
| 置き場所 | compose のネットワーク | `AWS::ElastiCache::SubnetGroup`(プライベートサブネット 2 つ) |
| 設定 | `command: redis-server --notify-keyspace-events Egx` | `AWS::ElastiCache::ParameterGroup` |
| アクセス制限 | なし(compose 内から自由) | `AWS::EC2::SecurityGroup`(ECS からの 6379 のみ) |
| 認証 | なし | AUTH トークン(SSM SecureString) |
| 暗号化 | なし | 保管時・転送時とも有効 |
| 監視 | 目視 | CloudWatch アラーム 2 本 → SNS → Slack |
| GUI | RedisInsight コンテナ | **無い**(踏み台か Session Manager 経由で `redis-cli`) |
| バージョン | 7.4 | **7.1**(理由は 3 節) |

**GUI が本番に無い**のが実感しやすい差。プライベートサブネットにいるので、
ブラウザからも手元の `redis-cli` からも直接は届かない。
ローカルで構造を覚えておくことに意味があるのはこのため。

## 2. Serverless かノード指定型か

ElastiCache には 2 つの建て方がある。

| | Serverless | ノード指定型 |
| --- | --- | --- |
| 容量 | 自動 | `CacheNodeType` で固定 |
| 料金 | 使った GB-時 + ECPU。**最小構成でも月 $90 前後** | `cache.t4g.micro` で約 $0.016/時(≒ 月 $12) |
| パラメータグループ | **持てない** | 持てる |
| 起動 | 速い | 数分 |

**このリポジトリは Serverless を選べなかった。**
[session-management.md](session-management.md) §7 のとおり、索引の掃除に
`notify-keyspace-events` が要るが、これはパラメータグループでしか設定できない。

仮に通知が不要だったとしても、**使い終わったら撤収する運用**では
「最小構成でも常時 $90」は噛み合わない。時間課金の安さが効く。

> 逆に、常時稼働で負荷が読めないシステムなら Serverless は素直に良い選択になる。
> **却下したのはこのリポジトリの事情**であって、Serverless が劣っているわけではない。

## 3. エンジンとバージョン — Redis OSS は 7.1 が最後

2024 年に Redis 社がライセンスを変更(RSALv2 / SSPL)したことを受けて、
AWS は Linux Foundation の **Valkey**(Redis のフォーク)に舵を切った。

| | ElastiCache で使えるバージョン | 料金 |
| --- | --- | --- |
| Redis OSS | **7.1 が最後**(8 系は提供されない) | 標準 |
| Valkey | 7.2 / 8.x | 約 20% 安い |

Docker Hub の `redis` は 8 系まで進んでいるので、**放っておくとローカルと本番がずれる**。
このリポジトリはローカルを `redis:7` に固定してメジャーを揃えた。

Valkey を採らなかったのは技術的な理由ではない(RESP 互換で Lettuce はそのまま繋がる)。
**リポジトリ全体で Redis と Valkey の用語が割れるのを避けた**だけである。
料金差も `cache.t4g.micro` では月 $2〜3 で判断材料にならない。
→ [ADR-0015](../../adr/0015-session-store-on-redis.md) の「検討したが採らなかった選択肢」

## 4. `ReplicationGroup` と `CacheCluster`

CloudFormation には Redis を建てる型が 2 つある。

- `AWS::ElastiCache::CacheCluster` — 最小。書く量が少ない
- `AWS::ElastiCache::ReplicationGroup` — レプリケーションを前提にした型

**`AtRestEncryptionEnabled` / `TransitEncryptionEnabled` / `AuthToken` は
`ReplicationGroup` 専用**で、`CacheCluster` には存在しない。
つまり `CacheCluster` で建てると、後から暗号化を足したくなった時点で**書き直し(= 作り直し)**になる。

ノード 1 台でも `ReplicationGroup` に `NumCacheClusters: 1` を書けば、
暗号化が使えるうえ、レプリカを増やすのも数字 1 つで済む。

```yaml
RedisReplicationGroup:
  Type: AWS::ElastiCache::ReplicationGroup
  Properties:
    Engine: redis
    EngineVersion: !Ref RedisEngineVersion      # 7.1
    CacheNodeType: !Ref RedisNodeType           # cache.t4g.micro
    NumCacheClusters: !Ref RedisNumCacheClusters
    AutomaticFailoverEnabled: !If [RedisHasReplica, true, false]
    MultiAZEnabled:           !If [RedisHasReplica, true, false]
```

**ノードが 1 台のときに `AutomaticFailoverEnabled: true` にすると作成が失敗する。**
フェイルオーバー先が無いため。`Conditions` でノード数から自動的に決めている。

```yaml
RedisHasReplica: !Not [!Equals [!Ref RedisNumCacheClusters, 1]]
```

> **クラスターモードは無効のまま**にしている。有効にすると論理データベース番号が使えなくなり、
> Spring Session 側も `RedisClusterConnection` 前提の設定が要る。この規模では複雑さが増すだけ。

## 5. パラメータグループ — 2 つの値がこのリポジトリの判断そのもの

```yaml
RedisParameterGroup:
  Type: AWS::ElastiCache::ParameterGroup
  Properties:
    CacheParameterGroupFamily: redis7
    Properties:
      notify-keyspace-events: Egx
      maxmemory-policy: noeviction
```

### `notify-keyspace-events: Egx`

既定は空(無効)。これが無いと、TTL で消えたセッションの ID が principal 索引に残り続ける
(→ [session-management.md](session-management.md) §7)。
**Serverless を却下した直接の理由**でもある。

### `maxmemory-policy: noeviction`

**ElastiCache の既定は `volatile-lru`** で、これは「TTL 付きのキーを古い順に捨てる」設定。
Spring Session のセッション本体(Hash)と影のキーには TTL が付いているので、**既定のままだとメモリ逼迫時に
セッションが静かに消え、ユーザーが身に覚えのないログアウトをする**。ログにも出ない。

さらに、**principal 索引(Set)には TTL が無い**ので追い出されない。
本体だけが消えて索引は残るため、**実体の無いセッション ID が索引に居座る**という壊れ方をする
(キーごとの TTL の有無 → [session-management.md](session-management.md) §5・§7)。

`volatile-lru` が既定なのは Redis を**キャッシュ**として使う前提だから。
キャッシュなら消えても再計算すればよい。**セッションは再計算できない**ので前提が違う。

`noeviction` だと、埋まった時点で書き込みが拒否されてログインが 500 になる。
派手に壊れるが、**壊れたことが分かる**。静かに消えるより運用できる。

> ちなみに **Docker 公式イメージの既定は `noeviction`** なので、
> ローカルと ElastiCache で既定値が食い違っている。
> 「ローカルで動いたから本番も大丈夫」が成立しない典型例。

### 予約メモリ

ElastiCache は `reserved-memory-percent`(既定 25%)でノードのメモリの一部を
バックアップやフェイルオーバーのために取り置く。
**`cache.t4g.micro` の 0.5GiB がまるごとデータに使えるわけではない。**
アラームのしきい値を決めるときはこれを踏まえる。

## 6. 暗号化と認証 — ElastiCache は既定で無防備

**ElastiCache には RDS の `ManageMasterUserPassword` に相当する自動生成が無く、
既定では認証が一切ない。** SG さえ通れば誰でも `KEYS *` が打てる。

そして中に入っているのは **セッション ID そのもの**である。
[session-management.md](session-management.md) §5 のとおり、
索引のキー名にはメールアドレスまで入っている。**読めた人はそのまま成りすませる。**
RDS よりも事故ったときの被害が直接的なので、ここは掛けておく。

```yaml
    AtRestEncryptionEnabled: true
    TransitEncryptionEnabled: true
    AuthToken: !Sub "{{resolve:ssm-secure:${SsmParameterPath}redis_auth_token}}"
```

- **`AtRestEncryptionEnabled`** — **ディスクに書かれたデータ**を KMS で暗号化する。
  Redis はインメモリだが、スナップショット・レプリカ同期の一時ファイル・スワップはディスクに出る。
  **後から有効化できない**ので、スナップショットを取らない構成(9 節)でも付けておく
- **`TransitEncryptionEnabled`** — **ネットワークを流れるデータ**を TLS で包む。
  クライアント ↔ ノード間とノード間レプリケーションの両方が対象。
  アプリ側も TLS で喋る必要があるので `REDIS_SSL` と対になっている

`AuthToken` は `TransitEncryptionEnabled: true` が前提(平文で AUTH を送っては意味がない)。

### `{{resolve:ssm-secure}}` が使える珍しい場所

このテンプレートの `BasicAuthCredential` のコメントにあるとおり、
`{{resolve:ssm-secure:...}}` は**対応プロパティが限られている**。
WAF の `SearchString` では使えず、パラメータ経由にせざるを得なかった。

**ElastiCache の `AuthToken` はその対応リストに入っている**ので、
テンプレートから直接 SecureString を読める。
値はリポジトリにもテンプレートにも現れない。

> **実機で確認すること。** 対応リストは AWS 側で変わりうるので、
> 初回構築時に `{{resolve:ssm-secure}}` が解決されることを確かめる。
> 解決されない場合は `NoEcho: true` のパラメータ経由(BasicAuthCredential と同じ形)に落とす。

**読まれるタイミングが他の機密と違う。** `app_db_password` などは ECS がタスクを起動するときに
注入するので、**パラメータが無くてもスタックの作成は成功してしまう**。
`AuthToken` は CloudFormation がテンプレートを解決する時点で読むため、
**無ければスタック作成がその場で失敗する。** 作り忘れに早く気づける、良いほうの壊れ方である。

### トークンの文字種が DB パスワードより厳しい

**ここが一番引っかかる。** AuthToken に許される記号は **`!` `&` `#` `$` `^` `<` `>` `-` の 8 つだけ**で、
長さは 16〜128 文字(古い版のドキュメントでは「印字可能 ASCII から `/` `"` `@` を除く」という緩い書き方)。

**`openssl rand -base64 24` で作ってはいけない。** base64 の出力アルファベットは `A-Za-z0-9+/` で、
`+` と `/` はどちらの版でも禁止だからである。

```bash
$ for i in $(seq 1000); do openssl rand -base64 24; done | grep -c '[+/]'
678                        # 約 68%(理論値 63.8%)が禁止文字を含む
```

**確率なので「たまに通ってしまう」のが厄介。** 3 回に 1 回は通る値が出るので、
一度うまくいくと正しい作り方だと思い込み、作り直した日に落ちる。

英数字だけにしておけば、どの版の制限にも収まる。

```bash
LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32; echo
```

このリポジトリの DB パスワード(`'` と `\` だけが NG)との対比と、7 つ全部の作り方
→ [cloudformation-operations.md](../../infrastructure/cloudformation-operations.md) §4・§4-2

### アプリ側の受け取り

ローカル(平文・認証なし)と切り分けるため、環境変数 2 つで制御している。

```yaml
# application.yml
spring:
  data:
    redis:
      password: ${REDIS_PASSWORD:}      # 空なら AUTH を送らない
      ssl:
        enabled: ${REDIS_SSL:false}
```

## 7. 接続情報をどう渡すか — タスク定義が 3 つある

`app.yml` には ECS タスク定義が 3 つあるが、**Redis が要るのは 2 つ**である。

| タスク定義 | イメージ | Redis |
| --- | --- | --- |
| `AppTaskDefinition` | アプリ(Spring Boot) | **要る** |
| `MigrateTaskDefinition` | アプリ(`APP_TASK=migrate`) | **要る** |
| `DbOpsTaskDefinition` | `mysql:8`(SQL を流すだけ) | 要らない |

**マイグレーションのタスクに Redis が要るのが直感に反する。**
「Flyway を流すだけなのになぜ?」の答えはこう。

1. このタスクは **Web アプリケーションとして起動する**。
   `spring.main.web-application-type=none` にすると Spring Session の自動設定が動かず、
   `FindByIndexNameSessionRepository` を要求する `UserSessionManager` が解決できずに
   起動そのものが失敗する(`TaskRunner` の javadoc に実測として記録されている)
2. `repository-type: indexed` は **起動時にキースペース通知を購読しに行く**。
   購読は繋ぎっぱなしにする動作なので、Lettuce の遅延接続では済まず、その場で TCP を張る

**渡し忘れるとマイグレーションが起動できず、デプロイがそこで止まる。**
`DbOpsTaskDefinition` が要らないのは、Spring Boot ではなく MySQL クライアントを動かしているから。

```yaml
  # 両方のタスク定義に同じものを書く
  Environment:
    - Name: REDIS_HOST
      Value: !GetAtt RedisReplicationGroup.PrimaryEndPoint.Address
    - Name: REDIS_PORT
      Value: !GetAtt RedisReplicationGroup.PrimaryEndPoint.Port
    - Name: REDIS_SSL
      Value: "true"
  Secrets:
    - Name: REDIS_PASSWORD
      ValueFrom: !Sub arn:aws:ssm:...:parameter${SsmParameterPath}redis_auth_token
```

**クラスターモード無効なので `PrimaryEndPoint`。** 有効にすると `ConfigurationEndPoint` になる。
前者は**現在のプライマリを指す DNS 名**で、フェイルオーバーすると向き先が自動で張り替わる。
後者は接続先ではなく**構成を問い合わせる入口**で、クライアントが `CLUSTER SLOTS` を打って
全ノードを把握する必要がある(4 節の「複雑さが増す」の中身)。

同じ `!GetAtt` をアプリ・マイグレーション・`Outputs` の 3 箇所で使っているので、切り替えるならまとめて直す。
綴りは `Endpoint` ではなく **`EndPoint`**(RDS 側は `Endpoint` で不統一。間違えるとデプロイまで気づかない)。

SG は ECS タスク 3 つで共通なので、**Redis 側の ingress ルールは 1 本で足りる。**

```yaml
RedisSecurityGroup:
  SecurityGroupIngress:
    - IpProtocol: tcp
      FromPort: 6379
      ToPort: 6379
      SourceSecurityGroupId: !Ref EcsSecurityGroup
```

### フェーズ16(Code 系デプロイ)側にも同じものが要る

2 代目以降のタスク定義は `deploy/taskdef.json` / `deploy/taskdef-migrate.json` が正で、
CodeBuild が `sed` でプレースホルダを埋めて register する。**こちらにも書かないと 2 回目のデプロイで壊れる。**

```bash
# deploy/buildspec.yml
-e "s|__REDIS_HOST__|$(out RedisEndpoint)|g" \
-e "s|__REDIS_PORT__|$(out RedisPort)|g" \
```

`$(out ...)` はスタックの `Outputs` を引く関数なので、`app.yml` に `RedisEndpoint` / `RedisPort` を
出力として足してある。buildspec には**未置換のプレースホルダが残っていたら落とす `grep`** が
あるので、片方だけ書き忘れる事故は自動的に止まる。

## 8. 監視

アラームは 2 本だけ。理由も含めて意味がはっきりしているものに絞った。

| アラーム | メトリクス | 見ているもの |
| --- | --- | --- |
| `redis-memory-high` | `DatabaseMemoryUsagePercentage` | `noeviction` で書き込み拒否が始まる手前 |
| `redis-evictions` | `Evictions` | **設定ミスの検知** |

**2 本目が面白い。** `maxmemory-policy: noeviction` なら `Evictions` は**常に 0 でなければならない**。
1 でも出たらパラメータグループが効いていないということで、
裏を返せば**セッションが黙って捨てられている**。
だからしきい値は 0 固定でパラメータ化していないし、評価も 1 データポイントで即鳴らす。

```yaml
    Threshold: 0
    EvaluationPeriods: 1
    DatapointsToAlarm: 1
```

### 次元(Dimensions)に注意

メトリクスの次元は `ReplicationGroupId` ではなく **`CacheClusterId`** で、
値は `<ReplicationGroupId>-001` というノード単位の名前になる。
`ReplicationGroup` の戻り値にノード ID は含まれないので、命名規則から組み立てている。

```yaml
    Dimensions:
      - Name: CacheClusterId
        Value: !Sub ${ProjectName}-${EnvName}-redis-001
```

**ノードを 2 台以上にしたら `-002` 以降も見る必要がある。** 現状は 1 台前提である。

通知先は Redis 専用の SNS トピックと Slack チャンネル。
RDS に相乗りさせなかったのは、見るべき対処が違うため(RDS はクエリとストレージ、
Redis はセッション数とメモリ)。Slack 側のチャンネル作成手順 → [docs/slack/README.md](../../slack/README.md)

## 9. 撤収とバックアップ

```yaml
    SnapshotRetentionLimit: 0
    DeletionPolicy: Delete
    UpdateReplacePolicy: Delete
```

**自動バックアップを取らない。** セッションは失われてもログインし直せばよく、
撤収前提の環境でスナップショットだけ残ると課金が続く
(RDS で `DeletionPolicy: Delete` を明示しているのと同じ考え方 → `app.yml` の RDS のコメント)。

結果として **ローカル(AOF で永続化)のほうが本番より手厚い**という逆転が起きている。
これは意図的で、ローカルは「観察を続けたい」、本番は「消えても困らない」という
目的の違いから来ている。

**本番で ElastiCache のノードが再起動すると全員ログアウトする。**
それが許容できない規模になったら、`RedisNumCacheClusters` を 2 以上にして
Multi-AZ のフェイルオーバーを効かせる(パラメータ 1 つで切り替わる)。

## 10. 料金の目安(東京リージョン、2026 年時点の概算)

| 構成 | 概算 |
| --- | --- |
| `cache.t4g.micro` × 1(stg) | 約 $0.016/時 ≒ 月 $12 |
| `cache.t4g.small` × 2(prod) | 約 $0.064/時 ≒ 月 $47 |
| Serverless 最小 | 月 $90 前後 |

**建てっぱなしにしなければ数百円で済む。** 検証のたびに建てて壊す運用なら、
1 回数時間として $0.1 未満である。撤収を忘れないことのほうがずっと重要。

## 11. つまずきポイント

- **`AutomaticFailoverEnabled` はノード 1 台だと作成が失敗する。** `Conditions` で分岐する(4 節)
- **`maxmemory-policy` の既定が ElastiCache と Docker で違う。** 明示しないとローカルと挙動が変わる(5 節)
- **Serverless はパラメータグループを持てない。** 通知が要るなら選べない(2 節)
- **`CacheCluster` では暗号化も AUTH も設定できない。** 後から足せないので最初から `ReplicationGroup`(4 節)
- **マイグレーションのタスクにも Redis が要る。** 忘れるとデプロイが止まる(7 節)
- **アラームの次元は `CacheClusterId` で `-001` が付く。** `ReplicationGroupId` ではない(8 節)
- **`deploy/` 側にも同じ環境変数を書く。** 初回は `app.yml` のタスク定義で動くので、
  2 回目のデプロイまで気づかない(7 節)
- **`SlackChannelIdRedis` は Slack でチャンネルを作るまで埋められない。**
  `params/*.json` はプレースホルダのままなので、そのまま構築すると `AllowedPattern` で落ちる(意図的)
- **AUTH トークンを `openssl rand -base64` で作らない。** `+` と `/` が禁止されているため
  **3 回に 2 回は弾かれる**。しかも通るときもあるので気づきにくい。英数字だけにする(6 節)

## 関連

- [redis-basics.md](redis-basics.md) — TTL と eviction の基礎
- [session-management.md](session-management.md) — Redis の中で何が起きているか
- [ADR-0015](../../adr/0015-session-store-on-redis.md) — 保存先を Redis にした決定
- [ADR-0010](../../adr/0010-monitoring-in-ephemeral-stack.md) — 監視を使い捨てスタックに入れる方針
- [environment-differences.md](../cloudformation/environment-differences.md) — 環境ごとに値を変える書き方
