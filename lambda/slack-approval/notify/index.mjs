// パイプラインの通知を Slack に投稿する(フェーズ17)。
//
// 方針 → docs/adr/0014-slack-approval-with-lambda.md
//
// 【この関数は AWS をほとんど触らない】
// 承認トークンは押された時点で interaction 側が取りに行くので、ここでは要らない。
// 唯一叩くのが GetPipelineExecution で、「どのコミットを承認しようとしているのか」を
// メッセージに出すため(コミット SHA とコミットメッセージが取れる)。
//
// 【SNS のメッセージ形式は CodeStarNotifications のもの】
// event.Records[].Sns.Message に JSON 文字列が入っている。
// 形が想定と違ったときに落とさず、生の内容をログに出して汎用メッセージを投げる。
import {
  CodePipelineClient,
  GetPipelineExecutionCommand,
} from "@aws-sdk/client-codepipeline";
import { SSMClient, GetParameterCommand } from "@aws-sdk/client-ssm";

const codepipeline = new CodePipelineClient({});
const ssm = new SSMClient({});

// コールドスタート時だけ SSM を叩く。以降は使い回す
let webhookUrl;
async function getWebhookUrl() {
  if (webhookUrl) return webhookUrl;
  const res = await ssm.send(
    new GetParameterCommand({
      Name: process.env.SLACK_WEBHOOK_URL_PARAM,
      WithDecryption: true,
    })
  );
  webhookUrl = res.Parameter.Value;
  return webhookUrl;
}

export async function handler(event) {
  for (const record of event.Records ?? []) {
    // 形が変わったときに追えるよう、まず生のまま残す
    console.log("SNS message:", record.Sns?.Message);
    let notification;
    try {
      notification = JSON.parse(record.Sns.Message);
    } catch {
      console.error("JSON として読めなかったので素通りする");
      continue;
    }
    const message = await buildMessage(notification);
    // 何も出さない通知がある(→ buildMessage)
    if (message) await post(message);
  }
  return { ok: true };
}

// 承認待ちかどうかの判定。
// detail.type.category が Approval で、状態が STARTED のとき。
// 【実機で確認済み(2026-09-07)】
// アクション単位の通知(detailType: "CodePipeline Action Execution State Change")には
// detail.type が付き、実行単位の通知には付かない。この差でも 2 つを見分けられる。
function isApprovalRequest(detail) {
  return (
    detail?.type?.category === "Approval" &&
    String(detail?.state).toUpperCase() === "STARTED"
  );
}

// 【却下は「失敗」ではないのに FAILED で飛んでくる】
// CodePipeline の手動承認に「却下」という終了状態は無く、却下するとアクションが失敗し、
// ステージが失敗し、実行が FAILED になる。本物の失敗と区別できるのは summary の文字列だけ。
// 実機で確認した形(2026-09-07):
//   アクション単位: detail["execution-result"]["external-execution-summary"]
//   実行単位:       additionalAttributes.failedActions[].additionalInformation
// どちらにも PutApprovalResult に渡した summary("Rejected by @<誰> via Slack")が入る。
function findRejection(notification, detail) {
  if (String(detail?.state).toUpperCase() !== "FAILED") return null;

  const isRejected = (s) => String(s ?? "").startsWith("Rejected by");

  // アクション単位の通知(detail.type を持つのはこちらだけ)
  if (detail.type) {
    if (detail.type.category !== "Approval") return null;
    const summary = detail["execution-result"]?.["external-execution-summary"];
    return isRejected(summary) ? summary : null;
  }

  // 実行単位の通知。失敗したアクションに承認の却下が混ざっているか
  const failed = notification.additionalAttributes?.failedActions ?? [];
  return failed.find((a) => isRejected(a.additionalInformation))
    ?.additionalInformation ?? null;
}

async function buildMessage(notification) {
  const detail = notification.detail ?? {};
  const pipeline = detail.pipeline ?? "(不明なパイプライン)";
  const region = notification.region ?? process.env.AWS_REGION;
  const consoleUrl =
    `https://${region}.console.aws.amazon.com/codesuite/codepipeline/pipelines/` +
    `${pipeline}/view?region=${region}`;

  if (isApprovalRequest(detail)) {
    const revision = await getRevision(pipeline, detail["execution-id"]);
    return approvalBlocks({ pipeline, detail, revision, consoleUrl });
  }

  // 【却下 1 回で 3 回言わないようにする】
  // 却下すると (1) 押した人のメッセージが interaction に差し替えられ、
  // (2) アクション単位の FAILED、(3) 実行単位の FAILED が飛んでくる。
  // 素通しすると :x: が 2 つ並び、承認者からは事故に見える。
  // アクション単位のほうを捨て、実行単位の 1 通だけを「却下」として出す
  // (コンソールから却下された場合は (1) が無いので、この 1 通が唯一の記録になる)。
  const rejection = findRejection(notification, detail);
  if (rejection) {
    if (detail.type) return null;
    return rejectionBlocks({ pipeline, rejection, consoleUrl });
  }

  return resultBlocks({ notification, detail, pipeline, consoleUrl });
}

