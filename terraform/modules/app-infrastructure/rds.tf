# RDS本体
resource "aws_db_instance" "main" {
  identifier                      = "${var.project_name}-db"
  engine                          = "mariadb"
  engine_version                  = "11.4" # ←ここを11.4系に固定
  instance_class                  = var.rds_config.instance_class
  allocated_storage               = 20
  storage_type                    = "gp3"
  db_name                         = var.db_name
  username                        = var.db_username # variables.tfで定義した変数を参照
  password                        = data.aws_ssm_parameter.db_password.value
  db_subnet_group_name            = aws_db_subnet_group.main.name
  vpc_security_group_ids          = [aws_security_group.rds_sg.id]
  skip_final_snapshot             = var.rds_config.skip_final_snapshot
  publicly_accessible             = false
  storage_encrypted               = true
  copy_tags_to_snapshot           = true
  auto_minor_version_upgrade      = true
  enabled_cloudwatch_logs_exports = var.rds_config.enabled_cloudwatch_logs_exports
  parameter_group_name            = aws_db_parameter_group.main.name
  maintenance_window              = "sun:15:00-sun:15:30" # UTC。JST では 月曜 00:00-00:30（UTC+9）

  # Multi-AZ 構成（スタンバイDBを別AZに自動配置、障害時は1〜2分で自動フェイルオーバー）
  multi_az = var.rds_config.multi_az

  # 自動バックアップ（最大35日、PITR有効化の前提）
  backup_retention_period = var.rds_config.backup_retention_period
  backup_window           = "17:00-17:30" # UTC。JST 02:00-02:30（メンテナンスウィンドウと被らないように）

  # Performance Insights（直近7日間は無料）
  performance_insights_enabled = var.rds_config.performance_insights_enabled

  # Enhanced Monitoring（OS層メトリクスをCloudWatch Logsへ送信。0で無効）
  monitoring_interval = var.rds_config.monitoring_interval
  monitoring_role_arn = var.rds_config.monitoring_interval > 0 ? module.rds_enhanced_monitoring_role.arn : null

  # DBの変更をすぐに反映させるか
  apply_immediately = var.rds_config.apply_immediately

  tags = {
    Name = "${var.project_name}-db"
  }

  # ロググループ(cloudwatch.tf)を RDS より先に作らせる（作成順）と同時に、
  # destroy を「RDS 削除 → ロググループ削除」の順にする（破棄は依存の逆順）ための依存。
  # ロググループが先に消えると、RDS が削除処理中の最終ログ書き込みで同名ロググループを
  # 再作成し、Terraform 管理外の孤児（保持期間: 無期限）が残ってしまう。
  # 設計: docs/monitoring/rds-log-monitoring.md
  depends_on = [
    aws_cloudwatch_log_group.rds_error,
    aws_cloudwatch_log_group.rds_slowquery,
  ]
}

# カスタムパラメータグループ。
# スロークエリログはデフォルトパラメータグループでは生成すらされない（log_slow_query=0）ため、
# ログを CloudWatch へエクスポートする以前に、まず生成を有効化する必要がある。
# 注意: MariaDB 10.11 でスロークエリ関連の変数が改名され、mariadb11.4 ファミリーは新名称のみ
# 受け付ける（旧名称 slow_query_log / long_query_time を指定すると apply 時に
# InvalidParameterValue: Could not find parameter で失敗する。MySQL 系の記事は旧名称が多いので注意）。
resource "aws_db_parameter_group" "main" {
  name   = "${var.project_name}-db-params"
  family = "mariadb11.4" # engine_version と揃える

  # スロークエリログの生成を有効化（旧名称: slow_query_log）
  parameter {
    name  = "log_slow_query"
    value = "1"
  }

  # スロークエリの閾値（秒）。MariaDB デフォルトは10秒だが、Webアプリで10秒は既に大事故なので
  # 実務の定石どおり1秒に締める（AWS 公式の設定例も 1.0 秒）（旧名称: long_query_time）
  parameter {
    name  = "log_slow_query_time"
    value = "1"
  }

  # CloudWatch Logs へのエクスポートは FILE 出力が前提（デフォルトの TABLE のままだと出力されない）。
  # RDS のログエクスポートは「インスタンス内のログファイルを転送する」仕組みなので、
  # ファイル出力になっていないログは CloudWatch に流せない。TABLE のままだとスロークエリは
  # DB 内の mysql.slow_log テーブルに書かれ、転送すべきファイルが存在しない状態になる。
  # なお、この log_output が効くのはスロークエリ/general ログのみで、エラーログは
  # この設定に関係なく常にファイル出力（だから今までもエラーログだけは転送できていた）。
  parameter {
    name  = "log_output"
    value = "FILE"
  }

  tags = {
    Name = "${var.project_name}-db-params"
  }
}

# RDS イベント購読。ログにもメトリクスにも出ない「RDS 自体のライフサイクルイベント」
# （フェイルオーバー・ストレージ逼迫・メンテナンス再起動など）を SNS に通知する。
# configuration change は apply のたびに自分にも通知が来るが、prod 想定と同じ構成を
# 維持する判断（docs/monitoring/rds-log-monitoring.md の決定7）。
resource "aws_db_event_subscription" "rds_alerts" {
  name        = "${var.project_name}-rds-events"
  sns_topic   = aws_sns_topic.rds_alerts.arn
  source_type = "db-instance"
  source_ids  = [aws_db_instance.main.identifier]

  event_categories = [
    "availability",         # 停止・再起動
    "configuration change", # DB設定変更（ガバナンス用途）
    "failover",             # Multi-AZ フェイルオーバー発生
    "failure",              # インスタンス障害
    "low storage",          # ストレージ残量逼迫
    "maintenance",          # メンテナンスウィンドウでの再起動等
  ]
}

#DBサブネットグループ
resource "aws_db_subnet_group" "main" {
  name = "${var.project_name}-db-subnet-group"
  subnet_ids = [
    local.private_subnet_a_id,
    local.private_subnet_c_id
  ]
  tags = {
    Name = "${var.project_name}-db-subnet-group"
  }
}
