package com.example.app.auth;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.app.user.User;
import com.example.app.user.UserRepository;

/**
 * Google ログインで「誰としてログインするか」を決める役。
 *
 * <p>設計 → docs/superpowers/specs/2026-08-15-phase4-google-auth-design.md §5
 * <p>方針の理由 → docs/adr/0004-google-account-linking.md
 *
 * <p>引数が OIDC のクレームそのものではなく素の値なのは意図的。Spring Security の型
 * ({@code OidcUser} や {@code OidcUserRequest})は {@link AppOidcUserService} で止めてあり、
 * ここから先はアプリの言葉だけで書ける。テストで {@code ClientRegistration} や
 * {@code OidcIdToken} を組み立てずに全分岐を確かめられるのはこのため。
 */
@Service
public class GoogleAccountService {

	private final UserRepository userRepository;
	private final UsernameGenerator usernameGenerator;

	GoogleAccountService(UserRepository userRepository, UsernameGenerator usernameGenerator) {
		this.userRepository = userRepository;
		this.usernameGenerator = usernameGenerator;
	}

	/**
	 * Google から届いた情報に対応する User を返す。必要なら作る・紐づける。
	 *
	 * @param sub           OIDC の不変 ID。ユーザー特定の第一の鍵
	 * @param email         Google 側のメールアドレス
	 * @param emailVerified Google がそのメールアドレスの所有権を確認済みか
	 * @param name          Google の表示名。無いこともある
	 */
	@Transactional
	public User resolve(String sub, String email, boolean emailVerified, String name) {
		Optional<User> linked = userRepository.findByGoogleSub(sub);
		if (linked.isPresent()) {
			// ここでは email_verified を見ない。sub が一致した時点で「過去に一度メールの所有権を
			// 確認した、同じ Google アカウント」だと確定しているため(決定5)。
			//
			// Google 側でメールアドレスを変えていても users.email は更新しない。この列は
			// 「このアプリが確認済みの、パスワードリセットの送り先」という別の意味を持っており、
			// 未確認のアドレスをそこに座らせるわけにいかない(決定3 → ADR-0004)。
			return linked.get();
		}

		// ここから先はメールアドレスを鍵にして人物を特定する。Google が所有権を確認していない
		// アドレスを信じると、他人を名乗る Google アカウントで既存アカウントを乗っ取れてしまう。
		if (!emailVerified) {
			throw new GoogleLoginNotAllowedException(GoogleLoginNotAllowedException.EMAIL_UNVERIFIED,
					"Google アカウントのメールアドレスが確認されていません: " + email);
		}

		Optional<User> sameEmail = userRepository.findByEmail(email);
		if (sameEmail.isPresent()) {
			User user = sameEmail.get();
			if (user.getEmailVerifiedAt() != null) {
				// アカウントリンク。id はそのままなので、投稿もいいねも引き継がれる。
				//
				// 既に別の google_sub が入っていた場合は上書きになる。同じメールアドレスの
				// 所有権を確認済みの Google アカウントが 2 つある状況なので同一人物とみなす。
				// 弾くと「どちらの Google でもログインできない」という戻れない状態を作る。
				//なぜ弾かないのか
				// users テーブルは google_sub を 1 つしか持てません。ここで「既に別の sub が入っているので拒否」とすると、
				// 先に登録されたほうの Google アカウントでしかログインできない状態が固定されます。上の例のように古いアカウントが既に消えている場合
				// 、その sub は二度と現れないので、Google ログインの経路が死んだままになります。
				// 上書きを許すと、両方のアカウントが生きている場合でも破綻しません。
				// A でログインすれば google_sub は A に戻り(44行目は外れるが 62行目のメールアドレス一致で拾われる)、B でログインすれば B になる。
				// 毎回どちらでも通るので、締め出される人が出ません
				user.setGoogleSub(sub);
				return user;
			}
			// 未確認アカウント。リンクせず作り直す。
			//
			// リンクして確認済みにすると、攻撃者が先にこのメールアドレスで登録しておいた
			// パスワードごとアカウントが有効化される(pre-hijacking)。会員登録と同じ対処
			// → docs/adr/0003-account-enumeration-and-unverified-signup.md
			// これを「無駄な削除」と見て消すのをやめると、その脆弱性が戻ってくる。
			userRepository.delete(user);
			// INSERT が先に走ってメールアドレスの UNIQUE 制約に当たらないよう、順序を確定させる
			// (JPA は SQL の発行順を自分で決めるため)。AuthService.signup と同じ理由。
			userRepository.flush();
		}

		return create(sub, email, name);
	}

	private User create(String sub, String email, String name) {
		String username = usernameGenerator.generateFrom(email);
		User user = new User(username, displayNameOf(name, username), email);
		user.setGoogleSub(sub);
		// Google が所有権を確認済みのメールアドレスなので、確認メールは送らない
		// (設計概要 §3「Google 登録は確認済み扱いでスキップ」)。
		user.setEmailVerifiedAt(LocalDateTime.now());
		// password_hash は NULL のまま。パスワードログインはできず、欲しくなったら
		// パスワードリセットの経路で設定する(→ docs/api/request-password-reset.md)。
		return userRepository.save(user);
	}

	/** users.display_name は VARCHAR(50) かつ NOT NULL。name が無い Google アカウントもある。 */
	private String displayNameOf(String name, String fallback) {
		if (name == null || name.isBlank()) {
			return fallback;
		}
		return name.length() > 50 ? name.substring(0, 50) : name;
	}
}
