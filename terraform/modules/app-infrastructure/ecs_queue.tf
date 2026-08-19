# --- Worker 用 ECS サービス（常時稼働） ---
resource "aws_ecs_service" "queue_worker" {
  name            = "${var.project_name}-queue-worker-service"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.queue_worker.arn
  desired_count   = var.ecs_queue_worker_service_config.desired_count

  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  dynamic "capacity_provider_strategy" {
    for_each = var.ecs_queue_worker_service_config.capacity_provider_strategy
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

  lifecycle {
    ignore_changes = [
      task_definition,
    ]
  }
}

# --- Worker 用 ECS タスク定義 ---
resource "aws_ecs_task_definition" "queue_worker" {
  family                   = "${var.project_name}-queue-worker-task"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = var.ecs_queue_worker_service_config.cpu
  memory                   = var.ecs_queue_worker_service_config.memory
  execution_role_arn       = module.ecs_task_execution_role.arn
  task_role_arn            = module.ecs_task_role.arn

  container_definitions = jsonencode([
    {
      name      = "queue-worker-container"
      image     = "${data.aws_ecr_repository.laravel.repository_url}:${var.image_tag_laravel}"
      essential = true
      command   = ["php", "artisan", "queue:work", "sqs", "--queue=${var.app_env}-qrcode-generation", "--tries=3", "--timeout=60"]

      environment = [
        { name = "DB_DATABASE", value = "${aws_db_instance.main.db_name}" },
        { name = "DB_HOST", value = "${aws_db_instance.main.address}" },
        { name = "DB_PORT", value = "3306" },
        { name = "DB_USERNAME", value = "${var.db_username}" },
        { name = "DB_CONNECTION", value = "mysql" },
        { name = "LOG_CHANNEL", value = "stderr" },
        { name = "LOG_DEPRECATIONS_CHANNEL", value = "stderr" },
        { name = "APP_URL", value = "https://${var.sub_backend_domain_name}.${var.domain_name}" },
        { name = "AWS_DEFAULT_REGION", value = "ap-northeast-1" },
        { name = "AWS_BUCKET", value = "${aws_s3_bucket.image_bucket.bucket}" },
        { name = "AWS_URL", value = "https://${aws_cloudfront_distribution.image_cdn.domain_name}" },
        { name = "AWS_USE_PATH_STYLE_ENDPOINT", value = "false" },
        { name = "APP_NAME", value = "practice" },
        { name = "APP_ENV", value = "${var.app_env}" },
        { name = "APP_DEBUG", value = "false" },
        { name = "FILESYSTEM_DISK", value = "s3" },
        # SQS キュー設定
        { name = "QUEUE_CONNECTION", value = "sqs" },
        { name = "SQS_PREFIX", value = "https://sqs.ap-northeast-1.amazonaws.com/${data.aws_caller_identity.current.account_id}" },
        { name = "SQS_QUEUE", value = "${var.app_env}-qrcode-generation" },
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
