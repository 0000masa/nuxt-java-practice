# ALB用: インターネットからのHTTP(80)を許可
resource "aws_security_group" "alb_sg" {
  name   = "${var.project_name}-alb-sg"
  vpc_id = module.vpc.vpc_id

  # HTTP (80)
  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # HTTPS (443) - ここがメインの通信
  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

#ECS用SG
resource "aws_security_group" "ecs_sg" {
  name        = "${var.project_name}-ecs-sg"
  description = "${var.project_name}-ecs-sg"
  vpc_id      = module.vpc.vpc_id

  ingress {
    from_port       = 80
    to_port         = 80
    protocol        = "tcp"
    security_groups = [aws_security_group.alb_sg.id]
  }

  # 送信ルール (egress)
  egress {
    description = "Allow outbound to Backend APIs"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.project_name}-ecs-sg"
  }
}

# RDS用SG
resource "aws_security_group" "rds_sg" {
  name        = "${var.project_name}-rds-sg"
  description = "${var.project_name}-rds-sg"
  vpc_id      = module.vpc.vpc_id

  ingress {
    description     = "from ecs-sg(${var.project_name}-ecs-sg)"
    from_port       = 3306
    to_port         = 3306
    protocol        = "tcp"
    security_groups = [aws_security_group.ecs_sg.id]
  }

  tags = { Name = "${var.project_name}-rds-sg" }
}
