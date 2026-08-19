module "app" {
  source = "../modules/app-infrastructure"

  project_name             = var.project_name
  github_repository        = var.github_repository
  github_environment_name  = var.github_environment_name
  github_allowed_branches  = var.github_allowed_branches
  tfstate_bucket           = var.tfstate_bucket
  tfstate_key              = var.tfstate_key
  domain_name              = var.domain_name
  sub_frontend_domain_name = var.sub_frontend_domain_name
  sub_backend_domain_name  = var.sub_backend_domain_name
  db_name                  = var.db_name
  db_username              = var.db_username
  parameter_store_path     = var.parameter_store_path
  image_tag_nginx          = var.image_tag_nginx
  image_tag_laravel        = var.image_tag_laravel
  ecr_repo_name_nginx      = var.ecr_repo_name_nginx
  ecr_repo_name_laravel    = var.ecr_repo_name_laravel
  enable_nat_gateway       = var.enable_nat_gateway
  app_env                  = var.app_env

  # stg は外部非公開のため frontend CloudFront に Basic 認証（CF Function 方式）を掛ける。
  # 認証情報は手動作成の SSM（生の "user:pass"）。preview も stg の同じ関数を共有する。
  # prod ルート（将来）は enable_basic_auth を渡さない＝デフォルト false で公開のまま。
  enable_basic_auth     = true
  basic_auth_credential = data.aws_ssm_parameter.preview_basic_auth.value

  # stg は破棄・再作成しやすいよう全 S3 バケットを強制削除可に（prod ルートは未指定＝default false で保護）。
  s3_force_destroy = true

  rds_config = var.rds_config

  ecs_web_service_config             = var.ecs_web_service_config
  ecs_queue_worker_service_config    = var.ecs_queue_worker_service_config
  ecs_runner_task_config             = var.ecs_runner_task_config
  ecs_batch_daily_report_task_config = var.ecs_batch_daily_report_task_config

  providers = {
    aws           = aws
    aws.us_east_1 = aws.us_east_1
  }
}
