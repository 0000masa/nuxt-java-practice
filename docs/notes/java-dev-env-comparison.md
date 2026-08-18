# Java 開発環境の手法比較 — なぜ Dev Container + docker-compose 方式を選んだか

コンテナで Java を開発するとき「保存 → 自動反映」をどう成立させるか。候補になった手法の比較と、このリポジトリが `.devcontainer/`(Dev Container)方式を採用した理由の記録。

## 前提: 問題はいつも「①コンパイル係を誰が務めるか」

[java-build-and-run.md](./java-build-and-run.md) のとおり、保存した変更が動いているアプリに反映されるには 2 段階が必要:

```
① ソース(.java)の変更を検知して コンパイル する係   ← 誰が務める?
        ↓ .class が変わる
② .class の変化を検知して アプリを再起動する係       ← spring-boot-devtools(build.gradle に常駐済み)
```

②は最初から居るので、手法の違いはすべて「①を誰に任せるか」の違いに帰着する。加えてこのリポジトリには「**ホスト(WSL)に JDK を置きたくない**」という要件がある。実行環境をコンテナに閉じ込めたのに、開発のためにホストへ JDK を入れたら本末転倒だから。

つまり物差しは 2 本: **①を安定して務められるか** / **ホストに JDK が不要か**。

## 手法カタログ

### 1. 手動 restart(ベースライン)

①を雇わず、反映したくなったら `docker compose restart backend`。bootRun がやり直され、そのとき差分コンパイルが走る。仕掛けゼロで壊れようがないが、毎回数十秒待つ。

### 2. コンテナ内に継続ビルドを常駐させる(試して撤回)

CMD で `./gradlew classes --continuous` をバックグラウンド常駐させ、①をコンテナ内の Gradle に務めさせる方式。一度実装したが、コードレビューで問題が集中して撤回した:

- **監視係が「無言で死ぬ」**。OOM 等で常駐プロセスが死んでもコンテナも API も健全に見え続け、「直したはずが反映されない」を延々デバッグすることになる。死んだことに気づく仕組みを別途作る必要がある
- バックグラウンドの常駐プロセスには**停止シグナルが届かず**、コンテナ破棄のたびに SIGKILL で即死する(gradle-cache のロック・キャッシュ破損リスク)
- Gradle デーモンが**常時 2 個**になりメモリの安全マージンが削られる
- 2 つの Gradle が同じ `build/` とキャッシュに並行アクセスする**ロック競合**の窓が残る

動きはするが「誰にも監視されない常駐プロセス」を抱え込む構成で、安定運用のためのコストが本体より大きい。

### 3. ホストの IDE が①を務める(volume 経由)

ホストの VS Code / IntelliJ が保存時に自動コンパイルし、`build/classes` に出た .class がマウント越しにコンテナへ届き、コンテナ内の devtools が拾う。実務でも定番の形。ただし**ホストに JDK 21 が必要**になり、このリポジトリの要件に反する。VS Code の場合は出力先が `build/classes` になる設定(Gradle Build Server)の確認も要る。

### 4. アプリをコンテナに入れない(実務では最多)

compose で動かすのは MySQL / MinIO などミドルウェアだけにして、Spring Boot 本体は IDE から直接起動する。構成が最も単純で、デバッガも直結でき、実務チームの第一候補になりやすい。ただし「アプリも本番同様コンテナで動かして学ぶ」というこのリポジトリの目的と合わず、やはりホストに JDK が要る。

### 5. devtools のリモート更新(`RemoteSpringApplication`)

変更した .class を HTTP で実行中アプリへ送り込む devtools の公式機能。volume マウントが使えない遠隔環境向けの仕組みで、ローカル開発で使う例は少ない。

### 6. ホットスワップ系(JRebel / HotswapAgent)

再起動すらせず、実行中 JVM の中のコードを書き換える上級ツール(devtools の「restart」とは別物の「hot swap」)。再起動の数十秒すら惜しい大規模アプリの現場向け。学習リポジトリには過剰で、①のコンパイル自体は別途必要なまま。

### 7. Dev Container(採用)

`.devcontainer/devcontainer.json` で「backend コンテナを、エディタごと中に入る開発環境として使う」と宣言する方式。VS Code の裏方(VS Code Server)と Java 拡張が**コンテナ内**で動くので、①はコンテナ内の VS Code が務め、コンパイルに使う JDK もコンテナに元から居るものを使う。**ホストに要るのは Docker と VS Code だけ**。

## 比較表

