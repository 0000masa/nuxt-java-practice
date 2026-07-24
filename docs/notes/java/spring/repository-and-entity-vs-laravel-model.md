# Spring の Repository と Entity — なぜ 2 つに分けるのか、Laravel の Model と何が違うのか

`PostRepository` を見ていて湧く疑問 — 「手書き SQL(JPQL)を書かないなら Repository は要らないのでは?」「`postRepository.save()` は Repository に書いていないのになぜ呼べる?」「`interface PostRepository extends JpaRepository<Post, Long> {}` と継承しているだけなら、Repository を書かずにエンティティ(`Post`)を直接使えないのか?」 — に答える学習メモ。あわせて Spring の **Repository / Entity** と Laravel の **Model** の関係を、**Active Record と Data Mapper** という 2 つの設計パターンの違いとして整理する。

対象ファイル: [PostRepository.java](../../../../backend/src/main/java/com/example/app/post/PostRepository.java) / [Post.java](../../../../backend/src/main/java/com/example/app/post/Post.java)

## JPA とは — Java の ORM の「標準仕様」であって実装ではない

このメモに何度も出てくる **JPA(Jakarta Persistence API、旧 Java Persistence API)** は、「Java でオブジェクトと DB テーブルを対応づける(= ORM する)なら、こういうアノテーションや API を用意しましょう」という**標準仕様(spec)**の名前。**仕様 = ルールブックであって、動く製品(実装)ではない**のがポイント。`@Entity` / `@Id` / `EntityManager` といった顔ぶれ(`jakarta.persistence.*`)はすべて JPA が定めたもの。

「仕様だけでは動かないのでは?」— そのとおりで、実際に DB とやり取りする**エンジン(実装)は別にある**。このプロジェクトを含む多くの Spring Boot アプリでは **Hibernate** がその実装を担う。さらにその上に、Repository を interface で書くだけにしてくれる便利層 **Spring Data JPA** が乗る。3 層の重なりで捉えるとよい:

| 層 | 正体 | このプロジェクトでの担い手 | たとえ |
|---|---|---|---|
| **JPA** | ORM の標準仕様(アノテーション・API の取り決め) | `jakarta.persistence.*`(`@Entity` 等) | コンセントの規格(形状のルール) |
| **Hibernate** | JPA を実装した ORM エンジン。実際に SQL を発行する。**Java 製**のライブラリ(Java で書かれ、Java アプリから使う) | 依存に含まれ裏で動く | 規格に合った実際の家電 |
| **Spring Data JPA** | Repository を interface だけで書けるようにする便利層 | `JpaRepository` / `@Query` | 家電をさらに使いやすくするリモコン |

「規格(JPA)に沿って作ってあるので、エンジンを Hibernate から別実装(EclipseLink など)に替えてもアプリのコードはほぼそのまま動く」— これが標準仕様のうれしさ。Laravel でいえば Eloquent が「仕様も実装も兼ねた 1 つの製品」なのに対し、Java は「仕様(JPA)と実装(Hibernate)を分けている」構図。

> 豆知識: 昔は `javax.persistence.*` という名前だった。**Java EE(Java Platform, Enterprise Edition)** — 標準の Java SE にサーバー向け・企業システム用の機能(Servlet / JSP / JPA など)を足した**仕様の集まり**。その JPA は Java EE の一部だった — が Oracle から Eclipse Foundation に移管され、**Jakarta EE** に改称された。この際「Java」という商標が使えず、パッケージ接頭辞も `javax` → `jakarta` に変わったため、`javax.persistence.*` が今の `jakarta.persistence.*` になった。中身・意味は同じ。

## まず結論(3 行)

1. **エンティティ単体では DB を検索・保存できない。** `Post` は「データの入れ物」であって、DB を叩くメソッドを 1 つも持っていない。
2. **だから手書き SQL が無くても Repository は要る。** `interface XxxRepository extends JpaRepository<...> {}` という**空の interface を書くのが正しいやり方**。それだけで `save` / `findById` などが使える。
3. **Laravel の Model は逆。** モデル自身が「データ+クエリ発行」を兼ねる(Active Record)。Spring は「データ(Entity)」と「クエリ発行(Repository)」を別クラスに分ける(Data Mapper)。ここが最大の違い。

## エンティティは「受け身のデータ」— 単体では DB を叩けない

`Post.java` をもう一度眺めると、そこにあるのは**フィールド**(id / user / category / body / createdAt)と**コンストラクタ**と**getter** だけ。`findAll()` や `save()` のような「DB とやり取りするメソッド」は 1 つも無い。

