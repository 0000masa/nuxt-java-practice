// 署名検証のテスト。
//
// 【なぜここだけテストするのか】
// 署名検証は壊れても正常に見える(常に true を返しても Slack からの通信は通る)。
// メッセージの見た目は Slack を見れば分かるが、これは見て分からない。
// → docs/test/README.md「Lambda のテスト」
//
// 実行: node --test lambda/slack-approval/test/
import { test } from "node:test";
import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import { isValidSlackRequest } from "../interaction/verify.mjs";

const SECRET = "test-signing-secret";
const NOW = 1_757_000_000;
const BODY = "payload=%7B%22type%22%3A%22block_actions%22%7D";

function sign(timestamp, rawBody, secret = SECRET) {
  return (
    "v0=" +
    createHmac("sha256", secret).update(`v0:${timestamp}:${rawBody}`).digest("hex")
  );
}

function call(overrides = {}) {
  const timestamp = String(NOW);
  return isValidSlackRequest({
    signingSecret: SECRET,
    timestamp,
    signature: sign(timestamp, BODY),
    rawBody: BODY,
    nowSeconds: NOW,
    ...overrides,
  });
}

test("正しい署名なら通る", () => {
  assert.equal(call(), true);
});

test("署名が 1 文字違えば落ちる", () => {
  const timestamp = String(NOW);
  const valid = sign(timestamp, BODY);
  const tampered = valid.slice(0, -1) + (valid.at(-1) === "0" ? "1" : "0");
  assert.equal(call({ signature: tampered }), false);
});

test("別の signing secret で署名されていれば落ちる", () => {
  const timestamp = String(NOW);
  assert.equal(call({ signature: sign(timestamp, BODY, "attacker-secret") }), false);
});

test("本文が改竄されていれば落ちる(署名は正しいまま)", () => {
  assert.equal(call({ rawBody: BODY + "&status=Approved" }), false);
});

// リプレイ攻撃対策。過去に流れた正規のリクエストをそのまま投げ直されても通さない
test("5 分より古ければ落ちる", () => {
  assert.equal(call({ nowSeconds: NOW + 301 }), false);
});

test("5 分ちょうどなら通る", () => {
  assert.equal(call({ nowSeconds: NOW + 300 }), true);
});

// 時計が進んでいる送信元も同じ窓で弾く
test("未来に 5 分より進んでいれば落ちる", () => {
  assert.equal(call({ nowSeconds: NOW - 301 }), false);
});

test("timestamp が数値でなければ落ちる", () => {
  assert.equal(call({ timestamp: "not-a-number" }), false);
});

// ヘッダーが無いリクエストで例外を投げず false を返すこと。
// 投げると Function URL が 500 を返し、原因が分かりにくくなる
test("必要な値が欠けていれば落ちる", () => {
  assert.equal(call({ signature: undefined }), false);
  assert.equal(call({ timestamp: undefined }), false);
  assert.equal(call({ rawBody: undefined }), false);
  assert.equal(call({ signingSecret: "" }), false);
});

// 長さが違うと timingSafeEqual は例外を投げる。先に長さを見ている
test("署名の長さが違っても例外にならない", () => {
  assert.equal(call({ signature: "v0=short" }), false);
});
