package com.example.app.post.dto;

import java.time.LocalDateTime; // 「日付＋時刻」を表す Java 標準の型(作成日時に使う)

import com.example.app.post.Post; // 詰め替え元になる Post エンティティ(別パッケージ post にあるので import が要る)

/**
 * 投稿1件を API のレスポンスとして返すときの「データの形」(DTO=データを運ぶためだけの入れ物)。
 *
 * <p>システム全体では、次の流れの一番最後にある「出口の器」にあたる。
 * <pre>
 * DB → Post(エンティティ) → [PostResponse に詰め替え] → JSON に変換 → ブラウザ
 * </pre>
 *
 * <p>DB 直結の Post をそのまま返さず、専用の器に詰め替えるのは「フロントに見せたい項目だけ」を
 * 明示的に制御するため。Post が「DB 側の姿」、PostResponse が「API 側の姿」と役割を分けている。
 */
// --- record(レコード)とは ---
// データを持ち運ぶだけのクラスを、ごく短い記述で作れる Java の仕組み(Java 16 で正式導入)。
// 下のカッコの中に「持ちたい項目(= コンポーネントと呼ぶ)」を並べるだけで、Java が裏側で
//   ・各項目を受け取るコンストラクタ(new PostResponse(id, body, ...) で作れる)
//   ・各項目を取り出すメソッド(id() / body() など。普通のクラスの getId() と違い get が付かず項目名そのまま)
//   ・equals / hashCode / toString(中身の比較や文字列化)
// を自動生成してくれる。「中身は入れ物、ロジックは持たない」という DTO の性格にぴったりの道具。
//
// クラス名のあとに関数のような引数リストが付くのは、普通の class では珍しく見えるが、
//   record ではこれが正式な文法そのもの。このカッコを「レコードヘッダー」と呼び、
//   同時に「標準コンストラクタの引数リスト」も兼ねる(だから同じ並びで new PostResponse(...) できる)。
//
// JSON への変換(このファイルには見えない裏側): @RestController がこの record を返すと、
//   Spring が使う Jackson というライブラリが「コンポーネント名を JSON のキー、その取り出しメソッドの
//   戻り値を JSON の値」に自動変換する。user / category も record なので再帰的にネストした JSON になる。
//   実際にはこういう JSON になる:
//     {
//       "id": 1, "body": "こんにちは", "createdAt": "2026-07-22T10:30:00",
//       "user":     { "id": 5, "username": "taro", "displayName": "太郎" },
//       "category": { "id": 2, "name": "雑談" }
//     }
public record PostResponse(
		Long id, // 投稿の ID
		String body, // 本文
		LocalDateTime createdAt, // 作成日時
		UserSummary user, // 投稿者(下で定義する絞り込み用 record。User そのものではない)
		CategorySummary category // カテゴリー(下で定義する絞り込み用 record)
) {

	// PostResponse の内側に定義した小さな record(= ネストした record)。
	// 「このレスポンスでしか使わない小さなデータの形」をその場に閉じ込めている。
	// UserSummary は投稿者のうち、API で見せたい id・ユーザー名・表示名の3つだけを持つ。
	//   User エンティティにパスワードやメールがあっても、この器に含めない限り JSON に漏れない(セキュリティ上の要)。
	//   注意: 今後この器に項目を足す = そのまま外に公開する、ということ。
	// {} が空なのは、record が中身(コンストラクタ・取り出しメソッド等)を自動生成するので書くことがないから。
	public record UserSummary(Long id, String username, String displayName) {
	}

	public record CategorySummary(Long id, String name) {
	}

	// from = Post(エンティティ)を受け取って PostResponse に詰め替える変換メソッド。
	// static … 実体を作らずクラス名から直接呼べる。だから呼ぶ側は new せず PostResponse.from(post) と書ける。
	//   こうした「自分自身のインスタンスを作って返す static メソッド」をファクトリメソッドと呼ぶ。
	// 返り値の型 PostResponse は、このメソッドが所属する record 自身(工場が自分の製品を返すイメージ。定番の形)。
	public static PostResponse from(Post post) {
		return new PostResponse(
				post.getId(), // Post の取り出しメソッド(Post.java)で ID を得る
				post.getBody(), // 本文
				post.getCreatedAt(), // 作成日時
				// post.getUser() で投稿にひも付く User を取り出し、必要な3項目だけ抜き出して UserSummary に詰める。
				// .getUser().getId() は「User を取り出して、その User の ID を取り出す」を1行で書いたもの。
				// ここで追加 SQL が飛ばないのは、PostRepository が join fetch で user/category を先に取っているから(N+1 回避)。
				new UserSummary(
						post.getUser().getId(),
						post.getUser().getUsername(),
						post.getUser().getDisplayName()),
				// 同じ要領で、カテゴリーから id と名前だけを CategorySummary に詰める。
				new CategorySummary(
						post.getCategory().getId(),
						post.getCategory().getName()));
	}
}
