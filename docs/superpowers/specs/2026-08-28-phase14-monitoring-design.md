# フェーズ14: 監視・検知層(CloudWatch アラーム + SNS + ログアーカイブ)

日付: 2026-08-28
方針 → [ADR-0010](../../adr/0010-monitoring-in-ephemeral-stack.md)
前提 → [フェーズ13 の設計書](./2026-08-19-phase13-cloudformation-design.md)

---

## 1. スコープ

**やること**

- CloudWatch アラーム 7 本 + メトリクスフィルタ 2 本 + RDS イベント購読 1 本
- SNS トピック 2 本 + メール購読 2 本(**スタック内**)
- CloudWatch Logs → Firehose → S3 のログアーカイブ
- 上記に伴うワークフローの変更(`ALERT_EMAIL` の受け渡し、購読確認リマインダ、撤収時のリトライ)

**やらないこと**

- **アプリのエラーログ通知**(Terraform 側の Lambda 経由のメール)。通知本文にログを入れられないと管理しづらいので、Sentry などを別途検討する
- **ALB のアラーム**(`TargetResponseTime` / `HTTPCode_ELB_5XX_Count`)。Terraform に無いものは増やさない
- **ダッシュボード**(`AWS::CloudWatch::Dashboard`)。Terraform 側にも無い
- **アラームの実機確認**。このリポジトリはまだ AWS 上で一度も建てていない

**前提となる立場**: 検知層は常時稼働を前提に設計されたものを**構成を変えずに**移植する。作り捨て運用との不整合は運用で吸収する。理由と、受け入れた 4 つの帰結 → [ADR-0010](../../adr/0010-monitoring-in-ephemeral-stack.md)。

---

## 2. 決定一覧

### 決定1: Terraform の検知層のうち、移植するのは 5 群

| 群 | Terraform の実体 | 何を捕まえるか | 移植 |
|---|---|---|---|
| A | RDS メトリクスアラーム 4 本 | じわじわ悪化する資源枯渇 | ○ |
| B | RDS ログのメトリクスフィルタ 2 + アラーム 2 | DB 内部のエラーと遅いクエリ | ○ |
| C | `aws_db_event_subscription` | ログにもメトリクスにも出ない RDS のライフサイクル事象 | ○ |
| D | ECS タスク数不足(Metric Math) | タスクが起動できていない・落ち続けている | ○ |
| E | アプリログ `ERROR`/`CRITICAL` → Lambda → メール | アプリの例外 | **×** |
| F | 全ログ → Firehose → S3 | 監査・保全 | ○ |

E を落としたので、`EcsLogGroup` のサブスクリプションフィルタ枠は **1/2** しか使わない(上限は 1 ロググループあたり 2 本)。**残りの 1 本は E を後から足すときのために空けてある。**

### 決定2: SNS はスタック内。トピックは Terraform と同じ 2 本

`ecs-task-shortage`(D 用)と `rds-alerts`(A・B・C を集約)。宛先は 2 本とも同じ `AlertEmail`。

**トピックポリシーは付けない。** CloudWatch アラームも RDS イベント購読も、同一アカウント内なら既定のトピックポリシーで publish できる(Terraform 側も付けていない)。

代償(購読確認を毎回 2 通踏む / 片方の踏み忘れで片系統が無音になる)は ADR-0010 の帰結 1。

### 決定3: 通知先のメールアドレスは GitHub の Environment secret から渡す

**`AlertEmail` パラメータ(`NoEcho: true`、`Default` なし)** で受け、`cfn-apply.yml` が `secrets.ALERT_EMAIL` を `--parameters` に積む。`BasicAuthCredential` と同じ経路なので、**新しい仕組みが 1 つも増えない。**

落とした案:

- **SSM の SecureString** — **使えない。** `{{resolve:ssm-secure:...}}` が対応するプロパティは 11 個に限定されていて、`AWS::SNS::Subscription.Endpoint` は入っていない(`BasicAuthCredential` と同じ制約)
- **SSM の平文 String を `{{resolve:ssm:...}}` で読む** — Terraform に一番近いが、手動管理の SSM が 4 つ → 5 つに増える
- **`params/<env>.json` に直書き** — メールアドレスが Git 履歴に残る

**`Default` を置かないのは意図的。** 渡し忘れると Change Set の作成が落ちる。`cfn-apply.yml` はそれより手前で `ALERT_EMAIL` の空文字を弾き、原因の分かるメッセージを出す。**検知層が黙って死ぬより、構築が止まるほうがマシ**という判断。

