# ファイル権限はどこに保存され、環境をまたぐと何が変わるのか — inode / umask / git のファイルモード

`sh ./gradlew test` の `sh` はなぜ必要なのか、という疑問から出発して、その根っこにある「ファイルの権限とは何で、どこに記録されていて、環境を移動すると何が起きるのか」を実測しながら整理した学習メモ。

要点は 5 つ。

1. **権限はファイルの中身に入っていない。** ファイル 1 つずつに付いている **inode** というメタ情報に入っている。OS が「全ファイルの権限台帳」を別に持っているわけではない
2. **環境をまたぐと権限は実際に変わる。** 同じリポジトリでも `umask` 次第で `rw-r--r--` にも `rw-------` にもなる。**ただし git が記録・再現するのは実行ビットだけ**で、そこは環境に依らず忠実に再現される
3. **「実行ビットを保持できない経路を通ると 644 になる」という仕組みは実在する。** git は clone / init のときにファイルシステムを実際に試して `core.fileMode` を自動判定し、信用できないと判断したら x ビットを無いものとして `100644` で記録する
4. **ただし `backend/gradlew` の 644 の原因はそれではない。** 実測の結果、**Spring Initializr が配る ZIP の時点ですでに 644** だった。さらに追うと、引き金は生成コマンドの **`-d baseDir=.`**（Initializr 側の不具合を踏む）。Windows も WSL も `unzip` も git も無関係で、最初から x ビットが付いていなかった
5. **だから `sh ./gradlew` が要る。** そして CI でも同じ問題が同じように起きる

以下、`backend/gradlew`（このリポジトリで実行権限が無いファイル）を題材に、実測結果を並べていく。

## 1. 権限はファイルの中身に入っていない — inode の話

ファイルシステムは、1 つのファイルを **2 つに分けて** 保存している。

```
【データブロック】             【inode】
 ファイルの中身そのもの         そのファイルについての情報
 #!/bin/sh                     - 権限 (rwx の 9 ビット)
 ...                           - 所有者の uid / グループの gid
 (8654 バイト)                 - サイズ
                               - タイムスタンプ (作成/更新/アクセス)
                               - データブロックがディスク上のどこにあるか
```

**権限は右側（inode）に入っている。** だから「ファイルの中身をエディタで開いても権限は見えない」し、逆に「中身を 1 バイトも変えずに権限だけ変える」（= `chmod`）ができる。

`stat` で inode の中身を覗ける。実測:

```
$ stat backend/gradlew
  File: backend/gradlew
  Size: 8654      	Blocks: 24         IO Block: 4096   regular file
Device: 8,80	Inode: 1129688     Links: 1
Access: (0644/-rw-r--r--)  Uid: ( 1000/masanoriadachi)   Gid: ( 1000/masanoriadachi)
Modify: 2026-07-17 16:33:01.000000000 +0900
```

`Access: (0644/-rw-r--r--)` が権限。`Inode: 1129688` がこのファイルの inode 番号。

### 「OS が全ファイルの権限台帳を持っているのか?」への答え

**持っていない。** ただし言い方は正確にしておきたい。

- inode は**ファイルシステム（パーティション）ごと**に「inode テーブル」としてまとめて置かれている。つまり「台帳」に相当するものはある
- しかしそれは **OS 全体で 1 つの台帳ではなく、ディスクのパーティションごと**にある。しかも中身は「ファイル 1 件 = 1 レコード」で、そのファイルと運命共同体
- ファイルを削除すれば、その inode も解放される。ファイルをコピーすれば、**コピー先で新しい inode が作られる**

最後の 1 行が、後の話（なぜ経路によって権限が失われるのか）の根っこになる。

### 名前も inode には入っていない

意外なところだが、**ファイル名は inode に入っていない**。名前と inode 番号の対応表を持っているのは**ディレクトリ**の側で、ディレクトリとは「名前 → inode 番号」の対応表そのものである。

```
ディレクトリ backend/ の中身（概念図）
  "gradlew"      → inode 1129688
  "gradlew.bat"  → inode 1129689
  "build.gradle" → inode 1129690
```

