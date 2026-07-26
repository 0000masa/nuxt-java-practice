# Spring の例外ハンドリング — なぜ「対応表」で済むのか、Laravel / Hono / Nuxt / Next.js と何が違うのか

`GlobalExceptionHandler` を見ていて湧く疑問 — 「なぜ `try-catch` を 1 つも書いていないのに例外が HTTP レスポンスになる?」「`@ExceptionHandler` を並べるだけでいいのはなぜ?」「他のフレームワークでも同じように書けるのか?」 — に答える学習メモ。**「例外を HTTP レスポンスへ変換する集約点を、フレームワークがどこまで用意してくれるか」**という軸で 4 つのフレームワークを並べる。

対象ファイル: [GlobalExceptionHandler.java](../../../../backend/src/main/java/com/example/app/common/exception/GlobalExceptionHandler.java) / [ErrorResponse.java](../../../../backend/src/main/java/com/example/app/common/dto/ErrorResponse.java)

> **このメモのコード例について**
> Spring 側のコードと、Nuxt が**受け取る**側のコード（`Form.vue`）は、このリポジトリの実物であり動作している。
> 一方 **Laravel / Hono / Next.js のコード例、および Nuxt がエラーを**返す**側のコード例は、このリポジトリに存在せず実行検証していない参考コード**。このプロジェクトのフロントは SSG（`nuxt generate`）で `server/` ディレクトリを持たないため、Nitro のサーバー機能は開発時の devProxy にしか使っていない。
> 対象バージョンは Spring Boot 4.1.0 / Java 21（`build.gradle` で確認）、Laravel 10 と 11、Hono v4、Nuxt 4（Nitro / h3）、Next.js 15 App Router。この領域はバージョン差が激しいので、各章の公式ドキュメントで裏を取ってから使うこと（リンクは執筆時点のもの）。

## まず結論(3 行)

1. **「例外を投げれば、フレームワークが拾って統一形式のレスポンスに変える」は当たり前ではない。** 用意しているフレームワークと、していないフレームワークがある。
2. **一番の違いは「例外の型ごとの振り分けを誰が書くか」。** Spring と Laravel はフレームワークが型で振り分けてくれる。Hono と Nitro は集約点だけ用意され、`instanceof` の分岐は自分で書く。Next.js は集約点そのものが無い。
3. **Spring は起動時に「例外の型 → メソッド」の対応表を作る**ので、ハンドラを増やすときは `@ExceptionHandler` を付けたメソッドを足すだけでよい。既存のコードに手を入れなくて済む（＝開放/閉鎖原則に沿う）。

## 主軸: 集約点の作り方 — 4 つのグラデーション

| | 集約点 | 型ごとの振り分け | 未処理の例外 | 例外を投げる作法 |
|---|---|---|---|---|
| **Spring Boot** | `@RestControllerAdvice` クラス（複数可） | **FW**（起動時に型 → メソッドの対応表を構築） | FW 既定の 500 レスポンス | **これしかない** |
| **Laravel** | 単一の例外ハンドラ設定（1 箇所） | **FW**（クロージャの型ヒントで振り分け） | FW 既定の 500 レスポンス | **これが標準** |
| **Hono** | `app.onError()`（1 つの関数） | **自分**（`instanceof` を手書き） | `onError` に来る | `throw` も `return` も可 |
| **Nuxt (Nitro)** | `nitro.errorHandler` / `error` フック | **自分** | ハンドラに来る | `throw` も `return` も可 |
| **Next.js** | **無い** | 自分（各ハンドラの `try/catch`） | ランタイム既定の 500 | `return` が主流 |

上から下へ「フレームワークが面倒を見てくれる度合い」が下がる。Spring は表の一番上、つまり**最も手厚い側**にいる。

