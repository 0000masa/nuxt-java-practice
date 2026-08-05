# 認証はセッション Cookie 方式(Spring Session JDBC)を使い、JWT を発行しない

日付: 2026-08-05
ステータス: accepted

## 決定

認証状態は **サーバー側のセッション**で保持し、ブラウザには **セッション ID だけを入れた Cookie** を渡す。セッションの実体は **MySQL**(`spring-session-jdbc` の `SPRING_SESSION` / `SPRING_SESSION_ATTRIBUTES`)に置く。**JWT は発行しない。**

ログインの入口は Spring Security 標準の **`formLogin()`** に乗せ、`loginProcessingUrl` を `/api/auth/login` にして success / failure ハンドラだけ JSON 返却に差し替える。

## 背景と理由

このアプリは **Nuxt を SSG ビルドして Spring Boot の `static/` から配信する**(設計概要のアーキテクチャ決定 2)。つまりフロントと API は**完全な同一オリジン**で、Cookie が素直に使える。JWT が本来必要になる状況(別ドメインの SPA、モバイルアプリ、サーバーをまたぐマイクロサービス)がこのアプリには存在しない。

JWT を選ばなかった理由は 3 つ。

1. **失効できない。** ログアウト、パスワード変更、アカウント停止のたびに「もう無効です」と言えないのが JWT の本質的な弱点で、対策としてブラックリストを DB に持つと**結局セッションを再発明する**ことになる。このアプリはパスワードリセット時に全端末を強制ログアウトする要件([フェーズ3 設計](../superpowers/specs/2026-08-05-phase3-auth-design.md) の決定11)があり、セッション方式ならこれが `PRINCIPAL_NAME` の index で 1 クエリで済む
2. **保存場所に安全な選択肢がない。** localStorage は XSS で読める。HttpOnly Cookie に入れるなら、それはもうセッション ID を Cookie に入れているのと同じで、JWT である必要がない
3. **記事の数と適切さが一致していない。** 「Spring Boot 認証」で出てくる記事は JWT が多数派だが、Spring チーム自身は同一オリジンの Web アプリに Cookie セッションを勧めている。多数派に合わせる理由がない

MySQL を選んで Redis を選ばなかったのは、① 開発環境のコンテナを増やさずに済む ② `SELECT * FROM SPRING_SESSION` でログイン状態を目で見られる、という**学習リポジトリとしての利点**が、性能上の不利(リクエストごとにセッションの SELECT / UPDATE が走る)を上回るため。この規模では性能差が体感できない。

`formLogin()` を選んだのは、セッション固定攻撃対策(認証成功時のセッション ID 再発行)と `SecurityContext` のセッションへの保存をフレームワークに任せられるため。特に後者は、Spring Security 6 以降は自前実装だと `SecurityContextRepository.saveContext()` を明示的に呼ばないと保存されず**ログインが維持できない**という有名な落とし穴があり、標準機構に乗ればそもそも踏まない。

## 検討したが採らなかった選択肢

- **JWT を自前発行** — 上記 3 点の理由で見送り。将来モバイルアプリを足すなら再検討の余地がある
- **セッションを Redis に置く** — 本番で複数インスタンスを走らせるなら実務での定番。今回は MySQL でも複数インスタンス間の共有はできるため、コンテナを増やす理由がなかった
- **IdP に委譲(Cognito / Keycloak / Auth0)** — 業務システムでは非常に多い選択で、「認証は作らず買う」のは正しい判断になりうる。ただし**認証の中身を学ぶことがこのフェーズの目的**なので、ブラックボックスに預けると目的を失う
- **自前 `AuthController` で `AuthenticationManager` を直接呼ぶ** — 全 API が JSON で揃い、認証の手順がコードとして読める利点があった。`formLogin()` の標準機構(特に上記の `saveContext` 問題)を捨てるほどの利点ではないと判断

## 結果として生じること

- **ログインリクエストだけ JSON ではなく form-urlencoded になる。** `formLogin()` は JSON ボディを読めないため、フロントは `/api/auth/login` にだけ `URLSearchParams` で送る。他の API とフロントの書き方が揃わないのは、この決定の代償として受け入れたもの。**「統一されていないから直そう」と JSON 化するとこの決定を壊すことになる**
- **CSRF 対策が必須になる。** Cookie でセッションを持つと CSRF が成立するため、Spring Security の CSRF を有効にし、`XSRF-TOKEN` Cookie → `X-XSRF-TOKEN` ヘッダ方式でフロントから送る。「REST API だから CSRF は不要」は Bearer トークン方式の話であって、この構成には当てはまらない
- **CSRF トークンの初回発行を意識する必要がある。** Spring Security 6 はトークンを遅延発行するため、ログイン前に一度 GET してトークンを受け取る必要がある。この役目は `GET /api/auth/me`(アプリ起動時に必ず叩く)が兼ねる
- **セッションテーブルを Flyway で管理する。** `spring.session.jdbc.initialize-schema` の既定は `embedded` なので MySQL では何も作られない。公式 DDL を V3 として取り込む。この 2 テーブルに JPA エンティティは作らないので `ddl-auto: validate` の検証対象外
- **スケールアウト時は DB がセッションの共有点になる。** ECS Fargate でタスクを増やしてもセッションは共有されるが、そのぶん RDS への読み書きが増える。性能が問題になったら Redis(ElastiCache)への差し替えを検討する — `spring-session-data-redis` に入れ替えるだけで済むよう、アプリコードは Spring Session の抽象より下に依存させない
