
# --- SNS topic ---
resource "aws_sns_topic" "ecs_task_shortage" {
  name = "${var.project_name}-ecs-task-shortage"
}

# --- SNS subscription (email) ---
resource "aws_sns_topic_subscription" "ecs_task_shortage_email" {
  topic_arn = aws_sns_topic.ecs_task_shortage.arn
  protocol  = "email"
  endpoint  = data.aws_ssm_parameter.alert_email_to.value
}

# --- RDS 検知層の集約トピック ---
# RDS の3系統の検知（①ログのメトリクスフィルタ+アラーム ②メトリクスアラーム4本
# ③RDS Event Subscription）をすべてこの1トピックに集約する。
# 設計: docs/monitoring/rds-log-monitoring.md / ADR 0012
resource "aws_sns_topic" "rds_alerts" {
  name = "${var.project_name}-rds-alerts"
}

resource "aws_sns_topic_subscription" "rds_alerts_email" {
  topic_arn = aws_sns_topic.rds_alerts.arn
  protocol  = "email"
  endpoint  = data.aws_ssm_parameter.alert_email_to.value
}
