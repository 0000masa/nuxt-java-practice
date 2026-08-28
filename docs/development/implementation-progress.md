# 実装フェーズ計画と進捗

投稿アプリ([設計概要](../superpowers/specs/2026-07-19-app-design-overview.md)、用語は [CONTEXT.md](../../CONTEXT.md))の実装計画と現在地。

**このファイルの運用**: Claude Code のセッション(誰でも・どのセッションでも)が実装に着手するときは、まずこのファイルを読んで現在地を把握し、フェーズの開始・完了時にステータスと「完了メモ」を更新すること。フェーズ内の細かい進捗も「完了メモ」に残してよい。

ステータス: `未着手` / `作業中` / `完了`

| # | フェーズ | 内容 | ステータス |
|---|---|---|---|
| 0 | 設計 | アプリ設計・テーブル設計・各種ドキュメント整備 | 完了 |
| 1 | DB 基盤 | Flyway 導入、全テーブルのマイグレーション(V1)、categories マスタ投入(V2)、パッケージを `com.example.app` に整理 | 完了 |
| 2 | 投稿・タイムライン | posts/categories の API(作成・削除・詳細・タイムライン=カーソルページネーション)+ フロント(タイムライン・投稿詳細・無限スクロール)。認証は未導入のため開発用ユーザーで代用 | 完了 |
| 3 | 認証(パスワード) | Spring Security + Spring Session JDBC(セッションテーブルは V3)。会員登録 → 確認メール(Mailpit)→ メール確認、ログイン/ログアウト、パスワードリセット、**パスワード変更**。フェーズ2の開発用ユーザーを実認証に置き換え。**設計 → [2026-08-05-phase3-auth-design.md](../superpowers/specs/2026-08-05-phase3-auth-design.md)** | 完了 |
| 4 | 認証(Google) | `oauth2Login()` による Google ログイン、同一メールのアカウントリンク(`google_sub` 紐づけ)。**設計 → [2026-08-15-phase4-google-auth-design.md](../superpowers/specs/2026-08-15-phase4-google-auth-design.md)** | 完了 |
| 5 | いいね | トグル API、タイムライン/詳細でのいいね数・自分のいいね状態表示(N+1 を解決する形で) | 未着手 |
| 6 | 画像 | 投稿画像(最大4枚)・プロフィール画像のアップロード(MinIO/S3)と配信、投稿削除時のオブジェクト削除 | 未着手 |
| 7 | プロフィール | プロフィールページ(ユーザー情報 + 投稿一覧)、本人による編集(表示名・bio・画像) | 未着手 |
| 8 | 検索ラボ | 検索 API(対象・一致方法・カテゴリー・方式/件数切り替え、安全上限)、実行時間計測、EXPLAIN 返却、フロント(条件フォーム・プリセット・計測表示) | 未着手 |
| 9 | シードタスク | タスクモード(`--app.task=seed`)実装。users 1万 / posts 100万 / likes 300万 をセットベース SQL で投入 | 未着手 |
| 10 | index 実験 | 検索ラボで実験用 index(複合・FULLTEXT)の before/after を検証し、結果を `docs/notes/` に記録 | 未着手 |
| 11 | 本番イメージ | `nuxt generate` の出力を Spring Boot の `static/` に同梱するマルチステージ Dockerfile、SPA フォールバック、**ECR へ push する GitHub Actions(OIDC AssumeRole)**。IAM は手動作成(循環依存のため)。**手順 → [github-actions-oidc.md](../infrastructure/github-actions-oidc.md)** | 完了 |
| 12 | AWS 運用 | `db-task.yml`(ECS Run Task で create-db-users/migrate/任意SQL)、SES/S3 の本番設定。**フェーズ13 に取り込んで実施済み**(DB ユーザー分離により必須化したため)。残っているのは seed の実行(フェーズ9 待ち) | 完了 |
| 13 | インフラコード | CloudFormation テンプレート(素の YAML)+ パラメータファイル + ワークフロー 3 本 + アプリ側の対応。**設計 → [2026-08-19-phase13-cloudformation-design.md](../superpowers/specs/2026-08-19-phase13-cloudformation-design.md)**、手順書 → [cloudformation-operations.md](../infrastructure/cloudformation-operations.md) | 作業中 |
| 14 | 監視・検知層 | CloudWatch アラーム(RDS メトリクス 4 / RDS ログ 2 / ECS タスク数不足 1)+ RDS イベント購読 + SNS 2 トピック + ログの S3 アーカイブ(Firehose)。**設計 → [2026-08-28-phase14-monitoring-design.md](../superpowers/specs/2026-08-28-phase14-monitoring-design.md)**、方針 → [ADR-0010](../adr/0010-monitoring-in-ephemeral-stack.md) | 作業中 |
| 15 | 通知先の Slack 化 | アラートの宛先をメールから Slack へ。Amazon Q Developer in chat applications(旧 AWS Chatbot)で SNS トピック 2 本を 2 チャンネルに転送。**設計 → [2026-08-28-phase15-slack-notification-design.md](../superpowers/specs/2026-08-28-phase15-slack-notification-design.md)**、方針 → [ADR-0011](../adr/0011-slack-notification-with-chatbot.md)、手順 → [docs/slack/README.md](../slack/README.md) | 作業中 |

## 実装方針(全フェーズ共通)

- バックエンドは**機能別パッケージ**(`com.example.app` 直下に `auth` / `user` / `post` / `like` / `category` / `searchlab` / `seed` + `config` / `common`)。詳細 → [backend-structure-best-practices.md](./backend-structure-best-practices.md)
- フロントは Nuxt 4 の `app/` 配下に配置。API 通信は composables に集約。詳細 → [frontend-structure-best-practices.md](./frontend-structure-best-practices.md)
- テストは**要所に絞る**(案A): ページネーションのクエリ、認証の境界(未確認メール・期限切れトークン)、いいねの重複防止、代表的な `@WebMvcTest` を数本
- スキーマ変更はすべて Flyway(`backend/src/main/resources/db/migration/`)。`ddl-auto` は `validate`
- 実験用 index(複合 index・FULLTEXT)は**マイグレーションに入れない**(フェーズ10で手動 ALTER して before/after を比較するため)
- backend の Java を編集したら `docker compose exec backend sh ./gradlew classes` で反映(CLAUDE.md 参照)

## 完了メモ

