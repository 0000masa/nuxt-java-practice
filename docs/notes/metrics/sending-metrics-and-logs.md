# メトリクスとログの送り方 — CloudWatch・Datadog・Prometheus・OpenTelemetry

データポイントが「どの経路を通って、どんな見た目(フォーマット)で、どのプロトコルで」監視サービスに届くのかを、4 つの方式で比べる。

前提 → [metrics-basics.md](metrics-basics.md)(データポイント・ディメンション・時系列)

**このノートは実機で確かめたものではなく、公式ドキュメントと一般的な知識に基づく。** 例は手で書いたもので、出典は末尾にまとめた。

## まず結論(4 行)

- **ほとんどの経路は HTTPS である。** CloudWatch も Datadog も、受け口は HTTPS の API。例外は手元の UDP(StatsD)くらい
- **フォーマットは方式ごとにばらばら。** フォーム形式・JSON・CBOR・protobuf・テキスト。「データポイントの標準フォーマット」は無い
- **エージェントは「手元で受けて、まとめて外へ送る」中継役。** アプリ → エージェントは軽い経路(UDP やローカル)、エージェント → サービスは HTTPS
- **送り方には push と pull がある。** CloudWatch・Datadog・OTLP は送る側から push、Prometheus はサーバーが取りに来る pull

## 1. 全体像 — 誰が誰に送るか

```
【push】送る側から届ける
  アプリ ──UDP(StatsD)──▶ エージェント ──HTTPS──▶ CloudWatch / Datadog
  アプリ ──HTTPS(SDK / OTLP)──────────────────────▶ CloudWatch / Datadog
  AWS のサービス(RDS など)──(AWS の内部経路)──────▶ CloudWatch

【pull】サーバーが取りに来る
  Prometheus サーバー ──HTTP GET /metrics──▶ アプリ(その時点の値をテキストで返す)
```

| 方式 | 向き | アプリから出ていくもの | サービスに届くプロトコル |
| --- | --- | --- | --- |
| CloudWatch | push | SDK の API 呼び出し、または EMF のログ | HTTPS(フォーム形式 / JSON / CBOR / OTLP) |
| Datadog Agent | push | DogStatsD(UDP)、またはエージェントが自分で集める | HTTPS(圧縮した JSON / protobuf) |
| Prometheus | **pull** | `/metrics` でテキストを返すだけ | HTTP(サーバーが GET しに来る) |
| OpenTelemetry | push | OTLP | HTTP(S) か gRPC(中身は protobuf か JSON) |

### 「HTTP ではない」経路はあるか

ある。ただし多くは**手元のネットワークの中だけ**である。

- **StatsD / DogStatsD** は UDP。届いたかどうかを確認しない代わりに、アプリが送信で待たされない。
  アプリの近くのエージェントが受けて、外のサービスへは HTTPS で送り直す
- **OTLP の gRPC** は HTTP/2 の上に乗っている。つまりこれも HTTP の一種
- **Datadog Agent のログ**は、古いバージョンでは TLS 付きの TCP(ポート 10516)で送っていた。
  v6.19 / v7.19 以降は圧縮した HTTPS が既定で、HTTPS が通らないときだけ TCP に戻る

インターネット越しの経路が HTTPS に揃っているのは、ファイアウォールを通しやすく、認証(署名や API キー)と暗号化をそのまま使えるからである。

## 2. 同じ 1 点を各フォーマットで見る

ここでは同じデータポイントを各方式で表す。

```
CPUUtilization {DBClusterIdentifier=my-cluster, Role=WRITER} = 60 (%)
時刻 2026-10-10T14:25:00Z(UNIX 秒 1791642300)
```

### 2-1. CloudWatch `PutMetricData` — 従来のフォーム形式(AWS Query プロトコル)

昔からの形式。HTTPS の POST で、本文は `key=value&...` のフォーム形式になる(見やすさのため改行している)。

