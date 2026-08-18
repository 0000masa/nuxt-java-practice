# 本番用: Nuxt の SSG 出力を Spring Boot の static/ に同梱した 1 個のイメージを作る。
# 開発用の Dockerfile は docker/frontend と docker/backend にある(あちらはソースを volumes でマウントするだけ)。
#
# ビルドコンテキストはリポジトリ直下。frontend と backend の両方を参照するため。
#   docker build -f docker/app/Dockerfile -t app .
#
# ステージを 3 つに分けているのは、最終イメージに JDK・Node・ソースコードを残さないため。

# ---------- ステージ1: フロントエンドを SSG ビルド ----------
FROM node:22-slim AS frontend

#  WORKDIR は「移動する」だけでなく「無ければ作る」命令
WORKDIR /frontend

# 依存の定義だけを先に COPY して npm ci を済ませる。
# ソースを変更しただけのときは、この層がキャッシュに当たって npm ci を飛ばせる。
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

COPY frontend/ ./

# 出力は /frontend/.output/public(index.html・200.html・404.html・_nuxt/ など)
RUN npm run generate


# ---------- ステージ2: SSG 出力を同梱して jar を作る ----------
FROM eclipse-temurin:21-jdk AS backend

WORKDIR /backend

# ステージ1と同じ理由で、依存の定義だけを先に COPY して依存を解決しておく
COPY backend/gradlew ./
COPY backend/gradle ./gradle
COPY backend/settings.gradle backend/build.gradle ./
# --no-daemon: Gradle の常駐 JVM を起動しない。RUN ごとにコンテナが使い捨てられて
#   次の RUN にも実行時にも引き継がれないので、使い回す相手がおらずメモリを食うだけになる。
# --quiet: 進行ログ(タスク名・BUILD SUCCESSFUL など)を減らす。ただし依存ツリー本体は
#   消えない。レポート系タスクは -q でも結果が読めるよう QUIET レベルで出力するため。
RUN sh ./gradlew dependencies --no-daemon --quiet

COPY backend/src ./src

# ここが「Nuxt を SSG ビルドして Spring Boot の static/ に置く」の実体。
# リポジトリ上の static/ は空で、この COPY のときだけ中身が入る。
COPY --from=frontend /frontend/.output/public/ ./src/main/resources/static/

# build ではなく bootJar を使う。build は test タスクに依存しており、
# このコンテナには MySQL が居ないのでテストが接続エラーで落ちてイメージが作れなくなる。
# --no-daemon の理由は上と同じ。
RUN sh ./gradlew bootJar --no-daemon


# ---------- ステージ3: 実行 ----------
FROM eclipse-temurin:21-jre AS runtime

# root で動かさない。コンテナ内で任意コード実行を許した場合の被害範囲を狭める。
# --system はログイン用ではないシステムアカウントとして作る指定。
# --create-home で /app が app 所有で作られるため、次の WORKDIR は作成せず移動するだけになる。
RUN groupadd --system app && useradd --system --gid app --home-dir /app --create-home app

WORKDIR /app

# --from=backend はビルドコンテキストではなくステージ2 のファイルシステムから取る指定。
# --chown をコピー時に付けるのは、後から RUN chown するとレイヤーが 1 つ増えたうえに
# 50 MB 超の jar がイメージ内に二重に残るため(元の層のファイルは消えない)。
COPY --from=backend --chown=app:app /backend/build/libs/*.jar /app/app.jar

USER app

EXPOSE 8080

# Java はコンテナのメモリ上限を認識するが、既定ではその 25% しかヒープに使わない。
# タスクに 1 GB 与えても 256 MB しか使わない状態になるので明示的に上げる。
# ENV なので実行時(ECS のタスク定義など)に上書きできる既定値になる。
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

# sh -c を挟むのは $JAVA_OPTS を展開するため。JSON 配列形式はシェルを通さないので、
# 直接書くと変数が展開されず文字列のまま java に渡ってしまう。
# exec を付けて java を PID 1 にする。付けないと sh が PID 1 になり、
# ECS がタスクを止めるときの SIGTERM が java に届かず、
# グレースフルシャットダウンが効かないまま強制終了を待つことになる。
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
