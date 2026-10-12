package com.example.app.auth;

import java.time.Duration;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.app.common.queue.ProcessedMessage;
import com.example.app.common.queue.ProcessedMessageRepository;
import com.example.app.user.User;
import com.example.app.user.UserRepository;

/**
 * 認証メールの依頼を 1 件処理する(ワーカー側の処理本体)。
 *
 * <p>SQS からの受信は {@link com.example.app.common.queue.SqsReceiveLoop} の仕事で、このクラスはキューを知らない。
 * そのためテストでは受信ループを通さず、このメソッドを直接呼んで確かめられる。
 *
 * <p>1 件の処理は 1 つのトランザクションで、次の順に進む(フェーズ20 の決定8)。
 * <ol>
 * <li>処理済みの記録に request_id があれば、何もしない(重複配信)</li>
 * <li>処理済みとして記録する(まだコミットしない)</li>
 * <li>処理する時点の状態を確かめ、送る必要が無ければここで終わる</li>
 * <li>トークンを発行し、メールを送る</li>
 * <li>コミットする(メソッドを抜けたとき)</li>
 * </ol>
 *
 * <p><b>「送ってからコミット」の順が肝。</b>逆(記録をコミットしてから送る)にすると、送信に失敗して再配信されたとき
 * 記録があるので「処理済み」と判断され、メールが永久に送られない。この順なら、送信に失敗すれば記録ごとロールバックされ、
 * 再配信でやり直せる。代わりに「送った直後、コミットの前に死ぬ」と再配信でもう 1 通届く。
 * 外部への副作用(メール送信)は DB のトランザクションに入らないので、「ちょうど 1 回」は作れず、
 * 失わない側に倒している。
 *
 * <p>Web では避けた「SES の往復の間トランザクションを開いたままにする」形を、ここでは許している。
 * ワーカーはユーザーを待たせないため。
 */
@Service
class AuthMailWorker {

	private static final Logger log = LoggerFactory.getLogger(AuthMailWorker.class);

	enum Outcome {
		/** メールを送った */
		SENT,
		/** 送る必要がなくなっていた(確認済み・ユーザーがいない・依頼の後にパスワードが変わった) */
		SKIPPED,
		/** 処理済みの依頼が再び届いた(重複配信) */
		DUPLICATE
	}

	private final ProcessedMessageRepository processedMessageRepository;
	private final UserRepository userRepository;
	private final AuthTokenService authTokenService;
	private final AuthMailSender authMailSender;

	AuthMailWorker(ProcessedMessageRepository processedMessageRepository, UserRepository userRepository,
			AuthTokenService authTokenService, AuthMailSender authMailSender) {
		this.processedMessageRepository = processedMessageRepository;
		this.userRepository = userRepository;
		this.authTokenService = authTokenService;
		this.authMailSender = authMailSender;
	}

	/**
	 * <b>readOnly = true を付けない。</b>読み取り専用のトランザクションは Aurora の Reader に繋がり、
	 * Web がコミットした直後のユーザーがレプリカの遅れでまだ見えないことがある(決定9、ADR-0016)。
	 *
	 * <p>timeout は、DB の応答が無いまま受信スレッドが止まり続けないようにするため(決定13)。
	 * SES の呼び出しには SDK 側のタイムアウトが別に付いている(→ config/MailSenderConfig)。
	 */
	@Transactional(timeout = 30)
	Outcome process(AuthMailRequestedEvent request) {
		if (processedMessageRepository.existsById(request.requestId())) {
			log.info("処理済みの依頼です。何もしません。requestId={}", request.requestId());
			return Outcome.DUPLICATE;
		}
		// saveAndFlush で INSERT をこの場で流す。同時に 2 つのワーカーが同じ依頼を処理しようとした場合、
		// 後の側はこの INSERT で先の側のコミットを待たされ、主キーの重複で失敗する(→ V5 のコメント)
		processedMessageRepository.saveAndFlush(new ProcessedMessage(request.requestId()));

		User user = userRepository.findById(request.userId()).orElse(null);
		String skipReason = skipReason(user, request);
		if (skipReason != null) {
			// 送らない場合も記録はコミットする。再配信されても同じ判断を繰り返さずに済む
			log.info("送る必要がなくなっていたので送りません。requestId={} userId={} 用途={} 理由={}",
					request.requestId(), request.userId(), request.purpose(), skipReason);
			return Outcome.SKIPPED;
		}

		String rawToken = authTokenService.issue(user, request.purpose(), validFor(request.purpose()));
		authMailSender.send(user.getEmail(), request.purpose(), rawToken);
		log.info("認証メールを送りました。requestId={} userId={} 用途={}",
				request.requestId(), request.userId(), request.purpose());
		return Outcome.SENT;
	}

	/**
	 * 処理する時点の状態を確かめる(決定11)。Web は依頼を受けた時点で同じことを確かめているが、
	 * キューで待っている間に状態が変わっていることがある。送ってよければ null。
	 */
	private String skipReason(User user, AuthMailRequestedEvent request) {
		if (user == null) {
			// 未確認のまま同じアドレスで登録し直されると、ユーザーは作り直されて ID が変わる(ADR-0003)
			return "ユーザーがいない";
		}
		return switch (request.purpose()) {
			case EMAIL_VERIFICATION -> user.getEmailVerifiedAt() != null ? "確認済み" : null;
			case PASSWORD_RESET -> {
				if (user.getEmailVerifiedAt() == null) {
					yield "メール未確認";
				}
				// パスワード変更は発行済みのリセットリンクを失効させる(AuthService#changePassword)。
				// ところがトークンの発行がワーカーに移ったので、「依頼 → 変更 → ここで発行」の順になると、
				// 失効をすり抜けたリンクが届いてしまう。依頼より後に変わっていれば送らないことで塞ぐ
				if (user.getPasswordChangedAt() != null && user.getPasswordChangedAt()
						.atZone(ZoneId.systemDefault()).toInstant().isAfter(request.requestedAt())) {
					yield "依頼の後にパスワードが変更された";
				}
				yield null;
			}
		};
	}

	private Duration validFor(AuthTokenPurpose purpose) {
		return switch (purpose) {
			case EMAIL_VERIFICATION -> AuthTokenService.EMAIL_VERIFICATION_TTL;
			case PASSWORD_RESET -> AuthTokenService.PASSWORD_RESET_TTL;
		};
	}
}
