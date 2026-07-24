# interface と implements — 「約束(契約)」の仕組みと Java/PHP/TypeScript の違い

`UserRepository` が `interface` で書かれていたのを見て「そもそも interface って何?」となった人向けの学習メモ。結論を先に言うと、**interface は「このメソッドを持ちます、という約束(契約)の見出しだけを定義したもの」**で、**`implements` は「その約束を守ります」とクラスが宣言するキーワード**。3 言語とも文法は似ているが、**Java / PHP は「`implements` と名乗った者だけが仲間(公称的)」、TypeScript は「形さえ合えば名乗らなくても仲間(構造的)」**という思想の違いがある。ここが最大のポイント。

あわせて、混同されやすい **抽象クラス(abstract class)** との違いも後半で解説する。

## 1. interface とは — 「できることの約束」

interface は、**「こういうメソッドを持っているはず」という約束(契約)だけを書いた型**。中身(実装)は書かない。「見出しだけのメニュー表」「資格の要件書」「コンセントの差し込み口の形」のようなもの。

```java
// Java: 「Animal を名乗るなら cry() を必ず持て」という約束
interface Animal {
    String cry();   // 中身は書かない。メソッドの「見出し」だけ
}

// この約束を守る(= implements する)クラスたち
class Dog implements Animal {
    public String cry() { return "ワン"; }   // 中身はここで書く
}
class Cat implements Animal {
    public String cry() { return "ニャー"; }
}
```

**何が嬉しいのか。** 呼ぶ側は「相手が `Animal` である」ことだけ知っていれば、それが `Dog` でも `Cat` でも**同じ書き方で扱える**。

```java
Animal a = new Dog();
System.out.println(a.cry());   // "ワン"。中身が Cat に変わっても呼び方は同じ
```

これは「共通の約束さえ満たしていれば、中身の違いを気にせず入れ替えられる」という仕組み(ポリモーフィズム)で、interface の一番の存在意義。コンセントの形(約束)さえ合えば、中の家電が扇風機でも掃除機でも挿せるのと同じ。

## 2. implements とは — 「約束を守ります」の宣言

`implements` は、**クラスが「この interface の約束を守ります」と名乗るキーワード**。名乗った以上、その interface が要求するメソッドを**全部書かないとコンパイルエラー**になる。

```java
class Dog implements Animal {
    // cry() を書き忘れると「Animal の約束を守れていない」とエラーになる
    public String cry() { return "ワン"; }
}
```

つまり `implements` は「書き忘れ防止の見張り」でもある。「`Animal` を名乗ったのに `cry()` が無い」を、動かす前(コンパイル時)に弾いてくれる。

### extends(継承)と implements(実装)の違い

初学者が最も混同するポイント。**`extends` は「中身ごと引き継ぐ」、`implements` は「約束だけ引き受けて中身は自分で書く」**。

| 何を何に対して | 使うキーワード | 個数 |
|---|---|---|
| クラス → **クラス**(親の中身を継承) | `extends` | **1 つだけ**(単一継承) |
| クラス → **interface**(約束を実装) | `implements` | **複数可** `implements A, B` |
| interface → **interface**(約束を拡張) | `extends` | 複数可 |

```java
// クラスは「1 つのクラスを継承」しつつ「複数の interface を実装」できる
class Dog extends Animal4Legged implements Runnable, Comparable<Dog> { ... }
```

「クラスの親は 1 人だけ、でも守る約束(interface)は何個でも持てる」と覚えると整理しやすい。なお interface 同士は `implements` ではなく `extends` でつなぐ(約束が別の約束を土台にする、というニュアンス)。

## 3. Java / PHP / TypeScript の書き方の比較

同じ `Animal` の例を 3 言語で並べると、見た目はよく似ている。

```java
// ── Java ──
interface Animal { String cry(); }
class Dog implements Animal {
    public String cry() { return "ワン"; }
}
```

```php
// ── PHP ──
interface Animal {
    public function cry(): string;   // 中身なし。全メソッドは public
}
class Dog implements Animal {
    public function cry(): string { return "ワン"; }
}
```

```typescript
// ── TypeScript ──
interface Animal { cry(): string; }
class Dog implements Animal {
    cry(): string { return "ワン"; }
}
```

`interface` を定義して `implements` で守る、という骨格は 3 言語共通。**ところが、この `implements` の「重み」が言語によって決定的に違う。**

## 4. 最大の違い — 「名乗った者だけ」か「形が合えば」か

ここがこのメモの核心。**Java と PHP は「公称的(こうしょうてき / nominal)」、TypeScript は「構造的(こうぞうてき / structural)」**という、型の判定方式そのものが違う。

### Java / PHP — `implements` と名乗って初めて仲間(公称的)

