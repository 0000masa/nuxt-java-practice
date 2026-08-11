package com.example.app.post;

// ↓ ここから import 群。import は「このファイルで使う道具を、別の住所(パッケージ)から持ち込む宣言」。
//   使う前に「どこの誰か」をはっきりさせる Java のルール。各アノテーションが具体的に何をするかは、
//   実際にそれが付くクラス宣言・メソッドの箇所で解説する。
import org.springframework.http.HttpStatus; // 201 CREATED などのステータスコード定数の置き場
import org.springframework.security.core.annotation.AuthenticationPrincipal; // ログイン中のユーザーを引数で受け取る指示
import org.springframework.validation.annotation.Validated; // クラスに付けてバリデーションを有効化する目印
import org.springframework.web.bind.annotation.DeleteMapping; // HTTP DELETE とメソッドを結ぶ
import org.springframework.web.bind.annotation.GetMapping; // HTTP GET とメソッドを結ぶ
import org.springframework.web.bind.annotation.PathVariable; // URL パスの一部(/{id} の id)を受け取る
import org.springframework.web.bind.annotation.PostMapping; // HTTP POST とメソッドを結ぶ
import org.springframework.web.bind.annotation.RequestBody; // リクエスト本文(JSON)を受け取る
import org.springframework.web.bind.annotation.RequestMapping; // クラス全体の URL の土台を決める
import org.springframework.web.bind.annotation.RequestParam; // URL の ? の後ろ(クエリパラメータ)を受け取る
import org.springframework.web.bind.annotation.ResponseStatus; // 返す HTTP ステータスコードを指定する
import org.springframework.web.bind.annotation.RestController; // このクラスが REST API コントローラーだと宣言する

import com.example.app.auth.AppUserDetails; // ログイン中のユーザーを表す principal
import com.example.app.post.dto.CreatePostRequest; // 投稿作成時に「受け取る」データの入れ物(DTO)
import com.example.app.post.dto.PostResponse; // 投稿1件を「返す」ときのデータの入れ物(DTO)
import com.example.app.post.dto.TimelineResponse; // タイムライン一覧を「返す」ときのデータの入れ物(DTO)

import jakarta.validation.Valid; // 受け取ったデータの中身をまとめて検証する指示
import jakarta.validation.constraints.Max; // 数値の最大値の制約
import jakarta.validation.constraints.Min; // 数値の最小値の制約

/**
 * 投稿(Post)に関する API リクエストの入口(コントローラー)。
 *
 * <p>コントローラーとは、ブラウザ(フロントの Nuxt)から届く HTTP リクエストを最初に受け取り、
 * 担当の処理へ振り分ける「受付係」。実際の処理はこのクラスには書かず、すべて PostService に委譲する。
 *
 * <pre>
 * ブラウザ → [PostController] → PostService → PostRepository → DB
 *            (受付・入口)         (処理の中身)   (DB とのやりとり)
 * </pre>
 *
 * <p>このクラス自身は「投稿を作る/消す」といった具体的な仕事を持たず、
 * 「受け取る → Service に渡す → 結果を返す」だけの薄い作りにしているのが設計上のポイント。
 */
// @RestController … このクラスは REST API のコントローラー、という宣言。
//   これが付くと、メソッドの戻り値(PostResponse など)を Spring が自動で JSON に変換して返してくれる。
// @RequestMapping("/api/posts") … このクラスが担当する URL の「土台」。
//   以降の各メソッドの URL は、すべてこの /api/posts を先頭に付けた形になる。
// @Validated … クラスに付けてバリデーションを有効化する目印。
//   特に timeline() の引数に直接付けた @Min/@Max を効かせるために必要。
@RestController
@RequestMapping("/api/posts")
@Validated
public class PostController {

	// ↓ DI(依存性注入)の置き場。このクラスは処理を任せる相手 PostService を1つ持つ。
	//   これは「PostService 型の値が入る postService というフィールド」の宣言(PHP や JS でいうプロパティに相当)。
	//   フィールド = オブジェクトが生きている間ずっと保持される箱。
	//   この行の時点では箱を用意しただけで中身(実体)はまだ空。箱を埋めるのは下のコンストラクタ。
	//   final = 一度セットしたら差し替えない。private = このクラスの中だけで使う。
	// import 不要の理由: PostService は PostController と同じパッケージ(com.example.app.post)にあるため、
	//   名前だけで呼べる(import は「別パッケージのクラス」を持ち込むときだけ必要)。
	//   ファイル冒頭で import している DTO は post.dto という別パッケージなので import が要る。
	private final PostService postService;

