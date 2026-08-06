# JPA のアクセスタイプ — Hibernate は「フィールド」を触るのか「getter/setter」を触るのか

`User.java` や `Post.java` を見ると、`private` なフィールドが並び、その下に getter が並んでいる。ここで素朴な疑問が出る。

- **Hibernate は DB の値をどこに書き込んでいるのか?** `private` なフィールドに直接? それとも setter 経由?
- `Post` には **setter が 1 つも無い**のに、なぜ DB から読み込めるのか?
- `getEmail()` という名前は決まりなのか? 変えたら壊れるのか?

この 3 つの答えを決めているのが **アクセスタイプ(access type)** という仕組み。**FIELD(フィールドアクセス)** と **PROPERTY(プロパティアクセス)** の 2 種類があり、**`@Id` をどこに付けたかで自動的に決まる**。

このプロジェクトは **全エンティティが FIELD** で、その結果として「setter を書かなくても動く」「getter の名前は自由」が成立している。

対象ファイル: [User.java](../../../../backend/src/main/java/com/example/app/user/User.java) / [Post.java](../../../../backend/src/main/java/com/example/app/post/Post.java)

## まず結論(4 行)

1. **アクセスタイプ = Hibernate がエンティティの値を読み書きするときに使う「経路」の種類。** FIELD ならフィールドを直接、PROPERTY なら getter/setter 経由。
2. **`@Id` をフィールドに付ければ FIELD、getter に付ければ PROPERTY。** 設定ファイルではなくアノテーションの位置で決まる。
3. **FIELD なら getter/setter は Hibernate から見て無関係。** 名前も自由、そもそも無くてもよい。
4. **このプロジェクトは 4 エンティティすべて FIELD。** だから `Post` は setter ゼロで「編集不可」を構造的に守れている。

---

## 1. そもそも何の「アクセス」なのか

「アクセスタイプ」という言葉だけ見ても何の話か掴みにくい。**誰が・いつ・何に対して** アクセスするのかを先に固定する。

### 登場人物は 4 つ

| 登場人物 | 役割 |
|---|---|
| **Hibernate** | JPA 仕様の実装。DB とオブジェクトの相互変換を担う実行時のライブラリ |
| **エンティティのインスタンス** | `User` オブジェクト 1 個。DB の 1 行に対応する |
| **`private` なフィールド** | `private String email;` など。値が実際に入っている場所 |
| **getter / setter** | `getEmail()` / `setEmail()`。値を出し入れするメソッド |

**アクセスタイプが決めているのは、「Hibernate → エンティティのインスタンス」の矢印が、フィールドに直接刺さるのか、getter/setter を通るのか** —— この 1 点だけ。アプリのコード(Service や Controller)が getter を呼ぶ話とは**まったく別の層の話**である。ここを混ぜると分からなくなる。

### いつアクセスするのか — 2 つの方向がある

Hibernate がエンティティの値を触るタイミングは 2 方向ある。**主語は常に Hibernate、対象は常にエンティティ**として読むこと。

- **Hibernate がエンティティに値を書き込む** — 値の流れは DB → オブジェクト(SELECT のとき)
- **Hibernate がエンティティから値を読み出す** — 値の流れはオブジェクト → DB(INSERT / UPDATE のとき)

「書き込み」「読み出し」という語は主語を取り違えると向きが真逆になる。**DB 目線で「DB に書き込む」と読むと INSERT の話になってしまう**ので、ここでは「エンティティに対して何をするか」で固定して読む。

**方向A: DB → オブジェクト(SELECT のとき)**

```
postRepository.findById(1L) を呼ぶ
  ↓
SELECT * FROM posts WHERE id = 1
  ↓
DB が 1 行返す( id=1, user_id=3, body='こんにちは', created_at=... )
  ↓
Hibernate:「空の Post を作る」  ← protected Post() を呼ぶ
  ↓
Hibernate:「各値をオブジェクトに入れる」  ← ★ここでアクセスタイプが効く★
        FIELD    なら → post の body フィールドに直接代入
        PROPERTY なら → post.setBody("こんにちは") を呼ぶ
  ↓
値が入った Post が Service に返る
```