| 手法 | ①コンパイル係 | ホスト JDK | 特徴 |
|---|---|---|---|
| 1. 手動 restart | 雇わない | 不要 | 最も堅牢。ただし毎回待つ |
| 2. 継続ビルド常駐 | コンテナ内の常駐 Gradle | 不要 | 監視されない常駐プロセスが弱点 |
| 3. ホスト IDE + volume | ホストの IDE | **必要** | 実務定番だが要件に反する |
| 4. コンテナに入れない | ホストの IDE | **必要** | 実務最多だが学習目的と合わない |
| 5. devtools リモート | ホスト側のビルド | 必要 | ローカル開発では少数派 |
| 6. ホットスワップ系 | 別途必要 | 構成による | 商用/上級向け。今回は過剰 |
| 7. **Dev Container** | **コンテナ内の VS Code** | **不要** | 初回セットアップが重い以外は両方の物差しを満たす |

## 採用構成の全体像

- CMD は今までどおり `sh ./gradlew bootRun`。**`docker compose up` だけで全コンテナが動く**(VS Code を開いていなくても API は立つ)
- Java を書くときは VS Code の「Reopen in Container」で backend コンテナに入る(手順 → [setup/backend.md](../setup/backend.md))
- 保存すると、コンテナ内の Java 拡張が自動コンパイル(①)して `build/classes` を更新し、devtools(②)がそれを検知してアプリだけを高速再起動する

```
保存(コンテナ内の VS Code)
  → Java 拡張が自動コンパイル(①) → build/classes の .class が更新される
  → devtools(②)が検知 → Spring アプリだけ高速再起動(JVM は生きたまま)
```

- ワークスペースは `/app`(= `backend/`)のみ。docs や frontend の編集はホスト側の VS Code ウィンドウで行う(2 ウィンドウ運用)
- `shutdownAction: "none"` を指定してあるので、Dev Container のウィンドウを閉じても compose は止まらない

## 日常の使い方 — ウィンドウの開き方と「どちらで編集するか」

### Dev Container ウィンドウの開き方と見分け方

1. ホスト側の VS Code に拡張機能「Dev Containers」(`ms-vscode-remote.remote-containers`)を入れておく(初回のみ)
2. `docker compose up -d` で環境を起動する
3. コマンドパレット(Ctrl+Shift+P)→ **「Dev Containers: Reopen in Container」**。**今のウィンドウ**が開き直り、`.devcontainer/devcontainer.json` が読まれて backend コンテナに接続される(初回はコンテナ内への VS Code Server + 拡張のインストールで数分待つ)。「閉じた」ように見えるが実際は同じウィンドウが接続先を切り替えてリロードしただけで、未保存の編集も hot exit(未保存内容の自動退避)で引き継がれる。ホスト側の作業ウィンドウが必要なら、このあと「WSL: New WSL Window」でもう 1 枚開いてリポジトリを開き直せばよい
4. **今どちらに居るかはウィンドウ左下の青色のリモートインジケータで見分ける。** 「Dev Container: backend (Spring Boot)」ならコンテナ内、「WSL: ...」ならホスト側
5. ホスト側に戻るときはコマンドパレット → **「Dev Containers: Reopen Folder Locally」**。`shutdownAction: "none"` なのでウィンドウをただ閉じてもコンテナ・compose は止まらない

手順だけの最小セットは [setup/backend.md](../setup/backend.md) にもある。

### 既存ウィンドウを開いたまま、別ウィンドウでコンテナに入る方法

「Reopen in Container」は名前のとおり「今のウィンドウを開き直す」コマンドなので、これを使う限り既存ウィンドウは必ずコンテナ用に切り替わる(挙動を変える設定はない)。今開いているホスト側ウィンドウに触れずにコンテナ用ウィンドウを**増やしたい**ときは、こうする:

1. コマンドパレット → **「WSL: New WSL Window」** で、WSL に接続された空ウィンドウをもう 1 枚開く
2. その新しいウィンドウでコマンドパレット → **「Dev Containers: Open Folder in Container...」**
3. フォルダ選択でリポジトリ直下(`.devcontainer/` がある場所)を選ぶ。新ウィンドウだけが backend コンテナに接続され、元のウィンドウは WSL 接続のまま残る

なお「先にホスト用の 2 枚目を開いておいて、片方だけ Reopen する」という順番は上手くいかない。VS Code は**同じ接続先の同じフォルダを 2 枚のウィンドウで開けない**(既存ウィンドウにフォーカスが移るだけ)ため。上の手順や、「Reopen in Container してからホスト用ウィンドウを開き直す」順番なら、コンテナ側ウィンドウのフォルダは `/app`(接続先も別)なのでこの制約に当たらない。

