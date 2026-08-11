package com.example.app.post.dto;

// ↓ import 群。使う制約(バリデーションの部品)を別パッケージから持ち込む宣言。
//   jakarta.validation は Spring 独自ではなく Java エコシステム共通の入力検証の仕組み(Bean Validation)。
import jakarta.validation.constraints.NotBlank; // 文字列が null / 空 / 空白のみでないことを要求する制約
import jakarta.validation.constraints.NotNull; // 値が null でないことを要求する制約(型を問わない)
import jakarta.validation.constraints.Size; // 文字数などの大きさの上限・下限を指定する制約

/**
 * 投稿(Post)を新規作成するとき、フロント(Nuxt)から「受け取る」データの形
 * (DTO=Data Transfer Object。層をまたいでデータを運ぶためだけの入れ物)。
 *
 * <p>システム全体では、次の流れの一番最初にある「入口の器」にあたる。
 *
 * <pre>
 * ブラウザ → JSON → [CreatePostRequest に詰め替え] → PostController → PostService → DB
 *                     (入口の器・入力チェック)         (受付)          (処理の中身)
 * </pre>
 *
 * <p>同じパッケージの PostResponse が「返すときの器」なのに対し、こちらは「受け取るときの器」。
 * 受信専用に器を分けているのは、フロントから受け付ける項目をここだけに絞り込めるようにするため。
 * たとえば投稿者や作成日時をこの器に置いていないのは、それらをフロントに指定させず
 * サーバー側で決める、という意思表示になっている(投稿者はログインしているセッションから決まるので、
 * フロントの言い値で他人名義の投稿は作れない)。
 */
// --- record(レコード)とは ---
// データを持ち運ぶだけのクラスを、ごく短い記述で作れる Java の仕組み(Java 16 で正式導入)。
// 下のカッコ(レコードヘッダー)に持ちたい項目を並べるだけで、それらを受け取るコンストラクタ・
//   値を取り出すメソッド(body() / categoryId())・equals / hashCode / toString を Java が自動生成する。
//   → 仕組みのより詳しい解説は、同じパッケージの PostResponse.java の冒頭を参照。
// record の値は一度作ったら変更できない(イミュータブル)。受け取ったリクエストを途中で
//   書き換えられないので、「フロントが送ってきた入力そのもの」を安心して持ち回せる。
//
// --- アノテーション(@ で始まる目印)とは ---
// コードに「性質・ルール」を後付けする付箋のようなもの。アノテーション自体は何も実行せず、
//   それを読み取る側(ここでは Spring)が意味を解釈して動く。
//
// --- このファイルには見えない裏側で起きること ---
// ① バインディング: ブラウザが送った JSON {"body":"こんにちは","categoryId":3} を、
//    Spring(内部で Jackson というライブラリ)がこの record の形へ自動で詰め替える。
// ② バリデーション: PostController.create の引数に付いた @Valid が引き金となり、
//    下に並ぶ @NotBlank / @Size / @NotNull を照合する(PostController.java:114)。
//    違反があれば PostService には一切届かず、共通の GlobalExceptionHandler.handleValidation が
//    HTTP 400 + フィールド別メッセージに変換して返す(GlobalExceptionHandler.handleValidation)。
//    → 裏を返すと、@Valid を書き忘れると下の制約は「書いてあるだけで発動しない」。ここが落とし穴。
public record CreatePostRequest(
		// 【本文】投稿の中身となる短文。
		// @NotBlank … null / "" / "   "(空白のみ)をすべて弾く、文字列専用の制約。
		//   似た @NotEmpty との違いは、空白だけの文字列も弾いてくれること。だから本文には @NotBlank が適切。
		// @Size(max = 280) … 文字数の上限。280 は投稿の仕様上の上限(→ リポジトリ直下 CONTEXT.md)。
		//   ※この 280 はプロジェクト内の 3 箇所に存在する(意図的な多層防御)。
		//     ① ここの @Size … 入口で弾き、人間向けメッセージを返す(使い勝手の担当。DB に触る前に止める)
		//     ② Post.java:50 の @Column(length = 280) … JPA のマッピング情報。実行時に入力は弾かない
		//     ③ DB の VARCHAR(280) … どの経路から書き込まれても通さない「最後の砦」
		//   上限を変えるときは 3 箇所セットで直す。詳細 → docs/notes/validation-layers.md
		// message = "..." … 違反時にユーザーへ返す文言。ここに書いた文字列がそのまま
		//   レスポンスの fieldErrors.body に入るので、画面にそのまま出せる日本語で書く。
		@NotBlank(message = "本文を入力してください")
		@Size(max = 280, message = "本文は280文字以内で入力してください")
		String body,

		// 【カテゴリーID】どのカテゴリーに投稿するか。投稿はちょうど1つのカテゴリーを必ず持つ(→ CONTEXT.md)。
		// @NotNull … 値が未指定(null)であることを禁じる。
		//   ここで @NotBlank を使えない理由: @NotBlank は文字列専用の制約なので、数値の Long には使えない
		//   (付けると検証実行時に例外になる)。数値の「未入力」は @NotNull で弾く、が正しい使い分け。
		// 型が Long(null になれるオブジェクト型)で long(null になれない基本型)ではないのも、この @NotNull と対応している。
		//   long にすると未指定が 0 に化けてしまい、「選んでいない」状態を検出できなくなる。
		// なお「その id のカテゴリーが実際に存在するか」はここでは判定できない(DB を見ないと分からない)。
		//   存在チェックは PostService.create の責務で、無ければ 404 を返す。この器は「形が正しいか」までを守る。
		@NotNull(message = "カテゴリーを選択してください")
		Long categoryId
) {
	// 本体が空なのは、record が必要なコンストラクタ・取り出しメソッドを自動生成するので書くことがないから。
	// PostResponse が持つ from(...) のような変換メソッドがここに無いのは、詰め替えの向きが逆だから。
	//   返す器は「エンティティ → record」を自分で書くが、受け取る器の「JSON → record」は Spring が担当する。
}