**方向B: オブジェクト → DB(INSERT / UPDATE のとき)**

```
postRepository.save(post) を呼ぶ
  ↓
Hibernate:「保存する値を取り出す」  ← ★ここでもアクセスタイプが効く★
        FIELD    なら → post の body フィールドを直接読む
        PROPERTY なら → post.getBody() を呼ぶ
  ↓
INSERT INTO posts (user_id, body, created_at) VALUES (?, ?, ?)
```

つまり **アクセスタイプは「DB とオブジェクトの間で値を受け渡す 2 か所」でだけ効く**。この受け渡し処理は **実体化(hydration)** と呼ばれる(→ [jpa-entity-noarg-constructor.md](./jpa-entity-noarg-constructor.md) の「実体化」の節)。

### 「`private` なのに外から書けるのはなぜか」

FIELD アクセスの説明で必ず引っかかるのがここ。`private String body;` は「クラスの外からは触れない」はずなのに、Hibernate は外部のライブラリである。

答えは **リフレクション** —— 実行中にクラスの構造を名前で調べて操作する Java の仕組み。リフレクションには `setAccessible(true)` という「アクセス修飾子のチェックを外す」操作があり、Hibernate はこれを使って `private` フィールドに直接値を代入している。

`private` は**コンパイル時に人間のコードを守るための仕切り**で、実行時のリフレクションはその仕切りを越えられる。Hibernate はこの越権を使って「空の器に値を差し込む」ことをしている。

> 引数なしコンストラクタが `protected` でよい理由も同じ。リフレクションなら `protected` でも呼べる(→ [jpa-entity-noarg-constructor.md](./jpa-entity-noarg-constructor.md))。

---

## 2. FIELD と PROPERTY で実際に何が変わるのか

同じ `User` を 2 通りに書いて並べる。**違いは `@Id` と `@Column` の付け場所だけ**で、フィールドの宣言そのものは変わらない。

### FIELD アクセス版(実際の `User.java`)

```java
@Entity
@Table(name = "users")
public class User {

    @Id                                    // ← アノテーションがフィールドに付いている
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;                       // ← Hibernate はここに直接代入する

    @Column(nullable = false)
    private String email;                  // ← Hibernate はここに直接代入する

    protected User() { }

    public String getEmail() {             // ← Hibernate は呼ばない。アプリ専用の窓口
        return email;
    }
    // setter を書かなくても Hibernate は困らない
}
```

Hibernate から見た経路:

```
DB の email 列 ──────────────▶ private String email    (直接)
                                      ▲
                                      │ getEmail() は経路の外
                                 アプリのコード
```

### PROPERTY アクセス版(このプロジェクトには存在しない架空の例)

```java
// ※ 対比のための架空コード。このリポジトリにこの形のエンティティは無い
@Entity
@Table(name = "users")
public class User {

    private Long id;                       // ← アノテーションが無い。Hibernate は直接は触らない
    private String email;                  // ← 同上

    protected User() { }

    @Id                                    // ← アノテーションが getter に付いている
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long getId() {
        return id;
    }

    @Column(nullable = false)
    public String getEmail() {             // ← 読み出しはこのメソッド経由
        return email;
    }

    public void setEmail(String email) {   // ← 書き込みはこのメソッド経由。省略できない
        this.email = email;
    }

    public void setId(Long id) {           // ← id にも setter が必要
        this.id = id;
    }
}
```

Hibernate から見た経路:

```
DB の email 列 ──▶ setEmail() ──▶ private String email
DB へ書く値    ◀── getEmail() ◀── private String email
```

### 差の一覧

| | FIELD | PROPERTY |
|---|---|---|
| アノテーションを付ける場所 | フィールド | getter メソッド |
| エンティティに**値を書き込む**経路(DB → オブジェクト) | フィールドに直接 | **setter を呼ぶ** |
| エンティティから**値を読み出す**経路(オブジェクト → DB) | フィールドから直接 | **getter を呼ぶ** |
| getter / setter は必要か | **不要**(無くても動く) | **必須**(片方欠けると起動時エラー) |
| getter / setter の名前 | **自由**(`body()` でも `readBody()` でもよい) | **JavaBeans 規約に従う必要がある** |
| 永続化の対象になるもの | `@Transient` 以外の**全フィールド** | `@Transient` 以外の**全プロパティ** |
| 列名の元になる名前 | フィールド名 | **getter 名から逆算したプロパティ名** |

