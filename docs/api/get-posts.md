# GET /api/posts — タイムライン取得

## 概要

全ユーザーの投稿を新しい順(id の降順)で返す。カーソルページネーション方式で、カテゴリーによる絞り込みができる。

## リクエスト

### クエリパラメータ

| パラメータ | 型 | 必須 | 説明 |
|-----------|-----|------|------|
| `cursor` | number | 任意 | 前ページ最後の投稿の `id`。指定するとそれより古い(id が小さい)投稿を返す。省略時は先頭ページ |
| `categoryId` | number | 任意 | 指定したカテゴリーの投稿だけに絞り込む |
| `limit` | number | 任意 | 1ページの件数。デフォルト 20、最小 1・最大 50 |

## レスポンス

**200 OK**

```json
{
  "posts": [
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
  ],
  "nextCursor": 42
}
```

- `nextCursor` … 次ページを取得するときに `cursor` として渡す値。**`null` なら最終ページ**

## エラー

| ステータス | 発生条件 |
|-----------|---------|
| 400 Bad Request | `limit` が 1〜50 の範囲外 |

```json
{
  "message": "リクエストパラメータが不正です: timeline.limit: 50 以下の値にしてください",
  "fieldErrors": null
}
```

## 処理の流れ

呼び出し順に、通過するファイルとメソッドを示す(パスは `backend/src/main/java/` からの相対)。

1. `PostController.timeline()` — `com/example/app/post/PostController.java`
   クエリパラメータを受け取り、`@Min` / `@Max` で `limit` を検証
2. `PostService.getTimeline()` — `com/example/app/post/PostService.java`
   `limit + 1` 件取得し、あふれたら「次のページがある」と判定して `nextCursor` を組み立てる
3. `PostRepository.findTimeline()` — `com/example/app/post/PostRepository.java`
   JPQL で `id < :cursor` と `category.id = :categoryId` の条件を適用(どちらも null なら無条件)。ユーザー・カテゴリーを fetch join して N+1 を回避

登場するその他のファイル:

- レスポンスの組み立て: `com/example/app/post/dto/TimelineResponse.java`、`com/example/app/post/dto/PostResponse.java`
- 対象エンティティ: `com/example/app/post/Post.java`

### カーソルページネーションの仕組み

- 投稿の `id` は AUTO_INCREMENT なので「id の降順 = 新しい順」が成り立ち、id がそのままカーソルを兼ねる
- OFFSET 方式と違い、ページ送り中に新しい投稿が増えても同じ投稿が二重に出ない
- 「次ページがあるか」は `limit + 1` 件取得して1件あふれるかどうかで判定する(COUNT クエリ不要)
