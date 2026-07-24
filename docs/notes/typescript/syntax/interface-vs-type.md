# TypeScript の interface と type — 「型に名前を付ける」2 つの書き方と instanceof

「TypeScript の interface って、結局オブジェクトの型を宣言してるだけでは? `type` と何が違うの? クラスの型も定義できるの? あと `instanceof` って何?」という疑問に答える学習メモ。結論を先に言うと:

- TS の **interface は「オブジェクト(や関数・クラス)の形に名前を付けて使い回す」道具**。変数・配列・オブジェクトの型宣言はできるが、それは `type` でもできる。
- **interface と type はほぼ同じことができる。** 差は「interface はオブジェクトの形専門で、拡張・マージが得意」「type は union / プリミティブ / タプルなど、オブジェクト以外にも名前を付けられる」。迷ったら **オブジェクトの形は interface、それ以外は type**。
- **`instanceof` は「実行時に、この値がこのクラスから作られたか?」を調べる演算子**。型(interface/type)ではなく**クラス**に対して使う。interface には使えない(実行時に消えるから)。

前回の [Java 側の interface メモ](../../java/syntax/interface-and-implements.md)は「クラスが `implements` する契約」の話が中心だった。TS では使われ方の重心が違うので、そこも対比しながら見ていく。

## 1. そもそも、変数の型宣言に interface は要らない

まず誤解を解いておく。**単なる変数・配列の型宣言に interface は不要**。TS には最初から組み込みの型(`number` / `string` / `boolean` / 配列 `number[]` など)があり、オブジェクトの形もその場に直接書ける。

```typescript
const n: number = 3            // 組み込みの型。interface は要らない
const names: string[] = []     // 配列の型も同様
const u: { id: number; name: string } = { id: 1, name: "a" }  // オブジェクトの形も直接書ける
```

では interface / type は何のためにあるのか。**同じ形を何度も使うとき、その形に「名前」を付けて使い回すため**。上の `{ id: number; name: string }` を毎回書くのは面倒だし、直したいとき全部直すのは大変。そこで名前を付ける。

```typescript
interface User { id: number; name: string }   // 形に User という名前を付けた
const u: User = { id: 1, name: "a" }           // 以後は User と書くだけ
```

つまり **interface は「型そのもの」ではなく「型に名前を付けて使い回す仕組み」**。ここが最初のポイント。

## 2. このプロジェクトの interface は「オブジェクトの形」

実際にこのリポジトリの frontend で使われている interface を見ると、すべて **「API から返ってくる JSON オブジェクトの形」** を表している。

```typescript
// frontend/app/types/post.ts
export interface UserSummary {
  id: number
  username: string
  displayName: string
}

export interface Post {
  id: number
  body: string
  createdAt: string
  user: UserSummary      // 別の interface を部品として組み合わせられる
  category: Category
}

export interface Timeline {
  posts: Post[]                    // 配列の形も表せる
  nextCursor: number | null        // 「number または null」も書ける
}
```

これらは**クラスではない**。「バックエンドの API が返す JSON はこういう形をしている」という**約束**を書いているだけ。だから前回の Java の「`class Dog implements Animal`(クラスが契約を実装する)」とは、使われ方の重心がまるで違う。

**TS では interface を「オブジェクトの形の設計図」として使うことが圧倒的に多い。** ユーザーの「interface ってオブジェクトの型宣言では?」という感覚は、TS に関しては半分正解。ただし「それだけ」ではなく、関数の型やクラスの契約にも使える(後述)。

## 3. interface と type の違い

`type`(型エイリアス = 型に別名を付ける機能)を使うと、interface とほぼ同じことが書ける。

```typescript
interface User { id: number; name: string }
type User = { id: number; name: string }   // ほぼ同じ意味
```

### 予備知識: プリミティブ型とタプルとは

このあとの比較表に「プリミティブ」「タプル」が出てくるので、先に意味を押さえておく。

**プリミティブ型** — TS/JS の最も基本的な、**それ以上分解できない単純な値**の型。`string`(文字列)/ `number`(数値)/ `boolean`(真偽値)/ `null` / `undefined` などが該当する。**オブジェクトや配列はプリミティブではない**(中に複数の値を抱える「入れ物」だから)。「1 個の素の値」がプリミティブ、とイメージするとよい。

```typescript
const a: string = "hello"           // プリミティブ
const b: number = 42                // プリミティブ
const c: boolean = true             // プリミティブ
const o: { x: number } = { x: 1 }   // これはオブジェクト(プリミティブではない)
```

