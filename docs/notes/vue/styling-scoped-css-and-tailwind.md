# スタイルの書き方 — `<style scoped>` と Tailwind、React 側との違い

Vue / Nuxt では `.vue` ファイルの中に CSS まで書くのが標準なのはなぜか、React / Next.js で Tailwind CSS が主流になったのはなぜか、その 2 つはどう関係しているのかをまとめるメモ。

[sfc-and-template-syntax.md](./sfc-and-template-syntax.md) §8 で `<style scoped>` の仕組み(`data-v-xxx` 属性を足しているだけ)は扱ったので、ここは「どの手法を選ぶか」という上の階層の話。

結論を先に言うと:

- **Vue で `<style scoped>` を同じファイルに書くのは、はい、最も標準的な手法。** 公式ガイドもスターターもエコシステムのライブラリもこれで統一されている。
- **React に「標準」が無いのは、React コンポーネントがただの JS 関数だから。** スタイルの置き場所が仕様のどこにも定義されていない空白地帯なので、外付けの解決策が世代ごとに入れ替わってきた。
- **SFC と Tailwind は同じ問題への別解。** 「コンポーネントとスタイルを同じ場所に置きたい」という要求に対し、Vue はファイル形式を発明して応え、React は `class` 属性に押し込むことで応えた。
- **だから Vue で Tailwind を使う動機は React ほど強くない。** ただし scoped CSS が苦手なこと(デザイントークンの統一・コンポーネント間の共有)は実在し、そこが Tailwind を入れる判断の分かれ目になる。
- **Tailwind は「スタイルを 1 ファイルにまとめる」ものではない。** マークアップの `class` 属性に直書きする手法で、まとめる度合いで言えば SFC より徹底している。

---

## 0. 対応表

| やりたいこと | React / Next.js | Vue / Nuxt |
|---|---|---|
| コンポーネント専用のスタイル | `Foo.module.css` を別ファイルで作り import | `<style scoped>` を同じファイルに書く |
| グローバル CSS | `app/globals.css` を `layout.tsx` で import | `assets/css/main.css` を `nuxt.config.ts` の `css:` で読む |
| 条件付きクラス | `className={clsx('base', { active })}` | `:class="{ active }"`(言語機能) |
| JS の値をスタイルに流す | インライン `style={{ color }}` / CSS-in-JS | `:style="{ color }"` / `<style>` 内の `v-bind(color)` |
| ユーティリティクラス方式 | Tailwind CSS(現在のデファクト) | Tailwind CSS(使えるが既定ではない) |
| 完成品コンポーネント集 | shadcn/ui、MUI など | Nuxt UI、Vuetify、PrimeVue など |

## 1. SFC の `<style>` ブロックは 1 種類ではない

`.vue` の 3 ブロックのうち `<style>` は、`scoped` 専用ではない。形式としては次が全部合法で、1 ファイルに複数の `<style>` を並べることもできる。

| 書き方 | 意味 |
|---|---|
| `<style scoped>` | **既定**。ビルド時に `data-v-xxx` 属性を付けてこのコンポーネントに閉じる |
| `<style>` | グローバル CSS。どのコンポーネントにも効く |
| `<style module>` | CSS Modules。クラス名がハッシュ化され、テンプレートから `$style.postCard` で参照する |
| `<style src="./card.css" scoped>` | CSS だけ別ファイルに置き、スコープ機能は使う |
| `<style lang="scss">` | Sass などのプリプロセッサを通す |

つまり「1 ファイルにまとめる」は SFC の**既定**であって**強制**ではない。CSS が長くなったら `src` で切り出せる。

### `v-bind()` — JS の値を CSS に流す

`<style>` の中から `<script setup>` の変数を参照できる。

```vue
<script setup lang="ts">
const themeColor = ref('#1d4ed8')
</script>

<style scoped>
.post-card {
  border-color: v-bind(themeColor);
}
</style>
```

実装はビルド時に CSS 変数(`--xxx`)へ置き換え、要素側にその変数をインラインで設定する形。**動的な値のためだけに CSS-in-JS を入れる理由が Vue には薄い**のは、この機能があるため。

React で同じことをするには、インライン `style` を書くか CSS-in-JS を入れるか、自分で CSS 変数を設定するかになる。

