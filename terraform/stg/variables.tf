variable "project_name" {
  description = "プロジェクトの名前"
  type        = string
}

variable "github_repository" {
  description = "GitHub Actions OIDCで引き受けを許可するリポジトリ（\"owner/repo\" 形式）"
  type        = string
}

variable "github_environment_name" {
  description = "AssumeRole を許可する GitHub Environment 名（例: stg, prod）"
  type        = string
}

variable "github_allowed_branches" {
  description = "GitHub Actions の environment 別ロールに AssumeRole を許可するブランチ名のリスト（例: stg は [\"main\", \"develop\"]、prod は [\"main\"]）"
  type        = list(string)
}

variable "preview_github_environment_name" {
  description = "preview デプロイロールの AssumeRole を許可する GitHub Environment 名（OIDC sub: repo:OWNER/REPO:environment:NAME）。preview_shared.tf で使用。"
  type        = string
  default     = "preview"
}

variable "tfstate_bucket" {
  description = "Terraform state が格納されている S3 バケット名"
  type        = string
}

variable "tfstate_key" {
  description = "Terraform state の S3 オブジェクトキー"
  type        = string
}

variable "domain_name" {
  description = "Route53のドメイン名"
  type        = string
}

variable "sub_frontend_domain_name" {
  description = "Route53のサブドメイン名(フロントエンド)"
  type        = string
}

variable "sub_backend_domain_name" {
  description = "Route53のサブドメイン名(バックエンド)"
  type        = string
}

variable "db_name" {
  description = "RDSのデータベース名"
  type        = string
}

variable "db_username" {
  description = "RDSのユーザー名"
  type        = string
}

variable "parameter_store_path" {
  description = "Parameter Storeのパス"
  type        = string
}

variable "image_tag_nginx" {
  description = "ECRのイメージタグ (nginx)"
  type        = string
}

variable "image_tag_laravel" {
  description = "ECRのイメージタグ (Laravel / migration / seeder / batch / queue worker 共通)"
  type        = string
}

variable "ecr_repo_name_nginx" {
  description = "ECRリポジトリ名 (nginx)"
  type        = string
}

variable "ecr_repo_name_laravel" {
  description = "ECRリポジトリ名 (Laravel)"
  type        = string
}

variable "enable_nat_gateway" {
  description = "NAT Gatewayを有効化するかどうか"
  type        = bool
}

variable "app_env" {
  description = "アプリケーションの環境（例: staging, production）"
  type        = string
}

variable "rds_config" {
  description = "RDSの環境別設定（モジュール側variables.tfに各属性の説明あり）"
  type = object({
    instance_class                  = string
    skip_final_snapshot             = bool
    enabled_cloudwatch_logs_exports = list(string)
    multi_az                        = bool
    backup_retention_period         = number
    performance_insights_enabled    = bool
    monitoring_interval             = number
    apply_immediately               = bool
    alarm_thresholds = object({
      cpu_utilization_percent  = number
      free_storage_space_bytes = number
      freeable_memory_bytes    = number
      database_connections     = number
    })
  })
}

variable "ecs_web_service_config" {
  description = "ECS Webサービスの環境別設定（モジュール側variables.tfに各属性の説明あり）"
  type = object({
    cpu                  = string
    memory               = string
    desired_count        = number
    bake_time_in_minutes = number
    capacity_provider_strategy = list(object({
      capacity_provider = string
      weight            = number
      base              = number
    }))
    autoscaling = object({
      min_capacity        = number
      max_capacity        = number
      cpu_target_value    = number
      memory_target_value = number
    })
  })
}

variable "ecs_queue_worker_service_config" {
  description = "ECS Queue Workerサービスの環境別設定（モジュール側variables.tfに各属性の説明あり）"
  type = object({
    cpu           = string
    memory        = string
    desired_count = number
    capacity_provider_strategy = list(object({
      capacity_provider = string
      weight            = number
      base              = number
    }))
  })
}

variable "ecs_runner_task_config" {
  description = "ECS Runnerタスク（migrate / seed / shell 共通）の環境別設定（モジュール側variables.tfに各属性の説明あり）"
  type = object({
    cpu    = string
    memory = string
  })
}

variable "ecs_batch_daily_report_task_config" {
  description = "ECS Batch（日次レポート）タスクの環境別設定（モジュール側variables.tfに各属性の説明あり）"
  type = object({
    cpu    = string
    memory = string
    capacity_provider_strategy = list(object({
      capacity_provider = string
      weight            = number
      base              = number
    }))
  })
}