## ① Spring Boot — 型 → メソッドの対応表（このプロジェクトの実物）

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(ResourceNotFoundException e) {
        return ErrorResponse.of(e.getMessage());
    }

    @ExceptionHandler(ForbiddenOperationException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ErrorResponse handleForbidden(ForbiddenOperationException e) {
        return ErrorResponse.of(e.getMessage());
    }
}
```

起動時に Spring がこのクラスを見つけ、`@ExceptionHandler` の付いたメソッドから「例外の型 → 呼ぶメソッド」の対応表を作る。実行時に例外が投げられると、対応表を引いてリフレクションで該当メソッドを呼ぶ（→ 詳しい流れは `GlobalExceptionHandler.java` のコメント）。

**注目すべきは、分岐（`if` / `switch` / `instanceof`）を 1 つも書いていないこと。**型そのものが振り分けの鍵になっている。ハンドラを増やしたいときは新しいメソッドを足すだけで、既存のメソッドには一切触らない。

もう 1 つの特徴として、`@RestControllerAdvice` クラスは**複数置ける**。`basePackages` や `assignableTypes` を指定すれば「この Controller 群だけに効くハンドラ」も作れる。他の 3 つのフレームワークはいずれも集約点が 1 つなので、ここは Spring 独自の柔軟さ。

公式: <https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-exceptionhandler.html>

## ② Laravel — 思想は Spring とほぼ同じ。置き場所がバージョンで変わった

Laravel も「Service が例外を投げ、フレームワークが拾って変換する」思想で、**4 つの中で Spring に最も近い**。ただし**設定の置き場所が Laravel 11 で変わった**ので、両方を載せる。

### Laravel 10 以前 — `app/Exceptions/Handler.php`

```php
// app/Exceptions/Handler.php
namespace App\Exceptions;

use Illuminate\Foundation\Exceptions\Handler as ExceptionHandler;
use Illuminate\Http\Request;

class Handler extends ExceptionHandler
{
    public function register(): void
    {
        // 型ヒントで振り分ける。ここが Spring の @ExceptionHandler(X.class) に相当
        $this->renderable(function (ResourceNotFoundException $e, Request $request) {
            return response()->json(['message' => $e->getMessage()], 404);
        });

        $this->renderable(function (ForbiddenOperationException $e, Request $request) {
            return response()->json(['message' => $e->getMessage()], 403);
        });
    }
}
```

### Laravel 11 以降 — `bootstrap/app.php`

```php
// bootstrap/app.php
use Illuminate\Foundation\Configuration\Exceptions;

return Application::configure(basePath: dirname(__DIR__))
    ->withExceptions(function (Exceptions $exceptions) {
        $exceptions->render(function (ResourceNotFoundException $e, Request $request) {
            return response()->json(['message' => $e->getMessage()], 404);
        });
    })->create();
```

`Handler.php` というクラスが無くなり、アプリ全体の設定を集めた `bootstrap/app.php` に統合された。**書く中身（型ヒント付きクロージャを登録する）は同じ**なので、10 の書き方を知っていれば移行は難しくない。

### Spring との違い

| | Spring | Laravel |
|---|---|---|
| 振り分けの書き方 | メソッドに `@ExceptionHandler(X.class)` | クロージャの**引数の型ヒント** |
| 置き場所 | 任意のクラス（複数可） | 1 箇所（10: `Handler.php` / 11: `bootstrap/app.php`） |
| 追加の単位 | メソッド | クロージャ |

どちらも「型で振り分ける」点は同じ。Laravel は 1 つのファイルにクロージャを積んでいく形なので、ハンドラが増えるとそのファイルが縦に伸びる。Spring はクラスを分けられる。

**Laravel が最初から用意している自動変換**も押さえておくとよい。`findOrFail()` が投げる `ModelNotFoundException` は自動で 404 になり、`ValidationException` は自動で 422 になる。Spring では `ResourceNotFoundException` を自作したが、Laravel では `findOrFail()` を呼ぶだけで同じ結果になる。

公式: <https://laravel.com/docs/11.x/errors> / <https://laravel.com/docs/10.x/errors>

## ③ Hono — 集約点は 1 つ。振り分けは自分で書く

```ts
import { Hono } from 'hono'
import { HTTPException } from 'hono/http-exception'

const app = new Hono()

// 投げる側
app.get('/api/posts/:id', async (c) => {
  const post = await findPost(c.req.param('id'))
  if (!post) throw new HTTPException(404, { message: '投稿が見つかりません' })
  return c.json(post)
})

