# ==============================================================================
# EventBridge + ECS バッチ処理（日次レポート）
# 毎日 UTC 00:00（JST 09:00）に php artisan report:daily を実行
# ==============================================================================

# --- EventBridge ルール（毎日 UTC 00:00 = JST 09:00） ---
resource "aws_cloudwatch_event_rule" "daily_report" {
  name                = "${var.project_name}-daily-report"
  description         = "毎日 UTC 00:00（JST 09:00）に日次レポートバッチを実行"
  schedule_expression = "cron(0 0 * * ? *)"
}

# --- EventBridge ターゲット（ECS タスク） ---
resource "aws_cloudwatch_event_target" "daily_report" {
  rule      = aws_cloudwatch_event_rule.daily_report.name
  target_id = "${var.project_name}-batch-daily-report"
  arn       = aws_ecs_cluster.main.arn
  role_arn  = module.eventbridge_role.arn

  ecs_target {
    task_definition_arn = aws_ecs_task_definition.batch_daily_report.arn
    task_count          = 1

    dynamic "capacity_provider_strategy" {
      for_each = var.ecs_batch_daily_report_task_config.capacity_provider_strategy
      content {
        capacity_provider = capacity_provider_strategy.value.capacity_provider
        weight            = capacity_provider_strategy.value.weight
        base              = capacity_provider_strategy.value.base
      }
    }

    network_configuration {
      subnets          = [local.private_subnet_a_id, local.private_subnet_c_id]
      security_groups  = [aws_security_group.ecs_sg.id]
      assign_public_ip = false
    }
  }
}
