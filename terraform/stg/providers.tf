terraform {
  #1.14.3以上、1.15.0未満（= 1.14系の範囲で固定）
  required_version = "~> 1.14.3"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.6"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }
  backend "s3" {
    bucket = "github-action-terraform-tf-state-bucket" # 直接名前を書く
    key    = "practice/laravel/stg/terraform.tfstate"  # 直接パスを書く
    region = "ap-northeast-1"
    # DynamoDBの代わりにこれを使用
    use_lockfile = true
  }
}

provider "aws" {
  region = "ap-northeast-1"
}

# CloudFrontの証明書用 (バージニア北部)
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"
}