// 受ける側。集約点は「1 つの関数」
app.onError((err, c) => {
  if (err instanceof HTTPException) {
    return c.json({ message: err.message, fieldErrors: null }, err.status)
  }
  if (err instanceof ForbiddenOperationError) {
    return c.json({ message: err.message, fieldErrors: null }, 403)
  }
  return c.json({ message: 'サーバー内部でエラーが発生しました', fieldErrors: null }, 500)
})
```

集約点が 1 つある点は Spring / Laravel と同じ。**違うのは `instanceof` の連鎖を自分で書くこと。**型ごとの振り分けをフレームワークが肩代わりしてくれない。

この差は、ハンドラが増えたときに効いてくる。Spring はメソッドを足すだけで既存に触れないが、Hono は `onError` という**1 つの関数の中身を編集し続ける**ことになる。10 種類の例外を扱うなら 10 本の `if` が 1 関数に並ぶ。

なお TypeScript の `instanceof` はクラスにしか使えないので、独自の例外を作るときは `class ForbiddenOperationError extends Error {}` のようにクラスとして定義する必要がある。

公式: <https://hono.dev/docs/api/exception>

## ④ Nuxt (Nitro / h3) — Hono に近いが、フックの役割分担に注意

Nuxt のサーバーエンジン **Nitro**（内部で **h3** を使う）も、集約点を 1 つ持つ形。

```ts
// server/api/posts/[id].get.ts
export default defineEventHandler(async (event) => {
  const post = await findPost(getRouterParam(event, 'id'))
  if (!post) {
    throw createError({
      statusCode: 404,
      statusMessage: 'Not Found',
      data: { message: '投稿が見つかりません' }, // data に入れた中身がレスポンスに載る
    })
  }
  return post
})
```

**ここが引っかかりやすいポイント。**Nitro には 2 種類の仕組みがあり、役割が違う。

| 仕組み | 用途 | レスポンスの中身を変えられるか |
|---|---|---|
| `nitroApp.hooks.hook('error', ...)`（Nitro プラグイン） | エラーの**通報**（ログ・Sentry 送信など） | **変えられない** |
| `nitro.errorHandler` に指定したハンドラ | エラーレスポンスの**整形** | 変えられる |

「エラーフックがあるから形を変えられるはず」と思って `hooks.hook('error')` を書いても、レスポンスは既定のままで変わらない。整形したいなら `nitro.errorHandler` の方を使う。

Nitro の既定のエラー JSON は `{ "url": ..., "statusCode": 404, "statusMessage": ..., "message": ..., "data": ... }` という形で、`ErrorResponse`（`message` / `fieldErrors`）とはキーが違う。

**このプロジェクトでは上記のコードは書いていない。**`frontend/` に `server/` ディレクトリが無く、Nitro は `nuxt.config.ts` の `devProxy` として `/api` を `backend:8080` へ転送する役目だけを担っている。エラーレスポンスを作るのは常に Spring Boot 側。

公式: <https://nuxt.com/docs/getting-started/error-handling>

## ⑤ Next.js — 集約点が無い

App Router の Route Handler には、**エラーを一括で整形するフックが存在しない**。

```ts
// app/api/posts/[id]/route.ts
export async function GET(req: Request, { params }: { params: Promise<{ id: string }> }) {
  try {
    const post = await findPost((await params).id)
    if (!post) {
      return NextResponse.json({ message: '投稿が見つかりません', fieldErrors: null }, { status: 404 })
    }
    return NextResponse.json(post)
  } catch (e) {
    return NextResponse.json({ message: 'サーバー内部でエラーが発生しました', fieldErrors: null }, { status: 500 })
  }
}
```

**この `try/catch` を、エンドポイントの数だけ書くことになる。**これは `GlobalExceptionHandler` が無かった場合の Spring とちょうど同じ状況で、集約しないと何が起きるか（同じコードの散在、形式のばらつき）を実感できる。

### 誤解しやすい 2 つの仕組み

- **`error.tsx` / `global-error.tsx`** — これは React の Error Boundary で、**画面が壊れたときに代わりに表示する UI**。API レスポンスの JSON を整形する仕組みではない。名前が似ているので混同しやすい。
- **`instrumentation.ts` の `onRequestError`** — エラーを外部サービスへ**通報**するためのフック。Nitro の `hooks.hook('error')` と同じ立ち位置で、レスポンスの中身は変えられない。

### 集約したいなら自分で作る

実務では、高階関数（関数を受け取って関数を返す関数）でラップする形が使われる。

```ts
// lib/with-error-handler.ts
export function withErrorHandler(handler: Handler): Handler {
  return async (req, ctx) => {
    try {
      return await handler(req, ctx)
    } catch (e) {
      if (e instanceof ResourceNotFoundError) {
        return NextResponse.json({ message: e.message, fieldErrors: null }, { status: 404 })
      }
      if (e instanceof ForbiddenOperationError) {
        return NextResponse.json({ message: e.message, fieldErrors: null }, { status: 403 })
      }
      return NextResponse.json({ message: 'サーバー内部でエラーが発生しました' }, { status: 500 })
    }
  }
}

