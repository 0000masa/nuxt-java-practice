# CloudFront → ALB のカスタムヘッダー検証用シークレット
resource "random_password" "cf_secret" {
  length  = 32
  special = false
}

# CloudFront用 WAF Web ACL (us-east-1 必須)
resource "aws_wafv2_web_acl" "cloudfront_waf" {
  provider = aws.us_east_1
  name     = "${var.project_name}-cloudfront-waf"
  scope    = "CLOUDFRONT"

  default_action {
    allow {}
  }

  rule {
    name     = "AWS-AWSManagedRulesCommonRuleSet"
    priority = 1

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesCommonRuleSet"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.project_name}-common-rule-set"
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "${var.project_name}-cloudfront-waf"
    sampled_requests_enabled   = true
  }
}
