# --- Runner Task ---
# migrate / seed / 任意シェルコマンドを GitHub Actions から containerOverrides で
# 実行する汎用タスク定義。command はワークフロー側で必ず上書きする前提。
resource "aws_ecs_task_definition" "runner" {
  family                   = "${var.project_name}-runner-task"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = var.ecs_runner_task_config.cpu
  memory                   = var.ecs_runner_task_config.memory
  execution_role_arn       = module.ecs_task_execution_role.arn
  task_role_arn            = module.ecs_task_role.arn

  container_definitions = jsonencode([
    {
      name  = "runner-container"
      image = "${data.aws_ecr_repository.laravel.repository_url}:${var.image_tag_laravel}"

      environment = [
        { name = "DB_DATABASE", value = "${aws_db_instance.main.db_name}" },
        { name = "DB_HOST", value = "${aws_db_instance.main.address}" },
        { name = "DB_PORT", value = "3306" },
        { name = "DB_USERNAME", value = "${var.db_username}" },
        { name = "DB_CONNECTION", value = "mysql" },
        { name = "LOG_CHANNEL", value = "stderr" },
        { name = "LOG_DEPRECATIONS_CHANNEL", value = "stderr" },
        { name = "AWS_DEFAULT_REGION", value = "ap-northeast-1" },
        { name = "AWS_USE_PATH_STYLE_ENDPOINT", value = "false" },
        { name = "APP_NAME", value = "practice" },
        { name = "APP_ENV", value = "${var.app_env}" },
      ]

      secrets = [
        { name = "DB_PASSWORD", valueFrom = "${data.aws_ssm_parameter.db_password.arn}" },
        { name = "APP_KEY", valueFrom = "${data.aws_ssm_parameter.app_key.arn}" },
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.ecs_log.name
          "awslogs-region"        = "ap-northeast-1"
          "awslogs-stream-prefix" = "runner"
        }
      }
    }
  ])
}

# --- バッチ処理用 ECS タスク定義 ---
resource "aws_ecs_task_definition" "batch_daily_report" {
  family                   = "${var.project_name}-batch-daily-report-task"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = var.ecs_batch_daily_report_task_config.cpu
  memory                   = var.ecs_batch_daily_report_task_config.memory
  execution_role_arn       = module.ecs_task_execution_role.arn
  task_role_arn            = module.ecs_task_role.arn

  container_definitions = jsonencode([
    {
      name      = "batch-container"
      image     = "${data.aws_ecr_repository.laravel.repository_url}:${var.image_tag_laravel}"
      essential = true
      command   = ["php", "artisan", "report:daily"]

      environment = [
        { name = "DB_DATABASE", value = "${aws_db_instance.main.db_name}" },
        { name = "DB_HOST", value = "${aws_db_instance.main.address}" },
        { name = "DB_PORT", value = "3306" },
        { name = "DB_USERNAME", value = "${var.db_username}" },
        { name = "DB_CONNECTION", value = "mysql" },
        { name = "LOG_CHANNEL", value = "stderr" },
        { name = "LOG_DEPRECATIONS_CHANNEL", value = "stderr" },
        { name = "AWS_DEFAULT_REGION", value = "ap-northeast-1" },
        { name = "AWS_USE_PATH_STYLE_ENDPOINT", value = "false" },
        { name = "APP_NAME", value = "practice" },
        { name = "APP_ENV", value = "${var.app_env}" },
        { name = "APP_DEBUG", value = "false" },
        { name = "MAIL_MAILER", value = "ses" },
        { name = "MAIL_FROM_ADDRESS", value = "noreply@${var.sub_frontend_domain_name}.${var.domain_name}" },
        { name = "MAIL_FROM_NAME", value = "${var.project_name}" },
      ]

      secrets = [
        { name = "DB_PASSWORD", valueFrom = "${data.aws_ssm_parameter.db_password.arn}" },
        { name = "APP_KEY", valueFrom = "${data.aws_ssm_parameter.app_key.arn}" },
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.ecs_log.name
          "awslogs-region"        = "ap-northeast-1"
          "awslogs-stream-prefix" = "ecs"
        }
      }
    }
  ])
}
