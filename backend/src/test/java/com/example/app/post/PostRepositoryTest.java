package com.example.app.post;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
// Spring Boot 4 でテストアノテーションのパッケージが技術別モジュールに移動している(Boot 3 の記事とは import が異なる)
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;

import com.example.app.category.Category;
import com.example.app.category.CategoryRepository;
import com.example.app.user.User;
import com.example.app.user.UserRepository;

/**
 * カーソルページネーションのクエリ検証(バグの温床になりやすい境界条件を押さえる)。
 * 開発用 MySQL(docker compose の mysql)に対して実行し、各テスト後にロールバックされる。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PostRepositoryTest {

	@Autowired
	PostRepository postRepository;

	@Autowired
	CategoryRepository categoryRepository;

	@Autowired
	UserRepository userRepository;

	User user;
	Category category1;
	Category category2;
	Post post1;
	Post post2;
	Post post3;
	Post post4;
	Post post5;

	@BeforeEach
	void setUp() {
		// 開発 DB を共用するため、既存の投稿はトランザクション内で消して前提を固定する(ロールバックで元に戻る)
		postRepository.deleteAll();

		user = userRepository.save(new User("repo_test_user", "リポジトリテスト", "repo-test@example.com"));
		category1 = categoryRepository.findById(1L).orElseThrow();
		category2 = categoryRepository.findById(2L).orElseThrow();

		// id 昇順で 5 件(古い→新しい)。カテゴリーは 1,2,1,2,1 と交互
		post1 = postRepository.save(new Post(user, category1, "投稿1"));
		post2 = postRepository.save(new Post(user, category2, "投稿2"));
		post3 = postRepository.save(new Post(user, category1, "投稿3"));
		post4 = postRepository.save(new Post(user, category2, "投稿4"));
		post5 = postRepository.save(new Post(user, category1, "投稿5"));
	}

	@Test
	void タイムラインは新しい順に返る() {
		List<Post> result = postRepository.findTimeline(null, null, PageRequest.of(0, 10));

		assertThat(result).extracting(Post::getId)
				.containsExactly(post5.getId(), post4.getId(), post3.getId(), post2.getId(), post1.getId());
	}

	@Test
	void カーソルより新しい投稿は返らない() {
		// カーソル = 前ページ最後の投稿の id。それ「より小さい」id だけが返る(カーソル自身は含まない)
		List<Post> result = postRepository.findTimeline(post3.getId(), null, PageRequest.of(0, 10));

		assertThat(result).extracting(Post::getId)
				.containsExactly(post2.getId(), post1.getId());
	}

	@Test
	void カテゴリー絞り込みとカーソルを併用できる() {
		List<Post> result = postRepository.findTimeline(post5.getId(), category1.getId(), PageRequest.of(0, 10));

		assertThat(result).extracting(Post::getId)
				.containsExactly(post3.getId(), post1.getId());
	}

	@Test
	void limitで件数が制限される() {
		List<Post> result = postRepository.findTimeline(null, null, PageRequest.of(0, 2));

		assertThat(result).extracting(Post::getId)
				.containsExactly(post5.getId(), post4.getId());
	}
}
