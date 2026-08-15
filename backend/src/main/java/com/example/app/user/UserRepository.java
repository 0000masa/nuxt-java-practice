package com.example.app.user;

import java.util.Optional; // 「値が有るか無いか」を表す箱(null の代わり)

import org.springframework.data.jpa.repository.JpaRepository; // 継承するだけで標準 CRUD を提供する Spring Data の基底

// class ではなく interface。メソッドの「宣言」だけ書き、中身(実装)は Spring Data JPA が
// 起動時に自動生成する。JpaRepository<User, Long> を継承するだけで、save(保存)/
// findById(id で1件)/findAll(全件)/delete(削除)/count(件数)などの定番 CRUD メソッドが
// 1行も書かずに使えるようになる。<User, Long> は「User を扱い、その主キー(id)の型は Long」の意味。
// 主キーが long でなく Long(オブジェクト型)なのは、保存前の id が未採番=null を表せるようにするため
//   (詳細 → PostRepository のコメント / docs/notes/java/spring/repository-and-entity-vs-laravel-model.md)。
public interface UserRepository extends JpaRepository<User, Long> {

	/**
	 * ユーザー名(username)で User を 1 件探す。
	 * 中身(SQL)は書いていないが、Spring Data JPA が「メソッド名」を読み取り、対応する SQL を
	 * 起動時に自動生成する(クエリメソッド/派生クエリと呼ぶ)。findBy + Username →
	 * 「select ... from users where username = ?」に相当する。値は ? のプレースホルダ経由で
	 * 渡るため、文字列連結と違い SQL インジェクションの心配がない。
	 * 戻り値の Optional<User> は「見つかるかもしれない/ないかもしれない」を型で表す入れ物で、
	 * 呼び出し側に存在チェックを強制でき、null 由来のエラー(NullPointerException)を防げる。
	 */
	Optional<User> findByUsername(String username);

	/**
	 * メールアドレスで User を 1 件探す。ログインの識別子はメールアドレスなので、
	 * 認証(UserDetailsService)はこのメソッドでユーザーを引く。
	 */
	Optional<User> findByEmail(String email);

	/**
	 * Google OIDC の sub(不変 ID)で User を 1 件探す。
	 * Google ログインはまずこれで引く。メールで引かないのは、利用者が Google 側で
	 * メールアドレスを変えても同じユーザーだと分かるようにするため
	 * → docs/adr/0004-google-account-linking.md
	 */
	Optional<User> findByGoogleSub(String googleSub);
}
