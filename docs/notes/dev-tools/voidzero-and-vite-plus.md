# void0 とは何なのか — VoidZero / Oxc / Rolldown / Vite+ の関係

「void0 というツールが出てきて、Lint も整形もバンドルも全部統合したらしい」という話を整理するメモ。

結論を先に言うと **「void0」はツール名ではなく会社名**で、統合ツールの正体は **Vite+** という別の名前の製品である。ここを分けないと「Next.js で使えるのか」という問いに正しく答えられない。

工程そのものの地図(誰が何を担当しているか)→ [frontend-toolchain-map.md](./frontend-toolchain-map.md)

結論を先に言うと:

- **void0(VoidZero)は会社名。** Vite の作者 Evan You が 2024 年に設立した JavaScript ツーリング専業の会社で、**2026 年に Cloudflare に参加**している。
- **登場する名前は 4 層に分かれる。** 会社(VoidZero)/ 部品(Oxc・Rolldown)/ 個別ツール(oxlint・oxfmt・Vite・Vitest)/ 統合入口(Vite+)。
- **「統合」の技術的な正体は「パーサーを 1 つにしたこと」。** CLI を 1 個にまとめた話ではない。
- **Vite+ はビルドツールではなく「開発の入口」。** npm / nvm / Turborepo に相当する層まで飲み込んでいる。比較相手は ESLint 単体ではなく「npm + nvm + turbo + vite + vitest + eslint + prettier」のセット全部。
- **Next.js で使えるかは Yes/No で割れない。** 統合入口としては **No**(dev / build は Vite そのもの)、部品としては **Yes**(oxlint / oxfmt はフレームワーク非依存)。
- **まだ beta。** 学習対象として追う価値は高いが、実務の主軸に置く段階ではない。

## 4 層に分けて名前を整理する

| 層 | 名前 | 実体 |
|---|---|---|
| **会社** | **VoidZero**(void0) | Evan You が 2024 年に設立した会社。2026 年に Cloudflare に参加 |
| **部品** | **Oxc** | Rust 製の言語ツールチェーン。パーサー / リゾルバ / トランスフォーマ / minifier の集合。**単体では使わない土台** |
| **部品** | **Rolldown** | Rust 製バンドラ。Oxc の上に載る。Rollup の API 互換を狙う |
| **個別ツール** | **oxlint** | Lint 担当。ESLint の位置 |
| **個別ツール** | **oxfmt** | 整形担当。Prettier の位置 |
| **個別ツール** | **Vite** | dev サーバー + 本番ビルド。v8 からバンドラが Rollup → Rolldown |
| **個別ツール** | **Vitest** | テストランナー。Jest の位置 |
| **個別ツール** | **tsdown** | ライブラリ配布用のビルダー(`.d.ts` 込みでパッケージを作る) |
| **統合入口** | **Vite+**(`vp`) | 上記を 1 つのコマンドに束ねた製品。**あなたが聞いた「統合したもの」がこれ** |

依存の向きで描くとこうなる。

```
                    ┌─ oxlint    (Lint)
                    ├─ oxfmt     (整形)
  Oxc ──────────────┼─ minifier  (minify)
  (パーサー/リゾルバ) │
                    └─ Rolldown ─┬─ Vite 8 ─── Vitest
                      (バンドル)  └─ tsdown

  ↑ ここまでが「部品と個別ツール」= 全部 OSS で単体でも使える

  ────────────────────────────────────────────

  Vite+ (vp) ← 上の全部を 1 バイナリに束ね、
               さらに Node バージョン管理・パッケージマネージャ・
               モノレポのタスク実行まで足したもの
```

**「void0 を導入する」という言い方は成立しない。** 導入できるのは oxlint / oxfmt / Rolldown / Vite / Vitest / Vite+ のどれかで、粒度がまるで違う。

## 何が「統合」されたのか — CLI ではなく AST

「統合」を「複数のコマンドを 1 個の CLI にまとめた」と理解すると、ただの利便性の話になってしまう。実際にはもっと下の層で起きている。