JPA 仕様(Jakarta Persistence)は PROPERTY アクセスについてこう定めている。

> persistent properties of the entity class must follow the method signature conventions for JavaBeans read/write properties, as defined by the JavaBeans `Introspector` class. For every persistent property `property` of type `T` of the entity, there must be a getter method, `getProperty`, and setter method `setProperty`. For boolean properties, `isProperty` may be used as an alternative name for the getter method.

つまり PROPERTY では `getEmail()` / `setEmail()` という**名前そのものが仕様の一部**になる。`readEmail()` に改名したら `email` プロパティは消えてしまう。

### 思考実験1: setter に検証ロジックを入れたらどうなるか

```java
public void setBody(String body) {
    if (body.length() > 280) {
        throw new IllegalArgumentException("280 文字を超えています");
    }
    this.body = body;
}
```

- **FIELD アクセス**: DB から読み込むとき Hibernate は setter を呼ばないので、**この検証は一度も走らない**。DB に 300 文字入っていても素通りで読み込める。検証はアプリのコードが setter を呼ぶときだけ効く。
- **PROPERTY アクセス**: DB から読み込むたびに setter が呼ばれるので、**DB の既存データが検証に引っかかると読み込みで例外**になる。マイグレーション前の古いデータで壊れる典型パターン。

「setter に書いた処理が走るタイミングが変わる」—— これがアクセスタイプの一番実害のある差。

### 思考実験2: getter で値を加工したらどうなるか

```java
public String getEmail() {
    return email.toLowerCase();   // 小文字に揃えて返す
}
```

- **FIELD アクセス**: Hibernate は getter を通らずフィールドを読むので、**DB には加工前の値が保存される**。画面表示だけ小文字になる。
- **PROPERTY アクセス**: Hibernate が getter を呼んで保存値を決めるので、**DB に小文字化された値が保存される**。さらに厄介なのは、Hibernate が変更検知(dirty check)でも getter を呼ぶため、**毎回「値が変わった」と誤判定されて余計な UPDATE が飛ぶ**ことがある。

---

## 3. なぜ「`@Id` の位置」で決まるのか

「そんな判定方法があるのか」と唐突に感じる部分。順を追うと筋は通っている。

### 起動時に Hibernate がやっていること

Spring Boot の起動時、Hibernate は `@Entity` の付いたクラスを 1 つずつリフレクションで走査し、「どの列とどの値を対応させるか」の対応表(メタモデル)を組み立てる。このとき最初に決めなければならないのが **「そもそもフィールドを見に行くのか、メソッドを見に行くのか」**。ここが決まらないと走査を始められない。

そこで Hibernate は **必ず 1 つだけ存在するアノテーションである `@Id` の位置** を手がかりにする。Hibernate 7 のドキュメントの記述。

> Hibernate automatically determines the access type from the location of attribute-level annotations. Concretely: if a field is annotated `@Id`, field access is used, or if a getter method is annotated `@Id`, property access is used.

`@Id` はどのエンティティにも必ず 1 つある(主キーが無いエンティティは存在しない)。だから **「設定を追加せずに判定できる唯一の目印」** として使える。設定ファイルに `access-type: field` と書かせる代わりに、**すでに書いてあるアノテーションの位置を宣言として読み取っている**わけである。

### アノテーションの位置は「作者の意思表明」として扱われる

`@Column` をフィールドに書くということは、書いた人が「この**フィールド**が列に対応する」と考えている、という意思表明になる。getter に書けば「この**メソッドの戻り値**が列に対応する」という表明になる。Hibernate はその表明をそのまま経路の選択に使っている。

だから **混在させてはいけない**。同じドキュメントが一貫性を要求している。

> Mapping annotations should be placed consistently: if `@Id` annotates a field, the other mapping annotations should also be applied to fields, or if `@Id` annotates a getter, the other mapping annotations should be applied to getters.

