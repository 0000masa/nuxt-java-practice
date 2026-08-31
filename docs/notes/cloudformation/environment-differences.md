# RDS / ECS の環境差分と、IaC 2 ツールでの表現力

stg と本番で設定を変えるべきなのはどこか。そして「環境ごとに値を変える」という同じ目的に対して、Terraform と CloudFormation で書けること・書けないことがどう違うか。

コード例は全部このリポジトリから引く。`terraform/`(前に書いた Laravel + nginx の構成)と `cloudformation/app.yml`(今の Spring Boot の構成)が両方コミットされているので、同じ話題を 2 つの書き方で並べられる。

**注意:** 2 つは別のアプリで 1:1 の移植ではない。重なるのはリソースの種類までで、値をそのまま写してはいけない。

このノートは記述の確からしさを 3 段階で書き分ける。

- **仕様** — 公式ドキュメントに書かれていること
- **傾向** — 実務でよく見る形。根拠が弱いので断定しない
- **未検証** — まだ実物で確かめていない

**前提を先に断っておく。** このリポジトリは学習用で常時公開しない(→ `CLAUDE.md`)。`cfn-destroy.yml` は stg 固定で、**prod のスタックを建てる導線がそもそも無い**。つまり `cloudformation/params/prod.json` の 40 項目余りは**一度も AWS に適用されたことがない**。第1部で「prod ではこうすべき」と書く部分は、断りのない限り**未検証**として読むこと。基準は「人が使う本番」の実務水準に置いている。

要点は 3 つ。

1. **環境差の項目は「自分で選べる値」「選べない値」「他から計算で決まる値」に分かれる。** 一枚の表に混ぜると誤読する
2. **RDS を守る手段は CloudFormation にもある。** 足りないのは `DeletionPolicy` **という属性だけ**が環境で切り替えられないこと。答えは `DeletionProtection` をパラメータに出すことで、**このリポジトリでは実際にそうした**(→ §5-4)
3. **CloudFormation に算術が無いので、しきい値のような従属値は手計算した定数を `params` に固定するしかない。** 根拠をコメントに残さないと後で検算できなくなる

関連ノート: [Terraform 経験者のための CloudFormation](./terraform-to-cloudformation.md) / [CLI コマンドと Change Set](./cli-commands-and-change-sets.md) / [ECS のタスク定義は誰が持つか](./ecs-deploy-ownership.md) / [テンプレートの分割と置き場](./templates-and-prerequisites.md) / [テンプレートの書き方(YAML の文法と組み込み関数)](./template-syntax-and-functions.md)

---
---

# 第1部 — 何を環境で変えるのか

## 1. 環境差が生まれる 6 つの理由

`params/stg.json` と `params/prod.json` を眺めると値が違う項目が並ぶが、**違う理由は同じではない**。6 種類ある。

| # | 分類 | 意味 | 判断の主体 |
|---|---|---|---|
| 1 | **コスト** | 本番は金を払う、stg は削る | 自分 |
| 2 | **復旧手段** | 壊れたときに戻せるかどうか | 自分 |
| 3 | **AWS の制約** | 選択の余地がない。他の値と組み合わせが決まっている | AWS |
| 4 | **露出の制御** | stg だけ開ける / 本番だけ塞ぐ | 自分 |
| 5 | **変更の当て方** | 反映をどれだけ慎重にやるか | 自分 |
| 6 | **従属値** | 上の 1〜5 の値から計算で決まる | 計算 |

**3 と 6 を独立させているのに理由がある。**

**分類 3(制約)の例。** `params/stg.json` の `DbPerformanceInsights: false` は「stg には要らないから切った」ではない。**db.t4g の micro / small は Performance Insights に非対応で、`true` にすると `InvalidParameterCombination` でスタック作成ごと失敗する**(仕様。`app.yml:141-146` の Description にもそう書いてある)。他の項目と同じ表に「stg: false / prod: true」と並べると「stg でも有効にできるが節約のために切った」と読める。これは誤読。

**分類 6(従属値)の例。** `RdsConnectionsThreshold` は自分で決める値ではない。MySQL on RDS の `max_connections` は既定で `{DBInstanceClassMemory/12582880}`(仕様)なので、**`DbInstanceClass` を変えたら連動して計算し直す値**。prod の 307 は db.t4g.medium(RAM 4 GiB)から出た数字で、stg の 72 は db.t4g.micro(RAM 1 GiB)から出た数字。

分類 6 は第2部への伏線でもある。**CloudFormation には算術の組み込み関数が無いので、この計算はテンプレートの中では書けない**(→ §7)。

---

## 2. RDS の項目カタログ

`cloudformation/app.yml` の `Database`(`app.yml:747-799`)と、`terraform/modules/app-infrastructure/rds.tf` の `aws_db_instance.main` を突き合わせたもの。

### 2-1. 一覧

| 項目 | 分類 | stg | prod | 実務水準ならどうか |
|---|---|---|---|---|
| `DbInstanceClass` | 1 | `db.t4g.micro` | `db.t4g.medium` | 妥当。ただし t 系はバーストなので、常時負荷なら m/r 系 |
| `DbAllocatedStorage` | 1 | 20 | 20 | **縮小はできない**(仕様)ので、多めに取らず 20 から始める。伸ばし方は §2-2 |
| `DbMaxAllocatedStorage` | 1 | 100 | 100 | ストレージ自動スケーリングの上限。**設定しないと自動スケーリング自体が働かない**(→ §2-2) |
| `DbMultiAZ` | 2 | `false` | `true` | prod は必須。stg で `false` は正しい(倍額になるため) |
| `DbBackupRetentionDays` | 2 | 0 | 35 | **上限の 35 にした。** 実務では 7 が最低ライン、14〜35 も普通。長さは「壊れたことに気づくまでの時間」で決める。0 で自動バックアップ無効 |
| `DbDeleteAutomatedBackups` | 2 | `true` | `false` | prod は `false`。DB を消した後も保持期間ぶん自動バックアップが残り、最後の復旧手段になる |
| `DbDeletionProtection` | 2 | `false` | `true` | prod は `true`。ただし撤収時に `false` へ更新する 1 手が要る(→ §5-4・§10) |
| `DbPerformanceInsights` | **3** | `false`(制約) | `true` | 直近 7 日の保持は無料。medium 以上なら prod で `true` は当然 |
| `DbMonitoringInterval` | 1 | 60 | 60 | 両環境同じ。60 秒なら取り込み量が CloudWatch Logs の無料枠に収まるという判断 |
| `RdsCpuThresholdPercent` | 6 | 90 | 90 | AWS 公式の推奨アラーム値。インスタンスクラスに依存しないので同値でよい |
| `RdsFreeStorageThresholdBytes` | 6 | 2 GiB | 2 GiB | 割当の 10%。両環境とも 20 GB なので同値。式どおり |
| `RdsFreeableMemoryThresholdBytes` | 6 | 256 MiB | 1 GiB | RAM の 25%。1 GB → 256 MiB、4 GB → 1 GiB。式どおり |
| `RdsConnectionsThreshold` | 6 | 72 | 307 | prod は式どおり。stg は §2-4 参照 |
| `LogRetentionDays` | 1 | 7 | 30 | prod で 30 は短め。監査要件があるなら 90〜365 か、S3 へのエクスポートを併用 |

**テンプレートに直書きで環境差になっていない項目。** これらは §4 で扱う。

`StorageType: gp3`(`app.yml:765`) / `AutoMinorVersionUpgrade: true`(`app.yml:782`) / `PreferredMaintenanceWindow`・`PreferredBackupWindow`(`app.yml:793-796`) / `EnableCloudwatchLogsExports`(`app.yml:790-792`) / `DeletionPolicy`・`UpdateReplacePolicy`(`app.yml:752-753`)。

### 2-2. `DbAllocatedStorage` は「増やすことしかできない」

