package com.example.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * アプリ固有の設定値(application.yml の app.* )。
 *
 * <p>record なのでコンストラクタで束縛され、起動後に書き換えられない。
 * 型が付くので、@Value("${app.base-url}") を各所に散らすより取り違えが起きにくい。
 *
 * @param baseUrl フロントエンドの入口 URL。メール本文のリンクを組み立てるのに使う
 *                (開発は http://localhost:3000、本番は同一オリジンの公開 URL)
 * @param mail    メール送信の設定
 */
//prefix = "app" は「app. で始まるプロパティだけを、このクラスの束縛対象にする」という指定
//読み込み元は application.yml に限りません。
// 「app. で始まるプロパティ」を Environment 全体から探すので、環境変数 APP_MAIL_FROM でも同じ場所に入ります（. → _、大文字化した形が環境変数の綴り）。
@ConfigurationProperties(prefix = "app")
public record AppProperties(String baseUrl, Mail mail) {

	/**
	 * @param from 送信元アドレス。開発は Mailpit が何でも受け取るので任意の値でよい
	 */
	public record Mail(String from) {
	}
}
