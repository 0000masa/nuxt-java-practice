resource "aws_iam_policy" "ecs_s3_policy" {
  name        = "${var.project_name}-s3-policy"
  description = "Allow ecs to upload/delete images in S3"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "S3ObjectRW"
        Effect = "Allow"
        Action = [
          "s3:PutObject",
          "s3:DeleteObject",
          "s3:GetObject"
        ]
        Resource = "${aws_s3_bucket.image_bucket.arn}/*"
      },
      {
        Sid    = "S3ListBucket"
        Effect = "Allow"
        Action = [
          "s3:ListBucket"
        ]
        Resource = aws_s3_bucket.image_bucket.arn
      }
    ]
  })
}

# ECSタスク用のIAMポリシー（このドメインからのみ送信を許可）
resource "aws_iam_policy" "ses_send_policy" {
  name        = "${var.project_name}-ses-send-policy"
  description = "Allow ECS task to send email via SES domain"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action   = ["ses:SendEmail", "ses:SendRawEmail"]
        Effect   = "Allow"
        Resource = aws_ses_domain_identity.main.arn
      }
    ]
  })
}

# FireLens経由でCloudWatch Logsにログを送信するためのポリシー
resource "aws_iam_policy" "firelens_cloudwatch_logs" {
  name = "${var.project_name}-firelens-cloudwatch-logs"

  policy = jsonencode({
    Version = "2012-10-17",
    Statement = [
      {
        Sid    = "AllowWriteCloudWatchLogs",
        Effect = "Allow",
        Action = [
          "logs:CreateLogStream",
          "logs:PutLogEvents",
          "logs:DescribeLogStreams"
        ],
        Resource = [
          "${aws_cloudwatch_log_group.ecs_log.arn}:log-stream:*"
        ]
      }
    ]
  })
}

# ログアーカイブ: CloudWatch Logs が Firehose にレコードを流すための権限（当該ストリームに限定）
resource "aws_iam_policy" "cwl_put_to_firehose" {
  name        = "${var.project_name}-cwl-put-to-firehose"
  description = "Allow CloudWatch Logs subscription filter to put records into the log-archive Firehose stream"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "PutToFirehose"
        Effect = "Allow"
        Action = [
          "firehose:PutRecord",
          "firehose:PutRecordBatch"
        ]
        Resource = aws_kinesis_firehose_delivery_stream.logs_archive.arn
      }
    ]
  })
}

# ログアーカイブ: Firehose がアーカイブバケットへ書き込むための権限（当該バケットに限定）
resource "aws_iam_policy" "firehose_write_logs_archive" {
  name        = "${var.project_name}-firehose-write-logs-archive"
  description = "Allow the log-archive Firehose stream to write objects to the archive bucket"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "S3Write"
        Effect = "Allow"
        Action = [
          "s3:AbortMultipartUpload",
          "s3:GetBucketLocation",
          "s3:GetObject",
          "s3:ListBucket",
          "s3:ListBucketMultipartUploads",
          "s3:PutObject"
        ]
        Resource = [
          aws_s3_bucket.logs_archive.arn,
          "${aws_s3_bucket.logs_archive.arn}/*"
        ]
      },
      {
        # 配信エラーの理由を CloudWatch Logs に書くための権限（当該ロググループに限定）。
        Sid    = "WriteDeliveryErrorLogs"
        Effect = "Allow"
        Action = [
          "logs:PutLogEvents"
        ]
        Resource = "${aws_cloudwatch_log_group.firehose_logs_archive.arn}:*"
      }
    ]
  })
}

# ECS実行ロールにSSMの読み取り権限を追加するポリシー
resource "aws_iam_policy" "ecs_execution_ssm_policy" {
  name = "${var.project_name}-execution-ssm-policy"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
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
          # preview 用 DB パスワードの読み取りは stg ルート側で後付けする（ADR 0007）。
          # モジュール本体は preview を一切参照しない。
        ]
      }
      # カスタムKMSキーでSecureStringを暗号化している場合は以下のkms:Decryptが必要
      # AWS管理キー（aws/ssm）で暗号化している場合は不要（SSM API経由で暗黙的に復号される）
      # {
      #   Effect = "Allow"
      #   Action = ["kms:Decrypt"]
      #   Resource = "<カスタムKMSキーのARN>"
      # }
    ]
  })
}

# 稼働中のECSタスクのコンテナに「リモートで入る」ための通信を許可するポリシー
resource "aws_iam_policy" "ecs_exec_policy" {
  name = "${var.project_name}-ecs-exec-policy"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "ssmmessages:CreateControlChannel",
        "ssmmessages:CreateDataChannel",
        "ssmmessages:OpenControlChannel",
        "ssmmessages:OpenDataChannel"
      ]
      Resource = "*"
    }]
  })
}

# --- EventBridge が ECS タスクを起動するためのポリシー ---
resource "aws_iam_policy" "eventbridge_ecs_run_task" {
  name        = "${var.project_name}-eventbridge-ecs-run-task"
  description = "Allow EventBridge to run ECS batch tasks"

  #正規表現 "/:[0-9]+$/" は「末尾の :数字」を指す。これを ":*" に置換することで、タスク定義のバージョンに関係なく全てのバージョンを対象にできる。
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "AllowRunTask"
        Effect = "Allow"
        Action = "ecs:RunTask"
        Resource = replace(
          aws_ecs_task_definition.batch_daily_report.arn,
          "/:[0-9]+$/",
          ":*"
        )
      },
      {
        Sid    = "AllowPassRole"
        Effect = "Allow"
        Action = "iam:PassRole"
        Resource = [
          module.ecs_task_execution_role.arn,
          module.ecs_task_role.arn
        ]
      }
    ]
  })
}

# --- SQS アクセス用 IAM ポリシー ---
resource "aws_iam_policy" "sqs_queue_policy" {
  name        = "${var.project_name}-sqs-queue-policy"
  description = "Allow ECS tasks to send/receive messages from SQS queue"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "AllowSqsAccess"
        Effect = "Allow"
        Action = [
          "sqs:SendMessage",
          "sqs:ReceiveMessage",
          "sqs:DeleteMessage",
          "sqs:GetQueueAttributes",
          "sqs:GetQueueUrl",
          "sqs:ChangeMessageVisibility"
        ]
        Resource = aws_sqs_queue.qrcode_generation.arn
      }
    ]
  })
}

# 通知用のLambda関数がLaravelのCloudWatch Logsを読み取れるようにするポリシー
resource "aws_iam_policy" "lambda_read_laravel_logs" {
  name = "${var.project_name}-lambda-read-laravel-logs"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "ReadLaravelLogs"
        Effect = "Allow"
        Action = [
          "logs:FilterLogEvents"
        ]
        Resource = [
          "${aws_cloudwatch_log_group.ecs_log.arn}:*"
        ]
      }
    ]
  })
}
