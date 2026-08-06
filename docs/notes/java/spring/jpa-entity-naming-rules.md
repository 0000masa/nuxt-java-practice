# JPA エンティティに「命名ルール」はあるのか — 名前が効く場面・効かない場面

「`@Entity` と `@Table` を付けたクラスがエンティティ」という理解の次に出てくる疑問。

- エンティティのフィールド名やメソッド名は、**決まった名前にしないといけないのか?**
- 主キーのフィールドは `id` という名前でなければならないのか?
- `getXxx()` という形は必須なのか? `onCreate()` という名前は決まりなのか?

結論から言うと **「この名前でなければならない」というルールはほぼ無い**。JPA は名前ではなく **アノテーションで判断している**。ただし「名前が効いてくる場面」が 4 つあり、そこを知らないと事故る。

対象ファイル: [User.java](../../../../backend/src/main/java/com/example/app/user/User.java) / [Post.java](../../../../backend/src/main/java/com/example/app/post/Post.java) / [UserRepository.java](../../../../backend/src/main/java/com/example/app/user/UserRepository.java)

## まず結論

**JPA が見ているのは名前ではなくアノテーション。**

- `@Id` が付いたものが主キー。フィールド名は `id` でなくても `primaryKey` でも動く
- `@Column` が付いたものが通常の列
- `@ManyToOne` が付いたものが関連
- `@PrePersist` を付けたメソッドは INSERT 直前に呼ばれる。**メソッド名は自由**(`onCreate` は慣習)
- getter の名前も自由(FIELD アクセスの場合。→ 後述)

**一方、名前が効く場面は 4 つ。**

| 場面 | 何が起きるか | 間違えるとどうなるか |
|---|---|---|
| ① フィールド名 → **列名** | `@Column(name=...)` を省略すると、フィールド名から列名が自動生成される | 列名が想定と違い、起動時に検証エラー |
| ② クエリメソッド名 → **プロパティ名** | `findByEmail` は「`email` というプロパティがある」前提で SQL を組み立てる | **起動時に例外**でアプリが立ち上がらない |
| ③ getter 名 → **プロパティ名**(PROPERTY アクセスのときだけ) | 属性名を getter 名から逆算するため JavaBeans 規約が必須 | プロパティが認識されず列が消える |
| ④ **SQL の予約語** | `order` などをフィールド名にすると生成 SQL が構文エラーになる | 実行時に SQL エラー |

さらに「名前ではないが本当に必須のルール」が別にある(→ 後述の一覧表)。

---

## `@Entity` と `@Table` の関係

まず前提の整理。この 2 つは役割が違い、**`@Table` は省略できる**。

| アノテーション | 役割 | 省略できるか |
|---|---|---|
| `@Entity` | このクラスを「DB のテーブルに対応する管理対象」だと Hibernate に伝える | **できない**(これが無ければエンティティではない) |
| `@Table` | 対応するテーブル名を明示する | **できる**。省略するとクラス名から自動生成される |

`@Table` を省略した場合、`User` クラスは `user` テーブルに対応づけられる。このプロジェクトが `@Table(name = "users")` と書いているのは、**テーブル名が複数形でクラス名と一致しないから**である。「エンティティの条件」ではなく「名前の明示」が目的。

---

## 場面① フィールド名 → 列名(命名戦略)

`@Column(name = "...")` を省略すると、Hibernate はフィールド名から列名を組み立てる。この変換規則を **命名戦略(naming strategy)** と呼ぶ。

### 変換は 2 段階になっている

```
フィールド名          論理名(logical name)        物理名(実際の列名)
passwordHash  ──▶  passwordHash          ──▶  password_hash
              暗黙的命名戦略               物理命名戦略
              (implicit naming strategy)   (physical naming strategy)
```

- **暗黙的命名戦略** — `@Column(name=...)` が無いときに「論理名」を決める。基本はフィールド名そのまま。`@ManyToOne` の外部キー列のように「フィールド名 + `_` + 参照先の主キー列名」を組み立てる仕事もここが担う
- **物理命名戦略** — 論理名を実際の DB 識別子に変換する。ここでケース変換が起きる

