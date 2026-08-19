# =====================================================================
# GitHub Actions ワークフロー用 最小権限 IAM ポリシー
# =====================================================================
# 各ワークフローが実行する AWS API 呼び出しのみを許可する。
# - ecs:RegisterTaskDefinition / ecs:DescribeTaskDefinition は AWS の仕様上
#   resource-level 制約に対応していないため Resource = "*" となる。
# - サービス／タスク定義の更新先は ARN で限定し、可能な箇所では
#   ecs:cluster / iam:PassedToService 条件で更にスコープを絞る。

# ---------------------------------------------------------------------
# ecr-deploy-laravel.yml: Laravel イメージの ECR push
# ---------------------------------------------------------------------
resource "aws_iam_policy" "gha_ecr_laravel_policy" {
  name        = "${var.project_name}-gha-ecr-laravel-policy"
  description = "GitHub Actions: push Laravel image to ECR"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "EcrAuthorizationToken"
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = "*"
      },
      {
        Sid    = "EcrPushLaravel"
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability",
          "ecr:InitiateLayerUpload",
          "ecr:UploadLayerPart",
          "ecr:CompleteLayerUpload",
          "ecr:PutImage",
          "ecr:BatchGetImage"
        ]
        Resource = data.aws_ecr_repository.laravel.arn
      }
    ]
  })
}

# ---------------------------------------------------------------------
# ecr-deploy-nginx.yml: Nginx イメージの ECR push
# ---------------------------------------------------------------------
resource "aws_iam_policy" "gha_ecr_nginx_policy" {
  name        = "${var.project_name}-gha-ecr-nginx-policy"
  description = "GitHub Actions: push Nginx image to ECR"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "EcrAuthorizationToken"
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = "*"
      },
      {
        Sid    = "EcrPushNginx"
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability",
          "ecr:InitiateLayerUpload",
          "ecr:UploadLayerPart",
          "ecr:CompleteLayerUpload",
          "ecr:PutImage",
          "ecr:BatchGetImage"
        ]
        Resource = data.aws_ecr_repository.nginx.arn
      }
    ]
  })
}

# ---------------------------------------------------------------------
# ecs-update-laravel.yml / ecs-update-nginx.yml で共有
# main service (practice-stg-main-service) のタスク定義差し替え
# ---------------------------------------------------------------------
resource "aws_iam_policy" "gha_ecs_update_main_service_policy" {
  name        = "${var.project_name}-gha-ecs-update-main-service-policy"
  description = "GitHub Actions: register task definition and update main ECS service"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "EcsRegisterAndDescribeTaskDefinition"
        Effect = "Allow"
        Action = [
          "ecs:RegisterTaskDefinition",
          "ecs:DescribeTaskDefinition"
        ]
        Resource = "*"
      },
      {
        Sid    = "EcsUpdateMainService"
        Effect = "Allow"
        Action = [
          "ecs:UpdateService",
          "ecs:DescribeServices"
        ]
        Resource = aws_ecs_service.main.id
        Condition = {
          ArnEquals = {
            "ecs:cluster" = aws_ecs_cluster.main.arn
          }
        }
      },
      {
        Sid    = "PassEcsTaskRoles"
        Effect = "Allow"
        Action = ["iam:PassRole"]
        Resource = [
          module.ecs_task_execution_role.arn,
          module.ecs_task_role.arn
        ]
        Condition = {
          StringEquals = {
            "iam:PassedToService" = "ecs-tasks.amazonaws.com"
          }
        }
      }
    ]
  })
}

