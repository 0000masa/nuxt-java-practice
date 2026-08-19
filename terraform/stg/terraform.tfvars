project_name             = "practice-stg"
github_repository        = "0000masa/react-laravel-practice-public"
github_environment_name  = "stg"
github_allowed_branches  = ["main", "develop"]
tfstate_bucket           = "github-action-terraform-tf-state-bucket"
tfstate_key              = "practice/laravel/stg/terraform.tfstate"
domain_name              = "mylabinfra.com"
sub_frontend_domain_name = "stg.www"
sub_backend_domain_name  = "stg.api"
db_name                  = "practice_db"
db_username              = "admin"
parameter_store_path     = "/practice/stg/"
image_tag_nginx          = "sha-8ccd019c5528b71fefc7c547014800b27aef99be"
image_tag_laravel        = "sha-ec4e906ca8c781afd909e243057b399c9802959e"
ecr_repo_name_nginx      = "react-laravel-practice-nginx-ecs"
ecr_repo_name_laravel    = "react-laravel-practice-laravel-ecs"
enable_nat_gateway       = true
app_env                  = "staging"

rds_config = {
  instance_class                  = "db.t4g.micro"
  skip_final_snapshot             = true
  enabled_cloudwatch_logs_exports = ["error", "slowquery"]
  multi_az                        = false
  backup_retention_period         = 0
  # Performance Insights は db.t2/t3/t4g の micro・small では非対応で、true にすると
  # apply が InvalidParameterCombination で失敗する（= この false は選択ではなく制約）。
  # prod 想定では db.t4g.medium 以上 + true（7日保持は無料）。判断の経緯: docs/adr/0012
  performance_insights_enabled = false

  # Enhanced Monitoring（拡張モニタリング）の収集間隔（秒）。0で無効、有効時は 1/5/10/15/30/60。
  # 通常の CloudWatch メトリクスがハイパーバイザー由来の1分粒度なのに対し、こちらは OS 内の
  # エージェントからプロセスごとの CPU/メモリ等を CloudWatch Logs へ送る（取り込み課金）。
  # 60秒間隔なら取込 0.27GB/月/インスタンスで CloudWatch Logs 無料枠（5GB/月）内 = 実質無料のため
  # stg でも有効化（CPU 等のアラーム通知後にプロセス単位で原因を特定する分析層の道具）。
  # 1〜15秒への細粒度化は取込量が跳ねる（1秒 = 16GB/月）ので障害調査時のみ一時的に。
  # 詳細: docs/monitoring/rds-observability-tools.md
  monitoring_interval = 60

  apply_immediately = true

  # 検知層のメトリクスアラーム閾値。AWS 公式推奨を db.t4g.micro（RAM 1GB / ストレージ 20GB）に
  # 当てはめた値。max_connections はメモリ連動（{DBInstanceClassMemory/12582880} ≈ 80）のため、
  # インスタンスクラスを変えるときはここも見直す。設計: docs/monitoring/rds-log-monitoring.md
  alarm_thresholds = {
    cpu_utilization_percent  = 90
    free_storage_space_bytes = 2147483648 # 割当20GBの10% = 2GiB
    freeable_memory_bytes    = 268435456  # RAM 1GBの25% = 256MiB
    database_connections     = 72         # max_connections(約80)の90%
  }
}

ecs_web_service_config = {
  cpu                  = "1024"
  memory               = "2048"
  desired_count        = 2
  bake_time_in_minutes = 0
  capacity_provider_strategy = [
    { capacity_provider = "FARGATE_SPOT", weight = 1, base = 0 }
  ]
  autoscaling = {
    min_capacity        = 2
    max_capacity        = 4
    cpu_target_value    = 60
    memory_target_value = 70
  }
}

ecs_queue_worker_service_config = {
  cpu           = "256"
  memory        = "512"
  desired_count = 1
  capacity_provider_strategy = [
    { capacity_provider = "FARGATE_SPOT", weight = 1, base = 0 }
  ]
}

ecs_runner_task_config = {
  cpu    = "256"
  memory = "512"
}

ecs_batch_daily_report_task_config = {
  cpu    = "256"
  memory = "512"
  capacity_provider_strategy = [
    { capacity_provider = "FARGATE_SPOT", weight = 1, base = 0 }
  ]
}
