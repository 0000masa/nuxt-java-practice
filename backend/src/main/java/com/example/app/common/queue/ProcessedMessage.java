package com.example.app.common.queue;

import java.time.LocalDateTime;

import org.springframework.data.domain.Persistable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * 処理済みの依頼の記録(processed_messages テーブルの 1 行)。キューの重複配信を止める冪等キー。
 *
 * <p>表を作った理由と主キーの役割は V5 のマイグレーションのコメントを参照。
 *
 * <p><b>{@link Persistable} を実装しているのは、{@code save()} を必ず INSERT にするため。</b>
 * 主キーを自分で決めるエンティティを {@code save()} すると、Spring Data は「既存の行かもしれない」と見て
 * merge(SELECT してから INSERT か UPDATE)を選ぶ。{@link #isNew()} が常に true を返せば persist(INSERT)になり、
 * 同じ request_id が既にあれば主キーの重複で失敗する。それがこの表に期待している動き。
 * Persistable<String> は Spring Data のインターフェースで、「このエンティティが新規かどうか」を Spring Data に自分で答えるためのもの
 */
@Entity
@Table(name = "processed_messages")
public class ProcessedMessage implements Persistable<String> {

	@Id
	@Column(name = "request_id", length = 36)
	private String requestId;

	@Column(name = "processed_at", nullable = false)
	private LocalDateTime processedAt;

	protected ProcessedMessage() {
		// JPA 用
	}

	public ProcessedMessage(String requestId) {
		this.requestId = requestId;
		this.processedAt = LocalDateTime.now();
	}

	// Persistable が要求するメソッドです。主キーの値を返す
	@Override
	public String getId() {
		return requestId;
	}

	// @Transient(49 行目): 「isNew は列ではない」という目印。
	// このクラスはフィールドに @Id を付けているので、JPA はフィールドだけを列とみなす。
	// そのため実際には無くても困りませんが、Spring Data のリファレンスの例に倣った防御的な書き方
	@Override
	@Transient
	public boolean isNew() {
		return true;
	}
}