`@Id` はフィールド、`@Column` は getter という書き方をすると、getter 側の `@Column` は**黙って無視される**(FIELD アクセスと判定されるため)。エラーにならず、`@Column(nullable = false)` などの指定だけが効かない状態になるので発見しにくい。

### 明示したいときは `@Access`

自動判定に頼らず明示することもできる。JPA 仕様の記述。

> The access type of an individual entity class, mapped superclass, or embeddable class may be specified for that class, independent of the default for the entity hierarchy to which it belongs, by annotating the class with the `Access` annotation.

```java
@Entity
@Access(AccessType.FIELD)   // 明示。@Id の位置に頼らない
public class User { ... }
```

このプロジェクトでは使っていない。`@Id` がフィールドに付いていることで FIELD が確定しており、追加の宣言は要らないため。

---

## 4. このプロジェクトでは

### 4 エンティティすべて FIELD アクセス

`@Id` の位置を確認した結果:

| エンティティ | `@Id` の位置 | アクセスタイプ |
|---|---|---|
| [User.java:18](../../../../backend/src/main/java/com/example/app/user/User.java) | フィールド `id` | FIELD |
| [Post.java:29](../../../../backend/src/main/java/com/example/app/post/Post.java) | フィールド `id` | FIELD |
| [Category.java:18](../../../../backend/src/main/java/com/example/app/category/Category.java) | フィールド `id` | FIELD |
| [AuthToken.java:30](../../../../backend/src/main/java/com/example/app/auth/AuthToken.java) | フィールド `id` | FIELD |

### 証拠1: `Post` は setter ゼロで動いている

`Post.java` には setter が 1 つも無い。それでも `postRepository.findById()` で DB から読み込める。**PROPERTY アクセスならこれは不可能**(setter が無いと値を入れられず起動時に失敗する)。setter 無しで動いている事実が、FIELD アクセスであることの実地の証拠になっている。

そしてこれは偶然ではなく設計。`Post.java:23` のコメントにある「setter を置かず getter だけにして『作成後は書き換えない(編集不可)』を構造で守っている」は、**FIELD アクセスだから選べる設計**である。PROPERTY アクセスを選んでいたら、Hibernate のために setter を公開せざるを得ず、この防御は成立しなかった。

### 証拠2: `User.setPasswordHash` は Hibernate 用ではない

`User.java:98` の `setPasswordHash` は、パスワード設定というアプリの操作のために置かれている。FIELD アクセスなので **Hibernate はこのメソッドを一度も呼ばない**。DB から `password_hash` 列を読むときは `passwordHash` フィールドに直接代入される。

「setter があるから Hibernate が使っている」わけではない、という区別が重要。

### 帰結: getter の名前は変えても壊れない(ただし別の理由で壊れる)

FIELD アクセスなので、`getEmail()` を `email()` に改名しても **Hibernate の永続化は壊れない**。ただし壊れる箇所は別にある。

- Jackson による JSON 変換(`@RestController` の戻り値)は JavaBeans 規約で動くため、レスポンスのキー名が変わる
- Thymeleaf などのテンプレートや、その getter を呼んでいるアプリのコード

つまり **「JPA は名前を見ていないが、他のライブラリは見ている」**。名前の話の全体像 → [jpa-entity-naming-rules.md](./jpa-entity-naming-rules.md)

---

## 5. 自分のコードにどう影響するか(判断基準)

### 新しくエンティティを書くとき

**FIELD を選ぶ(このプロジェクトの方針)。** `@Id` をフィールドに付ければそれだけで確定する。得られるもの:

- setter を置かない読み取り専用エンティティが書ける(`Post` の方式)
- getter に表示用の加工ロジックを入れても、保存される値には影響しない
- getter/setter を書き忘れても永続化は動く
- アノテーションがフィールドの真上に並ぶので、列との対応が上から下に読める

### PROPERTY を選ぶ動機があるケース

- **DB の列とオブジェクトの表現を変換したい**。例えば DB は `'Y'/'N'` の文字列だがオブジェクトは `boolean` で持ちたい、といった変換を getter/setter の中で書ける(ただし現代では `AttributeConverter` を使うほうが素直)
- **既存の JavaBeans 前提のフレームワークに合わせる必要がある**レガシー統合

