# 永続化コンテキスト — JPA はいつ SQL を発行するのか

このリポジトリには `flush` を呼ぶ箇所が 4 つあった。SQL ログを取って実測したところ、**そのうち 1 つは完全に不要**で、削除した。

なぜ 3 つは必要で、1 つは不要だったのか。答えはすべて **永続化コンテキスト(persistence context)がいつ SQL を出すか** に帰着する。`save()` を呼んだ瞬間に SQL が出ると思っていると、この仕分けはできない。

対象ファイル: [GoogleAccountService.java](../../../../backend/src/main/java/com/example/app/auth/GoogleAccountService.java) / [Post.java](../../../../backend/src/main/java/com/example/app/post/Post.java) / [PostRepositoryTest.java](../../../../backend/src/test/java/com/example/app/post/PostRepositoryTest.java) / [GoogleAccountServiceTest.java](../../../../backend/src/test/java/com/example/app/auth/GoogleAccountServiceTest.java)

このメモに出てくる SQL ログは**すべてこのリポジトリで実際に採取したもの**。採取方法は末尾の「実測のやり方」に書いた。

## まず結論(3 行)

1. **`save()` / `delete()` は即 SQL ではない。** 永続化コンテキストに「やる予定」として溜まり、flush のときにまとめて発行される
2. **ただし INSERT だけは例外で、`save()` の時点で即発行される。** このプロジェクトの id は DB の AUTO_INCREMENT 採番(`GenerationType.IDENTITY`)なので、INSERT を実行しないと id が分からないため
3. **だから明示的な flush が要るのは DELETE / UPDATE の順序を確定させたいときだけ。** 「保存を DB に届けるための flush」はこのリポジトリには 1 つも必要なかった

## 永続化コンテキストとは何か

**トランザクションの間だけ存在する、エンティティの置き場**。`EntityManager`(`jakarta.persistence.EntityManager`)が 1 つ持っていて、`@Transactional` なメソッドに入ってから抜けるまで生き続ける。Spring Data JPA の Repository も内部ではこれを使っているので、`postRepository.save()` は「`EntityManager` に置き場への出し入れを頼む」の言い換えにすぎない。

役割は 3 つある。

| 役割 | 何をするか | 別名 |
|---|---|---|
| **書き込みの遅延** | `save` / `delete` を溜めておき、flush でまとめて発行する | Unit of Work |
| **同一性の保証** | 同じ id のエンティティは常に同じ Java インスタンスを返す | Identity Map / 一次キャッシュ |
| **変更の追跡** | 置き場にあるエンティティの中身が書き換わったら、flush 時に UPDATE を自動生成する | dirty checking |

この 3 つはどれも「アプリが SQL を書かなくて済む」ための仕組みだが、**代償として「いつ SQL が出るか」がコードの見た目と一致しなくなる**。テストで踏む落とし穴はほぼ全部ここから来る。

## 役割 1: 書き込みの遅延 — ただし INSERT は例外

### 実測 — INSERT は `save()` の行で出ている

`save()` / `flush()` / `clear()` / 読み直しの前後に目印を出して SQL ログを採ると、こうなる。

```
###PROBE### A: postRepository.save() の直前
Hibernate: insert into posts (body,category_id,created_at,user_id) values (?,?,?,?)
###PROBE### B: save() の直後 id=405
###PROBE### C: entityManager.flush() の直前
###PROBE### D: entityManager.clear() の直前
###PROBE### E: findByIdWithDetails() の直前
Hibernate: select p1_0.id,p1_0.body,... from posts p1_0 join users u1_0 ... where p1_0.id=?
###PROBE### F: 読み直し完了 reloaded==saved は false
```

読みどころは 2 つ。

- **A と B の間に `insert` が出ている。** `save()` は溜めずにその場で INSERT した
- **C と D の間には SQL が 1 つも無い。** `flush()` は流すものが無く空転した

### なぜ INSERT だけ即発行なのか

`Post` の id はこう宣言されている。

