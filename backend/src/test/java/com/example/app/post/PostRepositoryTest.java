package com.example.app.post;

// import static … クラス名を省略して static メソッドを直接呼べるようにする import。
//   これがあるので Assertions.assertThat(...) ではなく assertThat(...) と書ける。
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
// Spring Boot 4 でテストアノテーションのパッケージが技術別モジュールに移動している(Boot 3 の記事とは import が異なる)
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;

import com.example.app.category.Category;
import com.example.app.category.CategoryRepository;
import com.example.app.user.User;
import com.example.app.user.UserRepository;

/**
 * カーソルページネーションのクエリ検証(バグの温床になりやすい境界条件を押さえる)。
 * テスト専用 database app_test の MySQL に対して実行し、各テスト後にロールバックされる
 * (接続先の切り替えは backend/build.gradle の test タスク)。
 *
 * <p><b>検証対象 findTimeline の前提</b>(クエリ本体と詳しい読み方 → PostRepository.java:20-46)
 * <ul>
 * <li>カーソルページネーション = 「◯ページ目」ではなく「この id より古いものを◯件」で次ページを取る方式。
 * 閲覧中に投稿が増えても同じ投稿が 2 回出ない。id(AUTO_INCREMENT)をカーソルに使うので「新しい順 = id の降順」</li>
 * <li>cursor / categoryId はどちらも null 可で、null ならその条件が無効化される(有無 4 パターンを 1 本のクエリでさばく)</li>
 * <li>JPQL に LIMIT 構文がないため、件数制限は Pageable 経由でしか渡せない</li>
 * </ul>
 */