```java
@Entity
@Table(name = "posts")
public class Post {
    @Id private Long id;
    private User user;
    private String body;
    // ... getter だけ。DB を検索/保存するメソッドは無い
}
```

つまりエンティティは、荷物でいう**「箱そのもの」**。箱は自分で倉庫に出入りできない。「`@Entity` が付いているんだから DB とつながっていそう」と感じるが、`@Entity` は **「この箱は posts テーブルの 1 行に対応します」という"対応表の宣言"**にすぎず、**運ぶ動作**(出し入れ)は持っていない。

だから `Post.findById(1)` のような書き方はコンパイルエラーになる。**エンティティを直接使って DB から取ってくることはできない。** これが最初の、そして一番大事なポイント。

## だから Repository が要る — 手書き SQL が無くても「空の interface」を書く

箱を倉庫に出し入れする**「運び屋」**が Repository。`Post` を DB と出し入れするには、必ず `PostRepository` のような窓口が要る。

### `postRepository.save()` の出どころ — 継承で降ってくる

`PostRepository` 本体には `save` も `findById` も書いていない。なのに `PostService` で `postRepository.save(...)` が呼べるのは、**`JpaRepository` から継承しているから**。

```java
public interface PostRepository extends JpaRepository<Post, Long> {
    // ↑ この 1 行で save / findById / findAll / delete / count ... が全部ついてくる
}
```

`extends JpaRepository<Post, Long>` は「`Post` を扱い、主キーの型は `Long` の Repository ですよ」という宣言。Spring Data JPA が**起動時にこの interface の実装クラスを自動生成**し、`save` などの中身をそこに用意する。だから自分では 1 行も書いていない `save` が呼べる(→ 名前解決や interface の仕組みは [java-package-basics.md](../../java-package-basics.md) も参照)。

> **なぜ主キーの型が `Long`(小文字の `long` ではない)?** 主キーは DB では NOT NULL なのに、なぜ null を許す `Long` を使うのか — 理由は 2 つ。
> 1. **ジェネリクスの型引数にはプリミティブ型を書けない。** `JpaRepository<Post, long>` は Java の文法上アウトで、型引数にはオブジェクト型(`long` を包んだ箱である `Long`)しか渡せない。この一点だけでも `long` は選べない。
> 2. **保存前は id が未採番 = `null` だから。** id は `@GeneratedValue` で DB の AUTO_INCREMENT に採番を任せているので、`new Post(...)` した直後・`save()` する前は id が決まっていない。プリミティブの `long` は初期値 0 が入ってしまい「まだ id が無い」を表せないが、`Long` なら `null` で表せる。Hibernate はこの `id == null` を見て「これは新規レコードだ(INSERT すべき)」と判断する。
>
> 「主キーは NOT NULL では?」という疑問はもっともで、それは**保存後の DB 列の制約**の話。一方**Java オブジェクトの一生**で見ると、生成〜保存の間に id が null の瞬間があり、その状態を表すために nullable な `Long` を使う。DB 側(NOT NULL)と Java オブジェクト側(一時的に null あり)は別レイヤーの話、と分けて考えるとよい。

### 手書き SQL が無いなら「空の interface」が正解

ここで本題。**カスタムクエリ(`findTimeline` のような `@Query`)が要らない場合でも、Repository は書く。** 中身を空にするだけ:

```java
public interface CommentRepository extends JpaRepository<Comment, Long> {
    // 標準 CRUD しか要らないなら、中身は空でよい。これで完成
}
```

「空なら書く意味がないのでは?」と思うかもしれないが、**この空 interface こそが `save` / `findById` などの"運ぶ動作"を手に入れる唯一の入口**。空でも書くのが正しい。エンティティを直接使う道は無い(前述のとおりエンティティは箱でしかないから)。

> 補足: 実は Repository を書かず、`EntityManager`(JPA の最下層の API)を直接使って DB を叩く方法も存在する。ただしそれは低レベルで冗長になりがちで、Spring では **「エンティティごとに Repository interface を 1 つ書く」のが標準の作法**。このプロジェクトも全エンティティがこの流儀(Post / Category / User すべてに Repository がある)。

## SQL を書かなくてもクエリは作れる — 3 段階

「Repository = SQL を書く場所」と思いがちだが、実際には**手書き SQL を書かずに済む方法が 2 段階あり、手書きは最終手段**。このプロジェクトの 3 つの Repository がちょうど 3 段階の実例になっている。

### ① 継承だけ — 標準 CRUD(SQL を 1 文字も書かない)

