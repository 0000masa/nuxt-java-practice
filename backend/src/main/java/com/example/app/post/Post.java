package com.example.app.post;

import java.time.LocalDateTime; // 日付+時刻を表す Java 標準の型(作成日時に使う)

import com.example.app.category.Category; // カテゴリーのエンティティ(この投稿が属するカテゴリー)
import com.example.app.user.User; // ユーザーのエンティティ(この投稿の投稿者)

import jakarta.persistence.Column; // 列とのひも付け・制約(nullable/length など)を指定するアノテーション
import jakarta.persistence.Entity; // このクラスを DB テーブルに対応するエンティティだと示すアノテーション
import jakarta.persistence.FetchType; // 関連データの読み込みタイミング(LAZY/EAGER)を表す列挙
import jakarta.persistence.GeneratedValue; // 主キーの採番方法を指定するアノテーション
import jakarta.persistence.GenerationType; // 採番戦略(IDENTITY など)を表す列挙
import jakarta.persistence.Id; // 主キーのフィールドに付けるアノテーション
import jakarta.persistence.JoinColumn; // 関連を結ぶ外部キー列(user_id など)を指定するアノテーション
import jakarta.persistence.ManyToOne; // 多対一の関連を表すアノテーション
import jakarta.persistence.PrePersist; // INSERT 直前に自動実行するメソッドに付けるアノテーション
import jakarta.persistence.Table; // 対応するテーブル名を明示するアノテーション

/**
 * 投稿を表すエンティティ(DB の posts テーブルの 1 行 = この Post オブジェクト 1 個)。
 * 編集不可のため updated_at を持たず、削除は物理削除。
 * id(AUTO_INCREMENT)はカーソルページネーションのカーソルを兼ねる。
 * setter を置かず getter だけにして「作成後は書き換えない(編集不可)」を構造で守っている。
 */
@Entity // このクラスを DB テーブルと対応する入れ物(エンティティ)として JPA に管理させる目印
@Table(name = "posts") // 対応するテーブル名を明示
public class Post {

	@Id // 主キー(1 行を一意に識別する値)
	@GeneratedValue(strategy = GenerationType.IDENTITY) // 採番は DB の AUTO_INCREMENT 任せ(save 後に id が入る)
	private Long id;

	// @ManyToOne = 多対一(多くの投稿が 1 人の User に属する)。LAZY は「必要になるまで読み込まない」
	//   (一覧で毎回 user まで取ると無駄なため。まとめ取りは Repository の join fetch が担当)。
	// @JoinColumn = DB 側の外部キー列 user_id で結ぶ。Java 側では User オブジェクトを丸ごと持つ形になる。
	// optional=false と nullable=false はどちらも「必須(null 禁止)」だが効く層が違う:
	//   optional(@ManyToOne)= JPA/オブジェクト層の宣言。「関連は必ず存在」を Hibernate に伝え、取得時の
	//     JOIN 最適化(必ず居るので INNER JOIN でよい)などに効く。
	//   nullable(@JoinColumn)= DB 列側の NOT NULL 指定。ただし本プロジェクトのスキーマは Flyway 管理
	//     (ddl-auto=validate)なので、実際の NOT NULL 制約は V1 マイグレーションの SQL が定義しており、
	//     ここの nullable=false は主に意図の明示と検証用ヒント(スキーマ管理の役割分担 → docs/notes/flyway-basics.md)。
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "category_id", nullable = false)
	private Category category;

	@Column(nullable = false, length = 280) // 必須・最大 280 文字
	private String body;

	// updatable = false … 一度入れたら UPDATE 対象にしない(作成日時は後から書き換えないため)
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	// JPA 専用の引数なしコンストラクタ。JPA は「空の器を作って値を後から差し込む」ため必須。
	// protected にして、アプリ側から中身の無い中途半端な Post を作れないようにしている。
	protected Post() {
		// JPA 用
	}

	// アプリが使う正規の入り口。投稿者・カテゴリ・本文がそろって初めて Post を作れる。
	// createdAt は引数に取らず、保存直前に onCreate で自動セットする。
	public Post(User user, Category category, String body) {
		this.user = user;
		this.category = category;
		this.body = body;
	}

	// @PrePersist … INSERT の直前に JPA が自動で呼ぶメソッド。作成日時のセット忘れを防ぐ。
	@PrePersist
	void onCreate() {
		createdAt = LocalDateTime.now();
	}

	// 以下は値を外から読むための getter 群。setter は置かず読み取り専用にしている(上記「編集不可」)。
	public Long getId() {
		return id;
	}

	public User getUser() {
		return user;
	}

	public Category getCategory() {
		return category;
	}

	public String getBody() {
		return body;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}
}