# ---------------------------------------------------------------------
# ecs-update-laravel-que.yml: queue worker サービスのタスク定義差し替え
# ---------------------------------------------------------------------
resource "aws_iam_policy" "gha_ecs_update_queue_service_policy" {
  name        = "${var.project_name}-gha-ecs-update-queue-service-policy"
  description = "GitHub Actions: register task definition and update queue worker ECS service"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "EcsRegisterAndDescribeTaskDefinition"
        Effect = "Allow"
        Action = [
          "ecs:RegisterTaskDefinition",
          "ecs:DescribeTaskDefinition"
        ]
        Resource = "*"
      },
      {
        Sid    = "EcsUpdateQueueService"
        Effect = "Allow"
        Action = [
          "ecs:UpdateService",
          "ecs:DescribeServices"
        ]
        Resource = aws_ecs_service.queue_worker.id
        Condition = {
          ArnEquals = {
            "ecs:cluster" = aws_ecs_cluster.main.arn
          }
        }
      },
      {
        Sid    = "PassEcsTaskRoles"
        Effect = "Allow"
        Action = ["iam:PassRole"]
        Resource = [
          module.ecs_task_execution_role.arn,
          module.ecs_task_role.arn
        ]
        Condition = {
          StringEquals = {
            "iam:PassedToService" = "ecs-tasks.amazonaws.com"
          }
        }
      }
    ]
  })
}

# ---------------------------------------------------------------------
# db-task.yml: SSM 取得 + runner タスクを RunTask (containerOverrides で
# migrate / seed / shell を切替) + 完了待ち
# ---------------------------------------------------------------------
resource "aws_iam_policy" "gha_db_runner_policy" {
  name        = "${var.project_name}-gha-db-runner-policy"
  description = "GitHub Actions: run ECS runner task with command overrides (migrate/seed/shell)"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "ReadNetworkConfigSsmParameters"
        Effect = "Allow"
        Action = [
          "ssm:GetParameter",
          "ssm:GetParameters"
        ]
        Resource = [
          aws_ssm_parameter.backend_subnet_id_a.arn,
          aws_ssm_parameter.backend_security_group_id.arn
        ]
      },
      {
        Sid    = "RunRunnerTask"
        Effect = "Allow"
        Action = ["ecs:RunTask"]
        Resource = replace(
          aws_ecs_task_definition.runner.arn,
          "/:[0-9]+$/",
          ":*"
        )
        Condition = {
          ArnEquals = {
            "ecs:cluster" = aws_ecs_cluster.main.arn
          }
        }
      },
      {
        Sid      = "DescribeRunningTasksInCluster"
        Effect   = "Allow"
        Action   = ["ecs:DescribeTasks"]
        Resource = "*"
        Condition = {
          ArnEquals = {
            "ecs:cluster" = aws_ecs_cluster.main.arn
          }
        }
      },
      {
        Sid    = "PassEcsTaskRoles"
        Effect = "Allow"
        Action = ["iam:PassRole"]
        Resource = [
          module.ecs_task_execution_role.arn,
          module.ecs_task_role.arn
        ]
        Condition = {
          StringEquals = {
            "iam:PassedToService" = "ecs-tasks.amazonaws.com"
          }
        }
      }
    ]
  })
}

# ---------------------------------------------------------------------
# s3-deploy-frontend.yml: S3 sync + CloudFront invalidation
# ---------------------------------------------------------------------
resource "aws_iam_policy" "gha_s3_deploy_frontend_policy" {
  name        = "${var.project_name}-gha-s3-deploy-frontend-policy"
  description = "GitHub Actions: deploy frontend assets to S3 and invalidate CloudFront"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "ReadFrontendDeploySsmParameters"
        Effect = "Allow"
        Action = [
          "ssm:GetParameter",
          "ssm:GetParameters"
        ]
        Resource = [
          aws_ssm_parameter.frontend_bucket_name.arn,
          aws_ssm_parameter.cloudfront_distribution_id.arn
        ]
      },
      {
        Sid      = "ListFrontendBucket"
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = aws_s3_bucket.frontend_bucket.arn
      },
      {
        Sid    = "WriteFrontendBucketObjects"
        Effect = "Allow"
        Action = [
          "s3:PutObject",
          "s3:DeleteObject",
          "s3:GetObject"
        ]
        Resource = "${aws_s3_bucket.frontend_bucket.arn}/*"
      },
      {
        Sid    = "InvalidateFrontendCdn"
        Effect = "Allow"
        Action = [
          "cloudfront:CreateInvalidation",
          "cloudfront:GetInvalidation"
        ]
        Resource = aws_cloudfront_distribution.frontend_cdn.arn
      }
    ]
  })
}