> Java にも「プリミティブ型」という言葉があるが、指す顔ぶれは違う(`int` / `long` / `double` / `boolean` / `char` など、オブジェクトでない値型)。共通するのは「オブジェクトでない単純な値」という発想で、具体的な顔ぶれは言語ごとに別、と押さえておけばよい。

**タプル(tuple)** — **「要素数と、各位置の型が固定された配列」**。普通の配列 `number[]` は「number が何個入ってもよい」が、タプルは「1 番目は number、2 番目は string、しかも 2 個ちょうど」のように、位置ごとの型と個数を決め打ちする。

```typescript
const pair: [number, string] = [1, "a"]    // 1番目は number、2番目は string で固定
// const bad: [number, string] = ["a", 1]  // ❌ 順番が違う
// const bad2: [number, string] = [1]       // ❌ 個数が足りない

const nums: number[] = [1, 2, 3, 4]         // 比較: 普通の配列は型も個数もゆるい
```

「座標を表す `[x, y]` の 2 個組」「`[成功フラグ, メッセージ]` という戻り値」のように、**位置に意味がある固定長の組**を表したいときに使う。

オブジェクトの形に名前を付けるだけなら、どちらでもよい。違いが出るのは次のような場面。

| | **interface** | **type(型エイリアス)** |
|---|---|---|
| オブジェクトの形に名前 | ✅ 得意 | ✅ できる |
| union 型(`A \| B`) | ❌ 書けない | ✅ `type Id = number \| string` |
| プリミティブ/タプルに別名 | ❌ | ✅ `type Name = string` / `type Pair = [number, string]` |
| 関数の型 | ✅ 可(やや冗長) | ✅ 得意 `type Fn = (x: number) => string` |
| 拡張 | `extends` | `&`(交差型)で合成 |
| 同名で再宣言 | **✅ 自動でマージ(宣言マージ)** | ❌ エラー |
| クラスの `implements` 対象 | ✅ | ✅ |

押さえるべき違いは 3 つ。

- **type は「何にでも」名前を付けられる。** union(`number | string`)、プリミティブ(`string`)、タプル(`[number, string]`)、関数など、**オブジェクト以外**も別名にできる。interface はオブジェクトの形が主戦場で、union やプリミティブには使えない。
- **拡張のしかたが違う。** interface は `extends` で他の interface を土台にする。type は `&`(交差型)で複数の型を合体させる。
  ```typescript
  interface Dog extends Animal { bark(): void }        // interface は extends
  type Dog = Animal & { bark: () => void }             // type は &
  ```
- **interface は「宣言マージ」される。** 同じ名前で interface を 2 回書くと、TS はそれらを**自動で合体**させる(ライブラリの既存型に後から項目を足す用途で使う)。便利な反面、うっかり同名を書くと勝手にくっついて事故になる。type は同名再宣言をエラーで弾くので、その点は安全。

### 使い分けの指針

- **オブジェクトやクラスの形** → interface(公式ドキュメントもまずこちらを推奨)
- **union / プリミティブ / タプル / 関数など、オブジェクト以外を含む** → type

実務では「オブジェクトは interface、それ以外は type」か「全部 type で統一」のどちらかにチームで揃えることが多い。このプロジェクトの `types/` はオブジェクトの形なので interface を使っている。

## 4. interface でクラスの型定義もできる

できる。やり方は 2 通り。

**(a) クラスが `implements` して「契約」として使う**(前回の Java メモと同じ考え方)

```typescript
interface Animal { cry(): string }

class Dog implements Animal {
  cry(): string { return "ワン" }   // Animal が要求する cry() を実装
}
```

この `implements` は「Dog が Animal の形(cry メソッド)を満たしているか、コンパイル時にチェックしてね」という確認。ただし前回述べたとおり、TS は構造的型付けなので **`implements` は必須ではない**(形さえ合えば書かなくても型は通る)。

**(b) クラスのインスタンスを受ける「変数の型」として使う**

interface はクラスのインスタンスを入れる変数の型にもなる。構造的型付けなので、形が合えば `implements` の有無に関係なく代入できる。

```typescript
const d: Animal = new Dog()   // Dog のインスタンスを Animal 型の変数へ。OK
```

つまり「クラスの型定義もできるか?」の答えは **できる**。`implements` でクラス側に契約を課すことも、interface をクラスインスタンスの型として使うこともできる。ただし TS での interface の主役はあくまで「2 章のオブジェクトの形」で、クラス絡みはその応用、という温度感。

## 5. instanceof とは

`instanceof` は、**「この値が、指定したクラスから作られたインスタンスか?」を実行時に調べる演算子**。結果は `true` / `false`。