`stat` の `Links: 1` は「この inode を指している名前が 1 つ」という意味。`ln`（ハードリンク）で同じ inode に別名を付けると 2 になる。同じ inode を指している以上、**どちらの名前から見ても権限は同一**になる。

### 所有者は「名前」ではなく「数字」で保存されている

`Uid: ( 1000/masanoriadachi)` の丸括弧に注目。inode に入っているのは **`1000` という数字だけ**で、`masanoriadachi` は「uid 1000 に対応する名前」を `/etc/passwd` から引いて `stat` が親切に表示しているだけである。

これがはっきり分かる実測がある。ホストと backend コンテナで、**同じファイル**（bind mount なので同じ inode）を見た結果:

```
【ホスト】
$ ls -l backend/gradlew
-rw-r--r-- 1 masanoriadachi masanoriadachi 8654 Jul 17 16:33 backend/gradlew

【backend コンテナの中】
$ docker compose exec backend ls -l gradlew
-rw-r--r-- 1 ubuntu ubuntu 8654 Jul 17 07:33 gradlew
```

**権限（`-rw-r--r--`）とサイズは完全に一致し、所有者の表示名だけが違う。** コンテナの `/etc/passwd` では uid 1000 が `ubuntu` という名前だったからで、ファイル側は何も変わっていない。「権限や所有者は数字で保存され、名前は環境ごとの翻訳結果」という構造がそのまま出ている。

## 2. rwx と 644 / 755 の読み方

`ls -l` の先頭 10 文字が権限の全体像。1 文字目を除いた 9 文字を **3 文字ずつ 3 組**に区切って読む。

```
-  rw-   r--   r--
│   │     │     └─ その他の人 (other) : 読める
│   │     └─────── グループ (group)   : 読める
│   └───────────── 所有者 (owner)     : 読める・書ける
└─ ファイル種別 (- は通常ファイル、d はディレクトリ)
```

各組は必ず `rwx` の順で、権限が無いところが `-`。

- **r（read）** — 中身を読める
- **w（write）** — 書き換えられる
- **x（execute）** — **プログラムとして起動できる**

3 段階に分かれているのは、1 台のマシンを複数人で共有する前提の OS だから。「自分は書けるが他人には読ませるだけ」という区別ができる。

### 8 進数表記

`r=4 / w=2 / x=1` を足して 1 桁にする。

| 記号 | 計算 | 数字 |
|---|---|---|
| `rw-` | 4 + 2 | **6** |
| `r-x` | 4 + 1 | **5** |
| `r--` | 4 | **4** |
| `rwx` | 4 + 2 + 1 | **7** |

- **644 = `rw-r--r--`** — 所有者は読み書き、他は読むだけ。**誰も実行できない**（設定ファイル、`.java` ソースなど普通のファイル）
- **755 = `rwxr-xr-x`** — 所有者は何でも、他は読む・実行する。**スクリプトや実行ファイルの定番**

`chmod +x file` は x ビットを立てる操作（644 → 755）。`chmod 755 file` と数字で書いても同じ。chmod は change mode の略。

### root でも x ビットが 1 つも無ければ実行できない

「root は何でもできる」とよく言われるが、実行に関してはそうではない。backend コンテナは root で動いている（`id -u` が `0`）。そこで直接実行を試した実測:

```
$ docker compose exec backend sh -c 'id -u; ./gradlew --version'
0
sh: 1: ./gradlew: Permission denied
```

root は読み取り・書き込みの制限は無視できるが、**x ビットが 3 組すべてで立っていないファイルは実行できない**（1 つでも立っていれば root は実行できる）。「うっかり実行を防ぐ」ための仕様で、権限の話で root を例外扱いしてはいけない数少ない箇所。

### ディレクトリの x は意味が違う

同じ `x` でも、ディレクトリでは「起動できる」ではなく「**中を通り抜けられる**」を意味する。実測:

```
$ chmod 555 d && cat d/f.txt
hi                                  ← x があるので通れる

$ chmod 444 d && cat d/f.txt
cat: d/f.txt: Permission denied      ← x を外すと、中のファイル名が分かっていても辿れない
```

だからディレクトリは 755 が標準で、x を外すと事実上封鎖になる。