**仕様: RDS の割当ストレージは縮小できない。** これは環境差の項目というより**一方向の項目**で、一度 50 GB にしたら 20 GB には戻せない。だから「とりあえず多めに」の判断は取り消しが効かない。

**この項目が設定しているのはデータ量ではない。** RDS インスタンスに付ける EBS ボリュームのサイズ(GiB)で、テーブルとインデックスの実データのほかに、バイナリログ(`BackupRetentionPeriod > 0` で有効)、InnoDB の redo / undo、`ALTER TABLE` や大きい `ORDER BY` が使う一時領域、エンジンと OS の予約分が同じ領域に乗る。行数から見積もると足りない。MySQL の最小は 20 GiB(`app.yml:110-112` の `MinValue: 20`)。

**gp3 にはもう一段の落とし穴がある(仕様)。** RDS の gp3 は、割当ストレージが 400 GiB 未満のあいだ **3,000 IOPS / 125 MiB/s のベースラインに固定**され、それ以上をプロビジョニングするには 400 GiB 以上に増やす必要がある。IOPS が足りなくなったら、必要な容量とは無関係に **400 GiB まで増やすしか手が無い**。これはコストの分類 1 の話に見えて、実際は分類 3(制約)。

**そしてこの制約が、「本番だから多めに」の根拠を消している。**

- **gp2 時代:** IOPS = 3 × GiB(仕様)。20 GiB では 60 IOPS しか出ないので、**容量ではなく性能のために** 100〜200 GiB を割り当てるのが定石だった
- **gp3(このテンプレート):** 400 GiB 未満は固定。**20 GiB でも 50 GiB でも性能はまったく同じ**

かつて prod を 50 にしていたのはこの古い定石の名残で、gp3 では性能上の意味が無い。加えてこのリポジトリは画像を S3 に置く(`CLAUDE.md` の決定事項 3)ので、DB に溜まるのは行データだけ。**prod も 20 に揃えた。**

**伸ばす手段は容量ではなく `MaxAllocatedStorage`。そしてこれは、名前から受ける印象と逆向きに効く。**

| | 割当ストレージの動き | 空きが尽きたら |
|---|---|---|
| **設定しない** | **一切増えない。** `AllocatedStorage` の値で固定 | RDS が `storage-full` になり書き込みができなくなる。手で `AllocatedStorage` を増やすまで DB が止まる |
| **設定する** | 空きが割当の 10% を切る状態が続くと **AWS が自動で増やす**。設定値まで伸びる | 設定値に達するまでは自動で回避される |

**「設定しない = 上限なし」ではなく「設定しない = 増えない」。** ここを取り違えると、いちばん危ない側(何もしない)を安全だと思い込む。

**課金は実際の割当ぶんだけなので、上限を高く置くこと自体にコストは無い。** ただし**自動で伸びた分も縮小できない**(§2-2 冒頭の一方向性はここにも掛かる)。暴走したクエリの一時領域やログの肥大で一度伸びたら、その容量の課金がスタックを消すまで続く。つまり `MaxAllocatedStorage` は上限というより **「事故ったときに自動的に払ってよい上限額」**。MySQL の gp3 は 64 TiB まで設定できるが、そこまで開けると事故時の請求が青天井になる。**両環境とも 100 GiB**(割当 20 GiB の 5 倍)にしたのはこの読み方から。

**上限を大きくしても救済は速くならない。** 自動スケーリングは**一度動くと 6 時間は次の変更ができない**(仕様)ので、バルクインポートのように一気に埋まる操作には上限の大小に関係なく追いつかない。

**分類 6 の従属値がいちばん壊れる場所でもある。** 自動スケーリングは**割当ストレージが AWS 側で勝手に変わる唯一の経路**で、`RdsFreeStorageThresholdBytes` は「割当の 10%」を手計算した定数だから、割当が 20 → 40 GB に伸びてもしきい値は 2 GiB のまま取り残される。**今の 2 GiB は「割当の 10%」ではなく「復旧作業に必要な絶対容量」として読み替えるのが正しい**(→ §9-1)。

### 2-3. Multi-AZ は「可用性」ではなく「復旧手段」として読む

`DbMultiAZ` は分類 2 に置いた。可用性の話として語られがちだが、実際に効くのは**壊れたときに何分で戻るか**。Multi-AZ ならスタンバイへの自動フェイルオーバーで 1〜2 分、シングル AZ ならスナップショットからの復旧で数十分〜。

**傾向: prod は Multi-AZ、stg はシングル AZ。** 費用がほぼ倍になるので、検証環境で有効にする理由はまずない。

参考の Terraform も同じ分け方をしている(`rds.tf:23-24`、`variables.tf:109` の「prodはtrue推奨」)。

### 2-4. 従属値の算出根拠は、値と一緒に残す

`RdsConnectionsThreshold` の prod 307 は、公称どおりに計算した値。

```
db.t4g.medium = RAM 4 GiB = 4294967296 バイト
max_connections = {DBInstanceClassMemory/12582880} = 4294967296 / 12582880 ≈ 341
341 × 0.9(AWS 推奨は max_connections の 90%)≈ 307   ← prod.json の値
```

stg の 72 は同じ式からは出てこない。

```
db.t4g.micro = RAM 1 GiB = 1073741824 バイト
1073741824 / 12582880 ≈ 85 → × 0.9 ≈ 77
```

Terraform 側の `terraform.tfvars:49` を見ると `72` の根拠は「`max_connections`(約80)の 90%」と書かれている。`DBInstanceClassMemory` は OS が使う分を差し引いた値なので公称 RAM ちょうどではなく、実測の 80 を採ったのだと読める。

**どちらが正しいかより、ここで学ぶべきことは別にある。** 2 つの環境で**算出の根拠が違う**まま値だけが残っている。CloudFormation は計算してくれないので、この式は人間が回す。**「何 GB から、どの式で、何%として出したか」をコメントに残さないと、インスタンスクラスを変えたときに検算できなくなる。** `app.yml:292-297` の Description はまさにそのために書かれている。

---

## 3. ECS の項目カタログ

`app.yml` の `Cluster` / `AppTaskDefinition` / `Service` / `ScalableTarget`(`app.yml:1191-1537`)と `ecs_web.tf` を突き合わせたもの。

### 3-1. 一覧

| 項目 | 分類 | stg | prod | 実務水準ならどうか |
|---|---|---|---|---|
| `WebCpu` / `WebMemory` | 1 | 512 / 1024 | 1024 / 2048 | **組み合わせが決まっている**(分類 3 でもある。§3-2) |
| `WebDesiredCount` | 1 | 1 | 2 | prod の 2 は最低ライン。2 AZ に 2 タスクなので、1 AZ 障害で半減する |
| `WebOnDemandBase` | 2 | 0 | 2 | FARGATE で**必ず**確保するタスク数。prod の 2 タスクを守る(§3-3) |
| `WebOnDemandWeight` | 1 | 0 | 0 | base を超えた分の FARGATE 取り分。0 なので超過分は全部 Spot |
| `WebSpotWeight` | 1 | 1 | 1 | 同じく FARGATE_SPOT の取り分。**`MinValue: 1`** は空リスト防止の錘(§3-3) |
| `WebMinCapacity` / `WebMaxCapacity` | 6 | 1 / 2 | 2 / 4 | `DesiredCount` と整合が要る。`Min > Desired` にすると勝手に増える |
| `WebCpuTarget` / `WebMemoryTarget` | 1 | 60 / 70 | 60 / 70 | 同値。妥当 |
| `WebCpuScaleInCooldown` / `WebMemoryScaleInCooldown` | 1 | 120 / 180 | 300 / 300 | prod はスケールインを長く待つ(§3-5) |
| `WebCpuScaleOutCooldown` / `WebMemoryScaleOutCooldown` | 1 | 60 / 60 | 60 / 60 | 同値。詰まると機会損失になるので両環境短い |
| `BakeTimeInMinutes` | **5** | 0 | 30 | Blue/Green の切替後に blue を残す時間。prod は 30〜60 が妥当 |
| `ContainerInsights` | 1 | `enhanced` | `enhanced` | **prod も stg も同値。** enhanced は追加課金が大きい(§3-4) |