# ---------------------------------------------------------------------
# ecspresso-update-task.yml: ecspresso でタスク定義登録 + サービスデプロイ
# ---------------------------------------------------------------------
resource "aws_iam_policy" "gha_ecspresso_policy" {
  name        = "${var.project_name}-gha-ecspresso-policy"
  description = "GitHub Actions: ecspresso register / deploy for ECS services and tasks"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "EcsTaskDefinitionRegisterAndDescribe"
        Effect = "Allow"
        Action = [
          "ecs:RegisterTaskDefinition",
          "ecs:DescribeTaskDefinition",
          "ecs:DeregisterTaskDefinition",
          "ecs:ListTaskDefinitions"
        ]
        Resource = "*"
      },
      {
        Sid    = "EcsServiceUpdateAndDescribe"
        Effect = "Allow"
        Action = [
          "ecs:UpdateService",
          "ecs:DescribeServices",
          "ecs:ListServices",
          # ECS native blue/green デプロイ完了待ちで ecspresso が呼ぶ。
          # ListServiceDeployments のみ resource-level 制約に対応。
          "ecs:ListServiceDeployments"
        ]
        Resource = [
          aws_ecs_service.main.id,
          aws_ecs_service.queue_worker.id
        ]
        Condition = {
          ArnEquals = {
            "ecs:cluster" = aws_ecs_cluster.main.arn
          }
        }
      },
      {
        # DescribeServiceDeployments / DescribeServiceRevisions は AWS 仕様で
        # resource-level 非対応のため Resource = "*" 必須。
        Sid    = "EcsDescribeServiceDeploymentDetails"
        Effect = "Allow"
        Action = [
          "ecs:DescribeServiceDeployments",
          "ecs:DescribeServiceRevisions"
        ]
        Resource = "*"
      },
      {
        Sid    = "EcsClusterDescribe"
        Effect = "Allow"
        Action = [
          "ecs:DescribeClusters",
          "ecs:ListClusters"
        ]
        Resource = aws_ecs_cluster.main.arn
      },
      {
        Sid    = "EcsTaskDescribe"
        Effect = "Allow"
        Action = [
          "ecs:DescribeTasks",
          "ecs:ListTasks"
        ]
        Resource = "*"
        Condition = {
          ArnEquals = {
            "ecs:cluster" = aws_ecs_cluster.main.arn
          }
        }
      },
      {
        Sid    = "PassEcsTaskRoles"
        Effect = "Allow"
        Action = ["iam:PassRole"]
        Resource = [
          module.ecs_task_execution_role.arn,
          module.ecs_task_role.arn
        ]
        Condition = {
          StringEquals = {
            "iam:PassedToService" = "ecs-tasks.amazonaws.com"
          }
        }
      },
      {
        # ECS native blue/green デプロイでは ECS サービス自身が ALB のリスナールールを
        # 切り替えるため、loadBalancers[].advancedConfiguration.roleArn に渡すロールを
        # ECS (ecs.amazonaws.com) に PassRole する権限が必要。
        # 上の PassEcsTaskRoles とは渡し先サービスが異なる (ecs-tasks vs ecs)。
        Sid    = "PassEcsInfraLbRole"
        Effect = "Allow"
        Action = ["iam:PassRole"]
        Resource = [
          module.ecs_infra_lb.arn
        ]
        Condition = {
          StringEquals = {
            "iam:PassedToService" = "ecs.amazonaws.com"
          }
        }
      },
      {
        # ecspresso verify がタスクロールの存在確認のために GetRole を呼ぶ
        Sid    = "GetEcsTaskRolesForVerify"
        Effect = "Allow"
        Action = ["iam:GetRole"]
        Resource = [
          module.ecs_task_execution_role.arn,
          module.ecs_task_role.arn
        ]
      },
      {
        # ecspresso verify が ECR イメージの存在確認用に呼ぶ。
        # GetAuthorizationToken は AWS 仕様で Resource = "*" 必須。
        Sid      = "EcrAuthorizationTokenForVerify"
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = "*"
      },
      {
        Sid    = "EcrReadImageForVerify"
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability",
          "ecr:BatchGetImage",
          "ecr:GetDownloadUrlForLayer",
          "ecr:DescribeImages"
        ]
        Resource = [
          data.aws_ecr_repository.laravel.arn,
          data.aws_ecr_repository.nginx.arn
        ]
      },
      {
        # ecspresso-update-task.yml の事前ステップが service-def.jsonnet 用に
        # subnet / security group ID を SSM から取得して GITHUB_ENV にエクスポートする。
        Sid    = "ReadNetworkConfigSsmParameters"
        Effect = "Allow"
        Action = [
          "ssm:GetParameter",
          "ssm:GetParameters"
        ]
        Resource = [
          aws_ssm_parameter.backend_subnet_id_a.arn,
          aws_ssm_parameter.backend_subnet_id_c.arn,
          aws_ssm_parameter.backend_security_group_id.arn,
        ]
      },
      {
        # ecspresso verify が secrets の valueFrom に書かれた SSM Parameter を読みに行く。
        # web / queue-worker / batch / runner の各タスク定義が参照する全 secret を含める。
        Sid    = "SsmReadSecretsForVerify"
        Effect = "Allow"
        Action = [
          "ssm:GetParameters",
          "ssm:GetParameter"
        ]
        Resource = [
          data.aws_ssm_parameter.db_password.arn,
          data.aws_ssm_parameter.app_key.arn,
          data.aws_ssm_parameter.google_client_id.arn,
          data.aws_ssm_parameter.google_client_secret.arn,
          aws_ssm_parameter.otel_collector_config.arn
        ]
      },
      {
        # ecspresso verify が awslogs ロググループの存在確認用に呼ぶ。
        # logs:DescribeLogGroups は List 系 API で Resource = "*" 必須。
        Sid      = "LogsDescribeForVerify"
        Effect   = "Allow"
        Action   = ["logs:DescribeLogGroups"]
        Resource = "*"
      },
      {
        # ecspresso verify は LogConfiguration の検証時に
        # `ecspresso-verify-<timestamp>` というログストリームを作成し、
        # 実際にログイベントを書き込んで awslogs ドライバの設定が機能することを確認する。
        Sid    = "LogsWriteForVerify"
        Effect = "Allow"
        Action = [
          "logs:CreateLogStream",
          "logs:PutLogEvents"
        ]
        Resource = "${aws_cloudwatch_log_group.ecs_log.arn}:*"
      },
      {
        Sid      = "ReadTfstateObject"
        Effect   = "Allow"
        Action   = ["s3:GetObject"]
        Resource = "arn:aws:s3:::${var.tfstate_bucket}/${var.tfstate_key}"
      },
      {
        Sid      = "ListTfstateBucket"
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = "arn:aws:s3:::${var.tfstate_bucket}"
        Condition = {
          StringLike = {
            "s3:prefix" = ["${dirname(var.tfstate_key)}/*"]
          }
        }
      },
      {
        Sid    = "DescribeApplicationAutoScaling"
        Effect = "Allow"
        Action = [
          "application-autoscaling:DescribeScalableTargets",
          "application-autoscaling:DescribeScalingPolicies"
        ]
        Resource = "*"
      },
      {
        # ecspresso verify が service definition の loadBalancers[].targetGroupArn と
        # advancedConfiguration の listenerRule を実在確認するために呼ぶ。
        # ELB v2 の Describe 系 API は resource-level 権限非対応のため Resource = "*" 必須。
        Sid    = "ElbDescribeForVerify"
        Effect = "Allow"
        Action = [
          "elasticloadbalancing:DescribeTargetGroups",
          "elasticloadbalancing:DescribeListeners",
          "elasticloadbalancing:DescribeRules"
        ]
        Resource = "*"
      }
    ]
  })
}
