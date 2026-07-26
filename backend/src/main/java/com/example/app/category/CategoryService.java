package com.example.app.category;

import java.util.List; // 一覧(カテゴリーのリスト)を表す Java 標準の型

import org.springframework.stereotype.Service; // このクラスが業務ロジック担当(サービス)だと示す目印
import org.springframework.transaction.annotation.Transactional; // メソッドの DB 操作をトランザクション化する指示

import com.example.app.category.dto.CategoryResponse; // カテゴリー1件を返すときのデータ(DTO)

/**
 * カテゴリーにまつわる業務ロジックを担当するクラス。
 *
 * <p>CategoryController(受付係)から処理を任される「実際に仕事をする中身」にあたる。
 *
 * <pre>
 * ブラウザ → CategoryController(受付) → [CategoryService] → CategoryRepository(DB係) → DB
 * </pre>
 *
 * <p>カテゴリーは運営側が用意するマスタデータで、アプリからは参照のみ(→ CONTEXT.md)。
 * そのため今のところこの Service の中身は「Repository から取り、DTO に詰め替えて返す」だけの薄い処理しかない。
 * それでも層を挟んでいるのは、次の3つの利点があるため。
 * <ul>
 * <li>Controller は HTTP の入口に専念でき、ビジネスロジックを持たない(→ docs/development/backend-structure-best-practices.md)</li>
 * <li>トランザクション境界(@Transactional)を宣言する場所ができる</li>
 * <li>Controller のテストで Service をモックに差し替えられる(CategoryControllerTest)</li>
 * </ul>
 *
 * <p>将来「非公開カテゴリーを除外する」「投稿数を添えて返す」といったルールが増えたとき、
 * 追加先がこのクラスに決まっているのも、あらかじめ層を分けておく利点。
 */
// @Service … 業務ロジックを担う部品だという Spring への目印(@RestController の仲間)。
//   これが付くと Spring が起動時に実体を1個だけ作って管理する(= Bean)。だから CategoryController に注入できる。
@Service
public class CategoryService {

	// この Service が使う道具。DI(依存性注入)でコンストラクタから受け取る(仕組みは PostController のコメント参照)。
	// import が要らない理由: CategoryRepository はこのクラスと同じパッケージ(com.example.app.category)にあるため。
	private final CategoryRepository categoryRepository; // カテゴリーの DB 出し入れ役

	public CategoryService(CategoryRepository categoryRepository) {
		this.categoryRepository = categoryRepository;
	}

	// 【一覧取得】カテゴリーを表示順(display_order の昇順)で全件返す。
	// @Transactional(readOnly = true) … このメソッド中の DB 操作をひとかたまり(トランザクション)にする指示。
	//   readOnly = true は「読むだけで書き換えない」宣言で、DB に最適化のヒントを与える(取得系に付ける)。
	//   件数の少ないマスタデータの1クエリなので効果は小さいが、取得系の書き方を PostService と揃えている。
	@Transactional(readOnly = true)
	public List<CategoryResponse> getCategories() {
		// 処理は3段階。
		//   1. findAllByOrderByDisplayOrderAsc() で Category(エンティティ=DB 側の姿)の一覧を取る。
		//      SQL は書かれていないが、メソッド名から Spring Data JPA が自動生成する(→ CategoryRepository のコメント)。
		//   2. stream() で「流れ作業のベルトコンベア」に載せ、.map(...) で1件ずつ別のものに変換する。
		//      CategoryResponse::from はメソッド参照。category -> CategoryResponse.from(category) と同じ意味。
		//   3. toList() でベルトコンベアの結果を List に戻す。
		// エンティティをそのまま返さず CategoryResponse に詰め替えるのは、API に見せる項目(id と name)だけに
		//   絞り込むため。displayOrder のような内部都合の項目を外に漏らさない(→ CategoryResponse.java)。
		return categoryRepository.findAllByOrderByDisplayOrderAsc().stream()
				.map(CategoryResponse::from)
				.toList();
	}
}
