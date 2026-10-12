package com.example.app.common.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * 受信ループ({@link SqsReceiveLoop})の結合テスト。本物の SQS の代わりに ElasticMQ のテスト専用キューを使う。
 *
 * <p>確かめるのは「受け取る → 処理 → 削除」「失敗したら削除しない」「処理中の 1 件を終えてから止まる」
 * 「受信スレッドが想定外に終わったら後始末を呼ぶ」の 4 つ。
 * 可視性タイムアウトによる再配信や DLQ への移動は SQS(ElasticMQ)自体の仕様なので、ここでは確かめない
 * (待ち時間が長いので手で観察する → docs/notes/sqs/local-observation.md)。
 *
 * <p>Spring は起動しない。受信ループは Spring に依存しない作りなので、部品を直接組み立てる。
 * 接続先は .env の {@code SQS_ENDPOINT}、キューは build.gradle が渡す {@code SQS_AUTH_MAIL_QUEUE_URL}(auth-mail-test)。
 */
class SqsReceiveLoopTest {

	/** テストでは受信の待ちを短くして、止めるときに待たされないようにする */
	private static final int WAIT_TIME_SECONDS = 1;

	private SqsClient sqsClient;
	private String queueUrl;
	private SqsReceiveLoop loop;

	@BeforeEach
	void setUp() {
		String endpoint = System.getenv("SQS_ENDPOINT");
		queueUrl = System.getenv("SQS_AUTH_MAIL_QUEUE_URL");
		// コンテナの外(ElasticMQ に届かない場所)で実行したときは飛ばす
		assumeTrue(endpoint != null && queueUrl != null && queueUrl.endsWith("-test"),
				"ElasticMQ のテスト用キューが設定されていません");
		sqsClient = SqsClient.builder()
				.endpointOverride(URI.create(endpoint))
				.region(Region.AP_NORTHEAST_1)
				.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
				.build();
		sqsClient.purgeQueue(request -> request.queueUrl(queueUrl));
	}

	@AfterEach
	void tearDown() {
		if (loop != null && loop.isRunning()) {
			loop.stop();
		}
		if (sqsClient != null) {
			sqsClient.purgeQueue(request -> request.queueUrl(queueUrl));
			sqsClient.close();
		}
	}

	@Test
	@DisplayName("受け取って処理に成功したら、メッセージを削除する")
	void deletesMessageAfterSuccess() throws Exception {
		// handled … いつ確かめてよいか(受信スレッドが処理を終えたか)を知るためのもの
		CountDownLatch handled = new CountDownLatch(1);
		// received … 何を処理したか(handler に渡った本文)を確かめるためのもの。
		// ラムダの中から、外の変数に結果を渡すためにStringBuilderを使う
		StringBuilder received = new StringBuilder();
		loop = newLoop(body -> {
			received.append(body);
			handled.countDown();
		});
		send("hello");

		loop.start();
		// 受信スレッドが handler を呼び終えるまで最大 10 秒待つ。時間切れ(false)なら受け取れていないので失敗
		assertThat(handled.await(10, TimeUnit.SECONDS)).isTrue();
		loop.stop();

		assertThat(received).hasToString("hello");
		// 見えているものも、受け取られて見えなくなっているものも 0 件 = 削除された
		assertThat(countVisible() + countNotVisible()).isZero();
	}

	@Test
	@DisplayName("処理に失敗したら削除しない(可視性タイムアウトの間、見えなくなっているだけ)")
	void keepsMessageAfterFailure() throws Exception {
		CountDownLatch handled = new CountDownLatch(1);
		loop = newLoop(body -> {
			handled.countDown();
			throw new IllegalStateException("処理に失敗した");
		});
		send("will-fail");

		loop.start();
		assertThat(handled.await(10, TimeUnit.SECONDS)).isTrue();
		loop.stop();

		// 削除されていないので、可視性タイムアウト(60 秒)が切れれば再配信される
		assertThat(countNotVisible()).isEqualTo(1);
	}

	@Test
	@DisplayName("処理中に止められたら、その 1 件を最後まで終えて削除してから止まる")
	void finishesInFlightMessageOnStop() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		AtomicBoolean finished = new AtomicBoolean(false);
		loop = newLoop(body -> {
			started.countDown();
			Thread.sleep(2000); // SES と DB に時間がかかっている、という想定
			finished.set(true);
		});
		send("slow");

		loop.start();
		assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
		loop.stop(); // ECS の SIGTERM にあたる

		assertThat(finished).isTrue();
		assertThat(countVisible() + countNotVisible()).isZero();
	}

	@Test
	@DisplayName("受信スレッドが想定外の例外(Error)で終わったら、後始末を呼ぶ")
	void callsUnexpectedExitHandler() throws Exception {
		CountDownLatch exited = new CountDownLatch(1);
		loop = new SqsReceiveLoop("test", sqsClient, queueUrl, WAIT_TIME_SECONDS,
				body -> {
					throw new AssertionError("想定外"); // Exception ではない Throwable は処理の失敗として扱われない
				},
				exited::countDown);
		send("boom");

		loop.start();

		// ワーカーではここでアプリごと終了させ、ECS にタスクを入れ替えてもらう
		assertThat(exited.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(loop.isRunning()).isFalse();
	}

	private SqsReceiveLoop newLoop(SqsReceiveLoop.MessageHandler handler) {
		return new SqsReceiveLoop("test", sqsClient, queueUrl, WAIT_TIME_SECONDS, handler,
				() -> {
					throw new AssertionError("後始末が呼ばれるはずがない");
				});
	}

	private void send(String body) {
		sqsClient.sendMessage(request -> request.queueUrl(queueUrl).messageBody(body));
	}

	private int countVisible() {
		return attribute(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES);
	}

	private int countNotVisible() {
		return attribute(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE);
	}

	private int attribute(QueueAttributeName name) {
		Map<QueueAttributeName, String> attributes = sqsClient.getQueueAttributes(request -> request
				.queueUrl(queueUrl).attributeNames(name)).attributes();
		return Integer.parseInt(attributes.get(name));
	}
}