- **フェーズ15: アラートの通知先を Slack に移した(実機未検証)**(2026-08-28):
  - **方針** → **[ADR-0011](../adr/0011-slack-notification-with-chatbot.md)**、**設計** → [2026-08-28-phase15-slack-notification-design.md](../superpowers/specs/2026-08-28-phase15-slack-notification-design.md)(決定 9 件)、**手順書を新設** → [docs/slack/README.md](../slack/README.md)
  - **動機は ADR-0010 の帰結 1 の解消。** メール購読は「建てるたびに購読確認を 2 通踏む / 踏むまで 1 通も届かないのにスタックは緑 / 片方だけ踏み忘れるとその系統だけ無音」で、**環境を建てるたびに必ず発生し、失敗しても何も起きない**種類の手作業だった。Slack のチャンネル転送には購読確認の概念が無い
  - **配線は Chatbot に任せ、webhook も Lambda も使わない。** **SNS の HTTPS 購読で Slack の webhook URL を直接叩く構成は成立しない**(Slack が `SubscriptionConfirmation` に応答しないので購読が永久に `PendingConfirmation`。ペイロード形式も合わない)。webhook を使うなら整形役が必須で、その役を AWS に持たせた。**アプリケーション以外のコードを持ちたくない**という判断
  - **作ったもの**: `AWS::Chatbot::SlackChannelConfiguration` 2 本 + Chatbot 用 IAM ロール 1 本。**消したもの**: SNS の email 購読 2 本 + `AlertEmail` パラメータ + `cfn-apply.yml` の `ALERT_EMAIL` ガードと積み込み + Environment secret 1 つ。`app.yml` は 82,361 → **86,936 バイト**(パラメータ 43 / リソース 84 / 出力 18)
  - **アラームの構成には一切触っていない。** SNS トピック 2 本も分割の軸もそのまま。今回は宛先の付け替えだけ。チャンネルは `#njp-alerts-ecs` / `#njp-alerts-rds` で、**stg と prod は共用**(通知にアラーム名が入るので判別できる。分けたくなったら `params` の値を差し替えるだけ)
  - **Slack の ID は `params` に平文で置いた。** ワークスペース ID もチャンネル ID も**秘密ではない**(認可済みの AWS アカウントからしか使えず、「知っていれば誰でも投稿できる」webhook URL とは違う)。`HostedZoneId` と同じ扱い。**結果として仕組みが 1 つ減った**(Environment secret が 5 つ → 4 つ)
  - **踏んだ / 確かめたこと**:
    - **`GuardrailPolicies` を省略すると `AdministratorAccess` が既定で適用される。** 通知の受信に権限は要らないので `AWSDenyAll` を明示した
    - **Chatbot のチャンネル設定は常駐にできない。** トピック名固定で ARN は変わらないが、設定は対象トピックに自分自身を購読させる形で動くので、撤収でトピックが消えると購読も失われる(帰結 1 が形を変えて再発する)
    - **CloudWatch Logs のサブスクリプションフィルタは SNS に送れない**(送信先は Kinesis / Firehose / Lambda / OpenSearch の 4 つだけ)。**E(アプリのエラーログ通知)は Chatbot でも実現しない**ので、ADR-0010 の結論は変わらない
    - **Chatbot の API エンドポイントは us-east-2 の 1 本だけ**だが、ap-northeast-1 のスタックから作ってよい。東京は対応リージョン
    - **IAM の手動作業はゼロ。** リソースを作るのは `AdministratorAccess` を持つ CloudFormation サービスロールなので、`chatbot:*` を足す必要がない(フェーズ14 では `EmptyBuckets` の追加が必要だった)
  - **ADR-0010 の記述を 1 つ訂正した。** 「CloudFormation で Lambda を持つとコード zip の置き場が常駐 S3 として増える」は**事実として誤り**(`Code.ZipFile` にインラインで書ける)。**E を移植しない結論は変わらない**が、理由が間違っていた
  - **着手前に必要な手動作業は Slack 側の 2 つだけ**(どちらも 1 回きり・撤収しても消えない): ① AWS コンソールでワークスペースを認可 ② チャンネル 2 つを作り `/invite @Amazon Q`。得た 3 つの ID を `params` のプレースホルダと置き換える
  - **実機未検証。** 実測で覆りうる項目は設計書 §5 に一覧化した(`/invite` 忘れでも作成が成功するか、RDS イベントの表示形式、OK 通知 7 通の体感、`/aws/chatbot/...` のログ量)

- **フェーズ14: 監視・検知層を実装した(実機未検証)**(2026-08-28):
  - **方針を先に決めた** → **[ADR-0010](../adr/0010-monitoring-in-ephemeral-stack.md)**。参考にした Terraform リポジトリの検知層を**構成を変えずに**移植し、**作り捨て運用との不整合は運用で吸収する**。目的が「実務で通用する構成を書けるようになること」なので、運用の都合に合わせて検知層を削ると学習題材としての価値が落ちるため
  - **設計** → [2026-08-28-phase14-monitoring-design.md](../superpowers/specs/2026-08-28-phase14-monitoring-design.md)(決定 10 件)
  - **作ったもの**: CloudWatch アラーム 7 本(RDS メトリクス 4 / RDS ログ 2 / ECS タスク数不足 1)+ メトリクスフィルタ 2 本 + RDS イベント購読 + SNS トピック 2 本と購読 + ログの S3 アーカイブ(サブスクリプションフィルタ → Firehose → S3)。`app.yml` は 54,178 → **82,361 バイト**(パラメータ 41 / リソース 83 / 出力 16)
  - **アプリのエラーログ通知(Terraform 側の Lambda 経由)は移植していない。** 通知本文にログを入れられないと管理しづらいため、Sentry 等を別途検討する。`EcsLogGroup` のサブスクリプションフィルタ枠は 1/2 しか使っていない(上限 2 本)ので後から足せる
  - **受け入れた 4 つの帰結**(全部 ADR-0010 に記録):
    - 建てるたびに **SNS の購読確認メールを 2 通踏む**。踏むまで通知は 1 通も届かないのに**スタックは緑になる**(SES の EmailIdentity と同じ形)
    - 建てるたびに **「OK になりました」通知が 7 通届く**。新規アラームは `INSUFFICIENT_DATA` → `OK` の遷移でも `OKActions` が発火するため
    - **ログアーカイブは撤収のたびに全部消える。** ライフサイクル(30 日 / 365 日)は一度も発火しない。**保全機能としては動いていない**
    - **スロークエリのアラームはフェーズ8・10 の実験中に鳴り続ける。** 閾値は緩めず人が無視する。**WAF のマネージドルールを入れなかったのと同じ衝突がこれで 2 回目**
  - **フェーズ13 で新しく開いていた穴を 1 つ塞いだ。** `ContainerInsights` の `AllowedValues` から `disabled` を外した。ECS タスク数不足のアラームは `ECS/ContainerInsights` の `RunningTaskCount` に依存していて、標準の `AWS/ECS` に代替が無い。`params` を 1 行変えるだけで**アラームが残ったまま二度と鳴らなくなる**経路だった(Terraform 側は `enhanced` の直書きで、この穴は無かった)
  - **CloudFormation 固有で踏んだこと**:
    - **`{{resolve:ssm-secure:...}}` の対応プロパティ 11 個に `AWS::SNS::Subscription.Endpoint` は入っていない。** 通知先メールは `BasicAuthCredential` と同じく GitHub の Environment secret から渡す(`ALERT_EMAIL`)。`Default` を置かない必須パラメータにして、渡し忘れたら構築が止まるようにした
    - **メトリクスフィルタの出力にはディメンションが無い。** 名前空間に環境名を含めないと stg と prod のメトリクスが合算される(Terraform は `project_name` 自体が環境名込みだったので問題にならなかった)
    - **`force_destroy` が無いことが、書き手のいるバケットで競合になる。** Firehose が 900 秒ごとに書きにくるので、「空にする → `delete-stack`」の間にフラッシュが挟まると `BucketNotEmpty` で `DELETE_FAILED`(確率 10〜20%、しかも 15 分待たされた末に分かる)。`cfn-destroy.yml` に 1 回だけのリトライを入れた
  - **着手前に必要な手動作業が 2 つある(やらないと動かない)**: ① Environment `stg` に secret `ALERT_EMAIL` を登録 ② `gha-cfn-stg` ロールの `EmptyBuckets` にログアーカイブのバケット ARN を追加(→ 手順書 §2-2・§5)
  - **実機未検証。** 実測で覆りうる項目は設計書 §5 に一覧化した(OK 通知の実数、MySQL の起動時に `[ERROR]` が出るか、`# Query_time:` パターンが MySQL でも一致するか、撤収リトライの発生頻度)

