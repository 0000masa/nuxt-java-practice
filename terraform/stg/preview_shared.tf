# =====================================================================
# PR ごとの検証環境（preview）の「共有 / ブートストラップ」リソース
# =====================================================================
# 1 回だけ作成し、全 PR の preview が共有する。PR ごとのリソースは
# terraform/pr-env/ が作る（このファイルでは作らない）。
#
# このファイルは stg ルート専用に置く（共有モジュールには置かない）。
# preview は stg にしか相乗りせず prod には不要なため、モジュールに入れると
# prod ルートまで preview リソースを生やしてしまう。詳細は ADR 0007:
#   docs/adr/0007-preview-shared-in-stg-root.md
# モジュールが公開する ALB / ECS / 実行ロール / Route53 の値は module.app.* の
# output から参照する（pr-env 向けに既に公開済み）。
# 運用詳細: docs/deploy/pr-preview-environment.md

locals {
  preview_zone_apex   = "preview.${var.domain_name}"     # 例: preview.mylabinfra.com
  preview_wildcard    = "*.preview.${var.domain_name}"   # viewer: pr-<n>.preview.<domain>
  preview_api_origin  = "api.preview.${var.domain_name}" # 共有 ALB オリジン（CloudFront の /api 用）
  preview_oidc_sub    = "repo:${var.github_repository}:environment:${var.preview_github_environment_name}"
  preview_role_prefix = "preview-pr" # per-PR ロールの命名（pr-env と合わせる）
}

