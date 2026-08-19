# =============================================================================
# モジュール用 providers.tf
# =============================================================================
# このファイルは stg/providers.tf とは役割が異なる。
#
# - stg/providers.tf:
#     provider ブロック（リージョン指定）と backend ブロック（state保存先）を「定義する側」
#
# - modules/app-infrastructure/providers.tf（このファイル）:
#     モジュールが「aws と aws.us_east_1 の2つの provider を受け取る」と「宣言する側」
#     （configuration_aliases）
#
# モジュール内の acm.tf と waf.tf で provider = aws.us_east_1 を指定しているため、
# この宣言がないとエラーになる。
# =============================================================================

terraform {
  required_providers {
    aws = {
      source                = "hashicorp/aws"
      version               = "~> 6.6"
      configuration_aliases = [aws.us_east_1]
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }
}