```
POST / HTTP/1.1
Host: monitoring.ap-northeast-1.amazonaws.com
Content-Type: application/x-www-form-urlencoded; charset=utf-8
Authorization: AWS4-HMAC-SHA256 Credential=...(SigV4 署名)

Action=PutMetricData
&Version=2010-08-01
&Namespace=MyApp
&MetricData.member.1.MetricName=CPUUtilization
&MetricData.member.1.Unit=Percent
&MetricData.member.1.Value=60
&MetricData.member.1.Timestamp=2026-10-10T14%3A25%3A00Z
&MetricData.member.1.Dimensions.member.1.Name=DBClusterIdentifier
&MetricData.member.1.Dimensions.member.1.Value=my-cluster
&MetricData.member.1.Dimensions.member.2.Name=Role
&MetricData.member.1.Dimensions.member.2.Value=WRITER
```

- `MetricData.member.N` を増やせば、**1 回のリクエストで複数のデータポイント**(最大 1000 件・1 MB)を送れる
- 名前空間 `AWS/` は AWS のサービス専用なので、自分で送るときは `MyApp` などにする。
  `AWS/RDS` のデータポイントは RDS が AWS の内部で入れるもので、この API で送ることはない

### 2-2. CloudWatch `PutMetricData` — JSON / CBOR プロトコル

2025 年に、AWS SDK と CloudWatch の通信に JSON と CBOR のプロトコルが加わり、SDK の既定はこちらに移った。
フォーム形式より本文が小さく、送る側の CPU とメモリも少なくて済む。中身の構造はフォーム形式と同じである。

```
POST / HTTP/1.1
Host: monitoring.ap-northeast-1.amazonaws.com
Content-Type: application/x-amz-json-1.0
X-Amz-Target: GraniteServiceVersion20100801.PutMetricData
Authorization: AWS4-HMAC-SHA256 Credential=...
```

```json
{
  "Namespace": "MyApp",
  "MetricData": [
    {
      "MetricName": "CPUUtilization",
      "Dimensions": [
        { "Name": "DBClusterIdentifier", "Value": "my-cluster" },
        { "Name": "Role", "Value": "WRITER" }
      ],
      "Timestamp": 1791642300,
      "Value": 60,
      "Unit": "Percent"
    }
  ]
}
```

- `X-Amz-Target` の `GraniteServiceVersion20100801` は、CloudWatch の API に AWS の内部で付いている名前である
- **CBOR** は「JSON と同じ構造を、バイナリで詰めて表す」形式。中身は上の JSON と同じだが、人間には読めないバイト列になる
- どのプロトコルを使うかは SDK が決める。アプリのコードは `putMetricData(...)` を呼ぶだけで変わらない

### 2-3. CloudWatch Logs `PutLogEvents` — ログの場合

ログも HTTPS の POST で、本文は JSON である。

```
POST / HTTP/1.1
Host: logs.ap-northeast-1.amazonaws.com
Content-Type: application/x-amz-json-1.1
X-Amz-Target: Logs_20140328.PutLogEvents
Authorization: AWS4-HMAC-SHA256 Credential=...
```

```json
{
  "logGroupName": "/ecs/app",
  "logStreamName": "app/app/0123456789abcdef",
  "logEvents": [
    { "timestamp": 1791642300000, "message": "2026-10-10T14:25:00Z INFO Started Application in 8.1 seconds" },
    { "timestamp": 1791642300120, "message": "{\"level\":\"WARN\",\"msg\":\"slow query\",\"ms\":1520}" }
  ]
}
```

- ログ 1 件は **時刻(ミリ秒)+ `message` の文字列** だけ。JSON を書いても、CloudWatch Logs にとってはただの文字列である
  (Logs Insights などが、読むときに JSON として解釈してくれる)
- 1 回のリクエストで複数件(合計 1 MB まで)を、時刻順に並べて送る
- `message` に EMF の JSON を書けば、CloudWatch がそこからメトリクスを取り出す(→ [metrics-basics.md](metrics-basics.md) §6)

### 2-4. DogStatsD — アプリから Datadog Agent へ(UDP)

