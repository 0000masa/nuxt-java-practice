# # =============================================================================
# # moved ブロック: 既存リソースを module.app 配下に移行
# # =============================================================================
# #
# # ■ このファイルの目的
# # モジュール化により、Terraform state 内のリソースアドレスが変わる。
# #   例: aws_db_instance.main → module.app.aws_db_instance.main
# #
# # moved ブロックがないと、Terraform は
# #   「旧アドレスのリソースを destroy して、新アドレスで create する」
# # と判断してしまう。moved ブロックがあれば、state 内のアドレスだけ
# # 書き換わり、AWS 上の実リソースには何も起きない。
# #
# # ■ state 移行のタイミング
# # state の移動は terraform apply ではなく terraform plan の時点で実行される。
# #   1. terraform init  — モジュールソースを読み込む
# #   2. terraform plan  — ここで state 内のアドレスが書き換わる
# #                        plan 出力に「has moved to」と表示される
# #   3. plan の結果は 0 adds, 0 changes, 0 destroys になる
# # つまり terraform apply は不要で、terraform plan だけで state 移行が完了する。
# #
# # ■ まだ terraform apply をしていない（AWS上にリソースが存在しない）場合
# # state にリソースが存在しないため、moved ブロックは不要。
# # このファイルごと削除して問題ない。
# # =============================================================================

# # --- VPC ---
# moved {
#   from = module.vpc
#   to   = module.app.module.vpc
# }

# moved {
#   from = aws_vpc_endpoint.s3_gateway
#   to   = module.app.aws_vpc_endpoint.s3_gateway
# }

# # --- Security Groups ---
# moved {
#   from = aws_security_group.alb_sg
#   to   = module.app.aws_security_group.alb_sg
# }

# moved {
#   from = aws_security_group.ecs_sg
#   to   = module.app.aws_security_group.ecs_sg
# }

# moved {
#   from = aws_security_group.rds_sg
#   to   = module.app.aws_security_group.rds_sg
# }

# # --- RDS ---
# moved {
#   from = aws_db_instance.main
#   to   = module.app.aws_db_instance.main
# }

# moved {
#   from = aws_db_subnet_group.main
#   to   = module.app.aws_db_subnet_group.main
# }

# # --- ALB ---
# moved {
#   from = aws_lb.main
#   to   = module.app.aws_lb.main
# }

# moved {
#   from = aws_lb_listener.http
#   to   = module.app.aws_lb_listener.http
# }

# moved {
#   from = aws_lb_listener.https
#   to   = module.app.aws_lb_listener.https
# }

# moved {
#   from = aws_lb_target_group.slot_a
#   to   = module.app.aws_lb_target_group.slot_a
# }

# moved {
#   from = aws_lb_target_group.slot_b
#   to   = module.app.aws_lb_target_group.slot_b
# }

# moved {
#   from = aws_lb_listener_rule.ecs_test
#   to   = module.app.aws_lb_listener_rule.ecs_test
# }

# moved {
#   from = aws_lb_listener_rule.ecs_production
#   to   = module.app.aws_lb_listener_rule.ecs_production
# }

# # --- ACM ---
# moved {
#   from = aws_acm_certificate.cert_frontend
#   to   = module.app.aws_acm_certificate.cert_frontend
# }

# moved {
#   from = aws_acm_certificate.cert_backend
#   to   = module.app.aws_acm_certificate.cert_backend
# }

# moved {
#   from = aws_acm_certificate_validation.cert_frontend
#   to   = module.app.aws_acm_certificate_validation.cert_frontend
# }

# moved {
#   from = aws_acm_certificate_validation.cert_backend
#   to   = module.app.aws_acm_certificate_validation.cert_backend
# }

# # --- S3 ---
# moved {
#   from = aws_s3_bucket.frontend_bucket
#   to   = module.app.aws_s3_bucket.frontend_bucket
# }

# moved {
#   from = aws_s3_bucket.image_bucket
#   to   = module.app.aws_s3_bucket.image_bucket
# }

# moved {
#   from = aws_s3_bucket_policy.bucket_policy
#   to   = module.app.aws_s3_bucket_policy.bucket_policy
# }

# moved {
#   from = aws_s3_bucket_policy.frontend
#   to   = module.app.aws_s3_bucket_policy.frontend
# }

# # --- CloudFront ---
# moved {
#   from = aws_cloudfront_origin_access_control.s3_oac_images
#   to   = module.app.aws_cloudfront_origin_access_control.s3_oac_images
# }

# moved {
#   from = aws_cloudfront_origin_access_control.s3_oac_frontend
#   to   = module.app.aws_cloudfront_origin_access_control.s3_oac_frontend
# }

