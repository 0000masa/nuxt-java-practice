package com.example.app.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.example.app.common.queue.ProcessedMessageRepository;
import com.example.app.user.User;
import com.example.app.user.UserRepository;

import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * 認証メールのワーカーの処理本体({@link AuthMailWorker#process})のテスト。
 *
 * <p>受信ループ(SQS)は通さず、依頼を直接渡す。確かめたいのは「1 件の依頼を受けたときの判断」で、
 * それは本番で再現しにくい場面(重複配信、キューで待つ間の状態の変化、送信の失敗)ほど大事になる
 * (→ docs/superpowers/specs/2026-10-11-phase20-sqs-auth-mail-design.md の決定21)。
 *
 * <p>{@code @MockitoBean} の組み合わせを {@link AuthFlowTest} と揃えているのは、Spring のコンテキストを使い回させるため
 * (組み合わせが違うと別のコンテキストとして起動し直す)。SqsClient はこのテストでは使わない。
 *
 * <p>トランザクションで囲まない。{@code process} 自身のコミットとロールバックを確かめたいため。作ったデータは自分で消す。
 */
@SpringBootTest
class AuthMailWorkerTest {

	private static final String EMAIL = "worker-test@example.com";

	@MockitoBean
	JavaMailSender mailSender;

	@MockitoBean
	SqsClient sqsClient;

	@Autowired
	AuthMailWorker authMailWorker;

	@Autowired
	UserRepository userRepository;

	@Autowired
	ProcessedMessageRepository processedMessageRepository;

	/** このテストで作った依頼の ID。後片付けで処理済みの記録を消す */
	private final List<String> requestIds = new ArrayList<>();

	@BeforeEach
	@AfterEach
	void cleanUp() {
		userRepository.findByEmail(EMAIL).ifPresent(userRepository::delete);
		processedMessageRepository.deleteAllById(requestIds);
	}

	@Test
	@DisplayName("未確認のユーザーへのメール確認の依頼 → メールを送る")
	void sendsVerificationMail() {
		User user = saveUser(false);

		AuthMailWorker.Outcome outcome = authMailWorker.process(request(user, AuthTokenPurpose.EMAIL_VERIFICATION));

		assertThat(outcome).isEqualTo(AuthMailWorker.Outcome.SENT);
		verify(mailSender).send(any(SimpleMailMessage.class));
	}

	@Test
	@DisplayName("同じ依頼が 2 回届いた(重複配信)→ 2 回目は何もしない")
	void ignoresDuplicateDelivery() {
		User user = saveUser(false);
		AuthMailRequestedEvent request = request(user, AuthTokenPurpose.EMAIL_VERIFICATION);

		authMailWorker.process(request);
		AuthMailWorker.Outcome second = authMailWorker.process(request);

		assertThat(second).isEqualTo(AuthMailWorker.Outcome.DUPLICATE);
		// 2 回目もトークンを発行してしまうと、1 通目のリンクが失効する。メールは 1 通だけ
		verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
	}

	@Test
	@DisplayName("キューで待つ間にメール確認が済んだ → 送らない")
	void skipsWhenAlreadyVerified() {
		User user = saveUser(true);

		AuthMailWorker.Outcome outcome = authMailWorker.process(request(user, AuthTokenPurpose.EMAIL_VERIFICATION));

		assertThat(outcome).isEqualTo(AuthMailWorker.Outcome.SKIPPED);
		verify(mailSender, never()).send(any(SimpleMailMessage.class));
	}

	@Test
	@DisplayName("キューで待つ間にユーザーが作り直された(ID が無い)→ 送らない")
	void skipsWhenUserIsGone() {
		// ユーザーを保存せずに、record のコンストラクタで依頼を直接作っている
		// 「作り直し」を本当に再現するなら、ユーザーを保存して削除する手順になる。
		// しかし、このテストが確かめたいのは「ID のユーザーが見つからないときにどうするか」だけ。
		// 存在しない ID を渡すほうが簡単で、結果も同じになる
		// DB に絶対に存在しない IDにするためにLong.MAX_VALUEにしてる
		AuthMailRequestedEvent request = track(new AuthMailRequestedEvent(
				UUID.randomUUID().toString(), Long.MAX_VALUE, AuthTokenPurpose.EMAIL_VERIFICATION, Instant.now()));

		AuthMailWorker.Outcome outcome = authMailWorker.process(request);

		assertThat(outcome).isEqualTo(AuthMailWorker.Outcome.SKIPPED);
		verify(mailSender, never()).send(any(SimpleMailMessage.class));
	}

	@Test
	@DisplayName("リセットを依頼した後にパスワードが変更された → 送らない(失効のすり抜けを防ぐ)")
	void skipsResetWhenPasswordChangedAfterRequest() {
		User user = saveUser(true); // saveUser の中でパスワードを設定するので、password_changed_at は今になる
		// 依頼はそれより前(1 分前)に出ていた、という状況を作る
		AuthMailRequestedEvent request = track(new AuthMailRequestedEvent(UUID.randomUUID().toString(),
				user.getId(), AuthTokenPurpose.PASSWORD_RESET, Instant.now().minusSeconds(60)));

		AuthMailWorker.Outcome outcome = authMailWorker.process(request);

		assertThat(outcome).isEqualTo(AuthMailWorker.Outcome.SKIPPED);
		verify(mailSender, never()).send(any(SimpleMailMessage.class));
	}

	@Test
	@DisplayName("パスワードの変更より後のリセットの依頼 → 送る")
	void sendsResetWhenRequestedAfterPasswordChange() {
		User user = saveUser(true);
		AuthMailRequestedEvent request = track(new AuthMailRequestedEvent(UUID.randomUUID().toString(),
				user.getId(), AuthTokenPurpose.PASSWORD_RESET, Instant.now().plusSeconds(1)));

		AuthMailWorker.Outcome outcome = authMailWorker.process(request);

		assertThat(outcome).isEqualTo(AuthMailWorker.Outcome.SENT);
	}

	@Test
	@DisplayName("メール送信に失敗 → 処理済みの記録もロールバックされ、再配信でやり直せる")
	void rollsBackRecordWhenSendingFails() {
		User user = saveUser(false);
		AuthMailRequestedEvent request = request(user, AuthTokenPurpose.EMAIL_VERIFICATION);
		doThrow(new MailSendException("SMTP が応答しない")).when(mailSender).send(any(SimpleMailMessage.class));

		// 例外がそのまま投げられる → 受信ループはメッセージを削除しない → SQS が再配信する
		assertThatThrownBy(() -> authMailWorker.process(request)).isInstanceOf(MailSendException.class);
		// 「送ってからコミット」なので、記録は残っていない。残っていると、再配信が「処理済み」として捨てられ、永久に届かない
		assertThat(processedMessageRepository.existsById(request.requestId())).isFalse();

		// 再配信(2 回目)では送信が通る。reset で「例外を投げる」指定を外すと、send は何もせず成功する
		reset(mailSender);
		assertThat(authMailWorker.process(request)).isEqualTo(AuthMailWorker.Outcome.SENT);
	}

	private User saveUser(boolean verified) {
		User user = new User("workertest", "ワーカー試験", EMAIL);
		user.setPasswordHash("{noop}dummy");
		if (verified) {
			user.setEmailVerifiedAt(LocalDateTime.now());
		}
		return userRepository.save(user);
	}

	private AuthMailRequestedEvent request(User user, AuthTokenPurpose purpose) {
		return track(AuthMailRequestedEvent.of(user.getId(), purpose));
	}

	private AuthMailRequestedEvent track(AuthMailRequestedEvent request) {
		requestIds.add(request.requestId());
		return request;
	}
}
