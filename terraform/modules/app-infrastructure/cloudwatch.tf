# --- CloudWatch Log Group ---
resource "aws_cloudwatch_log_group" "ecs_log" {
  name              = "/ecs/${var.project_name}"
  retention_in_days = 30 //30日で消す
}

# ログアーカイブ用 Firehose の「配信エラーの理由」を記録するロググループ。
# これが無いと Firehose が S3 配信に失敗した際、原因(IAM/バケット等)を追えない。
# 運用診断用なので保持は短め(14日)。アーカイブ対象ではない(subscriptionには流さない)。
resource "aws_cloudwatch_log_group" "firehose_logs_archive" {
  name              = "/aws/kinesisfirehose/${var.project_name}-logs-archive"
  retention_in_days = 14
}

# CloudWatch Logs は2階層: ロググループ(器・保持日数を持つ) > ログストリーム(ログ行の実体の1本)。
# 例: /ecs/<project> ロググループの中に、ECS がコンテナ/タスクごとにストリームを自動生成している。
# ECS/Lambda はストリームを実行時に自動生成するので明示不要だが、Firehose の
# cloudwatch_logging_options は書き込み先ストリーム名の指定が要る。ここで1本先に作っておくことで
# Firehose ロールの権限を logs:PutLogEvents だけに絞れる(自動生成させると CreateLogStream も必要になる)。
resource "aws_cloudwatch_log_stream" "firehose_logs_archive_s3_delivery" {
  name           = "S3Delivery"
  log_group_name = aws_cloudwatch_log_group.firehose_logs_archive.name
}

# staging.ERROR または staging.CRITICAL を Lambda に流す
resource "aws_cloudwatch_log_subscription_filter" "laravel_error_critical_to_lambda" {
  name           = "${var.project_name}-laravel-error-to-lambda"
  log_group_name = aws_cloudwatch_log_group.ecs_log.name

  # どちらかを含めばマッチ（OR）
  filter_pattern  = "?${var.app_env}.ERROR ?${var.app_env}.CRITICAL"
  destination_arn = aws_lambda_function.notification_function.arn

  # 権限が先にないと作成に失敗するので依存関係を明示
  depends_on = [aws_lambda_permission.allow_cloudwatch_logs_invoke]
}

# 全ログを S3 へ長期アーカイブするため Firehose に流す。
# filter_pattern は空文字 = 全件マッチ（監査目的で全ログを残す）。
# 上の error/critical → Lambda フィルタとは別物。subscription filter は
# 1ロググループあたりデフォルト最大2本で、これで 2/2（上限内）。
# 設計: docs/monitoring/cloudwatch-logs-s3-archival.md / ADR 0011
resource "aws_cloudwatch_log_subscription_filter" "ecs_log_to_firehose_archive" {
  name            = "${var.project_name}-ecs-log-to-firehose-archive"
  log_group_name  = aws_cloudwatch_log_group.ecs_log.name
  filter_pattern  = ""
  destination_arn = aws_kinesis_firehose_delivery_stream.logs_archive.arn

  # role_arn が必要な理由（上の Lambda 向けフィルタには無いのに、こちらには有る）:
  #   配信先のタイプで認可方式が違う。
  #   - Lambda 宛て: Lambda 側のリソースベースポリシー(aws_lambda_permission)で
  #     「logs.<region>.amazonaws.com からの呼び出し」を許可する → フィルタに role_arn 不要。
  #   - Firehose/Kinesis 宛て: そうしたリソースベースポリシーが無いため、CloudWatch Logs が
  #     この role を AssumeRole して firehose:PutRecord を実行する → role_arn 必須。
  role_arn = module.cwl_to_firehose_role.arn
}

