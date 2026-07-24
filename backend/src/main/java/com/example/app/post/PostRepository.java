package com.example.app.post;

import java.util.List; // 一覧(投稿のリスト)を表す Java 標準の型
import java.util.Optional; // 「値が有るか無いか」を表す箱(null の代わり)

import org.springframework.data.domain.Pageable; // 取得件数(LIMIT)や並び順を渡す指定オブジェクト
import org.springframework.data.jpa.repository.JpaRepository; // 継承するだけで標準 CRUD を提供する Spring Data の基底
import org.springframework.data.jpa.repository.Query; // メソッドに実行させるクエリ(JPQL)を指定するアノテーション
import org.springframework.data.repository.query.Param; // JPQL の :名前 とメソッド引数を結びつけるアノテーション

// class ではなく interface。メソッドの「宣言」だけ書き、中身(実装)は Spring Data JPA が
// 起動時に自動生成する。JpaRepository<Post, Long> を継承するだけで、save(保存)/
// findById(id で1件)/findAll(全件)/delete(削除)/count(件数)などの定番 CRUD メソッドが
// 1行も書かずに使えるようになる。<Post, Long> は「Post を扱い、その主キー(id)の型は Long」の意味。
// 主キーが long でなく Long(オブジェクト型)なのは、(1) ジェネリクスの型引数にプリミティブ型は書けない
//   (2) 保存前は id が未採番=null で、long だと 0 になり「まだ id が無い」を表せないため。
//   DB 列は NOT NULL だが、それは保存後の話。詳細 → docs/notes/java/spring/repository-and-entity-vs-laravel-model.md
public interface PostRepository extends JpaRepository<Post, Long> {

	/**
	 * タイムライン取得(カーソルページネーション)。
	 * - 新しい順 = id の降順。カーソルは「前ページ最後の投稿の id」で、それより小さい id を返す
	 * - user / category を fetch join で同時に取得し、N+1 を避ける(to-one なので LIMIT と併用しても安全)
	 *
	 * JPQL の読み方(SQL に似ているが、テーブル名/カラム名ではなくクラス名/プロパティ名で書く言語):
	 * - select p from Post p ... : Post クラス(= posts テーブル)を p という別名で扱う
	 * - join fetch p.user / p.category : Post.java で user/category は FetchType.LAZY(必要時まで
	 *   読み込まない)設定。放置すると投稿ごとに追加 SQL が飛ぶ(N+1 問題)ため、ここで先回りして
	 *   1回の SQL にまとめ取りしている
	 * - :cursor / :categoryId : 名前付きパラメータ。下の @Param と対応する。文字列連結ではなく
	 *   プレースホルダ経由で値が渡るため SQL インジェクションを防げる
	 * - (:cursor is null or p.id < :cursor) : cursor 未指定(null)なら全件対象、指定時はその id より
	 *   小さいものだけ。cursor/categoryId の有無 4 パターンを 1 本のクエリでさばく書き方
	 */
	@Query("""
			select p from Post p
			join fetch p.user
			join fetch p.category
			where (:cursor is null or p.id < :cursor)
			  and (:categoryId is null or p.category.id = :categoryId)
			order by p.id desc
			""")
	List<Post> findTimeline(
			@Param("cursor") Long cursor, // JPQL の :cursor に結びつく(以下同様)
			@Param("categoryId") Long categoryId,
			Pageable pageable); // 取得件数(LIMIT)や並び順を渡す Spring の部品

	/**
	 * id を指定して投稿を 1 件、投稿者(user)・カテゴリ(category)込みで取得する。
	 * 標準の findById も使えるが、それだと user/category が LAZY のまま残り、後でアクセスした
	 * 瞬間に追加 SQL が飛ぶ。join fetch で一緒に取るためにあえて自作している。
	 * 戻り値の Optional<Post> は「見つかるかもしれない/ないかもしれない」を型で表す入れ物で、
	 * 呼び出し側に存在チェックを強制でき、null 由来のエラーを防げる。
	 */
	@Query("""
			select p from Post p
			join fetch p.user
			join fetch p.category
			where p.id = :id
			""")
	Optional<Post> findByIdWithDetails(@Param("id") Long id);
}
