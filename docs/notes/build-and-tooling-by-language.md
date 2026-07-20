# 言語で変わる開発環境 — Java / Node.js / PHP のビルドの必要性とコード補完の仕組み

「なぜ Java だけ開発環境づくりでこんなに悩むのか」の答え。ビルドの要否もコード補完の仕組みも、根っこは同じ**「その言語の実行系がどんな形式なら実行できるか」**の違いに帰着する、という学習メモ。

## 実行モデルの違い — 実行系が「何を実行できるか」

### PHP: ソースをそのまま解釈する

PHP のインタープリタはソース(.php)を直接読んで実行する。リクエストのたびにソースを読むモデルなので、**保存すれば次のリクエストから反映**される。ビルド工程はそもそも存在しない。

- 内部では opcache がソースをバイトコード化してキャッシュしているが、これは開発者から見えない透過的な仕組みで「ビルド作業」ではない
- `composer install` は依存ライブラリの**取得**であって、コードの変換(ビルド)ではない

### Node.js: JS はそのまま。TypeScript は「見えないビルド」

V8(Node.js のエンジン)は JavaScript を直接実行するので、素の JS にビルドは不要。TypeScript は V8 がそのままでは解釈できないため「型注釈を剥がして JS にする」変換が必要になる — ただし開発中は **Vite / Nuxt / Next.js の dev サーバーがリクエストや保存に応じてメモリ上で変換**するので、開発者にはビルドが見えない(Next.js は SWC という Rust 製の高速変換系を使う。`tsx` などのローダーや、近年 Node 本体に入りつつある型剥がし実行も同種の仕組み)。

つまり「Node は開発中ビルド不要」の正確な言い方は**「ビルドしていない」のではなく「ビルドが速すぎて・暗黙すぎて見えない」**。本番向けには `npm run generate`(SSG)のような明示的なビルドをする。

### Java: バイトコードしか実行できない

JVM が実行できるのは**バイトコード(.class)だけ**で、ソース(.java)を直接解釈・実行する経路が言語仕様上存在しない(`java Main.java` の単一ファイル実行機能もあるが、裏で同じコンパイルが走っているだけ)。だからコンパイルは省略不可能で、**開発中も必ず「誰か」が javac を回している**。

#### では本番の jar は何なのか — 「実行される形式」ではなく「運ぶための箱」

本番ビルドで作る jar は、**大量の .class(と設定ファイル)を 1 つにまとめた zip 形式の箱**にすぎない。`java -jar app.jar` で起動しても、JVM が実行しているのは箱の中から読み出した**バイトコードそのもの**で、「本番だけ別の形式を実行している」わけではない。

では class → jar の箱詰め工程はいつ必要か。答えは**配布(デプロイ)のときだけ**:

- **開発中は jar を作らない。** `bootRun` は `build/classes` にあるばらの .class をクラスパスで直接指して起動する(だから保存 → コンパイル → devtools 再起動のループに jar は登場しない)
- **本番は「1 ファイルで運べる」ことに価値がある。** コピーも起動コマンド(`java -jar`)も単純になり、Spring Boot の実行可能 jar は依存ライブラリの jar まで同梱するので、サーバーに置くのはファイル 1 個で済む

つまり jar 化は実行のためではなく**運搬のための工程**。「開発 = ばらの .class を直接実行 / 本番 = 箱詰めして運んでから、中の .class を実行」という関係になる。

#### 本番イメージの作り方 — 実務では jar を丸ごと COPY する

では ECR に push する本番イメージはどう作るか。**.class や設定ファイルを 1 つずつ COPY する運用は実務に存在しない**。ファイル数が数百〜数千になり、抜け漏れや COPY の管理が破綻する — まさにそれをしないために jar という箱がある。定番は jar 1 個を COPY して exec 形式で起動するだけ:

```dockerfile
FROM eclipse-temurin:21-jre
COPY <どこかで作った>/app.jar /app/app.jar
CMD ["java", "-jar", "/app/app.jar"]
```

論点は「その jar を**どこで**作るか」で、実務では 2 流派ある:

- **CI で作って COPY する派** — GitHub Actions 等で `./gradlew build` してから、できた jar を Dockerfile が COPY する。CI はテストの時点でコンパイル済みなので二度手間がなく、CI のキャッシュも素直に効く。ただし「イメージの中身の一部が CI の手順に依存する」ため、Dockerfile だけ見てもイメージの作り方が完結しない
- **マルチステージビルド派** — Dockerfile 内のビルド用ステージ(JDK)で `./gradlew build` し、実行用ステージ(JRE)には jar だけをコピーする。`docker build` 一発で誰の環境でも同じイメージが再現でき、**Docker 公式・Spring 公式ドキュメントが示す現在の標準形**。ビルドの二度手間は BuildKit のキャッシュマウントで緩和できる