// app/api/posts/[id]/route.ts
export const GET = withErrorHandler(async (req, ctx) => { /* ... */ })
```

これは **`@RestControllerAdvice` を手作りしている**のと同じこと。ただしフレームワークの機能ではないので、全エンドポイントに `withErrorHandler(...)` を書き忘れないよう自分たちで守る必要がある。Spring は書き忘れようがない（集約点がフレームワーク側にあるため）。

公式: <https://nextjs.org/docs/app/getting-started/error-handling>

## バリデーションエラーの扱い — ここが実務で一番差が出る

「入力エラーを項目ごとに返す」処理は、フレームワークによって**ステータスコードもボディの形も違う**。

| | 検証の起動 | 例外 | ステータス | ボディ |
|---|---|---|---|---|
| **Spring** | `@Valid @RequestBody` | `MethodArgumentNotValidException` | **400** | `{"message":"...","fieldErrors":{"body":"本文を入力してください"}}` |
| **Laravel** | FormRequest / `$request->validate()` | `ValidationException` | **422** | `{"message":"...","errors":{"body":["必須です","280文字以内で"]}}` |
| **Hono** | `@hono/zod-validator` の hook | （自分で扱う） | 自分で決める | 自分で決める |
| **Nuxt** | `readValidatedBody(event, schema)` | `ZodError` など | 自分で決める | 自分で決める |
| **Next.js** | 手書きの `schema.parse()` | `ZodError` など | 自分で決める | 自分で決める |

**注意すべき違いが 2 つある。**

1. **ステータスコードが違う。** Spring のこの実装は **400 Bad Request**、Laravel は **422 Unprocessable Entity**。422 は「文法としては正しい JSON だが、内容が処理できない」という意味で、バリデーションエラーにはより厳密な選択。Laravel を知っている人が Spring の API を叩くと 400 が返って戸惑うので、フロント側の分岐を書くときは注意する。
2. **1 項目あたりのメッセージ数が違う。** Laravel の `errors` は**配列**で、1 つの項目に複数のメッセージが入りうる。このプロジェクトは `putIfAbsent` を使って**最初の 1 件だけ**を残すため `Record<string, string>` になる（→ [validation-layers.md](../../validation-layers.md)）。フロントの型定義を Laravel の感覚で `string[]` にすると噛み合わない。

Hono / Nuxt / Next.js は、バリデーションライブラリ（zod など）が投げるエラーを**自分で整形する**のが基本。Spring と Laravel はここまでフレームワークが面倒を見てくれる。

## 後半の軸: エラーを「投げる」か「返す」か

前半は集約点の話だったが、その手前に**「そもそもエラーをどう表現するか」**という、より深い分かれ目がある。

### Java — 例外が言語の一級市民

Java には**検査例外（checked exception）**という仕組みがあり、「このメソッドは `IOException` を投げうる」ことをコンパイラが強制的にチェックする。つまり**例外は言語設計に組み込まれた正式なエラー表現**。だから Spring も「例外を投げる」以外の作法を用意していない。

このプロジェクトの `ResourceNotFoundException` が `RuntimeException`（非検査例外）を継承しているのは、検査例外にすると投げる可能性のあるメソッドすべてに `throws` 宣言が必要になり、Service 層のシグネチャが汚れるため。「Java には検査／非検査の 2 種類があり、業務エラーには非検査を使うのが今の主流」と覚えておくとよい。

### TypeScript — 投げても返してもよい

TypeScript では `throw` が**型に現れない**。関数の戻り値の型を見ても「この関数が何を投げるか」は分からず、コンパイラも警告しない。そのため次の 2 通りが両方とも普通に使われる。

```ts
// 投げる
throw new HTTPException(404, { message: '...' })

