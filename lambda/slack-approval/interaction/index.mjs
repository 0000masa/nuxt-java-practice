// Slack のボタン押下を受けて、CodePipeline の承認を確定する(フェーズ17)。
//
// 方針 → docs/adr/0014-slack-approval-with-lambda.md
//
// 【この関数は公開されている】
// Function URL は AuthType: NONE。守りは verify.mjs の署名検証と、
// pipeline.yml の ReservedConcurrentExecutions だけ。
// 署名検証を通る前に AWS を一切叩かないこと。
//
// 【トークンは押された時点で取りに行く】
// 通知に埋め込むと、SUPERSEDED で実行が入れ替わったときに古いトークンを持ったままになる。
// ここで get-pipeline-state を読めば、常に「いま保留中の承認」を対象にできる。
import {
  CodePipelineClient,
  GetPipelineStateCommand,
  PutApprovalResultCommand,
} from "@aws-sdk/client-codepipeline";
import { SSMClient, GetParameterCommand } from "@aws-sdk/client-ssm";
import { isValidSlackRequest } from "./verify.mjs";

const codepipeline = new CodePipelineClient({});
const ssm = new SSMClient({});

let signingSecret;
async function getSigningSecret() {
  if (signingSecret) return signingSecret;
  const res = await ssm.send(
    new GetParameterCommand({
      Name: process.env.SLACK_SIGNING_SECRET_PARAM,
      WithDecryption: true,
    })
  );
  signingSecret = res.Parameter.Value;
  return signingSecret;
}

export async function handler(event) {
  // Function URL はヘッダー名を小文字で渡す
  const headers = event.headers ?? {};
  const rawBody = event.isBase64Encoded
    ? Buffer.from(event.body ?? "", "base64").toString("utf8")
    : (event.body ?? "");

  if (
    !isValidSlackRequest({
      signingSecret: await getSigningSecret(),
      timestamp: headers["x-slack-request-timestamp"],
      signature: headers["x-slack-signature"],
      rawBody,
    })
  ) {
    // 誰が叩いたかは分からないので、内容は残さず 401 だけ返す
    console.warn("署名検証に失敗した");
    return { statusCode: 401, body: "" };
  }

  const payload = parsePayload(rawBody);
  if (payload?.type !== "block_actions") {
    // URL 検証など、想定外の種類は黙って 200 を返す(Slack 側で再送されないように)
    console.log("扱わない種類:", payload?.type);
    return { statusCode: 200, body: "" };
  }

  return await respond(payload.response_url, await approve(payload));
}

function parsePayload(rawBody) {
  try {
    const payload = new URLSearchParams(rawBody).get("payload");
    return payload ? JSON.parse(payload) : null;
  } catch (e) {
    console.error("payload を読めなかった:", e);
    return null;
  }
}

async function approve(payload) {
  const action = payload.actions?.[0];
  const status = { approve: "Approved", reject: "Rejected" }[action?.action_id];
  if (!status) return `:warning: 知らないボタンです (${action?.action_id})`;

  const { pipeline, stage, action: actionName } = JSON.parse(action.value);
  // 誰が押したかを CodePipeline 側にも残す。
  // Lambda 経由になったことで CloudTrail からは追えなくなったため(→ ADR-0014)
  const who = payload.user?.username ?? payload.user?.name ?? payload.user?.id;

  const token = await findToken(pipeline, stage, actionName);
  if (!token) {
    return `:information_source: この承認はすでに終わっています。`;
  }

  try {
    await codepipeline.send(
      new PutApprovalResultCommand({
        pipelineName: pipeline,
        stageName: stage,
        actionName,
        token,
        result: { status, summary: `${status} by @${who} via Slack` },
      })
    );
  } catch (e) {
    console.error("PutApprovalResult に失敗した:", e);
    return `:x: 承認を反映できませんでした: ${e.name}`;
  }

  const label = status === "Approved" ? ":white_check_mark: 承認" : ":no_entry: 却下";
  return `${label}しました — *${pipeline}* / @${who}`;
}

// 保留中の承認だけがトークンを持つ。終わっていれば undefined が返る
async function findToken(pipelineName, stageName, actionName) {
  const state = await codepipeline.send(
    new GetPipelineStateCommand({ name: pipelineName })
  );
  return state.stageStates
    ?.find((s) => s.stageName === stageName)
    ?.actionStates?.find((a) => a.actionName === actionName)
    ?.latestExecution?.token;
}

// 【元のメッセージの差し替えは response_url への POST でしかできない】
// Block Kit(blocks)では HTTP 応答の本文は読まれず、「200 が返ってきた」という
// 合図だけが意味を持つ。Slack の公式ドキュメントにこう書かれている:
//   "With blocks, it is not possible to publish a new message by responding directly
//    to the HTTP request. You will always need to use the response_url for this purpose.
//    The HTTP response may now only be used to send an HTTP 200 acknowledgement response."
//
// 応答本文に replace_original を入れれば差し替わる、というのは attachments 時代
// (legacy interactive messages)の挙動で、blocks では**エラーも出さずに捨てられる**。
// 「却下は CodePipeline に通っているのに Slack のメッセージだけ変わらない」という形で
// 実際に踏んだ。→ docs/notes/aws-code-service/slack-block-kit-response.md
//
// 【1 往復増えるぶん、Slack の 3 秒に近づく】
// Slack は 200 を 3 秒以内に求める。ここに至るまでに get-pipeline-state →
// put-approval-result が挟まり、さらにこの POST が乗るので、コールドスタートでは
// 超えることがある(超えても差し替えは成立し、Slack に警告が出るだけ)。
// 厳密にやるなら「先に 200 を返して続きを別 Lambda で」だが、そこまではしていない。
async function respond(responseUrl, text) {
  // ボタン押下(block_actions)なら必ず付いてくる。無いのは想定外なので記録だけ残す
  if (!responseUrl) {
    console.error("response_url が無いので差し替えられない:", text);
    return { statusCode: 200, body: "" };
  }

  try {
    const res = await fetch(responseUrl, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        // 押されたメッセージをそのまま置き換える。ボタンが消えるので、
        // 古いボタンが残り続ける問題が起きない
        replace_original: true,
        text,
        blocks: [{ type: "section", text: { type: "mrkdwn", text } }],
      }),
    });
    if (!res.ok) {
      console.error(`差し替えに失敗した: ${res.status} ${await res.text()}`);
    }
  } catch (e) {
    console.error("差し替えに失敗した:", e);
  }

  // 【差し替えが失敗しても 200 を返す】
  // 承認そのものはすでに CodePipeline に通っている。ここで 500 を返すと Slack が
  // 再送し、同じボタンの処理がもう一度走る(2 回目はトークンが無いので
  // 「すでに終わっています」になり実害は無いが、ログが紛らわしくなる)
  return { statusCode: 200, body: "" };
}
