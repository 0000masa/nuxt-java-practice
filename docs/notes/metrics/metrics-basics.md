# メトリクスの基礎 — データポイント・ディメンション・時系列

「CPU 使用率のアラーム」と言ったときに、裏で何がどういう単位で記録されているのかをまとめる。
CloudWatch を主な例にするが、考え方は Prometheus・Datadog・OpenTelemetry でも共通である。

送り方(プロトコル・フォーマット) → [sending-metrics-and-logs.md](sending-metrics-and-logs.md)
Aurora のアラームの設計 → [フェーズ19 設計書](../../superpowers/specs/2026-09-13-phase19-aurora-design.md)

**このノートは実機で確かめたものではなく、公式ドキュメントと一般的な知識に基づく。** 出典は末尾にまとめた。

## まず結論(4 行)

- メトリクスの 1 件(データポイント)は **「名前 + ディメンション + 時刻 + 数値 1 つ」**。CPU とメモリを 1 件に同居させない
- **ディメンションは名前の一部。** 組み合わせが違えば別の時系列になる。同じ CPU でも、インスタンス単位とクラスタ単位は別物
- 従来の CloudWatch は **送られてきた組み合わせしか持たない**。後から別の切り口で集計し直すことはできない(Prometheus は逆)
- **ログは「1 件に何でも書ける記録」、メトリクスは「数値 1 つの時系列」。** アラームとグラフが直接扱うのはメトリクス

## 1. データポイントは何でできているか

CloudWatch のデータポイント 1 件は次の組である。

| 要素 | 例 | 役割 |
| --- | --- | --- |
| 名前空間 | `AWS/RDS` | どのサービスのメトリクスか。AWS のサービスは `AWS/` で始まる |
| メトリクス名 | `CPUUtilization` | 何を測ったか |
| ディメンション | `DBClusterIdentifier=my-cluster`, `Role=WRITER` | どの対象の値か(名前と値の組。最大 30 個) |
| タイムスタンプ | `2026-10-10T14:25:00Z` | いつの値か |
| 値 | `60` | **数値 1 つ** |
| 単位 | `Percent` | 表示用 |

ここで大事なのは 2 点。

1. **1 件に入る数値は 1 つだけ。** CPU・メモリ・接続数は、メトリクス名の違う別々の件になる
2. **ディメンションは付け足しの情報欄ではなく、「どの時系列か」を決める名前の一部。**

つまり、次のような「1 時点のスナップショットを 1 件の JSON にまとめる」形ではない。

```jsonc
// こうはなっていない
{
  "date": "2026-10-10T14:25:00Z",
  "DBClusterIdentifier": "my-cluster",
  "DBInstanceIdentifier": "db-1",
  "Role": "WRITER",
  "CPUUtilization": 60,
  "FreeableMemory": 1073741824
}
```

この形に近いのはむしろ **ログ**のほうである(→ 6 節)。

### 「数値 1 つ」は製品を問わない

「1 件 = 数値 1 つ」は CloudWatch だけの決まりではない。Prometheus でも Datadog でも OpenTelemetry でも同じである。
§3 で見る「後から集計できるかどうか」とは別の話なので、混ぜないこと。

| | 1 件に入る数値 | 後から集計し直せるか |
| --- | --- | --- |
| 従来の CloudWatch | 1 つ | できない(送った組み合わせだけ) |
| Prometheus | 1 つ | できる(ラベルで束ね直す) |

Prometheus で後から集計できるのは、**同じメトリクス名の時系列どうし**をラベルで束ねられるからである。
CPU とメモリは Prometheus でも別のメトリクス名で、1 件にまとめて持つことはない。

例外は**値のばらつき**を表す型(§5 のヒストグラム / 分布)で、1 件に複数の数値が入る。

- CloudWatch の `StatisticValues`(件数・合計・最小・最大)や `Values` / `Counts` の配列
- OpenTelemetry のヒストグラムのデータポイント(件数・合計・バケットごとの件数)
- Prometheus のネイティブヒストグラム。従来のヒストグラムは `_bucket` / `_sum` / `_count` という、数値 1 つずつの時系列に分けて持つ

