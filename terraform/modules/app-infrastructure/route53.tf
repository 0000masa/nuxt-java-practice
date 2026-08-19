# 1. すでに持っているホストゾーンの情報を取得する
#    (AWS上のRoute 53にあるゾーン情報を参照します)
data "aws_route53_zone" "main" {
  name         = var.domain_name
  private_zone = false
}

# Frontend (CloudFront) へのエイリアス
resource "aws_route53_record" "frontend_record" {
  zone_id = data.aws_route53_zone.main.zone_id
  name    = "${var.sub_frontend_domain_name}.${var.domain_name}"
  type    = "A"

  alias {
    name                   = aws_cloudfront_distribution.frontend_cdn.domain_name
    zone_id                = aws_cloudfront_distribution.frontend_cdn.hosted_zone_id
    evaluate_target_health = false
  }
}

# Frontend (CloudFront) へのエイリアス (IPv6)
resource "aws_route53_record" "frontend_record_aaaa" {
  zone_id = data.aws_route53_zone.main.zone_id
  name    = "${var.sub_frontend_domain_name}.${var.domain_name}"
  type    = "AAAA"

  alias {
    name                   = aws_cloudfront_distribution.frontend_cdn.domain_name
    zone_id                = aws_cloudfront_distribution.frontend_cdn.hosted_zone_id
    evaluate_target_health = false
  }
}

# Backend (ALB) へのエイリアス
resource "aws_route53_record" "backend_record" {
  # どのホストゾーンに追加するか
  zone_id = data.aws_route53_zone.main.zone_id

  # どんなサブドメインにするか
  name = "${var.sub_backend_domain_name}.${var.domain_name}"

  # Aレコード（IPv4アドレスへの紐付け）
  type = "A"

  # 【重要】ALBへの転送設定 (CNAMEではなくAliasを使うのがAWSの定石)
  alias {
    name                   = aws_lb.main.dns_name # 作成したALBのDNS名
    zone_id                = aws_lb.main.zone_id  # 作成したALBのホストゾーンID
    evaluate_target_health = true
  }
}



# ② 検証用DNSレコードの作成（Route53への登録）
# ACMが「このレコードを追加して所有権を証明しろ」と言ってくる情報を自動登録します
resource "aws_route53_record" "cert_validation_frontend" {
  for_each = {
    for item in aws_acm_certificate.cert_frontend.domain_validation_options : item.domain_name => {
      name   = item.resource_record_name
      record = item.resource_record_value
      type   = item.resource_record_type
    }
  }

  allow_overwrite = true
  name            = each.value.name
  records         = [each.value.record]
  ttl             = 60
  type            = each.value.type
  zone_id         = data.aws_route53_zone.main.zone_id
}

resource "aws_route53_record" "cert_validation_backend" {
  for_each = {
    for item in aws_acm_certificate.cert_backend.domain_validation_options : item.domain_name => {
      name   = item.resource_record_name
      record = item.resource_record_value
      type   = item.resource_record_type
    }
  }
  allow_overwrite = true
  name            = each.value.name
  records         = [each.value.record]
  ttl             = 60
  type            = each.value.type
  zone_id         = data.aws_route53_zone.main.zone_id
}

resource "aws_route53_record" "ses_dkim_records" {
  count   = 3
  zone_id = data.aws_route53_zone.main.zone_id
  # DKIMトークン（3つ）をループしてレコードを作成
  name    = "${aws_ses_domain_dkim.main.dkim_tokens[count.index]}._domainkey.${var.sub_frontend_domain_name}"
  type    = "CNAME"
  ttl     = "600"
  records = ["${aws_ses_domain_dkim.main.dkim_tokens[count.index]}.dkim.amazonses.com"]
}

resource "aws_route53_record" "ses_dmarc_record" {
  zone_id = data.aws_route53_zone.main.zone_id
  name    = "_dmarc.${var.sub_frontend_domain_name}"
  type    = "TXT"
  ttl     = "600"
  records = ["v=DMARC1; p=none;"]
}

# カスタムMAIL FROMドメイン用のMXレコード
# SESがこのドメインからメールを送信できるようにする
resource "aws_route53_record" "ses_mail_from_mx" {
  zone_id = data.aws_route53_zone.main.zone_id
  name    = aws_ses_domain_mail_from.main.mail_from_domain
  type    = "MX"
  ttl     = "600"
  records = ["10 feedback-smtp.ap-northeast-1.amazonses.com"]
}

# カスタムMAIL FROMドメイン用のSPFレコード
# このドメインからのメール送信をSESに許可する
resource "aws_route53_record" "ses_mail_from_spf" {
  zone_id = data.aws_route53_zone.main.zone_id
  name    = aws_ses_domain_mail_from.main.mail_from_domain
  type    = "TXT"
  ttl     = "600"
  records = ["v=spf1 include:amazonses.com ~all"]
}