```java
@Id
@GeneratedValue(strategy = GenerationType.IDENTITY) // 採番は DB の AUTO_INCREMENT 任せ
private Long id;
```

`IDENTITY` は「id を決めるのは DB」という意味。Hibernate は id の値を自分では作れず、知る方法は **INSERT を実行して DB に採番させ、`LAST_INSERT_ID()` で受け取る** しかない。

一方で `save()` は id の入ったエンティティを返す約束になっている。**戻り値に id を入れるには、その場で INSERT するしかない。** これが「遅延できない」の中身。

> **採番方式が違えば挙動も変わる。** PostgreSQL の `GenerationType.SEQUENCE` なら「シーケンスから次の番号だけ先にもらう」ことができるので、id を確定させたまま INSERT は flush まで遅らせられる(その場合 INSERT をまとめて発行するバッチ最適化も効く)。MySQL に SEQUENCE は無いので、このプロジェクトでは実質 IDENTITY 一択であり、結果として INSERT は常に即時になる。

DELETE と UPDATE には戻り値で返すものが何も無いので、素直に溜められる。**「INSERT だけが特別扱い」というのがこのメモで一番効いてくる事実。**

## 役割 2: 同一性の保証 — `clear()` が要る唯一の理由

同じトランザクション内で同じ id を取りに行くと、**SQL を発行しても結果を捨てて、置き場にある既存のインスタンスを返す**。同じ行が Java 側で 2 つのオブジェクトになると、片方だけ書き換えたときにどちらが正しいか決まらなくなるため。

### 実測 — `clear()` の有無で結果が変わる

```
（clear あり）
###PROBE### E: findByIdWithDetails() の直前
Hibernate: select p1_0.id,... where p1_0.id=?
###PROBE### F: 読み直し完了 reloaded==saved は false   ← 別インスタンス

（clear なし）
###PROBE### G: clear なしで findByIdWithDetails() の直前
Hibernate: select p1_0.id,... where p1_0.id=?
###PROBE### H: reloaded==saved は true                  ← 同じインスタンス
```

**どちらも SELECT は発行されている。** 違うのは結果の扱いだけで、`clear()` していない側は SELECT の結果を捨てて既存インスタンスを返している。

ここがテストで効く。`save()` の直後に `findById()` して中身を確かめても、**DB に行が入ったことの証明にはならない**。Java 側にオブジェクトがあることしか分かっていない。

```java
// PostRepositoryTest.readsBackSavedPost
Post saved = postRepository.save(new Post(user, category2, "読み直す投稿"));

entityManager.flush();  // 溜まっている変更を DB へ送る(この時点では空転する)
entityManager.clear();  // 置き場を空にする ← これが本命

Post reloaded = postRepository.findByIdWithDetails(saved.getId()).orElseThrow();
assertThat(reloaded).isNotSameAs(saved); // 別インスタンス = DB から読み直した証拠
```

**順序が `flush()` → `clear()` である理由**: `clear()` は溜まっている変更を**書き出さずに捨てる**。先に `clear()` すると未発行の DELETE / UPDATE が消えてしまう。このテストでは INSERT が既に出ているので実害は無いが、順序を覚え違えると別の場面で壊れる。

## 役割 3: 変更の追跡 — `save()` を呼ばなくても保存される

`GoogleAccountService.resolve()` のアカウントリンク分岐は、`save()` を呼んでいない。

```java
if (user.getEmailVerifiedAt() != null) {
    user.setGoogleSub(sub);
    return user;              // save() を呼んでいないのに google_sub は保存される
}
```

これは置き場にあるエンティティ(managed 状態)の中身が書き換わったことを Hibernate が検出し、flush 時に UPDATE を自動生成しているから。

### 実測 — setter だけで UPDATE が出る

```
###PROBE### I: setGoogleSub() の直前(save は呼ばない)
###PROBE### J: flush() の直前
Hibernate: update users set avatar_image_key=?,bio=?,display_name=?,email=?,
           email_verified_at=?,google_sub=?,password_hash=?,updated_at=?,username=? where id=?
###PROBE### K: flush() の直後
```

