package com.example.app.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import com.zaxxer.hikari.HikariDataSource;

/**
 * 読み書きでデータベースの接続先を分ける(Writer / Reader)。
 *
 * <p>{@code @Transactional(readOnly = true)} のトランザクションは Reader に、それ以外(トランザクション外を含む)は
 * Writer に接続する。本番の Aurora では Writer がクラスタエンドポイント、Reader が Reader エンドポイントになる
 * (→ docs/adr/0016-aurora-provisioned-with-read-write-split.md)。
 * 開発とテストは {@code DB_READER_HOST} を設定しないので、両方が同じ MySQL に繋がる。
 *
 * <p><b>振り分けているのは {@link LazyConnectionDataSourceProxy} だけ。</b>
 * トランザクションの開始時に Spring が {@code setReadOnly(true)} を呼んでも、このプロキシは実際の接続をまだ取らずに
 * 値を控えておき、最初の SQL を流す瞬間に「控えた値が true なら Reader、そうでなければ Writer」から接続を借りる
 * ({@code setReadOnlyDataSource} は Spring Framework 6.1.2 から)。
 * 遅延させないと、readOnly が決まる前に接続を取ってしまい振り分けられない。
 *
 * <p>自前で {@link DataSource} を定義したので Spring Boot の DataSource 自動設定は引く。
 * ただし {@link DataSourceProperties}(spring.datasource.*)は自動設定が用意したものをそのまま使っている。
 * Flyway はアプリの DataSource を使わず、{@code spring.flyway.url} から自分で接続を作る(application.yml のコメント)。
 */
@Configuration
public class DataSourceConfig {

	/**
	 * Writer 用のプール。接続情報は spring.datasource.*、プールの設定は spring.datasource.hikari.*。
	 *
	 * <p>{@code defaultCandidate = false} は「型だけでは注入されない」指定。
	 * これが無いと DataSource 型の bean が 3 つになり、JPA などが {@link #dataSource} を選べずに起動に失敗する。
	 * 取り出すときは {@code @Qualifier("writer")} を付ける。
	 */
	@Bean(defaultCandidate = false)
	@Qualifier("writer")
	@ConfigurationProperties("spring.datasource.hikari")
	HikariDataSource writerDataSource(DataSourceProperties properties) {
		return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
	}

	/**
	 * Reader 用のプール。ユーザーとパスワードは Writer と同じで、URL だけが違う(→ ADR-0016。Reader 専用の DB ユーザーは作らない)。
	 *
	 * <p>app.datasource.reader.hikari.read-only を true にしている。
	 * {@link LazyConnectionDataSourceProxy} は Reader から借りた接続に {@code setReadOnly(true)} を呼ばない
	 * (プールの既定として設定済みのはず、という前提の作り)。付けないと、Reader が同じ MySQL を指す開発環境では
	 * 読み取り専用トランザクション内の書き込みが素通りし、振り分けの誤りに気づけない。
	 */
	@Bean(defaultCandidate = false)
	@Qualifier("reader")
	@ConfigurationProperties("app.datasource.reader.hikari")
	HikariDataSource readerDataSource(DataSourceProperties properties,
			@Value("${app.datasource.reader.url}") String url) {
		return properties.initializeDataSourceBuilder().type(HikariDataSource.class).url(url).build();
	}

	/** アプリ(JPA・JdbcTemplate・ヘルスチェック)が使う唯一の DataSource */
	@Bean
	DataSource dataSource(@Qualifier("writer") HikariDataSource writer,
			@Qualifier("reader") HikariDataSource reader) {
		LazyConnectionDataSourceProxy proxy = new LazyConnectionDataSourceProxy(writer);
		proxy.setReadOnlyDataSource(reader);
		return proxy;
	}
}
