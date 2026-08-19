# =====================================================================
# GitHub Actions ワークフロー用 最小権限 IAM ロール
# =====================================================================
# 各ワークフローに専用ロールを定義し、OIDC で引き受け可能にする。
# - ECR push 系 2 ロール: 任意 ref / environment から引き受け可（修正ブランチで stg 確認できるように）
# - その他 7 ロール: `var.github_environment_name` の Environment +
#   `var.github_allowed_branches` で許可されたブランチからのみ引き受け可
#
# 注意: ワークフロー側で `environment:` を指定すると OIDC トークンの `sub` は
#       `repo:OWNER/REPO:environment:NAME` 形式になり、`ref:refs/heads/...` 形式
#       にはならない。`sub` 単独では environment と branch を同時に縛れないため、
#       branch 制約は別クレーム `:ref` を `trust_policy_conditions` で追加して併用する。
#
# terraform-aws-modules/iam/aws//modules/iam-role の GitHub OIDC ネイティブサポートを利用。
# `iss` / `aud` / `sub` の condition はモジュール側で自動付与される。

locals {
  # AssumeRole を許可する単一の GitHub Environment に対する sub クレーム (StringEquals)
  oidc_sub_environment = "repo:${var.github_repository}:environment:${var.github_environment_name}"
  # 任意 ref から引き受け可能な sub クレームパターン (StringLike, ECR push 用)
  oidc_sub_any_ref = "repo:${var.github_repository}:*"

  # 環境別 7 ロールの trust policy に追加する「許可ブランチからのみ実行可」制約。
  # `:ref` クレームは GitHub OIDC が標準で発行するクレームの一つで、ジョブを起動した
  # リポジトリの ref（例: refs/heads/main）を表す。enable_github_oidc は sub/aud/iss
  # しか自動で入れないため、`trust_policy_conditions` で明示的に追加する。
  # StringEquals + 複数値は OR 評価なので、リストに並べるだけで複数ブランチ許可になる。
  trust_conditions_allowed_branches = [
    {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:ref"
      values   = [for b in var.github_allowed_branches : "refs/heads/${b}"]
    }
  ]
}

# ---------------------------------------------------------------------
# ECR push: Laravel イメージ (任意 ref / environment)
# ---------------------------------------------------------------------
module "gha_ecr_laravel_role" {
  source = "terraform-aws-modules/iam/aws//modules/iam-role"

  name            = "${var.project_name}-gha-ecr-laravel-role"
  use_name_prefix = false

  enable_github_oidc     = true
  oidc_wildcard_subjects = [local.oidc_sub_any_ref]

  policies = {
    EcrPushLaravel = aws_iam_policy.gha_ecr_laravel_policy.arn
  }
}

# ---------------------------------------------------------------------
# ECR push: Nginx イメージ (任意 ref / environment)
# ---------------------------------------------------------------------
module "gha_ecr_nginx_role" {
  source = "terraform-aws-modules/iam/aws//modules/iam-role"

  name            = "${var.project_name}-gha-ecr-nginx-role"
  use_name_prefix = false

  enable_github_oidc     = true
  oidc_wildcard_subjects = [local.oidc_sub_any_ref]

  policies = {
    EcrPushNginx = aws_iam_policy.gha_ecr_nginx_policy.arn
  }
}

# ---------------------------------------------------------------------
# ECS UpdateService: main service (Laravel コンテナ更新, 当該 environment + 許可ブランチのみ)
# ---------------------------------------------------------------------
module "gha_ecs_update_laravel_role" {
  source = "terraform-aws-modules/iam/aws//modules/iam-role"

  name            = "${var.project_name}-gha-ecs-update-laravel-role"
  use_name_prefix = false

  enable_github_oidc      = true
  oidc_subjects           = [local.oidc_sub_environment]
  trust_policy_conditions = local.trust_conditions_allowed_branches

  policies = {
    EcsUpdateMainService = aws_iam_policy.gha_ecs_update_main_service_policy.arn
  }
}

