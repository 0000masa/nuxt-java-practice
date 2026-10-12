# DB アクセスの層 — JDBC から Spring Data JPA まで、Laravel / Prisma / Drizzle ではどこが担うか

「JPA と Hibernate は何が違うのか」「HikariCP や Connector/J は何をしているのか」「Flyway はどこに入るのか」という疑問に答える学習メモ。`build.gradle` に並ぶ DB まわりのライブラリを**層の地図**として整理し、同じ層を Laravel / Prisma / Drizzle では誰が担っているかを対応づける。

JPA / Hibernate / Spring Data JPA の 3 層そのものの説明と、Active Record / Data Mapper の比較は [repository-and-entity-vs-laravel-model.md](repository-and-entity-vs-laravel-model.md) に、「いつ SQL が出るか」(永続化コンテキスト)の ORM 比較は [jpa-persistence-context.md](jpa-persistence-context.md) にある。このメモはそれより広い範囲の地図で、細部はそちらに任せる。

対象ファイル: [build.gradle](../../../../backend/build.gradle) / [application.yml](../../../../backend/src/main/resources/application.yml) / [UserRepository.java](../../../../backend/src/main/java/com/example/app/user/UserRepository.java)

## まず結論(3 行)

1. **Java の DB アクセスは 7 つの部品の積み重ね。** 上から Spring Data JPA → JPA → Hibernate → JDBC → HikariCP → ドライバ。そこに別枠でマイグレーションの Flyway が加わる。
2. **「仕様」と「実装」が分かれているのが Java の特徴。** JPA(仕様)を Hibernate(実装)が、JDBC(仕様)を Connector/J(実装)が担う。Node.js には言語標準の DB API が無いので、Prisma / Drizzle はドライバとの結び付けを自前で持つ。
3. **このリポジトリではスキーマの正は Flyway の SQL。** エンティティは手書きし、起動時に `ddl-auto: validate` で答え合わせする。Prisma / Drizzle は「スキーマ定義が正で、マイグレーションと型はそこから生成する」ので、向きが逆になる。

## 全体の地図

```
あなたのコード(UserRepository, AuthMailWorker など)
        │
  ① Spring Data JPA   … Repository インターフェースを書くだけで実装を生成する
        │
  ② JPA(仕様)       … @Entity, EntityManager などの「決まりごと」
        │
  ③ Hibernate(実装)  … JPA を実際に動かす本体。SQL を組み立てる
        │
  ④ JDBC(仕様)       … Java から DB に SQL を投げる標準 API
        │
  ⑤ HikariCP           … コネクションプール(接続の使い回し)
        │
  ⑥ MySQL Connector/J  … JDBC の MySQL 向け実装(ドライバ)
        │
      MySQL / Aurora

  別枠: ⑦ Flyway … テーブルを作る・変える(マイグレーション)
```

| | 何か | 種類 | このリポジトリでの入り方 |
| --- | --- | --- | --- |
| ① Spring Data JPA | `JpaRepository` を継承したインターフェースから実装を自動生成する。`findByEmail` のようなメソッド名からクエリを作る | Spring のライブラリ | `spring-boot-starter-data-jpa` |
| ② JPA(Jakarta Persistence) | オブジェクトとテーブルを対応づける(ORM)ための**仕様**。`@Entity`・`@Id`・`EntityManager` などの API を定める | 仕様(インターフェースの集まり) | 同上(`jakarta.persistence.*`) |
| ③ Hibernate | JPA の**実装**。永続化コンテキストの管理、SQL の生成、`ddl-auto: validate` の検証を実際に行う | ライブラリ | 同上(starter が既定で Hibernate を選ぶ) |
| ④ JDBC | `Connection`・`PreparedStatement`・`ResultSet` など、SQL を投げて結果を受け取る**標準 API** | 仕様(JDK に同梱) | Hibernate が内部で使う |
| ⑤ HikariCP | DB 接続を作り置きして使い回すプール | ライブラリ | 同上(Spring Boot の既定) |
| ⑥ MySQL Connector/J | JDBC の API を MySQL の通信プロトコルに翻訳するドライバ | ライブラリ | `runtimeOnly 'com.mysql:mysql-connector-j'` |
| ⑦ Flyway | `V5__...sql` のようなファイルを順に流してスキーマを管理する | ツール | `spring-boot-starter-flyway` + `flyway-mysql` |

### 混同しやすい組み合わせ

- **JPA と Hibernate は「仕様」と「実装」。** コードが `jakarta.persistence.Entity` を import していて、`org.hibernate.*` を import していないのはこのため。JDBC(仕様)と Connector/J(実装)も同じ関係。詳しくは [repository-and-entity-vs-laravel-model.md](repository-and-entity-vs-laravel-model.md) の冒頭。
- **JPA と Spring Data JPA は「道具」と「その道具を楽に使う仕組み」。** 素の JPA では `entityManager.persist(x)` を自分で呼ぶ。Spring Data JPA はそれを `save()` に包み、`isNew()` を見て persist か merge かを選ぶ(→ `ProcessedMessage` が `Persistable` を実装している理由)。
- **Flyway は ORM ではない。** アプリが動いているときの読み書きには関わらず、起動時にテーブルを作ったり変えたりするだけ。