### 決定4: `OKActions` は Terraform どおり全アラームに付ける

初回構築時に「OK になりました」通知が 7 通届く、B は 1 回の異常につき必ず 2 通になる、という代償を受け入れる(ADR-0010 の帰結 2)。

### 決定5: 閾値は A の 4 つだけパラメータに開く

Terraform の `rds_config.alarm_thresholds` に対応。CloudFormation の `Parameters` は構造化した型を持てないので、[フェーズ13 の決定2](./2026-08-19-phase13-cloudformation-design.md) どおり平坦に開く。**CloudFormation には算術の組み込み関数が無い**ので、「RAM の 25%」のような計算はテンプレート側でできず、計算済みの値を `params` に書く。

| パラメータ | stg (t4g.micro / 20GiB) | prod (t4g.medium / 50GiB) | 根拠 |
|---|---|---|---|
| `RdsCpuThresholdPercent` | `90` | `90` | AWS 公式推奨 |
| `RdsFreeStorageThresholdBytes` | `2147483648`(2 GiB) | `5368709120`(5 GiB) | 割当ストレージの 10% |
| `RdsFreeableMemoryThresholdBytes` | `268435456`(256 MiB) | `1073741824`(1 GiB) | RAM の 25% |
| `RdsConnectionsThreshold` | `72` | `307` | `max_connections`(= `DBInstanceClassMemory/12582880`)の 90% |

**B の閾値(`[ERROR]` 5 分で 1 件 / スロークエリ 5 分で 5 件)と評価期間は直書き。** Terraform も直書きで、環境で変える理由が無い。フェーズ8・10 との衝突は閾値ではなく運用で吸収する(ADR-0010 の帰結 4)。

### 決定6: カスタムメトリクスの名前空間に環境名を入れる

`${ProjectName}-${EnvName}/RDS`。**メトリクスフィルタの出力にはディメンションが無い**ので、名前空間が同じだと stg と prod の値が同じメトリクスに合算される。

Terraform は `${var.project_name}/RDS` だが、あちらは `project_name = "practice-stg"` と**環境名込み**なので、これは形を変えたのではなく**同じ挙動に揃えたもの**。

### 決定7: `ContainerInsights` の `AllowedValues` から `disabled` を外す

D は `ECS/ContainerInsights` 名前空間の `RunningTaskCount` / `DesiredTaskCount` を読む。**この 2 つは Container Insights が有効なときだけ発行され**、標準の `AWS/ECS` にタスク数のメトリクスは無い(CPU / メモリ使用率だけ)。

`disabled` を選べる状態のままだと、`params` を 1 行書き換えるだけで**アラームは残ったまま二度と鳴らなくなる**。費用を抑えたいときは `enhanced` → `enabled` に落とせば足りる。

**これは Terraform 側には無かった穴**(あちらは `enhanced` の直書きで、変数ですらない)。フェーズ13 でパラメータに開いたときに新しく開いたもの。

### 決定8: ログアーカイブのバケットはスタック内に置き、撤収のたびに捨てる

**F は保全機能としては動いていない。** ライフサイクル(30 日 Glacier IR / 365 日削除)は一度も発火しない。理由と代償 → ADR-0010 の帰結 3。

Firehose の設定は Terraform どおり。

- `BufferingHints`: 900 秒 / 64 MB。低トラフィックでは時間側が先に効く
- `CompressionFormat: UNCOMPRESSED`。CloudWatch Logs は subscription に gzip 済みで流すので、展開も再圧縮もしない(展開すると取り込み課金が解凍後のバイト数基準になる)
- `Prefix: app-logs/!{timestamp:yyyy/MM/dd/}`、`ErrorOutputPrefix: errors/!{firehose:error-output-type}/...`
- 配信エラーの**理由**を記録するロググループ(保持 14 日)とログストリーム `S3Delivery` を先に作る。先に作ることで Firehose のロールを `logs:PutLogEvents` だけに絞れる(自動生成させると `logs:CreateLogStream` も要る)

IAM ロールは 2 つ増える。

| ロール | 信頼する相手 | 権限 |
|---|---|---|
| `firehose-role` | `firehose.amazonaws.com` | アーカイブバケットへの書き込み + 配信エラーログの `PutLogEvents` |
| `cwl-to-firehose-role` | `logs.<region>.amazonaws.com` | `firehose:PutRecord` / `PutRecordBatch` |

**サブスクリプションフィルタに `RoleArn` が要るのは配信先が Firehose だから。** Lambda 宛てなら Lambda 側のリソースベースポリシー(`AWS::Lambda::Permission`)で許可するのでロールが要らないが、Firehose にはそれが無い。

