resource "aws_lambda_function" "notification_function" {
  function_name = "${var.project_name}-notifications-email"
  role          = module.notification_lambda_role.arn

  runtime          = "python3.11"
  handler          = "lambda_function.lambda_handler"
  filename         = data.archive_file.lambda_zip.output_path
  source_code_hash = data.archive_file.lambda_zip.output_base64sha256

  timeout     = 30
  memory_size = 128

  environment {
    variables = {
      PROJECT_NAME     = var.project_name
      APP_ENV          = var.app_env
      ALERT_EMAIL_TO   = data.aws_ssm_parameter.alert_email_to.value
      ALERT_EMAIL_FROM = "noreply@${var.sub_frontend_domain_name}.${var.domain_name}",
    }
  }
  lifecycle {
    ignore_changes = [filename, source_code_hash]
  }
}

resource "local_file" "lambda_py" {
  filename = "${path.module}/.tmp/lambda_function.py"
  content  = <<PY
import base64
import gzip
import json
import os
import urllib.request
from datetime import datetime
from zoneinfo import ZoneInfo

import boto3
from botocore.exceptions import ClientError

JST = ZoneInfo("Asia/Tokyo")

SES_REGION = os.getenv("SES_REGION", "ap-northeast-1")
ses = boto3.client("ses", region_name=SES_REGION)

def convert_to_jst_from_epoch_ms(epoch_ms: int) -> str:
    dt_utc = datetime.fromtimestamp(epoch_ms / 1000, tz=ZoneInfo("UTC"))
    dt_jst = dt_utc.astimezone(JST)
    return dt_jst.strftime("%Y-%m-%d %H:%M:%S")

def send_email(subject: str, body: str) -> None:
    """
    ALERT_EMAIL_TO が設定されているときだけ SES でメール送信する
    """
    to_raw = os.getenv("ALERT_EMAIL_TO")
    if not to_raw:
        return  # 宛先未設定なら何もしない（既存挙動を変えない）

    from_addr = os.environ["ALERT_EMAIL_FROM"]  # SESで検証済みが必要

    #ALERT_EMAIL_TOにtest@example.com,test2@example.com,test3@example.com というようにカンマ区切りで複数のメールアドレスが設定されている場合は、それぞれのメールアドレスにメールを送信する
    to_addrs = [a.strip() for a in to_raw.split(",") if a.strip()]

    try:
        ses.send_email(
            Source=from_addr,
            Destination={"ToAddresses": to_addrs},
            Message={
                "Subject": {"Data": subject, "Charset": "UTF-8"},
                "Body": {"Text": {"Data": body, "Charset": "UTF-8"}},
            },
        )
    except ClientError as e:
        # 失敗しても Lambda 全体を落としたくないなら握る（必要なら raise に変えてOK）
        print("メール送信失敗:", e.response.get("Error", {}))

def _decode_cwlogs_event(event) -> dict:
    """
    CloudWatch Logs subscription filter -> Lambda の event を展開して dict にする
    """
    compressed = base64.b64decode(event["awslogs"]["data"])
    decompressed = gzip.decompress(compressed)
    return json.loads(decompressed.decode("utf-8"))

def _prettify_json(text: str) -> str:
    """
    文字列がJSON（またはJSONを含む行）であれば整形して返す
    """
    try:
        obj = json.loads(text)
        return json.dumps(obj, indent=2, ensure_ascii=False)
    except (json.JSONDecodeError, TypeError):
        pass

    # JSON が行の途中に埋め込まれているケース (例: "prefix {"key":"value"}")
    idx = text.find("{")
    if idx > 0:
        prefix = text[:idx]
        json_part = text[idx:]
        try:
            obj = json.loads(json_part)
            return prefix + "\n" + json.dumps(obj, indent=2, ensure_ascii=False)
        except (json.JSONDecodeError, TypeError):
            pass

    return text

def _detect_error_type(messages: list[str], app_env: str) -> str:
    joined = "\n".join(messages)
    if f"{app_env}.CRITICAL" in joined:
        return "クリティカルエラー"
    if f"{app_env}.ERROR" in joined:
        return "標準エラー"
    return "その他のエラー"

def lambda_handler(event, context):
    project_name = os.environ["PROJECT_NAME"]
    app_env = os.environ["APP_ENV"]

    data = _decode_cwlogs_event(event)

    log_group = data.get("logGroup", f"/ecs/{project_name}")
    log_stream = data.get("logStream", "")
    log_events = data.get("logEvents", [])

    messages = []
    last_ts = None
    for e in log_events:
        msg = (e.get("message") or "").rstrip()
        if msg:
            messages.append(_prettify_json(msg))
        last_ts = e.get("timestamp", last_ts)

    if not messages:
        return {"statusCode": 200, "body": json.dumps("no logs", ensure_ascii=False)}

    error_type = _detect_error_type(messages, app_env)
    occurred_at = convert_to_jst_from_epoch_ms(last_ts or int(datetime.now(tz=ZoneInfo("UTC")).timestamp() * 1000))

    error_text = "\n".join(messages)
    max_chars = 3500
    if len(error_text) > max_chars:
        error_text = error_text[:max_chars] + "\n...(以下省略)"

    description = f"{app_env}環境でエラーが発生しました。\nエラー文:\n{error_text}"

    formatted_message = f"""【practice-laravel-deploy - エラー報告】
エラーの種類: {error_type}
発生時間: {occurred_at}
対応: エラーの確認及び、対応をお願いいたします。
-----------------------------------------------------
【内容】
Description: {description}
Log Group: {log_group}
Log Stream: {log_stream}
"""

    # メールで送信（宛先が設定されている時だけ）
    mail_subject = f"【practice-laravel-deploy - エラー報告】{app_env} / {error_type} / {occurred_at}"
    send_email(mail_subject, formatted_message)

    return {"statusCode": 200, "body": json.dumps("通知完了", ensure_ascii=False)}

PY
}

# LambdaがCloudWatch Logsから呼び出せるようにする
resource "aws_lambda_permission" "allow_cloudwatch_logs_invoke" {
  statement_id  = "AllowExecutionFromCloudWatchLogs"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.notification_function.function_name
  principal     = "logs.ap-northeast-1.amazonaws.com"
  source_arn    = "${aws_cloudwatch_log_group.ecs_log.arn}:*"
}