// 返す
return c.json({ message: '...' }, 404)
```

「返す」方は**エラーが型として見える**（戻り値の型に含まれる）ので、型安全性を重視して意図的にこちらを選ぶ設計もある。Hono の `c.json(..., 404)` や Next.js の `NextResponse.json(..., { status: 404 })` が典型。

### 他の言語での位置づけ

この「投げる vs 返す」は言語設計レベルの対立で、名前が付いている。

- **例外方式（Java / PHP / Python / C#）** — 異常系を通常のフローから切り離せる。呼び出し側が握りつぶすと気づきにくい弱点がある
- **エラーを値として返す方式（Go の `error` 戻り値 / Rust の `Result<T, E>`）** — エラーの存在が型に現れ、無視するとコンパイルエラーや警告になる。そのぶん記述量は増える

Spring が「集約点」を用意できるのは、**例外が呼び出し階層を突き抜けて上まで飛んでいく**性質を持つから。Service で投げた例外が Controller を素通りしてフレームワークまで届くので、そこで一括して捕まえられる。「返す」方式では値が呼び出し元に一段ずつ返るだけなので、同じ仕組みは作りにくい。**Hono や Next.js の集約が弱いのは、TypeScript のエラー表現が 2 通りあることと無関係ではない。**

## 参考: 実は共通規格がある — RFC 9457 Problem Details

ここまで見た 4 つは、いずれも**独自の形式**でエラーを返していた。実は「HTTP API のエラーレスポンスはこの形にしよう」という標準規格が存在する。**RFC 9457（旧 RFC 7807）Problem Details for HTTP APIs** で、`Content-Type: application/problem+json` として次のキーを返す。

```json
{
  "type": "https://example.com/probs/not-found",
  "title": "Not Found",
  "status": 404,
  "detail": "投稿が見つかりません: id=999",
  "instance": "/api/posts/999"
}
```

面白いのは、**Spring Boot はこれを最初から持っている**こと。`ProblemDetail` クラスがあり、`application.yml` に次を書くと標準の例外が自動でこの形式になる。

```yaml
spring:
  mvc:
    problemdetails:
      enabled: true
```

**このプロジェクトはこの設定を書いていない**（既定は無効）ため、独自の `ErrorResponse`（`message` / `fieldErrors`）を使っている。Laravel・Hono・Next.js も既定では RFC 9457 形式を返さない。つまり「共通規格はあるが、どのフレームワークも既定では使っていない」というのが現状。

独自形式には独自形式の良さもある。`{"message": "...", "fieldErrors": {...}}` は RFC 9457 より短く、フロントが読む場所が明快で、学習用の題材として素直。一方で複数のサービスをまたぐ大規模な API では、規格に揃えておくとクライアント側の共通処理が書ける。**どちらが正しいという話ではなくトレードオフ。**

規格: <https://www.rfc-editor.org/rfc/rfc9457.html>

## このプロジェクトでの一往復 — Spring が出し、Nuxt が受け取る

ここは実物のコードで追える部分。`GlobalExceptionHandler` が作った JSON を、フロントが実際にどう読んでいるか。

```
PostService が ResourceNotFoundException を投げる
   ↓
GlobalExceptionHandler.handleNotFound が捕捉
   ↓
HTTP 404 + {"message":"投稿が見つかりません: id=999","fieldErrors":null}
   ↓
Nuxt の $fetch(ofetch) が「2xx 以外」を検知して FetchError を throw
   ↓