UDP のポート 8125 に、1 行のテキストを投げる。

```
aws.rds.cpuutilization:60|g|#dbclusteridentifier:my-cluster,role:writer
```

| 部分 | 意味 |
| --- | --- |
| `aws.rds.cpuutilization` | メトリクス名 |
| `60` | 値 |
| `g` | 型(`g` = ゲージ、`c` = カウンタ、`h` = ヒストグラム など) |
| `#...` | タグ(Datadog では、ディメンションに当たるものを**タグ**と呼ぶ) |

- **時刻が無い。** エージェントが受け取った時刻を使う
- エージェントは届いた値を一定間隔(既定 10 秒)ごとに集計して、まとめて Datadog に送る
- 本物の RDS のメトリクスを Datadog で見るときは、Datadog の AWS 連携が CloudWatch から取り込むのが普通である。
  ここでは形式を比べるために、同じ値を DogStatsD で書いた

### 2-5. Datadog の API — エージェントから Datadog へ(HTTPS)

公開されている API の形式(v2)は、HTTPS の POST で JSON を送る。

```
POST /api/v2/series HTTP/1.1
Host: api.datadoghq.com
Content-Type: application/json
DD-API-KEY: <API キー>
```

```json
{
  "series": [
    {
      "metric": "aws.rds.cpuutilization",
      "type": 3,
      "points": [{ "timestamp": 1791642300, "value": 60 }],
      "tags": ["dbclusteridentifier:my-cluster", "role:writer"]
    }
  ]
}
```

- `type: 3` はゲージ(`1` = カウント、`2` = レート)
- 認証は AWS の署名ではなく、**API キーをヘッダに付ける**方式
- **最近のエージェント自身は、同じ API に protobuf で圧縮して送っている。** その形式は公開されていないので、上の JSON は「同じ中身を公開 API で送るとこうなる」という例である。
  エージェントを入れていれば、この違いを意識することは無い

### 2-6. Prometheus — サーバーが取りに来る(テキスト形式)

アプリは `/metrics` に今の値をテキストで並べておくだけで、Prometheus サーバーが一定間隔(例: 15 秒)で GET しに来る。

```
GET /metrics HTTP/1.1
Host: app:8080
```

```
# HELP cpu_utilization CPU utilization in percent
# TYPE cpu_utilization gauge
cpu_utilization{db_cluster_identifier="my-cluster",role="WRITER"} 60
```

- `{...}` の中が**ラベル**(Prometheus では、ディメンションに当たるものをラベルと呼ぶ)
- **時刻は普通書かない。** サーバーが取りに来た時刻が付く(書くこともでき、その場合は行末にミリ秒で置く)
- 返すのは「今の値」だけ。過去の値を持って記録するのはサーバーの仕事
- push が必要な場合のために、Prometheus どうしで送り合う remote write(HTTP POST + protobuf + snappy 圧縮)や、
  短命なジョブ用の Pushgateway もある

### 2-7. OpenTelemetry(OTLP)

製品をまたいで使える共通のプロトコル。中身は protobuf(または JSON)で、HTTP の POST(`/v1/metrics`、ポート 4318)か gRPC(ポート 4317)で送る。
CloudWatch(`https://monitoring.<リージョン>.amazonaws.com/v1/metrics`)も Datadog も受け付ける。

JSON で書くとこうなる(protobuf の場合も構造は同じ)。

```json
{
  "resourceMetrics": [
    {
      "resource": {
        "attributes": [{ "key": "service.name", "value": { "stringValue": "app" } }]
      },
      "scopeMetrics": [
        {
          "scope": { "name": "example" },
          "metrics": [
            {
              "name": "cpu.utilization",
              "unit": "%",
              "gauge": {
                "dataPoints": [
                  {
                    "timeUnixNano": "1791642300000000000",
                    "asDouble": 60,
                    "attributes": [
                      { "key": "db.cluster.identifier", "value": { "stringValue": "my-cluster" } },
                      { "key": "role", "value": { "stringValue": "WRITER" } }
                    ]
                  }
                ]
              }
            }
          ]
        }
      ]
    }
  ]
}
```

