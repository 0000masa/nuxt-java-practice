package com.example.app.auth;

import java.util.Map;

import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/**
 * 特定ユーザーのログインセッションを消す役。
 *
 * <p>パスワードを変えたときに「別の端末で開かれているセッション」を無効化するために使う。
 * これができるのはセッションをサーバー側に持っているからで、
 * JWT 方式なら発行済みトークンを失効させる仕組みを自分で作る必要があった
 * (→ docs/adr/0002-session-cookie-over-jwt.md)。
 *
 * <p>検索キーは Spring Security から見たログイン識別子 = <b>メールアドレス</b>。
 * 保存先は Redis で、principal 名ごとの Set が索引になっている
 * (キー名は {@code spring:session:index:...PRINCIPAL_NAME_INDEX_NAME:<email>})。
 * <b>このクラスは保存先を知らない。</b>{@code FindByIndexNameSessionRepository} という
 * 抽象にだけ依存しているので、MySQL から Redis に移した際もコードは 1 行も変えていない
 * (→ docs/adr/0015-session-store-on-redis.md、docs/notes/redis/session-management.md)。
 */
@Component
class UserSessionManager {

	// 型引数が <? extends Session> なのは、実装(RedisIndexedSessionRepository)が扱う
	// セッションの具体型をこちらが知る必要がないため。deleteById は型引数を使わないので支障はない。
	// 具体型に触らないことが、保存先の差し替えを 1 行で済ませている理由でもある。
	private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;

	UserSessionManager(FindByIndexNameSessionRepository<? extends Session> sessionRepository) {
		this.sessionRepository = sessionRepository;
	}

	/** そのユーザーの全セッションを消す。パスワードリセット完了時に使う(全端末が強制ログアウトされる)。 */
	void deleteAll(String email) {
		sessions(email).keySet().forEach(sessionRepository::deleteById);
	}

	/**
	 * そのユーザーのセッションのうち、指定したもの以外を消す。ログイン中のパスワード変更で使う。
	 *
	 * <p>全部消すと変更した本人も追い出されてしまうため、操作中のセッションだけ残す。
	 */
	void deleteAllExcept(String email, String keepSessionId) {
		sessions(email).keySet().stream()
				.filter(sessionId -> !sessionId.equals(keepSessionId))
				.forEach(sessionRepository::deleteById);
	}

	//「キーは String、値は Session か、その子クラス（extends Session）」という宣言です。? は「具体的にどのクラスかは指定しない」という意味で、ワイルドカード と呼びます。
	private Map<String, ? extends Session> sessions(String email) {
		//このメソッドが返す Map は DB に問い合わせた結果から新しく組み立てられたコピー（スナップショット） です。
		// DB と連動して自動更新されるビューではありません。この後で DB からセッションを削除しても、手元の Map は変わりません。
		return sessionRepository.findByPrincipalName(email);
	}
}
