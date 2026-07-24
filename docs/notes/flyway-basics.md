# Flyway の基礎 — DB マイグレーションの仕組みと実行タイミング

このリポジトリの DB スキーマ管理に使っている Flyway の学習メモ。`backend/src/main/resources/db/migration/` の SQL ファイルがいつ・どうやって MySQL に反映されるのかの読み解き。PHP / Laravel 出身者向け。

## Flyway とは — Laravel migration の Java 定番

Flyway は「DB スキーマの変更を、バージョン番号付きの SQL ファイルとして Git で管理し、順番に適用していく」ツールです。Redgate 社が開発しており、Java 界では DB マイグレーションの定番(もう 1 つの定番に Liquibase がある。Gradle vs Maven のような二択の関係)。

Laravel のマイグレーションと役割はほぼ同じで、対応関係は次のとおりです。

| | Laravel | Flyway(このリポジトリ) |
|---|---|---|
| ファイル置き場 | `database/migrations/` | `src/main/resources/db/migration/` |
| ファイルの中身 | PHP(スキーマビルダー) | **素の SQL** |
| 適用済みの記録 | `migrations` テーブル | `flyway_schema_history` テーブル |
| 実行順の決まり方 | ファイル名の日時 + バッチ番号 | ファイル名のバージョン番号(V1, V2...) |
| 実行のしかた | `php artisan migrate` を明示的に打つ | **Spring Boot 起動時に自動実行**(組み込み方式の場合) |
| ロールバック | `migrate:rollback`(`down()` を書く) | **無い**(無料版には undo 機能が無い。前進のみ) |
| Seeder | `db:seed` として独立した機能 | **無い**(INSERT もマイグレーションとして書く) |

ファイルを Git で管理し、適用状況を DB のテーブルで管理する「二重管理」の構図は Laravel と同じです。一方で太字にした 3 つ——**自動実行・ロールバック無し・Seeder 無し**——が Laravel との大きな違いで、それぞれ後の章で説明します。

なお Flyway は Java 製ですが Java 専用ではありません。単体の CLI や公式 Docker イメージ(`flyway/flyway`)もあり、Node.js や Go のプロジェクトが CI/CD から `flyway migrate` を叩く使い方もできます。ただし Java / Spring Boot では「アプリの依存ライブラリとして組み込み、起動時に実行させる」方式が最も一般的で、このリポジトリもその方式です(CLI のインストールは不要。Flyway 本体は JAR の中に入っている)。

## 履歴はどこに保存されるか — Git と DB の二重管理

Flyway が起動時にやることは、本質的には次の突き合わせだけです。

```
リポジトリ内のファイル               DB の flyway_schema_history
├── V1__create_base_tables.sql  ←→  V1 適用済み(checksum 一致 ✓)
└── V2__insert_categories.sql   ←→  V2 適用済み(checksum 一致 ✓)
                                     → 未適用なし。何もせずアプリ起動へ
```

ここに `V3__add_xxx.sql` を足して起動すると、「V3 は履歴に無い」と判定されて V3 だけが実行され、履歴に 1 行追記されます。**何度起動しても、適用済みのものは二度と実行されません。**

`flyway_schema_history` テーブルは初回実行時に Flyway が自動で作り、1 マイグレーション = 1 行で次を記録します。

| カラム | 内容 |
|---|---|
| `installed_rank` | 適用された通し番号(実行順) |
| `version` | バージョン(`V1` → `1`) |
| `description` | ファイル名の説明部分(`create base tables`) |
| `script` | ファイル名 |
| `checksum` | **ファイル内容のチェックサム**(改変検知に使う) |
| `installed_by` / `installed_on` | 実行した DB ユーザーと日時 |
| `execution_time` | 実行にかかった時間(ms) |
| `success` | 成功したか(失敗した行も残る) |

実機で見るには(接続情報は `.env` の値):

```bash
docker compose exec mysql mysql -u app -p app \
  -e "SELECT installed_rank, version, description, installed_on, success FROM flyway_schema_history;"
```

### チェックサムによる改変検知

