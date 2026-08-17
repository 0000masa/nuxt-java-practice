package com.example.app.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import com.example.app.common.exception.InvalidRequestException;
import com.example.app.user.User;
import com.example.app.user.UserRepository;

/**
 * 使い捨てトークンの境界条件の検証。認証で一番壊れると困る部分をここで押さえる
 * (全フェーズ共通のテスト方針が名指ししている「期限切れトークン」がこれ)。
 *
 * <p>@Import(AuthTokenService.class) … @DataJpaTest は Repository と JPA しか読み込まないので、
 * 検証対象の @Service を明示的に足している。AuthTokenService が依存するのは AuthTokenRepository
 * だけなので、これだけでアプリ全体を起動せずに済む。引数の .class はインスタンスではなくクラス自体を
 * 渡す書き方で、実際に new するのは Spring。
 *
 * <p>スライスの使い分けと専用 database app_test について → docs/notes/java/spring/testing-and-test-database.md
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AuthTokenService.class)
class AuthTokenServiceTest {

	// @Import と @Autowired は役割が別なので両方要る。
	//   @Import … Spring にインスタンスを生成させ、DI コンテナに登録する
	//   @Autowired … 登録済みのインスタンスをこのフィールドに入れてもらう(DI = 依存性注入)
	//   片方だけだと、@Import 無しなら「Bean が見つからない」で起動時に失敗し、
	//   @Autowired 無しならフィールドが null のまま実行時に NullPointerException になる。
	// 下の Repository 2 つに @Import が要らないのは @DataJpaTest が登録済みだから。
	//   登録経路は違っても受け取りには @Autowired が要る、というのがこの 2 つが別物である証拠。

	//@DataJpaTest が載せるもの／載せないものを分けるとこうなります。
	// 載る（自動で用意される）
	// - DataSource（DB 接続）、EntityManager、トランザクションマネージャ
	// - @Entity の付いたクラス（User など）のスキャン
	// - Spring Data JPA のリポジトリ（UserRepository など）
	// - Flyway / Liquibase などマイグレーション関連の自動設定
	// 載らない
	// - 自分で書いた @Service / @Component / @RestController（＝ GoogleAccountService、UsernameGenerator）
	// - @ConfigurationProperties の付いたクラス
	//@SpringBootTest にすればアプリ全体が起動するので @Import は不要になりますが、
	// 起動が遅くなり、無関係な設定ミスでも落ちるようになるため、必要な 2 つだけを名指しするこの書き方のほうが適切です。

	@Autowired
	AuthTokenService authTokenService;

	@Autowired
	AuthTokenRepository authTokenRepository;

	@Autowired
	UserRepository userRepository;

	User user;

	@BeforeEach
	void setUp() {
		user = new User("token_test", "トークン検証用", "token-test@example.com");
		user.setEmailVerifiedAt(LocalDateTime.now());
		userRepository.save(user);
	}

	@Test
	@DisplayName("発行したトークンは 1 回だけ使えて、対象のユーザーが返る")
	void consumesValidTokenOnce() {
		String rawToken = authTokenService.issue(user, AuthTokenPurpose.EMAIL_VERIFICATION,
				Duration.ofHours(24));

		User consumed = authTokenService.consume(rawToken, AuthTokenPurpose.EMAIL_VERIFICATION);

		assertThat(consumed.getId()).isEqualTo(user.getId());
	}

	@Test
	@DisplayName("DB に保存されるのは生の値ではなくハッシュ")
	void storesHashedTokenOnly() {
		String rawToken = authTokenService.issue(user, AuthTokenPurpose.EMAIL_VERIFICATION,
				Duration.ofHours(24));

		// 生の値で直接引けてしまうなら、平文で保存されているということ(→ 設計の決定6 が守られていない)
		assertThat(authTokenRepository.findByToken(rawToken)).isEmpty();
	}

	@Test
	@DisplayName("有効期限が切れたトークンは弾く")
	void rejectsExpiredToken() {
		// 期限を過去にして発行する(1 秒前に切れたトークン)
		String rawToken = authTokenService.issue(user, AuthTokenPurpose.EMAIL_VERIFICATION,
				Duration.ofSeconds(-1));

		assertThatThrownBy(() -> authTokenService.consume(rawToken, AuthTokenPurpose.EMAIL_VERIFICATION))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("有効期限");
	}

	@Test
	@DisplayName("使用済みのトークンは 2 回目を弾く")
	void rejectsUsedToken() {
		String rawToken = authTokenService.issue(user, AuthTokenPurpose.EMAIL_VERIFICATION,
				Duration.ofHours(24));
		authTokenService.consume(rawToken, AuthTokenPurpose.EMAIL_VERIFICATION);

		assertThatThrownBy(() -> authTokenService.consume(rawToken, AuthTokenPurpose.EMAIL_VERIFICATION))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("既に使用されています");
	}

	@Test
	@DisplayName("存在しないトークンは弾く")
	void rejectsUnknownToken() {
		assertThatThrownBy(
				() -> authTokenService.consume("not-a-real-token", AuthTokenPurpose.EMAIL_VERIFICATION))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("リンクが不正です");
	}

	@Test
	@DisplayName("用途が違うトークンは流用できず、存在しない場合と同じメッセージになる")
	void rejectsTokenIssuedForAnotherPurpose() {
		// メール確認用のトークンでパスワードリセットを通そうとする
		String rawToken = authTokenService.issue(user, AuthTokenPurpose.EMAIL_VERIFICATION,
				Duration.ofHours(24));

		// メッセージを「存在しない」と同じにしているのは、トークンの存在自体を教えないため
		assertThatThrownBy(() -> authTokenService.consume(rawToken, AuthTokenPurpose.PASSWORD_RESET))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("リンクが不正です");
	}

	@Test
	@DisplayName("同じ用途で新しく発行すると、古い未使用トークンは使えなくなる")
	void invalidatesPreviousUnusedToken() {
		String oldToken = authTokenService.issue(user, AuthTokenPurpose.EMAIL_VERIFICATION,
				Duration.ofHours(24));
		String newToken = authTokenService.issue(user, AuthTokenPurpose.EMAIL_VERIFICATION,
				Duration.ofHours(24));

		// 有効なリンクが同時に複数存在しないようにしている(確認メールを再送したときの挙動)
		assertThatThrownBy(() -> authTokenService.consume(oldToken, AuthTokenPurpose.EMAIL_VERIFICATION))
				.isInstanceOf(InvalidRequestException.class);
		assertThat(authTokenService.consume(newToken, AuthTokenPurpose.EMAIL_VERIFICATION).getId())
				.isEqualTo(user.getId());
	}
}
