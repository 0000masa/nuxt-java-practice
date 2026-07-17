# 開発用: 実行環境(Node)だけを持ち、ソースコードは docker-compose の volumes でマウントする
FROM node:22-slim

WORKDIR /app

EXPOSE 3000

# --host 0.0.0.0: コンテナ外(ホスト)からアクセスできるように全インターフェースで待ち受ける
CMD ["npm", "run", "dev", "--", "--host", "0.0.0.0", "--port", "3000"]
