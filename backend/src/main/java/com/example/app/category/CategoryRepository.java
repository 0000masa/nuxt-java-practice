package com.example.app.category;

import java.util.List; // 複数の値を順番に並べて入れておく箱(一覧)

import org.springframework.data.jpa.repository.JpaRepository; // 継承するだけで標準 CRUD を提供する Spring Data の基底

// categories テーブルとやり取りするための「窓口」。Controller → Service → Repository という
// 三段構えの一番下、DB アクセス担当にあたる(CategoryService から使われる)。
// class ではなく interface なので、メソッドの「宣言」だけを書き、実際に動く中身は Spring Data JPA が
// 起動時に自動生成する。JpaRepository を継承するだけで save / findById / findAll / delete / count などの
// 定番 CRUD が 1 行も書かずに使える(interface・継承・クエリメソッドの詳しい説明は UserRepository のコメント参照)。
// <Category, Long> は「Category を扱い、その主キー(id)の型は Long」の意味。Category.java の id が Long なのと対応する。
public interface CategoryRepository extends JpaRepository<Category, Long> {

	/**
	 * カテゴリを表示順(display_order)の昇順で全件取得する。
	 * 中身(SQL)は書いていないが、Spring Data JPA が「メソッド名」を読み取って対応する SQL を
	 * 起動時に自動生成する(クエリメソッド/派生クエリ)。名前を分解すると
	 * findAll(全件) + ByOrderBy(並び替え条件の合図) + DisplayOrderAsc(displayOrder で昇順=Ascending) となり、
	 * 「select * from categories order by display_order asc」に相当する。
	 * カテゴリは画面に決まった順で出したいマスタデータなので、並び順を保証して全件返すこのメソッドを用意している。
	 * 戻り値の List<Category> は Category が 0 件以上入った一覧。
	 */
	List<Category> findAllByOrderByDisplayOrderAsc();
}