**変更したのは `google_sub` 1 列なのに、全列が UPDATE 文に載っている。** Hibernate の既定は「エンティティごとに UPDATE 文を 1 つ用意して使い回す」方式で、そのほうが SQL のパース結果をキャッシュしやすい。列を絞りたい場合は `@DynamicUpdate` を付けるが、SQL が毎回変わるぶん別のコストが乗るので、必要になるまで付けない。

この挙動には裏返しがある。**取ってきたエンティティを「一時的に書き換えて表示に使う」といったことをすると、意図せず UPDATE が飛ぶ。** エンティティを Controller まで持ち出さず DTO に変換しているのは、この事故を構造的に防ぐ意味もある。

## エンティティの 4 状態

ここまでの話は「エンティティが今どの状態か」で整理できる。

| 状態 | 意味 | 変更追跡 | 例 |
|---|---|---|---|
| **new / transient** | `new` しただけ。置き場に無い | されない | `new Post(user, category, "本文")` |
| **managed** | 置き場にある。DB の行と対応している | **される** | `save()` の戻り値、`findById()` の戻り値 |
| **detached** | 置き場から外れた。もう追跡されない | されない | `clear()` した後の変数、トランザクションを抜けた後 |
| **removed** | 削除予定として印が付いている | — | `delete()` した直後(まだ DELETE は出ていないことがある) |

`clear()` が何をするかは、これで一言で言える。**置き場の全エンティティを managed から detached に落とす。**

detached になった変数を触っても UPDATE は飛ばない。テストで `clear()` した後の `saved` を書き換えても DB には何も起きない。

## テストのときに何が起きるか

### `@DataJpaTest` がしていること

3 つある(→ 詳細は [testing-and-test-database.md](testing-and-test-database.md))。

1. DB 関連の Bean だけを載せた小さなアプリを起動する
2. Repository を `@Autowired` で受け取れるようにする
3. **各テストメソッドをトランザクションで包み、終了時にロールバックする**

3 番がこのメモの本題に直結する。

### テストとサービスは同じ永続化コンテキストを共有する

`@DataJpaTest` が張ったトランザクションの中で `googleAccountService.resolve()`(`@Transactional`)を呼ぶと、**Spring は新しいトランザクションを作らず、既にあるものに参加する**(伝播の既定値 `REQUIRED`)。トランザクションが 1 つなら永続化コンテキストも 1 つ。

つまり **サービスが溜めた変更が、そのままテスト側から見えている**。これは便利だが、次の 2 つの誤判定を生む。

- **偽陽性**: サービスが DB に書けていなくても、置き場にオブジェクトがあるのでテストが通る
- **偽陰性**: 本番では 2 つのトランザクションに分かれる処理が、テストでは 1 つに融合して別の挙動になる

### ロールバックで終わると何が起きないか

通常、flush はコミットの直前に自動で走る。ところが `@DataJpaTest` は**コミットしない**ので、その自動 flush が起きない。溜まったままの DELETE / UPDATE は **SQL が一度も発行されないまま消える**。

これが意味するのは、**DB 側の制約(UNIQUE、NOT NULL、外部キー)に当たるかどうかをテストで検証できない**ということ。制約違反は SQL を投げて初めて分かるため。

ただし前述のとおり **INSERT は即発行される**ので、「INSERT が制約に当たる」ケースだけは flush なしでも検出できる。このプロジェクトで「保存を届けるための flush」が要らなかったのはこれが理由。

### auto-flush — クエリの直前には自動で流れる

もう 1 つ、明示的な flush を不要にしている仕組みがある。**JPQL / 派生クエリを発行する直前、Hibernate は関係するテーブルの未反映の変更を自動で flush する**(`FlushModeType.AUTO`)。溜まった DELETE を無視して SELECT すると、消したはずの行が返ってきてしまうため。