## 2. React 側に「標準」が無い構造的な理由

**React のコンポーネントはただの JavaScript 関数**([vue-vs-react-overview.md](./vue-vs-react-overview.md) §3 の「根っこ」がここにも効いてくる)。ファイル形式を発明していないので、「スタイルをどこに置くか」が言語仕様にもフレームワーク仕様にも書かれていない。空白地帯なので外付けの解決策が次々に生まれ、そのたびに主流が入れ替わってきた。

| 時期 | 手法 | 内容 | 現在の位置づけ |
|---|---|---|---|
| ~2015 | グローバル CSS + BEM | `.block__element--modifier` の命名規約で衝突を防ぐ | 規律を人間が守る必要があり破綻しやすい |
| 2016~ | **CSS Modules** | `Foo.module.css`。ビルド時にクラス名をハッシュ化 | Next.js / Vite が標準サポート。今も現役 |
| 2017~ | **CSS-in-JS** | styled-components、emotion。JS の中に CSS を書く | 一時デファクト。**React Server Components と相性が悪く App Router 世代で後退** |
| 2021~ | **Tailwind CSS** | ユーティリティクラスを `class` に並べる | 現在のデファクト。`create-next-app` が導入を尋ねてくる |
| 2022~ | zero-runtime CSS-in-JS | vanilla-extract、Panda CSS。書き味は CSS-in-JS、出力はビルド時の静的 CSS | CSS-in-JS の後継として一定の支持 |

CSS-in-JS が後退した理由は押さえておく価値がある。**スタイルを実行時に生成する以上、ブラウザ側で JS が動く必要がある。** Server Component では動かせないので `'use client'` が必須になり、RSC を採用する意味を削いでしまった。

一方 Vue は 2014 年の時点で SFC という**ファイル形式そのもの**を発明し、`<style>` をその一部にした。`scoped` はコンパイラが面倒を見る公式機能。だから Vue には「スタイルの置き場所論争」がそもそも起きていない。**論争が起きるかどうかが、この 2 つの最大の差。**

## 3. 核心 — SFC と Tailwind は同じ問題への別解

JSX には `<style>` ブロックが無い。だから React でスタイルを書ける場所は実質 2 つしかない。

- **別ファイル**(CSS Modules)→ 編集のたびに `.tsx` と `.module.css` を行き来する
- **`class` 属性**(Tailwind)→ 行き来しなくて済む

```jsx
<article className="bg-white border border-slate-200 rounded-lg p-4">
```

Tailwind が React 界で強い理由はここにある。**SFC が `<style scoped>` で得ている「コンポーネントとスタイルが同じ場所にある」という性質を、React で得るための手段が Tailwind だった。** ついでにスコープ問題(ユーティリティクラスは元から衝突しない)も同時に解決している。

同じものを 2 つの流儀で書くと差がはっきりする。

```vue
<!-- Vue SFC: 同じファイルの別ブロック -->
<template>
  <article class="post-card">...</article>
</template>

<style scoped>
.post-card {
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 16px;
}
</style>
```

```jsx
// React + Tailwind: 同じ行
<article className="bg-white border border-slate-200 rounded-lg p-4">...</article>
```

**どちらも「近くに置く」という目的は達成している。** 違うのは、名前を付けるか(SFC)付けないか(Tailwind)。

- SFC 方式は `.post-card` という**意味の名前**が残るので、マークアップを読んだときに構造の意図が分かる。CSS の知識がそのまま使える。
- Tailwind 方式は名前を考えずに済み、値がパレットに縛られるので**ばらつきが出ない**。代わりに `class` が長くなり、マークアップから意味が読み取りにくくなる。

**Vue で Tailwind を使う動機が React ほど強くないのは、素の SFC が既に「近くに置く」を解決しているから。** Tailwind を入れる理由があるとすれば、それは近さではなく次章の 2 点になる。

## 4. `<style scoped>` が苦手なこと

正直に言うと、scoped CSS には Tailwind が解決していて自分は解決していない弱点が 2 つある。

### (a) デザイントークンを統一する仕組みが無い

`scoped` はスタイルを閉じ込めるが、**値の一貫性については何も保証しない**。色や余白を各ファイルに直接書けてしまう。

