package com.example.app.category;

// テストクラスは「テスト対象と同じパッケージ名」に置くのが Java の慣習(ファイルの置き場所は src/test/java 側)。
// このおかげで CategoryController と CategoryService は import なしで名前だけで呼べる。

// ↓ ここから import 群。import は「このファイルで使う道具を、別の住所(パッケージ)から持ち込む宣言」。
//   下の 4 行の import static は特殊で、クラスではなく「クラスの中の static メソッドそのもの」を持ち込む。
//   ふつうの import なら MockMvcRequestBuilders.get(...) と書く必要があるが、static import すると
//   get(...) とクラス名を省略して呼べる。これは横着ではなく、テストコードを英語の文章のように
//   読ませるための意図的な作法(perform(get(...)).andExpect(status().isOk()) が
//   「GET を実行して、ステータスが OK であることを期待する」と読める)。Spring の公式ドキュメントもこの形。
//   ※static メソッド = インスタンス(実体)を作らず、クラス名だけで呼べるメソッド。Math.max(1, 2) のあれ。
import static org.mockito.Mockito.when; // モックに「呼ばれたらコレを返せ」と台本を渡す
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get; // GET リクエストを組み立てる
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath; // レスポンス JSON の中身を検証する
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status; // レスポンスのステータスコードを検証する

import java.util.List; // テストデータの一覧(カテゴリーのリスト)を作るのに使う Java 標準の型

import org.junit.jupiter.api.DisplayName; // テストの表示名を日本語で付けるための目印
import org.junit.jupiter.api.Test; // このメソッドはテストだ、と JUnit に伝える目印
import org.springframework.beans.factory.annotation.Autowired; // Spring が用意した部品を受け取る指示
// Spring Boot 4 でテストアノテーションのパッケージが技術別モジュールに移動している
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest; // Web 層だけを起動する軽量テストの宣言
import org.springframework.test.context.bean.override.mockito.MockitoBean; // 本物の Bean をモックに差し替える指示
import org.springframework.test.web.servlet.MockMvc; // 実サーバーなしで HTTP リクエストを疑似送信する道具

import com.example.app.category.dto.CategoryResponse; // カテゴリー1件を返すときのデータ(DTO)

/**
 * カテゴリー一覧 API(GET /api/categories)の Controller 層だけを検証するテスト。
 * Service はモックにし、Web 層(URL のひも付け・JSON 変換)だけを起動する。
 *
 * <p>バックエンドは下のようなバケツリレーになっているが、このテストが見張るのは一番左の 1 区間だけ。
 * Service より右はニセモノ(モック)に差し替えるので、DB には一切触れない。
 *
 * <pre>
 * ブラウザ → [CategoryController] → CategoryService → CategoryRepository → DB
 *             ↑ここだけ本物         ↑モックに差し替え   ↑起動すらしない      ↑使わない
 * </pre>
 *
 * <p>車の検査に例えると、車体ごと走らせる走行テストではなく、ハンドルだけを取り外して
 * 「左に回したら左を向くか」を確かめている状態。「DB に本当にカテゴリーが入っているか」は関心事ではなく、
 * 「Service が 2 件返してきたとき、Controller はそれを 200 と JSON 配列に正しく変換できるか」だけを見る。
 * DB を使わないぶん、実行は一瞬で終わる。
 *
 * <p>2 本のテストはどちらも「準備 → 実行 → 検証」の 3 拍子。
 * これは Arrange-Act-Assert と呼ばれる、テストの世界の共通フォーマット。
 * <ol>
 * <li>準備 … ニセモノの CategoryService に「getCategories() を呼ばれたらコレを返せ」と台本を渡す</li>
 * <li>実行 … 本物のサーバーを起動せずに、GET /api/categories という擬似リクエストを Controller に投げる</li>
 * <li>検証 … 返ってきたステータスコードと JSON の中身が期待どおりかを確かめる</li>
 * </ol>
 *
 * <p><b>このテストの守備範囲</b> — 1 本目は「順序」を検証しているが、カテゴリーを display_order の昇順に
 * 並べる仕事そのものは CategoryRepository.findAllByOrderByDisplayOrderAsc() が担っており、そこは対象外。
 * ここで保証できるのは「Controller が Service から受け取った並びを崩さないこと」まで。
 * 仮に Repository のメソッドを findAll() に変えてしまっても、このテストは緑のまま通る。
 *
 * <p>テストの種類(SpringBootTest / WebMvcTest / DataJpaTest)の使い分け、テスト専用 database app_test の
 * 仕組み、実行コマンドは → docs/test/README.md、docs/notes/java/spring/testing-and-test-database.md
 */
