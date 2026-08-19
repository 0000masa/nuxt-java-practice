resource "aws_lb" "main" {
  name               = "${var.project_name}-alb"
  load_balancer_type = "application" //指定しなくてもデフォルトでALB（application）になる
  subnets            = [local.public_subnet_a_id, local.public_subnet_c_id]
  security_groups    = [aws_security_group.alb_sg.id]
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect" # 転送ではなくリダイレクト

    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.main.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-Res-PQ-2025-09" #書かなくても大丈夫だけど明示する

  # 【修正】変数ではなく、acm.tfで作ったリソース（検証完了後のもの）を参照する
  certificate_arn = aws_acm_certificate_validation.cert_backend.certificate_arn

  # デフォルトアクション: CloudFront経由でないリクエストは403で拒否
  default_action {
    type = "fixed-response"
    fixed_response {
      content_type = "text/plain"
      message_body = "Forbidden"
      status_code  = "403"
    }
  }
}

resource "aws_lb_target_group" "slot_a" {
  name        = "${var.project_name}-tg-a"
  port        = 80
  protocol    = "HTTP"
  vpc_id      = module.vpc.vpc_id
  target_type = "ip"

  health_check {
    path    = "/api/health"
    matcher = "200"
  }
}

resource "aws_lb_target_group" "slot_b" {
  name        = "${var.project_name}-tg-b"
  port        = 80
  protocol    = "HTTP"
  vpc_id      = module.vpc.vpc_id
  target_type = "ip"

  health_check {
    path    = "/api/health"
    matcher = "200"
  }
}

# --- Test rule (任意): ヘッダ付きだけ「テストトラフィック」として扱う ---
resource "aws_lb_listener_rule" "ecs_test" {
  listener_arn = aws_lb_listener.https.arn
  priority     = 10

  # CloudFront経由のみ許可
  #「どのURLから来たか（= CloudFront 経由の https://www.../api か、直叩きの https://api.../api か）
  #を見ている」のではなく、リクエストに付いている
  #HTTP ヘッダー X-CloudFront-Secret の値が一致するかを条件
  condition {
    http_header {
      http_header_name = "X-CloudFront-Secret"
      values           = [random_password.cf_secret.result]
    }
  }

  condition {
    http_header {
      http_header_name = "X-Environment"
      values           = ["test"]
    }
  }

  action {
    type = "forward"
    forward {
      target_group {
        arn    = aws_lb_target_group.slot_a.arn
        weight = 1
      }
      target_group {
        arn    = aws_lb_target_group.slot_b.arn
        weight = 0
      }
    }
  }

  # ECS がデプロイ中に weight を書き換えるので Terraform は追従しない
  lifecycle {
    ignore_changes = [action]
  }
}


# --- Production rule: すべてのパスを weight 1/0 で forward（ECS が切替時に weight を入れ替える）---
resource "aws_lb_listener_rule" "ecs_production" {
  listener_arn = aws_lb_listener.https.arn
  priority     = 100

  # CloudFront経由のみ許可
  condition {
    http_header {
      http_header_name = "X-CloudFront-Secret"
      values           = [random_password.cf_secret.result]
    }
  }

  # 本番ホスト（www CloudFront 経由 = all_viewer で転送される viewer Host）に限定する。
  # これが無いと「X-CloudFront-Secret + /*」だけで成立し、同じシークレットを共有する
  # preview（pr-<n>.preview...）のリクエストまで priority 100 でこのルールが拾ってしまい、
  # preview 用ルール（priority 20000+n）まで評価が回らず stg 本体へ誤転送される。
  condition {
    host_header {
      values = ["${var.sub_frontend_domain_name}.${var.domain_name}"]
    }
  }

  # パスパターン「/*」は全パスにマッチするため省略可能。
  # 将来的にパスを限定する可能性を考慮して明示的に残している。
  condition {
    path_pattern {
      values = ["/*"]
    }
  }

  action {
    type = "forward"
    forward {
      target_group {
        arn    = aws_lb_target_group.slot_a.arn
        weight = 1
      }
      target_group {
        arn    = aws_lb_target_group.slot_b.arn
        weight = 0
      }
    }
  }

  lifecycle {
    ignore_changes = [action]
  }
}
