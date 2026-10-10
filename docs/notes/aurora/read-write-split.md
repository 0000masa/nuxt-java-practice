# Aurora の Writer と Reader を使い分ける — 読み書き分離と「書いた直後に読む」問題

Aurora に Reader を足しただけでは、アプリは Reader を使わない。
このノートは、**アプリがどうやって Writer と Reader を使い分けるのか**と、**使い分けると何が起きるのか**を、
このリポジトリのコードに沿って追う。

決定 → [ADR-0016](../../adr/0016-aurora-provisioned-with-read-write-split.md)
設計 → [フェーズ19 設計書](../../superpowers/specs/2026-09-13-phase19-aurora-design.md)
続き → [connection-pool-and-failover.md](connection-pool-and-failover.md)(接続プールの大きさとフェイルオーバー)

## まず結論(5 行)

- **Aurora もリードレプリカも、SQL を見て自動で振り分けてはくれない。** 用意されるのは行き先の違うエンドポイントだけで、どれに繋ぐかはアプリが決める
- Java では **SQL の文面ではなく、トランザクション単位で決める**のが普通。目印は `@Transactional(readOnly = true)`
- **接続先はトランザクションの開始時に決まり、途中で変わらない。** 同じトランザクションで INSERT してから SELECT すれば、両方 Writer に行く
- 問題になるのは**書き込みのリクエストが終わった後、別のリクエストで読む**とき。Reader はわずかに遅れるので、書いたはずのデータが見えないことがある
- このアプリは、**認証の読み取り 2 か所だけ Writer に固定**し、残りの読み取りを Reader に送っている

## 1. Aurora のエンドポイント

| エンドポイント | つながる先 | このリポジトリでの使い道 |
|---|---|---|
| クラスタエンドポイント | 常に Writer。フェイルオーバーすると新しい Writer に向き直る(DNS) | `DB_HOST`(書き込みと、読み取り専用でないトランザクション) |
| Reader エンドポイント | Reader のどれか(DNS で振り分け)。**書き込みはエラーになる** | `DB_READER_HOST`(読み取り専用トランザクション) |
| インスタンスエンドポイント | その 1 台に固定 | 使っていない |

どれも DNS 名なので、「どの IP に繋がるか」は DNS を引いた瞬間に決まる。
長く生きる接続プールとの相性の話は [connection-pool-and-failover.md](connection-pool-and-failover.md)。

## 2. 振り分けるのは誰か

| やり方 | 何を見て振り分けるか | 注意点 |
|---|---|---|
| **アプリ(Spring)** | トランザクションが `readOnly` か | このリポジトリはこれ |
| ドライバ(AWS Advanced JDBC Wrapper) | 接続に `setReadOnly(true)` が呼ばれたか | 目印はアプリと同じ `readOnly`。フェイルオーバーも速くなる |
| プロキシ(ProxySQL など) | SQL の文面 | `SELECT ... FOR UPDATE` を Reader に送る、トランザクションの途中で行き先が変わる、などの落とし穴がある |

RDS Proxy にも読み取り専用のエンドポイントはあるが、**振り分けてくれるわけではなく、行き先を用意するだけ**である。

## 3. このリポジトリの振り分け — `LazyConnectionDataSourceProxy`

コード → `backend/src/main/java/com/example/app/config/DataSourceConfig.java`

```java
LazyConnectionDataSourceProxy proxy = new LazyConnectionDataSourceProxy(writer);
proxy.setReadOnlyDataSource(reader);   // Spring Framework 6.1.2 から
```

仕組みは「**接続を取るのを最初の SQL まで遅らせる**」ことにある。

```
@Transactional(readOnly = true) のメソッドに入る
  → Spring が接続に setReadOnly(true) を呼ぶ
      → プロキシは本物の接続をまだ取らず、「readOnly = true」と控えるだけ
最初の SQL を流す
  → プロキシが控えを見て、true なら reader のプール、false なら writer のプールから接続を借りる
```

遅らせないと、`readOnly` が決まる前に接続を取ってしまい、振り分けようがない。

### Reader のプールは `read-only: true` にしておく

`setReadOnlyDataSource` を使うと、**Reader から借りた接続には `setReadOnly(true)` が呼ばれない**
(「プールの既定として設定済みのはず」という前提の作り。Spring のソースで確認)。
本番の Aurora の Reader はインスタンス自体が書き込みを拒否するので困らないが、
**開発では Reader も同じ MySQL を指すので、付けないと書き込みが素通りする。**
そこで `application.yml` の `app.datasource.reader.hikari.read-only: true` を付けている。

### 設計時に考えていた `AbstractRoutingDataSource` との違い

昔からの定番はこちらで、既存の現場のコードで見かけるのもこちらが多い。

```java
class RoutingDataSource extends AbstractRoutingDataSource {
  protected Object determineCurrentLookupKey() {
    return TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? "reader" : "writer";
  }
}
// これを LazyConnectionDataSourceProxy で包む(包まないと判定より先に接続を取られる)
```

やっていることは同じで、`setReadOnlyDataSource` は**ルーティング用のクラスを自作しなくて済む**公式の機能。

## 4. 接続先はトランザクション単位で決まる