### どちらのウィンドウで編集しても「ファイルは」変わる。それでも Java はコンテナ側で編集する

バインドマウント(`./backend:/app`)はコピーではなく**同一実体を 2 か所から見せる**仕組みなので、ホスト側ウィンドウで `.java` を編集・保存してもコンテナ内のファイルはちゃんと変わる。「反映されるか」だけならされる。それでも **Java の編集は Dev Container ウィンドウで行う**。理由は 2 つ:

- **①コンパイル係が反応するのは「自分のウィンドウで起きた保存イベント」だから。** 保存 → 自動コンパイルの引き金は、Dev Container ウィンドウ内の Java 拡張が受け取る保存イベント。ホスト側で保存した場合、コンテナ内の拡張から見るとそれは「外部変更」(誰かが外からファイルを書き換えた出来事)で、**再ビルドは走らない**。Dev Container ウィンドウを開いたまま実際に試しても `build/classes` の .class は更新されなかった(検証の詳細 → [java-build-and-run.md](./java-build-and-run.md))。Dev Container ウィンドウを開いていなければ①はそもそも不在で、いずれにせよ devtools は何も拾わない
- **ホスト側では Java の編集体験が成立しないから。** 補完・エラー表示を担う言語サーバー(Eclipse JDT LS)は JDK の上で動く。JDK を置かない方針のホスト側で `.java` を開いても、ただのテキストエディタになる

つまり分業はこう固定する: **Java → Dev Container ウィンドウ / docs・frontend・compose 等 → ホスト側ウィンドウ**。

注意点:

- **同じファイルを両方のウィンドウで開いて編集しない。** VS Code は外部変更を検知して追従するが、両方に未保存の編集があると上書きの衝突が起きる。分業を守っていれば起きない
- ホスト側で Java を触ってしまった・git 操作でソースが変わった等、反映が怪しいときは `docker compose restart backend` で確実に反映できる(手法 1 への安全なフォールバック)

### 定義ジャンプ(Ctrl+クリック)が Dev Container ウィンドウでしか効かない理由

ホスト側ウィンドウで `SpringApplication` を Ctrl+クリックしても飛べないのは、定義ジャンプがエディタ本体ではなく**言語サーバーの機能**であり、それを動かす 3 点セットがすべてコンテナ側にしかないから:

1. **Java 拡張(`vscjava.vscode-java-pack`)** — 言語サーバー本体(Eclipse JDT LS)はこの拡張に同梱される。devcontainer.json の指定により**コンテナ内にだけ**インストールされる
2. **JDK** — JDT LS はそれ自体が Java 製プログラムで、JVM がないと起動すらできない(ホストで補完・エラー表示が効かないのと同じ理由)
3. **Gradle が解決した依存 jar** — `SpringApplication` のような自分が書いていないクラスの「定義の場所」は、Gradle がダウンロードした jar の中。言語サーバーはコンテナ内の Gradle キャッシュの jar を参照して飛び先を決める

つまり Dev Container ウィンドウでだけ飛べるのは特別な設定の成果ではなく、「言語サーバーが動ける場所 = 3 点セットが揃っている場所」がコンテナ内だけだから。挙動で戸惑いやすい点が 2 つ:

- **ライブラリへ飛ぶと読み取り専用のファイルが開く。** それはソース jar から取り出されたライブラリのコードで、自分のプロジェクトのファイルではない。編集できないのが正常
- **開いた直後は飛べないことがある。** 言語サーバーは起動時にプロジェクト全体を解析するため、ウィンドウ右下のステータス表示が落ち着くまで(1〜2 分)待つと飛べるようになる

「TS / PHP はホストにランタイムが無くても飛べるのに、なぜ Java だけ飛べないのか」という言語間の違いは [build-and-tooling-by-language.md](./build-and-tooling-by-language.md) の「コード補完も同じ構図」の節を参照。

### Claude Code に Java を編集させたとき — 手動で①を務める

Claude Code(claude CLI)は**ホスト(WSL)側で動くツール**なので、その編集はコンテナ内から見ると「外部変更」。①コンパイル係(Dev Container 内の VS Code)は自分のウィンドウの保存イベントにしか反応しないため、**Claude Code の編集は保存だけでは反映されない**。編集が終わったら、リポジトリ直下(`docker-compose.yml` がある場所)でどちらかを実行する:

```bash
docker compose exec backend sh ./gradlew classes   # 軽い: コンパイルだけ → devtools が拾って高速再起動
docker compose restart backend                     # 重い: コンテナごと再起動(依存変更時・反映が怪しいとき)
```

`exec` の方の仕組み:

- `docker compose exec backend ...` は「動いている backend コンテナの中でコマンドを 1 回実行する」。コンテナの WORKDIR が `/app`(= `backend/`)なので `./gradlew` がそのまま見つかる。`sh` を挟むのは CMD と同じ実行権限の保険
- `classes` はコンパイル(+リソース処理)だけを行うタスク。`build/classes` の .class が更新されれば、あとは常駐している devtools(②)が普段どおり検知してアプリだけを再起動する。**アプリを止めないので restart より速い**(アプリ稼働中の実行で完走を確認済み。変更がなければ `UP-TO-DATE` と表示されて何もしないので、何度実行しても安全)
- つまりこれは「①コンパイル係を、その場で 1 回だけ人力(または Claude Code 自身)が務める」操作。Claude Code に編集させるときは、このコマンドの実行までセットで頼めば「編集 → 反映 → 動作確認」が一連で完結する(CLAUDE.md にも記載)

## トレードオフ(把握したうえで受け入れたもの)

- **初回の「Reopen in Container」は重い。** コンテナ内に VS Code Server と拡張一式をダウンロードするため数分かかる。2 回目以降は速い
- **VS Code を開いていない間は①が不在。** その間の変更は手法 1(restart)で反映する。仕掛けが壊れるのではなく、素朴な方式に自然に戻るだけ — 手法 2 の「無言死」と違い、反映されない理由が自明なのがこの構成の利点
- **`build.gradle` の依存変更は保存では反映されない。** devtools が差し替えるのは自分のコード側のクラスローダーだけで、依存ライブラリのクラスパスは起動時に固定されるため。依存を追加・変更したら `docker compose restart backend`
- **bootRun の Gradle デーモンと VS Code 側のビルドが同じキャッシュ(gradle-cache)を共有する。** 保存時の短いコンパイルなので常駐プロセス(手法 2)よりはるかに衝突しにくいが、ゼロではない

## 用語集

- **Dev Container** — 「このコンテナをエディタごと入る開発環境として使う」ための公開仕様(containers.dev)。中身は普通の Docker コンテナ
- **devcontainer.json** — 入るサービス・入れる拡張などを書く指示書。`.devcontainer/` に置く
- **VS Code Server** — VS Code の裏方部分。Dev Container ではコンテナ内で動き、拡張・言語サーバー・ターミナルを担う
- **Gradle Build Server** — VS Code のビルドを Gradle 本体に委譲する仕組み。出力先がコマンドラインと同じ `build/classes` になる
- **restart / hot swap** — devtools の「Spring ごと組み立て直す」再起動と、JRebel 等の「実行中コードを書き換える」ホットスワップは別物
- **保存イベント** — エディタが「保存された」ことを拡張機能に通知する仕組み。自動コンパイル(①)の引き金
- **外部変更** — その VS Code ウィンドウの外で行われたファイル書き換え。検知はされるが、保存イベントとは扱いが別
- **リモートインジケータ** — VS Code ウィンドウ左下の青色の表示。今どの環境(WSL / Dev Container)に接続しているかを示す
- **Gradle キャッシュ** — Gradle がダウンロードした依存 jar の保管場所(コンテナ内の `~/.gradle`)。定義ジャンプの飛び先の材料でもある
- **ソース jar** — ライブラリの .class ではなく元のソースコードを収めた jar。定義ジャンプで人間が読めるコードが開くのはこれのおかげ(言語サーバーの定義は [build-and-tooling-by-language.md](./build-and-tooling-by-language.md) の用語集を参照)
- **`RemoteSpringApplication`** — devtools のリモート更新用クライアント

## 関連

- ①②の 2 段階モデル、bootRun が「一発実行」である話 → [java-build-and-run.md](./java-build-and-run.md)
- そもそも言語によってビルドの要否・補完の仕組みが違う話 → [build-and-tooling-by-language.md](./build-and-tooling-by-language.md)
- このリポジトリの「開発用コンテナ」(実行環境イメージ + マウント)の設計 → [docker-dev-containers.md](./docker-dev-containers.md)(※「開発用コンテナ」と「Dev Container」は別概念。あちらのメモの注記を参照)