ただしこれも **「1 つのメトリクスの、ばらつきの要約」** であり、CPU とメモリのように別のものを同居させているわけではない。

### データポイントの「フォーマット」

データポイントは**概念**であって、決まったファイル形式は無い。

- **送るとき**の見た目はプロトコル次第。CloudWatch だけでもフォーム形式・JSON・CBOR があり、
  Prometheus はテキスト、OpenTelemetry は protobuf か JSON(並べた例 → [sending-metrics-and-logs.md](sending-metrics-and-logs.md) §2)。
  データポイントは 1 件ずつ独立しているが、**通信は 1 件ずつではない**。`PutMetricData` は 1 回のリクエストに
  最大 1000 件を配列で詰められ、エージェントや SDK もためてからまとめて送る。「1 件ずつ別の件」と「1 件ずつ別の通信」は別の話
- **保存するとき**の形式は各製品の内部実装で、CloudWatch は公開していない。
  時系列データベースは一般に、時刻と値を列ごとに詰めて圧縮する(1 件ずつ JSON で持つことはまず無い)
- **読み出すとき**も 1 件ずつではない。CloudWatch の `GetMetricData` は、1 本の時系列を
  「時刻の配列」と「値の配列」の 2 本の配列で返す。下はその**レスポンス**(送るときの形ではない)

```json
{
  "MetricDataResults": [
    {
      "Id": "writerCpu",
      "Label": "CPUUtilization",
      "Timestamps": ["2026-10-10T14:27:00Z", "2026-10-10T14:26:00Z", "2026-10-10T14:25:00Z"],
      "Values": [58.0, 62.5, 60.0],
      "StatusCode": "Complete"
    }
  ]
}
```

名前とディメンションは時系列 1 本につき 1 回だけ持ち、データポイントは `(時刻, 値)` の列として並ぶ、という見方が実態に近い。

## 2. 時系列 — ディメンションの組み合わせごとに 1 本

**名前空間 + メトリクス名 + ディメンションの組み合わせ** が同じデータポイントを時刻順に並べたものが、1 本の**時系列**(CloudWatch の用語では 1 つの「メトリクス」)である。

Aurora の CPU なら、同じ時刻について次のような件が**別々の時系列**に入る。

```
CPUUtilization {DBInstanceIdentifier=db-1}                   → 60
CPUUtilization {DBInstanceIdentifier=db-2}                   → 20
CPUUtilization {DBClusterIdentifier=my-cluster, Role=WRITER} → 60
CPUUtilization {DBClusterIdentifier=my-cluster, Role=READER} → 20
CPUUtilization {DBClusterIdentifier=my-cluster}              → (全台をまとめた値)
```

- 組み合わせが 10 通りあれば、1 時点の CPU は **10 件のデータポイント**として 10 本の時系列に入る
- 同じ値のコピーとは限らない。**それぞれの単位で集計した値**が入る(クラスタ単位は全台をまとめた値なので db-1 とは違う)
- `Role=WRITER` の値は自分で付けた名前ではなく、RDS が使っている値。その時点で誰が Writer かを RDS が判断して集計するので、フェイルオーバー後は新しい Writer の値が入る
- AWS の内部で本当に 10 回送っているのか、1 回受けて振り分けているのかは公開されていない。
  確かなのは **結果として別々の時系列として保存される**こと

### 選ぶときは完全一致

CloudWatch でアラームやグラフの対象を選ぶときは、ディメンションの組み合わせが**完全に一致する**時系列が選ばれる。

- `DBClusterIdentifier` + `Role` を指定 → その組で送られてきた時系列
- `Role` だけを指定 → **その組の時系列は存在しない**。アラームは `INSUFFICIENT_DATA` のまま(または欠損の扱いの設定どおり)になる

コンソールの「すべてのメトリクス」も、大量のデータを条件で絞り込むというより、
「名前空間 → ディメンションの組の種類 → 1 本」と**保存済みの時系列の一覧から 1 本を選ぶ**操作である。