```typescript
const d: Animal = new Dog()
console.log(d instanceof Dog)   // true … d は Dog から作られた
console.log(d instanceof Cat)   // false
```

内部では「その値がそのクラス(の仲間)から作られたか」を血統書のようにたどって判定している。ポイントは **`instanceof` は "実行時" に動く**こと。プログラムを動かしている最中に、実際のオブジェクトを見て判定する。

### なぜ interface には instanceof が使えないのか

ここが前回のメモ(「TS の interface は instanceof できない」)の答え。

```typescript
console.log(d instanceof Animal)   // ❌ エラー: Animal は interface
```

理由は、**interface / type はコンパイル後に消えてしまう**から。TS は最終的にただの JavaScript に変換されるが、JavaScript に interface という概念は無い。だから実行時には `Animal` が存在せず、「`Animal` から作られたか?」を調べようがない。

一方、**クラスは実行時にも残る**(JavaScript にも class がある)。だから `new` もできるし `instanceof` も効く。ここから TS の重要な感覚が導ける:

- **interface / type = 「型の世界」の住人**。コンパイル時のチェック専用で、実行時には消える。`instanceof` は使えない。
- **class = 「型の世界」にも「値の世界」にもいる**。型としても使えるし、実行時に実体として残る。だから `new` も `instanceof` もできる。

「実行時にこの値が本当に何なのかを判定したい」ときは、消えてしまう interface ではなく、残るクラス(や、`typeof` / プロパティの有無チェックなど別の手段)を使う——これが TS の型と値の住み分け。

> なお前回の Java メモでは「Java の interface は `instanceof` できる」と書いた。Java の interface は**実行時にも存在する**ので判定できる。ここが TS(消える)と Java(残る)の決定的な差。同じ `instanceof` でも背景が違う。

## つまずきポイント

- **変数やプリミティブの型宣言に interface は要らない。** `const n: number` で十分。interface は「複雑な形に名前を付けて使い回す」ためのもの。
- **オブジェクトの形なら interface と type は交換可能。** union(`A | B`)やプリミティブの別名は `type` だけ。「オブジェクト以外も名前にしたい → type」。
- **interface は同名で書くと勝手にマージされる(宣言マージ)。** 意図せず同名を 2 つ書くと合体して謎の型になる。type なら同名はエラーで気づける。
- **interface / type に `instanceof` は使えない。** 実行時に消えるから。`instanceof` はクラスにだけ。
- **`instanceof` は「値の世界」、interface は「型の世界」。** TS は型情報がコンパイルで消える、を思い出せば混乱しない。
- **`implements` は TS では任意。** 構造的型付けなので、形が合えば書かなくても型は通る(前回メモ参照)。

## 用語集

- **interface** — オブジェクト(や関数・クラス)の「形」に名前を付ける仕組み。TS では主にオブジェクトの形の設計図として使う。
- **type(型エイリアス)** — 任意の型に別名を付ける仕組み。オブジェクトに加え union・プリミティブ・タプル・関数など何にでも使える。
- **プリミティブ型** — `string` / `number` / `boolean` / `null` / `undefined` など、それ以上分解できない単純な値の型。オブジェクトや配列は含まない。Java の同名語(`int`/`boolean` など)とは顔ぶれが違う。
- **タプル(tuple)** — `[number, string]` のように、要素数と各位置の型を固定した配列。普通の配列(`number[]`)より厳しく縛る。
- **union 型** — `number | string` のように「A または B」を表す型。`type` でしか名前を付けられない。
- **交差型(&)** — `A & B` で複数の型を合体させる。type 側での「拡張」にあたる。
- **宣言マージ** — 同名の interface を複数書くと自動で合体する TS の機能。type には無い。
- **構造的型付け** — 形(プロパティ・メソッド)が合えば同じ型とみなす方式。TS はこれ。→ [Java 側メモ](../../java/syntax/interface-and-implements.md)
- **instanceof** — ある値が指定クラスのインスタンスかを実行時に判定する演算子。interface には使えない。
- **型の世界 / 値の世界** — interface/type はコンパイル時のみ(型の世界)。class は実行時にも残る(値の世界)。この分離が instanceof の可否を決める。

## 関連

- 前回の Java 中心メモ(interface とは・implements・公称的 vs 構造的・抽象クラス) → [../../java/syntax/interface-and-implements.md](../../java/syntax/interface-and-implements.md)
- このメモの実例になった型定義 → [frontend/app/types/post.ts](../../../../frontend/app/types/post.ts) / [category.ts](../../../../frontend/app/types/category.ts)