// @DataJpaTest … JPA と Repository だけを立ち上げるスライステスト。これを見つけた Spring が次の 3 つをする。
//   1. DB 関連の部品だけを載せた小さなアプリを起動する(@RestController や @Service は読み込まない)
//   2. Repository を @Autowired で受け取れるようにする
//   3. 各テストメソッドを自動でトランザクションに包み、終了時にロールバックする
//      ← setUp() の deleteAll() を安心して呼べるのはこれが理由
// @AutoConfigureTestDatabase(replace = NONE) … @DataJpaTest は既定で DB をインメモリ DB(H2 など)に
//   差し替える。NONE はそれを止めて設定どおりの MySQL につなぐ指定。H2 は MySQL と方言が違い、
//   ddl-auto: validate によるスキーマ検証が成立しないため、本物の MySQL を使う方針にしている。
// 3 段階(@SpringBootTest / @WebMvcTest / @DataJpaTest)の使い分けと、専用 database app_test を採用した
//   経緯(以前は開発 DB を共用していた)→ docs/notes/java/spring/testing-and-test-database.md
// クラスに public が付いていないのは JUnit 5 の作法(JUnit 4 では必須だった)。
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PostRepositoryTest {

	// @Autowired … このフィールドの中身は Spring が用意したものを入れる(DI = 依存性注入)。
	//   new をどこにも書いていないのに、テストが動くときには実体が入っている。
	//   さらに PostRepository は interface(→ PostRepository.java:18)で、実際に動くクラスは
	//   Spring Data JPA が起動時に自動生成している。@Query の JPQL から SQL を組み立てる処理も自動生成。
	//   本体側(PostController など)はコンストラクタ注入だが、テストクラスのインスタンスを作るのは
	//   Spring ではなく JUnit なので、テストではフィールド注入が公式の書き方
	//   (DI とフィールド注入の詳しい説明 → CategoryControllerTest.java:80-92)。
	// Repository が 3 つ必要なのは、投稿を作るには投稿者(User)とカテゴリー(Category)が必須のため
	//   (→ Post.java:42-48 の optional = false)。
	@Autowired
	PostRepository postRepository;

	@Autowired
	CategoryRepository categoryRepository;

	@Autowired
	UserRepository userRepository;

	// setUp() で作ったデータを各テストメソッドから参照するための置き場。
	// 4 本のテストが同じフィールドを共有しているが、前のテストの値は残らない。JUnit 5 はテストメソッドごとに
	//   テストクラスのインスタンスを作り直すので、フィールドは毎回 null から始まり @BeforeEach が埋め直す。
	User user;
	Category category1;
	Category category2;
	Post post1;
	Post post2;
	Post post3;
	Post post4;
	Post post5;

	// @BeforeEach … 各テストメソッドの直前に毎回実行される。テストごとに同じ前提を作り直すための下準備。
	@BeforeEach
	void setUp() {
		// 前提を固定するため既存の投稿を消す。これが無いと、DB に他の投稿が残っていた場合に
		// 「5 件が新しい順に返る」という検証が壊れる。app_test は普段ほぼ空だが、残骸に依存しないよう明示的に消す
		// (@DataJpaTest がテストをトランザクションで包むので、この削除はテスト終了時にロールバックされる)。
		postRepository.deleteAll();

		// save() の戻り値を受け取っているのは、id が INSERT 後にしか入らないため
		// (保存前の User は id が null で、DB の AUTO_INCREMENT が採番する)。
		user = userRepository.save(new User("repo_test_user", "リポジトリテスト", "repo-test@example.com"));

		// カテゴリーは運営管理のマスタデータで、Flyway の V2__insert_categories.sql が 10 件を投入済み
		//   (id 1 = 雑談 / id 2 = 技術)。だからテスト内で作らず、既存のシードを取ってくる。
		// 1L の L … この数値が int ではなく long であることを示す印。findById が要求する型が Long のため必要
		//   → docs/notes/java/syntax/numeric-literals-and-integer-types.md
		// findById の戻り値 Optional<Category> は「中身が有るか無いか」を型で表す箱で、null の代わりに使う。
		//   orElseThrow() は中身があれば取り出し、無ければ例外を投げる。前提が崩れていたら即落ちてほしいので適切。
		category1 = categoryRepository.findById(1L).orElseThrow();
		category2 = categoryRepository.findById(2L).orElseThrow();

		// id 昇順で 5 件(古い→新しい)。カテゴリーは 1,2,1,2,1 と交互
		// 保存した順に若い id が付くので post1 < post2 < ... < post5 になり、新しい順では post5 が先頭に来る。
		// カテゴリーを交互にするのは意図的。category1 の投稿が post1, post3, post5 と飛び飛びになるので、
		//   絞り込みが効いていない実装なら必ず落ちる。全部 category1 にすると、絞り込みが壊れていても通ってしまう。
		// createdAt は渡していないが、INSERT 直前に @PrePersist の付いた onCreate() が入れる(→ Post.java:72-75)。
		post1 = postRepository.save(new Post(user, category1, "投稿1"));
		post2 = postRepository.save(new Post(user, category2, "投稿2"));
		post3 = postRepository.save(new Post(user, category1, "投稿3"));
		post4 = postRepository.save(new Post(user, category2, "投稿4"));
		post5 = postRepository.save(new Post(user, category1, "投稿5"));
	}

	// @Test … 「このメソッドはテストです」の目印。付け忘れると実行されないまま成功扱いになる。
	// @DisplayName … レポートに表示される名前。メソッド名は英語 camelCase で動詞始まり、内容は @DisplayName に
	//   日本語 1 文で書く(命名規約 → docs/test/README.md)。
	// 以降の 4 本は書き方がほぼ同じなので、共通の説明はこの 1 本目にまとめてある。
	@Test
	@DisplayName("タイムラインは新しい順に返る")
	void returnsNewestFirst() {
		// 引数は (cursor, categoryId, pageable)。null, null は「カーソルなし = 先頭ページ」「絞り込みなし」の意味。
		// PageRequest.of(0, 10) … Pageable(取得件数や並び順の指定)を作る。「0 ページ目・1 ページ 10 件」で、
		//   Spring Data JPA がこれを見て SQL に LIMIT 10 を付ける。
		List<Post> result = postRepository.findTimeline(null, null, PageRequest.of(0, 10));

		// assertThat(result) … result について主張する(AssertJ = 検証用ライブラリ)。
		// .extracting(Post::getId) … 各要素から getId() の結果だけを抜き出し、id のリストに変換する。
		//   Post::getId の :: は「メソッドを呼ばずに値として渡す」記法(JS の p => p.getId() に相当)
		//   → docs/notes/functions-as-values.md
		//   Post 自体を比較しないのは equals を実装していないため。オブジェクト比較は同一インスタンス判定になる。
		// .containsExactly(...) … この値がこの順番でぴったり含まれること。順序に厳しい。
		//   並び順そのものが検証対象なので、順序を無視する contains ではなくこちらを使う。
		// この 1 本が守るのは order by p.id desc(→ PostRepository.java:41)。昇順に変えられると落ちる。
		assertThat(result).extracting(Post::getId)
				.containsExactly(post5.getId(), post4.getId(), post3.getId(), post2.getId(), post1.getId());
	}

	@Test
	@DisplayName("カーソルより新しい投稿は返らない")
	void excludesPostsNewerThanCursor() {
		// カーソル = 前ページ最後の投稿の id。それ「より小さい」id だけが返る(カーソル自身は含まない)
		// この 1 本が守るのは JPQL の p.id < :cursor を <= にしないこと(→ PostRepository.java:39)。
		// <= だと post3 自身が含まれ、ページの境目で同じ投稿が 2 回表示される不具合になる。
		List<Post> result = postRepository.findTimeline(post3.getId(), null, PageRequest.of(0, 10));

		assertThat(result).extracting(Post::getId)
				.containsExactly(post2.getId(), post1.getId());
	}

	@Test
	@DisplayName("カテゴリー絞り込みとカーソルを併用できる")
	void filtersByCategoryWithCursor() {
		// この 1 本が守るのは 2 つの条件が両方効くこと。category1 は post1, post3, post5 →
		// カーソル post5 で post5 自身が外れ → 残る post3, post1 を id 降順。
		// and で結んだ categoryId 条件が抜けると post4, post3, post2, post1 になり落ちる。
		List<Post> result = postRepository.findTimeline(post5.getId(), category1.getId(), PageRequest.of(0, 10));

		assertThat(result).extracting(Post::getId)
				.containsExactly(post3.getId(), post1.getId());
	}

	@Test
	@DisplayName("limit で件数が制限される")
	void limitsResultCount() {
		// この 1 本が守るのは Pageable が SQL の LIMIT に変換されていること。@Query を自分で書いたクエリでは
		// Pageable の引数を足し忘れてもコンパイルが通り、件数制限だけが静かに効かなくなる。
		// あわせて join fetch と LIMIT の併用が安全なことの確認にもなる(user / category は to-one なので
		// SQL 側で LIMIT が効く。to-many だと Hibernate が全件をメモリに読んでから絞る → PostRepository.java:23)。
		List<Post> result = postRepository.findTimeline(null, null, PageRequest.of(0, 2));

		assertThat(result).extracting(Post::getId)
				.containsExactly(post5.getId(), post4.getId());
	}
}