## 3. umask — 新規ファイルの権限を決める引き算

「ファイルを新しく作ったとき、権限は誰が決めるのか」の答えが **umask**。プロセスごとに持っている「**この権限は与えるな**」というマスク値で、新規作成時に引き算される。

```
新規ファイル: 666 (rw-rw-rw-) から umask を引く
新規ディレクトリ: 777 (rwxrwxrwx) から umask を引く
```

実測（`umask` を変えて `touch` / `mkdir`）:

```
=== 新規ファイル ===
umask 022 → -rw-r--r--    (666 - 022 = 644)
umask 077 → -rw-------    (666 - 077 = 600)
umask 002 → -rw-rw-r--    (666 - 002 = 664)

=== 新規ディレクトリ ===
umask 022 → drwxr-xr-x    (777 - 022 = 755)
umask 077 → drwx------    (777 - 077 = 700)
```

この環境の umask は `0022`（ホストもコンテナも同じだった）。Linux の一般的な既定値で、だから新規ファイルは 644、新規ディレクトリは 755 になる。

### ここが「644 が既定値になる」理由の 1 つ目

上の表をよく見ると、**新規ファイルは 666 から始まる**。666 には x が 1 つも含まれていない。つまり——

> **どんな umask を設定しても、新しく作られたファイルに実行ビットは付かない。**

x ビットは「作った後に `chmod` で明示的に立てる」ものであって、自然に付くことはない。ファイルの既定が 644 で、755 は意図的な操作の結果、という非対称性がここにある。

（ディレクトリは 777 から始まるので x が付く。ディレクトリは通り抜けられないと使い物にならないため。）

## 4. git が記録するのは実行ビットだけ

では git はこの 9 ビットをどう扱うのか。**実質 2 種類しか記録しない。**

| git のモード | 意味 |
|---|---|
| `100644` | 通常ファイル（実行ビットなし） |
| `100755` | 通常ファイル（実行ビットあり） |
| `120000` | シンボリックリンク |
| `160000` | サブモジュール |

r / w の細かい違い、所有者、グループは**一切記録されない**。`git ls-files -s` で確認できる。実測:

```
$ git ls-files -s backend/gradlew backend/gradlew.bat
100644 b9bb139f790567973216cd313e69ae65789c3754 0	backend/gradlew
100644 24c62d56f2d4a91975bd1aa72103d2ec628e449f 0	backend/gradlew.bat
```

### では clone したとき、ディスク上の権限はどう決まるのか

**実行ビットは git の記録どおり、それ以外は umask が決める。** 755 と 644 のファイルを 1 つずつ入れたテスト用リポジトリを作り、umask を変えて clone した実測:

```
=== git に記録されたモード ===
100644  plain.txt
100755  run.sh

=== umask 0022 で clone ===
-rw-r--r--  plain.txt
-rwxr-xr-x  run.sh

=== umask 0077 で clone ===
-rw-------  plain.txt
-rwx------  run.sh          ← 所有者の x は残っている

=== umask 0000 で clone ===
-rw-rw-rw-  plain.txt
-rwxrwxrwx  run.sh
```

読み取れることが 2 つある。

1. **r / w の権限は環境ごとにまったく違う値になる。** 同じコミットから clone しても `-rw-r--r--` にも `-rw-------` にもなる
2. **実行できるかどうかは 3 ケースすべてで正しく再現された。** 755 として記録されたファイルは所有者が実行でき、644 のファイルはどのケースでも実行できない

git が実行ビットだけを記録するのは、この非対称性が理由。**実行できるかどうかはプログラムの動作に直結する**ので持ち越さないと壊れる。一方 r / w や所有者は「誰のマシンの、どのユーザーの持ち物か」という環境固有の事情なので、持ち越すと逆に困る。

## 5. `core.fileMode` — 実行ビットが失われる代表的な経路

ここが「実行ビットを保持できない経路を通ると 644 になるのか」への直接の答え。git の公式ドキュメント（`git-config(1)`、git 2.43.0 で確認）にそのまま書かれている。