**テンプレートに直書きの項目。** `EnableExecuteCommand: true`(`app.yml:1417`) / `MinimumHealthyPercent: 100`・`MaximumPercent: 200`(`app.yml:1429-1430`) / `HealthCheckGracePeriodSeconds: 120`(`app.yml:1438`)。これらは §4 と §5。

### 3-2. CPU とメモリは「選ぶ」のではなく「組み合わせから選ぶ」

**仕様: Fargate の CPU とメモリは自由な組み合わせにできない。** 256 なら 512/1024/2048 MiB、512 なら 1〜4 GB、1024 なら 2〜8 GB…と決まっている。だから分類 1(コスト)であると同時に分類 3(制約)でもある。

CloudFormation 側は `AllowedValues` で CPU の側だけ縛っている。

```yaml
# app.yml:168-173
  WebCpu:
    Type: String
    AllowedValues: ["256", "512", "1024", "2048", "4096"]
  WebMemory:
    Type: String
    Description: MiB。WebCpu と組み合わせが決まっている
```

**組み合わせの妥当性までは検証できない。** `AllowedValues` は 1 つのパラメータの中でしか効かないので、「WebCpu が 256 のとき WebMemory は 512 か 1024 か 2048」という**パラメータ間の制約は書けない**。Terraform の `validation` ブロックなら object の中を見て書ける。ここは Terraform のほうが強い(→ §8)。

### 3-3. capacity provider — 混合戦略を書いた

**傾向: 本番の定石は「FARGATE を `base` で最低数だけ確保し、それを超えた分を FARGATE_SPOT に流す」混合戦略。** Spot は中断されるので全部 Spot にはできず、かといって全部オンデマンドだと高い。

以前はここに「今の設計では 1 つしか選べない」と書いていた。**書けるようにした。**

Terraform 側はリストを受け取って `dynamic` ブロックで展開している。

```hcl
# ecs_web.tf:24-31
  dynamic "capacity_provider_strategy" {
    for_each = var.ecs_web_service_config.capacity_provider_strategy
    content {
      capacity_provider = capacity_provider_strategy.value.capacity_provider
      weight            = capacity_provider_strategy.value.weight
      base              = capacity_provider_strategy.value.base
    }
  }
```

```hcl
# terraform.tfvars:58-60 — リストの要素を増やすだけで混合になる
  capacity_provider_strategy = [
    { capacity_provider = "FARGATE_SPOT", weight = 1, base = 0 }
  ]
```

**CloudFormation 側は「オブジェクトのリスト」を渡せない**(`Parameters` は平坦な型しか持てない)ので、**フィールドごとに 3 本のパラメータへ開く**ことになる。そのうえで要素の出し入れを `Fn::If` + `AWS::NoValue` でやる。

```yaml
# app.yml:322-336
Conditions:
  UseOnDemand: !Or
    - !Not [!Equals [!Ref WebOnDemandBase, 0]]
    - !Not [!Equals [!Ref WebOnDemandWeight, 0]]
```

```yaml
# app.yml:1419-1427
      CapacityProviderStrategy:
        - !If
          - UseOnDemand
          - CapacityProvider: FARGATE
            Base: !Ref WebOnDemandBase
            Weight: !Ref WebOnDemandWeight
          - !Ref AWS::NoValue        # ← stg ではこの要素ごと消える
        - CapacityProvider: FARGATE_SPOT
          Weight: !Ref WebSpotWeight
```

解決結果はこうなる。

| | 解決後の戦略 | 意味 |
|---|---|---|
| **stg** | `[{FARGATE_SPOT, Weight: 1}]` | Spot 100% |
| **prod** | `[{FARGATE, Base: 2, Weight: 0}, {FARGATE_SPOT, Weight: 1}]` | **平常時の 2 タスクはオンデマンド、それを超えた分は全部 Spot** |

#### `base` と `weight` の読み方(仕様)

- **`base` は「最低このプロバイダで走らせるタスク数」。戦略の中で 1 つの provider にしか付けられない**
- **`weight` は base を満たした後の配分比。**絶対値ではなく比なので、`0:1` と `0:5` は同じ「全部 Spot」
- **weight 0 の provider は base を満たす以外に使われない。**だから prod の `FARGATE / Base 2 / Weight 0` は「2 台は必ずオンデマンド、超過分は一切オンデマンドを使わない」
- **全 provider の weight が 0 だと `CreateService` / `RunTask` が失敗する**

#### なぜ prod は `base = 2` なのか

`WebDesiredCount` も `WebMinCapacity` も 2 で、**これは「2 AZ に 1 台ずつ置いて 1 AZ 障害でも生き残る」最低ライン**(§3-1)。ここに Spot を混ぜると、**AZ 障害と Spot 中断という独立した 2 つの要因**で同じ 1 台が落ちうる。既に最低ラインと認めている構成を、コスト削減のために削ることになる。

だから **Spot が効くのはスケールアウトした 3・4 台目だけ**。節約幅は小さいが、`base` を `WebMinCapacity` に合わせるのが §3-3 冒頭の「最低数だけ確保し、それを超えた分を Spot に流す」の素直な読み方でもある。

#### stg を Spot 100% にする代償(仕様)

- **Fargate は Spot 容量をオンデマンドで代替しない。** 容量が無ければタスクは上がらず、ECS は取れるまで再試行し続ける
- **タスクが 1 つだけのサービスは、容量が空くまで停止したままになる。** stg は `WebDesiredCount: 1` なので、中断 = 全断
- 中断時は 2 分前に SIGTERM と EventBridge のイベントが飛ぶ

検証環境なので許容するが、**「stg が落ちている」の原因候補に Spot 中断が常にある**ことは覚えておく。

**この設定は `base` が Blue/Green デプロイ中にどう効くかに依存していた。stg の実機で確かめて、そのままでよいと分かっている → §3-6。**

### 3-4. `ContainerInsights` は stg と prod が同値のまま

`enhanced` は Container Insights の強化オブザーバビリティで、タスク単位・コンテナ単位のメトリクスが増えるかわりに**課金も増える**。テンプレートのコメント自身が逃げ道を書いている。

```yaml
# app.yml:241-247
  ContainerInsights:
    Type: String
    # disabled を許していない。ECS タスク数不足のアラーム(EcsRunningLessThanDesiredAlarm)は
    # ECS/ContainerInsights 名前空間の RunningTaskCount / DesiredTaskCount を読むので、
    # 切るとアラームは残ったまま二度と鳴らなくなる(標準の AWS/ECS にタスク数のメトリクスは無い)。
    # 費用を抑えたいときは enhanced -> enabled に落とす。→ docs/adr/0010
    AllowedValues: [enhanced, enabled]
```

**`disabled` を選択肢から外しているのは正しい。** アラームが黙って死ぬより構築が止まるほうがマシ、という判断が一貫している。一方で **`enhanced` を両環境で採っているのは学習目的の選択**(強化メトリクスを見るのが目的)。実務なら、メトリクスを常時見る prod こそ `enhanced`、使い捨ての stg は `enabled` に落とす、という向きが自然。今の設定はその逆ではなく「両方最大」なので、単純に費用が高い。

### 3-5. クールダウンは「削りすぎない」ための待ち時間

スケーリングポリシーのクールダウンは、**増やした / 減らした直後に、次の増減まで待つ秒数**。2 つは非対称に効く。

- **スケールアウト**(60 秒、両環境同じ)— 詰まると機会損失に直結するので短い。負荷が来ているのに待つ理由がない
- **スケールイン**(stg 120 / 180、prod 300)— **削りすぎると次の山で払い直しになる。** このアプリはタスクの起動に Spring Boot の 30〜60 秒 + `HealthCheckGracePeriodSeconds: 120` が乗るので、谷のたびに削って戻すのはただの遅延と課金の往復になる

