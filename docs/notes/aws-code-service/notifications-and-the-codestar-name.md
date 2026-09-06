# 通知と CodeStar という名前 — 誰が Slack にメッセージを送っているのか

> **このノートは実機未検証。** フェーズ16 のパイプラインはまだ一度も回っていない
> (→ [implementation-progress.md](../../development/implementation-progress.md))。
> 確からしさを 3 段階で書き分ける。**仕様** = 公式に明記 / **推定** = 仕様から導いた /
> **未検証** = 実機で確かめる。

`pipeline.yml` の `PipelineNotificationRule` は `AWS::CodeStarNotifications::NotificationRule`
という型を持っている。**この「CodeStar」は、もう存在しないサービスの名前。**
このノートは「これは何というサービスの何なのか」「なぜこの名前なのか」を片付ける。

Slack 側の手順は [docs/slack/README.md](../../slack/README.md)、
やめた Chatbot 方式は [chatbot-approval-attempt.md](chatbot-approval-attempt.md)、
ロールの分担は [roles.md](roles.md)。

---

## 0. 名前の規則 — 固定・既定・任意

| 名前 | 区分 | 備考 |
|---|---|---|
| 型名 `AWS::CodeStarNotifications::NotificationRule` | **固定** | AWS が決めている。改名されていない(→ §2) |
| サービスプリンシパル `codestar-notifications.amazonaws.com` | **固定** | トピックポリシーの `Principal`。ここを `codepipeline` にすると届かない |
| IAM プレフィックス `codestar-notifications:` | **固定** | `codepipeline:` ではない(→ §1) |
| `EventTypeIds` の各値 | **固定** | `codepipeline-pipeline-manual-approval-needed` など。綴りは AWS の定義(→ §5) |
| `DetailType` | **既定 `FULL`** | 明示しているが既定と同じ。`BASIC` にすると承認カードが痩せる(→ §5) |
| `Targets` の `TargetType` | **固定** | `SNS` か `AWSChatbotSlack` の 2 つだけ |
| ルール名(`...-pipeline`) | **任意** | 人間が読むためのもの |
| `Sid`(`AllowCodeStarNotifications`) | **任意** | 同上 |

---

## 1. Code 系サービスの「中」ではなく「上」にある

**通知は CodePipeline の機能ではない。** CodeCommit / CodeBuild / CodeDeploy / CodePipeline の
**4 つに横断でかかる共通機能**で、AWS はこれを「Developer Tools コンソールの機能」と呼んでいる。

**仕様** 公式は「コンソールの機能でありながら、独立した API・リソース型・権限・イベントを持つ」と明言している。