- ディメンションに当たるものは**属性(attributes)**。さらに「どこから来たか」の属性(`resource`)を別に持つ
- 時刻はナノ秒。桁が大きいので、JSON では文字列で書く
- 階層が深いのは、1 回の送信で「1 つのサービスの、複数の計測ライブラリの、複数のメトリクスの、複数のデータポイント」をまとめて送るため

### 並べてみると

| 方式 | 本文の形式 | 時刻 | ディメンションの呼び名 |
| --- | --- | --- | --- |
| CloudWatch(従来) | フォーム形式 | 送る側が付ける(省略すると受信時刻) | Dimensions |
| CloudWatch(新) | JSON / CBOR | 同上 | Dimensions |
| DogStatsD | 1 行のテキスト | 書かない(エージェントが付ける) | tags |
| Datadog API | JSON / protobuf | 送る側が付ける | tags |
| Prometheus | テキスト | 普通書かない(サーバーが付ける) | labels |
| OTLP | protobuf / JSON | 送る側が付ける(ナノ秒) | attributes |

名前は違っても、**「名前 + キーと値の組 + 時刻 + 数値」**という骨格は全部同じである。

### フォーマットそのものの違い

5 つのフォーマットは、次の 3 つの軸で整理できる。

1. **人が読めるか(テキストかバイナリか)**
2. **階層(入れ子)を表せるか**
3. **キーの名前を中に書くか(書かないなら、読む側に別の定義書が要る)**

| フォーマット | テキスト / バイナリ | 入れ子 | キーの名前 | 読むのに必要なもの | 主な用途 |
| --- | --- | --- | --- | --- | --- |
| フォーム形式 | テキスト | **できない**(`member.1.Name` のようにキーの名前に番号を埋めて表す) | 書く | なし | HTML のフォーム送信、AWS の古い API |
| JSON | テキスト | できる | 書く | なし | Web API 全般 |
| CBOR | **バイナリ** | できる | 書く | なし | JSON と同じ中身を小さく速く送りたいとき |
| protobuf | **バイナリ** | できる | **書かない(番号だけ)** | **`.proto` ファイル(スキーマ)** | gRPC、OTLP、大量のデータを送る社内通信 |
| テキスト(Prometheus / StatsD) | テキスト | できない(1 行 1 件) | 書く | なし | メトリクス専用。`printf` で書けるほど単純 |

同じ `{ Role: "WRITER", Value: 60 }` を各形式で表すと、大きさはこう変わる。

| フォーマット | 中身 | バイト数 |
| --- | --- | --- |
| フォーム形式 | `Role=WRITER&Value=60` | 20 |
| JSON | `{"Role":"WRITER","Value":60}` | 28 |
| CBOR | `a2 64 52 6f 6c 65 66 57 52 49 54 45 52 65 56 61 6c 75 65 18 3c` | 21 |
| protobuf | `0a 06 57 52 49 54 45 52 10 3c` | 10 |
| Prometheus のテキスト | `cpu{Role="WRITER"} 60` | 21 |
| DogStatsD | `cpu:60\|g\|#Role:WRITER` | 21 |

最後の 2 つだけは、比べるために**メトリクス名 `cpu` を足している**。
この 2 つは「任意のキーと値の組」を書く汎用の形式ではなく、「メトリクス名 + ラベル(タグ)+ 値」しか書けない専用の書式なので、
`{ Role, Value }` をそのままの形では表せない。`Role` はラベル、`Value` はメトリクスの値という役割に当てはめて書いている。

- **フォーム形式**は小さく見えるが、入れ子を表せない。そのため実際の `PutMetricData` では、`MetricData.member.1.Dimensions.member.2.Name=` のような長いキーが件数分並んで、一番大きくなる(§2-1)
- **CBOR** は JSON をそのままバイナリにしたもので、`"Role"` という文字列(`52 6f 6c 65`)は残っている。
  縮むのは、記号(`{` `"` `:` `,`)が型と長さの 1 バイトに置き換わり、数値が 10 進の文字ではなく 2 進で入るから。
  データの中にキーの名前があるので、**定義書なしで読み戻せる**
