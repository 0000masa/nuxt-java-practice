# OS のユーザーとファイルの置き場所 — なぜ分かれているのか、どこに置くべきなのか

「Windows も Linux もユーザーごとにフォルダを作る方式なのはなぜか」という疑問から出発して、インストール先の使い分け、`C:\` 直下にフォルダを作ると何が起きるか、Linux サーバーへのアプリ配置、AWS CLI の認証情報の置き場所までを、手元の PC で実測しながら整理した学習メモ。

要点は 5 つ。

1. **OS のユーザーは「人」ではなく「権限の主体」。** 手元の WSL には 28 個のユーザーがいるが、そのうち **25 個はログインできない**(実測)。人間は 1 人しかいない
2. **インストール先の分岐点は 1 つだけ** — 「書き込む先のフォルダに自分の権限があるか」。全体インストールとユーザー別インストールの違いはこれに尽きる
3. **`C:\` 直下に作ったフォルダは、他のユーザーから読めるどころか書き換えもできる**(実測)。一方 `C:\Users\<ユーザー名>` には他の一般ユーザーは**入れない**(実測)。だからユーザー配下に作るほうがいい
4. **Linux サーバーへの配置では、そのアプリ専用のユーザーを作るのが定石。** 人が使うためではなく、乗っ取られたときの被害範囲を縛るため
5. **AWS CLI の認証情報がユーザーごとに分かれるのは事実だが、「ユーザーで分ける」が目的ではない。** 分離の軸は 3 層あり、サーバー・CI での正解は「そもそも置かない」

権限そのもの(`rwx` の 9 ビットがどこに記録されているか、`umask` とは何か)は [file-permissions-and-exec-bit.md](./file-permissions-and-exec-bit.md) で扱った。ここではその上の階層、**「誰が」に当たるユーザーの話**を扱う。

## この文書での「ユーザー」の呼び分け

このテーマでは「ユーザー」という言葉が 3 つの別物を指してしまう。以下、必ず次のように呼び分ける。

| 呼び方 | 何を指すか | 例 |
|---|---|---|
| **OS ユーザー** | OS がファイルやプロセスの権限を判定するために持つアカウント | `masanoriadachi`(uid 1000)、`www-data`、Windows の `masanori.adachi` |
| **IAM ユーザー** | AWS 側のアカウント。OS とは無関係 | IAM コンソールで作るユーザー |
| **アプリのユーザー** | このアプリにメールとパスワードで登録した利用者 | [CONTEXT.md](../../CONTEXT.md) の「ユーザー(User)」 |

この 3 つはまったく別のもので、対応関係もない。以降、単に「ユーザー」と書いたら **OS ユーザー**を指す。

## 1. OS ユーザーとは「権限の主体」であって、人ではない

最初にひっくり返しておきたい前提がある。**OS ユーザー = パソコンを使う人間、ではない。**

OS から見たユーザーの正体は、**ファイルの所有者やプロセスの実行者として権限判定に使われる ID** でしかない。Linux では `uid`(数値)、Windows では `SID`(文字列)がその実体で、名前は人間向けの表示にすぎない。

実測。まず Linux 側:

```
$ id
uid=1000(masanoriadachi) gid=1000(masanoriadachi) groups=1000(masanoriadachi),27(sudo),1001(docker)
```

`uid=1000` が本体で、`masanoriadachi` はその表示名。Windows 側も構造は同じ:

```
$ whoami /user
User Name                       SID
=============================== ==============================================
desktop-tennuhg\masanori.adachi S-1-5-21-4090821595-3417302871-1960351343-1002
```

### 手元の PC にいるユーザーの大半は人間ではない

`/etc/passwd` には OS ユーザーが 1 行 1 件で並んでいる。この WSL 環境で数えると:

```
$ wc -l < /etc/passwd
28
$ awk -F: '$7 ~ /(nologin|false)$/' /etc/passwd | wc -l
25
```

**28 個のユーザーのうち 25 個は、ログインシェルが `nologin` / `false` に設定されていてログインできない。**(`/etc/passwd` は `:` 区切りで、7 番目のフィールドがログインシェル。`awk` でそこだけを見ている)中身を見ると理由がわかる:

```
$ grep -E "^(root|daemon|www-data|nobody|masanoriadachi):" /etc/passwd
root:x:0:0:root:/root:/bin/bash
daemon:x:1:1:daemon:/usr/sbin:/usr/sbin/nologin
www-data:x:33:33:www-data:/var/www:/usr/sbin/nologin
nobody:x:65534:65534:nobody:/nonexistent:/usr/sbin/nologin
masanoriadachi:x:1000:1000:,,,:/home/masanoriadachi:/bin/bash
```

`www-data`(Web サーバー用)、`daemon`、`nobody` — これらは**プログラムを動かすために存在するユーザー**で、対応する人間はいない。実際に動いているプロセスを見ると、それぞれ別のユーザーで動いていることがわかる:

```
$ ps -eo user:16,comm --sort=user
USER             COMMAND
masanoriadachi   systemd
messagebus       dbus-daemon
polkitd          polkitd
root             systemd
syslog           rsyslogd
systemd-resolve  systemd-resolve
systemd-timesync systemd-timesyn
```

DBus には `messagebus`、ログ収集には `syslog`、名前解決には `systemd-resolve` と、**サービスごとに専用ユーザーが割り当てられている**。これが後の「サーバーへの配置」の章で効いてくる。

つまり `/etc/passwd` は「この PC を使う人の名簿」ではなく、**「この OS の中で権限を持ちうる主体の一覧」**。人間はそのうちの一部でしかない。

### ホームディレクトリは「その主体の作業場所」

各ユーザーには**ホームディレクトリ**が割り当てられる(`/etc/passwd` の 6 番目の項目)。人間のユーザーなら `/home/masanoriadachi`、`www-data` なら `/var/www`、`nobody` は `/nonexistent`(存在しないパス — 作業場所が要らないので)。

Linux ではこのパスが環境変数 `HOME` に入り、`~` はその展開結果として扱われる:

```
$ echo $HOME
/home/masanoriadachi
```

Windows なら `C:\Users\<ユーザー名>` が同じ役割で、環境変数 `USERPROFILE` に入る。

**このあと出てくる「ユーザー配下に置く」は、すべて「このホームディレクトリの下に置く」という意味。** そして `~/.aws` のようなパスがユーザーごとに別物になるのは、`~` の展開先がユーザーごとに違うから、というだけの話になる。

## 2. なぜユーザーが分かれているのか

「1 台の PC を複数人で使うため」は理由の一部でしかない。一人しか使わない PC でもユーザーは分かれている。本当の理由は 3 つある。

### 理由 1: 隔離 — 壊せる範囲を限定する

あるユーザーの権限で動いているプログラムが暴走しても、そのユーザーが書き込める場所しか壊せない。OS の本体(`/usr`、`C:\Windows`)は別の所有者(`root` / `TrustedInstaller`)のものなので手を出せない。

実測。共有領域の所有者はすべて `root` になっている:

```
$ ls -ld /usr/bin /usr/local/bin /opt /etc /root
drwxr-xr-x  2 root root  36864 /usr/bin
drwxr-xr-x  2 root root   4096 /usr/local/bin
drwxr-xr-x  2 root root   4096 /opt
drwxr-xr-x 99 root root   4096 /etc
drwx------  7 root root   4096 /root
```

`drwxr-xr-x` の後半 `r-x` `r-x` は「グループとその他は読める・入れるが、**書けない**」。だから一般ユーザーが `/usr/bin` にファイルを置こうとすると `Permission denied` になり、`sudo` が要る。

### 理由 2: 責任の追跡 — 誰がやったかを OS が記録できる

ファイルの所有者もプロセスの実行者もユーザー単位で記録される。全部が同じユーザーで動いていたら、ログを見ても「何が」書いたのか区別がつかない。

### 理由 3: 最小権限 — プログラムに必要な分だけ渡す

これが `www-data` のような**人間のいないユーザー**が存在する理由。Web サーバーを `root` で動かすと、脆弱性を突かれた瞬間に OS 全体を取られる。専用ユーザーで動かしておけば、取られてもそのユーザーが触れる範囲で止まる。

**ユーザーを分ける目的は「人を分ける」ことではなく「権限を分ける」こと**、と捉えるとこの先が全部つながる。

### 補足: Windows の管理者は、いつも管理者として動いているわけではない

Windows で自分が Administrators グループに入っていても、**普段のプロセスは管理者権限を外した状態で動いている**(UAC)。実測すると、Administrators は「拒否のみに使用するグループ」として無効化されている:

```
$ whoami /groups
BUILTIN\Administrators   エイリアス   S-1-5-32-544   拒否のみに使用するグループ
```

「管理者として実行」や UAC のダイアログで承認すると、初めて Administrators が有効なプロセスが起動する。**管理者アカウントでも、昇格していないプロセスは一般ユーザーと同じ権限で動く** — インストーラが権限を要求してくる場面はこれが理由。

Linux の `sudo` も発想は同じで、普段は uid 1000 のまま、必要なコマンドだけ root として実行する。

## 3. インストール先を分ける基準は「書き込む先に権限があるか」だけ

「すべてのユーザー向けインストール」と「このユーザーのみのインストール」は、機能の違いではない。**ファイルをどこに書くかの違いで、そこから権限の要否がすべて決まる。**

| | 全体インストール | ユーザー別インストール |
|---|---|---|
| 書き込む先(Windows) | `C:\Program Files` | `%LOCALAPPDATA%\Programs` |
| 書き込む先(Linux) | `/usr/local/bin`、`/opt` | `~/.local`、`~/.config` 配下 |
| 管理者権限 | **要る**(UAC / `sudo`) | **要らない** |
| 誰が使えるか | 全ユーザー | インストールした人だけ |
| 他ユーザーへの影響 | ある(バージョンが共有される) | ない(各自が別バージョンを持てる) |
| 設定の保存先 | どちらもユーザー配下(`~/.config` など) | 同左 |

最後の行が見落としやすい。**全体インストールしたソフトでも、設定ファイルは各ユーザーのホーム配下に作られる。** プログラム本体は共有、設定は個別、という分業になっている。

### `/usr/bin` と `/usr/local/bin` の違い — 持ち主が違う

Linux 側の共有領域には、表に挙げなかったものがもう 1 つある。`/usr/bin` だ。ここも root 所有の共有領域だが、**`/usr/local/bin` とは持ち主が違う。**

| | `/usr/bin` | `/usr/local/bin` |
|---|---|---|
| 誰が置くか | **`apt` などのパッケージ管理システム** | **人間**(管理者が手で入れたもの) |
| パッケージ更新の影響 | 上書き・削除される | **触られない** |
| `dpkg` が中身を把握しているか | している | していない |
| 件数(この環境の実測) | 1035 | 12 |

`/usr/local` の `local` は「このマシンで独自に入れたもの」の意味。**パッケージ管理システムが管理する領域と、人間が手で入れたものの領域を分けておくための境界線**で、これがないと `apt upgrade` のたびに手で入れたファイルが消えたり衝突したりする。

実測すると、持ち主の違いがはっきり出る:

```
$ dpkg -S /usr/bin/git
git: /usr/bin/git                                       ← apt が把握している