`implements Animal` と**明示的に宣言したクラスだけ**が `Animal` として扱われる。たとえ中身がそっくりでも、名乗っていなければ別物扱い。

```java
// cry() を持っているが implements していないクラス
class Robot {
    public String cry() { return "ビープ"; }
}

Animal a = new Robot();   // ❌ コンパイルエラー。Robot は Animal を名乗っていない
```

**会員証を提示した人だけを会員と認める**イメージ。見た目が会員そっくりでも、会員登録(`implements`)していなければ入れない。

### TypeScript — 形さえ合えば名乗らなくても仲間(構造的)

TypeScript は、**必要なプロパティ・メソッドの「形」が合っていれば、`implements` していなくても自動的にその型とみなす**。これを「ダックタイピング(アヒルのように鳴くならアヒルとみなす)」と言う。

```typescript
interface Animal { cry(): string; }

// implements を書いていないただのオブジェクト
const robot = { cry: () => "ビープ" };

const a: Animal = robot;   // ✅ OK! 形(cry(): string)が合っているので Animal 扱い
```

**見た目と持ち物が会員そっくりなら、登録の有無を問わず会員として通す**イメージ。TypeScript で `class Dog implements Animal` と書く `implements` は、**「形が合っているか一応チェックしてね」という任意の確認用**にすぎず、書かなくても形さえ合えば型は通る。ここが Java/PHP 出身者の一番驚くところ。

| | 型の判定方式 | `implements` の役割 | 形は同じだが名乗ってない相手 |
|---|---|---|---|
| **Java** | 公称的(名前で判定) | **必須の宣言**。これが無いと仲間になれない | 別物(エラー) |
| **PHP** | 公称的(名前で判定) | **必須の宣言** | 別物(エラー) |
| **TypeScript** | 構造的(形で判定) | **任意の確認用**。無くても形が合えば通る | 仲間扱い(OK) |

## 5. もう一つの違い — 実行時に残るか、消えるか

- **Java / PHP の interface は、実行時にも存在する。** だから「この変数は実際に `Animal` か?」を動作中に判定できる(`instanceof`)。

```java
if (a instanceof Animal) { ... }   // Java: 実行時に判定できる
```

- **TypeScript の interface は、コンパイル後に消える(型チェック専用)。** TypeScript は最終的にただの JavaScript に変換されるが、JavaScript には interface という概念が無いため、型情報は全部消える。だから **`instanceof Animal` は書けない**(実行時には `Animal` が存在しないから)。

```typescript
if (a instanceof Animal) { ... }   // ❌ TS ではエラー。Animal は実行時に存在しない
```

「Java/PHP の interface は建物に残る看板、TS の interface は工事中だけ使う設計図(完成後は撤去される)」とイメージすると分かりやすい。

## 6. interface は中身を持てる? — default メソッド

原則「interface に中身は書かない」だが、例外がある。

- **Java**: Java 8 以降、`default` を付ければ interface にも中身のあるメソッドを書ける(実装クラス共通の便利メソッドを持たせる用途)。
- **PHP**: interface に中身は**書けない**(見出しのみ)。共通の中身を配りたいときは `trait`(トレイト)という別の仕組みを使う。
- **TypeScript**: interface は「形」の定義なので中身は持てない。

この違いは今は「そういう例外もある」程度でよい。基本は「interface = 見出しだけ」で覚えておけば困らない。

## 7. だから `UserRepository` は動いた — Spring の特殊ケース

ここで最初の疑問に戻る。`UserRepository` は `interface` なのに、`implements` するクラスも中身も無い。なのに `findByUsername` が動く。なぜか。

```java
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);   // 中身が無い
}
```

普通の interface は「自分で `implements` するクラスを書いて中身を埋める」もの。しかし **Spring Data JPA は、この interface を見つけると、約束(メソッド名)から中身を推測して実装クラスを起動時に自動生成し、裏でこっそり用意してくれる**。つまり「`implements Xxx { ... }` を書く作業を、Spring が肩代わりしている」。

これは interface の**特殊で高度な使い方**であって、interface の基本形ではない。基本はあくまで「1〜2 章のように、自分で `implements` して中身を書く」もの。`UserRepository` は「約束だけ書けば実装は自動」という、Spring が用意した便利な仕組みに乗っている、と理解すればよい。

## 8. 抽象クラス(abstract class)との違い

interface とよく混同されるのが **抽象クラス**。両方「中身のないメソッド」を持てるので紛らわしいが、役割が違う。

**抽象クラスとは、`new` で直接インスタンス化できない、"未完成の親クラス"。** 中身のあるメソッドと、中身のない(`abstract` な)メソッドの**両方**を持て、さらに**フィールド(状態)やコンストラクタも持てる**。

