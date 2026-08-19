# pr-env（PR ごとの検証環境）が terraform_remote_state で参照する出力。
# module.app の出力をそのまま root に公開する。

output "vpc_id" { value = module.app.vpc_id }
output "private_subnet_ids" { value = module.app.private_subnet_ids }
output "ecs_security_group_id" { value = module.app.ecs_security_group_id }
output "ecs_cluster_arn" { value = module.app.ecs_cluster_arn }
output "ecs_cluster_name" { value = module.app.ecs_cluster_name }
output "alb_https_listener_arn" { value = module.app.alb_https_listener_arn }
output "alb_dns_name" { value = module.app.alb_dns_name }
output "alb_zone_id" { value = module.app.alb_zone_id }

output "cloudfront_secret" {
  value     = module.app.cloudfront_secret
  sensitive = true
}

output "ecr_nginx_repository_url" { value = module.app.ecr_nginx_repository_url }
output "ecr_laravel_repository_url" { value = module.app.ecr_laravel_repository_url }
output "rds_address" { value = module.app.rds_address }
output "rds_port" { value = module.app.rds_port }
output "route53_zone_id" { value = module.app.route53_zone_id }
output "domain_name" { value = module.app.domain_name }
output "app_key_ssm_arn" { value = module.app.app_key_ssm_arn }
output "ecs_task_execution_role_arn" { value = module.app.ecs_task_execution_role_arn }
output "aws_region" { value = module.app.aws_region }
output "aws_account_id" { value = module.app.aws_account_id }

# --- preview 共有リソース（stg ルートの preview_shared.tf が実体。ADR 0007）---
output "preview_cf_certificate_arn" { value = aws_acm_certificate_validation.preview_cf.certificate_arn }
output "preview_permissions_boundary_arn" { value = aws_iam_policy.preview_boundary.arn }
output "preview_deploy_role_arn" { value = module.gha_preview_deploy_role.arn }
output "preview_api_origin_host" { value = local.preview_api_origin }
output "preview_zone_apex" { value = local.preview_zone_apex }
output "preview_db_password_ssm_arn" { value = data.aws_ssm_parameter.preview_db_password.arn }

# --- preview が再利用する stg 本体リソース（実体はモジュール）---
output "image_bucket" { value = module.app.image_bucket }
output "image_cdn_domain_name" { value = module.app.image_cdn_domain_name }
output "spa_fallback_function_arn" { value = module.app.spa_fallback_function_arn }
output "parameter_store_path" { value = module.app.parameter_store_path }

# preview は stg の検証済み SES アイデンティティをそのまま使う（独自検証なし）
output "ses_domain_identity_arn" { value = module.app.ses_domain_identity_arn }
output "ses_domain_identity_name" { value = module.app.ses_domain_identity_name }

# preview の CloudFront も共有のマネージド WAF を使う（Basic 認証は CF Function 側）
output "cloudfront_waf_arn" { value = module.app.cloudfront_waf_arn }