// 「どのコミットを承認するのか」を出すために実行を 1 回だけ読む。
// 取れなくてもメッセージは出す(承認そのものは止めない)
async function getRevision(pipelineName, executionId) {
  if (!executionId) return null;
  try {
    const res = await codepipeline.send(
      new GetPipelineExecutionCommand({
        pipelineName,
        pipelineExecutionId: executionId,
      })
    );
    const revision = res.pipelineExecution?.artifactRevisions?.[0];
    if (!revision) return null;
    return {
      id: (revision.revisionId ?? "").slice(0, 7),
      // revisionSummary は CodeStarSourceConnection では JSON 文字列で入ってくる
      summary: firstLine(parseRevisionSummary(revision.revisionSummary)),
    };
  } catch (e) {
    console.error("GetPipelineExecution に失敗した(メッセージは続行する):", e);
    return null;
  }
}

function parseRevisionSummary(summary) {
  if (!summary) return "";
  try {
    return JSON.parse(summary).CommitMessage ?? summary;
  } catch {
    return summary;
  }
}

const firstLine = (text) => String(text).split("\n")[0].slice(0, 200);

function approvalBlocks({ pipeline, detail, revision, consoleUrl }) {
  // ボタンに載せる情報。押された側はこれだけで put-approval-result を組み立てられる
  const target = JSON.stringify({
    pipeline,
    stage: detail.stage,
    action: detail.action,
  });

  const lines = [`*${pipeline}* のデプロイ承認をお願いします。`];
  if (revision?.id) lines.push(`コミット: \`${revision.id}\``);
  if (revision?.summary) lines.push(`> ${revision.summary}`);

  return {
    text: `${pipeline} のデプロイ承認をお願いします`, // 通知バナー用
    blocks: [
      { type: "section", text: { type: "mrkdwn", text: lines.join("\n") } },
      {
        type: "actions",
        elements: [
          {
            type: "button",
            style: "primary",
            text: { type: "plain_text", text: "承認" },
            action_id: "approve",
            value: target,
            // 押し間違いが本番デプロイに直結するので、承認だけ確認を挟む
            confirm: {
              title: { type: "plain_text", text: "デプロイしますか？" },
              text: { type: "mrkdwn", text: "承認すると本番のリスナーが切り替わります。" },
              confirm: { type: "plain_text", text: "承認する" },
              deny: { type: "plain_text", text: "やめる" },
            },
          },
          {
            type: "button",
            style: "danger",
            text: { type: "plain_text", text: "却下" },
            action_id: "reject",
            value: target,
          },
        ],
      },
      {
        type: "context",
        elements: [{ type: "mrkdwn", text: `<${consoleUrl}|コンソールで開く>` }],
      },
    ],
  };
}

// 却下されて終わったとき。失敗と同じ :x: にしないための専用カード
function rejectionBlocks({ pipeline, rejection, consoleUrl }) {
  const lines = [
    `:no_entry: *${pipeline}* — 却下により中止しました`,
    // PutApprovalResult に渡した summary をそのまま出す。
    // 「誰が却下したか」はこの文字列にしか残っていない
    `> ${rejection}`,
    "デプロイは行われていません。",
  ];

  return {
    text: `${pipeline}: 却下により中止`,
    blocks: [
      { type: "section", text: { type: "mrkdwn", text: lines.join("\n") } },
      {
        type: "context",
        elements: [{ type: "mrkdwn", text: `<${consoleUrl}|コンソールで開く>` }],
      },
    ],
  };
}

// 承認待ち以外(成功・失敗・承認の結果)。ボタンは付けない
function resultBlocks({ notification, detail, pipeline, consoleUrl }) {
  const state = String(detail.state ?? "").toUpperCase();
  const icon = { SUCCEEDED: ":white_check_mark:", FAILED: ":x:" }[state] ?? ":information_source:";
  const where = [detail.stage, detail.action].filter(Boolean).join(" / ");

  const lines = [`${icon} *${pipeline}* — ${notification.detailType ?? state}`];
  if (where) lines.push(`ステージ: ${where}`);
  if (state) lines.push(`状態: \`${state}\``);

  return {
    text: `${pipeline}: ${state || "通知"}`,
    blocks: [
      { type: "section", text: { type: "mrkdwn", text: lines.join("\n") } },
      {
        type: "context",
        elements: [{ type: "mrkdwn", text: `<${consoleUrl}|コンソールで開く>` }],
      },
    ],
  };
}

async function post(body) {
  const res = await fetch(await getWebhookUrl(), {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  // Incoming Webhook は成功で "ok" を返す。失敗理由は本文に入る
  if (!res.ok) {
    throw new Error(`Slack への投稿に失敗した: ${res.status} ${await res.text()}`);
  }
}