発展形として、Spring Boot の **layered jar**(箱を「依存ライブラリ層 / 自分のコード層」に開け直して COPY し、変更のない依存層を Docker レイヤーキャッシュに乗せて push/pull を速くする)や、Dockerfile 自体を書かない **Buildpacks**(`./gradlew bootBuildImage`)・**Jib** という流儀もある。

**このリポジトリの方針は「全工程マルチステージ」**。「ECR に push するイメージが何でできているかは Dockerfile を見れば全部わかる」ことを優先し、Nuxt の SSG ビルドまで含めて Dockerfile 内で完結させる(ステージ構成 → [setup/backend.md](../setup/backend.md))。

### まとめ表

| | PHP | Node.js(TypeScript) | Java |
|---|---|---|---|
| 実行系が実行できる形式 | ソースそのもの | JS(TS は変換が必要) | バイトコード(.class) |
| 開発中のビルド | 不要 | 暗黙(dev サーバーがその場で変換) | **必要**(①コンパイル係) |
| 保存 → 反映 | 次のリクエストから | HMR で即 | コンパイル + 再起動を経て |
| 本番のビルド | 不要 | する(バンドル / SSG) | する(jar = .class の箱詰め) |

## 開発環境の設計への波及

この違いが、docker-compose 開発環境の作りにそのまま現れる:

- **PHP** — ソースをマウントすれば終わり。何も足す必要がない
- **Node.js** — dev サーバー(`nuxt dev`)自体が監視・変換・HMR を内蔵している。マウント + dev サーバーで完結(このリポジトリの frontend がまさにこれ)
- **Java** — `bootRun` は起動時に一回コンパイルするだけの「一発実行」なので、保存を反映するには**①コンパイル係を別途誰かが務める**必要がある。誰に任せるかの比較と結論 → [java-dev-env-comparison.md](./java-dev-env-comparison.md)

## コード補完も同じ構図 — 言語サーバーが何を必要とするか

補完・エラー表示・定義ジャンプは、エディタ本体ではなく**言語サーバー**という別プログラムの仕事(エディタとは LSP という共通プロトコルで会話する)。「言語サーバーを動かすのに何が要るか」も言語ごとに違い、実はビルドの話と同じ構図になっている。

- **TypeScript / Vue** — tsserver / Volar は Node の上で動くが、**VS Code が Node ランタイムを同梱している**ので追加インストールは不要。補完の材料は `node_modules` 内の型定義ファイルで、「ファイルとして見えれば」よい。だからホストに Node が無くても `docker compose exec nuxt npm install` でコンテナ側から作れば、マウント越しにホストにも現れて補完が効く(ネイティブバイナリを含むパッケージは OS・アーキテクチャの互換に注意。WSL2 とコンテナはどちらも Linux/glibc なのでこのリポジトリでは問題ない)
- **PHP** — 定番の Intelephense も VS Code 同梱の Node で動き、標準ライブラリの定義(スタブ)を同梱しているので、**PHP 本体がインストールされていなくても補完できる**
- **Java** — 言語サーバー(Eclipse JDT Language Server)は **JVM の上で動く Java プログラム**で、さらにプロジェクトのクラスパス解決に Gradle を使う。つまり**補完のためだけに JDK 一式が必要**。実行だけでなくツーリングまで JDK を要求するのが Node / PHP との決定的な違いで、「エディタごとコンテナに入る Dev Container」が Java で特に効く理由がここにある

### 定義ジャンプの「飛び先」も同じ対応関係

自分が書いていないライブラリのクラスへ定義ジャンプするには、言語サーバーが参照できる「定義の実体」がファイルとして手元に要る。TS / Vue なら `node_modules` 内の型定義・ソース(`npm install` で取得)、PHP なら Intelephense 同梱のスタブ(取得作業すら不要)、Java なら Gradle キャッシュ内の jar・**ソース jar**(ライブラリの元ソースを収めた jar。Gradle が取得)がそれにあたる。