# moved {
#   from = aws_cloudfront_function.spa_fallback
#   to   = module.app.aws_cloudfront_function.spa_fallback
# }

# moved {
#   from = aws_cloudfront_distribution.image_cdn
#   to   = module.app.aws_cloudfront_distribution.image_cdn
# }

# moved {
#   from = aws_cloudfront_distribution.frontend_cdn
#   to   = module.app.aws_cloudfront_distribution.frontend_cdn
# }

# # --- ECS ---
# moved {
#   from = aws_ecs_cluster.main
#   to   = module.app.aws_ecs_cluster.main
# }

# moved {
#   from = aws_ecs_service.main
#   to   = module.app.aws_ecs_service.main
# }

# moved {
#   from = aws_ecs_task_definition.main
#   to   = module.app.aws_ecs_task_definition.main
# }

# moved {
#   from = aws_appautoscaling_target.ecs
#   to   = module.app.aws_appautoscaling_target.ecs
# }

# moved {
#   from = aws_appautoscaling_policy.cpu
#   to   = module.app.aws_appautoscaling_policy.cpu
# }

# moved {
#   from = aws_appautoscaling_policy.memory
#   to   = module.app.aws_appautoscaling_policy.memory
# }

# moved {
#   from = aws_ecs_service.queue_worker
#   to   = module.app.aws_ecs_service.queue_worker
# }

# moved {
#   from = aws_ecs_task_definition.queue_worker
#   to   = module.app.aws_ecs_task_definition.queue_worker
# }

# moved {
#   from = aws_ecs_task_definition.batch_daily_report
#   to   = module.app.aws_ecs_task_definition.batch_daily_report
# }

# # --- IAM Roles (modules) ---
# moved {
#   from = module.ecs_task_execution_role
#   to   = module.app.module.ecs_task_execution_role
# }

# moved {
#   from = module.ecs_task_role
#   to   = module.app.module.ecs_task_role
# }

# moved {
#   from = module.ecs_infra_lb
#   to   = module.app.module.ecs_infra_lb
# }

# moved {
#   from = module.eventbridge_role
#   to   = module.app.module.eventbridge_role
# }

# moved {
#   from = module.notification_lambda_role
#   to   = module.app.module.notification_lambda_role
# }

# # --- IAM Policies ---
# moved {
#   from = aws_iam_policy.ecs_s3_policy
#   to   = module.app.aws_iam_policy.ecs_s3_policy
# }

# moved {
#   from = aws_iam_policy.ses_send_policy
#   to   = module.app.aws_iam_policy.ses_send_policy
# }

# moved {
#   from = aws_iam_policy.firelens_cloudwatch_logs
#   to   = module.app.aws_iam_policy.firelens_cloudwatch_logs
# }

# moved {
#   from = aws_iam_policy.ecs_execution_ssm_policy
#   to   = module.app.aws_iam_policy.ecs_execution_ssm_policy
# }

# moved {
#   from = aws_iam_policy.ecs_exec_policy
#   to   = module.app.aws_iam_policy.ecs_exec_policy
# }

# moved {
#   from = aws_iam_policy.eventbridge_ecs_run_task
#   to   = module.app.aws_iam_policy.eventbridge_ecs_run_task
# }

# moved {
#   from = aws_iam_policy.sqs_queue_policy
#   to   = module.app.aws_iam_policy.sqs_queue_policy
# }

# moved {
#   from = aws_iam_policy.lambda_read_laravel_logs
#   to   = module.app.aws_iam_policy.lambda_read_laravel_logs
# }

# # --- Lambda ---
# moved {
#   from = aws_lambda_function.notification_function
#   to   = module.app.aws_lambda_function.notification_function
# }

# moved {
#   from = local_file.lambda_py
#   to   = module.app.local_file.lambda_py
# }

# moved {
#   from = aws_lambda_permission.allow_cloudwatch_logs_invoke
#   to   = module.app.aws_lambda_permission.allow_cloudwatch_logs_invoke
# }

# # --- CloudWatch / Monitoring ---
# moved {
#   from = aws_cloudwatch_log_group.ecs_log
#   to   = module.app.aws_cloudwatch_log_group.ecs_log
# }

# moved {
#   from = aws_cloudwatch_log_subscription_filter.laravel_error_critical_to_lambda
#   to   = module.app.aws_cloudwatch_log_subscription_filter.laravel_error_critical_to_lambda
# }

# moved {
#   from = aws_cloudwatch_metric_alarm.ecs_running_less_than_desired
#   to   = module.app.aws_cloudwatch_metric_alarm.ecs_running_less_than_desired
# }