### Spring Boot の既定

Spring Boot 4.1 のリファレンスの記述。

> By default, Spring Boot configures the physical naming strategy with `CamelCaseToUnderscoresNamingStrategy`. Using this strategy, all dots are replaced by underscores and camel casing is replaced by underscores as well. Additionally, by default, all table names are generated in lower case. For example, a `TelephoneNumber` entity is mapped to the `telephone_number` table.

つまり **キャメルケースがアンダースコア区切りの小文字に変換される**。

```
username        → username           (変化なし)
displayName     → display_name
passwordHash    → password_hash
avatarImageKey  → avatar_image_key
emailVerifiedAt → email_verified_at
```

変更したい場合は `spring.jpa.hibernate.naming.physical-strategy` / `implicit-strategy` で差し替えられる。**このプロジェクトはどちらも設定していない**(`backend/` 配下に `naming` の設定は無い)ため、既定がそのまま効いている。

---

## 場面② クエリメソッド名 → プロパティ名

ここが 4 つの中で最も「名前のルール」に近い。Spring Data JPA の **クエリメソッド(派生クエリ)** は、メソッド名を単語に分解して**エンティティのプロパティ名と突き合わせる**仕組みだからである。

```
findByEmail
  │    └──── 「email」という属性を探す ──▶ where email = ?
  └───────── 「1 件取得」の意味
```

`UserRepository.java:30` の `findByEmail` は、`User` に `email` という属性があることに依存している。**フィールドを `mailAddress` に改名すると、突き合わせに失敗してアプリ起動時に例外**になる。`@Column(name = "email")` を付けて DB の列名を保っても解決しない —— クエリメソッドが見ているのは**列名ではなく Java 側の属性名**だから。

```java
// フィールドを mailAddress に改名した場合
@Column(name = "email")
private String mailAddress;

Optional<User> findByEmail(String email);       // ✗ 起動時エラー(email という属性が無い)
Optional<User> findByMailAddress(String mail);  // ○ メソッド名も合わせて改名する必要がある
```

**エンティティのフィールド名を変えるときは、Repository のメソッド名も一緒に見る** —— これが実務上一番効く注意点。

> 救いは「実行時まで気付かない」のではなく**起動時に落ちる**こと。Spring Data JPA は起動時にクエリメソッドを解析して実装を生成するため、名前の不一致はその場で露見する(→ [repository-and-entity-vs-laravel-model.md](./repository-and-entity-vs-laravel-model.md))。

---

## 場面③ getter 名(PROPERTY アクセスのときだけ)

Hibernate が値を読み書きする経路には **FIELD アクセス**(フィールドに直接)と **PROPERTY アクセス**(getter/setter 経由)の 2 種類があり、**`@Id` をどこに付けたかで決まる**。

- **`@Id` をフィールドに付けた** → FIELD アクセス → **getter/setter の名前は自由。そもそも無くてもよい**
- **`@Id` を getter に付けた** → PROPERTY アクセス → 属性名を getter 名から逆算するので **`getXxx()` / `isXxx()` という JavaBeans 規約が必須**

このプロジェクトは 4 エンティティすべて `@Id` をフィールドに付けており、**全部 FIELD アクセス**。したがって getter の名前は JPA から見て自由である。その証拠に `Post` は setter が 1 つも無いまま動いている。

詳細(何の「アクセス」なのか、2 つで実際に何が変わるのか、なぜ `@Id` の位置で決まるのか) → **[jpa-access-type-field-vs-property.md](./jpa-access-type-field-vs-property.md)**

---

## 名前ではない「本当に必須のルール」

名前は自由だが、**クラスの形**には仕様上の縛りがある。Jakarta Persistence 仕様の要求。

| 必須ルール | 理由 |
|---|---|
| **引数なしコンストラクタ**(`public` か `protected`) | Hibernate が「空の器」を作ってから値を差し込むため |
| **クラスを `final` にできない** | 遅延ロード用のプロキシ(エンティティを継承した代理オブジェクト)を作れなくなるため |
| **`@Id`(主キー)が必要** | 1 行を一意に識別できないと管理できないため |
| フィールドは `private` / `protected` / package-private | 仕様の要求(`public` フィールドは不可) |

