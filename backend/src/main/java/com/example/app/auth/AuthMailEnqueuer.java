package com.example.app.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.example.app.config.AppProperties;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * 認証メールの依頼を SQS に送る(Web 側)。
 *
 * <p>phase = AFTER_COMMIT … 発行元のトランザクションがコミットされた<b>後</b>に送る(フェーズ20 の決定5)。
 * コミット前に送ると、ワーカーがまだコミットされていないユーザーを読みに行って見つけられない、
 * ロールバックしてもメッセージだけが残る、という食い違いが起きる。
 *
 * <p>逆向きの食い違い(コミットしたのに送れていない)は起こり得る。送信の失敗をここで握りつぶしているのは
 * 以前のメール送信と同じ理由で、コミット後に例外を投げても利用者に 500 が返るだけで何も戻らないため。
 * 記録を残し、利用者には再送で救ってもらう。これを塞ぐのがアウトボックスで、今回は採っていない。
 *
 * <p><b>リスナーはリクエストと同じスレッドで動く</b>点は以前と変わらない
 * (→ docs/notes/java/spring/application-events-vs-queues.md)。変わったのは待つ相手で、
 * SES ではなく SQS への 1 回の送信だけになった。
 */
@Component
class AuthMailEnqueuer {

	private static final Logger log = LoggerFactory.getLogger(AuthMailEnqueuer.class);

	private final SqsClient sqsClient;
	private final JsonMapper jsonMapper;
	private final AppProperties appProperties;

	AuthMailEnqueuer(SqsClient sqsClient, JsonMapper jsonMapper, AppProperties appProperties) {
		this.sqsClient = sqsClient;
		this.jsonMapper = jsonMapper;
		this.appProperties = appProperties;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onAuthMailRequested(AuthMailRequestedEvent event) {
		try {
			String body = jsonMapper.writeValueAsString(event);
			String messageId = sqsClient.sendMessage(SendMessageRequest.builder()
					.queueUrl(appProperties.sqs().authMailQueueUrl())
					.messageBody(body)
					.build())
					.messageId();
			log.info("認証メールの依頼をキューに送りました。requestId={} 用途={} messageId={}",
					event.requestId(), event.purpose(), messageId);
		} catch (RuntimeException e) {
			// SQS の失敗(SdkException)や JSON 化の失敗(JacksonException)に限らず捕まえる。
			// ここから例外が漏れると、コミット済みなのに利用者へ 500 が返る
			log.error("認証メールの依頼をキューに送れませんでした。requestId={} userId={} 用途={}",
					event.requestId(), event.userId(), event.purpose(), e);
		}
	}
}
