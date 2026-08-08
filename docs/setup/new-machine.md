# 新しい PC に開発環境を作る手順(Windows 11)

別の Windows PC で、このリポジトリの開発環境をゼロから再現する手順。Docker / VS Code / git / GitHub 接続がまったく無い状態を出発点にする。

対象は **Windows 11 + WSL2 + Docker Desktop**。既存の開発機と同じ構成なので、構築後はコマンドもパスもすべて共通になる。

> **未検証の注記**: 手順 1〜4(WSL / `.wslconfig` / Docker Desktop / VS Code)は Windows 側の操作のため、この文書を書いた環境からは実行して確認できていない。各社の公式ドキュメントを根拠に記述しており、公式へのリンクを併記してある。手順 5 以降は既存の開発機と同一の内容。実際に構築したときに差異があれば、この文書を直すこと。

> Windows 10 を使う場合、手順 1 だけが大きく異なる(仮想マシンプラットフォームの手動有効化とカーネル更新が必要)。[Microsoft の公式手順](https://learn.microsoft.com/ja-jp/windows/wsl/install-manual)に従ったうえで、手順 2 から合流すること。

## 0. 全体像 — 何をどの層に入れるか

この構成は **3 つの層**に分かれる。どの層に何を入れるかを取り違えると「インストールしたのに使えない」が起きるので、最初に把握しておく。

```
┌─ ① Windows ────────────────────────────────────┐
│  Docker Desktop / VS Code 本体                   │
│  VS Code 拡張: WSL, Dev Containers               │
│                                                  │
│  ┌─ ② WSL2 (Ubuntu) ──────────────────────────┐  │
│  │  git / gh / docker CLI / リポジトリ本体     │  │
│  │  VS Code 拡張: Vue Volar, Markdown 系       │  │
│  │                                             │  │
│  │  ┌─ ③ コンテナ(docker compose) ────────┐  │  │
│  │  │  nuxt / backend / mysql / minio /     │  │  │
│  │  │  mailpit                              │  │  │
│  │  │  VS Code 拡張: Java Pack, Gradle      │  │  │
│  │  └───────────────────────────────────────┘  │  │
│  └─────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────┘
```

| 層 | 入れるもの | 誰が管理するか |
|---|---|---|
| ① Windows | Docker Desktop、VS Code、`WSL` / `Dev Containers` 拡張 | この手順書(手動) |
| ② WSL2 | git、gh、リポジトリ、Vue / Markdown 系拡張 | この手順書 + `.vscode/extensions.json`(自動推奨) |
| ③ コンテナ | Node 22、JDK 21、MySQL 8、MinIO、Mailpit、Java 系拡張 | `docker-compose.yml` と `.devcontainer/devcontainer.json`(全自動) |

**重要なのは、Node.js も JDK も MySQL も ① ② にインストールしないこと。** すべて ③ のコンテナが持っている。Windows に要るのは Docker Desktop と VS Code、WSL に要るのは git と gh だけ(設計の理由 → [docs/notes/java-dev-env-comparison.md](../notes/java-dev-env-comparison.md))。

> **用語の注意**: このリポジトリのドキュメントでは、一貫して **「ホスト」= WSL2** を指す(対義語は「コンテナ」)。Windows のことは「ホスト」と呼ばず「Windows」と書く。例えば [docs/notes/java-dev-env-comparison.md](../notes/java-dev-env-comparison.md) の「ホストに JDK を置きたくない」は「WSL に JDK を置きたくない」の意味。

所要時間の目安は、ダウンロード待ちを含めて 1〜2 時間。

---

## 1. WSL2 と Ubuntu をインストールする

**管理者として** PowerShell を開き(スタートメニューで「PowerShell」を右クリック →「管理者として実行」):

```powershell
wsl --install
```

これ 1 つで「WSL 本体 + 仮想マシンプラットフォームの有効化 + 既定のディストリビューション(Ubuntu の最新 LTS)のインストール」までが行われる。完了したら **Windows を再起動する**。

再起動後に Ubuntu のウィンドウが自動で開き、**UNIX ユーザー名とパスワード**を聞かれる。

- **ユーザー名**: 好きな名前でよい(Windows のユーザー名と一致させる必要はない)。この手順書は絶対パスを使わないので、何にしても後続の手順は変わらない
- **パスワード**: `sudo` を打つときに使う。入力しても画面に何も表示されないのは正常。忘れると面倒なので控えておく

インストールされたことの確認:

```powershell
wsl -l -v
```

```
  NAME            STATE           VERSION
* Ubuntu-24.04    Stopped         2
```

**VERSION が `2` であること**を必ず確認する。`1` になっていると Docker Desktop が連携できない。

ディストロの名前は環境によって `Ubuntu` になることも `Ubuntu-24.04` になることもある。**以降の手順に出てくる `Ubuntu-24.04` は、ここで表示された実際の名前に読み替えること。**

### Ubuntu が 2 つ登録されてしまった場合

エクスプローラーの「Linux」に **`Ubuntu` と `Ubuntu-24.04` の両方**が見えることがある(実測あり)。中身は同じ Ubuntu 24.04 LTS で、**別インストールが 2 つ登録されている**状態。片方だけ残すこと。放置すると Docker Desktop の WSL Integration のトグルが 2 つ並び、「有効にしたのと別のターミナルを開いて `docker` が見つからない」という事故が起きる。

まず、どちらにユーザーが作られているかを確認する(エクスプローラーで覗くとそのディストロは起動するため、`STATE` が `Running` かどうかは判断材料にならない):

```powershell
wsl -d Ubuntu -- ls /home
wsl -d Ubuntu-24.04 -- ls /home
```

- ユーザー名が出たほうがセットアップ済み。片方だけなら、そちらを残す
- **両方に出た場合は、すでに既定(`wsl -l -v` で `*` が付いているほう)を残す**。`--set-default` が不要で手数が減り、名前に版が入っているほうが将来 Ubuntu 26.04 を入れたときに区別しやすい

残すほうで `sudo` が通ることを**削除の前に**確認する(パスワードを別々に設定していた場合の保険):

```powershell
wsl -d Ubuntu-24.04
```

```bash
whoami     # ユーザー名が出る
sudo -v    # パスワードが通れば OK
exit
```

確認できたら、使わないほうを削除する:

```powershell
wsl --unregister Ubuntu
wsl -l -v
```

> **`wsl --unregister` は指定したディストロのファイルシステムを完全に削除する。確認ダイアログは出ない。** 残すほうを間違えて指定していないか、実行前に必ず見直すこと。

`*` の付いた 1 行だけになれば完了。既定になっていないほうを残した場合は `wsl --set-default <名前>` で既定にしておく。

### ターミナルを開く場所に注意

PowerShell から `wsl` と打つと、**そのときの Windows のカレントディレクトリ(`/mnt/c/Users/...`)を引き継いで起動する。** ここで作業してもエラーにはならないが、リポジトリをこの下に clone するとつまずき #4 に直行する。

- **スタートメニューの「Ubuntu 24.04.x LTS」から開く**と、最初から `~`(`/home/<ユーザー名>`)で始まる
- PowerShell から開いた場合は、最初に `cd ~` と打つ

公式ドキュメント → [WSL を使用して Windows に Linux をインストールする](https://learn.microsoft.com/ja-jp/windows/wsl/install)

## 2. `.wslconfig` でメモリ上限を決める

**WSL2 は既定でホストメモリの 50% を上限に確保しにいく。** 32GB 搭載機なら最大 16GB を WSL が握ることになり、ゲームや配信ソフトと同時に使うとメモリを取り合う。上限を明示しておく。

Windows 側のユーザーフォルダ(`C:\Users\<Windowsのユーザー名>\`)に **`.wslconfig`** というファイルを作る。管理者 PowerShell ではなく通常の PowerShell で:

```powershell
notepad "$env:USERPROFILE\.wslconfig"
```

「ファイルが存在しません。作成しますか?」に「はい」と答え、以下を貼り付けて保存する。

```ini
[wsl2]
memory=12GB
swap=4GB
```

| 設定 | 値 | 理由 |
|---|---|---|
| `memory` | `12GB` | このスタック(MySQL + JDK/Gradle + Node)の実測は 4〜6GB 程度。12GB あればビルドが重なっても足り、残り 20GB をゲーム側に残せる |
| `swap` | `4GB` | 上限に達したときに即 OOM で落ちるのを防ぐ緩衝材 |

`processors`(CPU コア数)は**指定しない**。既定で全論理コアが使え、Gradle と Nuxt のビルドはコア数がそのまま速度になるため、制限する利点がない。

設定を反映するには WSL を一度落とす(次に WSL を使うと自動で起動し直す):

```powershell
wsl --shutdown
```

> 補足: WSL 2.0 以降なら `[experimental]` セクションに `autoMemoryReclaim=gradual` を足すと、使い終わったメモリを Windows 側へ徐々に返すようになる。任意。

公式ドキュメント → [WSL の詳細設定構成](https://learn.microsoft.com/ja-jp/windows/wsl/wsl-config)

## 3. Docker Desktop をインストールする

[Docker Desktop 公式ページ](https://www.docker.com/products/docker-desktop/)から Windows 版インストーラをダウンロードして実行する。

**AMD64 版と ARM64 版を聞かれたら AMD64 を選ぶ。** 「AMD64」は CPU メーカーの AMD ではなく **64bit の x86 命令セットの名前**(`x64` と同義)で、Intel の CPU でもこちらを選ぶ。ARM64 は Snapdragon 搭載機専用。判断に迷ったら PowerShell で `echo $env:PROCESSOR_ARCHITECTURE` を実行すると、`AMD64` か `ARM64` かがそのまま表示される。

> コマンドで済ませたい場合は `winget install --id Docker.DockerDesktop -e` でも同じものが入る(アーキテクチャは自動判別される)。

インストール中の選択:

- **「Use WSL 2 instead of Hyper-V」にチェックを入れる**(既定でチェック済みのはず)

インストール後に **Windows を再起動**し、Docker Desktop を起動して以下 2 つを設定する。

**(a) WSL Integration を有効にする ← 忘れやすい**

Settings → **Resources** → **WSL Integration** → **`Ubuntu-24.04` のトグルを ON** → Apply & Restart

これを ON にしないと、Ubuntu のターミナルで `docker` コマンドが見つからない。既定ディストロ以外は自動で有効にならない。

**(b) 自動起動を切る(推奨)**

Settings → **General** → **「Start Docker Desktop when you sign in」を OFF**

既定は ON で、Windows にサインインするたび Docker が常駐してメモリを握る。開発するときだけ手動で起動すれば、ゲーム中は完全に無関係でいられる。

**動作確認** — Ubuntu のターミナル(スタートメニューの「Ubuntu」)で:

```bash
docker version
docker compose version
```

両方バージョンが表示されれば連携できている。

> **ライセンスについて**: Docker Desktop は個人利用・小規模事業者・教育・オープンソース用途では無料だが、**従業員 251 人以上または年間売上 1000 万ドル以上の企業での業務利用には有料サブスクリプションが必要**。個人の学習用 PC なら無料の範囲内。最新の条件は [Docker の公式ライセンス条項](https://docs.docker.com/subscription/desktop-license/)を確認すること。

## 4. VS Code と Windows 側の拡張機能を入れる

[VS Code 公式ページ](https://code.visualstudio.com/)から Windows 版をダウンロードして実行する。

> `winget install --id Microsoft.VisualStudioCode -e` でも同じ。どちらも既定は **User Installer**(管理者権限不要)で、これが望ましい。

**VS Code は Windows 側にだけ入れる。** WSL の中に入れてはいけない。VS Code は「画面は Windows、裏方は WSL やコンテナの中」という分離構造で動くため、本体は 1 つで足りる。

インストール後、拡張機能ビュー(`Ctrl+Shift+X`)で以下 2 つを入れる。**これらは Windows 側にしか入らない拡張**なので、リポジトリの推奨設定では自動化できない。手で入れる。

| 拡張機能 | ID | 役割 |
|---|---|---|
| WSL | `ms-vscode-remote.remote-wsl` | WSL の中のフォルダを開けるようにする |
| Dev Containers | `ms-vscode-remote.remote-containers` | backend コンテナの中に入って Java を書けるようにする |

日本語化したい場合は `ms-ceintl.vscode-language-pack-ja` も追加する(任意)。

**それ以外の拡張(Vue Volar など)は今は入れなくてよい。** 手順 7 でリポジトリを開いたときに「このリポジトリには推奨拡張機能があります」という通知が出るので、そこから一括インストールする(`.vscode/extensions.json` に定義してある)。

## 5. WSL に git を入れて身元を設定する

Ubuntu のターミナルで:

```bash
sudo apt update && sudo apt install -y git
git --version
```

> **Git for Windows は入れないこと。** リポジトリは WSL の中に置くので、Windows 側の git は使われない。むしろ入れて Windows 側で clone してしまうと、改行コードの自動変換(`core.autocrlf=true`)でシェルスクリプトが壊れる(→ つまずき #10)。

次に、コミットに記録される名前とメールアドレスを設定する。

```bash
git config --global user.name "0000masa"
git config --global user.email "134136756+0000masa@users.noreply.github.com"
```

**メールアドレスには GitHub の noreply アドレスを使うこと。** 生のメールアドレスを設定すると、それがコミット履歴に永久に残り、公開リポジトリでは誰でも見られる状態になる。自分の noreply アドレスは GitHub の **Settings → Emails → Keep my email addresses private** の欄に `<ID>+<ユーザー名>@users.noreply.github.com` の形で表示されている。

> このゲーミング PC は個人アカウント専用と決めているので、**グローバル設定をそのまま個人用の身元にしてよい**。会社用アカウントも併用する場合は、グローバルを会社用にしてリポジトリごとに `git config --local user.email ...` で上書きする運用になる(現行の開発機はこの形)。

設定の確認:

```bash
git config --global --list | grep user
```

**任意** — 新しいリポジトリを `git init` で作るときの初期ブランチ名を `main` にしておく:

```bash
git config --global init.defaultBranch main
```

このリポジトリは clone するだけなのでブランチ名はリモート側(`main`)が使われ、**この設定の有無は今回の手順に影響しない。** 効くのは自分で新規プロジェクトを作るときで、設定しないと `master` で始まってしまい、`git init` のたびに既定ブランチ名に関するヒントが表示される。

## 6. GitHub CLI を入れて GitHub に接続する

SSH キーを作らずに、GitHub CLI(`gh`)のブラウザ認証で接続する。**認証は初回の 1 回だけ**で、以後は `git push` のたびに `gh` が裏でトークンを渡すので、毎日ログインする必要はない。

**インストール**(Ubuntu 標準リポジトリの `gh` は古いことがあるため、公式リポジトリを追加する):

```bash
sudo mkdir -p -m 755 /etc/apt/keyrings
wget -qO- https://cli.github.com/packages/githubcli-archive-keyring.gpg \
  | sudo tee /etc/apt/keyrings/githubcli-archive-keyring.gpg > /dev/null
sudo chmod go+r /etc/apt/keyrings/githubcli-archive-keyring.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
  | sudo tee /etc/apt/sources.list.d/github-cli.list > /dev/null
sudo apt update && sudo apt install -y gh
```

公式手順 → [GitHub CLI installation](https://github.com/cli/cli/blob/trunk/docs/install_linux.md)

**認証**:

```bash
gh auth login
```

対話で以下のように答える。

| 質問 | 答え |
|---|---|
| What account do you want to log into? | **GitHub.com** |
| What is your preferred protocol for Git operations? | **HTTPS** |
| Authenticate Git with your GitHub credentials? | **Yes** ← これで git の認証設定まで自動で入る |
| How would you like to authenticate GitHub CLI? | **Login with a web browser** |

ワンタイムコード(`XXXX-XXXX`)が表示されるので控え、Enter を押すとブラウザが開く。コードを貼り付けて認可すれば完了。

> WSL からは Windows のブラウザが自動で開かないことがある。その場合は表示された URL(`https://github.com/login/device`)を Windows 側のブラウザに手でコピーして開けばよい。

**確認**:

```bash
gh auth status
```

`Logged in to github.com account 0000masa` のように出れば成功。

**この認証が何をしたか**: トークンが `~/.config/gh/hosts.yml` に保存され、git の設定に `credential.https://github.com.helper = !gh auth git-credential` が書き込まれた。以後 `git push` すると git が裏で `gh` にトークンを聞きにいくため、認証を意識するのは初回だけになる。

## 7. リポジトリを clone する

**必ず WSL の中(`~/` 配下)に clone すること。** `/mnt/c/...`(Windows 側のドライブ)に置くとファイル I/O が桁違いに遅くなり、ビルドもホットリロードも実用にならない(→ つまずき #4)。

```bash
mkdir -p ~/kenshuu_2025 && cd ~/kenshuu_2025
gh repo clone 0000masa/nuxt-java-practice
cd nuxt-java-practice
```

**VS Code で開く**:

```bash
code .
```

初回は VS Code Server が WSL 内に自動ダウンロードされる(数十秒)。ウィンドウ左下に **`WSL: Ubuntu`** と表示されていれば、WSL の中を開けている。

開いた直後に「**このリポジトリには推奨拡張機能があります**」という通知が右下に出るので、**「インストール」を押して一括で入れる**。入らなかった場合は拡張機能ビューの検索窓に `@recommended` と打てば一覧が出る。

## 8. `.env` を作る

`.env` は秘密情報を含みうるため git 管理外(`.gitignore` 済み)。テンプレートからコピーして作る。

```bash
cp .env.example .env
```

**値の編集は不要**。開発環境用の初期値がそのまま入っている(中身の説明 → [docs/development/README.md](../development/README.md))。

## 9. コンテナを起動する

```bash
docker compose up -d
```

初回はイメージのダウンロードとビルドで 5〜10 分かかる。Gradle の依存ライブラリ取得も走るため、backend が応答するまでさらに数分待つことがある。

```bash
docker compose ps          # 5 つのサービスが Up になっているか
docker compose logs -f backend   # Ctrl+C で抜ける
```

backend のログに `Started AppApplication in ...` が出れば起動完了。

## 10. テスト用データベース `app_test` を作る

**クローン直後に 1 回だけ**必要。これをやらないとテストが全部落ちる。

```bash
docker compose exec mysql mysql -uroot -proot -e "
  CREATE DATABASE IF NOT EXISTS app_test CHARACTER SET utf8mb4;
  GRANT ALL PRIVILEGES ON app_test.* TO 'app'@'%';
  FLUSH PRIVILEGES;"
```

手動でやる理由とテストの方針 → [docs/test/README.md](../test/README.md)

## 11. 動作確認

**URL が開けること**(Windows 側のブラウザからそのまま `localhost` で開ける):

| URL | 期待する表示 |
|---|---|
| http://localhost:3000 | Nuxt のトップページ(投稿一覧) |
| http://localhost:8080/api/posts | 投稿の JSON |
| http://localhost:8025 | Mailpit の受信箱 |
| http://localhost:9001 | MinIO の管理画面(`minioadmin` / `minioadmin`) |

**テストが通ること**(ここまで確認して環境構築の成功とする):

```bash
docker compose exec backend sh ./gradlew test
```

`BUILD SUCCESSFUL` が出れば完了。URL が開けるだけでは DB マイグレーションの失敗を見逃すため、**必ずテストまで通す**。

## 12. Dev Container で backend の Java を書けるようにする

Java のコードはコンテナの中に入って書く。VS Code のコマンドパレット(`Ctrl+Shift+P`)で:

```
Dev Containers: Reopen in Container
```

`.devcontainer/devcontainer.json` が読まれ、backend コンテナに接続される。**初回はコンテナ内に VS Code Server と Java 拡張をダウンロードするため数分かかる**(進捗が止まって見えても待つ)。

詳しい使い方と 2 ウィンドウ運用 → [docs/setup/backend.md](./backend.md) の「日常の開発 — Dev Container で backend コンテナに入る」

## 13. Claude Code を入れる(任意)

このリポジトリは `.claude/skills/` に独自のスキル(`/explain-code`、`/quick-review` など)を git 管理下で持っている。**Claude Code 本体さえ入れれば、設定は clone した時点で揃っている。**

```bash
curl -fsSL https://claude.ai/install.sh | bash
```

ネイティブインストーラなので **Node.js は不要**。シェルを開き直してから、リポジトリのディレクトリで:

```bash
claude
```

初回は認証のためブラウザが開く。

---

## 付録. 別プロジェクト用のツール(このリポジトリには不要)

**ここから下はこのリポジトリの開発には一切不要。** 同じ PC で扱う別プロジェクトのための手順なので、必要になったときだけ読めばよい。

このリポジトリで使わない根拠:

- **Node.js** — コンテナ(`node:22-slim`)が持っている
- **AWS CLI** — AWS へのデプロイは GitHub Actions の OIDC 認証で行う設計で、手元から `aws` を叩く手順は存在しない(→ [docs/infrastructure/README.md](../infrastructure/README.md))
- **Stripe CLI** — このリポジトリは決済を扱わない
- **ngrok** — ローカルをインターネットに公開する必要がない(外部サービスからの webhook を受けないため)

### 共通する前提

1. **すべて WSL(Ubuntu)の中に入れる。** Windows 側には入れない
2. **`unzip` と `curl` が要る。** WSL の Ubuntu は最小構成なので、先に入れておく

   ```bash
   sudo apt update && sudo apt install -y unzip curl
   ```

3. **認証はいずれもブラウザを開くので、先に `wslu` を入れておく。** WSL には Linux 用ブラウザが 1 つも無いため、素のままだと `xdg-open` が `Permission denied` を延々並べて失敗する(実測)。`wslu` が提供する `wslview` を入れると、WSL からの「ブラウザを開け」という要求が **Windows の既定ブラウザ**に転送される

   ```bash
   sudo apt install -y wslu
   echo 'export BROWSER=wslview' >> ~/.bashrc
   exec bash
   wslview https://example.com   # Windows のブラウザが開けば成功
   ```

   入れない場合は、表示された URL を手で Windows 側のブラウザに貼れば認証自体は通る
4. **現行機から認証情報のファイルをコピーしないこと。** `~/.aws/credentials` と `~/.config/stripe/config.toml` は生の秘密情報を含む。ファイル共有や USB を経由すると経路上に平文で残るため、新 PC では必ずログインをやり直して取得する

### Node.js(fnm)

バージョン管理ツール **fnm** を使う(現行の開発機と同じ構成。fnm 1.38.1 / Node 22.14.0)。プロジェクトごとに Node のバージョンを切り替えられるので、Node を直接インストールするより後々の事故が少ない。

`unzip` が無いと `Checking availability of unzip... Missing!` で中断する(実測)。上の共通前提を先に済ませておくこと。

```bash
curl -fsSL https://fnm.vercel.app/install | bash
```

インストーラが `~/.bashrc` に以下を追記する(手で書く場合はこの内容):

```bash
# fnm
FNM_PATH="$HOME/.local/share/fnm"
if [ -d "$FNM_PATH" ]; then
  export PATH="$FNM_PATH:$PATH"
  eval "`fnm env`"
fi
```

シェルを開き直してから Node を入れる:

```bash
exec bash
fnm install 22        # Node 22 LTS
fnm default 22        # 既定に設定
node -v && npm -v
```

別のバージョンが必要になったら `fnm install 20` のように追加し、`fnm use 20` で切り替える。プロジェクト直下に `.nvmrc` があれば `fnm use` だけでその版に合わせられる。

### AWS CLI

**公式インストーラで v2 を入れる。** Ubuntu 24.04 の標準リポジトリには `awscli` パッケージが無く(`apt-cache policy awscli` の候補が `(none)`)、`apt install` では入らない。現行機も公式インストーラ版(`aws-cli/2.31.14` が `/usr/local/bin/aws` に配置)。

```bash
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
unzip awscliv2.zip
sudo ./aws/install
aws --version          # aws-cli/2.x.x と出れば成功
rm -rf aws awscliv2.zip
```

公式ドキュメント → [AWS CLI の最新バージョンをインストールまたは更新する](https://docs.aws.amazon.com/ja_jp/cli/latest/userguide/getting-started-install.html)

#### 認証 — IAM Identity Center(SSO)を優先する

```bash
aws configure sso
```

対話で SSO の開始 URL・リージョン・アカウント・ロール・プロファイル名を聞かれる。

最初に聞かれる **SSO start URL**(`https://d-xxxxxxxxxx.awsapps.com/start` の形)の調べ方は 3 通り:

| 取得元 | 場所 |
|---|---|
| **既存機の設定ファイル**(最速) | `~/.aws/config` の `[sso-session ...]` → `sso_start_url` |
| AWS マネジメントコンソール | **IAM Identity Center** → ダッシュボード → **「AWS アクセスポータルの URL」** |
| ブラウザの履歴・ブックマーク | 普段 AWS にログインするときに開いているポータルの URL そのもの |

コンソールで調べるには先に管理アカウントへサインインしている必要があり、「ログインする URL を調べるためにログインする」という循環になりやすい。既存機が手元にあるなら `~/.aws/config` から転記するのが確実(**このファイルに秘密情報は含まれないので見て構わない**。秘密が入っているのは `~/.aws/credentials` のほう)。

残りの項目(`sso_region` / `sso_account_id` / `sso_role_name` / `region` / `output`)も同じく `~/.aws/config` に揃っている。

**ただし、全部を事前に知っている必要はない。** 認証に失敗するのは次の 2 つを間違えたときだけで、アカウントとロールは**ブラウザ承認後に一覧から選ぶ**形で提示される。

| プロンプト | 一致必須? | 間違えるとどうなるか |
|---|---|---|
| SSO session name | 不要 | ローカルの呼び名。AWS 側に存在しない概念 |
| **SSO start URL** | **必須** | 承認画面に到達できず認証が失敗する |
| **SSO region** | **必須** | 登録リクエストが通らず失敗する |
| SSO registration scopes | 不要 | 既定 `sso:account:access` のままでよい |
| アカウント / ロール | 選ぶだけ | 承認後に一覧で提示される |
| CLI default client Region | 不要 | ログインは成功し、コマンドが別リージョンを見るだけ |
| CLI default output format | 不要 | 表示形式が変わるだけ。空 Enter で `json` |
| CLI profile name | 不要 | `--profile` に書く名前が変わるだけ |

> **最後の `Profile name [AdministratorAccess-<アカウントID>]` で Enter を押すと、この長い既定値がそのままプロファイル名になる**(実測)。最初に入力した「SSO session name」は**セッション名**であってプロファイル名ではないため、`--profile masanori-sso` は `could not be found` になる。短い名前にしたいならここで自分で入力すること。
>
> 押してしまっても `~/.aws/config` のセクション見出しを書き換えるだけでよく、**再ログインは不要**。SSO トークンは `~/.aws/sso/cache/` にセッション名を鍵として保存されており、プロファイル名とは無関係だから。
>
> ```ini
> [profile AdministratorAccess-247064999676]   ← この行を
> [profile masanori-sso]                        ← こう変える
> ```
>
> 編集は `code ~/.aws/config` か `nano ~/.aws/config` で行う。**`.aws` は `.` 始まりの隠しフォルダなので、エクスプローラーの既定では見えない**(表示するなら「表示」→「表示」→「隠しファイル」、またはアドレスバーに `\\wsl.localhost\Ubuntu-24.04\home\<ユーザー名>\.aws` を直接入力)。

> **プロンプトの `[...]` はその項目の既定値。** 何も入力せず Enter を押すとその値が採用される。`[None]` は既定値なしなので必ず入力が要る。`SSO registration scopes [sso:account:access]` はそのまま Enter でよい。

**`SSO region` は「リソースを置くリージョン」ではなく、IAM Identity Center のインスタンスが存在するリージョン。** IAM Identity Center コンソールを開いたときに表示されているリージョンがそれ。次に出てくる `CLI default client Region` とは別物なので混同しないこと(たまたま同じ値になることは多い)。

**`CLI default client Region` は、`aws` コマンドがどのリージョンを操作するかの既定値。** プロファイルの `region` として保存され、`--region` を毎回打たなくて済むようにするためのもの。

```bash
aws ec2 describe-instances                      # 既定リージョンを見る
aws ec2 describe-instances --region us-east-1   # その場だけ上書き
```

間違えても認証は成功するが、**気づきにくい壊れ方**をする。東京リージョンに建てたリソースを既定 `us-east-1` のまま探すと、エラーではなく**空の結果**が返り「作ったはずのものが消えた」と誤解する。このリポジトリで使う値は `ap-northeast-1`(東京)。

関連する例外を 2 つ:

- **IAM・Route 53・CloudFront はこの設定を無視する。** リージョンという概念を持たないグローバルサービスのため
- **CloudFront で使う ACM 証明書だけは `us-east-1` でなければならない。** 独自ドメインの HTTPS を設定するときに踏むので、そのときだけ `--region us-east-1` を付ける

設定の実体は `~/.aws/config` というテキストファイルなので、間違えたら直接開いて直すか、`aws configure sso` をやり直せば上書きされる。壊れるものは無い。

疎通確認:

```bash
aws sts get-caller-identity --profile <プロファイル名>
```

アカウント ID と ARN が返れば成功。SSO のセッションが切れたら `aws sso login --profile <プロファイル名>` で取り直す。

**なぜ SSO を優先するか** — `aws configure`(長期アクセスキー)方式は、失効しない秘密鍵が平文で `~/.aws/credentials` に残り続ける。SSO は数時間で切れる一時認証情報を都度取得するため、ディスクに残るのは失効済みトークンだけになる。このリポジトリが GitHub Actions 側でもアクセスキーを使わず OIDC を選んでいるのと同じ理由(→ [docs/infrastructure/README.md](../infrastructure/README.md))。

#### 認証 — SSO が使えない相手の場合

IAM Identity Center を有効にしていない AWS アカウント(個人アカウントなど)では SSO を使えない。その場合のみ長期アクセスキーを使う。

```bash
aws configure --profile <プロファイル名>
```

IAM でアクセスキーを発行し、Access Key ID / Secret Access Key / リージョン / 出力形式を入力する。

> **この方式を使うときの注意**: 発行したキーは自分で無効化するまで有効。使わなくなったら **IAM から必ず削除する**こと。また、ルートユーザーのアクセスキーは絶対に作らない(IAM ユーザーを作ってそちらに権限を付ける)。

### Stripe CLI

**Stripe 公式の apt リポジトリを追加して入れる**(現行機と同じ経路。`/etc/apt/sources.list.d/stripe.list`)。

```bash
curl -s https://packages.stripe.dev/api/security/keypair/stripe-cli-gpg/public \
  | gpg --dearmor \
  | sudo tee /usr/share/keyrings/stripe.gpg > /dev/null
echo "deb [signed-by=/usr/share/keyrings/stripe.gpg] https://packages.stripe.dev/stripe-cli-debian-local stable main" \
  | sudo tee /etc/apt/sources.list.d/stripe.list > /dev/null
sudo apt update && sudo apt install -y stripe
stripe --version
```

apt リポジトリ経由なので、以後は `sudo apt upgrade` で一緒に更新される。

公式ドキュメント → [Stripe CLI](https://docs.stripe.com/stripe-cli)

#### 認証

```bash
stripe login
```

ペアリングコードが表示され、ブラウザで承認すると `~/.config/stripe/config.toml`(パーミッション `600`)に認証情報が保存される。

疎通確認:

```bash
stripe config --list
```

登録済みのアカウントが表示されれば成功。不要になったら `stripe logout` で削除できる。

#### 保存される認証情報の性質

**有効期限は 90 日。** 設定ファイルに `test_mode_key_expires_at` として失効日が記録される(現行機の実測: ログイン日 `2026-06-11` → 失効日 `2026-09-09`)。放置された鍵が永久に生き続けることはないが、**平文のテキストファイルに 90 日間残る**ことは事実なので扱いは慎重に。

| 方式 | 寿命 |
|---|---|
| AWS SSO の一時認証情報 | 数時間 |
| **Stripe CLI のキー** | **90 日** |
| AWS の長期アクセスキー | 無期限(手で消すまで) |

#### 本番モードのキーが保存されるかはバージョンによる ← 要注意

| CLI のバージョン | `config.toml` に保存されるキー | `--live` で本番を操作できるか |
|---|---|---|
| 1.34.0(実測) | `test_mode_api_key` のみ | **できない**(本番キーが無い) |
| **1.45.1(実測)** | `test_mode_api_key` + **`live_mode_api_key`** | **できる** |

**新しい版では `stripe login` を 1 回実行しただけで、本番の決済データを操作できる状態になる。** 「本番用のログインを別途しなければ安全」という前提は現行版では成り立たない。

コマンドの既定はテストモードなので、うっかり本番を書き換えることは起きにくい。ただし `--live` を付けたコマンドは**実際に本番に届く**。

守り方は 2 つ:

1. **状態を把握する** — `stripe config --list` に `live_mode_api_key` が含まれるかを確認する。含まれていれば「この PC は本番に届く」と認識して扱う
2. **そもそも本番キーを持たせない** — ダッシュボードで**テストモード限定の制限付きキー**を作り、それを貼り付けてログインする

   ```bash
   stripe login --interactive
   ```

   通常の `stripe login`(ブラウザのペアリング)と違い、キーを手で指定する方式。本番キーは保存されないので、`--live` を打っても本番には届かない。本番の決済データを絶対に触らせたくない PC ではこちらを使う

**使わなくなったら `stripe logout` で認証情報を消す。** 90 日の失効を待たずに、`config.toml` からキーを削除できる。

```bash
stripe logout          # 現在のプロファイルの認証情報を削除
stripe logout --all    # すべてのプロファイルの認証情報を削除
```

消えたことは `stripe config --list` で確認する。次に使うときは `stripe login` をやり直せばよい。

打つべき場面は主に 3 つ:

- そのプロジェクトの作業が当分ない
- PC を手放す・他人に渡す
- `stripe config --list` に `live_mode_api_key` があり、この PC に本番キーを置きたくないと判断した

> `stripe logout` が消すのは**手元のファイルにあるキー**。Stripe 側に登録された鍵そのものを確実に無効化したいなら、Stripe ダッシュボードの API キー画面から該当のキーを revoke すること。

`stripe listen --forward-to ...`(webhook の転送)などの実際の使い方は、転送先 URL も webhook のパスもプロジェクトごとに違うため、**そのプロジェクトのリポジトリ側に書くこと**。この手順書の役割は「PC を使える状態にする」までとする。

### ngrok

**WSL の中で動いているサーバーを、一時的にインターネットから見えるようにするツール。** 外部サービスからの webhook をローカルで受け取る、スマートフォンの実機から確認する、といった用途で使う。

**公式の apt リポジトリを追加して入れる。**

```bash
sudo mkdir -p -m 755 /etc/apt/keyrings
curl -sSL https://ngrok-agent.s3.amazonaws.com/ngrok.asc \
  | sudo gpg --dearmor -o /etc/apt/keyrings/ngrok.gpg
echo "deb [signed-by=/etc/apt/keyrings/ngrok.gpg] https://ngrok-agent.s3.amazonaws.com buster main" \
  | sudo tee /etc/apt/sources.list.d/ngrok.list > /dev/null
sudo apt update && sudo apt install -y ngrok
ngrok version
```

> リポジトリ行の `buster` は Debian のコードネームだが、**ngrok は全ディストリビューション共通でこの 1 つを使う**。Ubuntu 24.04 でもそのまま指定する(公式手順どおり)。

公式ドキュメント → [ngrok のインストール](https://ngrok.com/docs/getting-started/)

#### 設定 — authtoken を登録する

アカウント作成済みなら、[ダッシュボードの Your Authtoken ページ](https://dashboard.ngrok.com/get-started/your-authtoken)にトークンが表示されている。それを登録する。

```bash
ngrok config add-authtoken <トークン>
```

保存先は `~/.config/ngrok/ngrok.yml`(パーミッション `600`)。中身はこの形になる(現行機で確認):

```yaml
version: "3"
agent:
    authtoken: ...
```

確認:

```bash
ngrok config check
```

> **authtoken は秘密情報。** アカウントに紐づく鍵なので、コピーして共有したりリポジトリに入れたりしないこと。漏れたらダッシュボードから再発行する。

#### 使い方

WSL の中で動いているサーバー(例: ポート 3000)を公開する:

```bash
ngrok http 3000
```

`Forwarding https://xxxx.ngrok-free.app -> http://localhost:3000` のように公開 URL が表示される。**`Ctrl+C` で停止するまで公開され続ける。**

Docker コンテナで動かしているサーバーも、`ports:` で WSL 側に公開されていれば同じように指定できる。

無料プランでも**静的ドメインを 1 つ**使えるので、URL が毎回変わると困る場合(webhook の登録先など)はダッシュボードの Domains で取得する。指定するコマンドはダッシュボードに表示されるものをそのまま使うのが確実(オプション名がバージョンで変わっている)。

> **セキュリティ上の注意 — 起動中は、その URL を知っていれば誰でもアクセスできる。** 認証はかからない。開発用の管理画面やデバッグ用エンドポイントも一緒に公開されるので、**用が済んだら必ず `Ctrl+C` で止めること。** 起動しっぱなしで放置しない。

---

## つまずきポイント集

### 1. WSL 内で `docker: command not found`

Docker Desktop の **WSL Integration が OFF**。Settings → Resources → WSL Integration → `Ubuntu-24.04` のトグルを ON にして Apply & Restart(手順 3-a)。

既定ディストロ以外は自動で有効にならないため、ここを踏むケースが多い。**ディストロが 2 つ登録されている場合**(→ 手順 1)、ON にしたのと別のほうのターミナルを開いていることもある。`wsl -l -v` で今どれを使っているか確認すること。

### 2. `docker compose up` で `variable is not set` の警告が大量に出る

`.env` が無い。`.env` は `.gitignore` 済みなので clone では付いてこない。

```bash
cp .env.example .env
```

### 3. テストが全部落ちる(`Unknown database 'app_test'`)

テスト用データベースが未作成。手順 10 を実行する。Flyway はテーブルを作れるが **database 自体は作れない**ため、この 1 回だけ手作業が要る(理由 → [docs/test/README.md](../test/README.md))。

### 4. ビルドが異常に遅い / ファイルを保存しても反映されない

リポジトリを `/mnt/c/...`(Windows 側)に置いている。WSL から Windows のファイルシステムへのアクセスは 9P プロトコル経由で、**同じ操作が 10 倍以上遅くなる**。さらにファイル変更通知が届かず、Nuxt の HMR や Java の自動コンパイルが動かない。

```bash
pwd   # /home/<ユーザー名>/... で始まっていれば正しい
```

`/mnt/c/` で始まっていたら、`~/kenshuu_2025/` 配下に clone し直す(手順 7)。

### 5. ゲームの調子がおかしくなった

WSL2 を有効にすると、Windows は裏で**仮想マシンプラットフォーム(Hyper-V 基盤)を常時 ON** にする。これはゲーム側に副作用が出ることがある。

| 影響 | 内容 |
|---|---|
| アンチチートとの相性 | 仮想化を検知するアンチチート(Riot Vanguard など)で、有効化後に起動できない・警告が出るという報告がある。現在は多くが対応済みだがタイトルによる |
| わずかな性能低下 | 仮想化ベースセキュリティが有効な状態でフレームレートが数%落ちるという計測例がある |
| メモリの常時確保 | Docker Desktop を起動しっぱなしだと WSL が数 GB を握り続ける |
| VirtualBox との共存 | 6.1 より前の VirtualBox は Hyper-V と共存できない |

**厄介なのは、この副作用が環境構築の翌日以降、まったく無関係な場面(ゲーム起動時)で表面化すること。** 原因を WSL に結びつけられずに時間を溶かしやすいので、心当たりがあればこの順で切り分ける。

1. `wsl --shutdown` — WSL とコンテナを丸ごと停止する。開発を再開するときは `docker compose up -d` でよい
2. Docker Desktop の **「Start Docker Desktop when you sign in」を OFF**(手順 3-b)。開発時だけ手動起動すれば、ゲーム中は無関係になる
3. それでも駄目なら、Windows の「機能の有効化または無効化」から**「仮想マシン プラットフォーム」を一時的に OFF**(要再起動)。WSL は使えなくなるので、開発するときは戻す

### 6. `./gradlew: Permission denied`

`gradlew` の実行ビットが落ちているため、このリポジトリでは**全箇所 `sh ./gradlew` と書いて回避している**。`./gradlew` と打っていないか確認する。

経緯と詳細 → [docs/setup/backend.md](./backend.md)、[docs/notes/file-permissions-and-exec-bit.md](../notes/file-permissions-and-exec-bit.md)

### 7. Dev Container の初回起動が終わらない

**数分かかるのが正常。** コンテナの中に VS Code Server と Java 拡張一式をダウンロードしているため。進捗表示が止まって見えても待つ。2 回目以降は数秒で開く。

### 8. `docker compose up` がポート競合で失敗する

このリポジトリは `3000` / `8080` / `3306` / `9000` / `9001` / `1025` / `8025` を使う。ゲーミング PC は用途が雑多なので、過去に入れた MySQL や XAMPP、配信ソフトのローカルサーバーがこれらを掴んでいることがある。

Windows 側の PowerShell で使用中のプロセスを調べる:

```powershell
netstat -ano | findstr :3306
```

最後の列が PID なので、タスクマネージャーの「詳細」タブで PID から犯人を特定して止める。

### 9. WSL がメモリを握りっぱなしになる

```powershell
wsl --shutdown
```

恒久対策は手順 2 の `.wslconfig`。設定済みでも上限まで確保したまま返さないことがあるため、そのときはこのコマンドで一度落とす。

### 10. 原因不明で `gradlew` やシェルスクリプトが壊れる

**Git for Windows を入れて、Windows 側で clone してしまった**ケース。Git for Windows は既定で `core.autocrlf=true` になっており、チェックアウト時に改行を LF から CRLF に変換する。シェルスクリプトの行末に `\r` が付くと、Linux 側では `bad interpreter` などの意味不明なエラーになる。

リポジトリも Docker も正常に見えるのに `gradlew` だけ動かない、という症状で原因の見当がつかない。

**予防策: git は WSL 側にだけ入れる**(手順 5)。すでに入れてしまった場合は、`~/` 配下に clone し直すこと。

### 11. `~/.aws` がエクスプローラーでフォルダに見えない / 中身が Windows 側にある

```bash
ls -ld ~/.aws ~/.azure
```

先頭が `l` なら**シンボリックリンク**で、実体は `/mnt/c/Users/<Windowsユーザー名>/.aws` にある(実測)。エクスプローラーが WSL のシンボリックリンクをフォルダーではなく「ファイルのような項目」として表示するため、`.aws` が見つからないという症状になる。

**動作はするが、AWS の認証情報と SSO トークンが WSL ではなく Windows のファイルシステムに保存される。** Windows 上の任意のプロセスから読める場所であり、Unix のパーミッション(`600`)も drvfs 越しでは実効性が薄い。長期アクセスキーを置くなら望ましくない。

**原因(推定)**: Docker Desktop の WSL Integration。かつて ECS(AWS)と ACI(Azure)へのデプロイ機能を持っていた名残で、連携を有効にしたときに `.aws` と `.azure` を Windows 側へリンクする。対象がこの 2 つだけで、Azure CLI を入れていなくても空の `.azure` が作られる点が特徴。**リンク先が既に実ディレクトリとして存在する場合は上書きしない**(現行機は `.aws` が実ディレクトリ、`.azure` だけリンクという状態で確認)。

この手順書は Docker Desktop(手順 3)を AWS CLI(付録)より先に入れるため、`~/.aws` が存在しないタイミングで連携が有効になり、リンクが作られる。**コマンドではなく順序に起因する。**

**対処** — 実ディレクトリに戻す。すべて WSL(Ubuntu)のターミナルで実行する(`/mnt/c/...` は WSL から見た Windows の C ドライブなので、PowerShell に切り替える必要はない):

```bash
cp -r /mnt/c/Users/<Windowsユーザー名>/.aws ~/aws-backup      # 中身を退避
rm ~/.aws                                                     # リンクを外す(リンク先は消えない)
mkdir -p ~/.aws
cp -r ~/aws-backup/. ~/.aws/
find ~/.aws -type d -exec chmod 700 {} \;
find ~/.aws -type f -exec chmod 600 {} \;
ls -ld ~/.aws                                                 # 先頭が d になっていること
aws sts get-caller-identity --profile <プロファイル名>
```

`cp` はコピーなので、`/mnt/c/...` 側の原本は残る。疎通確認が済むまでは消さずに残しておけば、失敗しても取り直せる。確認後に `rm -rf ~/aws-backup` で退避先を片付ける。

> 退避先に `/tmp` を使わないのは、Ubuntu の systemd が `/tmp` の中身を **30 日で自動削除する**ため(`D /tmp 1777 root root 30d`)。電源を落としても消えはしないが、放置した退避データが黙って消える場所に置く必要はない。

> **`rm ~/.aws` は末尾にスラッシュを付けないこと。** `rm -rf ~/.aws/` と書くとリンクを辿って Windows 側の中身を消す。ここでやりたいのは「リンクという名の矢印を捨てる」ことだけ。

成功すると `~/.aws` が `drwx------`(700)の実ディレクトリになり、`aws sts get-caller-identity` がアカウント ID と ARN を返す。**再ログインは不要**(SSO トークンのキャッシュも一緒に移動しているため)。

**確認と後片付け**(この順で行う):

1. **リンクが復活しないことを確かめる** — Docker Desktop を再起動してから:

   ```bash
   ls -ld ~/.aws     # 先頭が d のままなら恒久的に直っている
   ```

   `l` に戻るなら原因の推定が外れているので、対処を考え直す必要がある。

2. **`.azure` も外す** — 中身を確認してから消す:

   ```bash
   ls -la /mnt/c/Users/<Windowsユーザー名>/.azure
   rm ~/.azure      # 空(. と .. だけ)ならリンクを消すだけでよい
   ```

   中身がある場合は、`.aws` と同じ手順で WSL 内の実ディレクトリに移すこと。

3. **退避先と Windows 側の原本を消す** — 手順 1 が確認できてから:

   ```bash
   rm -rf ~/aws-backup
   rm -rf /mnt/c/Users/<Windowsユーザー名>/.aws
   ```

   Windows 側の削除は、**Windows 版の AWS CLI を使う予定がない**ことが前提。使うなら残す(その場合は認証情報が Windows 側にも存在し続ける点を意識しておく)。

---

## 関連ドキュメント

- [docs/development/README.md](../development/README.md) — docker-compose 環境の構成(5 コンテナ、ポート、環境変数)
- [docs/setup/backend.md](./backend.md) — Spring Boot の構築手順と Dev Container の使い方
- [docs/setup/frontend.md](./frontend.md) — Nuxt の構築手順
- [docs/test/README.md](../test/README.md) — テストの実行方法と `app_test` の作り方
- [docs/notes/java-dev-env-comparison.md](../notes/java-dev-env-comparison.md) — なぜホストに JDK を置かない構成にしたか
