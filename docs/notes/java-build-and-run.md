# Java の実行環境とビルドの仕組み — eclipse-temurin、JVM、bootRun

`docker/backend/Dockerfile` のベースイメージ `eclipse-temurin:21-jdk` とは何か、Java の「ビルドしてから動かす」は開発中どうなるのか、についての学習メモ。PHP / Node 出身者向け。

## eclipse-temurin とは — 「Java 公式」が一枚岩ではない話

Java の世界は npm や PHP と事情が違い、**同じ設計図（OpenJDK のソースコード）から複数の団体がそれぞれ「ビルド済み JDK」を配布**しています。Oracle 製、Amazon 製（Corretto）、Microsoft 製、そして Eclipse Adoptium 製の **Temurin**。どれも同じ互換性テストに合格した「中身は実質同じ」JDK で、パン屋に例えると「同じレシピで焼いた別の店のパン」です。

Temurin が定番になっている理由:

- **無料でライセンスの心配がない**（Oracle 製は商用利用の条件が何度も変わって皆が疲れた歴史がある）
- **Docker Hub の「Docker Official Images」認定**を受けている。かつての `openjdk` 公式イメージは 2022 年に非推奨（deprecated）になり、公式ドキュメントが乗り換え先として eclipse-temurin などを案内している

つまり「Oracle 製の公式イメージ」ではないが、「Docker 公式イメージであり、Java コンテナの事実上の標準」です。

### タグ `21-jdk` の意味

- **21** — Java 21（LTS = 長期サポート版）
- **jdk** — コンパイラ（javac）込みの**開発キット**。実行専用の縮小版 **JRE**（`21-jre`）と区別される

開発コンテナはコンテナ内でコンパイルするので JDK 版が必須。本番用 Dockerfile を作るときは「ビルドは JDK、実行イメージは JRE（小さい）」と分ける**マルチステージビルド**が定石になります。

## 「ビルドしてから各 OS で動かす」の正体

```
PHP / Node:  ソースコード ──そのまま──→ 実行系が直接解釈
Java:        ソースコード(.java) ──コンパイル──→ バイトコード(.class) ──→ JVM が実行
```

コンパイル結果は OS ごとの実行ファイルではなく**バイトコード**という中間形式です。OS ごとの差は **JVM（Java 仮想マシン）**が吸収します。「各 OS の実行環境で動かす」とは「OS ごとに用意された JVM の上で、同じバイトコードを動かす」という意味です（Write once, run anywhere）。

## 開発中もコードを変えるたびにビルドが必要？

**必要です。そして保存しただけでは何も起きません。** 「ホストで保存 → バインドマウントでコンテナ内のソースも即変わる」までは自動ですが、そこから先が Nuxt とは違います。

### bootRun は「監視員」ではなく「一発実行のコマンド」

`npm run dev`（nuxt dev）と見た目が似ているので同じ性質だと思いやすいのですが、性格が違います。

| | `nuxt dev` | `./gradlew bootRun` |
|---|---|---|
| 起動時 | dev サーバーを立てる | 差分コンパイル → アプリ起動 |
| 起動後にファイルを保存すると | **監視していて自動反映**（ホットリロードが本業） | **何も起きない**（Gradle の仕事は起動時に終わっていて、動いているのは Spring Boot アプリだけ） |

レストランに例えると、bootRun は「注文を受けて料理して配膳したら仕事終了のコック」、nuxt dev は「席に張り付いていておかわりに即応える給仕」です。bootRun の差分コンパイル（変更されたファイルだけ再コンパイル）が走るのは**コマンドを実行したその瞬間に一回だけ**です。

### 「保存 → 反映」に必要な 2 段階

変更が動いているアプリに反映されるまでの経路を分解するとこうなります。

```
① ソース(.java)の変更を検知して コンパイル する係   ← この構成では不在！
        ↓ .class(コンパイル済みファイル)が変わる
② .class の変化を検知して アプリを再起動する係       ← spring-boot-devtools(居る)
```

`backend/build.gradle` に入っている `spring-boot-devtools`（`developmentOnly` スコープ)が見張っているのは**ソースではなくコンパイル結果（.class）**です。つまり後半②の係は常駐しているのに、前半①の「保存を検知してコンパイルする係」がコンテナ + bootRun だけの構成にはいません。バインドマウントが運んでくるのはソースであって、コンパイル結果ではないからです。

