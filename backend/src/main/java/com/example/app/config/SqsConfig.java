package com.example.app.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

/**
 * SQS への接続クライアント。Web(送る側)とワーカー(受け取る側)の両方が使う。
 *
 * <p>本番はリージョンも資格情報も AWS SDK の既定の解決順序に任せる(SES と同じ。→ {@link MailSenderConfig})。
 * 開発とテストは {@code app.sqs.endpoint} で ElasticMQ に向ける。
 *
 * <p><b>Bean を作るだけでは SQS に繋がない。</b>最初の送信・受信で初めて通信し、資格情報もそのとき解決する。
 * そのためキューの URL を持たない環境(migrate のタスクなど)でも起動は失敗しない。
 * ただしリージョンだけは作る時点で要る。ECS のタスクは {@code AWS_REGION} を自動で持つので問題にならないが、
 * {@code app.sqs.endpoint} も {@code AWS_REGION} も無いローカルでは起動に失敗する(開発は .env が endpoint を渡す)。
 */
@Configuration
public class SqsConfig {

	/**
	 * 1 回の呼び出し全体の上限。受信のロングポーリング(最大 20 秒)より長くする。
	 * 外部への通信にはすべてタイムアウトを付け、固まる代わりに例外にする(決定13)。
	 * 例外になれば、受信ループの通常のエラー処理(削除しない → 再配信)に乗る。
	 */
	private static final Duration API_CALL_TIMEOUT = Duration.ofSeconds(30);

	/** SDK が自動でリトライするときの、1 回あたりの上限 */
	private static final Duration API_CALL_ATTEMPT_TIMEOUT = Duration.ofSeconds(25);

	@Bean
	SqsClient sqsClient(AppProperties appProperties) {
		SqsClientBuilder builder = SqsClient.builder()
				.overrideConfiguration(config -> config
						.apiCallTimeout(API_CALL_TIMEOUT)
						.apiCallAttemptTimeout(API_CALL_ATTEMPT_TIMEOUT));

		AppProperties.Sqs sqs = appProperties.sqs();
		if (sqs.hasEndpointOverride()) {
			// ElasticMQ は署名を検証しないので、資格情報もリージョンも何でもよい。
			// ただし SDK は「無い」と通信の前に失敗するので、ダミーを入れておく。
			builder.endpointOverride(URI.create(sqs.endpoint()))
					.region(Region.AP_NORTHEAST_1)
					.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")));
		}
		return builder.build();
	}
}
