# Gradle の基礎 — Wrapper、./gradlew、キャッシュの置き場所

Gradle という道具そのものについての学習メモ。`docker/backend/Dockerfile` の `CMD ["sh", "./gradlew", "bootRun"]` と、`docker-compose.yml` の `gradle-cache:/root/.gradle` の読み解き。PHP / Node 出身者向け。

## Gradle とは — 「npm 一式」の Java 版

PHP / Node の世界では複数ツールに分かれている役割が、Java では Gradle 1 つに束ねられています。

| 役割 | Node | PHP（Composer） | Java（Gradle） |
|---|---|---|---|
| 依存ライブラリの管理 | npm + package.json | Composer + composer.json | Gradle + build.gradle |
| タスクの実行（dev 起動、テスト等） | npm scripts | composer scripts（Laravel 実務では artisan が主役） | Gradle タスク（`bootRun`、`test`...） |
| 成果物のビルド | vite / webpack など | **なし**（PHP はコンパイル不要でそのまま実行） | Gradle 自身（コンパイル〜Jar 作成） |
| 依存の保存先 | node_modules（プロジェクト内） | vendor（プロジェクト内） | `~/.gradle/caches`（ホーム。全プロジェクト共有） |

PHP 列の「ずれ」は、そのまま Java を理解するヒントになります。PHP はコンパイル不要なので、依存を入れる Composer とフレームワークの便利コマンド（artisan）があれば回りますが、Java は「コンパイル → jar に梱包」というビルド工程が**必須**——だからビルドツールが主役に座り、依存管理もタスク実行も 1 つに束ねる構図になります。保存先が Java だけプロジェクト外（ホーム共有）なのも思想の違いで、詳しくは後述の[「置き場所の思想」](#node_modules--vendor-との違い--置き場所の思想)を参照。

感覚としては `./gradlew bootRun` ≒ `npm run dev` ≒ `php artisan serve`、`./gradlew build` ≒ `npm run build` です。なお Java 界にはもう 1 つ **Maven** という老舗ビルドツールがあり、Gradle か Maven かの二択が npm か yarn かの選択に似た立ち位置です。Spring Initializr の最初の選択肢（`type=gradle-project`）はまさにこれを選んでいました（→ [spring-initializr.md](./spring-initializr.md)）。

## Gradle Wrapper — 本体を自動調達する「案内人」

普通に考えると Gradle を使うには先に Gradle のインストールが必要ですが、プロジェクトに同梱された `gradlew` というスクリプト（**Gradle Wrapper**）がその世話を全部やります。

1. `backend/gradle/wrapper/gradle-wrapper.properties` に書かれた「このプロジェクトが使うべき Gradle のバージョン」（`distributionUrl`）を読む
2. 手元になければ**そのバージョンの Gradle 本体をダウンロード**する
3. それを使って指定タスクを実行する

利点は 2 つ:

- **インストール不要** — backend コンテナに Gradle を入れていないのに動くのはこのため（イメージは JDK だけ）
- **バージョン統一** — チーム・CI 全員が同じバージョンで揃い、「私の Gradle は古くて動かない」事故がなくなる

Node で例えるなら「リポジトリに小さな nvm + 実行スクリプトが同梱されている」ような状態です。

## `./gradlew` はどこを指しているか

`./gradlew` はディレクトリではなく**ファイルへのパス**です。`.` = 「現在のディレクトリ」なので、「**今いるディレクトリにある gradlew というファイル**」の意味。シェルは裸の名前（`gradlew`）だと PATH という登録済みの場所しか探さないため、「そこにあるこのファイルだよ」と `./` で明示します。

backend コンテナでは `WORKDIR /app` なので `./gradlew` = `/app/gradlew`。そして `/app` にはホストの `backend/` がバインドマウントされているので、**実体はリポジトリの `backend/gradlew`** です。（CMD で `sh ./gradlew` と sh 経由にしている理由は [docker-dev-containers.md](./docker-dev-containers.md) を参照）

## `~/.gradle` キャッシュ — 何が、どこに貯まるのか

Gradle は作業に必要なものを**ユーザーのホームディレクトリ配下の `.gradle`** に貯めます。中身は大きく 2 種類:

1. **Gradle 本体** — Wrapper がダウンロードしたもの（`wrapper/dists/`）
2. **依存ライブラリの jar** — Maven Central から取得したもの（`caches/` 配下）

### コンテナ内では「コンテナの ~/.gradle」に配置される

その理解で正しいです。backend コンテナの実行ユーザーは root で、root のホームディレクトリは `/root`。つまりコンテナ内の `~/.gradle` = `/root/.gradle` であり、`docker-compose.yml` の `gradle-cache:/root/.gradle` は**まさにその場所に named volume を差し込んでいます**。ホスト側に対応する `~/.gradle` は存在せず（ホストで Gradle を動かさない限り作られない）、named volume の実データは Docker が管理する領域に保存されます。

### node_modules / vendor との違い — 「置き場所の思想」

役割（ダウンロードした依存の置き場）は近いですが、思想が違います。

| | node_modules / vendor | ~/.gradle のキャッシュ |
|---|---|---|
| 置き場所 | **プロジェクトの中**（プロジェクトごとにコピーを持つ） | **ユーザーのホーム**（全プロジェクトで共有） |
| 中身 | 依存ライブラリのみ | 依存ライブラリ + **Gradle 本体** |
| プロジェクト側の参照 | プロジェクト内のフォルダを直接読む | ビルド時にキャッシュ内の jar をパスで参照 |

Java プロジェクトには node_modules に相当する「プロジェクト内の依存フォルダ」が**存在しません**。10 個のプロジェクトが同じライブラリを使うなら、キャッシュに 1 部だけ置いてみんなで参照します（pnpm の共有ストア方式に近い考え方）。

## なぜバインドマウントではなく named volume なのか

frontend の node_modules と比べると設計の違いが見えます。

**node_modules が「バインドマウントで扱われている」のは、選んだ結果というより巻き込まれた結果**です。node_modules はプロジェクトの中（`frontend/node_modules`）にあるので、`./frontend:/app` でプロジェクトごとマウントすれば自動的に共有されます。しかもホスト側でも npm や IDE（型補完など）がそのフォルダを必要とするので、ホストに実体があることに意味があります。

一方 `~/.gradle` は**プロジェクトの外**にあるので、`./backend:/app` のマウントには含まれません。そして何もしなければコンテナのファイルシステムは使い捨てなので、コンテナを作り直すたびに Gradle 本体も依存も全部ダウンロードし直しになります。永続化の手段は 2 つ:

1. **ホストのフォルダを割り当てる（バインドマウント）** — 例: `~/.gradle-docker:/root/.gradle`
2. **named volume を割り当てる** — このリポジトリの選択

named volume が選ばれる理由:

- **ホスト側に実体を見せる必要がない。** Gradle はコンテナ内でしか動かさないので、ホストの誰もこのキャッシュを読まない（node_modules との決定的な違い）
- **ホストを汚さない。** バインドマウントだと root 所有のファイル群がホストのホームに現れ、パスの取り決めも必要になる
- **「消えては困るが、消えても再生成できる」性質に合う。** named volume は `docker compose down` で残り、`down -v` で意図的に捨てられる。キャッシュの扱いとしてちょうどいい

つまり「**ホストと共有したいものはバインドマウント、コンテナだけの永続データは named volume**」という使い分けで、mysql-data / minio-data と同じ側に分類された、ということです。

### では `./backend:/` にすれば `.gradle` もマウント圏内に入る?

「`/root/.gradle` がマウントの外にあるなら、`./backend:/` とコンテナのルートごとマウントすれば圏内に入るのでは?」という発想は筋が良いのですが、**不可能です**。理由はマウントの性質にあります。

マウントの動きは「フォルダの中身をコピーして混ぜる」ではなく、**「その場所の上にホストのフォルダを重ね掛けする」**です。重ねた下にあったものは消えはしませんが**見えなくなります**（布をかぶせるイメージ。**マスキング**と呼ばれる性質）。コンテナの `/` にはイメージの中身そのもの——OS のファイル一式（`/bin/sh` など）、JDK、`/root`——が広がっているので、仮に `/` へ重ねられたら:

```
/ に backend/ を重ね掛け
→ /bin も /usr(JDK)も /root も全部 backend の中身で覆い隠される
→ sh が見つからない、Java も無い → CMD の実行すら不可能
```

`.gradle` を圏内に入れるどころか、**gradlew を動かす道具一式を自分で隠してしまう**わけです。この事故が起きないよう、Docker はマウント先 `/` の指定自体をエラーにしています。

そもそも `/` ごと包む必要はなく、マウントは**任意のパスに個別に差し込める**ので狙い撃ちすればよい（前述の手段 1 `./.gradle-cache:/root/.gradle` がそれ）。もう 1 つ、**キャッシュの側を引っ越させる**別解もあります:

```yaml
    environment:
      GRADLE_USER_HOME: /app/.gradle-home   # ~/.gradle の代わりにここを使え、という指示
```

これでキャッシュが `/app` の中 = 既存マウントの圏内に生まれます。「node_modules がプロジェクトの中にあるから巻き込まれてマウントされる」構図を Gradle で人工的に再現するやり方です。（どの方法も技術的には可能で、それでも named volume が選ばれる理由は前述のとおり）

なおマスキングは部分マウントでも起きます。イメージ内に `/app` へ COPY 済みのファイルがある状態で `./backend:/app` をマウントすると COPY した中身は隠れる——「Dockerfile で作ったはずのファイルが無い!」の定番原因です。`/` は拒否されますが `/usr` などの重要ディレクトリは拒否されないので、マウント先は「そこに何が既にあるか」を意識して選ぶこと。

## 実際のプロジェクトではどうしているか — 「ホストに見せる」の実務調査

.gradle / node_modules / vendor をホストから見えるようにするかは、エコシステムごとに定番が違います。

| | ホストから見える? | 実務でよくある形 |
|---|---|---|
| **vendor（Laravel）** | **見えるのが主流** | Laravel 公式の Sail がプロジェクト丸ごとバインドマウント（vendor 込み） |
| **node_modules** | **両方の流派がある** | コンテナ内開発の定番は「匿名ボリュームで隠す」。ホストでも npm を使う構成（このリポジトリ）も普通 |
| **~/.gradle** | **見えないのが主流** | named volume（このリポジトリと同じ）が定番。ホストでも Gradle を使う人だけ共有マウントする流派がある |

- **node_modules** — コンテナ内開発では[匿名ボリュームで隠すのが定番の推奨](https://medium.com/@duckdevv/docker-node-modules-management-why-anonymous-volume-is-the-right-answer-247fbc14c481)（`.:/app` + `/app/node_modules`）。理由はホストとコンテナの OS 差による native バイナリの不整合、Docker Desktop（Mac/Windows）での性能、権限問題。ただし隠すと**ホストの IDE が型定義を読めなくなる**ので、ホストでも npm install する構成も同じくらい見かける
- **vendor（Laravel）** — [公式ツールの Sail](https://laravel.com/docs/13.x/sail) はプロジェクト丸ごとバインドマウントで vendor も**見える**。PHP の IDE は vendor 内のクラス定義を読んで補完するため、見えることに実益がある。一方 Mac/Windows では [vendor をコンテナ内ボリュームに移す高速化 Tips](https://dev.to/tylerlwsmith/speed-up-laravel-in-docker-by-moving-vendor-directory-19b9) が知られる程度に性能コストもある
- **~/.gradle** — [Gradle 公式ドキュメントの Docker ガイド](https://docs.gradle.org/current/userguide/docker.html)自体が named volume 方式を案内。変種として[ホストの `~/.gradle` を共有マウントする流派](https://www.endoflineblog.com/optimizing-development-with-docker)もある（ホストでも Gradle / IDE ビルドを使う人には二重ダウンロードが消えて合理的）。Java の IDE は node_modules のようなプロジェクト内フォルダではなく**ホスト側の ~/.gradle と自前のインデックス**で補完を賄うため、コンテナのキャッシュを見せるメリットがない

分かれ目はほぼ一点: **「ホスト側の道具（IDE やパッケージマネージャ）がそのフォルダを読むか?」**

- 読む（vendor、node_modules と TypeScript 補完）→ 見せる価値がある。性能が問題になったら初めて隠す工夫をする
- 読まない（~/.gradle）→ 隠す（named volume）が素直

このリポジトリの現状（node_modules はホスト実体・.gradle は named volume）は、この基準に照らして両方とも実務の主流パターンに乗っています。

## 落とし穴

- **`docker compose down -v` を気軽に打たない。** gradle-cache は再ダウンロードで済むが、**mysql-data も一緒に消えて DB の中身が飛ぶ**
- **`backend/.gradle`（プロジェクト直下）は別物。** ビルドの途中状態を置くプロジェクト固有の作業フォルダで、共有キャッシュ（~/.gradle）とは役割が違う。git には入れない
- **ホストでも Gradle を動かすとキャッシュは二重になる。** ホストの `~/.gradle` とコンテナの named volume は別の場所なので、それぞれ初回ダウンロードが走る

## 用語集

- **Gradle** — Java のビルドツール。依存管理 + タスク実行 + ビルドを 1 つで担う（npm 一式の Java 版）
- **Maven** — Gradle と並ぶもう 1 つの定番ビルドツール。二択の関係
- **Gradle Wrapper（gradlew）** — プロジェクト指定バージョンの Gradle 本体を自動ダウンロードして実行する同梱スクリプト
- **gradle-wrapper.properties** — Wrapper が読む「使うべき Gradle バージョン」の設定ファイル
- **`./`** — 「現在のディレクトリ」を表すパス表記。PATH 検索ではなく場所の明示
- **~/.gradle** — ユーザー単位の Gradle キャッシュ置き場（本体 + 依存 jar）。全プロジェクト共有
- **named volume** — Docker が管理する永続保存領域。コンテナを作り直しても残り、`down -v` で削除
- **バインドマウント** — ホストのフォルダをコンテナに見せる方式。実体はホスト側にある
- **マウントの重ね掛け（マスキング）** — マウント先に元からあったファイルは削除されず「見えなくなる」だけ、という性質
- **GRADLE_USER_HOME** — `~/.gradle` の場所を変更する環境変数

## 関連

- CMD の読み解き、バインドマウントと named volume の使い分けの実例 → [docker-dev-containers.md](./docker-dev-containers.md)
- bootRun タスクの中身、初回起動が遅い理由 → [java-build-and-run.md](./java-build-and-run.md)
- build.gradle の書き方と依存管理 → [gradle-dependencies.md](./gradle-dependencies.md)