### 反映させる方法は 2 つ

1. **素朴な方法: コンテナを再起動する**（`docker compose restart backend`）。bootRun がやり直され、そのとき差分コンパイルが走る
2. **①の係を追加で雇う方法: 継続ビルドをもう 1 プロセス動かす。** 別ターミナルで `./gradlew compileJava --continuous`（`--continuous` = ソースを監視して変わるたびにタスクを再実行するモード）を実行しておくと、これが .class を更新し続け、devtools がそれを拾ってアプリを高速再起動する。IntelliJ などの IDE の「保存時自動コンパイル」を使う場合は、**IDE が①の係を務めている**という構図

まとめ: 「ビルドは必要。マウントが運ぶのはソースだけ。反映には『コンパイルする係』を誰かが務める必要があり、それが再起動か、継続ビルドか、IDE か、の違い」。

## `bootRun` タスクと Gradle Wrapper

- **`./gradlew`（Gradle Wrapper）** — Gradle 本体をインストールしていなくても、初回に正しいバージョンを自動ダウンロードして実行するスクリプト。ダウンロードした本体は docker-compose の `gradle-cache` ボリュームに保存され、コンテナを作り直しても再ダウンロードされない（Gradle そのものと キャッシュの置き場所の詳細 → [gradle-basics.md](./gradle-basics.md)）
- **`bootRun`** — Spring Boot の Gradle プラグインが提供するタスク。「コンパイル → 組み込み Tomcat ごとアプリを起動」まで一気にやる。ポート 8080 で待ち受け（EXPOSE 8080 / compose の `"8080:8080"`）
- CMD の先頭に `sh` が付いている理由（実行権限の保険）は [docker-dev-containers.md](./docker-dev-containers.md) を参照

## 落とし穴

- **初回の `docker compose up` はかなり待たされる。** Gradle 本体のダウンロード → 依存ライブラリ全取得 → 全ファイルコンパイル → 起動、と続くため。2 回目以降は gradle-cache が効いて速くなる。「壊れた?」と思う前にログを見る
- **devtools は本番に入らない。** `developmentOnly` スコープのおかげで `./gradlew build` の本番用 Jar には含まれない。スコープ（[gradle-dependencies.md](./gradle-dependencies.md) 参照）が「開発用の仕掛けを本番に紛れさせない」仕事をしている例
- **`21-jre` にすると開発コンテナは動かない。** コンパイラがないので bootRun のコンパイル段階で失敗する。JRE が活きるのは実行だけを行う本番イメージ

## 用語集

- **OpenJDK** — Java のオープンソース実装。各社がこれをビルドして配布する
- **Temurin / Eclipse Adoptium** — OpenJDK のビルド配布プロジェクト。旧 AdoptOpenJDK。事実上の標準
- **JDK / JRE** — 開発キット（コンパイラ込み）/ 実行環境のみ
- **LTS** — 長期サポート版。Java 21 はこれ
- **バイトコード** — コンパイル結果の中間形式。OS 非依存で、JVM が実行する
- **JVM（Java 仮想マシン）** — バイトコードを各 OS 上で実行する層。「どこでも動く」の実現装置
- **差分コンパイル** — 変更されたファイルだけを再コンパイルする仕組み。毎回のビルドを速くする
- **spring-boot-devtools** — クラス（.class）の変化を検知してアプリを高速再起動する開発ツール。ソースは見ていない
- **bootRun** — コンパイル + Spring Boot アプリ起動を行う Gradle タスク。実行時に一回だけ働き、ファイル監視はしない
- **compileJava** — コンパイルだけを行う Gradle タスク（bootRun はこれ + 起動を含む上位タスク）
- **継続ビルド（`--continuous`）** — ソースを監視し、変化のたびに指定タスクを自動再実行する Gradle のモード
- **マルチステージビルド** — 「ビルド用イメージ」と「実行用イメージ」を分ける Dockerfile の書き方

## 関連

- Dockerfile・CMD・マウントの設計 → [docker-dev-containers.md](./docker-dev-containers.md)
- build.gradle と依存管理・スコープ → [gradle-dependencies.md](./gradle-dependencies.md)