第1世代のツールは、**それぞれが自前のパーサーを持っていた**。ESLint は `espree`、Prettier は独自、tsc は TypeScript 本体、esbuild は Go 実装。同じ `app.ts` を 4 回パースして 4 つの AST を作り、3 つを捨てていた。

Oxc がやったのは、**この土台を 1 つに統合すること**。

```
【従来】

  app.ts ─┬→ ESLint   → 自前パーサー → AST①
          ├→ Prettier → 自前パーサー → AST②
          ├→ tsc      → 自前パーサー → AST③
          └→ esbuild  → 自前パーサー → AST④

【Oxc】

  app.ts ─→ Oxc parser ─→ AST (1 個) ─┬→ oxlint
             + resolver               ├→ oxfmt
                                      ├→ Rolldown
                                      └→ minifier
```

得られるものは 2 つ。**速度**(パースが 1 回になる)と、**挙動の一貫性**(「Lint は通るのにビルドが `import` の解決に失敗する」という実装差の事故が消える)。

つまり Vite+ の価値は「コマンドが 1 個で済む」ことより、**下に共通の基盤があること**にある。同じ「統合」を謳っても、単に複数バイナリをラップしただけのものとは性質が違う。

詳しい高速化の内訳(実行環境・並列化を含む 3 要因)→ [frontend-toolchain-map.md の該当節](./frontend-toolchain-map.md#なぜ速くなったのか--理由は-3-つある)

## Vite+ のコマンド一覧 — 何を飲み込んだのか

`vp` のサブコマンドを見ると、Vite+ が置き換えようとしている範囲が分かる。

| グループ | コマンド | 従来これを担っていたもの |
|---|---|---|
| **開発** | `dev` `build` `preview` | Vite |
| **品質** | `check` `lint` `fmt` | ESLint + Prettier + tsc(`check` は 3 つを 1 コマンドで走らせる) |
| **テスト** | `test` | Jest / Vitest |
| **配布** | `pack` | tsdown / Rollup + `tsc -d` |
| **依存管理** | `install` `add` `remove` `update` `why` `outdated` `dedupe` `list` `link` `pm` | **npm / pnpm / yarn** |
| **タスク実行** | `run` `exec`(`vpx`) `dlx` `cache` | npm scripts + `npx` + **Turborepo / Nx** |
| **ランタイム管理** | `node` `env` `toolchain` | **nvm / Volta / fnm** |
| **足場** | `create` `migrate` `config` `hooks` `staged` | `create-vite` + husky + lint-staged |
| **保守** | `upgrade` `implode` | — |

注目すべき点が 3 つある。

1. **`install` / `add` がある。** つまり Vite+ は**パッケージマネージャの層まで来ている**。既存の pnpm / npm / yarn / Bun を検出して使い分ける形で、置き換えというよりラップ。
2. **`node` / `env` がある。** Node.js のバージョン管理(nvm 相当)も範囲内。プロジェクトごとに Node のバージョンを固定できる。
3. **`run` がモノレポ対応のタスクランナー。** 依存関係を見た順序制御とキャッシュを持つ。ここは Turborepo / Nx の領域。

**だから Vite+ の比較相手は ESLint ではない。** 「npm + nvm + turbo + vite + vitest + eslint + prettier + husky + lint-staged」というセット全部が比較相手で、これが「unified toolchain(統合ツールチェーン)」と「entry point(入口)」という言い方をしている理由。

`vp check` の中身は **oxfmt(整形) + oxlint(Lint) + tsgo(型検査)** の 3 つで、**型検査は自前実装ではなく tsgo(TypeScript の Go 移植)に委譲している**。統合ツールの「統合」が必ずしも全部自前実装を意味しないことの例。

## Next.js で使えるのか

**答え: 3 層に分けると綺麗に決まる。**

| Vite+ の層 | 中身 | Next.js で使えるか |
|---|---|---|
| `vp dev` / `vp build` / `vp preview` | **Vite 8 + Rolldown そのもの** | **使えない** |
| `vp lint` / `vp fmt` | **oxlint / oxfmt**(単体でも動くツール) | **中身は使える**。ただし Vite+ 経由は不自然 |
| `vp install` / `vp add` / `vp run` / `vp node` | パッケージマネージャ / タスクランナー / ランタイム管理 | **原理的には可能だが公式に保証なし** |
| `vp test` | Vitest | Next.js でも Vitest 自体は使えるが、Vite+ 経由の組み合わせは検証領域 |

### なぜ dev / build は使えないのか

Next.js は**自前のビルドパイプラインを持つフレームワーク**であって、バンドラを差し替えて使うライブラリではない。

- Next.js のビルドは **Turbopack**(Vercel 製の Rust バンドラ)が担当し、**Next.js 16 では `next dev` と `next build` の両方で既定かつ stable**
- Turbopack は Next.js の App Router の仕組み(Server Components の境界判定、ルートごとのコード分割、RSC ペイロードの生成)と密結合していて、汎用バンドラで代替できる部分ではない
- つまり Vite と Turbopack は**同じ工程を担当する競合**であり、共存させる話ではない

ちなみに Next.js 16.3 では `import.meta.glob`(Vite 由来の API)への対応が入っている。これは「Vite が使えるようになった」のではなく、**Vite で普及した API を Turbopack 側が取り込んだ**という話。互換性の向きが逆。

### なぜ lint / fmt は使えるのか

oxlint と oxfmt は**バンドラに依存しない単体ツール**で、やっているのは「ファイルを読んで AST を作り、ルールを当てる」だけ。Vite があるかどうかは関係ない。

実際 oxlint には **Next.js 用のプラグイン**があり、`eslint-plugin-next` のルールを移植している。ESLint と併用する場合の `eslint-plugin-oxlint` にも Next.js 向けの設定が用意されている。

ただし **Vite+ 経由で入れるのは筋が悪い**。Vite+ は設定を `vite.config.ts` に書く前提で設計されていて、Next.js プロジェクトに `vite.config.ts` を置くのは本末転倒。Next.js で使うなら **oxlint / oxfmt を直接 devDependencies に入れて `.oxlintrc.json` で設定する**のが素直。

(なお「`vite.config.ts` があるだけで oxlint がエラーを出す」という不具合報告が上がっている。Vite+ と Oxc の結合が進んでいる副作用で、Vite を使っていないプロジェクトでは設定ファイルを分ける方が安全という状況。)

### まとめると

> **Vite+ は「Vite を使っている人のための統合入口」。**
> Next.js で欲しいのは統合入口ではなく部品(oxlint / oxfmt)なので、そこだけ直接取る。

このリポジトリの Nuxt は Vite ベースなので、**将来 Vite+ の恩恵を受けられる側**にいる。ここは Next.js との明確な差。

## Biome との違い

「統合ツール」は Vite+ が最初ではない。**Biome** が先に実務投入されている。よく混同されるが、担当範囲がまったく違う。

| | **Biome** | **Vite+** |
|---|---|---|
| 担当工程 | **④Lint + ⑤整形**(+ import 並べ替え等) | **①〜⑨ ほぼ全部** |
| 置き換える対象 | ESLint + Prettier | npm + nvm + turbo + vite + vitest + eslint + prettier |
| ビルド | **やらない** | やる(Vite 8 + Rolldown) |
| 前提 | **なし**(どんなプロジェクトでも入る) | Vite ベースのプロジェクト |
| 出自 | Rome プロジェクトの後継。コミュニティ主導 | VoidZero(Cloudflare 傘下) |
| 成熟度 | 安定版。実務採用あり | **beta** |
| Next.js | **問題なく使える** | 統合入口としては使えない |

**競合ではなく、切り口が違う。**

- Biome は「**横に薄く広く**」— Lint と整形だけを、どんなプロジェクトにでも
- Vite+ は「**縦に厚く**」— Vite プロジェクトの開発体験を丸ごと

だから「Biome か Vite+ か」という問いは筋が悪い。正しい問いは:

- **Vite を使っていないプロジェクト(Next.js / Hono / Laravel など)** → Biome(または ESLint + Prettier、または oxlint 単体)
- **Vite ベースで、ツールを揃えたい** → Vite+ が候補に入る。ただし beta

## beta であることの意味

2026 年 8 月時点で Vite+ は **beta、MIT ライセンスで OSS 化済み**。alpha 以降 500 以上の PR、1,300 以上の依存リポジトリという規模。

「beta だから使えない」ではなく、**何が確定していないかを理解して扱う**という話。

- **設定ファイルの置き場所が動いている。** 設定を `vite.config.ts` に集約する方針が、Oxc 単体の設定ファイル(`.oxlintrc.json`)との関係で揉めている。今の書き方が来年も通る保証はない
- **`vp` はグローバルにインストールするバイナリ + プロジェクトの `vite-plus` パッケージという 2 段構成。** CI やコンテナでの再現性は自分で組む必要がある。このリポジトリのように docker-compose と GitHub Actions で環境を作る構成だと、そこの手当てが増える
- **oxlint / oxfmt / Rolldown 単体は先に安定している。** つまり **Vite+ を待たずに部品だけ先に取れる**。これが現実的な追い方

このリポジトリでの現時点の立場は「**追うが、入れない**」。理由:

1. 目的が「実務と同じ開発環境」なので、まず実務の定石(ESLint + Prettier、Nuxt なら `@nuxt/eslint`)を通るべき
2. Nuxt が使う Vite は現時点で 7.3.6(= Rollup)で、**Vite 8 = Rolldown への移行がまだ来ていない**。土台が動いている最中に上に載せると切り分けが難しくなる
3. ただし **Oxc はもう `node_modules` に居て動いている**(Nuxt の自動インポート解析)。「知らないうちに使っている」状態なので、何者かを把握しておく意味は十分ある

## 用語集

- **VoidZero(void0)** — Evan You が 2024 年に設立した JavaScript ツーリング専業の会社。2026 年に Cloudflare に参加。**ツール名ではない**
- **Oxc** — VoidZero 製の Rust 言語ツールチェーン。パーサー / リゾルバ / トランスフォーマ / minifier の集合。「JavaScript Oxidation Compiler」の略
- **Rolldown** — Oxc の上に載る Rust 製バンドラ。Rollup の API 互換を狙う。Vite 8 以降のバンドラ
- **oxlint** — Oxc 製の Lint ツール。ESLint の位置。870 以上のルールを内蔵し、ESLint 互換 API の JS プラグインも動かせる
- **oxfmt** — Oxc 製の整形ツール。Prettier の位置
- **tsdown** — ライブラリ配布用のビルダー。型定義込みで npm パッケージを作る工程を担う
- **Vite+(`vp`)** — 上記を 1 バイナリに束ねた統合入口。パッケージマネージャ・Node バージョン管理・モノレポタスク実行も含む。2026年8月時点で beta
- **tsgo** — TypeScript コンパイラの Go 移植。`vp check` が型検査を委譲している先
- **Turbopack** — Vercel 製の Rust バンドラ。Next.js 16 では dev / build の両方で既定。Vite / Rolldown の**競合**
- **Biome** — Rome の後継。Lint + 整形を 1 バイナリに統合。Vite 非依存で、担当範囲は Vite+ より狭く適用範囲は広い

## 関連

- 9 工程の定義と全ツールの担当範囲マトリクス → [frontend-toolchain-map.md](./frontend-toolchain-map.md)
- Java / Laravel / Hono では工程の埋まり方がどう違うか → [lint-and-format-by-language.md](./lint-and-format-by-language.md)
- Nuxt と Next.js の設計の違い(なぜビルドの前提が違うのか) → [../vue/nuxt-vs-nextjs.md](../vue/nuxt-vs-nextjs.md)
- 言語ごとに「ビルドが要る/要らない」が変わる理由 → [../build-and-tooling-by-language.md](../build-and-tooling-by-language.md)