# --- CloudWatch Alarm (Metric Math) ---
resource "aws_cloudwatch_metric_alarm" "ecs_running_less_than_desired" {
  alarm_name        = "${var.project_name}-ecs-running-less-than-desired"
  alarm_description = "ECS service running tasks is less than desired tasks"

  # expression が 1 になったらアラーム
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = 1

  evaluation_periods  = 1
  datapoints_to_alarm = 1

  # メトリクス未取得で誤爆しないように（必要なら "breaching" に変える）
  treat_missing_data = "notBreaching"

  alarm_actions = [aws_sns_topic.ecs_task_shortage.arn]
  ok_actions    = [aws_sns_topic.ecs_task_shortage.arn]

  # 現在 RUNNING のタスク数
  metric_query {
    id = "m_running"
    metric {
      namespace   = "ECS/ContainerInsights"
      metric_name = "RunningTaskCount"
      dimensions = {
        ClusterName = aws_ecs_cluster.main.name
        ServiceName = aws_ecs_service.main.name
      }
      period = 60
      stat   = "Minimum"
    }
    # return_data = 複数の metric_query のうち「どの結果を閾値判定の対象にするか」のフラグ。
    # アラームでは必ずちょうど1つだけ true にする（0個でも2個以上でもエラー）。
    # このクエリは expression から id (m_running) で参照される計算材料なので false。
    return_data = false
  }

  # Desired（目標タスク数）
  metric_query {
    id = "m_desired"
    metric {
      namespace   = "ECS/ContainerInsights"
      metric_name = "DesiredTaskCount"
      dimensions = {
        ClusterName = aws_ecs_cluster.main.name
        ServiceName = aws_ecs_service.main.name
      }
      period = 60
      stat   = "Minimum"
    }
    # 上と同じく計算材料（expression から m_desired で参照）なので false
    return_data = false
  }

  # 不足していたら 1、そうでなければ 0
  metric_query {
    id         = "e_shortage"
    expression = "IF(m_running < m_desired, 1, 0)"
    label      = "running < desired"
    # この式の結果だけが threshold(1) と比較される = アラームの判定対象
    return_data = true
  }
}

# =====================================================================
# RDS 検知層（ログ・メトリクス・イベントの3系統 → SNS rds_alerts に集約）
# 設計: docs/monitoring/rds-log-monitoring.md / ADR 0012
# =====================================================================

# --- RDS ロググループ ---
# RDS はログエクスポート時に /aws/rds/instance/<identifier>/<log-type> という名前の
# ロググループが無ければ自動作成するが、その場合は保持期間「無期限」の Terraform 管理外
# リソースになってしまう。同名で先に作っておくことで保持期間を管理下に置く
# （RDS 側には作成順・破棄順を保証する depends_on を張っている。rds.tf 参照）。
resource "aws_cloudwatch_log_group" "rds_error" {
  name              = "/aws/rds/instance/${var.project_name}-db/error"
  retention_in_days = 30
}

resource "aws_cloudwatch_log_group" "rds_slowquery" {
  name              = "/aws/rds/instance/${var.project_name}-db/slowquery"
  retention_in_days = 30
}

