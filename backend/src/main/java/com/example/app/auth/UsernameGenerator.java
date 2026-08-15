package com.example.app.auth;

import org.springframework.stereotype.Component;

import com.example.app.user.UserRepository;

/**
 * Google ログインで作るユーザーの username を組み立てる役。
 *
 * <p>パスワード登録では利用者が自分で入力するが、Google ログインには入力欄が無いので
 * サーバー側で決める(設計の決定6)。あとからフェーズ7 のプロフィール編集で変更できる前提。
 *
 * <p><b>受容していること</b>: username は公開情報(X の @xxx 相当)なので、メールアドレスの
 * ローカル部が公になる。ランダムな文字列にすれば漏れないが、意味のある初期値のほうが
 * 親切だと判断した → docs/adr/0004-google-account-linking.md
 */
@Component
class UsernameGenerator {

	/** users.username は VARCHAR(30)。連番の接尾辞(_123 など)が付く余地を残して短めに切る。 */
	private static final int BASE_MAX_LENGTH = 20;

	/** ローカル部から使える文字が 1 つも取れなかったときの土台。 */
	private static final String FALLBACK = "user";

	/** 連番を振っても空きが見つからないときに諦める上限。現実には 1〜2 回で決まる。 */
	private static final int MAX_ATTEMPTS = 1000;

	private final UserRepository userRepository;

	UsernameGenerator(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	/**
	 * メールアドレスから、まだ誰も使っていない username を作る。
	 *
	 * <p>{@code masanori.adachi@gmail.com} → {@code masanori_adachi}、
	 * 既に埋まっていれば {@code masanori_adachi_2}。
	 */
	String generateFrom(String email) {
		String base = toBase(email);
		if (isAvailable(base)) {
			return base;
		}
		// 2 から始めるのは、空きが無かった最初の候補が実質 1 番目だから(_1 は使わない)。
		for (int suffix = 2; suffix < MAX_ATTEMPTS; suffix++) {
			String candidate = base + "_" + suffix;
			if (isAvailable(candidate)) {
				return candidate;
			}
		}
		throw new IllegalStateException("username の空きが見つかりません: " + base);
	}

	private boolean isAvailable(String username) {
		return userRepository.findByUsername(username).isEmpty();
	}

	/**
	 * メールアドレスのローカル部を username に使える形に均す。
	 *
	 * <p>小文字化 → 英数字と _ 以外を _ に置換 → 前後の _ を落とす → 長さを切る、の順。
	 * 記号だけのローカル部(現実にはほぼ無いが RFC 上は可能)だと空になるので、その場合は
	 * 固定文字列にする。空文字は username の一意制約に当たり続けるだけで復帰できないため。
	 */
	private String toBase(String email) {
		int at = email.indexOf('@');
		String localPart = at < 0 ? email : email.substring(0, at);

		String normalized = localPart.toLowerCase()
				.replaceAll("[^a-z0-9_]", "_") // ドットやプラス記号(a.b+tag@…)をまとめて _ にする
				.replaceAll("^_+|_+$", ""); // 変換で前後に付いた _ は見た目が悪いので落とす

		if (normalized.length() > BASE_MAX_LENGTH) {
			// 切った位置が _ の直前だと末尾に _ が残るので、もう一度落とす。
			normalized = normalized.substring(0, BASE_MAX_LENGTH).replaceAll("_+$", "");
		}
		return normalized.isEmpty() ? FALLBACK : normalized;
	}
}
