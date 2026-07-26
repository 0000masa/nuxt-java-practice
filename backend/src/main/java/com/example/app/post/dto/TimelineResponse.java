package com.example.app.post.dto;

import java.util.List;

/**
 * @param nextCursor 次ページ取得時に cursor として渡す値。null なら最終ページ
 */
public record TimelineResponse(List<PostResponse> posts, Long nextCursor) {
}
