-- Spring Session JDBC のセッション格納テーブル(設計 → docs/superpowers/specs/2026-08-05-phase3-auth-design.md §3)
--
-- 内容は spring-session-jdbc 4.1.0 の jar に入っている公式 DDL
-- (org/springframework/session/jdbc/schema-mysql.sql)をそのままコピペで取り込んだもの。
-- Spring Session 側の SQL がこのカラム名・型を前提に組み立てられているため、手を加えない。
-- V1 の他テーブルと違って CHARSET / COLLATE を明示していないのも公式のままである(既定値で足りる)。
--
-- なぜ自動生成に頼らないのか:
--   spring.session.jdbc.initialize-schema の既定値は embedded(H2 などの組み込み DB のときだけ作る)で、
--   MySQL では何も作られない。テーブルが無いまま起動すると最初のセッション書き込みで落ちる。
--   本プロジェクトは「スキーマ変更はすべて Flyway」「ddl-auto は validate」の方針なので Flyway で作る。
--
-- この 2 テーブルに JPA エンティティは作らない(アプリから直接触らない)。
--   ddl-auto: validate はエンティティに対応するテーブルだけを検査するので、検証対象外で問題にならない。
--
-- PRINCIPAL_NAME の index(SPRING_SESSION_IX3)は「特定ユーザーの全セッションを消す」ために使う。
--   パスワードリセット完了時の全端末強制ログアウトがこれを使う(同設計 §5)。

CREATE TABLE SPRING_SESSION (
	PRIMARY_ID CHAR(36) NOT NULL,
	SESSION_ID CHAR(36) NOT NULL,
	CREATION_TIME BIGINT NOT NULL,
	LAST_ACCESS_TIME BIGINT NOT NULL,
	MAX_INACTIVE_INTERVAL INT NOT NULL,
	EXPIRY_TIME BIGINT NOT NULL,
	PRINCIPAL_NAME VARCHAR(100),
	CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC;

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
	SESSION_PRIMARY_ID CHAR(36) NOT NULL,
	ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
	ATTRIBUTE_BYTES BLOB NOT NULL,
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC;