$ dpkg -S /usr/local/bin/aws
dpkg-query: no path found matching pattern /usr/local/bin/aws
                                                        ← apt は知らない = 手で入れたもの
```

`/usr/local/bin` の中身を見ると、確かに手で入れたものばかりが並んでいる:

```
$ ls /usr/local/bin
aws  aws_completer  cagent  corepack  kubectl  n  node  npm  npx  sam  session-manager-plugin  stripe.old
```

### 同名のコマンドがあったらどちらが動くか — PATH の順序で決まる

2 つの領域に同じ名前のコマンドがあった場合、**先に見つかったほうが実行される**。`PATH` は「実行ファイルを探すディレクトリの並び」で、前から順に探すため。

```
$ echo $PATH | tr ':' '\n'
...
/usr/local/bin      ← 11 番目
/usr/sbin
/usr/bin            ← 13 番目
```

**`/usr/local/bin` のほうが前にある。** これは意図された順序で、「手で入れた新しいバージョンが、パッケージ版より優先される」ようになっている。

この環境には `node` が 3 つあり、実際に優先順位が働いている:

```
$ which -a node
/run/user/1000/fnm_multishells/1893_.../bin/node    ← これが実行される(PATH の 2 番目)
/run/user/1000/fnm_multishells/896_.../bin/node
/usr/local/bin/node
```

`which` は最初の 1 つ、`which -a` は全部を表示する。**「入れたはずのバージョンと `node -v` の結果が食い違う」ときは、たいていこの優先順位が原因**なので、`which -a` で重複を疑う。

なお、この住み分けは慣習であって OS が強制するものではない。実測でも例外が 1 つ見つかった:

```
$ dpkg -S /usr/local
session-manager-plugin: /usr/local     ← deb パッケージなのに /usr/local へ入れている
```

AWS の session-manager-plugin は `.deb` で配られながら `/usr/local` に置く作りになっている。**慣習を破るパッケージも実在する**、という例。

まとめると、**手動インストールは `/usr/local/bin`(単体の実行ファイル)か `/opt`(ディレクトリ一式)に置き、`/usr/bin` には置かない。**

### 実測: 同じ PC の中に両方が共存している

この WSL 環境では、AWS CLI と Node.js が対照的な入り方をしている。

```
$ which aws
/usr/local/bin/aws          ← 共有領域。インストールに sudo が要った
$ which node
/run/user/1000/fnm_multishells/59204_1786189542574/bin/node
                            ← パスに uid の 1000 が入っている。完全にユーザー専用