**stg で CPU 120 / メモリ 180 と差を付けているのは、メモリが CPU ほど素直に下がらないから**(JVM はヒープをすぐ OS に返さない)。参考の Terraform も `ecs_web.tf:265-283` で同じ差を付けている。**prod はどちらも 300 に揃えた** — 人が使っている環境では「メモリだから」より「本番だから」のほうが強い理由になる。

**パラメータは 4 本立てた。** 2 本(スケールイン / スケールアウト)にまとめると params は軽くなるが、この CPU 120 / メモリ 180 の使い分けが消える。

### 3-6. 検証済み — `base` は「サービスリビジョン」単位で効く

**§3-3 の prod の設定には、公式ドキュメントで確定できなかった前提が 1 つあった。stg の実機で確かめて決着した(2026-08-30)。**

> **結論: `base` はサービスリビジョン単位で評価される。** Blue/Green デプロイ中も、green は自分で base を満たしにいく。**§3-3 の案 A(`base 2` / OD weight `0` / Spot weight `1`)はそのままでよい。**

以下は、何が問題だったのか・どう確かめたのかの記録。**公式ドキュメントには今も書かれていない**ので、根拠は下の実測だけ。

#### 何が分からなかったのか

このサービスは `Strategy: BLUE_GREEN`(`app.yml:1432`)なので、**デプロイ中は blue 2 + green 2 = 4 タスクが同時に走る**。このとき `base: 2` を誰が満たすのかで結果が変わる。

| | `base` の数え方 | green の 2 タスクはどこに乗るか |
|---|---|---|
| **材料 1** | **サービス全体**(blue + green をまとめて数える) | blue の 2 タスクが既に base を満たしている → green は weight 配分 → `WebOnDemandWeight: 0` なので **全部 Spot** |
| **材料 2** | **サービスリビジョン単位**(blue と green が別々に満たす) | green も自分で base 2 を満たす → **全部オンデマンド** |

#### 両方に根拠がある

**材料 1 の根拠** — `CapacityProviderStrategyItem` の API リファレンスにある `base` の定義。

> The *base* value designates how many tasks, at a minimum, to run on the specified capacity provider **for each service**.

「for each service revision」ではなく「for each **service**」と書かれている。

**材料 2 の根拠** — `ServiceRevision` API が**自前の `capacityProviderStrategy` を持っている**こと。

> **capacityProviderStrategy** — The capacity provider strategy **the service revision uses**.

blue と green はそれぞれ別の service revision で、それぞれが戦略(base を含む)を抱えている構造になっている。

**`deployment-type-blue-green.html` の Considerations にも、`blue-green-deployment-how-it-works.html` の 6 フェーズの説明にも、capacity provider の話は出てこない。** どちらが正しいかを決める記述は見つからなかった。

#### 材料 1 だったら何が壊れていたか

**「平常時の 2 タスクは必ずオンデマンド」という約束が、デプロイ 1 回で破綻する。** しかも自動では戻らない。

| 時点 | blue | green | サービス全体 |
|---|---|---|---|
| 平常時 | OD 2 | — | **OD 2**(base 充足) |
| SCALE_UP | OD 2 | **Spot 2** | base は blue が満たしているので green は weight 配分 |
| BAKE_TIME | OD 2(無トラフィック) | Spot 2(**本番トラフィック**) | 同上 |
| CLEAN_UP 後 | — | Spot 2 | **OD 0 / Spot 2。base 2 が満たされていない** |

`base` はタスクを**配置するとき**に効く条件で、走っているタスクを並べ替える仕組みではない(**これは推論。ECS に capacity provider を理由とした再配置の記述は見つからなかった**)。

そして次のデプロイでは blue が Spot なので base が未充足になり、**green の 2 タスクが両方オンデマンドに行く**。つまり:

> **デプロイのたびに「全部オンデマンド」と「全部 Spot」が交互に入れ替わる。**

**weight は比なので、デプロイをまたいでも自己安定する。壊れるのは「N 台」という絶対数を持つ `base` だけ。**

#### どう確かめたか

判別には **`WebDesiredCount >= WebOnDemandBase`**、つまり **blue だけで base を満たしきっている状態**が要る。今の stg は `WebDesiredCount: 1` に対して base 0 なので、そのままでは判別できない。

`params/stg.json` を一時的にこう変えて建てる(**検証用の一時コミット。終わったら戻す**)。

| パラメータ | 平常値 | 検証中 | なぜ |
|---|---|---|---|
| `WebDesiredCount` | `1` | **`2`** | blue だけで base 2 を満たすため。**これが無いと判別できない** |
| `WebMinCapacity` | `1` | **`2`** | 検証の合間にスケールインで 1 に落ちると条件が崩れる |
| `WebOnDemandBase` | `0` | **`2`** | prod と同じ |

`WebOnDemandWeight`(0)と `WebSpotWeight`(1)は平常値と同じなので触らない。

| # | 操作 | 見るもの |
|---|---|---|
| 1 | `cfn-deploy`(`dry_run: true`) | CREATE の Change Set が作れる |
| 2 | `cfn-deploy`(タグ A で構築) | タスク 2 個が **`FARGATE`**。違えば前提が崩れている |
| 3 | `cfn-apply`(タグ B) | **`FARGATE_SPOT` × 2 なら材料 1 / `FARGATE` × 2 なら材料 2** |
| 4 | `cfn-apply`(タグ A に戻す) | `FARGATE` に戻れば**交互に入れ替わる**ことの決定的証拠(材料 1 確定) |
| 5 | 戻すコミット → `cfn-apply` → `cfn-destroy` | ついでに `UseOnDemand: false` の分岐も通る |

**再デプロイは `cfn-apply` の `image_tag` を変えて起こす。** ECR のタグが 2 つあれば往復できる。`ImageTag` を変えると `AppTaskDefinition` が `Replacement: True` になるが、`cfn-apply.yml:325` の `EXEMPT_TYPE` が判定から外しているのでガードには止められない。

**`BakeTimeInMinutes` は `0` のままでよい。** `cfn-apply` は `stack-update-complete` を待つので、ワークフローが終わった時点で bake も終わり blue は消えている。判定は「デプロイ後に残ったタスク(= green)」だけで付く。

#### 観測で踏んだ罠 — 「起動タイプ」では判別できない

**仕様: `Task` の `launchType` が取る値は `EC2` / `FARGATE` / `EXTERNAL` / `MANAGED_INSTANCES` の 4 つで、`FARGATE_SPOT` という値は存在しない。** Fargate Spot は起動タイプではなくキャパシティープロバイダーの区別で、インフラ種別としてはどちらも `FARGATE`。

| フィールド | 値 | 判別に使えるか |
|---|---|---|
| `launchType`(コンソールの「起動タイプ」) | 常に `FARGATE` | ❌ |
| **`capacityProviderName`(「キャパシティープロバイダー」)** | `FARGATE` か `FARGATE_SPOT` | ✅ |

**実際にここで一度読み違えた。**「起動タイプが両方 Fargate だった」は「両方オンデマンドだった」ではなく、**何も言っていない**。

```bash
cluster=nuxt-java-practice-stg-cluster
service=nuxt-java-practice-stg-app

# 走っているタスク
aws ecs describe-tasks --cluster "$cluster" \
  --tasks $(aws ecs list-tasks --cluster "$cluster" --service-name "$service" --query 'taskArns' --output text) \
  --query 'tasks[].{cp:capacityProviderName,launch:launchType,started:startedAt}' --output table

# 停止済み(1 時間程度は残る。前のデプロイの証拠を後から拾える)
aws ecs describe-tasks --cluster "$cluster" \
  --tasks $(aws ecs list-tasks --cluster "$cluster" --service-name "$service" \
              --desired-status STOPPED --query 'taskArns' --output text) \
  --query 'tasks[].{cp:capacityProviderName,stopped:stoppedAt,reason:stoppedReason}' --output table
```