```java
// 抽象クラス: 共通の中身(name とあいさつ)は親が持ち、cry() だけ子に任せる
abstract class AbstractAnimal {
    protected String name;                      // 状態を持てる(interface は不可)
    AbstractAnimal(String name) { this.name = name; }

    public String greet() { return name + "です"; }   // 中身のある共通メソッド
    public abstract String cry();               // 中身なし。子が必ず埋める
}

class Dog extends AbstractAnimal {              // 継承は extends(implements ではない)
    Dog(String name) { super(name); }
    public String cry() { return "ワン"; }
}
```

### interface との比較

| | **interface** | **抽象クラス** |
|---|---|---|
| 主な役割 | **できること(役割)の約束** | **共通の土台(実装・状態)の共有** |
| 状態(フィールド)を持てるか | 基本は不可(定数のみ) | **持てる** |
| 中身のあるメソッド | 例外的に可(Java の `default`) | **普通に持てる** |
| いくつ持てるか | **複数**(`implements A, B`) | **1 つだけ**(`extends` は単一) |
| つなぐキーワード | `implements` | `extends` |
| 関係のイメージ | 「〜できる(can-do)」 | 「〜の一種である(is-a)」 |

### 使い分け

- **バラバラな種類のクラスに「同じ役割・できること」を持たせたい** → interface。例: `Dog` と `Robot` は無関係だが、どちらも「音を出せる(`Soundable`)」でまとめたい。
- **関係の近いクラス同士で「共通の中身や状態」を配りたい** → 抽象クラス。例: `Dog` も `Cat` も「名前を持ち、あいさつができる動物」という土台を共有したい。

「約束(できること)を横断的に揃えるのが interface、親子で中身を受け継ぐのが抽象クラス」と押さえておけばよい。**interface は何個でも実装できるが、抽象クラス(親)は 1 つだけ**、という制約の違いも実務で効いてくる。

なお PHP・TypeScript にも `abstract class` はある。TypeScript の抽象クラスは(interface と違って)**実行時にも残る**(実体は普通のクラスだから)点も、5 章の話とつながる。

## つまずきポイント

- **`extends` と `implements` の取り違え。** クラスが interface を受けるのは `implements`、クラス/interface が同種を受け継ぐのは `extends`。「中身ごと継ぐ=extends、約束だけ引き受ける=implements」。
- **interface は `new` できない。** 約束(見出し)だけで中身が無いから。`new Animal()` は不可。`new Dog()` のように実装クラスを作る。
- **Java/PHP は `implements` し忘れると、形が同じでも他人。** 公称的なので「名乗り」が必須。TS 感覚で「形が合うから通るはず」と思うと詰まる。
- **TS は逆に、`implements` を書かなくても形が合えば通る。** 公称脳だと「なぜ名乗ってないのに型が合う?」と驚く。構造的だから。
- **TS の interface は `instanceof` できない。** 実行時に消えるため。実行時の型判定が必要なら class を使う。
- **`implements` したのにメソッドを書いていない(Java/PHP)。** 「約束を破った」とコンパイルエラー。要求メソッドを全部実装する。

## 用語集

- **interface** — メソッドの「見出し(約束・契約)」だけを定義した型。中身は書かない。
- **implements** — クラスが「この interface の約束を守る」と宣言するキーワード。要求メソッドの実装を強制される。
- **extends** — 中身ごと引き継ぐキーワード。クラス→クラス、interface→interface で使う。クラスは 1 つしか extends できない。
- **公称的型付け(nominal typing)** — `implements` と名乗った関係だけを「同じ型」と認める方式。Java・PHP がこれ。
- **構造的型付け(structural typing)** — プロパティ・メソッドの「形」が合えば同じ型とみなす方式。TypeScript がこれ。ダックタイピングとも。
- **ポリモーフィズム** — 共通の約束(interface)を満たす相手を、中身の違いを気にせず同じ書き方で扱えること。
- **default メソッド** — Java 8 以降、interface に持たせられる「中身のあるメソッド」。
- **抽象クラス(abstract class)** — `new` できない未完成の親クラス。中身あり/なし両方のメソッドと、状態(フィールド)を持てる。
- **trait(PHP)** — 共通の中身(実装)を複数クラスに配る PHP の仕組み。PHP の interface が中身を持てない穴を埋める。

## 関連

- 今回のきっかけになった Spring の interface → `backend/.../user/UserRepository.java` と [PostRepository.java](../../../../backend/src/main/java/com/example/app/post/PostRepository.java)
- コンストラクタの見分け方(クラス名と同名・戻り値なし) → [constructor-declaration.md](./constructor-declaration.md)
- 型の明示と型推論(`var`)、静的型付けの話 → [type-declaration-and-var.md](./type-declaration-and-var.md)
- 配列と List、ジェネリクス `<...>` の話 → [array-vs-list.md](./array-vs-list.md)
