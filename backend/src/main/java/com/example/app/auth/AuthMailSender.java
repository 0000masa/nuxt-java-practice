package com.example.app.auth;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.mail.MailException;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.stereotype.Component;

import com.example.app.config.AppProperties;

/**
 * 認証メール(メール確認 / パスワードリセット)の本文組み立てと送信。
 *
 * <p>本文はプレーンテキスト。送るのが 2 種類だけなのでテンプレートエンジンは導入していない。
 *
 * <p>開発では Mailpit が受け取る。ブラウザで http://localhost:8025 を開くと届いたメールが見られる。
 * 本番は SES の API 経路。<b>どちらが注入されるかは {@link com.example.app.config.MailSenderConfig} が決める</b>ので、
 * ここは MailSender インターフェースだけを知っていればよい。
 */
//これを付けておくと、Spring は起動時にこのクラスを見つけて インスタンスを 1 個だけ自動で作り、以後アプリの中で使い回します。
@Component
class AuthMailSender {

	private final MailSender mailSender;
	private final AppProperties appProperties;

	AuthMailSender(MailSender mailSender, AppProperties appProperties) {
		this.mailSender = mailSender;
		this.appProperties = appProperties;
	}

	/**
	 * 認証メールを組み立てて送る。<b>ワーカーの {@link AuthMailWorker} から、トランザクションの中で呼ばれる。</b>
	 *
	 * <p>以前は Web のコミット後に呼ばれ、送信の失敗をログに残して握りつぶしていた。
	 * 今は逆で、<b>失敗は例外のまま投げる</b>。ワーカーのトランザクションがロールバックされ、
	 * メッセージは削除されずに SQS から再配信される。それがリトライになる(フェーズ20 の決定8・10)。
	 *
	 * @throws MailException 送信に失敗したとき
	 */
	void send(String toEmail, AuthTokenPurpose purpose, String rawToken) {
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(appProperties.mail().from());
		message.setTo(toEmail);
		message.setSubject(subject(purpose));
		message.setText(text(purpose, rawToken));
		mailSender.send(message);
	}

	private String subject(AuthTokenPurpose purpose) {
		return switch (purpose) {
			case EMAIL_VERIFICATION -> "[投稿アプリ] メールアドレスの確認";
			case PASSWORD_RESET -> "[投稿アプリ] パスワードの再設定";
		};
	}

	private String text(AuthTokenPurpose purpose, String rawToken) {
		String url = link(purpose, rawToken);
		return switch (purpose) {
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
	private String link(AuthTokenPurpose purpose, String rawToken) {
		String path = switch (purpose) {
			case EMAIL_VERIFICATION -> "/verify-email";
			case PASSWORD_RESET -> "/password-reset/confirm";
		};
		// Base64URL は URL に使える文字だけで構成されるが、値の作り方を変えたときに壊れないよう明示的に符号化する。
		return appProperties.baseUrl() + path + "?token="
				+ URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
	}
}