// @WebMvcTest … Web 層(HTTP の入口)だけを起動するテスト、という宣言。
//   アノテーション(@ で始まる目印)自体は何も実行しないが、これを見つけた Spring が裏で次のことをする。
//   「荷物に貼る取扱注意シール」に近く、シール自体は何もしないが、見た運送業者が扱いを変える。
//     1. Spring を起動する。ただし全部ではなく、@RestController・JSON 変換器・例外ハンドラなど Web 層の部品だけ
//     2. @Service や @Repository、DB 接続(DataSource)は読み込まない。だから MySQL が止まっていても通る
//     3. MockMvc(HTTP リクエストのシミュレーター)を用意し、@Autowired で受け取れるようにする
// 括弧の中の CategoryController.class は「この Controller 1 個だけを対象にする」という絞り込み。
//   省略すると全 Controller が読み込まれ、PostController が必要とする PostService まで用意しろと
//   言われて起動に失敗する。
//   .class は「クラスそのものを値として渡す」記法(クラスリテラル → docs/notes/java/syntax/class-literal.md)。
//   実体ではなく設計図のまま手渡しているイメージ。
// クラスに public が付いていないのは JUnit 5 の作法。JUnit 4 では必須だったが、5 からは不要になった。
@WebMvcTest(CategoryController.class)
class CategoryControllerTest {

	// MockMvc … Tomcat を起動して TCP 通信することなく、Spring の内部だけで
	//   「HTTP リクエストが来た」ことにして Controller を呼び出せるテスト用の道具。
	// @Autowired … 直下の mockMvc フィールドについて「中身は Spring が用意したものを入れておいて」と頼む目印。
	//   フィールド = クラスの直下(メソッドの外)に書く変数。そのオブジェクトが生きている間ずっと中身を保持する箱で、
	//     他の言語でいうプロパティ・メンバ変数にあたる。メソッド内で宣言する変数(ローカル変数)は
	//     メソッドを抜けると消えるが、フィールドは残る。「MockMvc mockMvc;」は MockMvc が型、mockMvc が名前。
	//   注目すべきは、下の行が箱を用意しただけで中身を入れていない点。new をどこにも書いていないのに、
	//     テストが動くときには実体が入っている。これが DI(依存性注入)で、@WebMvcTest が組み立てた
	//     「Controller 一式が載った小さなサーバー」に接続済みの MockMvc が差し込まれる。
	//     レンタカーを借りるとき、自分で車を組み立てず、鍵を受け取るだけで済むのと同じ。
	//   @Autowired を消すと誰も箱を埋めないので mockMvc は null のままになり、
	//     mockMvc.perform(...) を呼んだ瞬間に NullPointerException で落ちる。
	// 本体側(CategoryController.java:18)がコンストラクタ注入なのに、ここがフィールド注入なのはなぜか:
	//   テストクラスのインスタンスを作るのは Spring ではなく JUnit なので、コンストラクタ注入が使いにくい。
	//   テストではフィールドに @Autowired を付けるのが公式の書き方。使い分けであって、矛盾ではない。
	@Autowired
	MockMvc mockMvc;

	// @MockitoBean … 本物の CategoryService の代わりに、空っぽの替え玉(モック)を Spring に登録する指示。
	//   CategoryController は「CategoryService をください」としか言っていないので、渡されたのが偽物だと
	//   気づかずに使う。
	// モック = 本物のふりをする空っぽの替え玉。映画のスタントダブルのようなもの。全メソッドが
	//   「何もせず null / 空リスト / 0 を返す」状態で生まれ、テスト側が when(...).thenReturn(...) で
	//   「このメソッドが呼ばれたらコレを返せ」と台本を書き込める。
	// モックにする理由は 2 つ。
	//   - 速い・壊れにくい … DB も Repository も要らないので、DB の中身でテスト結果が揺れない
	//   - 状況を自由に作れる … 「カテゴリーが 0 件」を、DB を空にしなくても一瞬で再現できる(下の 2 本目がこれ)
	// これも Boot 3.4 以降の新しい名前。古い記事にある @MockBean は非推奨になっている。
	@MockitoBean
	CategoryService categoryService;