# --- メトリクスフィルタ ---
# エラーログ: MariaDB の深刻度タグ [ERROR] の行だけカウントする。
# エラーログは名前に反してエラー以外も流れる: 起動・シャットダウン・InnoDB 初期化などの
# 通常メッセージは [Note]、軽微な警告は [Warning] のタグで出力され、正常稼働中の
# エラーログはほぼ [Note] だけ。つまり「ロググループにログが流れている」のに
# メトリクスが出現しないのは正常（[ERROR] 行が来て初めてメトリクスが発行される）。
# [Warning] は起動時等にも出るノイズなので意図的に対象外。
# パターンに [] を含むため引用符で囲んだ完全一致タームにしている。
resource "aws_cloudwatch_log_metric_filter" "rds_error_log" {
  name           = "${var.project_name}-rds-error-log"
  log_group_name = aws_cloudwatch_log_group.rds_error.name
  # CloudWatch に渡る実際のパターンは "[ERROR]"（外側の \" は HCL のエスケープ）。
  # 意味:「文字列 [ERROR] をそのまま含む行にマッチ」。MariaDB のエラーログは
  # 「2026-07-05 12:00:00 0 [ERROR] InnoDB: ...」のように深刻度タグを含むため、この行だけ数える。
  # フィルタ構文では引用符なしの [...] は「スペース区切りのフィールド分解」という別機能の
  # 構文になってしまうので、リテラルとして扱うには引用符で囲んだタームにする必要がある。
  pattern = "\"[ERROR]\""

  metric_transformation {
    name      = "RdsErrorLogCount"
    namespace = "${var.project_name}/RDS"
    # value = パターンにマッチした1件につきメトリクスへ送る値。固定の "1" にすることで
    # 「1件マッチ = 1」の件数カウントになる（アラーム側は Sum で合計して件数を判定する）。
    # 固定値のほかに、ログから抽出した数値（例: JSON ログの $.duration）を送る使い方もある。
    value = "1"
  }
}

# スロークエリ: 1エントリは複数行（# Time: / # User@Host: / # Query_time: / SQL本文）に
# またがるため、行数ではなくエントリごとに1回だけ出る「# Query_time:」をカウントする。
resource "aws_cloudwatch_log_metric_filter" "rds_slowquery_log" {
  name           = "${var.project_name}-rds-slowquery-log"
  log_group_name = aws_cloudwatch_log_group.rds_slowquery.name
  # 実際のパターンは "# Query_time:"。意味:「この文字列をそのまま含む行にマッチ」。
  # スロークエリの1エントリに1回だけ現れるヘッダ行（# Query_time: 2.000000 Lock_time: ...）を
  # 数える。# とスペースを含むリテラルなので、上と同じく引用符で囲んだタームにしている。
  pattern = "\"# Query_time:\""

  metric_transformation {
    name      = "RdsSlowQueryCount"
    namespace = "${var.project_name}/RDS"
    # 上と同じく「1件マッチ = 1」の件数カウント
    value = "1"
  }
}

# --- ログ由来のアラーム ---
# エラーログは「5分間に1件以上」で即通知（エラーは1件でも異常）
resource "aws_cloudwatch_metric_alarm" "rds_error_log" {
  alarm_name        = "${var.project_name}-rds-error-log"
  alarm_description = "RDS error log contains [ERROR] entries"

  namespace   = "${var.project_name}/RDS"
  metric_name = "RdsErrorLogCount"
  statistic   = "Sum"
  period      = 300

  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = 1
  evaluation_periods  = 1
  datapoints_to_alarm = 1

  # メトリクスフィルタはマッチが無いとデータ自体を出さないので、データ無し = 正常として扱う
  treat_missing_data = "notBreaching"

  alarm_actions = [aws_sns_topic.rds_alerts.arn]
  ok_actions    = [aws_sns_topic.rds_alerts.arn]
}

# スロークエリは1件ごとの通知はアラート疲れを起こすため、「5分間に5件以上」の急増だけ拾う。
# 深掘り（どのSQLが遅いか）は通知後に Logs Insights で行う（stg は PI 非対応のため）。
resource "aws_cloudwatch_metric_alarm" "rds_slowquery_surge" {
  alarm_name        = "${var.project_name}-rds-slowquery-surge"
  alarm_description = "RDS slow query count surged (>= 5 in 5 minutes)"

  namespace   = "${var.project_name}/RDS"
  metric_name = "RdsSlowQueryCount"
  statistic   = "Sum"
  period      = 300

  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = 5
  evaluation_periods  = 1
  datapoints_to_alarm = 1

  treat_missing_data = "notBreaching"

  alarm_actions = [aws_sns_topic.rds_alerts.arn]
  ok_actions    = [aws_sns_topic.rds_alerts.arn]
}

