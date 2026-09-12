-- セッションの保存先を MySQL から Redis(ElastiCache)に移したので、
-- V3 で作ったセッションテーブルを落とす(→ docs/adr/0015、設計 → specs/2026-09-12-phase18-redis-session-design.md)。
--
-- なぜ V3 のファイルを消さないのか:
--   Flyway は適用済みマイグレーションのチェックサムを flyway_schema_history に持っており、
--   ファイルを消したり中身を変えたりすると、既に流した環境で validate が落ちる。
--   「過去を消さず、前に進んで打ち消す」のが Flyway の作法。
--   V3 にコメントを足すことすらしない(それだけでチェックサムが変わる)。
--
-- 外部キーは張られていないので順序の制約は無いが、
-- 子テーブル(属性)→ 親テーブル(セッション)の順に落として意図を明示する。
--
-- Redis に移した後もう一度 JDBC に戻したくなったら、V3 の DDL を V5 として作り直すこと。

DROP TABLE IF EXISTS SPRING_SESSION_ATTRIBUTES;
DROP TABLE IF EXISTS SPRING_SESSION;
