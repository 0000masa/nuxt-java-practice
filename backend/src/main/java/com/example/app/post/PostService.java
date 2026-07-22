package com.example.app.post;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.app.category.Category;
import com.example.app.category.CategoryRepository;
import com.example.app.common.exception.ForbiddenOperationException;
import com.example.app.common.exception.ResourceNotFoundException;
import com.example.app.post.dto.CreatePostRequest;
import com.example.app.post.dto.PostResponse;
import com.example.app.post.dto.TimelineResponse;
import com.example.app.user.CurrentUserProvider;
import com.example.app.user.User;

@Service
public class PostService {

	private final PostRepository postRepository;
	private final CategoryRepository categoryRepository;
	private final CurrentUserProvider currentUserProvider;

	public PostService(PostRepository postRepository, CategoryRepository categoryRepository,
			CurrentUserProvider currentUserProvider) {
		this.postRepository = postRepository;
		this.categoryRepository = categoryRepository;
		this.currentUserProvider = currentUserProvider;
	}

	@Transactional(readOnly = true)
	public TimelineResponse getTimeline(Long cursor, Long categoryId, int limit) {
		// limit + 1 件取得し、あふれたら「次のページがある」と判定する
		List<Post> fetched = postRepository.findTimeline(cursor, categoryId, PageRequest.of(0, limit + 1));
		boolean hasNext = fetched.size() > limit;
		List<Post> page = hasNext ? fetched.subList(0, limit) : fetched;
		Long nextCursor = hasNext ? page.get(page.size() - 1).getId() : null;
		return new TimelineResponse(page.stream().map(PostResponse::from).toList(), nextCursor);
	}

	@Transactional(readOnly = true)
	public PostResponse getPost(Long id) {
		Post post = postRepository.findByIdWithDetails(id)
				.orElseThrow(() -> new ResourceNotFoundException("投稿が見つかりません: id=" + id));
		return PostResponse.from(post);
	}

	@Transactional
	public PostResponse create(CreatePostRequest request) {
		Category category = categoryRepository.findById(request.categoryId())
				.orElseThrow(() -> new ResourceNotFoundException("カテゴリーが見つかりません: id=" + request.categoryId()));
		User user = currentUserProvider.getCurrentUser();
		Post post = postRepository.save(new Post(user, category, request.body()));
		return PostResponse.from(post);
	}

	@Transactional
	public void delete(Long id) {
		Post post = postRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("投稿が見つかりません: id=" + id));
		User currentUser = currentUserProvider.getCurrentUser();
		if (!post.getUser().getId().equals(currentUser.getId())) {
			throw new ForbiddenOperationException("自分の投稿以外は削除できません");
		}
		postRepository.delete(post);
	}
}
