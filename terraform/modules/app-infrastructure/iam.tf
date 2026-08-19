module "ecs_task_execution_role" {
  source = "terraform-aws-modules/iam/aws//modules/iam-role"

  name            = "${var.project_name}-execution-role"
  use_name_prefix = false

  trust_policy_permissions = {
    #ecs_tasksはだめ
    ecsTasks = {
      actions = ["sts:AssumeRole"]
      principals = [{
        type        = "Service"
        identifiers = ["ecs-tasks.amazonaws.com"]
      }]
    }
  }

  policies = {
    AmazonECSTaskExecutionRolePolicy = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
    EcsExecutionSsmPolicy            = aws_iam_policy.ecs_execution_ssm_policy.arn
  }
}

module "ecs_task_role" {
  source = "terraform-aws-modules/iam/aws//modules/iam-role"

  name            = "${var.project_name}-task-role"
  use_name_prefix = false

  trust_policy_permissions = {
    #ecs_tasksはだめ
    ecsTasks = {
      actions = ["sts:AssumeRole"]
      principals = [{
        type        = "Service"
        identifiers = ["ecs-tasks.amazonaws.com"]
      }]
    }
  }

  policies = {
    EcsS3Policy            = aws_iam_policy.ecs_s3_policy.arn
    SesSendPolicy          = aws_iam_policy.ses_send_policy.arn
    EcsExecPolicy          = aws_iam_policy.ecs_exec_policy.arn
    FirelensCloudWatchLogs = aws_iam_policy.firelens_cloudwatch_logs.arn
    XRayDaemonWriteAccess  = "arn:aws:iam::aws:policy/AWSXRayDaemonWriteAccess"
    SqsQueuePolicy         = aws_iam_policy.sqs_queue_policy.arn
  }
}

module "ecs_infra_lb" {
  source = "terraform-aws-modules/iam/aws//modules/iam-role"

  name            = "${var.project_name}-ecs-infra-lb-role"
  use_name_prefix = false

  trust_policy_permissions = {
    ecs = {
      actions = ["sts:AssumeRole"]
      principals = [{
        type        = "Service"
        identifiers = ["ecs.amazonaws.com"]
      }]
    }
  }

  policies = {
    AmazonECSInfrastructureRolePolicyForLoadBalancers = "arn:aws:iam::aws:policy/AmazonECSInfrastructureRolePolicyForLoadBalancers"
  }
}


# --- EventBridge 用 IAM ロール ---
module "eventbridge_role" {
  source = "terraform-aws-modules/iam/aws//modules/iam-role"

  name            = "${var.project_name}-eventbridge-role"
  use_name_prefix = false

  trust_policy_permissions = {
    events = {
      actions = ["sts:AssumeRole"]
      principals = [{
        type        = "Service"
        identifiers = ["events.amazonaws.com"]
      }]
    }
  }

  policies = {
    EventBridgeEcsRunTask = aws_iam_policy.eventbridge_ecs_run_task.arn
  }
}

module "notification_lambda_role" {
  source = "terraform-aws-modules/iam/aws//modules/iam-role"

  name            = "${var.project_name}-notification-lambda-role"
  use_name_prefix = false

  trust_policy_permissions = {
    lambda = {
      actions = ["sts:AssumeRole"]
      principals = [{
        type        = "Service"
        identifiers = ["lambda.amazonaws.com"]
      }]
    }
  }

  policies = {
    LambdaBasicExecutionRole    = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole",
    LambdaReadLaravelLogsPolicy = aws_iam_policy.lambda_read_laravel_logs.arn,
    LambdaSesSendPolicy         = aws_iam_policy.ses_send_policy.arn,
  }
}

# --- ログアーカイブ: CloudWatch Logs → Firehose 用ロール ---
# subscription filter が引き受け、Firehose にレコードを流す。
module "cwl_to_firehose_role" {
  source = "terraform-aws-modules/iam/aws//modules/iam-role"

  name            = "${var.project_name}-cwl-to-firehose-role"
  use_name_prefix = false

  trust_policy_permissions = {
    cwlogs = {
      actions = ["sts:AssumeRole"]
      principals = [{
        type        = "Service"
        identifiers = ["logs.ap-northeast-1.amazonaws.com"]
      }]
    }
  }

  policies = {
    PutToFirehose = aws_iam_policy.cwl_put_to_firehose.arn
  }
}

# --- ログアーカイブ: Firehose → S3 用ロール ---
# Firehose ストリームが引き受け、アーカイブバケットに書き込む。
module "firehose_logs_role" {
  source = "terraform-aws-modules/iam/aws//modules/iam-role"

  name            = "${var.project_name}-firehose-logs-role"
  use_name_prefix = false

  trust_policy_permissions = {
    firehose = {
      actions = ["sts:AssumeRole"]
      principals = [{
        type        = "Service"
        identifiers = ["firehose.amazonaws.com"]
      }]
    }
  }

  policies = {
    WriteToS3 = aws_iam_policy.firehose_write_logs_archive.arn
  }
}

# --- RDS Enhanced Monitoring 用 IAM ロール ---
# 標準メトリクス(AWS/RDS)は AWS 側のパイプラインで発行されるだけなので権限不要だが、
# Enhanced Monitoring は OS 内エージェントの収集データを「このアカウントの」CloudWatch Logs
# (RDSOSMetrics ロググループ)にログとして書き込む。AWS のサービスが顧客アカウント内の
# リソースを操作するには顧客が委任した IAM ロールが必須 — そのため RDS の監視サービス
# (monitoring.rds.amazonaws.com)が AssumeRole できるロールを渡す。マネージドポリシーの
# 中身は logs:CreateLogGroup / PutLogEvents 等の Logs 書き込み権限。
# 上の cwl_to_firehose_role(CloudWatch Logs → Firehose の委任)と同じ「サービスへの委任」パターン。
module "rds_enhanced_monitoring_role" {
  source = "terraform-aws-modules/iam/aws//modules/iam-role"

  name            = "${var.project_name}-rds-monitoring-role"
  use_name_prefix = false

  trust_policy_permissions = {
    rdsMonitoring = {
      actions = ["sts:AssumeRole"]
      principals = [{
        type        = "Service"
        identifiers = ["monitoring.rds.amazonaws.com"]
      }]
    }
  }

  policies = {
    AmazonRDSEnhancedMonitoringRole = "arn:aws:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
  }
}

