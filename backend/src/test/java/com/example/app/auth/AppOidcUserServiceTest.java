package com.example.app.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.app.user.User;

/**
 * Google 由来の値をアプリの言葉に翻訳する部分の検証。
 *
 * <p>{@link GoogleAccountService} 側の分岐は {@code GoogleAccountServiceTest} が見ているので、
 * ここで確かめるのは<b>その手前と後ろ</b>だけ。どちらも壊しても画面上は正常に見えるうえ、
 * 他のどのテストもこのクラスを通らないため、ここで押さえておく。
 * <ol>
 * <li>{@code email_verified} が 3 値であること(クレームが無ければ null)を踏まえた変換
 * <li>アプリ都合の拒否を、失敗ハンドラが読める {@code OAuth2AuthenticationException} へ翻訳
 * </ol>
 *
 * <p><b>delegate をリフレクションで差し替えている理由</b>: 本番の {@code delegate} は
 * {@code new OidcUserService()} で固定されており、そのまま呼ぶと Google と実通信してしまう。
 * 差し替え用のコンストラクタを本番コードに足す手もあるが、テストの都合を本番コードに
 * 持ち込まない方針でリフレクションを選んでいる({@code AppOidcUserTest} が User の id を
 * 入れているのと同じ手段)。フィールド名を文字列で指定するため、{@code delegate} を
 * リネームするとコンパイルは通ったままこのテストだけが落ちる。
 */
class AppOidcUserServiceTest {

	private static final String SUB = "google-sub-123";
	private static final String EMAIL = "taro@example.com";

	// @MockitoBean ではなく mock() なのは、このテストが Spring のコンテキストを起動せず、
	// 差し替える先の Bean が存在しないため。AppOidcUserService は new で組み立てられるうえ、
	// delegate はクラス内で new している非 Bean なので Bean 差し替えでは届かない。
	private final OidcUserService delegate = mock(OidcUserService.class);
	private final GoogleAccountService googleAccountService = mock(GoogleAccountService.class);

	private AppOidcUserService appOidcUserService;

	@BeforeEach
	void setUp() {
		appOidcUserService = new AppOidcUserService(googleAccountService);
		ReflectionTestUtils.setField(appOidcUserService, "delegate", delegate);
	}

	/**
	 * 本番側は {@code Boolean.TRUE.equals(...)} の 1 行で「true 以外はすべて false」に倒している。
	 * null(Google がクレームを返さない場合)と false は同じルールの 2 ケースなのでまとめて確かめる。
	 */
	//name = "email_verified = {0}" はテスト実行結果の表示名で、{0} に第 1 引数が入ります。
	// 落ちたときに email_verified = null と email_verified = false のどちらのケースかがすぐ分かります。
	@ParameterizedTest(name = "email_verified = {0}")
	@NullSource //引数に null を 1 回流す
	@ValueSource(booleans = false) // 引数に false を 1 回流す
	@DisplayName("email_verified が明示的に true でなければ未確認(false)として渡す")
	void treatsNonTrueEmailVerifiedAsFalse(Boolean emailVerified) {
		OidcUser oidcUser = googleUser(emailVerified);
		when(delegate.loadUser(any())).thenReturn(oidcUser);
		when(googleAccountService.resolve(any(), any(), anyBoolean(), any())).thenReturn(appUser());

		//OidcUserRequest は delegate.loadUser(any()) にそのまま渡されるだけで中身は一度も読まれないため、空のモックで足ります
		appOidcUserService.loadUser(mock(OidcUserRequest.class));

		// true 側に倒すと、所有権が未確認のメールアドレスで既存アカウントに
		// リンクできてしまう。安全側(false)に落ちていることを確かめる。
		verify(googleAccountService).resolve(SUB, EMAIL, false, "太郎");
	}