> **core.fileMode**
> Tells Git if the executable bit of files in the working tree is to be honored.
>
> Some filesystems lose the executable bit when a file that is marked as executable is checked out, or checks out a non-executable file with executable bit on. **git-clone(1) or git-init(1) probe the filesystem to see if it handles the executable bit correctly and this variable is automatically set as necessary.**
>
> The default is true (when core.filemode is not specified in the config file).

つまり **git は clone / init のときに、実際にファイルを作って x ビットが保持されるか試している**。保持されないファイルシステム（Windows の NTFS、CIFS マウント、metadata オプション無しの WSL の `/mnt/c` など）だと判定に失敗し、`core.fileMode = false` が自動で書き込まれる。

`false` になると何が起きるか。実測:

```
=== core.fileMode=false のリポジトリで、x ビット付きの新規ファイルを add ===
ディスク上: -rwxr-xr-x
git の記録: 100644 1a2485251c33a70432394c93fb89330ef214bfc9 0	new.sh

=== core.fileMode=false で、追跡済みファイルを chmod 644 した後の git status ===
（出力なし = 変更として検知されない）
```

**ディスク上で 755 なのに、git は 100644 で記録した。** ご想像どおり「644 を既定値にしてしまう」挙動である。正確に言えば「x ビットを**信用しない**ので、無いものとして扱う」。同じ理由で、追跡済みファイルの `chmod` も差分として現れない。

### ただし、このリポジトリの 644 は `core.fileMode` が原因ではない

`core.fileMode` は「実行ビットが失われる経路」の代表例だが、**`backend/gradlew` が 644 なのはこれとは別の理由**だった。まず、この環境は `core.fileMode` が `true` で probe に成功している:

```
$ git config core.fileMode
true

$ findmnt -T . -o FSTYPE
FSTYPE
ext4
```

つまり git は x ビットを正しく見ている。にもかかわらず 644 なのは、**そもそも最初から x ビットが付いていなかった**からである。原因は次節。

## 6. 真犯人 — 生成コマンドの `-d baseDir=.`

`backend/` は Spring Initializr から ZIP を取得して `unzip` する手順で作られている（→ [spring-initializr.md](./spring-initializr.md)）。その手順をそのまま再現し、**ZIP の中に記録されている権限**を確認した実測がこれ。

```
$ curl -s https://start.spring.io/starter.zip \
    -d type=gradle-project -d language=java -d javaVersion=21 \
    -d dependencies=web,data-jpa,mysql,validation,devtools -d baseDir=. \
    -o starter.zip

$ unzip -Z starter.zip
-rw-r--r--  2.0 unx     8654 bX defN 26-Jul-27 10:19 gradlew        ← ZIP の時点で 644
-rw-r--r--  2.0 unx     2846 bX defN 26-Jul-27 10:19 gradlew.bat
-rw-r--r--  2.0 unx      973 bX defN 26-Jul-27 10:19 build.gradle
```

**ZIP の中身がすでに 644 だった。** 展開後も当然 644 になる:

```
$ unzip -q starter.zip && ls -l gradlew
-rw-r--r-- 1 ... 8654 ... gradlew
```

このファイルは `backend/gradlew` と **1 バイトも違わない**（`cmp` で確認）。サイズ 8654 も一致しており、同じ出自であることは間違いない。

### ZIP は Unix の権限を運べる。運ばれてこなかっただけ

「zip 形式だから実行ビットが落ちたのでは」という説は成り立たない。**同じ ZIP の中で、ディレクトリはちゃんと 755 で記録されている:**

```
$ unzip -Z starter.zip | grep "^d"
drwxr-xr-x  2.0 unx        0 bX defN 26-Jul-27 10:19 src/
drwxr-xr-x  2.0 unx        0 bX defN 26-Jul-27 10:19 src/main/
drwxr-xr-x  2.0 unx        0 bX defN 26-Jul-27 10:19 gradle/wrapper/
（以下同様。755 なのはディレクトリだけで、ファイルは 1 つも 755 が無い）
```

ZIP には Unix のモードを格納する領域（外部属性）があり、この ZIP はそれを使っている（`unx` の表示がその印）。ディレクトリは 755、ファイルは一律 644 で書き出されているだけである。

`unzip` も無罪。実行ビット付きの ZIP を自作して展開すると、ちゃんと保持される:

```
=== zip に記録された権限 ===
?rwxr-xr-x  exe.sh
?rw-r--r--  plain.txt
=== unzip した結果 ===
-rwxr-xr-x  exe.sh          ← 保持されている
-rw-r--r--  plain.txt
```

### `-d baseDir=.` が引き金だった — Initializr の意図は 755

ここまでで「Initializr が実行ビット無しで配っている」と言えそうに見えるが、**それは正確ではない**。Initializr のソースを読むと、**ラッパースクリプトだけ明示的に 0755 にする実装**になっている（`initializr-web` の `ProjectGenerationController`）。

```java
private int getUnixMode(String wrapperScript, String entryName, Path path) {
	if (Files.isDirectory(path)) {
		return UnixStat.DIR_FLAG | UnixStat.DEFAULT_DIR_PERM;          // ディレクトリは 755
	}
	return UnixStat.FILE_FLAG | (entryName.equals(wrapperScript) ? 0755 : UnixStat.DEFAULT_FILE_PERM);
	//                            ↑ ラッパーだけ 755、それ以外は 644
}
```

つまり**意図は「gradlew は実行可能で配る」**であり、安全のために外しているのではない。では、なぜ 644 になったのか。条件が `entryName.equals(wrapperScript)` という**文字列の完全一致**だからである。それぞれの値はこう決まる。

```java
private static String getWrapperScript(ProjectDescription description) {
	String script = buildSystem.id().equals(MavenBuildSystem.ID) ? "mvnw" : "gradlew";
	return (description.getBaseDirectory() != null) ? description.getBaseDirectory() + "/" + script : script;
}                                                    // ↑ baseDir を前に付ける

private String getEntryName(Path root, Path path) {
	String entryName = root.relativize(path).toString().replace('\\', '/');   // ← 相対パス
	...
}
```

`baseDir=.` を渡すと、この 2 つが噛み合わなくなる。

| `baseDir` | `wrapperScript` | ZIP の `entryName` | 一致するか | 結果 |
|---|---|---|---|---|
| 指定なし | `gradlew` | `gradlew` | ○ | **755** |
| `backend` | `backend/gradlew` | `backend/gradlew` | ○ | **755** |
| **`.`** | **`./gradlew`** | **`gradlew`** | **×** | **644** |

`.` は相対パスを作ると消えてしまうため、`"gradlew".equals("./gradlew")` が `false` になり、`DEFAULT_FILE_PERM`（644）に落ちる。**Initializr 側の不具合**である。

実測で裏付けた。同じコマンドで `baseDir` だけを変えた結果:

```
-d baseDir=.             → -rw-r--r-- gradlew            ← 644（壊れる）
-d baseDir=backend       → -rwxr-xr-x backend/gradlew    ← 755（正常）
(baseDir 指定なし)        → -rwxr-xr-x gradlew            ← 755（正常）
```

**`docs/setup/backend.md` の生成コマンドは `-d baseDir=.` を使っている。** これが 644 の起点だった。Maven 版の `mvnw` も同じ条件で同じように落ちる。

### 結論としての因果の連鎖

```
① 生成コマンドが -d baseDir=. を指定している            ← ここが起点
② Initializr の名前比較が外れ、gradlew が 644 で ZIP に入る
      （ラッパーを 755 にする実装はあるが、この条件では発動しない）
③ unzip が忠実に 644 で展開する
④ chmod +x を挟まないまま git add
      → ディスク上が本当に 644 なので、正しく 100644 で記録される
        （core.fileMode=true なので git は正常に動いている）
⑤ commit → 履歴に 100644 が刻まれる
⑥ 以後、誰がどこで clone しても 644 ← 環境をいくら正常にしても直らない
```

**②③④はどれも「正しく動いた結果」である点に注意。** 壊れているのは ① と ② の組み合わせだけで、あとは全部その 644 を忠実に運んだにすぎない。

**⑤ が重要。** 一度履歴に `100644` が入ると、ext4 上の正常な環境で clone しても直らない。「今いる環境の問題」ではなく「記録された履歴の問題」なので、直すには履歴の側（git のインデックス）を触る必要がある（→ 第 8 節）。