```

Node.js は fnm(バージョン管理ツール)経由で入れているため、実体はユーザー配下にある。`npm -g` のインストール先も同様:

```
$ npm config get prefix
/home/masanoriadachi/.local/share/fnm/node-versions/v22.14.0/installation
```

**`npm install -g` の `-g`(global)は「OS 全体に」という意味ではなく「このプロジェクトではなく、この Node 環境全体に」という意味。** 書き込む先がユーザー配下なので `sudo` は要らない。逆に、Node.js を `apt` で入れていると `-g` の先が `/usr/lib/node_modules` になり `sudo` が必要になる — 同じコマンドなのに要否が変わるのは、インストール方法によって書き込む先が変わるから。

`/run/user/1000` の権限も見ておくと、思想がはっきりする:

```
$ ls -ld /run/user/1000
drwx------ 8 masanoriadachi masanoriadachi 760 /run/user/1000
```

`drwx------` = root を除く他のユーザーは読むことすらできない。ディレクトリ名が uid そのものになっているのも、これが「uid 1000 専用の領域」だから。

Windows 側でも同じ二分法が実測できる:

```
$ icacls "C:\Program Files"
C:\PROGRA~1 NT SERVICE\TrustedInstaller:(F)
            BUILTIN\Administrators:(M)
            BUILTIN\Users:(RX)              ← 一般ユーザーは読み・実行のみ。書けない
```

```
$ ls C:\Users\masanori.adachi\AppData\Local\Programs
Antigravity
Microsoft VS Code
Notion
cursor
```

**VS Code も Cursor も Notion も、`Program Files` ではなくユーザー配下に入っている。** これらは意図的にユーザー別インストールを既定にしているツールで、そのおかげで管理者権限のない環境でも入り、自動アップデートも昇格なしで走る。

### では、全体インストールされるのは何か — 基準はサイズではない

同じ PC の `Program Files` を見ると、性格のはっきり違う顔ぶれが並んでいる:

```
$ ls "C:\Program Files"
DBeaver  DIFX  Docker  Google  HP  HPCommRecovery  Hyper-V  Intel
WSL  Windows Defender  WindowsApps  WindowsPowerShell  nodejs ...
```

ユーザー配下(`VS Code` / `Cursor` / `Notion`)と見比べると、線引きが読み取れる。**全体インストールになるのは、「そうしないと機能しないもの」**であって、大きいものではない。

| 全体インストールになる理由 | この PC での実例 |
|---|---|
| **OS の機能そのもの** — カーネルや OS の一部として組み込まれる | `WSL`、`Hyper-V`、`Windows Defender` |
| **デバイスドライバ** — ハードウェアを扱うのでカーネル空間に入る | `Intel`、`HP`、`DIFX` |
| **サービスとして常駐する** — ログインしていなくても動く必要がある | `Docker`(バックグラウンドサービス + WSL/Hyper-V 連携) |
| **全ユーザーで共有したい** — 各自が別々に持つ意味がない | `nodejs`(MSI 版)、`DBeaver`、`HeidiSQL` |

いずれも**管理者権限が必要な操作を含んでいる**のが共通点。ドライバはカーネルに登録し、サービスは OS のサービス一覧に登録し、OS 機能は `C:\Windows` を触る。ユーザー配下に置いても、これらの登録はできない。**「全体インストールを選んだ」のではなく「そうするしかない」**という順序になっている。

裏を返すと、**エディタやチャットアプリのように「起動して使うだけ」のソフトには全体インストールにする理由がない。** VS Code や Cursor がユーザー別を既定にしているのはそのため。

### サイズは基準になるのか — ならない

ゲームのような容量の大きいものが全体インストールになるのでは、という発想は自然だが、**サイズと権限は無関係**。10GB のソフトでもドライバを積まないならユーザー配下に置ける。

実際、大きいソフトは**第 3 の選択肢**を取ることが多い。プログラム本体と巨大なデータを分け、データの置き場所をユーザーに選ばせる方式で、Steam がその典型:

| | 置き場所 | サイズ |
|---|---|---|
| Steam 本体 | `C:\Program Files (x86)\Steam`(既定) | 数百 MB |
| ゲームのデータ | ライブラリフォルダ(**任意のドライブを指定可能**) | 数十 GB〜 |

つまり容量が大きいことは、**全体インストールを選ぶ理由ではなく、「置き場所を選べるようにする」理由**になっている。同じ発想は Docker のイメージ置き場や、動画編集ソフトのキャッシュ設定にも出てくる。

なお、容量が大きいものをユーザー配下に置くと問題になる環境は実在する。**企業でよく使われる移動ユーザープロファイル**(ログインのたびにユーザー配下をネットワーク経由で同期する仕組み)がそれで、この場合は大きなデータをユーザー配下から外す。ただしこれは「サイズ」ではなく「その環境がユーザー配下を同期対象にしているから」という別の事情。

### どちらを選ぶか

- **迷ったらユーザー別。** 管理者権限が要らず、他の人に影響せず、消すときもフォルダごと消せる
- **全体インストールを選ぶのは、複数ユーザーで共有する必要があるとき**か、OS のサービスとして常駐させる必要があるとき(後述の Linux サーバーはこちら)
- 開発ツールのバージョン管理(fnm、SDKMAN、rustup など)は**ユーザー別が前提**。プロジェクトごとに違うバージョンを使い分けたいのに、OS 全体で 1 つに固定されては困るため

## 4. Windows で `C:\` 直下にフォルダを作るとどうなるか

結論から言うと、**作れるが、他のユーザーから読めるうえに書き換えもできる状態になる。**

### なぜ管理者でなくても作れるのか

`C:\` 自体の ACL(アクセス制御リスト)を実測すると、その許可が明示的に入っている:

```
$ icacls C:\
C:\ BUILTIN\Administrators:(OI)(CI)(F)
    NT AUTHORITY\SYSTEM:(OI)(CI)(F)
    BUILTIN\Users:(OI)(CI)(RX)
    NT AUTHORITY\Authenticated Users:(OI)(CI)(IO)(M)
    NT AUTHORITY\Authenticated Users:(AD)