Form.vue の catch が e.data(= 上の JSON)から message / fieldErrors を読む
```

`usePosts.ts` は `$fetch` を薄く包んでいるだけで、エラー処理はしていない。実際に受け取っているのは [Form.vue](../../../../frontend/app/components/post/Form.vue) の `catch` 節で、`data?.fieldErrors` の最初の値、無ければ `data?.message` を画面に出している。

ここで **`$fetch` は 2xx 以外を「投げる」**点に注目したい。ブラウザ標準の `fetch` は 404 でも 500 でも例外を投げず（`res.ok` を自分で見る必要がある）、`$fetch` は投げる。**同じ「fetch」という名前でもエラーの表し方が逆**なので、`try/catch` を書き忘れると未処理の例外になる。前章の「投げる vs 返す」がフロント側にも顔を出している例。

## つまずきポイント

- **Laravel 脳で 422 を期待しない。** このプロジェクトのバリデーションエラーは **400**。Laravel の既定は 422 で、ステータスコードが違う。
- **`fieldErrors` を `string[]` だと思わない。** Laravel の `errors` は配列だが、このプロジェクトは `putIfAbsent` により 1 項目 1 メッセージ（`Record<string, string>`）。
- **Next.js の `error.tsx` を「API のエラー整形」だと思わない。** あれは画面の Error Boundary であって、JSON レスポンスの形には関与しない。
- **Nitro の `hooks.hook('error')` でレスポンスは変えられない。** あれは通報用。整形は `nitro.errorHandler` の方。
- **Hono / Next.js では「何も書かなければ FW 既定の 500」が返る。** 統一形式にしたいなら自分で全経路を押さえる必要がある。Spring も同じで、`@ExceptionHandler` に載っていない例外は Spring 既定の形式（`{"timestamp","status","error","path"}`）になり `ErrorResponse` の形にならない。
- **`@RestControllerAdvice` のハンドラを「参照 0 件だから未使用」と判断しない。** 呼び出しはリフレクション経由なので、ソース上に呼び出し行が存在しない（→ `GlobalExceptionHandler.java` のコメント）。

## 用語集

- **集約点（centralized error handler）** — 各所で投げられた例外を 1 箇所で受け止め、レスポンスへ変換する仕組み。Spring の `@RestControllerAdvice`、Laravel の例外ハンドラ設定、Hono の `onError` が該当
- **`@RestControllerAdvice`** — 全 Controller に横断的に効く追加設定を書くクラスに付ける Spring のアノテーション。Advice は AOP（アスペクト指向）の用語で「本来の処理の外側から口を出すもの」の意
- **`@ExceptionHandler(X.class)`** — 「例外の型 X が来たらこのメソッド」を結びつける Spring のアノテーション
- **検査例外 / 非検査例外（checked / unchecked exception）** — Java の区分。検査例外はコンパイラが `throws` 宣言または `catch` を強制する。`RuntimeException` を継承したものは非検査で強制されない。業務エラーには非検査を使うのが今の主流
- **`renderable()` / `$exceptions->render()`** — Laravel で「この例外型はこう返す」を登録するメソッド。10 以前は `Handler.php` の `register()` 内、11 以降は `bootstrap/app.php` の `withExceptions()` 内に書く
- **`HTTPException`** — Hono が用意する HTTP エラー用の例外クラス。`throw new HTTPException(404, {...})` の形で使う
- **`createError()`** — Nuxt / Nitro（h3）のエラー生成関数。`statusCode` / `statusMessage` / `data` を持つエラーを作る
- **Nitro / h3** — Nuxt のサーバーエンジンと、その土台の HTTP フレームワーク。このプロジェクトでは開発時の devProxy にしか使っていない
- **Error Boundary** — React で、子コンポーネントが投げたエラーを捕まえて代替 UI を表示する仕組み。Next.js の `error.tsx` がこれ。API レスポンスとは無関係
- **高階関数（higher-order function）** — 関数を受け取る、または関数を返す関数。Next.js で集約点を自作する `withErrorHandler` がこの形（→ [functions-as-values.md](../../functions-as-values.md)）
- **RFC 9457 / Problem Details** — HTTP API のエラーレスポンス形式の標準規格。`application/problem+json` で `type` / `title` / `status` / `detail` / `instance` を返す。旧 RFC 7807
- **`ProblemDetail`** — RFC 9457 を実装した Spring の組み込みクラス。`spring.mvc.problemdetails.enabled=true` で有効になる（このプロジェクトは未使用）
- **422 Unprocessable Entity** — 「構文は正しいが内容を処理できない」を表すステータス。Laravel はバリデーションエラーにこれを使う。Spring のこの実装は 400 を使う
- **`$fetch` / ofetch** — Nuxt の HTTP クライアント。ブラウザ標準の `fetch` と違い、2xx 以外のとき例外を投げる。レスポンスボディは `err.data` に入る

## 関連

- `GlobalExceptionHandler` の 1 行ずつの読み方 → [GlobalExceptionHandler.java](../../../../backend/src/main/java/com/example/app/common/exception/GlobalExceptionHandler.java) のコメント
- なぜバリデーションのハンドラが 2 つ必要か（ボディとクエリパラメータで例外の型が違う） → [validation-layers.md](../../validation-layers.md)
- このプロジェクトのエラーレスポンスの形と対応表 → [docs/api/README.md](../../../api/README.md)
- Spring と Laravel の設計思想の違い（もう 1 つの比較軸） → [repository-and-entity-vs-laravel-model.md](./repository-and-entity-vs-laravel-model.md)
