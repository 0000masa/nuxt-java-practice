# Block Kit のボタン応答では、メッセージを差し替えられない

フェーズ17 の承認 Lambda で踏んだ。**Slack で「却下」を押すと CodePipeline には
ちゃんと反映されるのに、Slack のメッセージだけ元のまま(ボタンも残ったまま)**という症状。
エラーは何も出ない。

コードは [`lambda/slack-approval/interaction/index.mjs`](../../../lambda/slack-approval/interaction/index.mjs)、
方針は [ADR-0014](../../adr/0014-slack-approval-with-lambda.md)、
設計書は [フェーズ17](../../superpowers/specs/2026-09-06-phase17-slack-approval-design.md)。

---

## 1. 何を間違えたか

ボタンが押されると Slack は Function URL に `block_actions` の payload を POST してくる。
これに対して、**HTTP 応答の本文にメッセージを載せて返していた。**

```js
// ❌ 動かない
return {
  statusCode: 200,
  headers: { "content-type": "application/json" },
  body: JSON.stringify({
    replace_original: true,
    text: ":no_entry: 却下しました",
    blocks: [{ type: "section", text: { type: "mrkdwn", text } }],
  }),
};
```

**`blocks` を使っている場合、この本文は読まれない。** 公式ドキュメントの表現:

> With blocks, it is not possible to publish a new message by responding directly to the
> HTTP request. You will always need to use the `response_url` for this purpose.
> **The HTTP response may now only be used to send an HTTP 200 acknowledgement response.**
>
> — [Handling user interaction in your Slack apps](https://docs.slack.dev/interactivity/handling-user-interaction/)

つまり Slack から見ると「200 が返ってきた」以上の意味を持たない。
**400 も返らず、警告も出ず、黙って捨てられる。** これが原因の切り分けを難しくした。

## 2. なぜ「応答本文で差し替わる」と思い込んだのか

**それは attachments 時代(legacy interactive messages)の挙動だから。**
`attachments` + `actions` でボタンを作っていた頃は、応答本文にメッセージを返せば
元メッセージが置き換わった。ネット上の記事やサンプルにはこの世代のものが多く残っている。

Block Kit(`blocks` + `elements`)に移行した際にこの経路は廃止され、
**差し替えは `response_url` に一本化された。**

| | legacy interactive messages(`attachments`) | Block Kit(`blocks`) |
|---|---|---|
| HTTP 応答本文にメッセージ | **差し替わる** | **読まれない**(200 の合図のみ) |
| `response_url` に POST | 使える | **これしかない** |

**「Slack のボタン」で検索して出てくるコードがどちらの世代か**を見分ける必要がある。
`attachments` / `"type": "button"` が `actions` 配列に直接入っていれば前者、
`blocks` の中の `"type": "actions"` → `elements` なら後者。

## 3. 直した形

`response_url` は payload に入っていて、**押されたメッセージに紐づく使い捨ての webhook URL**。
発行から **30 分・5 回まで**使える。

```js
// ⭕ 差し替えは response_url に POST、HTTP 応答は 200 だけ
await fetch(payload.response_url, {
  method: "POST",
  headers: { "content-type": "application/json" },
  body: JSON.stringify({ replace_original: true, text, blocks: [...] }),
});
return { statusCode: 200, body: "" };
```

**元のメッセージが Incoming Webhook で投稿されたものでも差し替えられる。**
`response_url` は「投稿の仕方」ではなく「押されたメッセージ」に紐づくため。
`chat.update` を使う道もあるが、あちらは bot トークン(`chat:write`)が要る。
このリポジトリは webhook URL だけで済ませる方針なので `response_url` が合う
(→ 設計書の決定8)。

## 4. 代償 — 3 秒ルールに近づく

**Slack は 200 を 3 秒以内に求める。** 1 往復増えたぶん、そこに近づいた。
承認 Lambda の処理順はこうなっている:

```
署名検証 → get-pipeline-state(トークン取得)→ put-approval-result
        → response_url に POST → 200 を返す
```

コールドスタートだと 3 秒を超えることがある。超えても

- **承認・却下は成立する**(先に終わっている)
- **メッセージの差し替えも成立する**(`response_url` は 30 分有効)
- Slack 側に一瞬警告が出るだけ

厳密にやるなら「先に 200 を返し、続きを非同期の別 Lambda で」だが、
承認者が 1 人の学習用途では過剰なので採らなかった。
気になったら CloudWatch Logs の `Duration` / `Init Duration` を見る。

## 5. ついでに確かめたこと — 却下すると FAILED で終わる

**これは正常。** CodePipeline の手動承認に「却下」という専用の終了状態は無い。
`PutApprovalResult` に `status: Rejected` を渡すと:

1. その承認アクションが **Failed** になる
2. ステージが失敗する
3. **実行全体が FAILED** で終わる(7 日放置のタイムアウトも同じ)

大事なのは **Deploy ステージに進まずに終わること**で、赤くなるのは却下の正常な結末。
コンソールの Approve アクションには `Rejected by @<ユーザー> via Slack` が残る
(`PutApprovalResult` の `summary`。Lambda 経由になって CloudTrail から追えなくなった
ぶんの埋め合わせ → ADR-0014)。

ただし通知としては **`pipeline-execution-failed` と `action-execution-failed` の
2 通が `:x:` で飛ぶ**ので、承認者からは事故に見える。
出し分けるなら `notify` 側で `detail.type.category === "Approval"` かつ FAILED を
「却下により中止」として扱う(未対応)。