```

記号の読み方:

| 記号 | 意味 |
|---|---|
| `(F)` / `(M)` / `(RX)` | フルコントロール / 変更 / 読み取りと実行 |
| `(AD)` | サブフォルダの作成を許可 |
| `(OI)(CI)` | 配下のファイル・フォルダに継承させる |
| `(IO)` | このフォルダ自身には適用せず、継承先にだけ適用する |
| `(I)` | この許可は親から継承されたもの |

4 行目と 5 行目が答え。**`Authenticated Users:(AD)` によって、ログインしている任意のユーザーが `C:\` 直下にフォルダを作れる**(継承フラグが付いていないので `C:\` 直下限定)。だから昇格なしで作れる。

### 作ったフォルダは誰から見えるのか

`C:\` 直下に作られたフォルダの ACL を実測すると(フォルダ名は `C:\myfolder` として伏せる):

```
$ icacls C:\myfolder
C:\myfolder BUILTIN\Administrators:(I)(OI)(CI)(F)
            NT AUTHORITY\SYSTEM:(I)(OI)(CI)(F)
            BUILTIN\Users:(I)(OI)(CI)(RX)      ← 全ユーザーが読める
            NT AUTHORITY\Authenticated Users:(I)(M)  ← 変更もできる
```

すべての行に `(I)` が付いている = **`C:\` の許可がそのまま継承されている**。結果として:

- `BUILTIN\Users:(RX)` — この PC の**すべてのユーザーが中身を読める**
- `Authenticated Users:(M)` — 読むだけでなく**書き換え・削除もできる**

質問への直接の答えは「見られる」で、しかも見られるだけでは済まない。`C:\` の ACL にあった `(OI)(CI)(IO)(M)` の `(IO)` が効いていて、`C:\` 自体は変更不可でも**その配下は変更可**になる、という設計のため。

### ユーザー配下と比べる

同じコマンドをホームディレクトリに対して実行すると、様子がまったく違う:

```
$ icacls C:\Users\masanori.adachi
C:\Users\masanori.adachi NT AUTHORITY\SYSTEM:(OI)(CI)(F)
                         BUILTIN\Administrators:(OI)(CI)(F)
                         DESKTOP-TENNUHG\masanori.adachi:(OI)(CI)(F)
```

**`BUILTIN\Users` の行も `Authenticated Users` の行も存在しない。** 許可されているのは SYSTEM、管理者、そして本人だけ。他の一般ユーザーは、このフォルダに**入ることすらできない**。

`(I)` が付いていない = 親(`C:\Users`)からの継承ではなく、このフォルダに明示的に設定された許可であることも読み取れる。Windows はホームディレクトリを作るときに、継承を断ち切って本人専用の許可を設定している。

Linux も同じ設計になっている:

```
$ ls -ld /home /home/masanoriadachi
drwxr-xr-x  3 root           root           /home
drwxr-x--- 40 masanoriadachi masanoriadachi /home/masanoriadachi
```

`/home` 自体は誰でも通れる(`r-x`)が、`/home/masanoriadachi` は `drwxr-x---` で **その他のユーザーには権限がゼロ**。**Ubuntu 21.04 から新規ユーザーのホームは 750 が既定**になっている(それ以前は 755 で、他のユーザーから中身を読めた)。

この既定値は設定ファイルに書かれていて、手元でも確認できる:

```
$ grep -A1 "^# Default: DIR_MODE" /etc/adduser.conf
# Default: DIR_MODE=0750
#DIR_MODE=0750
$ grep "^HOME_MODE" /etc/login.defs
HOME_MODE	0750
```

`adduser` を使うときは `adduser.conf` の `DIR_MODE`、`useradd` を直接使うときは `login.defs` の `HOME_MODE` が効く。どちらも 0750 で揃っている。

### 結論: ユーザー配下に作るべき

| 置き場所 | 他ユーザーから | 適した用途 |
|---|---|---|
| `C:\` 直下 | 読める・書き換えられる | 意図的に全ユーザーで共有したいものだけ |
| `C:\Users\<自分>` 配下 | 入れない | **通常はこちら**。作業フォルダ、リポジトリ、設定、認証情報 |
| `C:\Program Files` | 読める・書けない | 全ユーザー向けにインストールされたプログラム本体 |
| `C:\ProgramData` | 読める・(既定では)書ける | 全ユーザー共有のアプリデータ |

`C:\` 直下を選びたくなる動機は主に「パスが短くなる」「日本語ユーザー名を含むパスを避けたい」あたりで、これらは実際にツールの不具合を避ける目的では正当な理由になる。ただし**その場合でも、置くものが他人に読まれて困る内容でないかは別途確認が必要**。認証情報・鍵ファイル・個人情報を含むものを `C:\` 直下に置くのは避ける。

置いてしまったものを後から守るなら、フォルダの継承を切って許可を自分だけにする(エクスプローラーのプロパティ → セキュリティ → 詳細設定 → 「継承の無効化」)。ただし**最初からユーザー配下に置くほうが確実**。

Linux 側でも同じ判断になる。誰でも書ける `/tmp` は特別扱いで、末尾の `t`(スティッキービット)により「自分が作ったファイルしか消せない」制約が付いている:

```
$ ls -ld /tmp
drwxrwxrwt 16 root root /tmp
```

これがないと、共有の書き込み可能フォルダは誰かが他人のファイルを消せてしまう。**「みんなが書ける場所」を安全に運用するには追加の仕掛けが要る**、ということの実例。

## 5. Linux サーバーへアプリを配置するとき

EC2 やオンプレの Linux サーバーにアプリを置く場合、**そのアプリ専用のユーザーを作ってから配置するのが定石**。ただし「人が使うため」ではないので、作り方が普通のユーザーとは違う。

### 5-1. なぜ root で動かさないのか

`root` は権限判定を素通りする特別なユーザー(uid 0)。root で動かしたアプリが乗っ取られると、攻撃者はそのサーバーの全ファイルを読み書きでき、他のサービスも止められ、ログも消せる。

**アプリを専用ユーザーで動かしておけば、被害はそのユーザーが触れる範囲で止まる。** これは「攻撃されない」ための対策ではなく「攻撃されたときの被害範囲を縛る」ための対策。

もう一つ実務的な理由として、80 番など 1024 未満のポートを開くには root 権限が要る。これは「起動だけ root で行い、ポートを確保したら一般ユーザーに降格する」設計や、リバースプロキシ・ロードバランサに前段を任せる設計で回避する。**このリポジトリは後者**で、TLS 終端と 443 番の受け口は ALB が担当し、アプリ自身は 8080 番で動く(→ [CLAUDE.md](../../CLAUDE.md) のアーキテクチャ上の決定事項)。

### 5-2. サービス専用ユーザーはログインできないように作る

サービス用ユーザーには、人間向けのユーザーには付ける 3 つのものを**あえて付けない**。

```bash
sudo useradd --system --no-create-home --shell /usr/sbin/nologin appuser
```

| オプション | 意味 | なぜそうするか |
|---|---|---|
| `--system` | システムユーザーとして作る(uid が 1000 未満になる) | 人間用の uid 帯と混ざらない |
| `--no-create-home` | ホームディレクトリを作らない | 作業場所が要らない |
| `--shell /usr/sbin/nologin` | ログインシェルを与えない | **このユーザーでログインさせない** |

ただし **`--no-create-home` は実は冗長**。`--system` を付けた時点でホームは作られないためで、`man useradd` にそう書いてある:

> Note that useradd will not create a home directory for such a user, regardless of the default setting in /etc/login.defs (CREATE_HOME). You have to specify the -m options if you want a home directory for a system account to be created.

`login.defs` 側にも「この設定はシステムユーザーには適用されない」と明記されている。**システムユーザーはホームを作らないのが既定で、欲しい場合は逆に `-m` を付ける**、という関係。上のコマンドで書いているのは意図を読み手に示すための明示指定で、外しても結果は変わらない。

3 行目が要点。仮にパスワードが漏れても、SSH でこのユーザーとしてログインすることはできない。第 1 章で見た `www-data` がまさにこの形で作られている:

```
www-data:x:33:33:www-data:/var/www:/usr/sbin/nologin
```

uid が 33(1000 未満)、シェルが `nologin`。手元の WSL で見た `messagebus`、`polkitd`、`syslog` も同じ作りで、**サービスごとに専用ユーザーを立てる**のは Linux 全体で一貫した慣習になっている。

### 5-3. アプリ本体はどこに置くか

**`/opt/<アプリ名>` か `/srv/<アプリ名>` に置き、`/home` には置かない。**

| 置き場所 | 使うべきか | 理由 |
|---|---|---|
| `/opt/myapp` | **推奨** | 「OS のパッケージ管理外で入れたソフト」の標準的な置き場所 |
| `/srv/myapp` | 可 | サーバーが外部に提供するデータ用。Web サイトの実体などはこちら |
| `/home/appuser/myapp` | **避ける** | `/home` は人間の作業領域。人を消すとアプリごと消える運用事故になりやすい |
| `/usr/local/bin` | 単一の実行ファイルなら可 | ディレクトリ一式を置く場所ではない |

第 3 章の「ユーザー別インストールが無難」とは逆の結論になるのが要注意な点。**サーバー上のアプリは特定の人間に属さない共有の資産**なので、共有領域に置き、所有者だけをサービス専用ユーザーにする、という形を取る。

```bash
sudo mkdir -p /opt/myapp
sudo chown -R appuser:appuser /opt/myapp     # 所有者だけ専用ユーザーに
sudo chmod 750 /opt/myapp                     # 本人と同グループだけ
```

### 5-4. systemd の `User=` で権限を落とす

作ったユーザーでアプリを動かす指定は、サービス定義ファイル(`/etc/systemd/system/myapp.service`)の 1 行で行う:

```ini
[Unit]
Description=My App
After=network.target

