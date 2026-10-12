package com.example.app.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.app.user.UserRepository;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * 会員登録からログインまでを 1 本で通す結合テスト。
 *
 * <p>認証は手作業で確認するのが一番面倒な機能なので、「登録 → 未確認ではログインできない →
 * メール確認 → ログインできる」という一連の流れをここで自動化しておく。
 *
 * <p>アプリ全体を起動するため、URL ごとの認可も本物が効いている(未ログインでの投稿が
 * 401 になることをこのテストで確かめられるのは、そのため)。
 *
 * <p><b>メール送信はモックに差し替える。</b>実際に SMTP へ送らないだけでなく、
 * トークンは DB にハッシュしか残らないので、生の値を得るには送信内容を覗くしかない
 * (これは利用者と同じ経路をたどっているとも言える)。
 *
 * <p><b>キュー(SQS)もモックに差し替え、ワーカーの処理はテストが代わりに呼ぶ。</b>
 * 登録の API はキューに依頼を送るだけで、トークンの発行とメール送信はワーカーが行う(→ docs/adr/0017)。
 * 送られた依頼を捕まえて {@link AuthMailWorker} に渡すことで、受信ループを動かさずに
 * 「Web → キュー → ワーカー」の流れを 1 本のテストの中でたどる。
 */
// @SpringBootTest … アプリ全体を起動する。DataSource は application.yml の設定そのままなので本物の MySQL に
//   つなぐ。@DataJpaTest のようなスライステストと違い「DB をインメモリ DB に差し替える」既定が無いため、
//   PostRepositoryTest.java:48 の @AutoConfigureTestDatabase(replace = NONE) にあたる打ち消しは要らない。
//   接続先の database は build.gradle の test タスクが DB_NAME を上書きするので app_test になる(両者共通)。
//   @DataJpaTest が持つ「各テストをトランザクションで包み、終了時にロールバックする」機能も無い。書き込みは
//   そのまま app_test に残るので、後片付けは cleanUp() で自分で行う(トランザクションを足せない理由もそこに記載)。
@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowTest {

	private static final String EMAIL = "flow-test@example.com";
	private static final String PASSWORD = "password12345";

	/** メール本文に埋め込まれた確認リンクからトークンを取り出すための正規表現 */
	private static final Pattern TOKEN_IN_MAIL = Pattern.compile("token=([A-Za-z0-9_-]+)");

	// @Autowired と @MockitoBean は択一ではなく、後者が前者の仕事を含む(重ねて付けない)。
	//   @Autowired … DI コンテナにある本物をそのまま受け取る。コンテナの中身には手を触れない。
	//     テストがコンストラクタ注入でなくフィールド注入なのは、テストクラスのインスタンスを作るのが
	//     Spring ではなく JUnit で、コンストラクタで渡す隙が無いため(テストではこちらが公式)。
	//   @MockitoBean … コンテナ内の bean を Mockito のモックに差し替えたうえで受け取る。テスト専用。
	//     差し替えはアプリ全体に効くので、AuthMailSender が注入される JavaMailSender も偽物になる。それが狙い
	//     (@Autowired にすると本物が入り、verify() にモックでない値を渡して NotAMockException で落ちる)。
	//     ただし差し替えるとコンテキストが別物としてキャッシュされ、アプリの起動が 1 回増える。本物をつなぐのが
	//     結合テストの価値でもあるので、モックはここでは JavaMailSender の 1 個だけに絞っている。
	@Autowired
	MockMvc mockMvc; // ← 本物が欲しい(リクエストを実際に処理させたい)

	@Autowired
	UserRepository userRepository; // ← 本物が欲しい(本当に DB から消したい)

	@MockitoBean
	JavaMailSender mailSender; // ← 偽物にしたい(SMTP に送らせない & 送信内容を覗きたい)

	@MockitoBean
	SqsClient sqsClient; // ← 偽物にしたい(キューに送らせない & 送った依頼を覗きたい)

	@Autowired
	AuthMailWorker authMailWorker; // ← 本物が欲しい(依頼を渡してトークンの発行とメール送信をさせる)

	@Autowired
	JsonMapper jsonMapper;

	// このテストはトランザクションで囲めない(囲むとコミットされず、AFTER_COMMIT のキューへの送信が発火しない)。
	// そのため作ったデータは自分で消す。前回の失敗が残っていても動くよう、前後の両方で消している。
	@BeforeEach
	@AfterEach
	void cleanUp() {
		userRepository.findByEmail(EMAIL).ifPresent(userRepository::delete);
	}

	// モックの SqsClient は既定で null を返すので、送信の応答だけ用意しておく
	@BeforeEach
	void stubQueue() {
		when(sqsClient.sendMessage(any(SendMessageRequest.class)))
				.thenReturn(SendMessageResponse.builder().messageId("test-message-id").build());
	}

	@Test
	@DisplayName("登録 → 未確認ではログイン不可 → メール確認 → ログイン成功")
	void signsUpVerifiesAndLogsIn() throws Exception {
		// 1. 会員登録
		mockMvc.perform(post("/api/auth/signup").with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"username":"flowtest","displayName":"結合テスト",
						 "email":"%s","password":"%s"}
						""".formatted(EMAIL, PASSWORD)))
				.andExpect(status().isCreated());

		// 2. キューに送られた依頼をワーカーが処理すると、確認メールが送られ、そこからトークンが取れる
		authMailWorker.process(captureQueuedRequest());
		String rawToken = captureTokenFromSentMail();

		// 3. メール未確認の状態ではログインできない。しかもメッセージが区別されている
		//    (「パスワードが違う」と誤解させないため → 設計の固定した細部)
		mockMvc.perform(post("/api/auth/login").with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("email", EMAIL)
				.param("password", PASSWORD))
				.andExpect(status().isUnauthorized())
				// 完全一致でなく部分一致にしているのは、守りたいのが「パスワード違いと区別されたメッセージが返ること」で、
				// 文言そのものではないため。jsonPath の 2 引数版は Hamcrest の条件オブジェクトを直接受け取る。
				.andExpect(jsonPath("$.message", containsString("確認が完了していません")));

		// 4. メール確認
		mockMvc.perform(post("/api/auth/verify-email").with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"%s\"}".formatted(rawToken)))
				.andExpect(status().isNoContent());

		// 5. 今度はログインできる
		mockMvc.perform(post("/api/auth/login").with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("email", EMAIL)
				.param("password", PASSWORD))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.user.username").value("flowtest"))
				.andExpect(jsonPath("$.user.email").value(EMAIL));
	}

	@Test
	@DisplayName("未ログインでは投稿できない(閲覧は公開、書き込みは認証必須)")
	void rejectsPostWithoutLogin() throws Exception {
		mockMvc.perform(post("/api/posts").with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"body\":\"未ログインからの投稿\",\"categoryId\":1}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value("ログインが必要です"));
	}

	@Test
	@DisplayName("Google ログインの入口が Google へ 302 する")
	void redirectsToGoogle() throws Exception {
		// このテストが守っているのは、認可エンドポイントの baseUri を /api 配下に移してあること
		// (設計の決定1)。既定に戻すと devProxy に乗らず、フェーズ11 で Nuxt の /login ページと
		// 衝突する。外しても Google の画面まで到達しないが、原因は画面からは分からない。
		MvcResult result = mockMvc.perform(get("/api/oauth2/authorization/google"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrlPattern("https://accounts.google.com/**"))
				.andReturn();

		// 決定1 のもう半分。redirect_uri は Google に「ここへ戻して」と伝える値で、
		// application.yml の spring.security.oauth2.client.registration.google.redirect-uri が載る。
		// 受け取る側の redirectionEndpoint(SecurityConfig.java:111)と一致していなければ、
		// 戻ってきたリクエストを誰も処理できない。既定の /login/oauth2/code/* に戻すと、
		// フェーズ11 で Nuxt の /login ページともぶつかる。
		// クエリパラメータなので URL エンコードされて載る。デコードしてから比べる。
		// ホスト部は APP_BASE_URL 次第で変わるため、パスだけを見る。
		//レスポンスのどの値を見ているか
		// 返ってきたレスポンスは、HTTP で書くとこういう形です。この部分のredirect_uriというクエリパラメータを見てる。
		// HTTP/1.1 302
		// Location: https://accounts.google.com/o/oauth2/v2/auth?response_type=code&client_id=dummy-client-id
		//           &scope=openid profile email&state=AO_AR6Aa3Gg...
		//           &redirect_uri=http://localhost:3000/api/login/oauth2/code/google
		//           &nonce=I5RDTCUY3Fpc...&code_challenge=aHmGrqlW...&code_challenge_method=S256
		String location = URLDecoder.decode(result.getResponse().getRedirectedUrl(), StandardCharsets.UTF_8);
		assertThat(location).contains("/api/login/oauth2/code/google");
	}

	/** コミットの後に SQS へ送られた依頼を取り出す(→ AuthMailEnqueuer) */
	private AuthMailRequestedEvent captureQueuedRequest() {
		ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
		verify(sqsClient).sendMessage(captor.capture());
		return jsonMapper.readValue(captor.getValue().messageBody(), AuthMailRequestedEvent.class);
	}

	// テストの中でアプリが送った確認メールを捕まえて、本文に入っているトークンを取り出すメソッド
	private String captureTokenFromSentMail() {

		// ArgumentCaptorとは : Mockito のクラスで、モックのメソッドに渡された引数を、テストから後で取り出すためのもの
		// ArgumentCaptor.forClass とは : 捕まえる引数の型を指定して captor を作る、static のファクトリメソッド
		ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);

		// - capture():verify(...) の引数の位置に書きます。「どんな値でも条件を満たすことにして、その値を保存する」という意味の指示になります。verify の中でだけ使います。
		verify(mailSender).send(captor.capture());// 履歴を照合し、本物のメールを captor のリストに入れる

		// - getValue():保存した値を取り出します。何回も呼ばれた場合は、最後の 1 つが返ります。
		SimpleMailMessage sent = captor.getValue();
		assertThat(sent.getTo()).containsExactly(EMAIL);
		assertThat(sent.getSubject()).contains("メールアドレスの確認");

		Matcher matcher = TOKEN_IN_MAIL.matcher(sent.getText());
		assertThat(matcher.find()).as("メール本文に token= を含むリンクがあること").isTrue();
		return matcher.group(1);
	}
}
