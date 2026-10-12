package com.example.app.auth;

import java.time.Instant;
import java.util.UUID;

/**
 * 「認証メールを送る必要が生じた」という依頼。<b>そのまま SQS のメッセージの本文(JSON)になる。</b>
 *
 * <p>流れ(→ docs/adr/0017-auth-mail-via-sqs-worker.md):
 * <ol>
 * <li>{@link AuthService} がトランザクションの中でこのイベントを発行する</li>
 * <li>コミットの後、{@link AuthMailEnqueuer} が JSON にして SQS に送る(Web の仕事はここまで)</li>
 * <li>ワーカーの {@link AuthMailWorker} が受け取り、トークンを発行してメールを送る</li>
 * </ol>
 *
 * <p><b>生のトークンは載せない。</b>トークンはワーカーが発行するので、キューにも DLQ にも秘密が残らない。
 * <b>宛先はメールアドレスではなく userId。</b>未確認ユーザーは同じアドレスで登録し直すと作り直されて ID が変わるので
 * (ADR-0003)、古い依頼は「ユーザーがいない」で自然に捨てられる。
 *
 * <p>形式の変更は<b>項目の追加だけ</b>にする。デプロイ中は新旧の Web とワーカーが混在するが、
 * 受け取る側は知らない項目を無視する(Jackson 3 の既定)ので、追加なら古いワーカーも読める。バージョン番号は持たない。
 *
 * @param requestId   冪等キー。Web が送る前に作る。SQS の MessageId は SDK のリトライで変わるので使わない
 * @param userId      宛先のユーザー
 * @param purpose     用途。メールの件名・本文・リンク先とトークンの有効期限がこれで決まる
 * @param requestedAt 依頼の時刻。パスワードリセットでは、これより後にパスワードが変わっていれば送らない
 */
record AuthMailRequestedEvent(String requestId, Long userId, AuthTokenPurpose purpose, Instant requestedAt) {

	static AuthMailRequestedEvent of(Long userId, AuthTokenPurpose purpose) {
		return new AuthMailRequestedEvent(UUID.randomUUID().toString(), userId, purpose, Instant.now());
	}
}