[Service]
User=appuser
Group=appuser
WorkingDirectory=/opt/myapp
ExecStart=/usr/bin/java -jar /opt/myapp/app.jar
EnvironmentFile=/etc/myapp/env
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

**systemd 自体は root で動いているが、`User=appuser` があるとプロセスを起動する直前に権限を `appuser` に落とす。** これが「1024 未満のポートは root が要る」問題と両立できる仕組みでもある(`AmbientCapabilities=CAP_NET_BIND_SERVICE` を足すと、一般ユーザーのまま低いポートを開ける)。

`EnvironmentFile` の指す `/etc/myapp/env` に DB のパスワードなどを置き、このファイルだけ `chmod 640` かつ `chown root:appuser` にしておく — アプリからは読めるが他のユーザーからは読めない、という形が定番。環境変数の渡し方そのものは [env-vars-basics.md](./env-vars-basics.md) に整理してある。

#### ファイルを置いただけでは反映されない

`.service` ファイルを `/etc/systemd/system/` に置くと自動で有効になる、**わけではない**。置いたあとに 3 つのコマンドが要る。

```bash
sudo systemctl daemon-reload    # 1. systemd に設定ファイルを読み直させる
sudo systemctl enable myapp     # 2. サーバー起動時に自動で立ち上がるようにする
sudo systemctl start myapp      # 3. 今すぐ起動する
```

それぞれ役割が違う。

1. **`daemon-reload`** — systemd はファイルの変更を自動では検知しない。置いた・書き換えたあとにこれを実行しないと、古い内容のまま動き続ける(新しい systemd は `systemctl status` に「Unit file changed on disk」と警告を出してくれる)
2. **`enable`** — **これが `[Install] WantedBy=` を書く理由。** 起動時に立ち上げる登録を行う
3. **`start`** — 今このプロセスを起動する。`enable` は「次回の起動から」なので、両方必要

`enable` が何をしているかは実測すると分かりやすい。**シンボリックリンクを 1 本張っているだけ**:

```
$ ls -l /etc/systemd/system/multi-user.target.wants/
cron.service -> /usr/lib/systemd/system/cron.service
console-setup.service -> /usr/lib/systemd/system/console-setup.service
...
```

`WantedBy=multi-user.target` と書いておくと、`enable` したときに `multi-user.target.wants/` の中へこのリンクが作られる。**「multi-user 状態になったときに起動してほしいサービス」の一覧がこのディレクトリ**で、systemd は起動時にここを見る。`disable` はこのリンクを消すだけなので、設定ファイル本体は残る。

#### 起動の流れ — 何が引き金で、何が起きるのか

**起点は 2 つあり、どちらも読むファイルは同じ。**

| 起点 | 誰が起動するか | いつ |
|---|---|---|
| `sudo systemctl start myapp` | 人が手で | 今すぐ起動したいとき |
| サーバーの起動 | **systemd 自身** | `enable` 済みなら毎回 |

