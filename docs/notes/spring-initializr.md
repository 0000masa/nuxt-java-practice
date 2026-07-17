# Spring Initializr — Spring Boot プロジェクトの作り方

Spring Boot の新規プロジェクトの「ひな形（スケルトン）」を生成する仕組みについての学習メモ。`docs/setup/backend.md` のプロジェクト作成コマンドの読み解き。

## 結論

`docs/setup/backend.md` にある curl コマンドは、**Spring Boot プロジェクトのひな形を生成するコマンド**です。他のフレームワークでいうと次に該当します。

| フレームワーク | プロジェクト生成コマンド |
|---|---|
| Spring Boot | `curl https://start.spring.io/starter.zip ...` |
| Laravel | `composer create-project laravel/laravel myapp`（または `laravel new myapp`） |
| Next.js | `npx create-next-app myapp` |
| Nuxt（このリポジトリのフロント） | `npx nuxi init myapp` |

## 仕組み — 「Web サービスに注文して ZIP で受け取る」

コマンドの正体は、Spring 公式の生成サービス **Spring Initializr**（https://start.spring.io/）に「こういう構成のプロジェクトをください」と HTTP で注文し、完成品を ZIP で受け取る処理です。ピザの注文に例えると、`-d` の一つひとつが「トッピングの指定」、返ってくる ZIP が「焼き上がったピザ」です。

```bash
cd backend
curl https://start.spring.io/starter.zip \   # Spring Initializr に注文
  -d type=gradle-project \                   # ビルドツールは Gradle で
  -d language=java \                         # 言語は Java で
  -d javaVersion=21 \                        # Java 21 向けで
  -d dependencies=web,data-jpa,mysql,validation,devtools \  # この 5 つのライブラリ入りで
  -d baseDir=. \                             # ZIP 内にサブフォルダを作らず直下に展開できる形で
  -o starter.zip                             # 受け取った ZIP を starter.zip として保存
unzip starter.zip && rm starter.zip          # 展開して ZIP 本体は削除
```

- **curl** はコマンドラインから HTTP リクエストを送るツール。ブラウザの代わりに URL へアクセスできる
- **`-d`（data）** はリクエストに添えるパラメータ。これを付けると curl は POST リクエスト（サーバーにデータを送る形式）になる
- Spring Initializr をブラウザで開くと GUI の選択画面があるが、その裏側の API を curl で直接叩いているのがこのコマンド。`docs/setup/backend.md` の表（Project: Gradle、Java 21 など）は GUI で選ぶ場合の説明で、CLI コマンドとまったく同じ注文内容

生成される中身は、`build.gradle`（依存関係の定義 → 詳細は [gradle-dependencies.md](./gradle-dependencies.md)）、`src/main/java/` 配下の起動クラス、`gradlew`（Gradle 本体をインストールしなくても使えるラッパースクリプト）などの「動く最小構成」です。

## Laravel / Next.js との生成方式の違い

役割は同じですが、**生成のされ方**が違います。

| | Spring Boot | Laravel | Next.js |
|---|---|---|---|
| 生成のされ方 | **Web サービスがサーバー側で ZIP を組み立てて返す** | composer がテンプレートをダウンロードして手元で展開 | npm がツールをダウンロードして手元で対話的に生成 |
| 何をダウンロードするか | **完成品のプロジェクト**（ZIP） | **テンプレートプロジェクト**（ZIP） | **生成ツールというプログラム**（tarball） |
| 依存の指定 | `-d dependencies=web,data-jpa,...` で注文時に指定 | 生成後に `composer require` で追加 | 生成時の対話 + 後から `npm install` |

Laravel や Next.js はパッケージマネージャ（composer / npm）が手元のマシンでひな形を組み立てるのに対し、Spring Initializr は「リモートのサービスが完成品の ZIP を作って送ってくる」方式です。だから curl（ただの HTTP クライアント）だけで済み、事前のインストールが不要です。

### 実は 3 つとも裏側は「アーカイブのダウンロード + 展開」

「zip をダウンロードして展開する」という部品レベルの処理は 3 つとも共通しています。違うのは**何をダウンロードして、どこで組み立てるか**です。