なお `core.fileMode` が `true` な今の環境では、`chmod` の結果は `git status` に差分として現れる。`false` の環境と挙動が違うので、試すときは注意する。

## 7. 開発環境と GitHub Actions で権限は変わるか

**変わる部分と変わらない部分がある。そして今回問題になっている部分は変わらない。**

| | 開発環境（ローカル） | GitHub Actions の runner |
|---|---|---|
| **r / w の権限** | umask 次第（この環境は 0022 → 644） | umask 次第。**一致する保証は無い** |
| **所有者の表示名** | `masanoriadachi` | runner のユーザー |
| **実行ビット** | git の記録どおり | **git の記録どおり（同じ）** |

第 4 節の実測が示したとおり、**実行ビットだけは umask に関係なく再現される**。したがって:

- `backend/gradlew` は runner 上でも 644 になり、`./gradlew test` は同じように `Permission denied` で落ちる
- 逆に言えば「ローカルでは動くのに CI だけ落ちる」「CI だけ勝手に直る」という食い違いは**起きない**
- だから CI の YAML でも同じく `sh ./gradlew test` と書く（→ [ci-with-github-actions.md](./ci-with-github-actions.md)）

### docker compose のマウントも同じ inode を見ている

compose は `backend/` をホストからコンテナへ bind mount している。bind mount は**コピーではなく同じ inode を別の場所から見せる**仕組みなので、権限も同一になる。第 1 節の実測（ホストとコンテナで `-rw-r--r--` が一致）がその証拠。「コンテナに入れば権限が変わるのでは」という期待は成立しない。

### 例外: artifact 経由では実行ビットが失われる

`services` や compose のマウントと違い、**GitHub Actions の artifact を経由すると権限は潰される**。`actions/upload-artifact` の README「Permission Loss」節に明記されている。

> File permissions are not maintained during zipped artifact upload. **All directories will have `755` and all files will have `644`.**

ここでも同じ 644 が出てくる。artifact の実体は zip で、zip 経由で往復すると x ビットが落ちるためである。回避策として README は「tar に固めてから `archive: false` でアップロードする」を案内している。

現状このリポジトリで artifact に載せる予定があるのはテストレポート（HTML / XML）だけなので影響は無い。**将来、ビルド成果物やスクリプトをジョブ間で受け渡すようになったら刺さる**ので、そのときに思い出す。

## 8. 帰結: なぜ `sh ./gradlew` なのか

ここまでの話が全部つながる場所。

### `./gradlew` を打ったとき何が起きるか

```
1. シェルが「./gradlew を実行したい」とカーネルに依頼する
2. カーネルが gradlew の x ビットを見る
      ↓ 立っていない (644)
3. 「Permission denied」を返す ← ここで終わり
```

**gradlew の中身は 1 バイトも読まれない。** 門の前で「入館証に実行の判が無い」と止められる形で、書類を開く前に帰される。

x ビットが立っていれば、続きはこうなる。

```
3'. カーネルが先頭の数バイトを覗く
4'. 1 行目が #!/bin/sh → 「sh に読ませればいいんだな」と判断
5'. /bin/sh を起動し、gradlew を材料として渡す
```

この 1 行目の `#!` を **シバン（shebang）** と呼ぶ。`gradlew` は Java の実行ファイルではなく**シェルスクリプト**で、実際に先頭はこうなっている（`backend/gradlew:1`）。

```sh
#!/bin/sh
```

### `sh ./gradlew` は何が違うのか

命令の主語が入れ替わる。

```
./gradlew test        → 実行するのは gradlew → gradlew の x ビットが必要
sh ./gradlew test     → 実行するのは sh      → sh の x ビットが必要（当然ある）
                         gradlew は sh に渡す「引数」= 読むだけ → r ビットで足りる
```

`sh` は `/bin/sh` にある正規の実行ファイルなので x ビットは元から立っている。gradlew は「sh に読ませるテキストファイル」の扱いになり、`r--` があれば読める。

