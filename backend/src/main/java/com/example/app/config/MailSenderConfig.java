package com.example.app.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.MailSender;

import software.amazon.awssdk.services.sesv2.SesV2Client;

/**
 * メールの送信経路の切り替え(開発 = SMTP / 本番 = SES の API)。
 *
 * <p>設計 → docs/superpowers/specs/2026-08-19-phase13-cloudformation-design.md の決定4
 *
 * <p>開発は Mailpit(認証なしの SMTP)で受けるので、Spring Boot が
 * {@code spring.mail.host} から自動で作る {@code JavaMailSenderImpl} をそのまま使う。
 * {@code JavaMailSender} は {@code MailSender} を継承しているので、送信側
 * ({@link com.example.app.auth.AuthMailSender})が {@code MailSender} だけを知っていれば両方に対応できる。
 *
 * <p>本番で SMTP を使わない理由は、SES の SMTP パスワードが
 * <b>IAM ユーザーのシークレットキーから HMAC で導出する値</b>で CloudFormation では生成できず、
 * IAM ユーザーの長期クレデンシャルを手動で常駐させることになるため。
 * API 経路ならタスクロールの {@code ses:SendEmail} だけで送れる。
 */
// 1. Spring が起動し、@Configuration の付いたクラスを探して MailSenderConfig を見つける。
// 2. 中の @Bean メソッドを見つけるが、まだ実行はしない。先に @ConditionalOnProperty の条件を確認する。
// 3. 設定 app.mail.transport の値を読む。ses でなければ、この2つの Bean 定義は登録されずに終わる
//    （＝開発環境ではこのファイルは実質何もしない）。
// 4. ses だった場合、SesV2Client と MailSender の Bean 定義が登録される。まだ実行はされない。
// 5. その後で Spring Boot の自動設定 MailSenderAutoConfiguration が処理される。これは
//    @ConditionalOnMissingBean(MailSender.class) なので、4 の定義があると自動設定ごと降り、
//    SMTP 側の Bean 定義は作られない。MailSender の候補は 1 つだけになる。
// 6. すべての定義が出そろってから、実際に sesV2Client() が実行され、その戻り値が
//    sesMailSender(...) の引数として渡される（これが DI）。
// 7. 以後、AuthMailSender がメールを送るたびに、この SES 版が使われる。
@Configuration
public class MailSenderConfig {

	/**
	 * SES への接続クライアント。{@code app.mail.transport=ses} のときだけ作る。
	 *
	 * <p>リージョンと資格情報は AWS SDK の既定の解決順序に任せる。ECS のタスクは
	 * {@code AWS_REGION} と、タスクロールを引くための資格情報エンドポイントを自動で持つので、
	 * アプリ側に設定を書く必要がない。
	 */
	//@Bean は「自分では書き換えられない他人のクラスを登録する」ときに使います。
	// SesV2Client は AWS が提供するクラスなので @Component を貼りに行けません。だから @Bean メソッドで包む必要があります。
	@Bean
	@ConditionalOnProperty(name = "app.mail.transport", havingValue = "ses")
	SesV2Client sesV2Client() {
		return SesV2Client.create();
	}

	/**
	 * SES 経路の送信実装。
	 *
	 * <p><b>この Bean を登録すると SMTP 側の Bean は作られない。</b>
	 * Boot の {@code MailSenderAutoConfiguration} には {@code @ConditionalOnMissingBean(MailSender.class)}
	 * が付いており、自前の {@code MailSender} があると自動設定ごと降りる
	 * ({@code application.yml} が {@code spring.mail.host} に {@code localhost} という既定値を
	 * 持っていても関係ない)。ユーザー定義の {@code @Configuration} は自動設定より先に処理されるので、
	 * この判定ではこちらの Bean 定義が先に存在している。
	 *
	 * <p>逆に {@code app.mail.transport} が {@code ses} 以外なら、この {@code @Bean} メソッドが
	 * 飛ばされて {@code MailSender} が 1 つも無い状態になり、自動設定が {@code JavaMailSenderImpl} を作る。
	 * どちらの経路でも<b>候補は常に 1 つ</b>なので、{@code @Primary} で優先順位を付ける必要はない
	 * (Spring Boot 4.1.0 で実測。→ docs/notes/java/spring/mail-sending-and-transport-switching.md)。
	 */
	@Bean
	@ConditionalOnProperty(name = "app.mail.transport", havingValue = "ses")
	MailSender sesMailSender(SesV2Client sesV2Client) {
		return new SesMailSender(sesV2Client);
	}
}
