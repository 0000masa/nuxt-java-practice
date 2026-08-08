# 新しい PC に開発環境を作る手順(Windows 11)

別の Windows PC で、このリポジトリの開発環境をゼロから再現する手順。Docker / VS Code / git / GitHub 接続がまったく無い状態を出発点にする。

対象は **Windows 11 + WSL2 + Docker Desktop**。既存の開発機と同じ構成なので、構築後はコマンドもパスもすべて共通になる。

> **未検証の注記**: 手順 1〜4(WSL / `.wslconfig` / Docker Desktop / VS Code)は Windows 側の操作のため、この文書を書いた環境からは実行して確認できていない。各社の公式ドキュメントを根拠に記述しており、公式へのリンクを併記してある。手順 5 以降は既存の開発機と同一の内容。実際に構築したときに差異があれば、この文書を直すこと。

> Windows 10 を使う場合、手順 1 だけが大きく異なる(仮想マシンプラットフォームの手動有効化とカーネル更新が必要)。[Microsoft の公式手順](https://learn.microsoft.com/ja-jp/windows/wsl/install-manual)に従ったうえで、手順 2 から合流すること。

## 0. 全体像 — 何をどの層に入れるか

この構成は **3 つの層**に分かれる。どの層に何を入れるかを取り違えると「インストールしたのに使えない」が起きるので、最初に把握しておく。

```
┌─ ① Windows(ホスト) ────────────────────────────┐
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

**重要なのは、Node.js も JDK も MySQL もホストにインストールしないこと。** すべて ③ のコンテナが持っている。ホストに必要なのは Docker と VS Code と git だけ(設計の理由 → [docs/notes/java-dev-env-comparison.md](../notes/java-dev-env-comparison.md))。

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

## 4. VS Code とホスト側の拡張機能を入れる

[VS Code 公式ページ](https://code.visualstudio.com/)から Windows 版をダウンロードして実行する。

> `winget install --id Microsoft.VisualStudioCode -e` でも同じ。どちらも既定は **User Installer**(管理者権限不要)で、これが望ましい。

**VS Code は Windows 側にだけ入れる。** WSL の中に入れてはいけない。VS Code は「画面は Windows、裏方は WSL やコンテナの中」という分離構造で動くため、本体は 1 つで足りる。

インストール後、拡張機能ビュー(`Ctrl+Shift+X`)で以下 2 つを入れる。**これらはホスト側にしか入らない拡張**なので、リポジトリの推奨設定では自動化できない。手で入れる。

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

## 付録. ホスト側に Node.js を用意する(このリポジトリには不要)

**このリポジトリの開発には不要**(Node はコンテナが持っている)。ホストで直接 Next.js などを動かす別プロジェクト用の手順。

バージョン管理ツール **fnm** を使う(現行の開発機と同じ構成。fnm 1.38.1 / Node 22.14.0)。プロジェクトごとに Node のバージョンを切り替えられるので、Node を直接インストールするより後々の事故が少ない。

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

---

## 関連ドキュメント

- [docs/development/README.md](../development/README.md) — docker-compose 環境の構成(5 コンテナ、ポート、環境変数)
- [docs/setup/backend.md](./backend.md) — Spring Boot の構築手順と Dev Container の使い方
- [docs/setup/frontend.md](./frontend.md) — Nuxt の構築手順
- [docs/test/README.md](../test/README.md) — テストの実行方法と `app_test` の作り方
- [docs/notes/java-dev-env-comparison.md](../notes/java-dev-env-comparison.md) — なぜホストに JDK を置かない構成にしたか
