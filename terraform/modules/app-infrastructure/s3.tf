#フロントエンドのバケット
resource "aws_s3_bucket" "frontend_bucket" {
  bucket = "${var.project_name}-frontend-bucket"

  tags = {
    Name = "${var.project_name}-frontend-bucket"
  }
  force_destroy = var.s3_force_destroy
}

#QR画像を保存するバケット
resource "aws_s3_bucket" "image_bucket" {
  bucket = "${var.project_name}-images-bucket"

  tags = {
    Name = "${var.project_name}-images-bucket"
  }
  force_destroy = var.s3_force_destroy
}

# S3バケットポリシー
# AWSコンソールではCloudFrontディストリビューション作成時にS3オリジンとOACを指定すると、
# このバケットポリシーは自動で追加されるため、手動で個別に作成する必要はない。
resource "aws_s3_bucket_policy" "bucket_policy" {
  bucket = aws_s3_bucket.image_bucket.id

  # dataソースを使わず、ここで直接JSONを定義・エンコードする
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "AllowCloudFrontServicePrincipal"
        Effect = "Allow"
        Principal = {
          Service = "cloudfront.amazonaws.com"
        }
        Action   = "s3:GetObject"
        Resource = "${aws_s3_bucket.image_bucket.arn}/*"
        Condition = {
          StringEquals = {
            "AWS:SourceArn" = aws_cloudfront_distribution.image_cdn.arn
          }
        }
      }
    ]
  })
}

# AWSコンソールではCloudFrontディストリビューション作成時にS3オリジンとOACを指定すると、
# このバケットポリシーは自動で追加されるため、手動で個別に作成する必要はない。
resource "aws_s3_bucket_policy" "frontend" {
  bucket = aws_s3_bucket.frontend_bucket.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "AllowCloudFrontServicePrincipal"
        Effect = "Allow"
        Principal = {
          Service = "cloudfront.amazonaws.com"
        }
        Action   = "s3:GetObject"
        Resource = "${aws_s3_bucket.frontend_bucket.arn}/*"
        Condition = {
          StringEquals = {
            "AWS:SourceArn" = aws_cloudfront_distribution.frontend_cdn.arn
          }
        }
      }
    ]
  })
}

# ---------------------------------------------------------------------
# ログアーカイブ用バケット
# CloudWatch Logs を Firehose 経由で長期保管（監査・コンプライアンス目的）。
# 設計: docs/monitoring/cloudwatch-logs-s3-archival.md / 方式判断: ADR 0011
# ---------------------------------------------------------------------
resource "aws_s3_bucket" "logs_archive" {
  bucket = "${var.project_name}-logs-archive-bucket"

  tags = {
    Name = "${var.project_name}-logs-archive-bucket"
  }
  # stg は true（破棄しやすく）、prod は default false（監査ログを誤削除から守る）。
  force_destroy = var.s3_force_destroy
}

# 監査ログなので公開は全面ブロック
resource "aws_s3_bucket_public_access_block" "logs_archive" {
  bucket                  = aws_s3_bucket.logs_archive.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# 暗号化（SSE-S3 / AES256）。挙動は S3 デフォルトと同じだが、監査バケットなので意図を明示する。
# prod で CMK による鍵利用監査が必要になったら aws:kms に切り替える。
resource "aws_s3_bucket_server_side_encryption_configuration" "logs_archive" {
  bucket = aws_s3_bucket.logs_archive.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# ライフサイクル（2ルール）
#  ① 128KB超のみ 30日後に Glacier IR へ移行（小オブジェクトは移行課金の無駄を避け Standard 据え置き）
#  ② 全件 365日後に削除
# 詳細・選定理由は設計ドキュメント参照。
resource "aws_s3_bucket_lifecycle_configuration" "logs_archive" {
  bucket = aws_s3_bucket.logs_archive.id

  rule {
    id     = "transition-large-to-glacier-ir"
    status = "Enabled"

    filter {
      object_size_greater_than = 131072 # 128KB超のみ移行対象
    }

    transition {
      days          = 30
      storage_class = "GLACIER_IR"
    }
  }

  rule {
    id     = "expire-all"
    status = "Enabled"

    filter {} # サイズ問わず全オブジェクト

    expiration {
      days = 365
    }
  }
}