このリポジトリの `frontend/app` から色を全部拾うと、既にこうなっている。

| 色 | 出現するファイル |
|---|---|
| `#64748b` | `Card.vue` `index.vue` `[id].vue` `Form.vue`(4 ファイル) |
| `#1d4ed8` | `Card.vue` `index.vue`(2 箇所)`[id].vue` `Form.vue`(4 ファイル) |
| `#e2e8f0` | `Card.vue` `Form.vue` `default.vue`(3 ファイル) |
| `#dc2626` | `Card.vue` `Form.vue`(2 箇所)(2 ファイル) |
| `#cbd5e1` | `index.vue` `Form.vue`(2 ファイル) |

「テーマカラーを変えたい」となったら全ファイルを grep することになる。Tailwind なら `text-slate-500` `bg-blue-700` と書くので、値は最初から設定 1 箇所にある。

なお**ここで使われている色は Tailwind の既定パレットそのもの**(`#64748b` = slate-500、`#1d4ed8` = blue-700、`#e2e8f0` = slate-200、`#dc2626` = red-600)。誰かが Tailwind の値を手で写した形になっている。

**素の CSS でも解決はできる。** グローバル CSS に CSS 変数を定義し、各 SFC からはそれを参照する。

```css
/* app/assets/css/main.css */
:root {
  --color-text-muted: #64748b;
  --color-primary: #1d4ed8;
  --color-border: #e2e8f0;
}
```

```vue
<style scoped>
.post-meta { color: var(--color-text-muted); }
</style>
```

Tailwind との差は「守らなくても動く」こと。変数を無視して直接 `#64748b` と書いても壊れない。強制力があるかどうかがフレームワークとの違いになる。

### (b) コンポーネントをまたぐスタイル共有が苦手

`index.vue` の投稿ボタンと `Form.vue` の送信ボタンを同じ見た目にしたい、というとき、scoped CSS ではスタイルを共有できない。選択肢は 3 つで、どれも一手間かかる。

1. 共通コンポーネント(`components/ui/Button.vue`)に切り出す — 正攻法だが、見た目を揃えるためだけにコンポーネントを作ることになる
2. グローバル CSS に `.btn-primary` を置く — scoped の利点を一部手放す
3. 各ファイルにコピーする — 現状これ

Tailwind ならクラス文字列をコピーすれば揃う。ただし**それはそれで「同じ長い文字列が散る」という別の重複を生む**ので、結局は共通コンポーネントに切り出すことになる。この問題は Tailwind でも完全には消えない。

## 5. SSG との関係

このリポジトリは `nuxt generate`(SSG)で、出力を Spring Boot の `static/` に置いて配信する。**本番に Node.js が存在しない**ので、スタイル手法の選択にも影響がある。

| 手法 | 実行時の JS | SSG との相性 |
|---|---|---|
| `<style scoped>` | 不要(ビルド時に静的 CSS) | 良い |
| `<style module>` | 不要 | 良い |
| Tailwind | 不要(ビルド時にソースを走査し、使われたクラスだけ出力) | 良い |
| `v-bind()` in `<style>` | CSS 変数の設定のみ | 良い |
| CSS-in-JS(実行時生成) | 必要 | **避ける** |

つまり CSS-in-JS 以外はどれを選んでも構わない。この制約が選択を狭めるわけではない。

## 6. Vue / Nuxt でも Tailwind を使うのはどういうときか

「Vue = scoped CSS、React = Tailwind」と単純に分かれているわけではない。Vue / Nuxt で Tailwind が選ばれる場面は主に 3 つ。

1. **Tailwind ベースの UI ライブラリを使う** — Nuxt UI が代表([nuxi-templates.md](../nuxi-templates.md) の `ui` テンプレート)。ライブラリ側が Tailwind 前提なので、自分のコードも揃える
2. **React と Vue のプロジェクトでデザインシステムを共有する** — クラス名の語彙を揃えられる
3. **チームに Tailwind 経験者が多い** — 学習コストが既に払われている

Tailwind v4 を Nuxt に入れる場合は、`@tailwindcss/vite` を Vite プラグインとして追加し、グローバル CSS で `@import "tailwindcss";` するのが現在の作法(v3 時代の `@nuxtjs/tailwindcss` モジュール + `tailwind.config.js` とは手順が変わっている)。

