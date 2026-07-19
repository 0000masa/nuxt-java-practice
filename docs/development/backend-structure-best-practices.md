# Spring Boot パッケージ・フォルダ構成ベストプラクティス

Java 21 + Spring Boot + Gradle で REST API(`/api/**`)を提供する本プロジェクトの backend 構成の参考資料。
Web 調査(Spring 公式ドキュメント + コミュニティ記事)の結果をまとめたもの。実際の構成を決める際はこの資料を参照する。

前提: Spring Security(パスワード + Google OAuth2)、Spring Session JDBC、MySQL、メール送信、S3 クライアント、Nuxt SSG 出力の `static/` 配信。

---

## 1. パッケージ構成: レイヤー別 vs 機能別

### レイヤー別(package by layer)

`controller` / `service` / `repository` / `entity` / `dto` … と**技術的な役割**でパッケージを切る古典的な方法。

- 長所: 直感的で学習コストが低い。小〜中規模や学習用途で扱いやすい。多くの入門記事・チュートリアルがこの形
- 短所: 1つの機能(例: ユーザー機能)に関わるクラスが全パッケージに散らばる。機能追加・削除時に多数のパッケージを横断することになり、規模が大きくなるほど見通しが悪化。クラスを package-private にできず、レイヤー間の境界をコンパイラで守れない

### 機能別(package by feature)

`user` / `post` … と**ドメイン機能**でパッケージを切り、その中に controller/service/repository を配置する方法。

- 長所: 高凝集。関連クラスが1フォルダにまとまり、機能の追加・削除・(将来の)マイクロサービス分離が容易。機能内部のクラスを package-private にでき、他機能からの誤用を防げる
- 短所: レイヤー別に慣れた人には最初やや不慣れ

### 現在の主流・推奨

**Spring 公式ドキュメント(Structuring Your Code)は機能別(package by feature)を推奨**している。公式サンプルも `customer/`・`order/` という機能パッケージの中に Controller/Service/Repository/Entity を置く形。コミュニティでも「小規模ならレイヤー別で十分、チーム開発・中規模以上なら機能別」が共通見解。

### 公式が明示する2つの必須ルール(どちらの方式でも守る)

1. **デフォルトパッケージ(package 宣言なし)を使わない。** `@ComponentScan` / `@EntityScan` / `@SpringBootApplication` が全 classpath を走査してしまい問題になる。`com.example.app` のように逆ドメインで切る
2. **メインクラス(`@SpringBootApplication` 付き)をルートパッケージに置く。** 直下のパッケージが自動的にコンポーネントスキャン / エンティティスキャンの基点になる

---

## 2. 各レイヤーの責務分担

| レイヤー | 責務 | やってはいけないこと |
|---|---|---|
| **Controller** | HTTP の入口。リクエスト受信、Request DTO へのバインド + バリデーション(`@Valid`)、Service の呼び出し、Response DTO を返す | ビジネスロジックを書かない。Entity を直接返さない |
| **Service** | ビジネスロジックの中心。トランザクション境界(`@Transactional`)、複数 Repository の協調、ルール適用、Entity ⇔ DTO 変換の起点 | HTTP/Web 層のクラス(HttpServletRequest 等)に依存しない |
| **Repository** | DB アクセス。`JpaRepository` を継承したインターフェース。CRUD とクエリ定義 | ビジネスロジックを持たない |
| **Entity** | DB テーブルにマッピングされる永続化モデル(`@Entity`, `@Column`) | API レスポンスとして外に出さない |
| **DTO / Request / Response** | 層をまたぐデータ運搬。入力用(Request)と出力用(Response)を分ける。バリデーション注釈は Request DTO に付ける | Entity の代わりに使い回さない |
| **Mapper** | Entity ⇔ DTO の変換責務を集約。手書き or MapStruct | — |
| **Config** | Security/Session/S3/Mail/Jackson などの `@Configuration` | — |
| **Exception** | `@RestControllerAdvice` によるグローバル例外ハンドリング + 独自例外クラス | 各 Controller で try-catch を散らさない |

補足のベストプラクティス:

- **Service はインターフェース + `Impl` に分ける流儀**(`UserService` / `UserServiceImpl`)が production 記事で定番。ただしテスト用モックが不要なら実装クラス1つでも可(過剰設計を避ける判断もあり)
- **統一レスポンス**: `ApiResponse<T>` のような共通ラッパーでエラー/成功の形を揃えると API が一貫する
- Controller は薄く(thin controller)、ロジックは Service に寄せる