- **フェーズ13 追補: CloudFormation を叩くのを `cfn-apply.yml` 1 本に集約**(2026-08-24):
  - **`cfn-deploy.yml` から aws コマンドを全部消した。** `workflow_call` で `cfn-apply.yml`(1 段目・4 段目)と `db-task.yml`(2 段目・3 段目)を呼ぶだけの 5 ジョブになり、258 行 → 147 行(ほぼコメント)。`cfn-apply.yml` と重複していた「テンプレートを S3 経由で渡す」「params を jq で組み立てる」「`--tags` を毎回渡す」が 1 か所に寄った → **[ADR-0009](../adr/0009-cfn-apply-as-the-single-cloudformation-caller.md)**
  - **`cfn-apply.yml` が `--change-set-type CREATE` も担うようになった。** `aws cloudformation deploy` が暗黙にやっていた「スタックが無い / `REVIEW_IN_PROGRESS` なら CREATE」の判定と、`stack-create-complete` / `stack-update-complete` の出し分けを自前で持つ
  - **`workflow_dispatch` から見た `cfn-apply.yml` の挙動は変わっていない。** guard を外す 3 入力(`web_desired_count` / `allow_missing_stack` / `allow_zero_desired_count`)は **`workflow_call` にしか宣言していない**ので、Actions の UI からは触れない。これが安全弁
  - **`cfn-apply.yml` から `concurrency` を削除した。** 呼ばれる側のワークフローレベル `concurrency` も適用されるため、呼び出し側と同じグループ名を持つと親子で枠を取り合って止まる。外しても precheck が `cfn-deploy` / `cfn-destroy` の実行中の全期間を拾うことを確認済み
  - **4 段目にも Change Set の差分と Replacement の安全弁が付いた**(これまで `deploy` を直に叩いていたので差分が出ていなかった)。締めのサマリは 5 段目 `summary` が `outputs` から組み立てるので **AWS を叩かない**
  - **学習メモを 1 本追加** → [reusable-workflows.md](../notes/github-actions/reusable-workflows.md)(`workflow_call` の書き方と 5 つの落とし穴、composite action との使い分け)
  - **実機未検証。** テンプレート置き場の S3 バケットを手動作成するまで構築も反映もできない(下の 2026-08-21 のメモ参照)

- **フェーズ13 追補: 反映専用ワークフローと、テンプレートのサイズ上限**(2026-08-21):
  - **`cfn-apply.yml` を新設。** 既存スタックに `app.yml` / `params` の変更を反映するだけのワークフロー(1 ジョブ)。`create-change-set` → 差分をジョブサマリ → `execute-change-set`。`Replacement: True` を含むときは `allow_replacement=true` が無ければ実行せずに失敗する。実行前に「スタックの存在 / 状態 / `WebDesiredCount`≠0」を確かめる。設計 → 設計書の決定20
  - **アプリのイメージ更新も CloudFormation 経由で行うことを決めた** → **[ADR-0007](../adr/0007-app-deploy-inside-cloudformation.md)**。Terraform 時代の「作成は IaC / 普段の更新は Actions」は CloudFormation では巻き戻りの原因になる。代替(ECS サービスから family だけ参照する)はあるが採らない
  - **`cfn-deploy.yml` の欠陥を修正した。** `app.yml` が **54,178 バイト**で「リクエストに直接載せられる上限 51,200 バイト」を超えており、**初回実行時に必ず失敗する状態だった**(aws-cli が AWS を呼ぶ前に `DeployBucketRequiredError` で落ちる)。3 か所の `deploy` に `--s3-bucket` を追加
  - **テンプレート置き場の S3 バケットが手動管理の常駐リソースとして 1 つ増えた。** `nuxt-java-practice-cfn-templates-<アカウントID>`。作成手順 → 手順書 §3。**この作業をしていないと構築も反映もできない**
  - **`aws cloudformation validate-template --template-body file://...` はもう使えない**(同じ上限が `ValidateTemplate` にも掛かる)。手元の構文チェックは `cfn-lint`
  - **設計書の記述を 3 点訂正した**: 「CloudFormation は実リソースを読み直さない」は既定の Change Set に限る(`--deployment-mode REVERT_DRIFT` は読む)/ `ignore_changes = [task_definition]` には代替がある(family 参照)/ ワークフローは 3 本 → 4 本