**このときシバンは無視される。** `#` はシェルスクリプトのコメント開始記号なので、sh から見れば単なるコメント行である。シバンは「カーネルが起動時に読む案内板」であって「sh への命令」ではない。結果として **どちらの書き方でも最終的に sh が gradlew を解釈する**という同じ結果に着地するので、`sh` を前に付ける回避策は安全に成立する。

### このリポジトリの採用: `sh ./gradlew` で統一

すでに全面的にこの形になっている。

| 場所 | 記述 |
|---|---|
| `docker/backend/Dockerfile:10` | `CMD ["sh", "./gradlew", "bootRun"]`（コメントに理由も書かれている） |
| `CLAUDE.md` のビルド・実行コマンド節 | `docker compose exec backend sh ./gradlew test` / `... sh ./gradlew classes` |
| `docs/test/README.md` | テスト実行コマンドすべて |
| CI（これから作る） | `run: sh ./gradlew test` |

### 本来の直し方（今回は採用していない）

git のインデックスに記録されたモードを 100755 に変えれば、`sh` は不要になる。ディスクを触らずインデックスだけ変える方法:

```bash
git update-index --chmod=+x backend/gradlew
```

または、`core.fileMode` が `true` なこの環境ならディスク側を直しても差分として拾われる:

```bash
chmod +x backend/gradlew    # git status に変更として現れる
```

どちらもコミットすればリポジトリのモードが `100755` になり、以後 clone した全員が実行できるようになる。**コミットはユーザー自身の作業**（→ `CLAUDE.md` の作業ルール）。

**今回これを採用しない理由は 3 つ。**

1. **すでに 4 箇所が `sh` 付きで統一され、動いている。** 直す動機が「動かないから」ではない
2. **直しても既存の記述は全部そのまま動く。** `sh ./gradlew` は 755 のファイルにも問題なく使えるので、直す / 直さないは**どちらも壊れない選択**である。急いで決める必要が無い
3. **学習リポジトリとして、この状態自体に価値がある。** `./gradlew` と打って `Permission denied` を踏み、その理由を辿れる環境になっている

つまり「本来は 755 が正しいが、`sh` 統一でも実害が無いので現状を維持し、理由を文書に残す」という判断。将来 `./gradlew` と直接打ちたくなったら、上のコマンド 1 つで移行できる。

## つまずきポイント

- **エディタで中身を見ても権限は分からない。** 権限は inode にあり、ファイルの中身とは別。`ls -l` か `stat` で見る
- **ファイルをコピーすると権限は「コピー先の事情」で決まる。** コピー先で新しい inode が作られるため。`cp -p` や `tar` は権限を保持するが、zip や単純な `cp` は落とす
- **root でも x ビットがゼロなら実行できない。** 「root なら何でもできる」は実行に関しては成り立たない
- **ディレクトリの `x` は「起動」ではなく「通り抜け」。** 同じ記号で意味が変わる
- **新規ファイルに x ビットは絶対に付かない。** 666 から引き算されるので、umask をどう設定しても付かない。x は `chmod` で立てるもの
- **`core.fileMode=false` のリポジトリでは `chmod` が git status に出ない。** 「権限を直してコミットしたのに反映されない」の原因はこれ。`git update-index --chmod=+x` を使う
- **`core.fileMode` を真っ先に疑うと間違える。** このリポジトリの 644 は `core.fileMode`（`true`）のせいではなく、配布元の ZIP がそうだったから。**まず `unzip -Z` や `ls -l` で「入ってきた時点でどうだったか」を見る**のが順序として正しい
- **Spring Initializr に `-d baseDir=.` を渡すと `gradlew` が 644 になる。** ラッパーだけ 755 にする実装はあるが、`baseDir=.` のとき名前比較（`"gradlew".equals("./gradlew")`）が外れて発動しない。`baseDir` を付けない、または `backend` のような名前を指定すれば 755 で配られる。`baseDir=.` を使うなら生成直後に `chmod +x gradlew`（→ [spring-initializr.md](./spring-initializr.md)）
- **「配布元が悪い」で止めると 1 段浅い。** 今回も「Initializr が 644 で配る」まで分かった時点で終わりに見えたが、ソースを読むと**意図は 755** で、こちらの渡したパラメータが引き金だった。「相手の意図はどうなっているか」まで確認すると結論が変わることがある
- **一度 644 でコミットされたモードは、正常な環境で clone しても直らない。** 履歴に記録されているため。直すにはコミットが必要
- **「ローカルでは動くが CI では落ちる」は実行ビットでは起きない。** git が忠実に再現するため。逆に r / w の権限は環境ごとに違ってよい
- **artifact 経由では実行ビットが失われる。** `upload-artifact` はすべて 755 / 644 に潰す。tar に固めれば保持できる
- **`gradlew.bat` も 644 だが問題にならない。** Windows は実行の可否を拡張子（`.bat` / `.exe`）で判断し、Unix の実行ビットを見ない