コンソールで見るなら、タスクの詳細画面の「キャパシティープロバイダー」。一覧にも列として出せる。

#### 実測結果(2026-08-30、stg)

`describe-tasks` の `capacityProviderName` と `stoppedReason` の `deployment ecs-svc/...` で、リビジョンごとに束ねたもの。

| 時刻 | リビジョン | CP | 出来事 |
|---|---|---|---|
| 22:02:37 | **R0**(旧戦略: base 0 / Spot 100%) | `FARGATE_SPOT` | 初回構築 |
| **22:27:27** | **R0** | **`FARGATE_SPOT`** | **desired 1→2 で blue が 1 台増えた** |
| 22:27:32 / 22:27:54 | **R1**(新戦略: base 2) | `FARGATE` × 2 | 検証値を当てたときの green |
| 22:31 | R0 停止 | | |
| **22:44:02 / 22:44:32** | **R2** | **`FARGATE` × 2** | **判定点** |
| 22:47 | R1 停止 | | |
| 22:59:47 / 23:00:10 | **R3** | `FARGATE` × 2 | もう一度 |
| 23:03 | R2 停止 | | |

**判定点は 22:44。** そのとき blue(R1)は `FARGATE` × 2 で走っていた。サービス全体で数えるなら base 2 は充足済みなので、**材料 1 なら green は weight 配分になり、`WebOnDemandWeight: 0` だから全部 Spot になるはず**だった。実測は `FARGATE` × 2。**22:59 の R2 → R3 でも同じ**で、独立した 2 回とも材料 1 の予測を外した。

**決定打は 22:27 の 1 行。** 同じ瞬間に **blue が Spot に 1 台増え、green が FARGATE で立ち上がっている**。CloudFormation は既に新しい戦略(base 2)を当てているのに、**blue は旧戦略のまま Spot に増えた**。これは `ServiceRevision.capacityProviderStrategy` が「そのリビジョンが使う戦略」を各自で抱えている、という材料 2 の構造そのもの。

**副産物として分かったこと。**

- **`DesiredCount` の変更は blue にも効く。** 新旧の戦略が同時に走る瞬間がある
- **`base` の定義にある "for each service" は、リビジョンをまたいで合算する意味ではない**(少なくとも Blue/Green ではそう振る舞わない)。API リファレンスの文面だけからは読み取れない

#### もし材料 1 だったら

棄却されたので採らなかったが、記録として残す。`base` は「N 台」という絶対数なのでリビジョンをまたぐと壊れうる一方、**`weight` は比なのでデプロイをまたいでも自己安定する**。だから逃げ道は「base への依存を減らす」方向にあった。

| 案 | base / OD weight / Spot weight | 材料 1 の世界での挙動 |
|---|---|---|
| **A'** | `2` / `1` / `1` | オンデマンドが 1〜2 台で揺れる。0 にはならない |
| **D** | `0` / `1` / `1` | 常に OD 1 + Spot 1。base を使わないので解釈に依存しないが、平常時から 1 台が Spot になる |


---

## 4. 露出の制御 — stg だけ開ける / prod だけ塞ぐ

分類 4 は RDS / ECS をまたぐので独立させる。

| 項目 | stg | prod | 何が変わるか |
|---|---|---|---|
| `EnableBasicAuth` | `true` | `false` | WAF による Basic 認証(→ `docs/adr/0006`) |
| 任意 SQL の実行 | 可 | 不可 | `db-task.yml` が prod に対しては任意 SQL を流さない |
| `EnableExecuteCommand` | `true` | `true` | ECS Exec。**環境差になっていない** |

**`EnableBasicAuth` の向きに注意。** stg が `true` で prod が `false`。「本番のほうが厳しい」の逆に見えるが正しい。Basic 認証は**まだ見せたくない環境を隠す**ためのもので、公開する本番には掛けない。

**ただしこのリポジトリでは、その prod が公開されることは無い。** `CLAUDE.md` の「常時公開しない」と `prod.json` の `EnableBasicAuth: false` は、prod を実際に建てた瞬間にぶつかる。建てるなら prod も `true` にするか、そもそも prod を建てないか。今は後者。

**`EnableExecuteCommand` について、よくある「本番では切れ」は雑すぎる。** これは**機能を有効にするかどうか**のフラグで、実際に `aws ecs execute-command` を打てるかは **IAM(`ecs:ExecuteCommand`)で決まる**。有効にしただけで誰でも入れるわけではない。

正確に言うなら本番で必要なのは 3 つ。

1. `ecs:ExecuteCommand` を持つ IAM プリンシパルを絞る
2. **セッションのログを CloudWatch Logs か S3 に残す**(誰がいつ何をしたかの監査。クラスタの `ExecuteCommandConfiguration` で設定する)
3. 監査ログを設定できないなら、そのときは `false` にする

今の `app.yml` は `EnableExecuteCommand: true` を直書きしていて、`ExecuteCommandConfiguration` も設定していない。**stg では正しい判断**(デバッグに要る)。prod でこのまま建てるなら 2 が要る。

---

## 5. 分けた項目と、分けないと決めた項目

もともとここは「まだ分けていない項目」の一覧だった。そのうち **`params/` だけで分けられるもの**を実際に切り出したので、記録に書き換える。

### 5-1. 分けた — パラメータ 10 本

| 項目 | 前 | stg | prod | 分けた理由 |
|---|---|---|---|---|
| `DbDeletionProtection` | 設定なし(= `false`) | `false` | `true` | 削除 API 自体を拒否する。RDS を守る本命(→ §5-4) |
| `DbDeleteAutomatedBackups` | `true` 直書き | `true` | `false` | prod は DB を消しても保持期間ぶん自動バックアップが残る |
| `DbMaxAllocatedStorage` | 設定なし | `100` | `100` | **設定しないと自動スケーリングが働かず、割当が固定される**(→ §2-2) |
| `WebCpuScaleInCooldown` | `120` 直書き | `120` | `300` | prod は谷でタスクを削りすぎない(→ §3-5) |
| `WebCpuScaleOutCooldown` | `60` 直書き | `60` | `60` | 同上。両環境短いまま |
| `WebMemoryScaleInCooldown` | `180` 直書き | `180` | `300` | 同上 |
| `WebMemoryScaleOutCooldown` | `60` 直書き | `60` | `60` | 同上 |
| `WebOnDemandBase` | `WebCapacityProvider` で 1 つだけ選ぶ形 | `0` | `2` | **混合戦略が書けるようになった**(→ §3-3) |
| `WebOnDemandWeight` | 同上 | `0` | `0` | 同上 |
| `WebSpotWeight` | 同上 | `1` | `1` | 同上 |

**作業は 3 ファイル同時。** テンプレートの直書きを `!Ref` に変えて、`Parameters` に 1 つ足して、`params/stg.json` と `params/prod.json` の両方に値を書く。**これがこのやり方のコスト。** Terraform で `rds_config` の object にフィールドを 1 つ足すのと比べると、パラメータが増えるほど重くなる(→ §7)。

**両環境同値のパラメータがある**(`DbMaxAllocatedStorage`・`Web*ScaleOutCooldown`・`WebSpotWeight`)。値が同じでもパラメータに出すのは、`DbMonitoringInterval`(60/60)・`RdsCpuThresholdPercent`(90/90)・`WebCpuTarget`(60/60)と同じ扱い。**「今たまたま同じ」と「構造的に同じ」は違う**ので、前者は開けておく。

### 5-2. 分けないと決めた

**「まだやっていない」ではなく「やらないと決めた」。** 理由を書いておかないと、次に読んだ人が同じ検討をやり直すことになる。

