# POST /api/posts — 投稿の作成

## 概要

投稿を新規作成する。投稿者は「いまリクエストしているユーザー」(現状は認証未実装のため固定の `dev_user`。詳細は [README の「認証の現状」](./README.md#認証の現状フェーズ3で置き換え予定))。

## リクエスト

### ボディ(JSON)

| フィールド | 型 | 必須 | バリデーション |
|-----------|-----|------|--------------|
| `body` | string | 必須 | 空白のみは不可、280文字以内 |
| `categoryId` | number | 必須 | null 不可。存在するカテゴリーの id であること |

```json
{
  "body": "投稿の本文",
  "categoryId": 2
}
```

## レスポンス

**201 Created** — 作成された投稿を返す

```json
{
  "id": 43,
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
| 400 Bad Request | ボディのバリデーション違反(`fieldErrors` に詳細が入る) |
| 404 Not Found | `categoryId` のカテゴリーが存在しない |

400 の例:

```json
{
  "message": "入力内容に誤りがあります",
  "fieldErrors": {
    "body": "本文は280文字以内で入力してください"
  }
}
```

## 処理の流れ

呼び出し順に、通過するファイルとメソッドを示す(パスは `backend/src/main/java/` からの相対)。

1. `PostController.create()` — `com/example/app/post/PostController.java`
   `@Valid` で `CreatePostRequest` のバリデーションを実行(違反時はここで 400)
2. `PostService.create()` — `com/example/app/post/PostService.java`
   カテゴリーの存在を `CategoryRepository` で確認(なければ 404)し、`CurrentUserProvider` から投稿者を取得して `Post` を保存
3. `PostRepository.save()` — `com/example/app/post/PostRepository.java`
   INSERT。`createdAt` は `Post` の `@PrePersist` で保存時に自動設定

登場するその他のファイル:

- リクエストボディ定義(バリデーション): `com/example/app/post/dto/CreatePostRequest.java`
- カテゴリー存在チェック: `com/example/app/category/CategoryRepository.java`
- 投稿者の取得: `com/example/app/user/CurrentUserProvider.java`(実装は `com/example/app/user/DevCurrentUserProvider.java`)
- 400 / 404 変換: `com/example/app/common/exception/GlobalExceptionHandler.java`、`com/example/app/common/exception/ResourceNotFoundException.java`
- レスポンスの組み立て: `com/example/app/post/dto/PostResponse.java`
- 対象エンティティ: `com/example/app/post/Post.java`