**Tailwind を入れても `<style scoped>` は使える。** 排他ではないので、大半をユーティリティクラスで書き、複雑なところだけ `<style scoped>` に落とす、という併用が実際には多い。

## 7. このリポジトリの方針

**現状は `<style scoped>` + 最小限のグローバル CSS。当面この形を続ける。**

```
app/assets/css/main.css   14 行。box-sizing、body のフォント・背景色だけ
app/**/*.vue              各コンポーネントの <style scoped>
```

Tailwind も Nuxt UI も入れない。理由は 3 つ。

1. **このリポジトリは学習用**で、目的は Nuxt / Vue そのものを学ぶこと。Tailwind を入れると `<style scoped>` を書く機会が消え、Vue の標準的な書き方を身につける経験が失われる
2. **Nuxt UI については判断済み。** [nuxi-templates.md](../nuxi-templates.md) で「まず Nuxt 自体を学びたい段階では情報量が多すぎる」として `ui` テンプレートを選ばなかった。ここで覆すのは一貫性を欠く
3. **規模が小さい。** 2 ページ + 2 コンポーネントでは Tailwind の投資が回収できない

§4(a) の色の重複は認識しているが、**今は直さない**。画面が増えて実際に困った時点で CSS 変数に切り出す。その作業は Tailwind へ移行する場合の下準備にもなるので、どちらに進んでも無駄にならない。

---

## 落とし穴

- **Tailwind を「スタイルを 1 ファイルにまとめる手法」だと理解する。** 逆で、CSS ファイルを書かずマークアップの `class` に直書きする手法
- **`scoped` がスタイルの一貫性まで保証すると思う。** 保証するのは影響範囲だけ。値のばらつきは別問題
- **`scoped` が子コンポーネントの中まで効くと思う。** 効かない。`:deep()` が必要(→ [sfc-and-template-syntax.md](./sfc-and-template-syntax.md) §8)
- **React の癖で CSS-in-JS を持ち込む。** 動的な値なら `:style` か `v-bind()` で足りる。SSG では実行時生成のライブラリは避ける
- **CSS が長くなったから別ファイルに移せないと思う。** `<style src="./card.css" scoped>` で切り出せる
- **Tailwind と `<style scoped>` が排他だと思う。** 併用できる

## 用語集

- **スコープ付き CSS(scoped CSS)** — スタイルの影響範囲を 1 コンポーネントに閉じる仕組み。Vue は `data-v-xxx` 属性、CSS Modules はクラス名のハッシュ化で実現する
- **CSS Modules** — CSS ファイルのクラス名をビルド時に一意な名前へ書き換える方式。React 側の標準的な選択肢の 1 つ。Vue でも `<style module>` で使える
- **CSS-in-JS** — JavaScript のコード内に CSS を書き、実行時にスタイルを生成する方式。styled-components、emotion など
- **ユーティリティクラス** — `p-4` `text-slate-500` のように 1 つの CSS 宣言だけを持つ小さなクラス。これを並べてスタイルを組み立てるのが Tailwind の方式
- **デザイントークン** — 色・余白・フォントサイズなど、デザイン上の値に付けた名前。`--color-primary` や `slate-500` がそれにあたる
- **zero-runtime** — 実行時に JS を動かさず、ビルド時に静的な CSS を出力すること。SSG や Server Component と相性がよい
- **`v-bind()` (CSS)** — SFC の `<style>` から `<script setup>` の値を参照する機能。ビルド時に CSS 変数へ変換される

## 関連

- `<style scoped>` の内部の仕組み → [sfc-and-template-syntax.md](./sfc-and-template-syntax.md) §8
- 全体像と対応表 → [vue-vs-react-overview.md](./vue-vs-react-overview.md)
- Nuxt UI / `ui` テンプレートを選ばなかった経緯 → [../nuxi-templates.md](../nuxi-templates.md)
- SSG を採用した理由 → [../../tech-stack/README.md](../../tech-stack/README.md)
- Vue 公式「SFC の CSS 機能」 https://ja.vuejs.org/api/sfc-css-features
- Tailwind CSS 公式「Styling with utility classes」 https://tailwindcss.com/docs/styling-with-utility-classes