| 項目 | 何を設定するか | 分けない理由 |
|---|---|---|
| `AutoMinorVersionUpgrade` | マイナーバージョンをメンテナンスウィンドウで自動的に上げるか | **両環境 `true`。** 「prod は `false` にして上げるタイミングを自分で握る」流儀もあるが、セキュリティ修正を自動で当てるほうを採った。参考の Terraform も `rds.tf:18` で `true` 直書き |
| `MinimumHealthyPercent` / `MaximumPercent` | デプロイ中に最低何 % のタスクを健全に保つか / 最大何 % まで増やしてよいか | **両環境 `100` / `200`。** stg を `0` / `100` に落とせば 2 タスク分の瞬間課金を避けられる…はずだが、この Service は `Strategy: BLUE_GREEN`(`app.yml:1432`)で **green 側のタスク一式が別に立ち上がる**ので、どのみち避けられない(未検証)。分ける実益が無い |
| `EnableCloudwatchLogsExports` | どの RDS ログを CloudWatch Logs に流すか | **両環境 `[error, slowquery]`。** `CommaDelimitedList` にすれば分けられるが、実務でもこの 2 つで足りる(`general` は本番で入れない)。分ける口実が無い |

### 5-3. まだ残っているもの

| 項目 | 今 | 本番運用なら | なぜ残っているか |
|---|---|---|---|
| `DeletionPolicy` / `UpdateReplacePolicy` | `Delete` 直書き | prod は `Snapshot` | **技術的に不可能。** 属性なので組み込み関数が使えない(→ §6・§8-2) |
| `ExecuteCommandConfiguration` | 設定なし | prod は必須(§4 の 2) | `params` だけでは済まない。ログ用の LogGroup とタスクロールの権限追加が要る |

### 5-4. `DeletionProtection` — RDS を守る本命はこれ

`DeletionPolicy` が環境で切り替えられないので RDS を守れない、という話ではない。**守る手段は複数あって、そのうち `DeletionPolicy` だけが環境差にできない。**

| やりたいこと | 手段 | 環境で切り替えられるか |
|---|---|---|
| スタックを消してもリソースを残す | `DeletionPolicy: Retain` | ❌ 属性なので組み込み関数不可(→ §6) |
| 最終スナップショットを残して消す | `DeletionPolicy: Snapshot`(RDS はこれが既定) | ❌ 同上 |
| **削除の API 自体を拒否する** | **`DeletionProtection: true`** | ✅ **ただのプロパティなので `!Ref` で渡せる** |
| スタック更新での事故を防ぐ | スタックポリシー | ✅ テンプレート外の指定なので環境ごとに変えられる |

**答えは 3 行目。** `DeletionProtection` は `AWS::RDS::DBInstance` の普通のプロパティなので `!Ref` が効く。**実際にそう書いた。**

```yaml
# app.yml:137-140
  DbDeletionProtection:
    Type: String
    AllowedValues: ["true", "false"]
    Description: prod は true。true のままだと delete-stack が RDS を消せず撤収ごと失敗するので、消す前に false へ更新する 1 手が要る
```

```yaml
# app.yml:778
      DeletionProtection: !Ref DbDeletionProtection   # ← プロパティなので !Ref が効く
```

Terraform 側も同じで `deletion_protection = var.rds_config.deletion_protection` と書ける。**ここに 2 ツールの差は無い。**

**副作用が 1 つある(仕様)。** `DeletionProtection: true` の RDS は削除 API を拒否するので、**`delete-stack` がその RDS を消せずスタック削除ごと失敗する**。撤収するには先に `false` に更新する 1 手が要る。

**このリポジトリでの帰結。** `params/stg.json` は `false`、`params/prod.json` は `true` にした。**stg は作り捨てなので `false` が正しく、prod は「本番だったらこうする」という設計の記録として `true`。**

**そして prod は「建てられるが撤収できない環境」になった。** これは事故ではなく、§10 の衝突をそのまま値に落とした結果。`cfn-destroy.yml` は stg 固定なので今すぐ壊れることはないが、**prod を建てるなら撤収手順のほうを直す**(消す前に `false` へ更新するステップを足す)という順番になる。

---

## 6. 環境差に見えて、設計判断ではないもの

`params/*.json` には値が違うが**このノートの対象外**の項目がある。除外の理由を書いておく。

| 項目 | stg / prod | なぜ対象外か |
|---|---|---|
| `VpcCidr` ほかサブネット | `192.168.0.0/20` / `192.168.16.0/20` | **重複できないから分けている**だけ。「本番だから値を変える」ではない |
| `SsmParameterPath` | `/nuxt-java-practice/{stg,prod}/` | 名前空間の分離。値そのものに意味は無い |
| `APP_BASE_URL` / `MAIL_FROM` | `!Sub https://${EnvName}.${AppSubdomain}.${DomainName}` | **環境名から自動的に決まる**。人が選ぶ値ではない |
| `EnvName` / `SsmParameterPath` を使う各種名前 | スタック内で自動生成 | 同上 |

**見分け方はこう。** 「この値を stg と prod で同じにしたら何が起きるか」を考えて、答えが**「衝突する」なら分離であって環境差ではない**。答えが「本番が弱くなる」「stg が高くつく」なら本物の環境差。

---
---

# 第2部 — Terraform と CloudFormation での書き方の違い

型レベルの対訳(`variable` ↔ `Parameters`、`count` ↔ `Conditions` など)は[別ノート](./terraform-to-cloudformation.md)にある。ここは**「環境ごとに値を変える」という一点に絞ったときにだけ出てくる差**を書く。

---

## 7. 環境差をどこに置くか — 3 通りある

| | 置き場 | 例 |
|---|---|---|
| Terraform | 環境ディレクトリの `tfvars` | `terraform/stg/terraform.tfvars` |
| CloudFormation(A) | **テンプレート外のパラメータファイル** | `cloudformation/params/{stg,prod}.json` |
| CloudFormation(B) | **テンプレート内の `Mappings`** | `!FindInMap [EnvConfig, !Ref EnvName, DbInstanceClass]` |

**このリポジトリは A。** そして、それは既に決まっているルールとして書かれている。

> **環境差分は `params/` にしか書かない。** `app.yml` に環境名ごとの値(`Mappings` の stg / prod など)を持ち込まないこと。prod を追加するときに共通部分を編集しないで済む状態を保つのが、この分け方の目的。
> — `cloudformation/README.md:18`

B(`Mappings` 方式)はこう書く。CloudFormation 特有の選択肢で、Terraform に直接の相当物は無い。

```yaml
Mappings:
  EnvConfig:
    stg:
      DbInstanceClass: db.t4g.micro
      DbMultiAZ: "false"
    prod:
      DbInstanceClass: db.t4g.medium
      DbMultiAZ: "true"

Resources:
  Database:
    Properties:
      DBInstanceClass: !FindInMap [EnvConfig, !Ref EnvName, DbInstanceClass]
```

**A と B のトレードオフ。**

| | A: `params/*.json` | B: `Mappings` |
|---|---|---|
| 渡し忘れ | **起きる。** 値が無いパラメータがあると Change Set 作成で落ちる | 起きない。テンプレート内で完結 |
| 環境の追加 | ファイルを 1 つ足すだけ。**テンプレートを触らない** | **テンプレートを編集する**(共通部分に手が入る) |
| 差分のレビュー | 環境ごとに別ファイルなので並べて見にくい | 2 環境の値が縦に並ぶので比較しやすい |
| 秘密の扱い | ワークフローが `--parameter-overrides` で上書きできる | テンプレートに書くので上書きしづらい |
| Terraform からの移行 | `tfvars` とほぼ同じ発想で移れる | 発想を変える必要がある |

**「テンプレートを触らずに環境を足せる」を採ったのが A。** 逆に言えば、渡し忘れの事故は A のほうが起きやすい。`aws cloudformation deploy` は渡さなかったパラメータを `UsePreviousValue: true` で埋めてくれるが、`create-change-set` を直に叩くときは自分で全部組む必要がある(→ [CLI ノート §5-3](./cli-commands-and-change-sets.md))。

---

## 8. CloudFormation で「環境差にできない」場所

ここが 2 ツールでいちばん差が出る節。

