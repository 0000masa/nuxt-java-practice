# DELETE /api/posts/{id} — 投稿の削除

## 概要

自分の投稿を削除する。削除は**物理削除**(投稿は編集不可・物理削除という仕様。`CONTEXT.md` 参照)。「自分」の判定は「いまリクエストしているユーザー」(現状は認証未実装のため固定の `dev_user`。詳細は [README の「認証の現状」](./README.md#認証の現状フェーズ3で置き換え予定))。

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
   パスパラメータ `id` を受け取る
2. `PostService.delete()` — `com/example/app/post/PostService.java`
   投稿を取得(なければ 404)し、`CurrentUserProvider` の現在ユーザーと投稿の所有者を比較(不一致なら `ForbiddenOperationException` → 403)
3. `PostRepository.delete()` — `com/example/app/post/PostRepository.java`
   DELETE(物理削除)

登場するその他のファイル:

- 現在ユーザーの取得: `com/example/app/user/CurrentUserProvider.java`(実装は `com/example/app/user/DevCurrentUserProvider.java`)
- 403 / 404 変換: `com/example/app/common/exception/ForbiddenOperationException.java`、`com/example/app/common/exception/ResourceNotFoundException.java`、`com/example/app/common/exception/GlobalExceptionHandler.java`
- 対象エンティティ: `com/example/app/post/Post.java`