## 3. 集計を「送る前」にやるか「見るとき」にやるか

ここが製品ごとに流儀が分かれる。

| | 従来の CloudWatch(Classic) | Prometheus / CloudWatch の OTel メトリクス |
| --- | --- | --- |
| 何を保存するか | **送る側が用意した組み合わせ**ごとの時系列 | 一番細かい単位(ラベルを全部付けた時系列) |
| クラスタ単位の値を見るには | その組み合わせのデータポイントを**送っておく** | 見るときにクエリで集計する(`avg by (cluster) (cpu)`) |
| 送らなかった切り口 | **作れない** | ラベルさえあれば後から作れる |

AWS のドキュメントも、送っていないディメンションの組み合わせでは統計を取り出せない、と明記している。
自分でメトリクスを送る(`PutMetricData`)場合、`{InstanceId=...}` だけを送ればクラスタ単位の時系列は存在しない。
欲しければ **送る側がその組み合わせのデータポイントも送る**。

補足が 2 つある。

- **CloudWatch Metrics Insights**(SQL 風のクエリ)を使えば、既にある時系列どうしを `GROUP BY` で束ねることはできる。
  ただし束ねる材料は送られてきた時系列とそのディメンションだけで、時系列に無いディメンション
  (たとえばインスタンス単位の時系列に `Role` は無い)で切ることはできない
- **CloudWatch は OpenTelemetry のメトリクスもネイティブに受け付けるようになった(2026 年時点で GA)。**
  OTLP で送ったメトリクスは PromQL で問い合わせられ、Prometheus と同じく見るときに集計できる。
  ただし RDS の標準メトリクスは従来型のままなので、このリポジトリのアラームには §2 の話がそのまま当てはまる

> この節の内容は、以前の説明で「CloudWatch は代わりに集計してくれない」と言い切った部分を、
> 「従来型のメトリクスでは」と範囲を限定して言い直したものである。

## 4. カーディナリティ — 時系列の本数はコストになる

ディメンション(ラベル)の値の種類が増えるほど、時系列の本数は掛け算で増える。この本数を**カーディナリティ**と呼ぶ。

- CloudWatch の従来型のカスタムメトリクスは **時系列 1 本ごとに月額**がかかる。組み合わせを 10 通り送れば 10 本分
- ユーザー ID やリクエスト ID のように値が無数にあるものをディメンションにすると、時系列が爆発する。
  こういう「1 件ごとに違う値」は**ログに書く**もの
- CloudWatch の OTel メトリクスは時系列の本数ではなく取り込んだ量(GB)で課金されるので、高カーディナリティに強い

## 5. メトリクスの種類

値の意味によって、いくつかの型がある。名前は製品によって少し違う。

| 型 | 意味 | 例 |
| --- | --- | --- |
| **ゲージ(gauge)** | その時点の値。上がりも下がりもする | CPU 使用率、空きメモリ、接続数 |
| **カウンタ(counter)** | 増える一方の累計、またはある期間の回数 | リクエスト数、エラー数 |
| **ヒストグラム / 分布** | 値のばらつき。パーセンタイルを出すのに使う | レスポンスタイム |

RDS の `CPUUtilization` や `FreeableMemory` はゲージである。
CloudWatch の従来型はデータポイントに型を持たせず、「1 分間に届いた値の Average / Maximum / Sum …」を
**見る側が統計(`Statistic`)として選ぶ**。Prometheus・DogStatsD・OTLP は送る側が型を宣言する。

## 6. ログとの違い

| | ログ | メトリクス |
| --- | --- | --- |
| 1 件の中身 | 時刻 + **任意の文字列**(JSON を書いてもよい) | 時刻 + **数値 1 つ** |
| 向いているもの | 「何が起きたか」の記録。1 件ごとに違う値(ユーザー ID など) | 「どのくらいか」の推移 |
| 保存先(AWS) | CloudWatch Logs | CloudWatch メトリクス |
| アラーム | そのままでは張れない | 張れる |
| 量に対する料金 | 書いた量(GB)に比例 | 時系列の本数(従来型) |