	// コンストラクタ = クラスから実体を作るとき、最初に1回だけ呼ばれる初期化メソッド。
	// Java は「クラス名と完全に同じ名前」かつ「戻り値の型を書かない(void すら書かない)」メソッドを
	//   コンストラクタとみなす。うっかり public void PostController(...) と void を付けると、
	//   名前が同じでもコンストラクタではない「ただのメソッド」になり、new のとき呼ばれない(落とし穴)。
	//   ※PHP の __construct() のような固定名ではなく、Java は「名前 = クラス名」で見分ける方式。
	// カッコの中の「PostService postService」は引数(パラメータ)の宣言。上のフィールドとは別物で、
	//   コンストラクタ実行中だけ生きる一時的な変数。外(Spring)から渡された実体をここで受け取る。
	// 注目: コード上どこにも new PostService() と書いていないのに、postService には実体が入る。
	//   Spring が起動時に PostService の実体を用意し、この引数へ「注入(inject)」してくれる。これが DI。
	//   おかげでコントローラーは「Service をどう作るか」を気にせず、渡されたものを使うことに集中できる。
	public PostController(PostService postService) {
		// 右辺 postService = 引数(一時的に受け取ったもの)、左辺 this.postService = 上のフィールド(長生きの箱)。
		// this. は「このオブジェクト自身の」の意味で、名前が同じ引数とフィールドを区別するために付ける。
		// これを書かず postService = postService とすると両方とも引数扱いになり、フィールドは空のままになる。
		this.postService = postService;
	}

	// 【一覧取得】GET /api/posts … タイムライン(投稿の新しい順の一覧)を返す。
	// @GetMapping(URL 指定なし) … クラスの土台 /api/posts に何も足さないので GET /api/posts に対応。
	// 引数はすべて @RequestParam = URL の ? の後ろ(例: ?cursor=10&limit=20)から値を受け取る。
	//   - cursor    : どこから先を読むかの目印。required=false なので無指定なら null になる
	//   - categoryId: カテゴリーでの絞り込み用。同じく任意
	//   - limit     : 取得件数。defaultValue="20" で無指定なら 20。@Min(1)@Max(50) により
	//                 1〜50 の範囲外(例: 100)は Service に届く前に Spring が弾く
	// 型の使い分け: cursor/categoryId は「無指定=null」を許したいので、null になれる Long(オブジェクト型)。
	//   limit は必ず値が決まるので、null になれない int(基本型)を使う。
	// 本体は、受け取った3つをそのまま postService.getTimeline に渡し、戻り値をそのまま返すだけ。
	@GetMapping
	public TimelineResponse timeline(
			@RequestParam(required = false) Long cursor,
			@RequestParam(required = false) Long categoryId,
			@RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
		return postService.getTimeline(cursor, categoryId, limit);
	}

	// 【1件取得】GET /api/posts/{id} … URL に含まれる id の投稿を1件返す。
	// @GetMapping("/{id}") … URL は /api/posts/{id}。{id} は「変わる値」のプレースホルダ。
	//   例: GET /api/posts/5 なら id に 5 が入る。
	// @PathVariable Long id … URL パスそのものの一部({id})を取り出して引数に入れる指示。
	//   timeline() の @RequestParam(? の後ろ)との違いに注目。こちらは「パスの一部」を取り出す。
	@GetMapping("/{id}")
	public PostResponse get(@PathVariable Long id) {
		return postService.getPost(id);
	}

	// 【作成】POST /api/posts … 新しい投稿を作る。POST は「新規作成」を表す HTTP メソッド。
	// @ResponseStatus(HttpStatus.CREATED) … 成功時に返すステータスを 201(CREATED=作成された)に指定。
	//   指定しない既定は 200 OK だが、新規作成には 201 を返すのが REST の作法。
	// @RequestBody CreatePostRequest request … リクエストの本文(JSON)を CreatePostRequest に変換して受け取る。
	//   ブラウザが送る {"body":.., "categoryId":..} が自動で Java オブジェクトに詰め替えられる(JSON→オブジェクト)。
	// @Valid … request の中身を、CreatePostRequest に書かれた制約に従ってチェック。違反なら Service に渡る前にエラー。
	// @AuthenticationPrincipal AppUserDetails principal … ログイン中のユーザーを Spring Security から受け取る。
	//   このエンドポイントは SecurityConfig で認証必須にしてあるので、ここに来た時点で principal は必ず居る
	//   (未ログインなら Controller に入る前にフィルタが 401 を返す)。
	//   公開エンドポイントで同じ引数を受けると、未ログイン時は null になる点に注意。
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public PostResponse create(@Valid @RequestBody CreatePostRequest request,
			@AuthenticationPrincipal AppUserDetails principal) {
		return postService.create(request, principal.getUserId());
	}

	// 【削除】DELETE /api/posts/{id} … URL の id の投稿を削除する。DELETE は「削除」を表す HTTP メソッド。
	// @ResponseStatus(HttpStatus.NO_CONTENT) … 成功時のステータスを 204(NO_CONTENT=返す中身なし)に指定。
	//   削除は返すデータがないため 204 が定番。戻り値の型が void(何も返さない)なのもこれと対応している。
	// 認可(=自分の投稿か?)のチェックはここではなく PostService.delete 側にある。
	//   他人の投稿を消そうとすると Service が例外(ForbiddenOperationException)を投げる。
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id, @AuthenticationPrincipal AppUserDetails principal) {
		postService.delete(id, principal.getUserId());
	}
}