```
###PROBE### T: delete() の直前
###PROBE### U: delete() の直後(flush は呼んでいない)
Hibernate: delete from users where id=?                        ← auto-flush
Hibernate: select u1_0.id,... from users u1_0 where u1_0.username=?
###PROBE### V: findByUsername() の直後
```

`delete()` の行では SQL が出ず、`findByUsername()` の直前でまとめて出ている。

**この auto-flush が効くのはクエリの直前だけで、INSERT の直前には効かない。** そこに順序の落とし穴がある。

```
###PROBE### W: delete() の直前
###PROBE### X: save() の直前(間に flush もクエリも挟まない)
Hibernate: insert into users (...) values (...)                ← INSERT が先に出た
###PROBE### Y: 例外 DataIntegrityViolationException
```

同じメールアドレスで「消してから作り直す」つもりが、**INSERT が先に発行されて UNIQUE 制約に当たった**。`GoogleAccountService` が `create()` の前に明示的に `userRepository.flush()` を呼んでいるのは、この順序を確定させるため。

```java
userRepository.delete(user);
// INSERT が先に走ってメールアドレスの UNIQUE 制約に当たらないよう、順序を確定させる
// (JPA は SQL の発行順を自分で決めるため)。
userRepository.flush();
```

なお現状の `create()` は `usernameGenerator.generateFrom()` の中で `findByUsername()` を呼ぶので、**明示的な flush が無くても auto-flush が DELETE を先に流してくれる**。それでもこの 1 行を残しているのは、`create()` の実装が変わった瞬間に上の例外が復活する形の依存だから。「たまたま効いている auto-flush」に順序を任せない、という保険にあたる。

### `flush()` / `clear()` / `saveAndFlush()` の使い分け

| やりたいこと | 呼ぶもの | 理由 |
|---|---|---|
| `save()` した id を使いたい | **何も呼ばない** | IDENTITY なので `save()` で INSERT 済み |
| INSERT が制約に当たるか確かめたい | **何も呼ばない** | 同上。`save()` の時点で例外が飛ぶ |
| DELETE / UPDATE を DB に届けたい | `flush()` | 溜まったままロールバックされるのを防ぐ |
| DELETE を INSERT より先に確定させたい | `flush()` | auto-flush は INSERT の前には効かない |
| DB に行が入ったことを確かめたい | `flush()` + `clear()` | 置き場を空にしないと同じインスタンスが返る |
| コミット後の処理を動かしたい | **`@Transactional` を外す** | flush ではコミットにならない |

最後の行は [AuthFlowTest](../../../../backend/src/test/java/com/example/app/auth/AuthFlowTest.java) の事情。確認メールの送信は `@TransactionalEventListener(AFTER_COMMIT)` で動くので、テストをトランザクションで囲むと発火しない。だから `AuthFlowTest` は `@Transactional` を付けず、作ったデータを `@BeforeEach` / `@AfterEach` で自分で消している。**flush はコミットではない** — この区別がここで効く。

## 4 箇所の仕分け — 実測の結果

冒頭の問いに戻る。

| 箇所 | 種類 | 判定 | 理由 |
|---|---|---|---|
| `GoogleAccountService:90` | 本番コード `userRepository.flush()` | **必要** | DELETE を INSERT より先に確定させる。消すと `DataIntegrityViolationException` の形に戻りうる |
| `PostRepositoryTest.readsBackSavedPost` | `flush()` + `clear()` | **必要**(`clear` が) | 置き場を空にしないと DB を読んだ証明にならない |
| `UsernameGeneratorTest` | `saveAndFlush()` | 不要だが**残す** | INSERT は即発行なので `save()` で足りる。「先に DB へ入れておく」という前提を明示する意味で残している |
| `GoogleAccountServiceTest` | `entityManager.flush()` | **不要 → 削除した** | DELETE は `resolve()` の中で流れており、INSERT は IDENTITY で発行済み。流すものが残っていなかった |

4 番目は実際に消してテストを走らせ、6 本とも通ることを確認した上で削除している。

## 他の ORM に同じ仕組みはあるか

