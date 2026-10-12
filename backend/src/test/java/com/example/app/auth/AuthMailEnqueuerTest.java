package com.example.app.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Web が認証メールの依頼を SQS に送るタイミング({@link AuthMailEnqueuer})のテスト。
 *
 * <p>コミットの後にだけ送ること(フェーズ20 の決定5)。コミット前に送ると、ワーカーがまだ見えないユーザーを
 * 読みに行く、ロールバックしても依頼だけが残る、という食い違いが起きる。
 *
 * <p>SQS はモック。{@code @MockitoBean} の組み合わせは {@link AuthFlowTest} と揃えてコンテキストを使い回す。
 */
@SpringBootTest
class AuthMailEnqueuerTest {

	@MockitoBean
	JavaMailSender mailSender;

	@MockitoBean
	SqsClient sqsClient;

	@Autowired
	ApplicationEventPublisher eventPublisher;

	@Autowired
	TransactionTemplate transactionTemplate;

	@Autowired
	JsonMapper jsonMapper;

	@BeforeEach
	void stubQueue() {
		when(sqsClient.sendMessage(any(SendMessageRequest.class)))
				.thenReturn(SendMessageResponse.builder().messageId("test-message-id").build());
	}

	@Test
	@DisplayName("コミットすると、依頼が JSON でキューに送られる")
	void sendsAfterCommit() {
		AuthMailRequestedEvent event = AuthMailRequestedEvent.of(1L, AuthTokenPurpose.EMAIL_VERIFICATION);

		// トランザクションを開始して、その中でイベントを発行し、コミットする
		transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(event));

		ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
		verify(sqsClient).sendMessage(captor.capture());
		String body = captor.getValue().messageBody();
		// 値と JSON での表し方(userId は数値、purpose は enum の名前)。requestedAt は実行のたびに変わるので項目の有無だけ
		assertThat(body)
				.contains("\"requestId\":\"" + event.requestId() + "\"")
				.contains("\"userId\":1")
				.contains("\"purpose\":\"EMAIL_VERIFICATION\"")
				.contains("\"requestedAt\"");
		// 本文に載るのは依頼の 4 項目だけ。生のトークンもメールアドレスも載らない。
		// contains だけでは余計な項目が増えても通るので、項目名の集合を完全一致で見る
		JsonNode json = jsonMapper.readTree(body);
		assertThat(json.propertyNames())
				.containsExactlyInAnyOrder("requestId", "userId", "purpose", "requestedAt");
	}

	@Test
	@DisplayName("ロールバックすると、キューには送られない")
	void doesNotSendOnRollback() {
		transactionTemplate.executeWithoutResult(status -> {
			eventPublisher.publishEvent(AuthMailRequestedEvent.of(1L, AuthTokenPurpose.EMAIL_VERIFICATION));
			// ロールバックさせる
			status.setRollbackOnly();
		});

		verify(sqsClient, never()).sendMessage(any(SendMessageRequest.class));
	}
}
