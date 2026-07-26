package com.example.app.common.exception;

// ↓ ここから import 群。import は「このファイルで使う道具を、別の住所(パッケージ)から持ち込む宣言」。
//   なお下で使っている ResourceNotFoundException / ForbiddenOperationException は import していないが、
//   これは書き忘れではない。この2つはこのファイルと同じパッケージ(com.example.app.common.exception)に
//   あるため名前だけで呼べる(import が要るのは「別パッケージのクラス」を持ち込むときだけ)。
import java.util.LinkedHashMap; // 入れた順番を覚えている Map の実装
import java.util.Map; // 「キー → 値」の対応表を表す型(インターフェース)

import org.springframework.http.HttpStatus; // 404 NOT_FOUND などのステータスコードを名前で扱うための列挙
import org.springframework.web.bind.MethodArgumentNotValidException; // リクエストボディの検証違反で Spring が投げる例外
import org.springframework.web.bind.annotation.ExceptionHandler; // 「この例外が来たらこのメソッド」を結びつける
import org.springframework.web.bind.annotation.ResponseStatus; // 返す HTTP ステータスコードを指定する
import org.springframework.web.bind.annotation.RestControllerAdvice; // 全 Controller 共通の追加設定だと宣言する

import com.example.app.common.dto.ErrorResponse; // エラー時に「返す」データの入れ物(DTO)

import jakarta.validation.ConstraintViolationException; // クエリパラメータの検証違反で投げられる例外

/**
 * 例外を HTTP レスポンスへ変換する共通ハンドラ。
 * 各 Controller に try-catch を書かず、ここに集約する。
 *
 * <p>アプリのどこかで投げられた例外(エラー)を受け取り、クライアント(Nuxt)へ返す
 * HTTP ステータスコードと JSON へ「翻訳」するのがこのクラスの仕事。
 *
 * <pre>
 * ブラウザ → PostController → PostService → PostRepository → DB
 *                                  ↓ 例外を投げる
 *                      [GlobalExceptionHandler]  ← このクラス
 *                                  ↓ ステータス + JSON に変換
 *                               ブラウザへ
 * </pre>
 *
 * <p>1箇所に集約している理由は2つ。1つは、同じ変換コードが各 Controller にコピペで散らばるのを防ぐため。
 * もう1つは、エラーレスポンスの形をアプリ全体で1つ(ErrorResponse)に統一するため。
 * おかげで PostService は「投稿が見つかりません」と例外を投げるだけでよく、
 * それが 404 になるのか 403 になるのかを知らずに済む(責務の分離)。
 *
 * <p>返す JSON の形と「例外 → ステータス」の対応表は docs/api/README.md にも記載がある。
 */
// --- アノテーション(@ で始まる目印)とは ---
// コードに「性質・ルール」を後付けする付箋のようなもの。アノテーション自体は何も実行せず、
//   それを読み取る側(ここでは Spring)が意味を解釈して動く。
//   → より詳しい説明は CreatePostRequest.java の冒頭を参照。
//
// @RestControllerAdvice … 「このクラスには、全 Controller に共通で効く追加設定が書いてあります」という宣言。
//   Advice(助言)は「本来の処理の外側から口を出す」という意味合いで、ここでは例外処理を担当する。
//   先頭の Rest は、メソッドの戻り値を Spring が自動で JSON に変換して返すことを意味する
//   (これが無いと、戻り値が「表示するテンプレートの名前」として解釈されてしまう)。
//
// --- このファイルには見えない裏側で起きること ---
// ① アプリ起動時、Spring がクラスを走査して @RestControllerAdvice の付いたこのクラスを発見する。
// ② このクラスの実体を Spring 自身が1つ作り、管理下に置く(この管理されるオブジェクトを Bean と呼ぶ)。
//    → コード上どこにも new GlobalExceptionHandler() と書いていないのに実体ができるのはこのため。
// ③ 続けて @ExceptionHandler の付いたメソッドを読み取り、「例外の型 → 呼ぶメソッド」の対応表を作る。
// ④ リクエスト処理中に例外が投げられると、Spring が Controller の外側でそれを捕まえ、
//    対応表を引いて該当のメソッドを呼ぶ。この「実行時にクラスやメソッドを名前で見つけて呼び出す仕組み」を
//    リフレクションという。
//
// --- ここが分かりにくいポイント ---
// 下の handleNotFound などを VS Code で「参照へ移動 / すべての参照を検索」しても 0 件になる。
//   呼んでいるのは自分たちのコードではなく Spring で、しかも上記④のリフレクション経由。
//   つまりソースコードのどこにも handleNotFound(...) と書かれた行が存在しないため、検索に引っかからない。
//   これは異常ではなく、アノテーションで結びつけるコードでは普通のこと。
//   「参照 0 件だから使われていない、消してよい」と判断してはいけない。
@RestControllerAdvice
public class GlobalExceptionHandler {