いずれも今のプロジェクトには該当しない。**迷ったら FIELD**、が現代の実務でも標準的な選択。

### 既存エンティティを触るとき注意すること

- **アノテーションを getter に移動しない。** `@Column` だけ getter に移すと黙って無視される
- **フィールドを追加すると自動的に列とみなされる。** FIELD アクセスでは `@Transient` 以外の全フィールドが永続化対象。計算用の一時的なフィールドを足すと、`ddl-auto: validate` の下では**対応する列が無いと起動時エラー**になる。永続化したくないフィールドには `@Transient` を付ける
- **setter に検証を足しても DB 読み込み時には走らない。** 検証は Bean Validation(`@Size` など)や Service 層で行う(→ [validation-layers.md](../../validation-layers.md))

---

## つまずきポイント

- **「private なのに Hibernate が書けるのはおかしい」で止まらない。** リフレクションの `setAccessible(true)` でアクセス修飾子のチェックを外している
- **`@Id` はフィールド、`@Column` は getter という混在。** エラーにならず getter 側の指定だけ無視されるため気付きにくい
- **FIELD アクセスで setter に処理を書いて「呼ばれない」と悩む。** DB 読み込み時は setter を通らない
- **PROPERTY アクセスで getter を書き換えて余計な UPDATE が飛ぶ。** 変更検知でも getter が呼ばれるため
- **`@Transient` の付け忘れ。** FIELD アクセスでは全フィールドが列扱いになる
- **アクセスタイプはアプリのコードと無関係。** Service が `post.getBody()` を呼ぶのは常に getter 経由。アクセスタイプが変えるのは Hibernate 側の経路だけ

## 用語集

- **アクセスタイプ(access type)** — Hibernate がエンティティの値を読み書きするときに使う経路の種類。FIELD と PROPERTY の 2 つ
- **FIELD アクセス** — フィールドに直接読み書きする方式。`@Id` をフィールドに付けると選ばれる
- **PROPERTY アクセス** — getter/setter 経由で読み書きする方式。`@Id` を getter に付けると選ばれる
- **JavaBeans 規約** — `T getProperty()` / `void setProperty(T)`、boolean は `isProperty()` という命名・シグネチャの取り決め。PROPERTY アクセスで必須
- **プロパティ** — getter/setter の組から導かれる論理的な属性。`getEmail()` があれば `email` プロパティ
- **リフレクション** — 実行中にクラスの構造を名前で調べ・操作する Java の仕組み。`setAccessible(true)` で `private` の壁も越えられる
- **実体化(hydration)** — DB の行からオブジェクトを作り、値を詰める処理
- **変更検知(dirty check)** — 読み込み時の値と現在の値を比べ、UPDATE が必要か判定する処理
- **`@Transient`** — そのフィールド/プロパティを永続化対象から外すアノテーション
- **`@Access`** — アクセスタイプを明示的に指定するアノテーション。`@Id` の位置による自動判定を上書きできる
- **メタモデル** — 起動時に組み立てられる「クラスと列の対応表」

## 関連

- 名前のルール全体(フィールド名 → 列名、クエリメソッド名、必須ルールの一覧) → [jpa-entity-naming-rules.md](./jpa-entity-naming-rules.md)
- 引数なしコンストラクタ・`final` 不可・プロキシ・実体化の詳細 → [jpa-entity-noarg-constructor.md](./jpa-entity-noarg-constructor.md)
- JPA / Hibernate / Spring Data JPA の 3 層の役割分担 → [repository-and-entity-vs-laravel-model.md](./repository-and-entity-vs-laravel-model.md)
- 検証をどの層で行うか → [validation-layers.md](../../validation-layers.md)
- 公式ドキュメント
  - [Hibernate 7 ORM Introduction — Entities](https://docs.hibernate.org/orm/7.0/introduction/html_single/Hibernate_Introduction.html)(アクセスタイプの自動判定、エンティティの要件)
  - [Jakarta Persistence 3.2 仕様 — Access Type](https://jakarta.ee/specifications/persistence/3.2/jakarta-persistence-spec-3.2.html)(JavaBeans 規約、`@Access`)