`@PrePersist` などのコールバックメソッドは **名前は自由**だが **シグネチャ(戻り値 `void` / 引数なし)は決まっている**。`onCreate` という名前はこのプロジェクトの慣習で、`stampCreatedAt` でも動く。

これらの詳細(なぜ空の器が要るのか、なぜ `protected` なのか、`record` が使えない理由) → **[jpa-entity-noarg-constructor.md](./jpa-entity-noarg-constructor.md)**

---

## このプロジェクトでは

### 列名は「明示派」。ただし既定の変換でも同じ結果になる

代表的な 2 エンティティを調べた結果。「既定変換の結果」は `@Column(name=...)` を消したらどうなるかを示す。

**`User`**([User.java](../../../../backend/src/main/java/com/example/app/user/User.java))

| フィールド | `@Column(name=...)` | 実際の列名 | 既定変換の結果 | 一致 |
|---|---|---|---|---|
| `id` | なし | `id` | `id` | ○ |
| `username` | なし | `username` | `username` | ○ |
| `displayName` | `display_name` | `display_name` | `display_name` | ○ |
| `email` | なし | `email` | `email` | ○ |
| `passwordHash` | `password_hash` | `password_hash` | `password_hash` | ○ |
| `googleSub` | `google_sub` | `google_sub` | `google_sub` | ○ |
| `bio` | なし | `bio` | `bio` | ○ |
| `avatarImageKey` | `avatar_image_key` | `avatar_image_key` | `avatar_image_key` | ○ |
| `emailVerifiedAt` | `email_verified_at` | `email_verified_at` | `email_verified_at` | ○ |
| `createdAt` / `updatedAt` | `created_at` / `updated_at` | 同左 | 同左 | ○ |

**`Post`**([Post.java](../../../../backend/src/main/java/com/example/app/post/Post.java))

| フィールド | 指定 | 実際の列名 | 既定変換の結果 | 一致 |
|---|---|---|---|---|
| `id` | なし | `id` | `id` | ○ |
| `user` | `@JoinColumn(name = "user_id")` | `user_id` | `user_id` | ○ |
| `category` | `@JoinColumn(name = "category_id")` | `category_id` | `category_id` | ○ |
| `body` | なし | `body` | `body` | ○ |
| `createdAt` | `@Column(name = "created_at")` | `created_at` | `created_at` | ○ |

**すべて既定の変換結果と一致している。** つまり `@Column(name = "password_hash")` は**書かなくても同じ列にマッピングされる**。それでも書いているのは、**対応先の列名をコード上で読めるようにする意図の明示**である。`Category` と `AuthToken` も同じ方針。

### ズレたら起動時に落ちる仕組みがある

[application.yml:10](../../../../backend/src/main/resources/application.yml) が `ddl-auto: validate`。スキーマは Flyway が管理し、Hibernate は起動時に **エンティティと実テーブルの突き合わせ検証だけ**を行う。

```
起動 → Flyway がマイグレーション適用 → Hibernate が validate
                                          ↓
                              エンティティの列と実テーブルの列が一致するか?
                                          ↓
                              不一致なら SchemaManagementException で起動失敗
```

したがってこのプロジェクトでは、**フィールド名を変えて列名とズレるとアプリが起動しない**。実行時に静かに壊れるのではなく即座に分かる、という安全網になっている(Flyway の役割 → [flyway-basics.md](../../flyway-basics.md))。

### 改名するときに一緒に直す場所

`User.email` を改名する場合、影響範囲は 3 つ。

1. **Flyway のマイグレーション**(列名も変えるなら新しい `V*.sql` を追加。既存ファイルは編集しない)
2. **`UserRepository` のクエリメソッド名**(`findByEmail` → 新しい属性名に合わせる)
3. **getter を使っている箇所**(Service / DTO への変換 / Jackson が生成する JSON のキー名)