注意したいのは、**サーバー起動時に `systemctl start` が実行されるわけではない**こと。systemd が `multi-user.target.wants/` のリンクを見て、自分で起動する。**引き金は違うが、読まれる設定ファイルも、そこから先の流れもまったく同じ。**

その流れが次のとおり:

```
systemctl start myapp   (またはサーバー起動 → systemd が自分で)
  → systemd が myapp.service を読む
      User=appuser          … 権限を appuser に落とす
      WorkingDirectory=     … カレントディレクトリを /opt/myapp にする
      EnvironmentFile=      … /etc/myapp/env の中身を環境変数として持たせる
  → その状態で ExecStart の行を実行する
      /usr/bin/java -jar /opt/myapp/app.jar
  → java が起動し、jar の中のアプリが動き出す
  → Spring Boot が application.yml を読む
      ${DB_HOST} の穴に、systemd から渡された環境変数が入る
```

**`User=` などの行は「プロセスをどんな状態で生ませるか」を決めていて、`ExecStart=` は「何を生むか」を決めている。** 権限を落とすのもカレントディレクトリを移すのも環境変数を持たせるのも、すべて `ExecStart` の実行より**前**に済ませてある。

#### `ExecStart=` はコマンドラインそのもの

`ExecStart=` は「このファイルにこの設定を使わせる」という対応づけではなく、**systemd が実行するコマンドを 1 行で書いたもの**。

```
ExecStart=/usr/bin/java -jar /opt/myapp/app.jar
          ├────────────┤ ├──────────────────┤
          実行プログラム      それに渡す引数
```

`man systemd.service` の説明では「The first item becomes the command to execute, and the subsequent items the arguments」。つまりターミナルで手で打つ次の 1 行と同じ意味で、それを systemd に代行させているだけ。

```
$ /usr/bin/java -jar /opt/myapp/app.jar
```

ここで **`app.jar` は設定ファイルではなく、アプリのプログラム本体**である点に注意。`java` は Java のプログラムを動かす実行係で、それ単体では何もできない。`-jar /opt/myapp/app.jar` は `java` に渡す引数で「この jar に入っているプログラムを動かせ」という指示になる。`-jar` は `java` のオプションであって、**systemd は中身を解釈していない**(systemd から見ればただの文字列)。

#### 設定は 2 層に分かれている

「設定ファイル」と呼べるものが 2 つ登場するので、読む人で区別する。

| | `myapp.service` | `application.yml`(jar に同梱) |
|---|---|---|
| 読む人 | **systemd** | **Spring Boot(アプリ自身)** |
| 決めること | どのユーザーで、どのコマンドを、どんな環境で起動するか | どの DB に繋ぐか、何番ポートで待つか |
| いつ読まれるか | プロセスを起動する直前 | アプリが起動したあと |

**2 つを繋いでいるのが `EnvironmentFile=` → 環境変数 → `${DB_HOST}` という経路。** systemd はアプリの設定を直接書き換えるのではなく、環境変数として渡すところまでをやり、アプリが自分でそれを拾う。この受け渡しの仕組みは [env-vars-basics.md](./env-vars-basics.md) に整理してある。

#### `ExecStart=` で引っかかりやすい点が 2 つ

**1. シェルではない。** `man systemd.service` に明記されている。

> redirection using "<", "<<", ">", and ">>", pipes using "|", running programs in the background using "&", and other elements of shell syntax are not supported.

`ExecStart=java -jar app.jar > log.txt` のような書き方は動かない。使いたければ `ExecStart=sh -c '...'` と明示的にシェルを呼ぶ。

**2. パスは絶対パスで書く。** ここで使われるのは**自分のシェルの `PATH` ではない**。

> If the command is not a full (absolute) path, it will be resolved to a full path using a **fixed search path determined at compilation time**.

systemd 固有の固定リスト(`/usr/local/bin`、`/usr/bin`、`/bin` など)が使われるので、第 3 章で見た `fnm` の `node` のように**ユーザー配下にあるものは名前だけでは見つからない**。`ExecStart=/usr/bin/java` と絶対パスで書いているのはこのため。

#### ファイル名は自由 — ただしそれがサービス名になる

ファイル名はアプリのフォルダ名と一致している必要はない。**ファイル名から `.service` を除いた部分が、そのまま `systemctl` で指定する名前になる**というだけの関係:

```
/etc/systemd/system/myapp.service  →  sudo systemctl start myapp
```

拡張子のほうは自由ではなく、**種類を表す決められた名前**。`.service`(常駐プロセス)のほかに `.timer`(定期実行、cron の代わり)、`.socket`、`.mount` などがあり、systemd は拡張子で扱いを変える。

#### 置き場所も `/usr/bin` と `/usr/local/bin` と同じ関係になっている

`.service` ファイルの置き場所は 2 つあり、第 3 章で見た住み分けとまったく同じ構造をしている:

| ディレクトリ | 誰が置くか | この環境の実測 |
|---|---|---|
| `/usr/lib/systemd/system/` | **パッケージ管理システム**(`apt` など) | 172 個の `.service` |
| `/etc/systemd/system/` | **人間**(管理者が手で書いたもの) | 管理者が置いたファイルと、`enable` のリンク |

`/etc` 側が優先されるので、**パッケージ版の設定を上書きしたいときも `/etc` 側に置く**(元ファイルは編集しない)。`apt upgrade` で上書きされないのも `/usr/local/bin` と同じ理屈。自分のアプリの `.service` を `/etc/systemd/system/` に置くのは、この住み分けに従っている。

### 5-5. コンテナだとどう変わるか

このリポジトリは EC2 に直接置く構成ではなく、**ECS Fargate でコンテナとして動かす**(→ [docs/infrastructure/README.md](../infrastructure/README.md))。この場合、話は次のように変わる。

**変わること** — ユーザーを作る作業が `useradd` から Dockerfile の記述に移る。`/opt` に置くかどうかも意味が薄れる(コンテナのファイルシステムはそのアプリ専用で、他のアプリと共有していない)。systemd も使わず、コンテナのプロセス 1 番がアプリ本体になる。

**変わらないこと** — **root で動かさない、という原則は変わらない。** コンテナの中の root は、コンテナの外から見ると隔離されているが、それは「壁がもう 1 枚ある」だけで、壁を越える手段(カーネルの脆弱性、特権モード、ホストのボリュームマウント)は存在する。

Dockerfile では次のように書く:

```dockerfile
FROM eclipse-temurin:21-jre
RUN useradd --system --no-create-home --shell /usr/sbin/nologin appuser
COPY --chown=appuser:appuser build/libs/app.jar /app/app.jar
USER appuser
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

`USER appuser` 以降の命令と、起動されるプロセスがこのユーザーで動く。

**このリポジトリの現状**: `docker/backend/Dockerfile` と `docker/frontend/Dockerfile` には `USER` の指定がなく、**コンテナ内は root で動いている**(実測)。これは開発用コンテナとしては妥当な選択で、ホスト側のファイルを bind mount して編集する構成では、ユーザーを絞ると uid の不一致で書き込めなくなる問題が出るため。ただし**本番用イメージを作る段階では `USER` を入れる**必要がある。

## 6. 認証情報とユーザー — 分離の軸は 3 層ある

「AWS CLI などの認証情報はユーザーごとに分けるのが普通」という理解は、**事実としては正しいが、目的の捉え方を一段変えたほうがいい。**

### 6-1. `~/.aws` がユーザー配下にあるのはなぜか

AWS CLI が認証情報を `~/.aws` に置くのは、**`~` の展開先がユーザーごとに違うから、結果としてユーザーごとに別々になる**という順序。「ユーザーで分けるために `~/.aws` にしている」のではなく、「ユーザーの持ち物はホーム配下に置く」という OS の作法に従った結果、自動的に分かれている。

実測:

```
$ ls -l ~/.aws/
drwxr-xr-x 3 masanoriadachi masanoriadachi 4096 cli
-rw------- 1 masanoriadachi masanoriadachi  365 config
-rw------- 1 masanoriadachi masanoriadachi   57 credentials
drwxr-xr-x 3 masanoriadachi masanoriadachi 4096 sso
```

`credentials` と `config` が `-rw-------`(600) = **root を除いて所有者以外は読めない**。

この 600 は偶然ではない、と実測から言える。**この環境の `umask` は 022 なので、普通にファイルを作れば 644 になるはず**だからだ。実際、同じ階層のディレクトリは `drwxr-xr-x`(755)で umask どおりの値になっている。ファイルだけが 600 になっているのは umask では説明がつかないので、**AWS CLI 側がファイルを作るときに権限を明示的に設定していると考えられる**(AWS CLI v2 は実行ファイルに固めて配布されているためソースでは確認できなかった。ここは実測からの推論)。

同じことは SSO のトークンキャッシュでも起きている:

```
$ ls -ld ~/.aws/sso/cache; ls -l ~/.aws/sso/cache/
drwxr-xr-x 2 masanoriadachi masanoriadachi 4096 /home/masanoriadachi/.aws/sso/cache
-rw------- 1 masanoriadachi masanoriadachi 3665 774ba089....json
```

ディレクトリは 755、中の JSON は 600。**秘密が入るファイルだけを狙って絞っている**という意図が読み取れる。

では、755 のままのディレクトリは危なくないのか。**ディレクトリの権限だけ見れば確かに緩い**が、それでも安全なのは、第 4 章で見たとおり親の `/home/masanoriadachi` が 750 で他ユーザーを弾いているから。

**権限は上から順に評価される。** `/home/masanoriadachi` に入れなければ、その下がどうなっていようと辿り着けない。逆に言うと、**ホームディレクトリの権限を緩めると下の対策がまとめて無意味になる**ので、`chmod 755 ~` のようなことは軽率にやらない。

### 6-2. 分離の軸は 3 層ある

「認証情報を分ける」と言ったとき、実際には次の 3 つの別々の軸がある。混同しやすいので分けて考える。

| 層 | 分離する単位 | 何のためか | 使う場面 |
|---|---|---|---|
| ① OS ユーザー | 人 | **人同士**が互いの認証情報を読めないようにする | 1 台を複数人で使う、踏み台サーバー |
| ② プロファイル | 権限セット・環境 | **1 人が**本番と検証、複数アカウントを取り違えないようにする | 個人の開発 PC。ほぼ全員に該当 |
| ③ ロール(置かない) | — | **そもそもディスクに長期の秘密を残さない** | サーバー、CI/CD |

**①は「複数人が同じ OS を使う」ときにだけ意味を持つ。** 個人の PC で自分しかログインしないなら、OS ユーザーをさらに分ける必要はない — すでに分かれている状態が既定なので、追加で何かする話ではない。

**個人開発で日常的に使うのは②。** `~/.aws/config` に複数のプロファイルを書き、`--profile` で切り替える:

```bash
aws sts get-caller-identity --profile masanori-sso
```

このリポジトリでの具体的な設定手順は [docs/setup/new-machine.md](../setup/new-machine.md) に書いてある。

**そして、サーバーと CI での正解は③。**

### 6-3. サーバー・CI では「置かない」が正解

EC2 やコンテナ、GitHub Actions に**長期のアクセスキーをファイルとして置くのは避ける**。理由は単純で、失効しない秘密がディスクに残り続けるから。漏れたときに被害が止まらず、定期的な差し替えも運用の負担になる。

代わりに、実行環境そのものに権限を持たせる:

| 実行環境 | 使う仕組み | 認証情報の形 |
|---|---|---|
| EC2 | インスタンスプロファイル(IAM ロール) | メタデータサービスから一時認証情報を自動取得 |
| ECS / Fargate | タスクロール | 同上。コンテナに自動で渡る |
| GitHub Actions | OIDC | 実行のたびに短命の一時認証情報を発行 |
| 開発 PC | IAM Identity Center(SSO) | 数時間で失効するトークンをキャッシュ |

いずれも AWS SDK / CLI が自動的に拾いに行くので、**アプリ側のコードは何も変えなくていい**。`~/.aws/credentials` があればそれを、なければメタデータサービスを、という順で探す仕組み(認証情報プロバイダチェーン)になっている。

**このリポジトリは③を採用している。** GitHub Actions から AWS への認証はアクセスキーではなく OIDC で行う(→ [docs/infrastructure/README.md](../infrastructure/README.md))。開発 PC 側も SSO を優先しており、理由は同じ — ディスクに残るのが失効済みトークンだけになるため(→ [docs/setup/new-machine.md](../setup/new-machine.md))。

### 6-4. だから、質問への答えは

「認証情報はユーザーごとに分けるのが普通ですよね？」に対しては:

- **手元の PC では、そうなっている** — ただし意図して分けているというより、`~` 配下に置く作法の結果としてそうなる。追加でやるべきことは「ホームディレクトリの権限を緩めない」くらい
- **1 人で使う PC で本当に必要なのは、OS ユーザーの分割ではなくプロファイルの分割** — 本番と検証を取り違えないこと、が実際のリスク
- **サーバー・CI では「ユーザーごとに分ける」という発想自体を使わない** — 置かないのが正解

## まとめ

| 質問 | 答え |
|---|---|
| OS はユーザーごとにフォルダを作る方式か | そう。ただしユーザー = 人ではなく「権限の主体」。28 個中 25 個はログインできないユーザーだった(実測) |
| なぜユーザーが分かれているのか | 人を分けるためではなく**権限を分ける**ため。隔離・責任追跡・最小権限の 3 つ |
| ユーザー別インストールと全体インストールの違い | 書き込む先が共有領域(要管理者権限)かユーザー配下(不要)か。それだけ |
| サーバー配置でもユーザーを作るのか | 作る。ただし `nologin` の**ログインできない専用ユーザー**。root で動かさないため |
| `C:\` 直下に作ると他ユーザーから見られるか | 見られる。読めるうえに**書き換えもできる**(実測)。`C:\Users\<自分>` は他の一般ユーザーが入れない(実測) |
| ユーザー配下に作るほうがいいか | 通常はそう。共有が目的でないなら `C:\` 直下は選ばない |
| 認証情報はユーザーごとに分けるのが普通か | 手元では結果的にそうなる。ただし実際に効くのはプロファイル分割で、サーバー・CI では**置かない**のが正解 |

## 用語集

- **uid / gid** — Linux がユーザーとグループを識別する数値。名前は表示用で、権限判定に使われるのはこの数値のほう
- **SID** — Windows でユーザーやグループを識別する文字列(`S-1-5-21-...`)。uid に相当する
- **システムユーザー** — 人間ではなくプログラムを動かすために存在するユーザー。uid が 1000 未満で、ログインシェルに `nologin` が設定される(`www-data`、`syslog` など)
- **ログインシェル** — そのユーザーでログインしたときに起動されるプログラム。`/etc/passwd` の 7 番目のフィールド。`nologin` を指定するとログインできなくなる
- **ホームディレクトリ** — そのユーザーの作業領域。`/etc/passwd` の 6 番目のフィールドで、環境変数 `HOME`(Windows は `USERPROFILE`)に入り、`~` の展開先になる
- **`DIR_MODE` / `HOME_MODE`** — 新規ユーザーのホームに付ける権限の既定値。`adduser` は `adduser.conf` の `DIR_MODE`、`useradd` は `login.defs` の `HOME_MODE` を見る。Ubuntu 21.04 から 0750
- **UAC(ユーザーアカウント制御)** — Windows が管理者ユーザーにも普段は管理者権限を外したトークンを渡す仕組み。昇格して初めて Administrators が有効になる
- **ACL(アクセス制御リスト)** — Windows の権限の実体。「誰に何を許可するか」の並び。`icacls` で確認できる
- **継承(ACL の)** — 親フォルダの許可が配下に自動で適用される仕組み。`(OI)(CI)` が継承の指定、`(I)` は継承された結果であることを示す
- **スティッキービット** — 誰でも書けるディレクトリで「自分が作ったファイルしか消せない」制約を加える権限。`ls` で末尾が `t` になる(`/tmp` の `drwxrwxrwt`)
- **`umask`** — 新しく作るファイル・ディレクトリから落とす権限のマスク。022 ならファイルは 644、ディレクトリは 755 になる
- **`/opt` と `/usr/local`** — パッケージ管理の外で手動インストールしたものを置く共有領域。`/opt` はディレクトリ一式、`/usr/local/bin` は単体の実行ファイル向け。`/usr/bin` は `apt` の管理領域なので使わない
- **`PATH`** — 実行ファイルを探すディレクトリの並び。前から順に探し、最初に見つかったものが実行される。`/usr/local/bin` が `/usr/bin` より前にあるので、手で入れたものがパッケージ版より優先される
- **`which -a`** — 同名のコマンドを PATH 上から全部挙げる。バージョンが食い違うときの調べ方
- **サービス専用ユーザー** — アプリ 1 つのために作る、ログインできないシステムユーザー。乗っ取られたときの被害範囲を縛るために使う
- **ユニットファイル(`.service`)** — systemd にサービスの起動方法を教える設定ファイル。拡張子が種類を表し(`.service` / `.timer` など)、ファイル名から拡張子を除いた部分が `systemctl` で使うサービス名になる
- **`daemon-reload` / `enable` / `start`** — ユニットファイルを置いたあとに要る 3 手順。順に「設定を読み直す」「起動時に立ち上げる登録(`WantedBy` の `.wants` へシンボリックリンクを張る)」「今すぐ起動する」。**置いただけでは反映されない**
- **`ExecStart=`** — systemd が実行するコマンドラインそのもの。最初の項目が実行プログラム、残りがその引数。シェルではないのでパイプやリダイレクトは使えず、パスは絶対パスで書く
- **systemd の `User=`** — サービス定義でプロセスの実行ユーザーを指定する行。systemd 自体は root で動くが、起動直前に権限をこのユーザーへ落とす
- **プロファイル(AWS CLI)** — `~/.aws/config` に複数書ける設定の束。`--profile` で切り替える。1 人が複数アカウント・複数環境を取り違えないための仕組み
- **インスタンスプロファイル / タスクロール** — EC2 や ECS に IAM ロールを紐付ける仕組み。長期の秘密鍵をディスクに置かず、一時認証情報が自動で渡る
- **認証情報プロバイダチェーン** — AWS SDK / CLI が認証情報を探す順序の決まり。環境変数 → `~/.aws` → メタデータサービスと順に探すので、置き場所が変わってもアプリのコードは変えなくてよい

## 関連

- **この章(第 5 章)の続き — 配置から公開・更新・運用までの通しの手順** → [deploying-to-bare-linux.md](./deploying-to-bare-linux.md)(Spring Boot と Nginx + Laravel の 2 通り)
- 権限そのものの仕組み(`rwx` がどこに記録されるか、`umask`、git とファイルモード) → [file-permissions-and-exec-bit.md](./file-permissions-and-exec-bit.md)
- 環境変数と `.env`、秘密情報をプロセスへ渡す方法 → [env-vars-basics.md](./env-vars-basics.md)
- AWS CLI / SSO の設定手順と、`~/.aws` が Windows 側へのリンクになる問題 → [docs/setup/new-machine.md](../setup/new-machine.md)
- GitHub Actions から AWS への OIDC 認証(この文書の「置かない」の実例) → [docs/infrastructure/README.md](../infrastructure/README.md)
- docker-compose 開発環境の 5 コンテナ構成と環境変数の方針 → [docs/development/README.md](../development/README.md)
