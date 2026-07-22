package com.example.app.post;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.app.post.dto.CreatePostRequest;
import com.example.app.post.dto.PostResponse;
import com.example.app.post.dto.TimelineResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/api/posts")
@Validated
public class PostController {

	private final PostService postService;

	public PostController(PostService postService) {
		this.postService = postService;
	}

	@GetMapping
	public TimelineResponse timeline(
			@RequestParam(required = false) Long cursor,
			@RequestParam(required = false) Long categoryId,
			@RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
		return postService.getTimeline(cursor, categoryId, limit);
	}

	@GetMapping("/{id}")
	public PostResponse get(@PathVariable Long id) {
		return postService.getPost(id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public PostResponse create(@Valid @RequestBody CreatePostRequest request) {
		return postService.create(request);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id) {
		postService.delete(id);
	}
}