	// 【一覧取得】Service が 2 件返したとき、200 と「その並び順どおりの JSON 配列」になるかを見る。
	// @Test … 「このメソッドはテストです」の目印。これが付いたメソッドを JUnit が自動で探し出して実行する。
	//   付け忘れると、そのテストは静かに実行されないまま「成功」扱いになる(よくある事故)。
	// @DisplayName … レポートに表示される名前。日本語で書ける。
	//   メソッド名は英語 camelCase で動詞始まり、内容は @DisplayName に日本語 1 文
	//   (returnsCategoriesInServiceOrder もこの規約どおり) → docs/test/README.md の「命名規約」
	// throws Exception … 「このメソッドの中でエラーが起きたら、自分では処理せず呼び出し元に投げます」という宣言。
	//   mockMvc.perform(...) が例外を投げうるので必要。テストでは例外を握りつぶさず投げっぱなしにするのが正解
	//   (例外が出た = テスト失敗、として扱いたいため)。
	@Test
	@DisplayName("カテゴリー一覧は 200 と Service が返した順序で返す")
	void returnsCategoriesInServiceOrder() throws Exception {
		// 【準備】モックへの台本渡し。when(A).thenReturn(B) は「A が呼ばれたら B を返せ」と、英語の語順のまま読む。
		// List.of(...) … Java 9 以降の書き方で、中身を変更できないリストをその場で作る。
		//   new ArrayList<>() して add を並べるより短く、テストデータ作りに向いている。
		// 1L の L … この数値が int ではなく long(より大きい整数型)であることを示す印。CategoryResponse の
		//   id が Long 型で、L を外して 1 と書くとコンパイルエラーになる(1 は int リテラルであり、
		//   int → Long は「int → long の拡大変換」+「long → Long のボックス化」の 2 段になる。Java は
		//   引数渡しでこの 2 段を自動適用しない)。1L なら long → Long の 1 段で済むので通る。
		//   そもそも id が Long なのは DB のカラムが BIGINT だから。詳しくは
		//   → docs/notes/java/syntax/numeric-literals-and-integer-types.md
		// CategoryResponse は record(→ CategoryResponse.java:5)。record は「データを入れるだけの箱」を
		//   1 行で定義する Java 16 以降の記法で、コンストラクタ・id()/name() の取得メソッド・equals・toString が
		//   自動生成される。中身を後から変更できない(イミュータブル)ので、API のレスポンス用データに向く。
		when(categoryService.getCategories()).thenReturn(List.of(
				new CategoryResponse(1L, "お知らせ"),
				new CategoryResponse(2L, "日常")));

		// 【実行】と【検証】。. でつないでいく書き方をメソッドチェーンと呼ぶ。各メソッドが「自分自身」を返すので
		//   そのまま次の呼び出しを繋げられる。改行して縦に並べると、上から順に読める仕様書のようになる。
		// perform(get(...)) … Tomcat も TCP 通信も使わず、Spring の内部だけで「GET リクエストが来た」ことにして
		//   Controller を呼ぶ。だからミリ秒で終わる。
		// status().isOk() … ステータスコードが 200 であること。CategoryController.java:22 の @GetMapping は
		//   何も指定していないので既定の 200 が返る。
		// jsonPath("...") … JSON の中の特定の場所を指す「住所表記」(JSONPath)。ファイルパスの JSON 版。
		//   $ = ルート(JSON 全体) / $[0] = 配列の 0 番目 / $[0].name = 0 番目の name / $.length() = 配列の長さ
		// ここで検証しているレスポンス JSON は、実際にはこの形になっている。
		//   [ {"id": 1, "name": "お知らせ"}, {"id": 2, "name": "日常"} ]
		//   CategoryController.list() は List<CategoryResponse> を返すだけで、JSON への変換コードはどこにもない。
		//   これは @RestController が裏で Jackson というライブラリを呼び、record のプロパティ名をキーにして
		//   JSON へ自動変換しているため。このテストは、その自動変換の結果までを保証している。
		// 最後の 3 行が順序の検証。0 番目が「お知らせ」、1 番目が「日常」= 台本に渡した並びのまま出ることを確かめる。
		//   JSON の配列は順序が意味を持つので、これは有効な検証(ただし守備範囲は冒頭の Javadoc のとおり)。
		mockMvc.perform(get("/api/categories"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].id").value(1))
				.andExpect(jsonPath("$[0].name").value("お知らせ"))
				.andExpect(jsonPath("$[1].name").value("日常"));
	}

	// 【0 件のケース】台本を「空を返せ」に変えただけで、あとは 1 本目と同じ形。
	// List.of() は引数なしで呼ぶと空のリストになる。
	// このテストの価値 … 該当データが無いときに 404 でも null でもなく、[](空配列)と 200 を返すのが REST の作法。
	//   フロント側は data.map(...) のように書くので、null が返ってくると画面がエラーで落ちる。
	//   つまりこの 1 本が、フロントを守る契約書になっている。
	// なお 2 本のテストはそれぞれまっさらな状態から始まる。JUnit 5 はテストメソッドごとにテストクラスの
	//   インスタンスを作り直し、モックも毎回リセットされるので、1 本目の台本が 2 本目に漏れることはない。
	@Test
	@DisplayName("カテゴリーが 0 件でも 200 と空配列を返す")
	void returnsEmptyArrayWhenNoCategories() throws Exception {
		when(categoryService.getCategories()).thenReturn(List.of());

		mockMvc.perform(get("/api/categories"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}
}