- **Laravel（`composer create-project`）**: Packagist（composer のパッケージ登録サイト）経由で GitHub 上のリリース ZIP をダウンロードして展開する。ここまでは Spring Boot の unzip とほぼ同じ。そのあと手元で `composer install`（依存ライブラリの取得）と初期化スクリプト（`.env` 生成、`php artisan key:generate` など）が自動で走る
- **Next.js（`npx create-next-app`）**: npm レジストリからダウンロードするのは**プロジェクトではなく「生成プログラム」**（tarball 形式）。展開されたツールが手元で起動し、対話の答えに応じて**同梱テンプレートからファイルを書き出して**プロジェクトを作る。最後に `npm install` が依存ライブラリを 1 個ずつ tarball で取得・展開する

ピザで例えると: Spring Initializr は「焼き上がったピザが宅配で届く」、Laravel は「冷凍ピザを買って自宅のオーブンで焼く」、Next.js は「ピザ焼き機が届いて、好みを聞かれながらその場で作ってくれる」。

## 「インストール」の正体 — ダウンロード + 展開 + α

npm の tarball、composer の ZIP、Java の jar（中身は ZIP 形式）、Linux の `.deb` / `.rpm` — どれも「圧縮アーカイブを取得して所定の場所に展開する」がインストールの本体です。ただし実際には展開の前後に工程が付きます。

1. **依存関係の解決** — 「A には B と C が必要、B には D が必要…」を芋づる式に計算する（`npm install` が遅い理由の大半）
2. **ダウンロード + 展開** — 共通の中核部分
3. **後処理** — 初期化スクリプトの実行、設定ファイル生成、PATH への登録、環境によってはコンパイル

つまり「インストール = 依存解決 + ダウンロード + 展開 + 後処理」であり、本質は「**所定の場所に使える状態で配置すること**」。ダウンロードはそのための手段です（手元のソースからビルドして配置する `make install` のように、ダウンロードしないインストールもある）。

## このリポジトリでの注意点

- コマンドは `backend/` の中で実行する前提。`-d baseDir=.` を付けているので、展開すると `backend/build.gradle`、`backend/src/...` のように**直下に**ファイルが並ぶ。付け忘れると `backend/demo/...` のように余計な一段が挟まる
- `dependencies` の 5 つは `docs/setup/backend.md` の依存関係リストと一対一対応: `web` = Spring Web（REST API + 組み込み Tomcat）、`data-jpa` = DB アクセス、`mysql` = MySQL ドライバ、`validation` = リクエスト検証、`devtools` = 開発時の自動再起動

## 落とし穴

- **生成は最初の一回だけ。** `create-next-app` と同じで「プロジェクトの産声」であり、日常の開発では二度と実行しない。以後の起動は `./gradlew bootRun`
- **`npx` は「インストール」ではない。** `npx` は一時的にダウンロード → 実行 → プロジェクトには残さない使い捨て実行。`create-next-app` 自体はプロジェクトの依存に入らない（生成が一回きりなのと同じ理屈）
- **zip と tar.gz は役割としては同じ「アーカイブ」。** 圧縮方式が違うだけで、npm 系は tar.gz、Java 系は zip（jar も中身は zip）という文化圏ごとの慣習
- **既にファイルがあるディレクトリで展開すると混ざる。** `unzip` は既存ファイルとマージするので、`backend/` が空の状態で実行するのが安全
- **依存の後付けはコマンドではなくファイル編集。** → [gradle-dependencies.md](./gradle-dependencies.md)

## 用語集

- **Spring Initializr** — Spring 公式のプロジェクトひな形生成サービス。GUI と API の両方がある
- **curl** — コマンドラインで HTTP リクエストを送るツール
- **`-d`** — curl でリクエストにパラメータを添えるオプション。付けると POST になる
- **スケルトン（ひな形）** — 動く最小構成だけを持つ初期プロジェクト
- **Gradle Wrapper（gradlew）** — Gradle 本体を同梱スクリプト経由で自動取得する仕組み。Gradle のインストール不要
- **アーカイブ** — 複数ファイルを 1 つにまとめた（多くは圧縮した）ファイル。zip、tar.gz、jar など
- **tarball** — tar 形式でまとめて gzip で圧縮したファイル（.tar.gz / .tgz）。npm パッケージの配布形式
- **Packagist / npm レジストリ** — composer / npm がパッケージを探しに行く公式の登録サイト
- **依存関係の解決** — 必要なライブラリの必要なライブラリ…を芋づる式に計算する工程
- **npx** — npm パッケージをインストールせず一時取得して実行するコマンド
