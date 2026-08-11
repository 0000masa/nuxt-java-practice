package com.example.app.post;

import java.util.List; // 一覧(投稿のリスト)を表す Java 標準の型

import org.springframework.data.domain.PageRequest; // 取得するページ番号と件数を包んだ指定オブジェクト
import org.springframework.stereotype.Service; // このクラスが業務ロジック担当(サービス)だと示す目印
import org.springframework.transaction.annotation.Transactional; // メソッドの DB 操作をトランザクション化する指示

import com.example.app.category.Category; // カテゴリーのエンティティ
import com.example.app.category.CategoryRepository; // カテゴリーの DB 出し入れ役
import com.example.app.common.exception.ForbiddenOperationException; // 認可エラー(HTTP 403 に変換される)自作例外
import com.example.app.common.exception.ResourceNotFoundException; // 見つからないエラー(HTTP 404 に変換される)自作例外
import com.example.app.post.dto.CreatePostRequest; // 投稿作成時に受け取るデータ(DTO)
import com.example.app.post.dto.PostResponse; // 投稿1件を返すときのデータ(DTO)
import com.example.app.post.dto.TimelineResponse; // タイムライン一覧を返すときのデータ(DTO)
import com.example.app.user.User; // ユーザーのエンティティ
import com.example.app.user.UserRepository; // ユーザーの DB 出し入れ役

/**
 * 投稿にまつわる業務ロジック(アプリのルールに沿った処理)を担当するクラス。
 *
 * <p>PostController(受付係)から処理を任される「実際に仕事をする中身(調理場)」にあたる。
 * <pre>
 * ブラウザ → PostController(受付) → [PostService(調理場)] → PostRepository(DB係) → DB
 * </pre>
 *
 * <p>DB との出し入れそのものは Repository に任せ、この Service は「取ってきたデータをどう加工し、
 * どんなルールで通す/弾くか(存在確認・権限チェック・ページング計算)」に集中している。
 */
// @Service … 業務ロジックを担う部品だという Spring への目印(@RestController の仲間)。
//   これが付くと Spring が起動時に実体を1個だけ作って管理する(= Bean)。だから PostController に注入できる。
@Service
public class PostService {

	// この調理場が使う3つの道具。いずれも DI(依存性注入)でコンストラクタから受け取る(仕組みは PostController と同じ)。
	private final PostRepository postRepository; // 投稿の DB 出し入れ役
	private final CategoryRepository categoryRepository; // カテゴリーの DB 出し入れ役
	private final UserRepository userRepository; // 投稿者を引くための DB 出し入れ役

	public PostService(PostRepository postRepository, CategoryRepository categoryRepository,
			UserRepository userRepository) {
		this.postRepository = postRepository;
		this.categoryRepository = categoryRepository;
		this.userRepository = userRepository;
	}

	// 【一覧取得】タイムラインを1ページ分取得する。ページ送りは「カーソルページネーション」方式。
	// アルゴリズム(+1件のトリック):
	//   1. 欲しい limit 件より1件多く取る(limit + 1)。
	//   2. limit を超えて取れたら「次のページがある(hasNext)」と判定できる。
	//   3. 次があるなら、余分な1件を切り捨てて limit 件ちょうどに整える。
	//   4. 次ページ取得用の目印(nextCursor) = 今のページ最後の投稿の id。無ければ null(=最終ページ)。
	//   こうすると「次があるか」を、余分な COUNT クエリなしで判定できる。
	// @Transactional … このメソッド中の DB 操作を「ひとかたまり(トランザクション)」にする指示。途中で例外が飛べば
	//   全部なかったことにする(ロールバック)安全装置で、Spring がメソッドの入口/出口で自動制御する。
	//   readOnly = true は「読むだけで書き換えない」宣言。DB に最適化のヒントを与える(取得系に付ける)。
	@Transactional(readOnly = true)
	public TimelineResponse getTimeline(Long cursor, Long categoryId, int limit) {
		// limit + 1 件取得(0ページ目・サイズ limit+1)。findTimeline は join fetch で user/category も同時取得(N+1 回避)。
		List<Post> fetched = postRepository.findTimeline(cursor, categoryId, PageRequest.of(0, limit + 1));
		boolean hasNext = fetched.size() > limit; // 1件多く取れた = 次ページあり
		// 三項演算子(条件 ? 真の値 : 偽の値)。次があるなら余分1件を切り落とし(subList は先頭から limit 件)、なければ全部。
		List<Post> page = hasNext ? fetched.subList(0, limit) : fetched;
		// 次があれば「末尾の投稿の id」を次のカーソルに、無ければ null。size()-1 はリストの最後の位置。
		Long nextCursor = hasNext ? page.get(page.size() - 1).getId() : null;
		// stream で各 Post を PostResponse に変換してリスト化し、nextCursor と共に詰めて返す。
		//   .map(PostResponse::from) はメソッド参照。post -> PostResponse.from(post) と同じ意味。
		return new TimelineResponse(page.stream().map(PostResponse::from).toList(), nextCursor);
	}

