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
 * @param task    タスクモード。未指定(null)なら通常の Web アプリとして動く。
 *                値が入っていると {@link TaskRunner} が処理を実行してプロセスを終了させる
 */
//prefix = "app" は「app. で始まるプロパティだけを、このクラスの束縛対象にする」という指定
//読み込み元は application.yml に限りません。
// 「app. で始まるプロパティ」を Environment 全体から探すので、環境変数 APP_MAIL_FROM でも同じ場所に入ります（. → _、大文字化した形が環境変数の綴り）。
@ConfigurationProperties(prefix = "app")
public record AppProperties(String baseUrl, Mail mail, String task) {

	/**
	 * @param from      送信元アドレス。開発は Mailpit が何でも受け取るので任意の値でよい。
	 *                  本番は SES で検証済みのドメインのアドレスでなければ送信が拒否される
	 * @param transport 送信経路。実装の切り替えを行うのは {@link MailSenderConfig} の
	 *                  {@code @ConditionalOnProperty} で、そちらは文字列として値を読むため、
	 *                  <b>このフィールドを読むコードは無い</b>。
	 *                  それでも enum で受け取っているのは<b>綴りの検証のため</b>。
	 *                  {@code MAIL_TRANSPORT=sess} のような打ち間違いは、この束縛が
	 *                  起動時に失敗させる(型が {@code String} だと、黙って SMTP 側が
	 *                  選ばれて本番でメールだけが飛ばない状態になる)。
	 */
	public record Mail(String from, Transport transport) {

		/**
		 * 送信経路。yml / 環境変数には小文字で書く({@code smtp} / {@code ses})。
		 * Spring Boot の緩やかな束縛が大文字の定数名に対応させる。
		 */
		public enum Transport {
			/** 開発。Mailpit が受け取る SMTP。Bean は Boot の自動設定が作る */
			SMTP,
			/** 本番。SES の API。Bean は {@link MailSenderConfig} が作る */
			SES
		}
	}
}
