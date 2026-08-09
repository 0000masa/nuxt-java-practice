package com.example.app.auth;

/**
 * 認証トークンの用途。auth_tokens.purpose に文字列として保存される。
 *
 * <p>メール確認とパスワードリセットは「有効期限付きの使い捨てトークン」として構造が同一なので、
 * 1 テーブルをこの列で共用する(設計概要 §4)。
 */
public enum AuthTokenPurpose {

	// 下の 2 つは変数ではなく enum 定数。コンパイラが
	// `public static final AuthTokenPurpose EMAIL_VERIFICATION = ...` というフィールドに展開する。
	// 型は AuthTokenPurpose 自身で、入っているのは文字列ではなく AuthTokenPurpose のインスタンス。
	// インスタンスはクラス読み込み時に 1 個ずつ生成され、再代入されない(final)。
	//
	// 各インスタンスは継承元の java.lang.Enum に次の値を保持している。
	//   name    … 宣言名そのままの文字列("EMAIL_VERIFICATION")。name() で取り出す。
	//             toString() の既定の戻り値でもあるため、ログには文字列として出る。
	//             AuthToken の @Enumerated(EnumType.STRING) が保存するのはこの値。
	//   ordinal … 宣言順の番号(0 から)。ordinal() で取り出す。並び替えで変わるので保存には使わない。
	//
	// 定数はアプリ全体で 1 個ずつしか存在しないので、比較は == でよい。
	// 逆に文字列との equals は型が違うため常に false になる。

	/** 会員登録後のメール確認。有効期限 24 時間。 */
	EMAIL_VERIFICATION,

	/** パスワードリセット。有効期限 1 時間。 */
	PASSWORD_RESET
}