## 同じ層を誰が担うか — Laravel / Prisma / Drizzle との対応

| 層 | Java(このリポジトリ) | Laravel | Prisma | Drizzle |
| --- | --- | --- | --- | --- |
| 呼び出しの窓口 | Spring Data JPA(`userRepository`) | Eloquent のモデル(`User::`) | Prisma Client(`prisma.user`) | `db`(drizzle のインスタンス) |
| ORM / クエリの組み立て | JPA(仕様)+ Hibernate(実装) | Eloquent + Query Builder | Prisma Client の内部(クエリエンジン) | Drizzle 本体 |
| 言語標準の DB API | **JDBC** | **PDO** | なし | なし |
| コネクションプール | HikariCP | 基本なし(PHP はリクエストごとに接続する) | ドライバ側のプール | ドライバ側のプール(`mysql2` の `createPool` など) |
| ドライバ | MySQL Connector/J | `pdo_mysql`(PHP の拡張) | ドライバアダプタ経由で差し込む | `mysql2` などを自分で渡す |
| マイグレーション | Flyway(別製品) | `php artisan migrate`(本体に同梱) | `prisma migrate`(同じ製品) | `drizzle-kit`(別パッケージ) |
| 型の出どころ | エンティティを手書き | なし(モデルは型を持たない) | `prisma generate` で生成 | スキーマ定義から推論 |

この表から見えること:

- **「言語標準の DB API」の行が埋まるのは Java と PHP だけ。** JDBC と PDO は役割がほぼ同じで、どの DB でも同じ書き方で SQL を投げられる共通の口になっている。Node.js にはこれが無い。そのため Prisma はドライバアダプタという自前の共通口を用意し、Drizzle はドライバごとに入口(`drizzle-orm/mysql2` など)を分けている。
- **Prisma は縦に長い 1 製品。** クエリの組み立て、マイグレーション、型生成までを 1 つの製品が持つ。以前は Rust 製のクエリエンジンが接続まで抱えていた。Prisma 7 からは、ドライバを外から差し込むドライバアダプタ方式になった。
- **Drizzle は薄い。** クエリの組み立てだけを担い、ドライバもプールも利用者が渡す。マイグレーションは別パッケージの drizzle-kit。部品を自分で組むという意味では、Java の分け方に近い。
- **Laravel は Eloquent に寄せた 1 製品。** ORM・クエリビルダ・マイグレーションが本体に入っている。PDO の上に乗る構造は Java の「JDBC の上に Hibernate」と同じ形。

## スキーマの正(source of truth)はどこにあるか

Prisma や Drizzle から来ると一番戸惑うのがここ。「テーブルの形」を最初に書くのはどこで、残りはどうやって追従するか。

| | スキーマの正 | マイグレーションの作り方 | コードの型の出どころ |
| --- | --- | --- | --- |
| このリポジトリ | **Flyway の SQL**(`db/migration/V*.sql`) | 手書き | エンティティを手書きし、`ddl-auto: validate` で SQL と答え合わせする |
| Prisma | `schema.prisma` | `prisma migrate dev` がスキーマの差分から SQL を生成する | `prisma generate` がスキーマから生成する |
| Drizzle | TS のスキーマ定義(`mysqlTable(...)`) | `drizzle-kit generate` がスキーマの差分から SQL を生成する | スキーマ定義からそのまま推論される(`typeof users.$inferSelect`) |
| Laravel | マイグレーションの PHP | 手書き | なし |

**Java 側だけ、同じ形を 2 か所に手で書いている。** テーブルの形は Flyway の SQL に、オブジェクトの形はエンティティのアノテーション(`@Table` / `@Column`)に書く。両者がずれないことを保証する仕組みが、[application.yml](../../../../backend/src/main/resources/application.yml) の次の 1 行。

```yaml
jpa:
  hibernate:
    ddl-auto: validate # スキーマは Flyway(db/migration)が管理。エンティティとの不一致は起動時エラーで検出する
```

Prisma / Drizzle はスキーマ定義が 1 つで、SQL も型もそこから生成されるので、そもそもずれようがない。Java では生成する代わりに、**起動時に突き合わせる**ことで同じ安全を得ている。`@Table(name = "processed_messages")` を書き忘れると起動に失敗するのはこのため。

> **Prisma / Drizzle に近い「コードが正」の運用もできる。** `ddl-auto: update` にすると、Hibernate がエンティティを見てテーブルを作ったり列を足したりする。ただし列の削除や型の変更は扱えず、履歴も残らないので本番では使わない。理由は [flyway-basics.md](../../flyway-basics.md) の「Hibernate の自動生成(ddl-auto)という別の道」を参照。

## 同じ処理を 4 つのスタックで — メールアドレスでユーザーを 1 件取る

題材は [UserRepository.java](../../../../backend/src/main/java/com/example/app/user/UserRepository.java) の `findByEmail`。`users.email` には一意制約がある(`V1__create_base_tables.sql` の `uk_users_email`)。

