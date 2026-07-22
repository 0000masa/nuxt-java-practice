# REST API ドキュメント

バックエンド(Spring Boot)が提供する REST API のドキュメント。**エンドポイントごとに1ファイル**で管理する。API を変更したら該当ファイルを必ず更新すること。

## エンドポイント一覧

| メソッド | パス | 内容 | ドキュメント |
|---------|------|------|------------|
| GET | `/api/posts` | タイムライン取得(カーソルページネーション・カテゴリー絞り込み) | [get-posts.md](./get-posts.md) |
| GET | `/api/posts/{id}` | 投稿の単体取得 | [get-post-by-id.md](./get-post-by-id.md) |
| POST | `/api/posts` | 投稿の作成 | [create-post.md](./create-post.md) |
| DELETE | `/api/posts/{id}` | 投稿の削除(自分の投稿のみ) | [delete-post.md](./delete-post.md) |
| GET | `/api/categories` | カテゴリー一覧取得 | [get-categories.md](./get-categories.md) |

ベース URL について:フロントエンドは相対パス `/api` を呼び、開発時は Nuxt の devProxy が backend コンテナへ転送する。詳細は `docs/development/` を参照。

## ファイル関係ツリー

```
backend/src/main/java/com/example/app/
├── post/                          … 投稿ドメイン
│   ├── PostController.java        … GET/POST /api/posts、GET/DELETE /api/posts/{id} の HTTP 入口
│   ├── PostService.java           … 業務ロジック(ページネーション判定、カテゴリー存在チェック、所有者チェック)
│   ├── PostRepository.java        … DB アクセス(JPA。fetch join で N+1 を回避)
│   ├── Post.java                  … エンティティ(posts テーブル)
│   └── dto/
│       ├── CreatePostRequest.java … 投稿作成のリクエストボディ(バリデーション定義)
│       ├── PostResponse.java      … 投稿1件のレスポンス(ユーザー・カテゴリーの要約を内包)
│       └── TimelineResponse.java  … タイムラインのレスポンス(posts + nextCursor)
├── category/                      … カテゴリードメイン
│   ├── CategoryController.java    … GET /api/categories の HTTP 入口(単純な参照のみのため Service 層なし)
│   ├── CategoryRepository.java    … DB アクセス(JPA)
│   ├── Category.java              … エンティティ(categories テーブル。Flyway で投入するマスタデータ)
│   └── dto/
│       └── CategoryResponse.java  … カテゴリー1件のレスポンス
├── user/                          … ユーザードメイン(ユーザー用の API はまだない)
│   ├── CurrentUserProvider.java   … 「いまリクエストしているユーザー」の抽象(下記「認証の現状」参照)
│   ├── DevCurrentUserProvider.java… 開発用の固定ユーザーを返す仮実装
│   ├── User.java                  … エンティティ(users テーブル)
│   └── UserRepository.java        … DB アクセス(JPA)
└── common/                        … 横断的関心事
    ├── dto/
    │   └── ErrorResponse.java     … エラーレスポンスの共通形(下記「エラーレスポンス」参照)
    └── exception/
        ├── GlobalExceptionHandler.java      … 例外 → HTTP レスポンスへの一元変換
        ├── ResourceNotFoundException.java   … リソースが存在しない → 404
        └── ForbiddenOperationException.java … 認可されていない操作 → 403
```

依存の流れは `Controller → Service → Repository` が基本。`PostService` は投稿作成時のカテゴリー存在チェックのために `CategoryRepository` に、投稿者の特定のために `CurrentUserProvider` にも依存する。

## エラーレスポンス

エラーはすべて `GlobalExceptionHandler` で HTTP レスポンスに変換され、共通形 `ErrorResponse` で返る。各 Controller に try-catch は書かない。

```json
{
  "message": "人間向けのエラーメッセージ",
  "fieldErrors": null
}
```

`fieldErrors` はリクエストボディのバリデーションエラー(400)のときだけ「フィールド名 → メッセージ」のマップが入り、それ以外は `null`。

| ステータス | 変換元の例外 | 発生条件 |
|-----------|------------|---------|
| 400 Bad Request | `MethodArgumentNotValidException` | リクエストボディのバリデーション違反(`fieldErrors` あり) |
| 400 Bad Request | `ConstraintViolationException` | クエリパラメータのバリデーション違反(`@Min` / `@Max` など) |
| 403 Forbidden | `ForbiddenOperationException` | 認可されていない操作(例:他人の投稿の削除) |
| 404 Not Found | `ResourceNotFoundException` | 対象リソースが存在しない |

## 認証の現状(フェーズ3で置き換え予定)

**現在、認証は未実装。** 「いまリクエストしているユーザー」は `CurrentUserProvider` インターフェースで抽象化されており、現在は開発用実装 `DevCurrentUserProvider`(`@Profile("!prod")`)が固定ユーザー `dev_user` を返す(初回アクセス時に自動作成)。

そのため、投稿の作成はすべて `dev_user` の投稿になり、削除の所有者チェックも `dev_user` を基準に行われる。フェーズ3でセッションベースの認証実装に差し替える予定。差し替え時はこのセクションと `CurrentUserProvider` の実装だけが変わり、各エンドポイントの仕様は変わらない想定。

## 日時のフォーマット

レスポンス中の日時(`createdAt` など)は `LocalDateTime` を ISO-8601 形式(タイムゾーンなし)でシリアライズしたもの。例: `"2026-07-20T10:15:30"`