	// 【1件取得】id で投稿を1件取得する。無ければ 404。
	@Transactional(readOnly = true)
	public PostResponse getPost(Long id) {
		// findByIdWithDetails の戻り値は Optional(「値が有るか無いか」を表す箱。null の代わり)。
		// orElseThrow = 中身があれば取り出し、空なら例外を投げる。() -> new ... はラムダ式(その場で書く小さな関数)。
		// ResourceNotFoundException は投げると HTTP 404 に変換される(変換は共通の GlobalExceptionHandler が担当)。
		Post post = postRepository.findByIdWithDetails(id)
				.orElseThrow(() -> new ResourceNotFoundException("投稿が見つかりません: id=" + id));
		return PostResponse.from(post);
	}

	// 【作成】新しい投稿を作る。書き込み系なので @Transactional(readOnly なし)。
	// userId は投稿者。Controller が @AuthenticationPrincipal から取り出して渡す(認証必須のエンドポイント)。
	@Transactional
	public PostResponse create(CreatePostRequest request, Long userId) {
		// まず紐づけるカテゴリーの存在確認。無ければ 404。
		//   (投稿は必ず実在するカテゴリーに紐づく、という CONTEXT.md のルールをここで守っている)
		//   request は record なので categoryId() / body() のように項目名メソッドで値を取り出す。
		Category category = categoryRepository.findById(request.categoryId())
				.orElseThrow(() -> new ResourceNotFoundException("カテゴリーが見つかりません: id=" + request.categoryId()));
		// 投稿者を DB から引く。レスポンス(PostResponse)に username と displayName を含めるので、
		// 参照だけを返す getReferenceById ではなく実体が必要になる。
		//   セッションは生きていても users の行が消えている場合があり得るので、無ければ 404。
		User user = userRepository.findById(userId)
				.orElseThrow(() -> new ResourceNotFoundException("ユーザーが見つかりません: id=" + userId));
		// Post の実体を作り、save で DB に保存。save は Spring Data JPA が用意済みで、保存後の Post(id 等が埋まる)を返す。
		Post post = postRepository.save(new Post(user, category, request.body()));
		return PostResponse.from(post);
	}

	// 【削除】投稿を削除する。存在確認 → 権限チェック → 削除、の順。
	@Transactional
	public void delete(Long id, Long userId) {
		// 削除対象の存在確認。無ければ 404。
		Post post = postRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("投稿が見つかりません: id=" + id));
		// 認可(権限)チェック: 投稿の持ち主と今のユーザーが違えば削除させず 403。
		//   比較は == ではなく .equals() を使う。id の型は Long(オブジェクト型)で、== は「同じ箱か」を見るため、
		//   値が同じでも別物と判定されうる。中身の値が等しいかは .equals() で見るのが Java の鉄則。
		//   post.getUser() は遅延読み込みのプロキシだが、getId() は外部キーの値そのものなので
		//   users テーブルへの SELECT は発生しない(ここではユーザーの中身が要らない)。
		if (!post.getUser().getId().equals(userId)) {
			throw new ForbiddenOperationException("自分の投稿以外は削除できません");
		}
		postRepository.delete(post); // CONTEXT.md のとおり物理削除(本当に消す)
	}
}