- **フェーズ13 実装済み・実機未検証**(2026-08-20): 設計を固めてから実装した。**AWS 上でまだ一度も建てていないので、実機確認は次のセッションの最初の作業。**
  - **設計** → [2026-08-19-phase13-cloudformation-design.md](../superpowers/specs/2026-08-19-phase13-cloudformation-design.md)(決定 19 件)。**[ADR-0005](../adr/0005-separate-db-users-for-app-and-migration.md)**(DB ユーザーの分離)と **[ADR-0006](../adr/0006-basic-auth-with-waf.md)**(Basic 認証を WAF で実装)を追加
  - **作ったもの**:
    - `cloudformation/app.yml` — 1 テンプレートに全リソース(パラメータ 36 / リソース 62 / 出力 13 / Condition 3)
    - `cloudformation/params/{stg,prod}.json` — 環境差分。**`HostedZoneId` は `REPLACE_WITH_HOSTED_ZONE_ID` のままなので埋める必要がある**
    - `.github/workflows/cfn-deploy.yml` / `db-task.yml` / `cfn-destroy.yml`(2026-08-21 に `cfn-apply.yml` を追加)
    - `docs/infrastructure/cloudformation-operations.md` — 手動セットアップ(IAM ロール 2 つ・SSM 4 つ・GitHub Environment・Google・SES)と構築/撤収手順、詰まったときの見どころ
    - アプリ側: actuator(liveness を ALB に使う)、`config/MailSenderConfig` と `SesMailSender`(SES の API 経路)、`config/TaskRunner`(タスクモード)、Flyway を環境変数で切る設定
  - **設計時の想定と違った点(実測で判明)**:
    - **`SPRING_MAIN_WEB_APPLICATION_TYPE=none` では起動できない。** Spring Session JDBC の自動設定が動かず(セッションは Web スコープの機能)、`UserSessionManager` が要求する `FindByIndexNameSessionRepository` が解決できずコンテキストの初期化に失敗する。**Web は普通に立てて起動後に終了させる**形に変え、`APP_TASK=migrate` のタスクモードを実装した。これは**フェーズ9 の `--app.task=seed` の土台**でもある
    - **`app.task` を `application.yml` に書いてはいけない。** `task: ${APP_TASK:}` と書くと未指定でも「空文字のプロパティが存在する」状態になり、`@ConditionalOnProperty` が成立して(false 以外なら成立する判定なので)**通常起動でも即終了する**
    - **actuator のメールのヘルス指標を切る必要があった。** 有効なままだと `mailHealthContributor` の生成が `'beans' must not be empty` で失敗し `@SpringBootTest` のテスト 3 本が落ちる。本番でも集約 `/api/actuator/health` を叩くたびに SMTP 接続を試みるので、切るのが正しい(送信は SES の API 経路で SMTP の生死は無関係)
    - **`MailSender` の Bean は 2 つにならない(当初の想定を後で訂正)。** Boot の `MailSenderAutoConfiguration` には `@ConditionalOnMissingBean(MailSender.class)` が付いているので、`sesMailSender` を登録した時点で**自動設定ごと降り**、`spring.mail.host` の既定値 `${SMTP_HOST:localhost}` があっても SMTP 側は作られない。当初は「候補が 2 つになる」と考えて `@Primary` を付けていたが、**実測して不要と分かったので外した**(2026-08-20)。解説 → [mail-sending-and-transport-switching.md](../notes/java/spring/mail-sending-and-transport-switching.md)
    - **`MAIL_TRANSPORT` の綴り間違いが起動時に検出されなかった。** 切り替えは `@ConditionalOnProperty(havingValue = "ses")` の文字列一致なので、`sess` と打つと**黙って SMTP 側が選ばれ**、本番では `localhost:1025` への接続失敗がログに残るだけになる(送信失敗は握りつぶす設計のため)。`AppProperties.Mail.transport` を enum(`SMTP` / `SES`)にして、不正値は束縛の時点で起動を落とすようにした(2026-08-20)
    - **YAML は 1 ノードに 2 つのタグを付けられない。** `!Base64 !Ref X` は構文エラーで、`Fn::Base64:` の長い形式にする必要がある
    - **`!Sub '${...}:password::'` はクォートが必要。** 末尾のコロンが YAML のマッピング区切りと解釈される
    - **ECS の `containerOverrides` は `secrets` を上書きできない。** 任意 SQL の実行ユーザー切り替えは、環境変数で選ばせてコンテナ側のシェルで分岐する形にした
    - **環境変数に入れた文字列の中の `$VAR` は展開されない。** `SQL` 環境変数にパスワードのプレースホルダを書いても効かないので、ユーザー作成の SQL はシェルのダブルクォート内で組み立てている
  - **実測済み(docker compose 上)**:
    - actuator の公開範囲 — `/api/actuator/health/liveness` は未ログインで 200、`health` / `readiness` は未ログイン 401・ログイン中 200、`env` / `beans` / `configprops` は**ログイン中でも 404**(公開対象外)
    - `APP_TASK=migrate` → 終了コード 0(9 秒、Flyway 適用 → `TaskRunner` → 終了) / `APP_TASK=bogus` → 終了コード 1 / 未指定 → Web が生き続ける
    - `MAIL_TRANSPORT=ses` でコンテキストが起動し、`MailSender` の Bean は `sesMailSender` の 1 個だけ(`JavaMailSender` 型の Bean は 0 個)。`@Primary` を外しても同じ
    - `app.mail.transport=sess`(綴り間違い)で起動失敗 — `No enum constant ...Transport.sess`
    - actuator のメールのヘルス指標を `enabled: true` に戻すと `AuthFlowTest` の 3 本が `'beans' must not be empty` で落ちる(原因は `mailHealthContributor` が具象型 `JavaMailSenderImpl` で Bean を探すのに、テストのモックが `JavaMailSender` インターフェースであること)
    - テスト 46 本すべて成功
  - **実機で確かめること**: SES の DKIM トークンが作り直しで変わるか / Blue/Green の重み入れ替えがリスナールール側だけで成立するか(公式の移行ガイドはリスナーの `DefaultActions` も両ターゲットグループの forward にしている) / Basic 認証を通した状態で Google ログインのコールバックが成立するか / `FARGATE_SPOT` の中断頻度 / RDS の `EngineVersion: "8.4"` がメジャーバージョン指定として通るか
  - **手元の検証で踏んだ罠**: **パイプが終了コードを隠す。** `java -jar app.jar | tail` の終了コードは `tail` のものになるので、起動失敗を「終了コード 0」と読み違えた。`aws ecs run-task` の結果判定でも同じ形の罠があるため、ワークフローでは値を変数に入れてから判定している