	// 【404 Not Found】対象のリソースが存在しないとき。
	//
	// @ExceptionHandler(ResourceNotFoundException.class) … 「ResourceNotFoundException が飛んできたら
	//   このメソッドで処理する」という指定。
	//   末尾の .class は「クラスそのものを値として渡す」書き方(クラスリテラル)。
	//   new ResourceNotFoundException(...) ではない点に注目。ここで指定したいのは
	//   「まだ起きていない例外の“種類”」なので、実体(インスタンス)では表現できない。
	// @ResponseStatus(HttpStatus.NOT_FOUND) … このメソッドが処理したときに返すステータスを 404 に固定する。
	//   HttpStatus は「決まった選択肢の一覧」を型にしたもの(列挙型 = enum)。404 と数値で直接書かず
	//   NOT_FOUND と名前で書くことで、読んだ瞬間に意味が分かる。
	// メソッド名 handleNotFound は Spring から見れば何でもよい(対応付けはアノテーションが行うため)。
	//   人間が読むための名前。
	// 引数 e には、実際に投げられた例外の実体が Spring から渡される。
	//
	// 戻り値がそのままレスポンスの中身になる:
	//   ErrorResponse.of(...) は ErrorResponse を作るための入り口(→ ErrorResponse.java)。
	//     fieldErrors を null で埋める定型作業を隠しているので、new ErrorResponse(msg, null) と
	//     書くより意図が読み取りやすい。
	//   ErrorResponse は record(データを運ぶだけのクラスを短く書く仕組み。詳しい解説は PostResponse.java の冒頭)。
	//   e.getMessage() は例外に持たせたメッセージ。ResourceNotFoundException は受け取った文字列を
	//     super(message) で親の RuntimeException に預けているだけなので、この取り出しメソッドは親から受け継いだもの。
	//   返した ErrorResponse は Jackson(オブジェクト ↔ JSON 変換ライブラリ)が JSON に直す。
	//     → {"message":"投稿が見つかりません: id=999","fieldErrors":null}
	// この例外を投げている側は PostService.getPost / PostService.delete / PostService.create。
	//   DB に該当する行が無いときに投げている。
	@ExceptionHandler(ResourceNotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public ErrorResponse handleNotFound(ResourceNotFoundException e) {
		return ErrorResponse.of(e.getMessage());
	}

	// 【403 Forbidden】許可されていない操作をしようとしたとき。
	// 構造は上の handleNotFound とまったく同じなので、違うところだけ挙げる。
	//   - 受け持つ例外が ForbiddenOperationException
	//   - 返すステータスが 403
	// 403 と 401 の使い分け: 403 Forbidden は「あなたが誰かは分かっているが、その操作は許可されていない」、
	//   401 Unauthorized は「そもそも誰か分からない(ログインしていない)」。ここは前者。
	// 投げているのは PostService.delete の1箇所だけ。投稿を削除できるのは投稿者本人のみで
	//   (→ CONTEXT.md「投稿」)、他人の投稿を消そうとするとこの例外になる。
	@ExceptionHandler(ForbiddenOperationException.class)
	@ResponseStatus(HttpStatus.FORBIDDEN)
	public ErrorResponse handleForbidden(ForbiddenOperationException e) {
		return ErrorResponse.of(e.getMessage());
	}

	/** リクエストボディ(@Valid)のバリデーションエラー */
	// 【400 Bad Request】リクエストボディ(JSON)の中身が制約に違反していたとき。
	//
	// MethodArgumentNotValidException は Spring が投げる例外。PostController.create の引数に付いた
	//   @Valid が引き金で、CreatePostRequest に書かれた制約(@NotBlank / @Size / @NotNull)に違反すると、
	//   Controller のメソッド本体に入る前に投げられる。つまり不正な入力は PostService に一切届かない。
	// 上の2つと違い、このメソッドだけ「どの項目が」「なぜ」駄目だったかを詰め直す処理を持つ。
	//   利用者が画面上のどの入力欄を直せばよいか分かるようにするため。
	@ExceptionHandler(MethodArgumentNotValidException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ErrorResponse handleValidation(MethodArgumentNotValidException e) {
		// Map<String, String> は「キー(文字列) → 値(文字列)」の対応表。ここには「項目名 → メッセージ」を入れる。
		// 左辺の型が Map で右辺が LinkedHashMap なのは、Map が「仕様(インターフェース)」、
		//   LinkedHashMap が「実際の入れ物(実装)」だから。よく使う HashMap との違いは、
		//   LinkedHashMap が入れた順番を覚えていること。並びが毎回変わる HashMap より
		//   レスポンスが安定するので、こちらを選んでいる。
		//   ※ ただし「フォームの項目順どおりに並ぶ」ことまでは保証されない。LinkedHashMap が守るのは
		//     あくまで「入れた順」で、その手前の getFieldErrors() が返す順序(= 制約が評価される順序)は
		//     フィールドの宣言順とは限らないため。実測でも body / categoryId の並びは毎回同じにならない。
		//     表示順を確定させたいならフロント側で並べ替えること。
		// new LinkedHashMap<>() の <> は「左辺と同じ型なので省略」の意味(ダイヤモンド演算子)。
		// なぜここが record ではなく Map なのか(キーが実行時まで決まらないから)、TS の Record<string,string> /
		//   PHP の連想配列との対応 → docs/notes/java/syntax/map-vs-record.md
		Map<String, String> fieldErrors = new LinkedHashMap<>();
		// 下の1文は3段階に分けて読む:
		//   ① e.getBindingResult().getFieldErrors() … 例外が抱えている検証結果から、
		//      項目単位のエラーの一覧を取り出す。
		//   ② .forEach(...) … 一覧の要素を1つずつ処理する。for 文の代わり。
		//   ③ error -> ... … ラムダ式(その場で書く、名前のない小さな関数)。
		//      「error を受け取ってこの処理をする」という手順そのものを forEach に渡している。
		//      for 文で書くと次と同じ意味になる。
		//        for (FieldError error : e.getBindingResult().getFieldErrors()) { ... }
		// putIfAbsent は「そのキーがまだ無いときだけ入れる」メソッド。put との違いが重要で、
		//   1つの項目に複数の制約(例: @NotBlank と @Size)が付いていて両方違反した場合、
		//   put だと後から来たメッセージで上書きされるが、putIfAbsent なら最初の1件だけが残る。
		//   → 「1項目につきメッセージは1つ」を保証している。
		// error.getField() は項目名(例: "body")、error.getDefaultMessage() は制約に書いた message
		//   (例: "本文を入力してください" → CreatePostRequest)。
		e.getBindingResult().getFieldErrors()
				.forEach(error -> fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));
		// ここだけ ErrorResponse.of(...) ではなく new を使っている。of は fieldErrors を null で埋める
		//   ための入り口なので、中身を詰めたいこの場面では使えない。
		//   → {"message":"入力内容に誤りがあります","fieldErrors":{"body":"本文を入力してください"}}
		return new ErrorResponse("入力内容に誤りがあります", fieldErrors);
	}

	/** クエリパラメータ(@Min / @Max など)のバリデーションエラー */
	// 【400 Bad Request】URL の ? の後ろ(クエリパラメータ)が制約に違反していたとき。
	//
	// 同じ 400 なのにハンドラが2つ必要な理由: 検証が発動する仕組みが違うため、投げられる例外の型が別だから。
	//   - ボディ           … @Valid @RequestBody を Spring MVC がリクエスト変換時に検証
	//                        → MethodArgumentNotValidException(上の handleValidation が担当)
	//   - クエリパラメータ … クラスに付けた @Validated を起点に、Spring がメソッド呼び出しへ割り込んで検証
	//                        → ConstraintViolationException(このメソッドが担当)
	//   この2つは継承関係が無いので、片方のハンドラでもう片方を受けることはできない。
	//   実例は PostController.timeline の limit に付いた @Min(1) @Max(50)。
	//   2層のバリデーションの全体像は docs/notes/validation-layers.md を参照。
	// こちらは項目ごとに詰め直さず、例外が持つメッセージをそのまま文言に連結して返している。
	@ExceptionHandler(ConstraintViolationException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ErrorResponse handleConstraintViolation(ConstraintViolationException e) {
		return ErrorResponse.of("リクエストパラメータが不正です: " + e.getMessage());
	}
}