# ---------------------------------------------------------------------
# ECS UpdateService: main service (Nginx コンテナ更新, 当該 environment + 許可ブランチのみ)
# Laravel と同じサービスを更新するため policy は共有
# ---------------------------------------------------------------------
module "gha_ecs_update_nginx_role" {
  source = "terraform-aws-modules/iam/aws//modules/iam-role"

  name            = "${var.project_name}-gha-ecs-update-nginx-role"
  use_name_prefix = false

  enable_github_oidc      = true
  oidc_subjects           = [local.oidc_sub_environment]
  trust_policy_conditions = local.trust_conditions_allowed_branches

  policies = {
    EcsUpdateMainService = aws_iam_policy.gha_ecs_update_main_service_policy.arn
  }
}

# ---------------------------------------------------------------------
# ECS UpdateService: queue worker (当該 environment + 許可ブランチのみ)
# ---------------------------------------------------------------------
module "gha_ecs_update_queue_role" {
  source = "terraform-aws-modules/iam/aws//modules/iam-role"

  name            = "${var.project_name}-gha-ecs-update-laravel-queue-role"
  use_name_prefix = false

  enable_github_oidc      = true
  oidc_subjects           = [local.oidc_sub_environment]
  trust_policy_conditions = local.trust_conditions_allowed_branches

  policies = {
    EcsUpdateQueueService = aws_iam_policy.gha_ecs_update_queue_service_policy.arn
  }
}

# ---------------------------------------------------------------------
# DB Runner タスク実行 (migrate / seed / shell 共通, 当該 environment + 許可ブランチのみ)
# ---------------------------------------------------------------------
module "gha_db_runner_role" {
  source = "terraform-aws-modules/iam/aws//modules/iam-role"

  name            = "${var.project_name}-gha-db-runner-role"
  use_name_prefix = false

  enable_github_oidc      = true
  oidc_subjects           = [local.oidc_sub_environment]
  trust_policy_conditions = local.trust_conditions_allowed_branches

  policies = {
    DbRunnerRunTask = aws_iam_policy.gha_db_runner_policy.arn
  }
}

# ---------------------------------------------------------------------
# S3 frontend デプロイ + CloudFront キャッシュ削除 (当該 environment + 許可ブランチのみ)
# ---------------------------------------------------------------------
module "gha_s3_deploy_frontend_role" {
  source = "terraform-aws-modules/iam/aws//modules/iam-role"

  name            = "${var.project_name}-gha-s3-deploy-frontend-role"
  use_name_prefix = false

  enable_github_oidc      = true
  oidc_subjects           = [local.oidc_sub_environment]
  trust_policy_conditions = local.trust_conditions_allowed_branches

  policies = {
    S3DeployFrontend = aws_iam_policy.gha_s3_deploy_frontend_policy.arn
  }
}

# ---------------------------------------------------------------------
# ecspresso によるタスク定義登録 / サービスデプロイ (当該 environment + 許可ブランチのみ)
# ---------------------------------------------------------------------
module "gha_ecspresso_role" {
  source = "terraform-aws-modules/iam/aws//modules/iam-role"

  name            = "${var.project_name}-gha-ecspresso-role"
  use_name_prefix = false

  enable_github_oidc      = true
  oidc_subjects           = [local.oidc_sub_environment]
  trust_policy_conditions = local.trust_conditions_allowed_branches

  policies = {
    Ecspresso = aws_iam_policy.gha_ecspresso_policy.arn
  }
}

# =====================================================================
# 出力: 各ロールの ARN（GitHub Secrets 設定に利用）
# =====================================================================
output "github_actions_role_arns" {
  description = "GitHub Actions ワークフローごとの IAM ロール ARN"
  value = {
    ecr_laravel        = module.gha_ecr_laravel_role.arn
    ecr_nginx          = module.gha_ecr_nginx_role.arn
    ecs_update_laravel = module.gha_ecs_update_laravel_role.arn
    ecs_update_nginx   = module.gha_ecs_update_nginx_role.arn
    ecs_update_queue   = module.gha_ecs_update_queue_role.arn
    db_runner          = module.gha_db_runner_role.arn
    s3_deploy_frontend = module.gha_s3_deploy_frontend_role.arn
    ecspresso          = module.gha_ecspresso_role.arn
  }
}
