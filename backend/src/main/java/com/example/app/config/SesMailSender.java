package com.example.app.config;

import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

/**
 * SES の API でメールを送る {@link MailSender} の実装。
 *
 * <p>Bean として登録する条件は {@link MailSenderConfig} 側にある。
 *
 * <p>{@code JavaMailSender} ではなく {@code MailSender} を実装しているのは、このアプリが送るのが
 * <b>プレーンテキストのメール 2 種類だけ</b>({@link com.example.app.auth.AuthMailSender})で、
 * {@code MimeMessage} を組み立てる機能が要らないため。添付や HTML が必要になったら
 * {@code SendEmailRequest} の {@code content.raw} に MIME を渡す形へ広げる。
 */
class SesMailSender implements MailSender {

	private final SesV2Client client;

	SesMailSender(SesV2Client client) {
		this.client = client;
	}

	@Override
	public void send(SimpleMailMessage simpleMessage) throws MailException {
		send(new SimpleMailMessage[] { simpleMessage });
	}

	/**
	 * 複数通を送る側が本体。1 通ずつ SES に投げる。
	 *
	 * <p>途中で失敗したら、その時点で {@code MailSendException} を投げて残りは送らない。
	 * この判断ができるのは、呼び出し元が<b>常に 1 通しか渡さない</b>ため
	 * ({@code AuthMailSender} は 1 通ずつ送る)。複数通を渡す用途が出てきたら、
	 * 「1 通の失敗で残りを止めるべきか」を改めて決める必要がある。
	 */
	@Override
	public void send(SimpleMailMessage... simpleMessages) throws MailException {
		for (SimpleMailMessage message : simpleMessages) {
			try {
				client.sendEmail(toRequest(message));
			} catch (RuntimeException e) {
				// SES の例外(未検証のアドレス・送信上限・権限不足など)はすべて
				// SDK 固有の型なので、Spring の階層に載せ替えて呼び出し元の catch を保つ。
				// AuthMailSender は MailException を捕まえてログに残す作りになっている。
				throw new MailSendException("SES へのメール送信に失敗しました", e);
			}
		}
	}

	private SendEmailRequest toRequest(SimpleMailMessage message) {
		return SendEmailRequest.builder()
				.fromEmailAddress(message.getFrom())
				.destination(Destination.builder().toAddresses(message.getTo()).build())
				.content(EmailContent.builder()
						.simple(Message.builder()
								.subject(utf8(message.getSubject()))
								.body(Body.builder().text(utf8(message.getText())).build())
								.build())
						.build())
				.build();
	}

	/**
	 * 文字集合を明示する。既定のままだと日本語の件名・本文が化ける。
	 */
	private Content utf8(String value) {
		return Content.builder().data(value).charset("UTF-8").build();
	}
}
