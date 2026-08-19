data "aws_caller_identity" "current" {}

data "aws_ecr_repository" "nginx" {
  name = var.ecr_repo_name_nginx
}

data "aws_ecr_repository" "laravel" {
  name = var.ecr_repo_name_laravel
}

data "aws_ssm_parameter" "db_password" {
  name            = "${var.parameter_store_path}db_password"
  with_decryption = true
}

data "aws_ssm_parameter" "google_client_id" {
  name            = "${var.parameter_store_path}google_client_id"
  with_decryption = true
}

data "aws_ssm_parameter" "google_client_secret" {
  name            = "${var.parameter_store_path}google_client_secret"
  with_decryption = true
}

data "aws_ssm_parameter" "app_key" {
  name            = "${var.parameter_store_path}app_key"
  with_decryption = true
}

data "aws_ssm_parameter" "alert_email_to" {
  name            = "${var.parameter_store_path}alert_email_to"
  with_decryption = true
}

# フロント(静的配信)用: キャッシュ最適化
data "aws_cloudfront_cache_policy" "caching_optimized" {
  name = "Managed-CachingOptimized"
}

# S3オリジンに余計な情報を渡さない（最小）
data "aws_cloudfront_origin_request_policy" "s3_cors" {
  name = "Managed-CORS-S3Origin"
}

# API用なので「キャッシュしない」ポリシーを取得
data "aws_cloudfront_cache_policy" "caching_disabled" {
  name = "Managed-CachingDisabled"
}

# API用なので「Cookieやヘッダーを全て通す」ポリシーを取得
data "aws_cloudfront_origin_request_policy" "all_viewer" {
  name = "Managed-AllViewer"
}

# Lambda用のzipファイル
data "archive_file" "lambda_zip" {
  type        = "zip"
  source_file = local_file.lambda_py.filename
  output_path = "${path.module}/.tmp/lambda.zip"
}
