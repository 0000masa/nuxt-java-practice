# 開発用: 実行環境(JDK)だけを持ち、ソースコードは docker-compose の volumes でマウントする
# 本番用(ECR に push する)Dockerfile は AWS 構築時に別途作成する
FROM eclipse-temurin:21-jdk

WORKDIR /app

EXPOSE 8080

# sh 経由で起動: マウントされた gradlew に実行権限が無くても動くように
CMD ["sh", "./gradlew", "bootRun"]
