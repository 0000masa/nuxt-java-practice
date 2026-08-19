resource "aws_ses_domain_identity" "main" {
  domain = "${var.sub_frontend_domain_name}.${var.domain_name}"
}

resource "aws_ses_domain_dkim" "main" {
  domain = "${var.sub_frontend_domain_name}.${var.domain_name}"
}

# カスタムMAIL FROMドメインの設定
# バウンスメールの送信元を独自ドメインにすることで、メールの信頼性を向上させる
resource "aws_ses_domain_mail_from" "main" {
  domain                 = aws_ses_domain_identity.main.domain
  mail_from_domain       = "mail.${var.sub_frontend_domain_name}.${var.domain_name}"
  behavior_on_mx_failure = "UseDefaultValue"
}