- **フェーズ11 完了**(2026-08-18): **フェーズ5〜10(いいね・画像・プロフィール・検索ラボ・シード・index 実験)を飛ばして着手した。** アプリの最低限の機能が揃ったので、機能を増やす前に「AWS にデプロイできる形」を先に通しておくため。飛ばしたフェーズは後で戻って実施する。
  - **作ったもの**:
    - `docker/app/Dockerfile` — 3 ステージ(Node 22 で `npm run generate` → Temurin 21 JDK で SSG 出力を `static/` に入れて `bootJar` → Temurin 21 JRE で実行)。**ビルドコンテキストはリポジトリ直下**(frontend と backend の両方を材料にするため)
    - `.dockerignore`(新規) — `.git` / `node_modules` / 各種ビルド生成物、そして **`.env`**。`.env` には DB パスワードと Google のクライアントシークレットが入っているので、コンテキストに含めないことで焼き込み事故を防ぐ
    - `config/StaticResourceConfig.java` — SSG 出力を配信するためのリソースリゾルバ(下記)
    - `.github/workflows/ecr-push.yml` — `workflow_dispatch` のみ。タグはコミットの短縮 SHA
    - `docs/infrastructure/github-actions-oidc.md` — IAM の作成手順(コンソール / CLI 併記)と、信頼ポリシー・権限ポリシーの逐条解説
    - `.gitignore` に `backend/src/main/resources/static/*` を追加(手元検証で置いた生成物を誤ってコミットしないため)
  - **設計時の想定と違った点(実測で判明)**: **`/` 以外の 8 ページすべてが 404 になる。** 当初は「動的ルート `/posts/{id}` だけ SPA フォールバックが要る」と考えていたが、Nuxt は `login/index.html` という「ディレクトリ + index.html」形式で出力するのに対し、**Spring の `ResourceHttpRequestHandler` はディレクトリに `index.html` を補う機能を持たない**(`/` だけが welcome-page として特別扱いされている)。そのためプリレンダ済みのページも配信されない
  - **`StaticResourceConfig` の解決順序**(3 段): ① 実ファイルがあれば返す ② 拡張子が無く `path/index.html` があれば返す ③ 拡張子が無く `/api` 配下でもなければ `200.html`。それ以外は 404 のまま
    - **`/api` を除外しているのが要点。** 未マッチの `/api/**` は未ログインなら Spring Security が先に 401 を返すが、**ログイン中は 404 として MVC まで届く**ため、除外しないと JSON を期待している呼び出し元に HTML が返る。ログイン済みセッションで `/api/nosuchpath` が **404 + `application/json`** になることを実測で確認済み
    - **拡張子付きを除外しているのも同じ理由。** `/_nuxt/missing.js` が HTML を返すと、壊れた JS として読み込まれる
    - エラーページ方式(404 を `200.html` に飛ばす)を採らなかったのは、**プリレンダ済み HTML が一度も使われなくなる**ため。実測で `/login` は 3,951 バイト(ログインフォームまで描画済み)、`200.html` は 1,080 バイトの空 HTML
  - **動作確認済み(`docker build` + `docker run`、compose の MySQL に接続)**:
    - イメージサイズ **405 MB**、実行ユーザーは **uid 999(非 root)**、**PID 1 が java**(`docker stop` が 0.8 秒で完了 = SIGTERM が届いている。届かないと 10 秒待たされる)
    - **9 ページすべて 200**。プリレンダ済みの 8 ページはそれぞれ異なるサイズの完成 HTML、`/posts/1` と `/nosuchpage` は 1,080 バイトの `200.html`
    - `/favicon.ico` と HTML が参照している `/_nuxt/*.js` は 200、`/_nuxt/missing.js` は 404
    - `/api/categories` は 200 + JSON、`/api/nosuchpath` は未ログイン 401 / ログイン中 404(いずれも JSON)
  - **ワークフローの設計**: タグが不変(ECR が IMMUTABLE)なので、**同じ SHA が既にあればビルド前にスキップして成功終了**する。5〜8 分かけてから `ImageTagAlreadyExists` で落ちるのを避けるため。存在チェックは `ImageNotFoundException` かどうかで分岐しており、権限不足などを「無いからビルドしよう」と取り違えない
  - **キャッシュは buildx の `type=gha,mode=max`。** `mode=max` が必須で、既定の `min` だと最終イメージに残る層しか書き出されず、マルチステージの中間層(`npm ci` と Gradle の依存解決)がまったくキャッシュされない
  - **信頼ポリシーは `StringLike` で `repo:0000masa@134136756/nuxt-java-practice@1303585339:*`**(別ブランチからも push したいため。`@` 以降の数値は下記のとおり GitHub の仕様)。**この選択の代償として、このロールを使うワークフローに `pull_request` トリガーを足してはいけない**(fork からの PR でも `sub` がこのパターンに一致するため)。ワークフローとドキュメントの両方に注意書きを入れてある
  - **テストは書いていない。** `@WebMvcTest` はリソースハンドラを載せず、`static/` はリポジトリ上は空(Docker ビルド時にだけ埋まる)なのでテスト用の静的ファイルを別途用意する必要があり、割に合わないと判断した。代わりに上記の `docker run` + curl の実測で確認している
  - **AWS 側のセットアップと通し確認が完了**(2026-08-18): OIDC プロバイダ・IAM ロール `nuxt-java-practice-gha-ecr-push`・ライフサイクルポリシー(直近 10 個)・GitHub Secrets `AWS_ECR_PUSH_ROLE_ARN` を作成し、ワークフローで **push 成功**(`nuxt-java-practice-ecs:34f5509` の 1 タグのみ。`unknown/unknown` が並んでいないので `provenance: false` が効いている)。**2 回目の実行が事前チェックでスキップ**されることも確認し、存在チェックの両分岐が実データで通った
  - **手順書に無かった落とし穴が 4 つあった**(いずれも [github-actions-oidc.md](../infrastructure/github-actions-oidc.md) の §8 にエラーメッセージから引ける形で追記済み):
    - **IAM のロールの `--description` に日本語が使えない**。`[\u0009\u000A\u000D\u0020-\u007E\u00A1-\u00FF]*` の制約があり ASCII と Latin-1 補助のみ。コンソールの説明欄も同じ。一方 **ECR のライフサイクルポリシーの `description` は日本語で通る**ので、「AWS だから英数字だけ」と一般化しないこと
    - **`--query`(JMESPath)のキー名に日本語が使えない**。引用符なしの識別子は `[A-Za-z_][A-Za-z0-9_]*` のみで、AWS に届く前にクライアント側で失敗する
    - **`sub` クレームにオーナー ID とリポジトリ ID が入る**。`repo:0000masa@134136756/nuxt-java-practice@1303585339:ref:refs/heads/main` の形。GitHub の「不変サブジェクトクレーム」で、**2026-07-15 以降に作られたリポジトリに自動適用**される。ウェブ上の記事はほぼすべて旧形式 `repo:<owner>/<repo>:*` で書かれているのでそのまま真似すると必ず `Not authorized to perform sts:AssumeRoleWithWebIdentity` になる。ID で縛るほうが改名に強く乗っ取りにも耐えるので、戻さず受け入れた
    - **push に `ecr:BatchGetImage` が要る**。`docker buildx build --push` はマニフェストを PUT する前に GET するため。AWS のドキュメントが「push に必要」として挙げる 5 アクションは素の `docker push` の話で、**buildx は 1 手多い**。当初「pull の権限は不要」と書いていた判断が誤りだった
  - **原因の切り分け方**: OIDC の失敗は推測せず、ワークフローに一時的なステップを足して**実際のトークンのクレーム**(`sub` / `aud` / `iss`)を出すのが早い。クレームは公開情報なのでログに出して問題ない(秘密なのは署名)。Secret も値そのものはマスクされるが長さや分解した部分は出せる。手順 → [github-actions-oidc.md](../infrastructure/github-actions-oidc.md) §8「`sub` を実際に確認する」
  - **フェーズ13 への申し送り**: ALB のヘルスチェックに使えるエンドポイントは現状 **`/`(SSG の index.html を 200 で返す)のみ**。actuator は入れていないので、`/actuator/health` を使いたければ依存追加が必要。またイメージタグは CloudFormation の `ImageTag` パラメータとして渡す前提で、ワークフローのジョブサマリに出力している
  - **残っている開発データ**: 検証用に `spa_check` / `spa-check@example.com` / パスワード `password123`(メール確認済み)を作成した。そのままログイン確認に使える
