# DELETE /api/posts/{id} — 投稿の削除

## 概要

自分の投稿を削除する。削除は**物理削除**(投稿は編集不可・物理削除という仕様。`CONTEXT.md` 参照)。

**認証必須。** CSRF トークンも必要(→ [README の「認証とセッション」](./README.md#認証とセッション))。「自分」の判定はログインしているセッションから決まるので、フロントの言い値で他人の投稿を消すことはできない。

## リクエスト

### パスパラメータ

| パラメータ | 型 | 必須 | 説明 |
|-----------|-----|------|------|
| `id` | number | 必須 | 削除する投稿の id |

## レスポンス

**204 No Content** — ボディなし

## エラー

| ステータス | 発生条件 |
|-----------|---------|
| 401 Unauthorized | 未ログイン |
| 403 Forbidden | CSRF トークンが無い / 合わない |
| 403 Forbidden | 投稿の所有者が自分でない |
| 404 Not Found | 指定した id の投稿が存在しない |

403 の例:

```json
{
  "message": "自分の投稿以外は削除できません",
  "fieldErrors": null
}
```

## 処理の流れ

呼び出し順に、通過するファイルとメソッドを示す(パスは `backend/src/main/java/` からの相対)。

1. `PostController.delete()` — `com/example/app/post/PostController.java`
   パスパラメータ `id` と、`@AuthenticationPrincipal AppUserDetails` で現在ユーザーを受け取る
2. `PostService.delete()` — `com/example/app/post/PostService.java`
   投稿を取得(なければ 404)し、投稿の所有者の id と現在ユーザーの id を比較(不一致なら `ForbiddenOperationException` → 403)
   `post.getUser()` は遅延読み込みのプロキシだが、`getId()` は外部キーの値そのものなので users への SELECT は発生しない
3. `PostRepository.delete()` — `com/example/app/post/PostRepository.java`
   DELETE(物理削除)

登場するその他のファイル:

- 現在ユーザー(principal): `com/example/app/auth/AppUserDetails.java`
- 認可ルール: `com/example/app/config/SecurityConfig.java`
- 403 / 404 変換: `com/example/app/common/exception/ForbiddenOperationException.java`、`com/example/app/common/exception/ResourceNotFoundException.java`、`com/example/app/common/exception/GlobalExceptionHandler.java`
- 対象エンティティ: `com/example/app/post/Post.java`
