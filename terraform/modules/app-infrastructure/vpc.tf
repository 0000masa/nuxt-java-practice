module "vpc" {
  source = "terraform-aws-modules/vpc/aws"

  name = "${var.project_name}-vpc"

  # ============================================================
  # プライベートIPアドレス範囲 (RFC 1918) は3種類ある
  #
  # 1. 10.0.0.0/8      (大規模: 約1,677万IP)
  # 2. 172.16.0.0/12    (中規模: 約104万IP)
  # 3. 192.168.0.0/16   (小規模: 約6.5万IP)
  # ============================================================

  # --- 大規模: 10.0.0.0/8 の場合 ---
  # cidr = "10.0.0.0/16"
  # public_subnets  = ["10.0.4.0/24", "10.0.3.0/24"]
  # private_subnets = ["10.0.1.0/24", "10.0.2.0/24"]

  # --- 中規模: 172.16.0.0/12 の場合 ---
  # cidr = "172.16.0.0/16"
  # public_subnets  = ["172.16.4.0/24", "172.16.3.0/24"]
  # private_subnets = ["172.16.1.0/24", "172.16.2.0/24"]

  # --- 小規模: 192.168.0.0/16 の場合 (現在使用中) ---
  cidr = "192.168.0.0/20"

  azs             = ["ap-northeast-1a", "ap-northeast-1c"]
  public_subnets  = ["192.168.4.0/24", "192.168.3.0/24"]
  private_subnets = ["192.168.1.0/24", "192.168.2.0/24"]

  map_public_ip_on_launch = true

  enable_dns_hostnames = true
  enable_dns_support   = true

  enable_nat_gateway     = var.enable_nat_gateway
  single_nat_gateway     = true
  one_nat_gateway_per_az = false

  tags = {
    Name = "${var.project_name}-vpc"
  }
}

locals {
  public_subnet_a_id  = module.vpc.public_subnets[0]
  public_subnet_c_id  = module.vpc.public_subnets[1]
  private_subnet_a_id = module.vpc.private_subnets[0]
  private_subnet_c_id = module.vpc.private_subnets[1]
}

resource "aws_vpc_endpoint" "s3_gateway" {
  vpc_id            = module.vpc.vpc_id
  service_name      = "com.amazonaws.ap-northeast-1.s3"
  vpc_endpoint_type = "Gateway"

  route_table_ids = module.vpc.private_route_table_ids

  tags = {
    Name = "${var.project_name}-s3-endpoint"
  }
}