- **protobuf** はキーの名前を送らない。`0a` は「1 番のフィールド、長さ付きのデータ」、`10` は「2 番のフィールド、整数」という意味しか持たない。
  1 番が `Role` だと知るには、送る側と同じ `.proto` ファイルが要る

  ```protobuf
  message Sample {
    string role  = 1;
    int32  value = 2;
  }
  ```

  Datadog Agent の送る中身が「非公開」(§2-5)なのはこのため。通信を覗いてもバイト列しか見えず、`.proto` が公開されていなければ読めない。
  逆に OTLP は `.proto` を公開しているので、誰でも読み書きできる
- **Prometheus のテキストや StatsD** は汎用の形式ではなく、メトリクスを書くためだけに作られた 1 行 1 件の書式。
  入れ子は無いが、メトリクスにはそもそも要らない

### Prometheus のテキストと StatsD は何が違うのか

見た目は似ているが、書き方の違いではない。**1 行が表しているものが違う。**

| | Prometheus のテキスト | StatsD / DogStatsD |
| --- | --- | --- |
| 1 行が表すもの | **今の状態**(この瞬間の値の一覧表) | **起きた出来事 1 つ**(1 回分の報告) |
| 向き | pull(サーバーが HTTP で取りに来る) | push(アプリが UDP で投げる) |
| 値を覚えているのは | **アプリ**(プロセス内で累計を持ち続ける) | **受け取る側**(エージェントが一定時間ぶんを集計する) |
| 1 回に返す / 送る量 | そのアプリの全メトリクス | 1 行(数行をまとめることもある) |
| ラベル / タグ | 最初から仕様にある | 元の StatsD には無い。DogStatsD などが `#` で拡張した |
| 名前の慣習 | `_` 区切り(`http_requests_total`) | `.` 区切り(`http.requests`) |

違いが一番はっきり出るのは**カウンタ**である。リクエストを数える場合:

```
# Prometheus — アプリは起動してからの累計を持っていて、聞かれたらそれを答える
http_requests_total{path="/api/posts"} 1027

# StatsD — アプリはリクエストのたびに「1 回あった」と投げるだけ。足し算はエージェントがやる
http.requests:1|c|#path:/api/posts
```

- Prometheus は**取りこぼしに強い**。1 回の取得に失敗しても、次に取れた累計の差分から増えた数がわかる
- StatsD は**アプリが楽**。状態を持たずに投げるだけで済む。ただし UDP なので、落ちた行の分はそのまま数え漏れになる

ゲージ(CPU 使用率のような「その時点の値」)では、どちらも「今いくつか」を 1 つ書くだけなので、違いが見えにくい。

**どれを選ぶかは「読みやすさ」と「小ささ・速さ」の交換**である。人がデバッグしやすいテキスト(フォーム・JSON)から、
送る量が多いところほどバイナリ(CBOR・protobuf)へ移る、というのが CloudWatch の SDK の変更(§2-2)や OTLP の既定が protobuf であることの背景にある。

## 3. エージェントは何をしているのか

Datadog Agent も CloudWatch エージェントも、役割は同じである。

```
┌─ 同じホスト / 同じタスク ─────────────────────────┐
│ アプリ ──UDP 8125──▶ エージェント                  │
│                       ├ 自分でも集める(CPU、メモリ、ディスク、ログファイル) │
│                       ├ 一定時間ためて、集計して、まとめる                 │
│                       └ 圧縮する                                        │
└───────────────────────│───────────────────────────┘
                        ▼ HTTPS(API キー / 署名)
                   Datadog / CloudWatch
```

エージェントを挟む理由:

- **アプリを待たせない。** アプリは手元に UDP で投げて終わり。外への通信の遅さや失敗を引き受けるのはエージェント
- **まとめて送る。** 1 点ごとに HTTPS で外に出ると、接続と認証のコストが大きい
- **アプリが知らない値を集める。** OS の CPU やディスクは、アプリより OS に近いエージェントの方が集めやすい
- **資格情報を 1 か所に置ける。** API キーや AWS の権限を、アプリではなくエージェントに持たせる

## 4. このリポジトリでは — 実際に流れている 3 つの経路

```
① RDS の標準メトリクス
   Aurora のインスタンス ──(AWS の内部経路。非公開)──▶ CloudWatch メトリクス(AWS/RDS、60 秒ごと)
                                                       └▶ アラーム(RdsWriterCpuHighAlarm など)

② 拡張モニタリング(DbMonitoringInterval: 60)
   インスタンス上の RDS のエージェント ──▶ CloudWatch Logs(ロググループ RDSOSMetrics、JSON のログ)

③ アプリのログ
   ECS のコンテナの標準出力 ──awslogs ドライバ──▶ PutLogEvents(HTTPS + JSON)──▶ CloudWatch Logs
```

- **① はこちらから送っていない。** RDS が AWS の内部で送るので、プロトコルもフォーマットも公開されていない。
  見えるのは、保存された時系列(`AWS/RDS` の名前空間とディメンション)だけ
- **② は「JSON のログ」として届く。** OS のプロセスごとの CPU やメモリの内訳など、1 件にたくさんの値が入った JSON で、
  まさに「1 時点のスナップショットを 1 件の JSON に」という形である。ただしログなので、そのままではアラームに使えない
- **③ は 2-3 節の `PutLogEvents` そのもの。** タスク定義(`deploy/taskdef.json`)の `logDriver: awslogs` が、コンテナの出力を 1 行ずつイベントにして送っている。
  呼び出しの権限はタスクの**実行ロール**が持つ

このリポジトリには Datadog・Prometheus・OTel は入っていない。入れるとしたら、②と同じ「エージェントを足す」形になる。
Fargate ならエージェントを**サイドカー**(同じタスクの別コンテナ)として足す。

## 出典

- [PutMetricData — API Reference](https://docs.aws.amazon.com/AmazonCloudWatch/latest/APIReference/API_PutMetricData.html)
- [Amazon CloudWatch SDK supports optimized JSON, CBOR protocols(2025-07 プレビュー)](https://aws.amazon.com/about-aws/whats-new/2025/07/amazon-cloudwatch-sdk-optimized-json-cbor-protocols-in-preview)
- [Amazon CloudWatch SDK supports JSON, CBOR protocols(2025-12 一般提供、スペイン語版の告知)](https://aws.amazon.com/es/about-aws/whats-new/2025/12/amazon-cloudwatch-sdk-json-cbor-protocols/)
- [PutLogEvents — CloudWatch Logs API Reference](https://docs.aws.amazon.com/AmazonCloudWatchLogs/latest/APIReference/API_PutLogEvents.html)
- [CloudWatch Logs の HTTP エンドポイント(OTLP / JSON など)](https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/CWL_HTTP_Endpoints.html)
- [Send metrics using OpenTelemetry — Amazon CloudWatch](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/metrics-otel-send.html)
- [DogStatsD のデータグラム形式 — Datadog](https://docs.datadoghq.com/developers/dogstatsd/datagram_shell/)
- [Submit metrics(v2 series API)— Datadog](https://docs.datadoghq.com/api/latest/metrics/#submit-metrics)
- [Agent のログの送信方式(HTTPS / TCP)— Datadog](https://docs.datadoghq.com/agent/logs/log_transport/)
- [Prometheus のテキスト形式(Exposition formats)](https://prometheus.io/docs/instrumenting/exposition_formats/)
- [OTLP の仕様 — OpenTelemetry](https://opentelemetry.io/docs/specs/otlp/)
- [Amazon RDS の拡張モニタリング](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/USER_Monitoring.OS.html)
