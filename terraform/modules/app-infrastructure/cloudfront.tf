# Origin Access Control（推奨の新方式）
# OACはCloudFront経由でのみS3バケットにアクセスできるようにする仕組み。
# ディストリビューションごとに分けることで、変更時の影響範囲を限定できる。

# 画像バケット用OAC
resource "aws_cloudfront_origin_access_control" "s3_oac_images" {
  name                              = "${var.project_name}-images-oac"
  description                       = "OAC for S3 images bucket"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

# フロントエンドバケット用OAC
resource "aws_cloudfront_origin_access_control" "s3_oac_frontend" {
  name                              = "${var.project_name}-frontend-oac"
  description                       = "OAC for S3 frontend bucket"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

locals {
  # Basic 認証スニペット。CF Functions は実行時に SSM を読めないため、apply 時に Terraform の
  # base64encode で "Basic <b64>" を完成させて焼き込み、受信 Authorization ヘッダと完全一致で
  # 判定する（WAF の search_string と同じ考え方）。
  basic_auth_snippet = <<-JS
    var authHeader = request.headers.authorization;
    if (!authHeader || authHeader.value !== "Basic ${base64encode(var.basic_auth_credential)}") {
      return {
        statusCode: 401,
        statusDescription: "Unauthorized",
        headers: { "www-authenticate": { value: "Basic realm=\"restricted\"" } }
      };
    }
  JS

  # enable_basic_auth=true（stg/preview）のときだけ関数の先頭に差し込む。false（prod）は空＝認証なし。
  basic_auth_check = var.enable_basic_auth ? local.basic_auth_snippet : ""
}

resource "aws_cloudfront_function" "spa_fallback" {
  name    = "${var.project_name}-spa-fallback"
  runtime = "cloudfront-js-2.0"
  comment = "Optional Basic auth (stg/preview) + rewrite SPA routes to /index.html"
  publish = true

  # 認証判定 → SPA フォールバックの順。default と /api/* の両ビヘイビアに付与するため、
  # /api/ や拡張子付きは早期 return（SPA 書き換えはしないが認証は通す）。
  code = <<EOF
  function handler(event) {
    var request = event.request;
${local.basic_auth_check}
    var uri = request.uri;

    // API と静的ファイル（拡張子あり）は書き換えない
    if (uri.startsWith('/api/') || uri.includes('.')) return request;

    // SPAルートは index.html に寄せる
    request.uri = '/index.html';
    return request;
  }
  EOF
}

resource "aws_cloudfront_distribution" "image_cdn" {
  enabled             = true
  is_ipv6_enabled     = true
  comment             = "${var.project_name}-images-cdn"
  default_root_object = ""

  origin {
    domain_name = aws_s3_bucket.image_bucket.bucket_regional_domain_name
    origin_id   = "${var.project_name}-s3-image-origin"

    origin_access_control_id = aws_cloudfront_origin_access_control.s3_oac_images.id
  }

  default_cache_behavior {
    allowed_methods  = ["GET", "HEAD"]
    cached_methods   = ["GET", "HEAD"]
    target_origin_id = "${var.project_name}-s3-image-origin" //上で定義したoriginのorigin_idと一致してないといけない

    viewer_protocol_policy = "redirect-to-https"
    compress               = true

    cache_policy_id          = data.aws_cloudfront_cache_policy.caching_optimized.id
    origin_request_policy_id = data.aws_cloudfront_origin_request_policy.s3_cors.id
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = true
  }

  tags = {
    Name = "${var.project_name}-images-cdn"
  }
}

resource "aws_cloudfront_distribution" "frontend_cdn" {
  enabled             = true
  comment             = "${var.project_name}-frontend-cdn"
  default_root_object = "index.html"

  # 【重要】独自ドメインを使用するための設定
  aliases = ["${var.sub_frontend_domain_name}.${var.domain_name}"]

  # フロントエンド用オリジン
  origin {
    domain_name              = aws_s3_bucket.frontend_bucket.bucket_regional_domain_name
    origin_id                = aws_s3_bucket.frontend_bucket.id
    origin_access_control_id = aws_cloudfront_origin_access_control.s3_oac_frontend.id
  }

  #  バックエンド用オリジン
  origin {
    # バックエンドのドメイン (例: api.example.com)
    domain_name = "${var.sub_backend_domain_name}.${var.domain_name}"
    origin_id   = "backend-api" //terraform内の紐づけid

    # CloudFront経由のみALBアクセスを許可するためのカスタムヘッダー
    custom_header {
      name  = "X-CloudFront-Secret"
      value = random_password.cf_secret.result
    }

    # バックエンドがHTTPSの場合の設定
    custom_origin_config {
      http_port              = 80 //多分設定しなくても同じ
      https_port             = 443
      origin_protocol_policy = "https-only" # バックエンドへはHTTPSで通信
      origin_ssl_protocols   = ["TLSv1.2"]
    }
  }

  # /api/* に来たリクエストをバックエンドへ流す
  ordered_cache_behavior {
    path_pattern     = "/api/*"
    target_origin_id = "backend-api" # 上で定義したIDを指定

    # APIなので全メソッド許可
    allowed_methods = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods  = ["GET", "HEAD"]

    # APIはキャッシュせず、Cookieやヘッダーを全てバックエンドに渡す (重要)
    cache_policy_id          = data.aws_cloudfront_cache_policy.caching_disabled.id
    origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer.id

    viewer_protocol_policy = "redirect-to-https"
    compress               = true

    # Basic 認証は CloudFront Function で行う。WAF と違い関数はビヘイビアごとなので、
    # /api/* にも付けないと API パスが認証を素通りする。
    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.spa_fallback.arn
    }
  }


  default_cache_behavior {
    allowed_methods  = ["GET", "HEAD"]
    cached_methods   = ["GET", "HEAD"]
    target_origin_id = aws_s3_bucket.frontend_bucket.id
    compress         = true

    cache_policy_id          = data.aws_cloudfront_cache_policy.caching_optimized.id
    origin_request_policy_id = data.aws_cloudfront_origin_request_policy.s3_cors.id

    viewer_protocol_policy = "redirect-to-https" # HTTPできたらHTTPSに飛ばす

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.spa_fallback.arn
    }
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    acm_certificate_arn      = aws_acm_certificate_validation.cert_frontend.certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }

  web_acl_id = aws_wafv2_web_acl.cloudfront_waf.arn
}
