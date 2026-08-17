package com.example.app.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import com.example.app.user.User;
import com.example.app.user.UserRepository;

/**
 * Google ログインで「誰としてログインするか」を決める分岐の検証。
 *
 * <p>設計 → docs/superpowers/specs/2026-08-15-phase4-google-auth-design.md §5
 * <p>方針 → docs/adr/0004-google-account-linking.md
 *
 * <p>Spring Security の型を一切使っていないことに注目。{@link GoogleAccountService} が
 * OIDC のクレームではなく素の引数を受け取る形にしてあるので、{@code ClientRegistration} や
 * {@code OidcIdToken} を組み立てずに全分岐を確かめられる。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ GoogleAccountService.class, UsernameGenerator.class })
class GoogleAccountServiceTest {

	@Autowired
	GoogleAccountService googleAccountService;

	@Autowired
	UserRepository userRepository;

	@Test
	@DisplayName("google_sub が一致したら既存ユーザーを返し、users を増やさない")
	void returnsLinkedUser() {
		User existing = save(user("linked_user", "linked@example.com", sub -> sub.setGoogleSub("sub-linked")));
		long before = userRepository.count();

		User resolved = googleAccountService.resolve("sub-linked", "linked@example.com", true, "リンク済み");

		assertThat(resolved.getId()).isEqualTo(existing.getId());
		assertThat(userRepository.count()).isEqualTo(before);
	}

	@Test
	@DisplayName("google_sub が一致すれば、Google 側のメールが違っても users.email は変えない")
	void doesNotOverwriteEmail() {
		User existing = save(user("keep_email", "old@example.com", u -> u.setGoogleSub("sub-keep")));

		User resolved = googleAccountService.resolve("sub-keep", "new@example.com", true, "変更後の名前");

		assertThat(resolved.getId()).isEqualTo(existing.getId());
		assertThat(resolved.getEmail()).isEqualTo("old@example.com");
	}

	@Test
	@DisplayName("確認済みの既存ユーザーとメールが一致したら、同じ id のまま google_sub を紐づける")
	void linksVerifiedAccount() {
		User existing = save(user("verified_user", "verified@example.com",
				u -> u.setEmailVerifiedAt(LocalDateTime.now())));
		Long idBefore = existing.getId();

		User resolved = googleAccountService.resolve("sub-new", "verified@example.com", true, "本人");

		// id が変わらない = 投稿もいいねも引き継がれる、というのがアカウントリンクの要点。
		assertThat(resolved.getId()).isEqualTo(idBefore);
		assertThat(resolved.getGoogleSub()).isEqualTo("sub-new");
	}

	@Test
	@DisplayName("未確認の既存ユーザーとメールが一致したら、行を作り直してパスワードを捨てる")
	void recreatesUnverifiedAccount() {
		// 攻撃者が victim のメールアドレスで先に登録した状態を作る(pre-hijacking)。
		User unverified = save(user("attacker", "victim@example.com", u -> u.setPasswordHash("{bcrypt}attacker")));
		Long idBefore = unverified.getId();

		User resolved = googleAccountService.resolve("sub-victim", "victim@example.com", true, "victim");

		// ここで flush は要らない。DELETE は resolve() の中の userRepository.flush()
		// (GoogleAccountService.java:90)が既に流しており、INSERT は id が AUTO_INCREMENT 採番
		// (GenerationType.IDENTITY)なので save() の時点で発行済みになる。
		// id が変わる = 別の行になった。攻撃者のパスワードは残っていない。
		assertThat(resolved.getId()).isNotEqualTo(idBefore);
		assertThat(resolved.getPasswordHash()).isNull();
		assertThat(resolved.getEmailVerifiedAt()).isNotNull();
		assertThat(userRepository.findById(idBefore)).isEmpty();
	}

	@Test
	@DisplayName("どちらにも一致しなければ、確認済み・パスワード未設定のユーザーを作る")
	void createsNewUser() {
		User resolved = googleAccountService.resolve("sub-brand-new", "brand.new@example.com", true, "新規 太郎");

		assertThat(resolved.getId()).isNotNull();
		assertThat(resolved.getGoogleSub()).isEqualTo("sub-brand-new");
		assertThat(resolved.getUsername()).isEqualTo("brand_new");
		assertThat(resolved.getDisplayName()).isEqualTo("新規 太郎");
		// 確認メールを送らずに済むのは Google が所有権を確認済みだから(設計概要 §3)。
		assertThat(resolved.getEmailVerifiedAt()).isNotNull();
		// パスワードログインはできない。設定したければパスワードリセット経路を使う。
		assertThat(resolved.getPasswordHash()).isNull();
	}

	@Test
	@DisplayName("email_verified が false なら拒否し、users を一切変更しない")
	void rejectsUnverifiedGoogleEmail() {
		User existing = save(user("target", "target@example.com", u -> u.setEmailVerifiedAt(LocalDateTime.now())));
		long before = userRepository.count();

		// 他人のメールアドレスを名乗る、所有権未確認の Google アカウント(→ ADR-0004)。
		assertThatThrownBy(
				() -> googleAccountService.resolve("sub-impostor", "target@example.com", false, "なりすまし"))
				.isInstanceOf(GoogleLoginNotAllowedException.class);

		assertThat(userRepository.count()).isEqualTo(before);
		assertThat(userRepository.findById(existing.getId()).orElseThrow().getGoogleSub()).isNull();
	}

	// --- 以下はテストデータ組み立ての小道具 ---
	//TypeScriptでいうfunction user(username: string, email: string, customize: (user: User) => void): Userと同じ意味
	// 	TypeScript なら customize(user) とそのまま呼べますが、Java は customize.accept(user) です。
	// Java には「関数そのもの」という型が無く、メソッドを 1 つだけ持つインターフェースで代用しているためです。
	// これを関数型インターフェースと呼びます。u -> u.setGoogleSub("x") というラムダは、
	// コンパイル時に「accept メソッドの中身がこれ、というオブジェクト」に変換されています。
	// そのため、呼ぶときのメソッド名は型ごとに決まっています。
	// ┌────────────────┬─────────────────────┬──────────────┐
	// │       型       │ TypeScript で書くと │ 呼ぶメソッド │
	// ├────────────────┼─────────────────────┼──────────────┤
	// │ Consumer<T>    │ (x: T) => void      │ accept       │
	// ├────────────────┼─────────────────────┼──────────────┤
	// │ Supplier<T>    │ () => T             │ get          │
	// ├────────────────┼─────────────────────┼──────────────┤
	// │ Function<T, R> │ (x: T) => R         │ apply        │
	// ├────────────────┼─────────────────────┼──────────────┤
	// │ Predicate<T>   │ (x: T) => boolean   │ test         │
	// └────────────────┴─────────────────────┴──────────────┘
	private User user(String username, String email, Consumer<User> customize) {
		// User のコンストラクタは (username, displayName, email)。display_name は
		// このテストの検証対象ではないので username を流用している。
		User user = new User(username, username, email);
		customize.accept(user);
		return user;
	}

	private User save(User user) {
		return userRepository.saveAndFlush(user);
	}
}