JPA の永続化そのものは `@Column(name=...)` で吸収できるが、**2 と 3 は名前で繋がっているので吸収できない**。

---

## つまずきポイント

- **SQL の予約語をフィールド名にする。** `order`, `group`, `key`, `desc`, `rank` などは、生成 SQL が `select order from ...` になり構文エラーになる。`@Column(name = "\"order\"")` で引用符を付けるか、名前を避ける
- **`@Table` を省略してテーブル名がズレる。** クラス名が単数形(`User`)でテーブルが複数形(`users`)なら、省略すると `user` を探して `validate` で落ちる
- **クエリメソッド名の不一致を「列名を合わせれば直る」と思う。** クエリメソッドが見ているのは Java 側の属性名。`@Column` では解決しない
- **`getURL()` のような大文字連続。** JavaBeans の逆算規則ではプロパティ名は `URL`(`uRL` ではない)になる。PROPERTY アクセスのときだけ問題になる
- **boolean の getter 名。** PROPERTY アクセスでは `isActive()` / `getActive()` のどちらでもよいが、`Boolean`(ラッパー型)では `isActive()` が認識されない実装もある。FIELD アクセスなら無関係
- **フィールドを足すと自動的に列扱いになる。** FIELD アクセスでは `@Transient` 以外の全フィールドが永続化対象。計算用の一時フィールドを足すと `validate` で落ちる
- **`onCreate` という名前が必須だと思う。** `@PrePersist` が効いているのはアノテーションのため。名前は自由(シグネチャは `void` / 引数なし)

## 用語集

- **JPA** — Java の ORM 標準仕様。`jakarta.persistence.*` のアノテーション群がこれ
- **Hibernate** — JPA 仕様の実装。Spring Boot が既定で組み込む ORM 本体
- **命名戦略(naming strategy)** — Java 側の名前を DB 側の識別子に変換する規則
- **暗黙的命名戦略(implicit naming strategy)** — `@Column(name=...)` が無いときに論理名を決める部分
- **物理命名戦略(physical naming strategy)** — 論理名を実際の列名・テーブル名に変換する部分。Spring Boot の既定は `CamelCaseToUnderscoresNamingStrategy`
- **論理名 / 物理名** — 変換の途中の名前と、DB に実際に発行される識別子
- **クエリメソッド(派生クエリ)** — Spring Data がメソッド名を解析して SQL を自動生成する仕組み。プロパティ名に依存する
- **アクセスタイプ** — Hibernate が値を読み書きする経路(FIELD / PROPERTY)。`@Id` の位置で決まる
- **`@Transient`** — そのフィールドを永続化対象から外すアノテーション
- **`ddl-auto: validate`** — テーブルを作らず、エンティティと既存スキーマの一致だけを起動時に検証するモード

## 関連

- アクセスタイプ(FIELD / PROPERTY)の詳細 → [jpa-access-type-field-vs-property.md](./jpa-access-type-field-vs-property.md)
- 引数なしコンストラクタ・`final` 不可・プロキシ・実体化 → [jpa-entity-noarg-constructor.md](./jpa-entity-noarg-constructor.md)
- Entity と Repository の役割分担、JPA / Hibernate / Spring Data JPA の 3 層 → [repository-and-entity-vs-laravel-model.md](./repository-and-entity-vs-laravel-model.md)
- スキーマを Flyway が管理する仕組みと `ddl-auto: validate` の関係 → [flyway-basics.md](../../flyway-basics.md)
- 公式ドキュメント
  - [Spring Boot — Configure Hibernate Naming Strategy](https://docs.spring.io/spring-boot/how-to/data-access.html#howto.data-access.configure-hibernate-naming-strategy)(既定の命名戦略)
  - [Jakarta Persistence 3.2 仕様](https://jakarta.ee/specifications/persistence/3.2/jakarta-persistence-spec-3.2.html)(エンティティの要件、アクセスタイプ)
  - [Hibernate 7 ORM Introduction — Entities](https://docs.hibernate.org/orm/7.0/introduction/html_single/Hibernate_Introduction.html)