# --- メトリクスアラーム定番4本 ---
# 閾値は AWS 公式推奨（Best Practice Recommended Alarms）ベース。インスタンスクラス依存の
# 値を含むため rds_config.alarm_thresholds として環境ごとの tfvars から渡す。
# 評価は公式推奨どおり「60秒×5データポイント連続」（一過性のスパイクで鳴らさない）。
resource "aws_cloudwatch_metric_alarm" "rds_cpu_high" {
  alarm_name        = "${var.project_name}-rds-cpu-high"
  alarm_description = "RDS CPUUtilization is too high (burst credit exhaustion / runaway query)"

  namespace   = "AWS/RDS"
  metric_name = "CPUUtilization"
  dimensions = {
    DBInstanceIdentifier = aws_db_instance.main.identifier
  }
  statistic = "Average"
  period    = 60

  comparison_operator = "GreaterThanThreshold"
  threshold           = var.rds_config.alarm_thresholds.cpu_utilization_percent
  evaluation_periods  = 5
  datapoints_to_alarm = 5

  treat_missing_data = "notBreaching"

  alarm_actions = [aws_sns_topic.rds_alerts.arn]
  ok_actions    = [aws_sns_topic.rds_alerts.arn]
}

# ストレージ枯渇は DB 停止に直結するため、悪化方向に安全側の Minimum で評価する
resource "aws_cloudwatch_metric_alarm" "rds_free_storage_low" {
  alarm_name        = "${var.project_name}-rds-free-storage-low"
  alarm_description = "RDS FreeStorageSpace is running low (risk of DB stopping)"

  namespace   = "AWS/RDS"
  metric_name = "FreeStorageSpace"
  dimensions = {
    DBInstanceIdentifier = aws_db_instance.main.identifier
  }
  statistic = "Minimum"
  period    = 60

  comparison_operator = "LessThanThreshold"
  threshold           = var.rds_config.alarm_thresholds.free_storage_space_bytes
  evaluation_periods  = 5
  datapoints_to_alarm = 5

  treat_missing_data = "notBreaching"

  alarm_actions = [aws_sns_topic.rds_alerts.arn]
  ok_actions    = [aws_sns_topic.rds_alerts.arn]
}

resource "aws_cloudwatch_metric_alarm" "rds_freeable_memory_low" {
  alarm_name        = "${var.project_name}-rds-freeable-memory-low"
  alarm_description = "RDS FreeableMemory is running low (swap / OOM precursor)"

  namespace   = "AWS/RDS"
  metric_name = "FreeableMemory"
  dimensions = {
    DBInstanceIdentifier = aws_db_instance.main.identifier
  }
  statistic = "Average"
  period    = 60

  comparison_operator = "LessThanThreshold"
  threshold           = var.rds_config.alarm_thresholds.freeable_memory_bytes
  evaluation_periods  = 5
  datapoints_to_alarm = 5

  treat_missing_data = "notBreaching"

  alarm_actions = [aws_sns_topic.rds_alerts.arn]
  ok_actions    = [aws_sns_topic.rds_alerts.arn]
}

resource "aws_cloudwatch_metric_alarm" "rds_connections_high" {
  alarm_name        = "${var.project_name}-rds-connections-high"
  alarm_description = "RDS DatabaseConnections near max_connections (connection leak / pool exhaustion)"

  namespace   = "AWS/RDS"
  metric_name = "DatabaseConnections"
  dimensions = {
    DBInstanceIdentifier = aws_db_instance.main.identifier
  }
  statistic = "Average"
  period    = 60

  comparison_operator = "GreaterThanThreshold"
  threshold           = var.rds_config.alarm_thresholds.database_connections
  evaluation_periods  = 5
  datapoints_to_alarm = 5

  treat_missing_data = "notBreaching"

  alarm_actions = [aws_sns_topic.rds_alerts.arn]
  ok_actions    = [aws_sns_topic.rds_alerts.arn]
}