---

## 3. DTO とエンティティの分離(慣習)

- **Entity を Controller から直接返さない / 受け取らない**のが強い慣習。理由: DB スキーマの露出防止、API 入力の単純化、内部構造変更が API 破壊につながらない、バリデーションの分離、セキュリティ(意図しないフィールドの受付/漏洩を防ぐ、いわゆる mass assignment 対策)
- **入力用 Request DTO と出力用 Response DTO を分ける**(`CreateUserRequest` / `UserResponse`)。バリデーション注釈は Request 側に集約
- 変換は **Mapper に集約**。手書きでも良いが、フィールドが増えるなら **MapStruct**(コンパイル時生成、リフレクションなしで高速)が定番

---

## 4. `src/main/resources` 配下の構成

```
src/main/resources/
├── application.yml              # 共通設定
├── application-dev.yml          # 開発(docker-compose: MySQL/MinIO/Mailpit 向け)
├── application-prod.yml         # 本番(RDS/S3/SES 向け。値は環境変数参照)
├── db/
│   └── migration/               # Flyway マイグレーション(後述)
├── static/                      # Nuxt SSG の generate 出力を配置 → Tomcat が配信
└── templates/                   # (メールテンプレート等を使う場合)
```

プロファイル分割の方針:

- `application.yml` に共通、`application-{profile}.yml` に環境差分。1ファイル内で `---` + `spring.config.activate.on-profile` により分割も可能
- **秘匿値(DB パスワード、OAuth クライアントシークレット、AWS 認証情報、メール認証)はハードコードせず環境変数参照**(`${DB_PASSWORD}` 等)。本プロジェクトの「環境変数方針」と整合
- `static/` に置いた SSG 出力を組み込み Tomcat が配信する構成なので、Nginx や別フロント配信基盤は不要(本プロジェクトのアーキテクチャ決定と一致)

---

## 5. Flyway を使う場合の標準配置

- **デフォルトのスクリプト配置場所は `classpath:db/migration`**(= `src/main/resources/db/migration/`)。Spring Boot は起動時に自動でこのディレクトリを読み、primary datasource に対して実行する
- **命名規則**: `V<バージョン>__<説明>.sql`(アンダースコア2つ)。例: `V1__create_users_table.sql`, `V2__add_google_oauth_columns.sql`。繰り返し実行系は `R__` プレフィックス
- **Spring Session JDBC のテーブル**(`SPRING_SESSION` / `SPRING_SESSION_ATTRIBUTES`)も Flyway マイグレーションで作るのが本番では安全(自動 DDL に頼らない)。MySQL 用の公式スキーマ DDL を `V__` として取り込む
- **プロファイル別ロケーション**も可能:

  ```yaml
  spring:
    flyway:
      locations: classpath:db/migration/common
  ---
  spring:
    config.activate.on-profile: dev
    flyway:
      locations: classpath:db/migration/common,classpath:db/migration/dev
  ```

  共通マイグレーション + 開発用シードデータを分けたいときに有効

---

## 6. テストコードの構成の慣習

- 配置は本体とミラー: `src/test/java/com/example/app/...` に対象と同じパッケージで置く(テストスライスが `@SpringBootApplication` を上位パッケージから自動発見できる)
- **命名で単体/結合を区別**: 単体は `〜Test`、結合(重い/外部依存)は `〜IT`(Integration Test)。Gradle なら `test` と(必要に応じ)`integrationTest` タスク/ソースセットで分離
- **テストスライスを使い分ける**(フルコンテキスト起動を避け高速化):
  - `@WebMvcTest` — Controller 層のみ。Service はモック。バリデーション・エラーレスポンス・ステータスコードの検証
  - `@DataJpaTest` — Repository/JPA 層のみ。トランザクションは各テスト後ロールバック
  - `@SpringBootTest` — アプリ全体を起動する結合テスト。Web 環境モードや TestRestTemplate/WebTestClient を使う
- **外部依存(MySQL/S3/メール)は Testcontainers** で実物に近い形でテストするのが近年の主流。単体テストではモックで代替

---

## 7. 具体的なディレクトリツリー例(本アプリ向け)

### 推奨案A: 機能別(package by feature)— 公式推奨・中規模以上向け

