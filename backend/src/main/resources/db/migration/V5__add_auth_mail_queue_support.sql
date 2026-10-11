-- 認証メールを SQS 経由でワーカーに送らせるための 2 つの変更
-- (→ docs/adr/0017、設計 → specs/2026-10-11-phase20-sqs-auth-mail-design.md)。

-- ① 処理済みの依頼の記録(冪等キー)。設計の決定8。
--
-- SQS の標準キューは同じメッセージを 2 回届けることがある。ワーカーは処理の最初にこの表へ
-- request_id を INSERT し、メール送信が終わってからコミットする。2 回目に届いたものは、
-- 行が既にあるので何もせずに終わる。
--
-- 主キーを request_id にしているのは、同時に 2 つのワーカーが同じ依頼を受け取ったとき、
-- 後から INSERT した側が先の側のコミットを待たされ、主キーの重複で失敗するようにするため
-- (主キーそのものが「この依頼は処理中」という鍵の役を果たす)。
--
-- 認証のテーブル(auth_tokens)に列を足さず別の表にしたのは、冪等性がキューを使う側の都合で、
-- 認証の業務とは関係が無いため。今後キューを使う処理が増えても、同じ表を使い回せる。
--
-- 掃除の仕組みは作っていない。メインキューの保持期間(1 日)より古い行は二度と参照されないので、消してよい。
CREATE TABLE processed_messages (
    request_id   CHAR(36)    NOT NULL, -- Web が送る前に作る UUID。SQS の MessageId ではない(SDK のリトライで変わるため)
    processed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (request_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ② パスワードを最後に設定した日時。設計の決定11。
--
-- トークンの発行がワーカーに移ったので、「リセットを申請 → パスワードを変更 → ワーカーが発行」の順になると、
-- パスワード変更による失効(changePassword の invalidateUnused)をすり抜けたリンクが届いてしまう。
-- ワーカーは依頼の時刻とこの列を比べ、依頼より後に変更されていればリセットメールを送らない。
--
-- updated_at は表示名の変更などでも更新されるので代わりにならない。
-- 既存の行は NULL(いつ設定したか分からない = 比べようがないので送る)。
ALTER TABLE users
    ADD COLUMN password_changed_at DATETIME(6) NULL AFTER password_hash;
