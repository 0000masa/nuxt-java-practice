# GET /api/posts/{id} — 投稿の単体取得

## 概要

指定した id の投稿を1件返す。

## リクエスト

### パスパラメータ

| パラメータ | 型 | 必須 | 説明 |
|-----------|-----|------|------|
| `id` | number | 必須 | 投稿の id |

## レスポンス

**200 OK**

```json
{
  "id": 42,
  "body": "投稿の本文",
  "createdAt": "2026-07-20T10:15:30",
  "user": {
    "id": 1,
    "username": "dev_user",
    "displayName": "開発ユーザー"
  },
  "category": {
    "id": 2,
    "name": "日常"
  }
}
```

## エラー

| ステータス | 発生条件 |
|-----------|---------|
| 404 Not Found | 指定した id の投稿が存在しない |

```json
{
  "message": "投稿が見つかりません: id=999",
  "fieldErrors": null
}
```

## 処理の流れ

呼び出し順に、通過するファイルとメソッドを示す(パスは `backend/src/main/java/` からの相対)。

1. `PostController.get()` — `com/example/app/post/PostController.java`
   パスパラメータ `id` を受け取る
2. `PostService.getPost()` — `com/example/app/post/PostService.java`
   投稿を取得し、なければ `ResourceNotFoundException`(→ 404)
3. `PostRepository.findByIdWithDetails()` — `com/example/app/post/PostRepository.java`
   ユーザー・カテゴリーを fetch join して1クエリで取得

登場するその他のファイル:

- 404 変換: `com/example/app/common/exception/ResourceNotFoundException.java`、`com/example/app/common/exception/GlobalExceptionHandler.java`
- レスポンスの組み立て: `com/example/app/post/dto/PostResponse.java`
- 対象エンティティ: `com/example/app/post/Post.java`