```java
@Transactional                       // readOnly なし → Writer
public PostResponse create(...) {
    categoryRepository.findById(...) // 読み取りだが、同じトランザクションなので Writer
    userRepository.findById(...)     // 同上
    postRepository.save(...)         // Writer
}
```

**注意: Spring Data のリポジトリは、自分でも `@Transactional(readOnly = true)` を持っている**
(`SimpleJpaRepository` の検索系メソッド)。サービスのトランザクションの中から呼べばそちらに参加するので問題ないが、
**トランザクションの外からリポジトリを直接呼ぶと、その呼び出しは Reader に行く。**
このリポジトリでは、リポジトリはすべて `@Transactional` の付いたサービスから呼んでいる(実装時に確認)。

## 5. 書いた直後に読む問題

Aurora の Reader は Writer とストレージを共有しているが、遅延はゼロではない(AWS の説明では通常 100 ミリ秒未満)。

このアプリで起きうる場所を洗い出した結果:

| 流れ | 書き込み(Writer) | 直後の読み取り | Reader で読むと |
|---|---|---|---|
| 初めての Google ログイン | ユーザーを INSERT | リダイレクト → `/api/auth/me` | **未ログイン扱いになる** |
| メール確認 → ログイン | 確認済みフラグを UPDATE | `loadUserByUsername` | 「メール未確認」で弾かれる |
| パスワードリセット → ログイン | パスワードを UPDATE | `loadUserByUsername` | 新しいパスワードで入れない |
| 投稿の作成・削除 | INSERT / DELETE | なし(画面はレスポンスの値を使う) | 問題なし |

実務でよく使う対策は 3 つ。

1. **書いた結果をレスポンスで返し、直後に読み直さない**(投稿の作成がこの形)
2. **直後に読む可能性のある読み取りだけ Writer に固定する**(このアプリは認証の 2 か所をこれにした)
3. 書き込んだユーザーのリクエストだけ、数秒間 Writer に固定する(セッションに印を付ける。汎用的だが仕組みが大きい)

### 認証の読み取りを Writer に固定しても困らない理由

Reader に逃がす価値があるのは **「回数 × 1 回の重さ」の合計が大きい読み取り**である。

- 認証の読み取りは主キーかユニークキーで 1 行引くだけ(1 ミリ秒未満)
- しかも**リクエストごとには走らない**。ログイン状態は Redis のセッションにあり、DB を見るフィルタは無い。
  `loadUserByUsername` はログイン 1 回につき 1 回、`/api/auth/me` はページを開いたときとログイン直後だけ
- Writer を苦しめるのは大量の行を走査する読み取り(タイムライン、検索ラボ、index 実験)で、それは Reader に送っている

コードには `readOnly = true` を外した理由をコメントで書いてある。**書かないと、後から読んだ人が「付け忘れ」と思って付け直す。**

## 6. セッションを DB に置いていたら、Reader に逃がすべきか

**逃がさない。** セッションは Reader に最も向かない読み取りである。

1. **遅延がそのまま「ログアウト」になる。** ログイン直後にセッションの行を INSERT し、次のリクエストがすぐ読みに来る
2. **遅延がそのまま「セキュリティの穴」になる。** ログアウトやパスワード変更で消したセッションが、Reader が追いつくまで通ってしまう
3. **どのみち毎リクエストで書く。** Spring Session JDBC はリクエストごとに最終アクセス時刻を UPDATE する

セッションの読み書きが DB の負荷になったときの答えは、Reader ではなく **DB の外(Redis)に出すこと**。
このリポジトリがフェーズ18 でやったのがそれ(→ [ADR-0015](../../adr/0015-session-store-on-redis.md))。

## 7. ローカルでどう確かめているか

ローカルは会社の開発環境と同じく **MySQL 1 台**で、Writer と Reader に同じ接続情報を渡している
(`DB_READER_HOST` を設定しなければ `DB_HOST` を使う)。Aurora 用のローカルイメージは存在しないし、
ローカルに binlog のレプリケーションを組むチームも少数派である。

それでも振り分けの誤りには気づける。Reader のプールが `read-only: true` なので、
読み取り専用トランザクションの中で書こうとすると、**Connector/J がサーバに送る前に**止める。

```
Connection is read-only. Queries leading to data modification are not allowed.
```

(設計時は MySQL サーバの `Cannot execute statement in a READ ONLY transaction` で止まると見ていたが、実測では手前のドライバだった)

`ReadWriteRoutingTest` がこれと、「どちらのプールから接続を借りたか」(Hikari の使用中の接続数)を確かめている。
**遅延そのものはローカルでは再現できない。** そこは設計で避ける(§5)か、stg で確かめる。

## 8. 実務ではどうしているか

割合を示す信頼できる統計は無いので、傾向として。

- `AbstractRoutingDataSource` は昔からの定番で、既存のプロジェクトほどこちら
- AWS Advanced JDBC Wrapper は 2023 年ごろから広まった。読み書き分離より**フェイルオーバーを速くする目的**で入れることが多い
- **読み書きを分離しない現場も多い。** アプリは Writer だけに繋ぎ、Reader は分析・バッチ・人間の調査用、あるいはフェイルオーバー先
- Writer 用と Reader 用で DB ユーザーを分けるのは少数派。Reader インスタンス自体が書き込みを拒否するため。
  「読み取り専用ユーザー」がよく作られるのは、人間や BI ツール用
