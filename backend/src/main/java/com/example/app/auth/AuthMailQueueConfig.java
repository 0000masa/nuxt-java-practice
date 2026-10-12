package com.example.app.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.app.common.queue.SqsReceiveLoop;
import com.example.app.config.AppProperties;

import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * ワーカーの役割({@code app.role=worker})のときだけ、認証メールのキューの受信ループを立てる。
 *
 * <p>Web とワーカーは同じイメージで、違いは環境変数 {@code APP_ROLE} だけ(フェーズ20 の決定2)。
 * Web の役割ではこのクラスごと読み込まれず、キューを受け取らない。
 *
 * <p>クラス名の {@code Config} に意味は無く、命名の慣習にすぎない。Spring が見ているのは {@code @Configuration} で、
 * これに含まれる {@code @Component} によってコンポーネントスキャンで見つかり、{@code @Bean} メソッドを持つ設定クラスとして扱われる。
 *
 * <p>起動から受信が始まるまでの流れ:
 * <ol>
 * <li>{@code APP_ROLE=worker} なので、このクラスが読み込まれる</li>
 * <li>Spring が {@link #authMailReceiveLoop} を呼び、返した {@link SqsReceiveLoop} のインスタンスが Bean として登録される
 *     (この時点ではインスタンスがあるだけで、受信はまだ始まっていない)</li>
 * <li>他の Bean もすべて作られる</li>
 * <li>起動の最後に、Spring が SmartLifecycle の Bean の {@code start()} を呼ぶ。受信スレッドが立ち、受信が始まる</li>
 * <li>以後、{@code stop()} が呼ばれるまで、受信スレッドが「受け取る → 処理する → 削除する」を繰り返す</li>
 * </ol>
 *
 * <p><b>ワーカーでも Web サーバーは立つ。</b>設計では {@code SPRING_MAIN_WEB_APPLICATION_TYPE=none} にする予定だったが、
 * それだと Spring Session が初期化されず {@link UserSessionManager} が解決できずに起動に失敗する
 * ({@link com.example.app.config.TaskRunner} が同じ理由で Web サーバーを立てている)。
 * ワーカーは ALB に登録しないので、8080 番が開いていても外からは届かない。
 * 同じ理由で、ワーカーにも Redis の接続情報が要る。
 */
@Configuration
@ConditionalOnProperty(name = "app.role", havingValue = "worker")
class AuthMailQueueConfig {

	private static final Logger log = LoggerFactory.getLogger(AuthMailQueueConfig.class);

	/**
	 * ロングポーリングの秒数。SQS の上限の 20 秒にする。メッセージが無いあいだ SQS が応答を保留するので、
	 * 空振りの受信(これも課金される)が減り、届いたメッセージはすぐに返ってくる。
	 */
	private static final int WAIT_TIME_SECONDS = 20;

	@Bean
	SqsReceiveLoop authMailReceiveLoop(SqsClient sqsClient, AppProperties appProperties, JsonMapper jsonMapper,
			AuthMailWorker authMailWorker, ConfigurableApplicationContext context) {
		String queueUrl = appProperties.sqs().authMailQueueUrl();
		// Web と違い、ワーカーはキューが分からなければ仕事が無い。黙って起動せず、ここで止める
		if (queueUrl == null || queueUrl.isBlank()) {
			throw new IllegalStateException("ワーカーとして起動するには SQS_AUTH_MAIL_QUEUE_URL が必要です");
		}
		return new SqsReceiveLoop("auth-mail", sqsClient, queueUrl, WAIT_TIME_SECONDS,
				body -> {
					// 形式が壊れていれば例外になり、削除されずに再配信され、5 回目で DLQ に移る(決定10)
					AuthMailRequestedEvent request = jsonMapper.readValue(body, AuthMailRequestedEvent.class);
					AuthMailWorker.Outcome outcome = authMailWorker.process(request);
					log.debug("requestId={} の処理結果: {}", request.requestId(), outcome);
				},
				() -> exit(context));
	}

	/**
	 * 受信スレッドが想定外に終わったとき、アプリごと終了する(決定13)。
	 * プロセスが終われば ECS がタスクを入れ替える。生きているのに受信していない状態を残さない。
	 *
	 * <p><b>呼ばれ方:</b>{@link #authMailReceiveLoop} が {@link SqsReceiveLoop} に渡したラムダ {@code () -> exit(context)} の中から呼ばれる。
	 * このラムダは {@code SqsReceiveLoop} のフィールド {@code onUnexpectedExit} に入っており、型は {@link Runnable} なので、
	 * {@code run()} を呼ぶとラムダの中身、つまり {@code exit(context)} が実行される。
	 * {@code run()} を呼ぶのは {@code SqsReceiveLoop#run()} の {@code finally} にある {@code onUnexpectedExit.run()} で、
	 * {@code stop()} で止められずに受信ループを抜けたときだけ、受信スレッドが呼ぶ。
	 *
	 * <p><b>このメソッドがすること:</b>新しいスレッド({@code auth-mail-exit})を作って {@code start()} するだけで、すぐに戻る。
	 * Spring の終了({@code SpringApplication.exit}。終了コード 1 を返す)と JVM の終了({@code System.exit})は、
	 * その新しいスレッドの中で順に実行される。
	 *
	 * <p>別スレッドで行うのは、Spring の終了処理が受信ループの {@code stop()} を呼び、{@code stop()} が受信スレッドの終わりを待つため。
	 * 受信スレッドから直接呼ぶと、自分自身の終わりを待つことになる。
	 */
	private static void exit(ConfigurableApplicationContext context) {
		new Thread(() -> System.exit(SpringApplication.exit(context, () -> 1)), "auth-mail-exit").start();
	}
}