**結論から言うと、これは Java 固有の仕組みではない。** ORM が **Data Mapper** パターンを採ったかどうかで決まる。同じ言語でも ORM が違えば挙動が違う。

### 3 つの設計パターン — 誰が「保存する」責任を持つか

比較表に入る前に、そこに出てくる 3 つのパターン名を押さえておく。違いは一言で言える。**保存の主語が誰か。**

**Active Record — オブジェクト自身が保存する**

```php
$user = User::find(1);        // モデルが自分を検索する
$user->google_sub = 'sub-123';
$user->save();                // モデルが自分を保存する
```

1 つのクラスが「データの入れ物」と「DB を操作するメソッド」を兼ねる。レコード自身が能動的(active)に振る舞うのでこの名前。Laravel Eloquent、Rails ActiveRecord が代表。

**Data Mapper — データと保存係を分ける**

```java
User user = userRepository.findById(1L).orElseThrow(); // 保存係が検索する
user.setGoogleSub("sub-123");                          // データは書き換えるだけ
// 保存は EntityManager の仕事(このプロジェクトでは変更追跡が拾う)
```

`User` は getter / setter しか持たず、DB を操作するメソッドを 1 つも持たない。出し入れは `EntityManager`(と、その上に乗る Repository)という別の層が担当する。JPA / Hibernate、Doctrine、SQLAlchemy、EF Core が代表。

**クエリビルダ — そもそもオブジェクトを行に対応づけない**

```ts
const [user] = await db.select().from(users).where(eq(users.id, 1));
await db.update(users).set({ googleSub: 'sub-123' }).where(eq(users.id, 1));
```

返ってくるのは「users テーブルの 1 行を写し取っただけのオブジェクト」で、DB の行と結びついていない。SQL を型安全に組み立てる道具であって、オブジェクトを管理する気がない。Drizzle が代表で、Prisma もこちら寄り。

### なぜこの違いが永続化コンテキストの有無を決めるのか

永続化コンテキストは「今このトランザクションで預かっているエンティティ全部」を 1 箇所で把握していないと成立しない。溜める・追跡する・同一性を保証する、のどれも**全体を見ている誰か**を必要とするため。

- **Data Mapper には、その「誰か」がいる。** 保存係(`EntityManager`)が 1 人だけ存在し、出し入れが全部そこを通る。だから「今どのオブジェクトを預かっているか」を知っていて、変更を溜めることも、同じ id には同じインスタンスを返すこともできる。**永続化コンテキストは Data Mapper を採ったから可能になった機能**であって、おまけで付いてきたものではない
- **Active Record には、その「誰か」がいない。** `$user->save()` は誰にも相談せず自分で SQL を出す。全モデルが個別に自分を保存する構造なので、全体をまとめて管理する層が置けない。`$user1` と `$user2` が同じ行を指していても、互いの存在を知る手段がない
- **クエリビルダには、管理する対象がない。** 返るのはただのオブジェクトで、DB の行との対応が切れている。追跡しようにも「これはどの行か」を ORM が覚えていない

つまり **Active Record とクエリビルダに永続化コンテキストが「無い」のは、機能をサボっているのではなく、構造上置き場が無い**ということ。

### 見分け方 — 保存のコードを見れば分かる

| 書き方 | 主語 | パターン |
|---|---|---|
| `user.save()` / `$user->save()` | ユーザーが保存する | **Active Record** |
| `userRepository.save(user)` / `em.persist(user)` | 保存係がユーザーを保存する | **Data Mapper** |
| `db.insert(users).values({...})` | users テーブルに INSERT する | **クエリビルダ** |

なお境界はきれいに割り切れない。**TypeORM は Active Record モードと Data Mapper モードのどちらでも書ける**ようになっており、下の表で「両対応」としているのはこのため。Prisma は自身を ORM と呼ぶが、オブジェクトを追跡しない点でクエリビルダ側に置いている。

Repository と Entity をなぜ 2 つのクラスに分けるのか、という**クラス設計の側から見た比較**は [repository-and-entity-vs-laravel-model.md](repository-and-entity-vs-laravel-model.md) にある。ここではあくまで「SQL がいつ出るか」に効く部分だけを扱っている。