# # --- Route53 / DNS ---
# moved {
#   from = aws_route53_record.frontend_record
#   to   = module.app.aws_route53_record.frontend_record
# }

# moved {
#   from = aws_route53_record.frontend_record_aaaa
#   to   = module.app.aws_route53_record.frontend_record_aaaa
# }

# moved {
#   from = aws_route53_record.backend_record
#   to   = module.app.aws_route53_record.backend_record
# }

# # for_each リソース: キーはドメイン名
# # ※ 実際のキーは `terraform state list` で確認してください
# #    推定キー: "www.favoritemyanime.com" / "api.favoritemyanime.com"
# moved {
#   from = aws_route53_record.cert_validation_frontend["www.favoritemyanime.com"]
#   to   = module.app.aws_route53_record.cert_validation_frontend["www.favoritemyanime.com"]
# }

# moved {
#   from = aws_route53_record.cert_validation_backend["api.favoritemyanime.com"]
#   to   = module.app.aws_route53_record.cert_validation_backend["api.favoritemyanime.com"]
# }

# # count リソース: ses_dkim_records[0], [1], [2]
# moved {
#   from = aws_route53_record.ses_dkim_records[0]
#   to   = module.app.aws_route53_record.ses_dkim_records[0]
# }

# moved {
#   from = aws_route53_record.ses_dkim_records[1]
#   to   = module.app.aws_route53_record.ses_dkim_records[1]
# }

# moved {
#   from = aws_route53_record.ses_dkim_records[2]
#   to   = module.app.aws_route53_record.ses_dkim_records[2]
# }

# moved {
#   from = aws_route53_record.ses_dmarc_record
#   to   = module.app.aws_route53_record.ses_dmarc_record
# }

# moved {
#   from = aws_route53_record.ses_mail_from_mx
#   to   = module.app.aws_route53_record.ses_mail_from_mx
# }

# moved {
#   from = aws_route53_record.ses_mail_from_spf
#   to   = module.app.aws_route53_record.ses_mail_from_spf
# }

# # --- SES ---
# moved {
#   from = aws_ses_domain_identity.main
#   to   = module.app.aws_ses_domain_identity.main
# }

# moved {
#   from = aws_ses_domain_dkim.main
#   to   = module.app.aws_ses_domain_dkim.main
# }

# moved {
#   from = aws_ses_domain_mail_from.main
#   to   = module.app.aws_ses_domain_mail_from.main
# }

# # --- SNS ---
# moved {
#   from = aws_sns_topic.ecs_task_shortage
#   to   = module.app.aws_sns_topic.ecs_task_shortage
# }

# moved {
#   from = aws_sns_topic_subscription.ecs_task_shortage_email
#   to   = module.app.aws_sns_topic_subscription.ecs_task_shortage_email
# }

# # --- SQS ---
# moved {
#   from = aws_sqs_queue.qrcode_generation
#   to   = module.app.aws_sqs_queue.qrcode_generation
# }

# # --- EventBridge ---
# moved {
#   from = aws_cloudwatch_event_rule.daily_report
#   to   = module.app.aws_cloudwatch_event_rule.daily_report
# }

# moved {
#   from = aws_cloudwatch_event_target.daily_report
#   to   = module.app.aws_cloudwatch_event_target.daily_report
# }

# # --- SSM Parameters ---
# moved {
#   from = aws_ssm_parameter.backend_subnet_id_a
#   to   = module.app.aws_ssm_parameter.backend_subnet_id_a
# }

# moved {
#   from = aws_ssm_parameter.backend_security_group_id
#   to   = module.app.aws_ssm_parameter.backend_security_group_id
# }

# moved {
#   from = aws_ssm_parameter.frontend_bucket_name
#   to   = module.app.aws_ssm_parameter.frontend_bucket_name
# }

# moved {
#   from = aws_ssm_parameter.cloudfront_distribution_id
#   to   = module.app.aws_ssm_parameter.cloudfront_distribution_id
# }

# moved {
#   from = aws_ssm_parameter.backend_url
#   to   = module.app.aws_ssm_parameter.backend_url
# }

# moved {
#   from = aws_ssm_parameter.otel_collector_config
#   to   = module.app.aws_ssm_parameter.otel_collector_config
# }

# # --- WAF ---
# moved {
#   from = random_password.cf_secret
#   to   = module.app.random_password.cf_secret
# }

# moved {
#   from = aws_wafv2_web_acl.cloudfront_waf
#   to   = module.app.aws_wafv2_web_acl.cloudfront_waf
# }
