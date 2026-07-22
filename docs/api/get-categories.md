# GET /api/categories — カテゴリー一覧取得

## 概要

カテゴリーの一覧を表示順(`displayOrder` の昇順)で返す。カテゴリーは運営側が用意するマスタデータで、レコードは Flyway(`V2__insert_categories.sql`)で投入され、アプリからは参照のみ(作成・更新・削除の API はない)。

## リクエスト

パラメータなし。

## レスポンス

**200 OK**

```json
[
  { "id": 1, "name": "お知らせ" },
  { "id": 2, "name": "日常" },
  { "id": 3, "name": "技術" }
]
```

※ `name` の値は Flyway で投入した実データに依存する。上記は形式のイメージ。

## エラー

なし(パラメータがないため、通常の運用でエラーになるケースはない)。

## 処理の流れ

呼び出し順に、通過するファイルとメソッドを示す(パスは `backend/src/main/java/` からの相対)。

1. `CategoryController.list()` — `com/example/app/category/CategoryController.java`
   単純な参照のみのため Service 層を挟まず `CategoryRepository` を直接呼ぶ
2. `CategoryRepository.findAllByOrderByDisplayOrderAsc()` — `com/example/app/category/CategoryRepository.java`
   Spring Data JPA のメソッド名規約による導出クエリで、`display_order` 昇順の全件取得

登場するその他のファイル:

- レスポンスの組み立て: `com/example/app/category/dto/CategoryResponse.java`
- 対象エンティティ: `com/example/app/category/Category.java`
