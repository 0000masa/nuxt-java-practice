package com.example.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.zaxxer.hikari.HikariDataSource;

/**
 * 読み書きの振り分け({@link DataSourceConfig})の検証。
 *
 * <p>開発とテストでは Writer も Reader も同じ MySQL(app_test)を指すので、「どちらの MySQL に届いたか」では
 * 見分けられない。代わりに<b>どちらのプールから接続が貸し出されているか</b>(Hikari の使用中の接続数)を見る。
 *
 * <p>もう 1 つ確かめたいのは、振り分けを誤ったとき開発環境で気づけること。
 * 本番の Reader は Aurora が書き込みを拒否するが、開発では同じ MySQL なので、
 * Reader プールの read-only 設定が効いていなければ書き込みが素通りしてしまう。
 */
// トランザクションは TransactionTemplate で張る。テストメソッドに @Transactional を付けると
// テスト全体が 1 つの(読み書きの)トランザクションになり、readOnly の切り替えを試せない。
@SpringBootTest
class ReadWriteRoutingTest {

	@Autowired
	PlatformTransactionManager transactionManager; // JPA のトランザクションマネージャ(本番のサービスと同じもの)

	@Autowired
	JdbcTemplate jdbcTemplate; // トランザクション内では、JPA と同じ接続を使って SQL を流す

	@Autowired
	@Qualifier("writer")
	HikariDataSource writer;

	@Autowired
	@Qualifier("reader")
	HikariDataSource reader;

	@Test
	@DisplayName("readOnly のトランザクションは Reader のプールから接続を借りる")
	void readOnlyTransactionUsesReader() {
		transaction(true).executeWithoutResult(status -> {
			jdbcTemplate.queryForObject("SELECT 1", Integer.class);

			assertThat(activeConnections(reader)).isEqualTo(1);
			assertThat(activeConnections(writer)).isZero();
		});
	}

	@Test
	@DisplayName("readOnly でないトランザクションは Writer のプールから接続を借りる")
	void readWriteTransactionUsesWriter() {
		transaction(false).executeWithoutResult(status -> {
			jdbcTemplate.queryForObject("SELECT 1", Integer.class);

			assertThat(activeConnections(writer)).isEqualTo(1);
			assertThat(activeConnections(reader)).isZero();
		});
	}

	@Test
	@DisplayName("readOnly のトランザクション内で書き込むとエラーになる(開発環境でも振り分けの誤りに気づける)")
	void writeInReadOnlyTransactionFails() {
		// 拒否するのは MySQL サーバより手前の Connector/J。読み取り専用の接続でデータを変える文を実行しようとすると、
		// サーバに送る前に「Connection is read-only. Queries leading to data modification are not allowed.」で止める(実測)。
		// 対象の行が無い UPDATE にしてあるので、万一通ってしまっても app_test は汚れない。
		assertThatThrownBy(() -> transaction(true).executeWithoutResult(
				status -> jdbcTemplate.update("UPDATE categories SET name = name WHERE id = -1")))
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("Connection is read-only");
	}

	private TransactionTemplate transaction(boolean readOnly) {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setReadOnly(readOnly);
		return template;
	}

	/** プールがまだ 1 度も使われていなければ MXBean が無いので 0 とみなす */
	private static int activeConnections(HikariDataSource dataSource) {
		var pool = dataSource.getHikariPoolMXBean();
		return pool == null ? 0 : pool.getActiveConnections();
	}
}