### 決定9: 撤収は「空にする → 削除」を 1 回だけリトライする

**Firehose は 900 秒ごとにバケットへ自動で書きにくる。** 「空にする → `delete-stack`」の間(ECS サービスの削除などで 1〜3 分)にフラッシュが挟まると `BucketNotEmpty` になり、**15 分待たされた末に `DELETE_FAILED`** で終わる。確率はおよそ 10〜20%。

2 回目が確実に成功するのは、1 回目の削除で **Firehose とサブスクリプションフィルタが既に消えている**ため(バケットは Firehose に依存されているので、CloudFormation は Firehose を先に消す)。

**バケット名は最初に控えて、リトライ時に引き直さない。** `DELETE_FAILED` になった後のスタックから `Outputs` を読めるとは限らないため。

**これは Terraform には無かった問題。** あちらは `force_destroy` が destroy の一部としてバケットを空にするので、この窓が存在しない。素の CloudFormation に `force_destroy` 相当が無いことの帰結。

### 決定10: 購読確認は検査せず、固定リマインダだけ出す

`cfn-deploy.yml` の締めのサマリに、トピック名と「踏むまで届かない」ことを書く。**AWS を 1 回も叩かない**ので、Actions のロールに触らずに済む。

実際の `PendingConfirmation` の数を検査する案もあったが、`sns:ListSubscriptionsByTopic` を Actions のロールに足す必要があるので採らなかった。代わりに**読み飛ばされやすい**という弱点は残る。

---

## 3. 変更したファイル

| ファイル | 変更 |
|---|---|
| `cloudformation/app.yml` | パラメータ 5 / リソース 21 / 出力 3 を追加。`ContainerInsights` の `AllowedValues` を縮小 |
| `cloudformation/params/{stg,prod}.json` | 閾値 4 つを追加(列幅も揃え直した) |
| `.github/workflows/cfn-apply.yml` | `ALERT_EMAIL` を必須パラメータとして積む + 空文字のガード |
| `.github/workflows/cfn-deploy.yml` | 締めのサマリに購読確認のリマインダ |
| `.github/workflows/cfn-destroy.yml` | バケット 2 つを空にする + 1 回リトライ |
| `docs/infrastructure/cloudformation-operations.md` | 手動作業 2 つ(secret / IAM)と購読確認の手順 |

`app.yml` は 54,178 → **82,361 バイト**になった。S3 経由なので上限は 1MB([ADR-0008](../../adr/0008-template-bucket-as-resident-resource.md))で、まだ余裕がある。ライフサイクルも同一なので、[フェーズ13 の決定1](./2026-08-19-phase13-cloudformation-design.md) がネストスタックを却下した判断も変わらない。

---

## 4. 着手前に必要な手動作業

**この 2 つをやらないと動かない。**

1. **GitHub Environment `stg` に secret `ALERT_EMAIL` を登録する。** 未登録だと `cfn-apply.yml` がガードで落ちる(意図した設計)
2. **`nuxt-java-practice-gha-cfn-stg` ロールの `EmptyImageBucket` にアーカイブバケットの ARN を足す。** リソースを画像バケットに限定しているので、**このままだと撤収が必ず失敗する**

CloudFormation サービスロールは `AdministratorAccess` なので、SNS / Firehose / CloudWatch のリソース型が増えても追加作業は無い。

---

## 5. 実機で確かめること(未検証)

このリポジトリはまだ AWS 上で一度も建てていない。以下は設計時の想定で、**実測で覆る可能性がある。**

- **初回構築時に届く「OK になりました」通知の実数。** 7 通と見込んでいる(アラーム 7 本ぶん)
- **RDS の起動時に `[ERROR]` 行が出るかどうか。** 出るなら建てるたびに B のエラーアラームが 1 回鳴る。MySQL 8.4 の起動メッセージは `[System]` / `[Note]` / `[Warning]` なので出ないはずだが、RDS 特有の行があるかもしれない
- **`# Query_time:` パターンが MySQL のスロークエリログに一致するか。** Terraform 側は MariaDB 前提で書かれている(パラメータグループの変数名は MySQL と MariaDB で違うことを既にフェーズ13 で踏んでいる)
- **C(イベント購読)が建てるたびに何か飛ばすか。** `creation` / `deletion` カテゴリは購読していないので静かなはずだが、`availability` に何か含まれるかもしれない
- **撤収のリトライが実際に発生する頻度**