`JpaRepository` を継承した時点で使える `save` / `findById` / `findAll` / `delete` / `count` など。`PostService.create` の `postRepository.save(...)` や `delete` の `postRepository.findById(id)` がこれ。**SQL もメソッド定義も不要。**

### ② メソッド名から自動生成 — 命名規則クエリ(これも SQL を書かない)

メソッド名を**規則どおりに書くだけ**で、Spring Data が SQL を組み立ててくれる。このプロジェクトの実例:

```java
// UserRepository.java
Optional<User> findByUsername(String username);
//              └ findBy + フィールド名(username)→「username で 1 件検索」の SQL が自動生成される

// CategoryRepository.java
List<Category> findAllByOrderByDisplayOrderAsc();
//             └ findAll + OrderBy + displayOrder + Asc →「displayOrder 昇順で全件」が自動生成される
```

`@Query` も SQL も書いていないのに検索できる。`findBy○○` / `OrderBy○○Asc` といった**メソッド名のパターン**が、そのまま問い合わせ条件になる。Laravel でいう `Post::where('username', $name)->first()` に一番近い感覚。

### ③ `@Query` の JPQL — 自動生成では表せないときの手書き

`findTimeline` や `findByIdWithDetails` がこれ。`join fetch` やカーソル条件など、メソッド名では表現しきれない複雑なクエリを **JPQL**(テーブル名でなくクラス名で書く JPA 専用言語)で書く。さらに DB 固有の生 SQL が要れば `@Query(nativeQuery = true)` も使える。

**まとめ**: ①→②→③ の順に「手書き度」が上がる。多くのメソッドは①②で足り、③は本当に必要なときだけ。だから「SQL を書かない = Repository 不要」ではなく、**「SQL を書かなくても Repository は①②の形で立派に働く」**が正解。

## Laravel の Model との対比 — 「クエリ発行役」を誰が持つか

「Laravel の Model は SQL を書かないですよね?」という疑問は、半分正しく半分誤解。正確には **「Laravel の Model は"クエリ発行の役割"を自分で持っている(＝ Spring の Repository の仕事も兼ねる)」**。

| 役割 | Laravel(Eloquent) | Spring(JPA) |
|---|---|---|
| データの形・1 行の入れ物 | **Model**(`app/Models/Post.php`) | **Entity**(`Post.java`) |
| DB へのクエリ発行 | **同じ Model**(`Post::where(...)`, `$post->save()`) | **別クラスの Repository**(`postRepository.save(...)`) |

- Laravel は `Post::find(1)` や `$post->save()` のように、**モデル自身がクエリを発行できる**。モデルが「箱」と「運び屋」を兼ねている。
- Spring は箱(`Post`)と運び屋(`PostRepository`)を**別々のクラスに分ける**。`Post` にクエリ発行メソッドは無い。

### 「Laravel はSQLを書かない」は誤解 — 書く場所と手段はある

- `Post::where('category_id', 1)->get()` の `where(...)` は、**内部で SQL(`WHERE category_id = ?`)を組み立てている**。書き方が SQL に見えないだけで、クエリを作っているのは事実。
- 生 SQL も書ける: `DB::select('SELECT ...')` や `Post::whereRaw('...')`。Laravel でも生 SQL は普通に書ける。
- つまり Spring と Laravel の違いは **「SQL を書く/書かない」ではなく「クエリ発行の役割を"データと同じクラス"に持たせるか、"別クラス(Repository)"に分けるか」**。

## パターン名で整理 — Active Record と Data Mapper

この違いには設計パターンの正式名がある。他の言語・FW を触るときにも効く知識なので押さえておくとよい。

- **Active Record(Laravel Eloquent / Ruby on Rails)** — **1 つのオブジェクトがデータとDB操作を兼ねる**。`$post->save()` のように「レコード自身が能動的(active)に自分を保存する」。手軽で書きやすいのが長所。
- **Data Mapper(Spring Data JPA / Hibernate / Doctrine(PHP))** — **データ(Entity)と、DB との対応づけ(データの出し入れ)を担う層(Repository / EntityManager)を分離する**。エンティティは DB を意識しない純粋なデータでいられ、テストや保守で両者を巻き込みにくいのが長所。分けるぶんクラス数は増える。

| | Active Record | Data Mapper |
|---|---|---|
| 代表 | Laravel Eloquent, Rails | Spring Data JPA, Hibernate, Doctrine |
| データと DB 操作 | 同じクラスが兼ねる | 別クラスに分ける(Entity / Repository) |
| 保存の書き方 | `$post->save()` | `postRepository.save(post)` |
| 手軽さ | ◎(1 クラスで完結) | ○(クラスは増える) |
| データとDBの分離 | ○(密結合寄り) | ◎(疎結合) |