## 用語集

- **inode** — ファイル 1 つずつに付く管理情報の入れ物。権限・所有者・サイズ・タイムスタンプ・データの位置を持つ。**ファイル名は含まない**
- **データブロック** — ファイルの中身そのものが置かれているディスク上の領域。inode とは別
- **パーミッション（権限）** — 所有者 / グループ / その他 × r / w / x の 9 ビット。inode に保存される
- **実行ビット（x ビット）** — そのファイルをプログラムとして起動してよいことを示すフラグ。無いと `Permission denied`
- **644 / 755** — 権限の 8 進数表記。r=4、w=2、x=1 の足し算。644 は実行不可、755 は実行可
- **`chmod`** — change mode。権限を変更するコマンド。`chmod +x file` で実行ビットを立てる
- **`stat`** — inode の中身を表示するコマンド。`ls -l` より詳しい
- **uid / gid** — ユーザー / グループを表す数字。inode に入っているのは数字だけで、表示名は環境ごとの翻訳結果
- **ハードリンク** — 同じ inode に付けた別名。`stat` の `Links` がその数。どちらの名前から見ても権限は同一
- **umask** — 新規作成時に「与えない権限」を指定するマスク。ファイルは 666 から、ディレクトリは 777 から引かれる。この環境は `0022`
- **ファイルモード（git）** — git が記録するモード。`100644`（実行不可）/ `100755`（実行可）/ `120000`（シンボリックリンク）/ `160000`（サブモジュール）。r / w や所有者は記録しない
- **`core.fileMode`** — 作業ツリーの実行ビットを信用するかの git 設定。clone / init 時にファイルシステムを試して自動設定される。既定は `true`
- **`git update-index --chmod=+x`** — ディスクを触らず、git のインデックス上のモードだけを 100755 に変えるコマンド
- **シバン（shebang）** — スクリプト 1 行目の `#!/bin/sh`。カーネルが「どのインタプリタで動かすか」を判断する案内板。`sh ./file` 形式では単なるコメントとして無視される
- **bind mount** — ホストのディレクトリをコンテナ内の別のパスから見せる仕組み。コピーではなく同じ inode を見るので、権限も一致する

## 関連

- CI で `sh ./gradlew` と書く理由、runner の位置づけ、4 つの構成方式 → [ci-with-github-actions.md](./ci-with-github-actions.md)
- テストの実行コマンド（すべて `sh` 付き）とテスト方針 → [docs/test/README.md](../test/README.md)
- `gradlew`（Gradle Wrapper）が何をしているスクリプトなのか → [gradle-basics.md](./gradle-basics.md)
- `docker compose exec` にシェルが必要な場面 / 不要な場面 → [docker-exec-and-shells.md](./docker-exec-and-shells.md)
- `gradlew` を含む backend のファイル構成 → [backend-project-files.md](./backend-project-files.md)
- 644 の起点になった ZIP 取得手順（`baseDir=.` の副作用） → [spring-initializr.md](./spring-initializr.md) / [docs/setup/backend.md](../setup/backend.md)
- 公式ドキュメント: [git-config(1) の core.fileMode](https://git-scm.com/docs/git-config) / [actions/upload-artifact の Permission Loss](https://github.com/actions/upload-artifact)
- 原因コード: [initializr の ProjectGenerationController](https://github.com/spring-io/initializr/blob/main/initializr-web/src/main/java/io/spring/initializr/web/controller/ProjectGenerationController.java)（`getUnixMode` / `getWrapperScript`）
