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

**元のメッセージが Incoming Webhook で投稿されたものでも差し替えられる**(2026-09-07 に実機で確認)。
`response_url` は「投稿の仕方」ではなく「押されたメッセージ」に紐づくため。
`chat.update` を使う道もあるが、あちらは bot トークン(`chat:write`)が要る。
このリポジトリは webhook URL だけで済ませる方針なので `response_url` が合う
(→ 設計書の決定8)。

> **切り分けで一度誤った推測をした。**
> 直したあと最初に押したときも差し替わらなかったので「Incoming Webhook で投稿した
> メッセージは差し替えられないのでは」と疑ったが、**原因は単に修正版がまだデプロイ
> されていなかったこと**だった。`push` しただけでは Lambda は入れ替わらない。
> `gh run list --workflow=pipeline-apply.yml --json createdAt,headSha` で
> **「どのコミットを配ったか」**を見れば 1 分で分かる。

## 3-2. 差し替わったかどうかは、時刻だけ見ても分からない

**差し替えてもメッセージの投稿時刻(`ts`)は変わらない。** 実機ではこうなった。

```
09:10:11  承認依頼を投稿(Incoming Webhook)
09:10:47  却下を押す → 同じメッセージが「却下しました」に差し替わる
```

Slack の表示はどちらも `[09:10]` のまま。**新しく生えたメッセージのように見える**が、
承認依頼のほうが消えている(=差し替わった)ことが手がかりになる。
「差し替わらず新規投稿された」と誤読しかけた。

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

ただし素通しすると、却下 1 回で **`action-execution-failed` と
`pipeline-execution-failed` の 2 通が `:x:` で飛ぶ**(押した人のメッセージの差し替えと
合わせて、同じことを 3 回言うことになる)。承認者からは事故に見えるので、
**`notify` で出し分けるようにした。**

**区別できるのは summary の文字列だけ。** 却下も本物の失敗も `state` は `FAILED` で、
`error-code` も `JobFailed` で同じ。`PutApprovalResult` に渡した summary
(`Rejected by @<誰> via Slack`)がどちらの通知にも載るので、それを見る。

| 通知 | `detail.type` | 却下の印 |
|---|---|---|
| アクション単位 | **ある**(`category: Approval`) | `detail["execution-result"]["external-execution-summary"]` |
| 実行単位 | **無い** | `additionalAttributes.failedActions[].additionalInformation` |

**`detail.type` の有無で 2 種類の通知を見分けられる**のが実機で分かった収穫。
`notify` は**アクション単位のほうを捨て、実行単位を 1 通だけ「却下により中止しました」**
として出す(コンソールから却下された場合は差し替えが起きないので、この 1 通が唯一の記録になる)。