```
backend/
├── build.gradle
├── settings.gradle
└── src/
    ├── main/
    │   ├── java/com/example/app/
    │   │   ├── App.java                       # @SpringBootApplication(ルート)
    │   │   ├── user/                          # ユーザー機能
    │   │   │   ├── UserController.java
    │   │   │   ├── UserService.java
    │   │   │   ├── UserRepository.java
    │   │   │   ├── User.java                  # Entity
    │   │   │   ├── UserMapper.java
    │   │   │   └── dto/
    │   │   │       ├── CreateUserRequest.java
    │   │   │       └── UserResponse.java
    │   │   ├── auth/                          # 認証(パスワード + Google OAuth2)
    │   │   │   ├── AuthController.java
    │   │   │   ├── AuthService.java
    │   │   │   └── dto/
    │   │   ├── <他の機能>/ ...
    │   │   ├── config/                        # 横断的な設定
    │   │   │   ├── SecurityConfig.java        # Spring Security + OAuth2
    │   │   │   ├── SessionConfig.java         # Spring Session JDBC
    │   │   │   ├── StorageConfig.java         # S3 クライアント
    │   │   │   ├── MailConfig.java
    │   │   │   └── WebConfig.java
    │   │   └── common/                        # 共通・横断
    │   │       ├── exception/
    │   │       │   ├── GlobalExceptionHandler.java  # @RestControllerAdvice
    │   │       │   └── ResourceNotFoundException.java
    │   │       ├── dto/ApiResponse.java       # 共通レスポンスラッパー
    │   │       └── util/
    │   └── resources/
    │       ├── application.yml
    │       ├── application-dev.yml
    │       ├── application-prod.yml
    │       ├── db/migration/
    │       │   ├── V1__create_users_table.sql
    │       │   └── V2__create_spring_session_tables.sql
    │       └── static/                        # Nuxt SSG 出力
    └── test/
        └── java/com/example/app/
            ├── user/
            │   ├── UserControllerTest.java    # @WebMvcTest
            │   ├── UserServiceTest.java       # 単体(Mockito)
            │   └── UserRepositoryTest.java    # @DataJpaTest
            └── AppIntegrationIT.java          # @SpringBootTest(Testcontainers)
```

### 参考案B: レイヤー別(package by layer)— 学習用の入り口として

```
src/main/java/com/example/app/
├── App.java
├── controller/       # 各種 Controller
├── service/
│   └── impl/         # インターフェース + Impl 方式を採る場合
├── repository/
├── entity/
├── dto/
│   ├── request/
│   └── response/
├── mapper/
├── config/           # SecurityConfig, SessionConfig, StorageConfig, MailConfig
├── exception/        # GlobalExceptionHandler + 独自例外
├── constant/         # API パス・定数
└── util/
```

---

## 出典 URL 一覧

- Spring 公式: [Structuring Your Code](https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html)
- [Spring Boot Project Structure Best Practices Used in Production (DEV)](https://dev.to/kamlesh_patil/spring-boot-project-structure-best-practices-used-in-production-4h85)
- [Package by Layer vs Package by Feature (Medium / Akın Topbaş)](https://medium.com/@akintopbas96/spring-boot-code-structure-package-by-layer-vs-package-by-feature-5331a0c911fe)
- [Spring Boot Project Structure Best Practices: Layer-Based vs Feature-Based (Medium / Mohamed Rifai)](https://rifaiio.medium.com/spring-boot-project-structure-best-practices-layer-based-vs-feature-based-explained-simply-4a9002f3cff0)
- [Spring Boot Layered Architecture: Controller, Service, Repository (Compile My Mind)](https://www.compilemymind.com/posts/spring-boot-layered-architecture/)
- [Service Layer Pattern in Java With Spring Boot (foojay.io)](https://foojay.io/today/service-layer-pattern-in-java-with-spring-boot/)
- [Flyway with Spring Boot (BootcampToProd)](https://bootcamptoprod.com/flyway-with-spring-boot/)
- [Flyway migrations with Spring (j-labs)](https://www.j-labs.pl/en/tech-blog/flyway-migrations-with-spring/)
- [Structuring and Testing Modules and Layers with Spring Boot (reflectoring)](https://reflectoring.io/testing-verticals-and-layers-spring-boot/)
- [Spring Boot Test Slices: @WebMvcTest vs @DataJpaTest (Medium / Javarevisited)](https://medium.com/javarevisited/spring-boot-test-slices-webmvctest-vs-datajpatest-explained-aea25e1bd2cf)
- [How I test production-ready Spring Boot applications (Wim Deblauwe)](https://www.wimdeblauwe.com/blog/2025/07/30/how-i-test-production-ready-spring-boot-applications/)