# ---------------------------------------------------------------------
# ワイルドカード ACM 証明書（CloudFront 用 = us-east-1）
# ---------------------------------------------------------------------
resource "aws_acm_certificate" "preview_cf" {
  provider          = aws.us_east_1
  domain_name       = local.preview_wildcard
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

# ---------------------------------------------------------------------
# ワイルドカード ACM 証明書（ALB 用 = ap-northeast-1）
# CloudFront → ALB(api.preview...) のオリジン TLS 用。ALB の HTTPS リスナーに追加する。
# ---------------------------------------------------------------------
resource "aws_acm_certificate" "preview_alb" {
  domain_name       = local.preview_wildcard
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

# 2 証明書とも検証対象は同一ドメイン (*.preview...) のため DNS レコードは同じになる。
# allow_overwrite で衝突を回避し、1 レコードを共有する。
resource "aws_route53_record" "preview_cert_validation" {
  for_each = {
    for o in aws_acm_certificate.preview_cf.domain_validation_options : o.domain_name => {
      name   = o.resource_record_name
      record = o.resource_record_value
      type   = o.resource_record_type
    }
  }
  allow_overwrite = true
  zone_id         = module.app.route53_zone_id
  name            = each.value.name
  records         = [each.value.record]
  type            = each.value.type
  ttl             = 60
}

resource "aws_acm_certificate_validation" "preview_cf" {
  provider                = aws.us_east_1
  certificate_arn         = aws_acm_certificate.preview_cf.arn
  validation_record_fqdns = [for r in aws_route53_record.preview_cert_validation : r.fqdn]
}

resource "aws_acm_certificate_validation" "preview_alb" {
  certificate_arn         = aws_acm_certificate.preview_alb.arn
  validation_record_fqdns = [for r in aws_route53_record.preview_cert_validation : r.fqdn]
}

# ALB の HTTPS リスナーに preview ワイルドカード証明書を追加（SNI: api.preview...）
resource "aws_lb_listener_certificate" "preview" {
  listener_arn    = module.app.alb_https_listener_arn
  certificate_arn = aws_acm_certificate_validation.preview_alb.certificate_arn
}

# ---------------------------------------------------------------------
# 共有 API オリジン: api.preview.<domain> → ALB（全 PR の CloudFront が /api でここを指す）
# ---------------------------------------------------------------------
resource "aws_route53_record" "preview_api_origin" {
  zone_id = module.app.route53_zone_id
  name    = local.preview_api_origin
  type    = "A"
  alias {
    name                   = module.app.alb_dns_name
    zone_id                = module.app.alb_zone_id
    evaluate_target_health = true
  }
}

# frontend バケットは PR ごとに pr-env が作る（terraform/pr-env/main.tf）。
# destroy でバケットごと中身も消えるよう per-PR にした。共有バケットは持たない。

# ---------------------------------------------------------------------
# Basic 認証の資格情報（手動作成の SSM パラメータ、生の "user:pass" 形式）。
# Basic 認証は WAF ではなく CloudFront Function（module の spa_fallback）で行う。
# この値を stg ルートが module.app の basic_auth_credential に渡し、enable_basic_auth=true
# のとき関数コードへ "Basic <b64>" を焼き込む。preview の CloudFront も stg の同じ関数を
# 共有するため、別途 preview 専用 WAF は持たない（WAF は全環境で cloudfront_waf 1枚に集約）。
# base64 化は module 側で base64encode() する（SSM には人間が入力する生の値を置く）。
# ---------------------------------------------------------------------
data "aws_ssm_parameter" "preview_basic_auth" {
  name            = "${var.parameter_store_path}preview_basic_auth"
  with_decryption = true
}

# preview の DB ユーザー(preview)のパスワード。手動作成の SSM パラメータ。
# 共有実行ロールがこの ARN を読めるよう、stg ルートで後付けポリシーを足す
# （aws_iam_role_policy.preview_execution_ssm。モジュール本体は preview を知らない）。
data "aws_ssm_parameter" "preview_db_password" {
  name            = "${var.parameter_store_path}preview_db_password"
  with_decryption = true
}

# preview デプロイ用 OIDC ロール（GitHub Environment `preview` 経由でのみ AssumeRole 可）
module "gha_preview_deploy_role" {
  source = "terraform-aws-modules/iam/aws//modules/iam-role"

  name            = "${var.project_name}-gha-preview-deploy-role"
  use_name_prefix = false

  enable_github_oidc = true
  oidc_subjects      = [local.preview_oidc_sub]

  policies = {
    PreviewDeploy = aws_iam_policy.preview_deploy.arn
  }
}

# ---------------------------------------------------------------------
# preview デプロイ用 IAM ポリシー（GitHub Actions が AssumeRole して Terraform 実行）
# 注意: これは「最小権限の初期案」。CloudFront / ELBv2 など resource-level 非対応 API は
#       Resource="*" になる。IAM だけは /preview/ パス + Boundary 必須で昇格を防ぐ。
#       実運用では terraform plan/apply の AccessDenied を見て継続的に絞り込む。
# ---------------------------------------------------------------------
resource "aws_iam_policy" "preview_deploy" {
  name        = "${var.project_name}-gha-preview-deploy-policy"
  description = "GitHub Actions: provision/destroy per-PR preview environments"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "CloudFront"
        Effect   = "Allow"
        Action   = ["cloudfront:*"]
        Resource = "*"
      },
      {
        Sid      = "Elbv2"
        Effect   = "Allow"
        Action   = ["elasticloadbalancing:*"]
        Resource = "*"
      },
      {
        Sid      = "Ecs"
        Effect   = "Allow"
        Action   = ["ecs:*"]
        Resource = "*"
        Condition = {
          # クラスタ単位に縛れる API はこの条件が効く（縛れない API は無視される）。
          ArnEqualsIfExists = { "ecs:cluster" = module.app.ecs_cluster_arn }
        }
      },
      {
        Sid      = "Sqs"
        Effect   = "Allow"
        Action   = ["sqs:*"]
        Resource = "arn:aws:sqs:ap-northeast-1:${module.app.aws_account_id}:${var.project_name}-preview-pr*"
      },
      {
        Sid      = "Route53Change"
        Effect   = "Allow"
        Action   = ["route53:ChangeResourceRecordSets", "route53:ListResourceRecordSets", "route53:GetHostedZone"]
        Resource = "arn:aws:route53:::hostedzone/${module.app.route53_zone_id}"
      },
      {
        Sid      = "Route53Get"
        Effect   = "Allow"
        Action   = ["route53:GetChange"]
        Resource = "*"
      },
      {
        # PR ごとの frontend バケットを作成/削除/操作する（命名は <project>-preview-pr*）。
        # 範囲を preview-pr* バケットに限定したうえで s3:* を許可（作成/ポリシー/アップロード一式）。
        Sid      = "S3PreviewBuckets"
        Effect   = "Allow"
        Action   = ["s3:*"]
        Resource = ["arn:aws:s3:::${var.project_name}-preview-pr*", "arn:aws:s3:::${var.project_name}-preview-pr*/*"]
      },
      {
        # DescribeLogGroups は「ロググループ一覧」の列挙系 API。IAM 評価が特定名ではなく
        # アカウント全体の log-group 名前空間（log-group::log-stream:）に対して行われるため、
        # prefix 付き ARN ではマッチせず拒否される。列挙系は Resource="*" が必須。
        Sid      = "LogsDescribe"
        Effect   = "Allow"
        Action   = ["logs:DescribeLogGroups"]
        Resource = "*"
      },
      {
        # 変更系は preview-pr* のロググループに限定（末尾 * が :log-stream まで含めて吸収する）。
        Sid      = "LogsManage"
        Effect   = "Allow"
        Action   = ["logs:CreateLogGroup", "logs:DeleteLogGroup", "logs:PutRetentionPolicy", "logs:TagResource", "logs:ListTagsForResource"]
        Resource = "arn:aws:logs:*:${module.app.aws_account_id}:log-group:/ecs/${var.project_name}-preview-pr*"
      },
      {
        Sid      = "AcmWafRead"
        Effect   = "Allow"
        Action   = ["acm:DescribeCertificate", "acm:ListCertificates", "wafv2:GetWebACL", "wafv2:AssociateWebACL", "wafv2:DisassociateWebACL", "wafv2:ListResourcesForWebACL"]
        Resource = "*"
      },
      # --- ECR: PR イメージ(nginx/laravel)のビルド&push 用 ---
      # ecr:GetAuthorizationToken は resource-level 非対応なので Resource="*"。
      # push 系は laravel / nginx の2リポジトリに限定する。
      {
        Sid      = "EcrAuth"
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = "*"
      },
      {
        Sid    = "EcrPushPreview"
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability",
          "ecr:InitiateLayerUpload",
          "ecr:UploadLayerPart",
          "ecr:CompleteLayerUpload",
          "ecr:PutImage",
          "ecr:BatchGetImage"
        ]
        Resource = [
          "arn:aws:ecr:ap-northeast-1:${module.app.aws_account_id}:repository/${var.ecr_repo_name_laravel}",
          "arn:aws:ecr:ap-northeast-1:${module.app.aws_account_id}:repository/${var.ecr_repo_name_nginx}"
        ]
      },
      # --- IAM: /preview/ 配下のみ。CreateRole は Boundary 付与を強制（権限昇格防止）---
      # preview デプロイロール（GitHub Actions が AssumeRole）に「IAM ロールを新規作成
      # してよい」を与えるブロック。実際に作られる先は pr-env/iam.tf の
      # aws_iam_role.task（per-PR タスクロール）。ただし 2 つのゲートを掛ける:
      #   1) Resource ゲート: ARN が role/preview/* に一致するロールしか作れない。
      #      → pr-env/iam.tf の path = "/preview/" がこれを満たす。path を省略すると
      #        ARN は role/<name> になり AccessDenied。
      #   2) Condition ゲート: CreateRole 時に iam:PermissionsBoundary が
      #      preview_boundary の ARN と完全一致しないと拒否。
      #      → pr-env/iam.tf の permissions_boundary = ...preview_permissions_boundary_arn
      #        がこれを満たす。付け忘れると AccessDenied。
      # この 2 ゲートにより「Boundary 付き・/preview/ 配下のロール」しか作れず、
      # PR コードが admin ロールをブートストラップする経路を塞ぐ（ADR 0006）。
      {
        Sid      = "IamCreatePreviewRolesWithBoundary"
        Effect   = "Allow"
        Action   = ["iam:CreateRole", "iam:TagRole"]
        Resource = "arn:aws:iam::${module.app.aws_account_id}:role/preview/*"
        Condition = {
          StringEquals = { "iam:PermissionsBoundary" = aws_iam_policy.preview_boundary.arn }
        }
      },
      {
        Sid    = "IamManagePreviewRoles"
        Effect = "Allow"
        Action = [
          "iam:DeleteRole", "iam:GetRole", "iam:PutRolePolicy", "iam:DeleteRolePolicy",
          "iam:GetRolePolicy", "iam:ListRolePolicies", "iam:ListAttachedRolePolicies",
          "iam:AttachRolePolicy", "iam:DetachRolePolicy", "iam:ListInstanceProfilesForRole"
        ]
        Resource = "arn:aws:iam::${module.app.aws_account_id}:role/preview/*"
      },
      {
        Sid      = "IamPassPreviewRoles"
        Effect   = "Allow"
        Action   = ["iam:PassRole"]
        Resource = "arn:aws:iam::${module.app.aws_account_id}:role/preview/*"
        Condition = {
          StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" }
        }
      },
      # 共有の実行ロール（ECR pull / SSM 読み取り）も ECS に渡す
      {
        Sid      = "IamPassExecutionRole"
        Effect   = "Allow"
        Action   = ["iam:PassRole"]
        Resource = module.app.ecs_task_execution_role_arn
        Condition = {
          StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" }
        }
      },
      # --- pr-env の Terraform state（backend S3）---
      {
        Sid      = "TfstateObject"
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
        Resource = "arn:aws:s3:::${var.tfstate_bucket}/practice/laravel/preview/*"
      },
      {
        Sid      = "TfstateList"
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = "arn:aws:s3:::${var.tfstate_bucket}"
        Condition = {
          StringLike = { "s3:prefix" = ["practice/laravel/preview/*"] }
        }
      },
      # stg state（remote_state で読む）への読み取り
      {
        Sid      = "TfstateReadStg"
        Effect   = "Allow"
        Action   = ["s3:GetObject"]
        Resource = "arn:aws:s3:::${var.tfstate_bucket}/${var.tfstate_key}"
      },
      # SSM（preview 用シークレット読み取り：preview_db_password / app_key 等）
      {
        Sid      = "SsmRead"
        Effect   = "Allow"
        Action   = ["ssm:GetParameter", "ssm:GetParameters"]
        Resource = "arn:aws:ssm:*:${module.app.aws_account_id}:parameter${var.parameter_store_path}*"
      }
    ]
  })
}

