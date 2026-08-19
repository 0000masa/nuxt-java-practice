variable "project_name" {
  description = "プロジェクトの名前"
  type        = string
}

variable "github_repository" {
  description = "GitHub Actions OIDCで引き受けを許可するリポジトリ（\"owner/repo\" 形式）"
  type        = string
}

variable "github_environment_name" {
  description = "AssumeRole を許可する GitHub Environment 名（例: stg, prod）。OIDC sub クレーム repo:OWNER/REPO:environment:NAME のチェックに使用。"
  type        = string
}

variable "github_allowed_branches" {
  description = <<-EOT
    environment 別 GitHub Actions ロール（ECS update / db_runner / s3_deploy_frontend / ecspresso）
    の AssumeRole を許可するブランチ名のリスト。
    値はブランチ名のみ指定し、モジュール側で "refs/heads/<branch>" 形式に変換して
    IAM trust policy の token.actions.githubusercontent.com:ref 条件に展開する。
    例: stg は ["main", "develop"]、prod は ["main"]。
    ECR push 2 ロールは任意 ref から引き受け可能で、この変数の影響を受けない。
  EOT
  type        = list(string)

  validation {
    condition     = length(var.github_allowed_branches) > 0
    error_message = "github_allowed_branches には少なくとも1つのブランチ名を指定してください。"
  }
}

variable "tfstate_bucket" {
  description = "Terraform state が格納されている S3 バケット名（GitHub Actions の ecspresso ロールが state を読むため）"
  type        = string
}

variable "tfstate_key" {
  description = "Terraform state の S3 オブジェクトキー（例: practice/laravel/stg/terraform.tfstate）"
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
  description = <<-EOT
    RDSの環境別設定。
    - instance_class: インスタンスクラス
    - skip_final_snapshot: 削除時に最終スナップショットをスキップするか（prodはfalse推奨）
    - enabled_cloudwatch_logs_exports: CloudWatch Logsへエクスポートするログ種別
    - multi_az: Multi-AZ構成を有効にするか（prodはtrue推奨）
    - backup_retention_period: 自動バックアップの保持日数（0で無効、最大35）
    - performance_insights_enabled: Performance Insightsを有効にするか（直近7日間は無料）
    - monitoring_interval: Enhanced Monitoringの収集間隔（秒）。0で無効、有効時は1/5/10/15/30/60
    - apply_immediately: 設定変更を即時反映するか（prodはfalse推奨）
    - alarm_thresholds: 検知層のメトリクスアラーム閾値。インスタンスクラス依存の値
      （FreeStorageSpace/FreeableMemory/DatabaseConnections）を含むため環境ごとに渡す。
      設計: docs/monitoring/rds-log-monitoring.md
  EOT
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
      cpu_utilization_percent  = number # CPUUtilization がこの%を超えたら（公式推奨: 90）
      free_storage_space_bytes = number # FreeStorageSpace がこのバイト数を下回ったら（公式推奨: 割当の10%）
      freeable_memory_bytes    = number # FreeableMemory がこのバイト数を下回ったら（公式推奨: 総メモリの25%）
      database_connections     = number # DatabaseConnections がこの数を超えたら（公式推奨: max_connections の90%）
    })
  })
}

variable "ecs_web_service_config" {
  description = <<-EOT
    ECS Web サービス（nginx + laravel + log-router + adot-collector）の環境別設定。
    - desired_count: 起動タスク数の初期値
    - cpu: タスク全体のCPU（"256"/"512"/"1024"/"2048"/"4096"）
    - memory: タスク全体のメモリ（MiB、cpuに応じた組み合わせ要）
    - bake_time_in_minutes: ECS ネイティブ Blue/Green の切替後に Blue を残す時間（分）。
      0〜10080。stg は短め（0〜5）、prod は自動ロールバック猶予を確保するため長め（30〜60）推奨
    - capacity_provider_strategy: FARGATE / FARGATE_SPOT の比率と最低数
      - capacity_provider: "FARGATE" または "FARGATE_SPOT"
      - weight: 配分比率（同一プロバイダで複数指定する用途用）
      - base: このプロバイダで最低限確保するタスク数
    - autoscaling: Application Auto Scaling 設定
      - min_capacity / max_capacity: スケール時の下限/上限タスク数
      - cpu_target_value: CPU使用率の目標値（%）。これを超えるとスケールアウト
      - memory_target_value: メモリ使用率の目標値（%）
  EOT
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
  description = <<-EOT
    ECS Queue Worker サービス（Laravel queue:work）の環境別設定。
    - desired_count: 常時稼働させるワーカー数
    - cpu / memory: タスクのCPU / メモリ
    - capacity_provider_strategy: FARGATE / FARGATE_SPOT の比率と最低数
  EOT
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
  description = <<-EOT
    ECS Runner タスク（migrate / seed / 任意 shell コマンドを containerOverrides で
    実行する汎用タスク）のタスク定義設定。
    - cpu / memory: タスクのCPU / メモリ
    （launch_type / capacity_provider は run-task 実行側で指定する想定のため変数化対象外）
  EOT
  type = object({
    cpu    = string
    memory = string
  })
}

variable "ecs_batch_daily_report_task_config" {
  description = <<-EOT
    ECS Batch（日次レポート）タスクの環境別設定。EventBridgeから起動される。
    - cpu / memory: タスクのCPU / メモリ
    - capacity_provider_strategy: FARGATE / FARGATE_SPOT の比率と最低数（EventBridge起動時に適用）
  EOT
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

variable "s3_force_destroy" {
  description = <<-EOT
    モジュール内 S3 バケットを terraform destroy 時に中身ごと強制削除するか。
    stg は true（破棄・再作成しやすく）、prod は false（フロント資産・QR画像・監査ログを誤削除から守る）推奨。
    安全側に倒すため default は false。stg ルートだけ明示的に true を渡す。
  EOT
  type        = bool
  default     = false
}

variable "enable_basic_auth" {
  description = <<-EOT
    frontend CloudFront に Basic 認証（CloudFront Function 方式）を掛けるか。
    stg / preview は true（非公開化）、prod は false（公開）。
    true のとき spa_fallback 関数の先頭に Basic 認証判定が差し込まれる。
  EOT
  type        = bool
  default     = false
}

variable "basic_auth_credential" {
  description = <<-EOT
    Basic 認証の生の資格情報 "user:pass"（enable_basic_auth=true のときのみ使用）。
    base64 化は module 内で行い、"Basic <b64>" を関数コードに焼き込む。
    CF Functions は実行時に SSM を読めないため apply 時の埋め込みが必要。
  EOT
  type        = string
  default     = ""
  sensitive   = true
}