どちらが優れているという話ではなく**思想の違い**。Spring が Data Mapper を採るのは、「データの形(滅多に変わらない)」と「DB アクセスのやり方(要件でよく変わる)」を混ぜない、という Java 文化の「役割ごとにクラスを分ける」志向に沿っている。

## つまずきポイント

- **「@Entity が付いてるから DB とつながっている」ではない。** `@Entity` は対応表の宣言だけ。出し入れは必ず Repository(または EntityManager)経由。
- **空 interface を「無意味」と思って消さない。** 空でも `JpaRepository` 継承ぶんの CRUD を提供する本体。消すと `save` などが呼べなくなる。
- **Repository に SQL が無い ≠ クエリしていない。** ①標準 CRUD ②メソッド名クエリ は SQL を書かずに検索している。手書き `@Query` は③の段階だけ。
- **Laravel 脳で `Post.findById()` と書かない。** Spring ではエンティティにクエリメソッドは無い。`postRepository.findById()` と Repository 経由で書く。
- **メソッド名クエリはスペルが命。** `findByUserName` と `findByUsername` はフィールド名と一致しないと起動時にエラー。②はメソッド名がそのまま仕様になるため、フィールド名と厳密に揃える必要がある。

## 用語集

- **JPA(Jakarta Persistence API、旧 Java Persistence API)** — Java で ORM を行うための標準仕様。`@Entity` などの `jakarta.persistence.*` を定める。仕様であって実装ではない
- **Hibernate** — JPA を実装した ORM エンジン。実際に SQL を発行する。Spring Boot の既定実装
- **Spring Data JPA** — Hibernate の上に乗り、Repository を interface だけで書けるようにする便利層。`JpaRepository` を提供する
- **Java SE / Java EE** — Java SE(Standard Edition)は Java の標準機能一式。Java EE(Enterprise Edition)はそれにサーバー向け・企業システム用の仕様(Servlet / JSP / JPA など)を足した仕様の集まり
- **Jakarta EE** — Java EE が Oracle から Eclipse Foundation に移管された後の名称。商標の都合でパッケージ接頭辞が `javax` → `jakarta` に変わった(`jakarta.persistence.*` はこの流れ)
- **プリミティブ型 / ラッパー型** — `long`(プリミティブ)は値そのもので null 不可・初期値 0。`Long`(ラッパー)は long を包んだオブジェクト型で null を持てる。ジェネリクスの型引数や「未設定」を表したい場面ではラッパー型を使う
- **エンティティ(Entity)** — DB テーブルの 1 行に対応する「データの入れ物」クラス。DB 操作メソッドは持たない受け身の存在
- **リポジトリ(Repository)** — エンティティを DB と出し入れする窓口。Spring Data JPA では interface として書く
- **`JpaRepository<T, ID>`** — 継承するだけで標準 CRUD(save/findById/findAll/delete/count 等)を提供する Spring Data の基底 interface。`T` は扱うエンティティ、`ID` は主キーの型
- **標準 CRUD** — 作成(Create)・読取(Read)・更新(Update)・削除(Delete)の基本操作
- **命名規則クエリ(派生クエリ)** — `findByUsername` のようにメソッド名の規則から SQL を自動生成する仕組み。SQL を書かずに検索できる
- **JPQL** — テーブル名でなくクラス名で書く JPA 専用の問い合わせ言語。`@Query` で指定する
- **`EntityManager`** — JPA の最下層 API。Repository を使わず直接 DB を叩くこともできるが、通常は Repository 越しに使う
- **Active Record** — 1 オブジェクトがデータと DB 操作を兼ねる設計パターン。Laravel Eloquent / Rails が代表
- **Data Mapper** — データ(Entity)と、DB との対応づけを担う層(Repository)を分離する設計パターン。Spring Data JPA / Hibernate / Doctrine が代表
- **Eloquent** — Laravel の ORM。Active Record 方式のモデルを提供する

## 関連

- パッケージ・interface・import の仕組み(なぜ Entity と Repository を別ファイル・別クラスにできるか) → [java-package-basics.md](../../java-package-basics.md)
- `PostRepository` の JPQL・join fetch・カーソルページネーションの詳細 → [PostRepository.java](../../../../backend/src/main/java/com/example/app/post/PostRepository.java) のコメント
- `Post` エンティティの `@Entity` / `@ManyToOne` / コンストラクタの読み方 → [Post.java](../../../../backend/src/main/java/com/example/app/post/Post.java) のコメント
