package com.example.app.auth;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.example.app.config.AppProperties;

/**
 * 認証メール(メール確認 / パスワードリセット)の本文組み立てと送信。
 *
 * <p>本文はプレーンテキスト。送るのが 2 種類だけなのでテンプレートエンジンは導入していない。
 *
 * <p>開発では Mailpit が受け取る。ブラウザで http://localhost:8025 を開くと届いたメールが見られる。
 */
//これを付けておくと、Spring は起動時にこのクラスを見つけて インスタンスを 1 個だけ自動で作り、以後アプリの中で使い回します。
@Component
class AuthMailSender {

	private static final Logger log = LoggerFactory.getLogger(AuthMailSender.class);

	private final JavaMailSender mailSender;
	private final AppProperties appProperties;

	AuthMailSender(JavaMailSender mailSender, AppProperties appProperties) {
		this.mailSender = mailSender;
		this.appProperties = appProperties;
	}

	/**
	 * phase = AFTER_COMMIT … 発行元のトランザクションがコミットされた<b>後</b>に呼ばれる。
	 *
	 * <p>これにより「メールは届いたのに登録はロールバックされていた」という食い違いが起きない。
	 * 逆向きの食い違い(登録はできたがメールが届かない)は起こり得るので、確認メールの再送で救う。
	 *
	 * <p>送信失敗をここで握りつぶしているのは意図的。コミット後に例外を投げると、
	 * DB 上は登録が成立しているのに利用者には 500 が返る、という最も分かりにくい状態になる。
	 * 記録を残して再送に誘導するほうが復帰しやすい(設計の決定7)。
	 */
	//このアノテーションがあることによってeventPublisher.publishEvent()で発行されたイベントを受け取ることができる。
	//@TransactionalEventListener には、素の @EventListener には無い条件が 2 つ乗っています。
	// - 呼ばれるタイミングが遅れる — 発行元のトランザクションがコミットされた後（AFTER_COMMIT 指定のため）
	// - トランザクションの中で発行されないと、そもそも呼ばれない
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onAuthMailRequested(AuthMailRequestedEvent event) {
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(appProperties.mail().from());
		message.setTo(event.toEmail());
		message.setSubject(subject(event.purpose()));
		message.setText(text(event));

		try {
			mailSender.send(message);
		} catch (MailException e) {
			log.error("認証メールの送信に失敗しました。宛先={} 用途={}", event.toEmail(), event.purpose(), e);
		}
	}

	private String subject(AuthTokenPurpose purpose) {
		return switch (purpose) {
			case EMAIL_VERIFICATION -> "[投稿アプリ] メールアドレスの確認";
			case PASSWORD_RESET -> "[投稿アプリ] パスワードの再設定";
		};
	}

	private String text(AuthMailRequestedEvent event) {
		String url = link(event);
		return switch (event.purpose()) {
			case EMAIL_VERIFICATION -> """
					投稿アプリへのご登録ありがとうございます。

					以下のリンクを開くと、メールアドレスの確認が完了してログインできるようになります。

					%s

					このリンクは24時間で無効になります。
					心当たりがない場合は、このメールを破棄してください。
					""".formatted(url);
			case PASSWORD_RESET -> """
					パスワードの再設定が申請されました。

					以下のリンクから新しいパスワードを設定してください。

					%s

					このリンクは1時間で無効になります。
					再設定が完了すると、ログイン中のすべての端末からログアウトされます。
					心当たりがない場合は、このメールを破棄してください。パスワードは変更されません。
					""".formatted(url);
		};
	}

	/**
	 * メールに載せるリンク。<b>バックエンドではなくフロントのページ</b>に向ける(設計の決定5)。
	 * 遷移先のページが token をクエリから取り出し、POST で API を叩いて処理を完了させる。
	 */
	private String link(AuthMailRequestedEvent event) {
		String path = switch (event.purpose()) {
			case EMAIL_VERIFICATION -> "/verify-email";
			case PASSWORD_RESET -> "/password-reset/confirm";
		};
		// Base64URL は URL に使える文字だけで構成されるが、値の作り方を変えたときに壊れないよう明示的に符号化する。
		return appProperties.baseUrl() + path + "?token="
				+ URLEncoder.encode(event.rawToken(), StandardCharsets.UTF_8);
	}
}
