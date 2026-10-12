# SQS の振る舞いをローカル(ElasticMQ)で観察する

認証メールを SQS 経由のワーカーに移した(→ [ADR-0017](../../adr/0017-auth-mail-via-sqs-worker.md)、
[フェーズ20 設計書](../../superpowers/specs/2026-10-11-phase20-sqs-auth-mail-design.md))ときに、
**テストには書かず手で動かして確かめた**ことの手順と結果。

テストに書かなかったのは、どれも SQS(ElasticMQ)自体の仕様で、しかも待ち時間が長い(DLQ に移るまで約 5 分)ため。
アプリが正しく振る舞うことはテスト(`AuthMailWorkerTest` / `SqsReceiveLoopTest` など → [docs/test/README.md](../../test/README.md))で守っている。

> **ElasticMQ は本物の SQS とは別の実装。** ここに書いた秒数や順序は ElasticMQ 1.7.1 での観察で、
> 本物で同じになる保証は無い。IAM・キューポリシー・暗号化はそもそもローカルでは確かめられない。

## 準備

AWS CLI をホストから ElasticMQ に向ける。ElasticMQ は署名を検証しないので、資格情報はダミーでよい
(ただし無いと CLI が通信の前に失敗する)。

```bash
export AWS_ACCESS_KEY_ID=x AWS_SECRET_ACCESS_KEY=x AWS_DEFAULT_REGION=ap-northeast-1
Q=http://localhost:9324/000000000000/auth-mail
alias sqs='aws --endpoint-url http://localhost:9324 sqs'

# 件数を見る(見えている件数 / 受け取られて見えなくなっている件数)
sqs get-queue-attributes --queue-url $Q \
  --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible --output text
```

ElasticMQ の `elasticmq-native` イメージには**画面が無い**(9325 番は応答しなかった)。中身は CLI で見る。

## 1. ワーカーを止めると溜まり、戻すと流れる

```bash
docker compose stop worker
# 画面か API で会員登録を 2 回する
sqs get-queue-attributes ...      # → ApproximateNumberOfMessages が 2
docker compose start worker
docker compose logs -f worker     # → 起動して受信を始めた直後に 2 件とも「認証メールを送りました」
```

**観察結果**: 止めている間も会員登録は 201 で返り(約 0.1 秒)、メッセージはキューに 2 件溜まった。
ワーカーが起動して「受信を開始しました」のログが出た 0.5 秒後に 2 件とも処理され、Mailpit に届いた。キューは 0 件に戻った。

**「止まる」のではなく「遅れる」** — Web とワーカーを分けた意味がここに出る。

## 2. 同じ依頼が 2 回届いても、メールは 1 通

処理済みの依頼と同じ `requestId` のメッセージを手で送る。

```bash
sqs send-message --queue-url $Q --message-body \
  '{"requestId":"<処理済みの requestId>","userId":<その userId>,"purpose":"EMAIL_VERIFICATION","requestedAt":"2026-10-10T15:55:00Z","futureField":"x"}'
```

**観察結果**: ワーカーのログに `処理済みの依頼です。何もしません。` が出て、メッセージは削除された。Mailpit には届かない。

ついでに `futureField` という**知らない項目**を混ぜたが、エラーにならずに読めた(Jackson 3 は既定で未知の項目を無視する)。
メッセージの形式を「項目の追加だけ」で変えていけるのは、これがあるから(設計書の決定4)。

## 3. 処理に失敗し続けると、5 回目の後に DLQ へ移る

壊れた本文を送る。

```bash
sqs send-message --queue-url $Q --message-body 'not json'
docker compose logs -f worker | grep 受信回数
```

**観察結果**(ElasticMQ 1.7.1):

| 時刻 | 起きたこと |
| --- | --- |
| 15:57:01 | 受信回数=1 で失敗。削除しない |
| 15:58:01 | 受信回数=2 で失敗(ちょうど 60 秒後 = 可視性タイムアウト) |
| 15:59:01 | 受信回数=3 |
| 16:00:02 | 受信回数=4 |
| 16:01:02 | 受信回数=5 |
| 16:02 頃 | メインキューから消え、`auth-mail-dlq` に 1 件 |

読み取れること:

- **リトライはアプリが書いたものではない。** ワーカーは「失敗したら削除しない」としか書いていない。
  60 秒後にまた届くのは、受け取ったメッセージが**可視性タイムアウトの間だけ見えなくなっていた**から
- **DLQ に移すのも SQS の仕事。** キューのリドライブポリシー(`maxReceiveCount = 5`)が、
  6 回目に渡す代わりに DLQ へ移した。アプリは回数を数えていない
- DLQ に移ったメッセージは**誰も処理しない**。本番ではここにアラームを付けて気づけるようにする(設計書の決定19)

DLQ の中身を見る(`--visibility-timeout 0` を付けると、見ただけで隠れることがない):

```bash
sqs receive-message --queue-url http://localhost:9324/000000000000/auth-mail-dlq \
  --visibility-timeout 0 --query 'Messages[0].Body' --output text
```

## 4. ローカルではワーカーの「行儀のよい止まり方」は見えない

`docker compose stop worker` は 1 秒で終わり、受信ループの「受信を停止しました」のログは出なかった。

ローカルのワーカーは `sh ./gradlew bootRun` で動いていて、**SIGTERM を受け取るのは Gradle で、アプリの JVM ではない**ため
(Spring のシャットダウン処理を通らずに終わる)。本番のイメージは `exec java ...` で起動するので JVM が直接 SIGTERM を受ける。

止まり方は代わりに `SqsReceiveLoopTest` の「処理中に止められたら、その 1 件を最後まで終えて削除してから止まる」で確かめている。
本物の SIGTERM での振る舞いは stg で見る。

## 5. Gradle のキャッシュをコンテナ間で共有すると、ワーカーが起動しない

最初は backend と worker で Gradle のキャッシュ(`/root/.gradle` の volume)を共有したが、worker が 1 分待った末に失敗した。

```
> Timeout waiting to lock journal cache (/root/.gradle/caches/journal-1). It is currently in use by another process.
```

Gradle はキャッシュのロックを持っているプロセスに「譲ってくれ」と頼むとき、**localhost の通信**を使う。
コンテナが違うと localhost も別物なので、相手に頼みようがなく待ち続ける。
worker には別の volume(`gradle-cache-worker`)と別のプロジェクトキャッシュ(`--project-cache-dir`)を持たせて解決した。