### 8-1. 組み込み関数が使える場所は限られている

**仕様: 組み込み関数はテンプレートの特定の場所でしか使えない。** AWS の組み込み関数リファレンスは、使える場所を**リソースのプロパティ、`Outputs`、メタデータ属性、`UpdatePolicy` 属性**に限定している。

裏を返すと、**以下では組み込み関数が使えない**。

| 場所 | 例 | 環境差にしたくなる場面 |
|---|---|---|
| `DeletionPolicy` | `Delete` / `Retain` / `Snapshot` | **stg は消す / prod はスナップショットを残す** |
| `UpdateReplacePolicy` | 同上 | 同上 |
| `DependsOn` | 論理 ID のリスト | 環境によって依存順を変えたい(まず無い) |
| `Condition`(リソース属性) | Condition 名 | Condition 自体は `Conditions` セクションで組めるので実害は薄い |
| `Type`(リソース型) | `AWS::RDS::DBInstance` | 型を動的にする発想自体が無い |

**実害があるのは 1 行目だけ。** そして RDS では、それがちょうど本番でいちばん欲しい設定にぶつかる。

### 8-2. `skip_final_snapshot` は変数、`DeletionPolicy` は定数

Terraform 側は普通の引数なので変数化できる。

```hcl
# rds.tf:14
  skip_final_snapshot = var.rds_config.skip_final_snapshot
```

```hcl
# variables.tf:107
    - skip_final_snapshot: 削除時に最終スナップショットをスキップするか（prodはfalse推奨）
```

CloudFormation 側は定数しか書けない。

```yaml
# app.yml:752-753 — ここに !If は書けない
    DeletionPolicy: Delete
    UpdateReplacePolicy: Delete
```

```yaml
# ↓ これは通らない
    DeletionPolicy: !If [IsProd, Snapshot, Delete]
```

**回避策と、それぞれの代償。**

| 回避策 | 代償 |
|---|---|
| **`DeletionProtection` をパラメータに出す**(→ §5-4) | 削除を止める手段としてはこちらのほうが強い。ただし撤収に 1 手増える |
| テンプレートを stg 用 / prod 用の 2 本に分ける | 共通部分の二重管理。`cloudformation/README.md:18` の方針と正面から衝突する |
| RDS だけネストスタックに切り出し、ネストスタックのテンプレートを環境で差し替える | 構成が一段複雑になる。→ [テンプレートの分割ノート](./templates-and-prerequisites.md) |
| スタックポリシーで `Update:Delete` を拒否する | スタック**更新**での事故は防げるが、`delete-stack` は止められない |
| 諦めて、消す前に手でスナップショットを取る | 手順書に頼る = いつか忘れる |

**このリポジトリの結論は 1 行目。** `DeletionPolicy` の制約を回避しようとせず、`DeletionProtection` で守るのが素直。`DeletionPolicy` が定数なのは事実として覚えておけばよく、それを迂回する仕掛けは要らない。

**未検証:** `Transform: AWS::LanguageExtensions` は `Fn::ForEach` / `Fn::Length` / `Fn::ToJsonString` を足すが、これを入れれば `DeletionPolicy` で組み込み関数が使えるようになる、という話は確認できていない。使えるようにはならないと理解している。

### 8-3. `dynamic` ブロックが無い — capacity provider の混合

§3-3 の続き。**FARGATE + FARGATE_SPOT の混合は素の CloudFormation でも書ける。実際に書いた**(`app.yml:1419-1427`)。

**仕様: `Fn::If` はリストの要素としても使え、`AWS::NoValue` を返すとその要素がリストから取り除かれる。** だから「要素数が環境で変わるリスト」は表現できる。

```yaml
Conditions:
  UseOnDemand: !Or
    - !Not [!Equals [!Ref WebOnDemandBase, 0]]
    - !Not [!Equals [!Ref WebOnDemandWeight, 0]]

Resources:
  Service:
    Properties:
      CapacityProviderStrategy:
        - !If
          - UseOnDemand
          - CapacityProvider: FARGATE
            Base: !Ref WebOnDemandBase
            Weight: !Ref WebOnDemandWeight
          - !Ref AWS::NoValue      # ← 要素ごと消える
        - CapacityProvider: FARGATE_SPOT
          Weight: !Ref WebSpotWeight
```

**消す側を間違えないこと。** このノートは以前、FARGATE を常在させて Spot 側を `!If` で消す例を載せていた。**それは「prod は混合 / stg は FARGATE のみ」を作る形で、向きが逆**だった。このリポジトリで消したいのは **stg の FARGATE 要素**(stg こそ Spot 100% にしたい)。参考の Terraform も `terraform.tfvars:58-60` でリストに `FARGATE_SPOT` の 1 要素だけを入れており、そちらが正しい向き。

**消える側を無条件にしないこと。** 上の形では FARGATE_SPOT の要素が常に残る。**両方が消えて空リストになると「戦略を消す」という別の意味になり、ECS が失敗する**(→ §9-4)。`WebSpotWeight` の `MinValue: 1` がそれを構造的に防いでいる。

**ただし n 個には広がらない。** 要素を 3 つ 4 つと増やすには、そのぶん `Conditions` とパラメータを手で書き足す。Terraform の `for_each` のように「リストの長さぶん展開」はできない(→ [対訳ノート §4-4](./terraform-to-cloudformation.md))。**「0 個か 1 個か」の分岐までなら実用的、「n 個」は破綻する**、という線引き。

**そして「書けた」ことと「意図どおり動く」ことは別。** `base` が Blue/Green デプロイ中にどう効くかは公式ドキュメントに書かれておらず、実機で確かめて決着させた(→ §3-6)。

---

## 9. Terraform にあって CloudFormation に無いもの(と、その逆)

### 9-1. 算術が無い

**仕様: CloudFormation に四則演算の組み込み関数は無い。** `Fn::Add` のようなものは存在しない。

Terraform なら `locals` で計算できる。

```hcl
locals {
  ram_bytes             = 4 * 1024 * 1024 * 1024
  freeable_memory_bytes = local.ram_bytes * 0.25       # 書ける
}
```

CloudFormation は計算済みの定数を渡すしかない。テンプレート自身がそう書いている。

```yaml
# app.yml:278-280
  # 以下 4 つは AWS 公式の推奨アラーム(Best Practice Recommended Alarms)ベース。
  # インスタンスクラスとストレージ容量に連動するので環境ごとに渡す。
  # CloudFormation に算術の組み込み関数が無いため、計算済みの値を params に書く。
```

**帰結が §2-4 の話。** 従属値が「上流の値と切り離された裸の定数」として `params` に置かれるので、**インスタンスクラスを変えたときに連動しない**。人間が思い出して計算し直すしかない。Terraform なら `local` にすれば自動で追随させられる。

### 9-2. `apply_immediately` に相当する指定が無い

Terraform には RDS の変更を即時に当てるか、次のメンテナンスウィンドウまで待つかの選択がある。

```hcl
# rds.tf:37-38
  # DBの変更をすぐに反映させるか
  apply_immediately = var.rds_config.apply_immediately
```

```hcl
# variables.tf:113
    - apply_immediately: 設定変更を即時反映するか（prodはfalse推奨）
```

**これは分類 5(変更の当て方)の代表例で、本番では `false` にして深夜のウィンドウまで待たせるのが定石。**

`AWS::RDS::DBInstance` に `ApplyImmediately` プロパティは無い。

**未検証:** CloudFormation が RDS の変更をいつ当てるか(常に即時なのか、一部の変更はウィンドウまで待つのか)は実物で確かめていない。少なくとも**テンプレート側から選ぶ手段は無い**。prod で「変更は深夜に当てたい」をやるなら、**スタックの更新自体を深夜に走らせる**(ワークフローのスケジュール)という別のレイヤで解くことになる。

### 9-3. `lifecycle.ignore_changes` が無い