> While a feature of the Developer Tools console, notifications has its own API,
> AWS CodeStar Notifications. It also has its own AWS resource type (notification rules),
> permissions, and events.
> — [Notification concepts](https://docs.aws.amazon.com/dtconsole/latest/userguide/concepts.html)

つまり **CodePipeline の設定画面にぶら下がっているように見えるが、実体は別サービス。**
同じ形の通知ルールを、4 つのうちどのリソースにも付けられる。

| 通知ルールを付けられるリソース | サービス |
|---|---|
| リポジトリ | CodeCommit |
| ビルドプロジェクト | CodeBuild |
| デプロイアプリケーション | CodeDeploy |
| **パイプライン** | **CodePipeline** ← このリポジトリが使っているのはこれだけ |

**これが分かると、`pipeline.yml` の 2 つの「なぜ」が同時に片付く。**

- **IAM プレフィックスが `codepipeline:` ではない。** 通知ルールを作る権限は
  `codestar-notifications:CreateNotificationRule` で審査される
  (このリポジトリではスタックを建てる側の権限なので、
  → [cloudformation-operations.md](../../infrastructure/cloudformation-operations.md))
- **トピックポリシーの `Principal` が `codepipeline.amazonaws.com` ではない。**
  トピックに publish しに来るのは CodePipeline ではなく CodeStar Notifications 自身なので、
  `codestar-notifications.amazonaws.com` を許す(→ §6)

**仕様** 通知ルールは対象リソースと**同じリージョン**に作る必要がある。
1 リソースあたりルールは 10 本まで、1 ルールあたりターゲットは 10 個まで。

---

## 2. なぜ CodeStar という名前なのか

**「CodeStar」と名の付くものが 3 つあり、3 つとも別物。** ここが混乱の正体。

| 名前 | 正体 | 今 |
|---|---|---|
| **AWS CodeStar** | リポジトリ・パイプライン・ダッシュボードをテンプレートから丸ごと立ち上げるサービス(2017-) | **2024-07-31 に廃止**(コンソールも新規作成も終了) |
| **AWS CodeStar Notifications** | 通知ルール。4 サービス横断の共通機能 | **現役。名前もそのまま** |
| **AWS CodeStar Connections** | GitHub など外部 Git との接続 | **2024-03 に AWS CodeConnections へ改名** |

**推定** 名前の由来は「横断機能だったから」。通知も接続も特定の 1 サービスの機能ではないので、
`codepipeline-notifications` のような名前は付けられない。当時 Code 系全体の傘として
存在していたブランドが CodeStar だったので、そこから借りた——という順序だと辻褄が合う。
AWS は由来を明文化していないので、ここは**推定**。

**仕様** **CodeStar 本体の廃止は、Notifications と Connections には及ばない。**
名前を共有しているだけの別サービスで、廃止のアナウンスでも「影響しない」と切り分けられている。
CodeStar が作った成果物(リポジトリ・パイプライン)も動き続ける。

**改名されたのは Connections だけだった。**
その結果、**廃止済みサービスの名前が、現役のリソース型・IAM・サービスプリンシパルに残っている。**
`AWS::CodeStarNotifications::NotificationRule` という型名はこの化石。

**推定** Notifications が改名されなかった理由は公式に説明が無い。
Connections の改名では IAM プレフィックスを増やす(旧名も残す)という後方互換の手当てが要った。
同じことを Notifications でもやる価値が薄かった、という程度の話だと思われる。

出典 — [Introducing AWS CodeConnections](https://aws.amazon.com/about-aws/whats-new/2024/03/aws-codeconnections-formerly-codestar-connections/) /
[Connections rename: Summary of changes](https://docs.aws.amazon.com/dtconsole/latest/userguide/rename.html)

改名によって IAM の綴りが 2 つになった件(CLI は旧名のまま・権限は新名で審査される)は
[cloudformation-operations.md](../../infrastructure/cloudformation-operations.md) に詳しい。

---

## 3. 早見表 — どの綴りがどのサービスの語彙か

**似た綴りが 6 つあり、改名の影響を受けたものと受けていないものが混ざっている。**

| 綴り | どこに出るか | 改名の影響 |
|---|---|---|
| `AWS::CodeStarNotifications::NotificationRule` | `pipeline.yml` の型名 | **無し**(Notifications は改名されていない) |
| `codestar-notifications.amazonaws.com` | トピックポリシーの `Principal` | **無し** |
| `codestar-notifications:*` | 通知ルールを作る権限 | **無し** |
| `codeconnections:UseConnection` | CodePipeline サービスロール | **改名後の綴り** |
| `codestar-connections:UseConnection` | 同上(両方書いている) | **改名前の綴り** |
| **`CodeStarSourceConnection`** | Source アクションの `Provider` | **無し。ここだけ CodePipeline 側の語彙** |

**`CodeStarSourceConnection` に引きずられないこと。**
これは CodePipeline が持つ「アクションプロバイダの名前」であって、Connections の API 名ではない。
接続サービスが CodeConnections に改名されても、**この文字列は変わっていない。**
`Provider` の値なので、書き換えるとアクションが解決できなくなる。

---

## 4. このリポジトリでの経路と、その 3 段階の変遷

現行の経路(→ [docs/slack/README.md](../../slack/README.md) §0 と同じもの)。

```
CodePipeline(承認待ち / 失敗 / 成功)
      │  イベント
      ↓
CodeStarNotifications  ← 通知ルール。ここまでが「AWS が用意した通知」
      │  Targets: SNS
      ↓
SNS トピック           ← publish される側。だからトピックポリシーが要る(→ §6)
      │  Protocol: lambda
      ↓
Lambda(notify)        ← ここから先は自作。整形して webhook に POST
      ↓
Incoming Webhook ─→ Slack
```

**経路は 3 回変わっている。** 通知ルール自体はどの段階でも要るが、**その先が毎回違う。**

| 段階 | 経路 | 理由 |
|---|---|---|
| フェーズ16 設計(決定18) | NotificationRule → SNS → Chatbot | アラート側(app.yml)と同じ形に揃える想定だった |
| フェーズ16 実装 | NotificationRule → **Chatbot 直** | `TargetType: AWSChatbotSlack` で直接指せると分かったため。SNS が要らない |
| **フェーズ17(現行)** | NotificationRule → **SNS** → Lambda → Webhook | Lambda を起動したいから。SNS は Lambda を呼ぶための踏み台 |

**アラート 2 本(`app.yml`)は Chatbot のまま**で、通知の仕組みが 2 つ並立している。
アラート側が SNS を挟むのは CloudWatch アラームの制約が理由で、
こちらが SNS を挟むのは Lambda を呼びたいから。**同じ形になったが理由は別**
(→ [ADR-0014](../../adr/0014-slack-approval-with-lambda.md))。

---

## 5. 通知ルールが持つ 4 つのプロパティ

| プロパティ | 何を決めるか |
|---|---|
| `Resource` | **どのリソースを見張るか。** パイプラインの ARN |
| `EventTypeIds` | **何が起きたら鳴らすか** |
| `DetailType` | **どれだけ詳しく送るか**(`FULL` / `BASIC`) |
| `Targets` | **どこへ送るか**(SNS トピック or Chatbot 設定) |

### 5-1. `EventTypeIds` の綴りには規則がある

**仕様** 値は `<サービス>-<リソース種別>-<カテゴリ>-<イベント>` という組み立て。

```
codepipeline - pipeline - pipeline-execution - failed
     ↑            ↑              ↑              ↑
  サービス   リソース種別      カテゴリ       イベント
```

**`pipeline` が 3 回出てくるのはこのため。** 冗長に見えるが、
CodeBuild なら `codebuild-project-build-state-failed`、
CodeDeploy なら `codedeploy-application-deployment-failed` と、同じ規則で並ぶ。
§1 の「4 サービス横断の共通機能」がここにも現れている。

パイプラインで選べるカテゴリは 4 つ。このリポジトリは太字の 6 つを使っている。

| カテゴリ | イベント |
|---|---|
| Action execution | succeeded / **failed** / canceled / started |
| Stage execution | started / succeeded / resumed / canceled / failed |
| Pipeline execution | **failed** / canceled / started / resumed / **succeeded** / superseded |
| Manual approval | **failed** / **needed** / **succeeded** |

### 5-2. `DetailType: FULL` は承認カードの中身に直結する

**仕様** 手動承認の通知では、`FULL` と `BASIC` で入る情報が違う。

| 設定 | 手動承認の通知に入るもの |
|---|---|
| `FULL`(既定) | すべてのイベント詳細 + **カスタムデータ(設定していれば)** + **承認画面へのリンク** |
| `BASIC` | カスタムデータもリンクも**入らない** |

**つまり `BASIC` にすると `CustomData` が丸ごと消える。**
承認アクションの `CustomData` には `#{BuildVariables.IMAGE_TAG}` を埋めてあり、
「どのコミットを承認しようとしているのか」はここでしか分からない
(→ [env-vars-and-logs.md](env-vars-and-logs.md) §4-2)。
**`FULL` は既定値だが、消してよい行ではない。**

### 5-3. `Resource` を静的 ARN にした件

`!GetAtt Pipeline.Arn` ではなく `!Sub` の固定文字列にしてあり、そのぶん `DependsOn: Pipeline` を
自分で書いている。理由(`Resource` が createOnly で、更新対象への `GetAtt` は Change Set で
解決されないため `Replacement: Conditional` が付く)は `PipelineNotificationRule` のコメントと、
運用上の症状は [cloudformation-operations.md](../../infrastructure/cloudformation-operations.md)
のトラブルシュート欄にある。

---

## 6. なぜトピックポリシーを自分で書くのか

**仕様** **コンソールで通知ルールを作るときに SNS トピックも一緒に作れば、必要なポリシーは自動で付く。**
既存のトピックや手作りのトピックを使うなら、自分で付けなければならない。

> If you create an Amazon SNS topic as part of creating a notification rule, the topic is
> configured with the policy required to allow the publication of events to the topic.
> ... If you choose to use an already-existing topic or create one manually, you must
> configure it with the required permissions before users receive notifications.
> — [Configure Amazon SNS topics for notifications](https://docs.aws.amazon.com/dtconsole/latest/userguide/set-up-sns.html)

**このリポジトリは CloudFormation でトピックを自分で作っているので、後者にあたる。**
`PipelineNotificationTopicPolicy` が無いと、通知ルールは正常に作れるのに
**イベントが飛んできた時点で publish が拒否され、Slack に何も来ない。**

### 6-1. 公式のサンプルより 1 段厳しくしてある

公式が載せている文は、`Principal` に `codestar-notifications.amazonaws.com`、
`Action` に `SNS:Publish` を許すだけで、**`Condition` が無い。**

**このリポジトリは `aws:SourceAccount` を足している。** 条件が無いと
「CodeStar Notifications 経由なら誰の通知ルールからでも publish できる」という意味になり、
**他人のアカウントで作ったルールからこのトピックに撃ち込める**(混乱した代理人)。
サービス自身に悪意は無く、頼まれたとおり配送するだけなので、**受け取る側で絞る。**

**推定** より厳密には `aws:SourceArn` で通知ルール 1 本に絞れる。採っていないのは、
通知ルールの ARN が生成値を含んで静的に書けないうえ、ルール側がトピックを `Targets` に
取っている(依存の向きが逆になる)ため。アカウント単位で実害は塞げる。

### 6-2. 経路の両端に、それぞれ許可が要る

| 区間 | 許可を持つリソース |
|---|---|
| CodeStarNotifications → SNS | `PipelineNotificationTopicPolicy`(トピックのリソースポリシー) |
| SNS → Lambda | `NotifyInvokePermission`(`AWS::Lambda::Permission`) |

**別物なので、片方だけでは届かない。** `NotifySubscription` は「繋ぐ」だけで、許可は持たない。

### 6-3. トピックを暗号化するなら鍵ポリシーにも要る

**仕様** SNS トピックを KMS で暗号化した場合、**鍵ポリシー側にも**
`codestar-notifications.amazonaws.com` に `kms:GenerateDataKey*` と `kms:Decrypt` を
許す文(`kms:ViaService` を `sns.<リージョン>.amazonaws.com` に限定)が必要になる。

**現状は不要。** `PipelineNotificationTopic` に `KmsMasterKeyId` を付けていないため
(アーティファクトバケットの `SSEAlgorithm: AES256` は S3 側の話で、これとは別)。
**暗号化を足すときは鍵ポリシーも対で直すこと。**

---

## 7. 実機で確かめること

- [ ] `DetailType: FULL` で `CustomData` の `#{BuildVariables.IMAGE_TAG}` が
      展開された状態で Slack まで届くか(→ [env-vars-and-logs.md](env-vars-and-logs.md) §4-2)
- [ ] `aws:SourceAccount` を足した状態で publish が実際に通るか
      (公式サンプルより厳しくしているため、条件キーが期待どおり埋まるかを確認する)
- [ ] SNS に届くメッセージの `detail` の正確な形。Lambda の整形はここに依存している
      (→ [フェーズ17 の設計書](../../superpowers/specs/2026-09-06-phase17-slack-approval-design.md) の未解明点)
- [ ] `EventTypeIds` 6 つが実際にどれだけ鳴るか。`ExecutionMode: SUPERSEDED` を選んでいるので
      `pipeline-execution-superseded` が起きうるが、通知には入れていない。
      追い越されたことが分からなくて困るなら足す
- [ ] SNS まで来ているかの切り分け(トピックのメトリクスで見る → [docs/slack/README.md](../../slack/README.md) §10)