### 主要 ORM の対応表

| ORM | 言語 | パターン | 同一性の保証 | 変更の追跡 | flush 相当 |
|---|---|---|---|---|---|
| **JPA / Hibernate** | Java | Data Mapper | あり | あり | `flush()` |
| **Doctrine** | PHP | Data Mapper | あり | あり | `flush()` |
| **SQLAlchemy** | Python | Data Mapper | あり | あり | `flush()` |
| **EF Core** | C# | Data Mapper | あり | あり | `SaveChanges()` |
| **MikroORM** | TypeScript | Data Mapper | あり | あり | `flush()` |
| **TypeORM** | TypeScript | 両対応 | 限定的 | 限定的 | — |
| **Eloquent** | PHP | Active Record | **なし** | なし | **なし** |
| **Rails ActiveRecord** | Ruby | Active Record | **なし** | なし | **なし** |
| **Prisma** | TypeScript | クエリビルダ寄り | **なし** | なし | **なし** |
| **Drizzle** | TypeScript | クエリビルダ | **なし** | なし | **なし** |

つまり「Laravel だから無い」ではなく「**Laravel が Eloquent(Active Record)を選んでいるから無い**」。PHP でも Symfony でよく使われる Doctrine は `EntityManager` と `flush()` を JPA とほぼ同じ形で持っている。Node.js も同様で、Drizzle には無いが MikroORM にはある。

### Laravel(Eloquent)— 書いた瞬間に SQL

```php
$user = User::find(1);
$user->google_sub = 'sub-123';
// ここでは何も起きない

$user->save();
// ここで UPDATE users SET google_sub = ? WHERE id = 1
```

JPA との違いが 3 つある。

- **`save()` を呼ばないと保存されない。** JPA の変更追跡に相当するものは無い。`isDirty()` / `getDirty()` はあるが、これは「`save()` のときに変更列だけ UPDATE する」ための差分検出であって、自動保存ではない
- **同一性の保証が無い。** `User::find(1)` を 2 回呼ぶと SELECT が 2 回飛び、別インスタンスが 2 つできる。片方を書き換えても、もう片方は古いまま
- **flush が無い。** 書いた順に SQL が出るので、「DELETE と INSERT のどちらが先か」で悩むことがない

代わりに Eloquent 側の落とし穴がある。**`save()` の呼び忘れ**(JPA では変更追跡が拾ってしまう)と、**N+1**(JPA も同じだが、Eloquent は `with()` を書き忘れると静かに発生する)。

テストの書き味も変わる。Laravel の `RefreshDatabase` トレイトは JPA と同じく「トランザクションで包んでロールバック」する仕組みだが、**置き場が無いので「一次キャッシュに騙される」問題が構造的に起きない**。

```php
$this->assertDatabaseHas('users', ['google_sub' => 'sub-123']);
// 素の SELECT なので、常に DB の実際の状態を見ている
$user->refresh(); // 明示的に DB から読み直す。JPA の clear() + 再取得に近い
```

`assertDatabaseHas()` は JPA でいう `flush()` + `clear()` + 読み直しを 1 行で済ませているようなもの。JPA 側でこれに相当する手軽さが無いのは、置き場という中間層がある分の代償にあたる。

### Drizzle(Node.js/TypeScript)— await した瞬間に SQL

```ts
const [post] = await db.insert(posts).values({ userId, categoryId, body }).returning();
// この行で INSERT が実行される

post.body = '書き換え';
// ただのオブジェクトを書き換えただけ。DB には何も起きない

await db.update(posts).set({ body: '書き換え' }).where(eq(posts.id, post.id));
// UPDATE は自分で書く
```

Drizzle は「型の付いた SQL ビルダ」に近い設計で、**エンティティという概念自体を持たない**。返ってくるのはクラスのインスタンスですらなく、ただのオブジェクト。だから追跡するものも、溜めるものも無い。