`ecs_web.tf:70-76` には ecspresso との住み分けのための `ignore_changes` がある。CloudFormation に相当物は無い。ただしこれは**差分の出し方の違いから来る話**で、環境差の話ではない。詳細は[対訳ノート §5](./terraform-to-cloudformation.md)と[ECS のデプロイ所有権ノート](./ecs-deploy-ownership.md)。

### 9-4. 逆に、CloudFormation のほうが強いところ

環境差の文脈でも、CloudFormation が勝っている点がある。

| | CloudFormation | Terraform |
|---|---|---|
| 値の検証 | `AllowedValues` / `AllowedPattern` / `MinValue` / `MaxValue` を宣言で書ける | `validation` ブロックを自分で書く |
| 検証が走る時点 | **Change Set の作成時**。AWS に何も触る前に落ちる | `plan` 時 |

**このリポジトリはこれを意図的に使っている。**

```yaml
# app.yml:259-262 の要約
  # AllowedPattern を付けているのは、params のプレースホルダ(REPLACE_WITH_...)のまま
  # 反映しようとしたときに Change Set の作成で落とすため。付けないと Chatbot リソースの
  # 作成まで進んでから同じ理由で失敗する。
```

**環境差の値には「間違った値を書ける余地」が必ずある。** `AllowedValues: ["true", "false"]` や `MinValue: 0 / MaxValue: 35` を付けておくと、`params/prod.json` の書き間違いが**リソースを作る前に**止まる。パラメータを増やすたびに書くのは手間だが、`params` を手で書く運用とは相性がいい。

ただし §3-2 で見たとおり、**パラメータをまたぐ制約(WebCpu と WebMemory の組み合わせ)は書けない**。単項目の検証は CloudFormation、複数項目の整合は Terraform、という住み分けになる。

**今回 `DbMaxAllocatedStorage` を足したことで、この穴がもう 1 つ増えた。** RDS の仕様上この値は `DbAllocatedStorage` より大きくなければならないが、**両方に `MinValue: 20` を書く以上のことはできない**。`params` で `DbAllocatedStorage: 100 / DbMaxAllocatedStorage: 50` と書いても Change Set の作成は通り、**RDS の API を叩く段になって初めて落ちる**。`WebCpu` / `WebMemory` とまったく同じ形で、テンプレートのコメントに「値を変えるときは両方を見ること」と書いて済ませるしかない(`app.yml:113-116`)。

**capacity provider の 3 パラメータで、この穴はもっと危ない形になった。** `WebOnDemandBase` / `WebOnDemandWeight` / `WebSpotWeight` が全部 0 だと `CapacityProviderStrategy` が空リストになる。**空リストは「戦略を消す」という意味を持つ**(仕様。2025-06-12 の変更でそうなった)ので、クラスタに `DefaultCapacityProviderStrategy` が無いこのテンプレートでは ECS がこう言って失敗する。

```
No launch type to fall back to for empty capacity provider strategy.
Your service was not created with a launch type.
```

**Change Set の作成は通る。**落ちるのは実行中で、スタック更新はロールバックになる(10〜20 分)。パラメータをまたぐ検証が書けない以上、**`WebSpotWeight` に `MinValue: 1` を付けて、空リストという状態自体を作れなくする**しかない。これは「検証で弾く」ではなく「**そもそも表現できなくする**」側の解き方で、§3-4 で `ContainerInsights` の `AllowedValues` から `disabled` を外したのと同じ手つき。

---

## 10. 撤収前提の環境ならではの歪み

このリポジトリは「使い終わったらスタックごと消す」運用なので、**本番向けの設定がそのままでは噛み合わない**箇所がある。第1部で「本番運用ならこうすべき」と書いた項目のいくつかは、この運用と正面から衝突する。

| 本番の定石 | 撤収前提だとどうなるか |
|---|---|
| `DeletionProtection: true` | **撤収できなくなる。** 消す前に `false` に更新する 1 手が要る。**`prod.json` で実際に `true` にした**ので、prod を建てるなら撤収手順のほうを直すことになる(→ §5-4) |
| `DeletionPolicy: Snapshot` | 撤収のたびにスナップショットが残り、課金が続く |
| `DeleteAutomatedBackups: false` | 同上。保持期間ぶん自動バックアップの課金が残る。**`prod.json` で実際に `false` にした** |
| `BackupRetentionPeriod` を長く | 建てて消すだけの環境では使う機会が無い。**`prod.json` で実際に上限の 35 にした**ので、`DeleteAutomatedBackups: false` と組み合わさると撤収後 35 日ぶん課金が残る |
| ログの長期保持 | **撤収のたびに全部消える。**(→ `docs/adr/0010` が受け入れている帰結の 2 つ目) |
| `BakeTimeInMinutes` を長く | 検証のたびに 30 分待つことになる |

**この表の読み方。** 左の列は「本番運用なら正しい」、右の列は「この運用では機能しない」。**どちらかが間違っているのではなく、運用の形が違えば正しい設定も違う**というだけ。

`docs/adr/0010-monitoring-in-ephemeral-stack.md` は、同じ衝突を監視レイヤについて明示的に受け入れている。構成を歪めて解決せず、生じる不整合は運用で吸収する、という方針。**環境差の設計もそれに従う。**

**そして最後にもう一度。** `cfn-destroy.yml` は stg 固定で、prod のスタックを建てる導線が無い。`params/prod.json` は**「本番だったらこうする」という設計の記録**であって、動いている設定ではない。このノートの第1部を実務に持っていくときは、値そのものではなく**分類 1〜6 の分け方**のほうを持っていくこと。

---

## 11. 早見表

| やりたいこと | Terraform | CloudFormation |
|---|---|---|
| 環境ごとの値の束 | `variable` の `object({...})` + `tfvars` | 平坦な `Parameters` を並べて `params/*.json`(→ §7) |
| 環境の値をコードに埋める | 環境ディレクトリを分ける | `Mappings` + `!FindInMap`(このリポジトリでは禁止 → §7) |
| 値の検証 | `validation` ブロック(条件式が書ける) | `AllowedValues` / `AllowedPattern` / `MinValue` / `MaxValue`(→ §9-4) |
| 項目をまたぐ整合の検証 | 書ける | **書けない** |
| 削除時の挙動を環境で変える | `skip_final_snapshot = var....` | **`DeletionPolicy` は定数。`DeletionProtection` で代替**(→ §5-4, §8-2) |
| 要素数が環境で変わるリスト | `dynamic` + `for_each` | `Fn::If` + `AWS::NoValue`。0/1 の分岐まで。**capacity provider の混合で実際に使った**(→ §3-3, §8-3) |
| 値から値を計算する | `locals` | **できない。手計算して定数を置く**(→ §9-1) |
| 変更を即時に当てるか選ぶ | `apply_immediately` | **選べない**(→ §9-2) |
| 環境ごとにリソースを作る/作らない | `count = x ? 1 : 0` | `Conditions` + `Condition:` 属性 |

---

## 12. このリポジトリでの実物の場所

| 見たいもの | 場所 |
|---|---|
| 環境差のパラメータ定義 | `cloudformation/app.yml:32-306`(`Parameters` セクション全体。RDS は 106-166、ECS は 167-248) |
| 環境ごとの値 | `cloudformation/params/stg.json` / `prod.json` |
| 環境差の置き場のルール | `cloudformation/README.md:18` |
| RDS 本体 | `cloudformation/app.yml:747-799` ↔ `terraform/modules/app-infrastructure/rds.tf:1-53` |
| ECS サービスとオートスケーリング | `cloudformation/app.yml:1410-1537` ↔ `terraform/modules/app-infrastructure/ecs_web.tf` |
| Terraform 側の環境差の型 | `terraform/modules/app-infrastructure/variables.tf:103-170` |
| Terraform 側の環境差の値 | `terraform/stg/terraform.tfvars:20-67` |
| 撤収前提と監視の衝突 | `docs/adr/0010-monitoring-in-ephemeral-stack.md` |
