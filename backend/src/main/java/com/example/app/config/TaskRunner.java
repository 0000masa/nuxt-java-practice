package com.example.app.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * タスクモード。{@code app.task} が指定されたときだけ有効になり、処理を実行してプロセスを終了させる。
 *
 * <p>用途は ECS の Run Task から「1 回実行して終わる」処理を回すこと。
 * 設計 → docs/superpowers/specs/2026-08-19-phase13-cloudformation-design.md の決定11
 *
 * <p><b>なぜ {@code spring.main.web-application-type=none} を使わないのか。</b>
 * それだと Spring Session JDBC の自動設定が動かず(セッションは Web スコープの機能)、
 * {@code FindByIndexNameSessionRepository} を要求する
 * {@link com.example.app.auth.UserSessionManager} が解決できずに<b>起動そのものが失敗する</b>
 * (実測で確認)。そのため Web サーバーは普通に立て、起動しきったところで終了させる。
 * Run Task はプライベートサブネットで動き ALB に登録されないので、
 * 8080 番が一瞬開くことに実害はない。
 *
 * <p>{@code migrate} で何もしないのは、<b>Flyway がコンテキストの初期化中に走り終えている</b>ため。
 * ここに到達した時点でマイグレーションは適用済みで、あとは終了させるだけでよい。
 */
@Component
@ConditionalOnProperty(name = "app.task")
class TaskRunner implements ApplicationRunner {

	//new Logger(...) とは書けません。Logger はインターフェースなので、実体は Logback 側のクラスです。
	// そこで ファクトリメソッド（new の代わりにインスタンスを用意して返してくれる static メソッド）である
	//  LoggerFactory.getLogger に作ってもらいます。
	// 引数の TaskRunner.class は クラスそのものを表すオブジェクト（Class 型）です。
	// ここでは「このロガーの名前を com.example.app.config.TaskRunner にしてください」
	// と伝えるために渡しています。文字列で getLogger("com.example.app.config.TaskRunner") 
	// と書くのと結果は同じですが、.class で渡せば クラス名を変えたりパッケージを移動しても
	// 自動で追従するので、こちらが定石です。
	private static final Logger log = LoggerFactory.getLogger(TaskRunner.class);

	private final ConfigurableApplicationContext context;
	private final AppProperties appProperties;

	TaskRunner(ConfigurableApplicationContext context, AppProperties appProperties) {
		this.context = context;
		this.appProperties = appProperties;
	}

	@Override
	public void run(ApplicationArguments args) {
		String task = appProperties.task();
		int exitCode;

		switch (task) {
			case "migrate" -> {
				// Flyway はコンテキスト初期化中に完了している。ここでは記録だけ残す。
				log.info("タスク migrate を完了しました(Flyway はコンテキスト初期化中に適用済み)");
				exitCode = 0;
			}
			default -> {
				// 綴り間違いを黙って成功にしない。ワークフローが 0 以外で失敗を検知できるようにする。
				log.error("未知のタスク名です: {}", task);
				exitCode = 1;
			}
		}

		// SpringApplication.exit がコンテキストを閉じて ExitCodeGenerator の値を返す。
		// System.exit まで呼ぶのは、Web サーバーやセッションの掃除スケジューラが
		// 非デーモンスレッドを持っており、main を抜けただけでは JVM が終わらないため。
		int resolved = SpringApplication.exit(context, () -> exitCode);
		System.exit(resolved);
	}
}