- **フェーズ4 完了**(2026-08-17): [設計](../superpowers/specs/2026-08-15-phase4-google-auth-design.md) §9 の 7 ステップすべて完了。テスト 46 本すべて成功。**実際の Google アカウントで 4 経路すべて確認済み**(下記)。
  - **実機での確認結果(ステップ6)**:
    - **新規作成**: Google 初回ログインで users に 1 行できる。`masanori.basketball@gmail.com` → username `masanori_basketball` が自動生成され、`display_name` は Google の名前、`password_hash` は NULL、`email_verified_at` は作成時刻(確認メールは飛ばない)
    - **`SPRING_SESSION.PRINCIPAL_NAME` がメールアドレスになっている**ことを実データで確認。決定8(`AppOidcUser#getName()` の上書き)が効いている証拠。OIDC の既定のままなら `sub` が入る
    - **アカウントリンク**: `UPDATE users SET google_sub = NULL WHERE id = 28` で「確認済み・パスワード未設定・Google 未連携」の状態を作り、同じ Google アカウントで再ログイン → **users の件数と最大 id が変わらず、`google_sub` が元の値に復活**。`created_at` は据え置きで `updated_at` だけ動いたので、新規作成ではなく **UPDATE が走った = 既存行に紐づいた**と確定できた(この日時の差が一番わかりやすい判定材料)
    - **2 回目以降のログイン**: `sub` ヒットの経路。users に行が増えない
    - **`hasPassword: false` の画面分岐**: `/settings/password` で変更フォームではなくパスワード再設定への案内が出る
    - **戻り先の復元**: 未ログインで `/settings/password` → `/login?redirect=/settings/password` → Google ボタン → **`/settings/password` に着地**。`sessionStorage` + `/auth/callback` の経路(決定11・12)が効いている
  - **ステップ0**: 設計書と [ADR-0004](../adr/0004-google-account-linking.md)(自動アカウントリンク)、`CONTEXT.md` に「Google ログイン」「アカウントリンク」を追加
  - **ステップ1**: `spring-boot-starter-oauth2-client` 追加、`application.yml` に Google の登録、`SecurityConfig` に `oauth2Login()`(`baseUri` を 2 つとも `/api` 配下へ)+ `permitAll` 2 行 + `NullRequestCache`。`.env.example` に `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET`。**スキーマ変更なし**(`users.google_sub` は V1 で作成済み)
  - **ステップ2〜3**: `UsernameGenerator` / `GoogleAccountService` / `GoogleLoginNotAllowedException` / `AppOidcUser` / `AppOidcUserService`、`AuthResponseWriter` に `onOAuth2LoginSuccess` `onOAuth2LoginFailure` を追加
  - **ステップ4**: `MeResponse.CurrentUser` に `hasPassword`。`/settings/password` は false ならフォームを出さずパスワードリセットへ案内
  - **ステップ5**: `components/auth/GoogleButton.vue`(素の `<a href>`)、`utils/postLoginRedirect.ts`、`pages/auth/callback.vue`、`/login` の `?error=` 対応表、`/signup` にもボタン
  - **動作確認済み(curl / ビルド)**:
    - `GET /api/oauth2/authorization/google` が Google へ 302。`redirect_uri` は Host ではなく `APP_BASE_URL` 由来の `http://localhost:3000/api/login/oauth2/code/google`、`scope=openid profile email`、PKCE 付き。**devProxy 経由(3000 番)でも同じ**
    - 資格情報がダミーのままでもアプリが起動し、既存の公開 GET・登録・メール確認・ログインがすべて従来どおり動く。`/api/auth/me` に `hasPassword` が乗る
    - 8 ページすべて 200(`/auth/callback` を含む)、**SSG ビルドは 18 ルートのプリレンダ成功**
  - **SSG ビルドで見つかった問題と対処**: Nitro のクローラが生成 HTML の `<a href>` を辿るため、Google ボタンの `/api/oauth2/authorization/google` を Nuxt のページとして静的化しようとして**ビルドが 404 で落ちた**。`nuxt.config.ts` に `nitro.prerender.ignore: ['/api']` を追加して解決(フェーズ11 でも効いてくる設定)
  - **既存テストへの波及**: `SecurityConfig` が `AppOidcUserService` を要求するようになったため、`@Import(SecurityConfig.class)` を使う `@WebMvcTest` 3 クラス(`PostControllerTest` / `CategoryControllerTest` / `AuthControllerTest`)に `@MockitoBean AppOidcUserService` の追加が必要だった。フェーズ3 の `AuthResponseWriter` と同じ事情
  - **残っている開発データの訂正**: フェーズ3 のメモにある `masa@example.com` / `resetpass123` は**もう通らない**(401)。後のセッションでパスワードが変わったとみられる。新規登録 → メール確認 → ログインの経路は curl で通ることを確認済みなので、動作確認には新しいユーザーを作るのが早い。`dev_user` は「メール確認済み・パスワード未設定」のまま残っており、アカウントリンクの検証データとして使える
  - **`permitAll` は動作上は不要だった**(設計時の想定と違った点): `SecurityConfig` に足した `/api/oauth2/authorization/*` と `/api/login/oauth2/code/*` の `permitAll` は、外しても入口・戻り先とも同じレスポンスを返す(実機で確認)。`OAuth2AuthorizationRequestRedirectFilter` / `OAuth2LoginAuthenticationFilter` がどちらも `AuthorizationFilter` より手前でレスポンスを書いて後続に進まないため、`formLogin` / `logout` とまったく同じ理屈。**公開される URL の一覧として読めるように残してある**
  - **Google Cloud Console 側の設定**(新しい PC では再度必要): OAuth クライアント ID(ウェブアプリケーション)、リダイレクト URI に `http://localhost:3000/api/login/oauth2/code/google`(完全一致・**8080 ではなく 3000**)、同意画面が「テスト」ならログインに使うアカウントをテストユーザーに追加、`.env` に 2 つの値 → 手順は [docs/setup/google-oauth.md](../setup/google-oauth.md)
  - **残っている開発データ**: id 28 `masanori_basketball` / `masanori.basketball@gmail.com` が **Google 連携済み・パスワード未設定**の状態で残っている。`hasPassword: false` の画面や「パスワードログインできないアカウント」の検証にそのまま使える。`dev_user` も同じくパスワード未設定(Google 未連携)
  - **フェーズ5 への申し送り**: いいねは**公開エンドポイントで principal を受ける**ことになるが、`AppOidcUser` が `AppUserDetails` を継承しているので **`@AuthenticationPrincipal AppUserDetails` の 1 種類で両方のログイン手段を受けられる**(ログイン方法による分岐は不要)。未ログイン時に `null` が入る点だけフェーズ3 と同じ扱いにすればよい