- **`flush` が無い**。`await` した行で SQL が出る
- **同一性の保証が無い**。同じ行を 2 回取れば別オブジェクトが 2 つできる
- **トランザクションは `db.transaction(async (tx) => { ... })`**。中では `tx` を使う必要があり、うっかり `db` を使うとトランザクションの外に出てしまう

テストで「保存されたか」を確かめるときは、**素直にもう一度 SELECT すればよい**。読んだものは必ず DB の中身なので、`clear()` に相当する操作が要らない。

```ts
const found = await db.select().from(posts).where(eq(posts.id, id));
expect(found[0].body).toBe('新しい投稿');
```

### この差は何を交換しているのか

| | JPA / Doctrine / SQLAlchemy | Eloquent / Drizzle |
|---|---|---|
| SQL の発行回数 | 少ない(まとめられる) | 書いた分だけ |
| コードと SQL の対応 | **見た目と一致しない** | 一致する |
| 更新の書き方 | setter だけでよい | 明示的に書く |
| テストで気をつけること | flush / clear の要否 | 特になし |
| 得意な場面 | 1 トランザクションで多数の更新をする業務ロジック | 読み中心・SQL を自分で制御したい場面 |

**「賢い中間層を置いて楽をする代わりに、SQL がいつ出るかを追えなくなる」** という交換をしているのが Data Mapper 系で、**「毎回自分で書く代わりに、書いたとおりに動く」** のが Active Record / クエリビルダ系、と捉えるとよい。どちらが優れているという話ではなく、このメモの前半で扱った落とし穴は**前者を選んだ代償**にあたる。

## 実測のやり方

このメモのログは、SQL ログを一時的に有効にして使い捨てのテストを走らせて採った。設定ファイルは変更していない。

```bash
# Hibernate の SQL ログだけを一時的に DEBUG にする(application.yml は触らない)
docker compose exec -e LOGGING_LEVEL_ORG_HIBERNATE_SQL=DEBUG backend \
  sh ./gradlew test --tests '*XxxTest*' --rerun-tasks

# テストの標準出力は端末に出ないので、XML レポートから取り出す
docker compose exec backend sh -c \
  "grep -E 'Hibernate:' build/test-results/test/TEST-com.example.app.xxx.XxxTest.xml"
```

コードのどの行で SQL が出たかを知りたいので、`System.out.println` で目印を挟むとログの中に混ざって順序が読める。テストの標準出力も同じ XML の `<system-out>` に入る。

## つまずきポイント

- **`save()` したのに DB に無い、は INSERT では起きない。** IDENTITY 採番なので即発行される。起きるのは DELETE と UPDATE
- **`clear()` を先に呼ぶと未発行の変更が消える。** 必ず `flush()` → `clear()` の順
- **`findById()` で確かめても DB を見たことにならない。** 同じトランザクション内なら置き場のインスタンスが返る
- **`flush()` はコミットではない。** ロールバックすれば消えるし、`AFTER_COMMIT` のイベントも発火しない
- **エンティティを一時的に書き換えると UPDATE が飛ぶ。** managed 状態のものは触った時点で追跡対象
- **auto-flush はクエリの直前にしか効かない。** INSERT の前には効かないので、DELETE → INSERT の順序は自分で確定させる

## 関連

- テストの実行方法と `app_test` の作り方 → [docs/test/README.md](../../../test/README.md)
- `@SpringBootTest` / `@WebMvcTest` / `@DataJpaTest` の使い分けと Flyway との関係 → [testing-and-test-database.md](testing-and-test-database.md)
- Repository と Entity を分ける理由、Active Record と Data Mapper の詳しい比較 → [repository-and-entity-vs-laravel-model.md](repository-and-entity-vs-laravel-model.md)
- エンティティに引数なしコンストラクタが要る理由 → [jpa-entity-noarg-constructor.md](jpa-entity-noarg-constructor.md)
- コミット後に処理を動かす仕組み(`@TransactionalEventListener`)→ [application-events-vs-queues.md](application-events-vs-queues.md)
