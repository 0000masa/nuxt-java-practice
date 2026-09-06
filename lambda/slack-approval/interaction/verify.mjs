// Slack からのリクエストが本物か確かめる。
//
// 【この関数が唯一の防御】
// Function URL は AuthType: NONE で誰でも叩ける。ここが素通りすると
// 「デプロイの承認を誰でも実行できる」状態になる。
// しかも壊れても気づけない(常に true を返しても Slack からの通信は通るので
// 正常に見える)ため、test/verify.test.mjs で固定してある。
//
// 手順 → https://api.slack.com/authentication/verifying-requests-from-slack
import { createHmac, timingSafeEqual } from "node:crypto";

// リプレイ攻撃の窓。Slack の推奨値
const MAX_SKEW_SECONDS = 60 * 5;

/**
 * @param {object} args
 * @param {string} args.signingSecret Slack App の Signing Secret
 * @param {string} args.timestamp     X-Slack-Request-Timestamp ヘッダー
 * @param {string} args.signature     X-Slack-Signature ヘッダー
 * @param {string} args.rawBody       本文。**パースする前の生の文字列**
 * @param {number} [args.nowSeconds]  テストから時刻を差し込むため
 * @returns {boolean}
 */
export function isValidSlackRequest({
  signingSecret,
  timestamp,
  signature,
  rawBody,
  nowSeconds = Math.floor(Date.now() / 1000),
}) {
  if (!signingSecret || !timestamp || !signature || typeof rawBody !== "string") {
    return false;
  }

  // 数値でない timestamp を Number() に通すと NaN になり、下の比較が常に false
  // = 素通りではなく拒否になるが、意図を明示しておく
  const sent = Number(timestamp);
  if (!Number.isFinite(sent)) return false;
  if (Math.abs(nowSeconds - sent) > MAX_SKEW_SECONDS) return false;

  // 【生ボディを使う】
  // JSON.parse してから stringify し直すとキーの順序や空白が変わり、
  // 署名が一致しなくなる。Slack が送ってきたバイト列そのままで計算する
  const expected =
    "v0=" +
    createHmac("sha256", signingSecret)
      .update(`v0:${timestamp}:${rawBody}`)
      .digest("hex");

  // 【長さが違うと timingSafeEqual は例外を投げる】
  // 先に長さを見るのは、例外で落ちるのを避けるため。
  // 長さの違いは漏れるが、署名の長さは固定なので情報量が無い
  const a = Buffer.from(expected, "utf8");
  const b = Buffer.from(signature, "utf8");
  if (a.length !== b.length) return false;

  return timingSafeEqual(a, b);
}
