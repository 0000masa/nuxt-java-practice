resource "aws_acm_certificate" "cert_frontend" {
  provider          = aws.us_east_1 # ★重要: バージニアを指定
  domain_name       = "${var.sub_frontend_domain_name}.${var.domain_name}"
  validation_method = "DNS"

  # 変更時に再作成するライフサイクル設定
  lifecycle {
    create_before_destroy = true
  }

  tags = {
    Name = "${var.project_name}-acm-frontend"
  }
}

resource "aws_acm_certificate" "cert_backend" {
  domain_name       = "${var.sub_backend_domain_name}.${var.domain_name}" # 証明書を発行したいドメイン（変数化推奨）
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }

  tags = {
    Name = "${var.project_name}-acm-backend"
  }
}

# DNSレコードが反映され、AWS側で「発行完了」となるまでTerraformを待機させます
resource "aws_acm_certificate_validation" "cert_frontend" {
  provider                = aws.us_east_1 # これもバージニア
  certificate_arn         = aws_acm_certificate.cert_frontend.arn
  validation_record_fqdns = [for record in aws_route53_record.cert_validation_frontend : record.fqdn]
}

resource "aws_acm_certificate_validation" "cert_backend" {
  certificate_arn         = aws_acm_certificate.cert_backend.arn
  validation_record_fqdns = [for record in aws_route53_record.cert_validation_backend : record.fqdn]
}