- **フェーズ3 完了**(2026-08-06): [設計](../superpowers/specs/2026-08-05-phase3-auth-design.md) §9 の 7 ステップすべて完了。テスト 29 本すべて成功。
  - **ステップ4**: パスワードリセット(申請 → メール → 実行)、ログイン中のパスワード変更、セッション無効化。`UserSessionManager` が `FindByIndexNameSessionRepository#findByPrincipalName` でそのユーザーのセッションを引いて削除する(`SPRING_SESSION.PRINCIPAL_NAME` の index を使う)。リセットは全件削除、パスワード変更は操作中のセッション以外を削除。あわせて未使用のリセットトークンも失効させる
  - **ステップ5**: 認可を確定(公開: 閲覧系 GET と認証系 / 認証必須: `POST /api/posts`、`DELETE /api/posts/{id}`、`PUT /api/auth/password`)。**`CurrentUserProvider` / `DevCurrentUserProvider` を削除**し `@AuthenticationPrincipal` に置き換え。`PostService` は `create(request, userId)` / `delete(id, userId)` に変更
  - **ステップ6**: Pinia 導入。`stores/auth.ts` / `plugins/api.ts`(CSRF ヘッダ + 401 共通処理)/ `plugins/auth.client.ts`(起動時に `/api/auth/me`)/ `composables/useAuth.ts` / `middleware/auth.ts` / 6 ページ / ヘッダの導線。`PostCard` の削除ボタンは自分の投稿にだけ出す。`usePosts` は `$fetch` → `$api` に変更(CSRF ヘッダが必要なため)
  - **ステップ7**: テスト 3 クラス追加(`AuthTokenServiceTest` 7 本 / `AuthControllerTest` 7 本 / `AuthFlowTest` 2 本)、`docs/api/` に認証系 9 ファイル追加 + 既存 3 ファイル更新、`docs/test/README.md` のテスト一覧更新。**合計 29 本すべて成功**
  - **動作確認済み(ステップ4〜6)**:
    - パスワード変更: 2 端末でログイン → 変更 → セッション 2 → 1、操作端末は維持、別端末は `user: null`、旧パスワードは 401
    - パスワードリセット: 未登録 400 / 未確認 400 / 確認済み 204 + メール到着 → 実行で**セッション 0 件**(全端末追い出し)→ 2 回目のリンクは 400
    - 認可: 公開 GET 4 本すべて 200、未ログイン POST は CSRF なしで 403 / CSRF ありで 401、ログイン後の投稿は 201 で投稿者が正しい、他人の投稿の削除は 403
    - `eraseCredentials` の効果: `SPRING_SESSION_ATTRIBUTES` にパスワードハッシュが残らないことを実データで確認(実装前に作られたセッションには残っていたので、対比で確かめられた)
    - フロント: 8 ページすべて 200、devProxy 経由で Cookie と `X-XSRF-TOKEN` が正しく通る(`/api/auth/me` → ログイン → 投稿 → ログアウト)
    - **SSG ビルド**(`npm run generate`): 16 ルートのプリレンダ成功。`/settings/password` が「`/login` へのリダイレクト」ではなく本来の内容として静的化されており、middleware の `import.meta.server` ガードが効いていることを生成 HTML で確認
  - **未確認**: ブラウザでの実操作(フォーム送信、Pinia の状態遷移、ヘッダの表示切り替え)。curl と SSG ビルドまでは通っているが、画面上のクリック操作は試していない
  - **ステップ1**: 依存追加(`spring-boot-starter-security` / `spring-boot-starter-session-jdbc` / `spring-boot-starter-mail` / test に `spring-boot-starter-security-test`)、V3(Spring Session の公式 MySQL DDL を jar から取り出して取り込み)、`SecurityConfig` の骨格。`application.yml` に `spring.session.timeout: 1d` と `spring.session.jdbc.initialize-schema: never`
  - **ステップ2**: `AppUserDetails` / `AppUserDetailsService` / `formLogin` / `logout` / `GET /api/auth/me` / CSRF / `AuthResponseWriter`(Spring Security のリダイレクトを JSON に差し替える役)。curl で全経路を確認済み(CSRF なし → 403、パスワード誤り・未登録・パスワード未設定 → すべて同一の 401、未確認メール → 区別した 401、成功 → 200 + `SESSION` Cookie + `SPRING_SESSION` に 1 行、ログアウト → 204 + セッション行削除)
  - **ステップ3**: `AuthToken` / `AuthTokenPurpose` / `AuthTokenRepository` / `AuthTokenService`(SHA-256 ハッシュ保存)/ `AuthMailSender`(`@TransactionalEventListener(AFTER_COMMIT)`)/ `AuthService.signup` `verifyEmail` `resendVerification` / `AuthController` / `config/AppProperties`。`.env` と `.env.example` に `APP_BASE_URL` と `MAIL_FROM` を追加。共通例外を 2 つ追加(`InvalidRequestException` → 400、`FieldValidationException` → 400 + `fieldErrors`)
  - **ステップ3 の動作確認済み**: 登録 → Mailpit にメール到着 → 未確認ではログイン 401 → メール確認 204 → 同じリンク 2 回目は 400 → ログイン成功 200。確認済みメールの再登録 → 400 `fieldErrors.email`、既存 username → 400 `fieldErrors.username`、**未確認メールの再登録 → users の id が変わる(削除して作り直し = ADR-0003 の pre-hijacking 対策が効いている)**、再送は確認済み 400 / 未確認 204 / 未登録 400
  - **Spring Boot 4 / Spring Security 7 で判明した差異**(設計時の想定と違った点):
    - **Jackson が 3 系**。`com.fasterxml.jackson.databind` は存在せず `tools.jackson.databind` に移動している(アノテーションだけ `com.fasterxml.jackson.annotation` のまま)
    - **Spring Security は 7.1.0**。CSRF は `csrf(csrf -> csrf.spa())` の 1 行で済む。設計時に想定していた「XOR ハンドラの差し替え + 初回 Cookie 発行の自作フィルタ」は不要になった(`spa()` が Cookie リポジトリ・SPA 向けトークン解決・認証成功/ログアウト後の再発行をまとめて面倒を見る)
    - セッション用の starter は素の `spring-session-jdbc` ではなく **`spring-boot-starter-session-jdbc`**(Flyway と同じく、自動設定はこちら側に入っている)
    - **`@WebMvcTest` は Boot の既定のセキュリティ設定(全リクエスト認証必須)を使う。** アプリの認可ルールを効かせるには `@Import(SecurityConfig.class)` が必要で、これを入れないと公開しているはずの `GET /api/posts` や `GET /api/categories` が 401 になる。入れる場合は `SecurityConfig` が要求する `AuthResponseWriter` を `@MockitoBean` で用意することになり、その副作用で **401 / 403 のステータスコード自体はこのスライスで検証できなくなる**(ステータスを書くのがモックにした側なので)
    - `@AutoConfigureMockMvc` の import は `org.springframework.boot.webmvc.test.autoconfigure`
  - **残っている開発データ**: `masa@example.com` / パスワード `resetpass123`(確認済み。動作確認にそのまま使える)、`pending@example.com`(未確認のまま)、`dev_user`(パスワード未設定なのでログイン不可 = フェーズ4 の Google 専用ユーザーと同じ状態)
  - **フェーズ4 への申し送り**: `SecurityConfig` に `oauth2Login()` を足す形になる。`AppUserDetails` は `users.id` とメールしか持たないので Google 由来のユーザーにもそのまま使える。`dev_user` が「メール確認済み・パスワード未設定」の状態で残っているので、アカウントリンクの検証データとして使える
  - **別途相談したい点**: `npm audit` が 6 件(critical 1 件)を報告している。すべて Nuxt のツールチェーン側(`@nuxt/devtools` / `@nuxt/vite-builder` / `brace-expansion` / `postcss` / `tar`)で SSG 出力には含まれないが、critical の `@nuxt/devtools`(未認証 RPC による任意コマンド実行)は `devtools: { enabled: true }` のまま dev サーバーを `0.0.0.0:3000` で公開しているため、共有ネットワークでは注意が必要
