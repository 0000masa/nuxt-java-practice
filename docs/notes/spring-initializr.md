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
| 依存の指定 | `-d dependencies=web,data-jpa,...` で注文時に指定 | 生成後に `composer require` で追加 | 生成時の対話 + 後から `npm install` |

Laravel や Next.js はパッケージマネージャ（composer / npm）が手元のマシンでひな形を組み立てるのに対し、Spring Initializr は「リモートのサービスが完成品の ZIP を作って送ってくる」方式です。だから curl（ただの HTTP クライアント）だけで済み、事前のインストールが不要です。

## このリポジトリでの注意点

- コマンドは `backend/` の中で実行する前提。`-d baseDir=.` を付けているので、展開すると `backend/build.gradle`、`backend/src/...` のように**直下に**ファイルが並ぶ。付け忘れると `backend/demo/...` のように余計な一段が挟まる
- `dependencies` の 5 つは `docs/setup/backend.md` の依存関係リストと一対一対応: `web` = Spring Web（REST API + 組み込み Tomcat）、`data-jpa` = DB アクセス、`mysql` = MySQL ドライバ、`validation` = リクエスト検証、`devtools` = 開発時の自動再起動

## 落とし穴

- **生成は最初の一回だけ。** `create-next-app` と同じで「プロジェクトの産声」であり、日常の開発では二度と実行しない。以後の起動は `./gradlew bootRun`
- **既にファイルがあるディレクトリで展開すると混ざる。** `unzip` は既存ファイルとマージするので、`backend/` が空の状態で実行するのが安全
- **依存の後付けはコマンドではなくファイル編集。** → [gradle-dependencies.md](./gradle-dependencies.md)

## 用語集

- **Spring Initializr** — Spring 公式のプロジェクトひな形生成サービス。GUI と API の両方がある
- **curl** — コマンドラインで HTTP リクエストを送るツール
- **`-d`** — curl でリクエストにパラメータを添えるオプション。付けると POST になる
- **スケルトン（ひな形）** — 動く最小構成だけを持つ初期プロジェクト
- **Gradle Wrapper（gradlew）** — Gradle 本体を同梱スクリプト経由で自動取得する仕組み。Gradle のインストール不要