適用済みのファイルを後から書き換えると、ファイルのチェックサムと DB に記録されたチェックサムが食い違い、Flyway は起動時に **validate エラー**で止まります(`Migration checksum mismatch for migration version 1` のようなメッセージ)。

これはバグではなく安全装置です。適用済みマイグレーションを書き換えても既存の DB には反映されず、「ファイルと DB の実態がずれたまま気づかない」事故になるため、あえてエラーで知らせてくれます。対処は [つまずきポイント](#つまずきポイント) を参照。

## マイグレーションファイルの命名規則

```
V2__insert_categories.sql
│└┬┘└─────┬─────────┘
│ │       └ 説明(単語区切りはアンダースコア 1 個)
│ └ バージョン番号(この順に実行される)
└ プレフィックス V = Versioned Migration(1 回だけ実行)
```

**バージョンと説明の区切りはアンダースコア 2 個**(`__`)。ここを 1 個にすると Flyway がファイルを認識しないので注意。バージョン番号は `V2.1` や `V20260719` のような形式も使えますが、このリポジトリは連番(`V1`, `V2`, ...)です。

プレフィックスにはもう 1 種類 `R__`(Repeatable Migration)があります。バージョンを持たず、**ファイル内容が変わるたびに再実行される**もので、ビューや関数の定義、繰り返し更新されるデータの投入に使われます(冪等な SQL にする必要がある)。このリポジトリでは使っていません。

運用ルールはただ 1 つ: **適用済みのファイルは編集しない。変更したくなったら新しい V ファイルを足す**。カラムを追加し損ねたら `V1` を直すのではなく `V3__add_xxx.sql` を作ります。

## このリポジトリでの設定

`backend/build.gradle` の該当箇所:

```groovy
implementation 'org.springframework.boot:spring-boot-starter-flyway'
runtimeOnly 'org.flywaydb:flyway-mysql'
```

- **Spring Boot 4 では `spring-boot-starter-flyway` が必要。** ネット上の記事や AI の回答では「`org.flywaydb:flyway-core` を足せば Boot 起動時に動く」とよく書かれていますが、それは Boot 3 までの話。Boot 4 は自動設定がモジュール分割されたため、`flyway-core` 単体では Flyway の自動設定が有効にならず、**エラーも出ずに単に実行されない**という一番気づきにくい形で空振りします(starter は内部で `flyway-core` + 自動設定モジュールを束ねている)
- **`flyway-mysql` は MySQL 用のデータベースサポート。** Flyway 本体は DB ごとの方言対応を別モジュールに分けており、これが無いと MySQL への接続時に「Unsupported Database」エラーになる

`backend/src/main/resources/application.yml` 側:

```yaml
jpa:
  hibernate:
    ddl-auto: validate
```

役割分担が重要です。**スキーマを作る・変えるのは Flyway だけ**。Hibernate(JPA)にもエンティティクラスから自動でテーブルを作る機能(`ddl-auto: update` など)がありますが、このリポジトリでは `validate`(検証だけ)に留めています。マイグレーションを書き忘れてエンティティだけ変えると起動時エラーで検出される、という二段構えです。この使い分けの理由と `ddl-auto` の各値の詳細は次節「Hibernate の自動生成という別の道」を参照。

Flyway 側の設定(`spring.flyway.*`)は書いていませんが、デフォルトで `classpath:db/migration` を読むため、`src/main/resources/db/migration/` に置くだけで認識されます。

もう 1 つこのリポジトリ固有の方針として、**検索ラボの実験用 index(posts の複合 index / FULLTEXT)はあえてマイグレーションに入れていません**。フェーズ 10 で「index なし → 手動 ALTER で追加 → before/after 比較」をやるためです(V1 冒頭のコメント参照)。

## Hibernate の自動生成(ddl-auto)という別の道 — なぜ使わないのか

スキーマを用意する方法は Flyway だけではありません。Hibernate 自身にも、エンティティ(`@Entity` のクラス)を見て**テーブルを自動で作る/変える**機能があり、`spring.jpa.hibernate.ddl-auto` の値で切り替えます。「Flyway を書かず、これだけで済ませる」ことも一応できます。「マイグレーションは Flyway でなく Hibernate にやらせる道もあるのか?」の答えは **"ある。ただし本番向きではない"**。

### ddl-auto の値

| 値 | Hibernate の動き |
|---|---|
| `none` | 何もしない(スキーマにノータッチ) |
| `validate` | 作らない。エンティティと既存テーブルが一致するか**検証だけ**(ズレていれば起動時エラー) ← **このリポジトリ** |
| `update` | 足りないテーブル・カラムを**追加**してエンティティに合わせる(既存は極力壊さない) |
| `create` | 起動時に**全テーブルを作り直す**(既存データは消える) |
| `create-drop` | `create` + アプリ終了時に削除(テスト用) |

`update` や `create` にすれば、マイグレーション用の SQL を 1 行も書かずにテーブルができあがります。`@Column(length = 280)` から `VARCHAR(280)` が、`@ManyToOne` から外部キーが自動生成される、という具合で、開発の初期はとても手軽です。

### なぜ「マイグレーション」とは呼べないのか

Hibernate の `ddl-auto` は、厳密には「マイグレーション」ではなく「**スキーマの自動生成・同期**」です。Flyway のようなマイグレーションツールとは次の点で決定的に違います。

- **履歴・バージョンがない。** Flyway は `V1` `V2` と変更を 1 つずつ番号付きで記録し「どの環境がどこまで適用済みか」を管理します(→ 前述「履歴はどこに保存されるか」の節)。`update` は「今のエンティティに形を合わせる」だけで履歴を持たず、**ロールバックもできません**。
- **`update` は削除・改名ができない。** カラムを消す/名前を変える変更に追随できず(安全側に倒して基本"足す"だけ)、不要な列が残り続けます。
- **データ移行を書けない。** 「1 列を 2 列に分割して中身も移す」ような**データを伴う変更**は SQL でしか書けず、自動生成では表現できません。
- **生成される DDL が予測しづらい。** どんな SQL が発行されるかは Hibernate 任せで、本番でそれを走らせるのは危険です。

つまり `ddl-auto` は「今この瞬間のエンティティに形を合わせる」道具、Flyway は「変更の歴史を積み上げて再現可能にする」道具。**後者こそが本来のマイグレーション**です。

### 開発 vs 本番の使い分け

| 場面 | 向いている方法 |
|---|---|
| 本番があるアプリ | **Flyway(または Liquibase)一択に近い** — 履歴・ロールバック・データ移行・レビュー可能性が要る |
| 使い捨てのプロトタイプ・学習の最初期 | `ddl-auto: update` も便利 — SQL を書かずエンティティだけで回せる(本番に持っていかない前提) |
| 本番で `ddl-auto: update` | **避ける** — 予測不能な DDL・削除不可・履歴なしと地雷が多い |

### スキーマを触る係は 1 つに絞る

**Flyway と `ddl-auto: update` を同時に有効にしてはいけません。** 両方が別々にスキーマをいじると、変更が競合・二重適用になります。だからこのリポジトリは **Flyway に一本化し、Hibernate は `validate`(検証のみ)** にして係を分けています。`validate` は「マイグレーションを書き忘れてエンティティだけ変えたら起動時エラーで気づける」安全網として働きます(Hibernate は JPA 実装として**実行時に SQL を発行する係**、Flyway は**スキーマを用意する係**で、役割はそもそも別物です)。

## いつマイグレーションが実行されるか

組み込み方式なので、答えは「**Spring Boot が起動するたび**」です。起動シーケンスの中では JPA より前に走ります。

```
Spring Boot 起動
    ↓
DataSource 初期化(MySQL へ接続)
    ↓
Flyway: validate(チェックサム照合)→ migrate(未適用があれば実行)
    ↓
JPA / Hibernate 初期化(ddl-auto: validate でスキーマ検証)
    ↓
アプリケーション起動完了
```

この順序のおかげで、アプリのコードが動き出す時点ではスキーマが必ず最新になっています。「Spring Boot が起動するたび」を開発フローに当てはめると:

| タイミング | 何が起きるか |
|---|---|
| `docker compose up -d` | MySQL が healthy になってから backend が起動(`depends_on` の `service_healthy`)し、Flyway が実行される |
| devtools の自動再起動(Java 編集時) | アプリコンテキストが作り直されるので Flyway も毎回走る。未適用が無ければ照合だけして何もしない(一瞬で終わる) |
| `./gradlew test`(`@SpringBootTest`) | テスト用にアプリコンテキストが起動するので、接続先 DB に対して Flyway が走る |
| 本番(ECS)| フェーズ 12 で **ECS Run Task による分離実行**を計画(後述) |

### 新しいマイグレーションを追加したときの反映手順

SQL ファイルは Java と同じく「ビルド出力にコピーされて初めて」devtools と Flyway に認識されます。ホスト側で `V3__xxx.sql` を追加したら:

```bash
docker compose exec backend sh ./gradlew classes
```

Java 編集時と同じコマンドです(`classes` タスクはリソースのコピーを含む)。これで devtools が再起動し、起動時の Flyway が V3 を適用します。

### 本番(ECS)での計画

アプリ起動時に実行する方式は開発では快適ですが、本番では「複数タスクが同時に起動してマイグレーションが競合しうる」「マイグレーション失敗とアプリのデプロイが混ざる」という弱点があります。そのためこのリポジトリでは、本番はデプロイフローから **ECS Run Task でマイグレーションを 1 回だけ実行し、成功してからアプリを更新する**分離構成を計画しています(`docs/development/implementation-progress.md` フェーズ 12。詳細設計は未着手)。

## マスタデータとダミーデータの使い分け

Flyway には Laravel の Seeder に相当する独立機能がありません。代わりに **INSERT / UPDATE もマイグレーションの一部**として扱えます(Flyway にとってマイグレーションとは「DB に順序どおり適用する SQL」であって、DDL 限定ではない)。

ただし何でもマイグレーションに入れてよいわけではなく、データの性質で分けるのが定石で、このリポジトリも既にその方針です。

| データの種類 | 例 | 投入方法 |
|---|---|---|
| **マスタデータ**(本番にも必須・不変) | categories 10 件 | Flyway(`V2__insert_categories.sql`)で投入済み |
| **ダミーデータ**(開発・計測用) | users 1 万 / posts 100 万 / likes 300 万 | フェーズ 9 の **seed タスク**(`--app.task=seed`)。**Flyway には入れない** |

マスタデータをマイグレーションに入れるのは「そのデータが無いとアプリが動かない = スキーマの一部」だから。逆にダミーデータを入れないのは、本番 DB に 100 万件の架空投稿が入ってしまう上、`flyway_schema_history` に「開発専用の履歴」が混ざって環境ごとに履歴が分岐するからです。

## つまずきポイント

### 適用済みファイルを編集してしまった(checksum エラー)

```
Validate failed: Migration checksum mismatch for migration version 2
```

まず**ファイルを元の内容に戻す**のが正解(Git で戻せる)。変更したい内容は新しい V ファイルとして書きます。

開発環境で「まだ誰の DB にも配っていない書きかけの V3 を直したい」ようなケースなら、DB ごと作り直すのが手っ取り早い:

```bash
docker compose down -v   # ボリュームごと削除(mysql のデータが消える)
docker compose up -d     # まっさらな DB に V1 から適用し直す
```

`flyway repair` という履歴テーブルの修復コマンドもありますが、履歴と実態のずれを手動で握りつぶす操作なので最終手段です。

### マイグレーションが途中で失敗した

MySQL は **DDL(CREATE TABLE など)をトランザクションでロールバックできない**(暗黙コミットされる)ため、V ファイルの途中で失敗すると「テーブルは半分できたのに履歴は `success = 0`」という中途半端な状態が残り、以後の起動が失敗し続けます。開発環境なら上記の `down -v` での作り直しが一番確実です。

### ロールバックしたい

無料版の Flyway に `migrate:rollback` 相当はありません(undo は有料機能)。**前進のみ(forward-only)**が思想で、「取り消したい変更」は打ち消す内容の新しい V ファイルとして書きます(例: カラム追加をやめたければ `V4__drop_xxx_column.sql`)。Laravel の `down()` を書く習慣とは考え方が違う点です。