# ---------------------------------------------------------------------
# Permissions Boundary（per-PR ロールが持てる権限の「上限＝天井」）
# ---------------------------------------------------------------------
# 重要: これは「権限の付与」ではない。Permissions Boundary は、これが付いた
# ロールが持ちうる権限の最大集合（天井）を定義するだけ。
# per-PR タスクロールの実効権限 = （pr-env/iam.tf の自前ポリシー）∩（この天井）
# の積集合になる。
#   - Resource = "*" と広いが、実際の絞り込みは pr-env/iam.tf 側の自前ポリシーが
#     具体的 ARN で行う（例: SQS は当該 PR のキュー、S3 は image_bucket のみ）。
#   - 逆に、per-PR ロールの自前ポリシーに何を書いても、ここに無い action
#     （例: iam:* / s3:DeleteBucket など）は天井で弾かれて効かない。
# CreateRole の Condition（上の IamCreatePreviewRolesWithBoundary）でこの Boundary
# 付与が強制されるため、デプロイロールはこの天井を超えるロールを作れない。
# = PR コードによる権限昇格(pwn-request)を構造的に防ぐ（ADR 0006）。
# ---------------------------------------------------------------------
resource "aws_iam_policy" "preview_boundary" {
  name        = "${var.project_name}-preview-permissions-boundary"
  description = "Max permissions for per-PR preview task roles"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "PreviewRuntimeMax"
        Effect = "Allow"
        Action = [
          "sqs:SendMessage", "sqs:ReceiveMessage", "sqs:DeleteMessage",
          "sqs:GetQueueAttributes", "sqs:GetQueueUrl",
          "s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:ListBucket",
          "logs:CreateLogStream", "logs:PutLogEvents",
          "ssmmessages:CreateControlChannel", "ssmmessages:CreateDataChannel",
          "ssmmessages:OpenControlChannel", "ssmmessages:OpenDataChannel",
          # メール送信（preview は stg の検証済み SES アイデンティティから送る）。
          # 天井は広め（Resource=*）だが、被害半径は AppServiceProvider の
          # Mail::alwaysTo による固定宛先上書きで限定される。per-PR ロール本体は
          # identity ARN にスコープする（iam.tf の SesSend）。
          "ses:SendEmail", "ses:SendRawEmail"
        ]
        # 上限なので広めだが、per-PR ロール自身のポリシーで更に絞る。
        Resource = "*"
      }
    ]
  })
}

# ---------------------------------------------------------------------
# 共有 ECS 実行ロールへの preview 用 SSM 読み取りの後付け（ADR 0007）
# モジュール本体の実行ロールポリシーは preview を一切参照しない。preview の
# DB パスワード読み取りだけを、stg ルートからインラインポリシーで足す。
# prod ルートはこのファイルを持たないので、この権限は prod に存在しない。
# ---------------------------------------------------------------------
resource "aws_iam_role_policy" "preview_execution_ssm" {
  name = "${var.project_name}-preview-execution-ssm"
  role = module.app.ecs_task_execution_role_name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["ssm:GetParameters", "ssm:GetParameter"]
        Resource = [data.aws_ssm_parameter.preview_db_password.arn]
      }
    ]
  })
}
