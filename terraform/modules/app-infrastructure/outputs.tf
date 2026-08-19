# =====================================================================
# pr-env（PR ごとの検証環境）が terraform_remote_state で参照する出力
# =====================================================================
# preview モジュールは stg の state を読み、既存の ALB / VPC / ECS クラスタ /
# ECR / RDS / Route53 を再利用する。ここで必要な値を公開する。

output "vpc_id" {
  value = module.vpc.vpc_id
}

output "private_subnet_ids" {
  value = [local.private_subnet_a_id, local.private_subnet_c_id]
}

output "ecs_security_group_id" {
  description = "ECS タスク用セキュリティグループ（preview タスクでも再利用）"
  value       = aws_security_group.ecs_sg.id
}

output "ecs_cluster_arn" {
  value = aws_ecs_cluster.main.arn
}

output "ecs_cluster_name" {
  value = aws_ecs_cluster.main.name
}

output "alb_https_listener_arn" {
  description = "preview のリスナールールを追加する先（本番と同じ ALB の HTTPS リスナー）"
  value       = aws_lb_listener.https.arn
}

output "alb_dns_name" {
  value = aws_lb.main.dns_name
}

output "alb_zone_id" {
  value = aws_lb.main.zone_id
}

output "cloudfront_secret" {
  description = "ALB が CloudFront 経由のみ許可するための共有シークレット（preview の CloudFront も注入）"
  value       = random_password.cf_secret.result
  sensitive   = true
}

output "ecr_nginx_repository_url" {
  value = data.aws_ecr_repository.nginx.repository_url
}

output "ecr_laravel_repository_url" {
  value = data.aws_ecr_repository.laravel.repository_url
}

output "rds_address" {
  value = aws_db_instance.main.address
}

output "rds_port" {
  value = aws_db_instance.main.port
}

output "route53_zone_id" {
  value = data.aws_route53_zone.main.zone_id
}

output "domain_name" {
  value = var.domain_name
}

output "app_key_ssm_arn" {
  description = "Laravel APP_KEY の SSM パラメータ ARN（preview でも共有）"
  value       = data.aws_ssm_parameter.app_key.arn
}

output "ecs_task_execution_role_arn" {
  description = "ECR pull / SSM 読み取り用の実行ロール（preview タスクでも共有）"
  value       = module.ecs_task_execution_role.arn
}

output "aws_region" {
  value = "ap-northeast-1"
}

output "aws_account_id" {
  value = data.aws_caller_identity.current.account_id
}

# preview 共有リソース（ワイルドカード ACM / WAF / Boundary / デプロイロール /
# api.preview レコード / preview_db_password）は stg ルート（terraform/stg/preview_shared.tf）
# が持つ。モジュールは preview を一切知らない。詳細は ADR 0007。
# pr-env はそれらを stg ルートの output から読む。

# 画像は既存 stg のバケット / CDN を preview でも再利用する
output "image_bucket" {
  value = aws_s3_bucket.image_bucket.bucket
}

output "image_cdn_domain_name" {
  value = aws_cloudfront_distribution.image_cdn.domain_name
}

output "spa_fallback_function_arn" {
  description = "SPA フォールバック（＋ Basic 認証）CloudFront Function。preview の各 CloudFront でも再利用する。stg では enable_basic_auth=true で認証付きになり、preview もこの関数を共有するため認証付きになる。"
  value       = aws_cloudfront_function.spa_fallback.arn
}

output "cloudfront_waf_arn" {
  description = "共有 CloudFront WAF（AWS マネージドルール）の ARN。stg frontend に加え preview の CloudFront もこれを使う（Basic 認証は WAF ではなく上記関数で行うため、全環境で WAF は1枚に集約）。"
  value       = aws_wafv2_web_acl.cloudfront_waf.arn
}

# SES（メール送信）。preview は独自ドメインを SES 検証せず、stg の検証済み
# アイデンティティをそのまま使う（Route53 への SES レコード追加なし）。
# pr-env はこの2つを stg output 経由で受け取り、From アドレスと IAM の
# Resource スコープに使う。
output "ses_domain_identity_arn" {
  description = "検証済み SES ドメインアイデンティティの ARN。preview タスクロールの ses:SendEmail を identity 単位にスコープするのに使う。"
  value       = aws_ses_domain_identity.main.arn
}

output "ses_domain_identity_name" {
  description = "検証済み SES ドメイン名（例: stg.www.<domain>）。preview の MAIL_FROM_ADDRESS をこのドメインに向ける。"
  value       = aws_ses_domain_identity.main.domain
}

output "parameter_store_path" {
  value = var.parameter_store_path
}

output "ecs_task_execution_role_name" {
  description = "ECS 実行ロール名。stg ルートが preview 用 SSM 読み取りを後付けするために使う（ADR 0007）。"
  value       = module.ecs_task_execution_role.name
}