- **フェーズ3 の設計確定**(2026-08-05): 実装前に設計を詰めた。成果物 → [2026-08-05-phase3-auth-design.md](../superpowers/specs/2026-08-05-phase3-auth-design.md)(決定 14 件・API 一覧・フロー図・実装順序)、[ADR-0002](../adr/0002-session-cookie-over-jwt.md)(セッション Cookie 方式を採り JWT を発行しない)、[ADR-0003](../adr/0003-account-enumeration-and-unverified-signup.md)(ユーザー列挙を許容し未確認アカウントは再登録で作り直す)、`CONTEXT.md` に「メール確認 / パスワードリセット / パスワード変更」を追加。**スコープ追加**: パスワードリセット時のセッション無効化と同じ仕組みが使えるため、ログイン中のパスワード変更(`PUT /api/auth/password` + `/settings/password`)も含めることにした。**方針転換**: フェーズ2 で用意した `CurrentUserProvider` / `DevCurrentUserProvider` は使わず削除し、Spring Security 標準の `@AuthenticationPrincipal` に寄せる(`PostController` / `PostService` / `PostControllerTest` のシグネチャ変更を伴う)
- **テスト DB の分離**(2026-07-26): テストの接続先を開発 DB(`app`)から専用 database `app_test` に切り替えた。`build.gradle` の `test` タスクで `environment 'DB_NAME', 'app_test'` を指定するだけで、`application.yml` の `${DB_NAME:app}` を上書きできる。`app_test` は**クローン後に手動で 1 回作成が必要**(Flyway は database 自体を作らない。MySQL の init SQL は空ボリュームのみ有効なため自動化していない)。空の database を用意すれば Flyway が V1/V2 を流してテーブル 6 つ + カテゴリー 10 件を用意する。手順とテスト方針 → [docs/test/README.md](../test/README.md)、仕組みの解説 → [testing-and-test-database.md](../notes/java/spring/testing-and-test-database.md)
- **リファクタ**(2026-07-25): `CategoryController` が `CategoryRepository` を直接呼んでいた箇所に `CategoryService` を追加し、`Controller → Service → Repository` の三段構えを全機能で統一。当初は「単純な参照のみなので Service を挟まない」判断だったが、[backend-structure-best-practices.md](./backend-structure-best-practices.md) の「Controller は薄く、ロジックは Service に寄せる」に揃える方針を優先した。`@Transactional(readOnly = true)` の置き場が生まれ、`CategoryControllerTest`(`@WebMvcTest` + Service モック)を追加できるようになった
- **フェーズ2**(2026-07-20): 投稿・タイムライン完成。
  - backend: 機能別パッケージ(`user` / `category` / `post` / `common`)で API 実装。`GET/POST /api/posts`、`GET/DELETE /api/posts/{id}`、`GET /api/categories`。タイムラインは fetch join + `limit+1` 方式のカーソルページネーション。認証までのつなぎとして `CurrentUserProvider` インターフェース(フェーズ3でセッション実装に差し替え)+ `DevCurrentUserProvider`(dev_user を自動作成)
  - frontend: `app/` 配下に pages(タイムライン `/`・詳細 `/posts/[id]`)、components(`PostCard` / `PostForm`)、composables(`usePosts` / `useCategories`)、types。無限スクロールは IntersectionObserver。**API 取得は全て `server: false` / クライアント側**(SSG でビルド時にバックエンドが居ないため)
  - テスト: `PostRepositoryTest`(カーソル境界 4 本・実 MySQL でロールバック実行)+ `PostControllerTest`(バリデーション 5 本・`@WebMvcTest`)全て成功
  - **注意: Spring Boot 4 はテストアノテーションのパッケージも移動している。** `@DataJpaTest` → `org.springframework.boot.data.jpa.test.autoconfigure`、`@WebMvcTest` → `org.springframework.boot.webmvc.test.autoconfigure`、`@AutoConfigureTestDatabase` → `org.springframework.boot.jdbc.test.autoconfigure`。`@MockBean` は廃止で `@MockitoBean`(`org.springframework.test.context.bean.override.mockito`)を使う
  - 動作確認済み: curl で API 一式(カーソル・絞り込み・400/404/403/204)、Nuxt 側はページ描画と devProxy 経由の API 疎通まで(ブラウザでの無限スクロール操作は未確認)
- **フェーズ1**(2026-07-19): Flyway 導入完了。V1(全6テーブル)+ V2(categories 10件)適用済みを MySQL 実機で確認。パッケージは `com.example.demo` → `com.example.app`(メインクラス `Application`)、`rootProject.name = 'app'`、`ddl-auto: validate` + `open-in-view: false` に変更。**注意: Spring Boot 4 は自動設定がモジュール分割されており、`flyway-core` 単体では Flyway が有効にならない。`spring-boot-starter-flyway` が必要**(+ MySQL 用に `flyway-mysql`)
- **フェーズ0**(2026-07-19): 設計確定。成果物 → `docs/superpowers/specs/2026-07-19-app-design-overview.md`、`CONTEXT.md`、backend/frontend の structure-best-practices。Nuxt は実際には 4 系だったためドキュメント側を Nuxt 4 表記に修正済み
