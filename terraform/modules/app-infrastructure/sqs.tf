# ==============================================================================
# SQS キュー + ECS Worker サービス（QRコード非同期生成）
# SQS キューからジョブを取得し、QRコード生成・S3保存・DB更新を行う
# ==============================================================================

# --- SQS キュー ---
resource "aws_sqs_queue" "qrcode_generation" {
  name                       = "${var.app_env}-qrcode-generation"
  visibility_timeout_seconds = 90     #ワーカーがメッセージを受け取った直後、SQS はそのメッセージを一時的に "見えない状態" にする時間。
  message_retention_seconds  = 345600 # 4日 キューに入ったメッセージを、最大でどれだけ残すか。
  receive_wait_time_seconds  = 20     # ロングポーリング ワーカーが「メッセージある？」と取りに行くとき、最大 20秒待つ設定。

  tags = {
    Name = "${var.project_name}-qrcode-generation"
  }
}