	@Test
	@DisplayName("確認済みなら true を渡し、User と ID トークンを AppOidcUser にまとめて返す")
	void buildsPrincipalFromResolvedUser() {
		Instant now = Instant.now();
		OidcIdToken idToken = new OidcIdToken("id-token-value", now, now.plus(1, ChronoUnit.HOURS),
				Map.of("sub", SUB, "email", EMAIL));
		OidcUserInfo userInfo = new OidcUserInfo(Map.of("sub", SUB));

		OidcUser oidcUser = googleUser(true);
		when(oidcUser.getIdToken()).thenReturn(idToken);
		when(oidcUser.getUserInfo()).thenReturn(userInfo);
		when(delegate.loadUser(any())).thenReturn(oidcUser);
		when(googleAccountService.resolve(SUB, EMAIL, true, "太郎")).thenReturn(appUser());

		OidcUser principal = appOidcUserService.loadUser(mock(OidcUserRequest.class));

		// 型が AppOidcUser でないと、Controller の @AuthenticationPrincipal AppUserDetails に
		// 黙って null が渡り、認可は通ったまま Controller の中で 500 になる(設計の決定7)。
		assertThat(principal).isInstanceOf(AppOidcUser.class);
		assertThat(((AppOidcUser) principal).getUserId()).isEqualTo(42L);
		// Google の sub ではなくアプリのメールアドレスが principal 名になる(設計の決定8)。
		assertThat(principal.getName()).isEqualTo(EMAIL);
		assertThat(principal.getIdToken()).isSameAs(idToken);
		assertThat(principal.getUserInfo()).isSameAs(userInfo);
	}

	@Test
	@DisplayName("拒否されたら OAuth2AuthenticationException に翻訳し、コードと原因を保つ")
	void translatesRejectionIntoAuthenticationException() {
		GoogleLoginNotAllowedException rejection = new GoogleLoginNotAllowedException(
				GoogleLoginNotAllowedException.EMAIL_UNVERIFIED, "メールアドレスが確認されていません");
		OidcUser oidcUser = googleUser(false);
		when(delegate.loadUser(any())).thenReturn(oidcUser);
		when(googleAccountService.resolve(any(), any(), anyBoolean(), any())).thenThrow(rejection);

		Throwable thrown = catchThrowable(() -> appOidcUserService.loadUser(mock(OidcUserRequest.class)));

		// AuthenticationException でないと failureHandler に届かず、リダイレクトではなく 500 になる。
		assertThat(thrown).isInstanceOf(OAuth2AuthenticationException.class);
		// 原因を捨てると、障害調査でどこ由来の拒否か追えなくなる。
		assertThat(thrown.getCause()).isSameAs(rejection);
		// このコードがそのまま /login?error=<コード> になる(→ AuthResponseWriter.onOAuth2LoginFailure)。
		assertThat(((OAuth2AuthenticationException) thrown).getError().getErrorCode())
				.isEqualTo(GoogleLoginNotAllowedException.EMAIL_UNVERIFIED);
	}

	/**
	 * Google から返ってくる OidcUser の代役。
	 *
	 * <p>本物を組み立てるには ClientRegistration や署名済みの ID トークンが要るが、
	 * このクラスが読むのは 4 つのクレームだけなのでモックで足りる。
	 *
	 * <p>戻り値は必ず変数に受けてから使うこと。{@code thenReturn(googleUser(...))} と
	 * 書くと、{@code when(...)} を開いたまま内側で別の {@code when(...)} が走り
	 * {@code UnfinishedStubbingException} になる。
	 */
	private OidcUser googleUser(Boolean emailVerified) {
		OidcUser oidcUser = mock(OidcUser.class);
		when(oidcUser.getSubject()).thenReturn(SUB);
		when(oidcUser.getEmail()).thenReturn(EMAIL);
		when(oidcUser.getEmailVerified()).thenReturn(emailVerified);
		when(oidcUser.getFullName()).thenReturn("太郎");
		return oidcUser;
	}

	/** GoogleAccountService が返す想定の User。id は DB が採番するのでテストでは直接入れる。 */
	private User appUser() {
		User user = new User("google_taro", "太郎", EMAIL);
		user.setEmailVerifiedAt(LocalDateTime.now());
		ReflectionTestUtils.setField(user, "id", 42L);
		return user;
	}
}