つまり「ランタイム不要」の TS / PHP でも、ライブラリへ飛ぶための**依存の取得**は必要で、TS の `node_modules` と Java の Gradle キャッシュは「飛び先の材料置き場」として同じ役割。違いは、その材料を読んで飛び先を答える言語サーバー自身が VS Code 同梱の Node で動くか、**別途 JDK を要求するか**。定義ジャンプが Dev Container ウィンドウでしか効かない実際の症状と注意点 → [java-dev-env-comparison.md](./java-dev-env-comparison.md) の「定義ジャンプが Dev Container ウィンドウでしか効かない理由」の節

### 「VS Code 同梱の Node」とは — Node.js とは別物?

別物ではなく、**Node.js 本体のコピーを VS Code が自分の中に抱えている**という意味。VS Code は Electron(Chromium + Node.js を内蔵した、Web 技術でデスクトップアプリを作る基盤)でできており、拡張機能や言語サーバーはこの**内蔵 Node.js** の上で動く。WSL やコンテナ側に置かれる VS Code Server も、自分用の Node.js を一式持ち込む。

つまり「システムに `node` をインストールしたか」と「VS Code の拡張機能が動くか」は無関係で、tsserver / Volar / Intelephense が追加インストールなしで動くのはこのため。逆に言うと、内蔵 Node.js は VS Code 専用で、ターミナルで `node` や `npm` を打っても使えない — アプリを動かすための Node.js は別途必要(このリポジトリでは frontend コンテナが担っている)。

| | 言語サーバー | 動かすのに必要なもの | 補完の材料 | 定義ジャンプの飛び先 |
|---|---|---|---|---|
| TypeScript / Vue | tsserver / Volar | VS Code 同梱の Node | `node_modules` の型定義 | `node_modules` 内の型定義・ソース |
| PHP | Intelephense | VS Code 同梱の Node | 同梱スタブ + ソース | 同梱スタブ + プロジェクトのソース |
| Java | Eclipse JDT LS | **JDK(JVM + Gradle 連携)** | クラスパス上の .class / jar | Gradle キャッシュの jar(ソース jar) |

## 用語集

- **インタープリタ** — ソースコードを直接読んで実行するプログラム(PHP の実行系)
- **バイトコード** — コンパイル結果の中間形式。JVM が実行する(PHP の opcache が内部で作るものも同名)
- **jar** — 大量の .class と設定ファイルを 1 つにまとめた zip 形式の箱。配布のための形式で、実行されるのは中のバイトコード
- **ソース jar** — .class ではなくライブラリの元ソースコードを収めた jar。定義ジャンプの飛び先として人間が読めるコードを提供する
- **マルチステージビルド** — 1 つの Dockerfile 内で「ビルド用ステージ(JDK)」と「実行用ステージ(JRE)」を分け、成果物だけを後段に渡す書き方。本番イメージ作りの標準形
- **layered jar** — jar を「依存ライブラリ層 / 自分のコード層」に分けて Docker レイヤーキャッシュを効かせる Spring Boot の仕組み
- **Buildpacks / Jib** — Dockerfile を書かずにコンテナイメージを作る流儀(Spring Boot 公式 / Google 製)
- **SWC** — Next.js が使う Rust 製の高速 TypeScript/JavaScript 変換系
- **Electron** — Chromium + Node.js を内蔵したデスクトップアプリ基盤。VS Code の土台で、「VS Code 同梱の Node」の正体
- **VS Code Server** — WSL・コンテナ側で動く VS Code の裏方。自分用の Node.js を持ち込むため接続先に Node のインストールは不要
- **opcache** — PHP が内部で行う透過的なバイトコードキャッシュ。開発者の「ビルド作業」ではない
- **HMR(Hot Module Replacement)** — ページ全体を再読み込みせず変更モジュールだけ差し替える dev サーバーの機能
- **言語サーバー / LSP** — 補完・エラー表示を担う別プロセスと、エディタとの共通会話プロトコル
- **tsserver / Volar / Intelephense / Eclipse JDT LS** — TypeScript / Vue / PHP / Java それぞれの代表的な言語サーバー
- **型定義(.d.ts)** — TypeScript の補完・型チェックの材料になる型情報ファイル。`node_modules` 内に配布される
- **スタブ** — 標準ライブラリ等の「定義情報だけ」のファイル。Intelephense が PHP 本体なしで補完できる理由

## 関連

- Java の「①コンパイル係 / ②再起動係」モデルと bootRun の性質 → [java-build-and-run.md](./java-build-and-run.md)
- ①を誰に任せるかの手法比較と Dev Container 採用理由 → [java-dev-env-comparison.md](./java-dev-env-comparison.md)