```java
// Spring Data JPA — メソッド名からクエリが生成される。SQL は書かない
Optional<User> user = userRepository.findByEmail(email);
```

```php
// Laravel(Eloquent)— モデル自身がクエリを発行する
$user = User::where('email', $email)->first();
```

```ts
// Prisma — 生成されたクライアント経由。where はオブジェクトで書く
const user = await prisma.user.findUnique({ where: { email } });
```

```ts
// Drizzle — SQL の select ... from ... where をそのまま組み立てる
const [user] = await db.select().from(users).where(eq(users.email, email)).limit(1);
```

並べると、**SQL からの距離**が違う。

- 一番遠いのは Spring Data JPA。メソッド名がクエリの仕様になる
- Eloquent と Prisma はその中間。メソッドやオブジェクトで条件を組み立てる
- 一番近いのは Drizzle。`select` / `from` / `where` が SQL の語順のまま並ぶ

保存や更新まで並べると、「いつ SQL が出るか」の違いが大きく効いてくる。Hibernate は変更を溜めてから流し、Eloquent / Drizzle は書いた瞬間に流す。この話は [jpa-persistence-context.md](jpa-persistence-context.md) の「他の ORM に同じ仕組みはあるか」にまとめてある。

## ORM を使わない選択肢 — Java にも Drizzle 寄りの道具がある

Java でも Hibernate だけが選択肢ではない。このリポジトリでは使っていないが、層の地図でいえば ①〜③ を差し替える道具がある。

| 道具 | 一言で | 近いもの |
| --- | --- | --- |
| Spring の `JdbcClient` / `JdbcTemplate` | SQL は自分で書き、接続・例外・結果の詰め替えだけ肩代わりしてもらう | Laravel の `DB::select()`、Node の `mysql2` を直接使う |
| MyBatis | SQL を XML やアノテーションに書き、結果をオブジェクトに対応づける。日本の業務システムで多い | — |
| jOOQ | SQL を型安全な Java の DSL で組み立てる | **Drizzle**(ただし向きが逆で、jOOQ は既存の DB から Java のコードを生成する) |

## つまずきポイント

- **`org.hibernate.*` を import しないのは手抜きではない。** JPA の仕様(`jakarta.persistence.*`)だけに依存しておけば、実装を差し替えられる。Hibernate 固有の機能が必要なときだけ直接触る。
- **「ドライバを入れたのに接続できない」は Flyway で起きやすい。** Flyway は DB ごとのモジュールが別になっていて、MySQL には `flyway-mysql` が要る(`build.gradle` のコメント)。JDBC ドライバ(Connector/J)とは別物。
- **Prisma 感覚で「エンティティを書けばテーブルができる」と思わない。** このリポジトリでは先に Flyway の SQL を書く。エンティティだけ足すと、`validate` で起動に失敗する。
- **HikariCP は設定しなくても動いている。** `spring.datasource.*` を書くと、Spring Boot が自動で HikariCP のプールを作る。PHP ではリクエストごとに接続するのが普通なので、「プールがある」こと自体が Laravel との違い。

## 用語集

- **JDBC(Java Database Connectivity)** — Java から DB に SQL を投げるための標準 API。JDK に入っている。PHP の PDO に相当する
- **JDBC ドライバ** — JDBC の API を特定の DB の通信プロトコルに翻訳する実装。MySQL なら Connector/J
- **コネクションプール** — DB 接続を事前に何本か作っておき、使い回す仕組み。接続を作る手間(TCP・認証)を毎回払わずに済む。Spring Boot の既定は HikariCP
- **JPA(Jakarta Persistence)** — Java の ORM の標準仕様。実装ではない
- **Hibernate** — JPA の実装。Spring Boot の既定
- **Spring Data JPA** — JPA の上に乗り、Repository をインターフェースだけで書けるようにする層
- **マイグレーション** — スキーマの変更をバージョン付きのファイルとして管理し、順に適用する仕組み。このリポジトリでは Flyway
- **スキーマの正(source of truth)** — テーブルの形を最初に書く場所。ほかの表現(SQL・型・エンティティ)はそこに追従する
- **ドライバアダプタ(Prisma)** — Prisma Client に Node.js の DB ドライバ(`pg` など)を差し込むための口
- **drizzle-kit** — Drizzle のマイグレーション用 CLI。スキーマ定義の差分から SQL を生成する

## 関連

- JPA / Hibernate / Spring Data JPA の 3 層、Active Record と Data Mapper → [repository-and-entity-vs-laravel-model.md](repository-and-entity-vs-laravel-model.md)
- いつ SQL が出るか、Eloquent / Prisma / Drizzle との比較 → [jpa-persistence-context.md](jpa-persistence-context.md)
- Flyway の仕組み、`ddl-auto` を本番で使わない理由 → [flyway-basics.md](../../flyway-basics.md)
- エンティティとテーブルの名前の対応(`@Table` が要る理由)→ [jpa-entity-naming-rules.md](jpa-entity-naming-rules.md)