ログからメトリクスを作る方法はある。

- **メトリクスフィルタ** — ログを正規表現などで数えて、メトリクスを作る(「ERROR の行数」など)
- **Embedded Metric Format(EMF)** — 決まった形の JSON ログを書くと、CloudWatch が中の数値をメトリクスとして取り出す

EMF の例。1 行のログから、`{Cluster, Role}` と `{Cluster}` の **2 本の時系列**にデータポイントが入る。
「1 つの出来事から、組み合わせの数だけデータポイントができる」ことがよく見える形である。

```json
{
  "_aws": {
    "Timestamp": 1791642300000,
    "CloudWatchMetrics": [
      {
        "Namespace": "MyApp",
        "Dimensions": [["Cluster", "Role"], ["Cluster"]],
        "Metrics": [{ "Name": "CPUUtilization", "Unit": "Percent" }]
      }
    ]
  },
  "Cluster": "my-cluster",
  "Role": "WRITER",
  "CPUUtilization": 60
}
```

## 7. このリポジトリでは — アラームの `Dimensions:` を読む

`cloudformation/app.yml` の `RdsWriterCpuHighAlarm`:

```yaml
      Namespace: AWS/RDS
      MetricName: CPUUtilization
      Dimensions:
        - Name: DBClusterIdentifier
          Value: !Ref DbCluster
        - Name: Role
          Value: WRITER
      Statistic: Average
      Period: 60
```

このノートの言葉で読むとこうなる。

- `Namespace` + `MetricName` + `Dimensions` で、**RDS が送ってくる時系列のうち「このクラスタの Writer の CPU」の 1 本を選んでいる**。
  ラベルを付けているのではない(リソースの `Tags:` とも無関係)
- `Statistic: Average` + `Period: 60` で、その時系列の **1 分ごとの平均**を評価する
- インスタンス単位(`DBInstanceIdentifier`)ではなく役割単位にしているのは、フェイルオーバー後も「Writer の CPU」を見続けるため
- Reader 側は 2 台以上をまとめた時系列なので、1 台だけの悪化が平均で薄まる。そのため `Maximum` / `Minimum` で評価している

**落とし穴:** このアラームは `TreatMissingData: notBreaching` である。
ディメンションの名前や値を書き間違えると、存在しない時系列を選んでデータが来ないまま **鳴らずに黙る**。
stg を建てたら、選んでいる組み合わせが実在するかを一度確かめておくとよい。

```bash
aws cloudwatch list-metrics --namespace AWS/RDS --metric-name CPUUtilization \
  --dimensions Name=DBClusterIdentifier,Value=<クラスタ名>
```

結果に `Role` を含む組み合わせ(`WRITER` / `READER`)が出ていれば、アラームの選んでいる時系列は存在する。

## 出典

- [Amazon CloudWatch concepts — Dimensions](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/cloudwatch_concepts.html)
- [PutMetricData — API Reference](https://docs.aws.amazon.com/AmazonCloudWatch/latest/APIReference/API_PutMetricData.html)
- [GetMetricData — API Reference](https://docs.aws.amazon.com/AmazonCloudWatch/latest/APIReference/API_GetMetricData.html)
- [Amazon Aurora のメトリクス(CloudWatch)](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/Aurora.AuroraMonitoring.Metrics.html)
- [Embedded Metric Format の仕様](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/CloudWatch_Embedded_Metric_Format_Specification.html)
- [CloudWatch Metrics Insights](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/query_with_cloudwatch-metrics-insights.html)
- [Send metrics using OpenTelemetry — Amazon CloudWatch](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/metrics-otel-send.html)
- [Amazon CloudWatch が OpenTelemetry メトリクスをネイティブサポート(GA)— DevelopersIO](https://dev.classmethod.jp/articles/cloudwatch-otel-metrics-ga-otlp-promql/)
